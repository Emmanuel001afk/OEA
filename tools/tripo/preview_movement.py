#!/usr/bin/env python3
"""Preview the movement / environment coupling in Send Off.

Shows, side by side: how fast the body ramps up per surface, the grip of each
surface, slope cost, and the wind vector the whole world shares. The numbers
are the ones implemented in `ArenaMap.surfaceAt`, `ArenaMap.gripAt` and
`ArenaSim.move`; this sheet is a design reference, not a game screenshot.
"""

from __future__ import annotations

import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from concept_to_oeac import write_png  # noqa: E402
from preview_environment import FONT as BASE_FONT, mix  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "tools/tripo/concepts/movement-preview.png"
W, H = 1200, 860

EXTRA = {
    "F": ["11111", "10000", "10000", "11110", "10000", "10000", "10000"],
    "J": ["00111", "00010", "00010", "00010", "10010", "10010", "01100"],
    "K": ["10001", "10010", "10100", "11000", "10100", "10010", "10001"],
    "Q": ["01110", "10001", "10001", "10001", "10101", "10010", "01101"],
    "X": ["10001", "10001", "01010", "00100", "01010", "10001", "10001"],
    "Z": ["11111", "00001", "00010", "00100", "01000", "10000", "11111"],
    "1": ["00100", "01100", "00100", "00100", "00100", "00100", "01110"],
    "4": ["00010", "00110", "01010", "10010", "11111", "00010", "00010"],
    "5": ["11111", "10000", "11110", "00001", "00001", "10001", "01110"],
    "6": ["00110", "01000", "10000", "11110", "10001", "10001", "01110"],
    "7": ["11111", "00001", "00010", "00100", "01000", "01000", "01000"],
    "8": ["01110", "10001", "10001", "01110", "10001", "10001", "01110"],
    "9": ["01110", "10001", "10001", "01111", "00001", "00010", "01100"],
    "&": ["01100", "10010", "10100", "01000", "10101", "10010", "01101"],
    ".": ["00000", "00000", "00000", "00000", "00000", "00110", "00110"],
    ",": ["00000", "00000", "00000", "00000", "00110", "00110", "00100"],
    "/": ["00001", "00010", "00010", "00100", "01000", "01000", "10000"],
    "%": ["11001", "11010", "00010", "00100", "01000", "01011", "10011"],
    ":": ["00000", "00100", "00100", "00000", "00100", "00100", "00000"],
    "-": ["00000", "00000", "00000", "11111", "00000", "00000", "00000"],
    "(": ["00010", "00100", "01000", "01000", "01000", "00100", "00010"],
    ")": ["01000", "00100", "00010", "00010", "00010", "00100", "01000"],
}
FONT = {**BASE_FONT, **EXTRA}

SURFACES = [
    # name, grip, colour
    ("GRASS", 0.94, (126, 200, 120)),
    ("DIRT", 0.95, (186, 143, 96)),
    ("ROCK", 1.00, (160, 168, 178)),
    ("SAND", 0.86, (226, 205, 140)),
    ("WOOD", 0.97, (176, 132, 90)),
    ("SNOW", 0.78, (226, 238, 248)),
    ("WATER", 0.62, (96, 168, 220)),
]


def plot(px, x, y, c):
    if 0 <= x < W and 0 <= y < H:
        o = (y * W + x) * 3
        px[o:o + 3] = bytes(max(0, min(255, int(v))) for v in c)


def label(px, text, x, y, scale=2, colour=(235, 240, 246)):
    for ch in text.upper():
        glyph = FONT.get(ch, FONT[" "])
        for row, bits in enumerate(glyph):
            for col, bit in enumerate(bits):
                if bit == "1":
                    for sy in range(scale):
                        for sx in range(scale):
                            plot(px, x + col * scale + sx, y + row * scale + sy, colour)
        x += 6 * scale
    return x


def rect(px, x, y, w, h, c):
    for yy in range(y, y + h):
        for xx in range(x, x + w):
            plot(px, xx, yy, c)


def circle(px, cx, cy, r, c):
    for y in range(int(cy - r), int(cy + r) + 1):
        for x in range(int(cx - r), int(cx + r) + 1):
            if (x - cx) ** 2 + (y - cy) ** 2 <= r * r:
                plot(px, x, y, c)


def ramp_curve(accel, dt_total=0.6, steps=120):
    """Speed fraction over time for a first-order ramp (matches the sim)."""
    v = 0.0
    dt = dt_total / steps
    out = []
    for _ in range(steps + 1):
        k = 1.0 - math.exp(-accel * dt)
        v += (1.0 - v) * k
        out.append(v)
    return out


def main() -> int:
    px = bytearray((15, 19, 25) * (W * H))
    label(px, "SEND OFF - MOVEMENT & WORLD", 24, 16, 3, (255, 214, 120))
    label(px, "VELOCITY RAMP · SURFACE GRIP · SLOPE · WIND", 24, 44, 2, (130, 145, 165))

    # --- Surface grip bars -------------------------------------------------
    label(px, "SURFACE GRIP", 24, 82, 2, (255, 214, 120))
    label(px, "SPEED IS THE WALK 4.8 M/S MULTIPLIED BY GRIP", 24, 102, 1, (130, 145, 165))
    for i, (name, grip, col) in enumerate(SURFACES):
        y = 124 + i * 30
        label(px, name, 24, y + 3, 2, col)
        rect(px, 150, y, 300, 16, (36, 42, 52))
        rect(px, 150, y, int(300 * grip), 16, col)
        label(px, f"{grip:.2f}", 470, y + 3, 2, (220, 228, 238))
        label(px, f"{4.8 * grip:.2f} M/S", 540, y + 4, 1, (150, 165, 185))

    # --- Velocity ramp chart ----------------------------------------------
    label(px, "VELOCITY RAMP (GROUND)", 660, 82, 2, (255, 214, 120))
    label(px, "FRACTION OF WALK SPEED OVER 0.6 S", 660, 102, 1, (130, 145, 165))
    chart_x, chart_y, chart_w, chart_h = 660, 124, 500, 200
    rect(px, chart_x, chart_y, chart_w, chart_h, (22, 27, 34))
    for g in range(5):
        gy = chart_y + int(chart_h * g / 4)
        for gx in range(chart_x, chart_x + chart_w, 3):
            plot(px, gx, gy, (40, 47, 58))
    curves = [(15.0, (255, 190, 110), "FLAT 15"), (11.0, (140, 200, 255), "UPHILL 11"), (4.5, (168, 226, 178), "AIR 4.5")]
    for accel, col, _name in curves:
        pts = ramp_curve(accel)
        for i, v in enumerate(pts):
            x = chart_x + int(chart_w * i / (len(pts) - 1))
            y = chart_y + chart_h - int(chart_h * v)
            circle(px, x, y, 2, col)
    for i, (_a, col, name) in enumerate(curves):
        label(px, name, chart_x + 12 + i * 150, chart_y + chart_h + 8, 1, col)
    label(px, "TO FULL SPEED: 0.20 S FLAT · 0.27 S UPHILL", chart_x, chart_y + chart_h + 26, 1, (130, 145, 165))

    # --- Slope coupling ----------------------------------------------------
    y0 = 388
    label(px, "SLOPE COUPLING", 24, y0, 2, (255, 214, 120))
    label(px, "DOWN THE FALL LINE FASTER - UP IT SLOWER - PAST 0.5 YOU SLIDE", 24, y0 + 20, 1, (130, 145, 165))
    for i in range(11):
        slope = i / 10.0
        y = y0 + 40 + i * 17
        downhill = 1.0 + slope * 0.5
        uphill = max(0.62, 1.0 - slope * 0.55)
        mid = 220
        rect(px, 24, y, 420, 12, (32, 38, 47))
        # Centre line = 1.00x. Downhill gain grows right, uphill loss grows left.
        wd = int((420 - mid - 4) * (downhill - 1.0) / 0.5)
        wu = int((mid - 24) * (1.0 - uphill) / 0.38) if uphill < 1 else 0
        rect(px, mid, y, max(0, wd), 12, (126, 200, 120))
        rect(px, mid - max(0, wu), y, max(0, wu), 12, (255, 150, 130))
        label(px, f"SLOPE {slope:.1f}", 460, y, 1, (150, 165, 185))
        if slope >= 0.5:
            label(px, "SLIDE", 560, y, 1, (255, 120, 100))
    label(px, "UPHILL LOSS", 24, y0 + 232, 1, (255, 150, 130))
    label(px, "1.00X", 208, y0 + 232, 1, (150, 165, 185))
    label(px, "DOWNHILL GAIN", 300, y0 + 232, 1, (126, 200, 120))

    # --- Wind --------------------------------------------------------------
    wx0, wy0 = 660, 388
    label(px, "ONE WIND FOR EVERYTHING", wx0, wy0, 2, (255, 214, 120))
    label(px, "SOLDIERS · GRENADES · FOLIAGE · SEA · DEBRIS", wx0, wy0 + 20, 1, (130, 145, 165))
    cx, cy, r = wx0 + 220, wy0 + 150, 118
    for yy in range(cy - r, cy + r):
        for xx in range(cx - r, cx + r):
            d2 = (xx - cx) ** 2 + (yy - cy) ** 2
            if d2 <= r * r:
                plot(px, xx, yy, mix((26, 34, 44), (46, 60, 76), d2 / (r * r)))
    wind_dir = 0.42, -0.35
    for k in range(9):
        t0 = k / 9.0
        for s in range(70):
            t = s / 70.0
            ang = t * 5.0 + k
            rr = 18 + t * (r - 26)
            x = cx + math.sin(ang) * rr + t * 26 * wind_dir[0]
            y = cy + math.cos(ang) * rr + t * 26 * wind_dir[1]
            circle(px, x, y, 2.2, mix((90, 150, 200), (230, 245, 255), t))
    label(px, "GUST CYCLE 0.72-1.00", wx0, wy0 + 288, 1, (130, 145, 165))
    label(px, "SPRING 0.9 · SUMMER 0.55 · AUTUMN 1.15 · WINTER 1.3", wx0, wy0 + 306, 1, (130, 145, 165))

    write_png(OUT, W, H, bytes(px))
    print(f"wrote {OUT} ({W}x{H})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
