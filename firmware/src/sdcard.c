#include "sdcard.h"
#include "board.h"
#include "driver/gpio.h"
#include "driver/sdspi_host.h"
#include "esp_log.h"
#include "esp_vfs_fat.h"
#include "ff.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "sdmmc_cmd.h"
#include <string.h>

#define MOUNT "/sd"

static const char *TAG = "sdcard";
static sdmmc_card_t *card;

static bool present(void) { return gpio_get_level(PIN_SD_DETECT) == 0; }

static bool mount(void)
{
    gpio_set_level(PIN_SD_PWR_EN, 1);
    vTaskDelay(pdMS_TO_TICKS(50));
    sdmmc_host_t host = SDSPI_HOST_DEFAULT();
    host.slot = SPI2_HOST;
    host.max_freq_khz = 20000;
    sdspi_device_config_t slot = SDSPI_DEVICE_CONFIG_DEFAULT();
    slot.gpio_cs = PIN_SD_CS;
    slot.host_id = SPI2_HOST;
    esp_vfs_fat_sdmmc_mount_config_t cfg = {.format_if_mount_failed = false, .max_files = 4, .allocation_unit_size = 32 * 1024};
    esp_err_t err = esp_vfs_fat_sdspi_mount(MOUNT, &host, &slot, &cfg, &card);
    if (err != ESP_OK) {
        ESP_LOGW(TAG, "mount failed: %s", esp_err_to_name(err));
        card = NULL;
        return false;
    }
    ESP_LOGI(TAG, "mounted %s, %llu MB", card->cid.name, ((uint64_t)card->csd.capacity * card->csd.sector_size) >> 20);
    return true;
}

void sdcard_init(void)
{
    gpio_config_t det = {.pin_bit_mask = 1ULL << PIN_SD_DETECT, .mode = GPIO_MODE_INPUT, .pull_up_en = GPIO_PULLUP_ENABLE};
    gpio_config(&det);
    gpio_config_t pwr = {.pin_bit_mask = 1ULL << PIN_SD_PWR_EN, .mode = GPIO_MODE_OUTPUT};
    gpio_config(&pwr);
    gpio_set_level(PIN_SD_PWR_EN, 0);
    vTaskDelay(pdMS_TO_TICKS(5));
    ESP_LOGI(TAG, "card %s", present() ? "present" : "not present");
    if (present()) mount();
}

void sdcard_info(sdcard_info_t *out)
{
    memset(out, 0, sizeof *out);
    out->present = present();
    out->mounted = card != NULL;
    if (!card) return;
    strlcpy(out->name, card->cid.name, sizeof out->name);
    out->total = (uint64_t)card->csd.capacity * card->csd.sector_size;
    out->speed_khz = card->real_freq_khz;
    FATFS *fs;
    DWORD free_clusters;
    if (f_getfree("0:", &free_clusters, &fs) == FR_OK) out->free = (uint64_t)free_clusters * fs->csize * card->csd.sector_size;
}

bool sdcard_format(void)
{
    if (!card && (!present() || !mount())) {
        if (!present()) return false;
        esp_vfs_fat_sdmmc_mount_config_t cfg = {.format_if_mount_failed = true, .max_files = 4, .allocation_unit_size = 32 * 1024};
        sdmmc_host_t host = SDSPI_HOST_DEFAULT();
        host.slot = SPI2_HOST;
        sdspi_device_config_t slot = SDSPI_DEVICE_CONFIG_DEFAULT();
        slot.gpio_cs = PIN_SD_CS;
        slot.host_id = SPI2_HOST;
        if (esp_vfs_fat_sdspi_mount(MOUNT, &host, &slot, &cfg, &card) != ESP_OK) {
            card = NULL;
            return false;
        }
        return true;
    }
    esp_err_t err = esp_vfs_fat_sdcard_format(MOUNT, card);
    ESP_LOGI(TAG, "format: %s", esp_err_to_name(err));
    return err == ESP_OK;
}
