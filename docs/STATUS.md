# Status and resume notes

Last update: 2026-11-12. Read this file first when you resume the project.

## Project in one sentence

resticky is custom software for the Seeed reTerminal Sticky (E1005) e-paper device: continuous
conversation recording with transcripts, summaries and to-dos, on a hub (laptop now, phones next), with
low power and storage use that we can profile.

## What runs now

| Part | Where | State |
|---|---|---|
| Firmware 0.14.9 | `firmware/` (ESP-IDF via PlatformIO, LVGL 9, NimBLE) | Current release. Records Opus 16 kbps in 30 s chunks into a flash outbox (LittleFS on the 22 MB `spool` partition), uploads from it over Wi-Fi HTTP or Bluetooth LE and deletes a chunk only after the hub answers 200 or `ack`, shows transcripts, to-dos (with pages), stats, settings, and a pairing page with the BLE passkey. |
| BLE test hub | `firmware/tools/ble_hub.py` | `uv run firmware/tools/ble_hub.py --out <folder>`. Registers a BlueZ agent (KeyboardOnly), pairs (type the passkey from the e-paper on stdin), stores chunks as `<device>/<session>/<seq>.opus`, sends acks, prints KB/s for each chunk. Forget the bond: `bluetoothctl remove AA:BB:CC:DD:EE:FF`. |
| Laptop hub 0.7.0 (check-in logs the outbox, keeps the health history; transcript items have `place`) | `app/desktopApp` (Kotlin Multiplatform, JVM) | systemd user service `resticky-hub`, runs `~/.local/opt/resticky-hub/bin/resticky-hub` (installed with `./gradlew :desktopApp:installDist`, then copied). Port 8787, mDNS `_resticky._tcp`. Weather: fixed city only, set with `-city <name>` in the service `ExecStart` (no city: no forecast); settings and the last forecast in `~/.local/share/resticky/weather.json` (`-weather <file>`). |
| Data | `~/.local/share/resticky/data` | Sessions, chunks, transcripts. Backup before the hub change: `data.backup-go-20261110`. |
| Contract tests | `app/contract` | `app/contract/run.sh <hub binary>`; 23 tests. `run.sh` first checks that the hub does not start without `-token`, then starts it with a fixed test `-token`. |
| Test phone | Phone hotspot XL, 192.168.1.20 | Wireless adb (Android Wireless debugging, found by mDNS): `adb -s $(adb devices | awk '/_adb-tls-connect/{print $1; exit}') shell …`. No cable. |

## Firmware facts learned the hard way

- Internal RAM is small. Large buffers go to PSRAM (`EXT_RAM_BSS_ATTR`, `CONFIG_SPIRAM_ALLOW_BSS_SEG_EXTERNAL_MEMORY`).
  The audio task stack (32 KB, Opus) is in PSRAM. Every `xTaskCreate` result is checked: from 0.2.4 to
  0.5.0 the audio task was not created and no audio was uploaded (2026-11-09 14:36 to 2026-11-10 10:35).
- The audio task logs `frames …, encode failures …` every 60 s; check it after a firmware change.
- Internal RAM budget (0.7.0): 281 KB at start; Wi-Fi and net use about 108 KB, I2S DMA 33 KB, the BLE
  controller and NimBLE host task about 41 KB; 95 KB stays free with Wi-Fi up and BLE on (low mark 85 KB).
  LVGL allocates from PSRAM (`CONFIG_LV_USE_CUSTOM_MALLOC`, `src/lv_mem_psram.c`) and the panel buffer is
  in PSRAM (the SPI driver copies each 4000-byte piece). Before this, LVGL and the panel buffer used 108 KB
  internal, only 27 KB was free, and the BT controller init asserted (`Malloc failed`, boot loop).
  `ble_init` now refuses to start below 64 KB internal free, so a low-memory build records without BLE.
- The BT controller allocates internal DMA memory and asserts (no error return) when it cannot.
- `sdkconfig.sticky` is generated and git-ignored. `sdkconfig.defaults` applies only to options that are not
  in it yet: delete `sdkconfig.sticky` after a change to `sdkconfig.defaults` for a choice or a bool that
  was already set (BT, LVGL malloc).
- BLE (2026-11-10, laptop BlueZ, Intel adapter): LE 2M PHY, MTU 517, connection interval 45 ms (BlueZ
  default; the device does not ask for another one). 45 to 60 KB/s device → hub; a 30 s chunk (30 to 52 KB)
  takes 0.55 to 1.0 s, the ack comes 40 to 180 ms later. Audio overflows stayed 0.
- Wi-Fi upload of a 30 s chunk: 130 to 980 ms with BLE advertising, 130 to 280 ms with an idle BLE
  connection, 290 to 680 ms while a BLE hub is linked. No change that the measurement can show.
- The fuel gauge cannot measure the device current while USB is connected and the battery is full: current
  and average current read 0 mA. BLE power is not measured yet (needs a run on battery).
- Pixel test phone: at 12:25 on 2026-11-10 it announced `_resticky._tcp` (`Android_YUM9ARD9`), and the
  device selected it as the Wi-Fi hub until a request failed. The device uses the first mDNS answer.
- Start order: `wifi_init()` (driver), `mdns_init()`, tasks, then `wifi_start()`. Another order breaks the
  mDNS hub search.
- The panel is an SSD1677 (BUSY = 0 after reset). Refresh as the stock firmware does: full `0x22 = F4`,
  partial `0x22 = FC`, panel stays powered. Full about 1.55 s, partial about 0.47 s.
- Fonts: `firmware/tools/gen_text_fonts.py` makes hinted 1-bit Montserrat (SemiBold body, Bold titles)
  with FreeType. Anti-aliasing is off.
- Chinese text (0.15.6): `cjk_18` is a 1-bit Noto Sans CJK SC Medium font at 18 px (OFL, license in
  `firmware/src/cjk_18-OFL.txt`). It has 6950 characters: GB2312 level 1 (common Simplified), Big5 level 1
  (common Traditional), a list of Cantonese characters, CJK punctuation and full-width forms. It is the LVGL
  fallback of all text fonts and of `hand_28` and `hand_40`. A character that no font has shows as a box.
  The font adds 344 KB to the app (2794533 to 3138941 bytes, 74.8 % of the 4 MB slot). Make it again with
  `uv run firmware/tools/gen_text_fonts.py <Montserrat dir> mockups/assets/fonts /usr/share/fonts/noto-cjk firmware/src`.
- The device cuts hub text that is too long for its buffer at a UTF-8 character boundary. A to-do has
  96 bytes (32 Chinese characters), a title 64 bytes, an agenda title 49 bytes.
- Each refresh uploads the panel frame to the hub: `GET /api/screen?device=a1b2c3d4e5f6` (a PNG).
- Wi-Fi: up to 5 saved networks over USB serial: `wifi <ssid>|<password>`, `wifi-forget <ssid>`. Opening
  the serial port restarts the device.
- The device clock is set with SNTP. To-do changes are `PUT` with the time of the change.
- Outbox test (2026-11-10): hub stopped for 80 s; 3 chunks waited in flash and arrived 6 s after the
  hub started again. The I2S DMA buffer is about 1 s (16 x 1000 frames); the audio status line counts
  overflows (0 in the test).
- Hub mDNS: JmDNS stops announcing after a Wi-Fi drop on the laptop ("Network is unreachable"). The hub
  checks its announcement every 30 s and creates it again. A 48-minute outage on 2026-11-10 showed the
  outbox working: 97 chunks waited in flash and arrived in about 30 s.
- VAD (0.9.0, shadow mode, see `plan-recording.md`): the VADNet model is in the `model` partition and
  OTA does not write it. After a change of the esp-sr model selection: `pio run`, then
  `~/.platformio/penv/.espidf-5.5.3/bin/python managed_components/espressif__esp-sr/model/movemodel.py -d1 sdkconfig.sticky -d2 managed_components/espressif__esp-sr -d3 .pio/build/sticky`
  and `uvx esptool --port /dev/ttyACM0 write-flash 0x820000 .pio/build/sticky/srmodels/srmodels.bin`
  (in `firmware/`). Flashed on 2026-11-10. esp-sr adds 5.7 KB of internal `.data` (`sigmoid_table_5_8`).
- While a BLE hub is linked, chunks go on BLE and carry no VAD masks (the hubs reject a longer BLE chunk).
- Refresh rule as the stock firmware: partial for every change, full after 20 partials or for the Off
  screen; no idle timer.
- Firmware 0.15.0 (2026-11-11, built, not flashed, not measured). Power and integrity changes:
  - Speech gate: VADNet runs before Opus. Only speech, 500 ms pre-roll and 1.5 s hang-over are encoded.
    Silence is empty frames. See "Speech gate" in `plan-recording.md`. Health has `audio_keep_pm`,
    `early_chunks` and `lost_chunks`.
  - BLE controller modem sleep with the main crystal. The device asks for a 150 to 200 ms interval (latency 4)
    and for 15 to 30 ms during photo, lists and OTA frames.
  - BLE-only mode: new frames 23 `lists` and 24 `todo_set`. Wi-Fi stops after 60 s of a linked phone hub
    with lists, and starts again after 60 s without the link, or for 5 minutes for an update check, an
    install, a hub test or Settings › Wi-Fi.
  - Sleep, restart and power off stop the audio and write the upload queue to flash with acknowledgements.
    Sleep stops BLE and Wi-Fi and puts GPIO7 into the wake mask only when it is high. The OTA restart also
    writes the queue to flash first.
  - Mute is stored in NVS and face up does not start a muted recording. A black dot in the status row shows
    that the mic records.
  - Hub password: the phone app makes a 64-hex password and sends it in the new BLE frame 26 `hub_token`.
    The Sticky keeps it in NVS, sends `Authorization: Bearer <token>` to the hub and checks the hub with an
    HMAC proof from `GET /api/hello?nonce=…` before it uses the hub. A hub with a password answers `401` to a
    request without it. Laptop hub: `-token <hex>` or `-token-file <path>`; it does not start without one
    (`-insecure` only for local tests). See "Hub password" in `protocol.md`.
  - Before the first build of 0.15.0 in a checkout, delete `firmware/sdkconfig.sticky`. The old file has
    `# CONFIG_BT_CTRL_MODEM_SLEEP is not set`, and `sdkconfig.defaults` does not change a set value.
- Firmware 0.15.2 (2026-11-11, built, not flashed, not measured): two sleep modes. See "Sleep modes" in
  `plan-recording.md`.
  - "Sleep" (default: power key press) is light sleep. The screen keeps the page, the status row shows the
    moon. Any key wakes the device at once, with no restart and no boot animation. Wi-Fi and BLE stop
    before the sleep and start again after it; the phone connects again. Recording starts again if it is not
    muted (new recording-off reason "sleep"). A timer each 10 minutes checks the night window and changes
    to deep sleep when the night starts.
  - "Deep sleep" is a new button action (Settings › Buttons) and a button in Settings › Device. The night
    window also uses deep sleep. The deep sleep screen is `ui_show_deep_sleep(wake_minute)` (night end in
    minutes after midnight, or -1); the body is a placeholder (moon and full refresh). Wake restarts the
    device with the boot animation.
  - Look at on the device: the log lines `light sleep: woke after N s (M night checks)`,
    `ble: ready` after the wake (BLE starts again), `touch: wake: ok`, and the boot report for
    `sleep_step=11` to `19` (a hang or crash in light sleep; 16 = during the sleep).
- Firmware 0.15.5 (2026-11-12): the release of the 0.15.4 changes below (the v0.15.4 CI run failed), with the
  hub fixes, the summary backoff and the to-do rules.
- Firmware 0.15.4 (2026-11-12, built, not released, not flashed, not measured): power and loss visibility.
  - BLE asks for the fast interval while audio chunks wait in the outbox, and for the slow interval when
    the outbox is empty. Health: `ble_fast_s`, `ble_tx_bytes`.
  - E-paper: the SSD1677 goes into deep sleep after each refresh; each refresh does a reset and the init
    again and writes both RAM planes (old image from `shown_fb`). Look at on the device: partial refreshes
    show no ghost and no wrong pixels; the log gives `soft reset: N ms` before each refresh.
  - Audio: the CPU stays at 80 MHz in silence (filter and VAD); 240 MHz only while it encodes speech, and
    10 s after a frame that took more than 15 ms. Health: `audio_overruns` (must stay 0 or near 0),
    `overflows` must stay 0.
  - OTA confirm: hub check-in AND (an audio chunk OR recording off with a working mic). A muted device no
    longer rolls back a good update.
  - Shutdown save: restart, deep sleep, power off, update restart and rollback require "audio stopped and
    queue on flash", try again one time, then record the loss in NVS. Health: `shutdown_lost`, also
    `shutdown_lost=<n>` in `boot`. Health now also has `dropped_chunks`.


## Android app facts (layer 4, Wi-Fi part, 2026-11-10)

- Build: `cd app && ./gradlew :androidApp:assembleDebug`. Install: `adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk`.
  Release: `./gradlew :androidApp:assembleRelease` (R8 code and resource shrinking, keep rules in `androidApp/proguard-rules.pro`,
  signed with the debug key, so a debug and a release build can replace each other; 73 MB against 118 MB). CI publishes the
  release build as `resticky-android.apk` (the phone updater uses it) and the debug build as `resticky-android-debug.apk`.
  The build needs Gradle 9.6.1 (AGP 9.5.1) and compileSdk 36 (Compose BOM 2026.06.01; newer BOMs need API 37).
- The hub service is exported only to holders of `android.permission.DUMP` (adb shell), for tests with a locked phone:
  `adb shell am start-foreground-service -n dev.resticky.app/.HubService --ez mdns false`. With mDNS on, the
  Sticky can move from the laptop hub to the phone.
- Contract tests against the phone: `./gradlew :contract:test -PhubUrl=http://192.168.1.20:8787 -PhubData=adb:dev.resticky.app`
  (the test with the fake engines is skipped).
- whisper.cpp: `no_timestamps = true` drops the start of some chunks; the app keeps timestamps on. Opus is decoded with
  libopus at 16 kHz (MediaCodec took 2.4 s for each 30 s chunk). The app process is in the `foreground` cpuset
  (CPUs 0-6); 4 threads are the fastest there.
- All native libraries are 16 KB aligned (`ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON`, static libomp).
- Gemini Nano refused a 38k-character transcript ("policy check", ErrorCode 4). When Nano fails with the app open,
  Gemma 4 E2B makes the summary.
- BLE: Android drops notifications longer than 512 bytes (`gatt_process_notification: value.len larger than
  GATT_MAX_ATTR_LEN, discard`). The app asks for MTU 515, so the Sticky sends at most 512 bytes in each one.
- BLE pairing for tests with a locked phone: the passkey goes to the service with
  `adb shell am start-foreground-service -n dev.resticky.app/.HubService --es passkey 123456`.
- Screens: Montserrat static instances made from the variable font with
  `uvx --from fonttools fonttools varLib.instancer Montserrat.ttf wght=500 --static`; icons are the Tabler SVGs
  as vector drawables (`res/drawable/ic_*.xml`).
- Low power (2026-11-10): the Sticky is a Companion Device Manager association (Hub › ⌄ › Pair a new Sticky). The app
  links over BLE only to the associated Sticky. For tests without the phone screen:
  `adb shell cmd companiondevice associate 0 dev.resticky.app AA:BB:CC:DD:EE:FF`. There is no permanent wake lock;
  `resticky:job` is held only while whisper or a summary runs. The HTTP server and mDNS run only while the phone is
  on Wi-Fi. Settings › Transcribe: right away, every 15 minutes or only while charging (WorkManager). minSdk is 36.
- Weather, calendars, Wi-Fi from the phone (2026-11-10, hub side only; the firmware part is not built yet): see
  "Time", "Weather", "Agenda" and "Wi-Fi from the phone" in `protocol.md`. BLE frame types 8 to 13. Hub → device
  frames are written with write requests, one piece (at most MTU - 3, at most 512 bytes) at a time.
- Weather on the phone: the HTTP client is Ktor CIO (`app/shared`). Settings and the last forecast are in
  `files/weather.json`. "From phone" needs `ACCESS_COARSE_LOCATION`; Android gives the location only while the
  app is on screen (the hub service is not a location service), so the app stores the rounded location when it
  opens and the timed refresh (Weather › Refresh, default 30 minutes) uses the stored one. Test on 2026-11-10: fixed city "Lisbon" fetched on LTE.
- Calendars: `READ_CALENDAR`, asked when the Calendars page opens. The selection is in the `calendars`
  preferences. Test on 2026-11-10: 12 calendars selected with adb, the agenda had 4 events.
- Wi-Fi scan needs `NEARBY_WIFI_DEVICES` (declared `neverForLocation`); the list shows only 2.4 GHz networks.
- Health (2026-11-10): firmware 0.11.x sends `health` in each check-in. The hub keeps one sample a minute for 7 days
  in `<device>/health3.bin` (see "Health" in `protocol.md`); the Health tile on the Hub page shows the charts. First
  sample on the Pixel from firmware 0.11.2 over BLE: 61 KB internal RAM free, 852 bytes BLE stack free, 34 °C.
- Hub fixes (2026-11-12, built and unit-tested, not tested on a device):
  - Chunks: a body over 1 MB gets `413` and is not stored (before, the hub cut it and answered `200`); a body
    that is not whole Opus packets gets `400`. On BLE the same chunks get no `ack`. Contract test.
  - Transcription: a failed chunk waits (30 s, doubling, at most 1 hour) and the other work goes on; after 8
    tries the chunk has no text and the transcript shows "N parts not transcribed". State in `meta.json`
    `chunk_retry`.
  - Speakers: a result for an older input (a chunk came during the run) is dropped and the run repeats.
  - Store: the sessions stay in memory (checked with the `meta.json` time and size, no read when unchanged);
    the phone screens and the BLE lists update on a store change, not by polling.
  - Speakers window 10 minutes (was 20 on the phone, the whole session on the laptop), because sherpa-onnx
    has no streaming diarization. Laptop measurement: 69 MB Java heap peak for 10 minutes against 125 MB for 20.
  - Health keeps `lost_chunks`, `early_chunks`, `spool_write_errors`, `spool_corrupt`, `audio_keep_pm`,
    `ble_fast_s`, `ble_tx_bytes` and `shutdown_lost` (`health3.bin`), and the Health screen shows one line when
    a loss counter went up in the last 7 days. The firmware does not send `ble_fast_s`, `ble_tx_bytes` and
    `shutdown_lost` yet.
  - The summary choice "Wait for the laptop" is removed (it always failed).
- Places (2026-11-10): the Places tile on the Hub page names places on the phone; new live sessions get the place name (see
  "Places" in `protocol.md`). Needs precise location, and "Allow all the time" for tags while the app is closed.
  Not tested on the phone yet: the permission dialogs need the user.
- Pages (2026-11-10, board `mockups/app/board-app.png`): tabs Hub, Transcripts, Snap (raised camera button in the
  middle, camera design A), To-do, Photos. Hub: the Sticky card (tap: Sticky settings; ⌄: "Choose a Sticky") and
  tiles Sticky, Weather, Places, Calendars, Health, App settings. Sticky settings: pairing, Clock (face, 12/24 h),
  Weather (style, location, units, refresh), Feedback on the Sticky (`sound`: off, beep, buzz), Wi-Fi networks, Health, firmware,
  Forget. App settings: hub switch, Models, Pipeline, Places, Calendars, People, Updates, Transcribe, Hubs. The CDM sheet
  shows all associations, but the app links only to the first one.
- Transcript page: summary, to-dos, Marks chips, timed lines with speaker chips (tap: name the speaker), photos in
  the timeline, and the player (own Opus decoder and `AudioTrack`; Clean up, Skip silence, Crisp voice). See
  "Segments", "Speakers", "Playback view" and "Photos" in `protocol.md`.
- People (2026-11-11, built, not tested on a device): the hub keeps the voice of each session speaker in `meta.json`
  (`voices`), and a people library in `people.json`. After diarization it names a speaker when the cosine to a
  person is ≥ 0.62 and 0.05 above the next person; from 0.5 it only suggests the name (the first chip on
  the name sheet). A rename on the phone teaches the library. Automatic (2026-11-11, built, not tested on a
  device): after each diarization the hub groups the voices of all sessions, makes "You" from the voice in the most
  sessions (≥ 5 and ≥ 40 %) and an unnamed "Person N" for each voice in ≥ 3 sessions; after each summary it takes
  speaker names from the same model call when the transcript shows them (evidence quote, attendee or in the
  transcript, never "You", a user name wins). App settings › People: the list, delete, and
  the optional "Your voice" (45 s phone recording). The name sheet offers the attendees of overlapping calendar events. The
  thresholds are start values: tune them on the test set. See `plan-people.md`.
- Speakers on the Phone hotspot XL (2026-11-10): a 22.5-minute session took 98 s for the first 20-minute window and
  20 s for the rest (5 speakers). Laptop: RTF 0.044. Models are in `~/.local/share/resticky/models` (laptop) and in
  the app external files `models/` (phone).
- Denoise benchmark on the Pixel (60 s of session audio, one thread, 16 kHz, 20 ms blocks): RNNoise (with 16→48 kHz
  resampling) 1.4 % of one core (RTF 0.015); DPDFNet 2 (sherpa-onnx, DeepFilterNet family) 14.8 % (RTF 0.15; a second
  run 23.5 %); GTCRN 5.6 %. The app keeps DPDFNet 2 (better quality, no extra native library: sherpa-onnx is already
  in the app). DeepFilterNet 3 (libDF, Rust) was not measured: the permission system blocked the cargo build.
- Updates (2026-11-11): only by hand (Check for updates); firmware goes to the Sticky over BLE (frames 18–21).
  See "Firmware updates" in `protocol.md`.
- Firmware push test (2026-11-11, phone 0.14.5 build, Sticky 0.14.4, release 0.14.5): check and download work
  (2.78 MB in about 1 s, streamed to `files/firmware`). The BLE push reached `ready`, then 60 % at about 4 KB/s, and
  then the Sticky stopped answering a write for 30 s (Android `gatt_rsp_timeout`, status 133) and the link dropped.
  Earlier runs also got ATT status 17 (insufficient resources) from the Sticky; the phone now retries a failed write
  (10 times, 200 ms apart) instead of dropping it, which corrupted the frame stream before. The Sticky still reports
  0.14.4. Likely firmware cause: the OTA flash writes run inside the NimBLE GATT callback (and `net_ota_progress`
  waits on the net lock), so the host stalls while Wi-Fi and the audio spool also use the flash.
- Firmware 0.14.6 (not flashed on 2026-11-11) moves the OTA writes to the net task and adds update mode, the 16 KiB
  window and `ota_cancel`; the phone push now follows "Firmware push" in `protocol.md`. Not tested end to end yet.
- Test hook (adb shell only, the service needs `android.permission.DUMP`):
  `adb shell am start-foreground-service -n dev.resticky.app/.HubService --es firmware check|update|cancel`. The steps log
  under the tag `resticky` (`sticky update state=…`, `download …`, `ble ota_status …`).
- Phone tests that need the device: `./gradlew :androidApp:assembleDebugAndroidTest`, install both APKs with
  `adb install -r` (never `connectedAndroidTest`: it uninstalls the app and its data), then
  `adb shell am instrument -w dev.resticky.app.test/androidx.test.runner.AndroidJUnitRunner`. `EngineTest` plays
  sound: set the media volume to 0 first. Do not install while the app is in the foreground
  (`adb shell dumpsys activity activities | grep ResumedActivity`).
- BLE reconnect: the app connects again 3 s after the link closes. Firmware 0.10.0 fixed the reconnect fault (the
  Sticky called `ble_gap_set_data_len` in the connect event; the controller did not answer and the link dropped).

## Conversations (hub, 2026-11-11)

- A session is one recording run of the Sticky. A conversation is a view over the sessions. The stored sessions do
  not change.
- Consecutive sessions of one device are one conversation when the capture gap is less than 10 minutes.
- A silence of 5 minutes or more with no speech (from the segment times) splits a session into two conversations.
- Each conversation gets one summary. The hub stores it on the first session of the conversation: in the top-level
  fields when the conversation starts at 0 ms, else in `splits` (key: the start in ms).
- A to-do ID is `<session>-<minute × 100 + index>`. The minute is the start of the conversation in the session, so
  the IDs of old data do not change.
- Sessions are `open`, `provisional` (not complete, no arrival for 20 minutes) or `final` (final chunk and no
  seq gap, or no arrival for 24 hours). The times are arrival times. A late chunk opens a session again, and the
  pipeline does its work again. `started` follows the chunk with the lowest seq, with the clock anchor
  (`boot_id`, `boot_unix_ms`) of its boot; with no anchor the time is an estimate (`~`). See `protocol.md`.
- Each summary keeps the hash of its input (`input`). A summary for older input is not saved. A summary with no
  hash (old data) stays valid only for a conversation that is one whole session.
- After a new summary, the hub copies the done state of a to-do with the same normalized text.
- A repeated chunk with the same audio changes nothing. A repeated chunk with other audio is not stored (log
  `WARN chunk conflict`). The hub writes audio and `meta.json` to a temporary file and then renames the file.
- Old audio stays while its session has a pending chunk or a conversation that is not ready.
- Old data: the hub summarizes again each old conversation that has more than one session or a split. This uses
  the summary model once for each of them.

## Vocabulary (hub, 2026-11-11)

- `vocabulary.json` next to the data folder holds terms and fix rules (`Vocabulary.kt` in `app/shared`). See
  "Vocabulary" in `plan-transcription.md`.
- Each chunk gets a Whisper prompt from the vocabulary (phone `initial_prompt`, laptop `whisper-cli --prompt`).
  The log line `INFO transcribed` shows `prompt_chars` and `replaced`.
- Rules change new transcripts only. `chunk_rules` in `meta.json` records the changes for each chunk.
- Phone: long-press a word on the transcript page to correct it; App settings › Vocabulary lists the terms and
  rules. Not tested on a device yet.
- Automatic (2026-11-11, built, not tested on a device): calendar attendee names are terms; capitalized words
  that come back in 2 conversations become `auto` terms; the summary call proposes name-only corrections that the
  hub checks (candidate, not a common word, sound-alike) and applies with a dotted underline and Undo; a pair in
  2 conversations becomes a rule. See `plan-transcription.md`.

## Speakers of short replies (hub, 2026-11-11)

- Built, not tested on a device. Each word gets a speaker (the turn at the word midpoint; at an overlap a short
  turn of 2 s or less wins). Segments split at speaker changes. The diarizer keeps turns and gaps of 0.2 s. A short
  turn that interrupts a speaker does not join that speaker by time: a turn embedding (0.8 s or more) decides, else
  the speaker is "?" (`-1`). See "Speakers of short replies" in `plan-people.md`.

## Documents

- `plan-apps.md`: the approved plan (Kotlin Multiplatform apps, BLE and Wi-Fi links, engines, layers).
  Layers 1 (protocol, contract tests, JVM hub), 2 (flash outbox) and 3 (firmware BLE) are done.
- `protocol.md`: the hub HTTP API and the data format.
- `plan-recording.md`: recording, power and profiling plan.
- `plan-people.md`: speaker identity (voiceprints, people library, matching thresholds, privacy).
- `ui-screens.md`, `../DESIGN.md`, `../mockups/board.png`: UI. `../mockups/stock-reference/` (git-ignored)
  has the stock UI extracted from the backup.

## Next

1. Phone UI for conversations: the transcript list and the transcript page show one row for each conversation
   (`conversations()` in `app/shared`), not one row for each session. See "Conversations (hub)" above.
2. Phone UI: show "Speakers not available" when `speakers_failed` is true, and show the time of each to-do quote
   (`todo_quotes`).
3. `protocol.md`: write the conversation rules, the to-do IDs for split conversations, the chunk conflict rule and
   the new `meta.json` fields.
4. Firmware push from the phone: test 0.14.6 and later end to end.
5. BLE power on battery: idle advertising, connected idle, transferring, BLE off.
6. Transcription evaluation set (see `plan-transcription.md`).
7. Open decision: iOS builds (Mac over SSH or macOS CI, Apple Developer account).
