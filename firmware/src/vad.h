#pragma once
#include <stdbool.h>
#include <stdint.h>

bool vad_init(void);
bool vad_frame(int16_t *pcm, int samples);
