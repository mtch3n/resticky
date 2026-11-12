#include "esp_attr.h"
#include "ble.h"
#include "cJSON.h"
#include "clock.h"
#include "net.h"
#include "ota.h"
#include "ui.h"
#include "wifi.h"
#include "esp_heap_caps.h"
#include "esp_log.h"
#include "esp_mac.h"
#include "esp_random.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "freertos/task.h"
#include "host/ble_hs.h"
#include "host/util/util.h"
#include "nimble/nimble_port.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"
#include <string.h>

#define MAX_ATTR_LEN 512u
#define TYPE_CHUNK 1
#define TYPE_ACK 2
#define TYPE_WIFI_ADD 10
#define TYPE_WIFI_FORGET 11
#define TYPE_TIME 13
#define TYPE_INSTALL 16
#define TYPE_PHOTO 17
#define TYPE_LISTS 23
#define CHUNK_HEADER 43
#define ACK_LEN 8
#define ACK_TIMEOUT_MS 15000
#define RX_MAX (5 + 48000)
#define RX_IN 520
#define ADV_ITVL_MIN 480
#define ADV_ITVL_MAX 800
#define MIN_INTERNAL_FREE (64 * 1024)
#define SLOW_ITVL_MIN 120
#define SLOW_ITVL_MAX 160
#define SLOW_LATENCY 4
#define FAST_ITVL_MIN 12
#define FAST_ITVL_MAX 24
#define SUPERVISION_TIMEOUT 600
#define FAST_HOLD_MS 5000
#define SETTLE_MS 5000
#define REASK_MS 30000

void ble_store_config_init(void);

static const char *TAG = "ble";
static const ble_uuid128_t SVC = BLE_UUID128_INIT(0x32, 0x52, 0x10, 0xeb, 0x82, 0x4a, 0x0b, 0x92, 0x44, 0x4f, 0x72, 0xe5, 0x60, 0x1e, 0x39, 0x5a);
static const ble_uuid128_t RX = BLE_UUID128_INIT(0x32, 0x52, 0x10, 0xeb, 0x82, 0x4a, 0x0b, 0x92, 0x44, 0x4f, 0x72, 0xe5, 0x61, 0x1e, 0x39, 0x5a);
static const ble_uuid128_t TX = BLE_UUID128_INIT(0x32, 0x52, 0x10, 0xeb, 0x82, 0x4a, 0x0b, 0x92, 0x44, 0x4f, 0x72, 0xe5, 0x62, 0x1e, 0x39, 0x5a);
static uint16_t tx_handle;
static volatile uint16_t conn = BLE_HS_CONN_HANDLE_NONE;
static volatile bool secure, subscribed;
static volatile int passkey = -1;
static uint8_t own_addr_type;
static uint8_t mac[6];
static QueueHandle_t acks;
EXT_RAM_BSS_ATTR static uint8_t rx_frame[RX_MAX], rx_in[RX_IN];
static size_t rx_len;
static volatile uint32_t fast_until;
static bool started;
static volatile uint32_t fast_ms, fast_since, tx_bytes;
static volatile bool at_fast;

static void note_itvl(uint16_t itvl)
{
    uint32_t now = esp_log_timestamp();
    if (at_fast) fast_ms += now - fast_since;
    fast_since = now;
    at_fast = itvl && itvl <= FAST_ITVL_MAX;
}

static void note_conn(uint16_t handle)
{
    struct ble_gap_conn_desc d;
    note_itvl(ble_gap_conn_find(handle, &d) == 0 ? d.conn_itvl : 0);
}

static bool fast_type(uint8_t type)
{
    return type == TYPE_PHOTO || type == TYPE_LISTS || (type >= BLE_TYPE_OTA_BEGIN && type <= BLE_TYPE_OTA_CANCEL);
}

static void on_frame(uint8_t type, const uint8_t *p, size_t n)
{
    if (fast_type(type)) fast_until = esp_log_timestamp() + FAST_HOLD_MS;
    if (type == TYPE_ACK && n == ACK_LEN) {
        uint32_t ack[2];
        memcpy(ack, p, sizeof ack);
        if (xQueueSend(acks, ack, 0) != pdTRUE) ESP_LOGW(TAG, "ack queue full");
        return;
    }
    if (type == BLE_TYPE_BUZZ_TEST) {
        cJSON *j = cJSON_ParseWithLength((const char *)p, n);
        const cJSON *hz = cJSON_GetObjectItem(j, "hz");
        if (cJSON_IsNumber(hz) && hz->valueint >= BUZZ_HZ_MIN && hz->valueint <= BUZZ_HZ_MAX) net_buzz_test(hz->valueint);
        cJSON_Delete(j);
        return;
    }
    if (type == BLE_TYPE_HUB_TOKEN) {
        cJSON *j = cJSON_ParseWithLength((const char *)p, n);
        const char *token = cJSON_GetStringValue(cJSON_GetObjectItem(j, "token"));
        if (token) net_set_hub_token(token);
        cJSON_Delete(j);
        return;
    }
    if (type == HUB_FRAME_WEATHER || type == HUB_FRAME_AGENDA || type == HUB_FRAME_DISPLAY) {
        net_hub_frame(type, (const char *)p, n);
        return;
    }
    if (type == TYPE_WIFI_ADD || type == TYPE_WIFI_FORGET) {
        cJSON *j = cJSON_ParseWithLength((const char *)p, n);
        const char *ssid = cJSON_GetStringValue(cJSON_GetObjectItem(j, "ssid")), *psk = cJSON_GetStringValue(cJSON_GetObjectItem(j, "password"));
        if (ssid && type == TYPE_WIFI_ADD) wifi_add(ssid, psk ? psk : "");
        if (ssid && type == TYPE_WIFI_FORGET) wifi_forget(ssid);
        cJSON_Delete(j);
        net_checkin_soon();
        return;
    }
    if (type >= BLE_TYPE_OTA_BEGIN && type <= BLE_TYPE_OTA_CANCEL && type != BLE_TYPE_OTA_STATUS) {
        net_ota_frame(type, p, n);
        return;
    }
    if (type == TYPE_LISTS) {
        net_lists_frame((const char *)p, n);
        return;
    }
    if (type == TYPE_PHOTO) {
        ui_show_photo(p, n);
        return;
    }
    if (type == TYPE_INSTALL) {
        net_install_update();
        return;
    }
    if (type == TYPE_TIME) {
        cJSON *j = cJSON_ParseWithLength((const char *)p, n);
        cJSON *off = cJSON_GetObjectItem(j, "utc_offset_min");
        if (cJSON_IsNumber(off)) clock_set_offset(off->valueint);
        cJSON_Delete(j);
        return;
    }
    ESP_LOGW(TAG, "ignored frame type %u (%u bytes)", type, (unsigned)n);
}

static void rx_feed(const uint8_t *p, size_t n)
{
    for (size_t i = 0; i < n; i++) {
        rx_frame[rx_len++] = p[i];
        if (rx_len < 4) continue;
        uint32_t len;
        memcpy(&len, rx_frame, 4);
        if (len == 0 || len > RX_MAX - 4) {
            ESP_LOGW(TAG, "bad frame length %lu", (unsigned long)len);
            rx_len = 0;
        } else if (rx_len == 4 + len) {
            on_frame(rx_frame[4], rx_frame + 5, len - 1);
            rx_len = 0;
        }
    }
    if (rx_len >= 5 && fast_type(rx_frame[4])) fast_until = esp_log_timestamp() + FAST_HOLD_MS;
}

static int access_cb(uint16_t conn_handle, uint16_t attr_handle, struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    if (ctxt->op != BLE_GATT_ACCESS_OP_WRITE_CHR) return BLE_ATT_ERR_UNLIKELY;
    uint16_t n;
    if (ble_hs_mbuf_to_flat(ctxt->om, rx_in, sizeof rx_in, &n)) return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
    rx_feed(rx_in, n);
    return 0;
}

static const struct ble_gatt_svc_def services[] = {
    {
        .type = BLE_GATT_SVC_TYPE_PRIMARY,
        .uuid = &SVC.u,
        .characteristics = (struct ble_gatt_chr_def[]){
            {
                .uuid = &RX.u,
                .access_cb = access_cb,
                .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_WRITE_NO_RSP | BLE_GATT_CHR_F_WRITE_ENC | BLE_GATT_CHR_F_WRITE_AUTHEN,
            },
            {
                .uuid = &TX.u,
                .access_cb = access_cb,
                .val_handle = &tx_handle,
                .flags = BLE_GATT_CHR_F_NOTIFY | BLE_GATT_CHR_F_NOTIFY_INDICATE_ENC | BLE_GATT_CHR_F_NOTIFY_INDICATE_AUTHEN,
            },
            {0},
        },
    },
    {0},
};

static void update_security(void)
{
    struct ble_gap_conn_desc d;
    secure = conn != BLE_HS_CONN_HANDLE_NONE && ble_gap_conn_find(conn, &d) == 0 && d.sec_state.encrypted &&
             d.sec_state.authenticated && d.sec_state.bonded;
}

static int gap_event(struct ble_gap_event *e, void *arg);

static void advertise(void)
{
    struct ble_hs_adv_fields f = {
        .flags = BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP,
        .uuids128 = &SVC,
        .num_uuids128 = 1,
        .uuids128_is_complete = 1,
    };
    const char *name = ble_svc_gap_device_name();
    struct ble_hs_adv_fields r = {.name = (const uint8_t *)name, .name_len = strlen(name), .name_is_complete = 1};
    struct ble_gap_adv_params p = {
        .conn_mode = BLE_GAP_CONN_MODE_UND,
        .disc_mode = BLE_GAP_DISC_MODE_GEN,
        .itvl_min = ADV_ITVL_MIN,
        .itvl_max = ADV_ITVL_MAX,
    };
    int rc = ble_gap_adv_set_fields(&f);
    if (!rc) rc = ble_gap_adv_rsp_set_fields(&r);
    if (!rc) rc = ble_gap_adv_start(own_addr_type, NULL, BLE_HS_FOREVER, &p, gap_event, NULL);
    if (rc) ESP_LOGE(TAG, "advertising not started: %d", rc);
}

static int gap_event(struct ble_gap_event *e, void *arg)
{
    switch (e->type) {
    case BLE_GAP_EVENT_CONNECT:
        ESP_LOGI(TAG, "connect: status %d", e->connect.status);
        if (e->connect.status) {
            advertise();
            return 0;
        }
        conn = e->connect.conn_handle;
        note_conn(conn);
        rx_len = 0;
        update_security();
        return 0;
    case BLE_GAP_EVENT_DISCONNECT:
        ESP_LOGI(TAG, "disconnect: reason 0x%x", e->disconnect.reason);
        conn = BLE_HS_CONN_HANDLE_NONE;
        note_itvl(0);
        secure = subscribed = false;
        passkey = -1;
        advertise();
        return 0;
    case BLE_GAP_EVENT_ENC_CHANGE:
        passkey = -1;
        update_security();
        ESP_LOGI(TAG, "encryption change: status %d, bonded and authenticated %d", e->enc_change.status, secure);
        return 0;
    case BLE_GAP_EVENT_PASSKEY_ACTION:
        if (e->passkey.params.action != BLE_SM_IOACT_DISP) {
            ESP_LOGW(TAG, "passkey action %u not supported", e->passkey.params.action);
            return 0;
        }
        struct ble_sm_io io = {.action = BLE_SM_IOACT_DISP, .passkey = esp_random() % 1000000};
        passkey = io.passkey;
        ESP_LOGI(TAG, "pairing: passkey %06lu", (unsigned long)io.passkey);
        ble_sm_inject_io(e->passkey.conn_handle, &io);
        return 0;
    case BLE_GAP_EVENT_REPEAT_PAIRING: {
        struct ble_gap_conn_desc d;
        if (ble_gap_conn_find(e->repeat_pairing.conn_handle, &d) == 0) ble_store_util_delete_peer(&d.peer_id_addr);
        return BLE_GAP_REPEAT_PAIRING_RETRY;
    }
    case BLE_GAP_EVENT_SUBSCRIBE:
        if (e->subscribe.attr_handle == tx_handle) subscribed = e->subscribe.cur_notify;
        ESP_LOGI(TAG, "subscribe: handle %u notify %d", e->subscribe.attr_handle, e->subscribe.cur_notify);
        return 0;
    case BLE_GAP_EVENT_MTU:
        ESP_LOGI(TAG, "mtu %u", e->mtu.value);
        return 0;
    case BLE_GAP_EVENT_PHY_UPDATE_COMPLETE:
        ESP_LOGI(TAG, "phy: tx %u rx %u", e->phy_updated.tx_phy, e->phy_updated.rx_phy);
        return 0;
    case BLE_GAP_EVENT_CONN_UPDATE: {
        struct ble_gap_conn_desc d;
        if (ble_gap_conn_find(e->conn_update.conn_handle, &d) == 0) {
            note_itvl(d.conn_itvl);
            ESP_LOGI(TAG, "connection interval %u x 1.25 ms, latency %u", d.conn_itvl, d.conn_latency);
        }
        return 0;
    }
    }
    return 0;
}

static void on_sync(void)
{
    ble_hs_util_ensure_addr(0);
    ble_hs_id_infer_auto(0, &own_addr_type);
    advertise();
}

static void on_reset(int reason)
{
    ESP_LOGW(TAG, "host reset: %d", reason);
}

static void host_task(void *arg)
{
    nimble_port_run();
    vTaskDelete(NULL);
}

static bool notify(const uint8_t *p, size_t n)
{
    while (ble_ready()) {
        struct os_mbuf *om = ble_hs_mbuf_from_flat(p, n);
        int rc = om ? ble_gatts_notify_custom(conn, tx_handle, om) : BLE_HS_ENOMEM;
        if (rc == 0) {
            tx_bytes += n;
            return true;
        }
        if (rc != BLE_HS_ENOMEM) {
            ESP_LOGW(TAG, "notify failed: %d", rc);
            return false;
        }
        vTaskDelay(pdMS_TO_TICKS(2));
    }
    return false;
}

static bool send_frame(uint8_t type, const uint8_t *head, size_t head_len, const uint8_t *body, size_t body_len)
{
    size_t n = 5 + head_len + body_len;
    uint8_t *f = heap_caps_malloc(n, MALLOC_CAP_SPIRAM);
    if (!f) {
        ESP_LOGE(TAG, "no memory for a %u byte frame", (unsigned)n);
        return false;
    }
    uint32_t len = n - 4;
    memcpy(f, &len, 4);
    f[4] = type;
    memcpy(f + 5, head, head_len);
    if (body_len) memcpy(f + 5 + head_len, body, body_len);
    bool ok = true;
    for (size_t off = 0; ok && off < n;) {
        uint16_t mtu = ble_att_mtu(conn);
        if (mtu < BLE_ATT_MTU_DFLT) {
            ok = false;
            break;
        }
        size_t room = mtu - 3u < MAX_ATTR_LEN ? mtu - 3u : MAX_ATTR_LEN;
        size_t piece = n - off < room ? n - off : room;
        ok = notify(f + off, piece);
        off += piece;
    }
    free(f);
    return ok;
}

static bool start(void)
{
    size_t internal = heap_caps_get_free_size(MALLOC_CAP_INTERNAL);
    if (internal < MIN_INTERNAL_FREE) {
        ESP_LOGE(TAG, "not started: internal heap free %u KB", (unsigned)(internal / 1024));
        return false;
    }
    esp_err_t err = nimble_port_init();
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "nimble init failed: %s", esp_err_to_name(err));
        return false;
    }
    started = true;
    ble_hs_cfg.sync_cb = on_sync;
    ble_hs_cfg.reset_cb = on_reset;
    ble_hs_cfg.store_status_cb = ble_store_util_status_rr;
    ble_hs_cfg.sm_io_cap = BLE_SM_IO_CAP_DISP_ONLY;
    ble_hs_cfg.sm_bonding = 1;
    ble_hs_cfg.sm_mitm = 1;
    ble_hs_cfg.sm_sc = 1;
    ble_hs_cfg.sm_sc_only = 1;
    ble_hs_cfg.sm_our_key_dist = BLE_SM_PAIR_KEY_DIST_ENC | BLE_SM_PAIR_KEY_DIST_ID;
    ble_hs_cfg.sm_their_key_dist = BLE_SM_PAIR_KEY_DIST_ENC | BLE_SM_PAIR_KEY_DIST_ID;
    ble_svc_gap_init();
    ble_svc_gatt_init();
    int rc = ble_gatts_count_cfg(services);
    if (!rc) rc = ble_gatts_add_svcs(services);
    if (rc) {
        ESP_LOGE(TAG, "gatt service not added: %d", rc);
        return false;
    }
    ble_store_config_init();
    if (xTaskCreatePinnedToCore(host_task, "nimble_host", CONFIG_BT_NIMBLE_HOST_TASK_STACK_SIZE, NULL,
                                configMAX_PRIORITIES - 4, NULL, CONFIG_BT_NIMBLE_PINNED_TO_CORE) != pdPASS) {
        ESP_LOGE(TAG, "host task not created");
        return false;
    }
    ESP_LOGI(TAG, "ready, internal heap free %u KB, largest block %u KB",
             (unsigned)(heap_caps_get_free_size(MALLOC_CAP_INTERNAL) / 1024),
             (unsigned)(heap_caps_get_largest_free_block(MALLOC_CAP_INTERNAL) / 1024));
    return true;
}

bool ble_init(void)
{
    esp_read_mac(mac, ESP_MAC_WIFI_STA);
    acks = xQueueCreate(4, ACK_LEN);
    if (!acks) {
        ESP_LOGE(TAG, "ack queue not created");
        return false;
    }
    return start();
}

bool ble_resume(void)
{
    if (started || !acks) return started;
    bool ok = start();
    if (!ok && started) ble_stop();
    return ok;
}

bool ble_ready(void)
{
    return conn != BLE_HS_CONN_HANDLE_NONE && secure && subscribed;
}

bool ble_send_chunk(const audio_chunk_t *c)
{
    uint8_t h[CHUNK_HEADER];
    uint32_t duration = c->duration_ms, len = c->len;
    memcpy(h, mac, 6);
    memcpy(h + 6, &c->session, 4);
    memcpy(h + 10, &c->seq, 4);
    h[14] = c->final;
    memcpy(h + 15, &c->start_ms, 8);
    memcpy(h + 23, &duration, 4);
    memcpy(h + 27, &len, 4);
    memcpy(h + 31, &c->boot_id, 4);
    memcpy(h + 35, &c->boot_unix_ms, 8);
    xQueueReset(acks);
    int64_t t0 = esp_timer_get_time();
    if (!send_frame(TYPE_CHUNK, h, sizeof h, c->data, c->len)) return false;
    int64_t sent = esp_timer_get_time();
    for (int64_t waited = 0; ble_ready() && waited < ACK_TIMEOUT_MS * 1000LL; waited = esp_timer_get_time() - sent) {
        uint32_t ack[2];
        if (xQueueReceive(acks, ack, pdMS_TO_TICKS(200)) != pdTRUE || ack[0] != c->session || ack[1] != c->seq) continue;
        int64_t done = esp_timer_get_time();
        ESP_LOGI(TAG, "chunk session %08lx seq %lu (%u bytes): sent in %lld ms (%lld KB/s), ack after %lld ms",
                 (unsigned long)c->session, (unsigned long)c->seq, (unsigned)c->len, (sent - t0) / 1000,
                 (int64_t)c->len * 1000 / 1024 / ((sent - t0) / 1000 + 1), (done - sent) / 1000);
        return true;
    }
    ESP_LOGW(TAG, "no ack for session %08lx seq %lu", (unsigned long)c->session, (unsigned long)c->seq);
    return false;
}

bool ble_send_json(uint8_t type, const char *json)
{
    return send_frame(type, (const uint8_t *)json, strlen(json), NULL, 0);
}

bool ble_send_weather_set(const char *json)
{
    return ble_ready() && ble_send_json(BLE_TYPE_WEATHER_SET, json);
}

void ble_tick(bool busy)
{
    static int asked = -1;
    static uint32_t asked_at, ready_at;
    if (!ble_ready()) {
        ready_at = 0;
        asked = -1;
        return;
    }
    uint32_t now = esp_log_timestamp();
    if (!ready_at) ready_at = now;
    bool fast = busy || (int32_t)(fast_until - now) > 0;
    if (!fast && now - ready_at < SETTLE_MS) return;
    struct ble_gap_conn_desc d;
    if (ble_gap_conn_find(conn, &d)) return;
    bool in_range = fast ? d.conn_itvl <= FAST_ITVL_MAX : d.conn_itvl >= SLOW_ITVL_MIN && d.conn_latency == SLOW_LATENCY;
    if (in_range) {
        asked = fast;
        return;
    }
    if (asked == fast && now - asked_at < REASK_MS) return;
    struct ble_gap_upd_params p = {
        .itvl_min = fast ? FAST_ITVL_MIN : SLOW_ITVL_MIN,
        .itvl_max = fast ? FAST_ITVL_MAX : SLOW_ITVL_MAX,
        .latency = fast ? 0 : SLOW_LATENCY,
        .supervision_timeout = SUPERVISION_TIMEOUT,
    };
    int rc = ble_gap_update_params(conn, &p);
    asked = fast;
    asked_at = now;
    ESP_LOGI(TAG, "ask for the %s interval (%u to %u x 1.25 ms, latency %u): %d", fast ? "fast" : "slow", p.itvl_min, p.itvl_max,
             p.latency, rc);
}

void ble_stop(void)
{
    if (!started) return;
    started = false;
    int rc = nimble_port_stop();
    if (rc == 0) nimble_port_deinit();
    conn = BLE_HS_CONN_HANDLE_NONE;
    note_itvl(0);
    secure = subscribed = false;
    passkey = -1;
    ESP_LOGI(TAG, "stopped: %d", rc);
}

int ble_passkey(void)

{
    return passkey;
}

void ble_get_stats(ble_stats_t *out)
{
    out->fast_s = (fast_ms + (at_fast ? esp_log_timestamp() - fast_since : 0)) / 1000;
    out->tx_bytes = tx_bytes;
}
