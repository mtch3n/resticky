#pragma once
#include "audio.h"
#include <stdbool.h>
#include <stdint.h>

typedef struct {
    uint32_t chunks;
    uint32_t bytes;
    uint32_t dropped;
    uint32_t write_errors;
    uint32_t corrupt;
    const char *state;
} spool_stats_t;

bool spool_init(void);
bool spool_put(const audio_chunk_t *chunk);
audio_chunk_t *spool_oldest(uint32_t *id);
void spool_remove(uint32_t id);
void spool_stats(spool_stats_t *out);
