#pragma once
#include <stdbool.h>
#include <stdint.h>
#include <time.h>

typedef struct {
    bool gauge_ok;
    int soc_pct;
    int voltage_mv;
    int current_ma;
    int avg_current_ma;
    int remaining_mah;
    int full_mah;
    int design_mah;
    bool imu_ok;
    int ax_mg;
    int ay_mg;
    int az_mg;
    bool usb;
    bool climate_ok;
    int room_c10, humidity;
    bool charging;
    int vbus_mv;
} sensors_t;

void sensors_init(void);
void sensors_read(sensors_t *s);
int sensors_chip_celsius(void);
bool sensors_double_tap(void);
void sensors_sleep_prepare(void);
bool sensors_rtc_read(time_t *out);
bool sensors_rtc_write(time_t t);
