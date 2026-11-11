#include "buttons.h"
#include "board.h"
#include "driver/gpio.h"
#include "esp_log.h"
#include "esp_timer.h"
#include <stdbool.h>
#include <stdint.h>

#define HOLD_MS 1500
#define POWER_OFF_MS 10000
#define DOUBLE_MS 400

static const char *TAG = "buttons";
static const int PINS[BTN_COUNT] = {PIN_BTN_POWER, PIN_BTN_UP, PIN_BTN_DOWN};
static const char *const NAMES[BTN_COUNT] = {"power", "up", "down"};

typedef struct {
    bool down, armed, held, waiting;
    int64_t since, released;
} key_t;
static key_t keys[BTN_COUNT];

void buttons_init(void)
{
    uint64_t mask = 0;
    for (int b = 0; b < BTN_COUNT; b++) mask |= 1ULL << PINS[b];
    gpio_config_t in = {.pin_bit_mask = mask, .mode = GPIO_MODE_INPUT, .pull_up_en = GPIO_PULLUP_ENABLE};
    gpio_config(&in);
    for (int b = 0; b < BTN_COUNT; b++) keys[b].armed = gpio_get_level(PINS[b]) == 1;
}

void buttons_poll(button_cb cb)
{
    int64_t now = esp_timer_get_time() / 1000;
    for (int b = 0; b < BTN_COUNT; b++) {
        key_t *k = &keys[b];
        bool down = gpio_get_level(PINS[b]) == 0;
        if (!k->armed) {
            k->armed = !down;
            continue;
        }
        if (down && !k->down) {
            k->since = now;
            k->held = false;
        } else if (down && !k->held) {
            if (b == BTN_POWER && now - k->since >= POWER_OFF_MS) {
                k->held = true;
                ESP_LOGI(TAG, "%s (gpio %d) held 10 s", NAMES[b], PINS[b]);
                cb(b, BTN_POWER_OFF);
            } else if (b != BTN_POWER && now - k->since >= HOLD_MS) {
                k->held = true;
                ESP_LOGI(TAG, "%s (gpio %d) held", NAMES[b], PINS[b]);
                cb(b, BTN_HOLD);
            }
        } else if (!down && k->down && !k->held) {
            if (b != BTN_POWER) {
                ESP_LOGI(TAG, "%s (gpio %d) pressed", NAMES[b], PINS[b]);
                cb(b, BTN_PRESS);
            } else if (k->waiting && now - k->released < DOUBLE_MS) {
                k->waiting = false;
                ESP_LOGI(TAG, "%s (gpio %d) double press", NAMES[b], PINS[b]);
                cb(b, BTN_DOUBLE);
            } else {
                k->waiting = true;
                k->released = now;
            }
        }
        if (k->waiting && !down && now - k->released >= DOUBLE_MS) {
            k->waiting = false;
            ESP_LOGI(TAG, "%s (gpio %d) pressed", NAMES[b], PINS[b]);
            cb(b, BTN_PRESS);
        }
        k->down = down;
    }
}
