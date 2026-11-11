# Hub protocol

Status: 2026-11-11. This is the protocol between the Sticky and a hub on Wi-Fi (HTTP) and on Bluetooth LE
(see "Bluetooth LE"). The contract tests in `app/contract` check the HTTP part against each hub.

## Discovery

A hub announces the mDNS service `_resticky._tcp` on its LAN interfaces only (not on docker, bridge, veth,
virbr or loopback interfaces). The TXT record has `version=<hub version>`. The device uses the first IPv4
address and port that it finds.

## Hub password

A hub can have a password (the token): 32 random bytes, written as 64 lowercase hex characters.

- Phone hub: the app makes the password one time and keeps it in its preferences. App settings › Hub
  password shows the first 8 characters; tap it to copy the full password.
- Laptop hub: `-token <hex>` (copy it from the phone). A laptop hub without `-token` is open, as before, and
  logs a `WARN` at start.
- Delivery to the Sticky: BLE frame 26 `hub_token` (see "Message types"). The phone sends it each time the BLE
  link becomes ready. The link is bonded and encrypted (LE Secure Connections). The Sticky keeps the password
  in NVS (namespace `hub`, key `token`) and replaces the old one. A new password makes the Sticky find and
  check the hub again.
- Requests: when the Sticky has a password, each HTTP request to a hub has the header
  `Authorization: Bearer <token>` (also `GET /api/firmware/bin`).
- Hub check (no fake hub on the LAN): when the Sticky has a password and finds a hub (mDNS), it first sends
  `GET /api/hello?nonce=<16 random bytes, 32 hex characters>` with no `Authorization` header. The hub answers
  with `"proof":"<hex>"` in the hello JSON: hex(HMAC-SHA256(key = the 32 token bytes, message = the nonce hex
  text)). The Sticky uses the hub only if the proof is correct (constant-time compare). If not, it logs
  `WARN` and ignores that hub (BLE continues). A Sticky without a password does not check.
- Hub rule: when the hub has a password, each request except `GET /api/hello` must have the exact header
  `Authorization: Bearer <token>` (constant-time compare). If not, the hub answers `401`,
  `{"error":"need hub password"}`. `GET /api/hello` gives `proof` only when the request has a `nonce` and the
  hub has a password.

## Identifiers

A device ID and a session ID are lowercase hex, 1 to 16 characters (`^[0-9a-f]{1,16}$`). The device ID is
the Wi-Fi MAC address (12 characters). A session ID is a random 32-bit number (8 characters). A request
with a bad ID gets `400`.

## Endpoints

| Method and path | Request | Response |
|---|---|---|
| `GET /api/hello` | Optional `nonce` (see "Hub password"). No `Authorization` header necessary. | `200`, `{"service":"resticky-hub","version":"<v>"}`, and `"proof":"<hex>"` when the hub has a password and the request has a `nonce`. |
| `POST /api/checkin` | JSON `{"device","firmware","battery_pct","voltage_mv","current_ma","recording","outbox_chunks","outbox_bytes","dropped_chunks","wifi_saved","wifi_ssid","wifi_rssi","health"}`. The outbox fields count the chunks that wait in the device flash spool and the chunks that the device deleted because the spool was full. The Wi-Fi fields: see "Wi-Fi from the phone". `health` (optional, firmware 0.11 and later): see "Health". `marks` (optional): see "Marks". `sound`, `home` and `night` (optional): see "Display". | `200` after the marks are stored, `{"hub_version":"<v>","utc_offset_min":<n>,"clock_face":"<face>","weather_style":"<style>","hour_format":"24"|"12","sound":"off"|"beep"|"buzz","home":"<screen>","night":"off"|"HH:MM-HH:MM"}` (see "Time" and "Display"). `400` if the body is not JSON or the device ID is bad. |
| `POST /api/chunks` | Headers `X-Device`, `X-Session`, `X-Seq` (integer ≥ 0), `X-Final` (`1` or `0`), `X-Start-Ms` (device uptime in ms at the chunk start; see "Places"), `X-Duration-Ms`. Body: audio frames (see below), at most 1 MB. | `200` after the chunk is on disk. `400` if a header is missing or bad. A chunk that has the same device, session and sequence as a stored chunk replaces the file but does not add its duration again. |
| `GET /api/transcripts?device=<id>` | — | `200`, a JSON array of at most 18 items, newest session first. Sessions with the title "No speech" are not in the list. |
| `GET /api/todos?device=<id>` | — | `200`, `{"open":[todo],"done":[todo]}`. `done` has only the to-dos done since 00:00 local time today. |
| `PUT /api/todos/{device}/{session}/{index}` | JSON `{"done":true\|false,"at":"<RFC 3339 time>"}` | `200`, `{"done":…,"at":…}` with the state that the hub keeps. `404` if the to-do does not exist. `400` if the body is bad. See "To-do changes". |
| `POST /api/screen` | Header `X-Device`. Body: the 48000-byte panel frame. | `200`. `400` if the size is not 48000 bytes. |
| `GET /api/screen?device=<id>` | — | `200`, a 480x800 grey PNG of the last frame. `404` if there is no frame. |
| `GET /api/weather?device=<id>` | — | `200`, the weather JSON (see "Weather"). `400` if the device ID is bad. |
| `PUT /api/weather?device=<id>` | JSON `{"mode":"phone"\|"city","units":"c"\|"f"}`; each key is optional. | `200`, the weather JSON with the new settings. `400` if the body or a value is bad. |
| `GET /api/agenda?device=<id>` | — | `200`, the agenda JSON (see "Agenda"). `400` if the device ID is bad. |
| `GET /api/health?device=<id>&hours=<n>` | `hours`: 1 to 168, default 24. | `200`, a JSON array of health samples from the last `hours`, oldest first (see "Health"). `[]` for a device with no samples. `400` if the device ID or `hours` is bad. |
| `GET /api/devices` | — | `200`, a JSON object: device ID → the last check-in, with `seen` (the time). |
| `GET /api/firmware` | — | `200`, `{"version":"<x.y.z>","size":<bytes>}`: the newest firmware in the hub's firmware folder (laptop: `~/.local/share/resticky/firmware/<x.y.z>.bin`, `-firmware <dir>`; phone: app files `firmware/`). `404` if there is none. Both hubs. |
| `GET /api/firmware/bin?version=<x.y.z>` | — | `200`, the firmware image. `404` if there is no such version. |

### Voice activity (speech gate)

Firmware 0.9.0 to 0.14.9 ran a voice activity detector (ESP-SR VADNet `vadnet1_medium`) on each 20 ms frame,
but recorded and uploaded all audio (shadow mode). Firmware 0.15.0 and later use the detector as a gate: the
device encodes only speech, 500 ms of pre-roll before the speech and 1.5 s of hang-over after it. Each gated
20 ms frame is an empty frame (length 0, see "Audio frames"), so the chunk timeline stays exact. A chunk on
HTTP can have three more headers. A hub that does not know them ignores them.

| Header | Value |
|---|---|
| `X-Vad-Speech` | Base64 (standard, with padding) of a bit mask: one bit for each 20 ms frame of the chunk, `1` = speech. Frame `i` is in byte `i / 8`, bit `i % 8` (the least significant bit first). The mask has `ceil(frames / 8)` bytes; `frames` = `X-Duration-Ms` / 20. |
| `X-Vad-Keep` | The same format: `1` = the device encoded this frame (speech, pre-roll or hang-over). `0` = an empty frame. Before 0.15.0: the frames that a simulated gate (2 s pre-roll, 15 s hang-over) would keep. |
| `X-Vad-Keep-Prev` | Integer ≥ 0. Firmware 0.15.0 and later always send 0, because the pre-roll is always in the same chunk (the device delays the audio by the 500 ms pre-roll). Before 0.15.0: the number of frames at the end of the previous chunk that the simulated gate would also keep. |

A chunk with no VAD (no model in the `model` partition, or a chunk from an older firmware) has none of
these headers. On Bluetooth LE the chunk has no VAD data (see "Required hub change").

Required hub change (shared store, `app/shared`): `POST /api/chunks` reads the three headers and the store
writes them into `meta.json` as `vad`: sequence number (string) → `{"speech":"<base64>","keep":"<base64>",
"keep_prev":<n>}`. A chunk with no `X-Vad-Speech` header has no entry. The BLE `chunk` frame gets an
optional VAD trailer after the audio (u16 mask length `m`, speech mask `m` bytes, keep mask `m` bytes,
keep_prev u16); `parseChunk` must accept `size >= 31 + audio length` before the device sends it.

### Transcript item

```json
{"when":"13:12","title":"…","summary":"…","todos":["…"],"ready":true,"text":"…","place":"Office","marks":2,"photos":1}
```

- `when`: `HH:MM` for a session that started today, else `Mon D HH:MM` (Go layout `Jan 2 15:04`), local
  time.
- `ready`: `true` when the summary exists. While it is `false`, `title` and `summary` are empty and
  `todos` is `[]`. `todos` is always an array, never `null`.
- `text`: the last 180 characters (Unicode code points) of the transcript, with `...` before them when
  the transcript is longer.
- `place`: the name of the user's place where the session started, `"Elsewhere"` when it started at none
  of them, `""` when not known (no location access, the laptop hub, a session from the device backlog, or the
  location is not found yet). Always there. See "Places".
- `marks`: the number of marks in the session (see "Marks"). Always there.
- `photos`: the number of photos in the session (see "Photos"). Always there. The photos never leave the phone.

### To-do item

```json
{"id":"5f3a9c21-3","text":"…","from":"Product launch and Partner Plans · 13:12"}
```

`id` is `<session>-<index>`. `from` is the session title, ` · `, and `when` of the session.

### Audio frames

The body of a chunk is a list of Opus packets (16 kHz, mono, 20 ms each). Each packet has a 2-byte
little-endian length, then the packet bytes. A length of 0 is a 20 ms frame with no packet: the speech gate
removed it (firmware 0.15.0 and later), or the encoder failed. A decoder treats an empty frame as a lost
frame (`opus_decode` with no data gives 20 ms of packet-loss concealment, which fades to silence), and an
Ogg writer writes a 1-byte packet with the TOC byte only (a code 0 packet with an empty frame). Both hub
decoders (`opusFramesToOgg` in `app/shared` and `decodeOpus` in the Android app) do this already. A full
chunk has 1500 frames (30 s). A chunk can be shorter when its audio buffer (72,000 bytes) is full. Then the
device closes the chunk early and the next chunk continues the timeline with the next `seq` (firmware 0.15.0
and later). `X-Duration-Ms` always gives the real length.

### Screen frame

The frame is the panel buffer: 800x480, 1 bit for each pixel, 100 bytes for each row, the most
significant bit first, 1 = white. The portrait pixel (x, y) is in byte `(479 - x) * 100 + y / 8`, bit
`7 - y % 8`.

## Time

The device gets UTC from SNTP and has no time zone. Each hub sends its local UTC offset in minutes
(`utc_offset_min`, an integer, with daylight saving time at that moment, for example `-300` or `480`):

- HTTP: in the response to `POST /api/checkin`.
- BLE: in the `time` frame, `{"utc_offset_min":-300}`. The hub sends it when the link becomes ready and
  again when the value changes (the hub checks after each check-in).

The device applies the offset as a fixed offset and updates it at each check-in.

## Display

The hub owns the display settings. `clock_face` is the face of the clock page: `dial` (hand-drawn dial,
the default), `digits` (big digits), `words`, `note` (sticky note) or `flip` (flip clock). The user selects
it on the phone (Sticky settings › Clock); the laptop hub takes it from the `-clock-face <name>`
option (default `dial`). `weather_style` is the style of the Weather app: `sketch` (the default), `big`
(big picture), `journal` or `week`. The user selects it on the phone (Sticky settings › Weather › Style);
the laptop hub takes it from the `-weather-style <name>` option (default `sketch`). `hour_format` is `"24"` (the
default) or `"12"`: the hour format of all times on the device. The user selects it on the phone (Sticky settings ›
Clock › Hours); the laptop hub takes it from the `-hour-format 12|24` option. `buzz_hz` (default 175, range 60–1000) is the pitch of the Buzz feedback in Hz, with the same newest-change rule as `sound`: phone Sticky settings › Feedback › Buzz pitch (with Test), the Sticky Settings › Device presets, laptop hub `-buzz-hz <n>`. `sound` (default `beep`) is the
feedback of the Sticky for buttons and marks: `off`, `beep` (short high tones) or `buzz` (short low pulses that
feel like a phone vibration; the passive buzzer cannot shake). Phone: Sticky settings › Feedback; laptop hub:
`-sound off|beep|buzz`. The Sticky has its own setting too (Settings › Device › Feedback), and the newest change
wins: the device sends its value in each check-in as `"sound"`. When it differs from the hub value, the hub takes the device value, except after a
change on the hub: then the hub ignores the device value until a check-in reports the new hub value (the
device applied it). `home` (default `apps`) is the screen the Sticky shows after start and on Home: `apps` (all
apps as tiles), `clock`, `weather`, `recorder` or `todo` (firmware 0.14.4). Phone: Sticky settings › Home screen;
laptop hub: `-home <name>`. The device reports its value in the check-in as `"home"`, with the same newest-change
rule as `sound`. The hub ignores a value it does not know. `night` (default `"off"`) is the night time:
`"off"` or `"HH:MM-HH:MM"` (24 h, minutes `00` or `30`, start not equal to end, may cross midnight, for example
`"22:00-07:00"`). During the night the Sticky stops recording and deep-sleeps; it wakes at the end time or on a
button press, and sleeps again 5 minutes after the last touch while still in the window. Phone: Sticky settings ›
Night (On/Off, Start, End in 30-minute steps); laptop hub: `-night off|HH:MM-HH:MM`. The device sends its value in
every check-in, with the same newest-change rule. The hub ignores a value with another format.

- HTTP: in the response to `POST /api/checkin`.
- BLE: in the `display` frame, `{"clock_face":"dial","weather_style":"sketch","hour_format":"24","sound":"beep","home":"apps","night":"off"}`. The hub sends it when the
  link becomes ready and again when one of the values changes.

The device ignores a face or a style that it does not know.

## Weather

The hub gets the forecast from Open-Meteo (`api.open-meteo.com/v1/forecast`, no key) at a fixed interval
while it runs (15, 30, 60 or 180 minutes, default 30; phone: Hub › Weather › Refresh, stored as `interval_min` in
the weather settings file; laptop hub: `-weather-interval <minutes>`), and when the settings change. It keeps the last result (also on disk) when a fetch fails.
Only the location goes to Open-Meteo: the latitude and longitude rounded to 0.1° (about 10 km), or the
city name for the geocoding API (`geocoding-api.open-meteo.com/v1/search`, one time for each city).

Weather JSON (at most about 600 bytes; the hub keeps it below 2048 bytes):

```json
{"place":"Lisbon","mode":"phone","units":"c","at":1794358412,
 "now":{"temp":18,"feels":17,"cond":"cloud","hi":21,"lo":12,"rain":20},
 "hour":14,"temps":[18,19,21,21,21,20,20,19,18,16,15,14,14],
 "conds":["cloud","sun","cloud","rain","rain"],
 "days":[{"wd":"Tue","cond":"rain","lo":11,"hi":17}]}
```

| Field | Notes |
|---|---|
| `place` | Fixed city: the name from the geocoding API. From phone: the city part of the forecast time zone (`Asia/Lisbon` → `Lisbon`). Empty when not known. |
| `mode` | `phone` (the phone's approximate location) or `city` (a fixed city). The laptop hub is always `city`. |
| `units` | `c` or `f`. All temperatures are integers in this unit. |
| `at` | Unix time (seconds) of the forecast fetch. |
| `now` | Current temperature, feels-like, condition, today's high and low, today's highest rain chance in percent. |
| `hour`, `temps` | 13 hourly temperatures. `temps[0]` is the current hour (`hour`, 0 to 23, local time at the place); `temps[i]` is `hour + i`. |
| `conds` | 5 conditions, for `temps[0]`, `temps[3]`, `temps[6]`, `temps[9]` and `temps[12]`. |
| `days` | The next 5 days (tomorrow first): weekday (`Mon` to `Sun`), condition, low, high. |

`now`, `hour`, `temps`, `conds`, `days` and `at` are not in the JSON until the hub has a forecast (for example
`{"place":"","mode":"phone","units":"c"}`). The settings fields are always there, so the device can show
Settings › Weather.

A condition is one of `sun`, `cloud`, `rain`, `storm`, `snow`, `fog`, from the WMO weather code: 0 and 1 →
`sun`; 2 and 3 → `cloud`; 45 and 48 → `fog`; 51 to 67 and 80 to 82 → `rain`; 71 to 77, 85 and 86 → `snow`;
95 to 99 → `storm`; other codes → `cloud`.

Settings change from the device: `PUT /api/weather` on Wi-Fi, or the `weather_set` frame on BLE, with
`{"mode":"phone"|"city","units":"c"|"f"}` (each key is optional). The hub stores the change, sends the
weather JSON with the new settings at once (without forecast data when the units or the mode changed), then
fetches a new forecast and sends it. The fixed city itself is set only on the hub: Settings › Weather in the
phone app, or the `-city <name>` option of the laptop hub. A laptop hub ignores `mode` (it has no phone
location).

## Agenda

The phone hub reads the calendars that the user selects (Settings › Calendars) with Android
`CalendarContract`. Calendar data goes only to the device. The laptop hub has no calendars and sends
`show: false`.

```json
{"today":"2026-11-10","show":true,"events":[
 {"day":0,"start":"09:30","end":"10:00","title":"Standup"},
 {"day":1,"start":"","end":"","title":"Holiday"}]}
```

| Field | Notes |
|---|---|
| `today` | The hub's local date for `day` 0 (`YYYY-MM-DD`). |
| `show` | `false` when the user turned off "Show on the Sticky"; then `events` is empty and the clock shows no agenda. |
| `day` | 0 = today, 1 = tomorrow. |
| `start`, `end` | `HH:MM` local time of the hub. Both empty for an all-day event. An event that starts before the day has `00:00`; an event that ends after it has `24:00`. An event over midnight is in the list on both days. |
| `title` | At most 48 Unicode code points. |

Rules: events of all selected calendars and accounts together, no account marker; events that already
ended are not in the list; sorted by day, then start (all-day first), then end and title; at most 12 events;
the hub removes events from the end until the JSON is at most 2048 bytes. The phone hub builds the agenda
again every 5 minutes and when the selection changes, and sends it when it changed.

## Wi-Fi from the phone

The phone app adds and removes the device's saved networks over the bonded BLE link only (it is encrypted).
There is no HTTP endpoint for this.

- `wifi_add` (hub → device): `{"ssid":"Office-5G","password":"…"}`. SSID 1 to 32 bytes, password 0 (open
  network) or 8 to 64 bytes. The device saves it like the USB `wifi` command (at most 5 networks).
- `wifi_forget` (hub → device): `{"ssid":"Office-5G"}`.
- After it applies one of them, the device sends a `checkin` at once, so the app shows the new list.

Check-in fields: `wifi_saved` (array of the saved SSIDs, in the device order), `wifi_ssid` (the connected
SSID, `""` when not connected), `wifi_rssi` (dBm of the connected network, 0 when not connected). The phone
lists only 2.4 GHz networks that it can see, because the device has no 5 GHz radio.

## Health

The device sends a `health` object in each check-in (HTTP and BLE `checkin`, about every 30 s), so that the
hub keeps the history and the device stores none:

```json
{"uptime_s":3600,"heap_free":95000,"heap_min":85000,"heap_largest":60000,"psram_free":7157060,
 "psram_min":7153040,"stack_main":4300,"stack_net":9484,"stack_ble":852,"cpu_filter_pm":120,
 "cpu_opus_pm":340,"cpu_vad_pm":210,"opus_max_us":9100,"overflows":0,"temp_c":41.5}
```

| Field | Unit | Notes |
|---|---|---|
| `uptime_s` | s | Time since start. |
| `heap_free`, `heap_min`, `heap_largest` | bytes | Internal RAM: free now, lowest free since start, largest free block. |
| `psram_free`, `psram_min` | bytes | PSRAM: free now, lowest free since start. |
| `stack_main`, `stack_net`, `stack_ble` | bytes | Lowest free stack (high-water mark) of the main, net and BLE tasks. |
| `cpu_filter_pm`, `cpu_opus_pm`, `cpu_vad_pm` | per mille | Part of the audio core time used by the filter, the Opus encoder and the VAD. |
| `opus_max_us` | µs | Longest Opus encode of one frame. |
| `overflows` | count | I2S overflows since start. |
| `audio_keep_pm` | per mille | Optional, firmware 0.15.0 and later. Part of the frames in the last 60 s that the speech gate encoded. |
| `early_chunks` | count | Optional, firmware 0.15.0 and later. Chunks closed early in the last 60 s because the audio buffer was full. |
| `lost_chunks` | count | Optional, firmware 0.15.0 and later. Chunks lost since start (no memory for a chunk, or the upload queue was full). Must be 0. |
| `temp_c` | °C | Chip temperature. The hub keeps 0.1 °C. |

The hub stores only the 15 fields above `audio_keep_pm` in `health.bin`. To keep the three new fields, the hub
must add them to the record.

The hub keeps one sample for each check-in, but at most one sample in each minute (UTC minute of the hub
clock; the first check-in in a minute wins). A check-in without `health` gives a sample with `"health":null`.

Sample (from `GET /api/health`; all fields are always there):

```json
{"t":1794358412,"battery_pct":77,"voltage_mv":4012,"current_ma":-31,"recording":true,"outbox_chunks":3,
 "dropped_chunks":0,"wifi_rssi":-61,"health":{…},"firmware":"0.14.9"}
```

`t` is the Unix time (seconds) when the hub got the check-in. `firmware` is the `firmware` field of the
check-in (empty for samples from the older `health.bin` records).

Storage: `<device>/health2.bin` in the data folder, fixed 132-byte records, little-endian, appended at each
kept sample: `t` (i64), then 23 i32 values: `battery_pct`, `voltage_mv`, `current_ma`, `recording` (0 or 1),
`outbox_chunks`, `dropped_chunks`, `wifi_rssi`, a health flag (0 = no `health`), then the 15 health fields
in the order of the table, with `temp_c` × 10, then `firmware` (32 bytes UTF-8, padded with zero bytes).
The hub also reads `<device>/health.bin` (the older 100-byte records with no `firmware`). At the first new
sample it writes all samples into `health2.bin` and deletes `health.bin`. When the file has 11,520 records (7 days + 1 day), the hub
writes it again (temporary file, then rename) with only the samples of the last 7 days, at most 10,080.
Thus the file is at most about 1.15 MB, and `GET /api/health` never gives samples older than 7 days. A
partial record at the end (a crash during a write) is removed when the hub opens the file.

The phone app shows the history in Settings › Sticky › Health (6 h, 24 h or 7 d, at most 300 points in each
chart). It also shows the `boot` text of the last check-in, and "Battery drain" for each firmware version in
the range: the discharge rate in % per hour, the hours of data, and the hours from 100 % to 0 %. The rate
uses only discharge stretches of 1 hour or more. A stretch stops at a gap of more than 30 minutes, at a
sample with `current_ma` > 0 (charging), at a rise of `battery_pct`, and at a change of `firmware`.

## Places

The phone hub tags each new session with a place. The user names the places in Settings › Places on the
phone (name, radius 100, 150 or 300 m, the precise location of the phone when the user adds the place). The
places are stored only in the app preferences on the phone.

- When the first chunk of a session that the hub does not know arrives, the hub decides if the session is
  live: the chunk age is `uptime_s × 1000 + (now − time of the last check-in) − X-Start-Ms` (BLE: `start ms`),
  from the last check-in of the device. A chunk older than 10 minutes (from the outbox backlog) gets no tag. If
  the age is not known (no `health` in the last check-in, or a negative age after a restart of the device), the
  hub tags the session.
- The phone needs precise location (`ACCESS_FINE_LOCATION`) and, when the app is not on the screen,
  `ACCESS_BACKGROUND_LOCATION` ("Allow all the time"). The app asks for them only in Settings › Places. Without
  them the session gets no tag. The hub service type stays `connectedDevice`: with background access, a
  normal location request works from the service.
- One fix: the last known fused location if it is at most 10 minutes old, else one fused request with
  balanced power accuracy (at most 30 s). While the phone gets the fix, `place` is `"\u0000pending"`, and the
  summary of the session waits for at most 1 minute after the last chunk.
- The nearest place whose radius contains the fix gives the tag. No place → `"Elsewhere"`, with the location
  rounded to about 1 km in `meta.json`.
- The location never leaves the phone. The app does not use the Android `Geocoder`. Only the name goes to the
  Sticky (`place` in the transcript item) and to the on-device summary model as context (`"Elsewhere"` does
  not go to the model).

## Marks

A mark is a moment that the user marked on the Sticky with a double press while it records.

- The check-in (HTTP `POST /api/checkin` and the BLE `checkin`) has an optional field
  `"marks":[{"session":"<8 hex>","offset_ms":<int>,"at":<unix ms>}]`. `offset_ms` is the position in the
  session audio: `seq × 30000` + the position in the chunk (ms). `at` is the device clock (0 when the clock is
  not set); the hub does not store it.
- The device sends each mark in every check-in until the check-in is delivered: on HTTP, a `200` reply (the hub
  answers only after the marks are stored); on BLE, a successful write of the `checkin` frame (BLE has no reply).
- The hub stores each mark once (key: session and `offset_ms`) in `meta.json` of the session as `marks`
  (sorted). A repeat is ignored. A mark with a bad session ID or a negative offset is ignored; the reply is
  still `200`.
- A mark can arrive before the first chunk of its session (the chunks wait in the outbox). The hub then makes
  `meta.json` with no chunks. Such a session is not in the lists and gets no summary until its first chunk
  arrives; `started` is set from the first chunk.
- The summary prompt gets the marks as important moments: the time of each mark and the transcript text of the
  2 minutes before it.

## Segments and the session timeline

- The transcriber gives segments with start and end times. The hub stores them per chunk in `meta.json`
  `segments` (times in ms from the chunk start) and keeps the plain text in `chunks`.
- The session timeline: chunk `seq` starts at the sum of the durations of chunks `0 … seq-1` (`chunk_ms`; a
  chunk that is not stored counts 30 s). This is the same as `seq × 30000` while all chunks are 30 s.
- A chunk with no segments (transcribed before this change) shows as one block at the chunk start.
- Each segment has `words` (whisper token timestamps, tokens joined into words): an array of
  `[start_ms, end_ms, "text"]`, times from the chunk start. Phone: whisper.cpp `token_timestamps`; laptop:
  `whisper-cli -ojf` (`tokens[].offsets`). `[BLANK_AUDIO]` and special tokens are left out.
- `Session.offsets()` and the phone player use the same timeline. The phone plays the session audio with its own
  player: the Opus frames of the chunks in order (a missing chunk is silence) are decoded with libopus and written
  to an `AudioTrack`.

## Speakers

- When a session is closed and has no pending chunk, the hub finds the speakers before it makes the summary
  (sherpa-onnx: pyannote segmentation 3.0 and 3D-Speaker CAM++ embeddings, fast clustering with threshold 1.1,
  `min_duration_on` 0.3 s, `min_duration_off` 0.5 s). Clusters whose mean embeddings (from turns of 1.5 s and
  longer) have a cosine above 0.95 are merged. Speakers are numbered by their first turn.
- The phone diarizes at most 20 minutes of audio in one run; a longer session is done in 20-minute windows and
  the windows are joined by the same embedding rule. The laptop does the whole session in one run.
- Each segment gets the speaker with the most overlap (`speaker`, from 0; no overlap: the speaker of the segment
  before). `meta.json` `diarized` is true after the run (also when it failed or the models are missing; the
  summary does not wait). A new chunk sets it to false.
- Names: `speakers` in `meta.json` maps the speaker number (string) to a name. With no name the label is
  "Speaker A", "Speaker B", …. The phone renames for the whole session; a rename also replaces the old label in
  the title, the summary and the to-dos.
- The summary input has one line per speaker turn, `Name: text`.
- Models: phone: Settings › Models › Speakers (downloaded like the whisper models). Laptop:
  `-segmentation <file>` (default `~/.local/share/resticky/models/pyannote-segmentation-3-0.onnx`, from
  `huggingface.co/csukuangfj/sherpa-onnx-pyannote-segmentation-3-0/resolve/main/model.onnx`) and
  `-embedding <file>` (default `…/3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx`, sherpa-onnx release
  `speaker-recongition-models`). Without them the laptop hub logs a warning and skips the speakers.

## Playback view (phone only)

- Clean up: the transcript shows filler words and false starts as `-----` (`um`, `uh`, `erm`, `er`, `ah`, `hmm`,
  `mm`, `mhm`, "you know", "I mean", a word repeated at once, a cut-off word ending in `-`), and the player jumps
  over their time spans. Skip silence: the player jumps over gaps longer than 1.5 s without words (250 ms are
  kept at each side). A chunk with text but no word times counts as speech. The stored text does not change.
- Crisp voice: Off, Clear or Clear + denoise (remembered). Clear: Android `DynamicsProcessing` on the player
  session (pre-EQ: −24 dB below 90 Hz, −2 dB at 250–400 Hz, +3.5 dB at 2–5 kHz, +1 dB above 5 kHz; 3-band
  compressor 2:1 from −30 dB; limiter at −2 dB) and a slow level control to about −18 dBFS RMS of speech.
  Clear + denoise: the DPDFNet 2 model (sherpa-onnx `OnlineSpeechDenoiser`, 16 kHz, one thread) before the same
  chain. If `<session>/listen.opus` exists (an enhanced listening copy, the same frame format and timeline), the
  player uses it instead of the real-time denoiser. A switch while playing keeps the position: Clear ↔ denoise is
  a 50 ms crossfade, a switch to or from Off is a 25 ms fade out, the effect change, and a 25 ms fade in.
  The original audio is never changed.

## Photos (phone only)

- The Snap button (center of the tab bar, on every tab) and the Quick Settings tile "Snap to meeting" open the
  system camera. The photo is made at most 2048 px on the long side (JPEG quality 85) and stored in
  `<session>/photos/<unix ms>.jpg`; ML Kit text recognition (on the phone) reads the text.
- Linking: the session that is live at capture time (from `started` to `last_chunk` + 3 minutes), else the nearest
  session within 10 minutes; offset = capture time − `started`, limited to the session. Else the photo goes to "Not
  linked" (`files/unlinked/<unix ms>.jpg` and `.txt` with the text) on the Photos tab, where the user attaches it.
- The Photos tab shows the photos grouped by session, newest first (3 columns; tap: the viewer with the text and
  "Play from mm:ss", which opens the transcript at that time), then "Not linked".
- Show on Sticky (photo viewer, and the Android share target "Show on Sticky" for `image/*`, for example from
  Google Photos): the phone decodes the photo with its EXIF orientation, scales it to cover 480×800 and crops the
  center, makes it gray (0.299 R + 0.587 G + 0.114 B), stretches the levels (1 % and 99 % points, contrast 1.15)
  and dithers it to 1 bit with Atkinson dithering (cleaner whites and less noise than Floyd–Steinberg on the
  e-paper, good for text and whiteboards). It sends the BLE `photo` frame; with no BLE link it says that the
  Sticky is not connected.
- `meta.json` `photos`: `[{"offset_ms","file","text","taken_ms"}]`, sorted by offset. The summary prompt gets
  "photo text at mm:ss" lines. `/api/transcripts` has only the count.

## To-do changes

`PUT` sets the state, so a repeat or the same change from two hubs gives the same result:

- The hub keeps, for each to-do, the state and the time of the last change.
- A change with an `at` time older than the stored time is ignored; the response has the stored state.
- An open to-do that was never changed has no stored time.
- The device sets `at` from its clock, which it sets with SNTP after Wi-Fi connects. It sends a change
  only after the clock is set.

## Session rules

- A session is closed when a chunk has `X-Final: 1`, or when no chunk came for 3 minutes.
- Each chunk is transcribed when it arrives. A closed session with no pending chunk gets a summary: a
  title (at most 6 words), 1 to 2 sentences, and the to-dos. A session with no words gets the title
  "No speech" and no summary.
- Audio files (`.opus`, `.ogg`, `.wav`) are deleted 7 days after they were written. Text stays.

## Data folder

Default: `~/.local/share/resticky/data`.

```
<device>/
  screen.png                  last screen frame
  health.bin                  health history (see "Health")
  <session>/
    meta.json                 the session
    <seq>.opus                the chunk as received
    <seq>.ogg                 the chunk in an Ogg container (made for the transcription)
    photos/<unix ms>.jpg      photos (phone only)
    listen.opus               optional enhanced listening copy (phone only)
```

`meta.json`:

| Field | Type | Notes |
|---|---|---|
| `device`, `id` | string | |
| `started` | RFC 3339 time | arrival of the first chunk minus its duration |
| `last_chunk` | RFC 3339 time | arrival of the last chunk |
| `duration_ms` | integer | sum of the chunk durations |
| `final` | boolean | left out when false |
| `chunks` | object | sequence number (string) → transcript text. The text `"\u0000pending"` means "not transcribed yet". |
| `title`, `summary` | string | left out when empty |
| `todos` | array of strings | left out when empty |
| `done` | object | to-do index (string) → RFC 3339 time when it was marked done. Left out when empty. |
| `todo_changes` | object | to-do index (string) → RFC 3339 time of the last `PUT` change, open or done. Left out when empty. |
| `ready` | boolean | left out when false |
| `place` | string | Place tag (see "Places"). `"\u0000pending"` while the phone gets the location. Left out when empty. |
| `place_lat`, `place_lon` | number | Only with `"place":"Elsewhere"`: the location rounded to 0.01° (about 1 km). Never in an API response. |
| `segments` | object | sequence number (string) → array of `{"start_ms","end_ms","text","speaker","words"}`, times from the chunk start (see "Segments and the session timeline" and "Speakers"). `speaker` is left out when unknown, `words` when empty. Left out when empty. |
| `chunk_ms` | object | sequence number (string) → chunk duration in ms (`X-Duration-Ms`). Left out when empty. |
| `marks` | array of integers | Mark offsets in ms on the session timeline, sorted, no repeats (see "Marks"). Left out when empty. |
| `diarized` | boolean | The speaker run is done (see "Speakers"). Left out when false. |
| `speakers` | object | Speaker number (string) → name. Left out when empty. |
| `photos` | array | See "Photos". Left out when empty. |
| `vad` | object | sequence number (string) → `{"speech","keep","keep_prev"}` from the `X-Vad-*` headers (see "Voice activity"). Left out when empty. Needs the hub change. |

## Bluetooth LE

Firmware 0.7.0 and later. The device is a GATT server (NimBLE peripheral, one connection). The hub is the
central. The test hub is `firmware/tools/ble_hub.py`.

### Advertising

Name `resticky`, the service UUID in the advertising data, the name in the scan response. Interval 300 to
500 ms. The device advertises again after each disconnect.

### GATT service

| Item | UUID | Properties | Security |
|---|---|---|---|
| Service | `5a391e60-e572-4f44-920b-4a82eb105232` | | |
| RX (hub → device) | `5a391e61-e572-4f44-920b-4a82eb105232` | write, write without response | encrypted and authenticated link |
| TX (device → hub) | `5a391e62-e572-4f44-920b-4a82eb105232` | notify | encrypted and authenticated link to subscribe |

### Security

- LE Secure Connections only (legacy pairing is off), bonding, MITM protection.
- Device IO capability: DisplayOnly. The device shows a random 6-digit passkey on a full-screen "Pairing"
  page; the user types it on the hub. The page closes when pairing ends (success, failure or disconnect).
  The serial log also shows the passkey (`ble: pairing: passkey …`).
- Bonds (keys and subscriptions) are in NVS, at most 4. A bonded hub reconnects without a passkey. If a
  bonded peer pairs again, the device deletes the old bond and accepts the new pairing.
- The device sends chunks only when the link is encrypted, authenticated and bonded, and the hub has
  subscribed to TX.

### Link settings

The device asks for the LE 2M PHY and for data length 251 bytes. The preferred ATT MTU is 517.

Connection interval (firmware 0.15.0 and later): 5 s after the link is ready, the device asks for a slow
interval (150 to 200 ms, peripheral latency 4, supervision timeout 6 s) with a connection parameter update.
While an update runs (frames 18 to 22), and for 5 s after each part of a `photo` (17), `lists` (23) or OTA
frame, the device asks for a fast interval (15 to 30 ms, latency 0, timeout 6 s). Then it asks for the slow
interval again. If the interval is not in the requested range, the device asks again after 30 s. The hub must
accept these requests. The hub must not set its own interval (for example Android
`requestConnectionPriority`) after an update, because this stops the slow interval until the next request.

### Frames

Both directions carry a byte stream of frames. A frame is:

| Field | Size | Notes |
|---|---|---|
| length | u32, little-endian | number of bytes after this field (type + payload) |
| type | u8 | see the table below |
| payload | length - 1 bytes | |

A frame is split over as many notifications (TX) or writes (RX) as necessary; each one carries at most
MTU - 3 bytes of the stream, and never more than 512 bytes (the ATT value limit: Android drops longer
notifications). The Android hub asks for MTU 515 for this reason. A notification or write can contain the end of one frame and the start of the
next one. Both sides clear a partial frame when the connection closes. The hub writes RX with write
requests (with response), one at a time. The JSON payload of a hub → device frame is at most 2048 bytes, so
the device must accept RX frames of at most 2053 bytes (required firmware change: before this, the limit was
1020 bytes).

### Message types

| Type | Name | Direction | Payload |
|---|---|---|---|
| 1 | `chunk` | device → hub | header (31 bytes, little-endian) and the audio frames |
| 2 | `ack` | hub → device | session (u32), seq (u32) |
| 3 | `checkin` | device → hub | the JSON of `POST /api/checkin` (UTF-8) |
| 4 to 7 | reserved | | `state`, `hint`, `screen` (later layers) |
| 8 | `weather` | hub → device | the weather JSON (see "Weather") |
| 9 | `agenda` | hub → device | the agenda JSON (see "Agenda") |
| 10 | `wifi_add` | hub → device | `{"ssid","password"}` (see "Wi-Fi from the phone") |
| 11 | `wifi_forget` | hub → device | `{"ssid"}` |
| 12 | `weather_set` | device → hub | `{"mode","units"}`, the same as the body of `PUT /api/weather` |
| 13 | `time` | hub → device | `{"utc_offset_min":<n>}` (see "Time") |
| 14 | `display` | hub → device | `{"clock_face","weather_style","hour_format","sound","home","night"}` (see "Display") |
| 15, 16 | reserved | | |
| 17 | `photo` | hub → device | exactly 48000 bytes: a 480×800 portrait picture, row-major, 1 bit per pixel, the most significant bit first, 1 = black. The Sticky shows it full screen (full refresh) until the next touch or key and stores nothing. Needs firmware 0.14.1. |
| 18 | `ota_begin` | hub → device | `{"version":"x.y.z","size":N}` (see "Firmware push") |
| 19 | `ota_data` | hub → device | u32 little-endian offset, then at most 4096 bytes, strictly sequential |
| 20 | `ota_end` | hub → device | empty |
| 21 | `ota_status` | device → hub | `{"state":"…","percent":n,"offset":<bytes written>}` |
| 22 | `ota_cancel` | hub → device | empty: abort the update |
| 23 | `lists` | hub → device | `{"transcripts":[transcript item],"todos":{"open":[todo],"done":[todo]}}`: the same items as `GET /api/transcripts` and `GET /api/todos` for the linked device. At most 40960 bytes (see "BLE-only mode"). |
| 24 | `todo_set` | device → hub | `{"id":"<to-do id>","done":true\|false}`: `id` is the `id` of a to-do item. The hub applies it as `PUT /api/todos/{device}/{session}/{index}` with its current time as `at`, then sends a new `lists` frame. |
| 25 | `buzz_test` | hub → device | `{"hz":<60–1000>}`: the Sticky plays the buzz pattern once at this pitch, also when Feedback is not Buzz. Firmware 0.15.0. |
| 26 | `hub_token` | hub → device | `{"token":"<64 hex characters>"}`: the hub password (see "Hub password"). The phone sends it each time the link becomes ready. The Sticky stores it in NVS and replaces the old one. It ignores a token that is not 64 hex characters. Firmware 0.15.0. |

All JSON payloads are UTF-8 with no terminating zero byte.

The device ignores types that it does not know.

`chunk` header:

| Offset | Field | Type |
|---|---|---|
| 0 | device (the Wi-Fi MAC address, 6 bytes; the device ID is its lowercase hex) | 6 bytes |
| 6 | session | u32 |
| 10 | seq | u32 |
| 14 | final (1 or 0) | u8 |
| 15 | start ms (`X-Start-Ms`) | i64 |
| 23 | duration ms | u32 |
| 27 | audio length in bytes | u32 |
| 31 | audio frames, the same format as the body of `POST /api/chunks` | |

### Rules

- The device sends one chunk at a time, the oldest in the outbox, and deletes it only after an `ack` with
  the same session and seq. With no `ack` in 15 s, or when the link closes, the chunk stays in the outbox.
  After a missing `ack` the device does not send chunks on BLE for 60 s; in that time it uses Wi-Fi if a
  Wi-Fi hub is reachable. Then it tries BLE again. The device ignores an `ack` for a chunk that it does not
  wait for.
- The hub sends `ack` only after the chunk is on disk. A chunk that the hub already has (same device,
  session and seq) replaces the file, the same as on HTTP.
- The device sends `checkin` when the link becomes ready and then every 30 s.
- The hub sends `time`, `weather`, `agenda` and `display` when the link becomes ready, and then each one
  again only when its JSON changed (a new forecast, a settings change, a calendar change, a new offset, a
  new clock face or weather style). On Wi-Fi the device gets the same data with `GET /api/weather`,
  `GET /api/agenda` and the check-in reply.
- While a BLE hub is ready, chunks go only on BLE. Check-ins go only on BLE. Screen frames use Wi-Fi when a
  Wi-Fi hub is reachable.
- The hub sends `lists` when the link becomes ready and each time the transcripts or the to-dos change. The
  device puts the items into the same state as `GET /api/transcripts` and `GET /api/todos`. After the first
  `lists` frame of a link, the device stops the HTTP requests for transcripts and to-dos.
- While a BLE hub is ready, the device sends each to-do change as `todo_set`, also before its clock is set.
  The hub applies it as it applies `PUT /api/todos/...`, then sends a new `lists` frame. With
  no BLE hub, the device uses `PUT` on Wi-Fi.

### BLE-only mode

Firmware 0.15.0 and later. Weather, agenda, display, time and lists come on BLE. Thus the device does not
need Wi-Fi while a phone hub is linked.

- The device stops Wi-Fi (`esp_wifi_stop`) when the BLE hub was ready for 60 s without a break and a `lists`
  frame came on this link.
- The device starts Wi-Fi again when the BLE link is not ready for more than 60 s. It also starts Wi-Fi for
  5 minutes when the user starts a GitHub update check or an install, tests the hub, or opens Settings ›
  Wi-Fi. After the 5 minutes, the 60 s rule applies again.
- The Sticky shows "Off · phone linked" as the Wi-Fi state and "Phone linked" as the hub state.
- With Wi-Fi off, the device does not send screen frames and does not use SNTP. The clock continues from the
  last SNTP or RTC time.


### BLE-only mode

The device can turn Wi-Fi off while the phone hub is linked. Then the lists come on BLE:

- The hub sends `lists` when the link becomes ready, after each `checkin` and after each `todo_set`. It
  also reads the lists of the linked device every 2 s and sends `lists` again only when the JSON changed.
  The linked device is the `device` of the last `checkin` on the link; before the first `checkin` the hub
  sends no `lists` and ignores `todo_set`.
- If the JSON is more than 40960 bytes, the hub cuts `summary` and `text` of each transcript item to 300
  Unicode code points (and adds `...`), then removes the oldest transcript items until the JSON fits. Thus
  the device must accept RX frames of at most 40965 bytes.
- The hub does not send `lists` (or other pushes) while a firmware push (frames 18 to 22) runs, or while a
  `photo` frame is in the RX queue. It sends the lists again when the push or the photo is complete.
- A `todo_set` for a to-do that does not exist changes nothing; the hub still sends `lists`.

## Firmware updates

- After each successful check-in, the device asks `GET /api/firmware`. It offers the update on Settings >
  Device when the version is newer than its own and did not fail on this device before (ESP-IDF keeps
  the last invalid slot).
- The device writes the image into the other app slot; `esp_ota_end` checks the image hash that ESP-IDF
  appends to every image. The device does not start an update below 20% battery unless USB is connected.
- A new firmware starts in "pending verify". It confirms itself when it has written an audio chunk to the
  outbox and the hub answered a check-in (Wi-Fi or BLE), within 3 minutes. Otherwise, or if it crashes
  first, the bootloader starts the previous firmware.
- Releases are checked only by hand: the "Check for updates" button (phone App settings › Updates, Sticky
  settings › Firmware). Only that button asks the GitHub REST API
  (`GET https://api.github.com/repos/mtch3n/resticky/releases/latest`, no token, `User-Agent: resticky-android/<v>`,
  `If-None-Match` with the stored ETag). On `403`/`429` the phone waits until `X-RateLimit-Reset`. The answer is
  cached (`releases.json`). A release has the assets `resticky-android.apk` (release build), `resticky-android-debug.apk`, `resticky-firmware-<x.y.z>.bin` and
  `resticky-hub-desktop.zip` (CI, tags `v*`). The laptop hub never asks GitHub; its firmware folder is filled by
  hand (or `firmware/tools/release.sh`).
- After a check, the phone copies the release firmware into its firmware folder (`<x.y.z>.bin`), so its
  `/api/firmware` offers it.
- Update now (phone Sticky settings › Firmware, or Updates): see "Firmware push". Always over BLE, also when the
  Sticky is on Wi-Fi.

### Firmware push

Firmware 0.14.6 and later. The device puts all OTA frames in one 6-slot queue for its net task; flash writes do
not run in the BLE callback. During an update the device is in update mode: recording paused, no audio chunks,
no check-ins or sync, a full-screen update view with Cancel.

| Step | Hub sends | Device answers (`ota_status`) |
|---|---|---|
| Begin | `ota_begin` `{"version","size"}` | within about 2 s: `ready`, or `busy`, `battery` (below 20 % and not on USB) or `error` |
| Data | `ota_data` blocks, in order | `writing` with `percent` and `offset` (bytes written) after each block |
| End | `ota_end` | `installing`, then `ok` (done screen, restart after about 4 s) or `error` |
| Cancel | `ota_cancel` | `cancelled`; the device aborts the partition write, leaves update mode and records again |

- Flow control: at most 16 KiB not acknowledged: sent offset + length − last acknowledged offset ≤ 16384. The
  device drops more ("ota queue full").
- Every `ota_status` has `offset`. A touch on Cancel on the device also answers `cancelled`. The device aborts
  with `error` after 20 s without frames or when the link closes.
- The phone (Sticky settings › Firmware): 1 Download (always a fresh download into `<version>.bin.part`; the size
  must equal the release asset size), 2 Send to the Sticky (percent from the acknowledged offset, and KB/s),
  3 Install (`ota_end` → `installing` → `ok`, then the restart), 4 Done only when a check-in reports the new
  version ("Rolled back" when it reports another one, also during the 4 minutes after). Cancel (during Download
  and Send) stops the download and deletes the `.part` file, or sends `ota_cancel`. Update now after a cancel or
  an error starts again from the download.
- While a push runs, the phone asks for the high BLE connection priority (balanced again at the end), stops its
  other BLE pushes (weather, agenda, time, display, lists, photos) and stays with the one Sticky it started with. A BLE
  write that still fails after 10 retries closes the link (a dropped piece would break the frame stream), and the
  push fails and sends `ota_cancel`. Every wait has a deadline (begin 10 s, no progress 20 s, end 60 s, check-in
  5 minutes). The log under the tag `resticky` has each step and the measured KB/s.
- `busy`, `battery`, `error` and `cancelled` are shown as messages ("The Sticky is busy with another update",
  "Charge the Sticky to 20 % or connect USB first", "The Sticky reported an error", "Cancelled on the Sticky").
- Frames from the device longer than 1 MiB + 64 bytes are dropped and the reader starts again.
- Phone app update: App settings › Updates downloads `resticky-android.apk` and gives it to the Android package
  installer (`REQUEST_INSTALL_PACKAGES`; the user allows installs from resticky once and confirms each install).
  The APK must have the same signing key as the installed app.
- Release: `firmware/tools/release.sh` builds and copies the image to the firmware folder. After a USB
  flash, `firmware/tools/verify.sh` checks the check-in and an upload, and flashes the last good build
  back if they do not come.
