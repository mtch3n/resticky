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
#include "freertos/event_groups.h"
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
#define PREROLL_FRAMES 25
#define HANG_FRAMES 75
#define MAX_PACKET 400
#define IDLE_BIT BIT0
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
static volatile uint32_t overflows, lost_chunks;
static EventGroupHandle_t events;
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
        lost_chunks++;
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
    ESP_LOGI(TAG, "vad session %08lx seq %lu speech %s keep %s", c->session, c->seq, speech, keep);
}

typedef struct {
    int16_t pcm[FRAME_SAMPLES];
    bool speech;
} slot_t;

typedef struct {
    audio_chunk_t *chunk;
    uint32_t session, seq, session_ms;
    int32_t in, last_speech;
    int ring_n, ring_head;
    uint32_t frames, kept, speech, enc_fail, early;
    int64_t enc_us, enc_max_us;
} rec_t;

EXT_RAM_BSS_ATTR static slot_t ring[PREROLL_FRAMES];
EXT_RAM_BSS_ATTR static slot_t out_slot;
static uint8_t packet[MAX_PACKET];
static meter_t meter;

static void close_chunk(rec_t *r, bool final)
{
    if (!r->chunk) r->chunk = chunk_new(r->session, r->seq);
    if (!r->chunk) return;
    r->chunk->final = final;
    log_vad(r->chunk);
    close_meter(r->chunk, &meter);
    r->session_ms += r->chunk->duration_ms;
    chunk_cb(r->chunk);
    r->chunk = NULL;
    r->seq++;
}

static void emit(rec_t *r, const slot_t *f, bool keep)
{
    int n = 0;
    if (keep) {
        int64_t t = esp_timer_get_time();
        esp_audio_enc_in_frame_t in = {.buffer = (uint8_t *)f->pcm, .len = sizeof f->pcm};
        esp_audio_enc_out_frame_t out = {.buffer = packet, .len = sizeof packet};
        esp_audio_err_t er = esp_opus_enc_process(enc, &in, &out);
        t = esp_timer_get_time() - t;
        r->enc_us += t;
        if (t > r->enc_max_us) r->enc_max_us = t;
        if (er == ESP_AUDIO_ERR_OK) n = out.encoded_bytes;
        else r->enc_fail++;
    }
    if (r->chunk && r->chunk->len + 2 + n > CHUNK_CAP) {
        r->early++;
        ESP_LOGW(TAG, "chunk session %08lx seq %lu full after %lu ms, closed early", r->session, r->seq, r->chunk->duration_ms);
        close_chunk(r, false);
    }
    if (!r->chunk) r->chunk = chunk_new(r->session, r->seq);
    if (!r->chunk) return;
    audio_chunk_t *c = r->chunk;
    int i = c->duration_ms / 20;
    c->data[c->len++] = n & 0xFF;
    c->data[c->len++] = n >> 8;
    memcpy(c->data + c->len, packet, n);
    c->len += n;
    r->frames++;
    if (f->speech) {
        r->speech++;
        mark(c->vad.speech, i);
    }
    if (keep) {
        r->kept++;
        mark(c->vad.keep, i);
    }
    c->duration_ms += 20;
    if (c->duration_ms >= CHUNK_MS) close_chunk(r, false);
}

static bool keep_frame(const rec_t *r, int32_t index)
{
    return !vad_ok || r->last_speech >= index - HANG_FRAMES;
}

static void drain(rec_t *r)
{
    for (; r->ring_n > 0; r->ring_n--) {
        int32_t index = r->in - r->ring_n;
        emit(r, &ring[r->ring_head], keep_frame(r, index));
        r->ring_head = (r->ring_head + 1) % PREROLL_FRAMES;
    }
}

static void record(rec_t *r, int16_t *pcm, int64_t *vad_us, int64_t *vad_max_us)
{
    condition(pcm, &meter);
    bool speech = true;
    if (vad_ok) {
        int64_t t = esp_timer_get_time();
        speech = vad_frame(pcm, FRAME_SAMPLES);
        t = esp_timer_get_time() - t;
        *vad_us += t;
        if (t > *vad_max_us) *vad_max_us = t;
    }
    if (speech) r->last_speech = r->in;
    int slot = (r->ring_head + r->ring_n) % PREROLL_FRAMES;
    if (r->ring_n == PREROLL_FRAMES) {
        out_slot = ring[r->ring_head];
        r->ring_head = (r->ring_head + 1) % PREROLL_FRAMES;
        r->ring_n--;
        emit(r, &out_slot, keep_frame(r, r->in - PREROLL_FRAMES));
    }
    memcpy(ring[slot].pcm, pcm, sizeof ring[slot].pcm);
    ring[slot].speech = speech;
    r->ring_n++;
    r->in++;
    cur_session = r->session;
    cur_offset_ms = r->session_ms + (r->chunk ? r->chunk->duration_ms : 0) + r->ring_n * 20;
}

static void audio_task(void *arg)
{
    esp_pm_lock_handle_t fast;
    ESP_ERROR_CHECK(esp_pm_lock_create(ESP_PM_CPU_FREQ_MAX, 0, "audio", &fast));
    int16_t pcm[FRAME_SAMPLES];
    static rec_t r;
    int bitrate = 16000;
    int64_t last_report = 0, vad_us = 0, vad_max_us = 0, dsp_us = 0;
    for (;;) {
        int64_t now = esp_timer_get_time() / 1000;
        if (now - last_report >= 60000) {
            int64_t span_us = (now - last_report) * 1000;
            ESP_LOGI(TAG, "frames %lu, encoded %lu, speech %lu, encode failures %lu, early chunks %lu, lost chunks %lu, overflows %lu, recording %d, session %08lx",
                     r.frames, r.kept, r.speech, r.enc_fail, r.early, lost_chunks, overflows, want_recording, r.session);
            ESP_LOGI(TAG, "cpu at %d MHz: filter %lld.%02lld%%, opus %lld.%02lld%% (max %lld us), vad %lld.%02lld%% (max %lld us)",
                     CONFIG_ESP_DEFAULT_CPU_FREQ_MHZ, dsp_us * 100 / span_us, dsp_us * 10000 / span_us % 100, r.enc_us * 100 / span_us,
                     r.enc_us * 10000 / span_us % 100, r.enc_max_us, vad_us * 100 / span_us, vad_us * 10000 / span_us % 100, vad_max_us);
            load = (audio_load_t){.filter_pm = dsp_us * 1000 / span_us, .opus_pm = r.enc_us * 1000 / span_us,
                                  .vad_pm = vad_us * 1000 / span_us, .opus_max_us = r.enc_max_us, .overflows = overflows,
                                  .keep_pm = r.frames ? r.kept * 1000 / r.frames : 0, .early_chunks = r.early, .lost_chunks = lost_chunks};
            r.frames = r.kept = r.speech = 0;
            r.enc_us = r.enc_max_us = vad_us = vad_max_us = dsp_us = 0;
            last_report = now;
        }
        if (want_bitrate != bitrate) {
            bitrate = want_bitrate;
            esp_opus_enc_set_bitrate(enc, bitrate);
            ESP_LOGI(TAG, "opus bitrate %d", bitrate);
        }
        if (!mic_on && !want_recording) {
            xEventGroupSetBits(events, IDLE_BIT);
            vTaskDelay(pdMS_TO_TICKS(200));
            continue;
        }
        if (!mic_on) {
            xEventGroupClearBits(events, IDLE_BIT);
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
        if (!want_recording) {
            if (r.session) {
                drain(&r);
                close_chunk(&r, true);
                ESP_LOGI(TAG, "session %08lx ended after %lu chunks", r.session, r.seq);
                r.session = 0;
            }
            i2s_channel_disable(rx);
            gpio_set_level(PIN_MIC_EN, 0);
            mic_on = false;
            ESP_LOGI(TAG, "mic off");
            esp_pm_lock_release(fast);
            continue;
        }
        if (!r.session) {
            r = (rec_t){.session = esp_random(), .last_speech = INT32_MIN / 2, .frames = r.frames, .kept = r.kept, .speech = r.speech,
                        .enc_fail = r.enc_fail, .early = r.early, .enc_us = r.enc_us, .enc_max_us = r.enc_max_us};
            memset(&meter, 0, sizeof meter);
        }
        int64_t t0 = esp_timer_get_time();
        int64_t v0 = vad_us, e0 = r.enc_us;
        record(&r, pcm, &vad_us, &vad_max_us);
        dsp_us += esp_timer_get_time() - t0 - (vad_us - v0) - (r.enc_us - e0);
        esp_pm_lock_release(fast);
    }
}

void audio_init(audio_chunk_cb on_chunk)
{
    chunk_cb = on_chunk;
    events = xEventGroupCreate();
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

bool audio_stop(uint32_t timeout_ms)
{
    want_recording = false;
    bool idle = xEventGroupWaitBits(events, IDLE_BIT, false, true, pdMS_TO_TICKS(timeout_ms)) & IDLE_BIT;
    ESP_LOGI(TAG, "stop: %s", idle ? "final chunk queued, mic off" : "no answer");
    return idle;
}

void audio_count_lost(void)
{
    lost_chunks++;
}

void audio_set_bitrate(int bits_per_s)
{
    want_bitrate = bits_per_s;
}
