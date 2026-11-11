#include "touch.h"
#include "driver/gpio.h"
#include "driver/i2c_master.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#define PIN_TP_SDA 3
#define PIN_TP_SCL 2
#define PIN_TP_INT 21
#define PIN_TP_RST 41
#define PIN_TP_EN 42

static const char *TAG = "touch";
static i2c_master_dev_handle_t tp;
static bool ready;
static int last_x, last_y;
static bool pressed;

static bool reg_read(uint16_t reg, uint8_t *buf, size_t len)
{
    uint8_t r[2] = {reg >> 8, reg & 0xFF};
    return i2c_master_transmit_receive(tp, r, 2, buf, len, 20) == ESP_OK;
}

static void status_clear(void)
{
    uint8_t w[3] = {0x81, 0x4E, 0x00};
    i2c_master_transmit(tp, w, 3, 20);
}

static void reset_with_int(int level)
{
    gpio_set_direction(PIN_TP_INT, GPIO_MODE_OUTPUT);
    gpio_set_direction(PIN_TP_RST, GPIO_MODE_OUTPUT);
    gpio_set_level(PIN_TP_RST, 0);
    gpio_set_level(PIN_TP_INT, level);
    vTaskDelay(pdMS_TO_TICKS(10));
    gpio_set_level(PIN_TP_RST, 1);
    vTaskDelay(pdMS_TO_TICKS(10));
    gpio_set_level(PIN_TP_INT, level);
    vTaskDelay(pdMS_TO_TICKS(50));
    gpio_set_direction(PIN_TP_INT, GPIO_MODE_INPUT);
    vTaskDelay(pdMS_TO_TICKS(50));
}

bool touch_init(void)
{
    gpio_config_t en = {.pin_bit_mask = 1ULL << PIN_TP_EN, .mode = GPIO_MODE_OUTPUT};
    gpio_config(&en);
    gpio_set_level(PIN_TP_EN, 1);
    vTaskDelay(pdMS_TO_TICKS(50));

    i2c_master_bus_config_t cfg = {
        .i2c_port = I2C_NUM_0,
        .sda_io_num = PIN_TP_SDA,
        .scl_io_num = PIN_TP_SCL,
        .clk_source = I2C_CLK_SRC_DEFAULT,
        .glitch_ignore_cnt = 7,
    };
    i2c_master_bus_handle_t bus;
    ESP_ERROR_CHECK(i2c_new_master_bus(&cfg, &bus));

    uint8_t addr = 0;
    for (int level = 0; level < 2 && !addr; level++) {
        reset_with_int(level);
        if (i2c_master_probe(bus, 0x5D, 20) == ESP_OK) addr = 0x5D;
        else if (i2c_master_probe(bus, 0x14, 20) == ESP_OK) addr = 0x14;
    }
    if (!addr) {
        ESP_LOGE(TAG, "GT911 not found");
        return false;
    }
    i2c_device_config_t d = {.dev_addr_length = I2C_ADDR_BIT_LEN_7, .device_address = addr, .scl_speed_hz = 400000};
    ESP_ERROR_CHECK(i2c_master_bus_add_device(bus, &d, &tp));
    uint8_t id[4] = {0};
    reg_read(0x8140, id, 4);
    ESP_LOGI(TAG, "GT911 at 0x%02x, product id %.4s", addr, id);
    ready = true;
    return true;
}

bool touch_read(int *x, int *y)
{
    if (!ready) return false;
    uint8_t status = 0;
    if (reg_read(0x814E, &status, 1) && (status & 0x80)) {
        int count = status & 0x0F;
        uint8_t p[4];
        if (count > 0 && reg_read(0x8150, p, 4)) {
            int raw_x = p[0] | p[1] << 8;
            int raw_y = p[2] | p[3] << 8;
            last_x = raw_x;
            last_y = 799 - raw_y;
            if (!pressed) ESP_LOGI(TAG, "press raw x %d y %d -> ui %d,%d", raw_x, raw_y, last_x, last_y);
            pressed = true;
        } else {
            pressed = false;
        }
        status_clear();
    }
    *x = last_x;
    *y = last_y;
    return pressed;
}
