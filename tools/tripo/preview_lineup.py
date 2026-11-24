#!/usr/bin/env python3
"""Render a front-view lineup of every generated Send Off character.

Uses the same shaded, depth-buffered renderer as the single-character preview,
so the lineup shows the shipped meshes rather than a flat silhouette.
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import render_oeac as R  # noqa: E402
from concept_to_oeac import write_png  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "oea-engine/src/main/assets/characters"
OUT = ROOT / "tools/tripo/concepts/character-lineup.png"

CHARS = ["hero", "bot_ranger", "bot_medic", "bot_heavy", "bot_scout", "bot_night"]
TILE_W, TILE_H = 260, 560
BG = (22, 26, 32)
FOOT = 40


def main() -> None:
    width = TILE_W * len(CHARS)
    height = TILE_H + FOOT
    canvas = bytearray()
    for _ in range(width * height):
        canvas += bytes(BG)

    for col, name in enumerate(CHARS):
        parts, inverse_bind, tex_dir = R.load(name)
        tile = R.render(parts, tex_dir, TILE_W, TILE_H, 0.0, 0.0, BG, ss=1)
        for y in range(TILE_H):
            src = y * TILE_W * 3
            dst = (y * width + col * TILE_W) * 3
            canvas[dst:dst + TILE_W * 3] = tile[src:src + TILE_W * 3]
        R.draw_label(canvas, width, height, col * TILE_W + TILE_W // 2,
                     TILE_H + 12, name.replace("bot_", ""), BG,
                     ink=(226, 232, 240), px=2)

    write_png(OUT, width, height, bytes(canvas))
    print(f"wrote {OUT} ({width}x{height}, {len(CHARS)} characters)")


if __name__ == "__main__":
    main()
