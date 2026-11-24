#!/usr/bin/env python3
"""Tiny software preview for a .oeac character (flat-shaded front view).

No GPU, no external libraries — useful for sanity-checking a locally generated
mesh before it reaches a phone.
"""

from __future__ import annotations

import argparse
import math
import struct
import sys
from pathlib import Path
from typing import List, Tuple

sys.path.insert(0, str(Path(__file__).resolve().parent))
import concept_to_oeac as c2o  # noqa: E402
import glb_to_oeac as oeac  # noqa: E402


def read_oeac(path: Path) -> Tuple[List[dict], float]:
    data = path.read_bytes()
    o = 4
    _, _, meshes, _ = struct.unpack_from("<4H", data, o); o += 8
    for _ in range(19):
        o += 2 + 64

    def string() -> str:
        nonlocal o
        n = data[o]; o += 1
        value = data[o:o + n].decode(); o += n
        return value

    parts: List[dict] = []
    for _ in range(meshes):
        name, tex = string(), string()
        color, _flags = struct.unpack_from("<2I", data, o); o += 8
        vc, ic = struct.unpack_from("<2I", data, o); o += 8
        verts = struct.unpack_from("<%df" % (vc * 20), data, o); o += vc * 80
        inds = struct.unpack_from("<%dI" % ic, data, o); o += ic * 4
        parts.append({"name": name, "tex": tex, "color": color, "verts": verts, "idx": inds})
    return parts, 0.0


def texture_average(folder: Path, name: str, fallback: int) -> Tuple[int, int, int]:
    if not name:
        return (fallback >> 16) & 255, (fallback >> 8) & 255, fallback & 255
    path = folder / name
    if not path.exists():
        return (fallback >> 16) & 255, (fallback >> 8) & 255, fallback & 255
    w, h, rows = c2o.read_png_rgb(path)
    step = max(1, (w * h) // 4096)
    total = [0, 0, 0]; count = 0
    for i in range(0, w * h, step):
        x, y = i % w, i // w
        for c in range(3):
            total[c] += rows[y][x * 3 + c]
        count += 1
    return tuple(v // max(1, count) for v in total)  # type: ignore[return-value]


def render(asset: Path, out: Path, tex_dir: Path, width: int = 512, height: int = 768) -> None:
    parts, _ = read_oeac(asset)
    pixels = bytearray(b"\xff" * (width * height * 3))
    scale = height / 2.0
    cx, cy = width / 2.0, height * 0.96

    def project(x: float, y: float) -> Tuple[int, int]:
        return int(cx + x * scale), int(cy - y * scale)

    triangles = []
    for part in parts:
        colour = texture_average(tex_dir, part["tex"], part["color"])
        verts, idx = part["verts"], part["idx"]
        for i in range(0, len(idx), 3):
            a, b, c = idx[i], idx[i + 1], idx[i + 2]
            pts = []
            for v in (a, b, c):
                o = v * 20
                pts.append((verts[o], verts[o + 1], verts[o + 2]))
            depth = sum(p[2] for p in pts) / 3.0
            triangles.append((depth, pts, colour))
    triangles.sort(key=lambda t: t[0])

    for _depth, pts, colour in triangles:
        xy = [project(p[0], p[1]) for p in pts]
        minx = max(0, min(p[0] for p in xy)); maxx = min(width - 1, max(p[0] for p in xy))
        miny = max(0, min(p[1] for p in xy)); maxy = min(height - 1, max(p[1] for p in xy))
        if minx > maxx or miny > maxy:
            continue
        (x0, y0), (x1, y1), (x2, y2) = xy
        den = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2)
        if abs(den) < 1e-6:
            continue
        for y in range(miny, maxy + 1):
            for x in range(minx, maxx + 1):
                w0 = ((y1 - y2) * (x - x2) + (x2 - x1) * (y - y2)) / den
                w1 = ((y2 - y0) * (x - x2) + (x0 - x2) * (y - y2)) / den
                w2 = 1 - w0 - w1
                if w0 < 0 or w1 < 0 or w2 < 0:
                    continue
                o = (y * width + x) * 3
                pixels[o], pixels[o + 1], pixels[o + 2] = colour
    c2o.write_png(out, width, height, bytes(pixels))
    print(f"wrote {out} ({len(triangles)} triangles)")


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("asset", type=Path, nargs="?", default=Path("oea-engine/src/main/assets/characters/hero.oeac"))
    p.add_argument("--out", type=Path, default=Path("tools/tripo/concepts/local-mesh-preview.png"))
    p.add_argument("--textures", type=Path, default=Path("oea-engine/src/main/assets/characters/hero"))
    args = p.parse_args()
    render(args.asset, args.out, args.textures)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
