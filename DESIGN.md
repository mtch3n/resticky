# Design rules

## Screen

- The screen is e-paper. Design in portrait, 480x800, like the stock Seeedash UI.
- Black and white (1-bit) in mockups. Round corners and thin lines are fine, because they render
  well in 1-bit. No shadows, no gradients, no grey text.
- Black fill only for the active state (recording, the current tab, the primary button).
- No scrolling and no animation. Use pages and taps.
- A list that does not fit on one screen has a page row at the bottom: a 64 px round ‹ button, "page /
  pages", and a 64 px round › button (user, 2026-11-10: "have a < current/all >", "too small to touch"). The
  Up and Down side keys (not Power) and a swipe left or right also turn the page. No pager row (user,
  2026-11-09: "the screen does not scroll. you will have to add < >"; 2026-11-10: "since we have the back
  etc so we can remove the need that the next on the ui").
- Compact layout (user, 2026-11-10: "make a bit compact"): the app name is in the status row, next to the
  Wi-Fi icon; the page number is at the right, before the battery. No separate header row (user, 2026-11-10:
  "we dont need this just put on the tap as the wifi lives"). The status row keeps its launcher look: same
  height, no line under it (user, 2026-11-10: "why are you adding the seperator here"). 64 px list rows. No back arrow and no tab bar (user, 2026-11-10: "can we remove all these tiles here ? we can use the
  top bar to shw the current app", "no back. click on the top bar that name goes back to the home page").
  A tap on the name goes to the home page; a swipe right goes back one level.
- Touch targets are at least 64 px high.
- Section titles and rows use different weights (user, 2026-11-10: "make sure different weight so can seperate
  the title or the setting row"): section titles Bold 16 px upper case with space above; row names and values
  Medium (500).
- A switch shows its state as text inside it (user, 2026-11-10: "these switch should have on and off inside
  it"): on = black pill, white "ON", knob right; off = outlined pill, knob left, "OFF". 104 x 48 px.
- No lines between rows: space separates them (user, 2026-11-10: "why ther eare extra bars", "we are usign
  seperator too much").
- Recorder: no large microphone (user, 2026-11-10: "recording app do nto show the mic it is not useful").
  Show the state in words. No controls inside app pages: settings and switches are in Settings (user,
  2026-11-10: "no toggle the setting should be in the setting. not inside each app page"). Mute is the
  Microphone switch in Settings › Recording, and the Down key hold.
- Sleep: no sleep screen and no large icon. The Wi-Fi icon in the status row becomes a moon (user,
  2026-11-10: "no need larg icon for the sleep mode. just simple iuon on the wifi part").
- Boot and Off are splash screens (user, 2026-11-10: "i want there to be like a splash for the device is
  off", "make the boot screen better"). They are hand-drawn (rough.js lines, Caveat wordmark; user: "more had
  written like style"), and must not look like the stock splash (user: "better diffeent from the oiriginal fw
  design"; no circle of icons). Boot (user: "more artistic"): a taped sticky note with
  hatched shadow, a voice wave that runs into handwriting on the note, a check mark and a pencil; one part
  appears per boot step, with a hand-drawn progress bar. Off: a hand-drawn weather scene as art only (user: "A weather scene as
  art", "why the boot have the weather ? only thwe off screen"), an arrow to the power button with "press to
  start", and no "off" text (user: "no off text on off"). The update screens use the same hand-drawn style.

## Style (from the stock firmware assets)

- Icons: Tabler Icons (outline, MIT), stroke about 2.2, in 104 px rounded-square tiles
  (28 px radius) on the launcher. Active app tile: black fill, white icon.
- Font: Montserrat (OFL) only, also for titles (bold). No decorative fonts (user, 2026-11-09: "use
  normal fonts"). The font and its license are in `mockups/assets/fonts/`.
- Launcher: app tiles only. No clock, no "Add app" tile, no "resticky" title (user, 2026-11-09).
- Launcher tiles show the state in the tile, not as text below (user, 2026-11-10: "can the icon show the
  status iunstead of the small text below"): Recorder black when recording, white with a crossed-out mic
  when muted; a count badge on To-do and Transcripts; Weather shows the condition icon and temperature.

## Mockups

- Mockups must look like the real device: render at 480x800 and convert to 1-bit before you show
  them. Do not show web-style sketches (user, 2026-11-09: "the ui studio looks like shit").
- Landscape 800x480 mockups were rejected (user, 2026-11-09: "the home current design doesn't look
  good"). Match the stock look.
- No decoration that has no task: no card around one number, no tags, no separators where space
  already separates.
