#include "board.h"
#include "driver/gpio.h"
#include "driver/rtc_io.h"
#include "driver/uart.h"
#include "esp_log.h"
#include "esp_sleep.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#define HELD_PINS ((1ULL << PIN_PWR_HOLD) | (1ULL << PIN_PWR_LOCK) | (1ULL << PIN_CHG_EN_N))
static const char *TAG = "board";
static const int WAKE_PINS[] = {PIN_BTN_POWER, PIN_BTN_UP, PIN_BTN_DOWN, PIN_MOTION_INT};
#define WAKE_COUNT (sizeof WAKE_PINS / sizeof WAKE_PINS[0])
static const int KEY_PINS[] = {PIN_BTN_POWER, PIN_BTN_UP, PIN_BTN_DOWN};
#define KEY_COUNT (sizeof KEY_PINS / sizeof KEY_PINS[0])
static const int DRIVEN_PINS[] = {PIN_PWR_HOLD, PIN_PWR_LOCK, PIN_CHG_EN_N, PIN_EPD_PWR, PIN_EPD_RST, PIN_EPD_CS, PIN_EPD_DC,
                                  PIN_SD_PWR_EN, PIN_SD_CS, PIN_TP_EN, PIN_TP_RST, PIN_MIC_EN, PIN_BUZZER};
#define DRIVEN_COUNT (sizeof DRIVEN_PINS / sizeof DRIVEN_PINS[0])
#define KEY_SETTLE_MS 50

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
    esp_sleep_pd_config(ESP_PD_DOMAIN_RTC_PERIPH, ESP_PD_OPTION_ON);
    if (mask) esp_sleep_enable_ext1_wakeup_io(mask, ESP_EXT1_WAKEUP_ANY_LOW);

    if (wake_after_s) esp_sleep_enable_timer_wakeup((uint64_t)wake_after_s * 1000000);
    esp_deep_sleep_start();
}

bool board_light_sleep(uint32_t wake_after_s, uint32_t release_wait_ms)
{
    int64_t until = esp_timer_get_time() + release_wait_ms * 1000LL;
    while (keys_down() && esp_timer_get_time() < until) vTaskDelay(pdMS_TO_TICKS(20));
    vTaskDelay(pdMS_TO_TICKS(KEY_SETTLE_MS));
    for (int i = 0; i < DRIVEN_COUNT; i++) gpio_sleep_sel_dis(DRIVEN_PINS[i]);
    unsigned mask = 0;
    for (int i = 0; i < KEY_COUNT; i++) {
        if (!gpio_get_level(KEY_PINS[i])) continue;
        gpio_wakeup_enable(KEY_PINS[i], GPIO_INTR_LOW_LEVEL);
        mask |= 1u << KEY_PINS[i];
    }
    if (mask) esp_sleep_enable_gpio_wakeup();
    else ESP_LOGW(TAG, "light sleep: all keys low, timer wake only");
    esp_sleep_enable_timer_wakeup((uint64_t)wake_after_s * 1000000);
    uart_wait_tx_idle_polling(CONFIG_ESP_CONSOLE_UART_NUM);
    esp_err_t err = esp_light_sleep_start();
    esp_sleep_wakeup_cause_t cause = esp_sleep_get_wakeup_cause();
    for (int i = 0; i < KEY_COUNT; i++)
        if (mask & 1u << KEY_PINS[i]) gpio_wakeup_disable(KEY_PINS[i]);
    esp_sleep_disable_wakeup_source(ESP_SLEEP_WAKEUP_TIMER);
    if (mask) esp_sleep_disable_wakeup_source(ESP_SLEEP_WAKEUP_GPIO);
    bool timer = err == ESP_OK && cause == ESP_SLEEP_WAKEUP_TIMER;
    ESP_LOGI(TAG, "light sleep: %s, cause %d, key mask 0x%x, power %d up %d down %d", esp_err_to_name(err), cause, mask,
             gpio_get_level(PIN_BTN_POWER), gpio_get_level(PIN_BTN_UP), gpio_get_level(PIN_BTN_DOWN));
    return timer;
}
