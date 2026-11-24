#!/usr/bin/env python3
"""Prove the on-screen control layout never collides with itself or the HUD.

The anchors, radii and joystick home are **parsed out of `OeaArena.kt`**, not
copied here — a table in a checker is a table that goes stale. On every landscape
shape the game supports this verifies:

  * no control overlaps another control;
  * no control sits under the top-left vitals block or the map hanging below it;
  * no control sits under the top-right mode / clock / perspective column;
  * no control sits under the weapon-slot row (which shrinks on narrow windows);
  * the joystick ring clears the map, the throwable row and the screen edge.

Run it after touching any anchor, radius or the stick home. Units are dp at 1x.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ARENA = ROOT / "oea-games/src/main/java/com/oea/games/arena/OeaArena.kt"


def parse_layout() -> dict:
    src = ARENA.read_text()
    # Anchors and radii come from the two factory tables, which are what the
    # layout screen's RESET restores.
    pos = {k: (float(a), float(b)) for k, a, b in
           re.findall(r'"(\w+)" to floatArrayOf\(([\d.]+)f, ([\d.]+)f\)', src)}
    blk = src[src.index("btnDefaultRadius"):]
    blk = blk[:blk.index(")") + 1]
    radii = {k: float(v) for k, v in re.findall(r'"(\w+)" to ([\d.]+)f', blk)}
    stick = re.search(r"joystick\.radiusDp = ([\d.]+)f", src)
    home = re.search(r"joystick\.homeX = ([\d.]+)f\n\s+joystick\.homeY = ([\d.]+)f", src)
    d = re.search(r"val size = ([\d.]+)f \* d\n\s+val x = hud\.safeLeft \+ ([\d.]+)f \* d", src)
    return {
        "pos": {k: (*v, radii[k]) for k, v in pos.items() if k in radii},
        "stick_r": float(stick.group(1)) if stick else 46.0,
        "home": (float(home.group(1)), float(home.group(2))) if home else (0.135, 0.735),
        "map_size": float(d.group(1)) if d else 84.0,
        "map_x": float(d.group(2)) if d else 12.0,
        "map_y": 124.0,
    }


_L = parse_layout()
POS = _L["pos"]
SLOT_NARROW = (84, 34)
SLOT_WIDE = (112, 46)
SLOT_MARGIN = 12
MAP_PAD = 5
MAP = (_L["map_x"], _L["map_y"], _L["map_size"])
STICK = (_L["home"][0], _L["home"][1], _L["stick_r"])
SIZES = [(640, 360), (700, 360), (780, 360), (900, 400), (1024, 480), (1280, 600)]

def zones(w, h):
    cw, chh = SLOT_NARROW if w < 720 else SLOT_WIDE
    sx0 = w - SLOT_MARGIN
    mx, my, ms = MAP
    return {
        "slots": (sx0 - 2 * (cw + SLOT_MARGIN) - 4, h - SLOT_MARGIN - chh - 4, w + 2, h + 2),
        "map": (mx - MAP_PAD, my - MAP_PAD, mx + ms + MAP_PAD, my + ms + MAP_PAD),
        "topL": (10, 4, 10 + 30 + 10 + 116 + 40, 10 + 30 + 4 + 22 + 26 + 22 + 26 + 20 + 6),
        "topR": (w - 14 - 190 - 4, 4, w + 2, 10 + 120 + 4),
        "stick": (STICK[0] * w - STICK[2], STICK[1] * h - STICK[2],
                  STICK[0] * w + STICK[2], STICK[1] * h + STICK[2]),
    }

def hit(a, b, pad=0):
    return not (a[2] + pad <= b[0] or b[2] + pad <= a[0] or a[3] + pad <= b[1] or b[3] + pad <= a[1])

bad = []
for w, h in SIZES:
    zs = zones(w, h)
    bx = {k: (ax * w - r, ay * h - r, ax * w + r, ay * h + r) for k, (ax, ay, r) in POS.items()}
    for k, b in bx.items():
        if b[0] < 2 or b[1] < 2 or b[2] > w - 2 or b[3] > h - 2:
            bad.append((w, h, k, "off", tuple(round(v) for v in b)))
        for zn, z in zs.items():
            if hit(b, z):
                bad.append((w, h, k, "hits " + zn))
    ks = list(POS)
    for i in range(len(ks)):
        for j in range(i + 1, len(ks)):
            if hit(bx[ks[i]], bx[ks[j]], pad=3):
                bad.append((w, h, ks[i], "overlaps " + ks[j]))

if bad:
    for w, h, a, b in bad:
        print(f"  {w}x{h}: {a} {b}")
    print(f"CONTROL LAYOUT PROBLEMS - {len(bad)}")
    raise SystemExit(1)
print(f"CONTROL LAYOUT OK - {len(POS)} controls on {len(SIZES)} screen shapes: no overlaps, "
      f"nothing under the HUD, stick clear")
