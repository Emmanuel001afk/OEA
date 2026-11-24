#!/usr/bin/env python3
"""Small dependency-free smoke test for glb_to_oeac.py.

Builds a synthetic skinned GLB with the 19 OEA bones, converts it, and checks
the .oeac header, vertex stride, normalisation and animation records.
"""

from __future__ import annotations

import json
import struct
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CONVERTER = ROOT / "tools" / "tripo" / "glb_to_oeac.py"

JOINT_NAMES = [
    "mixamorig:Hips", "mixamorig:Spine", "mixamorig:Spine2", "mixamorig:Neck", "mixamorig:Head",
    "mixamorig:LeftShoulder", "mixamorig:LeftArm", "mixamorig:LeftForeArm", "mixamorig:LeftHand",
    "mixamorig:RightShoulder", "mixamorig:RightArm", "mixamorig:RightForeArm", "mixamorig:RightHand",
    "mixamorig:LeftUpLeg", "mixamorig:LeftLeg", "mixamorig:LeftFoot",
    "mixamorig:RightUpLeg", "mixamorig:RightLeg", "mixamorig:RightFoot",
]


def pad(data: bytes, fill: bytes = b"\0") -> bytes:
    return data + fill * ((4 - len(data) % 4) % 4)


def build_glb(path: Path) -> None:
    binary = bytearray()
    views = []

    def add(data: bytes, target: int) -> int:
        while len(binary) % 4:
            binary.append(0)
        offset = len(binary)
        binary.extend(data)
        views.append({"buffer": 0, "byteOffset": offset, "byteLength": len(data), "target": target})
        return len(views) - 1

    positions = [[-0.2, 0.0, 0.0], [0.2, 0.0, 0.0], [-0.15, 1.8, 0.0], [0.15, 1.8, 0.0]]
    joints = [[0, 0, 0, 0]] * len(positions)
    weights = [[1, 0, 0, 0]] * len(positions)
    indices = [0, 1, 2, 1, 3, 2]
    identity = [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]

    pos_view = add(struct.pack("<%df" % (len(positions) * 3), *[v for p in positions for v in p]), 34962)
    norm_view = add(struct.pack("<%df" % (len(positions) * 3), *[0, 0, 1] * len(positions)), 34962)
    uv_view = add(struct.pack("<%df" % (len(positions) * 2), *[0, 0] * len(positions)), 34962)
    joint_view = add(bytes(j for row in joints for j in row), 34962)
    weight_view = add(struct.pack("<%df" % (len(positions) * 4), *[v for w in weights for v in w]), 34962)
    index_view = add(struct.pack("<%dH" % len(indices), *indices), 34963)
    ibm_view = add(struct.pack("<%df" % (len(JOINT_NAMES) * 16), *[v for _ in JOINT_NAMES for v in identity]), 34962)
    times = [0.0, 1.0]
    rotations = [[0, 0, 0, 1], [0, 0.3826834, 0, 0.9238795]]
    time_view = add(struct.pack("<2f", *times), 34962)
    rot_view = add(struct.pack("<%df" % (len(rotations) * 4), *[v for q in rotations for v in q]), 34962)

    def acc(view: int, component: int, kind: str, count: int) -> dict:
        return {"bufferView": view, "componentType": component, "type": kind, "count": count}

    doc = {
        "asset": {"version": "2.0"},
        "scene": 0,
        "scenes": [{"nodes": [0]}],
        "nodes": [{"name": "Soldier", "mesh": 0, "skin": 0, "children": list(range(1, 20))}] +
                 [{"name": name} for name in JOINT_NAMES],
        "meshes": [{"primitives": [{"attributes": {
            "POSITION": acc(pos_view, 5126, "VEC3", len(positions)),
            "NORMAL": acc(norm_view, 5126, "VEC3", len(positions)),
            "TEXCOORD_0": acc(uv_view, 5126, "VEC2", len(positions)),
            "JOINTS_0": acc(joint_view, 5121, "VEC4", len(positions)),
            "WEIGHTS_0": acc(weight_view, 5126, "VEC4", len(positions)),
        }, "indices": acc(index_view, 5123, "SCALAR", len(indices)), "material": 0}]}],
        "materials": [{"name": "skin_tan", "pbrMetallicRoughness": {"baseColorFactor": [0.8, 0.6, 0.4, 1]}}],
        "skins": [{"joints": list(range(1, 20)), "inverseBindMatrices": acc(ibm_view, 5126, "MAT4", 19)}],
        "animations": [{"name": "walk", "samplers": [{"input": acc(time_view, 5126, "SCALAR", 2),
                                                      "output": acc(rot_view, 5126, "VEC4", 2)}],
                        "channels": [{"sampler": 0, "target": {"node": 6, "path": "rotation"}}]}],
        "accessors": [],
        "bufferViews": views,
        "buffers": [{"byteLength": len(binary)}],
    }
    # Accessors are referenced by index; flatten definitions in the order used.
    accessors = [
        acc(pos_view, 5126, "VEC3", len(positions)),
        acc(norm_view, 5126, "VEC3", len(positions)),
        acc(uv_view, 5126, "VEC2", len(positions)),
        acc(joint_view, 5121, "VEC4", len(positions)),
        acc(weight_view, 5126, "VEC4", len(positions)),
        acc(index_view, 5123, "SCALAR", len(indices)),
        acc(ibm_view, 5126, "MAT4", 19),
        acc(time_view, 5126, "SCALAR", 2),
        acc(rot_view, 5126, "VEC4", 2),
    ]
    doc["accessors"] = accessors
    # Replace the inline accessor objects with indices into the flattened list.
    prim = doc["meshes"][0]["primitives"][0]
    for key, idx in [("POSITION", 0), ("NORMAL", 1), ("TEXCOORD_0", 2), ("JOINTS_0", 3), ("WEIGHTS_0", 4)]:
        prim["attributes"][key] = idx
    prim["indices"] = 5
    doc["skins"][0]["inverseBindMatrices"] = 6
    sampler = doc["animations"][0]["samplers"][0]
    sampler["input"], sampler["output"] = 7, 8

    json_chunk = pad(json.dumps(doc, separators=(",", ":")).encode(), b" ")
    bin_chunk = pad(bytes(binary))
    glb = struct.pack("<4sII", b"glTF", 2, 12 + 8 + len(json_chunk) + 8 + len(bin_chunk))
    glb += struct.pack("<I4s", len(json_chunk), b"JSON") + json_chunk
    glb += struct.pack("<I4s", len(bin_chunk), b"BIN\0") + bin_chunk
    path.write_bytes(glb)


def check_oeac(path: Path) -> None:
    data = path.read_bytes()
    assert data[:4] == b"OEAC", "bad magic"
    version, bones, meshes, clips = struct.unpack_from("<4H", data, 4)
    assert (version, bones, meshes, clips) == (1, 19, 1, 1), (version, bones, meshes, clips)
    offset = 12
    offset += 19 * (2 + 16 * 4)
    name_len = data[offset]
    offset += 1 + name_len
    tex_len = data[offset]
    offset += 1 + tex_len
    color, flags = struct.unpack_from("<2I", data, offset)
    offset += 8
    verts, indices = struct.unpack_from("<2I", data, offset)
    offset += 8
    assert verts == 4 and indices == 6, (verts, indices)
    assert flags & 1, "skin flag not set for skin material"
    ys = []
    for i in range(verts):
        y = struct.unpack_from("<f", data, offset + i * 20 * 4 + 1 * 4)[0]
        ys.append(y)
    assert abs(min(ys)) < 1e-3, f"feet not at y=0: {min(ys)}"
    assert abs(max(ys) - 1.8) < 1e-3, f"height not normalised: {max(ys)}"


def main() -> int:
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        glb = root / "soldier.glb"
        out = root / "soldier.oeac"
        build_glb(glb)
        result = subprocess.run([sys.executable, str(CONVERTER), str(glb), "--out", str(out),
                                 "--no-textures", "--fps", "10", "--max-frames", "11"],
                                capture_output=True, text=True)
        if result.returncode:
            print(result.stdout, result.stderr, file=sys.stderr)
            return result.returncode
        check_oeac(out)
        print("glb_to_oeac smoke test passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
