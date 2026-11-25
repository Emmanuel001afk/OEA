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

# OEA bind-pose joint offsets. `soldier.BIND` is the source of truth: real
# human segment lengths on the engine's 19-bone hierarchy, with arms hanging
# 8 degrees out. The inverse binds below are its exact inverses, so the
# engine's Skeleton — which derives every joint offset from these matrices —
# animates the mesh around the joints it was built around.
def _offsets() -> List[Vec]:
    import soldier
    return [offset for (_parent, offset) in soldier.BIND]


OFFSETS: List[Vec] = _offsets()
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
                    gear: Sequence[str] = (), fem: bool = False) -> List[dict]:
    """Builds one character's meshes on the anatomy rig and packs them.

    The shapes live in `soldier.py`; this function only decides which palette
    and which kit goes on them, in the same way a bolt-on of the old generator
    did — except the old one assembled cylinders and spheres and this one lofts
    anatomy over real joint positions.
    """
    import humanoid
    import soldier

    skin, hair = colours["skin"], colours["hair"]
    vest, shirt = colours["vest"], colours["shirt"]
    pants, boots, gloves = colours["pants"], colours["boots"], colours["gloves"]
    dark = tuple(max(0, c - 26) for c in vest)  # type: ignore[assignment]

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
    camo_palette = [vest, shirt, dark, tuple(max(0, c - 16) for c in shirt)]  # type: ignore[list-item]
    tex_vest = texture("vest", vest, camo_palette, 3, True)
    tex_pants = texture("pants", pants, [pants, dark, vest], 4, True)
    tex_boots = texture("boots", boots, None, 5, False)

    palette = soldier.Palette(
        skin=skin, hair=hair, vest=vest, shirt=shirt, pants=pants,
        boots=boots, gloves=gloves,
    )
    eye = tuple(max(0, min(255, int(c * 0.62 + 40))) for c in hair)  # type: ignore[assignment]
    meshes = soldier.build(palette, gear=gear, fem=fem, eye=eye)  # type: ignore[arg-type]

    # Flags: 1 = skin (subtle SSS in the shader), 2 = detail (hidden at LOD 2),
    # 4 = double sided. Faces and hair are detail geometry; the body is not.
    textures = {
        "body": tex_skin, "face": "", "hair": "", "shirt": tex_fabric,
        "pants": tex_pants, "boots": tex_boots, "gloves": "",
        "vest": tex_vest, "gear": "",
    }
    flags = {
        "body": oeac.FLAG_SKIN, "face": oeac.FLAG_DETAIL, "hair": oeac.FLAG_DETAIL,
        "shirt": 0, "pants": 0, "boots": 0, "gloves": 0,
        "vest": oeac.FLAG_DETAIL, "gear": oeac.FLAG_DETAIL,
    }
    parts: List[dict] = []
    for name, mesh in meshes.items():
        if not mesh.idx:
            continue
        parts.append(humanoid.to_oeac_part(
            name, mesh, textures.get(name, ""), flags.get(name, 0),
        ))
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


def _imported_ids(assets: Path) -> set:
    """Character ids in the manifest whose asset is not the procedural body."""
    manifest_path = assets / "characters" / "manifest.json"
    if not manifest_path.exists():
        return set()
    try:
        previous = json.loads(manifest_path.read_text())
    except (ValueError, OSError):
        return set()
    imported = set()
    for entry in previous.get("characters", []):
        asset = str(entry.get("asset", ""))
        if asset and not asset == f"characters/{entry.get('id')}.oeac":
            imported.add(entry.get("id"))
    return imported


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
        if args.all:
            # `hero` is no longer procedural: it is the imported, auto-rigged
            # soldier from tools/tripo/autorig.py, and generating over it would
            # silently replace the modelled character with the primitive body.
            states = _imported_ids(args.assets)
            IDs = [v for v in IDs if v not in states]
            if states:
                print("skipping " + ", ".join(sorted(states)) + " — imported, not procedural")
        manifest = {"characters": []}
        for variant_id in IDs:
            cfg = VARIANTS[variant_id]
            colours = variant_colours(base, cfg)
            out = args.assets / "characters" / f"{variant_id}.oeac"
            tex_dir = args.assets / "characters" / variant_id
            parts = build_character(colours, tex_dir, not args.no_textures, cfg["gear"],
                                    bool(cfg.get("fem", False)))
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
            # Keep any harness that is not procedural. `hero` can be replaced by
            # an imported, auto-rigged character (tools/tripo/autorig.py), and
            # regenerating the manifest must not quietly drop it back to the
            # procedural body — nor leave the procedural .oeac orphaned on disk.
            if manifest_path.exists():
                try:
                    previous = json.loads(manifest_path.read_text())
                except (ValueError, OSError):
                    previous = {"characters": []}
                generated = {entry["id"] for entry in manifest["characters"]}
                for entry in previous.get("characters", []):
                    if entry.get("id") not in generated:
                        manifest["characters"].append(entry)
                        print(f"kept {entry['id']} ({entry.get('role', '')}) — not procedural")
                order = {name: i for i, name in enumerate(
                    ["bot_heavy", "bot_medic", "bot_night", "bot_ranger", "bot_scout", "hero"])}
                manifest["characters"].sort(key=lambda e: order.get(e["id"], 99))
            manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
            print(f"wrote {manifest_path}")
    except (ValueError, OSError, KeyError, struct.error) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
