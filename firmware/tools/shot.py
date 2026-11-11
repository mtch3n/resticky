# /// script
# requires-python = ">=3.12"
# dependencies = ["pillow==11.3.0", "pyserial==3.5"]
# ///
import base64
import re
import sys
import time

import serial
from PIL import Image

W, H, STRIDE = 480, 800, 100


def main():
    port, out = sys.argv[1], sys.argv[2]
    commands = sys.argv[3:]
    s = serial.Serial(port, 115200, timeout=1)
    for c in commands:
        s.write((c + "\r\n").encode())
        time.sleep(2.5)
    s.reset_input_buffer()
    s.write(b"shot\r\n")
    data, started, end = {}, False, time.time() + 40
    while time.time() < end:
        line = s.readline().decode(errors="ignore").strip()
        if line.startswith("SHOT BEGIN"):
            started = True
        elif started and (m := re.fullmatch(r"SHOT:(\d+):([A-Za-z0-9+/=]{4,80})", line)):
            data[int(m.group(1))] = m.group(2)
        elif started and line == "SHOT END":
            break
    rows = (48000 + 59) // 60
    missing = [r for r in range(rows) if r not in data]
    if missing:
        sys.exit(f"missing {len(missing)} rows, first {missing[:5]}")
    fb = base64.b64decode("".join(data[r] for r in range(rows)))
    im = Image.new("L", (W, H), 255)
    px = im.load()
    for x in range(W):
        for y in range(H):
            if not fb[(W - 1 - x) * STRIDE + y // 8] & (0x80 >> (y % 8)):
                px[x, y] = 0
    im.save(out)
    print(out, len(fb))


main()
