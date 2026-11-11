#pragma once

#define PIN_PWR_HOLD 45
#define PIN_PWR_LOCK 46
#define PIN_CHG_EN_N 39
#define PIN_CHG_STAT 40
#define VBUS_ADC_CHANNEL ADC_CHANNEL_8
#define PIN_EPD_SCK 13
#define PIN_EPD_MOSI 14
#define PIN_EPD_CS 15
#define PIN_EPD_DC 16
#define PIN_EPD_RST 17
#define PIN_EPD_BUSY 18
#define PIN_EPD_PWR 47
#define PIN_SENS_SDA 1
#define PIN_SENS_SCL 0
#define PIN_BTN_POWER 4
#define PIN_BTN_UP 5
#define PIN_BTN_DOWN 6
#define PIN_BUZZER 48
#define PIN_MOTION_INT 7
#define PIN_SD_PWR_EN 10
#define PIN_SD_DETECT 11
#define PIN_SD_MISO 12
#define PIN_SD_CS 8

void board_power_latch(void);
void board_power_off(void);
void board_sleep(void);
