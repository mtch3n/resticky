#pragma once
#include "net.h"
#include "sensors.h"
#include "settings.h"
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

typedef void (*ui_action_cb)(void);

typedef struct {
    ui_action_cb mute_toggle;
    ui_action_cb restart;
    ui_action_cb power_off;
    void (*set_todo)(const char *id, bool done);
    void (*settings_changed)(const settings_t *s);
    ui_action_cb test_hub;
    void (*forget_wifi)(const char *ssid);
    ui_action_cb install_update;
    ui_action_cb check_updates;
    ui_action_cb cancel_update;
    ui_action_cb need_wifi;
} ui_actions_t;

void ui_init(const ui_actions_t *actions);
void ui_boot_step(int step);
void ui_show_off(void);
void ui_show_sleep(void);
void ui_show_passkey(int passkey);
void ui_set_settings(const settings_t *s);
void ui_set_recording(bool recording);
void ui_set_sensors(const sensors_t *s);
void ui_set_net(const net_state_t *n);
void ui_key(action_t action);
void ui_show_mark(bool ok);
void ui_tick(void);
void ui_console(const char *command);
void ui_show_photo(const uint8_t *bits, size_t len);
