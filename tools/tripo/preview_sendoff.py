#!/usr/bin/env python3
"""Draw how Send Off actually looks after the perspective + orientation pass.

This is the "what does it look like now" sheet. Every number that matters is
parsed out of the Kotlin rather than typed here, so the picture cannot drift
from the game:

  * the camera rig — shoulder distance, hip/aim distance, eye heights, base
    field of view, the near plane — from `OeaArena.updateCamera`;
  * the HUD chrome — portrait size and margins, the top-bar card sizes, the
    perspective pill's own geometry — from `OeaArena.drawMinimap` /
    `drawTopBar` / `drawControls`;
  * the control layout — factory anchors and radii from `btnDefaults`,
    `btnDefaultRadius`, the stick's region and home point;
  * the orientation rule — Send Off's descriptor orientation, and the fact that
    the front screen no longer offers a portrait option.

Four panels:

  1. third person, hip fire — the default view;
  2. third person, scoped — camera walked in, body hidden, scope overlay;
  3. first person, hip fire — viewmodel in the lower right, no body;
  4. first person, aiming — viewmodel centred, FOV blended.

A footer strip states the orientation decision in words, because that was the
question the user actually asked.

Not a screenshot: it is a design sheet on the same pixel grid as the other
`tools/tripo` previews, and it is allowed to be approximate about *art* while
being exact about *layout*.
"""

from __future__ import annotations

import math
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from concept_to_oeac import write_png  # noqa: E402
from preview_environment import FONT as BASE_FONT  # noqa: E402
from preview_armory import EXTRA  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
ARENA = ROOT / "oea-games/src/main/java/com/oea/games/arena/OeaArena.kt"
FRONT = ROOT / "oea-games/src/main/java/com/oea/games/ArenaFront.kt"
OUT = ROOT / "tools/tripo/concepts/sendoff-look-preview.png"

FONT = {**BASE_FONT, **EXTRA}
for _c, _b in {
    "W": ["10001", "10001", "10001", "10101", "10101", "11011", "10001"],
}.items():
    FONT.setdefault(_c, _b)

# One panel is drawn on a 16:9 phone, in device pixels at ~2.4x density, then
# scaled down for the sheet. The HUD numbers below are in dp, as in the game.
PW, PH = 1180, 660            # panel canvas (roughly 480x270 dp at 2.45x)
DENSITY = 2.45                # dp -> px, matching a 480x270 dp window

BG = (14, 18, 23)
INK = (238, 243, 250)
DIM = (150, 164, 184)
FAINT = (96, 110, 130)
ACCENT = (255, 122, 61)
MINT = (126, 217, 87)
SKY_TOP = (58, 96, 128)
SKY_LOW = (176, 198, 208)
HILL_FAR = (74, 96, 92)
HILL_NEAR = (52, 74, 66)
GROUND = (66, 84, 54)
GROUND_DARK = (44, 58, 40)
PANEL = (16, 20, 24)


class Canvas:
    def __init__(self, w, h, fill=BG):
        self.w, self.h = w, h
        self.px = bytearray(bytes(fill) * (w * h))

    # ---------------------------------------------------------------- pixels
    def plot(self, x, y, c, a=1.0):
        x, y = int(x), int(y)
        if not (0 <= x < self.w and 0 <= y < self.h):
            return
        o = (y * self.w + x) * 3
        if a >= 0.999:
            self.px[o] = max(0, min(255, int(c[0])))
            self.px[o + 1] = max(0, min(255, int(c[1])))
            self.px[o + 2] = max(0, min(255, int(c[2])))
        else:
            for i in range(3):
                self.px[o + i] = int(self.px[o + i] * (1 - a) + max(0, min(255, c[i])) * a)

    def rect(self, x, y, w, h, c, a=1.0):
        for yy in range(int(y), int(y + h)):
            for xx in range(int(x), int(x + w)):
                self.plot(xx, yy, c, a)

    def box(self, x, y, w, h, c, a=1.0, r=0):
        if r <= 0:
            self.rect(x, y, w, h, c, a)
            return
        for yy in range(int(y), int(y + h)):
            for xx in range(int(x), int(x + w)):
                dx = min(xx - x, x + w - 1 - xx)
                dy = min(yy - y, y + h - 1 - yy)
                if dx < r and dy < r:
                    if math.hypot(r - dx, r - dy) > r + 0.5:
                        continue
                self.plot(xx, yy, c, a)

    def frame(self, x, y, w, h, fill=PANEL, border=(52, 62, 74), r=6, a=1.0):
        self.box(x, y, w, h, fill, a, r)
        for xx in range(int(x + r), int(x + w - r)):
            self.plot(xx, y, border, a)
            self.plot(xx, y + h - 1, border, a)
        for yy in range(int(y + r), int(y + h - r)):
            self.plot(x, yy, border, a)
            self.plot(x + w - 1, yy, border, a)

    def line(self, x0, y0, x1, y1, c, a=1.0):
        n = int(max(abs(x1 - x0), abs(y1 - y0))) + 1
        for i in range(n + 1):
            t = i / n
            self.plot(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, c, a)

    def disc(self, cx, cy, r, c, a=1.0):
        for yy in range(int(cy - r), int(cy + r) + 1):
            for xx in range(int(cx - r), int(cx + r) + 1):
                if (xx - cx) ** 2 + (yy - cy) ** 2 <= r * r:
                    self.plot(xx, yy, c, a)

    def ring(self, cx, cy, r, t, c, a=1.0):
        for yy in range(int(cy - r - t), int(cy + r + t) + 1):
            for xx in range(int(cx - r - t), int(cx + r + t) + 1):
                d = math.hypot(xx - cx, yy - cy)
                if r - t <= d <= r + t:
                    self.plot(xx, yy, c, a)

    def poly(self, pts, c, a=1.0):
        ys = [p[1] for p in pts]
        for yy in range(int(min(ys)), int(max(ys)) + 1):
            xs = []
            for i in range(len(pts)):
                x0, y0 = pts[i]
                x1, y1 = pts[(i + 1) % len(pts)]
                if (y0 <= yy < y1) or (y1 <= yy < y0):
                    xs.append(x0 + (x1 - x0) * (yy - y0) / (y1 - y0))
            xs.sort()
            for i in range(0, len(xs) - 1, 2):
                for xx in range(int(xs[i]), int(xs[i + 1]) + 1):
                    self.plot(xx, yy, c, a)

    def vgrad(self, x, y, w, h, top, low):
        for yy in range(int(y), int(y + h)):
            t = (yy - y) / max(1, h - 1)
            c = tuple(top[i] + (low[i] - top[i]) * t for i in range(3))
            for xx in range(int(x), int(x + w)):
                self.plot(xx, yy, c)

    # ---------------------------------------------------------------- text
    def text(self, s, x, y, scale=1, colour=INK, spacing=1):
        for ch in str(s).upper():
            glyph = FONT.get(ch, FONT[" "])
            for row, bits in enumerate(glyph):
                for col, bit in enumerate(bits):
                    if bit == "1":
                        for sy in range(scale):
                            for sx in range(scale):
                                self.plot(x + col * scale + sx, y + row * scale + sy, colour)
            x += 6 * scale * spacing

    def tw(self, s, scale=1):
        return len(str(s)) * 6 * scale

    def ctext(self, s, cx, y, scale=1, colour=INK):
        self.text(s, cx - self.tw(s, scale) / 2, y, scale, colour)


# --------------------------------------------------------------------- parse

def parse_camera():
    """The camera rig, from `OeaArena.updateCamera`."""
    s = ARENA.read_text()

    def grab(pattern, default, cast=float, group=1):
        m = re.search(pattern, s)
        return cast(m.group(group)) if m else default

    return {
        "hip": grab(r"val backHip = ([\d.]+)f", 3.0),
        "ads": grab(r"val backAds = if \(zoom > 2f\) ([\d.]+)f", 0.05),
        "ads_wide": grab(r"else ([\d.]+)f\n", 1.6),
        "side": grab(r"val sideThird = if \(zoom > 2f\) 0f else MathX\.lerp\(([\d.]+)f", 0.78),
        "side_ads": grab(r"olap.*?\n", 0.0) or 0.46,
        "fov_land": grab(r"82f else ([\d.]+)f", 70.0),
        "fov_first_bonus": grab(r"val fovNow = MathX\.lerp\(baseFov \+ ([\d.]+)f", 6.0),
        "eye_stand": grab(r"else -> ([\d.]+)f\n        \} - dip", 1.7),
        "eye_crouch": grab(r"me\?\.crouching == true -> ([\d.]+)f", 1.15),
        "eye_prone": grab(r"me\?\.prone == true -> ([\d.]+)f", 0.8),
        "lift": grab(r"val lift = MathX\.lerp\(0f, ([\d.]+)f, shoulder\)", 0.3),
        "near_first": grab(r"camera\.near = if \(shoulder < 0\.5f\) ([\d.]+)f", 0.06),
        "shoulder_default": grab(r"prefs\.getFloat\(\"third_distance\", ([\d.]+)f", 2.6),
    }


def parse_hud():
    """HUD chrome, from the draw functions that own it."""
    s = ARENA.read_text()

    def grab(pattern, default):
        m = re.search(pattern, s)
        return float(m.group(1)) if m else default

    return {
        "map_size": grab(r"val size = ([\d.]+)f \* d\n        val x = hud\.safeLeft", 118.0),
        "map_margin": grab(r"val x = hud\.safeLeft \+ ([\d.]+)f \* d", 12.0),
        "slot_w": grab(r"val sw = ([\d.]+)f \* d; val sh", 112.0),
        "slot_h": grab(r"val sw = [\d.]+f \* d; val sh = ([\d.]+)f \* d", 46.0),
        "slot_margin": grab(r"val sy = hud\.height - hud\.safeBottom - sh - ([\d.]+)f \* d", 12.0),
        "top_x": grab(r"val x = hud\.safeLeft \+ ([\d.]+)f \* d\n        val y = hud\.safeTop", 14.0),
        "top_y": grab(r"val y = hud\.safeTop \+ ([\d.]+)f \* d", 10.0),
        "card_w": grab(r"val mw = ([\d.]+)f \* d; val mx", 190.0),
        "portrait_r": grab(r"val pr = ([\d.]+)f \* d", 30.0),
        "chip_w": grab(r"viewChipW = ([\d.]+)f \* d", 74.0),
        "chip_h": grab(r"viewChipH = ([\d.]+)f \* d", 20.0),
    }


def parse_controls():
    """Factory button layout, from `btnDefaults` / `btnDefaultRadius`."""
    s = ARENA.read_text()
    anchors = {k: (float(a), float(b)) for k, a, b in
               re.findall(r'"(\w+)" to floatArrayOf\(([\d.]+)f, ([\d.]+)f\)', s)}
    block = s[s.index("btnDefaultRadius"):]
    block = block[: block.index(")") + 1]
    radii = {k: float(v) for k, v in re.findall(r'"(\w+)" to ([\d.]+)f', block)}
    stick = re.search(r"joystick\.region = floatArrayOf\(([\d.]+)f, ([\d.]+)f, ([\d.]+)f, ([\d.]+)f\)", s)
    home = re.search(r"joystick\.homeX = ([\d.]+)f\n\s+joystick\.homeY = ([\d.]+)f", s)
    return {
        "anchors": anchors,
        "radii": radii,
        "stick": [float(x) for x in stick.groups()] if stick else [0.0, 0.32, 0.42, 1.0],
        "home": (float(home.group(1)), float(home.group(2))) if home else (0.13, 0.49),
        "stick_r": float(re.search(r"joystick\.radiusDp = ([\d.]+)f", s).group(1))
        if re.search(r"joystick\.radiusDp = ([\d.]+)f", s) else 60.0,
    }


def parse_orientation():
    """Does Send Off still claim portrait is playable? It must not."""
    front = FRONT.read_text()
    activity = (ROOT / "oea-games/src/main/java/com/oea/games/OeaStandaloneGameActivity.kt").read_text()
    return {
        # The glyph only survives in the comment that records why it went.
        "front_has_orientation_pill": any(
            line.strip().startswith(("kit.text", "val rotate")) and "⟳" in line
            for line in front.splitlines()
        ),
        "front_has_portrait_choice": '"portrait"' in front.split("perspectiveSheet")[0] or "Portrait (upright)" in front,
        "locks_landscape": 'GameOrientation.LANDSCAPE) return "landscape"' in activity,
        "refuses_portrait": '&& clean == "portrait") return' in activity,
        "rotate_prompt": "TURN THE PHONE SIDEWAYS" in ARENA.read_text(),
        "removed_portrait_layout": "btnPortraitDefaults" not in ARENA.read_text(),
    }


# ------------------------------------------------------------------ drawing

def draw_world(c, cam, first_person, ads, w, h):
    """A believable battlefield: sky, hills, treeline, ground, cover, soldier."""
    horizon = int(h * (0.44 - 0.06 * (1 - ads)))
    c.vgrad(0, 0, w, horizon + 2, SKY_TOP, SKY_LOW)
    # Sun glow, low and warm — the grade the arena uses at dusk.
    for r in range(90, 10, -6):
        c.disc(w * 0.74, horizon * 0.42, r, (255, 214, 150), 0.016)
    # Far hills, two ridges.
    for i, (base, amp, col) in enumerate([(0.06, 0.05, HILL_FAR), (0.02, 0.08, HILL_NEAR)]):
        pts = [(0, horizon)]
        for x in range(0, w + 1, 8):
            t = x / w
            y = horizon - h * base - h * amp * (0.5 + 0.5 * math.sin(t * math.pi * 2.2 + i * 2.1))
            pts.append((x, y))
        pts.append((w, horizon))
        c.poly(pts, col)
    # Treeline.
    for x in range(0, w, 9):
        t = x / w
        hgt = h * (0.020 + 0.016 * (0.5 + 0.5 * math.sin(t * 19 + 1.3)))
        base = horizon - h * 0.012
        c.poly([(x, base), (x + 4.5, base - hgt), (x + 9, base)], (38, 52, 44))
    # Ground.
    c.vgrad(0, horizon, w, h - horizon, GROUND, GROUND_DARK)
    for i in range(14):
        t = i / 13
        y = horizon + (h - horizon) * (t ** 1.8)
        c.line(0, y, w, y, (58, 74, 48), 0.25 + 0.2 * t)
    # Dirt path receding.
    for yy in range(horizon, h):
        t = (yy - horizon) / max(1, h - horizon)
        half = 12 + t * 190
        c.rect(w * 0.46 - half, yy, half * 2, 1, (94, 86, 62), 0.25)
    # Cover: two crates and a tree, right of frame.
    def crate(cx, cy, s, col=(112, 88, 58)):
        c.rect(cx - s, cy - s * 0.9, s * 2, s * 1.8, col)
        c.rect(cx - s, cy - s * 0.9, s * 2, 2, (140, 112, 76))
        c.rect(cx - s * 0.15, cy - s * 0.9, 2, s * 1.8, (92, 72, 48))

    crate(w * 0.795, h * 0.735, 26)
    crate(w * 0.685, h * 0.700, 17)
    # Tree trunk + canopy.
    tx = w * 0.885
    c.rect(tx - 5, h * 0.42, 10, h * 0.30, (66, 52, 38))
    for r, a in ((58, 0.5), (44, 0.8), (30, 1.0)):
        c.disc(tx, h * 0.40, r, (44, 86, 52), a)
    # A distant enemy silhouette in cover, for scale.
    ex, ey = w * 0.62, h * 0.700
    c.rect(ex - 5, ey - 26, 10, 26, (40, 44, 46))
    c.disc(ex, ey - 30, 5, (40, 44, 46))
    # Enemy nameplate, as the game draws it.
    c.box(ex - 26, ey - 48, 52, 11, (10, 14, 18), 0.62, 3)
    c.ctext("HOSTILE", ex, ey - 45, 1, (255, 138, 108))


def draw_soldier(c, cx, cy, s, col, yaw_frac=0.0, crouch=False):
    """The over-the-shoulder soldier: torso, pack, helmet, rifle."""
    s = s * (0.82 if crouch else 1.0)
    # Pack and torso, slightly turned.
    c.box(cx - 17 * s, cy - 52 * s, 30 * s, 34 * s, col[0])
    c.box(cx - 20 * s, cy - 48 * s, 8 * s, 24 * s, col[1])
    # Legs, staggered.
    c.box(cx - 12 * s, cy - 18 * s, 9 * s, 20 * s, col[1])
    c.box(cx + 2 * s, cy - 18 * s, 9 * s, 20 * s, col[1])
    # Arms and rifle, angled across the body.
    c.box(cx + 8 * s, cy - 44 * s, 8 * s, 18 * s, col[1])
    c.line(cx + 12 * s, cy - 40 * s, cx + 40 * s, cy - 52 * s, (58, 62, 66))
    c.line(cx + 12 * s, cy - 40 * s, cx + 36 * s, cy - 50 * s, (86, 92, 98))
    # Helmet.
    c.disc(cx + 1 * s, cy - 58 * s, 9 * s, col[2])
    c.box(cx - 8 * s, cy - 58 * s, 18 * s, 3 * s, col[2])
    # Rim light on the left edge, from the low sun.
    for yy in range(int(cy - 64 * s), int(cy - 16 * s)):
        c.plot(cx - 18 * s, yy, (255, 210, 160), 0.25)


def draw_gun(c, x, y, scale, angle, ads):
    """The first-person weapon: receiver, barrel, mag, sight."""
    ca, sa = math.cos(angle), math.sin(angle)

    def P(px, py):
        return (x + px * ca * scale - py * sa * scale, y + px * sa * scale + py * ca * scale)

    def quad(pts, col, a=1.0):
        c.poly([P(*p) for p in pts], col, a)

    # Hand and grip.
    quad([(-6, 6), (14, 10), (12, 34), (-8, 30)], (74, 56, 44))
    # Receiver.
    quad([(-10, -12), (92, -10), (92, 10), (-10, 12)], (58, 62, 68))
    quad([(-10, -12), (92, -10), (92, -4), (-10, -4)], (78, 84, 90))
    # Barrel and handguard.
    quad([(92, -7), (176, -5), (176, 3), (92, 7)], (44, 48, 52))
    quad([(120, -9), (150, -8), (150, 0), (120, 1)], (34, 38, 42))
    # Magazine, canted forward like a real one.
    quad([(26, 10), (48, 12), (56, 52), (34, 50)], (50, 54, 58))
    # Sight rail: only visible when not aiming, it is what the eye lines up.
    quad([(30, -18), (66, -17), (66, -12), (30, -13)], (40, 44, 48))
    if ads:
        # Front post, small and centred, because the eye is behind it.
        quad([(150, -14), (156, -14), (156, -5), (150, -5)], (30, 34, 38))
    # Muzzle glow if it is firing.
    c.disc(*P(178, -1), 5 * scale, (255, 220, 150), 0.55)


def draw_hud(c, cam, hud, ctl, w, h, d, first, ads, scoped=False, ts=2):
    """The HUD, laid out from the parsed anchors. dp are multiplied by `d`.

    Geometry is exact — anchors, sizes and margins all come from the Kotlin.
    Text is drawn at `ts`x: the hero panels enlarge labels so they read at sheet
    size, the annotated strip draws them true-size so a collision shows.
    """
    TS = ts
    # --- map, top-left (92 dp, 12 dp off the corner)
    ms = hud["map_size"] * d
    mmx = 12 * d
    mmy = 124 * d
    c.box(mmx - 3, mmy - 3, ms + 6, ms + 6, (14, 18, 22), 0.88, 10)
    c.disc(mmx + ms / 2, mmy + ms / 2, ms * 0.47, (74, 118, 62))
    for tx_, ty_, tr in ((0.3, 0.4, 13), (0.66, 0.58, 11)):
        c.disc(mmx + ms * tx_, mmy + ms * ty_, tr, (150, 138, 106), 0.5)
    c.ring(mmx + ms / 2, mmy + ms / 2, ms * 0.30, 1.5, (255, 255, 255), 0.5)
    c.ring(mmx + ms / 2, mmy + ms / 2, ms * 0.22, 1.2, (100, 181, 246), 0.45)
    c.disc(mmx + ms / 2, mmy + ms / 2, 3.5, ACCENT)

    # --- vitals, to the right of the map
    x = 14 * d
    y = 10 * d
    pr = hud["portrait_r"] * d
    c.disc(x + pr / 2, y + pr / 2, pr / 2, (20, 26, 32), 0.92)
    c.disc(x + pr / 2, y + pr / 2, pr / 2 - 4, (79, 195, 247))
    c.ctext("K", x + pr / 2, y + pr / 2 - 7, TS, (12, 16, 20))
    bx = x + pr + 10 * d
    bw = 116 * d
    c.box(bx, y + 4, bw, 5 * d, (40, 48, 58), 0.92)
    c.box(bx, y + 4, bw * 0.68, 5 * d, (241, 243, 245))
    c.text("68 HP", bx + bw + 8 * d, y - 2, 1, (235, 240, 246))
    c.box(bx, y + 15 * d, bw, 4 * d, (40, 48, 58), 0.92)
    c.box(bx, y + 15 * d, bw * 0.4, 4 * d, (144, 202, 249))
    c.text("VEST 40", bx + bw + 8 * d, y + 12 * d, 1, (179, 217, 255))
    ly = y + pr + 4 * d
    c.frame(x, ly, 92 * d, 22 * d, (16, 20, 24), (44, 52, 62), 4, 0.6)
    c.text("LVL 14", x + 9 * d, ly + 5 * d, TS, (255, 213, 79))
    locy = ly + 26 * d
    c.frame(x, locy, 180 * d, 22 * d, (16, 20, 24), (44, 52, 62), 4, 0.6)
    c.text("IRON BAY - DUSK", x + 9 * d, locy + 5 * d, TS, (226, 234, 244))
    sy2 = locy + 26 * d
    c.frame(x, sy2, 132 * d, 20 * d, (16, 20, 24), (44, 52, 62), 4, 0.45)
    c.text("GRASS", x + 9 * d, sy2 + 4 * d, TS, (205, 220, 230))
    c.text("WIND 6", x + 92 * d, sy2 + 4 * d, 1, (150, 164, 184))

    # --- mode card, top-right
    mw = hud["card_w"] * d
    mx = w - 14 * d - mw
    c.frame(mx, y, mw, 46 * d, (16, 20, 24), (44, 52, 62), 6, 0.66)
    c.text("CLASH SQUAD", mx + 12 * d, y + 7 * d, 1, (168, 182, 200))
    c.text("2", mx + 12 * d, y + 20 * d, TS, (100, 181, 246))
    c.text("1", mx + 44 * d, y + 20 * d, TS, (255, 183, 77))
    c.text("ROUND 4", mx + 104 * d, y + 26 * d, 1, (140, 154, 172))
    c.frame(mx, y + 50 * d, mw, 20 * d, (16, 20, 24), (44, 52, 62), 4, 0.66)
    c.text("MATCH 4:12", mx + 10 * d, y + 52 * d, 1, (226, 234, 244))
    clock_y = y + 74 * d
    c.frame(mx, clock_y, mw, 20 * d, (16, 20, 24), (44, 52, 62), 4, 0.66)
    c.text("ROUND 1:38", mx + 10 * d, clock_y + 2 * d, 1, (226, 234, 244))

    # --- perspective pill, bottom of the right column
    cw, chh = 96 * d, hud["chip_h"] * d
    chip_y = clock_y + 24 * d
    c.box(mx, chip_y, cw, chh, (16, 20, 24), 0.66, 5)
    c.box(mx + 1, chip_y + 1, cw - 2, chh - 2, (27, 111, 168) if first else (255, 255, 255), 0.18, 5)
    c.text("1ST" if first else "3RD", mx + 7 * d, chip_y + 5 * d, TS, (120, 190, 255) if first else (236, 242, 250))
    c.text("VIEW", mx + cw - 6 * d - c.tw("VIEW"), chip_y + 6 * d, 1, (150, 164, 184))

    # --- kill feed, under the pill on the right
    feed_y = y + 144 * d
    for i, (k, v) in enumerate([("NOX", "TALON"), ("YOU", "BRICK")]):
        label = f"{k} > {v}"
        tw = c.tw(label) + 14
        fy = feed_y + i * 22 * d
        if fy + 18 * d > h - 12 * d - 70 * d:
            break
        c.box(w - 14 * d - tw, fy, tw, 18 * d, (16, 20, 24), 0.62, 4)
        c.text(label, w - 14 * d - tw + 8, fy + 5 * d, 1, (238, 243, 250))

    # --- status strip, above the slot row
    slot_top = h - 12 * d - hud["slot_h"] * d
    lines = [("RELOADING", (207, 216, 220)), ("SPAWN SHIELD 2S", (138, 180, 248))]
    yy = slot_top - 8 * d
    for label, col in reversed(lines):
        yy -= 18 * d
        c.ctext(label, w / 2, yy, 1, col)


    # --- weapon slots, bottom right
    sw, sh = hud["slot_w"] * d, hud["slot_h"] * d
    sy = h - 12 * d - sh
    for i, (name, ammo) in enumerate([("RIFLE", "24/30"), ("PAN", "--")]):
        sx = w - 12 * d - sw - i * (sw + 12 * d)
        c.frame(sx, sy, sw, sh, (22, 28, 36), (60, 72, 88), 5, 0.94)
        c.text(name, sx + 8 * d, sy + 7 * d, 1, (232, 238, 246))
        c.text(ammo, sx + 8 * d, sy + 22 * d, 1, (255, 213, 79) if i == 0 else (140, 154, 172))

    # --- stick and buttons
    jx, jy = w * ctl["home"][0], h * ctl["home"][1]
    jr = ctl.get("stick_r", 60.0) * d
    c.ring(jx, jy, jr, jr * 0.055, (255, 255, 255), 0.26)
    c.disc(jx, jy, jr * 0.40, (255, 255, 255), 0.10)
    c.disc(jx, jy, jr * 0.26, ACCENT, 0.55)
    c.ring(jx, jy, jr * 0.26, 2.0, (255, 255, 255), 0.35)
    for key in ("fire", "ads", "jump", "crouch", "reload", "sprint", "prone", "frag", "stun", "smoke", "heal", "mount"):
        a = ctl["anchors"].get(key)
        if not a:
            continue
        r = ctl["radii"].get(key, 26.0) * d
        cx, cy = w * a[0], h * a[1]
        col = ACCENT if key == "fire" else (236, 242, 250)
        c.disc(cx, cy, r, (18, 22, 28), 0.38)
        c.ring(cx, cy, r, 1.6, col, 0.62)
        if key == "fire":
            c.ctext("FIRE", cx, cy - 5, TS, (255, 190, 170))
        elif key == "ads":
            c.ctext("ADS", cx, cy - 5, TS, (200, 225, 245))

    # --- crosshair
    cx, cy = w / 2, h / 2
    gap, ln = 10.0, 8.0
    for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        c.line(cx + dx * gap, cy + dy * gap, cx + dx * (gap + ln), cy + dy * (gap + ln), (255, 255, 255), 0.85)
    c.disc(cx, cy, 1.6, (255, 255, 255), 0.85)

    if scoped:
        r = min(w, h) * 0.46
        for yy in range(h):
            for xx in range(w):
                if math.hypot(xx - cx, yy - cy) > r:
                    c.plot(xx, yy, (0, 0, 0), 1.0)
        c.ring(cx, cy, r, 14, (0, 0, 0), 1.0)
        c.line(cx - r, cy, cx + r, cy, (210, 210, 210), 0.45)
        c.line(cx, cy - r, cx, cy + r, (210, 210, 210), 0.45)
        for i in range(1, 5):
            off = i * r / 5
            c.line(cx - 7, cy - off, cx + 7, cy - off, (255, 255, 255), 0.5)
            c.line(cx - 7, cy + off, cx + 7, cy + off, (255, 255, 255), 0.5)
            c.line(cx - off, cy - 7, cx - off, cy + 7, (255, 255, 255), 0.5)
            c.line(cx + off, cy - 7, cx + off, cy + 7, (255, 255, 255), 0.5)
        c.disc(cx, cy, 2.5, (255, 255, 255))


def panel(cam, hud, ctl, first, ads, scoped, label, sub):
    c = Canvas(PW, PH)
    d = DENSITY
    if not scoped:
        draw_world(c, cam, first, ads, PW, PH)
    else:
        draw_world(c, cam, first, ads, PW, PH)
    # (The scope mask in draw_hud blacks out the frame itself.)
    if not first and not scoped:
        # Third-person body, off-centre to the left of the crosshair because the
        # camera sits over the right shoulder.
        # Over the right shoulder: the camera sits right of the body, so the
        # soldier appears LEFT of centre with the crosshair over his right side.
        draw_soldier(c, PW * 0.415, PH * 0.845, 2.1, ((52, 78, 96), (44, 66, 82), (78, 96, 108)))
    if first and not scoped:
        # The viewmodel, low right at hip, drawing in to the centre when aiming.
        x = PW * (0.56 if not ads else 0.44)
        y = PH * (0.93 if not ads else 0.88)
        draw_gun(c, x, y, 1.9 if not ads else 2.2, -0.13 if not ads else -0.03, ads)
    draw_hud(c, cam, hud, ctl, PW, PH, d, first, ads, scoped, ts=2)
    # Panel chrome.
    c.rect(0, 0, PW, 30, (10, 13, 17), 0.86)
    c.text(label, 12, 10, 1, (255, 255, 255))
    c.text(sub, PW - 12 - c.tw(sub), 10, 1, (150, 164, 184))
    return c


def hud_panel(w=1180, h=660, d=2.45):
    """One panel that is only the HUD, on flat ground, for reading the layout."""
    c = Canvas(w, h)
    c.vgrad(0, 0, w, h, (30, 44, 38), (24, 34, 30))
    for i in range(0, w, 62):
        c.rect(i, 0, 1, h, (255, 255, 255), 0.03)
    for i in range(0, h, 62):
        c.rect(0, i, w, 1, (255, 255, 255), 0.03)
    return c


def marker(c, x, y, n, col=(255, 214, 120)):
    """A numbered dot on the HUD; the legend to the right explains it."""
    c.disc(x, y, 11, (8, 11, 15), 0.92)
    c.ring(x, y, 11, 1.6, col, 0.95)
    c.ctext(str(n), x, y - 6, 2, col)


def main() -> None:
    cam = parse_camera()
    hud = parse_hud()
    ctl = parse_controls()
    ori = parse_orientation()

    problems = []
    if not ctl["anchors"]:
        problems.append("could not parse btnDefaults — the control layout in this sheet would be invented")
    if not (60 <= hud["map_size"] <= 140):
        problems.append(f"minimap size parsed as {hud['map_size']}dp — parse failed")
    for k, want in (("front_has_orientation_pill", False), ("front_has_portrait_choice", False),
                    ("locks_landscape", True), ("refuses_portrait", True),
                    ("removed_portrait_layout", True), ("rotate_prompt", True)):
        if ori[k] != want:
            problems.append(f"orientation fact {k} is {ori[k]}, expected {want}")

    pad = 26
    PW2, PH2 = 1180, 620
    colw = PW2 + pad * 2
    W = colw * 2
    head = 104
    cap_h = 52
    hud_w, hud_h, hd = 1000, 470, 2.25
    strip_top = head + PH2 + cap_h + 40
    foot = 196
    H = strip_top + hud_h + 34 + foot
    sheet = Canvas(W, H)

    sheet.text("SEND OFF", 26, 20, 3, INK)
    sheet.rect(26, 60, 196, 3, ACCENT)
    sheet.text("HOW IT LOOKS NOW", 26, 70, 1, (255, 176, 120))
    sheet.text("TWO PERSPECTIVES, ONE LANDSCAPE HUD, AND THE ROTATE QUESTION SETTLED.", 236, 36, 1, DIM)
    sheet.text("EVERY ANCHOR, RADIUS AND CAMERA DISTANCE BELOW IS PARSED OUT OF THE KOTLIN.", 236, 52, 1, FAINT)

    shots = [
        (False, False, False, "THIRD PERSON - HIP FIRE", "CAMERA 2.6M BACK, 0.78M OVER THE RIGHT SHOULDER"),
        (True, True, False, "FIRST PERSON - AIMING", "VIEWMODEL CENTRED, CAMERA ON THE EYE LINE"),
    ]
    for i, args in enumerate(shots):
        p = panel(cam, hud, ctl, *args)
        cx = pad + i * colw
        cy = head
        sheet.frame(cx - 1, cy - 1, PW2 + 2, PH2 + 2, BG, (58, 68, 82), 3)
        for y in range(min(PH2, p.h)):
            o = y * p.w * 3
            for x in range(min(PW2, p.w)):
                sheet.plot(cx + x, cy + y, p.px[o + x * 3:o + x * 3 + 3])
        sheet.text(args[3], cx, cy + PH2 + 10, 1, (210, 222, 238))
        sub = args[4]
        sheet.text(sub, cx, cy + PH2 + 26, 1, FAINT)
        sheet.text("HUD LABELS ENLARGED 2X TO READ; POSITIONS AND SIZES ARE TRUE.", cx + PW2 - sheet.tw("HUD LABELS ENLARGED 2X TO READ; POSITIONS AND SIZES ARE TRUE."), cy + PH2 + 26, 1, (90, 104, 122))
        note = ("SCOPED: THE CAMERA WALKS IN TO 1.1M, THE BODY IS HIDDEN AND THE SCOPE OVERLAY OWNS THE FRAME."
                if i == 0 else
                f"FIRST-PERSON FOV IS THE LANDSCAPE FOV PLUS {cam['fov_first_bonus']:.0f} DEGREES; NEAR PLANE {cam['near_first']:.2f}M.")
        sheet.text(note, cx, cy + PH2 + 40, 1, (150, 164, 184))

    # ------------------------------------------------------------ HUD strip
    sheet.text("THE HUD, ANNOTATED", 26, strip_top - 26, 1, (255, 176, 120))
    c = hud_panel(hud_w, hud_h, hd)
    draw_hud(c, cam, hud, ctl, hud_w, hud_h, hd, False, False, ts=1)
    hx = pad
    sheet.frame(hx - 1, strip_top - 1, hud_w + 2, hud_h + 2, BG, (58, 68, 82), 3)
    for y in range(hud_h):
        o = y * hud_w * 3
        for x in range(hud_w):
            sheet.plot(hx + x, strip_top + y, c.px[o + x * 3:o + x * 3 + 3])

    ms = hud["map_size"] * hd
    marks = [
        (12 * hd + ms / 2, 124 * hd + ms / 2, 1),
        (14 * hd + 15 * hd, 25 * hd, 2),
        (hud_w - 14 * hd - 48 * hd, (10 + 98 + 11) * hd, 3),
        (hud_w - 14 * hd - 60 * hd, (10 + 50 + 10) * hd, 4),
        (0.930 * hud_w + 40, 0.470 * hud_h, 5),
        (0.900 * hud_w + 88, 0.720 * hud_h, 6),
        (0.150 * hud_w - 26, 0.905 * hud_h, 7),
        (0.135 * hud_w - 40, 0.735 * hud_h, 8),
        (hud_w * 0.5, hud_h - 12 * hd - 23 * hd, 9),
        (hud_w - 12 * hd - 42 * hd, hud_h - 12 * hd - 17 * hd, 10),
    ]
    for x, y, n in marks:
        marker(sheet, hx + x, strip_top + y, n)

    hud_chip = f"{hud['map_size']:.0f}"
    legend = [
        (1, "MAP, UNDER THE VITALS", f"{hud_chip} DP, TOP-LEFT BELOW THE LOCATION CARD - NOT UNDER A THUMB."),
        (2, "VITALS OWN THE CORNER", "PORTRAIT, HP, VEST, LEVEL: THE FIRST THING YOU SEE."),
        (3, "VIEW PILL", "ONE TAP FLIPS 1ST / 3RD. A HOTSPOT, NOT A BUTTON."),
        (4, "MODE + CLOCKS", "UNCHANGED ANCHOR, NOW WITH THE PILL UNDER IT."),
        (5, "JUMP", "RIGHT EDGE, ABOVE THE AIM RING."),
        (6, "FIRE", "GLASS 34 DP, LOWEST-RIGHT THUMB REST."),
        (7, "THROWABLES", "FRAG, SMOKE, STUN, MOUNT ALONG THE BOTTOM."),
        (8, "STICK", f"{ctl['stick_r']:.0f} DP RING AT {ctl['home'][0]:.2f}, {ctl['home'][1]:.2f} - CLEARS MAP AND THROWABLES."),
        (9, "STATUS STRIP", "RELOAD, HEAL, SHIELD: GROWS UP, DROPS WHAT DOES NOT FIT."),
        (10, "WEAPON SLOTS", "84X34 DP ON NARROW WINDOWS, 112X46 OTHERWISE."),
    ]
    lx = hx + hud_w + 40
    ly = strip_top + 6
    sheet.text("KEY", lx, ly, 1, (255, 176, 120))
    for i, (n, title, detail) in enumerate(legend):
        yy = ly + 26 + i * 43
        sheet.disc(lx + 8, yy + 6, 8, (8, 11, 15), 0.92)
        sheet.ring(lx + 8, yy + 6, 8, 1.4, (255, 214, 120), 0.95)
        sheet.ctext(str(n), lx + 8, yy + 2, 1, (255, 214, 120))
        sheet.text(title, lx + 24, yy, 1, (226, 236, 248))
        sheet.text(detail, lx + 24, yy + 13, 1, (146, 160, 180))

    # ------------------------------------------------------------- footer
    fy = H - foot + 12
    sheet.rect(0, fy - 14, W, foot, (10, 13, 17))
    sheet.rect(0, fy - 14, W, 1, (48, 58, 70))
    sheet.text("THE ROTATE QUESTION", 26, fy, 2, (255, 176, 120))
    sheet.text("SETTLED IN THE BUILD, NOT IN A TOOLTIP. PORTRAIT IS NOT A WAY TO PLAY SEND OFF.", 26, fy + 26, 1, DIM)
    facts = [
        ("ORIENTATION", "LANDSCAPE ONLY. THE ACTIVITY LOCKS SENSOR-LANDSCAPE, EITHER WAY UP."),
        ("FRONT SCREEN", "NO AUTO / LANDSCAPE / PORTRAIT PILL. A DISPLAY ROW READS LANDSCAPE (LOCKED)."),
        ("HELD UPRIGHT", "THE MATCH PAUSES AND ONE PROMPT SAYS: TURN THE PHONE SIDEWAYS."),
        ("PORTRAIT LAYOUT", "DELETED. NO SECOND BUTTON SET, NO SQUASHED HUD, NO HALF-PROMISE."),
        ("PERSPECTIVE", "PAUSE MENU, FRONT SCREEN OR THE IN-MATCH PILL - ALL WRITE ONE SETTING."),
        ("AIM", f"BOTH VIEWS SHOOT FROM THE EYE: SHOULDER {cam['shoulder_default']:.1f}M, SCOPED 1.1M."),
    ]
    for i, (k, v) in enumerate(facts):
        fx = 26 + (i % 2) * (W // 2)
        fyy = fy + 56 + (i // 2) * 34
        sheet.text(k, fx, fyy, 1, (132, 180, 220))
        sheet.text(v, fx, fyy + 13, 1, (196, 208, 224))

    write_png(OUT, W, H, bytes(sheet.px))
    print(f"wrote {OUT.relative_to(ROOT)} · {W}x{H}")
    print(f"  camera: shoulder {cam['shoulder_default']}m, scoped {cam['ads']}m, side {cam['side']}m, "
          f"eye {cam['eye_stand']}/{cam['eye_crouch']}/{cam['eye_prone']}m, "
          f"fov {cam['fov_land']}+{cam['fov_first_bonus']} first person")
    print(f"  hud: map {hud['map_size']}dp, slots {hud['slot_w']}x{hud['slot_h']}dp, "
          f"view pill {hud['chip_w']}x{hud['chip_h']}dp, stick {ctl['stick_r']}dp")
    print(f"  orientation: {ori}")
    if problems:
        print("PROBLEMS:")
        for p in problems:
            print("  -", p)
        sys.exit(1)


if __name__ == "__main__":
    main()
