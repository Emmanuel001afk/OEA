#!/usr/bin/env python3
"""Send Off soldier — anatomy-first procedural character.

This replaces the primitive-assembly generator. Two rules drive every shape
here:

1. **Joints are the ground truth.** [BIND] is the 19-bone OEA skeleton with
   real human segment lengths (1.80 m, 7.5 heads, 0.30 m upper arm, 0.26 m
   forearm, 0.42 m thigh, 0.415 m shin). The mesh is built *around* those
   joint positions, so the elbow bends where the elbow is drawn. The previous
   generator placed the shoulder joint at y=1.16 while the mesh drew the arm
   from y=1.40, and gave the forearm 7 cm of bone: the arms swung around a
   point 24 cm from their own elbow, which is a large part of why the result
   read as a puppet.

2. **Masses, not primitives.** A limb is a loft of cross-sections whose
   half-width, half-depth and centre drift follow the underlying anatomy:
   deltoid cap, biceps forward of the bone, triceps behind it, forearm flare
   below the elbow, wrist taper, quadriceps forward of the femur, hamstring
   behind, gastrocnemius behind the tibia, a flat back and a forward chest.

The output is the same `.oeac` the phone loads, on the same 19 bones, so the
existing pose code (run cycle, aim, crouch, reload) drives it unchanged.
"""

from __future__ import annotations

import math
from typing import Dict, List, Sequence, Tuple

from humanoid import (Mesh, Station, blob, clamp, loft, mix, shade, smoothstep,
                      v_add, v_cross, v_dot, v_lerp, v_norm, v_scale, v_sub)

Vec = Tuple[float, float, float]
RGB = Tuple[int, int, int]

# --------------------------------------------------------------------- skeleton
#
# (parent, offset from parent joint). Bone convention is the engine's: every
# bone points along its own +Y and limbs hang along -Y, so the pose code's
# rotation-about-X is a forward/back swing. Arms hang 8 degrees out because
# that is how a person stands, and because it keeps the silhhouette readable
# without pretending the character is a scarecrow.
B = {
    "PELVIS": 0, "SPINE": 1, "CHEST": 2, "NECK": 3, "HEAD": 4,
    "SHOULDER_L": 5, "ARM_L": 6, "FORE_L": 7, "HAND_L": 8,
    "SHOULDER_R": 9, "ARM_R": 10, "FORE_R": 11, "HAND_R": 12,
    "THIGH_L": 13, "SHIN_L": 14, "FOOT_L": 15,
    "THIGH_R": 16, "SHIN_R": 17, "FOOT_R": 18,
}

BIND: List[Tuple[int, Vec]] = [
    (-1, (0.0, 0.930, 0.0)),        # PELVIS — root at hip height
    (0, (0.0, 0.170, 0.0)),         # SPINE
    (1, (0.0, 0.240, 0.0)),         # CHEST
    (2, (0.0, 0.120, 0.0)),         # NECK
    (3, (0.0, 0.130, 0.0)),         # HEAD (skull base)
    (2, (0.180, 0.110, 0.0)),       # SHOULDER_L
    (5, (0.006, -0.325, 0.0)),      # ARM_L (elbow)
    (6, (0.014, -0.285, 0.004)),    # FORE_L (wrist)
    (7, (0.006, -0.055, 0.006)),    # HAND_L
    (2, (-0.180, 0.110, 0.0)),      # SHOULDER_R
    (9, (-0.006, -0.325, 0.0)),     # ARM_R
    (10, (-0.014, -0.285, 0.004)),  # FORE_R
    (11, (-0.006, -0.055, 0.006)),  # HAND_R
    (0, (0.095, -0.020, 0.0)),      # THIGH_L
    (13, (0.006, -0.420, 0.006)),   # SHIN_L (knee)
    (14, (0.008, -0.415, -0.012)),  # FOOT_L (ankle)
    (0, (-0.095, -0.020, 0.0)),     # THIGH_R
    (16, (-0.006, -0.420, 0.006)),  # SHIN_R
    (17, (-0.008, -0.415, -0.012)), # FOOT_R
]

BONE_NAMES = ["PELVIS", "SPINE", "CHEST", "NECK", "HEAD",
              "SHOULDER_L", "ARM_L", "FORE_L", "HAND_L",
              "SHOULDER_R", "ARM_R", "FORE_R", "HAND_R",
              "THIGH_L", "SHIN_L", "FOOT_L", "THIGH_R", "SHIN_R", "FOOT_R"]


def joint_world() -> List[Vec]:
    out: List[Vec] = []
    for i, (parent, offset) in enumerate(BIND):
        if parent < 0:
            out.append(offset)
        else:
            p = out[parent]
            out.append((p[0] + offset[0], p[1] + offset[1], p[2] + offset[2]))
    return out


J = joint_world()

PELVIS, SPINE, CHEST, NECK, HEAD = J[0], J[1], J[2], J[3], J[4]
SHO_L, SHO_R = J[5], J[9]
ELB_L, ELB_R = J[6], J[10]
WRI_L, WRI_R = J[7], J[11]
THI_L, THI_R = J[13], J[16]
KNE_L, KNE_R = J[14], J[17]
ANK_L, ANK_R = J[15], J[18]

HEIGHT = 1.80


def _blend(near: int, far: int, t: float) -> List[Tuple[int, float]]:
    """The limb weight curve, shared by skin and cloth so they cannot shear."""
    k = smoothstep(0.55, 0.97, t)
    return [(near, 1.0 - k), (far, k)]


# --------------------------------------------------------------------- helpers

def weights_for_y(y: float) -> List[Tuple[int, float]]:
    """Torso weights by height: pelvis → spine → chest → neck."""
    if y <= 0.98:
        return [(B["PELVIS"], 1.0)]
    if y <= 1.10:
        t = smoothstep(0.98, 1.10, y)
        return [(B["PELVIS"], 1.0 - t), (B["SPINE"], t)]
    if y <= 1.26:
        t = smoothstep(1.10, 1.26, y)
        return [(B["SPINE"], 1.0 - t), (B["CHEST"], t)]
    if y <= 1.46:
        return [(B["CHEST"], 1.0)]
    t = smoothstep(1.46, 1.55, y)
    return [(B["CHEST"], 1.0 - t), (B["NECK"], t)]


def torso_rings() -> List[Tuple[float, float, float, float]]:
    """(y, half-width, half-depth, forward bias) from hip to neck base."""
    return [
        (0.845, 0.142, 0.096, 0.000),   # pelvis, under the shirt
        (0.905, 0.150, 0.100, 0.000),
        (0.970, 0.146, 0.097, 0.004),
        (1.035, 0.138, 0.094, 0.008),   # waist
        (1.100, 0.145, 0.097, 0.010),
        (1.165, 0.162, 0.104, 0.012),   # lower ribs
        (1.235, 0.176, 0.115, 0.014),   # chest
        (1.300, 0.184, 0.118, 0.012),
        (1.365, 0.184, 0.112, 0.004),   # upper chest
        (1.396, 0.190, 0.107, 0.002),   # acromion: widest point of the torso
        (1.418, 0.180, 0.101, -0.001),  # upper deltoid, already falling away
        (1.436, 0.162, 0.094, -0.003),
        (1.452, 0.140, 0.087, -0.004),  # trapezius
        (1.468, 0.116, 0.079, -0.005),
        (1.482, 0.094, 0.071, -0.006),
        (1.492, 0.076, 0.066, -0.006),  # neck root
    ]


# --------------------------------------------------------------------- head

def build_head(mesh: Mesh, skin: RGB, fem: bool) -> None:
    """Skull, jaw, brow, nose, ears, chin — a lofted head, not a ball.

    Stations run chin → crown. Half-width/half-depth follow a real skull: the
    jaw is narrow and the cheekbones are the widest part of the lower face,
    the cranium bulges behind the ears, and the back of the head (negative Z)
    is rounder than the face.
    """
    w = [(B["HEAD"], 1.0)]
    chin_y = 1.556
    stations = [
        # (y, half-width, half-depth, bias)
        (1.556, 0.044, 0.050, 0.020),   # chin
        (1.572, 0.062, 0.066, 0.014),   # jaw
        (1.592, 0.073, 0.077, 0.007),   # jaw corner
        (1.620, 0.078, 0.083, 0.002),   # cheek
        (1.652, 0.079, 0.086, 0.000),   # cheekbone — widest
        (1.678, 0.077, 0.087, -0.002),  # brow line
        (1.700, 0.074, 0.085, -0.004),  # forehead
        (1.718, 0.070, 0.082, -0.006),
        (1.740, 0.064, 0.074, -0.008),
        (1.762, 0.052, 0.060, -0.010),
        (1.780, 0.033, 0.038, -0.012),
        (1.790, 0.013, 0.016, -0.012),
    ]
    rings: List[List[int]] = []
    prev = None
    for i, (y, rx, rd, bias) in enumerate(stations):
        if fem:
            rx *= 0.95
            rd *= 0.97
        ids = mesh.ring((0.0, y, bias), (1.0, 0.0, 0.0), (0.0, 0.0, 1.0),
                        rx, rd, 16, i / (len(stations) - 1), skin, w, exponent=2.3)
        if prev is not None:
            mesh.bridge_rings(prev, ids)
        prev = ids
        rings.append(ids)
    mesh.cap(rings[0], (0.0, 1.552, 0.018), skin, w, flip=True)
    mesh.cap(rings[-1], (0.0, 1.794, -0.012), skin, w)
    # flatten the face plane slightly: a human face is flatter than a cylinder
    for i in range(len(mesh.pos)):
        x, y, z = mesh.pos[i]
        if z > 0.02 and 1.55 < y < 1.70:
            mesh.pos[i] = (x, y, z * 0.93)

    # brow ridge: a shallow shelf over the eyes
    for side in (-1, 1):
        blob(mesh, (side * 0.034, 1.682, 0.079), (0.034, 0.010, 0.014), skin, w, 12, 6)
    # chin mass
    blob(mesh, (0.0, 1.566, 0.050), (0.028, 0.013, 0.015), skin, w, 12, 6)
    # nose: bridge → tip → wings, built as a wedge so it catches light
    nose = [
        (1.706, 0.008, 0.008, 0.074),
        (1.690, 0.009, 0.010, 0.080),
        (1.672, 0.011, 0.012, 0.085),
        (1.654, 0.013, 0.014, 0.088),
        (1.640, 0.017, 0.015, 0.086),
    ]
    prev = None
    rs: List[List[int]] = []
    for i, (y, rx, rd, z) in enumerate(nose):
        ids = mesh.ring((0.0, y, z), (1.0, 0.0, 0.0), (0.0, 0.0, 1.0), rx, rd, 10,
                        i / (len(nose) - 1), skin, w, exponent=2.4)
        if prev is not None:
            mesh.bridge_rings(prev, ids)
        prev = ids
        rs.append(ids)
    mesh.cap(rs[0], (0.0, 1.706, 0.076), skin, w, flip=True)
    for side in (-1, 1):
        blob(mesh, (side * 0.015, 1.639, 0.081), (0.011, 0.008, 0.010), skin, w, 10, 6)
    # nostril shadows: two small dark commas under the tip
    for side in (-1, 1):
        blob(mesh, (side * 0.010, 1.6345, 0.0835), (0.005, 0.003, 0.004),
             shade(skin, 0.55), w, 8, 4)
    # ears: a shell with a hollow
    for side in (-1, 1):
        blob(mesh, (side * 0.078, 1.662, -0.006), (0.008, 0.028, 0.017), skin, w, 10, 8)
        blob(mesh, (side * 0.076, 1.662, -0.004), (0.0045, 0.017, 0.009),
             shade(skin, 0.84), w, 8, 6)
    # neck: thick at the base, tilting slightly forward into the skull
    neck_st = [
        (1.430, 0.068, 0.064, -0.004),
        (1.468, 0.061, 0.059, -0.002),
        (1.502, 0.055, 0.055, 0.000),
        (1.534, 0.052, 0.053, 0.004),
        (1.562, 0.050, 0.051, 0.008),
    ]
    prev = None
    ns: List[List[int]] = []
    for i, (y, rx, rd, bias) in enumerate(neck_st):
        t = smoothstep(1.44, 1.56, y)
        wts = [(B["CHEST"], (1.0 - t) * 0.6), (B["NECK"], 0.4 + t * 0.6 - (1.0 - t) * 0.0)]
        wts = [(B["CHEST"], max(0.0, (1.0 - t) * 0.55)), (B["NECK"], 1.0 - max(0.0, (1.0 - t) * 0.55))]
        ids = mesh.ring((0.0, y, bias), (1.0, 0.0, 0.0), (0.0, 0.0, 1.0), rx, rd, 14,
                        i / (len(neck_st) - 1), skin, wts, exponent=2.1)
        if prev is not None:
            mesh.bridge_rings(prev, ids)
        prev = ids
        ns.append(ids)


def build_face_parts(mesh: Mesh, skin: RGB, eye: RGB) -> None:
    """Eyes, lids, brows, lips — the features that make it a face.

    The eye line sits at half the head's height, which is the single proportion
    that most decides whether a head reads as human; a low eye line gives a
    long, alien face no matter how good the rest of it is.

    These are centimetre-scale features, so they are cheap on purpose: a
    handful of small lofts per eye, not spheres. A soldier's face is 200
    pixels tall on a phone and 20 at fighting distance.
    """
    w = [(B["HEAD"], 1.0)]
    eye_y = 1.672
    for side in (-1, 1):
        cx = side * 0.032
        blob(mesh, (cx, eye_y + 0.002, 0.075), (0.023, 0.012, 0.009), shade(skin, 0.86), w, 8, 4)
        blob(mesh, (cx, eye_y, 0.079), (0.0128, 0.0104, 0.0072), (236, 233, 228), w, 8, 5)
        blob(mesh, (cx, eye_y, 0.0848), (0.0070, 0.0070, 0.0034), eye, w, 8, 4)
        blob(mesh, (cx, eye_y, 0.0862), (0.0034, 0.0034, 0.0019), (16, 13, 12), w, 6, 3)
        # one lid band, split by colour: skin above, lash line on it
        blob(mesh, (cx, eye_y + 0.0072, 0.080), (0.0148, 0.0048, 0.008), skin, w, 8, 3)
        blob(mesh, (cx, eye_y + 0.0043, 0.0825), (0.0125, 0.0018, 0.006), shade(skin, 0.42), w, 8, 3)
        # brow: three short strokes angled up and out
        for k in range(3):
            bx = cx + side * (k - 1) * 0.0105
            by = eye_y + 0.0185 + (1 - abs(k - 1)) * 0.0016
            blob(mesh, (bx, by, 0.085), (0.0072, 0.0034, 0.0055), (52, 43, 39), w, 6, 3)
    # mouth: thin and closed, a little wider than the nose
    blob(mesh, (0.0, 1.6085, 0.084), (0.017, 0.0075, 0.0075), (140, 92, 86), w, 8, 4)
    blob(mesh, (0.0, 1.6075, 0.0865), (0.0165, 0.0012, 0.0035), (104, 62, 58), w, 8, 3)


def build_hair(mesh: Mesh, colour: RGB, fem: bool, ruffled: float = 1.0) -> None:
    """Short tactical hair: a cap that follows the skull, swept forward over one
    temple, tapering into a nape. Never a smooth helmet and never a bald egg
    with a lid resting on it."""
    w = [(B["HEAD"], 1.0)]
    # The cap is swept: it sits lower at the back and higher at the front, the
    # way short hair pushed back off the brow actually reads.
    cap = [
        (1.700, 0.081, 0.090, -0.008),
        (1.718, 0.080, 0.088, -0.010),
        (1.738, 0.076, 0.083, -0.011),
        (1.758, 0.066, 0.073, -0.012),
        (1.776, 0.050, 0.056, -0.013),
        (1.790, 0.028, 0.032, -0.013),
        (1.798, 0.011, 0.013, -0.013),
    ]
    prev = None
    rs: List[List[int]] = []
    for i, (y, rx, rd, bias) in enumerate(cap):
        ids = mesh.ring((0.0, y, bias), (1.0, 0.0, 0.0), (0.0, 0.0, 1.0),
                        rx * 1.02, rd * 1.02, 16, i / (len(cap) - 1), colour, w, exponent=2.3)
        if prev is not None:
            mesh.bridge_rings(prev, ids)
        prev = ids
        rs.append(ids)
    mesh.cap(rs[-1], (0.0, 1.801, -0.013), colour, w)

    # Back and sides: the cap runs down over the occiput and behind the ears,
    # interrupted only where the face is. Bridging the back half of each ring
    # is what gives hair a hairline instead of a hard hat edge.
    back = [
        (1.700, 0.081, 0.091, -0.008),
        (1.678, 0.083, 0.092, -0.006),
        (1.654, 0.082, 0.091, -0.004),
        (1.632, 0.077, 0.086, -0.002),
    ]
    brs: List[List[int]] = []
    for i, (y, rx, rd, bias) in enumerate(back):
        ids = mesh.ring((0.0, y, bias), (1.0, 0.0, 0.0), (0.0, 0.0, 1.0),
                        rx * 1.016, rd * 1.016, 16, i / (len(back) - 1),
                        shade(colour, 0.96), w, exponent=2.3)
        brs.append(ids)

    def back_half(ids: List[int]) -> List[int]:
        return ids[8:18] + [ids[0]]

    for i in range(len(brs) - 1):
        mesh.bridge_rings(back_half(brs[i]), back_half(brs[i + 1]))

    # Fringe: five soft locks combed to one side, each shorter and thinner than
    # the last, sitting on the brow rather than hovering above it.
    for i, (x, z, rx, ry, yy, k) in enumerate((
            (-0.064, 0.048, 0.024, 0.026, 1.698, 0.94),
            (-0.032, 0.066, 0.030, 0.022, 1.704, 1.0),
            (0.002, 0.074, 0.032, 0.020, 1.706, 1.0),
            (0.036, 0.070, 0.028, 0.018, 1.702, 0.98),
            (0.064, 0.054, 0.022, 0.016, 1.694, 0.94))):
        blob(mesh, (x, yy, z), (rx, ry * 1.3, rx * 0.44), shade(colour, k), w, 10, 5)
    # Temples: hair recedes above the ears, so the cap stops higher here.
    for side in (-1, 1):
        blob(mesh, (side * 0.076, 1.694, 0.008), (0.014, 0.030, 0.034),
             shade(colour, 0.9), w, 8, 6)
        # sideburn, short and pointed
        blob(mesh, (side * 0.071, 1.658, 0.012), (0.007, 0.014, 0.012),
             shade(colour, 0.84), w, 8, 4)
    # Nape: thins out so the neck does not look painted on.
    blob(mesh, (0.0, 1.626, -0.072), (0.056, 0.026, 0.030), shade(colour, 0.92), w, 12, 6)
    if fem:
        blob(mesh, (0.0, 1.640, -0.090), (0.064, 0.078, 0.030), shade(colour, 0.95), w, 12, 8)


def build_arm(mesh: Mesh, side: int, skin: RGB, glove: RGB, sleeve_end: float = 0.42) -> None:
    """One arm: deltoid cap, biceps/triceps, elbow, forearm flare, wrist.

    `sleeve_end` 0..1 is where the sleeve stops on the upper arm/forearm; the
    bare forearm starts below it.
    """
    sho, arm, fore, hand = ((B["SHOULDER_L"], B["ARM_L"], B["FORE_L"], B["HAND_L"])
                            if side > 0 else
                            (B["SHOULDER_R"], B["ARM_R"], B["FORE_R"], B["HAND_R"]))
    sh = SHO_L if side > 0 else SHO_R
    el = ELB_L if side > 0 else ELB_R
    wr = WRI_L if side > 0 else WRI_R

    # deltoid: a cap that sits over the shoulder joint and shades the armpit
    # Bone convention, and it is easy to get wrong: a joint is named for the
    # bone that STARTS there. SHOULDER starts at the shoulder, ARM at the elbow,
    # FORE at the wrist. So the upper arm is SHOULDER-weighted and the forearm
    # is ARM-weighted. Weighting one joint further down throws the whole upper
    # arm forward the moment the elbow bends, which is the puppet-on-a-stick
    # look we are getting rid of.
    # The deltoid must stay strictly inside the sleeve that covers it. When it
    # pokes through, the model reads as a bare shoulder in a sleeveless shirt.
    # No separate deltoid mass here on purpose: the sleeve cap in the shirt part
    # IS the shoulder. A skin blob inside it only ever pokes through and reads as
    # a torn sleeve.
    # Small, and low enough to stay inside the sleeve's opening: this is the
    # shoulder's roundness under the cloth, not a shoulder in its own right.
    blob(mesh, (sh[0] - 0.002, sh[1] - 0.098, sh[2] - 0.002), (0.030, 0.030, 0.032), skin,
         [(sho, 1.0)], 12, 8)

    # upper arm: shoulder → elbow
    # The tube starts below the shoulder joint: above that the sleeve cap is the
    # surface, and an arm tube poking out of it is exactly the artefact this
    # rewrite exists to remove.
    # The first station is deliberately small. The shoulder joint sits at
    # y = 1.45 and the trapezius line at that x is around y = 1.43, so a
    # full-width ring at the joint pokes its top *through* the shoulder and
    # leaves a strip of bare skin above the sleeve. A real arm has almost no
    # radius right at the acromion — the deltoid mass is below it — and the
    # sleeve is what the eye reads as the shoulder anyway.
    # The skin starts a tenth of the way down the upper arm, not at the joint.
    # That is not a shortcut: the sleeve is a closed tube that begins inside the
    # torso and bulges over the acromion, and the skin's own ring at the joint
    # sits *above* the sleeve's top there, so it showed as two pink wedges at
    # the shoulder tips. The shoulder above this point is cloth, and cloth is
    # what a shoulder looks like in a shirt. The loft is left uncapped at the
    # top, inside the sleeve, where nothing can see it.
    upper = [
        (0.10, 0.040, 0.043, 0.003),
        (0.28, 0.046, 0.049, 0.005),   # biceps belly forward
        (0.50, 0.046, 0.048, 0.005),
        (0.74, 0.041, 0.043, 0.002),
        (0.92, 0.037, 0.039, 0.000),
        (1.00, 0.036, 0.038, 0.000),
    ]
    stations = [Station(v_lerp(sh, el, t), rx, rd, bias, smoothstep(0.55, 0.97, t))
                for (t, rx, rd, bias) in upper]
    loft(mesh, stations, sho, arm, skin, 12, cap_a=False, cap_b=False)

    # forearm: elbow → wrist, with the brachioradialis swell just below the joint
    fore_profile = [
        (0.00, 0.036, 0.038, 0.000),
        (0.14, 0.039, 0.038, 0.003),
        (0.32, 0.037, 0.036, 0.003),
        (0.55, 0.031, 0.030, 0.002),
        (0.80, 0.025, 0.024, 0.000),
        (1.00, 0.022, 0.023, 0.000),
    ]
    stations = [Station(v_lerp(el, wr, t), rx, rd, bias, smoothstep(0.62, 0.97, t))
                for (t, rx, rd, bias) in fore_profile]
    stations[0].blend = 0.0
    loft(mesh, stations, arm, hand, skin, 12, cap_a=False, cap_b=False)
    # No elbow cap on purpose. At the joint the sleeve's cross-sections turn a
    # corner and pinch; a skin mass placed there shows through the crease as a
    # red fleck. The arm loft already narrows into the joint and widens below
    # it, which is what an elbow looks like anyway.


def build_hand(mesh: Mesh, side: int, colour: RGB, glove: RGB) -> None:
    """Palm, four fingers and an opposed thumb, on the hand bone."""
    hand = B["HAND_L"] if side > 0 else B["HAND_R"]
    wr = WRI_L if side > 0 else WRI_R
    # axis continues the forearm: down and slightly out
    el = ELB_L if side > 0 else ELB_R
    down = v_norm(v_sub(wr, el))
    out = v_norm(v_cross((0.0, 0.0, 1.0), down))
    if out[0] * side < 0:
        out = v_scale(out, -1.0)
    fwd = v_norm(v_cross(down, out))
    if fwd[2] < 0:
        fwd = v_scale(fwd, -1.0)
    w = [(hand, 1.0)]
    # palm — thicker on the little-finger side, tapering into the wrist
    palm = [
        (0.000, 0.024, 0.020),
        (0.022, 0.032, 0.023),
        (0.055, 0.036, 0.022),
        (0.082, 0.034, 0.019),
        (0.098, 0.030, 0.016),
    ]
    prev = None
    rs: List[List[int]] = []
    for i, (t, rx, ry) in enumerate(palm):
        c = v_add(wr, v_scale(down, t))
        c = v_add(c, v_scale(out, -side * 0.0))
        ids = mesh.ring(c, out, fwd, rx, ry, 12, i / (len(palm) - 1), colour, w, exponent=2.6)
        if prev is not None:
            mesh.bridge_rings(prev, ids)
        prev = ids
        rs.append(ids)
    mesh.cap(rs[0], wr, colour, w, flip=True)
    # fingers: four tapered segments, curled slightly forward
    for f in range(4):
        off = (f - 1.5) * 0.017 * side
        base = v_add(v_add(wr, v_scale(down, 0.094)), v_scale(out, off))
        curl = 0.014 + 0.005 * abs(f - 1.5)
        a = base
        b = v_add(base, v_add(v_scale(down, 0.058), v_scale(fwd, curl)))
        mid = v_lerp(a, b, 0.5)
        st = [Station(a, 0.0088, 0.0081, 0.0),
              Station(mid, 0.0080, 0.0076, 0.0),
              Station(b, 0.0064, 0.0060, 0.0)]
        loft(mesh, st, hand, hand, colour, 6, cap_a=False, cap_b=True)
    # thumb: off the palm toward the body, angled forward
    tdir = v_norm(v_add(v_scale(down, 0.72), v_scale(fwd, 0.55)))
    tb = v_add(v_add(wr, v_scale(down, 0.030)), v_scale(out, -0.026 * side))
    ta = v_add(tb, v_scale(tdir, 0.010))
    tto = v_add(tb, v_scale(tdir, 0.062))
    loft(mesh, [Station(ta, 0.012, 0.011, 0.0), Station(v_lerp(ta, tto, 0.5), 0.010, 0.0095, 0.0),
                Station(tto, 0.0075, 0.0072, 0.0)], hand, hand, colour, 8, cap_a=True, cap_b=True)


def build_glove_cuff(mesh: Mesh, side: int, colour: RGB) -> None:
    hand = B["HAND_L"] if side > 0 else B["HAND_R"]
    wr = WRI_L if side > 0 else WRI_R
    el = ELB_L if side > 0 else ELB_R
    down = v_norm(v_sub(wr, el))
    st = [Station(v_add(wr, v_scale(down, -0.018)), 0.025, 0.024, 0.0),
          Station(v_add(wr, v_scale(down, -0.004)), 0.030, 0.028, 0.0),
          Station(v_add(wr, v_scale(down, 0.012)), 0.030, 0.028, 0.0)]
    loft(mesh, st, hand, hand, colour, 12, cap_a=True, cap_b=False)


# --------------------------------------------------------------------- legs

def build_leg(mesh: Mesh, side: int, pants: RGB, boot: RGB, skin: RGB) -> None:
    """One leg: seat → thigh → knee → calf → ankle, with the clothing on it.

    The fabric is lofted over the whole leg rather than as a skirt with tubes
    hanging out of it, which is what made the last version read as a mannequin
    wearing a barrel.
    """
    thi, shin, foot = ((B["THIGH_L"], B["SHIN_L"], B["FOOT_L"]) if side > 0 else
                       (B["THIGH_R"], B["SHIN_R"], B["FOOT_R"]))
    hip = THI_L if side > 0 else THI_R
    knee = KNE_L if side > 0 else KNE_R
    ank = ANK_L if side > 0 else ANK_R

    # ---- thigh: starts inside the seat, so the two never show a seam
    thigh = [
        (-0.10, 0.083, 0.090, 0.010),   # up inside the pelvis
        (0.00, 0.086, 0.092, 0.008),    # hip
        (0.18, 0.083, 0.090, 0.012),    # quadriceps, forward of the femur
        (0.40, 0.075, 0.081, 0.010),
        (0.62, 0.065, 0.070, 0.006),
        (0.84, 0.056, 0.060, 0.002),
        (1.00, 0.052, 0.055, 0.000),    # knee
    ]
    stations = []
    for (t, rx, rd, bias) in thigh:
        blend = smoothstep(0.50, 0.95, t)
        stations.append(Station(v_lerp(hip, knee, t), rx, rd, bias, blend))
    loft(mesh, stations, thi, shin, pants, 14, cap_a=True, cap_b=True)
    # glute: the seat is a mass, not a tube end. Its inner edge has to stay
    # inside the other cheek — a 76 mm ball at x = ±0.088 overlaps the midline
    # and the two cheeks melt into one wide slab.
    blob(mesh, (hip[0] * 0.98, hip[1] + 0.020, -0.034), (0.062, 0.080, 0.060), pants,
         [(B["PELVIS"], 0.6), (thi, 0.4)], 14, 8)
    # hamstring, behind the femur
    blob(mesh, (hip[0], hip[1] - 0.155, -0.042), (0.048, 0.105, 0.038), pants,
         [(thi, 1.0)], 12, 8)

    # ---- knee: a soft joint plus a hard pad on the front
    blob(mesh, (knee[0], knee[1] + 0.004, knee[2]), (0.050, 0.052, 0.052), pants,
         [(shin, 0.65), (thi, 0.35)], 12, 8)
    pad = [(0.058, 0.038, 0.022), (0.024, 0.048, 0.028), (-0.046, 0.038, 0.022)]
    prev = None
    for i, (dy, rx, rd) in enumerate(pad):
        ids = mesh.ring((knee[0], knee[1] + dy, knee[2] + 0.038 - abs(dy) * 0.30),
                        (1.0, 0.0, 0.0), (0.0, 0.0, 1.0), rx, rd, 8, i / 2.0,
                        shade(pants, 0.78), [(shin, 1.0)], exponent=3.0)
        if prev is not None:
            mesh.bridge_rings(prev, ids)
        prev = ids

    # ---- calf: gastrocnemius behind the tibia, tapering to a slim ankle
    calf = [
        (0.00, 0.050, 0.053, 0.000),
        (0.14, 0.053, 0.060, -0.004),
        (0.30, 0.054, 0.064, -0.008),   # calf belly, behind
        (0.50, 0.047, 0.054, -0.006),
        (0.72, 0.038, 0.042, -0.002),
        (0.88, 0.031, 0.033, 0.000),
        (1.00, 0.028, 0.030, 0.000),    # ankle
    ]
    stations = [Station(v_lerp(knee, ank, t), rx, rd, bias, smoothstep(0.55, 0.96, t))
                for (t, rx, rd, bias) in calf]
    loft(mesh, stations, shin, foot, pants, 12, cap_a=True, cap_b=True)
    # the trouser leg is bloused over the boot, not tight to the ankle
    cuff = [(0.86, 0.038, 0.040), (0.94, 0.042, 0.044), (1.00, 0.040, 0.042)]
    prev = None
    for i, (t, rx, rd) in enumerate(cuff):
        ids = mesh.ring(v_lerp(knee, ank, t), (1.0, 0.0, 0.0), (0.0, 0.0, 1.0), rx, rd, 12,
                        i / 2.0, shade(pants, 0.94), [(shin, 1.0)], exponent=2.4)
        if prev is not None:
            mesh.bridge_rings(prev, ids)
        prev = ids


def build_boot(mesh: Mesh, side: int, boot: RGB, sole: RGB) -> None:
    """Combat boot: a laced shaft, a shaped last, and a lugged sole slab."""
    foot = B["FOOT_L"] if side > 0 else B["FOOT_R"]
    shin = B["SHIN_L"] if side > 0 else B["SHIN_R"]
    ank = ANK_L if side > 0 else ANK_R
    w_foot = [(foot, 1.0)]
    x = ank[0] - side * 0.004

    # ---- shaft: rises above the ankle, sits under the trouser blouse
    shaft = [
        (0.120, 0.044, 0.046, 0.0),
        (0.090, 0.047, 0.050, 0.002),
        (0.050, 0.049, 0.054, 0.004),
        (0.014, 0.050, 0.058, 0.006),
    ]
    prev = None
    srings: List[List[int]] = []
    for i, (dy, rx, rd, bias) in enumerate(shaft):
        k = smoothstep(0.12, 0.014, dy)
        ids = mesh.ring((x, ank[1] + dy, ank[2] + 0.004 + bias),
                        (1.0, 0.0, 0.0), (0.0, 0.0, 1.0), rx, rd, 12,
                        i / (len(shaft) - 1), boot,
                        [(shin, 1.0 - k * 0.7), (foot, k * 0.7)], exponent=2.4)
        if prev is not None:
            mesh.bridge_rings(prev, ids)
        prev = ids
        srings.append(ids)
    mesh.cap(srings[0], (x, ank[1] + 0.122, ank[2] + 0.004), boot,
             [(shin, 0.3), (foot, 0.7)], flip=True)

    # ---- last: heel (z -0.075) forward to the toe (z +0.20), on the ground
    last = [
        (-0.075, 0.030, 0.030, 0.062),
        (-0.060, 0.042, 0.040, 0.066),
        (-0.020, 0.049, 0.048, 0.064),
        (0.030, 0.051, 0.046, 0.056),
        (0.080, 0.050, 0.040, 0.048),
        (0.130, 0.047, 0.034, 0.041),
        (0.172, 0.041, 0.028, 0.036),
        (0.198, 0.031, 0.020, 0.033),
    ]
    prev = None
    lrings: List[List[int]] = []
    for i, (z, rx, ry, cy) in enumerate(last):
        ids = mesh.ring((x, cy, ank[2] + z), (1.0, 0.0, 0.0), (0.0, 1.0, 0.0), rx, ry, 12,
                        i / (len(last) - 1), boot, w_foot, exponent=2.9)
        if prev is not None:
            mesh.bridge_rings(prev, ids)
        prev = ids
        lrings.append(ids)
    mesh.cap(lrings[0], (x, 0.062, ank[2] - 0.077), boot, w_foot, flip=True)
    mesh.cap(lrings[-1], (x, 0.033, ank[2] + 0.202), boot, w_foot)

    # ---- sole: a slab with a raised heel block
    sole_last = [(-0.080, 0.033, 0.030), (-0.030, 0.052, 0.026), (0.060, 0.054, 0.024),
                 (0.140, 0.049, 0.022), (0.200, 0.032, 0.018)]
    prev = None
    grs: List[List[int]] = []
    for i, (z, rx, ry) in enumerate(sole_last):
        ids = mesh.ring((x, ry, ank[2] + z), (1.0, 0.0, 0.0), (0.0, 1.0, 0.0), rx,
                        ry * 0.62, 10, i / (len(sole_last) - 1), sole, w_foot, exponent=3.4)
        if prev is not None:
            mesh.bridge_rings(prev, ids)
        prev = ids
        grs.append(ids)
    mesh.cap(grs[0], (x, 0.030, ank[2] - 0.082), sole, w_foot, flip=True)
    mesh.cap(grs[-1], (x, 0.018, ank[2] + 0.202), sole, w_foot)


def build_vest(mesh: Mesh, vest: RGB, dark: RGB) -> None:
    """Plate carrier that follows the chest instead of a crate over it.

    The shell is a loft so it curves with the ribcage; the plate, pouches and
    straps are separate masses sitting on it.
    """
    w_lo = weights_for_y
    stations = [
        (1.045, 0.150, 0.104, 0.004),
        (1.100, 0.158, 0.108, 0.006),
        (1.170, 0.172, 0.113, 0.008),
        (1.240, 0.187, 0.121, 0.008),
        (1.310, 0.194, 0.122, 0.006),
        (1.370, 0.192, 0.117, 0.002),
        (1.412, 0.176, 0.108, 0.000),
    ]
    prev = None
    rings: List[List[int]] = []
    for i, (y, rx, rd, bias) in enumerate(stations):
        ids = mesh.ring((0.0, y, bias), (1.0, 0.0, 0.0), (0.0, 0.0, 1.0), rx, rd, 16,
                        i / (len(stations) - 1), vest, w_lo(y), exponent=3.0)
        if prev is not None:
            mesh.bridge_rings(prev, ids)
        prev = ids
        rings.append(ids)
    # front plate: a hard slab on the chest, the thing that reads as armour
    plate = [
        (1.135, 0.108, 0.020),
        (1.200, 0.118, 0.024),
        (1.285, 0.120, 0.024),
        (1.345, 0.108, 0.020),
    ]
    prev = None
    prs: List[List[int]] = []
    for i, (y, hw, hd) in enumerate(plate):
        ids = mesh.ring((0.0, y, 0.108 + hd), (1.0, 0.0, 0.0), (0.0, 0.0, 1.0),
                        hw, hd, 12, i / (len(plate) - 1), shade(vest, 1.08),
                        w_lo(y), exponent=3.6)
        if prev is not None:
            mesh.bridge_rings(prev, ids)
        prev = ids
        prs.append(ids)
    mesh.cap(prs[0], (0.0, 1.130, 0.128), shade(vest, 1.05), w_lo(1.13), flip=True)
    mesh.cap(prs[-1], (0.0, 1.350, 0.128), shade(vest, 1.05), w_lo(1.35))
    # magazine pouches, three across the belly plate
    for x in (-0.072, 0.0, 0.072):
        blob(mesh, (x, 1.190, 0.146), (0.031, 0.042, 0.022), dark, w_lo(1.19), 8, 5)
        # flap lip
        blob(mesh, (x, 1.230, 0.150), (0.029, 0.008, 0.020), shade(dark, 1.18), w_lo(1.22), 8, 3)
    # Shoulder straps, riding over the trapezius on top of the shirt: this is
    # the detail that makes a plate carrier read as webbing rather than as a
    # slightly different-coloured T-shirt.
    for side in (-1, 1):
        path = [((side * 0.058, 1.402, 0.104), 0.030, 0.014),
                ((side * 0.072, 1.452, 0.052), 0.032, 0.015),
                ((side * 0.080, 1.470, -0.006), 0.033, 0.015),
                ((side * 0.074, 1.442, -0.062), 0.032, 0.014),
                ((side * 0.062, 1.396, -0.104), 0.030, 0.013)]
        prev = None
        for i, (c, rx, ry) in enumerate(path):
            a = path[max(0, i - 1)][0]
            b = path[min(len(path) - 1, i + 1)][0]
            ids = mesh.ring(c, *frame_for(a, b), rx, ry, 10, i / (len(path) - 1),
                            shade(vest, 1.12), [(B["CHEST"], 1.0)], exponent=3.0)
            if prev is not None:
                mesh.bridge_rings(prev, ids)
            prev = ids
    # Cummerbund: a wide band at the waist, tighter than the plate above it, so
    # the carrier narrows into the body the way webbing actually does.
    belt = [(1.052, 0.158, 0.108), (1.020, 0.162, 0.110),
            (0.985, 0.165, 0.110), (0.958, 0.163, 0.109)]
    prev = None
    for i, (y, rx, rd) in enumerate(belt):
        ids = mesh.ring((0.0, y, 0.004), (1.0, 0.0, 0.0), (0.0, 0.0, 1.0), rx, rd, 16,
                        i / 3.0, shade(dark, 1.05), w_lo(y), exponent=3.0)
        if prev is not None:
            mesh.bridge_rings(prev, ids)
        prev = ids
    # buckle
    blob(mesh, (0.0, 0.968, 0.120), (0.030, 0.020, 0.012), shade(dark, 1.6),
         w_lo(0.97), 10, 6)


def build_pouches(mesh: Mesh, dark: RGB, thigh: int) -> None:
    """Hip pouches, a thigh holster and a knife — kit that breaks the
    silhouette into something that has been lived in."""
    # hip pouches, hanging off the belt
    for side in (-1, 1):
        blob(mesh, (side * 0.128, 0.905, 0.062), (0.040, 0.052, 0.032), dark,
             [(B["PELVIS"], 1.0)], 8, 5)
        blob(mesh, (side * 0.150, 0.918, -0.030), (0.028, 0.046, 0.030), shade(dark, 1.1),
             [(B["PELVIS"], 1.0)], 8, 4)
    # thigh pouch on the left, holster on the right
    side = 1 if thigh > 0 else -1
    x = (THI_L if side > 0 else THI_R)[0]
    thi = B["THIGH_L"] if side > 0 else B["THIGH_R"]
    blob(mesh, (x + side * 0.080, 0.760, 0.020), (0.026, 0.058, 0.050), dark,
         [(thi, 1.0)], 8, 5)
    other = -side
    x2 = (THI_L if other > 0 else THI_R)[0]
    thi2 = B["THIGH_L"] if other > 0 else B["THIGH_R"]
    blob(mesh, (x2 + other * 0.076, 0.780, 0.030), (0.022, 0.055, 0.036), shade(dark, 0.85),
         [(thi2, 1.0)], 8, 5)


def build_backpack(mesh: Mesh, colour: RGB, trim: RGB) -> None:
    w = [(B["SPINE"], 0.45), (B["CHEST"], 0.55)]
    body = [(1.090, 0.120, 0.056, -0.150), (1.180, 0.132, 0.066, -0.168),
            (1.280, 0.130, 0.062, -0.168), (1.360, 0.108, 0.046, -0.150)]
    prev = None
    rings = []
    for i, (y, rx, rd, z) in enumerate(body):
        ids = mesh.ring((0.0, y, z), (1.0, 0.0, 0.0), (0.0, 0.0, 1.0), rx, rd, 12,
                        i / (len(body) - 1), colour, w, exponent=3.0)
        if prev is not None:
            mesh.bridge_rings(prev, ids)
        prev = ids
        rings.append(ids)
    mesh.cap(rings[0], (0.0, 1.086, -0.150), colour, w, flip=True)
    mesh.cap(rings[-1], (0.0, 1.364, -0.150), colour, w)
    blob(mesh, (0.0, 1.090, -0.196), (0.100, 0.030, 0.040), trim, w, 12, 6)
    # straps over both shoulders
    for side in (-1, 1):
        blob(mesh, (side * 0.082, 1.395, -0.060), (0.028, 0.058, 0.026), trim, w, 8, 6)


GEAR_CHOICES = ("cap", "helmet", "hood", "mask", "backpack", "nvg")


def build_headgear(mesh: Mesh, gear: Sequence[str], colour: RGB, trim: RGB) -> None:
    w = [(B["HEAD"], 1.0)]
    if "cap" in gear:
        cap = [(1.700, 0.079, 0.086, -0.004), (1.726, 0.077, 0.084, -0.006),
               (1.750, 0.068, 0.074, -0.008), (1.770, 0.046, 0.050, -0.010)]
        prev = None
        for i, (y, rx, rd, bias) in enumerate(cap):
            ids = mesh.ring((0.0, y, bias), (1.0, 0.0, 0.0), (0.0, 0.0, 1.0), rx * 1.05, rd * 1.05,
                            14, i / 3.0, colour, w, exponent=2.4)
            if prev is not None:
                mesh.bridge_rings(prev, ids)
            prev = ids
        # peak
        blob(mesh, (0.0, 1.706, 0.098), (0.082, 0.010, 0.052), shade(colour, 0.88), w, 14, 4)
    if "helmet" in gear:
        helm = [(1.664, 0.089, 0.096, -0.006), (1.694, 0.092, 0.098, -0.008),
                (1.726, 0.088, 0.094, -0.010), (1.756, 0.070, 0.076, -0.012),
                (1.782, 0.040, 0.044, -0.012)]
        prev = None
        for i, (y, rx, rd, bias) in enumerate(helm):
            ids = mesh.ring((0.0, y, bias), (1.0, 0.0, 0.0), (0.0, 0.0, 1.0), rx, rd, 16,
                            i / (len(helm) - 1), colour, w, exponent=2.6)
            if prev is not None:
                mesh.bridge_rings(prev, ids)
            prev = ids
        # brim + side rails
        blob(mesh, (0.0, 1.668, 0.080), (0.090, 0.014, 0.040), shade(colour, 0.8), w, 14, 4)
        for side in (-1, 1):
            blob(mesh, (side * 0.088, 1.700, 0.010), (0.012, 0.040, 0.055), trim, w, 8, 6)
    if "hood" in gear:
        blob(mesh, (0.0, 1.646, -0.072), (0.098, 0.112, 0.086), colour, w, 16, 10)
        blob(mesh, (0.0, 1.744, -0.020), (0.096, 0.048, 0.090), colour, w, 16, 8)
    if "mask" in gear:
        blob(mesh, (0.0, 1.578, 0.070), (0.062, 0.040, 0.040), colour, w, 12, 8)
        blob(mesh, (0.0, 1.596, 0.086), (0.054, 0.014, 0.026), shade(colour, 1.15), w, 12, 5)
    if "nvg" in gear:
        blob(mesh, (0.0, 1.700, 0.092), (0.050, 0.024, 0.030), trim, w, 12, 6)


# --------------------------------------------------------------------- assembly

class Palette:
    def __init__(self, skin: RGB, hair: RGB, vest: RGB, shirt: RGB,
                 pants: RGB, boots: RGB, gloves: RGB, sole: RGB | None = None) -> None:
        self.skin = skin
        self.hair = hair
        self.vest = vest
        self.shirt = shirt
        self.pants = pants
        self.boots = boots
        self.gloves = gloves
        self.sole = sole or shade(boots, 0.55)


def build(colours: Palette, gear: Sequence[str] = (), fem: bool = False,
          eye: RGB = (78, 96, 82), vest_kit: bool = True) -> Dict[str, Mesh]:
    """Builds every material group. Returns name → Mesh (unpacked)."""
    p = colours
    dark = shade(p.vest, 0.62)
    parts: Dict[str, Mesh] = {}

    body = Mesh()
    build_head(body, p.skin, fem)
    for side in (-1, 1):
        build_arm(body, side, p.skin, p.gloves)
    parts["body"] = body

    face = Mesh()
    build_face_parts(face, p.skin, eye)
    parts["face"] = face

    hair = Mesh()
    build_hair(hair, p.hair, fem)
    parts["hair"] = hair

    shirt = Mesh()
    # The torso and the two sleeves are ONE surface: the sleeve's innermost
    # stations are the torso's own vertices at the shoulder, so the shirt has no
    # seam, no interpenetration and no sleeve cap to pucker. Fitting a separate
    # tube over a torso never looks like clothing, however careful the radii.
    rings = []
    prev = None
    tr = torso_rings()
    SEGS = 16
    SH = 3                      # ring index of the clavicle shelf
    while len(tr) < SH + 1:
        tr.append(tr[-1])

    def at(ring_index: int, k: int) -> Vec:
        y, rx, rd, bias = tr[ring_index]
        a = TAU * (k % SEGS) / SEGS
        return (rx * math.cos(a), y, rd * math.sin(a) + bias)

    for i, (y, rx, rd, bias) in enumerate(tr):
        ids = shirt.ring((0.0, y, bias), (1.0, 0.0, 0.0), (0.0, 0.0, 1.0), rx, rd, SEGS,
                         i / (len(tr) - 1), p.shirt, weights_for_y(y), exponent=2.6)
        if prev is not None:
            shirt.bridge_rings(prev, ids)
        prev = ids
        rings.append(ids)
    shirt.cap(rings[0], (0.0, 0.840, 0.0), p.shirt, weights_for_y(0.84), flip=True)
    # flatten the back of the torso so the shirt is not a barrel
    for i in range(len(shirt.pos)):
        x, y, z = shirt.pos[i]
        if z < 0:
            shirt.pos[i] = (x, y, z * 0.88)
    # sleeves: shoulder to just below the elbow, following the arm
    for side in (-1, 1):
        sho = B["SHOULDER_L"] if side > 0 else B["SHOULDER_R"]
        arm = B["ARM_L"] if side > 0 else B["ARM_R"]
        fore = B["FORE_L"] if side > 0 else B["FORE_R"]
        hand = B["HAND_L"] if side > 0 else B["HAND_R"]
        sh = SHO_L if side > 0 else SHO_R
        el = ELB_L if side > 0 else ELB_R
        wr = WRI_L if side > 0 else WRI_R
        # Sleeve: one loft from inside the shoulder to the wrist. The top of the
        # tube starts *inside* the torso shell, so the join is hidden and the
        # fabric that emerges is the part that should be seen — over the
        # deltoid.
        # The sleeve is one path. It starts *inside* the torso as a nearly
        # closed point, rises out over the deltoid, then runs down the arm to
        # the wrist. Modelling the deltoid as a rise out of the shoulder rather
        # than a ball or a cap parked on top of it is what stops it reading as a
        # shoulder pad; starting inside the torso means the join is hidden and
        # no skin can show through the seam.
        # The deltoid IS the top of the sleeve, not a cap placed on it. The tube
        # starts high inside the torso shell, bulges out to the widest point of
        # the shoulder as it emerges, then tapers down the arm. The junction is
        # hidden inside the body, so there is nothing to seam, and every earlier
        # attempt that put a separate mass here — a flat cap, a ball, a raised
        # path — showed a crease or a pad where the two shells met.
        arm_path: List[Tuple[Vec, float, float, List[Tuple[int, float]]]] = [
            # The first two stations are *inside* the torso shell, above the
            # acromion, so the sleeve emerges from the garment rather than
            # starting beside it. A sleeve whose top ring sits below the
            # shoulder line always leaves a bare strip of skin at the tip.
            # The tops of these four rings have to stay under the trapezius
            # line (neck root y = 1.492), or the sleeve's upper surface becomes
            # a plateau above the shoulder that reads as a hard square pad. Ring
            # top = centre y + half-depth, so each station is placed by where its
            # *surface* belongs, not by where its centre goes.
            # Ring top = centre y + half-depth, and the tops of these stations
            # have to rise above the torso's own shoulder surface or a strip of
            # skin shows at the tip. They also must not flatten into a plateau:
            # the line they trace (1.478 → 1.470 → 1.452) is the trapezius,
            # sloping down and out, and the torso rings beneath it narrow along
            # the same arc so the two never cross.
            ((sh[0] * 0.62, sh[1] - 0.020, 0.0), 0.030, 0.028,
             [(B["CHEST"], 0.85), (sho, 0.15)]),
            ((sh[0] * 0.85, sh[1] - 0.026, 0.0), 0.046, 0.044,
             [(B["CHEST"], 0.5), (sho, 0.5)]),
            ((sh[0] * 0.98, sh[1] - 0.032, 0.0), 0.058, 0.054,
             [(B["CHEST"], 0.2), (sho, 0.8)]),
            ((sh[0] + 0.004, sh[1] - 0.052, 0.0), 0.062, 0.059, [(sho, 1.0)]),
            # The weights on the sleeve must be the *same function of t* that
            # the arm underneath uses, or the two surfaces shear apart when the
            # elbow bends and bare skin pokes through the cloth. So the blend is
            # computed, not hand-written per station.
            (v_lerp(sh, el, 0.22), 0.060, 0.059, _blend(sho, arm, 0.22)),
            (v_lerp(sh, el, 0.42), 0.056, 0.057, _blend(sho, arm, 0.42)),
            (v_lerp(sh, el, 0.62), 0.052, 0.053, _blend(sho, arm, 0.62)),
            (v_lerp(sh, el, 0.82), 0.049, 0.050, _blend(sho, arm, 0.82)),
            (v_lerp(sh, el, 0.97), 0.049, 0.050, _blend(sho, arm, 0.97)),
            # past the joint in two steps, so the elbow crease does not pinch open
            (v_lerp(el, wr, 0.05), 0.050, 0.050, _blend(arm, hand, 0.05)),
            (v_lerp(el, wr, 0.28), 0.042, 0.042, _blend(arm, hand, 0.28)),
            (v_lerp(el, wr, 0.60), 0.034, 0.034, _blend(arm, hand, 0.60)),
            (v_lerp(el, wr, 0.92), 0.029, 0.029, _blend(arm, hand, 0.92)),
        ]
        centres = [st_[0] for st_ in arm_path]
        prev_ring = None
        first_ring = None
        for i, (c, rx, ry, weights) in enumerate(arm_path):
            a = centres[max(0, i - 1)]
            b = centres[min(len(centres) - 1, i + 1)]
            ids = shirt.ring(c, *frame_for(a, b), rx, ry, 12,
                             i / (len(arm_path) - 1), p.shirt, weights, exponent=2.2)
            if prev_ring is not None:
                shirt.bridge_rings(prev_ring, ids)
            else:
                first_ring = ids
            prev_ring = ids
        shirt.cap(prev_ring, arm_path[-1][0], p.shirt, [(arm, 1.0)])
        # Close the end that sits inside the torso. An open tube is invisible in
        # a wireframe and fatal in a renderer with back-face culling: the hole
        # reads as a transparent window onto whatever is behind it, which here
        # was bare skin — the "torn shoulder" look.
        shirt.cap(first_ring, arm_path[0][0], p.shirt,
                  [(B["CHEST"], 0.7), (sho, 0.3)], flip=True)
        # cuff: a proud band at the wrist where the glove starts
        prev = prev_ring
        for i, t in enumerate((0.90, 0.97)):
            c = v_lerp(el, wr, t)
            a, b = v_lerp(sh, el, 0.6), v_lerp(el, wr, 1.0)
            ids = shirt.ring(c, *frame_for(a, b), 0.0285, 0.0295, 12, i,
                             shade(p.shirt, 0.86), [(arm, 0.35), (hand, 0.65)], exponent=2.2)
            shirt.bridge_rings(prev, ids)
            prev = ids
    # collar
    prev = None
    for i, (y, rx, rd) in enumerate(((1.470, 0.076, 0.068), (1.484, 0.068, 0.062),
                                     (1.492, 0.062, 0.057))):
        ids = shirt.ring((0.0, y, -0.004), (1.0, 0.0, 0.0), (0.0, 0.0, 1.0), rx, rd, 14, i,
                         shade(p.shirt, 1.1), [(B["CHEST"], 0.4), (B["NECK"], 0.6)], exponent=2.3)
        if prev is not None:
            shirt.bridge_rings(prev, ids)
        prev = ids
    parts["shirt"] = shirt

    pants = Mesh()
    # The seat stops at the crotch; below that the two legs are separate lofts,
    # which is how trousers actually hang. A single block down to mid-thigh
    # reads as a skirt with legs poking out of it.
    prings = [
        (0.845, 0.140, 0.096, 0.000),
        (0.880, 0.148, 0.098, 0.000),
        (0.925, 0.150, 0.099, 0.002),
        (0.965, 0.146, 0.097, 0.004),
        (1.000, 0.138, 0.094, 0.006),
    ]
    prev = None
    prs = []
    for i, (y, rx, rd, bias) in enumerate(prings):
        ids = pants.ring((0.0, y, bias), (1.0, 0.0, 0.0), (0.0, 0.0, 1.0), rx, rd, 16,
                         i / (len(prings) - 1), p.pants, weights_for_y(y), exponent=2.5)
        if prev is not None:
            pants.bridge_rings(prev, ids)
        prev = ids
        prs.append(ids)
    pants.cap(prs[0], (0.0, 0.840, 0.0), p.pants, [(B["PELVIS"], 1.0)], flip=True)
    for i in range(len(pants.pos)):
        x_, y_, z_ = pants.pos[i]
        if z_ < 0:
            pants.pos[i] = (x_, y_, z_ * 0.9)
    # crotch gusset: closes the gap the two legs would otherwise leave
    blob(pants, (0.0, 0.842, 0.006), (0.086, 0.042, 0.076), p.pants,
         [(B["PELVIS"], 1.0)], 12, 8)
    for side in (-1, 1):
        build_leg(pants, side, p.pants, p.boots, p.skin)
    parts["pants"] = pants

    boots = Mesh()
    for side in (-1, 1):
        build_boot(boots, side, p.boots, p.sole)
    parts["boots"] = boots

    gloves = Mesh()
    for side in (-1, 1):
        build_hand(gloves, side, p.gloves, p.gloves)
        build_glove_cuff(gloves, side, shade(p.gloves, 0.88))
    parts["gloves"] = gloves

    if vest_kit:
        vestm = Mesh()
        build_vest(vestm, p.vest, dark)
        build_pouches(vestm, dark, 1)
        parts["vest"] = vestm

    gearm = Mesh()
    build_headgear(gearm, gear, p.vest, dark)
    if "backpack" in gear:
        build_backpack(gearm, shade(p.vest, 1.05), dark)
    if gearm.pos:
        parts["gear"] = gearm
    return parts


def _sleeve_frame_points(st: Sequence[Station], i: int) -> Tuple[Vec, Vec]:
    n = len(st)
    return st[max(0, i - 1)].centre, st[min(n - 1, i + 1)].centre


def frame_for(a: Vec, b: Vec) -> Tuple[Vec, Vec]:
    axis = v_norm(v_sub(b, a))
    x_axis = (1.0, 0.0, 0.0) if abs(axis[0]) < 0.9 else (0.0, 0.0, 1.0)
    x_axis = v_norm(v_cross(v_cross(axis, x_axis), axis))
    y_axis = v_norm(v_cross(axis, x_axis))
    if v_dot(y_axis, (0.0, 0.0, 1.0)) < 0:
        y_axis = v_scale(y_axis, -1.0)
        x_axis = v_scale(x_axis, -1.0)
    return x_axis, y_axis
