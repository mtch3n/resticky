# /// script
# requires-python = ">=3.12"
# dependencies = ["pillow==11.3.0"]
# ///
import subprocess
import sys
import tempfile
from pathlib import Path

from PIL import Image

CONDS = ["sun", "cloud", "rain", "storm", "snow", "fog"]
SIZES = {"s": 0.28, "m": 0.5, "l": 1.15, "xl": 2.0}

HELPERS = """
const text = (p, x, y, t, size) => { const e = document.createElementNS(NS, 'text'); e.setAttribute('x', x); e.setAttribute('y', y);
  e.setAttribute('text-anchor', 'middle'); e.setAttribute('font-family', 'H'); e.setAttribute('font-weight', 700); e.setAttribute('font-size', size); e.textContent = t; p.appendChild(e); };
"""

ASSETS = {}
for c in CONDS:
    for k, sc in SIZES.items():
        w, h = int(230 * sc) + 8, int(200 * sc) + 8
        ASSETS[f"wx_{c}_{k}"] = (w, h, f"cond('{c}', {w / 2}, {h / 2}, {sc}, 100);")
ASSETS["dial"] = (320, 320, """
const cx = 160, cy = 160, r = 150;
add(rc.circle(cx, cy, r * 2, pen(3.4, 1, 1.3)));
add(rc.circle(cx, cy, r * 2 - 18, pen(1.6, 2, 1.6)));
for (let k = 0; k < 60; k++) { if (k % 5) { const a = k * Math.PI / 30; add(rc.circle(cx + (r - 20) * Math.cos(a), cy + (r - 20) * Math.sin(a), 3, {...pen(1, 100 + k), fill: '#000', fillStyle: 'solid'})); } }
for (let k = 1; k <= 12; k++) { const a = (k * 30 - 90) * Math.PI / 180; text(svg, cx + (r - 46) * Math.cos(a), cy + (r - 46) * Math.sin(a) + 13, k, k % 3 ? 30 : 40); }
""")
ASSETS["note"] = (400, 330, """
const n = g('rotate(-3 200 160)');
n.appendChild(rc.path('M28 30 L378 30 L378 270 L338 310 L28 310 Z', {roughness: 0.8, stroke: 'none', fill: '#000', fillStyle: 'hachure', hachureGap: 6, fillWeight: 1.6, hachureAngle: 40, seed: 2}));
n.appendChild(rc.path('M16 18 L366 18 L366 258 L326 298 L16 298 Z', {...pen(3, 1), fill: '#fff', fillStyle: 'solid'}));
n.appendChild(rc.path('M366 258 L326 258 L326 298', pen(2.6, 3)));
n.appendChild(rc.polygon([[140, 2], [250, 6], [248, 38], [138, 34]], {...pen(2.4, 5), fill: '#000', fillStyle: 'hachure', hachureGap: 4, fillWeight: 1.4, hachureAngle: -60}));
""")
ASSETS["flip_card"] = (114, 266, """
add(rc.rectangle(10, 16, 100, 248, {roughness: 0.8, stroke: 'none', fill: '#000', fillStyle: 'hachure', hachureGap: 6, fillWeight: 1.6, hachureAngle: 40, seed: 10}));
add(rc.path('M21 4 H85 Q103 4 103 22 V234 Q103 252 85 252 H21 Q3 252 3 234 V22 Q3 4 21 4 Z', {...pen(3.2, 11), fill: '#fff', fillStyle: 'solid'}));
""")
ASSETS["sun_small"] = (92, 92, """
add(rc.circle(46, 46, 40, pen(2.6, 3)));
for (let k = 0; k < 8; k++) { const a = k * Math.PI / 4; add(rc.line(46 + 28 * Math.cos(a), 46 + 28 * Math.sin(a), 46 + 38 * Math.cos(a), 46 + 38 * Math.sin(a), pen(2.4, 10 + k))); }
""")
ASSETS["underline2"] = (380, 34, "add(rc.line(6, 10, 366, 6, pen(3, 1, 1.6))); add(rc.line(26, 24, 346, 22, pen(2, 2, 1.8)));")
ASSETS["underline"] = (360, 40, "add(rc.path('M6 10 C96 30 256 -2 346 18', pen(3, 1, 1.4)));")
ASSETS["ruled"] = (480, 700, """
for (let k = 0; k < 13; k++) add(rc.line(20, 58 + k * 52, 460, 58 + k * 52, pen(1, 500 + k, 0.6)));
add(rc.line(70, 0, 70, 700, pen(1.6, 520, 0.8)));
""")
for name, marks in {"update_arrow": ["M240 262 L240 352", "M206 322 L240 356 L274 322"], "update_check": ["M196 316 L228 350 L290 270"]}.items():
    ASSETS[name] = (240, 240, "const u = g('translate(-120 -200) rotate(-5 240 320)');"
                    "u.appendChild(rc.path('M160 230 L320 230 L320 370 L284 406 L160 406 Z', pen(3, 40)));"
                    "u.appendChild(rc.path('M320 370 L284 370 L284 406', pen(2.6, 41)));"
                    + "".join(f"u.appendChild(rc.path('{d}', pen(4, {80 + i})));" for i, d in enumerate(marks)))
ASSETS["update_fill"] = (292, 22, "add(rc.rectangle(0, 0, 292, 22, {stroke: 'none', fill: '#000', fillStyle: 'hachure', hachureGap: 5, fillWeight: 2.4, hachureAngle: -50, seed: 70}));")
ASSETS["update_frame"] = (304, 34, "add(rc.rectangle(2, 2, 300, 30, pen(2.6, 71)));")
ASSETS["card_half"] = (226, 176, "add(rc.rectangle(2, 2, 222, 172, pen(2.6, 600, 1.2)));")
ASSETS["card_full"] = (456, 176, "add(rc.rectangle(2, 2, 452, 172, pen(2.6, 604, 1.2)));")


def render(mockups: Path, name: str, w: int, h: int, js: str, tmp: Path) -> Image.Image:
    page = mockups / f"_asset_{name}.html"
    page.write_text(
        "<!doctype html><meta charset=utf-8><style>@font-face{font-family:H;src:url(assets/fonts/Caveat.ttf)}"
        f"*{{margin:0}}body{{width:{w}px;height:{h}px;background:#fff}}</style><body>"
        f"<svg id=s width={w} height={h}></svg><script src='assets/js/rough.js'></script><script src='assets/js/art.js'></script>"
        f"<script>{HELPERS}{js}</script></body>")
    out = tmp / f"{name}.png"
    try:
        subprocess.run(["google-chrome-stable", "--headless=new", "--disable-gpu", "--hide-scrollbars", "--force-device-scale-factor=1",
                        f"--window-size={max(w, 120)},{h}", f"--screenshot={out}", f"file://{page}"], capture_output=True, check=True)
    finally:
        page.unlink()
    return Image.open(out).convert("L").crop((0, 0, w, h))


def pack(name: str, im: Image.Image) -> str:
    w, h = im.size
    stride = (w + 7) // 8
    data = bytearray(b"\xff\xff\xff\xff\x00\x00\x00\xff")
    px = im.load()
    for y in range(h):
        row = bytearray(stride)
        for x in range(w):
            if px[x, y] < 128:
                row[x // 8] |= 0x80 >> (x % 8)
        data += row
    body = ",".join(str(b) for b in data)
    return (f"static const uint8_t {name}_data[] = {{{body}}};\n"
            f"const lv_image_dsc_t art_{name} = {{.header = {{.magic = LV_IMAGE_HEADER_MAGIC, .cf = LV_COLOR_FORMAT_I1, .w = {w}, .h = {h}, "
            f".stride = {stride}}}, .data_size = sizeof {name}_data, .data = {name}_data}};\n")


def main():
    mockups, out = Path(sys.argv[1]).resolve(), Path(sys.argv[2])
    parts, decls = ['#include "art.h"\n'], []
    with tempfile.TemporaryDirectory() as tmp:
        for name, (w, h, js) in ASSETS.items():
            parts.append(pack(name, render(mockups, name, w, h, js, Path(tmp))))
            decls.append(f"extern const lv_image_dsc_t art_{name};")
    (out / "art.c").write_text("\n".join(parts))
    (out / "art.h").write_text("#pragma once\n#include \"lvgl.h\"\n\n" + "\n".join(decls) + "\n")
    print(len(ASSETS), "assets")


main()
