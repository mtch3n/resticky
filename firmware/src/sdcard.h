#pragma once
#include <stdbool.h>
#include <stdint.h>

typedef struct {
    bool present, mounted;
    char name[8];
    uint64_t total, free;
    int speed_khz;
} sdcard_info_t;

void sdcard_init(void);
void sdcard_info(sdcard_info_t *out);
bool sdcard_present(void);
bool sdcard_format(void (*progress)(int percent));
