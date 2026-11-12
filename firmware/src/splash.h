#pragma once
#include <stdint.h>

#define SPLASH_FRAMES 5
#define SPLASH_BOOT_STEPS 4
#define SPLASH_OFF 4
#define SPLASH_SIZE (800 / 8 * 480)

extern const uint8_t splash_frames[SPLASH_FRAMES][SPLASH_SIZE];
