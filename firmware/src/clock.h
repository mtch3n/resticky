#pragma once
#include <stdbool.h>

void clock_init(void);
void clock_set_offset(int utc_offset_min);
void clock_synced(void);
