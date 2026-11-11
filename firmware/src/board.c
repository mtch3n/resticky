#include "board.h"
#include "driver/gpio.h"
#include "driver/rtc_io.h"
#include "esp_log.h"
#include "esp_sleep.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#define HELD_PINS ((1ULL << PIN_PWR_HOLD) | (1ULL << PIN_PWR_LOCK) | (1ULL << PIN_CHG_EN_N))
static const char *TAG = "board";
static const int WAKE_PINS[] = {PIN_BTN_POWER, PIN_BTN_UP, PIN_BTN_DOWN, PIN_MOTION_INT};
#define WAKE_COUNT (sizeof WAKE_PINS / sizeof WAKE_PINS[0])

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

static bool keys_down(void)
{
    return !gpio_get_level(PIN_BTN_POWER) || !gpio_get_level(PIN_BTN_UP) || !gpio_get_level(PIN_BTN_DOWN);
}

void board_sleep(uint32_t wake_after_s, uint32_t release_wait_ms)
{
    int64_t until = esp_timer_get_time() + release_wait_ms * 1000LL;
    while (keys_down() && esp_timer_get_time() < until) vTaskDelay(pdMS_TO_TICKS(20));
    uint64_t mask = 0;
    int levels[WAKE_COUNT];
    for (int i = 0; i < WAKE_COUNT; i++) {
        levels[i] = gpio_get_level(WAKE_PINS[i]);
        if (levels[i] || WAKE_PINS[i] == PIN_BTN_POWER) mask |= 1ULL << WAKE_PINS[i];
    }
    ESP_LOGI(TAG, "sleep: power %d up %d down %d int %d (1 = high), wake mask 0x%llx, timer %lu s", levels[0], levels[1], levels[2],
             levels[3], (unsigned long long)mask, (unsigned long)wake_after_s);
    if (!levels[3]) ESP_LOGW(TAG, "gpio %d is low: no wake on tap", PIN_MOTION_INT);
    gpio_hold_en(PIN_PWR_HOLD);
    gpio_hold_en(PIN_PWR_LOCK);
    gpio_hold_en(PIN_CHG_EN_N);
    gpio_deep_sleep_hold_en();
    for (int pin = PIN_BTN_POWER; pin <= PIN_MOTION_INT; pin++) {
        rtc_gpio_pullup_en(pin);
        rtc_gpio_pulldown_dis(pin);
    }
    if (mask) esp_sleep_enable_ext1_wakeup_io(mask, ESP_EXT1_WAKEUP_ANY_LOW);

    if (wake_after_s) esp_sleep_enable_timer_wakeup((uint64_t)wake_after_s * 1000000);
    esp_deep_sleep_start();
}
