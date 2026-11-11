# /// script
# requires-python = ">=3.12"
# dependencies = ["pillow==11.3.0"]
# ///
import subprocess
import sys
import tempfile
from pathlib import Path

from PIL import Image

W, H = 480, 800
PANEL_STRIDE = 800 // 8
FRAMES = [f"boot-{k}" for k in range(1, 7)] + ["power-off"]


def render(html: Path, out: Path) -> Image.Image:
    subprocess.run(["google-chrome-stable", "--headless=new", "--disable-gpu", "--hide-scrollbars",
                    "--force-device-scale-factor=1", f"--window-size={W},{H}", f"--screenshot={out}",
                    f"file://{html}"], capture_output=True, check=True)
    return Image.open(out).convert("L").crop((0, 0, W, H))


def pack(im: Image.Image) -> bytes:
    fb = bytearray(b"\xff" * (PANEL_STRIDE * W))
    px = im.load()
    for y in range(H):
        for x in range(W):
            if px[x, y] < 128:
                fb[(W - 1 - x) * PANEL_STRIDE + y // 8] &= ~(0x80 >> (y % 8)) & 0xFF
    return bytes(fb)


def main():
    mockups, out = Path(sys.argv[1]).resolve(), Path(sys.argv[2])
    subprocess.run([sys.executable, str(mockups / "gen_splash.py"), str(mockups)], check=True)
    rows = []
    with tempfile.TemporaryDirectory() as tmp:
        for name in FRAMES:
            data = pack(render(mockups / f"{name}.html", Path(tmp) / f"{name}.png"))
            rows.append("{" + ",".join(str(b) for b in data) + "}")
    out.write_text('#include "splash.h"\n\nconst uint8_t splash_frames[SPLASH_FRAMES][SPLASH_SIZE] = {\n'
                   + ",\n".join(rows) + "\n};\n")
    print(out, len(FRAMES), "frames")


main()
