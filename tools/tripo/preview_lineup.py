#!/usr/bin/env python3
"""Render a front-view lineup of every generated Send Off character."""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from preview_oeac import read_oeac, texture_average  # noqa: E402
from concept_to_oeac import write_png  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "oea-engine/src/main/assets/characters"
OUT = ROOT / "tools/tripo/concepts/character-lineup.png"

WIDTH, HEIGHT = 1200, 700
BG = (24, 28, 34)


def main() -> None:
    pixels = bytearray(BG * (WIDTH * HEIGHT))
    chars = ["hero", "bot_ranger", "bot_medic", "bot_heavy", "bot_scout", "bot_night"]
    for col, name in enumerate(chars):
        parts, _ = read_oeac(ASSETS / (name + ".oeac"))
        cx = 100 + col * 200
        cy = 520
        scale = 290
        tris = []
        for part in parts:
            colour = texture_average(ASSETS / name, part["tex"], part["color"])
            verts, idx = part["verts"], part["idx"]
            for i in range(0, len(idx) - 2, 3):
                pts = []
                for v in idx[i:i+3]:
                    o = v * 20
                    pts.append((int(cx + verts[o] * scale), int(cy - verts[o+1] * scale)))
                tris.append((sum(p[1] for p in pts) / 3, pts, colour))
        tris.sort(key=lambda t: t[0])
        for _, pts, colour in tris:
            minx = max(0, min(p[0] for p in pts)); maxx = min(WIDTH-1, max(p[0] for p in pts))
            miny = max(0, min(p[1] for p in pts)); maxy = min(HEIGHT-1, max(p[1] for p in pts))
            (x0,y0),(x1,y1),(x2,y2) = pts
            den = (y1-y2)*(x0-x2) + (x2-x1)*(y0-y2)
            if abs(den) < 1e-6: continue
            for y in range(miny, maxy+1):
                for x in range(minx, maxx+1):
                    w0 = ((y1-y2)*(x-x2)+(x2-x1)*(y-y2))/den
                    w1 = ((y2-y0)*(x-x2)+(x0-x2)*(y-y2))/den
                    w2 = 1-w0-w1
                    if w0 < 0 or w1 < 0 or w2 < 0: continue
                    o = (y*WIDTH+x)*3
                    shade = 0.85 + 0.15*max(0, min(1, (x-cx)/120))
                    pixels[o:o+3] = bytes(min(255, int(c*shade)) for c in colour)
        for x in range(cx-55, cx+55):
            for y in range(535, 540):
                o = (y*WIDTH+x)*3
                pixels[o:o+3] = bytes((74, 80, 88))
        label = name.replace("bot_", "").upper()
        for i, ch in enumerate(label):
            x0 = cx - len(label)*5 + i*10
            for y in range(558, 568):
                for x in range(x0, x0+7):
                    o = (y*WIDTH+x)*3
                    if 0 <= x < WIDTH: pixels[o:o+3] = bytes((220, 228, 236))
    write_png(OUT, WIDTH, HEIGHT, bytes(pixels))
    print(f"wrote {OUT} ({WIDTH}x{HEIGHT})")


if __name__ == "__main__":
    main()
