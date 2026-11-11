from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

here = Path(__file__).parent
FONT = "/tmp/fontdl/Montserrat-SemiBold.ttf"
BOLD = "/tmp/fontdl/Montserrat-Bold.ttf"
h1, h2, cap, tag = ImageFont.truetype(BOLD, 64), ImageFont.truetype(BOLD, 44), ImageFont.truetype(FONT, 32), ImageFont.truetype(BOLD, 26)
P = True
groups = [
    ("Home", [("hub", "Hub"), ("device-picker", "Choose a Sticky")]),
    ("Sticky settings", [("sticky", "Sticky settings", P), ("sticky-pair", "Sticky settings (pairing)", P), ("faces", "Clock"), ("weather-settings", "Weather"),
                         ("sticky-wifi", "Wi-Fi networks"), ("sticky-wifi-add", "Add a network"), ("health", "Health")]),
    ("App settings", [("settings", "App settings", P), ("updates", "Updates", P), ("models", "Models"), ("pipeline", "Pipeline"), ("places", "Places"), ("place-edit", "Add a place"), ("calendars", "Calendars")]),
    ("Transcripts & playback", [("transcripts", "Transcripts"), ("transcript", "Transcript · top"), ("transcript-player", "Transcript · playing"),
                                ("transcript-speakers", "Speakers, Clean up, Crisp voice"), ("speaker-rename", "Name a speaker")]),
    ("Photos", [("transcript-photo", "Photo in the timeline"), ("photo-view", "Photo viewer"), ("photos-unlinked", "Unlinked photos"), ("qs-tile", "Quick Settings tile")]),
    ("To-do", [("todo", "To-do")]),
]
W, H, pad, gap = 824, 1830, 60, 50
cols = max(len(items) for _, items in groups)
bw = pad * 2 + cols * W + (cols - 1) * gap
board = Image.new("RGB", (bw, 180 + len(groups) * (110 + 60 + H + gap)), "white")
d = ImageDraw.Draw(board)
d.text((pad, 50), "resticky phone app · approved designs; PROPOSED = settings split and updates", font=h1, fill="black")
y = 180
for title, items in groups:
    d.text((pad, y), title, font=h2, fill="black")
    d.line((pad, y + 66, bw - pad, y + 66), fill=(170, 170, 170), width=4)
    y += 110
    for i, (name, caption, *proposed) in enumerate(items):
        im = Image.open(f"/tmp/app-{name}.png").convert("RGB").resize((W, H))
        im.save(here / f"{name}.png")
        x = pad + i * (W + gap)
        tx = x
        if proposed:
            box = d.textbbox((0, 0), "PROPOSED", font=tag)
            d.rounded_rectangle((x, y - 2, x + box[2] + 28, y + 40), radius=20, fill="black")
            d.text((x + 14, y + 4), "PROPOSED", font=tag, fill="white")
            tx = x + box[2] + 42
        d.text((tx, y), caption, font=cap, fill="black")
        board.paste(im, (x, y + 56))
        d.rounded_rectangle((x - 3, y + 53, x + W + 2, y + 56 + H + 2), radius=40, outline="black" if proposed else (150, 150, 150), width=8 if proposed else 4)
    y += 60 + H + gap
board.save(here / "board-app.png")
