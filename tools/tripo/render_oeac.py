#!/usr/bin/env python3
"""Software renderer for OEA `.oeac` characters — the eyeball for the art pass.

No GPU, no numpy, no Pillow: pure Python. It reads the same `.oeac` the phone
loads, so what you see here is the shipped mesh, not a mock-up.

What it does that the old preview did not:

* real 3D — orthographic camera at any yaw/pitch, so you can look at the side
  and the back of a character instead of only its front;
* a depth buffer, so the near arm stops being painted over by the far one;
* smooth Lambert + hemisphere + rim lighting, so form reads at all (the old
  preview filled every triangle with one flat colour, which is why a decent
  mesh still looked like a cardboard cut-out);
* 2x supersampling;
* an optional T-pose, produced by re-binding the mesh to a straight-arm
  skeleton — this is the pose image-to-3D riggers want.

Usage:
  python3 tools/tripo/render_oeac.py hero --views front,side,back,three-quarter
  python3 tools/tripo/render_oeac.py hero --out /tmp/hero.png --yaw 35 --tpose
"""

from __future__ import annotations

import argparse
import math
import struct
import sys
from pathlib import Path
from typing import Dict, List, Sequence, Tuple

sys.path.insert(0, str(Path(__file__).resolve().parent))
from concept_to_oeac import write_png  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "oea-engine/src/main/assets/characters"

Vec3 = Tuple[float, float, float]
RGB = Tuple[int, int, int]


# --------------------------------------------------------------------- reading

def read_oeac(path: Path) -> Tuple[List[dict], List[List[float]]]:
    """Returns (parts, inverse_bind) exactly as CharacterAssetLoader sees them."""
    data = path.read_bytes()
    if data[:4] != b"OEAC":
        raise ValueError(f"{path} is not an .oeac file")
    version, bones, meshes, clips = struct.unpack_from("<4H", data, 4)
    if version != 1:
        raise ValueError(f"unsupported .oeac version {version}")
    o = 12
    inverse_bind: List[List[float]] = []
    for _ in range(bones):
        o += 2  # parent
        inverse_bind.append(list(struct.unpack_from("<16f", data, o)))
        o += 64

    def string() -> str:
        nonlocal o
        n = data[o]
        o += 1
        value = data[o:o + n].decode()
        o += n
        return value

    parts: List[dict] = []
    for _ in range(meshes):
        name, tex = string(), string()
        colour, flags = struct.unpack_from("<2I", data, o)
        o += 8
        vc, ic = struct.unpack_from("<2I", data, o)
        o += 8
        verts = list(struct.unpack_from("<%df" % (vc * 20), data, o))
        o += vc * 80
        idx = list(struct.unpack_from("<%dI" % ic, data, o))
        o += ic * 4
        parts.append({"name": name, "tex": tex, "color": colour,
                      "flags": flags, "verts": verts, "idx": idx})
    return parts, inverse_bind


def texture_average(folder: Path, name: str, fallback: int) -> RGB:
    """Mean colour of a part texture, in sRGB bytes (used as a flat albedo)."""
    if not name:
        return ((fallback >> 16) & 255, (fallback >> 8) & 255, fallback & 255)
    path = folder / name
    if not path.exists():
        return ((fallback >> 16) & 255, (fallback >> 8) & 255, fallback & 255)
    from concept_to_oeac import read_png_rgb
    w, h, rows = read_png_rgb(path)
    step = max(1, (w * h) // 4096)
    total = [0, 0, 0]
    count = 0
    for i in range(0, w * h, step):
        x, y = i % w, i // w
        for c in range(3):
            total[c] += rows[y][x * 3 + c]
        count += 1
    return tuple(v // max(1, count) for v in total)  # type: ignore[return-value]


# --------------------------------------------------------------------- matrices

def _mat_identity() -> List[float]:
    m = [0.0] * 16
    m[0] = m[5] = m[10] = m[15] = 1.0
    return m


def _mat_mul(a: Sequence[float], b: Sequence[float]) -> List[float]:
    out = [0.0] * 16
    for c in range(4):
        for r in range(4):
            out[c * 4 + r] = sum(a[k * 4 + r] * b[c * 4 + k] for k in range(4))
    return out


def _mat_invert(m: Sequence[float]) -> List[float]:
    rows = [[m[c * 4 + r] for c in range(4)] + [1.0 if r == k else 0.0 for k in range(4)]
            for r in range(4)]
    for col in range(4):
        pivot = max(range(col, 4), key=lambda r: abs(rows[r][col]))
        if abs(rows[pivot][col]) < 1e-12:
            raise ValueError("singular matrix")
        rows[col], rows[pivot] = rows[pivot], rows[col]
        inv = 1.0 / rows[col][col]
        rows[col] = [v * inv for v in rows[col]]
        for r in range(4):
            if r != col and rows[r][col] != 0.0:
                f = rows[r][col]
                rows[r] = [rows[r][k] - f * rows[col][k] for k in range(8)]
    out = [0.0] * 16
    for c in range(4):
        for r in range(4):
            out[c * 4 + r] = rows[r][4 + c]
    return out


def _apply(m: Sequence[float], p: Vec3) -> Vec3:
    x, y, z = p
    return (m[0] * x + m[4] * y + m[8] * z + m[12],
            m[1] * x + m[5] * y + m[9] * z + m[13],
            m[2] * x + m[6] * y + m[10] * z + m[14])


def _rotation_about(pivot: Vec3, axis: str, angle: float) -> List[float]:
    """4x4 rotation about an axis through `pivot` (used to build the T-pose)."""
    c, s = math.cos(angle), math.sin(angle)
    m = _mat_identity()
    if axis == "z":
        m[0], m[1], m[4], m[5] = c, s, -s, c
    elif axis == "x":
        m[5], m[6], m[9], m[10] = c, s, -s, c
    else:
        m[0], m[2], m[8], m[10] = c, -s, s, c
    t = _mat_identity()
    t[12], t[13], t[14] = -pivot[0], -pivot[1], -pivot[2]
    ti = _mat_identity()
    ti[12], ti[13], ti[14] = pivot
    return _mat_mul(ti, _mat_mul(m, t))


# --------------------------------------------------------------------- posing
#
# A straight port of Skeleton.update(). The point is not to reimplement the
# animator — it is to be able to see, offline, that a mesh built around the
# joints actually bends at them. The bones are the engine's: every bone points
# along its own +Y, limbs hang along -Y, and a rotation about local X swings a
# limb forward or back.

PARENTS = [-1, 0, 1, 2, 3, 2, 5, 6, 7, 2, 9, 10, 11, 0, 13, 14, 0, 16, 17]
BONE = {"PELVIS": 0, "SPINE": 1, "CHEST": 2, "NECK": 3, "HEAD": 4,
        "SHOULDER_L": 5, "ARM_L": 6, "FORE_L": 7, "HAND_L": 8,
        "SHOULDER_R": 9, "ARM_R": 10, "FORE_R": 11, "HAND_R": 12,
        "THIGH_L": 13, "SHIN_L": 14, "FOOT_L": 15,
        "THIGH_R": 16, "SHIN_R": 17, "FOOT_R": 18}


def _rot_x(a: float) -> List[float]:
    c, s = math.cos(a), math.sin(a)
    m = _mat_identity()
    m[5], m[6], m[9], m[10] = c, s, -s, c
    return m


def _rot_y(a: float) -> List[float]:
    c, s = math.cos(a), math.sin(a)
    m = _mat_identity()
    m[0], m[2], m[8], m[10] = c, -s, s, c
    return m


def _rot_z(a: float) -> List[float]:
    c, s = math.cos(a), math.sin(a)
    m = _mat_identity()
    m[0], m[1], m[4], m[5] = c, s, -s, c
    return m


DEFAULT_POSE = dict(phase=0.0, speed=0.0, airborne=False, crouch=False, lean=0.0,
                    aimHold=False, aimPitch=0.0, headYaw=0.0, hipOffsetY=0.0,
                    reload=0.0, shoulder_z=0.0)


def bone_matrices(inverse_bind: List[List[float]], pose: dict) -> List[List[float]]:
    """Skin matrices (world × inverse bind) for one pose, as Skeleton.update builds them."""
    p = dict(DEFAULT_POSE)
    p.update(pose)
    n = len(inverse_bind)
    bind_world = [_mat_invert(m) for m in inverse_bind]
    # local offsets, derived from the bind pose exactly like Skeleton does
    offsets: List[Tuple[float, float, float]] = []
    for i in range(n):
        if i == 0:
            offsets.append((bind_world[0][12], bind_world[0][13], bind_world[0][14]))
        else:
            rel = _mat_mul(_mat_invert(bind_world[PARENTS[i]]), bind_world[i])
            offsets.append((rel[12], rel[13], rel[14]))

    def set_local(i: int, rx: float, ry: float, rz: float) -> List[float]:
        rot = _mat_mul(_rot_z(rz), _mat_mul(_rot_y(ry), _rot_x(rx)))
        loc = _mat_identity()
        loc[12], loc[13], loc[14] = offsets[i]
        return _mat_mul(loc, rot)

    world: List[List[float]] = [None] * n  # type: ignore[list-item]
    root = _mat_identity()
    root[12], root[13], root[14] = offsets[0][0], offsets[0][1] + p["hipOffsetY"], offsets[0][2]
    world[0] = root

    s01 = max(0.0, min(1.0, p["speed"]))
    ph = p["phase"]
    lean_pitch = 0.10 * s01 + (0.18 if p["airborne"] else 0.0) + (0.30 if p["crouch"] else 0.0)
    roll = p["lean"] * 0.35 + (0.0 if p["airborne"] else s01 * 0.07 * math.sin(ph))
    locals_: List[List[float]] = [None] * n  # type: ignore[list-item]
    locals_[1] = set_local(1, lean_pitch, 0.0, -roll)
    locals_[2] = set_local(2, 0.0, 0.0, math.sin(ph * 2.2) * 0.012)
    locals_[3] = set_local(3, -lean_pitch * 0.3, 0.0, 0.0)
    locals_[4] = set_local(4, p["aimPitch"] * 0.3, p["headYaw"], 0.0)

    for side in (-1, 1):
        sho, arm, fore, hand = (9, 10, 11, 12) if side < 0 else (5, 6, 7, 8)
        if p["aimHold"]:
            ua, el = math.pi / 2 + p["aimPitch"], 0.18
        elif p["airborne"]:
            ua, el = math.pi * (0.42 + side * 0.12), 0.35
        elif s01 > 0.02:
            ua = s01 * 0.75 * math.sin(ph - side * math.pi)
            el = 0.55 + s01 * 0.35 * max(0.0, math.sin(ph - side * math.pi + 1.2))
        else:
            ua, el = math.sin(ph * 0.24 + side) * 0.06, 0.22
        if p["reload"] > 0.0:
            ua = ua + (0.45 - ua) * p["reload"]
            el = el + (0.85 - el) * p["reload"]
        locals_[sho] = set_local(sho, 0.0, 0.0, p["shoulder_z"] * (1 if side > 0 else -1))
        locals_[arm] = set_local(arm, -ua, 0.0, 0.0)
        locals_[fore] = set_local(fore, el, 0.0, 0.0)
        locals_[hand] = set_local(hand, 0.0, 0.0, 0.0)

    for side in (-1, 1):
        thigh, shin, foot = (16, 17, 18) if side < 0 else (13, 14, 15)
        if p["airborne"]:
            thigh_a = 0.75 if side < 0 else -0.45
            knee_b = 0.35 if side < 0 else 1.15
        elif p["crouch"]:
            thigh_a, knee_b = 0.95, 1.05
        elif s01 > 0.02:
            lp = ph + side * math.pi
            thigh_a = s01 * 0.85 * math.sin(lp)
            knee_b = s01 * 1.05 * max(0.0, math.cos(lp - 1.1))
        else:
            thigh_a, knee_b = s01 * 0.85 * math.sin(0.2 * ph), 0.0
        locals_[thigh] = set_local(thigh, -thigh_a, 0.0, 0.0)
        locals_[shin] = set_local(shin, knee_b, 0.0, 0.0)
        locals_[foot] = set_local(foot, 0.0, 0.0, 0.0)

    for i in range(1, n):
        world[i] = _mat_mul(world[PARENTS[i]], locals_[i])
    return [_mat_mul(world[i], inverse_bind[i]) for i in range(n)]


def skinned_positions(part: dict, matrices: List[List[float]] | None) -> List[Vec3]:
    """Vertex positions after skinning, or the authored bind pose when None."""
    verts = part.get("verts") or part.get("vertices") or []
    if matrices is None:
        return [(verts[i * 20], verts[i * 20 + 1], verts[i * 20 + 2])
                for i in range(len(verts) // 20)]
    out: List[Vec3] = []
    for i in range(len(verts) // 20):
        o = i * 20
        p = (verts[o], verts[o + 1], verts[o + 2])
        acc = [0.0, 0.0, 0.0]
        for k in range(4):
            bone = int(verts[o + 12 + k])
            w = verts[o + 16 + k]
            if w <= 0.0:
                continue
            q = _apply(matrices[bone], p)
            acc[0] += q[0] * w
            acc[1] += q[1] * w
            acc[2] += q[2] * w
        out.append((acc[0], acc[1], acc[2]))
    return out


POSE_PRESETS = {
    "bind": {},
    "tpose": {"shoulder_z": math.pi / 2},
    "aim": {"aimHold": True, "aimPitch": -0.08},
    "run": {"speed": 1.0, "phase": 2.1, "aimHold": True, "aimPitch": -0.1},
    "crouch": {"crouch": True, "aimHold": True, "aimPitch": -0.2, "hipOffsetY": -0.22},
    "reload": {"reload": 0.8, "aimHold": False, "aimPitch": -0.05},
}


# --------------------------------------------------------------------- shading

def _srgb_to_linear(c: float) -> float:
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def _linear_to_srgb(c: float) -> float:
    c = max(0.0, min(1.0, c))
    return c * 12.92 if c <= 0.0031308 else 1.055 * (c ** (1.0 / 2.4)) - 0.055


def _normalize(v: Sequence[float]) -> Vec3:
    n = math.sqrt(sum(c * c for c in v)) or 1.0
    return (v[0] / n, v[1] / n, v[2] / n)


def _distinct_colour(colour: RGB, name: str) -> RGB:
    """Nudge part families apart so kit reads without albedo textures."""
    return colour


class Frame:
    """RGB byte buffer with a depth buffer."""

    def __init__(self, width: int, height: int, background: RGB) -> None:
        self.width = width
        self.height = height
        self.rgb = bytearray()
        for _ in range(width * height):
            self.rgb += bytes(background)
        self.depth = [1e9] * (width * height)


def render(
    parts: List[dict],
    tex_dir: Path | None,
    width: int, height: int,
    yaw_deg: float = 0.0, pitch_deg: float = 0.0,
    background: RGB = (255, 255, 255),
    ss: int = 2,
    matrices: List[List[float]] | None = None,
    zoom: float = 1.0,
    centre_y: float = 0.90,
) -> bytes:
    W, H = width * ss, height * ss
    frame = Frame(W, H, background)
    yaw = math.radians(yaw_deg)
    pitch = math.radians(pitch_deg)
    cy, sy = math.cos(yaw), math.sin(yaw)
    cp, sp = math.cos(pitch), math.sin(pitch)

    # Camera basis: looking at the character from (yaw, pitch). Character faces
    # +Z, so yaw 0 sees the face.
    forward = (-sy * cp, -sp, -cy * cp)        # from eye toward character
    right = (cy, 0.0, -sy)
    up = (right[1] * forward[2] - right[2] * forward[1],
          right[2] * forward[0] - right[0] * forward[2],
          right[0] * forward[1] - right[1] * forward[0])
    up = _normalize(up)

    scale = (H * 0.88 / 1.86) * zoom
    ox, oy = W / 2.0, H / 2.0

    key = _normalize((0.55, 0.80, 0.65))
    fill = _normalize((-0.75, 0.25, 0.35))
    rim = _normalize((-0.25, 0.35, -0.85))
    sky = (0.52, 0.58, 0.68)
    ground = (0.24, 0.22, 0.20)

    for part in parts:
        pos = skinned_positions(part, matrices)
        verts = part.get("verts") or part.get("vertices") or []
        tex_rgb = texture_average(tex_dir, part.get("tex", ""), part["color"]) if tex_dir else \
            ((part["color"] >> 16) & 255, (part["color"] >> 8) & 255, part["color"] & 255)
        textured = bool(part.get("tex")) and tex_dir is not None
        idx = part.get("idx") or part.get("indices") or []

        # camera-space vertices, flat normals per triangle
        cam: List[Vec3] = []
        world: List[Vec3] = []
        for p in pos:
            world.append((p[0], p[1] - centre_y, p[2]))
            x = p[0] * right[0] + (p[1] - centre_y) * right[1] + p[2] * right[2]
            y = p[0] * up[0] + (p[1] - centre_y) * up[1] + p[2] * up[2]
            z = p[0] * forward[0] + (p[1] - centre_y) * forward[1] + p[2] * forward[2]
            cam.append((ox + x * scale, oy - y * scale, z))

        for t in range(0, len(idx) - 2, 3):
            a, b, c = idx[t], idx[t + 1], idx[t + 2]
            pa, pb, pc = world[a], world[b], world[c]
            e1 = (pb[0] - pa[0], pb[1] - pa[1], pb[2] - pa[2])
            e2 = (pc[0] - pa[0], pc[1] - pa[1], pc[2] - pa[2])
            n = (e1[1] * e2[2] - e1[2] * e2[1],
                 e1[2] * e2[0] - e1[0] * e2[2],
                 e1[0] * e2[1] - e1[1] * e2[0])
            ln = math.sqrt(n[0] ** 2 + n[1] ** 2 + n[2] ** 2)
            if ln < 1e-12:
                continue
            n = (n[0] / ln, n[1] / ln, n[2] / ln)
            # cull triangles facing away from the camera
            if n[0] * forward[0] + n[1] * forward[1] + n[2] * forward[2] > 0.0:
                continue

            ca, cb, cc = cam[a], cam[b], cam[c]
            minx = max(0, int(min(ca[0], cb[0], cc[0])))
            maxx = min(W - 1, int(max(ca[0], cb[0], cc[0])) + 1)
            miny = max(0, int(min(ca[1], cb[1], cc[1])))
            maxy = min(H - 1, int(max(ca[1], cb[1], cc[1])) + 1)
            if minx > maxx or miny > maxy:
                continue
            den = (cb[1] - cc[1]) * (ca[0] - cc[0]) + (cc[0] - cb[0]) * (ca[1] - cc[1])
            if abs(den) < 1e-9:
                continue

            # vertex colours (linear) blended to the texture mean
            base = [0.0, 0.0, 0.0]
            for k in range(3):
                s = 0.0
                for v in (a, b, c):
                    s += verts[v * 20 + 8 + k]
                base[k] = s / 3.0
            if textured:
                tint = [_srgb_to_linear(tex_rgb[k] / 255.0) for k in range(3)]
                for k in range(3):
                    base[k] = max(base[k], 0.0) * tint[k] + tint[k] * 0.35

            ndl = max(0.0, n[0] * key[0] + n[1] * key[1] + n[2] * key[2])
            ndf = max(0.0, n[0] * fill[0] + n[1] * fill[1] + n[2] * fill[2])
            hemi = 0.5 + 0.5 * n[1]
            amb = [ground[k] + (sky[k] - ground[k]) * hemi for k in range(3)]
            ndr = max(0.0, -(n[0] * forward[0] + n[1] * forward[1] + n[2] * forward[2]))
            fres = (1.0 - ndr) ** 3
            light = [amb[k] * 0.55 + 1.15 * ndl + 0.32 * ndf + 0.16 * fres for k in range(3)]
            lit = [_linear_to_srgb(base[k] * light[k]) * 255.0 for k in range(3)]

            for y in range(miny, maxy + 1):
                for x in range(minx, maxx + 1):
                    w0 = ((cb[1] - cc[1]) * (x - cc[0]) + (cc[0] - cb[0]) * (y - cc[1])) / den
                    w1 = ((cc[1] - ca[1]) * (x - cc[0]) + (ca[0] - cc[0]) * (y - cc[1])) / den
                    w2 = 1.0 - w0 - w1
                    if w0 < -1e-6 or w1 < -1e-6 or w2 < -1e-6:
                        continue
                    z = w0 * ca[2] + w1 * cb[2] + w2 * cc[2]
                    o = y * W + x
                    if z >= frame.depth[o]:
                        continue
                    frame.depth[o] = z
                    q = o * 3
                    frame.rgb[q] = int(max(0, min(255, lit[0])))
                    frame.rgb[q + 1] = int(max(0, min(255, lit[1])))
                    frame.rgb[q + 2] = int(max(0, min(255, lit[2])))

    # box-filter down from the supersampled buffer
    if ss == 1:
        return bytes(frame.rgb)
    out = bytearray(width * height * 3)
    inv = 1.0 / (ss * ss)
    for y in range(height):
        for x in range(width):
            r = g = b = 0
            for sy in range(ss):
                row = ((y * ss + sy) * W + x * ss) * 3
                for sx in range(ss):
                    q = row + sx * 3
                    r += frame.rgb[q]
                    g += frame.rgb[q + 1]
                    b += frame.rgb[q + 2]
            o = (y * width + x) * 3
            out[o] = int(r * inv)
            out[o + 1] = int(g * inv)
            out[o + 2] = int(b * inv)
    return bytes(out)


# --------------------------------------------------------------------- sheets

def load(name: str) -> Tuple[List[dict], List[List[float]], Path]:
    path = ASSETS / (name if name.endswith(".oeac") else name + ".oeac")
    parts, inverse_bind = read_oeac(path)
    return parts, inverse_bind, ASSETS / path.stem


def render_sheet(
    name: str,
    out: Path,
    views: Sequence[Tuple[str, float, float]],
    tile_w: int = 512, tile_h: int = 768,
    background: RGB = (255, 255, 255),
    pose: str = "bind",
    labels: bool = True,
) -> None:
    parts, inverse_bind, tex_dir = load(name)
    mats = bone_matrices(inverse_bind, POSE_PRESETS.get(pose, {}))
    W, H = tile_w * len(views), tile_h
    canvas = bytearray(W * H * 3)
    for i in range(W * H):
        canvas[i * 3:i * 3 + 3] = bytes(background)
    for col, (label, yaw, pitch) in enumerate(views):
        tile = render(parts, tex_dir, tile_w, tile_h, yaw, pitch, background, matrices=mats)
        for y in range(tile_h):
            src = y * tile_w * 3
            dst = (y * W + col * tile_w) * 3
            canvas[dst:dst + tile_w * 3] = tile[src:src + tile_w * 3]
        if labels:
            draw_label(canvas, W, H, col * tile_w + tile_w // 2, tile_h - 28, label, background)
    write_png(out, W, H, bytes(canvas))
    print(f"wrote {out} ({W}x{H})")


GLYPHS: Dict[str, List[str]] = {
    "A": ["01110", "10001", "10001", "11111", "10001", "10001", "10001"],
    "B": ["11110", "10001", "11110", "10001", "10001", "10001", "11110"],
    "C": ["01111", "10000", "10000", "10000", "10000", "10000", "01111"],
    "D": ["11110", "10001", "10001", "10001", "10001", "10001", "11110"],
    "E": ["11111", "10000", "11110", "10000", "10000", "10000", "11111"],
    "F": ["11111", "10000", "11110", "10000", "10000", "10000", "10000"],
    "G": ["01111", "10000", "10000", "10011", "10001", "10001", "01111"],
    "H": ["10001", "10001", "11111", "10001", "10001", "10001", "10001"],
    "I": ["111", "010", "010", "010", "010", "010", "111"],
    "K": ["10001", "10010", "11100", "10100", "10010", "10001", "10001"],
    "L": ["10000", "10000", "10000", "10000", "10000", "10000", "11111"],
    "M": ["10001", "11011", "10101", "10001", "10001", "10001", "10001"],
    "N": ["10001", "11001", "10101", "10011", "10001", "10001", "10001"],
    "O": ["01110", "10001", "10001", "10001", "10001", "10001", "01110"],
    "P": ["11110", "10001", "11110", "10000", "10000", "10000", "10000"],
    "Q": ["01110", "10001", "10001", "10001", "10101", "10010", "01101"],
    "R": ["11110", "10001", "11110", "10100", "10010", "10001", "10001"],
    "S": ["01111", "10000", "01110", "00001", "00001", "10001", "01110"],
    "T": ["11111", "00100", "00100", "00100", "00100", "00100", "00100"],
    "U": ["10001", "10001", "10001", "10001", "10001", "10001", "01110"],
    "V": ["10001", "10001", "10001", "10001", "10001", "01010", "00100"],
    "W": ["10001", "10001", "10001", "10101", "10101", "11011", "10001"],
    "X": ["10001", "01010", "00100", "00100", "00100", "01010", "10001"],
    "Y": ["10001", "01010", "00100", "00100", "00100", "00100", "00100"],
    "Z": ["11111", "00010", "00100", "00100", "01000", "10000", "11111"],
    "0": ["01110", "10001", "10011", "10101", "11001", "10001", "01110"],
    "1": ["001", "011", "001", "001", "001", "001", "111"],
    "2": ["01110", "10001", "00001", "00110", "01000", "10000", "11111"],
    "3": ["11110", "00001", "01110", "00001", "00001", "10001", "01110"],
    "4": ["00010", "00110", "01010", "10010", "11111", "00010", "00010"],
    "5": ["11111", "10000", "11110", "00001", "00001", "10001", "01110"],
    "6": ["01110", "10000", "11110", "10001", "10001", "10001", "01110"],
    "7": ["11111", "00001", "00010", "00100", "01000", "01000", "01000"],
    "8": ["01110", "10001", "01110", "10001", "10001", "10001", "01110"],
    "9": ["01110", "10001", "10001", "01111", "00001", "00001", "01110"],
    " ": ["000", "000", "000", "000", "000", "000", "000"],
    "-": ["00000", "00000", "00000", "11111", "00000", "00000", "00000"],
    ".": ["00", "00", "00", "00", "00", "00", "11"],
    "/": ["00001", "00010", "00010", "00100", "01000", "01000", "10000"],
    "+": ["00000", "00100", "00100", "11111", "00100", "00100", "00000"],
}

TEXT_COLOUR: RGB = (34, 38, 44)


def draw_label(canvas: bytearray, width: int, height: int, cx: int, cy: int,
               text: str, background: RGB, ink: RGB = TEXT_COLOUR, px: int = 2) -> None:
    text = text.upper()
    total = 0
    for ch in text:
        g = GLYPHS.get(ch, GLYPHS[" "])
        total += (len(g[0]) + 1) * px
    x = cx - total // 2
    for ch in text:
        g = GLYPHS.get(ch, GLYPHS[" "])
        for row, bits in enumerate(g):
            for col, bit in enumerate(bits):
                if bit != "1":
                    continue
                for dy in range(px):
                    for dx in range(px):
                        xx = x + col * px + dx
                        yy = cy + row * px + dy
                        if 0 <= xx < width and 0 <= yy < height:
                            o = (yy * width + xx) * 3
                            canvas[o:o + 3] = bytes(ink)
        x += (len(g[0]) + 1) * px


VIEWS: Dict[str, Tuple[float, float]] = {
    "front": (0.0, 0.0),
    "three-quarter": (35.0, 6.0),
    "side": (90.0, 0.0),
    "back": (180.0, 0.0),
}


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("character", nargs="?", default="hero", help="asset id, e.g. hero or bot_medic")
    p.add_argument("--out", type=Path, default=None)
    p.add_argument("--views", default="front,three-quarter,side,back")
    p.add_argument("--tile", default="512x768")
    p.add_argument("--white", action="store_true", help="white background (reference sheet)")
    p.add_argument("--pose", default="bind", choices=sorted(POSE_PRESETS),
                   help="bind | tpose | aim | run | crouch | reload")
    p.add_argument("--yaw", type=float, default=None)
    p.add_argument("--pitch", type=float, default=0.0)
    p.add_argument("--zoom", type=float, default=1.0)
    p.add_argument("--centre-y", type=float, default=0.90)
    args = p.parse_args()

    tile_w, tile_h = (int(v) for v in args.tile.lower().split("x"))
    background = (255, 255, 255) if args.white else (24, 28, 34)
    if args.yaw is not None:
        out = args.out or Path(f"/tmp/{args.character}-{int(args.yaw)}.png")
        parts, inverse_bind, tex_dir = load(args.character)
        mats = bone_matrices(inverse_bind, POSE_PRESETS.get(args.pose, {}))
        px = render(parts, tex_dir, tile_w, tile_h, args.yaw, args.pitch, background,
                    matrices=mats, zoom=args.zoom, centre_y=args.centre_y)
        write_png(out, tile_w, tile_h, px)
        print(f"wrote {out} ({tile_w}x{tile_h})")
        return 0
    views = [v.strip() for v in args.views.split(",") if v.strip()]
    unknown = [v for v in views if v not in VIEWS]
    if unknown:
        print(f"unknown view(s): {', '.join(unknown)} (have: {', '.join(sorted(VIEWS))})", file=sys.stderr)
        return 2
    out = args.out or Path(f"/tmp/{args.character}-sheet.png")
    render_sheet(args.character, out,
                 [(v, *VIEWS[v]) for v in views],
                 tile_w, tile_h, background, args.pose)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
