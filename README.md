# resticky

Custom software for the Seeed reTerminal Sticky (model E1005, SKU 100056818).

## Device (read from the unit on 2026-11-06)

| Item | Value | Source |
|---|---|---|
| SoC | ESP32-S3 (QFN56) rev v0.2, 240 MHz, Wi-Fi + BLE 5 | `esptool flash-id` |
| Flash | 32 MB, Winbond (`ef`/`4019`), quad | `esptool flash-id` |
| PSRAM | 8 MB embedded, octal | `esptool flash-id` |
| MAC | `c4:9e:7e:19:5c:24` | `esptool flash-id` |
| USB | WCH CH343 bridge `1a86:55d3` on UART0, shows as `/dev/ttyACM0` | `lsusb` |
| Display | 3.97 in, 800x480, 1-bit, SSD1677 (some units SSD2677) | FreeInk SDK |
| Touch | GT911 | FreeInk SDK |
| Sensors | SHT40 temp/humidity, LSM6DS3TR-C IMU, PCF8563 RTC, BQ27220 gauge | FreeInk SDK |
| Audio | PDM mic in, LEDC buzzer out, no speaker codec | FreeInk SDK |

The console is UART0 through the CH343 bridge, not native USB CDC. Use `Serial0`, and set
`ARDUINO_USB_CDC_ON_BOOT=0`.

## Pin map

From `refs/freeink-sdk/libs/hardware/BoardConfig/include/BoardConfig.h` (profile `STICKY`).

| Function | GPIO |
|---|---|
| Power latch: PWR_HOLD, PWR_LOCK (drive HIGH first at boot) | 45, 46 |
| Charge enable EN_BAT_CHGn (active low), charge status | 39, 40 |
| EPD SPI: SCK, MOSI, CS, DC, RST, BUSY, EP_PWR_EN | 13, 14, 15, 16, 17, 18, 47 |
| SD (SPI, shares EPD bus): MISO, CS, PWR_EN | 12, 8, 10 |
| Buttons (active low): OK/power, up, down | 4, 5, 6 |
| Touch GT911 (I2C 0x5D): SDA, SCL, INT, RST, EN | 3, 2, 21, 41, 42 |
| Sensor I2C bus (gauge 0x55, RTC 0x51, SHT40 0x44, IMU 0x6A): SDA, SCL | 1, 0 |
| PDM mic: CLK, DATA, EN (CLK and DATA work in `refs/reterminal-sticky-voice-companion`: 16 kHz, 16-bit, left channel) | 19, 20, 38 |
| Buzzer | 48 |
| VBUS voltage (ADC) | 9 |

GPIO0 is a strapping pin and is also the sensor I2C SCL. Do not leave a pull state on it that
changes the boot mode.

## Stock firmware backup

`backups/stock-seeedash-full-32MB.bin` is a full read of the flash before the first custom
flash (33554432 bytes, SHA-256
`c0554589c43b589b92e5849470feccc474578595990cd9e56e0ad4e84fea379d`). To restore the Seeedash
firmware:

```sh
uvx esptool --port /dev/ttyACM0 --baud 921600 write-flash 0 backups/stock-seeedash-full-32MB.bin
```

Stock partition table (read from the backup at 0x8000):

| Name | Type | Offset | Size |
|---|---|---|---|
| nvs | data/nvs | 0x009000 | 0.49 MB |
| otadata | data/ota | 0x086000 | 8 KB |
| phy_init | data/phy | 0x088000 | 4 KB |
| app0 | app/ota_0 | 0x090000 | 6 MB |
| app1 | app/ota_1 | 0x690000 | 6 MB |
| sys_storage | data/0x83 | 0xC90000 | 9.19 MB |
| usr_storage | data/0x83 | 0x15C0000 | 10 MB |
| coredump | data/coredump | 0x1FC0000 | 0.25 MB |

## Tools

- `uvx esptool` for flash reads, writes and chip info.
- PlatformIO, installed with `uv tool install platformio` (`pio`).
- `refs/` holds shallow clones of reference repos. They are not part of this project.
  - `reterminal-sticky-playground-registry`: Seeed starter templates (PlatformIO, Arduino).
  - `freeink-sdk`: drivers for display, touch, buttons, gauge, sensors, mic.
  - `crosspoint-reader`: full e-reader firmware with a `sticky` build env.
  - `reterminal-sticky-voice-companion`: Home Assistant voice satellite.
  - `reterminal_sticky_handwriting_pad`: touch drawing example.

## Links

- Product: https://www.seeedstudio.com/sticky/
- Community firmware list: https://www.seeedstudio.com/sticky/playground/firmware/
- Official firmware web flasher: https://www.seeedstudio.com/sticky/playground/official-firmware/
