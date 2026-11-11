# Plan: phone apps and hybrid links

Status: draft for approval, 2026-11-09.

## User decisions (2026-11-09)

- "an app so we don't need to pair with the laptop"; apps for Android and iOS.
- Stack: Kotlin Multiplatform ("ok kotlin multiplatform").
- Engines: free choice of Gemini Nano, Whisper and LiteRT-LM.
- Links: Wi-Fi and BLE together. "the user should not know the difference": the device selects the best
  and fastest link, sends, and receives data back, in all cases.
- Later: an SD card stores audio while no hub is near.
- Phone: Phone hotspot XL (Android 17, API 37, Tensor G5, 16 GB).
- Laptop hub: "no go hub. keep the data." (2026-11-10). The laptop hub moves to the shared Kotlin core;
  the recorded sessions in `~/.local/share/resticky/data` stay usable.

## Parts

```
                ┌── BLE (main link to phones) ──▶ Android app (hub) ─┐
Sticky ─ outbox ┤                               iOS app (hub)       ├── hub sync
                └── Wi-Fi (bulk, laptop) ──────▶ laptop hub (JVM) ──┘
```

| Part | Stack | Job |
|---|---|---|
| Sticky firmware | C, ESP-IDF, LVGL, NimBLE | Records, keeps every item in an outbox until a hub acknowledges it, selects the link. |
| Shared app core | Kotlin Multiplatform (`commonMain`) | Protocol messages, session store, pipeline rules (session end, "No speech", 7-day audio delete), sync. |
| Android app | Kotlin, Compose Multiplatform | BLE central in a foreground service, engines, screens. |
| iOS app | Kotlin, Compose Multiplatform, Swift where Apple APIs need it | BLE central with background mode `bluetooth-central` and state restoration, engines, screens. |
| Laptop hub | Kotlin Multiplatform, JVM target (`desktopApp`) | The shared core with whisper-cli and Codex engines, the same screens as the phones, and a mode without a window for the systemd service. Replaces the Go hub. |

## Protocol

One set of messages for both links. HTTP carries them on Wi-Fi; a GATT service carries them on BLE.

| Message | Direction | Content |
|---|---|---|
| `chunk` | device → hub | device, session, seq, final, start ms, duration ms, Opus frames |
| `ack` | hub → device | the chunk ID, sent only after the hub stored the chunk durably |
| `checkin` | device → hub | firmware, battery, USB, recording, outbox size, link stats |
| `todo_set` | both | to-do ID, done (true/false), time of the change. Replaces the current `toggle`, so a repeat or a second hub gives the same result. |
| `state` | hub → device | latest transcripts, summaries and to-dos (the lists that the device shows) |
| `hint` | phone → device | "hub X is reachable on Wi-Fi network Y" |
| `screen` | device → hub | the 1-bit frame (development only) |

Rules:

- Each chunk has a fixed ID (device, session, seq). A hub that receives an ID two times keeps one copy.
- The device deletes an item from the outbox only after an `ack`.
- BLE: one GATT service, one write characteristic (hub → device) and one notify characteristic
  (device → hub), messages in length-prefixed frames. LE Secure Connections pairing; the device shows a
  6-digit code on the e-paper and the phone asks the user to confirm it.

## Link selection on the device

| Case | Link |
|---|---|
| Paired phone connected over BLE, small outbox | BLE, each chunk when it is recorded |
| Outbox larger than about 5 MB, and a known Wi-Fi hub is reachable (from a `hint` or the last check) | Wi-Fi burst, then Wi-Fi off |
| No phone, but a Wi-Fi hub on a saved network | Wi-Fi |
| No hub | Outbox only (flash spool now, SD card later) |
| Firmware update | Wi-Fi if available, else BLE |

The Wi-Fi profiles (up to 5 saved networks) already exist in firmware 0.4.2.

## Engines (measured on the Pixel, 2026-11-09)

| Job | Engine | Time | Background |
|---|---|---|---|
| Speech-to-text, 30 s chunk | whisper.cpp base.en q8_0, 8 threads, OpenMP | 2.1–2.4 s | Yes |
| Summary | Gemini Nano (ML Kit `genai-prompt` 1.0.0-beta5) | 5–23 s | No: blocked unless the app is on screen |
| Summary | LiteRT-LM, Gemma 4 E2B | 40–90 s on a busy phone, about 2 GB RAM | Yes |
| Summary (laptop) | Codex `gpt-6-luna` | 6–7 s | Yes |

The user selects the model for each job; no model is locked (user, 2026-11-10: "allow me to select the
model do not lock down the model"). The Engines screen lists every engine and model that works on the
device, downloads models, and shows the time of each run. Defaults (balanced speed and quality):
whisper.cpp base.en q8_0 for speech-to-text; Gemini Nano for summaries while the app is on screen, and
LiteRT-LM Gemma 4 E2B in the background.

Facts from the tests that the apps must handle:

- Both phone models put the JSON in a Markdown code fence. Use structured output where the API has it,
  else remove the fence.
- Set the output token limit for Gemini Nano; the default cuts the JSON.
- Nano reported a 32k-token input limit on this phone (the docs say 4k). LiteRT-LM's test CLI is fixed
  at 4k; the Kotlin API must set a larger context, else long sessions go in parts.
- Both models give to-dos without a correct owner. The prompt must say "owner only if a name is said".

iOS engines: whisper.cpp, and Apple's on-device model for summaries when iOS allows it (to check).

## iOS limits

- iOS keeps a BLE connection to the Sticky in the background and wakes the app for each chunk (about
  10 s). The app stores the chunk and can transcribe it on the CPU in that time.
- Summaries run when the app is open, or in a background processing task that iOS starts later.
- Building and installing needs a Mac with Xcode, or a macOS CI runner and an Apple Developer account.

## App screens

1. Hub: link state (BLE or Wi-Fi), the connected Sticky (firmware, battery, recording, outbox size), and
   a live pipeline list: each chunk with its state (received, transcribing, done), engine and time.
2. Transcripts: sessions with summary and full text.
3. To-do: the same list as the device; a tick on either side updates both.
4. Engines: the selection for each job, model downloads, timings.
5. Device: pairing, saved Wi-Fi networks (add from the phone over BLE), firmware update.

## Repository layout

```
app/
  shared/        Kotlin Multiplatform core and Compose UI
  androidApp/
  iosApp/
  desktopApp/    laptop hub (JVM)
  contract/      protocol tests that run against every hub
```

## Build layers

Each layer works from end to end before the next one starts.

1. Protocol and laptop hub: write the message list above as a spec and the contract tests. Run the tests
   against the Go hub first, to record how it behaves now. Then build the Kotlin core and the JVM laptop
   hub, change `toggle` to `todo_set`, and make the JVM hub pass the tests. The JVM hub reads and writes
   the same data folder and the same `meta.json` format, so the existing sessions stay. Then remove the
   Go hub and point the systemd service at the JVM hub.
2. Firmware outbox on the flash spool partition (21.8 MB): chunks wait there until acknowledged. This
   also stops audio loss when the hub is down.
3. Firmware BLE: NimBLE GATT service, pairing with the code on the e-paper, chunk transfer with acks.
   Measure the BLE speed and power on this board.
4. Android app, first end to end: KMP project, BLE central foreground service, whisper.cpp (JNI),
   LiteRT-LM and Gemini Nano, Hub and To-do screens. The Sticky records to the Pixel with no laptop.
5. Link selection on the device and the Wi-Fi `hint` from the phone.
6. Hub sync between the phone and the laptop.
7. iOS app.
8. SD card spool (when the card is fitted).

## Open decisions

1. iOS builds: a Mac that is reachable over SSH, or a macOS CI runner. An Apple Developer account for
   TestFlight.

## Weather, clock and calendars (design approved in parts, 2026-11-10)

- Weather: the hub gets the forecast from Open-Meteo (free, no account) about every 30 minutes and gives it
  to the Sticky. Location: the phone's approximate location (rounded to about 10 km) or a fixed city that
  the user sets in the phone app (Settings › Weather). The user allowed both (2026-11-10: "allow fixed
  location or use the phone location"). The laptop hub uses the fixed city.
- Clock: the Sticky shows the time, the date, and today's and tomorrow's events.
- Calendars: the phone app reads the calendars of the Google accounts that are on the phone (Android
  `CalendarContract`, no extra sign-in). The user selects calendars from several accounts; the Sticky shows
  them together in one list, with no account tag (user, 2026-11-10: "no needthe tag"). "Add a Google account" opens the Android
  account settings.
- Stats page 2: firmware and uptime, CPU clock and temperature, internal RAM free and lowest, PSRAM, flash
  outbox, Wi-Fi, Bluetooth, battery voltage and current.
- Wi-Fi from the phone (user, 2026-11-10: "why cannot add through phone ?"): the phone app lists the Sticky's
  saved networks (Forget) and the networks that the phone can see. The user picks one and types the
  password; the phone sends it over the bonded, encrypted BLE link (new frame types: `wifi_add`,
  `wifi_forget`, and the saved list in the check-in). The USB `wifi` command stays for setup without a phone.
- Built on the hub side (2026-11-10): the phone app screens Settings › Calendars, Settings › Weather and
  Settings › Sticky › Wi-Fi networks, the weather and agenda data, and the BLE frames and HTTP endpoints in
  `protocol.md`. The Sticky's Settings › Weather sends a settings change to the hub (`weather_set` or
  `PUT /api/weather`). The weather settings belong to the hub only, so the check-in does not carry them.


## Parked ideas

- Buzzer as vibration (user, 2026-11-11: "see if we can use the sound to simulate the vibration"): short
  low-frequency pulses on the passive buzzer (GPIO48) as haptic-style feedback for taps, marks and alerts.
- "Update to vX" on the Sticky's Device page (user, 2026-11-11: "park issue on the update to xx on the sticky
  device"): look at the whole flow again; also, when no update is offered, the hidden button leaves a gap
  between "Check for updates" and Restart.
