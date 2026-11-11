#include "settings.h"
#include "nvs.h"

const int BUZZ_HZ[BUZZ_PITCHES] = {100, 175, 300, 500};

void settings_load(settings_t *out)
{
    *out = (settings_t){
        .bitrate_kbps = 16,
        .flip_stop = true,
        .buttons = {ACT_SLEEP, ACT_MARK, ACT_PREV, ACT_HOME, ACT_NEXT, ACT_MUTE, ACT_MARK},
        .sound = SOUND_BEEP,
        .buzz_hz = 175,
        .night_start = 22 * 60,
        .night_end = 7 * 60,
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
    uint8_t sound, home, night;
    uint16_t start, end;
    if (nvs_get_u8(h, "home", &home) == ESP_OK && home < HOME_COUNT) out->home = home;
    if (nvs_get_u8(h, "sound", &sound) == ESP_OK && sound < SOUND_COUNT) out->sound = sound;
    uint16_t hz;
    if (nvs_get_u16(h, "buzz_hz", &hz) == ESP_OK && hz >= BUZZ_HZ_MIN && hz <= BUZZ_HZ_MAX) out->buzz_hz = hz;
    if (nvs_get_u8(h, "night", &night) == ESP_OK) out->night_on = night;
    uint8_t muted;
    if (nvs_get_u8(h, "muted", &muted) == ESP_OK) out->muted = muted;
    if (nvs_get_u16(h, "night_start", &start) == ESP_OK && start < 1440) out->night_start = start;
    if (nvs_get_u16(h, "night_end", &end) == ESP_OK && end < 1440) out->night_end = end;
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
    nvs_set_u16(h, "buzz_hz", s->buzz_hz);
    nvs_set_u8(h, "night", s->night_on);
    nvs_set_u16(h, "night_start", s->night_start);
    nvs_set_u16(h, "night_end", s->night_end);
    nvs_set_u8(h, "home", s->home);
    nvs_set_u8(h, "muted", s->muted);

    nvs_set_blob(h, "buttons", s->buttons, sizeof s->buttons);
    nvs_commit(h);
    nvs_close(h);
}
