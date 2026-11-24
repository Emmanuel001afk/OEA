#!/usr/bin/env python3
"""Build a stopgap rigged 3D soldier from the saved Send Off concept image.

This is NOT Tripo3D and not a neural image-to-3D model. It reads the concept
PNG, measures its proportions/colours, and builds a clean low-poly humanoid
with OEA's 19-bone rig. The output is the same `.oeac` format the game already
loads, so it can be tried immediately while a real Tripo GLB is unavailable.

Usage:
  python3 tools/tripo/concept_to_oeac.py \
      --image tools/tripo/concepts/send-off-soldier-concept.png \
      --out oea-engine/src/main/assets/characters/hero.oeac \
      --textures oea-engine/src/main/assets/characters/hero
"""

from __future__ import annotations

import argparse
import json
import math
import random
import struct
import sys
import zlib
from pathlib import Path
from typing import Dict, List, Sequence, Tuple

sys.path.insert(0, str(Path(__file__).resolve().parent))
import glb_to_oeac as oeac  # noqa: E402  (same tools/tripo folder)

RGB = Tuple[int, int, int]
Vec = Tuple[float, float, float]
Weight = Tuple[int, float]

# OEA bind-pose joint offsets from Skeleton.kt. The mesh is authored around
# these positions and the inverse binds below are their exact inverses.
OFFSETS: List[Vec] = [
    (0.0, 0.90, 0.0), (0.0, 0.18, 0.0), (0.0, 0.28, 0.0), (0.0, 0.16, 0.0),
    (0.0, 0.12, 0.0),
    # A-pose arms: shoulders ±0.18/1.40, elbows ±0.38/1.20, wrists ±0.56/1.02.
    (0.20, -0.20, 0.0), (0.18, -0.18, 0.0), (0.06, -0.04, 0.01), (0.02, -0.01, 0.0),
    (-0.20, -0.20, 0.0), (-0.18, -0.18, 0.0), (-0.06, -0.04, 0.01), (-0.02, -0.01, 0.0),
    # Slightly apart legs: hips ±0.12, knees ±0.15, ankles ±0.17.
    (0.12, -0.02, 0.0), (0.03, -0.44, 0.0), (0.02, -0.42, 0.0),
    (-0.12, -0.02, 0.0), (-0.03, -0.44, 0.0), (-0.02, -0.42, 0.0),
]
B = oeac.BONE_INDEX

# Identifiable Send Off soldiers. The local player keeps the sampled concept
# palette; bots get their own colours and kit so they read apart in a match.
# `None` means "use the colour sampled from the concept image".
VARIANTS = {
    "hero": {"name": "Ranger 01", "role": "Local player", "gear": ()},
    "bot_ranger": {"name": "Ranger", "role": "Bot · assault", "gear": ("cap",),
                   "vest": (58, 86, 52), "shirt": (72, 96, 58), "pants": (58, 74, 46)},
    "bot_medic": {"name": "Medic", "role": "Bot · support", "gear": ("mask",),
                  "vest": (186, 188, 182), "shirt": (150, 154, 150), "pants": (72, 82, 92)},
    "bot_heavy": {"name": "Bulwark", "role": "Bot · heavy", "gear": ("helmet", "backpack"),
                  "vest": (72, 72, 80), "shirt": (60, 60, 66), "pants": (55, 55, 60)},
    "bot_scout": {"name": "Scout", "role": "Bot · recon", "gear": ("hood",),
                  "vest": (110, 92, 60), "shirt": (130, 110, 75), "pants": (105, 90, 65)},
    "bot_night": {"name": "Wraith", "role": "Bot · stealth", "gear": ("helmet", "mask"),
                  "vest": (35, 38, 46), "shirt": (30, 32, 38), "pants": (28, 30, 34)},
}


# --------------------------------------------------------------------- PNG I/O

def paeth(a: int, b: int, c: int) -> int:
    p = a + b - c
    pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
    return a if pa <= pb and pa <= pc else b if pb <= pc else c


def read_png_rgb(path: Path) -> Tuple[int, int, List[bytes]]:
    data = path.read_bytes()
    if not data.startswith(b"\x89PNG\r\n\x1a\n"):
        raise ValueError("not a PNG")
    pos, idat = 8, bytearray()
    width = height = 0
    while pos + 8 <= len(data):
        length = struct.unpack_from(">I", data, pos)[0]
        kind = data[pos + 4:pos + 8]
        payload = data[pos + 8:pos + 8 + length]
        pos += 12 + length
        if kind == b"IHDR":
            width, height, depth, colour, _, _, interlace = struct.unpack(">IIBBBBB", payload)
            if depth != 8 or colour != 2 or interlace != 0:
                raise ValueError("expected an 8-bit non-interlaced RGB PNG")
        elif kind == b"IDAT":
            idat.extend(payload)
        elif kind == b"IEND":
            break
    raw = zlib.decompress(bytes(idat))
    stride = width * 3
    rows: List[bytes] = []
    prev = bytearray(stride)
    for y in range(height):
        start = y * (stride + 1)
        filt = raw[start]
        line = bytearray(raw[start + 1:start + 1 + stride])
        for x in range(stride):
            a = line[x - 3] if x >= 3 else 0
            b = prev[x]
            c = prev[x - 3] if x >= 3 else 0
            if filt == 1:
                line[x] = (line[x] + a) & 255
            elif filt == 2:
                line[x] = (line[x] + b) & 255
            elif filt == 3:
                line[x] = (line[x] + (a + b) // 2) & 255
            elif filt == 4:
                line[x] = (line[x] + paeth(a, b, c)) & 255
            elif filt != 0:
                raise ValueError(f"unsupported PNG filter {filt}")
        rows.append(bytes(line))
        prev = line
    return width, height, rows


def write_png(path: Path, width: int, height: int, pixels: bytes) -> None:
    def chunk(kind: bytes, payload: bytes) -> bytes:
        return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF)

    raw = bytearray()
    for y in range(height):
        raw.append(0)
        raw.extend(pixels[y * width * 3:(y + 1) * width * 3])
    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    png += chunk(b"IEND", b"")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(png)


# --------------------------------------------------------------------- concept analysis

def patch_colour(rows: Sequence[bytes], w: int, h: int, cx: float, cy: float, rx: float, ry: float) -> RGB:
    samples: List[Tuple[int, int, int]] = []
    for y in range(max(0, int(cy - ry)), min(h, int(cy + ry) + 1)):
        for x in range(max(0, int(cx - rx)), min(w, int(cx + rx) + 1)):
            o = x * 3
            r, g, b = rows[y][o], rows[y][o + 1], rows[y][o + 2]
            if r < 245 or g < 245 or b < 245:  # ignore the white studio background
                samples.append((r, g, b))
    if not samples:
        return (90, 90, 90)
    return tuple(int(sorted(s[i] for s in samples)[len(samples) // 2]) for i in range(3))  # type: ignore[return-value]


def analyse(image: Path) -> Dict[str, object]:
    w, h, rows = read_png_rgb(image)
    x0, y0, x1, y1 = w, h, 0, 0
    for y, row in enumerate(rows):
        for x in range(w):
            o = x * 3
            if row[o] < 245 or row[o + 1] < 245 or row[o + 2] < 245:
                x0 = min(x0, x); x1 = max(x1, x)
                y0 = min(y0, y); y1 = max(y1, y)
    if x1 <= x0 or y1 <= y0:
        raise ValueError("concept image has no visible character")
    cx = (x0 + x1) * 0.5
    height = float(y1 - y0)

    def sample(nx: float, ny: float, radius: float = 0.02) -> RGB:
        return patch_colour(rows, w, h, cx + nx * height, y0 + ny * height,
                            radius * height, radius * height)

    colours = {
        "skin": sample(0.0, 0.07, 0.018),
        "hair": sample(0.0, 0.018, 0.018),
        "vest": sample(0.0, 0.27, 0.025),
        "shirt": sample(-0.155, 0.27, 0.022),
        "pants": sample(0.0, 0.62, 0.03),
        "boots": sample(-0.105, 0.93, 0.018),
        "gloves": sample(-0.235, 0.45, 0.02),
    }
    return {"size": (w, h), "bbox": (x0, y0, x1, y1), "colours": colours}


# --------------------------------------------------------------------- mesh builder

def variant_colours(base: Dict[str, RGB], variant: dict) -> Dict[str, RGB]:
    colours = dict(base)
    for key in ("vest", "shirt", "pants", "boots", "gloves", "skin", "hair"):
        if variant.get(key) is not None:
            colours[key] = tuple(variant[key])  # type: ignore[assignment]
    return colours


class Builder:
    def __init__(self) -> None:
        self.vertices: List[float] = []
        self.indices: List[int] = []

    def add(self, p: Vec, n: Vec, uv: Tuple[float, float], colour: RGB, weights: Sequence[Weight]) -> int:
        length = math.sqrt(sum(v * v for v in n)) or 1.0
        nr = [v / length for v in n]
        linear = [oeac.srgb_to_linear(c / 255.0) for c in colour]
        w = list(weights)[:4]
        total = sum(weight for _, weight in w) or 1.0
        vertex = [
            p[0], p[1], p[2], nr[0], nr[1], nr[2], uv[0], uv[1],
            linear[0], linear[1], linear[2], 1.0,
        ]
        for k in range(4):
            bone, weight = w[k] if k < len(w) else (0, 0.0)
            vertex.extend([float(bone), weight / total])
        i = len(self.vertices) // 20
        self.vertices.extend(vertex)
        return i

    def tri(self, a: int, b: int, c: int) -> None:
        self.indices.extend([a, b, c])

    def quad(self, a: int, b: int, c: int, d: int) -> None:
        self.tri(a, b, c); self.tri(a, c, d)


def smoothstep(a: float, b: float, x: float) -> float:
    t = max(0.0, min(1.0, (x - a) / (b - a)))
    return t * t * (3.0 - 2.0 * t)


def ellipsoid(b: Builder, c: Vec, r: Vec, bone: int, colour: RGB, segs: int = 16, rings: int = 10) -> None:
    base = len(b.vertices) // 20
    for j in range(rings + 1):
        phi = math.pi * j / rings
        sp, cp = math.sin(phi), math.cos(phi)
        for k in range(segs + 1):
            theta = 2 * math.pi * k / segs
            st, ct = math.sin(theta), math.cos(theta)
            nx, ny, nz = ct / r[0], cp / r[1], st / r[2]
            p = (c[0] + r[0] * sp * ct, c[1] + r[1] * cp, c[2] + r[2] * sp * st)
            b.add(p, (nx, ny, nz), (k / segs, j / rings), colour, [(bone, 1.0)])
    for j in range(rings):
        for k in range(segs):
            a = base + j * (segs + 1) + k
            b.quad(a, a + 1, a + segs + 2, a + segs + 1)


def tube(b: Builder, a: Vec, c: Vec, ra: float, rc: float, bone_a: int, bone_c: int,
         colour: RGB, segs: int = 12, rings: int = 5, bulge: float = 0.0) -> None:
    dx, dy, dz = c[0] - a[0], c[1] - a[1], c[2] - a[2]
    length = math.sqrt(dx * dx + dy * dy + dz * dz)
    if length < 1e-5:
        return
    ux, uy, uz = dx / length, dy / length, dz / length
    rx, ry, rz = uz, 0.0, -ux
    rl = math.sqrt(rx * rx + rz * rz)
    if rl < 1e-4:
        rx, rz = 1.0, 0.0
    else:
        rx, rz = rx / rl, rz / rl
    fx, fy, fz = uy * rz - uz * ry, uz * rx - ux * rz, ux * ry - uy * rx
    base = len(b.vertices) // 20
    for j in range(rings + 1):
        t = j / rings
        px, py, pz = a[0] + dx * t, a[1] + dy * t, a[2] + dz * t
        radius = (ra + (rc - ra) * t) * (1.0 + bulge * smoothstep(0.55, 0.9, t) * (1.0 - smoothstep(0.92, 1.0, t)))
        blend = smoothstep(0.65, 1.0, t)
        for k in range(segs + 1):
            theta = 2 * math.pi * k / segs
            st, ct = math.sin(theta), math.cos(theta)
            ox = rx * radius * ct + fx * radius * st
            oy = ry * radius * ct + fy * radius * st
            oz = rz * radius * ct + fz * radius * st
            b.add((px + ox, py + oy, pz + oz), (ox, oy, oz), (k / segs, t), colour,
                  [(bone_a, 1.0 - blend), (bone_c, blend)])
    for j in range(rings):
        for k in range(segs):
            q = base + j * (segs + 1) + k
            b.quad(q, q + 1, q + segs + 2, q + segs + 1)


def stack(b: Builder, rings: Sequence[Tuple[float, float, float, Sequence[Weight]]],
          colour: RGB, segs: int = 14) -> None:
    base = len(b.vertices) // 20
    for y, rx, rz, weights in rings:
        for k in range(segs + 1):
            theta = 2 * math.pi * k / segs
            ct, st = math.cos(theta), math.sin(theta)
            b.add((rx * ct, y, rz * st), (ct, 0.0, st), (k / segs, y / 1.8), colour, weights)
    for j in range(len(rings) - 1):
        for k in range(segs):
            q = base + j * (segs + 1) + k
            b.quad(q, q + segs + 1, q + segs + 2, q + 1)


def box(b: Builder, centre: Vec, half: Vec, bone: int, colour: RGB) -> None:
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
            ids.append(b.add(p, normal, ((sx + 1) / 2, (sy + 1) / 2), colour, [(bone, 1.0)]))
        # Corner order above is clockwise seen from outside; reverse for GL back-face culling.
        b.quad(ids[3], ids[2], ids[1], ids[0])


# --------------------------------------------------------------------- textures

def make_texture(path: Path, base: RGB, palette: Sequence[RGB] | None, seed: int, camo: bool) -> str:
    size = 256
    rng = random.Random(seed)
    pixels = bytearray(size * size * 3)
    shades = palette if camo and palette else [base]
    for y in range(size):
        for x in range(size):
            if camo and shades:
                field = (math.sin(x * 0.045 + seed) + math.sin(y * 0.052 - seed) +
                         math.sin((x + y) * 0.027 + seed * 0.5))
                index = int((field + 3.0) / 6.0 * len(shades))
                colour = shades[max(0, min(len(shades) - 1, index))]
                grain = rng.randint(-7, 7)
            else:
                colour = base
                grain = rng.randint(-5, 5)
            o = (y * size + x) * 3
            for i in range(3):
                pixels[o + i] = max(0, min(255, colour[i] + grain))
    write_png(path, size, size, bytes(pixels))
    return path.name


def argb(c: RGB) -> int:
    return (255 << 24) | (c[0] << 16) | (c[1] << 8) | c[2]


# --------------------------------------------------------------------- geometry

def build_character(colours: Dict[str, RGB], tex_dir: Path, save_textures: bool,
                    gear: Sequence[str] = ()) -> List[dict]:
    skin, hair = colours["skin"], colours["hair"]
    vest, shirt = colours["vest"], colours["shirt"]
    pants, boots, gloves = colours["pants"], colours["boots"], colours["gloves"]
    dark = tuple(max(0, c - 28) for c in vest)  # type: ignore[assignment]

    tex: Dict[str, str] = {}
    def texture(name: str, base: RGB, palette: Sequence[RGB] | None, seed: int, camo: bool) -> str:
        if not save_textures:
            return ""
        key = f"tex_{name}.png"
        if key not in tex:
            tex[key] = make_texture(tex_dir / key, base, palette, seed, camo)
        return tex[key]

    tex_skin = texture("skin", skin, None, 1, False)
    tex_fabric = texture("fabric", shirt, None, 2, False)
    camo_palette = [vest, shirt, dark, tuple(max(0, c - 18) for c in shirt)]  # type: ignore[list-item]
    tex_vest = texture("vest", vest, camo_palette, 3, True)
    tex_pants = texture("pants", pants, [pants, dark, vest], 4, True)
    tex_boots = texture("boots", boots, None, 5, False)

    parts: List[dict] = []

    def part(name: str, b: Builder, colour: RGB, textured: str, flags: int = 0) -> None:
        parts.append({"name": name, "texture": textured, "color": argb((255, 255, 255)) if textured else argb(colour),
                      "flags": flags, "vertices": b.vertices, "indices": b.indices})

    # Body: pelvis/neck/head/hands. The head is a proper ellipsoid with ears,
    # nose and jaw rather than the old single sphere.
    body = Builder()
    stack(body, [(0.64, 0.15, 0.115, [(B["PELVIS"], 1.0)]),
                 (0.78, 0.16, 0.125, [(B["PELVIS"], 0.7), (B["SPINE"], 0.3)]),
                 (0.90, 0.15, 0.12, [(B["SPINE"], 1.0)])], skin, segs=14)
    stack(body, [(1.42, 0.082, 0.082, [(B["NECK"], 1.0)]),
                 (1.52, 0.07, 0.07, [(B["HEAD"], 1.0)])], skin, segs=12)
    ellipsoid(body, (0, 1.62, 0), (0.112, 0.125, 0.112), B["HEAD"], skin, 18, 12)
    ellipsoid(body, (0, 1.535, 0.035), (0.082, 0.062, 0.088), B["HEAD"], skin, 14, 8)
    tube(body, (0, 1.60, 0.09), (0, 1.55, 0.15), 0.022, 0.014, B["HEAD"], B["HEAD"], skin, 8, 3)
    for side in (-1, 1):
        ellipsoid(body, (side * 0.108, 1.60, -0.005), (0.025, 0.042, 0.020), B["HEAD"], skin, 8, 6)
        ellipsoid(body, (side * 0.62, 0.98, 0.01), (0.05, 0.05, 0.032), B["HAND_L" if side > 0 else "HAND_R"], skin, 10, 6)
    part("body", body, skin, tex_skin, oeac.FLAG_SKIN)

    # Minimal face: eyes and mouth on the front of the head.
    face_b = Builder()
    for side in (-1, 1):
        ellipsoid(face_b, (side * 0.042, 1.645, 0.098), (0.014, 0.009, 0.006), B["HEAD"], (30, 26, 24), 8, 4)
    ellipsoid(face_b, (0, 1.565, 0.105), (0.025, 0.006, 0.006), B["HEAD"], (110, 62, 52), 10, 4)
    part("face", face_b, skin, "", oeac.FLAG_DETAIL)

    # Hair: a slightly oversized cap plus a short fringe.
    hair_b = Builder()
    ellipsoid(hair_b, (0, 1.70, -0.01), (0.116, 0.06, 0.116), B["HEAD"], hair, 18, 8)
    ellipsoid(hair_b, (0, 1.665, 0.085), (0.10, 0.028, 0.05), B["HEAD"], hair, 14, 6)
    part("hair", hair_b, hair, "", oeac.FLAG_DETAIL)

    # Shirt torso and sleeves.
    shirt_b = Builder()
    stack(shirt_b, [(0.88, 0.158, 0.122, [(B["PELVIS"], 0.5), (B["SPINE"], 0.5)]),
                    (1.02, 0.165, 0.128, [(B["SPINE"], 1.0)]),
                    (1.18, 0.178, 0.132, [(B["SPINE"], 0.5), (B["CHEST"], 0.5)]),
                    (1.36, 0.205, 0.135, [(B["CHEST"], 1.0)]),
                    (1.43, 0.17, 0.115, [(B["CHEST"], 0.5), (B["NECK"], 0.5)])], shirt, segs=16)
    for side in (-1, 1):
        sho, arm = ("SHOULDER_L", "ARM_L") if side > 0 else ("SHOULDER_R", "ARM_R")
        tube(shirt_b, (side * 0.18, 1.40, 0), (side * 0.38, 1.20, 0), 0.082, 0.066, B[sho], B[arm], shirt, 12, 5, 0.09)
    part("shirt", shirt_b, shirt, tex_fabric)

    # Armoured vest: shell, shoulder straps, magazine pouches and belt.
    vest_b = Builder()
    stack(vest_b, [(1.00, 0.172, 0.138, [(B["SPINE"], 1.0)]),
                   (1.16, 0.188, 0.145, [(B["SPINE"], 0.4), (B["CHEST"], 0.6)]),
                   (1.36, 0.215, 0.148, [(B["CHEST"], 1.0)])], vest, segs=16)
    for x in (-0.11, 0.0, 0.11):
        box(vest_b, (x, 1.21, 0.147), (0.045, 0.075, 0.025), B["CHEST"], dark)  # type: ignore[arg-type]
    box(vest_b, (0, 0.925, 0.10), (0.155, 0.035, 0.05), B["PELVIS"], dark)  # type: ignore[arg-type]
    for side in (-1, 1):
        box(vest_b, (side * 0.18, 1.36, 0.0), (0.07, 0.035, 0.12), B["SHOULDER_L" if side > 0 else "SHOULDER_R"], vest)
    part("vest", vest_b, vest, tex_vest, oeac.FLAG_DETAIL)

    # Bare forearms and gloves.
    arms = Builder()
    for side in (-1, 1):
        arm, fore, hand = ("ARM_L", "FORE_L", "HAND_L") if side > 0 else ("ARM_R", "FORE_R", "HAND_R")
        tube(arms, (side * 0.38, 1.20, 0), (side * 0.56, 1.02, 0), 0.058, 0.045, B[arm], B[fore], skin, 12, 5, 0.1)
        ellipsoid(arms, (side * 0.62, 0.98, 0.01), (0.055, 0.055, 0.032), B[hand], gloves, 10, 6)
    part("arms", arms, skin, tex_skin, oeac.FLAG_SKIN)

    # Cargo pants with a full pelvis shell and knee pads.
    pants_b = Builder()
    stack(pants_b, [(0.64, 0.152, 0.118, [(B["PELVIS"], 1.0)]),
                    (0.78, 0.163, 0.128, [(B["PELVIS"], 0.7), (B["SPINE"], 0.3)]),
                    (0.90, 0.153, 0.122, [(B["SPINE"], 1.0)])], pants, segs=14)
    for side in (-1, 1):
        thigh, shin, foot = ("THIGH_L", "SHIN_L", "FOOT_L") if side > 0 else ("THIGH_R", "SHIN_R", "FOOT_R")
        tube(pants_b, (side * 0.12, 0.88, 0), (side * 0.15, 0.44, 0), 0.105, 0.082, B[thigh], B[shin], pants, 14, 6, 0.12)
        tube(pants_b, (side * 0.15, 0.44, 0), (side * 0.17, 0.02, 0), 0.088, 0.062, B[shin], B[foot], pants, 14, 6, 0.1)
        box(pants_b, (side * 0.15, 0.46, 0.075), (0.075, 0.075, 0.022), B[shin], dark)  # type: ignore[arg-type]
    part("pants", pants_b, pants, tex_pants)

    # Combat boots.
    boots_b = Builder()
    for side in (-1, 1):
        foot = "FOOT_L" if side > 0 else "FOOT_R"
        box(boots_b, (side * 0.17, 0.085, 0.035), (0.075, 0.085, 0.14), B[foot], boots)
        ellipsoid(boots_b, (side * 0.17, 0.16, -0.01), (0.075, 0.045, 0.085), B[foot], boots, 12, 6)
    part("boots", boots_b, boots, tex_boots)

    # Variant kit: caps, helmets, hoods, face masks and backpacks are geometry,
    # not recolours, so bots are identifiable from behind as well as in front.
    gear_b = Builder()
    if "cap" in gear:
        ellipsoid(gear_b, (0, 1.735, -0.005), (0.125, 0.055, 0.125), B["HEAD"], vest, 16, 6)
        box(gear_b, (0, 1.705, 0.115), (0.115, 0.014, 0.055), B["HEAD"], dark)  # type: ignore[arg-type]
    if "helmet" in gear:
        ellipsoid(gear_b, (0, 1.72, -0.01), (0.135, 0.09, 0.135), B["HEAD"], vest, 16, 8)
        for side in (-1, 1):
            box(gear_b, (side * 0.12, 1.68, 0.0), (0.025, 0.055, 0.09), B["HEAD"], dark)  # type: ignore[arg-type]
    if "hood" in gear:
        ellipsoid(gear_b, (0, 1.65, -0.055), (0.132, 0.14, 0.10), B["HEAD"], shirt, 16, 8)
        ellipsoid(gear_b, (0, 1.735, -0.01), (0.128, 0.055, 0.128), B["HEAD"], shirt, 16, 6)
    if "mask" in gear:
        ellipsoid(gear_b, (0, 1.565, 0.088), (0.082, 0.05, 0.038), B["HEAD"], dark, 12, 6)  # type: ignore[arg-type]
    if "backpack" in gear:
        box(gear_b, (0, 1.16, -0.16), (0.14, 0.20, 0.075), B["SPINE"], vest)
        box(gear_b, (0, 1.00, -0.175), (0.12, 0.055, 0.04), B["SPINE"], dark)  # type: ignore[arg-type]
    if gear_b.vertices:
        part("gear", gear_b, vest, "", oeac.FLAG_DETAIL)

    return parts


def bind_matrices() -> List[List[float]]:
    names = oeac.BONE_NAMES
    if len(OFFSETS) != len(names):
        raise ValueError(f"expected {len(names)} joint offsets, got {len(OFFSETS)}")
    world: List[List[float]] = []
    for i, (x, y, z) in enumerate(OFFSETS):
        local = oeac.mat_identity()
        local[12], local[13], local[14] = x, y, z
        if i == 0:
            world.append(local)
        else:
            parent = oeac.BONE_PARENTS[i]
            world.append(oeac.mat_mul(world[parent], local))
    return [oeac.mat_invert(m) for m in world]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--image", type=Path, default=Path("tools/tripo/concepts/send-off-soldier-concept.png"))
    parser.add_argument("--assets", type=Path, default=Path("oea-engine/src/main/assets"))
    parser.add_argument("--variant", choices=sorted(VARIANTS), default="hero")
    parser.add_argument("--all", action="store_true", help="generate every identified variant and a manifest")
    parser.add_argument("--no-textures", action="store_true")
    args = parser.parse_args()

    try:
        base = analyse(args.image)["colours"]
        IDs = sorted(VARIANTS) if args.all else [args.variant]
        manifest = {"characters": []}
        for variant_id in IDs:
            cfg = VARIANTS[variant_id]
            colours = variant_colours(base, cfg)
            out = args.assets / "characters" / f"{variant_id}.oeac"
            tex_dir = args.assets / "characters" / variant_id
            parts = build_character(colours, tex_dir, not args.no_textures, cfg["gear"])
            oeac.write_oeac(out, bind_matrices(), parts, [])
            manifest["characters"].append({
                "id": variant_id, "name": cfg["name"], "role": cfg["role"],
                "asset": f"characters/{variant_id}.oeac",
                "textures": f"characters/{variant_id}",
                "color": argb(colours["vest"]),
            })
            print(f"{variant_id}: {cfg['name']} — {cfg['role']}")
        if args.all:
            manifest_path = args.assets / "characters" / "manifest.json"
            manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
            print(f"wrote {manifest_path}")
    except (ValueError, OSError, KeyError, struct.error) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
