# Plan: continuous conversation recording

Status: draft, revision 2, 2026-11-06. Revision 2 adds the findings of the Codex `gpt-6-astra`
review. The Antigravity `gemini-4-argon-high` review did not run (quota limit).

## Goal

The Sticky records the conversations in a room for a long time on battery. A hub on the LAN
makes transcripts from them. Later goals use the same chain: summaries, search, action items,
a voice conversation with an AI, and an on-screen character.

The hardware facts and the pin map are in `../README.md`. The battery capacity is not known yet.

## Principles

1. Do not send silence.
2. Do not keep Wi-Fi on.
3. Keep audio only for a short, fixed time after the transcript exists.
4. Measure everything. Each power and storage decision must come from measured data, not from
   estimates.

## Capture modes

The ESP32-S3 cannot capture PDM/I2S audio in light sleep, because the peripheral clocks stop.
So each mode is a trade between coverage and power:

| Mode | Behaviour | Coverage | Power |
|---|---|---|---|
| Continuous | The mic and the CPU run all the time. VAD (ESP-SR VADNet) with a pre-roll buffer selects the speech. | No speech lost. | Highest: about 30–50 mA all the time (to measure). |
| Duty-cycled | The mic samples 0.3 s every 3 s, with light sleep between samples. A sample with speech starts continuous capture until 10–20 s of silence. | Short utterances in a quiet room can be lost completely. In a conversation, up to 3 s at the start is lost. | Lowest in a quiet room. |

Decision pending. The chosen mode (or a rule that changes between them) comes from measurements in
layer 2.

## Device design

| Item | Behaviour |
|---|---|
| Codec | Opus, mono, 16 kHz. Test 8, 12 and 16 kbps, low complexity, and DTX in the silence tail. Choose by transcript quality and total energy. |
| CPU clock | Benchmark energy per recorded minute at 80, 160 and 240 MHz, with Opus, VAD and an upload at the same time. Do not assume that 80 MHz is best. |
| Wi-Fi bursts | Upload about every 15 min, or when the buffer is full. 15 min at 16 kbps is 1.8 MB, so a burst is about 5–10 s with the connect, not 2–3 s. Saved BSSID/channel and static IP, with a fallback to a full scan when they fail. Bounded retries with backoff. |
| Upload protocol | Each chunk has a stable ID and a checksum. The hub writes the chunk to durable storage before it sends the ack. Retries are duplicate-safe. Uploads are encrypted and authenticated. |
| RAM buffer | PSRAM ring buffer, about 1 h at 16 kbps. The device writes it to the flash spool before a planned deep sleep and when the battery is low. |
| Flash spool | Append-only and wear-distributed, with recoverable metadata. No metadata update for each packet. Size after the firmware, OTA and VADNet model partitions (less than 24 MB, about 3 h or less). Check that capture has no overruns during a flash erase or write. |
| Overflow policy | When the spool is full, the device drops the oldest chunks and counts them. |
| Screen | "● REC" mark, which changes only when the state changes. No live transcript on battery. |
| BLE | Compare BLE and Wi-Fi upload energy in layer 2. BLE is not automatically better. |

### Control rules, in priority order

1. Physical mute (a button toggle). Nothing can override it.
2. Face down (IMU): stop.
3. Night schedule (RTC): deep sleep.
4. Meeting windows from the hub calendar: continuous mode. This rule cannot override 1–3.

The IMU can detect the orientation. It cannot reliably detect "in a drawer", so the plan does
not use that rule.

### Estimated battery use per day (hypothesis, to replace with measurements)

| Mode | Average current | Hours | mAh/day |
|---|---|---|---|
| Quiet room, duty-cycled | ~5 mA | 8 | ~40 |
| Speech: record + encode | ~35 mA | 3 | ~105 |
| Wi-Fi bursts | — | — | ~15 |
| Night deep sleep | ~0.1 mA | 13 | ~2 |
| Total | | | ~160 |

The 5 mA and 0.1 mA values need very low sleep current for the full board (PSRAM, regulators,
mic, touch, USB bridge). They are not verified.

## VAD shadow mode (firmware 0.9.0, built)

The user approved this step on 2026-11-10 ("how can we go to sleep properly and auto wake ... and not
miss anything that is important").

- ESP-SR VADNet `vadnet1_medium` runs on each 20 ms frame in the audio task (`src/vad.c`). It needs
  512-sample (32 ms) calls, so `vad_frame` collects samples and keeps the last decision. Settings:
  `VAD_MODE_0` (the most sensitive), minimum speech 128 ms, minimum noise 1000 ms.
- The model is in the `model` partition (`srmodels.bin`, 288 KB, made by the esp-sr `movemodel.py`).
  An OTA update does not write this partition. Without the model the device records as before, with no
  VAD data.
- The device still records and uploads all audio. Each chunk carries a speech mask and the keep mask of a
  simulated gated recorder (2 s pre-roll, 15 s hang-over); see "Voice activity" in `protocol.md`. The
  spool file keeps the masks after the audio, so a chunk keeps them across a restart.
- The audio status line (every 60 s) adds `vad: cpu …%, max … us, speech …%, keep …%`. Each full chunk
  also logs its masks (`audio: vad session … seq …`).
- `firmware/tools/vad_report.py` reads the hub data folder (read-only) and reports the speech share, the
  kept share, the words in dropped audio (whisper-cli runs again with word times on the chunks that have
  dropped frames), clipped starts, and the flash and radio bytes that gating saves.
- Gate decision rule: switch the gate on only when the report shows no missed words that matter over
  several days of normal use.

## Speech gate (firmware 0.15.0, built)

The battery used about 11.5 % each hour (8.5 to 9 h) with DFS (80 to 240 MHz) and Wi-Fi maximum modem
sleep. Opus encoded all audio, also silence. The gate removes this work.

- The audio task runs the filter and VADNet first on each 20 ms frame. Then it puts the frame into a PCM ring
  of `PREROLL_FRAMES` frames (PSRAM). The frame that leaves the ring is encoded only if a speech frame is in
  the ring (pre-roll) or came less than `HANG_FRAMES` frames before it (hang-over). Other frames are empty
  frames (`[u16 0]`), so the timeline stays exact (see "Audio frames" in `protocol.md`).
- Because of the ring, the audio is 500 ms late. Thus the pre-roll is always in the same chunk, and a mark
  adds the 500 ms in the ring to its offset. When recording stops, the device encodes the frames in the ring
  before the final chunk.
- Without the VADNet model, the device encodes all frames, as before.
- The `CPU_FREQ_MAX` lock is held only from the end of an I2S read to the end of the filter, VAD and encode
  work of that frame. While the task waits for the next frame, DFS can go to 80 MHz.
- A full chunk buffer (72,000 bytes) closes the chunk early (`early chunks` in the log and `early_chunks` in
  health). The device never drops a frame from a chunk. A chunk that the device cannot queue counts in
  `lost_chunks`.

Sensitivity settings (constants, change and build):

| Constant | File | Default | Effect |
|---|---|---|---|
| `VAD_MODE` | `src/vad.c` | `VAD_MODE_0` | `VAD_MODE_0` is the most sensitive. `VAD_MODE_1` to `VAD_MODE_4` need more certain speech (fewer false starts, more missed quiet speech). The log at start gives the threshold of the mode. |
| `VAD_MIN_SPEECH_MS` | `src/vad.c` | 128 | Speech must last this time before VADNet says speech. |
| `VAD_MIN_NOISE_MS` | `src/vad.c` | 1000 | Noise must last this time before VADNet says noise (an internal hang-over). |
| `PREROLL_FRAMES` | `src/audio.c` | 25 (500 ms) | Audio kept before the first speech frame. Also the audio delay. |
| `HANG_FRAMES` | `src/audio.c` | 75 (1.5 s) | Audio kept after the last speech frame, added to `VAD_MIN_NOISE_MS`. |

Expected CPU load (health fields, at 240 MHz, to measure): `cpu_vad_pm` stays the same as in shadow mode
(about 200), because VADNet runs on all frames. `cpu_opus_pm` goes down to about the shadow-mode value
(about 340) × `audio_keep_pm` / 1000. For example, at 30 % kept frames it is about 100. `cpu_filter_pm` adds
the ring copy (a few per mille). With the example values in "Health" of `protocol.md` (filter 120, Opus 340,
VAD 210), the audio core is busy about 43 % of the time at 30 % kept frames, against 67 % before. Measure
the battery % per hour over long runs with the same use as before.

## Recording control (firmware 0.15.0)

The device keeps the reasons why recording is off. It records only when there is no reason.

| Reason | Set by | Cleared by |
|---|---|---|
| Mute | The mute action (key, back tap, Settings › Recording). Stored in NVS (`muted`), so it stays after a restart, a sleep and a power off. | Only the mute action. This also clears "face down". |
| Face down | Face down for 2 s (with "Stop when face down" on). | Face up for 2 s, or the setting off. |
| Update | Update mode. | The end of update mode. |
| Power | Restart, sleep and power off. | The next start. |

Thus face up never starts a recording that the user muted.

## Sleep sequence (firmware 0.15.0)

Before 0.15.0, sleep waited a fixed 3 s and then slept with Wi-Fi, BLE and the audio task on. GPIO7 (the IMU
and the fuel gauge interrupt, low = active) was always in the wake mask, so a low line woke the device at
once. The sequence now is:

1. Show the moon in the status row.
2. Stop recording. The audio task encodes the ring, queues the final chunk, switches the mic off and gives an
   acknowledgement (at most 5 s).
3. Park the net task. It writes the upload queue to the flash outbox and then stops (at most 20 s). If the
   net task does not stop in time, the caller writes the queue to flash.
4. Stop BLE (`nimble_port_stop`, `nimble_port_deinit`) and Wi-Fi (`esp_wifi_stop`).
5. Read the IMU interrupt sources (`TAP_SRC`, `WAKE_UP_SRC`, `D6D_SRC`) to release the latched INT1, and log
   the GPIO7 level before and after.
6. Wait at most 5 s until the keys are released. Log the levels of the keys and GPIO7. Put a key or GPIO7 into
   the ext1 mask only when its line is high. The power key is always in the mask.
7. `esp_deep_sleep_start`.

Restart and power off use steps 2 and 3. After an OTA install, the net task stops the audio and writes the
upload queue to flash before `esp_restart`, so the last recording is not lost. The boot report (reset
reason, wake cause, ext1 pins, core dump summary) did not change.

The fuel gauge can also pull GPIO7 low (for example a state-of-charge pulse) during deep sleep. This wakes the
device. The boot report shows `wake=ext1 pins=0x80` for this case.

## Next step: quiet mode (design, not built)


Quiet mode lowers the power when nobody speaks. It needs the gate on (dropped frames are not stored).

| Item | Behaviour |
|---|---|
| Enter | The gate was closed for N minutes (start with N = 10; a setting). Not while USB is connected and the screen is in use, not during an update. |
| Wi-Fi | `esp_wifi_stop()`. Chunks stay in the flash outbox. A timer starts Wi-Fi every 15 minutes for one check-in and the outbox, then stops it again. |
| BLE | Keep the bond. When connected, ask for a slow connection (interval 1 s, peripheral latency 4, `ble_gap_update_params`); when not connected, advertise every 2 s. |
| CPU | Power management (`esp_pm_configure`) with a 80 MHz maximum. The I2S driver holds an APB lock, so the mic does not stop; light sleep is not possible while the mic runs. First measure that Opus and VADNet keep up at 80 MHz (overflows stay 0). |
| Mic, VAD | Always on. The 2 s pre-roll ring (Opus packets, about 4 KB, PSRAM) is always filled. |
| Wake | VAD speech: the gate opens with the pre-roll, the CPU goes back to 240 MHz, Wi-Fi starts, BLE asks for the fast interval. A touch or a button also wakes. |
| Screen | No refresh in quiet mode; one partial refresh on enter and on wake. |
| Telemetry | Time in quiet mode, number of wakes, wake latency (speech to first upload), and the fuel gauge average current in each state. |

Measure before and after with the fuel gauge on battery (long runs) and a power meter (states). Quiet
mode is worth it only if it saves a measurable share of the daily energy.

## Hub design

- Transcription runs on the hub machine with local Whisper (whisper.cpp). Whisper starts only when
  audio chunks wait for transcription and stops when the queue is empty, so it uses no memory
  when idle (user, 2026-11-09: "use local whisper but do stop on idle").
- Summaries and to-dos use `codex exec -m gpt-6-luna` (small, fast model; the user's Codex login).

- Batch transcription of each uploaded chunk: local Whisper, or Deepgram pre-recorded.
- Text is about 60 KB per hour of speech. Keep all text, and make a daily summary.
- Keep the audio for 7 days after its transcript exists, then delete it automatically. The same
  rule applies to backups. If Deepgram is used, its data retention must also be set to the
  same limit or less.
- Access to transcripts and audio needs authentication.

## Profiling and telemetry

There are three measurement tools, and each one has a different job:

| Tool | Job |
|---|---|
| External power meter (Nordic PPK2 or an INA226 shunt on the battery line) | The real current of each state at high time resolution: sleep, sample, record, encode, Wi-Fi connect, upload, screen refresh. Used on the bench in layer 2. |
| BQ27220 fuel gauge | Long-term trends: battery %, voltage, average current, remaining capacity, mAh per day. Its readings update about every 1 s and its average current has a 14.5 s filter, so it cannot measure a 0.3 s state. A regression of daily energy against time-in-state over many days gives a second estimate of the cost of each state. |
| Labelled recordings | VAD quality: missed utterances, clipped starts, false triggers, transcription quality. The hub cannot see missed speech, so this needs a test set with known speech. |

**Device telemetry, one record for each upload burst:**

- Firmware version and configuration ID (mode, bitrate, duty cycle, VAD threshold).
- Time in each mutually exclusive power state: deep sleep, light sleep, sampling, recording,
  Wi-Fi. Activity timers for things that overlap (encoding, flash writes, screen refresh) are a
  separate list.
- Battery: percent, voltage, average current, remaining capacity, charge state.
- Audio: VAD triggers, seconds of speech recorded, Opus bytes, capture overruns, worst encode
  latency, gaps in capture with timestamps.
- Storage: PSRAM peak and current fill, flash spool use, chunks waiting for ack, chunks dropped.
- Wi-Fi: connect time, RSSI, bytes, throughput, failed connects, retries.
- CPU: free heap and PSRAM, reset reason, uptime.

**Hub telemetry, one record for each chunk:** arrival delay, transcription time and cost, words,
empty transcripts, storage used.

**Views:** a hub page with battery % over time, mAh per day, projected battery days, speech hours
per day, VAD results from the test set, storage per day, and the values of each firmware
configuration side by side.

## Firmware updates (OTA)

Updates must be easy: no USB cable and no tools on the PC.

- The hub keeps the newest signed firmware image. At each upload burst, the device reports its
  version and the hub replies when a newer image exists. No extra Wi-Fi time is necessary.
- Settings > Device shows "Update available" with one "Update now" button. An option "Auto-update
  at night" installs it during the night pause.
- Two app slots (ESP-IDF OTA, A/B). The new image goes into the slot that is not in use. After the
  restart, the new firmware must reach the hub one time and confirm itself. If it does not, the
  bootloader rolls back to the old slot automatically (`CONFIG_BOOTLOADER_APP_ROLLBACK_ENABLE`).
- The device does not start an update below 20% battery unless USB power is connected.
- The device keeps the audio that waits for upload in the flash spool during the update, so an
  update loses no audio.
- USB flash stays possible as the recovery path.

## Build layers

1. On USB power: continuous capture, Opus, chunk upload with IDs and acks, transcription,
   transcript, telemetry records.
2. Measurement: power meter per state, CPU clock and codec benchmarks, labelled VAD test set,
   BLE versus Wi-Fi. Choose the capture mode from the data.
3. Battery operation: the chosen capture mode, control rules, flash spool, overflow policy.
4. Hub features: summaries, search, action items, then the character.

## Open points

- The battery capacity. `DesignCapacity` in the gauge is a configured value, not a measurement.
  The real value comes from the battery label or a full charge and discharge cycle.
- The mic pins and the IMU axes are not yet confirmed on hardware.
- Which power meter to use.
- Consent rules where the device is used.
