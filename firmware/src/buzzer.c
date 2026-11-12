#include "buzzer.h"
#include "settings.h"
#include "driver/gpio.h"
#include "driver/ledc.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#define BUZZER_TIMER LEDC_TIMER_1
#define BUZZER_CHANNEL LEDC_CHANNEL_1
#define HALF_DUTY (1 << 13)

static int pin = -1;
static int sound = SOUND_BEEP, buzz_hz = 175;

void buzzer_set_sound(int s, int hz)
{
    sound = s;
    buzz_hz = hz;
}

static void attach(int gpio)
{
    ledc_timer_config_t t = {.speed_mode = LEDC_LOW_SPEED_MODE, .timer_num = BUZZER_TIMER, .duty_resolution = LEDC_TIMER_14_BIT,
                             .freq_hz = 2700, .clk_cfg = LEDC_AUTO_CLK};
    ledc_timer_config(&t);
    ledc_channel_config_t c = {.gpio_num = gpio, .speed_mode = LEDC_LOW_SPEED_MODE, .channel = BUZZER_CHANNEL, .timer_sel = BUZZER_TIMER, .duty = 0};
    ledc_channel_config(&c);
}

static void tone_duty(int hz, int ms, int duty)
{
    ledc_set_freq(LEDC_LOW_SPEED_MODE, BUZZER_TIMER, hz);
    ledc_set_duty(LEDC_LOW_SPEED_MODE, BUZZER_CHANNEL, duty);
    ledc_update_duty(LEDC_LOW_SPEED_MODE, BUZZER_CHANNEL);
    vTaskDelay(pdMS_TO_TICKS(ms));
    ledc_set_duty(LEDC_LOW_SPEED_MODE, BUZZER_CHANNEL, 0);
    ledc_update_duty(LEDC_LOW_SPEED_MODE, BUZZER_CHANNEL);
}

static void tone(int hz, int ms) { tone_duty(hz, ms, HALF_DUTY); }

static void buzz(int ms) { tone_duty(buzz_hz, ms, HALF_DUTY); }

void buzzer_init(int gpio)
{
    pin = gpio;
    if (pin >= 0) attach(pin);
}

void buzzer_click(void)
{
    if (pin < 0 || sound == SOUND_OFF) return;
    if (sound == SOUND_BUZZ) buzz(35);
    else tone(2700, 12);
}

void buzzer_test(int hz)
{
    if (pin < 0) return;
    int keep = buzz_hz;
    buzz_hz = hz;
    buzz(35);
    vTaskDelay(pdMS_TO_TICKS(400));
    buzz(100);
    vTaskDelay(pdMS_TO_TICKS(80));
    buzz(100);
    buzz_hz = keep;
}

void buzzer_mark(void)
{
    if (pin < 0 || sound == SOUND_OFF) return;
    if (sound == SOUND_BUZZ) {
        buzz(100);
        vTaskDelay(pdMS_TO_TICKS(80));
        buzz(100);
        return;
    }
    tone(2200, 60);
    vTaskDelay(pdMS_TO_TICKS(40));
    tone(3000, 80);
}
