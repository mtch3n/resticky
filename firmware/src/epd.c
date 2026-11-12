#define EPD_SPI_HZ (10 * 1000 * 1000)
#include "epd.h"
#include "board.h"
#include "driver/gpio.h"
#include "driver/spi_master.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

static const char *TAG = "epd";
static spi_device_handle_t spi;

static void send(bool data, const uint8_t *buf, size_t len)
{
    gpio_set_level(PIN_EPD_DC, data);
    while (len) {
        size_t n = len > 4000 ? 4000 : len;
        spi_transaction_t t = {.length = n * 8, .tx_buffer = buf};
        spi_device_polling_transmit(spi, &t);
        buf += n;
        len -= n;
    }
}

static void cmd(uint8_t c, const uint8_t *data, size_t len)
{
    send(false, &c, 1);
    if (len) send(true, data, len);
}

#define CMD(c, ...) do { const uint8_t d_[] = {__VA_ARGS__}; cmd(c, d_, sizeof d_); } while (0)

static bool wait_idle(const char *what)
{
    int64_t start = esp_log_timestamp();
    while (gpio_get_level(PIN_EPD_BUSY)) {
        if (esp_log_timestamp() - start > 15000) {
            ESP_LOGE(TAG, "busy timeout: %s", what);
            return false;
        }
        vTaskDelay(pdMS_TO_TICKS(2));
    }
    ESP_LOGI(TAG, "%s: %lld ms", what, esp_log_timestamp() - start);
    return true;
}

static void ram_area(void)
{
    CMD(0x11, 0x01);
    CMD(0x44, 0x00, 0x00, (EPD_W - 1) & 0xFF, (EPD_W - 1) >> 8);
    CMD(0x45, (EPD_H - 1) & 0xFF, (EPD_H - 1) >> 8, 0x00, 0x00);
    CMD(0x4E, 0x00, 0x00);
    CMD(0x4F, (EPD_H - 1) & 0xFF, (EPD_H - 1) >> 8);
}

bool epd_init(void)
{
    gpio_config_t out = {
        .pin_bit_mask = (1ULL << PIN_EPD_DC) | (1ULL << PIN_EPD_RST) | (1ULL << PIN_EPD_PWR),
        .mode = GPIO_MODE_OUTPUT,
    };
    gpio_config(&out);
    gpio_config_t in = {.pin_bit_mask = 1ULL << PIN_EPD_BUSY, .mode = GPIO_MODE_INPUT};
    gpio_config(&in);

    gpio_set_level(PIN_EPD_PWR, 1);
    vTaskDelay(pdMS_TO_TICKS(10));

    spi_bus_config_t bus = {
        .sclk_io_num = PIN_EPD_SCK,
        .mosi_io_num = PIN_EPD_MOSI,
        .miso_io_num = PIN_SD_MISO,
        .quadwp_io_num = -1,
        .quadhd_io_num = -1,
        .max_transfer_sz = 4096,
    };
    ESP_ERROR_CHECK(spi_bus_initialize(SPI2_HOST, &bus, SPI_DMA_CH_AUTO));
    spi_device_interface_config_t dev = {
        .clock_speed_hz = EPD_SPI_HZ,
        .mode = 0,
        .spics_io_num = PIN_EPD_CS,
        .queue_size = 1,
    };
    ESP_ERROR_CHECK(spi_bus_add_device(SPI2_HOST, &dev, &spi));

    gpio_set_level(PIN_EPD_RST, 0);
    vTaskDelay(pdMS_TO_TICKS(20));
    gpio_set_level(PIN_EPD_RST, 1);
    vTaskDelay(pdMS_TO_TICKS(30));
    ESP_LOGI(TAG, "BUSY idle level after reset: %d (1 means SSD2677)", gpio_get_level(PIN_EPD_BUSY));

    CMD(0x12);
    vTaskDelay(pdMS_TO_TICKS(10));
    if (!wait_idle("soft reset")) return false;
    CMD(0x18, 0x80);
    CMD(0x0C, 0xAE, 0xC7, 0xC3, 0xC0, 0x80);
    CMD(0x01, (EPD_H - 1) & 0xFF, (EPD_H - 1) >> 8, 0x02);
    CMD(0x3C, 0x01);
    ram_area();
    CMD(0x46, 0xF7);
    wait_idle("clear bw ram");
    CMD(0x47, 0xF7);
    wait_idle("clear red ram");
    return true;
}

void epd_refresh(const uint8_t *fb, bool full)
{
    if (full) {
        ram_area();
        cmd(0x26, fb, EPD_FB_SIZE);
    }
    ram_area();
    cmd(0x24, fb, EPD_FB_SIZE);
    CMD(0x21, 0x00, 0x00);
    CMD(0x18, 0x80);
    CMD(0x3C, full ? 0x01 : 0x80);
    CMD(0x22, full ? 0xF4 : 0xFC);
    CMD(0x20);
    wait_idle(full ? "full refresh" : "partial refresh");
    ram_area();
    cmd(0x26, fb, EPD_FB_SIZE);
}
