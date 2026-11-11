#include "net.h"
#include "esp_crt_bundle.h"
#include "buzzer.h"
#include <sys/time.h>
#include "ui.h"
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


static const char *TAG = "net";
static QueueHandle_t chunks, todo_changes;

typedef struct {
    char id[NET_TODO_ID_LEN];
    bool done;
} todo_change_t;
static SemaphoreHandle_t lock;
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

static bool find_hub(void)
{
    mdns_result_t *r = NULL;
    if (mdns_query_ptr("_resticky", "_tcp", 3000, 8, &r) != ESP_OK || !r) return false;
    const mdns_result_t *best = NULL;
    const mdns_ip_addr_t *best_addr = NULL;
    for (mdns_result_t *i = r; i; i = i->next) {
        for (mdns_ip_addr_t *a = i->addr; a; a = a->next) {
            if (a->addr.type != ESP_IPADDR_TYPE_V4) continue;
            if (!best || hub_prio(i) > hub_prio(best)) {
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
static bool device_beep = true;
static int64_t beep_local_at = -60000;

void net_set_beep(bool on)
{
    if (on != device_beep) beep_local_at = esp_log_timestamp();
    device_beep = on;
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

static const char *health_json(void)
{
    static char out[320];
    audio_load_t a;
    audio_get_load(&a);
    TaskHandle_t nimble = xTaskGetHandle("nimble_host"), net = xTaskGetHandle("net"), mainh = xTaskGetHandle("main");
    snprintf(out, sizeof out,
             "{\"uptime_s\":%lld,\"heap_free\":%u,\"heap_min\":%u,\"heap_largest\":%u,\"psram_free\":%u,\"psram_min\":%u,"
             "\"stack_main\":%u,\"stack_net\":%u,\"stack_ble\":%u,\"cpu_filter_pm\":%d,\"cpu_opus_pm\":%d,\"cpu_vad_pm\":%d,"
             "\"opus_max_us\":%d,\"overflows\":%lu,\"temp_c\":%d%s}",
             esp_timer_get_time() / 1000000, (unsigned)heap_caps_get_free_size(MALLOC_CAP_INTERNAL),
             (unsigned)heap_caps_get_minimum_free_size(MALLOC_CAP_INTERNAL), (unsigned)heap_caps_get_largest_free_block(MALLOC_CAP_INTERNAL),
             (unsigned)heap_caps_get_free_size(MALLOC_CAP_SPIRAM), (unsigned)heap_caps_get_minimum_free_size(MALLOC_CAP_SPIRAM),
             mainh ? (unsigned)uxTaskGetStackHighWaterMark(mainh) : 0, net ? (unsigned)uxTaskGetStackHighWaterMark(net) : 0,
             nimble ? (unsigned)uxTaskGetStackHighWaterMark(nimble) : 0, a.filter_pm, a.opus_pm, a.vad_pm, a.opus_max_us,
             (unsigned long)a.overflows, sensors_chip_celsius(), climate_part());
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
    if (used > 1 && used < n) snprintf(body + used - 1, n - used + 1, ",\"beep\":%s,\"health\":%s}", device_beep ? "true" : "false", health_json());
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
    const cJSON *bp = cJSON_GetObjectItem(j, "beep");
    if (cJSON_IsBool(bp) && esp_log_timestamp() - beep_local_at > 60000 && cJSON_IsTrue(bp) != state.beep) {
        state.beep = cJSON_IsTrue(bp);
        state.beep_from_hub = true;
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

static void fetch_transcripts(void)
{
    char path[64];
    snprintf(path, sizeof path, "/api/transcripts?device=%s", device_id);
    if (http("GET", path, NULL, NULL, 0, NULL, resp, sizeof resp) != 200) return;
    cJSON *arr = cJSON_Parse(resp);
    if (!cJSON_IsArray(arr)) {
        cJSON_Delete(arr);
        return;
    }
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
    cJSON_Delete(arr);
    xSemaphoreTake(lock, portMAX_DELAY);
    bool changed = next->count != state.count || memcmp(next->items, state.items, sizeof next->items);
    if (changed) {
        state.count = next->count;
        memcpy(state.items, next->items, sizeof next->items);
        state.version++;
    }
    xSemaphoreGive(lock);
}

static void add_todos(net_state_t *next, cJSON *list, bool done)
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

static void fetch_todos(void)
{
    char path[64];
    snprintf(path, sizeof path, "/api/todos?device=%s", device_id);
    if (http("GET", path, NULL, NULL, 0, NULL, resp, sizeof resp) != 200) return;
    cJSON *root = cJSON_Parse(resp);
    cJSON *open = cJSON_GetObjectItem(root, "open"), *done = cJSON_GetObjectItem(root, "done");
    if (!cJSON_IsArray(open) || !cJSON_IsArray(done)) {
        cJSON_Delete(root);
        return;
    }
    net_state_t *next = &scratch;
    memset(next, 0, sizeof *next);
    next->todos_loaded = true;
    next->open_todos = cJSON_GetArraySize(open);
    add_todos(next, open, false);
    add_todos(next, done, true);
    cJSON_Delete(root);
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
    audio_chunk_t *c;
    while (xQueueReceive(chunks, &c, 0) == pdTRUE) {
        if (spool_put(c)) ota_note_audio();
        free(c->data);
        free(c);
    }
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
        state.version++;
    }
    xSemaphoreGive(lock);
}

#define GITHUB_LATEST "https://api.github.com/repos/mtch3n/resticky/releases/latest"
#define GITHUB_EVERY_MS (6 * 3600 * 1000)

static void check_github(void)
{
    static int64_t last_check = -GITHUB_EVERY_MS;
    int64_t now = esp_log_timestamp();
    if (now - last_check < GITHUB_EVERY_MS || latest.version[0]) return;
    last_check = now;
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
        state.version++;
        xSemaphoreGive(lock);
    }
    cJSON_Delete(j);
}

static void show_progress(int percent)
{
    xSemaphoreTake(lock, portMAX_DELAY);
    state.update_progress = percent;
    state.version++;
    xSemaphoreGive(lock);
}

static void install_update(void)
{
    install_now = false;
    if (!latest.version[0] || (tele.battery_pct < OTA_MIN_BATTERY && !tele.usb)) {
        ESP_LOGW(TAG, "update not started: battery %d%%, usb %d", tele.battery_pct, tele.usb);
        return;
    }
    xSemaphoreTake(lock, portMAX_DELAY);
    state.updating = true;
    state.version++;
    xSemaphoreGive(lock);
    if (ota_install(hub_url, &latest, show_progress)) esp_restart();
    xSemaphoreTake(lock, portMAX_DELAY);
    state.updating = false;
    state.version++;
    xSemaphoreGive(lock);
}

static void sync_wifi(bool testing)
{
    todo_change_t change;
    bool changed = false;
    while (clock_set() && xQueueReceive(todo_changes, &change, 0) == pdTRUE) {
        send_todo_change(&change);
        changed = true;
    }
    if (changed) fetch_todos();
    send_screen();
    int64_t now = esp_log_timestamp();
    if (now - last_find > HUB_RECHECK_MS) {
        last_find = now;
        find_hub();
    }
    if (now - last_poll > POLL_MS) {
        last_poll = now;
        bool ok = checkin();
        if (ok) ota_note_hub();
        if (!ok || testing) mark_changed();
        if (ok) check_firmware();
        fetch_transcripts();
        fetch_todos();
        fetch_hub_json("weather", HUB_FRAME_WEATHER);
        fetch_hub_json("agenda", HUB_FRAME_AGENDA);
    }
    send_weather_change(false);
}

static void net_task(void *arg)
{
    for (;;) {
        ota_tick();
        spool_fresh();
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
    lock = xSemaphoreCreateMutex();
    chunks = xQueueCreate(8, sizeof(audio_chunk_t *));
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
    if (xTaskCreatePinnedToCore(net_task, "net", 12 * 1024, NULL, 5, NULL, 0) != pdPASS) ESP_LOGE(TAG, "net task not created");
    wifi_start();
}

void net_enqueue(audio_chunk_t *chunk)
{
    if (xQueueSend(chunks, &chunk, 0) != pdTRUE) {
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
    install_now = true;
}
