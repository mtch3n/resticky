#include "audio.h"
#include "ble.h"
#include "board.h"
#include "driver/gpio.h"
#include "buttons.h"
#include "buzzer.h"
#include "sdcard.h"
#include "clock.h"
#include "net.h"
#include "ota.h"
#include "epd.h"
#include "esp_attr.h"
#include "esp_heap_caps.h"
#include "esp_log.h"
#include "esp_pm.h"
#include "esp_core_dump.h"
#include "esp_sleep.h"
#include <stdlib.h>
#include "esp_system.h"
#include "nvs_flash.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "sensors.h"
#include "settings.h"
#include "touch.h"
#include "ui.h"
#include "lvgl.h"
#include <string.h>
#include <time.h>

static const char *TAG = "main";
static bool recording = true;
static bool update_pause, resume_after_update;
static settings_t settings;

static void set_recording(bool rec)
{
    recording = rec;
    ESP_LOGI(TAG, "recording %s", rec ? "on" : "off");
    audio_set_recording(rec);
    ui_set_recording(rec);
}

static void toggle_recording(void) { set_recording(!recording); }

#define FINAL_UPLOAD_MS 3000

static void apply_settings(const settings_t *s)
{
    bool sample = s->sound != settings.sound || s->buzz_pitch != settings.buzz_pitch;
    settings = *s;
    settings_save(&settings);
    audio_set_bitrate(settings.bitrate_kbps * 1000);
    buzzer_set_sound(settings.sound, BUZZ_HZ[settings.buzz_pitch]);
    net_set_sound(settings.sound);
    net_set_home(settings.home);
    net_set_night(settings.night_on, settings.night_start, settings.night_end);
    if (sample) buzzer_mark();
    ESP_LOGI(TAG, "settings: %d kbps, flip stop %d", settings.bitrate_kbps, settings.flip_stop);
}

static void restart(void)
{
    set_recording(false);
    vTaskDelay(pdMS_TO_TICKS(FINAL_UPLOAD_MS));
    esp_restart();
}

static void sleep_for(uint32_t seconds)
{
    set_recording(false);
    ui_show_sleep();
    vTaskDelay(pdMS_TO_TICKS(FINAL_UPLOAD_MS));
    sensors_double_tap();
    board_sleep(seconds);
}

static void go_sleep(void)
{
    ESP_LOGI(TAG, "manual sleep");
    sleep_for(0);
}

#define NIGHT_AWAKE_MS (5 * 60 * 1000)

static uint32_t last_button_ms;

static int night_left_s(const settings_t *s)
{
    time_t now = time(NULL);
    if (!s->night_on || now < 1700000000) return 0;
    struct tm t;
    localtime_r(&now, &t);
    int m = t.tm_hour * 60 + t.tm_min, start = s->night_start, end = s->night_end;
    bool in = start < end ? m >= start && m < end : m >= start || m < end;
    if (!in) return 0;
    int left = (end - m + 1440) % 1440;
    return left * 60 - t.tm_sec + 30;
}

static void night_check(void)
{
    int left = night_left_s(&settings);
    uint32_t idle = lv_display_get_inactive_time(NULL);
    uint32_t since_button = esp_log_timestamp() - last_button_ms;
    if (!left || update_pause || idle < NIGHT_AWAKE_MS || since_button < NIGHT_AWAKE_MS || esp_log_timestamp() < NIGHT_AWAKE_MS) return;
    ESP_LOGI(TAG, "night: sleep for %d s", left);
    sleep_for(left);
}

static void power_off(void)
{
    set_recording(false);
    vTaskDelay(pdMS_TO_TICKS(FINAL_UPLOAD_MS));
    ui_show_off();
    board_power_off();
}

static void do_action(action_t action);

static void on_button(button_t button, button_event_t event)
{
    if (event == BTN_POWER_OFF) return power_off();
    last_button_ms = esp_log_timestamp();
    buzzer_click();
    static const int SLOTS[BTN_COUNT][2] = {
        [BTN_POWER] = {SLOT_POWER_PRESS, -1},
        [BTN_UP] = {SLOT_UP_PRESS, SLOT_UP_HOLD},
        [BTN_DOWN] = {SLOT_DOWN_PRESS, SLOT_DOWN_HOLD},
    };
    int slot = event == BTN_DOUBLE ? SLOT_POWER_DOUBLE : SLOTS[button][event == BTN_HOLD];
    if (slot < 0) return;
    do_action(settings.buttons[slot]);
}

static void do_action(action_t action)
{
    if (update_pause) return;
    if (action == ACT_MARK) {
        bool ok = net_mark();
        if (ok) buzzer_mark();
        ui_show_mark(ok);
    }
    else if (action == ACT_SLEEP) go_sleep();
    else if (action == ACT_MUTE) toggle_recording();
    else ui_key(action);
}

static void report_boot(void)
{
    static const char *const RESETS[] = {"unknown", "power-on", "ext", "software", "panic", "int-wdt", "task-wdt", "wdt", "deep-sleep", "brownout", "sdio", "usb", "jtag", "efuse", "pwr-glitch", "cpu-lockup"};
    static const char *const WAKES[] = {"none", "all", "ext0", "ext1", "timer", "touch", "ulp", "gpio", "uart", "wifi", "cocpu", "cocpu-trap", "bt"};
    esp_reset_reason_t r = esp_reset_reason();
    esp_sleep_wakeup_cause_t w = esp_sleep_get_wakeup_cause();
    char text[160];
    int o = snprintf(text, sizeof text, "reset=%s wake=%s", r < sizeof RESETS / sizeof RESETS[0] ? RESETS[r] : "?",
                     (unsigned)w < sizeof WAKES / sizeof WAKES[0] ? WAKES[w] : "?");
    if (w == ESP_SLEEP_WAKEUP_EXT1) o += snprintf(text + o, sizeof text - o, " pins=0x%llx", (unsigned long long)esp_sleep_get_ext1_wakeup_status());
    esp_core_dump_summary_t *sum = malloc(sizeof *sum);
    if (sum && esp_core_dump_image_check() == ESP_OK && esp_core_dump_get_summary(sum) == ESP_OK) {
        o += snprintf(text + o, sizeof text - o, " dump=%s pc=0x%lx cause=%lu bt", sum->exc_task, (unsigned long)sum->exc_pc,
                      (unsigned long)sum->ex_info.exc_cause);
        for (int i = 0; i < 4 && i < (int)sum->exc_bt_info.depth && o < (int)sizeof text - 12; i++)
            o += snprintf(text + o, sizeof text - o, ",0x%lx", (unsigned long)sum->exc_bt_info.bt[i]);
        if (r != ESP_RST_PANIC) esp_core_dump_image_erase();
    }
    free(sum);
    ESP_LOGI(TAG, "boot: %s", text);
    net_set_boot(text);
}

static void log_memory(void)
{
    static const char *const TASKS[] = {"main", "net", "audio", "wifi", "console", "nimble_host", "tiT", "esp_timer"};
    char stacks[200] = "";
    size_t o = 0;
    for (size_t i = 0; i < sizeof TASKS / sizeof TASKS[0] && o < sizeof stacks - 24; i++) {
        TaskHandle_t t = xTaskGetHandle(TASKS[i]);
        if (t) o += snprintf(stacks + o, sizeof stacks - o, " %s %u", TASKS[i], (unsigned)uxTaskGetStackHighWaterMark(t));
    }
    ESP_LOGI(TAG, "mem: internal free %u KB, lowest %u KB, largest block %u KB | psram free %u KB, lowest %u KB | stack free (bytes):%s",
             (unsigned)(heap_caps_get_free_size(MALLOC_CAP_INTERNAL) / 1024), (unsigned)(heap_caps_get_minimum_free_size(MALLOC_CAP_INTERNAL) / 1024),
             (unsigned)(heap_caps_get_largest_free_block(MALLOC_CAP_INTERNAL) / 1024), (unsigned)(heap_caps_get_free_size(MALLOC_CAP_SPIRAM) / 1024),
             (unsigned)(heap_caps_get_minimum_free_size(MALLOC_CAP_SPIRAM) / 1024), stacks);
}

#define FACE_DOWN_MG (-800)
#define FLIP_HOLD_S 2

void app_main(void)
{
    board_power_latch();
    esp_pm_configure(&(esp_pm_config_t){.max_freq_mhz = 240, .min_freq_mhz = 80, .light_sleep_enable = false});
    esp_err_t err = nvs_flash_init();
    if (err == ESP_ERR_NVS_NO_FREE_PAGES || err == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        nvs_flash_erase();
        nvs_flash_init();
    }
    ota_init();
    sensors_init();
    clock_init();
    if (!epd_init()) ESP_LOGE(TAG, "display init failed");
    sdcard_init();
    touch_init();
    buttons_init();
    buzzer_init(PIN_BUZZER);
    ui_init(&(ui_actions_t){toggle_recording, restart, power_off, net_set_todo, apply_settings, net_test_hub, net_forget_wifi, net_install_update, net_check_updates, net_cancel_update});
    settings_load(&settings);
    buzzer_set_sound(settings.sound, BUZZ_HZ[settings.buzz_pitch]);
    net_set_home(settings.home);
    net_set_sound(settings.sound);
    report_boot();
    net_set_night(settings.night_on, settings.night_start, settings.night_end);
    ui_boot_step(2);
    net_init();
    audio_init(net_enqueue);
    ui_boot_step(3);
    if (!ble_init()) ESP_LOGE(TAG, "bluetooth not started");
    ui_boot_step(4);
    audio_set_bitrate(settings.bitrate_kbps * 1000);
    ui_set_settings(&settings);
    audio_set_recording(recording);
    unsigned net_version = 0;
    EXT_RAM_BSS_ATTR static wifi_status_t net_wifi;
    bool net_hub = false;

    int down_s = 0, up_s = 0, shown_passkey = -1;
    esp_pm_lock_handle_t ui_fast;
    ESP_ERROR_CHECK(esp_pm_lock_create(ESP_PM_CPU_FREQ_MAX, 0, "ui", &ui_fast));
    bool ui_held = false;
    for (int n = 0;; n++) {
        bool busy = lv_display_get_inactive_time(NULL) < 10000 || esp_log_timestamp() - last_button_ms < 10000;
        if (busy != ui_held) {
            ui_held = busy;
            if (busy) esp_pm_lock_acquire(ui_fast);
            else esp_pm_lock_release(ui_fast);
        }
        ui_tick();
        buttons_poll(on_button);
        if (gpio_get_level(PIN_MOTION_INT) == 0 && sensors_double_tap()) {
            ESP_LOGI(TAG, "double tap on the back");
            do_action(settings.buttons[SLOT_BACK_TAP]);
        }
        vTaskDelay(pdMS_TO_TICKS(50));
        int passkey = ble_passkey();
        if (passkey != shown_passkey) {
            shown_passkey = passkey;
            ui_show_passkey(passkey);
        }
        if (n % 20) continue;

        sensors_t s = {0};
        sensors_read(&s);
        bool face_down = s.imu_ok && s.az_mg < FACE_DOWN_MG;
        down_s = face_down ? down_s + 1 : 0;
        up_s = face_down ? 0 : up_s + 1;
        if (update_pause) {}
        else if (settings.flip_stop && recording && down_s == FLIP_HOLD_S) set_recording(false);
        else if (settings.flip_stop && !recording && up_s == FLIP_HOLD_S) set_recording(true);
        ui_set_sensors(&s);
        net_set_telemetry(s.soc_pct, s.voltage_mv, s.current_ma, recording, s.usb);
        net_set_climate(s.climate_ok, s.room_c10, s.humidity);
        EXT_RAM_BSS_ATTR static net_state_t ns;
        net_get_state(&ns);
        if ((ns.update_phase != UPDATE_OFF) != update_pause) {
            update_pause = !update_pause;
            ESP_LOGI(TAG, "update mode %s", update_pause ? "on" : "off");
            if (update_pause) {
                resume_after_update = recording;
                if (recording) set_recording(false);
            }
            else if (resume_after_update) set_recording(true);
        }
        if (ns.night_from_hub && (ns.night_on != settings.night_on || ns.night_start != settings.night_start || ns.night_end != settings.night_end)) {
            settings.night_on = ns.night_on;
            if (ns.night_on) {
                settings.night_start = ns.night_start;
                settings.night_end = ns.night_end;
            }
            settings_save(&settings);
            ui_set_settings(&settings);
        }
        if (n % 1200 == 0) night_check();
        if (ns.home_from_hub && ns.home != settings.home) {
            settings.home = ns.home;
            settings_save(&settings);
            ui_set_settings(&settings);
        }
        if (ns.sound_from_hub && ns.sound != settings.sound) {
            settings.sound = ns.sound;
            settings_save(&settings);
            buzzer_set_sound(settings.sound, BUZZ_HZ[settings.buzz_pitch]);
            ui_set_settings(&settings);
        }
        if (ns.version != net_version || memcmp(&ns.wifi, &net_wifi, sizeof net_wifi) || ns.hub != net_hub) {
            net_version = ns.version;
            net_wifi = ns.wifi;
            net_hub = ns.hub;
            ui_set_net(&ns);
        }
        if (n % 6000 == 0) log_memory();
        if (n % 600 == 0) ESP_LOGI(TAG, "batt %d%% %dmV %dmA avg %dmA rem %d/%d design %d mAh | vbus %d mV usb %d charging %d | acc x %d y %d z %d mg | %s | heap int %u KB min %u KB",
                 s.soc_pct, s.voltage_mv, s.current_ma, s.avg_current_ma, s.remaining_mah, s.full_mah,
                 s.design_mah, s.vbus_mv, s.usb, s.charging, s.ax_mg, s.ay_mg, s.az_mg, recording ? "REC" : "MUTED",
                 (unsigned)(heap_caps_get_free_size(MALLOC_CAP_INTERNAL) / 1024),
                 (unsigned)(heap_caps_get_minimum_free_size(MALLOC_CAP_INTERNAL) / 1024));
    }
}
