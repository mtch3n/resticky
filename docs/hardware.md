# Hardware: reTerminal Sticky (E1005)

Source: Seeed "reTerminal Sticky" schematic, rev 01, 2026-06-05
(https://files.seeedstudio.com/wiki/reterminal_sticky/res/reTerminal_Sticky_Schematic_diagram_260609.pdf).
The E1001–E1004 wiki pin tables do not apply to this board (for example GPIO45 is the power latch here, not the
buzzer).

## Pin map

| GPIO | Net | Part |
|---|---|---|
| 4 | PWR_BUTTON | Power key (also wakes from deep sleep) |
| 5 / 6 | BUTTON_UP / BUTTON_DOWN | Side keys |
| 45 / 46 | PWR_HOLD / PWR_LOCK | Power latch |
| 7 | BFG_INT and 6D_INTn | Fuel gauge interrupt and IMU INT1 (shared) |
| 0 / 1 | BFG_I2C_SCL/SDA = MISC_I2C | BQ27220 gauge 0x55, LSM6DS3TR-C IMU 0x6A, PCF8563 RTC 0x51, SHT40 0x44 |
| 39 / 40 | EN_BAT_CHGn / CHARGE_STATE | BQ25616 charger |
| 9 | PWR_IN_VOLT | VBUS sense (ADC) |
| 48 | BUZZER_PWM | MOSFET (CJ2324) to passive buzzer FUET-5018 |
| 19 / 20 / 38 | PDM_CLK / PDM_DATA / PDM_EN | MSM261DDB020 PDM mic; PDM_EN switches the mic power (TPS22916) |
| 13 / 14 / 15 / 16 / 17 / 18 / 47 | EP_SCK / SDI / CSn / DCn / RSTn / BUSY / PWR_EN | SSD1677 e-paper |
| 2 / 3 / 21 / 41 / 42 | TP_I2C_SCL / SDA / INT / RSTn / PWR_EN | GT911 touch |
| 8 / 10 / 11 / 12, 13 / 14 shared | SD_D3/CS / SD_PWR_EN / SD_DETECT / SD_D0/MISO, CLK / MOSI | microSD (SPI, shares the e-paper bus) |
| 43 / 44 | USB_RXD / USB_TXD | UART bridge to USB and pogo pins |

Other parts: ESP32-S3R8 (8 MB PSRAM), 32 MB flash, TPS631000 buck-boost, red/green charge LED, reset key.

## What the firmware uses (0.13.0)

| Feature | Used | Notes |
|---|---|---|
| E-paper, touch, keys, power latch | Yes | |
| PDM mic + Opus + VADNet (esp-nn) | Yes | |
| esp-dsp filter | Partly | ANSI functions; the aes3 (PIE SIMD) versions corrupted memory with the audio stack in PSRAM |
| Fuel gauge, charger status, VBUS | Yes | Polled each second |
| IMU | Yes | Face-down and double tap, polled |
| RTC | Yes | Time at boot, set after SNTP |
| Buzzer | Yes (0.13.0) | Click, mark sound, Sounds setting |
| Wi-Fi, BLE (2M PHY) | Yes | |
| **SHT40 temperature and humidity** | **No** | Room climate for Clock/Weather/Health |
| **microSD** | **No** | Large spool (plan layer 8) |
| **GPIO7 interrupt (IMU, gauge)** | **No** | Interrupt-driven tap, wake on tap from deep sleep, low-battery alert |
| **PDM_EN mic power** | **No** | Switch the mic off when muted or asleep |
| **RTC alarm (RTC_INTn)** | **No** | Scheduled wake (night pause); check where RTC_INTn goes |
| **CPU power management (DFS)** | **No** | Needs lower Opus complexity first (see plan-audio.md) |
| **PIE SIMD (esp-dsp aes3)** | **No** | Needs the audio task stack in internal RAM or a fix for the PIE context |
| **Hardware AES/SHA** | **No** | Encrypted uploads (HTTP is plain today) |
| ULP RISC-V coprocessor | No | Not needed while ext0/ext1 wake is enough |
