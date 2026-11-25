#!/usr/bin/env python3
"""Smoke test for the auto-rig path in glb_to_oeac.py (tools/tripo/autorig.py).

Builds an unrigged GLB from scratch — no skin, no joints, no animations, one
mesh in an A-pose — converts it, and checks that what comes out is a character
the engine can actually animate:

* a valid `.oeac` with 19 bones and a 20-float vertex stride;
* four bone ids in 0..18 followed by four weights summing to 1, in the
  non-interleaved layout the loader expects (this was wrong once: the ids and
  weights were written alternately, every vertex came out weighted to bone 0
  with weight 0, and the character folded through the floor);
* the bind pose stays a 1.8 m standing figure — no rotation baked into the
  inverse binds, since Skeleton rebuilds bones from translation alone;
* the T-pose widens the arms and the crouch lowers the head, i.e. the weights
  really drive the limbs.

Dependency-free: no numpy, no trimesh, no GLB fixtures in the repo.

Usage:
  python3 tools/tripo/test_autorig.py
"""

from __future__ import annotations

import json
import math
import struct
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CONVERTER = ROOT / "tools" / "tripo" / "glb_to_oeac.py"
sys.path.insert(0, str(ROOT / "tools" / "tripo"))

import render_oeac as R  # noqa: E402

HEIGHT = 1.80


def pad(data: bytes, fill: bytes = b"\0") -> bytes:
    return data + fill * ((4 - len(data) % 4) % 4)


def build_body() -> tuple[list[float], list[int]]:
    """A blocky humanoid in an A-pose, built from stacks of rings.

    Deliberately crude, but *resolved*: a single box for the torso has no
    vertices between the pelvis and the chest, so no rig could give that band a
    pelvis weight and the test would be measuring the fixture, not the rigger.
    Rings every ~5 cm put vertices where a real mesh has them.
    """
    verts: list[float] = []
    tris: list[int] = []
    SEGMENTS = 8

    def tube(cx: float, cz: float, y0: float, y1: float,
             rx: float, rz: float, rings: int) -> None:
        base = len(verts) // 3
        for j in range(rings + 1):
            t = j / rings
            y = y0 + (y1 - y0) * t
            for k in range(SEGMENTS):
                a = 2 * math.pi * k / SEGMENTS
                verts.extend([cx + rx * math.cos(a), y, cz + rz * math.sin(a)])
        for j in range(rings):
            for k in range(SEGMENTS):
                a = base + j * SEGMENTS + k
                b = base + j * SEGMENTS + (k + 1) % SEGMENTS
                c = b + SEGMENTS
                d = a + SEGMENTS
                tris.extend([a, b, c, a, c, d])
        # Cap both ends so the shell is closed and its winding is well defined.
        top = base + rings * SEGMENTS
        for k in range(1, SEGMENTS - 1):
            tris.extend([base, base + k + 1, base + k])
            tris.extend([top, top + k, top + k + 1])

    tube(0.0, 0.0, 0.82, 1.45, 0.16, 0.11, 13)      # torso
    tube(0.0, 0.0, 1.42, 1.60, 0.045, 0.045, 4)     # neck
    tube(0.0, 0.0, 1.58, 1.80, 0.095, 0.095, 5)     # head
    for side in (1, -1):
        # One straight A-pose arm at 32 degrees, as a single tapered tube.
        sin32, cos32 = math.sin(math.radians(32)), math.cos(math.radians(32))
        shoulder = (side * 0.17, 1.40, 0.0)
        arm_len = 0.62
        for i in range(8):
            t0, t1 = i / 8, (i + 1) / 8
            a = (shoulder[0] + side * sin32 * arm_len * t0,
                 shoulder[1] - cos32 * arm_len * t0, 0.0)
            b = (shoulder[0] + side * sin32 * arm_len * t1,
                 shoulder[1] - cos32 * arm_len * t1, 0.0)
            r = 0.052 - 0.016 * t1
            tube((a[0] + b[0]) / 2, 0.0, min(a[1], b[1]), max(a[1], b[1]), r, r, 1)
        tube(side * 0.10, 0.0, 0.44, 0.86, 0.058, 0.058, 9)   # thigh
        tube(side * 0.10, 0.0, 0.07, 0.46, 0.048, 0.048, 8)   # shin
        tube(side * 0.10, 0.05, 0.0, 0.09, 0.055, 0.10, 2)    # boot
    return verts, tris


def write_glb(path: Path) -> None:
    verts, tris = build_body()
    count = len(verts) // 3
    positions = b"".join(struct.pack("<3f", *verts[i * 3:i * 3 + 3]) for i in range(count))
    # Normals: outward from the body axis is close enough for the converter.
    normals = b"".join(struct.pack("<3f", 0.0, 1.0, 0.0) for _ in range(count))
    uvs = b"".join(struct.pack("<2f", (verts[i * 3] + 1) * 0.5, (verts[i * 3 + 1]) / HEIGHT)
                  for i in range(count))
    indices = b"".join(struct.pack("<I", i) for i in tris)

    blob = bytearray()
    views = []
    for data in (indices, positions, normals, uvs):
        while len(blob) % 4:
            blob.append(0)
        views.append({"buffer": 0, "byteOffset": len(blob), "byteLength": len(data)})
        blob.extend(data)
    while len(blob) % 4:
        blob.append(0)

    doc = {
        "asset": {"version": "2.0", "generator": "oea test_autorig"},
        "scene": 0,
        "scenes": [{"nodes": [0]}],
        "nodes": [{"name": "world", "children": [1]}, {"name": "body", "mesh": 0}],
        "meshes": [{"primitives": [{
            "attributes": {"POSITION": 1, "NORMAL": 2, "TEXCOORD_0": 3},
            "indices": 0, "mode": 4,
        }]}],
        "accessors": [
            {"bufferView": 0, "componentType": 5125, "count": len(tris), "type": "SCALAR"},
            {"bufferView": 1, "componentType": 5126, "count": count, "type": "VEC3"},
            {"bufferView": 2, "componentType": 5126, "count": count, "type": "VEC3"},
            {"bufferView": 3, "componentType": 5126, "count": count, "type": "VEC2"},
        ],
        "bufferViews": views,
        "buffers": [{"byteLength": len(blob)}],
    }
    js = pad(json.dumps(doc, separators=(",", ":")).encode(), b" ")
    total = 12 + 8 + len(js) + 8 + len(blob)
    out = bytearray(b"glTF" + struct.pack("<II", 2, total))
    out += struct.pack("<I4s", len(js), b"JSON") + js
    out += struct.pack("<I4s", len(blob), b"BIN\0") + bytes(blob)
    path.write_bytes(bytes(out))


def main() -> int:
    with tempfile.TemporaryDirectory() as tmp:
        tmp_path = Path(tmp)
        glb = tmp_path / "unrigged.glb"
        oeac = tmp_path / "out.oeac"
        write_glb(glb)

        result = subprocess.run(
            [sys.executable, str(CONVERTER), str(glb), "--out", str(oeac),
             "--textures", str(tmp_path / "tex"), "--no-textures"],
            capture_output=True, text=True)
        if result.returncode != 0:
            print("FAIL: converter rejected an unrigged GLB")
            print(result.stdout[-2000:])
            print(result.stderr[-2000:])
            return 1
        if not oeac.exists():
            print("FAIL: no .oeac written")
            return 1

        parts, inverse_bind = R.read_oeac(oeac)
        if len(inverse_bind) != 19:
            print(f"FAIL: {len(inverse_bind)} bones, expected 19")
            return 1
        if not parts:
            print("FAIL: asset has no parts")
            return 1

        for part in parts:
            verts = part["verts"]
            if len(verts) % 20:
                print(f"FAIL: {len(verts)} floats is not a multiple of 20")
                return 1
            for i in range(len(verts) // 20):
                o = i * 20
                ids = [verts[o + 12 + k] for k in range(4)]
                weights = [verts[o + 16 + k] for k in range(4)]
                for bone in ids:
                    if bone < 0 or bone > 18 or abs(bone - round(bone)) > 1e-6:
                        print(f"FAIL: vertex {i} bone id {bone} out of range")
                        return 1
                total = sum(weights)
                if abs(total - 1.0) > 1e-2:
                    print(f"FAIL: vertex {i} weights sum to {total:.3f}")
                    return 1
                if any(w < 0.0 for w in weights):
                    print(f"FAIL: vertex {i} has a negative weight")
                    return 1

        # The bind pose must be a standing 1.8 m figure with feet on the floor.
        pts = [p for part in parts for p in R.skinned_positions(part, None)]
        low = min(p[1] for p in pts)
        high = max(p[1] for p in pts)
        if abs(high - low - HEIGHT) > 0.02:
            print(f"FAIL: bind height is {high - low:.3f} m, expected {HEIGHT}")
            return 1
        if abs(low) > 0.02:
            print(f"FAIL: feet are at y = {low:.3f}, expected 0")
            return 1

        # Every bone must own geometry, or its limb will not bend.
        owned = set()
        for part in parts:
            verts = part["verts"]
            for i in range(len(verts) // 20):
                if verts[i * 20 + 16] >= 0.3:
                    owned.add(int(verts[i * 20 + 12]))
        missing = sorted(set(range(19)) - owned)
        # FORE_* owning nothing is normal (the whole hand goes to HAND_*).
        missing = [b for b in missing if b not in (7, 11)]
        if missing:
            print(f"FAIL: no vertices dominated by bones {missing}")
            return 1

        # The poses must actually move the character.
        bind_mats = R.bone_matrices(inverse_bind, {})
        tpose_mats = R.bone_matrices(inverse_bind, R.POSE_PRESETS["tpose"])
        crouch_mats = R.bone_matrices(inverse_bind, R.POSE_PRESETS["crouch"])

        def extent(mats):
            ps = [p for part in parts for p in R.skinned_positions(part, mats)]
            return (min(p[0] for p in ps), max(p[0] for p in ps),
                    min(p[1] for p in ps), max(p[1] for p in ps))

        x0, x1, _, y1 = extent(bind_mats)
        tx0, tx1, _, _ = extent(tpose_mats)
        _, _, _, cy1 = extent(crouch_mats)

        # The A-pose is already 1.03 m wide at the hands, so a T-pose has to
        # beat it by a clear margin to prove the shoulder weights drive.
        if (tx1 - tx0) < (x1 - x0) * 1.30:
            print(f"FAIL: T-pose barely widens the arms "
                  f"({tx1 - tx0:.2f} m vs {x1 - x0:.2f} m)")
            return 1
        if cy1 > y1 - 0.06:
            print(f"FAIL: crouch does not lower the head ({cy1:.2f} m vs {y1:.2f} m)")
            return 1

        print(f"  auto-rig: {len(parts)} parts, {len(pts)} vertices, "
              f"19 bones, T-pose {tx1 - tx0:.2f} m wide, "
              f"crouch head {cy1:.2f} m")
    print("autorig smoke test passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
