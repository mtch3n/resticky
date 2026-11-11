#pragma once
#include <stdbool.h>
#include "freertos/FreeRTOS.h"

#define WIFI_MAX_PROFILES 5
#define WIFI_SSID_LEN 33

typedef struct {
    int count;
    char ssid[WIFI_MAX_PROFILES][WIFI_SSID_LEN];
    char connected[WIFI_SSID_LEN];
    bool up, off;
    int rssi;
} wifi_status_t;

void wifi_init(void);
void wifi_start(void);
void wifi_stop(void);
void wifi_resume(void);

bool wifi_wait_up(TickType_t timeout);
void wifi_get_status(wifi_status_t *out);
bool wifi_add(const char *ssid, const char *psk);
bool wifi_forget(const char *ssid);
