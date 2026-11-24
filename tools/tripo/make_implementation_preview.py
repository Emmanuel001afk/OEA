#!/usr/bin/env python3
"""Compose the implemented Send Off art into one visual preview sheet."""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from concept_to_oeac import read_png_rgb, write_png  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
CONCEPTS = ROOT / "tools/tripo/concepts"
OUT = CONCEPTS / "implementation-preview.png"
BAR = (39, 46, 56)


def main() -> None:
    cw, ch, crows = read_png_rgb(CONCEPTS / "character-lineup.png")
    ww, wh, wrows = read_png_rgb(CONCEPTS / "weapon-preview.png")
    width = max(cw, ww)
    gap = 14
    height = ch + gap + wh
    out = bytearray(BAR * (width * height))
    for y in range(ch):
        row = crows[y]
        out[y * width * 3:(y * width + cw) * 3] = row[:cw * 3]
    base = ch + gap
    for y in range(wh):
        row = wrows[y]
        out[(base + y) * width * 3:(base + y) * width * 3 + ww * 3] = row[:ww * 3]
    write_png(OUT, width, height, bytes(out))
    print(f"wrote {OUT} ({width}x{height})")


if __name__ == "__main__":
    main()
