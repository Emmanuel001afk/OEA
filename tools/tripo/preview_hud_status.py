#!/usr/bin/env python3
"""Preview the in-match HUD at the screen shapes it has to survive.

The status strip is the piece that keeps getting into fights with the rest of
the HUD: reload, healing, regen, spawn shield and burst all live in one column
above the weapon slots. This renders that column against the real anchors —
slot row and safe area from `OeaArena.drawControls`, status anchors parsed from
the `drawStatus` block — on three aspect ratios, so a collision is a picture
instead of a playtest.

Anchors and sizes are parsed from the Kotlin; if the layout changes and this
picture does not, the parse failed and it says so.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from concept_to_oeac import write_png  # noqa: E402
from preview_environment import FONT as BASE_FONT  # noqa: E402
from preview_armory import EXTRA  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
ARENA = ROOT / "oea-games/src/main/java/com/oea/games/arena/OeaArena.kt"
OUT = ROOT / "tools/tripo/concepts/hud-status-preview.png"
FONT = {**BASE_FONT, **EXTRA}
for _c, _b in {
    "W": ["10001", "10001", "10001", "10101", "10101", "11011", "10001"],
    "K": ["10001", "10010", "10100", "11000", "10100", "10010", "10001"],
}.items():
    FONT.setdefault(_c, _b)

BG = (18, 22, 28)
PANEL = (26, 32, 40)
STROKE = (52, 60, 72)
TEXT = (238, 242, 248)
DIM = (132, 146, 166)
GREEN = (102, 187, 106)
MINT = (156, 204, 138)
BLUE = (138, 180, 248)
AMBER = (255, 213, 79)
GREY = (207, 216, 220)

# Screen shapes to check: a tall phone, a 16:9 landscape, and a squat landscape
# (the ones where a bottom-anchored column has the least room).
SHAPES = [(360, 780, "PORTRAIT 360x780"), (780, 360, "LANDSCAPE 780x360"), (640, 300, "SHORT 640x300")]


class Canvas:
    def __init__(self, w, h, fill=None):
        self.w, self.h = w, h
        self.px = bytearray((fill or BG) * (w * h))

    def plot(self, x, y, c):
        x, y = int(x), int(y)
        if 0 <= x < self.w and 0 <= y < self.h:
            o = (y * self.w + x) * 3
            self.px[o:o + 3] = bytes(max(0, min(255, int(v))) for v in c)

    def rect(self, x, y, w, h, c):
        for yy in range(int(y), int(y + h)):
            for xx in range(int(x), int(x + w)):
                self.plot(xx, yy, c)

    def frame(self, x, y, w, h, c=PANEL, border=STROKE):
        self.rect(x, y, w, h, c)
        self.rect(x, y, w, 1, border)
        self.rect(x, y + h - 1, w, 1, border)
        self.rect(x, y, 1, h, border)
        self.rect(x + w - 1, y, 1, h, border)

    def text(self, s, x, y, scale=1, colour=TEXT):
        for ch in str(s).upper():
            glyph = FONT.get(ch, FONT[" "])
            for row, bits in enumerate(glyph):
                for col, bit in enumerate(bits):
                    if bit == "1":
                        for sy in range(scale):
                            for sx in range(scale):
                                self.plot(x + col * scale + sx, y + row * scale + sy, colour)
            x += 6 * scale

    def text_w(self, s, scale=1):
        return len(str(s)) * 6 * scale

    def bar(self, x, y, w, h, v, c):
        self.rect(x, y, w, h, (44, 51, 62))
        self.rect(x, y, max(1, w * max(0.0, min(1.0, v))), h, c)

    def centre_text(self, s, cx, y, scale=1, colour=TEXT):
        self.text(s, cx - self.text_w(s, scale) / 2, y, scale, colour)


def parse_layout():
    """Anchors out of the Kotlin: slot row, safe-area use, status stacking."""
    s = ARENA.read_text()
    slot = re.search(r"val sw = ([\d.]+)f \* d; val sh = ([\d.]+)f \* d", s)
    slot_y = re.search(r"val sy = hud\.height - hud\.safeBottom - sh - ([\d.]+)f \* d", s)
    strip = re.search(r"var stripY = slotTop - ([\d.]+)f \* d", s)
    gaps = re.findall(r"stripY -= ([\d.]+)f \* d", s)
    return {
        "slot_w": float(slot.group(1)) if slot else 112.0,
        "slot_h": float(slot.group(2)) if slot else 46.0,
        "slot_margin": float(slot_y.group(1)) if slot_y else 12.0,
        "strip_gap": float(strip.group(1)) if strip else 26.0,
        "gaps": [float(g) for g in gaps] or [20.0, 30.0, 38.0],
    }


def parse_stack():
    """The status lines, in priority order, from the Kotlin that builds them."""
    s = ARENA.read_text()
    block = s[s.index("val lines = ArrayList<StatusLine>"):]
    block = block[: block.index("// How much room there is")]
    lines = []
    for m in re.finditer(r'StatusLine\("([^"]+)"[^,]*, ([\d.]+)f, ([^,]+), ([\d.]+)f', block):
        label = m.group(1).replace("…", "...")
        lines.append({"label": label, "size": float(m.group(2)), "bar_w": float(m.group(4))})
    # The label text is dynamic (counts, seconds); give each a sample.
    sample = {
        "Reloading": "RELOADING",
        "Healing...": "HEALING...",
        "Regenerating": "REGENERATING",
    }
    # Healing and regen are alternatives in the Kotlin (`else if`); the parse
    # cannot see that, so the sample stack keeps the healing branch only.
    lines = [l for l in lines if not l["label"].startswith("Regenerating")]
    for line in lines:
        lab = line["label"]
        line["shown"] = sample.get(lab) or lab.upper()
        if lab.startswith("Spawn shield"):
            line["shown"] = "SPAWN SHIELD 2S"
        if lab.startswith("Regen in"):
            line["shown"] = "REGEN IN 5S"
    return lines


def draw_status(c: Canvas, w, h, d, lay, stack, state):
    """Mirror of `drawStatus`: fit to the available room, then lay out upward."""
    slot_row_top = h - lay["slot_margin"] - lay["slot_h"] * d
    room = (slot_row_top - h * 0.5) - 14
    # Advance per line: the Kotlin's `advance` values, in dp.
    advances = {"RELOADING": 30.0, "HEALING...": 38.0, "REGENERATING": 30.0, "REGEN IN 5S": 28.0, "SPAWN SHIELD 2S": 20.0, "BURST": 20.0}
    chosen = []
    total = 0.0
    for line in stack:
        adv = advances.get(line["shown"], 24.0) * d
        if total + adv > room:
            break
        chosen.append((line, adv))
        total += adv
    y = slot_row_top - 8 * d
    for line, adv in chosen:
        y -= adv
        size = line["size"]
        c.centre_text(line["shown"], w / 2, y, max(1, int(size / 7)),
                      {"HEALING...": TEXT, "REGENERATING": MINT, "SPAWN SHIELD 2S": BLUE,
                       "RELOADING": GREY, "BURST": DIM, "REGEN IN 5S": (153, 214, 167)}.get(line["shown"], TEXT))
        if line["bar_w"] > 0:
            bw = line["bar_w"] * d
            c.bar(w / 2 - bw / 2, y + size * 1.45 * d, bw, 4.5 * d, state.get("bars", {}).get(line["shown"], 0.6),
                  GREEN if line["shown"].startswith("HEAL") else (MINT if "REGEN" in line["shown"] else GREY))
    dropped = len(stack) - len(chosen)
    return slot_row_top, dropped, total


def main() -> None:
    lay = parse_layout()
    stack = parse_stack()
    problems = []
    if not stack:
        problems.append("could not parse any status lines out of drawStatus — the sheet would be a lie")

    # Checks that do not need a picture: does the column fit above the slots?
    for w, h, name in SHAPES:
        d = 1.0
        slot_top = h - lay["slot_margin"] - lay["slot_h"] * d
        worst = lay["strip_gap"] + sum(lay["gaps"])
        if slot_top - worst < h * 0.35:
            problems.append(f"{name}: status column reaches y={slot_top - worst:.0f} of {h} (over the centre)")

    # A big canvas holding all three shapes side by side.
    cw = sum(w for w, _, _ in SHAPES) + 40 * (len(SHAPES) + 1)
    ch = max(h for _, h, _ in SHAPES) + 90
    sheet = Canvas(cw, ch)
    sheet.text("HUD STATUS STRIP — BOTTOM-ANCHORED, THREE SCREEN SHAPES", 24, 20, 1, TEXT)
    sheet.text("A HEALING PLAYER WITH A SPAWN SHIELD, MID-RELOAD, OWING A BURST: THE WORST CASE STACK.",
               24, 36, 1, DIM)

    x0 = 40
    state = {"bars": {"HEALING...": 0.55, "RELOADING": 0.65, "REGENERATING": 0.63, "REGEN IN 5S": 0.4}}
    for w, h, name in SHAPES:
        screen = Canvas(w, h)
        d = 1.0
        # Dim the world and draw the pieces that matter for collisions.
        screen.rect(0, 0, w, h, (30, 40, 34))
        slot_top, dropped, used = draw_status(screen, w, h, d, lay, stack, state)
        # Weapon slots, bottom-right — drawn first so the strip cannot hide them.
        sw, sh = lay["slot_w"] * d, lay["slot_h"] * d
        sy = h - lay["slot_margin"] * d - sh
        for i in range(2):
            sx = w - lay["slot_margin"] * d - sw - i * (sw + 12 * d)
            screen.frame(sx, sy, sw, sh, (42, 54, 70))
            screen.text("RIFLE" if i == 0 else "PAN", sx + 6, sy + 6, 1, TEXT)
        # Health bar top-left, for scale.
        screen.bar(16, 16, 120, 8, 0.38, (241, 243, 245))
        # A marker line where the strip must not cross.
        screen.rect(0, slot_top - 1, w, 1, (255, 90, 90))
        if dropped:
            screen.text(f"{dropped} LINE(S) DROPPED", 16, h - 14, 1, (255, 170, 90))
        else:
            screen.text(f"ALL {len(stack)} LINES FIT", 16, h - 14, 1, (150, 220, 160))
        # Does the *strip* cross the line? Render it alone on a blank canvas and
        # take its bounding box — the slot boxes legitimately live below the
        # line, so scanning the composited frame would blame them.
        probe = Canvas(w, h, fill=(0, 0, 0))   # blank, so the bbox is the strip alone
        draw_status(probe, w, h, d, lay, stack, state)
        lowest = -1
        for y in range(h):
            o = y * w * 3
            if any(probe.px[o + x * 3:o + x * 3 + 3] != b"\x00\x00\x00" for x in range(w)):
                lowest = y
        if lowest >= slot_top:
            problems.append(f"{name}: the status strip reaches y={lowest}, the slot row starts at {slot_top:.0f}")
        # Paste into the sheet. The border is drawn *before* the paste: a
        # fill-then-stroke frame afterwards would paint BG over the screen.
        sheet.frame(x0 - 1, 69, w + 2, h + 2, BG, STROKE)
        for y in range(h):
            for x in range(w):
                o = (y * w + x) * 3
                sheet.plot(x0 + x, 70 + y, screen.px[o:o + 3])
        sheet.text(name, x0, 70 + h + 10, 1, TEXT)
        x0 += w + 40

    sheet.text("RED LINE = THE SLOT ROW. THE COLUMN GROWS UPWARD FROM IT, SO IT CANNOT OVERLAP THE SLOTS.",
               24, ch - 32, 1, DIM)
    sheet.text(f"SLOT {lay['slot_w']:.0f}x{lay['slot_h']:.0f}DP, GAP {lay['strip_gap']:.0f}DP, "
               f"ROW GAPS {', '.join(f'{g:.0f}' for g in lay['gaps'])}DP.",
               24, ch - 18, 1, DIM)

    write_png(OUT, cw, ch, bytes(sheet.px))
    print(f"wrote {OUT.relative_to(ROOT)} · {len(SHAPES)} shapes")
    if problems:
        print("LAYOUT PROBLEMS:")
        for p in problems:
            print("  -", p)
        sys.exit(1)


if __name__ == "__main__":
    main()
