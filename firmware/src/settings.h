#pragma once
#include <stdbool.h>

typedef enum { ACT_NOTHING, ACT_SLEEP, ACT_MUTE, ACT_PREV, ACT_NEXT, ACT_HOME, ACT_MARK, ACT_COUNT } action_t;
typedef enum { SLOT_POWER_PRESS, SLOT_POWER_DOUBLE, SLOT_UP_PRESS, SLOT_UP_HOLD, SLOT_DOWN_PRESS, SLOT_DOWN_HOLD, SLOT_BACK_TAP, SLOT_COUNT } button_slot_t;

typedef enum { HOME_APPS, HOME_CLOCK, HOME_WEATHER, HOME_RECORDER, HOME_TODO, HOME_COUNT } home_t;

typedef struct {
    int bitrate_kbps;
    bool flip_stop;
    unsigned char buttons[SLOT_COUNT];
    bool beep;
    unsigned char home;
} settings_t;

void settings_load(settings_t *out);
void settings_save(const settings_t *s);
