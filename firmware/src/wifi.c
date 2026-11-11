#include "esp_attr.h"
#include "wifi.h"
#include "esp_event.h"
#include "esp_log.h"
#include "esp_wifi.h"
#include "freertos/event_groups.h"
#include "freertos/semphr.h"
#include "freertos/task.h"
#include "nvs.h"
#include <stdio.h>
#include <string.h>

#define UP_BIT BIT0
#define PSK_LEN 65
#define CONNECT_TIMEOUT_MS 20000
#define SCAN_FIRST_MS 10000
#define SCAN_MAX_MS (5 * 60 * 1000)
#define SCAN_MAX 20

static const char *TAG = "wifi";
static EventGroupHandle_t events;
static SemaphoreHandle_t lock;
static int count;
static char ssids[WIFI_MAX_PROFILES][WIFI_SSID_LEN];
static char psks[WIFI_MAX_PROFILES][PSK_LEN];
static char connected[WIFI_SSID_LEN];
static volatile bool connecting, off;
static TaskHandle_t task;

static void save(void)
{
    nvs_handle_t h;
    if (nvs_open("wifi", NVS_READWRITE, &h) != ESP_OK) return;
    nvs_erase_all(h);
    nvs_set_i32(h, "count", count);
    for (int i = 0; i < count; i++) {
        char key[16];
        snprintf(key, sizeof key, "ssid%d", i);
        nvs_set_str(h, key, ssids[i]);
        snprintf(key, sizeof key, "psk%d", i);
        nvs_set_str(h, key, psks[i]);
    }
    nvs_commit(h);
    nvs_close(h);
}

static void load(void)
{
    nvs_handle_t h;
    int32_t n = 0;
    if (nvs_open("wifi", NVS_READONLY, &h) != ESP_OK) return;
    nvs_get_i32(h, "count", &n);
    for (int i = 0; i < n && count < WIFI_MAX_PROFILES; i++) {
        char key[16];
        size_t a = WIFI_SSID_LEN, b = PSK_LEN;
        snprintf(key, sizeof key, "ssid%d", i);
        if (nvs_get_str(h, key, ssids[count], &a) != ESP_OK) continue;
        snprintf(key, sizeof key, "psk%d", i);
        if (nvs_get_str(h, key, psks[count], &b) != ESP_OK) continue;
        count++;
    }
    nvs_close(h);
}

static int find(const char *ssid)
{
    for (int i = 0; i < count; i++)
        if (!strcmp(ssids[i], ssid)) return i;
    return -1;
}

static void on_event(void *arg, esp_event_base_t base, int32_t id, void *data)
{
    if (base == WIFI_EVENT && id == WIFI_EVENT_STA_DISCONNECTED) {
        xEventGroupClearBits(events, UP_BIT);
        xSemaphoreTake(lock, portMAX_DELAY);
        connected[0] = 0;
        xSemaphoreGive(lock);
        connecting = false;
    }
    if (base == IP_EVENT && id == IP_EVENT_STA_GOT_IP) {
        connecting = false;
        xEventGroupSetBits(events, UP_BIT);
        ESP_LOGI(TAG, "connected to %s", connected);
    }
}

static bool connect_best(void)
{
    EXT_RAM_BSS_ATTR static wifi_ap_record_t aps[SCAN_MAX];
    uint16_t n = SCAN_MAX;
    if (esp_wifi_scan_start(NULL, true) != ESP_OK || esp_wifi_scan_get_ap_records(&n, aps) != ESP_OK) return false;
    xSemaphoreTake(lock, portMAX_DELAY);
    int best = -1, best_rssi = -1000;
    for (int i = 0; i < n; i++) {
        int p = find((const char *)aps[i].ssid);
        if (p >= 0 && aps[i].rssi > best_rssi) {
            best = p;
            best_rssi = aps[i].rssi;
        }
    }
    wifi_config_t wc = {0};
    if (best >= 0) {
        strlcpy((char *)wc.sta.ssid, ssids[best], sizeof wc.sta.ssid);
        strlcpy((char *)wc.sta.password, psks[best], sizeof wc.sta.password);
        strlcpy(connected, ssids[best], sizeof connected);
    }
    xSemaphoreGive(lock);
    if (best < 0) return false;
    ESP_LOGI(TAG, "joining %s (rssi %d)", wc.sta.ssid, best_rssi);
    wc.sta.listen_interval = 3;
    esp_wifi_set_config(WIFI_IF_STA, &wc);
    return esp_wifi_connect() == ESP_OK;
}

static void wifi_task(void *arg)
{
    int64_t started = 0;
    int idle_ms = SCAN_FIRST_MS;
    for (;;) {
        bool up = xEventGroupGetBits(events) & UP_BIT;
        int64_t now = esp_log_timestamp();
        if (connecting && now - started > CONNECT_TIMEOUT_MS) {
            esp_wifi_disconnect();
            connecting = false;
        }
        if (!off && !up && !connecting && count > 0) {
            connecting = connect_best();
            started = now;
            idle_ms = connecting ? SCAN_FIRST_MS : idle_ms * 2 < SCAN_MAX_MS ? idle_ms * 2 : SCAN_MAX_MS;
        }
        if (ulTaskNotifyTake(pdTRUE, pdMS_TO_TICKS(up || connecting ? 2000 : idle_ms))) idle_ms = SCAN_FIRST_MS;
    }
}

void wifi_init(void)
{
    events = xEventGroupCreate();
    lock = xSemaphoreCreateMutex();
    load();
    wifi_init_config_t wi = WIFI_INIT_CONFIG_DEFAULT();
    esp_wifi_init(&wi);
    esp_event_handler_register(WIFI_EVENT, WIFI_EVENT_STA_DISCONNECTED, on_event, NULL);
    esp_event_handler_register(IP_EVENT, IP_EVENT_STA_GOT_IP, on_event, NULL);
}

void wifi_start(void)
{
    esp_wifi_set_mode(WIFI_MODE_STA);
    esp_wifi_start();
    esp_wifi_set_ps(WIFI_PS_MAX_MODEM);
    ESP_LOGI(TAG, "%d saved networks", count);
    if (xTaskCreate(wifi_task, "wifi", 4096, NULL, 4, &task) != pdPASS) ESP_LOGE(TAG, "wifi task not created");
}

void wifi_stop(void)
{
    off = true;
    esp_wifi_stop();
    xEventGroupClearBits(events, UP_BIT);
    xSemaphoreTake(lock, portMAX_DELAY);
    connected[0] = 0;
    xSemaphoreGive(lock);
    connecting = false;
    ESP_LOGI(TAG, "stopped");
}

void wifi_resume(void)
{
    esp_wifi_start();
    esp_wifi_set_ps(WIFI_PS_MAX_MODEM);
    off = false;
    if (task) xTaskNotifyGive(task);
    ESP_LOGI(TAG, "started again");
}

bool wifi_wait_up(TickType_t timeout)
{
    return xEventGroupWaitBits(events, UP_BIT, false, true, timeout) & UP_BIT;
}

void wifi_get_status(wifi_status_t *out)
{
    xSemaphoreTake(lock, portMAX_DELAY);
    out->count = count;
    memcpy(out->ssid, ssids, sizeof out->ssid);
    strlcpy(out->connected, connected, sizeof out->connected);
    xSemaphoreGive(lock);
    out->up = xEventGroupGetBits(events) & UP_BIT;
    out->off = off;

    wifi_ap_record_t ap;
    out->rssi = out->up && esp_wifi_sta_get_ap_info(&ap) == ESP_OK ? ap.rssi : 0;
}

bool wifi_add(const char *ssid, const char *psk)
{
    if (!ssid[0] || strlen(ssid) >= WIFI_SSID_LEN || strlen(psk) >= PSK_LEN) return false;
    xSemaphoreTake(lock, portMAX_DELAY);
    int i = find(ssid);
    if (i < 0 && count < WIFI_MAX_PROFILES) i = count++;
    if (i >= 0) {
        strlcpy(ssids[i], ssid, WIFI_SSID_LEN);
        strlcpy(psks[i], psk, PSK_LEN);
        save();
    }
    xSemaphoreGive(lock);
    if (i >= 0) ESP_LOGI(TAG, "saved %s", ssid);
    if (i >= 0 && task) xTaskNotifyGive(task);
    else ESP_LOGW(TAG, "full, forget a network before adding %s", ssid);
    return i >= 0;
}

bool wifi_forget(const char *ssid)
{
    xSemaphoreTake(lock, portMAX_DELAY);
    int i = find(ssid);
    bool was_connected = i >= 0 && !strcmp(connected, ssid);
    if (i >= 0) {
        for (int j = i; j < count - 1; j++) {
            memcpy(ssids[j], ssids[j + 1], WIFI_SSID_LEN);
            memcpy(psks[j], psks[j + 1], PSK_LEN);
        }
        count--;
        save();
    }
    xSemaphoreGive(lock);
    if (was_connected) esp_wifi_disconnect();
    if (i >= 0) ESP_LOGI(TAG, "forgot %s", ssid);
    else ESP_LOGW(TAG, "%s is not saved", ssid);
    return i >= 0;
}
