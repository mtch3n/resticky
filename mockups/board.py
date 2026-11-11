import subprocess
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

here = Path(__file__).parent
FONT = "/tmp/fontdl/Montserrat-SemiBold.ttf"
BOLD = "/tmp/fontdl/Montserrat-Bold.ttf"
GROUPS = [
    ("Apps", [("launcher-a", "Launcher"), ("launcher-quiet", "Launcher, quiet mode"), ("todo", "To-do"), ("clock", "Clock: dial"), ("clock-digits", "Clock: digits"), ("clock-words", "Clock: words"), ("clock-note", "Clock: note"), ("clock-flip", "Clock: flip"), ("stats", "Stats"), ("stats-device", "Stats, page 2: device"), ("weather", "Weather: sketch"), ("weather-big", "Weather: big picture"), ("weather-journal", "Weather: journal"), ("weather-week", "Weather: week")]),
    ("Recorder", [("recorder", "Recording"), ("recorder-muted", "Muted"), ("transcripts", "Transcripts"), ("transcript", "Transcript")]),
    ("Settings", [("settings", "Settings"), ("settings-recording", "Recording"), ("settings-wifi", "Wi-Fi"), ("settings-hub", "Hub"), ("settings-weather", "Weather"), ("settings-device", "Device"), ("night", "Night")]),
    ("Buttons and sleep", [("button-map", "Button map"), ("settings-buttons", "Settings > Buttons"), ("launcher-sleep", "Sleep: moon in the status row")]),
    ("System", [("boot-3", "Boot, step 3 of 6"), ("boot-6", "Boot, ready"), ("power-off", "Off"), ("update-progress", "Update: download, Cancel"), ("update-install", "Update: install"), ("update-done", "Update done")]),
]
W, H, pad, gap, cols = 480, 800, 28, 20, 7


def shot(name):
    out = Path(f"/tmp/resticky-shot-{name}.png")
    subprocess.run(["google-chrome-stable", "--headless=new", "--disable-gpu", "--hide-scrollbars",
                    "--force-device-scale-factor=1", "--window-size=480,800", f"--screenshot={out}",
                    f"file://{here / (name + '.html')}"], capture_output=True, check=True)
    im = Image.open(out).convert("L").crop((0, 0, W, H)).point(lambda v: 0 if v < 128 else 255)
    im.convert("1").save(here / f"{name}.png")
    return im


h1, h2, cap = ImageFont.truetype(BOLD, 40), ImageFont.truetype(BOLD, 30), ImageFont.truetype(FONT, 22)
board_w = pad * 2 + cols * W + (cols - 1) * gap
lines = sum((len(items) + cols - 1) // cols for _, items in GROUPS)
board = Image.new("L", (board_w, 96 + len(GROUPS) * 64 + lines * (40 + H + gap)), 255)
d = ImageDraw.Draw(board)
d.text((pad, 24), "resticky UI · Sticky", font=h1, fill=0)
y = 96
for title, items in GROUPS:
    d.text((pad, y), title, font=h2, fill=0)
    d.line((pad, y + 44, board_w - pad, y + 44), fill=150, width=3)
    y += 64
    for i, (name, caption) in enumerate(items):
        x, row = pad + i % cols * (W + gap), y + i // cols * (40 + H + gap)
        d.text((x, row), caption, font=cap, fill=0)
        board.paste(shot(name), (x, row + 34))
        d.rectangle((x - 2, row + 32, x + W + 1, row + 34 + H + 1), outline=170, width=2)
    y += (len(items) + cols - 1) // cols * (40 + H + gap)
board.save(here / "board.png")
