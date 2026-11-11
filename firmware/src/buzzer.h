#pragma once
#include <stdbool.h>

void buzzer_init(int gpio);
void buzzer_set_sound(int sound);
void buzzer_click(void);
void buzzer_mark(void);
