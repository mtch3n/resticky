#include "settings.h"
#include "nvs.h"

void settings_load(settings_t *out)
{
    *out = (settings_t){
        .bitrate_kbps = 16,
        .flip_stop = true,
        .buttons = {ACT_SLEEP, ACT_MARK, ACT_PREV, ACT_HOME, ACT_NEXT, ACT_MUTE, ACT_MARK},
        .sound = SOUND_BEEP,
        .home = HOME_APPS,
    };
    nvs_handle_t h;
    if (nvs_open("settings", NVS_READONLY, &h) != ESP_OK) return;
    int32_t kbps;
    uint8_t flip;
    unsigned char buttons[SLOT_COUNT];
    size_t len = sizeof buttons;
    if (nvs_get_i32(h, "kbps", &kbps) == ESP_OK) out->bitrate_kbps = kbps;
    if (nvs_get_u8(h, "flip", &flip) == ESP_OK) out->flip_stop = flip;
    uint8_t sound, home;
    if (nvs_get_u8(h, "home", &home) == ESP_OK && home < HOME_COUNT) out->home = home;
    if (nvs_get_u8(h, "sound", &sound) == ESP_OK && sound < SOUND_COUNT) out->sound = sound;
    if (nvs_get_blob(h, "buttons", buttons, &len) == ESP_OK && len == sizeof buttons) {
        for (int i = 0; i < SLOT_COUNT; i++)
            if (buttons[i] < ACT_COUNT) out->buttons[i] = buttons[i];
    }
    nvs_close(h);
}

void settings_save(const settings_t *s)
{
    nvs_handle_t h;
    if (nvs_open("settings", NVS_READWRITE, &h) != ESP_OK) return;
    nvs_set_i32(h, "kbps", s->bitrate_kbps);
    nvs_set_u8(h, "flip", s->flip_stop);
    nvs_set_u8(h, "sound", s->sound);
    nvs_set_u8(h, "home", s->home);
    nvs_set_blob(h, "buttons", s->buttons, sizeof s->buttons);
    nvs_commit(h);
    nvs_close(h);
}
