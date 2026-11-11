#include "ota.h"
#include "esp_crt_bundle.h"
#include "esp_app_desc.h"
#include "esp_heap_caps.h"
#include "esp_http_client.h"
#include "esp_log.h"
#include "esp_ota_ops.h"
#include "esp_system.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define VERIFY_WINDOW_MS (3 * 60 * 1000)
#define BLOCK 4096

static const char *TAG = "ota";
static bool pending, audio_ok, hub_ok;
static int64_t boot_ms;

static void confirm_if_healthy(void)
{
    if (!pending || !audio_ok || !hub_ok) return;
    if (esp_ota_mark_app_valid_cancel_rollback() == ESP_OK) {
        pending = false;
        ESP_LOGI(TAG, "firmware %s confirmed: audio and hub are working", esp_app_get_description()->version);
    }
}

void ota_init(void)
{
    esp_ota_img_states_t st;
    const esp_partition_t *running = esp_ota_get_running_partition();
    pending = esp_ota_get_state_partition(running, &st) == ESP_OK && st == ESP_OTA_IMG_PENDING_VERIFY;
    boot_ms = esp_log_timestamp();
    ESP_LOGI(TAG, "running %s from %s%s", esp_app_get_description()->version, running->label,
             pending ? ", waiting for the health check" : "");
}

void ota_note_audio(void)
{
    audio_ok = true;
    confirm_if_healthy();
}

void ota_note_hub(void)
{
    hub_ok = true;
    confirm_if_healthy();
}

void ota_tick(void)
{
    if (!pending || esp_log_timestamp() - boot_ms < VERIFY_WINDOW_MS) return;
    ESP_LOGE(TAG, "health check failed (audio %d, hub %d), rolling back", audio_ok, hub_ok);
    esp_ota_mark_app_invalid_rollback_and_reboot();
}

static void parse(const char *v, int out[3])
{
    out[0] = out[1] = out[2] = 0;
    sscanf(v, "%d.%d.%d", &out[0], &out[1], &out[2]);
}

static bool failed_before(const char *version)
{
    const esp_partition_t *bad = esp_ota_get_last_invalid_partition();
    esp_app_desc_t desc;
    return bad && esp_ota_get_partition_description(bad, &desc) == ESP_OK && !strcmp(desc.version, version);
}

bool ota_is_newer(const char *version)
{
    if (failed_before(version)) return false;
    int a[3], b[3];
    parse(version, a);
    parse(esp_app_get_description()->version, b);
    for (int i = 0; i < 3; i++)
        if (a[i] != b[i]) return a[i] > b[i];
    return false;
}

bool ota_install(const char *base_url, const ota_info_t *info, ota_progress_cb progress)
{
    char url[160];
    snprintf(url, sizeof url, "%s/api/firmware/bin?version=%s", base_url, info->version);
    const esp_partition_t *slot = esp_ota_get_next_update_partition(NULL);
    uint8_t *buf = heap_caps_malloc(BLOCK, MALLOC_CAP_SPIRAM);
    esp_http_client_config_t cfg = {.url = info->url[0] ? info->url : url, .timeout_ms = 20000, .buffer_size = 4096, .buffer_size_tx = 1024,
                                    .crt_bundle_attach = info->url[0] ? esp_crt_bundle_attach : NULL};
    esp_http_client_handle_t c = esp_http_client_init(&cfg);
    esp_ota_handle_t h = 0;
    bool ok = buf && slot && esp_http_client_open(c, 0) == ESP_OK && esp_http_client_fetch_headers(c) >= 0;
    for (int hops = 0; ok && hops < 4; hops++) {
        int st = esp_http_client_get_status_code(c);
        if (st != 301 && st != 302 && st != 303 && st != 307 && st != 308) break;
        ok = esp_http_client_set_redirection(c) == ESP_OK && esp_http_client_close(c) == ESP_OK && esp_http_client_open(c, 0) == ESP_OK &&
             esp_http_client_fetch_headers(c) >= 0;
    }
    ok = ok && esp_http_client_get_status_code(c) == 200 && esp_ota_begin(slot, info->size, &h) == ESP_OK;
    size_t done = 0;
    int last = -1;
    while (ok && done < info->size) {
        int n = esp_http_client_read(c, (char *)buf, BLOCK);
        if (n <= 0) break;
        esp_err_t w = esp_ota_write(h, buf, n);
        if (w != ESP_OK) ESP_LOGE(TAG, "esp_ota_write: %s", esp_err_to_name(w));
        ok = w == ESP_OK;
        done += n;
        int pct = done * 100 / info->size;
        if (pct != last) progress(last = pct);
    }
    esp_http_client_cleanup(c);
    free(buf);
    ok = ok && done == info->size;
    esp_err_t end = h && ok ? esp_ota_end(h) : ESP_FAIL;
    if (h && !ok) esp_ota_abort(h);
    if (ok && end != ESP_OK) ESP_LOGE(TAG, "esp_ota_end: %s", esp_err_to_name(end));
    ok = ok && end == ESP_OK;
    esp_err_t boot = ok ? esp_ota_set_boot_partition(slot) : ESP_FAIL;
    if (ok && boot != ESP_OK) ESP_LOGE(TAG, "set boot partition: %s", esp_err_to_name(boot));
    ok = ok && boot == ESP_OK;
    if (!ok) {
        ESP_LOGE(TAG, "update to %s failed after %u of %u bytes", info->version, (unsigned)done, (unsigned)info->size);
        return false;
    }
    ESP_LOGI(TAG, "update to %s written to %s, restarting", info->version, slot->label);
    return true;
}
