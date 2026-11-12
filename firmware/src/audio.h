#pragma once
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define AUDIO_CHUNK_FRAMES 1500
#define AUDIO_MASK_BYTES (AUDIO_CHUNK_FRAMES / 8 + 1)

typedef struct {
    uint8_t speech[AUDIO_MASK_BYTES];
    uint8_t keep[AUDIO_MASK_BYTES];
    uint32_t keep_prev;
} audio_vad_t;

typedef struct {
    int8_t peak_db, rms_db, noise_db, speech_db;
    uint16_t clipped;
} audio_level_t;

typedef struct {
    uint32_t session;
    uint32_t seq;
    bool final;
    int64_t start_ms;
    uint32_t duration_ms;
    uint32_t boot_id;
    int64_t boot_unix_ms;
    size_t len;
    uint8_t *data;
    bool has_vad;
    audio_vad_t vad;
    bool has_level;
    audio_level_t level;
} audio_chunk_t;

typedef void (*audio_chunk_cb)(audio_chunk_t *chunk);

void audio_init(audio_chunk_cb on_chunk);
void audio_set_recording(bool recording);
bool audio_stop(uint32_t timeout_ms);
void audio_count_lost(void);
void audio_set_bitrate(int bits_per_s);

typedef struct {
    int filter_pm, opus_pm, vad_pm, opus_max_us, keep_pm;
    uint32_t overflows, early_chunks, lost_chunks;
} audio_load_t;

void audio_get_load(audio_load_t *out);
bool audio_position(uint32_t *session, uint32_t *offset_ms);
