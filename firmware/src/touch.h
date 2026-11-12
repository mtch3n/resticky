#pragma once
#include <stdbool.h>

bool touch_init(void);
bool touch_read(int *x, int *y);
void touch_sleep(void);
void touch_wake(void);
void touch_debug(void);
