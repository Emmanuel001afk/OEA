#!/usr/bin/env python3
"""Preview the Send Off season × time-of-day palette matrix.

This is a 2D swatch sheet for reviewing the same colour decisions made by
`ArenaWorld.kt` and `ArenaMap.kt`; it is not a game screenshot.
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from concept_to_oeac import write_png  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "tools/tripo/concepts/environment-preview.png"
W, H = 1200, 800
CELL_W, CELL_H = 300, 190
SEASONS = ["SPRING", "SUMMER", "AUTUMN", "WINTER"]
TIMES = ["DAWN", "NOON", "DUSK", "NIGHT"]

# 5x7 bitmap font: enough for the labels on the matrix.
FONT = {
    "A": ["01110", "10001", "10001", "11111", "10001", "10001", "10001"],
    "B": ["11110", "10001", "10001", "11110", "10001", "10001", "11110"],
    "C": ["01111", "10000", "10000", "10000", "10000", "10000", "01111"],
    "D": ["11110", "10001", "10001", "10001", "10001", "10001", "11110"],
    "E": ["11111", "10000", "10000", "11110", "10000", "10000", "11111"],
    "G": ["01111", "10000", "10000", "10111", "10001", "10001", "01111"],
    "H": ["10001", "10001", "10001", "11111", "10001", "10001", "10001"],
    "I": ["11111", "00100", "00100", "00100", "00100", "00100", "11111"],
    "L": ["10000", "10000", "10000", "10000", "10000", "10000", "11111"],
    "M": ["10001", "11011", "10101", "10101", "10001", "10001", "10001"],
    "N": ["10001", "11001", "10101", "10011", "10001", "10001", "10001"],
    "O": ["01110", "10001", "10001", "10001", "10001", "10001", "01110"],
    "P": ["11110", "10001", "10001", "11110", "10000", "10000", "10000"],
    "R": ["11110", "10001", "10001", "11110", "10100", "10010", "10001"],
    "S": ["01111", "10000", "10000", "01110", "00001", "00001", "11110"],
    "T": ["11111", "00100", "00100", "00100", "00100", "00100", "00100"],
    "U": ["10001", "10001", "10001", "10001", "10001", "10001", "01110"],
    "V": ["10001", "10001", "10001", "10001", "10001", "01010", "00100"],
    "W": ["10001", "10001", "10001", "10101", "10101", "11011", "10001"],
    "Y": ["10001", "10001", "01010", "00100", "00100", "00100", "00100"],
    "0": ["01110", "10001", "10011", "10101", "11001", "10001", "01110"],
    "2": ["01110", "10001", "00001", "00010", "00100", "01000", "11111"],
    "3": ["11110", "00001", "00001", "01110", "00001", "00001", "11110"],
    "-": ["00000", "00000", "00000", "11111", "00000", "00000", "00000"],
    " ": ["00000"] * 7,
}


def mix(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def label(px, text, x, y, scale=2, colour=(235, 240, 246)):
    for ch in text:
        glyph = FONT.get(ch, FONT[" "])
        for row, bits in enumerate(glyph):
            for col, bit in enumerate(bits):
                if bit == "1":
                    for sy in range(scale):
                        for sx in range(scale):
                            plot(px, x + col * scale + sx, y + row * scale + sy, colour)
        x += (5 + 1) * scale


def plot(px, x, y, c):
    if 0 <= x < W and 0 <= y < H:
        o = (y * W + x) * 3
        px[o:o+3] = bytes(max(0, min(255, v)) for v in c)


def main():
    px = bytearray((18, 22, 28) * (W * H))
    for si, season in enumerate(SEASONS):
        for ti, time in enumerate(TIMES):
            ox, oy = ti * CELL_W, si * CELL_H + 20
            night = time == "NIGHT"
            dusk = time == "DUSK"
            dawn = time == "DAWN"
            top = (48, 111, 208) if not night else (5, 8, 20)
            horizon = (197, 220, 242) if not night else (27, 36, 64)
            ground = (98, 143, 62)
            if season == "SPRING":
                ground = mix(ground, (127, 198, 90), 0.3)
            elif season == "AUTUMN":
                ground = mix(ground, (192, 128, 66), 0.5)
                horizon = mix(horizon, (255, 196, 147), 0.45)
            elif season == "WINTER":
                ground = mix(ground, (198, 211, 206), 0.7)
                top = mix(top, (143, 180, 216), 0.35)
            if night:
                ground = mix(ground, (20, 26, 36), 0.75)
            if dusk or dawn:
                horizon = mix(horizon, (255, 176, 126), 0.7)
                top = mix(top, (105, 94, 164), 0.4)
            for y in range(CELL_H):
                t = y / (CELL_H - 1)
                c = mix(top, horizon, min(1, t * 1.6))
                if t > 0.62:
                    c = mix(c, ground, min(1, (t - 0.62) * 3.2))
                for x in range(CELL_W):
                    plot(px, ox + x, oy + y, c)
            # Sun or moon.
            sx, sy, sr = ox + 228, oy + (48 if not dusk and not dawn else 92), 17
            disc = (255, 226, 168) if not night else (198, 214, 255)
            for y in range(sy - sr, sy + sr):
                for x in range(sx - sr, sx + sr):
                    if (x - sx) ** 2 + (y - sy) ** 2 <= sr * sr:
                        plot(px, x, y, disc)
            # Landmark silhouette, so the season reads on geometry too.
            for y in range(oy + 104, oy + 190):
                for x in range(ox + 34, ox + 86):
                    plot(px, x, y, mix((84, 92, 100), ground, 0.25))
            for y in range(oy + 84, oy + 108):
                for x in range(ox + 46, ox + 74):
                    plot(px, x, y, (214, 218, 222) if not night else (150, 164, 190))
            for x in range(ox + 58, ox + 62):
                for y in range(oy + 76, oy + 86):
                    plot(px, x, y, (255, 205, 130) if night else (190, 78, 58))
            label(px, f"{time} - {season}", ox + 12, oy + 10)
    # Column headers.
    for ti, time in enumerate(TIMES):
        label(px, time, ti * CELL_W + 110, 4, 2, (255, 210, 120))
    write_png(OUT, W, H, bytes(px))
    print(f"wrote {OUT} ({W}x{H})")


if __name__ == "__main__":
    main()
