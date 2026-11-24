#!/usr/bin/env python3
"""Preview what the colour grade does to the island, without a GPU.

The composite shader's grade block is reproduced here in Python, statement for
statement, and applied to a synthetic frame of the game's palette: sky ramp,
ground, foliage, a soldier, a HUD-ish white. Three strips show raw render,
day grade and night grade, so the difference is a picture instead of a claim.

Deliberately a *simulation of the formula*, not of the renderer: no shadows, no
bloom, no FXAA. If the shader's grade block changes, this must change with it.
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from concept_to_oeac import write_png  # noqa: E402
from preview_environment import FONT as BASE_FONT  # noqa: E402
from preview_armory import EXTRA  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "tools/tripo/concepts/grade-preview.png"
W, H = 1240, 900
PANEL_W, PANEL_H = 380, 300
FONT = {**BASE_FONT, **EXTRA}


def plot(px, x, y, c):
    if 0 <= x < W and 0 <= y < H:
        o = (y * W + x) * 3
        px[o:o + 3] = bytes(max(0, min(255, int(v))) for v in c)


def rect(px, x, y, w, h, c):
    for yy in range(max(0, y), min(H, y + h)):
        for xx in range(max(0, x), min(W, x + w)):
            plot(px, xx, yy, c)


def text(px, s, x, y, scale=1, colour=(238, 242, 248)):
    for ch in str(s).upper():
        for row, bits in enumerate(FONT.get(ch, FONT[" "])):
            for col, bit in enumerate(bits):
                if bit == "1":
                    for sy in range(scale):
                        for sx in range(scale):
                            plot(px, x + col * scale + sx, y + row * scale + sy, colour)
        x += 6 * scale


# ------------------------------------------------------------------ the grade

def clamp01(v):
    return max(0.0, min(1.0, v))


def smoothstep(a, b, x):
    if a == b:
        return 0.0 if x < a else 1.0
    t = clamp01((x - a) / (b - a))
    return t * t * (3 - 2 * t)


def grade(rgb, contrast, saturation, warmth, clarity=0.0, blur=None):
    """`COMPOSITE_FRAG`'s grade block: saturation, contrast, split tone, clarity."""
    r, g, b = rgb
    l = 0.2126 * r + 0.7152 * g + 0.0722 * b
    # saturation about the same luminance
    r, g, b = (l + (c - l) * saturation for c in (r, g, b))
    # contrast about mid grey
    r, g, b = ((c - 0.5) * contrast + 0.5 for c in (r, g, b))
    if abs(warmth) > 0.001:
        warm = (1.045, 1.005, 0.955)
        cool = (0.960, 0.990, 1.050)
        k = clamp01(smoothstep(0.18, 0.86, l) + warmth)
        r, g, b = (c * (cool[i] + (warm[i] - cool[i]) * k) for i, c in enumerate((r, g, b)))
    if clarity > 0.0 and blur is not None:
        r, g, b = (c + (c - blur[i]) * clarity for i, c in enumerate((r, g, b)))
    return tuple(clamp01(c) for c in (r, g, b))


def to8(c):
    return tuple(max(0, min(255, int(round(v * 255)))) for v in c)


# --------------------------------------------------------------- a fake frame

def make_frame():
    """A cheap but representative frame: sky, hills, ground, trees, a soldier."""
    fw, fh = PANEL_W, PANEL_H
    px = [[(0.0, 0.0, 0.0)] * fw for _ in range(fh)]
    horizon = int(fh * 0.42)
    for y in range(fh):
        for x in range(fw):
            if y < horizon:
                t = y / max(1, horizon)
                # sky: deep top to pale horizon
                c = (0.18 + 0.42 * t, 0.34 + 0.44 * t, 0.62 + 0.30 * t)
            else:
                t = (y - horizon) / max(1, fh - horizon)
                c = (0.30 + 0.14 * t, 0.42 + 0.12 * t, 0.20 + 0.08 * t)
            px[y][x] = c
    # hills
    for x in range(fw):
        hy = horizon - int(26 + 18 * __import__("math").sin(x * 0.02) ** 2)
        for y in range(max(0, hy), horizon):
            px[y][x] = (0.24, 0.34, 0.26)
    # trees
    for i, tx in enumerate((60, 130, 300, 330)):
        for y in range(horizon - 46, horizon + 4):
            for x in range(tx - 12, tx + 12):
                if 0 <= x < fw and 0 <= y < fh:
                    if (x - tx) ** 2 / 144 + (y - horizon + 16) ** 2 / 900 <= 1:
                        px[y][x] = (0.13, 0.26, 0.15)
    # a soldier and a white HUD bar
    for y in range(horizon + 40, horizon + 130):
        for x in range(180, 216):
            px[y][x] = (0.38, 0.34, 0.30)
    for y in range(horizon + 20, horizon + 28):
        for x in range(20, 150):
            px[y][x] = (0.94, 0.95, 0.96)
    return px


def blur3(frame, x, y):
    acc = [0.0, 0.0, 0.0]
    for dy in (-1, 0, 1):
        for dx in (-1, 0, 1):
            c = frame[min(len(frame) - 1, max(0, y + dy))][min(len(frame[0]) - 1, max(0, x + dx))]
            for i in range(3):
                acc[i] += c[i]
    return tuple(v / 9 for v in acc)


def main() -> None:
    base = make_frame()
    presets = [
        ("RAW RENDER", 1.0, 1.0, 0.0, 0.0),
        ("DAY · NOON", 1.10, 1.14, 0.18, 0.15),
        ("NIGHT · LIGHTS OUT", 1.12, 0.70, -0.24, 0.34),
        ("HAUNTED", 1.13, 0.60, -0.36, 0.30),
        ("GRADE OFF (SLIDER AT 0)", 1.0, 1.0, 0.0, 0.0),
    ]
    buf = bytearray((14, 18, 24) * (W * H))

    text(buf, "COLOUR GRADE — THE SAME FRAME, FOUR LOOKS", 40, 28, 2, (238, 242, 248))
    text(buf, "FORMULA COPIED FROM COMPOSITE_FRAG: SATURATION, CONTRAST, SPLIT TONE, LOCAL CLARITY.", 40, 56, 1, (132, 146, 166))

    x0 = 40
    for i, (name, contrast, sat, warmth, clarity) in enumerate(presets):
        col = i % 3
        row = i // 3
        ox = x0 + col * (PANEL_W + 30)
        oy = 96 + row * (PANEL_H + 70)
        for y in range(PANEL_H):
            for x in range(PANEL_W):
                src = base[y][x]
                out = grade(src, contrast, sat, warmth, clarity, blur3(base, x, y))
                plot(buf, ox + x, oy + y, to8(out))
        text(buf, name, ox, oy + PANEL_H + 12, 1, (238, 242, 248))
        text(buf, f"C {contrast:.2f}  S {sat:.2f}  W {warmth:+.2f}  K {clarity:.2f}",
             ox, oy + PANEL_H + 26, 1, (132, 146, 166))

    # A histogram-ish bar: the grade should not clip.
    text(buf, "THE GRADE IS CLAMPED IN SHADER — HIGHLIGHT ROLLOFF IS ONE-SIDED, SO WHITE HUD TEXT STAYS WHITE.",
         40, H - 60, 1, (132, 146, 166))
    text(buf, "GRADE STRENGTH 0 IN SETTINGS RETURNS THE RAW RENDER EXACTLY.",
         40, H - 42, 1, (255, 190, 92))
    text(buf, "BRIGHTNESS COMES FROM EXPOSURE AND THE SKY PALETTE, NOT THE GRADE — SO NIGHT READS DARK IN GAME, NOT HERE.",
         40, H - 24, 1, (132, 146, 166))

    write_png(OUT, W, H, bytes(buf))
    print(f"wrote {OUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
