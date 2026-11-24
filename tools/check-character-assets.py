#!/usr/bin/env python3
"""Validates the shipped Send Off character assets the way the engine reads them.

`CharacterAssetLoader` is strict about the `.oeac` it accepts: it demands
`OEAC` magic, version 1, exactly 19 bones, a stride of 20 floats per vertex,
bone indices in range and weights that sum to 1. A file that fails any of that
throws at load and the game silently falls back to the procedural rig — the
character still draws, which is why such a bug survives a play test.

This checks every bundled character plus the things that are easy to get wrong
by hand and invisible until you look closely:

* bone indices within 0..18 and four weights normalised to 1;
* every referenced texture actually present on disk;
* every shell wound outward, so a back-face-culling renderer shows it;
* the mesh actually sits in the bind pose — the fault that started the anatomy
  rewrite was a mesh drawn 24 cm from its own elbow;
* the joints bend at the right place, checked by posing the skeleton and
  measuring where the hand and foot end up.

Usage:
  python3 tools/check-character-assets.py
"""

from __future__ import annotations

import json
import math
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "oea-engine/src/main/assets"
sys.path.insert(0, str(ROOT / "tools/tripo"))

import render_oeac as R  # noqa: E402

BONES = 19
STRIDE = 20
WEIGHT_AT = 16
BONE_AT = 12
EPS = 1e-3


def fail(msg: str) -> None:
    print(f"FAIL: {msg}")
    raise SystemExit(1)


def shells(idx: list[int]) -> list[list[int]]:
    parent = list(range(max(idx) + 1))

    def find(a: int) -> int:
        while parent[a] != a:
            parent[a] = parent[parent[a]]
            a = parent[a]
        return a

    for t in range(0, len(idx), 3):
        a, b, c = idx[t], idx[t + 1], idx[t + 2]
        for x, y in ((a, b), (b, c)):
            ra, rb = find(x), find(y)
            if ra != rb:
                parent[rb] = ra
    groups: dict[int, list[int]] = {}
    for t in range(0, len(idx), 3):
        groups.setdefault(find(idx[t]), []).extend((idx[t], idx[t + 1], idx[t + 2]))
    return list(groups.values())


def check_part(name: str, part: dict, textures: Path) -> None:
    verts, idx = part["verts"], part["idx"]
    if len(verts) % STRIDE:
        fail(f"{name}/{part['name']}: {len(verts)} floats is not a multiple of {STRIDE}")
    if len(idx) % 3:
        fail(f"{name}/{part['name']}: index count {len(idx)} is not a triangle list")
    if not idx:
        fail(f"{name}/{part['name']}: empty part")
    if max(idx) * STRIDE >= len(verts):
        fail(f"{name}/{part['name']}: index {max(idx)} past the end of the vertex buffer")

    count = len(verts) // STRIDE
    for i in range(count):
        o = i * STRIDE
        pos = (verts[o], verts[o + 1], verts[o + 2])
        if not all(math.isfinite(v) for v in pos):
            fail(f"{name}/{part['name']}: vertex {i} is not finite")
        for k in range(4):
            bone = verts[o + BONE_AT + k]
            weight = verts[o + WEIGHT_AT + k]
            if bone < 0 or bone > BONES - 1:
                fail(f"{name}/{part['name']}: vertex {i} bone id {bone} out of range")
            if abs(bone - round(bone)) > 1e-6:
                fail(f"{name}/{part['name']}: vertex {i} bone id {bone} is not integral")
            if weight < -EPS or weight > 1 + EPS:
                fail(f"{name}/{part['name']}: vertex {i} weight {weight:.3f} out of range")
        total = sum(verts[o + WEIGHT_AT + k] for k in range(4))
        if abs(total - 1.0) > 1e-2:
            fail(f"{name}/{part['name']}: vertex {i} weights sum to {total:.3f}")

    for group in shells(idx):
        centre = [0.0, 0.0, 0.0]
        uniq = set(group)
        for v in uniq:
            centre[0] += verts[v * STRIDE]
            centre[1] += verts[v * STRIDE + 1]
            centre[2] += verts[v * STRIDE + 2]
        n = float(len(uniq))
        centre = [c / n for c in centre]
        score = 0.0
        for t in range(0, len(group), 3):
            pa = [verts[group[t + k] * STRIDE + j] for j, k in enumerate((0, 1, 2))]
            pb = [verts[group[t + 1] * STRIDE + j] for j in (0, 1, 2)]
            pc = [verts[group[t + 2] * STRIDE + j] for j in (0, 1, 2)]
            e1 = [pb[j] - pa[j] for j in range(3)]
            e2 = [pc[j] - pa[j] for j in range(3)]
            fn = (e1[1] * e2[2] - e1[2] * e2[1],
                  e1[2] * e2[0] - e1[0] * e2[2],
                  e1[0] * e2[1] - e1[1] * e2[0])
            mid = [(pa[j] + pb[j] + pc[j]) / 3.0 - centre[j] for j in range(3)]
            score += sum(fn[j] * mid[j] for j in range(3))
        if score < 0:
            fail(f"{name}/{part['name']}: a shell is wound inward "
                 f"({len(set(group))} vertices) — it is invisible with back-face culling")

    if part["tex"] and not (textures / part["tex"]).exists():
        fail(f"{name}/{part['name']}: texture {part['tex']} is missing from {textures}")


def check_bind(name: str, parts: list[dict], inverse_bind: list[list[float]]) -> None:
    """The mesh must be drawn around the joints its weights refer to."""
    if len(inverse_bind) != BONES:
        fail(f"{name}: {len(inverse_bind)} bones, expected {BONES}")
    bind_world = [R._mat_invert(m) for m in inverse_bind]
    joints = {n: bind_world[i][12:15] for i, n in enumerate(R.BONE)}

    def nearest(p, bone):
        """Mean distance from the vertices dominated by `bone` to its joint."""
        total, count = 0.0, 0
        for part in parts:
            verts = part["verts"]
            for i in range(len(verts) // STRIDE):
                o = i * STRIDE
                if round(verts[o + BONE_AT]) != bone or verts[o + WEIGHT_AT] < 0.6:
                    continue
                p3 = (verts[o], verts[o + 1], verts[o + 2])
                total += math.dist(p3, p)
                count += 1
        return total / count if count else 0.0

    # A bone owns the segment that *starts* at its joint, so a bone's vertices
    # hug that joint. If the mesh and the skeleton disagree the mean drifts down
    # the bone — the fault that started the rewrite put the elbow 24 cm off.
    # FORE_* is deliberately absent: the wrist bone owns no geometry here, the
    # whole hand is weighted to HAND_*, which is normal for a phone rig.
    for label in ("SHOULDER_L", "ARM_L", "HAND_L", "THIGH_L", "SHIN_L", "FOOT_L"):
        d = nearest(joints[label], R.BONE[label])
        if d > 0.30:
            fail(f"{name}: {label} geometry sits {d:.2f} m from its joint — "
                 f"the skeleton and the mesh disagree")
        if d == 0.0:
            fail(f"{name}: no vertices are dominated by {label} — "
                 f"the bone drives nothing and its limb will not bend")

    # Bone segment lengths, from the bind pose itself.
    for a, b, lo, hi in (("SHOULDER_L", "ARM_L", 0.24, 0.36),
                         ("ARM_L", "FORE_L", 0.20, 0.32),
                         ("THIGH_L", "SHIN_L", 0.36, 0.48),
                         ("SHIN_L", "FOOT_L", 0.36, 0.48)):
        d = math.dist(joints[a], joints[b])
        if not lo <= d <= hi:
            fail(f"{name}: {a}→{b} is {d:.3f} m, outside the human range {lo}–{hi}")


def check_pose(name: str, parts: list[dict], inverse_bind: list[list[float]]) -> None:
    """Poses the skeleton and measures where the extremities actually go."""
    def extents(mats):
        xs, ys = [], []
        for part in parts:
            for p in R.skinned_positions(part, mats):
                xs.append(p[0])
                ys.append(p[1])
        return min(xs), max(xs), min(ys), max(ys)

    bind = R.bone_matrices(inverse_bind, {})
    x0, x1, y0, y1 = extents(bind)
    height = y1 - y0
    if not 1.70 <= height <= 1.90:
        fail(f"{name}: bind-pose height is {height:.2f} m, expected about 1.80")
    if y0 < -0.06:
        fail(f"{name}: feet are {abs(y0):.3f} m below the floor")

    tpose = R.bone_matrices(inverse_bind, R.POSE_PRESETS["tpose"])
    tx0, tx1, _, _ = extents(tpose)
    if (tx1 - tx0) < (x1 - x0) * 1.6:
        fail(f"{name}: the T-pose barely widens the arms "
             f"({tx1 - tx0:.2f} m vs {x1 - x0:.2f} m at the sides) — "
             f"the shoulder weights are not driving the arms")

    crouch = R.bone_matrices(inverse_bind, R.POSE_PRESETS["crouch"])
    _, _, _, cy1 = extents(crouch)
    if cy1 > y1 - 0.06:
        fail(f"{name}: the crouch pose does not lower the head "
             f"({cy1:.2f} m vs {y1:.2f} m standing) — the leg weights are wrong")


def main() -> int:
    manifest_path = ASSETS / "characters/manifest.json"
    if not manifest_path.exists():
        fail(f"{manifest_path} is missing")
    manifest = json.loads(manifest_path.read_text())
    entries = manifest.get("characters") or []
    if len(entries) < 6:
        fail(f"manifest lists {len(entries)} characters, expected 6")

    seen = set()
    for entry in entries:
        name = entry["id"]
        if name in seen:
            fail(f"duplicate character id {name} in the manifest")
        seen.add(name)
        path = ASSETS / entry["asset"]
        if not path.exists():
            fail(f"{name}: asset {path} is missing")
        parts, inverse_bind = R.read_oeac(path)
        if not parts:
            fail(f"{name}: asset has no meshes")
        textures = ASSETS / entry["textures"]
        for part in parts:
            check_part(name, part, textures)
        check_bind(name, parts, inverse_bind)
        check_pose(name, parts, inverse_bind)
        tris = sum(len(p["idx"]) // 3 for p in parts)
        if tris > 20_000:
            fail(f"{name}: {tris} triangles is too heavy for a phone character")
        print(f"  {name:11s} {len(parts)} parts  {tris:5d} tris  bind + poses ok")

    print(f"CHARACTER ASSETS OK — {len(entries)} characters, 19 bones, "
          f"normalised weights, outward winding, poses behave")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
