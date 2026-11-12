#pragma once

typedef enum { BTN_POWER, BTN_UP, BTN_DOWN, BTN_COUNT } button_t;
typedef enum { BTN_PRESS, BTN_HOLD, BTN_POWER_OFF, BTN_DOUBLE } button_event_t;
typedef void (*button_cb)(button_t button, button_event_t event);

void buttons_init(void);
void buttons_poll(button_cb cb);
void buttons_disarm(void);
