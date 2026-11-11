#include "audio.h"
#include "driver/gpio.h"
#include "driver/i2s_pdm.h"
#include "encoder/impl/esp_opus_enc.h"
#include "esp_attr.h"
#include "esp_heap_caps.h"
#include "esp_log.h"
#include "esp_pm.h"
#include "esp_random.h"
#include "esp_timer.h"
#include "mbedtls/base64.h"
#include "freertos/FreeRTOS.h"
#include "freertos/idf_additions.h"
#include "freertos/task.h"
#include "dsps_biquad.h"
#include "dsps_biquad_gen.h"
#include "dsps_dotprod.h"
#include "vad.h"
#include <math.h>
#include <string.h>

#define PIN_PDM_CLK 19
#define PIN_PDM_DATA 20
#define PIN_MIC_EN 38
#define SAMPLE_RATE 16000
#define FRAME_SAMPLES 320
#define CHUNK_MS (AUDIO_CHUNK_FRAMES * 20)
#define CHUNK_CAP (CHUNK_MS / 20 * 48)
#define GAIN 4
#define DMA_DESCS 16
#define DMA_FRAMES 1000
#define PREROLL_FRAMES 100
#define HANG_FRAMES 750
#define MIC_START_MS 60
#define HPF_HZ 80.0f
#define DB_FLOOR 96
#define FULL_SCALE 32768.0f

typedef struct {
    uint16_t hist[DB_FLOOR + 1];
    float sum_sq, peak;
    uint32_t samples, clipped;
} meter_t;

static const char *TAG = "audio";
static i2s_chan_handle_t rx;
static void *enc;
static audio_chunk_cb chunk_cb;
static volatile bool want_recording;
static bool mic_on = true;
static volatile int want_bitrate = 16000;
static volatile uint32_t overflows;
static audio_load_t load;
static volatile uint32_t cur_session, cur_offset_ms;
static bool vad_ok;
static float hpf_coef[5], hpf_w[2];
EXT_RAM_BSS_ATTR static float x[FRAME_SAMPLES], y[FRAME_SAMPLES];

static void condition(int16_t *pcm, meter_t *m)
{
    for (int i = 0; i < FRAME_SAMPLES; i++) x[i] = pcm[i];
    dsps_biquad_f32_ansi(x, y, FRAME_SAMPLES, hpf_coef, hpf_w);
    float sum_sq = 0;
    dsps_dotprod_f32_ansi(y, y, &sum_sq, FRAME_SAMPLES);
    sum_sq *= GAIN * GAIN;
    for (int i = 0; i < FRAME_SAMPLES; i++) {
        float v = y[i] * GAIN, a = fabsf(v);
        if (a > m->peak) m->peak = a;
        if (v > 32767.0f || v < -32768.0f) {
            m->clipped++;
            v = v > 0 ? 32767.0f : -32768.0f;
        }
        pcm[i] = (int16_t)v;
    }
    float db = 10.0f * log10f(sum_sq / FRAME_SAMPLES / (FULL_SCALE * FULL_SCALE) + 1e-12f);
    int bin = db <= -DB_FLOOR ? DB_FLOOR : db >= 0 ? 0 : (int)-db;
    m->hist[bin]++;
    m->sum_sq += sum_sq;
    m->samples += FRAME_SAMPLES;
}

static int8_t percentile_db(const meter_t *m, int pct)
{
    uint32_t frames = 0, seen = 0;
    for (int b = 0; b <= DB_FLOOR; b++) frames += m->hist[b];
    for (int b = DB_FLOOR; b >= 0; b--) {
        seen += m->hist[b];
        if (seen * 100 >= frames * pct) return -b;
    }
    return 0;
}

static void close_meter(audio_chunk_t *c, meter_t *m)
{
    if (m->samples) {
        c->level = (audio_level_t){
            .peak_db = (int8_t)fminf(0, fmaxf(-DB_FLOOR, 20.0f * log10f(m->peak / FULL_SCALE + 1e-6f))),
            .rms_db = (int8_t)fminf(0, fmaxf(-DB_FLOOR, 10.0f * log10f(m->sum_sq / m->samples / (FULL_SCALE * FULL_SCALE) + 1e-12f))),
            .noise_db = percentile_db(m, 10),
            .speech_db = percentile_db(m, 95),
            .clipped = m->clipped > UINT16_MAX ? UINT16_MAX : m->clipped,
        };
        c->has_level = true;
        ESP_LOGI(TAG, "level session %08lx seq %lu: peak %d dBFS, rms %d, noise %d, speech %d, clipped %u", c->session, c->seq,
                 c->level.peak_db, c->level.rms_db, c->level.noise_db, c->level.speech_db, c->level.clipped);
    }
    memset(m, 0, sizeof *m);
}

static bool IRAM_ATTR on_overflow(i2s_chan_handle_t h, i2s_event_data_t *e, void *ctx)
{
    overflows++;
    return false;
}

static audio_chunk_t *chunk_new(uint32_t session, uint32_t seq)
{
    audio_chunk_t *c = heap_caps_calloc(1, sizeof *c, MALLOC_CAP_SPIRAM);
    if (c) c->data = heap_caps_malloc(CHUNK_CAP, MALLOC_CAP_SPIRAM);
    if (!c || !c->data) {
        free(c);
        ESP_LOGE(TAG, "no memory for a new chunk");
        return NULL;
    }
    c->session = session;
    c->seq = seq;
    c->start_ms = esp_timer_get_time() / 1000;
    c->has_vad = vad_ok;
    return c;
}

static void mark(uint8_t *mask, int i)
{
    mask[i >> 3] |= 1 << (i & 7);
}

static void log_vad(const audio_chunk_t *c)
{
    char speech[AUDIO_MASK_BYTES * 4 / 3 + 4], keep[sizeof speech];
    size_t n = (c->duration_ms / 20 + 7) / 8, o;
    if (!c->has_vad || mbedtls_base64_encode((unsigned char *)speech, sizeof speech, &o, c->vad.speech, n) ||
        mbedtls_base64_encode((unsigned char *)keep, sizeof keep, &o, c->vad.keep, n))
        return;
    ESP_LOGI(TAG, "vad session %08lx seq %lu speech %s keep %s keep_prev %lu", c->session, c->seq, speech, keep, c->vad.keep_prev);
}

static void audio_task(void *arg)
{
    esp_pm_lock_handle_t fast;
    ESP_ERROR_CHECK(esp_pm_lock_create(ESP_PM_CPU_FREQ_MAX, 0, "audio", &fast));
    bool held = false;
    int16_t pcm[FRAME_SAMPLES];
    uint8_t packet[400];
    audio_chunk_t *chunk = NULL;
    uint32_t session = 0, seq = 0;
    int bitrate = 16000;
    uint32_t frames = 0, enc_fail = 0;
    int64_t last_report = 0;
    int quiet = HANG_FRAMES;
    uint32_t vad_frames = 0, speech_frames = 0, kept_frames = 0;
    int64_t vad_us = 0, vad_max_us = 0, dsp_us = 0, enc_us = 0, enc_max_us = 0;
    static meter_t meter;
    for (;;) {
        int64_t now = esp_timer_get_time() / 1000;
        if (now - last_report >= 60000) {
            ESP_LOGI(TAG, "frames %lu, encode failures %lu, overflows %lu, recording %d, session %08lx", frames, enc_fail,
                     overflows, want_recording, session);
            if (vad_frames)
                ESP_LOGI(TAG, "vad: cpu %lld.%02lld%%, max %lld us, speech %lu%%, keep %lu%% of %lu frames", vad_us / (now - last_report) / 10,
                         vad_us / (now - last_report) % 10 * 10, vad_max_us, speech_frames * 100 / vad_frames,
                         kept_frames * 100 / vad_frames, vad_frames);
            int64_t span_us = (now - last_report) * 1000;
            ESP_LOGI(TAG, "cpu at %d MHz: filter %lld.%02lld%%, opus %lld.%02lld%% (max %lld us), vad %lld.%02lld%%",
                     CONFIG_ESP_DEFAULT_CPU_FREQ_MHZ, dsp_us * 100 / span_us, dsp_us * 10000 / span_us % 100, enc_us * 100 / span_us,
                     enc_us * 10000 / span_us % 100, enc_max_us, vad_us * 100 / span_us, vad_us * 10000 / span_us % 100);
            load = (audio_load_t){.filter_pm = dsp_us * 1000 / span_us, .opus_pm = enc_us * 1000 / span_us,
                                  .vad_pm = vad_us * 1000 / span_us, .opus_max_us = enc_max_us, .overflows = overflows};
            vad_frames = speech_frames = kept_frames = 0;
            vad_us = vad_max_us = dsp_us = enc_us = enc_max_us = 0;
            last_report = now;
        }
        if (want_bitrate != bitrate) {
            bitrate = want_bitrate;
            esp_opus_enc_set_bitrate(enc, bitrate);
            ESP_LOGI(TAG, "opus bitrate %d", bitrate);
        }
        if (held) {
            esp_pm_lock_release(fast);
            held = false;
        }
        if (!mic_on && !want_recording) {
            vTaskDelay(pdMS_TO_TICKS(200));
            continue;
        }
        if (!mic_on) {
            gpio_set_level(PIN_MIC_EN, 1);
            vTaskDelay(pdMS_TO_TICKS(MIC_START_MS));
            i2s_channel_enable(rx);
            mic_on = true;
            ESP_LOGI(TAG, "mic on");
        }
        size_t got = 0;
        esp_err_t rd = i2s_channel_read(rx, pcm, sizeof pcm, &got, 1000);
        if (rd != ESP_OK) {
            ESP_LOGW(TAG, "i2s read: %s (got %u)", esp_err_to_name(rd), (unsigned)got);
            continue;
        }
        esp_pm_lock_acquire(fast);
        held = true;
        frames++;
        if (!want_recording) {
            if (session) {
                if (!chunk) chunk = chunk_new(session, seq);
                if (!chunk) {
                    session = 0;
                    continue;
                }
                chunk->final = true;
                close_meter(chunk, &meter);
                chunk_cb(chunk);
                ESP_LOGI(TAG, "session %08lx ended after %lu chunks", session, seq + 1);
                chunk = NULL;
                session = 0;
            }
            if (!session) {
                i2s_channel_disable(rx);
                gpio_set_level(PIN_MIC_EN, 0);
                mic_on = false;
                ESP_LOGI(TAG, "mic off");
            }
            continue;
        }
        if (!session) {
            session = esp_random();
            seq = 0;
            quiet = HANG_FRAMES;
        }
        if (!chunk) chunk = chunk_new(session, seq);
        if (!chunk) continue;
        int64_t t0 = esp_timer_get_time();
        condition(pcm, &meter);
        int64_t t1 = esp_timer_get_time();
        esp_audio_enc_in_frame_t in = {.buffer = (uint8_t *)pcm, .len = sizeof pcm};
        esp_audio_enc_out_frame_t out = {.buffer = packet, .len = sizeof packet};
        esp_audio_err_t er = esp_opus_enc_process(enc, &in, &out);
        int64_t t2 = esp_timer_get_time();
        dsp_us += t1 - t0;
        enc_us += t2 - t1;
        if (t2 - t1 > enc_max_us) enc_max_us = t2 - t1;
        if (er != ESP_AUDIO_ERR_OK) {
            enc_fail++;
            continue;
        }
        if (chunk->len + 2 + out.encoded_bytes <= CHUNK_CAP) {
            chunk->data[chunk->len++] = out.encoded_bytes & 0xFF;
            chunk->data[chunk->len++] = out.encoded_bytes >> 8;
            memcpy(chunk->data + chunk->len, packet, out.encoded_bytes);
            chunk->len += out.encoded_bytes;
        }
        if (chunk->has_vad) {
            int i = chunk->duration_ms / 20;
            int64_t t = esp_timer_get_time();
            bool speech = vad_frame(pcm, FRAME_SAMPLES);
            t = esp_timer_get_time() - t;
            vad_us += t;
            if (t > vad_max_us) vad_max_us = t;
            vad_frames++;
            if (speech) {
                speech_frames++;
                mark(chunk->vad.speech, i);
                if (quiet >= HANG_FRAMES) {
                    for (int j = i > PREROLL_FRAMES ? i - PREROLL_FRAMES : 0; j < i; j++) mark(chunk->vad.keep, j);
                    if (i < PREROLL_FRAMES && seq > 0) chunk->vad.keep_prev = PREROLL_FRAMES - i;
                }
                quiet = 0;
            } else if (quiet < HANG_FRAMES) {
                quiet++;
            }
            if (quiet < HANG_FRAMES) {
                kept_frames++;
                mark(chunk->vad.keep, i);
            }
        }
        chunk->duration_ms += 20;
        cur_session = session;
        cur_offset_ms = seq * CHUNK_MS + chunk->duration_ms;
        if (chunk->duration_ms >= CHUNK_MS) {
            log_vad(chunk);
            close_meter(chunk, &meter);
            chunk_cb(chunk);
            chunk = NULL;
            seq++;
        }
    }
}

void audio_init(audio_chunk_cb on_chunk)
{
    chunk_cb = on_chunk;
    gpio_config_t en = {.pin_bit_mask = 1ULL << PIN_MIC_EN, .mode = GPIO_MODE_OUTPUT};
    gpio_config(&en);
    gpio_set_level(PIN_MIC_EN, 1);

    i2s_chan_config_t chan = I2S_CHANNEL_DEFAULT_CONFIG(I2S_NUM_0, I2S_ROLE_MASTER);
    chan.dma_desc_num = DMA_DESCS;
    chan.dma_frame_num = DMA_FRAMES;
    ESP_ERROR_CHECK(i2s_new_channel(&chan, NULL, &rx));
    i2s_pdm_rx_config_t cfg = {
        .clk_cfg = I2S_PDM_RX_CLK_DEFAULT_CONFIG(SAMPLE_RATE),
        .slot_cfg = I2S_PDM_RX_SLOT_DEFAULT_CONFIG(I2S_DATA_BIT_WIDTH_16BIT, I2S_SLOT_MODE_MONO),
        .gpio_cfg = {.clk = PIN_PDM_CLK, .din = PIN_PDM_DATA},
    };
    ESP_ERROR_CHECK(i2s_channel_init_pdm_rx_mode(rx, &cfg));
    i2s_event_callbacks_t cbs = {.on_recv_q_ovf = on_overflow};
    i2s_channel_register_event_callback(rx, &cbs, NULL);
    ESP_ERROR_CHECK(i2s_channel_enable(rx));

    esp_opus_enc_config_t oc = ESP_OPUS_ENC_CONFIG_DEFAULT();
    oc.sample_rate = SAMPLE_RATE;
    oc.channel = 1;
    oc.bitrate = 16000;
    oc.enable_dtx = true;
    oc.enable_vbr = true;
    dsps_biquad_gen_hpf_f32(hpf_coef, HPF_HZ / SAMPLE_RATE, 0.707f);
    ESP_ERROR_CHECK(esp_opus_enc_open(&oc, sizeof oc, &enc));
    vad_ok = vad_init();
    if (xTaskCreatePinnedToCoreWithCaps(audio_task, "audio", 32 * 1024, NULL, 6, NULL, 1, MALLOC_CAP_SPIRAM) != pdPASS) {
        ESP_LOGE(TAG, "audio task not created");
        return;
    }
    ESP_LOGI(TAG, "mic and opus ready (16 kHz, 16 kbps)");
}

bool audio_position(uint32_t *session, uint32_t *offset_ms)
{
    *session = want_recording ? cur_session : 0;
    *offset_ms = cur_offset_ms;
    return *session != 0;
}

void audio_get_load(audio_load_t *out)
{
    *out = load;
}

void audio_set_recording(bool recording)
{
    want_recording = recording;
}

void audio_set_bitrate(int bits_per_s)
{
    want_bitrate = bits_per_s;
}
