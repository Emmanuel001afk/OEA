#!/usr/bin/env python3
"""Anatomy-first humanoid mesh builder for OEA characters.

The first generation of these soldiers was assembled from cylinders and
spheres: an ellipsoid head on a thin neck, tubes for arms with a ball for a
hand, a box for a boot. Every part was right in isolation and the assembly
read as a mannequin, because a human silhouette is not made of primitives — it
is made of things that *taper*, and of masses (deltoid, pectoral, quadriceps,
calf, glute, jaw) that sit on the outside of the taper.

So this module does not place primitives. It lofts cross-sections along a path:
a limb is a station list from shoulder to wrist, each station carrying its own
half-width, half-depth and a centre offset so the mass can sit to the front of
the bone (biceps) or to the back (triceps, calf). Cross-sections are
super-ellipses, so the torso can be a rounded rectangle (wide chest, flat back,
flatter belly) instead of an egg. Normals are welded per vertex at the end, so
the surface shades smoothly rather than faceting.

Proportions target a 1.79 m athletic adult at ~7.5 heads, with the skeleton
from `concept_to_oeac.OFFSETS` as the hard constraint: joints land where the
mesh's knee, elbow and ankle actually are.

Everything is weighted to OEA's 19 bones as it is generated, so the same pose
code that animates the old mesh animates this one.
"""

from __future__ import annotations

import math
from typing import Dict, List, Sequence, Tuple

Vec = Tuple[float, float, float]
RGB = Tuple[int, int, int]
Weight = Tuple[int, float]

TAU = math.tau


# --------------------------------------------------------------------- maths

def v_add(a: Vec, b: Vec) -> Vec:
    return (a[0] + b[0], a[1] + b[1], a[2] + b[2])


def v_sub(a: Vec, b: Vec) -> Vec:
    return (a[0] - b[0], a[1] - b[1], a[2] - b[2])


def v_scale(a: Vec, k: float) -> Vec:
    return (a[0] * k, a[1] * k, a[2] * k)


def v_dot(a: Vec, b: Vec) -> float:
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]


def v_cross(a: Vec, b: Vec) -> Vec:
    return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])


def v_len(a: Vec) -> float:
    return math.sqrt(v_dot(a, a))


def v_norm(a: Vec) -> Vec:
    length = v_len(a)
    return (a[0] / length, a[1] / length, a[2] / length) if length > 1e-9 else (0.0, 1.0, 0.0)


def v_lerp(a: Vec, b: Vec, t: float) -> Vec:
    return (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t)


def smoothstep(a: float, b: float, x: float) -> float:
    if abs(b - a) < 1e-9:
        return 0.0 if x < a else 1.0
    t = max(0.0, min(1.0, (x - a) / (b - a)))
    return t * t * (3.0 - 2.0 * t)


def clamp(x: float, a: float, b: float) -> float:
    return a if x < a else b if x > b else x


def shade(c: RGB, k: float) -> RGB:
    return (int(clamp(c[0] * k, 0, 255)), int(clamp(c[1] * k, 0, 255)), int(clamp(c[2] * k, 0, 255)))


def mix(a: RGB, b: RGB, t: float) -> RGB:
    return (int(a[0] + (b[0] - a[0]) * t), int(a[1] + (b[1] - a[1]) * t), int(a[2] + (b[2] - a[2]) * t))


# --------------------------------------------------------------------- mesh

class Mesh:
    """Positions + uvs + colours + bone weights, with welded normals."""

    def __init__(self) -> None:
        self.pos: List[Vec] = []
        self.uv: List[Tuple[float, float]] = []
        self.col: List[RGB] = []
        self.w: List[List[Weight]] = []
        self.idx: List[int] = []

    def vert(self, p: Vec, uv: Tuple[float, float], colour: RGB, weights: Sequence[Weight]) -> int:
        self.pos.append(p)
        self.uv.append(uv)
        self.col.append(colour)
        self.w.append(list(weights))
        return len(self.pos) - 1

    def tri(self, a: int, b: int, c: int) -> None:
        self.idx.extend((a, b, c))

    def quad(self, a: int, b: int, c: int, d: int) -> None:
        self.tri(a, b, c)
        self.tri(a, c, d)

    # -- geometry helpers -------------------------------------------------

    def ring(
        self,
        centre: Vec, x_axis: Vec, y_axis: Vec,
        rx: float, ry: float, segs: int, uv_v: float,
        colour: RGB, weights: Sequence[Weight], exponent: float = 2.0,
        twist: float = 0.0,
    ) -> List[int]:
        """One cross-section: a super-ellipse in the plane of x_axis/y_axis."""
        ids: List[int] = []
        e = 2.0 / exponent          # exponent 2 = ellipse, larger = boxier
        for k in range(segs + 1):
            a = TAU * k / segs + twist
            ca, sa = math.cos(a), math.sin(a)
            cx = math.copysign(abs(ca) ** e, ca) if e != 1.0 else ca
            sy = math.copysign(abs(sa) ** e, sa) if e != 1.0 else sa
            p = (centre[0] + x_axis[0] * rx * cx + y_axis[0] * ry * sy,
                 centre[1] + x_axis[1] * rx * cx + y_axis[1] * ry * sy,
                 centre[2] + x_axis[2] * rx * cx + y_axis[2] * ry * sy)
            ids.append(self.vert(p, (k / segs, uv_v), colour, weights))
        return ids

    @staticmethod
    def bridge(a: Sequence[int], b: Sequence[int]) -> None:  # pragma: no cover - doc aid
        raise NotImplementedError

    def bridge_rings(self, a: Sequence[int], b: Sequence[int]) -> None:
        for k in range(len(a) - 1):
            self.quad(a[k], a[k + 1], b[k + 1], b[k])

    def cap(self, ids: Sequence[int], centre: Vec, colour: RGB, weights: Sequence[Weight], flip: bool = False) -> None:
        c = self.vert(centre, (0.5, 0.5), colour, weights)
        for k in range(len(ids) - 1):
            if flip:
                self.tri(ids[k + 1], ids[k], c)
            else:
                self.tri(ids[k], ids[k + 1], c)

    # -- output -----------------------------------------------------------

    def components(self) -> List[List[int]]:
        """Triangle indices grouped by connected shell (union-find over edges)."""
        parent = list(range(len(self.pos)))

        def find(a: int) -> int:
            while parent[a] != a:
                parent[a] = parent[parent[a]]
                a = parent[a]
            return a

        def union(a: int, b: int) -> None:
            ra, rb = find(a), find(b)
            if ra != rb:
                parent[rb] = ra

        tri_count = len(self.idx) // 3
        for t in range(tri_count):
            a, b, c = self.idx[t * 3], self.idx[t * 3 + 1], self.idx[t * 3 + 2]
            union(a, b)
            union(b, c)
        groups: Dict[int, List[int]] = {}
        for t in range(tri_count):
            groups.setdefault(find(self.idx[t * 3]), []).append(t)
        return list(groups.values())

    def orient_outward(self) -> None:
        """Make every shell present its outside to a back-face-culling renderer.

        Two tests are tempting here and both are wrong. A single global test
        cannot see it: the torso volume swamps a sleeve. A signed-volume test
        per shell cannot see it either, because a sleeve is an *open* tube and
        an open tube's signed volume is ~0 whichever way it winds.

        What does work for both closed and open shells is direction: take the
        shell's centroid and ask whether its triangles face away from it. A
        limb tube, a capped torso and a glove all answer correctly.
        """
        for group in self.components():
            ca = cb = cc = 0.0
            verts = set()
            for t in group:
                for k in (0, 1, 2):
                    verts.add(self.idx[t * 3 + k])
            n = float(len(verts)) or 1.0
            for v in verts:
                p = self.pos[v]
                ca += p[0]
                cb += p[1]
                cc += p[2]
            ca, cb, cc = ca / n, cb / n, cc / n
            score = 0.0
            for t in group:
                pa = self.pos[self.idx[t * 3]]
                pb = self.pos[self.idx[t * 3 + 1]]
                pc = self.pos[self.idx[t * 3 + 2]]
                fn = v_cross(v_sub(pb, pa), v_sub(pc, pa))
                mid = ((pa[0] + pb[0] + pc[0]) / 3.0 - ca,
                       (pa[1] + pb[1] + pc[1]) / 3.0 - cb,
                       (pa[2] + pb[2] + pc[2]) / 3.0 - cc)
                score += v_dot(fn, mid)
            if score < 0.0:
                for t in group:
                    self.idx[t * 3 + 1], self.idx[t * 3 + 2] = self.idx[t * 3 + 2], self.idx[t * 3 + 1]

    def build(self, vertex_offset: int = 0) -> dict:
        self.orient_outward()
        n = [0.0] * (len(self.pos) * 3)
        for t in range(0, len(self.idx) - 2, 3):
            a, b, c = self.idx[t], self.idx[t + 1], self.idx[t + 2]
            pa, pb, pc = self.pos[a], self.pos[b], self.pos[c]
            fn = v_cross(v_sub(pb, pa), v_sub(pc, pa))
            length = v_len(fn)
            if length < 1e-12:
                continue
            fn = v_scale(fn, 1.0 / length)
            for v in (a, b, c):
                n[v * 3] += fn[0]
                n[v * 3 + 1] += fn[1]
                n[v * 3 + 2] += fn[2]
        self.normal = n
        return {"pos": self.pos, "normal": n, "uv": self.uv, "col": self.col,
                "w": self.w, "idx": self.idx}


# --------------------------------------------------------------------- station lofts

class Station:
    """One cross-section along a lofted part.

    `centre` is the axis point, `rx`/`ry` the half-width and half-depth in the
    section's own frame, `bias` shifts the section's centre in local +Z (so a
    biceps mass sits forward of the bone and a calf sits behind it), `blend` is
    the weight given to the far bone (0 = all near bone, 1 = all far bone).
    """

    __slots__ = ("centre", "rx", "ry", "bias", "blend", "exponent", "colour")

    def __init__(self, centre: Vec, rx: float, ry: float, bias: float = 0.0,
                 blend: float = 0.0, exponent: float = 2.0, colour: RGB | None = None) -> None:
        self.centre = centre
        self.rx = rx
        self.ry = ry
        self.bias = bias
        self.blend = blend
        self.exponent = exponent
        self.colour = colour


def station_frame(prev: Vec, centre: Vec, nxt: Vec) -> Tuple[Vec, Vec, Vec]:
    """Swept frame at one station: tangent, width axis, depth axis.

    The old code used a single axis for a whole limb, which is fine for a
    straight tube and wrong the moment the path bends — a sleeve that curves
    over the deltoid then runs down the arm would shear its cross-sections.
    Here every station gets its own tangent from its neighbours, and the depth
    axis is world +Z projected perpendicular to that tangent, so "forward"
    means the same thing all the way down the limb.
    """
    tangent = v_norm(v_sub(nxt, prev))
    zw = (0.0, 0.0, 1.0)
    depth = v_sub(zw, v_scale(tangent, v_dot(zw, tangent)))
    if v_len(depth) < 1e-4:
        depth = v_sub((0.0, 1.0, 0.0), v_scale(tangent, v_dot((0.0, 1.0, 0.0), tangent)))
    depth = v_norm(depth)
    width = v_norm(v_cross(depth, tangent))
    return tangent, width, depth


def loft(
    mesh: Mesh,
    stations: Sequence[Station],
    bone_a: int, bone_b: int,
    colour: RGB,
    segs: int = 16,
    cap_a: bool = True, cap_b: bool = True,
    weights_override=None,
) -> None:
    """Loft a column of stations along a swept path."""
    n = len(stations)
    centres = [s.centre for s in stations]
    prev_ring: List[int] | None = None
    rings: List[List[int]] = []
    for i, s in enumerate(stations):
        a = centres[max(0, i - 1)]
        b = centres[min(n - 1, i + 1)]
        if i == 0:
            a = v_sub(centres[0], v_sub(centres[1], centres[0]))
        if i == n - 1:
            b = v_add(centres[-1], v_sub(centres[-1], centres[-2]))
        _, width, depth = station_frame(a, centres[i], b)
        centre = v_add(s.centre, v_scale(depth, s.bias))
        colour_i = s.colour or colour
        if weights_override is not None:
            weights = weights_override(i, s)
        else:
            weights = [(bone_a, 1.0 - s.blend), (bone_b, s.blend)]
        ids = mesh.ring(centre, width, depth, s.rx, s.ry, segs, i / max(1, n - 1),
                        colour_i, weights, s.exponent)
        if prev_ring is not None:
            mesh.bridge_rings(prev_ring, ids)
        prev_ring = ids
        rings.append(ids)
    if cap_a and rings:
        w = weights_override(0, stations[0]) if weights_override else [(bone_a, 1.0)]
        _, _, depth0 = station_frame(centres[0], centres[0], centres[1])
        mesh.cap(rings[0], v_add(centres[0], v_scale(depth0, stations[0].bias)),
                 stations[0].colour or colour, w, flip=True)
    if cap_b and rings:
        w = weights_override(n - 1, stations[-1]) if weights_override else [(bone_b, 1.0)]
        _, _, depth1 = station_frame(centres[-2], centres[-1], centres[-1])
        mesh.cap(rings[-1], v_add(centres[-1], v_scale(depth1, stations[-1].bias)),
                 stations[-1].colour or colour, w, flip=False)


def blob(
    mesh: Mesh, centre: Vec, radii: Vec, colour: RGB, weights: Sequence[Weight],
    segs: int = 16, rings: int = 10, phi_max: float = math.pi,
    squash_y: float = 1.0,
) -> None:
    """A smooth ellipsoid mass (deltoid, calf head, glute, cheek)."""
    base = len(mesh.pos)
    for j in range(rings + 1):
        phi = phi_max * j / rings
        sp, cp = math.sin(phi), math.cos(phi)
        for k in range(segs + 1):
            theta = TAU * k / segs
            st, ct = math.sin(theta), math.cos(theta)
            p = (centre[0] + radii[0] * sp * ct,
                 centre[1] + radii[1] * cp * squash_y,
                 centre[2] + radii[2] * sp * st)
            mesh.vert(p, (k / segs, j / rings), colour, weights)
    for j in range(rings):
        for k in range(segs):
            a = base + j * (segs + 1) + k
            mesh.quad(a, a + 1, a + segs + 2, a + segs + 1)


def limb(
    mesh: Mesh, a: Vec, b: Vec, profile: Sequence[Tuple[float, float, float, float]],
    bone_a: int, bone_b: int, colour: RGB, segs: int = 14,
    blend_from: float = 0.58, cap_a: bool = True, cap_b: bool = True,
) -> None:
    """A limb between two joints from a (t, rx, ry, bias) profile.

    `t` 0 = joint A, 1 = joint B. Weights blend from A to B over the last
    `1 - blend_from` of the segment, which puts the bend exactly at joint B.
    """
    stations = []
    for (t, rx, ry, bias) in profile:
        blend = smoothstep(blend_from, 1.0, t)
        stations.append(Station(v_lerp(a, b, t), rx, ry, bias, blend))
    loft(mesh, stations, bone_a, bone_b, colour, segs, cap_a=cap_a, cap_b=cap_b)


# --------------------------------------------------------------------- export

def to_oeac_part(name: str, mesh: Mesh, texture: str = "", flags: int = 0) -> dict:
    """Packs a [Mesh] into the `.oeac` part layout (`glb_to_oeac` writes it)."""
    import glb_to_oeac as oeac

    built = mesh.build()
    pos, nrm, uv, col, weights = built["pos"], built["normal"], built["uv"], built["col"], built["w"]
    vertices: List[float] = []
    for i in range(len(pos)):
        linear = [oeac.srgb_to_linear(c / 255.0) for c in col[i]]
        w = sorted(weights[i], key=lambda kv: -kv[1])[:4]
        total = sum(k for _, k in w) or 1.0
        vertices.extend([pos[i][0], pos[i][1], pos[i][2],
                         nrm[i * 3], nrm[i * 3 + 1], nrm[i * 3 + 2],
                         uv[i][0], uv[i][1],
                         linear[0], linear[1], linear[2], 1.0])
        # The stride is 20 floats laid out exactly as SkeletalMesh.Builder writes
        # it: four bone indices, then four weights. Interleaving them puts the
        # weights where the shader reads bone ids, and every vertex collapses to
        # bone 0 — the mesh renders, it just never deforms.
        for k in range(4):
            vertices.append(float(w[k][0]) if k < len(w) else 0.0)
        for k in range(4):
            vertices.append(w[k][1] / total if k < len(w) else 0.0)
    colour = col[0] if col else (255, 255, 255)
    argb = (255 << 24) | (colour[0] << 16) | (colour[1] << 8) | colour[2]
    return {"name": name, "texture": texture, "color": argb, "flags": flags,
            "vertices": vertices, "indices": list(built["idx"])}
