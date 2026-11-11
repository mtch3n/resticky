#pragma once
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define OTA_VERSION_LEN 32

typedef struct {
    char version[OTA_VERSION_LEN];
    size_t size;
    char url[320];
} ota_info_t;

typedef void (*ota_progress_cb)(int percent);

void ota_init(void);
void ota_note_audio(void);
void ota_note_hub(void);
void ota_tick(void);
bool ota_is_newer(const char *version);
bool ota_install(const char *base_url, const ota_info_t *info, ota_progress_cb progress, const volatile bool *cancel);
bool ota_stream_begin(const char *version, size_t size);
bool ota_stream_write(size_t offset, const uint8_t *data, size_t len);
bool ota_stream_end(void);
void ota_stream_abort(void);
size_t ota_stream_done(void);
int ota_stream_percent(void);
