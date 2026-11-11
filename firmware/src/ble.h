#pragma once
#include "audio.h"
#include <stdbool.h>
#include <stdint.h>

bool ble_init(void);
bool ble_ready(void);
bool ble_send_chunk(const audio_chunk_t *chunk);
#define BLE_TYPE_CHECKIN 3
#define BLE_TYPE_WEATHER_SET 12

bool ble_send_json(uint8_t type, const char *json);
bool ble_send_weather_set(const char *json);
int ble_passkey(void);
