#include "spool.h"
#include "esp_heap_caps.h"
#include "esp_littlefs.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include <dirent.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#define BASE "/spool"
#define MAGIC 0x52535031
#define RESERVE_BYTES (256 * 1024)

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
static uint32_t head, tail;
static spool_stats_t stats;

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

bool spool_init(void)
{
    lock = xSemaphoreCreateMutex();
    esp_vfs_littlefs_conf_t conf = {.base_path = BASE, .partition_label = "spool", .format_if_mount_failed = true};
    if (esp_vfs_littlefs_register(&conf) != ESP_OK) {
        ESP_LOGE(TAG, "mount failed");
        return false;
    }
    DIR *d = opendir(BASE);
    bool any = false;
    for (struct dirent *e; d && (e = readdir(d));) {
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
    ESP_LOGI(TAG, "%lu chunks waiting (%lu bytes), %u of %u KB used", (unsigned long)stats.chunks,
             (unsigned long)stats.bytes, (unsigned)(used / 1024), (unsigned)(total / 1024));
    return true;
}

bool spool_put(const audio_chunk_t *c)
{
    header_t h = {MAGIC, c->session, c->seq, c->final, c->start_ms, c->duration_ms, c->len};
    xSemaphoreTake(lock, portMAX_DELAY);
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
        ESP_LOGE(TAG, "write failed for session %08lx seq %lu", (unsigned long)c->session, (unsigned long)c->seq);
    }
    xSemaphoreGive(lock);
    return ok;
}

audio_chunk_t *spool_oldest(uint32_t *id)
{
    xSemaphoreTake(lock, portMAX_DELAY);
    audio_chunk_t *c = NULL;
    while (head < tail && !c) {
        char path[32];
        path_for(head, path, sizeof path);
        FILE *f = fopen(path, "rb");
        header_t h;
        if (f && fread(&h, sizeof h, 1, f) == 1 && h.magic == MAGIC) {
            c = heap_caps_calloc(1, sizeof *c, MALLOC_CAP_SPIRAM);
            if (c) c->data = heap_caps_malloc(h.len ? h.len : 1, MALLOC_CAP_SPIRAM);
            if (c && c->data && fread(c->data, 1, h.len, f) == h.len) {
                *c = (audio_chunk_t){.session = h.session, .seq = h.seq, .final = h.final, .start_ms = h.start_ms,
                                     .duration_ms = h.duration_ms, .len = h.len, .data = c->data};
                c->has_vad = fread(&c->vad, sizeof c->vad, 1, f) == 1;
                c->has_level = c->has_vad && fread(&c->level, sizeof c->level, 1, f) == 1;
                *id = head;
            } else if (c) {
                free(c->data);
                free(c);
                c = NULL;
            }
        }
        if (f) fclose(f);
        if (!c) {
            ESP_LOGW(TAG, "skipping unreadable chunk %lu", (unsigned long)head);
            unlink(path);
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
