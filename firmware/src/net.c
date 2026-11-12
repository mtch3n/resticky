#include "net.h"
#include "esp_crt_bundle.h"
#include "buzzer.h"
#include <sys/time.h>
#include "ui.h"
#include "touch.h"
#include "lvgl.h"
#include "ble.h"
#include "cJSON.h"
#include "driver/uart.h"
#include "esp_app_desc.h"
#include "esp_event.h"
#include "esp_attr.h"
#include "esp_heap_caps.h"
#include "esp_http_client.h"
#include "esp_log.h"
#include "esp_mac.h"
#include "esp_netif.h"
#include "clock.h"
#include "sensors.h"
#include "esp_heap_caps.h"
#include "esp_timer.h"
#include "esp_netif_sntp.h"
#include "esp_wifi.h"
#include "freertos/FreeRTOS.h"
#include "freertos/event_groups.h"
#include "freertos/queue.h"
#include "freertos/semphr.h"
#include "freertos/task.h"
#include "mbedtls/base64.h"
#include "mbedtls/constant_time.h"
#include "mbedtls/md.h"
#include "esp_random.h"
#include "mdns.h"
#include "nvs.h"
#include "nvs_flash.h"
#include "ota.h"
#include "spool.h"
#include "wifi.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define CLOCK_VALID_AFTER 1735689600
#define POLL_MS 30000
#define BLE_PAUSE_MS 60000
#define OTA_MIN_BATTERY 20
#define HUB_RECHECK_MS (5 * 60 * 1000)
#define CHECKIN_LEN 768
#define LISTS_MAX (40 * 1024)
#define WIFI_OFF_AFTER_MS 60000
#define WIFI_BACK_AFTER_MS 60000
#define WIFI_NEED_MS (5 * 60 * 1000)
#define TOKEN_HEX 64
#define NONCE_LEN 16


static const char *TAG = "net";
static QueueHandle_t chunks, todo_changes;

typedef struct {
    char id[NET_TODO_ID_LEN];
    bool done;
} todo_change_t;
static SemaphoreHandle_t lock, token_lock;
static char hub_token[TOKEN_HEX + 1];
EXT_RAM_BSS_ATTR static net_state_t state;
static char device_id[13];
static char hub_url[96];
EXT_RAM_BSS_ATTR static char resp[16384];
EXT_RAM_BSS_ATTR static net_state_t scratch;
static uint8_t *screen;
static size_t screen_len;
static bool screen_dirty;
static volatile bool test_now;
static int64_t last_poll, last_find, last_ble_checkin, ble_pause_until;
static char weather_change[64];
#define MAX_MARKS 8
typedef struct {
    uint32_t session, offset_ms;
    int64_t at_ms;
} mark_t;
static mark_t marks[MAX_MARKS];
static int mark_count;
static bool ble_linked;
static struct { int battery_pct, voltage_mv, current_ma; bool recording, usb; } tele;
static ota_info_t latest;
static volatile bool install_now;
static SemaphoreHandle_t fresh_lock, parked;
static volatile bool park_wanted, is_parked;
static TaskHandle_t net_handle;
EXT_RAM_BSS_ATTR static char lists_buf[LISTS_MAX];
static size_t lists_len;
static bool lists_pending, lists_seen;
static bool wifi_off;
static int64_t link_since, link_lost_at;
static volatile int64_t wifi_need_until;

static void *psram_malloc(size_t n) { return heap_caps_malloc(n, MALLOC_CAP_SPIRAM); }

static void console_task(void *arg)
{
    char line[160];
    size_t n = 0;
    for (;;) {
        uint8_t c;
        if (uart_read_bytes(UART_NUM_0, &c, 1, portMAX_DELAY) != 1) continue;
        if (c != '\n' && c != '\r') {
            if (n < sizeof line - 1) line[n++] = c;
            continue;
        }
        line[n] = 0;
        n = 0;
        char *sep = strchr(line, '|');
        if (!strncmp(line, "wifi ", 5) && sep) {
            *sep = 0;
            wifi_add(line + 5, sep + 1);
        } else if (!strcmp(line, "touch")) {
            touch_debug();
        } else if (!strcmp(line, "update")) {
            net_install_update();
        } else if (!strncmp(line, "wifi-forget ", 12)) {
            wifi_forget(line + 12);
        } else if (!strncmp(line, "show ", 5) || !strcmp(line, "shot")) {
            ui_console(line);
        }
    }
}

static int hub_prio(const mdns_result_t *r)
{
    for (size_t i = 0; i < r->txt_count; i++)
        if (!strcmp(r->txt[i].key, "prio") && r->txt[i].value) return atoi(r->txt[i].value);
    return 0;
}

static bool valid_token(const char *t)
{
    size_t n = strlen(t);
    for (size_t i = 0; i < n; i++)
        if (!strchr("0123456789abcdefABCDEF", t[i])) return false;
    return n == TOKEN_HEX;
}

static void unhex(const char *hex, uint8_t *out, size_t n)
{
    for (size_t i = 0; i < n; i++) {
        char b[3] = {hex[2 * i], hex[2 * i + 1], 0};
        out[i] = strtoul(b, NULL, 16);
    }
}

static void bearer(char *out, size_t n)
{
    xSemaphoreTake(token_lock, portMAX_DELAY);
    if (hub_token[0]) snprintf(out, n, "Bearer %s", hub_token);
    else out[0] = 0;
    xSemaphoreGive(token_lock);
}

void net_set_hub_token(const char *hex)
{
    if (!valid_token(hex)) {
        ESP_LOGW(TAG, "hub password ignored: not 64 hex characters");
        return;
    }
    xSemaphoreTake(token_lock, portMAX_DELAY);
    bool changed = strcmp(hub_token, hex) != 0;
    strlcpy(hub_token, hex, sizeof hub_token);
    xSemaphoreGive(token_lock);
    if (!changed) return;
    nvs_handle_t h;
    if (nvs_open("hub", NVS_READWRITE, &h) == ESP_OK) {
        nvs_set_str(h, "token", hex);
        nvs_commit(h);
        nvs_close(h);
    }
    ESP_LOGI(TAG, "hub password stored");
    xSemaphoreTake(lock, portMAX_DELAY);
    state.hub = false;
    state.version++;
    xSemaphoreGive(lock);
}

static void load_token(void)
{
    nvs_handle_t h;
    size_t len = sizeof hub_token;
    if (nvs_open("hub", NVS_READONLY, &h) != ESP_OK) return;
    if (nvs_get_str(h, "token", hub_token, &len) != ESP_OK || !valid_token(hub_token)) hub_token[0] = 0;
    nvs_close(h);
}

static bool hub_proven(const char *base)
{
    char token[TOKEN_HEX + 1];
    xSemaphoreTake(token_lock, portMAX_DELAY);
    strlcpy(token, hub_token, sizeof token);
    xSemaphoreGive(token_lock);
    if (!token[0]) return true;
    uint8_t nonce[NONCE_LEN], key[TOKEN_HEX / 2], want[32], got[32];
    char nonce_hex[2 * NONCE_LEN + 1], url[160], body[256];
    esp_fill_random(nonce, sizeof nonce);
    for (int i = 0; i < NONCE_LEN; i++) snprintf(nonce_hex + 2 * i, 3, "%02x", nonce[i]);
    snprintf(url, sizeof url, "%s/api/hello?nonce=%s", base, nonce_hex);
    esp_http_client_config_t cfg = {.url = url, .timeout_ms = 5000};
    esp_http_client_handle_t c = esp_http_client_init(&cfg);
    int got_len = -1;
    if (esp_http_client_open(c, 0) == ESP_OK && esp_http_client_fetch_headers(c) >= 0 && esp_http_client_get_status_code(c) == 200)
        got_len = esp_http_client_read_response(c, body, sizeof body - 1);
    esp_http_client_cleanup(c);
    body[got_len > 0 ? got_len : 0] = 0;
    cJSON *j = cJSON_Parse(body);
    const char *proof = cJSON_GetStringValue(cJSON_GetObjectItem(j, "proof"));
    bool ok = proof && strlen(proof) == 2 * sizeof got && valid_token(proof);
    if (ok) unhex(proof, got, sizeof got);
    cJSON_Delete(j);
    unhex(token, key, sizeof key);
    ok = ok && mbedtls_md_hmac(mbedtls_md_info_from_type(MBEDTLS_MD_SHA256), key, sizeof key, (const uint8_t *)nonce_hex,
                               strlen(nonce_hex), want) == 0 &&
         mbedtls_ct_memcmp(want, got, sizeof want) == 0;
    if (!ok) ESP_LOGW(TAG, "hub %s ignored: wrong hub password proof", base);
    return ok;
}

static bool find_hub(void)
{
    mdns_result_t *r = NULL;
    if (mdns_query_ptr("_resticky", "_tcp", 3000, 8, &r) != ESP_OK || !r) return false;
    const mdns_result_t *best = NULL;
    const mdns_ip_addr_t *best_addr = NULL;
    for (mdns_result_t *i = r; i; i = i->next) {
        for (mdns_ip_addr_t *a = i->addr; a; a = a->next) {
            if (a->addr.type != ESP_IPADDR_TYPE_V4) continue;
            char base[sizeof hub_url];
            snprintf(base, sizeof base, "http://" IPSTR ":%d", IP2STR(&a->addr.u_addr.ip4), i->port);
            if ((!best || hub_prio(i) > hub_prio(best)) && hub_proven(base)) {
                best = i;
                best_addr = a;
            }
            break;
        }
    }
    if (best) {
        char url[sizeof hub_url];
        snprintf(url, sizeof url, "http://" IPSTR ":%d", IP2STR(&best_addr->addr.u_addr.ip4), best->port);
        if (strcmp(url, hub_url)) ESP_LOGI(TAG, "hub: %s (%s, prio %d)", url, best->hostname ? best->hostname : "hub", hub_prio(best));
        strlcpy(hub_url, url, sizeof hub_url);
        xSemaphoreTake(lock, portMAX_DELAY);
        strlcpy(state.hub_host, best->hostname ? best->hostname : "hub", sizeof state.hub_host);
        state.hub = true;
        state.version++;
        xSemaphoreGive(lock);
    }
    mdns_query_results_free(r);
    return best != NULL;
}

static int http(const char *method, const char *path, const char *ctype, const char *body, size_t len,
                const char **headers, char *resp, size_t resp_cap)
{
    char url[192];
    snprintf(url, sizeof url, "%s%s", hub_url, path);
    esp_http_client_config_t cfg = {.url = url, .timeout_ms = 15000};
    esp_http_client_handle_t c = esp_http_client_init(&cfg);
    esp_http_client_set_method(c, !strcmp(method, "POST") ? HTTP_METHOD_POST : !strcmp(method, "PUT") ? HTTP_METHOD_PUT : HTTP_METHOD_GET);
    if (ctype) esp_http_client_set_header(c, "Content-Type", ctype);
    char auth[TOKEN_HEX + 8];
    bearer(auth, sizeof auth);
    if (auth[0]) esp_http_client_set_header(c, "Authorization", auth);
    for (int i = 0; headers && headers[i]; i += 2) esp_http_client_set_header(c, headers[i], headers[i + 1]);
    int status = -1;
    if (esp_http_client_open(c, len) == ESP_OK) {
        if (len) esp_http_client_write(c, body, len);
        esp_http_client_fetch_headers(c);
        status = esp_http_client_get_status_code(c);
        int got = resp ? esp_http_client_read_response(c, resp, resp_cap - 1) : 0;
        if (resp) resp[got > 0 ? got : 0] = 0;
    }
    esp_http_client_cleanup(c);
    return status;
}

static bool upload(audio_chunk_t *ch)
{
    char session[9], seq[11], start[21], dur[11];
    snprintf(session, sizeof session, "%08lx", ch->session);
    snprintf(seq, sizeof seq, "%lu", ch->seq);
    snprintf(start, sizeof start, "%lld", ch->start_ms);
    snprintf(dur, sizeof dur, "%lu", ch->duration_ms);
    char speech[256], keep[256], prev[11], level[40];
    const char *h[21] = {"X-Device", device_id, "X-Session", session, "X-Seq", seq, "X-Final", ch->final ? "1" : "0",
                         "X-Start-Ms", start, "X-Duration-Ms", dur};
    size_t mask = (ch->duration_ms / 20 + 7) / 8, o;
    if (ch->has_vad && mask <= AUDIO_MASK_BYTES &&
        !mbedtls_base64_encode((unsigned char *)speech, sizeof speech, &o, ch->vad.speech, mask) &&
        !mbedtls_base64_encode((unsigned char *)keep, sizeof keep, &o, ch->vad.keep, mask)) {
        snprintf(prev, sizeof prev, "%lu", ch->vad.keep_prev);
        const char *v[] = {"X-Vad-Speech", speech, "X-Vad-Keep", keep, "X-Vad-Keep-Prev", prev};
        memcpy(h + 12, v, sizeof v);
    }
    if (ch->has_level) {
        snprintf(level, sizeof level, "%d,%d,%d,%d,%u", ch->level.peak_db, ch->level.rms_db, ch->level.noise_db,
                 ch->level.speech_db, ch->level.clipped);
        int n = h[12] ? 18 : 12;
        h[n] = "X-Level";
        h[n + 1] = level;
    }
    int64_t t0 = esp_log_timestamp();
    int st = http("POST", "/api/chunks", "application/x-resticky-opus", (const char *)ch->data, ch->len, h, NULL, 0);
    ESP_LOGI(TAG, "upload session %s seq %s (%u bytes, %s ms, final %d): %d in %lld ms", session, seq, ch->len, dur, ch->final, st,
             esp_log_timestamp() - t0);
    return st == 200;
}

static int sent_marks;
static bool climate_ok;
static int climate_c10, climate_rh;

void net_set_climate(bool ok, int room_c10, int humidity)
{
    climate_ok = ok;
    climate_c10 = room_c10;
    climate_rh = humidity;
}
static int device_sound = SOUND_BEEP;
static int64_t sound_local_at = -60000;
static const char *const SOUNDS[SOUND_COUNT] = {"off", "beep", "buzz"};

static int device_home;
static bool device_night_on;
static int device_night_start = 22 * 60, device_night_end = 7 * 60;
static int64_t night_local_at = -60000;

void net_set_night(bool on, int start, int end)
{
    if (on != device_night_on || start != device_night_start || end != device_night_end) night_local_at = esp_log_timestamp();
    device_night_on = on;
    device_night_start = start;
    device_night_end = end;
}

static void night_text(char *out, size_t n)
{
    if (!device_night_on) strlcpy(out, "off", n);
    else snprintf(out, n, "%02d:%02d-%02d:%02d", device_night_start / 60, device_night_start % 60, device_night_end / 60, device_night_end % 60);
}

static bool parse_night(const char *v, bool *on, int *start, int *end)
{
    if (!v) return false;
    if (!strcmp(v, "off")) {
        *on = false;
        return true;
    }
    int h1, m1, h2, m2;
    if (strlen(v) != 11 || sscanf(v, "%2d:%2d-%2d:%2d", &h1, &m1, &h2, &m2) != 4) return false;
    if (h1 > 23 || h2 > 23 || (m1 != 0 && m1 != 30) || (m2 != 0 && m2 != 30) || h1 * 60 + m1 == h2 * 60 + m2) return false;
    *on = true;
    *start = h1 * 60 + m1;
    *end = h2 * 60 + m2;
    return true;
}
static int64_t home_local_at = -60000;
static const char *const HOMES[HOME_COUNT] = {"apps", "clock", "weather", "recorder", "todo"};

void net_set_home(int home)
{
    if (home != device_home) home_local_at = esp_log_timestamp();
    device_home = home;
}

static int device_buzz_hz = 175;
static int64_t buzz_local_at = -60000;
static volatile int buzz_test_hz;

void net_set_buzz_hz(int hz)
{
    if (hz != device_buzz_hz) buzz_local_at = esp_log_timestamp();
    device_buzz_hz = hz;
}

void net_buzz_test(int hz) { buzz_test_hz = hz; }

int net_take_buzz_test(void)
{
    int hz = buzz_test_hz;
    buzz_test_hz = 0;
    return hz;
}

void net_set_sound(int sound)
{
    if (sound != device_sound) sound_local_at = esp_log_timestamp();
    device_sound = sound;
}

static void marks_delivered(void)
{
    xSemaphoreTake(lock, portMAX_DELAY);
    int n = sent_marks < mark_count ? sent_marks : mark_count;
    memmove(marks, marks + n, (mark_count - n) * sizeof marks[0]);
    mark_count -= n;
    sent_marks = 0;
    xSemaphoreGive(lock);
}

bool net_mark(void)
{
    uint32_t session, offset;
    if (!audio_position(&session, &offset)) return false;
    struct timeval tv;
    gettimeofday(&tv, NULL);
    xSemaphoreTake(lock, portMAX_DELAY);
    if (mark_count == MAX_MARKS) memmove(marks, marks + 1, --mark_count * sizeof marks[0]);
    marks[mark_count++] = (mark_t){session, offset, (int64_t)tv.tv_sec * 1000 + tv.tv_usec / 1000};
    xSemaphoreGive(lock);
    ESP_LOGI(TAG, "mark session %08lx at %lu ms", (unsigned long)session, (unsigned long)offset);
    net_checkin_soon();
    return true;
}

static const char *climate_part(void)
{
    static char out[48];
    if (!climate_ok) return "";
    snprintf(out, sizeof out, ",\"room_c10\":%d,\"humidity\":%d", climate_c10, climate_rh);
    return out;
}

static char boot_text[160];

void net_set_boot(const char *text) { strlcpy(boot_text, text, sizeof boot_text); }

static const char *health_json(void)
{
    static char out[512];
    audio_load_t a;
    audio_get_load(&a);
    TaskHandle_t nimble = xTaskGetHandle("nimble_host"), net = xTaskGetHandle("net"), mainh = xTaskGetHandle("main");
    snprintf(out, sizeof out,
             "{\"uptime_s\":%lld,\"heap_free\":%u,\"heap_min\":%u,\"heap_largest\":%u,\"psram_free\":%u,\"psram_min\":%u,"
             "\"stack_main\":%u,\"stack_net\":%u,\"stack_ble\":%u,\"cpu_filter_pm\":%d,\"cpu_opus_pm\":%d,\"cpu_vad_pm\":%d,"
             "\"opus_max_us\":%d,\"overflows\":%lu,\"audio_keep_pm\":%d,\"early_chunks\":%lu,\"lost_chunks\":%lu,\"temp_c\":%d,\"boot\":\"%s\"%s}",
             esp_timer_get_time() / 1000000, (unsigned)heap_caps_get_free_size(MALLOC_CAP_INTERNAL),
             (unsigned)heap_caps_get_minimum_free_size(MALLOC_CAP_INTERNAL), (unsigned)heap_caps_get_largest_free_block(MALLOC_CAP_INTERNAL),
             (unsigned)heap_caps_get_free_size(MALLOC_CAP_SPIRAM), (unsigned)heap_caps_get_minimum_free_size(MALLOC_CAP_SPIRAM),
             mainh ? (unsigned)uxTaskGetStackHighWaterMark(mainh) : 0, net ? (unsigned)uxTaskGetStackHighWaterMark(net) : 0,
             nimble ? (unsigned)uxTaskGetStackHighWaterMark(nimble) : 0, a.filter_pm, a.opus_pm, a.vad_pm, a.opus_max_us,
             (unsigned long)a.overflows, a.keep_pm, (unsigned long)a.early_chunks, (unsigned long)a.lost_chunks, sensors_chip_celsius(),
             boot_text, climate_part());
    return out;
}

static void checkin_body(char *body, size_t n)
{
    spool_stats_t sp;
    spool_stats(&sp);
    wifi_status_t w;
    wifi_get_status(&w);
    cJSON *saved = cJSON_CreateArray();
    for (int i = 0; i < w.count; i++) cJSON_AddItemToArray(saved, cJSON_CreateString(w.ssid[i]));
    char *list = cJSON_PrintUnformatted(saved);
    cJSON *ssid = cJSON_CreateString(w.up ? w.connected : "");
    char *ssid_json = cJSON_PrintUnformatted(ssid);
    snprintf(body, n,
             "{\"device\":\"%s\",\"firmware\":\"%s\",\"battery_pct\":%d,\"voltage_mv\":%d,\"current_ma\":%d,\"recording\":%s,"
             "\"outbox_chunks\":%lu,\"outbox_bytes\":%lu,\"dropped_chunks\":%lu,\"wifi_saved\":%s,\"wifi_ssid\":%s,\"wifi_rssi\":%d}",
             device_id, esp_app_get_description()->version, tele.battery_pct, tele.voltage_mv, tele.current_ma,
             tele.recording ? "true" : "false", (unsigned long)sp.chunks, (unsigned long)sp.bytes, (unsigned long)sp.dropped,
             list ? list : "[]", ssid_json ? ssid_json : "\"\"", w.up ? w.rssi : 0);
    cJSON_free(list);
    cJSON_free(ssid_json);
    size_t used = strlen(body);
    if (used > 1 && used < n) {
        char night[16];
        night_text(night, sizeof night);
        snprintf(body + used - 1, n - used + 1, ",\"sound\":\"%s\",\"buzz_hz\":%d,\"home\":\"%s\",\"night\":\"%s\",\"health\":%s}", SOUNDS[device_sound], device_buzz_hz, HOMES[device_home], night, health_json());
    }
    xSemaphoreTake(lock, portMAX_DELAY);
    for (int i = 0; i < mark_count; i++) {
        used = strlen(body);
        if (used + 80 >= n) break;
        snprintf(body + used - 1, n - used + 1, "%s{\"session\":\"%08lx\",\"offset_ms\":%lu,\"at\":%lld}%s}", i ? "," : ",\"marks\":[",
                 (unsigned long)marks[i].session, (unsigned long)marks[i].offset_ms, marks[i].at_ms, i == mark_count - 1 ? "]" : "");
    }
    sent_marks = mark_count;
    xSemaphoreGive(lock);
    cJSON_Delete(saved);
    cJSON_Delete(ssid);
}

static const char *const CONDS[WX_COUNT] = {"sun", "cloud", "rain", "storm", "snow", "fog"};
static const char *const FACES[FACE_COUNT] = {"dial", "digits", "words", "note", "flip"};
static const char *const STYLES[WXS_COUNT] = {"sketch", "big", "journal", "week"};

static int pick(const cJSON *j, const char *const *names, int count, int fallback)
{
    if (!cJSON_IsString(j)) return fallback;
    for (int i = 0; i < count; i++)
        if (!strcmp(j->valuestring, names[i])) return i;
    return fallback;
}

static int num(const cJSON *o, const char *key) { return cJSON_GetNumberValue(cJSON_GetObjectItem(o, key)); }

static void parse_weather(const cJSON *j, net_weather_t *w)
{
    memset(w, 0, sizeof *w);
    strlcpy(w->place, cJSON_GetStringValue(cJSON_GetObjectItem(j, "place")) ?: "", sizeof w->place);
    const char *mode = cJSON_GetStringValue(cJSON_GetObjectItem(j, "mode")), *units = cJSON_GetStringValue(cJSON_GetObjectItem(j, "units"));
    w->from_phone = mode && !strcmp(mode, "phone");
    w->fahrenheit = units && !strcmp(units, "f");
    const cJSON *now = cJSON_GetObjectItem(j, "now");
    if (!cJSON_IsObject(now)) return;
    w->valid = true;
    w->temp = num(now, "temp");
    w->feels = num(now, "feels");
    w->hi = num(now, "hi");
    w->lo = num(now, "lo");
    w->rain = num(now, "rain");
    w->cond = pick(cJSON_GetObjectItem(now, "cond"), CONDS, WX_COUNT, WX_CLOUD);
    w->hour = num(j, "hour");
    const cJSON *temps = cJSON_GetObjectItem(j, "temps"), *conds = cJSON_GetObjectItem(j, "conds"), *days = cJSON_GetObjectItem(j, "days");
    for (int i = 0; i < NET_WX_HOURS; i++) w->temps[i] = cJSON_GetNumberValue(cJSON_GetArrayItem(temps, i));
    for (int i = 0; i < NET_WX_MARKS; i++) w->conds[i] = pick(cJSON_GetArrayItem(conds, i), CONDS, WX_COUNT, WX_CLOUD);
    w->days = LV_MIN(cJSON_GetArraySize(days), NET_WX_DAYS);
    for (int i = 0; i < w->days; i++) {
        const cJSON *d = cJSON_GetArrayItem(days, i);
        strlcpy(w->day[i].wd, cJSON_GetStringValue(cJSON_GetObjectItem(d, "wd")) ?: "", sizeof w->day[i].wd);
        w->day[i].cond = pick(cJSON_GetObjectItem(d, "cond"), CONDS, WX_COUNT, WX_CLOUD);
        w->day[i].lo = num(d, "lo");
        w->day[i].hi = num(d, "hi");
    }
}

static void parse_agenda(const cJSON *j, net_agenda_t *a)
{
    memset(a, 0, sizeof *a);
    a->show = cJSON_IsTrue(cJSON_GetObjectItem(j, "show"));
    const cJSON *ev = cJSON_GetObjectItem(j, "events");
    a->count = LV_MIN(cJSON_GetArraySize(ev), NET_AGENDA_MAX);
    for (int i = 0; i < a->count; i++) {
        const cJSON *e = cJSON_GetArrayItem(ev, i);
        a->ev[i].day = num(e, "day");
        strlcpy(a->ev[i].start, cJSON_GetStringValue(cJSON_GetObjectItem(e, "start")) ?: "", sizeof a->ev[i].start);
        strlcpy(a->ev[i].title, cJSON_GetStringValue(cJSON_GetObjectItem(e, "title")) ?: "", sizeof a->ev[i].title);
    }
}

static void apply_display(const cJSON *j)
{
    xSemaphoreTake(lock, portMAX_DELAY);
    uint8_t face = pick(cJSON_GetObjectItem(j, "clock_face"), FACES, FACE_COUNT, state.clock_face);
    uint8_t style = pick(cJSON_GetObjectItem(j, "weather_style"), STYLES, WXS_COUNT, state.weather_style);
    const cJSON *bz = cJSON_GetObjectItem(j, "buzz_hz");
    if (cJSON_IsNumber(bz) && bz->valueint >= BUZZ_HZ_MIN && bz->valueint <= BUZZ_HZ_MAX && esp_log_timestamp() - buzz_local_at > 60000 &&
        bz->valueint != device_buzz_hz) {
        state.buzz_hz = device_buzz_hz = bz->valueint;
        state.buzz_from_hub = true;
        state.version++;
    }
    int sound = pick(cJSON_GetObjectItem(j, "sound"), SOUNDS, SOUND_COUNT, -1);
    if (sound >= 0 && esp_log_timestamp() - sound_local_at > 60000 && sound != device_sound) {
        state.sound = device_sound = sound;
        state.sound_from_hub = true;
        state.version++;
    }
    int home = pick(cJSON_GetObjectItem(j, "home"), HOMES, HOME_COUNT, -1);
    if (home >= 0 && esp_log_timestamp() - home_local_at > 60000 && home != device_home) {
        state.home = device_home = home;
        state.home_from_hub = true;
        state.version++;
    }
    bool n_on = device_night_on;
    int n_start = device_night_start, n_end = device_night_end;
    if (parse_night(cJSON_GetStringValue(cJSON_GetObjectItem(j, "night")), &n_on, &n_start, &n_end) && esp_log_timestamp() - night_local_at > 60000 &&
        (n_on != device_night_on || (n_on && (n_start != device_night_start || n_end != device_night_end)))) {
        state.night_on = device_night_on = n_on;
        state.night_start = device_night_start = n_on ? n_start : device_night_start;
        state.night_end = device_night_end = n_on ? n_end : device_night_end;
        state.night_from_hub = true;
        state.version++;
    }
    const char *hf = cJSON_GetStringValue(cJSON_GetObjectItem(j, "hour_format"));
    bool hour12 = hf ? !strcmp(hf, "12") : state.hour12;
    if (face != state.clock_face || style != state.weather_style || hour12 != state.hour12) {
        state.clock_face = face;
        state.weather_style = style;
        state.hour12 = hour12;
        state.version++;
    }
    xSemaphoreGive(lock);
}

void net_hub_frame(uint8_t type, const char *json, size_t len)
{
    cJSON *j = cJSON_ParseWithLength(json, len);
    if (!j) return;
    if (type == HUB_FRAME_DISPLAY) {
        apply_display(j);
    } else {
        static net_weather_t w;
        static net_agenda_t a;
        if (type == HUB_FRAME_WEATHER) parse_weather(j, &w);
        else parse_agenda(j, &a);
        xSemaphoreTake(lock, portMAX_DELAY);
        if (type == HUB_FRAME_WEATHER) state.weather = w;
        else state.agenda = a;
        state.version++;
        xSemaphoreGive(lock);
    }
    cJSON_Delete(j);
}

void net_weather_set(bool from_phone, bool fahrenheit)
{
    xSemaphoreTake(lock, portMAX_DELAY);
    snprintf(weather_change, sizeof weather_change, "{\"mode\":\"%s\",\"units\":\"%s\"}", from_phone ? "phone" : "city",
             fahrenheit ? "f" : "c");
    state.weather.from_phone = from_phone;
    state.weather.fahrenheit = fahrenheit;
    state.version++;
    xSemaphoreGive(lock);
}

void net_checkin_soon(void)
{
    last_ble_checkin = 0;
    last_poll = 0;
}

static void fetch_hub_json(const char *what, uint8_t type)
{
    char path[64];
    snprintf(path, sizeof path, "/api/%s?device=%s", what, device_id);
    if (http("GET", path, NULL, NULL, 0, NULL, resp, sizeof resp) == 200) net_hub_frame(type, resp, strlen(resp));
}

static void send_weather_change(bool ble)
{
    char body[sizeof weather_change];
    xSemaphoreTake(lock, portMAX_DELAY);
    strlcpy(body, weather_change, sizeof body);
    xSemaphoreGive(lock);
    if (!body[0]) return;
    char path[64];
    snprintf(path, sizeof path, "/api/weather?device=%s", device_id);
    bool sent = ble ? ble_send_weather_set(body) : http("PUT", path, "application/json", body, strlen(body), NULL, resp, sizeof resp) == 200;
    if (!sent) return;
    if (!ble) net_hub_frame(HUB_FRAME_WEATHER, resp, strlen(resp));
    xSemaphoreTake(lock, portMAX_DELAY);
    if (!strcmp(body, weather_change)) weather_change[0] = 0;
    xSemaphoreGive(lock);
}

static bool checkin(void)
{
    char body[CHECKIN_LEN];
    checkin_body(body, sizeof body);
    int st = http("POST", "/api/checkin", "application/json", body, strlen(body), NULL, resp, sizeof resp);
    if (st == 200) {
        marks_delivered();
        cJSON *j = cJSON_Parse(resp);
        cJSON *off = cJSON_GetObjectItem(j, "utc_offset_min");
        if (cJSON_IsNumber(off)) clock_set_offset(off->valueint);
        if (j) apply_display(j);
        const char *want = cJSON_GetStringValue(cJSON_GetObjectItem(j, "install"));
        if (want && !strcmp(want, latest.version)) install_now = true;
        cJSON_Delete(j);
    }
    if (st != 200) {
        ESP_LOGW(TAG, "checkin failed: %d", st);
        xSemaphoreTake(lock, portMAX_DELAY);
        state.hub = false;
        xSemaphoreGive(lock);
        return false;
    }
    return true;
}

static void apply_transcripts(const cJSON *arr)
{
    if (!cJSON_IsArray(arr)) return;
    net_state_t *next = &scratch;
    memset(next, 0, sizeof *next);
    cJSON *it;
    cJSON_ArrayForEach(it, arr)
    {
        if (next->count >= NET_MAX_ITEMS) break;
        net_item_t *d = &next->items[next->count++];
        cJSON *v;
        if ((v = cJSON_GetObjectItem(it, "title")) && cJSON_IsString(v)) strlcpy(d->title, v->valuestring, sizeof d->title);
        if ((v = cJSON_GetObjectItem(it, "when")) && cJSON_IsString(v)) strlcpy(d->when, v->valuestring, sizeof d->when);
        if ((v = cJSON_GetObjectItem(it, "summary")) && cJSON_IsString(v)) strlcpy(d->summary, v->valuestring, sizeof d->summary);
        if ((v = cJSON_GetObjectItem(it, "text")) && cJSON_IsString(v)) strlcpy(d->text, v->valuestring, sizeof d->text);
        if ((v = cJSON_GetObjectItem(it, "todos")) && cJSON_IsArray(v)) d->todos = cJSON_GetArraySize(v);
        d->marks = cJSON_GetNumberValue(cJSON_GetObjectItem(it, "marks"));
        d->ready = cJSON_IsTrue(cJSON_GetObjectItem(it, "ready"));
    }
    xSemaphoreTake(lock, portMAX_DELAY);
    bool changed = next->count != state.count || memcmp(next->items, state.items, sizeof next->items);
    if (changed) {
        state.count = next->count;
        memcpy(state.items, next->items, sizeof next->items);
        state.version++;
    }
    xSemaphoreGive(lock);
}

static void fetch_transcripts(void)
{
    char path[64];
    snprintf(path, sizeof path, "/api/transcripts?device=%s", device_id);
    if (http("GET", path, NULL, NULL, 0, NULL, resp, sizeof resp) != 200) return;
    cJSON *arr = cJSON_Parse(resp);
    apply_transcripts(arr);
    cJSON_Delete(arr);
}

static void add_todos(net_state_t *next, const cJSON *list, bool done)
{
    cJSON *it;
    cJSON_ArrayForEach(it, list)
    {
        if (next->todo_count >= NET_MAX_TODOS) return;
        net_todo_t *t = &next->todos[next->todo_count++];
        cJSON *v;
        if ((v = cJSON_GetObjectItem(it, "id")) && cJSON_IsString(v)) strlcpy(t->id, v->valuestring, sizeof t->id);
        if ((v = cJSON_GetObjectItem(it, "text")) && cJSON_IsString(v)) strlcpy(t->text, v->valuestring, sizeof t->text);
        if ((v = cJSON_GetObjectItem(it, "from")) && cJSON_IsString(v)) strlcpy(t->from, v->valuestring, sizeof t->from);
        t->done = done;
    }
}

static void apply_todos(const cJSON *root)
{
    const cJSON *open = cJSON_GetObjectItem(root, "open"), *done = cJSON_GetObjectItem(root, "done");
    if (!cJSON_IsArray(open) || !cJSON_IsArray(done)) return;
    net_state_t *next = &scratch;
    memset(next, 0, sizeof *next);
    next->todos_loaded = true;
    next->open_todos = cJSON_GetArraySize(open);
    add_todos(next, open, false);
    add_todos(next, done, true);
    xSemaphoreTake(lock, portMAX_DELAY);
    if (!state.todos_loaded || next->open_todos != state.open_todos || next->todo_count != state.todo_count ||
        memcmp(next->todos, state.todos, sizeof next->todos)) {
        state.todos_loaded = true;
        state.open_todos = next->open_todos;
        state.todo_count = next->todo_count;
        memcpy(state.todos, next->todos, sizeof next->todos);
        state.version++;
    }
    xSemaphoreGive(lock);
}

static void fetch_todos(void)
{
    char path[64];
    snprintf(path, sizeof path, "/api/todos?device=%s", device_id);
    if (http("GET", path, NULL, NULL, 0, NULL, resp, sizeof resp) != 200) return;
    cJSON *root = cJSON_Parse(resp);
    apply_todos(root);
    cJSON_Delete(root);
}

void net_lists_frame(const char *json, size_t len)
{
    if (len > LISTS_MAX) {
        ESP_LOGW(TAG, "lists frame of %u bytes ignored", (unsigned)len);
        return;
    }
    xSemaphoreTake(lock, portMAX_DELAY);
    memcpy(lists_buf, json, len);
    lists_len = len;
    lists_pending = true;
    xSemaphoreGive(lock);
}

static void apply_lists(void)
{
    xSemaphoreTake(lock, portMAX_DELAY);
    bool had = lists_pending;
    cJSON *j = had ? cJSON_ParseWithLength(lists_buf, lists_len) : NULL;
    lists_pending = false;
    xSemaphoreGive(lock);
    if (!had) return;
    if (!j) {
        ESP_LOGW(TAG, "lists frame is not JSON");
        return;
    }
    apply_transcripts(cJSON_GetObjectItem(j, "transcripts"));
    apply_todos(cJSON_GetObjectItem(j, "todos"));
    cJSON_Delete(j);
    lists_seen = true;
    ESP_LOGI(TAG, "lists from the phone: %d transcripts, %d to-dos", state.count, state.todo_count);
}

static bool clock_set(void)
{
    return time(NULL) > CLOCK_VALID_AFTER;
}

static void send_todo_change(const todo_change_t *c)
{
    char session[NET_TODO_ID_LEN], path[96], body[80], at[24];
    strlcpy(session, c->id, sizeof session);
    char *dash = strchr(session, '-');
    if (!dash) return;
    *dash = 0;
    time_t now = time(NULL);
    struct tm utc;
    gmtime_r(&now, &utc);
    strftime(at, sizeof at, "%Y-%m-%dT%H:%M:%SZ", &utc);
    snprintf(path, sizeof path, "/api/todos/%s/%s/%s", device_id, session, dash + 1);
    snprintf(body, sizeof body, "{\"done\":%s,\"at\":\"%s\"}", c->done ? "true" : "false", at);
    int st = http("PUT", path, "application/json", body, strlen(body), NULL, NULL, 0);
    ESP_LOGI(TAG, "set to-do %s done=%d: %d", c->id, c->done, st);
}

static bool send_todo_ble(const todo_change_t *c)
{
    cJSON *j = cJSON_CreateObject();
    cJSON_AddStringToObject(j, "id", c->id);
    cJSON_AddBoolToObject(j, "done", c->done);
    char *body = cJSON_PrintUnformatted(j);
    bool ok = body && ble_send_json(BLE_TYPE_TODO_SET, body);
    ESP_LOGI(TAG, "set to-do %s done=%d over BLE: %s", c->id, c->done, ok ? "sent" : "failed");
    cJSON_free(body);
    cJSON_Delete(j);
    return ok;
}

static void send_todos_ble(void)
{
    todo_change_t change;
    while (xQueueReceive(todo_changes, &change, 0) == pdTRUE) {
        if (send_todo_ble(&change)) continue;
        xQueueSendToFront(todo_changes, &change, 0);
        return;
    }
}

static void send_screen(void)
{
    xSemaphoreTake(lock, portMAX_DELAY);
    bool dirty = screen_dirty;
    screen_dirty = false;
    xSemaphoreGive(lock);
    if (!dirty) return;
    const char *h[] = {"X-Device", device_id, NULL};
    int st = http("POST", "/api/screen", "application/octet-stream", (const char *)screen, screen_len, h, NULL, 0);
    if (st != 200) ESP_LOGW(TAG, "screen upload: %d", st);
}

static void spool_fresh(void)
{
    xSemaphoreTake(fresh_lock, portMAX_DELAY);
    audio_chunk_t *c;
    while (xQueueReceive(chunks, &c, 0) == pdTRUE) {
        if (spool_put(c)) ota_note_audio();
        free(c->data);
        free(c);
    }
    xSemaphoreGive(fresh_lock);
}

static void park_if_wanted(void)
{
    if (!park_wanted) return;
    spool_fresh();
    ESP_LOGI(TAG, "parked, outbox on flash");
    is_parked = true;
    xSemaphoreGive(parked);
    while (park_wanted) ulTaskNotifyTake(pdTRUE, portMAX_DELAY);
    is_parked = false;
    ESP_LOGI(TAG, "unparked");
}

static void restart_now(void)
{
    ESP_LOGI(TAG, "restart after the update");
    audio_stop(3000);
    spool_fresh();
    esp_restart();
}

static void mark_changed(void)
{
    xSemaphoreTake(lock, portMAX_DELAY);
    state.version++;
    xSemaphoreGive(lock);
}

static bool send_ble(void)
{
    int64_t now = esp_log_timestamp();
    if (!ble_linked || now - last_ble_checkin > POLL_MS) {
        char body[CHECKIN_LEN];
        checkin_body(body, sizeof body);
        ble_linked = ble_send_json(BLE_TYPE_CHECKIN, body);
        if (ble_linked) marks_delivered();
        if (ble_linked) ota_note_hub();
        last_ble_checkin = now;
    }
    send_weather_change(true);
    send_todos_ble();
    uint32_t id;
    audio_chunk_t *next = spool_oldest(&id);
    if (!next) return false;
    bool acked = ble_send_chunk(next);
    if (acked) spool_remove(id);
    free(next->data);
    free(next);
    if (!acked) ble_pause_until = esp_log_timestamp() + BLE_PAUSE_MS;
    return true;
}

static bool send_wifi(void)
{
    uint32_t id;
    audio_chunk_t *next = spool_oldest(&id);
    if (!next) return false;
    bool sent = upload(next);
    if (sent) spool_remove(id);
    if (sent && next->final) last_poll = 0;
    free(next->data);
    free(next);
    if (!sent) {
        xSemaphoreTake(lock, portMAX_DELAY);
        state.hub = false;
        xSemaphoreGive(lock);
        vTaskDelay(pdMS_TO_TICKS(5000));
    }
    return true;
}

static bool have_hub(bool ble, bool testing)
{
    if (state.hub) return true;
    int64_t now = esp_log_timestamp();
    if (ble && !testing && now - last_find < POLL_MS) return false;
    last_find = now;
    if (find_hub()) return true;
    ESP_LOGW(TAG, "no hub answered the mDNS query");
    if (testing) mark_changed();
    if (!ble) vTaskDelay(pdMS_TO_TICKS(5000));
    return false;
}

static void check_firmware(void)
{
    if (http("GET", "/api/firmware", NULL, NULL, 0, NULL, resp, sizeof resp) != 200) return;
    cJSON *j = cJSON_Parse(resp);
    cJSON *v = cJSON_GetObjectItem(j, "version"), *size = cJSON_GetObjectItem(j, "size");
    ota_info_t info = {0};
    if (cJSON_IsString(v) && cJSON_IsNumber(size) && ota_is_newer(v->valuestring)) {
        strlcpy(info.version, v->valuestring, sizeof info.version);
        info.size = size->valuedouble;
    }
    cJSON_Delete(j);
    xSemaphoreTake(lock, portMAX_DELAY);
    if ((info.version[0] || !latest.url[0]) && strcmp(latest.version, info.version)) {
        latest = info;
        strlcpy(state.update_version, info.version, sizeof state.update_version);
        if (info.version[0]) state.update_check = UPDATE_IDLE;
        state.version++;
    }
    xSemaphoreGive(lock);
}

#define GITHUB_LATEST "https://api.github.com/repos/mtch3n/resticky/releases/latest"
static volatile bool github_wanted, hub_check_wanted;

void net_check_updates(void)
{
    net_need_wifi();
    github_wanted = hub_check_wanted = true;
    last_poll = 0;
    xSemaphoreTake(lock, portMAX_DELAY);
    latest = (ota_info_t){0};
    state.update_version[0] = 0;
    state.update_check = UPDATE_CHECKING;
    state.version++;
    xSemaphoreGive(lock);
}

static void set_check(update_check_t check)
{
    xSemaphoreTake(lock, portMAX_DELAY);
    if (!state.update_version[0]) {
        state.update_check = check;
        state.version++;
    }
    xSemaphoreGive(lock);
}

static void check_github(void)
{
    if (!github_wanted) return;
    github_wanted = false;
    esp_http_client_config_t cfg = {.url = GITHUB_LATEST, .timeout_ms = 15000, .buffer_size = 2048,
                                    .crt_bundle_attach = esp_crt_bundle_attach, .user_agent = "resticky-sticky"};
    esp_http_client_handle_t c = esp_http_client_init(&cfg);
    esp_http_client_set_header(c, "Accept", "application/vnd.github+json");
    int len = -1;
    if (esp_http_client_open(c, 0) == ESP_OK && esp_http_client_fetch_headers(c) >= 0 && esp_http_client_get_status_code(c) == 200) {
        len = 0;
        for (int n; len < (int)sizeof resp - 1 && (n = esp_http_client_read(c, resp + len, sizeof resp - 1 - len)) > 0;) len += n;
    }
    esp_http_client_cleanup(c);
    if (len <= 0) {
        ESP_LOGW(TAG, "github release check failed");
        set_check(UPDATE_FAILED);
        return;
    }
    resp[len] = 0;
    cJSON *j = cJSON_Parse(resp);
    const char *tag = cJSON_GetStringValue(cJSON_GetObjectItem(j, "tag_name"));
    ota_info_t info = {0};
    const cJSON *a;
    cJSON_ArrayForEach(a, cJSON_GetObjectItem(j, "assets"))
    {
        const char *name = cJSON_GetStringValue(cJSON_GetObjectItem(a, "name")), *url = cJSON_GetStringValue(cJSON_GetObjectItem(a, "browser_download_url"));
        if (name && url && !strncmp(name, "resticky-firmware-", 18) && strstr(name, ".bin")) {
            strlcpy(info.url, url, sizeof info.url);
            info.size = cJSON_GetNumberValue(cJSON_GetObjectItem(a, "size"));
        }
    }
    if (tag && info.url[0] && ota_is_newer(tag[0] == 'v' ? tag + 1 : tag)) {
        strlcpy(info.version, tag[0] == 'v' ? tag + 1 : tag, sizeof info.version);
        ESP_LOGI(TAG, "github release %s available (%u bytes)", info.version, (unsigned)info.size);
        xSemaphoreTake(lock, portMAX_DELAY);
        latest = info;
        strlcpy(state.update_version, info.version, sizeof state.update_version);
        state.update_check = UPDATE_IDLE;
        state.version++;
        xSemaphoreGive(lock);
    }
    else set_check(tag ? UPDATE_NONE : UPDATE_FAILED);
    cJSON_Delete(j);
}

static volatile int64_t restart_at;
static volatile bool cancel_wanted;

#define OTA_BLOCK 4096
#define OTA_SLOTS 6
#define OTA_IDLE_MS 20000

typedef struct {
    uint8_t type;
    uint32_t offset;
    uint16_t len;
    uint8_t data[OTA_BLOCK];
} ota_item_t;

static QueueHandle_t ota_items;
EXT_RAM_BSS_ATTR static uint8_t ota_item_store[OTA_SLOTS * sizeof(ota_item_t)];
EXT_RAM_BSS_ATTR static ota_item_t ota_in, ota_work;
static StaticQueue_t ota_item_queue;

static void set_phase(update_phase_t phase, int percent, update_check_t check)
{
    xSemaphoreTake(lock, portMAX_DELAY);
    state.update_phase = phase;
    state.update_progress = percent;
    state.update_check = check;
    state.version++;
    xSemaphoreGive(lock);
}

static bool updating(void) { return state.update_phase != UPDATE_OFF; }

static bool power_ok(void) { return tele.usb || tele.battery_pct >= OTA_MIN_BATTERY; }

void net_cancel_update(void) { cancel_wanted = true; }

void net_ota_frame(uint8_t type, const uint8_t *p, size_t n)
{
    ota_in.type = type;
    ota_in.offset = 0;
    ota_in.len = 0;
    if (type == BLE_TYPE_OTA_DATA) {
        if (n < 4 || n - 4 > OTA_BLOCK) {
            ESP_LOGW(TAG, "ota data frame of %u bytes ignored", (unsigned)n);
            return;
        }
        memcpy(&ota_in.offset, p, 4);
        p += 4;
        n -= 4;
    }
    else if (n > OTA_BLOCK) return;
    memcpy(ota_in.data, p, n);
    ota_in.len = n;
    if (xQueueSend(ota_items, &ota_in, 0) != pdTRUE) ESP_LOGW(TAG, "ota queue full, frame %u dropped", type);
}

static void ota_reply(const char *st)
{
    char j[96];
    snprintf(j, sizeof j, "{\"state\":\"%s\",\"percent\":%d,\"offset\":%u}", st, ota_stream_percent(), (unsigned)ota_stream_done());
    ble_send_json(BLE_TYPE_OTA_STATUS, j);
}

static void ota_begin_phone(const ota_item_t *it)
{
    cJSON *j = cJSON_ParseWithLength((const char *)it->data, it->len);
    const char *v = cJSON_GetStringValue(cJSON_GetObjectItem(j, "version"));
    double size = cJSON_GetNumberValue(cJSON_GetObjectItem(j, "size"));
    bool valid = v && strlen(v) < OTA_VERSION_LEN && size >= 1 && size <= 16 * 1024 * 1024 && size == (double)(size_t)size;
    if (updating() && state.update_phase != UPDATE_RECEIVE) {
        ota_reply("busy");
    }
    else if (!power_ok()) {
        ota_reply("battery");
        set_phase(UPDATE_OFF, 0, UPDATE_LOW_BATTERY);
    }
    else if (!valid || !ota_stream_begin(v, (size_t)size)) {
        ota_reply("error");
        set_phase(UPDATE_OFF, 0, UPDATE_INSTALL_FAILED);
    }
    else {
        xSemaphoreTake(lock, portMAX_DELAY);
        strlcpy(state.update_version, v, sizeof state.update_version);
        xSemaphoreGive(lock);
        cancel_wanted = false;
        set_phase(UPDATE_RECEIVE, 0, UPDATE_IDLE);
        ota_reply("ready");
    }
    cJSON_Delete(j);
}

static void receive_from_phone(void)
{
    int64_t last = esp_log_timestamp();
    int shown = -1;
    while (state.update_phase == UPDATE_RECEIVE) {
        ble_tick(true);

        if (cancel_wanted || !ble_ready() || esp_log_timestamp() - last > OTA_IDLE_MS) {
            bool cancelled = cancel_wanted;
            ESP_LOGW(TAG, "phone update stopped: %s", cancelled ? "cancelled" : ble_ready() ? "no data" : "link lost");
            ota_stream_abort();
            if (ble_ready()) ota_reply(cancelled ? "cancelled" : "error");
            set_phase(UPDATE_OFF, 0, cancelled ? UPDATE_IDLE : UPDATE_INSTALL_FAILED);
            break;
        }
        if (xQueueReceive(ota_items, &ota_work, pdMS_TO_TICKS(500)) != pdTRUE) continue;
        last = esp_log_timestamp();
        if (ota_work.type == BLE_TYPE_OTA_CANCEL) cancel_wanted = true;
        else if (ota_work.type == BLE_TYPE_OTA_BEGIN) ota_begin_phone(&ota_work);
        else if (ota_work.type == BLE_TYPE_OTA_DATA) {
            if (!ota_stream_write(ota_work.offset, ota_work.data, ota_work.len)) {
                ota_reply("error");
                set_phase(UPDATE_OFF, 0, UPDATE_INSTALL_FAILED);
                break;
            }
            ota_reply("writing");
            int pct = ota_stream_percent();
            if (pct / 2 != shown) {
                shown = pct / 2;
                set_phase(UPDATE_RECEIVE, pct, UPDATE_IDLE);
            }
        }
        else if (ota_work.type == BLE_TYPE_OTA_END) {
            set_phase(UPDATE_INSTALL, 100, UPDATE_IDLE);
            ota_reply("installing");
            bool ok = ota_stream_end();
            ota_reply(ok ? "ok" : "error");
            set_phase(ok ? UPDATE_DONE : UPDATE_OFF, 100, ok ? UPDATE_IDLE : UPDATE_INSTALL_FAILED);
            if (ok) restart_at = esp_log_timestamp() + 4000;
        }
    }
}

static void phone_frames(void)
{
    while (xQueueReceive(ota_items, &ota_work, 0) == pdTRUE) {
        if (ota_work.type == BLE_TYPE_OTA_BEGIN) ota_begin_phone(&ota_work);
        else if (ota_work.type == BLE_TYPE_OTA_CANCEL) ota_reply("cancelled");
        if (state.update_phase == UPDATE_RECEIVE) receive_from_phone();
    }
}

static void download_progress(int percent)
{
    if (percent >= 100) set_phase(UPDATE_INSTALL, 100, UPDATE_IDLE);
    else if (percent % 2 == 0) set_phase(UPDATE_DOWNLOAD, percent, UPDATE_IDLE);
}

static void install_update(void)
{
    install_now = false;
    xSemaphoreTake(lock, portMAX_DELAY);
    ota_info_t info = latest;
    xSemaphoreGive(lock);
    if (!info.version[0] || updating()) return;
    if (!power_ok()) {
        ESP_LOGW(TAG, "update not started: battery %d%%, usb %d", tele.battery_pct, tele.usb);
        set_phase(UPDATE_OFF, 0, UPDATE_LOW_BATTERY);
        return;
    }
    cancel_wanted = false;
    set_phase(UPDATE_DOWNLOAD, 0, UPDATE_IDLE);
    vTaskDelay(pdMS_TO_TICKS(1500));
    char auth[TOKEN_HEX + 8];
    bearer(auth, sizeof auth);
    if (ota_install(hub_url, auth, &info, download_progress, &cancel_wanted)) {
        set_phase(UPDATE_DONE, 100, UPDATE_IDLE);
        restart_at = esp_log_timestamp() + 4000;
        return;
    }
    ESP_LOGW(TAG, "update %s", cancel_wanted ? "cancelled" : "failed");
    set_phase(UPDATE_OFF, 0, cancel_wanted ? UPDATE_IDLE : UPDATE_INSTALL_FAILED);
}

static void sync_wifi(bool testing)
{
    todo_change_t change;
    bool changed = false;
    while (!ble_linked && clock_set() && xQueueReceive(todo_changes, &change, 0) == pdTRUE) {
        send_todo_change(&change);
        changed = true;
    }
    if (changed && !lists_seen) fetch_todos();
    send_screen();
    int64_t now = esp_log_timestamp();
    if (now - last_find > HUB_RECHECK_MS) {
        last_find = now;
        find_hub();
    }
    if (now - last_poll > POLL_MS) {
        last_poll = now;
        bool ok = ble_linked || checkin();
        if (ok && !ble_linked) ota_note_hub();
        if (!ok || testing) mark_changed();
        if (ok && hub_check_wanted) {
            hub_check_wanted = false;
            check_firmware();
        }
        if (!lists_seen) {
            fetch_transcripts();
            fetch_todos();
        }
        if (!ble_linked) {
            fetch_hub_json("weather", HUB_FRAME_WEATHER);
            fetch_hub_json("agenda", HUB_FRAME_AGENDA);
        }
    }
    send_weather_change(false);
}

static void set_phone(bool phone)
{
    xSemaphoreTake(lock, portMAX_DELAY);
    if (state.phone != phone) {
        state.phone = phone;
        state.version++;
    }
    xSemaphoreGive(lock);
}

static void wifi_policy(void)
{
    int64_t now = esp_log_timestamp();
    bool linked = ble_ready();
    if (linked && !link_since) link_since = now;
    if (!linked) {
        link_since = 0;
        lists_seen = false;
        if (!link_lost_at) link_lost_at = now;
    } else {
        link_lost_at = 0;
    }
    set_phone(linked && lists_seen);
    bool needed = now < wifi_need_until;
    if (!wifi_off && !needed && linked && lists_seen && now - link_since >= WIFI_OFF_AFTER_MS) {
        ESP_LOGI(TAG, "phone linked for %lld s: Wi-Fi off", (now - link_since) / 1000);
        wifi_off = true;
        wifi_stop();
        xSemaphoreTake(lock, portMAX_DELAY);
        state.hub = false;
        state.version++;
        xSemaphoreGive(lock);
    } else if (wifi_off && (needed || (!linked && now - link_lost_at > WIFI_BACK_AFTER_MS))) {
        ESP_LOGI(TAG, "Wi-Fi on: %s", needed ? "needed" : "phone link lost");
        wifi_off = false;
        last_find = 0;
        wifi_resume();
        mark_changed();
    }
}

void net_need_wifi(void)
{
    wifi_need_until = esp_log_timestamp() + WIFI_NEED_MS;
}

static void net_task(void *arg)
{
    for (;;) {
        park_if_wanted();
        if (restart_at && esp_log_timestamp() > restart_at) restart_now();
        if (ble_ready()) phone_frames();
        ble_tick(updating());
        if (updating()) {
            spool_fresh();
            vTaskDelay(pdMS_TO_TICKS(200));
            continue;
        }
        ota_tick();
        spool_fresh();
        apply_lists();
        wifi_policy();
        bool ble = ble_ready() && esp_log_timestamp() >= ble_pause_until;
        if (!ble) ble_linked = false;
        bool busy = ble && send_ble();
        if (wifi_wait_up(ble ? 0 : pdMS_TO_TICKS(1000))) {
            check_github();
            bool testing = test_now;
            test_now = false;
            if (testing) last_poll = 0;
            if (install_now && latest.url[0]) install_update();
            if (have_hub(ble, testing)) {
                if (install_now) install_update();
                if (!ble) busy = send_wifi();
                sync_wifi(testing);
            }
        }
        audio_chunk_t *fresh;
        if (!busy) xQueuePeek(chunks, &fresh, pdMS_TO_TICKS(1000));
    }
}

static void on_time_sync(struct timeval *tv) { clock_synced(); }

void net_init(void)
{
    cJSON_InitHooks(&(cJSON_Hooks){.malloc_fn = psram_malloc, .free_fn = free});
    lock = xSemaphoreCreateMutex();
    token_lock = xSemaphoreCreateMutex();
    load_token();
    fresh_lock = xSemaphoreCreateMutex();
    parked = xSemaphoreCreateBinary();
    chunks = xQueueCreate(8, sizeof(audio_chunk_t *));
    ota_items = xQueueCreateStatic(OTA_SLOTS, sizeof(ota_item_t), ota_item_store, &ota_item_queue);
    spool_init();
    todo_changes = xQueueCreate(8, sizeof(todo_change_t));
    uint8_t mac[6];
    esp_read_mac(mac, ESP_MAC_WIFI_STA);
    snprintf(device_id, sizeof device_id, "%02x%02x%02x%02x%02x%02x", mac[0], mac[1], mac[2], mac[3], mac[4], mac[5]);

    esp_netif_init();
    esp_event_loop_create_default();
    esp_netif_create_default_wifi_sta();
    wifi_init();
    esp_sntp_config_t sntp = ESP_NETIF_SNTP_DEFAULT_CONFIG("pool.ntp.org");
    sntp.sync_cb = on_time_sync;
    esp_netif_sntp_init(&sntp);
    mdns_init();
    mdns_hostname_set("resticky");

    uart_driver_install(UART_NUM_0, 512, 0, 0, NULL, 0);
    if (xTaskCreate(console_task, "console", 4096, NULL, 3, NULL) != pdPASS) ESP_LOGE(TAG, "console task not created");
    if (xTaskCreatePinnedToCore(net_task, "net", 12 * 1024, NULL, 5, &net_handle, 0) != pdPASS) ESP_LOGE(TAG, "net task not created");
    wifi_start();
}

void net_enqueue(audio_chunk_t *chunk)
{
    if (xQueueSend(chunks, &chunk, 0) != pdTRUE) {
        audio_count_lost();
        ESP_LOGW(TAG, "upload queue full, chunk dropped");
        free(chunk->data);
        free(chunk);
    }
}

void net_set_telemetry(int battery_pct, int voltage_mv, int current_ma, bool recording, bool usb)
{
    tele.usb = usb;
    tele.battery_pct = battery_pct;
    tele.voltage_mv = voltage_mv;
    tele.current_ma = current_ma;
    tele.recording = recording;
}

void net_get_state(net_state_t *out)
{
    xSemaphoreTake(lock, portMAX_DELAY);
    *out = state;
    state.buzz_from_hub = state.sound_from_hub = state.home_from_hub = state.night_from_hub = false;
    xSemaphoreGive(lock);
    wifi_get_status(&out->wifi);
}

void net_set_todo(const char *id, bool done)
{
    todo_change_t c = {.done = done};
    strlcpy(c.id, id, sizeof c.id);
    xSemaphoreTake(lock, portMAX_DELAY);
    for (int i = 0; i < state.todo_count; i++) {
        if (strcmp(state.todos[i].id, id) || state.todos[i].done == done) continue;
        state.todos[i].done = done;
        state.open_todos += done ? -1 : 1;
        state.version++;
    }
    xSemaphoreGive(lock);
    xQueueSend(todo_changes, &c, 0);
}

void net_post_screen(const uint8_t *panel_fb, size_t len)
{
    xSemaphoreTake(lock, portMAX_DELAY);
    if (!screen) screen = heap_caps_malloc(len, MALLOC_CAP_SPIRAM);
    if (screen) {
        memcpy(screen, panel_fb, len);
        screen_len = len;
        screen_dirty = true;
    }
    xSemaphoreGive(lock);
}

void net_test_hub(void)
{
    net_need_wifi();
    test_now = true;

}

void net_forget_wifi(const char *ssid)
{
    wifi_forget(ssid);
    xSemaphoreTake(lock, portMAX_DELAY);
    state.version++;
    xSemaphoreGive(lock);
}

void net_install_update(void)
{
    net_need_wifi();
    install_now = true;
}

bool net_park(uint32_t timeout_ms)
{
    xSemaphoreTake(parked, 0);
    park_wanted = true;
    bool ok = is_parked || xSemaphoreTake(parked, pdMS_TO_TICKS(timeout_ms)) == pdTRUE;
    spool_fresh();
    ESP_LOGI(TAG, "park: %s", ok ? "net task stopped" : "net task busy, outbox written by the caller");
    return ok;
}

bool net_sleep(uint32_t timeout_ms)
{
    bool ok = net_park(timeout_ms);
    if (!wifi_off) wifi_stop();
    return ok;
}

void net_wake(void)
{
    if (!wifi_off) wifi_resume();
    park_wanted = false;
    if (net_handle) xTaskNotifyGive(net_handle);
}
