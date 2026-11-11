#include "esp_adc/adc_cali.h"
#include "esp_adc/adc_cali_scheme.h"
#include "esp_adc/adc_oneshot.h"
#include "driver/gpio.h"
#include "sensors.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/temperature_sensor.h"
#include "board.h"
#include "driver/i2c_master.h"
#include "esp_log.h"

static const char *TAG = "sensors";
static i2c_master_dev_handle_t gauge;
static i2c_master_dev_handle_t imu;
static i2c_master_dev_handle_t rtc;
static i2c_master_dev_handle_t climate;
static bool imu_ready;

static bool rd(i2c_master_dev_handle_t dev, uint8_t reg, uint8_t *buf, size_t len)
{
    return i2c_master_transmit_receive(dev, &reg, 1, buf, len, 50) == ESP_OK;
}

static bool wr(i2c_master_dev_handle_t dev, uint8_t reg, uint8_t val)
{
    uint8_t b[2] = {reg, val};
    return i2c_master_transmit(dev, b, 2, 50) == ESP_OK;
}

static bool rd16(i2c_master_dev_handle_t dev, uint8_t reg, int *out, bool is_signed)
{
    uint8_t b[2];
    if (!rd(dev, reg, b, 2)) return false;
    uint16_t v = b[0] | (b[1] << 8);
    *out = is_signed ? (int16_t)v : v;
    return true;
}

#define USB_PRESENT_MV 1000

static adc_oneshot_unit_handle_t adc;
static adc_cali_handle_t cali;

void sensors_init(void)
{
    gpio_config_t stat = {.pin_bit_mask = 1ULL << PIN_CHG_STAT, .mode = GPIO_MODE_INPUT, .pull_up_en = GPIO_PULLUP_ENABLE};
    gpio_config(&stat);
    adc_oneshot_unit_init_cfg_t ac = {.unit_id = ADC_UNIT_1};
    adc_oneshot_new_unit(&ac, &adc);
    adc_oneshot_chan_cfg_t ch = {.atten = ADC_ATTEN_DB_12, .bitwidth = ADC_BITWIDTH_DEFAULT};
    adc_oneshot_config_channel(adc, VBUS_ADC_CHANNEL, &ch);
    adc_cali_curve_fitting_config_t cc = {.unit_id = ADC_UNIT_1, .chan = VBUS_ADC_CHANNEL, .atten = ADC_ATTEN_DB_12, .bitwidth = ADC_BITWIDTH_DEFAULT};
    adc_cali_create_scheme_curve_fitting(&cc, &cali);

    i2c_master_bus_config_t cfg = {
        .i2c_port = I2C_NUM_1,
        .sda_io_num = PIN_SENS_SDA,
        .scl_io_num = PIN_SENS_SCL,
        .clk_source = I2C_CLK_SRC_DEFAULT,
        .glitch_ignore_cnt = 7,
        .flags.enable_internal_pullup = false,
    };
    i2c_master_bus_handle_t bus;
    ESP_ERROR_CHECK(i2c_new_master_bus(&cfg, &bus));
    for (uint8_t a = 0x08; a < 0x78; a++) {
        if (i2c_master_probe(bus, a, 20) == ESP_OK) ESP_LOGI(TAG, "i2c device at 0x%02x", a);
    }
    i2c_device_config_t g = {.dev_addr_length = I2C_ADDR_BIT_LEN_7, .device_address = 0x55, .scl_speed_hz = 100000};
    i2c_device_config_t m = {.dev_addr_length = I2C_ADDR_BIT_LEN_7, .device_address = 0x6A, .scl_speed_hz = 400000};
    ESP_ERROR_CHECK(i2c_master_bus_add_device(bus, &g, &gauge));
    ESP_ERROR_CHECK(i2c_master_bus_add_device(bus, &m, &imu));
    i2c_device_config_t r = {.dev_addr_length = I2C_ADDR_BIT_LEN_7, .device_address = 0x51, .scl_speed_hz = 100000};
    ESP_ERROR_CHECK(i2c_master_bus_add_device(bus, &r, &rtc));
    i2c_device_config_t c = {.dev_addr_length = I2C_ADDR_BIT_LEN_7, .device_address = 0x44, .scl_speed_hz = 100000};
    ESP_ERROR_CHECK(i2c_master_bus_add_device(bus, &c, &climate));
    uint8_t regs[16] = {0};
    if (rd(rtc, 0x00, regs, sizeof regs))
        ESP_LOGI(TAG, "rtc regs %02x %02x %02x %02x %02x %02x %02x %02x %02x %02x %02x %02x %02x %02x %02x %02x", regs[0], regs[1],
                 regs[2], regs[3], regs[4], regs[5], regs[6], regs[7], regs[8], regs[9], regs[10], regs[11], regs[12], regs[13],
                 regs[14], regs[15]);

    uint8_t who = 0;
    if (rd(imu, 0x0F, &who, 1) && who == 0x6A) {
        imu_ready = wr(imu, 0x12, 0x34) && wr(imu, 0x10, 0x60) && wr(imu, 0x15, 0x10) && wr(imu, 0x58, 0x83) &&
                    wr(imu, 0x59, 0x12) && wr(imu, 0x5A, 0x5E) && wr(imu, 0x5B, 0x80) && wr(imu, 0x5C, 0x00) &&
                    wr(imu, 0x5D, 0x33) && wr(imu, 0x5E, 0x08);
        gpio_config_t in = {.pin_bit_mask = 1ULL << PIN_MOTION_INT, .mode = GPIO_MODE_INPUT, .pull_up_en = GPIO_PULLUP_ENABLE};
        gpio_config(&in);
        uint8_t src;
        rd(imu, 0x1C, &src, 1);
        ESP_LOGI(TAG, "motion interrupt line level %d", gpio_get_level(PIN_MOTION_INT));
    }
    ESP_LOGI(TAG, "imu who_am_i=0x%02x ready=%d", who, imu_ready);
}

static int64_t fell_at = -10000000;

static void check_fall(void)
{
    uint8_t wake = 0;
    if (imu_ready && rd(imu, 0x1B, &wake, 1) && (wake & 0x20)) fell_at = esp_timer_get_time();
}

void sensors_read(sensors_t *s)
{
    int raw = 0;
    s->vbus_mv = 0;
    if (adc_oneshot_read(adc, VBUS_ADC_CHANNEL, &raw) == ESP_OK) adc_cali_raw_to_voltage(cali, raw, &s->vbus_mv);
    s->usb = s->vbus_mv > USB_PRESENT_MV;
    s->charging = gpio_get_level(PIN_CHG_STAT) == 0;
    s->gauge_ok = rd16(gauge, 0x2C, &s->soc_pct, false) && rd16(gauge, 0x08, &s->voltage_mv, false) &&
                  rd16(gauge, 0x0C, &s->current_ma, true) && rd16(gauge, 0x14, &s->avg_current_ma, true) &&
                  rd16(gauge, 0x10, &s->remaining_mah, false) && rd16(gauge, 0x12, &s->full_mah, false) &&
                  rd16(gauge, 0x3C, &s->design_mah, false);
    static int calls, room_c10, humidity;
    static bool climate_ok;
    if (calls++ % 30 == 0) {
        uint8_t cmd = 0xFD, m[6];
        climate_ok = i2c_master_transmit(climate, &cmd, 1, 50) == ESP_OK;
        if (climate_ok) {
            vTaskDelay(pdMS_TO_TICKS(10));
            climate_ok = i2c_master_receive(climate, m, 6, 50) == ESP_OK;
        }
        if (climate_ok) {
            room_c10 = -450 + 1750 * (m[0] << 8 | m[1]) / 65535;
            humidity = -6 + 125 * (m[3] << 8 | m[4]) / 65535;
            humidity = humidity < 0 ? 0 : humidity > 100 ? 100 : humidity;
        }
    }
    s->climate_ok = climate_ok;
    s->room_c10 = room_c10;
    s->humidity = humidity;
    check_fall();
    uint8_t b[6];
    s->imu_ok = imu_ready && rd(imu, 0x28, b, 6);
    if (s->imu_ok) {
        s->ax_mg = (int16_t)(b[0] | b[1] << 8) * 61 / 1000;
        s->ay_mg = (int16_t)(b[2] | b[3] << 8) * 61 / 1000;
        s->az_mg = (int16_t)(b[4] | b[5] << 8) * 61 / 1000;
    }
}

static int bcd(uint8_t v) { return (v >> 4) * 10 + (v & 0x0F); }
static uint8_t to_bcd(int v) { return (uint8_t)((v / 10) << 4 | v % 10); }

bool sensors_rtc_read(time_t *out)
{
    uint8_t r[7];
    if (!rd(rtc, 0x02, r, sizeof r) || r[0] & 0x80) return false;
    struct tm t = {
        .tm_sec = bcd(r[0] & 0x7F),
        .tm_min = bcd(r[1] & 0x7F),
        .tm_hour = bcd(r[2] & 0x3F),
        .tm_mday = bcd(r[3] & 0x3F),
        .tm_mon = bcd(r[5] & 0x1F) - 1,
        .tm_year = bcd(r[6]) + 100,
    };
    char *tz = getenv("TZ");
    char saved[16] = "";
    if (tz) snprintf(saved, sizeof saved, "%s", tz);
    setenv("TZ", "UTC0", 1);
    tzset();
    *out = mktime(&t);
    setenv("TZ", saved[0] ? saved : "UTC0", 1);
    tzset();
    return *out > 0;
}

bool sensors_rtc_write(time_t now)
{
    struct tm t;
    gmtime_r(&now, &t);
    uint8_t w[8] = {0x02, to_bcd(t.tm_sec), to_bcd(t.tm_min), to_bcd(t.tm_hour), to_bcd(t.tm_mday), (uint8_t)t.tm_wday,
                    to_bcd(t.tm_mon + 1), to_bcd(t.tm_year % 100)};
    return i2c_master_transmit(rtc, w, sizeof w, 50) == ESP_OK;
}

int sensors_chip_celsius(void)
{
    static temperature_sensor_handle_t ts;
    if (!ts) {
        temperature_sensor_config_t c = TEMPERATURE_SENSOR_CONFIG_DEFAULT(10, 80);
        if (temperature_sensor_install(&c, &ts) != ESP_OK || temperature_sensor_enable(ts) != ESP_OK) return 0;
    }
    float celsius = 0;
    temperature_sensor_get_celsius(ts, &celsius);
    return (int)celsius;
}

bool sensors_double_tap(void)
{
    uint8_t src = 0;
    if (!imu_ready) return false;
    int64_t now = esp_timer_get_time();
    check_fall();
    bool tap = rd(imu, 0x1C, &src, 1) && (src & 0x10);
    if (tap && now - fell_at < 2000000) {
        ESP_LOGI(TAG, "double tap ignored: free fall before it");
        return false;
    }
    return tap;
}
