# UI screens (approved 2026-11-09, To-do and Power off added the same day)

The design rules are in `../DESIGN.md`. The mockups are in `../mockups/`: one HTML source and one
1-bit PNG for each screen, and `board.png` with all screens. Every screen is portrait, 480x800, and
has a status line at the top: the Wi-Fi icon on the left, and the battery % and icon on the right.

## Navigation

```
Launcher ─┬─ Recorder ─┬─ Home (recording / muted)
          │            └─ Transcripts ── Transcript detail
          ├─ To-do
          ├─ Stats
          └─ Settings ─┬─ Recording
                       ├─ Wi-Fi and hub
                       ├─ Night pause
                       ├─ Power and screen
                       └─ Device ─┬─ Updating ── Update done
                                  └─ Shut down ── Power off
```

The Recorder pages have a bottom tab bar: Home, Transcripts (the active tab is black). To-do, Stats and
Settings have a Back bar at the bottom. The settings sub-pages have a back arrow in the header.
The mockups come from `../mockups/gen_screens.py`.

## Screens

| # | Screen | Mockup | Content |
|---|---|---|---|
| 1 | Launcher | `launcher-a.png` | App tiles only: Recorder (black tile when recording, "• REC 2 h 14" under the name), To-do ("4 open"), Stats, Settings. No clock, no title, no "Add app". |
| 2 | Recorder, recording | `recorder.png` | Title "Recorder". Large black round microphone button: tap to mute. "Recording", "Tap to mute · 2 h 14 today". The last transcript in a rounded box. |
| 3 | Recorder, muted | `recorder-muted.png` | The button is white with a crossed-out microphone. "Muted", "Tap to record · muted 12 min". |
| 4 | Transcripts | `transcripts.png` | "Today · 6", rows of time · title, then length · number of to-dos. Tap a row to open it. |
| 5 | Transcript detail | `transcript.png` | Back arrow and title. Time and length, the summary, the to-dos with tick boxes. |
| 6 | To-do | `todo.png` | "Open · 4": the open to-dos from all summaries, each with a tick box and the source conversation and time. "Done today" below. Back. |
| 7 | Stats | `stats.png` | "4.2 days" battery left, then "battery left · 78% · 162 mAh/day". Today: speech, recordings started, uploads and failures, audio sent. Back. |
| 8 | Settings | `settings.png` | Rows with icon, name, current value and ›: Recording, Wi-Fi and hub, Night pause, Power and screen, Device. "Back" at the bottom. |
| 9 | Settings > Recording | `settings-recording.png` | Capture mode (Continuous / Duty-cycled), Quality (8 / 12 / 16 kbps), Upload every (5 / 15 / 30 min), Stop when face down (switch). |
| 10 | Settings > Wi-Fi and hub | `settings-wifi.png` | Wi-Fi network and state. Hub address and status. Buttons: Change Wi-Fi, Test connection. |
| 11 | Settings > Night pause | `settings-night.png` | Pause at night (switch). From and To times with − and + buttons. |
| 12 | Settings > Power and screen | `settings-power.png` | Battery saver (switch), Sleep after (1 / 2 / 5 min), Button sound (switch). |
| 13 | Settings > Device | `settings-device.png` | "Update available" card with the new version, size, the current version and "Update now". Auto-update at night (switch). Battery health, audio storage. Restart and Shut down. |
| 14 | Updating | `update-progress.png` | "Updating to v0.2.0", progress bar, "62% · keep on USB or battery above 20%". |
| 15 | Update done | `update-done.png` | Check mark, "v0.2.0 installed", "Recording again". |
| 16 | Power off | `power-off.png` | "Off", "Press the top button to start". The e-paper keeps this image while the power is off. |

The numbers on the mockups are examples.

## Controls

- Segmented choice: a rounded bar, the selected part is black.
- Switch: a rounded track with a knob; on = black track, white knob on the right.
- Stepper: round − and + buttons around the value.
- Primary button: black, rounded. Secondary button: black outline, rounded.

## Buttons and sleep (design, 2026-11-10)

The Sticky has a power button (GPIO4, "OK") and two side keys (up GPIO5, down GPIO6). All three can wake the
device from deep sleep. Rules: every press clicks the buzzer and changes the screen; a single short press
never loses data or privacy (those actions need a hold); the buttons work without looking.

| Button | Press | Hold 1.5 s |
|---|---|---|
| Power | Sleep / wake (manual sleep: recording paused, radios off, deep sleep, sleep screen) | Hold 10 s: power off |
| Up | Previous page | Mark moment: the last 2 minutes are important (never dropped by the voice detector, audio kept, shown first in the summary) |
| Down | Next page | Mute / record |
| Up + Down | — | Hold 3 s: pair a phone (shows the BLE code) |

Settings > Buttons shows the map and lets the user give each press or hold another action: Sleep/wake,
Mute/record, Mark moment, Previous/next page, Home, Pair phone, Nothing. Power off on a 10 s hold is fixed.

Sleep indicators: in quiet mode (automatic, still listening) the Wi-Fi icon in the status line becomes a
moon and the Recorder tile says "Listening"; manual sleep shows the full sleep screen (moon, "Sleeping",
"Recording is paused", "Press any button to wake"), which the e-paper keeps without power.
Mockups: `../mockups/board.png`, group "Buttons and sleep" (`button-map`, `settings-buttons`, `sleep`) and
`launcher-quiet`. The button positions in `button-map` come from the pin map; check them on the device.
