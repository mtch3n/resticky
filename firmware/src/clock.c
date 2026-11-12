#include "clock.h"
#include "esp_log.h"
#include "esp_random.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "nvs.h"
#include "sensors.h"
#include <stdio.h>
#include <stdlib.h>
#include <sys/time.h>
#include <time.h>

#define VALID_AFTER 1735689600

static const char *TAG = "clock";
static int offset_min = 0;
static uint32_t boot_id;
static int64_t boot_unix_ms;
static portMUX_TYPE anchor_lock = portMUX_INITIALIZER_UNLOCKED;

static int64_t anchor_now(void)
{
    struct timeval tv;
    gettimeofday(&tv, NULL);
    if (tv.tv_sec <= VALID_AFTER) return 0;
    int64_t ms = (int64_t)tv.tv_sec * 1000 + tv.tv_usec / 1000 - esp_timer_get_time() / 1000;
    taskENTER_CRITICAL(&anchor_lock);
    boot_unix_ms = ms;
    taskEXIT_CRITICAL(&anchor_lock);
    ESP_LOGI(TAG, "boot %08lx anchor %lld", (unsigned long)boot_id, (long long)ms);
    return ms;
}

uint32_t clock_boot_id(void) { return boot_id; }

int64_t clock_boot_unix_ms(void)
{
    taskENTER_CRITICAL(&anchor_lock);
    int64_t ms = boot_unix_ms;
    taskEXIT_CRITICAL(&anchor_lock);
    return ms ? ms : anchor_now();
}

static void apply(void)
{
    char tz[16];
    int a = abs(offset_min);
    snprintf(tz, sizeof tz, "UTC%c%d:%02d", offset_min > 0 ? '-' : '+', a / 60, a % 60);
    setenv("TZ", tz, 1);
    tzset();
}

void clock_init(void)
{
    while (!boot_id) boot_id = esp_random();
    nvs_handle_t h;
    int32_t v;
    if (nvs_open("clock", NVS_READONLY, &h) == ESP_OK) {
        if (nvs_get_i32(h, "offset", &v) == ESP_OK) offset_min = v;
        nvs_close(h);
    }
    apply();
    time_t t;
    if (sensors_rtc_read(&t) && t > VALID_AFTER) {
        settimeofday(&(struct timeval){.tv_sec = t}, NULL);
        ESP_LOGI(TAG, "time from the RTC: %lld, utc offset %d min", (long long)t, offset_min);
        anchor_now();
    } else {
        ESP_LOGW(TAG, "RTC has no valid time; waiting for the network");
    }
}

void clock_set_offset(int utc_offset_min)
{
    if (utc_offset_min == offset_min || utc_offset_min < -14 * 60 || utc_offset_min > 14 * 60) return;
    offset_min = utc_offset_min;
    apply();
    nvs_handle_t h;
    if (nvs_open("clock", NVS_READWRITE, &h) == ESP_OK) {
        nvs_set_i32(h, "offset", offset_min);
        nvs_commit(h);
        nvs_close(h);
    }
    ESP_LOGI(TAG, "utc offset %d min", offset_min);
}

void clock_synced(void)
{
    anchor_now();
    time_t now = time(NULL);
    if (now > VALID_AFTER && sensors_rtc_write(now)) ESP_LOGI(TAG, "RTC set from the network: %lld", (long long)now);
}
