#pragma once
#include <stdint.h>

#define SPLASH_FRAMES 7
#define SPLASH_BOOT_STEPS 6
#define SPLASH_OFF 6
#define SPLASH_SIZE (800 / 8 * 480)

extern const uint8_t splash_frames[SPLASH_FRAMES][SPLASH_SIZE];
