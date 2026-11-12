#include "spool.h"
#include "esp_heap_caps.h"
#include "esp_littlefs.h"
#include "esp_log.h"
#include "esp_partition.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "freertos/task.h"
#include <dirent.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#define BASE "/spool"
#define MAGIC 0x52535031
#define RESERVE_BYTES (256 * 1024)
#define MOUNT_TRIES 3
#define SUPERBLOCK_BYTES (2 * 4096)

typedef struct {
    uint32_t magic;
    uint32_t session;
    uint32_t seq;
    uint32_t final;
    int64_t start_ms;
    uint32_t duration_ms;
    uint32_t len;
} header_t;

static const char *TAG = "spool";
static SemaphoreHandle_t lock;
static uint32_t head, tail, bad_files;
static spool_stats_t stats = {.state = "not mounted"};

static void path_for(uint32_t id, char *out, size_t n)
{
    snprintf(out, n, BASE "/%010lu.chk", (unsigned long)id);
}

static bool full(void)
{
    size_t total = 0, used = 0;
    esp_littlefs_info("spool", &total, &used);
    return total - used < RESERVE_BYTES;
}

static void drop_oldest(void)
{
    char path[32];
    struct stat st;
    path_for(head, path, sizeof path);
    if (stat(path, &st) == 0) {
        stats.bytes -= st.st_size;
        stats.chunks--;
        stats.dropped++;
        unlink(path);
        ESP_LOGW(TAG, "full, dropped chunk %lu", (unsigned long)head);
    }
    head++;
}

static bool is_bad(const char *name)
{
    size_t n = strlen(name);
    return n > 4 && !strcmp(name + n - 4, ".bad");
}

static void purge_bad(void)
{
    DIR *d = opendir(BASE);
    for (struct dirent *e; d && (e = readdir(d));) {
        if (!is_bad(e->d_name)) continue;
        char path[300];
        snprintf(path, sizeof path, BASE "/%s", e->d_name);
        if (unlink(path) == 0) bad_files--;
        ESP_LOGW(TAG, "full, deleted quarantined %s", e->d_name);
    }
    if (d) closedir(d);
}

static void quarantine(uint32_t id, const char *path, off_t size)
{
    char bad[32];
    snprintf(bad, sizeof bad, BASE "/%010lu.bad", (unsigned long)id);
    stats.corrupt++;
    stats.chunks--;
    stats.bytes -= size;
    if (rename(path, bad) == 0) {
        bad_files++;
        ESP_LOGE(TAG, "chunk %lu is corrupt, moved to %s", (unsigned long)id, bad);
    } else {
        ESP_LOGE(TAG, "chunk %lu is corrupt and cannot be moved aside, skipped until restart", (unsigned long)id);
    }
}

static bool partition_blank(void)
{
    const esp_partition_t *p = esp_partition_find_first(ESP_PARTITION_TYPE_DATA, ESP_PARTITION_SUBTYPE_ANY, "spool");
    if (!p) return false;
    uint32_t buf[64];
    for (size_t off = 0; off < SUPERBLOCK_BYTES; off += sizeof buf) {
        if (esp_partition_read(p, off, buf, sizeof buf) != ESP_OK) return false;
        for (size_t i = 0; i < sizeof buf / sizeof buf[0]; i++)
            if (buf[i] != UINT32_MAX) return false;
    }
    return true;
}

static const char *mount(void)
{
    esp_vfs_littlefs_conf_t conf = {.base_path = BASE, .partition_label = "spool"};
    for (int i = 0; i < MOUNT_TRIES; i++) {
        if (esp_vfs_littlefs_register(&conf) == ESP_OK) return "ok";
        ESP_LOGE(TAG, "mount failed (try %d of %d)", i + 1, MOUNT_TRIES);
        vTaskDelay(pdMS_TO_TICKS(200));
    }
    if (!partition_blank()) {
        ESP_LOGE(TAG, "partition holds data, not formatted; outbox off");
        return "mount failed";
    }
    ESP_LOGW(TAG, "blank partition, formatting");
    if (esp_littlefs_format("spool") != ESP_OK || esp_vfs_littlefs_register(&conf) != ESP_OK) return "format failed";
    return "formatted";
}

bool spool_init(void)
{
    lock = xSemaphoreCreateMutex();
    stats.state = mount();
    if (strcmp(stats.state, "ok") && strcmp(stats.state, "formatted")) return false;
    DIR *d = opendir(BASE);
    bool any = false;
    for (struct dirent *e; d && (e = readdir(d));) {
        if (is_bad(e->d_name)) {
            bad_files++;
            stats.corrupt++;
            continue;
        }
        char *end;
        uint32_t id = strtoul(e->d_name, &end, 10);
        if (strcmp(end, ".chk")) {
            char path[300];
            snprintf(path, sizeof path, BASE "/%s", e->d_name);
            unlink(path);
            continue;
        }
        char path[32];
        struct stat st;
        path_for(id, path, sizeof path);
        if (stat(path, &st) == 0) {
            stats.chunks++;
            stats.bytes += st.st_size;
        }
        if (!any || id < head) head = id;
        if (!any || id >= tail) tail = id + 1;
        any = true;
    }
    if (d) closedir(d);
    size_t total = 0, used = 0;
    esp_littlefs_info("spool", &total, &used);
    ESP_LOGI(TAG, "%lu chunks waiting (%lu bytes), %lu corrupt kept, %u of %u KB used", (unsigned long)stats.chunks,
             (unsigned long)stats.bytes, (unsigned long)bad_files, (unsigned)(used / 1024), (unsigned)(total / 1024));
    return true;
}

bool spool_put(const audio_chunk_t *c)
{
    header_t h = {MAGIC, c->session, c->seq, c->final, c->start_ms, c->duration_ms, c->len};
    xSemaphoreTake(lock, portMAX_DELAY);
    if (bad_files && full()) purge_bad();
    while (full() && head < tail) drop_oldest();
    char path[32], tmp[36];
    path_for(tail, path, sizeof path);
    snprintf(tmp, sizeof tmp, "%s.tmp", path);
    FILE *f = fopen(tmp, "wb");
    bool ok = f && fwrite(&h, sizeof h, 1, f) == 1 && fwrite(c->data, 1, c->len, f) == c->len &&
              (!c->has_vad || fwrite(&c->vad, sizeof c->vad, 1, f) == 1) &&
              (!c->has_vad || !c->has_level || fwrite(&c->level, sizeof c->level, 1, f) == 1);
    if (f) ok = fclose(f) == 0 && ok;
    ok = ok && rename(tmp, path) == 0;
    if (ok) {
        tail++;
        stats.chunks++;
        stats.bytes += sizeof h + c->len + (c->has_vad ? sizeof c->vad + (c->has_level ? sizeof c->level : 0) : 0);
    } else {
        unlink(tmp);
        stats.write_errors++;
        ESP_LOGE(TAG, "write failed for session %08lx seq %lu", (unsigned long)c->session, (unsigned long)c->seq);
    }
    xSemaphoreGive(lock);
    return ok;
}

typedef enum { READ_OK, READ_GONE, READ_LATER, READ_CORRUPT } read_result_t;

static bool header_fits(const header_t *h, off_t size)
{
    return h->magic == MAGIC && size >= (off_t)sizeof *h && h->len <= (size_t)size - sizeof *h;
}

static read_result_t read_chunk(const char *path, audio_chunk_t **out, off_t *size)
{
    struct stat st;
    if (stat(path, &st) != 0) return errno == ENOENT ? READ_GONE : READ_LATER;
    *size = st.st_size;
    FILE *f = fopen(path, "rb");
    if (!f) return READ_LATER;
    header_t h;
    read_result_t r = READ_CORRUPT;
    audio_chunk_t *c = NULL;
    if (fread(&h, sizeof h, 1, f) != 1 || !header_fits(&h, st.st_size)) goto done;
    c = heap_caps_calloc(1, sizeof *c, MALLOC_CAP_SPIRAM);
    if (c) c->data = heap_caps_malloc(h.len ? h.len : 1, MALLOC_CAP_SPIRAM);
    if (!c || !c->data) {
        r = READ_LATER;
        goto done;
    }
    if (fread(c->data, 1, h.len, f) != h.len) goto done;
    *c = (audio_chunk_t){.session = h.session, .seq = h.seq, .final = h.final, .start_ms = h.start_ms,
                         .duration_ms = h.duration_ms, .len = h.len, .data = c->data};
    size_t extra = st.st_size - sizeof h - h.len;
    c->has_vad = extra >= sizeof c->vad;
    c->has_level = extra >= sizeof c->vad + sizeof c->level;
    if ((c->has_vad && fread(&c->vad, sizeof c->vad, 1, f) != 1) || (c->has_level && fread(&c->level, sizeof c->level, 1, f) != 1)) goto done;
    *out = c;
    c = NULL;
    r = READ_OK;
done:
    if (c) free(c->data);
    free(c);
    fclose(f);
    return r;
}

audio_chunk_t *spool_oldest(uint32_t *id)
{
    xSemaphoreTake(lock, portMAX_DELAY);
    audio_chunk_t *c = NULL;
    while (head < tail && !c) {
        char path[32];
        path_for(head, path, sizeof path);
        off_t size = 0;
        read_result_t r = read_chunk(path, &c, &size);
        if (r == READ_LATER) {
            ESP_LOGW(TAG, "chunk %lu not readable now (no memory or file error), kept", (unsigned long)head);
            break;
        }
        if (r == READ_OK) *id = head;
        else {
            if (r == READ_CORRUPT) quarantine(head, path, size);
            head++;
        }
    }
    xSemaphoreGive(lock);
    return c;
}

void spool_remove(uint32_t id)
{
    char path[32];
    struct stat st;
    xSemaphoreTake(lock, portMAX_DELAY);
    path_for(id, path, sizeof path);
    if (stat(path, &st) == 0) {
        stats.chunks--;
        stats.bytes -= st.st_size;
        unlink(path);
    }
    if (id == head) head++;
    xSemaphoreGive(lock);
}

void spool_stats(spool_stats_t *out)
{
    xSemaphoreTake(lock, portMAX_DELAY);
    *out = stats;
    xSemaphoreGive(lock);
}
