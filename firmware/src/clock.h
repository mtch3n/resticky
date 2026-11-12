#pragma once
#include <stdbool.h>
#include <stdint.h>

void clock_init(void);
void clock_set_offset(int utc_offset_min);
void clock_synced(void);
uint32_t clock_boot_id(void);
int64_t clock_boot_unix_ms(void);
