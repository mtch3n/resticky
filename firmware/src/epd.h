#pragma once
#include <stdbool.h>
#include <stdint.h>

#define EPD_W 800
#define EPD_H 480
#define EPD_FB_SIZE (EPD_W / 8 * EPD_H)

bool epd_init(void);
void epd_refresh(const uint8_t *fb, const uint8_t *shown, bool full);
