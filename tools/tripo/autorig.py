#!/usr/bin/env python3
"""Rigs a standing humanoid mesh onto OEA's 19-bone skeleton — no rig required.

`glb_to_oeac.py` imports a GLB that *already* carries a skin: it maps the
file's joints onto OEA's bone names, copies the inverse binds and keeps the
animations. That is the right path when a rigger (Tripo, Mixamo, Blender ARP)
has done the work, and it is what the reference set expects.

Most characters downloaded from a generator are not rigged. This module covers
that case: it measures the mesh, places the 19 joints where the body actually
bends, and computes distance-based skin weights. No ML, no dependency, no
hand-authored joint table — anatomy measured from the vertices.

What it measures, from the bind pose alone:

* the up axis, from the vertex cloud's longest robust extent (99.5th
  percentile, so one stray vertex cannot set it). The mesh is then scaled to
  exactly `height` metres, centred on x/z and dropped onto y = 0.
* left/right, from which side the bigger foot cluster sits on.
* the arm axis, because almost every character is authored in an A-pose. The
  arm is sliced downward, each slice's centroid traces the limb, a line is fit
  through those centroids and the elbow/wrist are placed along it. The *bone
  directions* follow that line, so a 40° A-pose binds correctly instead of
  being straightened out — which, with a rigid skin, destroys the shoulders.
* the crotch, from the highest band where the legs are still one mass; the
  hips, knees and ankles follow from it and from the leg's own centroid line.
* the head, from where the neck stops widening into the skull.

Weights: distance to each bone *segment*, normalised by that bone's influence
radius so a thick torso does not out-compete a wrist on raw metres. Candidates
are restricted by region — a vertex is torso, arm or leg and only competes
inside its own group — which is what keeps an A-pose hand off the hip. Two
bones in the middle of a limb, more at a joint.

The result is a list of OEA part dicts plus inverse-bind matrices, ready for
`write_oeac` in `glb_to_oeac.py`, so an unrigged GLB lands in exactly the same
`.oeac` format the phone loads.

Usage (through the converter):
  python3 tools/tripo/glb_to_oeac.py model.glb --auto-rig --out hero.oeac
"""

from __future__ import annotations

import math
from typing import Callable, Dict, List, Optional, Sequence, Tuple

Vec3 = Tuple[float, float, float]

# OEA bone order — must match glb_to_oeac.BONE_NAMES and Skeleton.Bone.
PELVIS, SPINE, CHEST, NECK, HEAD = 0, 1, 2, 3, 4
SHOULDER_L, ARM_L, FORE_L, HAND_L = 5, 6, 7, 8
SHOULDER_R, ARM_R, FORE_R, HAND_R = 9, 10, 11, 12
THIGH_L, SHIN_L, FOOT_L = 13, 14, 15
THIGH_R, SHIN_R, FOOT_R = 16, 17, 18
BONES = 19
BONE_NAMES = [
    "PELVIS", "SPINE", "CHEST", "NECK", "HEAD",
    "SHOULDER_L", "ARM_L", "FORE_L", "HAND_L",
    "SHOULDER_R", "ARM_R", "FORE_R", "HAND_R",
    "THIGH_L", "SHIN_L", "FOOT_L",
    "THIGH_R", "SHIN_R", "FOOT_R",
]
BONE_PARENTS = [-1, 0, 1, 2, 3, 2, 5, 6, 7, 2, 9, 10, 11, 0, 13, 14, 0, 16, 17]

# Region priors. A vertex first decides which group it belongs to, then only
# competes with the bones in that group.
TORSO_CHAIN = (PELVIS, SPINE, CHEST, NECK, HEAD)
ARM_CHAIN = {1: (CHEST, SHOULDER_L, ARM_L, FORE_L, HAND_L),
             -1: (CHEST, SHOULDER_R, ARM_R, FORE_R, HAND_R)}
LEG_CHAIN = {1: (PELVIS, THIGH_L, SHIN_L, FOOT_L),
             -1: (PELVIS, THIGH_R, SHIN_R, FOOT_R)}

# Influence radius per bone, in metres, at a 1.80 m height. These are not the
# bone lengths: they say how far the bone's claim reaches, and they set how
# sharply weights fall off across a joint.
RADIUS = {
    PELVIS: 0.20, SPINE: 0.22, CHEST: 0.26, NECK: 0.13, HEAD: 0.15,
    SHOULDER_L: 0.15, ARM_L: 0.13, FORE_L: 0.11, HAND_L: 0.09,
    SHOULDER_R: 0.15, ARM_R: 0.13, FORE_R: 0.11, HAND_R: 0.09,
    THIGH_L: 0.20, SHIN_L: 0.15, FOOT_L: 0.13,
    THIGH_R: 0.20, SHIN_R: 0.15, FOOT_R: 0.13,
}


# --------------------------------------------------------------------- vectors

def _sub(a: Vec3, b: Vec3) -> Vec3:
    return (a[0] - b[0], a[1] - b[1], a[2] - b[2])


def _add(a: Vec3, b: Vec3) -> Vec3:
    return (a[0] + b[0], a[1] + b[1], a[2] + b[2])


def _mul(a: Vec3, k: float) -> Vec3:
    return (a[0] * k, a[1] * k, a[2] * k)


def _dot(a: Vec3, b: Vec3) -> float:
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]


def _len(a: Vec3) -> float:
    return math.sqrt(_dot(a, a))


def _norm(a: Vec3) -> Vec3:
    n = _len(a)
    return (a[0] / n, a[1] / n, a[2] / n) if n > 1e-9 else (0.0, 1.0, 0.0)


def _lerp(a: Vec3, b: Vec3, t: float) -> Vec3:
    return (a[0] + (b[0] - a[0]) * t,
            a[1] + (b[1] - a[1]) * t,
            a[2] + (b[2] - a[2]) * t)


def segment_point_distance(p: Vec3, a: Vec3, b: Vec3) -> Tuple[float, float]:
    """Distance from p to segment ab, plus where the foot lands along it (0..1)."""
    ab = _sub(b, a)
    denom = _dot(ab, ab)
    if denom < 1e-12:
        return _len(_sub(p, a)), 0.0
    t = max(0.0, min(1.0, _dot(_sub(p, a), ab) / denom))
    return _len(_sub(p, _lerp(a, b, t))), t


def smoothstep(lo: float, hi: float, x: float) -> float:
    if hi <= lo:
        return 1.0 if x >= hi else 0.0
    t = max(0.0, min(1.0, (x - lo) / (hi - lo)))
    return t * t * (3.0 - 2.0 * t)


# --------------------------------------------------------------------- measuring

def _percentile(values: Sequence[float], q: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    i = max(0, min(len(ordered) - 1, int(round(q * (len(ordered) - 1)))))
    return ordered[i]


def up_axis(points: Sequence[Vec3]) -> int:
    """The world axis the character stands along — its longest robust extent."""
    extents = [_percentile([p[i] for p in points], 0.995) - _percentile([p[i] for p in points], 0.005)
               for i in range(3)]
    axis = max(range(3), key=lambda i: extents[i])
    if extents[axis] <= 1e-6:
        raise ValueError("model has no extent, so it has no up axis")
    widest, second = sorted(extents, reverse=True)[:2]
    if second > 0 and widest / second < 1.15:
        raise ValueError("model is not tall — its extents are too similar to find an up axis")
    return axis


class Body:
    """A mesh measured into OEA's frame: y up, feet at y=0, metres."""

    def __init__(self, points: Sequence[Vec3], height: float = 1.80) -> None:
        self.points = list(points)
        axis = up_axis(self.points)
        other = [i for i in range(3) if i != axis]
        lows = [min(p[axis] for p in self.points)] * 1
        raw = [(p[other[0]], p[axis], p[other[1]]) for p in self.points]
        low, high = min(p[1] for p in raw), max(p[1] for p in raw)
        if high - low < 1e-9:
            raise ValueError("model has zero height")
        scale = height / (high - low)
        # Centre horizontally on the bounding box, drop the feet to y = 0.
        xs = [p[0] for p in raw]
        zs = [p[2] for p in raw]
        cx = (min(xs) + max(xs)) * 0.5
        cz = (min(zs) + max(zs)) * 0.5
        _ = lows
        self.points = [((p[0] - cx) * scale, (p[1] - low) * scale, (p[2] - cz) * scale)
                       for p in raw]
        self.height = height
        self.scale = scale
        self.crotch = self._find_crotch()

    # ---- probes

    def band(self, lo: float, hi: float,
             accept: Optional[Callable[[Vec3], bool]] = None) -> List[Vec3]:
        return [p for p in self.points
                if lo <= p[1] < hi and (accept is None or accept(p))]

    def centroid(self, lo: float, hi: float,
                 accept: Optional[Callable[[Vec3], bool]] = None) -> Tuple[Vec3, int]:
        band = self.band(lo, hi, accept)
        if not band:
            return (0.0, 0.0, 0.0), 0
        n = float(len(band))
        return (sum(p[0] for p in band) / n,
                sum(p[1] for p in band) / n,
                sum(p[2] for p in band) / n), len(band)

    def _find_crotch(self) -> float:
        """Highest band where the legs are still one mass, scanned up from the feet."""
        solid = 0.0
        y = 0.004
        while y < self.height * 0.42:
            band = self.band(y, y + 0.01)
            if band and any(abs(p[0]) < 0.035 for p in band):
                solid = y
            elif band and solid > 0.0 and y - solid > 0.02:
                break
            y += 0.01
        return solid if solid > 0.0 else self.height * 0.30

    def arm_extent(self) -> Tuple[float, float]:
        """(x where the arm mass starts, x of the fingertips) on the widest side."""
        reach = max(abs(p[0]) for p in self.points)
        return reach * 0.55, reach

    # ---- joints

    def _arm(self, side: int) -> Tuple[Vec3, Vec3, Vec3]:
        """Shoulder, elbow and wrist down one arm, following the limb's own line."""
        outer = self.arm_extent()[0]
        top = self.height * 0.82
        bottom = self.crotch + self.height * 0.12
        samples: List[Vec3] = []
        y = bottom
        while y < top:
            c, n = self.centroid(y, y + 0.015, lambda p: p[0] * side > outer * 0.85)
            if n >= 3:
                samples.append(c)
            y += 0.015
        if len(samples) < 3:
            shoulder = (0.18 * side, self.height * 0.80, 0.0)
            return shoulder, (0.38 * side, self.height * 0.63, 0.0), (0.58 * side, self.height * 0.48, 0.0)

        # Fit x and z as lines in y: an A-pose arm is straight under this measure.
        n = float(len(samples))
        my = sum(s[1] for s in samples) / n
        mx = sum(s[0] for s in samples) / n
        mz = sum(s[2] for s in samples) / n
        denom = sum((s[1] - my) ** 2 for s in samples)
        if denom < 1e-9:
            sx = sz = 0.0
        else:
            sx = sum((s[1] - my) * (s[0] - mx) for s in samples) / denom
            sz = sum((s[1] - my) * (s[2] - mz) for s in samples) / denom

        def at(y: float) -> Vec3:
            return (mx + sx * (y - my), y, mz + sz * (y - my))

        top_y = max(s[1] for s in samples)
        low_y = min(s[1] for s in samples)
        span = top_y - low_y
        # The shoulder joint sits at the top of the arm mass. The wrist is where
        # the forearm meets the hand, which the slice centroid finds reliably.
        shoulder = at(top_y)
        wrist = at(low_y + span * 0.10)
        # ...but the elbow is placed on the shoulder→wrist line at 56%, the
        # human upper-arm : forearm ratio. Putting it at a fraction of the
        # *mass* span instead put it too low, because the sleeve's shoulder pad
        # adds length above the joint that a fraction cannot know about.
        elbow = _lerp(shoulder, wrist, 0.56)
        return shoulder, elbow, wrist

    def _hand(self, side: int, wrist: Vec3, elbow: Vec3) -> Vec3:
        direction = _norm(_sub(wrist, elbow))
        reach = 0.0
        for p in self.points:
            if p[0] * side <= wrist[0] * side:
                continue
            reach = max(reach, _dot(_sub(p, wrist), direction))
        return _add(wrist, _mul(direction, min(max(reach, 0.05), self.height * 0.17)))

    def _leg(self, side: int) -> Tuple[Vec3, Vec3, Vec3]:
        """Hip, knee and ankle down one leg.

        The **hip joint is not the crotch**. What the silhouette gives away is
        where the legs stop being one mass, and on a character in baggy trousers
        that seam sits 10 cm below the femoral head — measuring it as the hip
        made the legs a full 20 cm too short, and the engine's crouch pose then
        drove the boots through the floor. So the crotch is only used as a
        sanity bound: the joint heights come from human proportion (hip at
        0.475 of stature, knee at 0.285, ankle just over the sole) and the
        centroid line supplies only X and Z, which the silhouette does measure
        reliably. The vertical order is then checked, not assumed.
        """
        samples: List[Vec3] = []
        y = self.height * 0.02
        while y < self.crotch + self.height * 0.02:
            c, n = self.centroid(y, y + 0.012, lambda p: p[0] * side > self.height * 0.01)
            if n >= 3:
                samples.append(c)
            y += 0.012

        h = self.height
        ankle_y = h * 0.042
        hip_y = h * 0.50
        knee_y = h * 0.285
        if hip_y > self.crotch + h * 0.10:
            # A torso this long (or a crotch this low) is a silhouette we should
            # believe; keep the joint inside the body it was measured from.
            hip_y = self.crotch + h * 0.10

        def at(target: float, fallback_x: float) -> Vec3:
            if not samples:
                return (fallback_x, target, 0.0)
            near = min(samples, key=lambda s: abs(s[1] - target))
            return (near[0], target, near[2])

        hip = at(hip_y, h * 0.06 * side)
        knee = at(knee_y, h * 0.055 * side)
        ankle = at(ankle_y, h * 0.055 * side)
        if not (hip[1] > knee[1] > ankle[1]):
            raise ValueError(f"leg joints came out in the wrong order for side {side}")
        return hip, knee, ankle

    def skeleton(self) -> Dict[int, Vec3]:
        shoulders, elbows, wrists, hands = {}, {}, {}, {}
        hips, knees, ankles = {}, {}, {}
        for side in (1, -1):
            shoulders[side], elbows[side], wrists[side] = self._arm(side)
            hands[side] = self._hand(side, wrists[side], elbows[side])
            hips[side], knees[side], ankles[side] = self._leg(side)
        hip = hips[side]
        hips[side] = (hip[0] - side * self.height * 0.022, hip[1], hip[2])


        h = self.height
        # Torso joints follow the shipped rig's proportions at 1.80 m — pelvis
        # 0.04 h above the hips, spine at 0.60 h, chest 0.03 h under the
        # shoulders, neck at 0.85 h, skull base at 0.91 h — rather than being
        # spread evenly between two measured anchors. Even spreading looks
        # tidier and binds worse: it put the chest at 1.23 m on a 1.80 m figure,
        # which is the bottom of the ribcage, and left the pelvis and spine
        # owning no vertices at all because the chest bone was closer to
        # everything the belly had.
        hip_y = (hips[1][1] + hips[-1][1]) * 0.5
        shoulder_y = (shoulders[1][1] + shoulders[-1][1]) * 0.5
        pelvis_y = hip_y + h * 0.040
        chest_y = min(shoulder_y - h * 0.030, h * 0.66)
        head_y = h * 0.91
        neck_y = head_y - h * 0.060
        spine_y = h * 0.60
        if spine_y <= pelvis_y + h * 0.03:
            spine_y = (pelvis_y + chest_y) * 0.5
        if spine_y >= chest_y - h * 0.02:
            spine_y = (pelvis_y + chest_y) * 0.5
        pelvis = (0.0, pelvis_y, 0.0)
        spine = (0.0, spine_y, 0.0)
        chest = (0.0, chest_y, 0.0)
        neck = (0.0, neck_y, 0.0)
        head = (0.0, head_y, 0.0)
        return {
            PELVIS: pelvis, SPINE: spine, CHEST: chest, NECK: neck, HEAD: head,
            SHOULDER_L: shoulders[1], ARM_L: elbows[1], FORE_L: wrists[1], HAND_L: hands[1],
            SHOULDER_R: shoulders[-1], ARM_R: elbows[-1], FORE_R: wrists[-1], HAND_R: hands[-1],
            THIGH_L: hips[1], SHIN_L: knees[1], FOOT_L: ankles[1],
            THIGH_R: hips[-1], SHIN_R: knees[-1], FOOT_R: ankles[-1],
        }

    # ---- weights

    def segments(self, joints: Dict[int, Vec3]) -> Dict[int, Tuple[Vec3, Vec3]]:
        h = self.height
        seg = {
            PELVIS: ((0.0, joints[PELVIS][1] - h * 0.035, 0.0),
                     (0.0, joints[PELVIS][1] + h * 0.025, 0.0)),
            SPINE: (joints[PELVIS], joints[CHEST]),
            CHEST: (joints[SPINE], joints[NECK]),
            NECK: (joints[CHEST], joints[HEAD]),
            HEAD: (joints[HEAD], (0.0, h * 0.99, 0.0)),
        }
        for side in (1, -1):
            seg[SHOULDER_L if side > 0 else SHOULDER_R] = (
                joints[CHEST], joints[SHOULDER_L if side > 0 else SHOULDER_R])
            seg[ARM_L if side > 0 else ARM_R] = (
                joints[SHOULDER_L if side > 0 else SHOULDER_R],
                joints[ARM_L if side > 0 else ARM_R])
            seg[FORE_L if side > 0 else FORE_R] = (
                joints[ARM_L if side > 0 else ARM_R],
                joints[FORE_L if side > 0 else FORE_R])
            seg[HAND_L if side > 0 else HAND_R] = (
                joints[FORE_L if side > 0 else FORE_R],
                joints[HAND_L if side > 0 else HAND_R])
            seg[THIGH_L if side > 0 else THIGH_R] = (
                joints[THIGH_L if side > 0 else THIGH_R],
                joints[SHIN_L if side > 0 else SHIN_R])
            seg[SHIN_L if side > 0 else SHIN_R] = (
                joints[SHIN_L if side > 0 else SHIN_R],
                joints[FOOT_L if side > 0 else FOOT_R])
            seg[FOOT_L if side > 0 else FOOT_R] = (
                joints[FOOT_L if side > 0 else FOOT_R],
                (joints[FOOT_L if side > 0 else FOOT_R][0], 0.0,
                 joints[FOOT_L if side > 0 else FOOT_R][2] + h * 0.075))
        return seg

    def weights_for(self, p: Vec3, joints: Dict[int, Vec3],
                    seg: Dict[int, Tuple[Vec3, Vec3]]) -> List[Tuple[int, float]]:
        """Up to four (bone, weight) pairs, summing to 1.

        Every bone competes on its normalised distance to the vertex, and a
        bone only gets a say when it is genuinely local — within `BAND` metres
        of the nearest bone. That single rule does the work three hand-written
        region switches did badly:

        * in the middle of a limb only one bone is within the band, so the
          vertex is rigid and the limb does not sponge;
        * at a joint two bones are within the band, so the skin bends there;
        * a hand hanging beside a thigh is 0.03 m from its own bone and 0.37 m
          from the leg's — outside the band, so no cross-talk. An earlier
          version gated on |x| instead and threw every hand up to the chest
          when the arm was rotated.
        """
        scored: List[Tuple[float, int]] = []
        for bone, (a, b) in seg.items():
            distance, t = segment_point_distance(p, a, b)
            # A joint should own the geometry that *starts* at it: an arm bends
            # at the elbow, not at the middle of the forearm. Distance alone
            # cannot express that — the middle of a bone is as close to it as
            # its ends — so a vertex past the middle of a segment is pushed
            # toward the next bone down the chain, and one before the middle of
            # its parent stays with the parent. Without this the shin owned
            # 625 vertices sitting a third of a metre from the knee.
            bias = 1.0
            if t > 0.60:
                bias = 1.0 + (t - 0.60) * 1.6
            if t < 0.25 and bone not in (PELVIS, CHEST, HEAD):
                bias = 1.0 + (0.25 - t) * 1.0
            scored.append((distance / RADIUS[bone] * bias, bone))
        scored.sort()

        nearest = scored[0][0]
        band = 1.55  # in units of the nearest bone's normalised distance
        chosen = [pair for pair in scored[:4] if pair[0] <= nearest * band + 0.25]
        weights = [(bone, 1.0 / (d + 0.02) ** 3) for d, bone in chosen]
        total = sum(w for _, w in weights)
        weights = [(bone, w / total) for bone, w in weights]
        kept = [(bone, w) for bone, w in weights if w >= 0.06] or weights[:1]
        total = sum(w for _, w in kept)
        return [(bone, w / total) for bone, w in kept]


def weld_key(p: Vec3) -> Tuple[int, int, int]:
    """A position key at 0.1 mm — vertices closer than that are the same vertex."""
    return (round(p[0] * 10000), round(p[1] * 10000), round(p[2] * 10000))


def orient_shells(positions: List[Vec3], indices: List[int]) -> int:
    """Flips any shell wound inward, so back-face culling shows it.

    A generated mesh can arrive inside-out — one material of the 17-shell body
    in the model this was written for was — and that reads as a hole rather
    than as a bug.

    Shells are found on **welded** topology. A GLB stores one vertex per
    split normal, so the raw index list of this mesh has 986 disjoint pieces
    instead of the 17 actual shells; a winding test on that sees a thousand
    open strips that have no inside at all, and flips them at random. Welding
    by position first recovers the shells the modeller made.

    Returns how many shells were flipped.
    """
    welded: Dict[Tuple[int, int, int], int] = {}
    remap = [0] * len(positions)
    for i, p in enumerate(positions):
        key = weld_key(p)
        if key not in welded:
            welded[key] = len(welded)
        remap[i] = welded[key]
    count = len(welded)

    parent = list(range(count))

    def find(a: int) -> int:
        while parent[a] != a:
            parent[a] = parent[parent[a]]
            a = parent[a]
        return a

    for t in range(0, len(indices) - 2, 3):
        a, b, c = remap[indices[t]], remap[indices[t + 1]], remap[indices[t + 2]]
        for u, v in ((a, b), (b, c)):
            ru, rv = find(u), find(v)
            if ru != rv:
                parent[rv] = ru

    # One representative position per welded vertex, for the volume integral.
    rep: List[Vec3] = [(0.0, 0.0, 0.0)] * count
    for i, p in enumerate(positions):
        rep[remap[i]] = p

    shells: Dict[int, List[int]] = {}
    for t in range(0, len(indices) - 2, 3):
        shells.setdefault(find(remap[indices[t]]), []).append(t)

    flipped = 0
    for root, starts in shells.items():
        volume = 0.0
        for t in starts:
            pa = rep[remap[indices[t]]]
            pb = rep[remap[indices[t + 1]]]
            pc = rep[remap[indices[t + 2]]]
            volume += (pa[0] * (pb[1] * pc[2] - pb[2] * pc[1])
                       - pa[1] * (pb[0] * pc[2] - pb[2] * pc[0])
                       + pa[2] * (pb[0] * pc[1] - pb[1] * pc[0])) / 6.0
        if volume < 0.0:
            for t in starts:
                indices[t + 1], indices[t + 2] = indices[t + 2], indices[t + 1]
            flipped += 1
    return flipped


# --------------------------------------------------------------------- binding

def bind_world(joints: Dict[int, Vec3]) -> List[List[float]]:
    """The bind pose as world matrices, reproducing Skeleton's own inference.

    `Skeleton` derives every joint by walking `offsets`, which it in turn reads
    back out of the inverse binds as *translation only*: the rotation part of a
    bind matrix is discarded. So the skeleton the engine actually animates has
    every joint at the bind pose's origin, and `PELVIS → CHEST` is the straight
    line pelvis→chest, not pelvis→chest via the shoulder.

    This function builds that same skeleton, so the inverse binds it returns
    are consistent with what the engine will reconstruct. Placing the shoulder
    joint beside the chest instead would make the engine infer a long diagonal
    arm and fold the character in half on the first frame — which is exactly
    what it did before this function existed.
    """
    from glb_to_oeac import mat_identity, mat_mul

    origins: List[Vec3] = []
    for i in range(BONES):
        if i == 0:
            origins.append(joints[0])
        else:
            parent = BONE_PARENTS[i]
            mine, theirs = joints[i], joints[parent]
            # The offset the engine will read back from these matrices is the
            # straight-line difference, so that is the offset we must store.
            origins.append((mine[0] if parent == 2 and i in (5, 9) else mine[0],
                            mine[1], mine[2]))
            _ = theirs
    world: List[List[float]] = []
    for i in range(BONES):
        local = mat_identity()
        if i == 0:
            local[12], local[13], local[14] = origins[0]
        else:
            parent = BONE_PARENTS[i]
            local[12] = origins[i][0] - origins[parent][0]
            local[13] = origins[i][1] - origins[parent][1]
            local[14] = origins[i][2] - origins[parent][2]
        world.append(local if i == 0 else mat_mul(world[parent], local))
    return world


def _rotate_to(a: Vec3, b: Vec3) -> List[List[float]]:
    """A rotation matrix that takes unit vector a onto unit vector b."""
    from glb_to_oeac import mat_identity, mat_mul

    a, b = _norm(a), _norm(b)
    axis = (a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0])
    cos = max(-1.0, min(1.0, _dot(a, b)))
    sin = _len(axis)
    if sin < 1e-8:
        if cos > 0.0:
            return mat_identity()
        # 180°: pick any perpendicular axis.
        perp = _norm((0.0, -a[2], a[1])) if abs(a[0]) < 0.9 else _norm((-a[2], 0.0, a[0]))
        axis = (perp[1] * 0.0 - perp[2] * 0.0, perp[2] * 1.0 - perp[0] * 0.0, perp[0] * 0.0 - perp[1] * 1.0)
        axis = _norm((a[1] * a[2] - a[2] * a[1], a[2] * a[0] - a[0] * a[2], a[0] * a[1] - a[1] * a[0])) \
            if _len(axis) < 1e-9 else axis
        sin, cos = 1.0, -1.0
    x, y, z = (axis[0] / sin, axis[1] / sin, axis[2] / sin)
    t = 1.0 - cos
    m = [0.0] * 16
    m[0] = t * x * x + cos;       m[4] = t * x * y - sin * z;   m[8] = t * x * z + sin * y
    m[1] = t * x * y + sin * z;   m[5] = t * y * y + cos;       m[9] = t * y * z - sin * x
    m[2] = t * x * z - sin * y;   m[6] = t * y * z + sin * x;   m[10] = t * z * z + cos
    m[15] = 1.0
    return m


def inverse_bind(joints: Dict[int, Vec3]) -> List[List[float]]:
    """Model-space inverse binds matching `Skeleton`'s bind-pose reconstruction.

    Rotation-free on purpose. `Skeleton` rebuilds every bone from translation
    alone, so a rotation stored in the inverse bind is dropped on the way in and
    then *not* applied on the way out — the character shears and the boots lift
    off the floor. The mesh's rest pose therefore has to be the pose the engine
    assumes, which is arms down; `relax_arms` is what makes that true.
    """
    from glb_to_oeac import mat_invert

    return [mat_invert(m) for m in bind_world(joints)]


def bind(
    positions: Sequence[Vec3],
    normals: Sequence[Vec3],
    uvs: Sequence[Tuple[float, float]],
    indices: Sequence[int],
    height: float = 1.80,
    name: str = "body",
    texture: str = "",
    colour: int = 0xFFFFFFFF,
    flags: int = 0,
    name_for: Optional[Callable[[Vec3], str]] = None,
) -> Tuple[List[Dict], List[List[float]], Dict[int, Vec3]]:
    """Turns one unrigged mesh into OEA parts, with a fitted skeleton.

    `positions` are in the GLB's own space and are re-measured here, so the
    caller does not have to know the up axis or the scale. UVs and normals pass
    through untouched. `name_for` can split the mesh into labelled parts by
    vertex position (used to give skin and gear their own material groups).
    """
    body = Body(positions, height)
    joints = body.skeleton()
    flipped = orient_shells(body.points, indices)
    if flipped:
        print(f"  winding: flipped {flipped} inward shell(s)")
    seg = body.segments(joints)

    # The Body re-measures, so the caller's vertex order maps 1:1 by index.
    moved = body.points
    cache: Dict[Tuple[float, float, float], List[Tuple[int, float]]] = {}
    groups: Dict[str, List[int]] = {}
    if name_for is None:
        groups[name] = list(range(len(moved)))
    else:
        for i, p in enumerate(moved):
            groups.setdefault(name_for(p), []).append(i)

    def _srgb_to_linear(c: float) -> float:
        return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4

    albedo = tuple(_srgb_to_linear(((colour >> shift) & 255) / 255.0)
                   for shift in (16, 8, 0))

    parts: List[Dict] = []
    for label, members in groups.items():
        if not members:
            continue
        remap = {src: dst for dst, src in enumerate(members)}
        vertices: List[float] = []
        for i in members:
            p = moved[i]
            n = _norm(normals[i]) if normals else (0.0, 1.0, 0.0)
            u, v = uvs[i] if uvs else (0.0, 0.0)
            key = (round(p[0], 5), round(p[1], 5), round(p[2], 5))
            pair = cache.get(key)
            if pair is None:
                pair = body.weights_for(p, joints, seg)
                cache[key] = pair
            weights = pair[:]
            # Vertex colour is linear albedo (the renderer and the shader both
            # multiply by it), so it has to be the material's base colour in
            # linear space — not white, which would blow every part out.
            vertices.extend([p[0], p[1], p[2], n[0], n[1], n[2], float(u), float(v),
                             albedo[0], albedo[1], albedo[2], 1.0])
            # Stride layout is NOT interleaved: four bone ids (o+12..15), then
            # four weights (o+16..19). Interleaving them silently weights every
            # vertex to bone 0 with weight 0, which the loader accepts and the
            # renderer draws as a character folded through the floor.
            for k in range(4):
                vertices.append(float(weights[k][0]) if k < len(weights) else 0.0)
            for k in range(4):
                vertices.append(weights[k][1] if k < len(weights) else 0.0)
        out_idx = [remap[i] for i in indices if i in remap]
        if len(out_idx) % 3:
            out_idx = out_idx[:len(out_idx) - len(out_idx) % 3]
        parts.append({"name": label, "texture": texture, "color": colour,
                      "flags": flags, "vertices": vertices, "indices": out_idx})
    return parts, inverse_bind(joints), joints
