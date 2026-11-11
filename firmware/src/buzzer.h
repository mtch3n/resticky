#pragma once
#include <stdbool.h>

void buzzer_init(int gpio);
void buzzer_enable(bool on);
void buzzer_click(void);
void buzzer_mark(void);
