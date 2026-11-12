#pragma once
#include "audio.h"
#include <stdbool.h>
#include <stdint.h>

bool ble_init(void);
bool ble_ready(void);
bool ble_send_chunk(const audio_chunk_t *chunk);
#define BLE_TYPE_CHECKIN 3
#define BLE_TYPE_WEATHER_SET 12
#define BLE_TYPE_OTA_BEGIN 18
#define BLE_TYPE_OTA_DATA 19
#define BLE_TYPE_OTA_END 20
#define BLE_TYPE_OTA_STATUS 21
#define BLE_TYPE_OTA_CANCEL 22
#define BLE_TYPE_TODO_SET 24
#define BLE_TYPE_BUZZ_TEST 25
#define BLE_TYPE_HUB_TOKEN 26

bool ble_send_json(uint8_t type, const char *json);
bool ble_send_weather_set(const char *json);
int ble_passkey(void);
void ble_tick(bool busy);
void ble_stop(void);
bool ble_resume(void);

