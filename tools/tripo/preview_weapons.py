#!/usr/bin/env python3
"""Preview every generated .oeam weapon as a shaded 3/4 view (no dependencies)."""

from __future__ import annotations

import struct
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from concept_to_oeac import write_png  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "oea-engine/src/main/assets/weapons"
OUT = ROOT / "tools/tripo/concepts/weapon-preview.png"
W, H = 1200, 640
BG = (21, 25, 31)


def to_srgb(c: float) -> int:
    v = 12.92 * c if c <= 0.0031308 else 1.055 * c ** (1 / 2.4) - 0.055
    return max(0, min(255, int(v * 255)))


def read_oeam(path: Path):
    data = path.read_bytes()
    assert data[:4] == b"OEAM"
    _, vc, ic = struct.unpack_from("<HHI", data, 4)
    verts = struct.unpack_from("<%df" % (vc * 12), data, 12)
    idx = struct.unpack_from("<%dI" % ic, data, 12 + vc * 48)
    return verts, idx


def main() -> None:
    buf = bytearray(BG * (W * H))
    names = ["pan", "pistol", "revolver", "smg", "rifle", "shotgun", "marksman",
             "sniper", "lmg", "rocket", "missile", "mortar", "howitzer"]
    def plot(x: int, y: int, c):
        if 0 <= x < W and 0 <= y < H:
            o = (y * W + x) * 3
            buf[o:o+3] = bytes(c)
    for n, name in enumerate(names):
        verts, idx = read_oeam(ASSETS / (name + ".oeam"))
        col, row = n % 7, n // 7
        cx, cy = 90 + col * 170, 175 + row * 300
        scale = 150 if name not in ("mortar", "howitzer", "rocket", "sniper") else 115
        tris = []
        for i in range(0, len(idx) - 2, 3):
            pts, cols, depth = [], [], 0.0
            for vi in idx[i:i+3]:
                o = vi * 12
                x, y, z = verts[o:o+3]
                nx, ny, nz = verts[o+3:o+6]
                col3 = tuple(to_srgb(max(0, min(1, verts[o+8+k]))) for k in range(3))
                sx = cx + (x * 0.6 + z * 0.8) * scale
                sy = cy - (y * 0.9 - z * 0.25) * scale
                light = 0.5 + 0.5 * max(0.0, nx * -0.4 + ny * 0.75 + nz * 0.5)
                pts.append((sx, sy))
                cols.append(tuple(int(c * light) for c in col3))
                depth += z
            tris.append((depth / 3, pts, cols))
        tris.sort(key=lambda t: t[0])
        for _, pts, cols in tris:
            xs = [p[0] for p in pts]; ys = [p[1] for p in pts]
            x0, x1 = max(0, int(min(xs))), min(W - 1, int(max(xs)))
            y0, y1 = max(0, int(min(ys))), min(H - 1, int(max(ys)))
            (ax, ay), (bx, by), (cx2, cy2) = pts
            den = (by - cy2) * (ax - cx2) + (cx2 - bx) * (ay - cy2)
            if abs(den) < 1e-6: continue
            for y in range(y0, y1 + 1):
                for x in range(x0, x1 + 1):
                    w0 = ((by - cy2) * (x - cx2) + (cx2 - bx) * (y - cy2)) / den
                    w1 = ((cy2 - ay) * (x - cx2) + (ax - cx2) * (y - cy2)) / den
                    w2 = 1 - w0 - w1
                    if w0 < 0 or w1 < 0 or w2 < 0: continue
                    c = tuple(int(sum(cols[k][ch] * w for k, w in enumerate((w0, w1, w2)))) for ch in range(3))
                    plot(x, y, c)
        for i, ch in enumerate(name.upper()):
            lx = int(cx - len(name) * 4 + i * 8)
            for y in range(cy + 105, cy + 114):
                plot(lx, y, (205, 215, 228))
                plot(lx + 1, y, (205, 215, 228))
    write_png(OUT, W, H, bytes(buf))
    print(f"wrote {OUT} ({W}x{H})")


if __name__ == "__main__":
    main()
