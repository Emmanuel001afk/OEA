#!/usr/bin/env python3
"""Generate Send Off's 3D weapon meshes as compact .oeam assets.

These are authored primitive meshes (stock/receiver/barrel/optic/magazine), not
AI-generated models. The format is tiny: OEAM header, 12-float vertices
(pos/normal/uv/linear RGBA) and u32 indices. The game loads them with
WeaponAssetLoader and falls back to the old procedural boxes if a file is
missing.

Usage: python3 tools/tripo/weapons_to_oeam.py
"""

from __future__ import annotations

import argparse
import math
import struct
from pathlib import Path
from typing import Dict, List, Sequence, Tuple

RGB = Tuple[int, int, int]
Vec = Tuple[float, float, float]

PALETTE: Dict[str, RGB] = {
    "body": (84, 90, 96),
    "dark": (50, 54, 60),
    "metal": (142, 150, 158),
    "grip": (64, 58, 48),
    "tan": (150, 124, 80),
    "optic": (40, 58, 70),
    "glass": (110, 200, 224),
    "accent": (210, 144, 62),
    "shell": (168, 98, 66),
}


def srgb_to_linear(c: float) -> float:
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


class Builder:
    def __init__(self) -> None:
        self.vertices: List[float] = []
        self.indices: List[int] = []

    def vertex(self, p: Vec, n: Vec, uv: Tuple[float, float], colour: RGB) -> int:
        length = math.sqrt(sum(v * v for v in n)) or 1.0
        v = [p[0], p[1], p[2], n[0] / length, n[1] / length, n[2] / length, uv[0], uv[1]]
        v.extend(srgb_to_linear(c / 255.0) for c in colour)
        v.append(1.0)
        i = len(self.vertices) // 12
        self.vertices.extend(v)
        return i

    def quad(self, a: int, b: int, c: int, d: int) -> None:
        self.indices.extend([a, b, c, a, c, d])

    def box(self, centre: Vec, half: Vec, colour: RGB) -> None:
        faces = [
            ((0, 0, -1), [(-1, -1, -1), (1, -1, -1), (1, 1, -1), (-1, 1, -1)]),
            ((0, 0, 1), [(-1, -1, 1), (-1, 1, 1), (1, 1, 1), (1, -1, 1)]),
            ((-1, 0, 0), [(-1, -1, -1), (-1, 1, -1), (-1, 1, 1), (-1, -1, 1)]),
            ((1, 0, 0), [(1, -1, -1), (1, -1, 1), (1, 1, 1), (1, 1, -1)]),
            ((0, -1, 0), [(-1, -1, -1), (-1, -1, 1), (1, -1, 1), (1, -1, -1)]),
            ((0, 1, 0), [(-1, 1, -1), (1, 1, -1), (1, 1, 1), (-1, 1, 1)]),
        ]
        for normal, corners in faces:
            ids = []
            for sx, sy, sz in corners:
                p = (centre[0] + sx * half[0], centre[1] + sy * half[1], centre[2] + sz * half[2])
                ids.append(self.vertex(p, normal, ((sx + 1) / 2, (sy + 1) / 2), colour))
            self.quad(ids[3], ids[2], ids[1], ids[0])

    def cylinder(self, centre: Vec, radius: float, length: float, colour: RGB, axis: str = "z", segments: int = 12) -> None:
        def position(a: float, t: float) -> Vec:
            if axis == "z":
                return (centre[0] + math.cos(a) * radius, centre[1] + math.sin(a) * radius, centre[2] + t * length)
            if axis == "y":
                return (centre[0] + math.cos(a) * radius, centre[1] + t * length, centre[2] + math.sin(a) * radius)
            return (centre[0] + t * length, centre[1] + math.cos(a) * radius, centre[2] + math.sin(a) * radius)

        def normal(a: float) -> Vec:
            if axis == "z":
                return (math.cos(a), math.sin(a), 0.0)
            if axis == "y":
                return (math.cos(a), 0.0, math.sin(a))
            return (0.0, math.cos(a), math.sin(a))

        base = len(self.vertices) // 12
        for i in range(segments + 1):
            a = 2 * math.pi * i / segments
            for t in (-0.5, 0.5):
                self.vertex(position(a, t), normal(a), (i / segments, t + 0.5), colour)
        for i in range(segments):
            a = base + i * 2
            self.quad(a, a + 2, a + 3, a + 1)
        # Caps keep silhouettes solid from front/back. Their outward direction
        # follows the chosen axis; winding is flipped for the negative cap.
        cap_normal: Vec = ((0, 0, -1), (0, 0, 1)) if axis == "z" else (
            ((0, -1, 0), (0, 1, 0)) if axis == "y" else ((-1, 0, 0), (1, 0, 0))
        )
        for side, t in enumerate((-0.5, 0.5)):
            n = cap_normal[side]
            c = self.vertex(centre, n, (0.5, 0.5), colour)
            ring = []
            for i in range(segments + 1):
                a = 2 * math.pi * i / segments
                ring.append(self.vertex(position(a, t), n, (math.cos(a) * 0.5 + 0.5, math.sin(a) * 0.5 + 0.5), colour))
            for i in range(segments):
                # Y rings are traversed in XZ, so they need the inverse order.
                positive = (side == 1) != (axis == "y")
                self.indices.extend([c, ring[i], ring[i + 1]] if positive else [c, ring[i + 1], ring[i]])

    def cone(self, centre: Vec, radius: float, length: float, colour: RGB, axis: str = "z", segments: int = 12) -> None:
        base = len(self.vertices) // 12
        tip = (centre[0], centre[1], centre[2] + length / 2) if axis == "z" else (centre[0], centre[1] + length / 2, centre[2])
        tip_id = self.vertex(tip, (0, 0, 1) if axis == "z" else (0, 1, 0), (0.5, 1), colour)
        for i in range(segments + 1):
            a = 2 * math.pi * i / segments
            p = (centre[0] + math.cos(a) * radius, centre[1] + math.sin(a) * radius, centre[2] - length / 2) if axis == "z" \
                else (centre[0] + math.cos(a) * radius, centre[1] - length / 2, centre[2] + math.sin(a) * radius)
            n = (math.cos(a), math.sin(a), 0.25) if axis == "z" else (math.cos(a), 0.25, math.sin(a))
            self.vertex(p, n, (i / segments, 0), colour)
        for i in range(segments):
            if axis == "y":
                self.indices.extend([tip_id, base + i + 1, base + i])
            else:
                self.indices.extend([tip_id, base + i, base + i + 1])


def detail_gun(b: Builder, kind: str) -> None:
    P = PALETTE
    body, dark, metal, grip = P["body"], P["dark"], P["metal"], P["grip"]
    if kind == "pan":
        b.cylinder((0, 0, 0.26), 0.20, 0.05, metal, "z", 20)
        b.box((0, 0.0, -0.04), (0.035, 0.035, 0.22), grip)
        b.cylinder((0, 0, 0.29), 0.13, 0.012, dark, "z", 16)
        return
    if kind == "pistol":
        b.box((0, 0.02, 0.10), (0.055, 0.055, 0.19), body)
        b.box((0, -0.09, -0.01), (0.048, 0.10, 0.055), grip)
        b.box((0, -0.055, 0.09), (0.012, 0.055, 0.035), dark)
        b.cylinder((0, 0.02, 0.27), 0.016, 0.06, metal, "z")
        return
    if kind == "revolver":
        b.cylinder((0, 0.02, 0.06), 0.055, 0.10, metal, "z", 12)
        b.box((0, 0.02, 0.19), (0.025, 0.025, 0.14), body)
        b.box((0, -0.09, -0.02), (0.045, 0.10, 0.05), grip)
        b.box((0, -0.04, 0.11), (0.014, 0.05, 0.03), dark)
        return

    # Shared rifle/SMG anatomy: receiver, stock, barrel, grip, magazine, optic.
    length = {
        "smg": 0.42, "rifle": 0.72, "shotgun": 0.82, "sniper": 1.04,
        "marksman": 0.70, "lmg": 0.66, "rocket": 0.95, "missile": 0.85,
        "mortar": 0.66, "howitzer": 0.82,
    }.get(kind, 0.6)
    front = length * 0.5
    receiver_len = length * 0.48
    b.box((0, 0, front - receiver_len * 0.5), (0.055, 0.075, receiver_len * 0.5), body)
    b.box((0, -0.015, front - receiver_len - 0.06), (0.06, 0.06, 0.16), dark)
    b.box((0, -0.085, front - receiver_len * 0.75), (0.035, 0.075, 0.05), grip)
    b.box((0, -0.115, front - receiver_len * 0.45), (0.04, 0.11, 0.055), P["tan"] if kind in ("smg", "rifle", "lmg") else dark)
    b.box((0, -0.02, -front + 0.11), (0.05, 0.07, 0.16), grip)
    b.cylinder((0, 0.015, front - 0.02), 0.018 if kind not in ("shotgun", "rocket") else 0.036, 0.22, metal, "z", 10)

    if kind in ("rifle", "marksman", "sniper", "lmg"):
        b.box((0, 0.095, front - receiver_len * 0.55), (0.025, 0.03, 0.14), P["optic"])
        b.box((0, 0.095, front - receiver_len * 0.55 + 0.075), (0.019, 0.019, 0.008), P["glass"])
    if kind == "shotgun":
        b.box((0, -0.05, front - 0.12), (0.07, 0.035, 0.22), P["shell"])
        b.cylinder((0, 0.015, front + 0.05), 0.026, 0.24, dark, "z", 10)
    if kind == "lmg":
        b.box((0, -0.15, front - receiver_len * 0.4), (0.09, 0.10, 0.09), dark)
        b.box((0, 0.13, front - receiver_len * 0.9), (0.02, 0.02, 0.30), metal)
    if kind == "sniper":
        b.cylinder((0, 0.13, front - receiver_len * 0.5), 0.035, 0.34, dark, "z", 12)
        b.cylinder((0, 0.13, front - receiver_len * 0.5 + 0.18), 0.038, 0.012, P["glass"], "z", 12)
        b.box((0, -0.10, front - 0.32), (0.012, 0.10, 0.012), metal)
        b.box((0, -0.10, front - 0.12), (0.012, 0.10, 0.012), metal)
    if kind == "rocket":
        b.cylinder((0, 0.02, 0.04), 0.085, length * 0.86, body, "z", 16)
        b.cylinder((0, 0.02, front - 0.02), 0.095, 0.06, accent(kind), "z", 16)
        b.cone((0, 0.02, front + 0.10), 0.08, 0.18, P["shell"], "z", 14)
        b.box((0, -0.14, -0.06), (0.045, 0.10, 0.06), grip)
    if kind == "missile":
        b.cylinder((0, 0.02, 0.0), 0.065, length * 0.8, body, "z", 14)
        b.cone((0, 0.02, front - 0.02), 0.065, 0.20, P["accent"], "z", 14)
        b.box((0, 0.10, -0.12), (0.012, 0.09, 0.16), metal)
        b.box((0, -0.11, -0.18), (0.035, 0.08, 0.12), grip)
    if kind in ("mortar", "howitzer"):
        b.cylinder((0, 0.06, 0.10), 0.085 if kind == "mortar" else 0.10, length * 0.72, body, "z", 16)
        b.cylinder((0, 0.06, front - 0.18), 0.095 if kind == "mortar" else 0.115, 0.05, metal, "z", 16)
        b.box((0, -0.15, -0.05), (0.22, 0.06, 0.22), dark)
        b.box((0, -0.08, -0.20), (0.10, 0.10, 0.08), grip)


def accent(kind: str) -> RGB:
    return PALETTE["accent"] if kind in ("rocket", "missile") else PALETTE["dark"]


def write_oeam(path: Path, b: Builder) -> None:
    out = bytearray(b"OEAM")
    out.extend(struct.pack("<HHI", 1, len(b.vertices) // 12, len(b.indices)))
    out.extend(struct.pack("<%df" % len(b.vertices), *b.vertices))
    out.extend(struct.pack("<%dI" % len(b.indices), *b.indices))
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(out)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assets", type=Path, default=Path("oea-engine/src/main/assets"))
    args = parser.parse_args()
    names = ["pan", "pistol", "smg", "rifle", "shotgun", "sniper", "revolver", "marksman",
             "lmg", "rocket", "missile", "mortar", "howitzer"]
    for name in names:
        b = Builder()
        detail_gun(b, name)
        out = args.assets / "weapons" / f"{name}.oeam"
        write_oeam(out, b)
        print(f"{name}: {len(b.vertices) // 12} vertices, {len(b.indices) // 3} triangles")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
