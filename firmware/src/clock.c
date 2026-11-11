#include "clock.h"
#include "esp_log.h"
#include "nvs.h"
#include "sensors.h"
#include <stdio.h>
#include <stdlib.h>
#include <sys/time.h>
#include <time.h>

#define VALID_AFTER 1735689600

static const char *TAG = "clock";
static int offset_min = 0;

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
    time_t now = time(NULL);
    if (now > VALID_AFTER && sensors_rtc_write(now)) ESP_LOGI(TAG, "RTC set from the network: %lld", (long long)now);
}
