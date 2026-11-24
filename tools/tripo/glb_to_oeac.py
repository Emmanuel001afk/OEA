#!/usr/bin/env python3
"""Convert a rigged Tripo3D GLB into OEA's compact .oeac character format.

The converter is deliberately dependency-free so it can run alongside the
repository on any machine with Python 3. It:

  * reads a GLB (JSON + BIN chunks),
  * maps a Mixamo/Tripo humanoid skeleton onto OEA's fixed 19 bones,
  * folds the skinned node transform into the vertices and inverse binds,
  * scales the result to OEA's ~1.8 m soldier height, feet at y = 0,
  * optionally samples GLB animation clips as bind-relative matrices,
  * writes one .oeac file plus its base-colour textures.

Example:
  python3 tools/tripo/glb_to_oeac.py soldier_rigged.glb \
      --out oea-engine/src/main/assets/characters/hero.oeac \
      --textures oea-engine/src/main/assets/characters/hero

No API key is needed here: generate and download the GLB with Tripo Studio or
the Tripo API first, then point this script at the downloaded file.
"""

from __future__ import annotations

import argparse
import base64
import json
import math
import os
import struct
import sys
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Sequence, Tuple

MAGIC = b"OEAC"
VERSION = 1
BONE_NAMES = [
    "PELVIS", "SPINE", "CHEST", "NECK", "HEAD",
    "SHOULDER_L", "ARM_L", "FORE_L", "HAND_L",
    "SHOULDER_R", "ARM_R", "FORE_R", "HAND_R",
    "THIGH_L", "SHIN_L", "FOOT_L",
    "THIGH_R", "SHIN_R", "FOOT_R",
]
BONE_PARENTS = [-1, 0, 1, 2, 3, 2, 5, 6, 7, 2, 9, 10, 11, 0, 13, 14, 0, 16, 17]
BONE_INDEX = {name: i for i, name in enumerate(BONE_NAMES)}

# Longest aliases first so "leftarm" wins over "left" substring matches.
ALIASES: Dict[str, List[str]] = {
    "PELVIS": ["hips", "pelvis", "hip", "root"],
    "SPINE": ["spine", "spine0", "spine1"],
    "CHEST": ["spine2", "chest", "upperchest", "torso"],
    "NECK": ["neck", "neck1"],
    "HEAD": ["head", "headtop"],
    "SHOULDER_L": ["leftshoulder", "shoulderl", "lshoulder", "left_shoulder"],
    "ARM_L": ["leftarm", "leftupperarm", "upperarml", "larm", "left_arm"],
    "FORE_L": ["leftforearm", "leftlowerarm", "lowerarml", "lforearm", "left_forearm"],
    "HAND_L": ["lefthand", "handl", "lhand", "left_hand"],
    "SHOULDER_R": ["rightshoulder", "shoulderr", "rshoulder", "right_shoulder"],
    "ARM_R": ["rightarm", "rightupperarm", "upperarmr", "rarm", "right_arm"],
    "FORE_R": ["rightforearm", "rightlowerarm", "lowerarmr", "rforearm", "right_forearm"],
    "HAND_R": ["righthand", "handr", "rhand", "right_hand"],
    "THIGH_L": ["leftupleg", "leftthigh", "upperlegl", "lthigh", "left_thigh"],
    "SHIN_L": ["leftleg", "leftcalf", "leftlowerleg", "lowerlegl", "lshin", "left_shin"],
    "FOOT_L": ["leftfoot", "lefttoe", "footl", "lfoot", "left_foot"],
    "THIGH_R": ["rightupleg", "rightthigh", "upperlegr", "rthigh", "right_thigh"],
    "SHIN_R": ["rightleg", "rightcalf", "rightlowerleg", "lowerlegr", "rshin", "right_shin"],
    "FOOT_R": ["rightfoot", "righttoe", "footr", "rfoot", "right_foot"],
}
ALL_ALIASES = sorted(
    ((alias, bone) for bone, aliases in ALIASES.items() for alias in aliases),
    key=lambda pair: len(pair[0]), reverse=True,
)

COMPONENT = {5120: ("b", 1), 5121: ("B", 1), 5122: ("h", 2), 5123: ("H", 2), 5125: ("I", 4), 5126: ("f", 4)}
TYPE_COUNT = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4, "MAT4": 16}
NORMALISED = {5120: 127.0, 5121: 255.0, 5122: 32767.0, 5123: 65535.0, 5125: 4294967295.0}

# Flags in the .oeac mesh record.
FLAG_SKIN = 1
FLAG_DETAIL = 2
FLAG_DOUBLE_SIDED = 4


# --------------------------------------------------------------------- matrices
# All matrices are column-major lists of 16 floats, exactly like glTF and Mat4.

def mat_identity() -> List[float]:
    m = [0.0] * 16
    m[0] = m[5] = m[10] = m[15] = 1.0
    return m


def mat_mul(a: Sequence[float], b: Sequence[float]) -> List[float]:
    out = [0.0] * 16
    for c in range(4):
        for r in range(4):
            out[c * 4 + r] = sum(a[k * 4 + r] * b[c * 4 + k] for k in range(4))
    return out


def mat_invert(m: Sequence[float]) -> List[float]:
    rows = [[m[c * 4 + r] for c in range(4)] + [1.0 if r == c else 0.0 for c in range(4)] for r in range(4)]
    for col in range(4):
        pivot = max(range(col, 4), key=lambda r: abs(rows[r][col]))
        if abs(rows[pivot][col]) < 1e-12:
            raise ValueError("singular matrix")
        rows[col], rows[pivot] = rows[pivot], rows[col]
        scale = rows[col][col]
        rows[col] = [v / scale for v in rows[col]]
        for r in range(4):
            if r == col:
                continue
            factor = rows[r][col]
            rows[r] = [v - factor * p for v, p in zip(rows[r], rows[col])]
    out = [0.0] * 16
    for r in range(4):
        for c in range(4):
            out[c * 4 + r] = rows[r][4 + c]
    return out


def mat_trs(t: Sequence[float], q: Sequence[float], s: Sequence[float]) -> List[float]:
    x, y, z, w = q
    xx, yy, zz = x * x, y * y, z * z
    xy, xz, yz = x * y, x * z, y * z
    wx, wy, wz = w * x, w * y, w * z
    m = mat_identity()
    m[0] = (1 - 2 * (yy + zz)) * s[0]
    m[1] = 2 * (xy + wz) * s[0]
    m[2] = 2 * (xz - wy) * s[0]
    m[4] = 2 * (xy - wz) * s[1]
    m[5] = (1 - 2 * (xx + zz)) * s[1]
    m[6] = 2 * (yz + wx) * s[1]
    m[8] = 2 * (xz + wy) * s[2]
    m[9] = 2 * (yz - wx) * s[2]
    m[10] = (1 - 2 * (xx + yy)) * s[2]
    m[12], m[13], m[14] = t
    return m


def point(m: Sequence[float], v: Sequence[float]) -> List[float]:
    return [
        m[0] * v[0] + m[4] * v[1] + m[8] * v[2] + m[12],
        m[1] * v[0] + m[5] * v[1] + m[9] * v[2] + m[13],
        m[2] * v[0] + m[6] * v[1] + m[10] * v[2] + m[14],
    ]


def direction(m: Sequence[float], v: Sequence[float]) -> List[float]:
    x = m[0] * v[0] + m[4] * v[1] + m[8] * v[2]
    y = m[1] * v[0] + m[5] * v[1] + m[9] * v[2]
    z = m[2] * v[0] + m[6] * v[1] + m[10] * v[2]
    length = math.sqrt(x * x + y * y + z * z) or 1.0
    return [x / length, y / length, z / length]


def srgb_to_linear(c: float) -> float:
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def linear_to_srgb(c: float) -> float:
    c = max(0.0, min(1.0, c))
    return c * 12.92 if c <= 0.0031308 else 1.055 * (c ** (1.0 / 2.4)) - 0.055


def to_argb(factor: Sequence[float]) -> int:
    rgb = [round(linear_to_srgb(max(0.0, min(1.0, float(c)))) * 255) for c in factor[:3]]
    a = round(max(0.0, min(1.0, float(factor[3] if len(factor) > 3 else 1.0))) * 255)
    return (a << 24) | (rgb[0] << 16) | (rgb[1] << 8) | rgb[2]


# --------------------------------------------------------------------- GLB/accessors

def read_glb(path: Path) -> Tuple[Dict[str, Any], bytes]:
    data = path.read_bytes()
    if len(data) < 20:
        raise ValueError("file is too short to be a GLB")
    magic, version, length = struct.unpack_from("<4sII", data, 0)
    if magic != b"glTF" or version != 2:
        raise ValueError("expected a glTF 2.0 GLB")
    offset, chunks = 12, {}
    while offset + 8 <= min(length, len(data)):
        chunk_len, chunk_type = struct.unpack_from("<I4s", data, offset)
        offset += 8
        if offset + chunk_len > len(data):
            break
        chunks[chunk_type] = data[offset:offset + chunk_len]
        offset += chunk_len
    if b"JSON" not in chunks:
        raise ValueError("GLB has no JSON chunk")
    doc = json.loads(chunks[b"JSON"].decode("utf-8").rstrip("\0 "))
    return doc, chunks.get(b"BIN\0", b"")


def read_accessor(doc: Dict[str, Any], binary: bytes, index: int) -> List[Any]:
    acc = doc["accessors"][index]
    view = doc["bufferViews"][acc["bufferView"]]
    fmt, size = COMPONENT[acc["componentType"]]
    count = TYPE_COUNT[acc["type"]]
    stride = view.get("byteStride", size * count)
    start = view.get("byteOffset", 0) + acc.get("byteOffset", 0)
    norm = NORMALISED.get(acc["componentType"]) if acc.get("normalized") else None
    values: List[Any] = []
    for i in range(acc["count"]):
        raw = struct.unpack_from("<" + fmt * count, binary, start + i * stride)
        if norm is not None and fmt != "f":
            raw = tuple(max(-1.0, v / norm) if fmt == "b" else min(1.0, v / norm) for v in raw)
        values.append(raw[0] if count == 1 else list(raw))
    return values


def normalise_name(name: str) -> str:
    name = name.split("|")[-1].split("/")[-1]
    for prefix in ("mixamorig:", "mixamorig", "armature.", "armature", "rig:", "rig", "tripo_"):
        if name.lower().startswith(prefix):
            name = name[len(prefix):]
            break
    return "".join(c for c in name.lower() if c.isalnum())


def match_bone(name: str) -> Optional[int]:
    n = normalise_name(name)
    for alias, bone in ALL_ALIASES:
        if n == alias or alias in n:
            return BONE_INDEX[bone]
    return None


def node_matrices(doc: Dict[str, Any], overrides: Optional[Dict[int, Tuple[List[float], List[float], List[float]]]] = None) -> List[List[float]]:
    nodes = doc.get("nodes", [])
    out: List[Optional[List[float]]] = [None] * len(nodes)
    children = set()
    for node in nodes:
        for child in node.get("children", []):
            children.add(child)

    def visit(index: int, parent: List[float]) -> None:
        node = nodes[index]
        if overrides and index in overrides:
            t, r, s = overrides[index]
            local = mat_trs(t, r, s)
        elif "matrix" in node:
            local = list(node["matrix"])
        else:
            local = mat_trs(node.get("translation", [0, 0, 0]), node.get("rotation", [0, 0, 0, 1]), node.get("scale", [1, 1, 1]))
        world = mat_mul(parent, local)
        out[index] = world
        for child in node.get("children", []):
            visit(child, world)

    roots = []
    if doc.get("scenes"):
        scene = doc["scenes"][doc.get("scene", 0)]
        roots = list(scene.get("nodes", []))
    if not roots:
        roots = [i for i in range(len(nodes)) if i not in children]
    for root in roots:
        visit(root, mat_identity())
    for i in range(len(nodes)):
        if out[i] is None:
            out[i] = mat_identity()
    return [m for m in out if m is not None]  # type: ignore[misc]


def image_bytes(doc: Dict[str, Any], binary: bytes, source_dir: Path, index: int) -> Tuple[bytes, str]:
    image = doc["images"][index]
    if "bufferView" in image:
        view = doc["bufferViews"][image["bufferView"]]
        start = view.get("byteOffset", 0)
        data = binary[start:start + view["byteLength"]]
        mime = image.get("mimeType", "image/png")
    elif str(image.get("uri", "")).startswith("data:"):
        header, payload = image["uri"].split(",", 1)
        data = base64.b64decode(payload)
        mime = header.split(";")[0].split(":")[-1]
    else:
        data = (source_dir / image["uri"]).read_bytes()
        mime = "image/png" if data[:4] == b"\x89PNG" else "image/jpeg"
    ext = ".jpg" if "jpeg" in mime or "jpg" in mime else ".png"
    return data, ext


# --------------------------------------------------------------------- conversion

def pick_primitive(doc: Dict[str, Any], binary: bytes) -> Tuple[int, int]:
    skinned = [(ni, node) for ni, node in enumerate(doc.get("nodes", [])) if "skin" in node and "mesh" in node]
    if not skinned:
        raise ValueError("GLB has no skinned mesh node")
    node_index, node = skinned[0]
    mesh = doc["meshes"][node["mesh"]]
    if not mesh.get("primitives"):
        raise ValueError("GLB mesh has no primitives")
    return node_index, 0  # additional primitives are merged below


def build_parts(doc: Dict[str, Any], binary: bytes, source_dir: Path, tex_dir: Optional[Path],
                node_index: int, node_global: List[float], skin: Dict[str, Any],
                joint_to_bone: Dict[int, int], save_textures: bool) -> List[Dict[str, Any]]:
    node = doc["nodes"][node_index]
    mesh = doc["meshes"][node["mesh"]]
    parts: List[Dict[str, Any]] = []
    texture_names: Dict[int, str] = {}

    for pi, prim in enumerate(mesh["primitives"]):
        attrs = prim["attributes"]
        if "POSITION" not in attrs or "JOINTS_0" not in attrs or "WEIGHTS_0" not in attrs:
            continue
        positions = read_accessor(doc, binary, attrs["POSITION"])
        normals = read_accessor(doc, binary, attrs["NORMAL"]) if "NORMAL" in attrs else [[0, 1, 0]] * len(positions)
        uvs = read_accessor(doc, binary, attrs["TEXCOORD_0"]) if "TEXCOORD_0" in attrs else [[0, 0]] * len(positions)
        joints = read_accessor(doc, binary, attrs["JOINTS_0"])
        weights = read_accessor(doc, binary, attrs["WEIGHTS_0"])
        colours = read_accessor(doc, binary, attrs["COLOR_0"]) if "COLOR_0" in attrs else None
        if "indices" in prim:
            indices = [int(v) for v in read_accessor(doc, binary, prim["indices"])]
        else:
            indices = list(range(len(positions)))

        material = doc.get("materials", [{}])[prim.get("material", 0)] if "material" in prim else {}
        pbr = material.get("pbrMetallicRoughness", {})
        factor = pbr.get("baseColorFactor", [1, 1, 1, 1])
        color = to_argb(factor)
        texture = ""
        tex_index = pbr.get("baseColorTexture", {}).get("index")
        if tex_index is not None and tex_dir is not None:
            doc_texture = doc["textures"][tex_index]
            image_index = doc_texture.get("source")
            if image_index is not None:
                if image_index not in texture_names:
                    data, ext = image_bytes(doc, binary, source_dir, image_index)
                    name = f"tex_{image_index}{ext}"
                    if save_textures:
                        tex_dir.mkdir(parents=True, exist_ok=True)
                        (tex_dir / name).write_bytes(data)
                    texture_names[image_index] = name
                texture = texture_names[image_index]

        label = str(material.get("name", f"part_{pi}"))
        flags = FLAG_DOUBLE_SIDED if material.get("doubleSided") else 0
        lower = label.lower()
        if any(tag in lower for tag in ("skin", "face", "head", "body")):
            flags |= FLAG_SKIN
        if any(tag in lower for tag in ("hair", "helmet", "cap", "vest", "backpack", "pack", "visor", "glass")):
            flags |= FLAG_DETAIL

        vertices: List[float] = []
        for i in range(len(positions)):
            p = point(node_global, positions[i])
            # A rigid node transform keeps normals correct; a scaled one needs
            # the inverse transpose. Node transforms here are normally identity.
            n = direction(node_global, normals[i])
            try:
                inv = mat_invert(node_global)
                inv_t = [inv[c * 4 + r] for r in range(4) for c in range(4)]
                n = direction(inv_t, normals[i])
            except ValueError:
                pass
            j4 = (list(joints[i]) + [0, 0, 0, 0])[:4]
            w4 = (list(weights[i]) + [0, 0, 0, 0])[:4]
            influences: List[Tuple[int, float]] = []
            for j, w in zip(j4, w4):
                bone = joint_to_bone.get(int(j))
                if bone is not None and w > 0:
                    influences.append((bone, float(w)))
            influences.sort(key=lambda pair: pair[1], reverse=True)
            influences = influences[:4]
            total = sum(w for _, w in influences)
            if total <= 1e-8:
                influences = [(0, 1.0)]
                total = 1.0
            c = colours[i] if colours else [1, 1, 1, 1]
            linear_rgb = [srgb_to_linear(max(0.0, min(1.0, float(v)))) for v in c[:3]]
            vdata = [
                p[0], p[1], p[2], n[0], n[1], n[2],
                float(uvs[i][0]), float(uvs[i][1]),
                linear_rgb[0], linear_rgb[1], linear_rgb[2], 1.0,
            ]
            for k in range(4):
                bone, weight = influences[k] if k < len(influences) else (0, 0.0)
                vdata.extend([float(bone), weight / total])
            vertices.extend(vdata)

        parts.append({"name": label or f"part_{pi}", "texture": texture, "color": color,
                      "flags": flags, "vertices": vertices, "indices": indices})
    if not parts:
        raise ValueError("no skinned primitive with JOINTS_0/WEIGHTS_0 found")
    return parts


def sample_animation(doc: Dict[str, Any], binary: bytes, anim: Dict[str, Any], node_global: List[float],
                     joint_to_bone: Dict[int, int], inverse_bind: List[List[float]], fps: int,
                     max_frames: int, in_place: bool) -> Tuple[str, int, List[List[float]]]:
    channels: Dict[int, Dict[str, Dict[str, Any]]] = {}
    end = 0.0
    for channel in anim.get("channels", []):
        target = channel.get("target", {})
        node = target.get("node")
        path = target.get("path")
        if node is None or path not in ("translation", "rotation", "scale"):
            continue
        sampler_index = channel.get("sampler")
        if sampler_index is None:
            continue
        sampler = anim["samplers"][sampler_index]
        times = read_accessor(doc, binary, sampler["input"])
        values = read_accessor(doc, binary, sampler["output"])
        end = max(end, float(times[-1]) if times else 0.0)
        channels.setdefault(node, {})[path] = {"times": times, "values": values,
                                               "interp": sampler.get("interpolation", "LINEAR")}
    if not channels:
        return str(anim.get("name", "clip")), 0, []

    def sample(entry: Dict[str, Any], t: float) -> List[float]:
        times = entry["times"]
        values = entry["values"]
        if not times:
            return [0, 0, 0] if entry is not None else [0, 0, 0]
        lo, hi = 0, len(times) - 1
        if t <= times[0]:
            return list(values[0])
        if t >= times[-1]:
            return list(values[-1])
        while hi - lo > 1:
            mid = (lo + hi) // 2
            if times[mid] <= t:
                lo = mid
            else:
                hi = mid
        span = float(times[hi]) - float(times[lo])
        alpha = 0.0 if span <= 1e-9 else (t - float(times[lo])) / span
        if entry["interp"] == "STEP":
            alpha = 0.0
        a, b = values[lo], values[hi]
        if len(a) == 4:  # quaternion, nlerp is enough for sampled clips
            dot = sum(x * y for x, y in zip(a, b))
            if dot < 0:
                b = [-v for v in b]
            out = [x * (1 - alpha) + y * alpha for x, y in zip(a, b)]
            length = math.sqrt(sum(v * v for v in out)) or 1.0
            return [v / length for v in out]
        return [x * (1 - alpha) + y * alpha for x, y in zip(a, b)]

    frames_count = min(max_frames, max(1, int(math.ceil(end * fps)) + 1))
    frames: List[List[float]] = []
    for f in range(frames_count):
        t = min(end, f / fps)
        overrides: Dict[int, Tuple[List[float], List[float], List[float]]] = {}
        for node, paths in channels.items():
            base = doc["nodes"][node]
            tr = sample(paths["translation"], t) if "translation" in paths else list(base.get("translation", [0, 0, 0]))
            ro = sample(paths["rotation"], t) if "rotation" in paths else list(base.get("rotation", [0, 0, 0, 1]))
            sc = sample(paths["scale"], t) if "scale" in paths else list(base.get("scale", [1, 1, 1]))
            overrides[node] = (tr, ro, sc)
        posed = node_matrices(doc, overrides)
        frame: List[float] = []
        for joint, bone in sorted(joint_to_bone.items(), key=lambda pair: pair[1]):
            # joint_to_bone maps original glTF joint index → OEA bone.
            pose = posed[joint]
            m = mat_mul(mat_mul(node_global, pose), inverse_bind[bone])
            if in_place and bone == BONE_INDEX["PELVIS"]:
                m[12] = m[13] = m[14] = 0.0
            frame.extend(m)
        frames.append(frame)
    return str(anim.get("name", "clip")), frames_count, frames


def convert(args: argparse.Namespace) -> None:
    doc, binary = read_glb(args.glb)
    node_index, _ = pick_primitive(doc, binary)
    node = doc["nodes"][node_index]
    skin = doc["skins"][node["skin"]]
    joints = skin["joints"]
    ibm_accessor = skin.get("inverseBindMatrices")
    ibms = read_accessor(doc, binary, ibm_accessor) if ibm_accessor is not None else [mat_identity() for _ in joints]
    ibm_by_joint = {joint: ibms[i] for i, joint in enumerate(joints)}

    joint_to_bone: Dict[int, int] = {}
    used = set()
    for joint_index in joints:
        name = str(doc["nodes"][joint_index].get("name", ""))
        bone = match_bone(name)
        if bone is not None and bone not in used:
            joint_to_bone[joint_index] = bone
            used.add(bone)
    missing = [name for name in BONE_NAMES if BONE_INDEX[name] not in used]
    if missing:
        raise ValueError("skeleton is missing OEA bones: " + ", ".join(missing) +
                         "\nGLB joints: " + ", ".join(str(doc["nodes"][j].get("name", j)) for j in joints))

    globals_base = node_matrices(doc)
    node_global = globals_base[node_index]
    inv_node = mat_invert(node_global)
    # Fold the node transform into the inverse binds (glTF: node * joint * ibm).
    inverse_bind_model: List[List[float]] = []
    for bone in range(len(BONE_NAMES)):
        joint = next(j for j, b in joint_to_bone.items() if b == bone)
        inverse_bind_model.append(mat_mul(ibm_by_joint[joint], inv_node))

    parts = build_parts(doc, binary, args.glb.parent, args.textures, node_index, node_global,
                        skin, joint_to_bone, not args.no_textures)

    clips: List[Tuple[str, int, List[List[float]]]] = []
    if not args.no_animations:
        for anim in doc.get("animations", [])[:args.max_clips]:
            name, frames, matrices = sample_animation(doc, binary, anim, node_global, joint_to_bone,
                                                      inverse_bind_model, args.fps, args.max_frames, args.in_place)
            if frames:
                clips.append((name, frames, matrices))

    # Normalise to OEA scale. S = T * Scale, and all skin matrices transform as S * M * S^-1.
    points = [parts[0]["vertices"][i:i + 3] for part in parts for i in range(0, len(part["vertices"]), 20)]
    if not points:
        raise ValueError("character has no vertices")
    lows = [min(p[axis] for p in points) for axis in range(3)]
    highs = [max(p[axis] for p in points) for axis in range(3)]
    height = highs[1] - lows[1]
    if height <= 1e-6:
        raise ValueError("character has zero height")
    scale = args.height / height
    sx = -(lows[0] + highs[0]) * 0.5 * scale
    sy = -lows[1] * scale
    sz = -(lows[2] + highs[2]) * 0.5 * scale
    S = mat_identity()
    S[0] = S[5] = S[10] = scale
    S[12], S[13], S[14] = sx, sy, sz
    inv_s = mat_invert(S)
    for part in parts:
        for i in range(0, len(part["vertices"]), 20):
            p = point(S, part["vertices"][i:i + 3])
            part["vertices"][i:i + 3] = p
    inverse_bind = [mat_mul(mat_mul(S, ibm), inv_s) for ibm in inverse_bind_model]
    scaled_clips: List[Tuple[str, int, List[List[float]]]] = []
    for name, frames, matrices in clips:
        scaled = [mat_mul(mat_mul(S, m), inv_s) for m in matrices]
        scaled_clips.append((name, frames, scaled))

    write_oeac(args.out, inverse_bind, parts, scaled_clips)


def write_string(out: bytearray, value: str) -> None:
    raw = value.encode("utf-8")[:255]
    out.append(len(raw))
    out.extend(raw)


def write_oeac(path: Path, inverse_bind: List[List[float]], parts: List[Dict[str, Any]],
               clips: List[Tuple[str, int, List[List[float]]]]) -> None:
    out = bytearray(MAGIC)
    out.extend(struct.pack("<HHHH", VERSION, len(BONE_NAMES), len(parts), len(clips)))
    for i in range(len(BONE_NAMES)):
        out.extend(struct.pack("<h", BONE_PARENTS[i]))
        out.extend(struct.pack("<16f", *inverse_bind[i]))
    for part in parts:
        write_string(out, part["name"])
        write_string(out, part["texture"])
        out.extend(struct.pack("<II", part["color"], part["flags"]))
        out.extend(struct.pack("<II", len(part["vertices"]) // 20, len(part["indices"])))
        out.extend(struct.pack("<%df" % len(part["vertices"]), *part["vertices"]))
        out.extend(struct.pack("<%dI" % len(part["indices"]), *part["indices"]))
    for name, frames, matrices in clips:
        write_string(out, name)
        out.extend(struct.pack("<II", frames, len(BONE_NAMES)))
        for matrix in matrices:
            out.extend(struct.pack("<16f", *matrix))
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(out)
    size = len(out) / 1024.0
    verts = sum(len(p["vertices"]) // 20 for p in parts)
    print(f"wrote {path} ({size:.0f} KiB, {len(parts)} parts, {verts} vertices, {len(clips)} clips)")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("glb", type=Path, help="rigged GLB downloaded from Tripo3D")
    parser.add_argument("--out", type=Path, required=True, help="destination .oeac file")
    parser.add_argument("--textures", type=Path, help="folder for extracted base-colour textures")
    parser.add_argument("--height", type=float, default=1.8, help="target character height in metres (default 1.8)")
    parser.add_argument("--fps", type=int, default=24, help="animation sample rate (default 24)")
    parser.add_argument("--max-frames", type=int, default=120, help="max frames per clip (default 120)")
    parser.add_argument("--max-clips", type=int, default=8, help="max clips to keep (default 8)")
    parser.add_argument("--in-place", action="store_true", help="remove root translation from animation clips")
    parser.add_argument("--no-animations", action="store_true", help="skip animation sampling")
    parser.add_argument("--no-textures", action="store_true", help="skip texture extraction")
    args = parser.parse_args()
    if args.textures is None:
        args.textures = args.out.parent / (args.out.stem + "_textures")
    try:
        convert(args)
    except (ValueError, KeyError, OSError, struct.error, json.JSONDecodeError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
