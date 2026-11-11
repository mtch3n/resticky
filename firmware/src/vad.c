#include "vad.h"
#include "esp_attr.h"
#include "esp_heap_caps.h"
#include "esp_log.h"
#include "esp_vadn_models.h"
#include "model_path.h"
#include <string.h>

#define MAX_CHUNK 1024
#define VAD_MODE VAD_MODE_0
#define VAD_MIN_SPEECH_MS 128
#define VAD_MIN_NOISE_MS 1000

static const char *TAG = "vad";
static const esp_vadn_iface_t *iface;
static model_iface_data_t *model;
static int chunk_samples;
EXT_RAM_BSS_ATTR static int16_t buf[MAX_CHUNK];
static int fill;
static bool speech;

bool vad_init(void)
{
    size_t int0 = heap_caps_get_free_size(MALLOC_CAP_INTERNAL), ps0 = heap_caps_get_free_size(MALLOC_CAP_SPIRAM);
    srmodel_list_t *models = esp_srmodel_init("model");
    char *name = models ? esp_srmodel_filter(models, ESP_VADN_PREFIX, NULL) : NULL;
    if (!name) {
        ESP_LOGE(TAG, "no VADNet model in the model partition");
        return false;
    }
    iface = esp_vadn_handle_from_name(name);
    model = iface ? iface->create(name, VAD_MODE, 1, VAD_MIN_SPEECH_MS, VAD_MIN_NOISE_MS) : NULL;
    if (!model) {
        ESP_LOGE(TAG, "%s not created", name);
        return false;
    }
    chunk_samples = iface->get_samp_chunksize(model);
    if (chunk_samples <= 0 || chunk_samples > MAX_CHUNK) {
        ESP_LOGE(TAG, "%s wants %d samples per call", name, chunk_samples);
        iface->destroy(model);
        model = NULL;
        return false;
    }
    ESP_LOGI(TAG, "%s ready: mode %d, threshold %.2f, min speech %d ms, min noise %d ms, %d samples at %d Hz per call, internal %u bytes, psram %u bytes",
             name, VAD_MODE, iface->get_det_threshold(model), VAD_MIN_SPEECH_MS, VAD_MIN_NOISE_MS, chunk_samples, iface->get_samp_rate(model), (unsigned)(int0 - heap_caps_get_free_size(MALLOC_CAP_INTERNAL)),
             (unsigned)(ps0 - heap_caps_get_free_size(MALLOC_CAP_SPIRAM)));
    return true;
}

bool vad_frame(int16_t *pcm, int samples)
{
    while (samples > 0) {
        int n = chunk_samples - fill < samples ? chunk_samples - fill : samples;
        memcpy(buf + fill, pcm, n * sizeof *pcm);
        fill += n;
        pcm += n;
        samples -= n;
        if (fill == chunk_samples) {
            speech = iface->detect(model, buf) == VAD_SPEECH;
            fill = 0;
        }
    }
    return speech;
}
