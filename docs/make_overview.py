#!/usr/bin/env python3
"""Draw docs/overview.png, the schematic screen at the top of README.md.

Run with any Python that has Pillow:
    python3 docs/make_overview.py
"""
import random
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter, ImageFont

OUT = Path(__file__).resolve().parent / "overview.png"
FONT = "/System/Library/Fonts/HelveticaNeue.ttc"
S = 2  # drawn at twice the size, for sharp text on high-resolution screens
W, H = 960, 560


def font(size, bold=False):
    return ImageFont.truetype(FONT, size * S, index=1 if bold else 0)


def box(d, x, y, w, h, fill, outline=None, r=6, width=1):
    d.rounded_rectangle([x * S, y * S, (x + w) * S, (y + h) * S], r * S, fill=fill, outline=outline, width=width * S)


def text(d, x, y, s, size=11, fill="#333333", bold=False, anchor="la"):
    d.text((x * S, y * S), s, font=font(size, bold), fill=fill, anchor=anchor)


def cells(w, h, seed, hue):
    """A fluorescence-like image: blurred bright blobs on black."""
    random.seed(seed)
    im = Image.new("RGB", (w * S, h * S), "black")
    d = ImageDraw.Draw(im)
    for _ in range(14):
        k = max(w, h) / 240
        cx, cy, r = random.randint(0, w * S), random.randint(0, h * S), int(random.randint(8, 22) * k * S)
        c = tuple(int(v * random.uniform(0.6, 1.0)) for v in hue)
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=c)
    return im.filter(ImageFilter.GaussianBlur(max(1.5, min(w, h) / 45) * S))


def window(img, d, x, y, w, h, title, content=None):
    box(d, x, y, w, h, "#ffffff", "#b9b9b9", r=7)
    d.rectangle([x * S, (y + 18) * S, (x + w) * S, (y + 18) * S], fill="#d0d0d0")
    for i, c in enumerate(("#ff5f57", "#febc2e", "#28c840")):
        d.ellipse([(x + 8 + i * 11) * S, (y + 6) * S, (x + 15 + i * 11) * S, (y + 13) * S], fill=c)
    if w < 140: text(d, x + 42, y + 5, title, 8, "#555555")
    else: text(d, x + w / 2, y + 4, title, 9, "#555555", anchor="ma")
    if content is not None:
        img.paste(content, ((x + 3) * S, (y + 21) * S))


def callout(d, x, y, tx, ty, label, anchor="la"):
    d.line([x * S, y * S, tx * S, ty * S], fill="#1f6feb", width=2 * S)
    d.ellipse([(x - 3) * S, (y - 3) * S, (x + 3) * S, (y + 3) * S], fill="#1f6feb")
    lines = label.split("\n")
    for i, line in enumerate(lines):
        text(d, tx, ty - 7 + i * 15, line, 12, "#1f6feb", bold=(i == 0), anchor=anchor)


img = Image.new("RGB", (W * S, H * S), "#ffffff")
d = ImageDraw.Draw(img)

# the screen
SX, SY, SW, SH = 150, 30, 600, 400
box(d, SX - 8, SY - 8, SW + 16, SH + 16, "#2b2b2b", r=14)
box(d, SX, SY, SW, SH, "#e9edf2", r=4)
d.rectangle([SX * S, SY * S, (SX + SW) * S, (SY + 12) * S], fill="#f6f6f6")  # macOS menu bar
text(d, SX + 22, SY + 1, "Fiji   File   Edit   Image   Process   Analyze   Plugins", 8, "#444444")

# the side toolbar
TX, TY, TW = SX, SY + 12, 30
d.rectangle([TX * S, TY * S, (TX + TW) * S, (SY + SH) * S], fill="#f3f3f3")
d.line([(TX + TW) * S, TY * S, (TX + TW) * S, (SY + SH) * S], fill="#c8c8c8", width=S)
glyphs = ["rect", "oval", "poly", "free", "line", "angle", "point", "wand", "A", "zoom", "hand", "drop", "sep", "Dev", "Stk", "LUT", "undo", "»"]
gy = TY + 6
for g in glyphs:
    if g == "sep":
        d.line([(TX + 6) * S, (gy + 2) * S, (TX + TW - 6) * S, (gy + 2) * S], fill="#c8c8c8", width=S)
        gy += 7
        continue
    cx, cy = TX + TW / 2, gy + 8
    col = "#2f5fa7"
    if g == "rect":
        box(d, cx - 6, cy - 5, 12, 10, None, col, r=1, width=1)
        box(d, TX + 3, gy, TW - 6, 16, None, "#7aa7e8", r=4)  # the selected tool
    elif g == "oval":
        d.ellipse([(cx - 6) * S, (cy - 5) * S, (cx + 6) * S, (cy + 5) * S], outline=col, width=S)
    elif g == "poly":
        d.polygon([((cx - 6) * S, (cy + 5) * S), ((cx - 2) * S, (cy - 5) * S), ((cx + 6) * S, (cy - 2) * S), ((cx + 3) * S, (cy + 5) * S)], outline=col)
    elif g == "free":
        d.arc([(cx - 6) * S, (cy - 5) * S, (cx + 6) * S, (cy + 5) * S], 20, 320, fill=col, width=S)
    elif g == "line":
        d.line([(cx - 6) * S, (cy + 5) * S, (cx + 6) * S, (cy - 5) * S], fill=col, width=S)
    elif g == "angle":
        d.line([(cx - 6) * S, (cy + 5) * S, (cx + 6) * S, (cy + 5) * S, (cx + 2) * S, (cy - 5) * S], fill=col, width=S)
    elif g == "point":
        d.line([(cx - 5) * S, cy * S, (cx + 5) * S, cy * S], fill=col, width=S)
        d.line([cx * S, (cy - 5) * S, cx * S, (cy + 5) * S], fill=col, width=S)
    elif g == "wand":
        d.line([(cx - 6) * S, (cy + 6) * S, (cx + 3) * S, (cy - 3) * S], fill=col, width=2 * S)
        text(d, cx + 3, cy - 9, "*", 10, col)
    elif g == "zoom":
        d.ellipse([(cx - 6) * S, (cy - 6) * S, (cx + 2) * S, (cy + 2) * S], outline=col, width=S)
        d.line([(cx + 1) * S, (cy + 1) * S, (cx + 6) * S, (cy + 6) * S], fill=col, width=2 * S)
    elif g == "hand":
        text(d, cx, cy - 7, "✋", 11, col, anchor="ma") if False else box(d, cx - 4, cy - 5, 8, 10, None, col, r=3)
    elif g == "drop":
        d.ellipse([(cx - 4) * S, (cy - 2) * S, (cx + 4) * S, (cy + 6) * S], fill=col)
        d.polygon([((cx - 4) * S, (cy + 1) * S), (cx * S, (cy - 6) * S), ((cx + 4) * S, (cy + 1) * S)], fill=col)
    elif g == "undo":
        d.arc([(cx - 5) * S, (cy - 5) * S, (cx + 5) * S, (cy + 5) * S], 180, 450, fill="#1f6feb", width=2 * S)
    else:
        text(d, cx, cy - 6, g, 9 if len(g) > 1 else 12, "#a33030" if g == "»" else col, bold=True, anchor="ma")
    gy += 18
text(d, TX + TW / 2, SY + SH - 34, "+", 13, "#555555", anchor="ma")
text(d, TX + TW / 2, SY + SH - 18, "•••", 10, "#555555", anchor="ma")

# the status strip
STX, STY, STW, STH = TX + TW, TY, SW - TW, 26
box(d, STX + 2, STY + 2, STW - 4, STH, "#fafafa", "#c8c8c8", r=4)
text(d, STX + 10, STY + 9, "1024 × 1024 pixels; 16-bit; 2 MB", 9, "#555555")
box(d, STX + STW - 140, STY + 7, 128, 15, "#ffffff", "#c8c8c8", r=4)
text(d, STX + STW - 134, STY + 9, "Search Fiji…", 8, "#999999")

# the shelf at the bottom
BX, BY, BW, BH = TX + TW, SY + SH - 48, SW - TW, 48
d.rectangle([BX * S, BY * S, (BX + BW) * S, (BY + BH) * S], fill="#efefef")
d.line([BX * S, BY * S, (BX + BW) * S, BY * S], fill="#c8c8c8", width=S)
for i, (lab, col) in enumerate([("ROI", "#6b8fcf"), ("B&C", "#8a8a8a"), ("Ch", "#a77bc9"), ("Res", "#6fae7a"), ("Log", "#a89a6a")]):
    text(d, BX + 22 + i * 34, BY + 16, lab, 11 if lab != "Ch" else 14, col, bold=True, anchor="ma")
    if lab in ("ROI", "B&C"):
        d.ellipse([(BX + 20 + i * 34) * S, (BY + 38) * S, (BX + 24 + i * 34) * S, (BY + 42) * S], fill="#555555")
d.line([(BX + 190) * S, (BY + 8) * S, (BX + 190) * S, (BY + 40) * S], fill="#c8c8c8", width=S)
for i, (seed, hue) in enumerate([(1, (60, 220, 90)), (2, (230, 70, 200)), (3, (80, 150, 255))]):
    tx = BX + 222 + i * 50
    box(d, tx - 2, BY + 4, 44, 40, "#ffffff", "#1f6feb" if i == 0 else "#c8c8c8", r=5, width=2 if i == 0 else 1)
    img.paste(cells(40, 28, seed, hue), (tx * S, (BY + 6) * S))
    text(d, tx + 20, BY + 35, ("C1-cells", "C2-cells", "C3-cells")[i], 7, "#333333", anchor="ma")
d.line([(BX + 395) * S, (BY + 8) * S, (BX + 395) * S, (BY + 40) * S], fill="#c8c8c8", width=S)
for i, (lab, col) in enumerate([("Seg", "#e07b39"), ("Cnt", "#3a9a8a")]):
    box(d, BX + 410 + i * 38, BY + 9, 30, 30, col, r=7)
    text(d, BX + 425 + i * 38, BY + 17, lab, 10, "#ffffff", bold=True, anchor="ma")
d.rounded_rectangle([(BX + BW - 42) * S, (BY + 9) * S, (BX + BW - 12) * S, (BY + 39) * S], 6 * S, outline="#999999", width=S)
text(d, BX + BW - 27, BY + 13, "+", 16, "#777777", anchor="ma")

# windows, tiled in the space left by the bars
AX, AY = STX + 6, STY + STH + 8
AH = BY - 6 - AY
window(img, d, AX, AY, 250, AH, "C1-cells (60%)", cells(244, AH - 24, 1, (60, 220, 90)))
window(img, d, AX + 256, AY, 200, 190, "C2-cells (45%)", cells(194, 166, 2, (230, 70, 200)))
window(img, d, AX + 462, AY, 98, 190, "ROI Manager")
for i in range(6):
    d.rectangle([(AX + 468) * S, (AY + 26 + i * 14) * S, (AX + 524) * S, (AY + 36 + i * 14) * S], fill="#e6eefb" if i == 1 else "#f4f4f4")
for i, lab in enumerate(["Add", "Update", "Delete", "Measure"]):
    box(d, AX + 530, AY + 26 + i * 18, 24, 13, "#f4f4f4", "#c8c8c8", r=3)
window(img, d, AX + 256, AY + 196, 304, AH - 196, "B&C")
import math
HX, HY, HW, HH = AX + 268, AY + AH - 10, 180, AH - 236  # a histogram
pts = [(HX + i, HY - HH * math.exp(-((i - 60) / 28.0) ** 2) - 0.25 * HH * math.exp(-((i - 125) / 18.0) ** 2)) for i in range(HW)]
d.polygon([(HX * S, HY * S)] + [(x * S, y * S) for x, y in pts] + [((HX + HW) * S, HY * S)], fill="#9a9a9a")
d.line([(HX + 30) * S, HY * S, (HX + 150) * S, (HY - HH) * S], fill="#222222", width=S)
for i, lab in enumerate(["Auto", "Reset", "Set", "Apply"]):
    box(d, AX + 470, AY + 222 + i * 18, 70, 13, "#f4f4f4", "#c8c8c8", r=3)
    text(d, AX + 505, AY + 223 + i * 18, lab, 8, "#444444", anchor="ma")

# callouts
callout(d, TX + TW / 2, TY + 90, 130, 140, "Vertical toolbar\nevery ImageJ tool;\nadd, move, edit,\nremove any button", anchor="ra")
callout(d, STX + 200, STY + 14, 520, 8, "Status strip: status line, progress bar, Fiji search")
callout(d, BX + 80, BY + 47, 260, 470, "Tools: open tool windows\nand pinned launchers")
callout(d, BX + 285, BY + 47, 460, 500, "Images: live previews,\nright-click to place, save, copy")
callout(d, BX + 440, BY + 47, 640, 470, "Macros: one click runs a .ijm\non the image (or on the ones you tick)")
callout(d, AX + 400, AY + 120, 800, 200, "Windows tiled\nbeside the bars\n(with Window Layouts)")

img.save(OUT, optimize=True)
print("Wrote", OUT)
