#include "board.h"
#include "driver/gpio.h"
#include "driver/rtc_io.h"
#include "esp_sleep.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#define HELD_PINS ((1ULL << PIN_PWR_HOLD) | (1ULL << PIN_PWR_LOCK) | (1ULL << PIN_CHG_EN_N))
#define WAKE_PINS ((1ULL << PIN_BTN_POWER) | (1ULL << PIN_BTN_UP) | (1ULL << PIN_BTN_DOWN) | (1ULL << PIN_MOTION_INT))

void board_power_latch(void)
{
    gpio_config_t out = {.pin_bit_mask = HELD_PINS, .mode = GPIO_MODE_OUTPUT};
    gpio_config(&out);
    gpio_set_level(PIN_PWR_HOLD, 1);
    gpio_set_level(PIN_PWR_LOCK, 1);
    gpio_set_level(PIN_CHG_EN_N, 0);
    gpio_hold_dis(PIN_PWR_HOLD);
    gpio_hold_dis(PIN_PWR_LOCK);
    gpio_hold_dis(PIN_CHG_EN_N);
    for (int pin = PIN_BTN_POWER; pin <= PIN_MOTION_INT; pin++) rtc_gpio_deinit(pin);
}

void board_power_off(void)
{
    gpio_set_level(PIN_PWR_HOLD, 0);
    gpio_set_level(PIN_PWR_LOCK, 0);
    vTaskDelay(pdMS_TO_TICKS(500));
    rtc_gpio_pullup_en(PIN_BTN_POWER);
    esp_sleep_enable_ext0_wakeup(PIN_BTN_POWER, 0);
    esp_deep_sleep_start();
}

void board_sleep(void)
{
    gpio_hold_en(PIN_PWR_HOLD);
    gpio_hold_en(PIN_PWR_LOCK);
    gpio_hold_en(PIN_CHG_EN_N);
    gpio_deep_sleep_hold_en();
    for (int pin = PIN_BTN_POWER; pin <= PIN_MOTION_INT; pin++) {
        rtc_gpio_pullup_en(pin);
        rtc_gpio_pulldown_dis(pin);
    }
    esp_sleep_enable_ext1_wakeup_io(WAKE_PINS, ESP_EXT1_WAKEUP_ANY_LOW);
    esp_deep_sleep_start();
}
