# /// script
# requires-python = ">=3.12"
# dependencies = ["freetype-py==2.5.1"]
# ///
import sys
from pathlib import Path

import freetype

CODEPOINTS = (
    list(range(0x20, 0x7F))
    + list(range(0xA0, 0x100))
    + [0x2013, 0x2014, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022, 0x2026, 0x2039, 0x203A, 0x2190, 0x2192, 0x2605, 0x2606]
)
FONTS = [
    ("text_16", "Montserrat-SemiBold.ttf", 16),
    ("label_16", "Montserrat-Bold.ttf", 16),
    ("body_18", "Montserrat-Medium.ttf", 18),
    ("body_20", "Montserrat-Medium.ttf", 20),
    ("text_18", "Montserrat-SemiBold.ttf", 18),
    ("text_20", "Montserrat-SemiBold.ttf", 20),
    ("title_22", "Montserrat-Bold.ttf", 22),
    ("title_28", "Montserrat-Bold.ttf", 28),
    ("title_40", "Montserrat-Bold.ttf", 40),
    ("clock_112", "Montserrat-Bold.ttf", 112, [0x20] + list(range(0x30, 0x3B)) + [0xB0, 0x2D, 0x25]),
    ("hand_28", "Caveat.ttf", 28, CODEPOINTS, 700),
    ("hand_40", "Caveat.ttf", 40, CODEPOINTS, 700),
    ("hand_64", "Caveat.ttf", 64, [0x20, 0x2D] + list(range(0x30, 0x3A)) + [0xB0], 700),
    ("hand_88", "Caveat.ttf", 88, [0x20, 0x27, 0x2D] + list(range(0x61, 0x7B)), 700),
    ("hand_128", "Caveat.ttf", 128, [0x20, 0x2D] + list(range(0x30, 0x3A)) + [0xB0], 700),
    ("hand_150", "Caveat.ttf", 150, [0x20] + list(range(0x30, 0x3B)), 700),
    ("hand_170", "Caveat.ttf", 170, [0x20] + list(range(0x30, 0x3A)), 700),
    ("hand_200", "Caveat.ttf", 200, [0x20] + list(range(0x30, 0x3B)), 700),
]
LOAD_FLAGS = freetype.FT_LOAD_RENDER | freetype.FT_LOAD_NO_AUTOHINT
THRESHOLD = 128


def glyph_bits(bitmap):
    bits = []
    for y in range(bitmap.rows):
        row = bitmap.buffer[y * bitmap.pitch : y * bitmap.pitch + bitmap.width]
        bits.extend(1 if v >= THRESHOLD else 0 for v in row)
    return bits


def pack(bits):
    out = bytearray()
    for i in range(0, len(bits), 8):
        byte = 0
        for j, b in enumerate(bits[i : i + 8]):
            byte |= b << (7 - j)
        out.append(byte)
    return out


def generate(name, ttf, size, codepoints=CODEPOINTS, weight=None):
    codepoints = sorted(codepoints)
    face = freetype.Face(str(ttf))
    if weight:
        face.set_var_design_coords((weight,))
    face.set_pixel_sizes(0, size)
    bitmap = bytearray()
    dscs = ["    {.bitmap_index = 0, .adv_w = 0, .box_w = 0, .box_h = 0, .ofs_x = 0, .ofs_y = 0}"]
    for cp in codepoints:
        face.load_char(chr(cp), LOAD_FLAGS)
        g = face.glyph
        bits = glyph_bits(g.bitmap)
        index = len(bitmap)
        bitmap += pack(bits)
        w, h = (g.bitmap.width, g.bitmap.rows) if any(bits) else (0, 0)
        dscs.append(
            f"    {{.bitmap_index = {index}, .adv_w = {g.advance.x // 4}, .box_w = {w}, .box_h = {h}, "
            f".ofs_x = {g.bitmap_left if w else 0}, .ofs_y = {g.bitmap_top - h if h else 0}}}"
        )
    ascender, descender = face.size.ascender >> 6, face.size.descender >> 6
    start = codepoints[0]
    offsets = ", ".join(hex(cp - start) for cp in codepoints)
    data = ",".join(f"0x{b:02x}" for b in bitmap) or "0x00"
    return f"""#include "lvgl.h"

static LV_ATTRIBUTE_LARGE_CONST const uint8_t glyph_bitmap[] = {{{data}}};

static const lv_font_fmt_txt_glyph_dsc_t glyph_dsc[] = {{
{",\n".join(dscs)}
}};

static const uint16_t unicode_list[] = {{{offsets}}};

static const lv_font_fmt_txt_cmap_t cmaps[] = {{
    {{.range_start = {start}, .range_length = {codepoints[-1] - start + 1}, .glyph_id_start = 1,
     .unicode_list = unicode_list, .glyph_id_ofs_list = NULL, .list_length = {len(codepoints)},
     .type = LV_FONT_FMT_TXT_CMAP_SPARSE_TINY}},
}};

static const lv_font_fmt_txt_dsc_t font_dsc = {{
    .glyph_bitmap = glyph_bitmap,
    .glyph_dsc = glyph_dsc,
    .cmaps = cmaps,
    .kern_dsc = NULL,
    .kern_scale = 0,
    .cmap_num = 1,
    .bpp = 1,
    .kern_classes = 0,
    .bitmap_format = 0,
}};

const lv_font_t {name} = {{
    .get_glyph_dsc = lv_font_get_glyph_dsc_fmt_txt,
    .get_glyph_bitmap = lv_font_get_bitmap_fmt_txt,
    .line_height = {ascender - descender},
    .base_line = {-descender},
    .subpx = LV_FONT_SUBPX_NONE,
    .underline_position = 0,
    .underline_thickness = 0,
    .dsc = &font_dsc,
    .fallback = NULL,
    .user_data = NULL,
}};
"""


def main():
    ttf_dir, hand_dir, out_dir = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])
    for name, ttf, size, *cps in FONTS:
        (out_dir / f"{name}.c").write_text(generate(name, (hand_dir if ttf.startswith("Caveat") else ttf_dir) / ttf, size, *cps))
        print(name, size, ttf)


main()
