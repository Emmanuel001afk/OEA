#!/usr/bin/env python3
"""Preview the Send Off rule set and the three bot personalities.

The card values are parsed straight out of `ArenaRules.kt`, so this sheet is a
readable dump of the real rules rather than a mockup: if a score limit or clock
changes in code, it changes here. Not a game screenshot — it is a design sheet.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from concept_to_oeac import write_png  # noqa: E402
from preview_environment import FONT as BASE_FONT, mix  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
RULES = ROOT / "oea-games/src/main/java/com/oea/games/arena/ArenaRules.kt"
OUT = ROOT / "tools/tripo/concepts/rules-preview.png"
W, H = 1200, 920

EXTRA = {
    "F": ["11111", "10000", "10000", "11110", "10000", "10000", "10000"],
    "J": ["00111", "00010", "00010", "00010", "10010", "10010", "01100"],
    "K": ["10001", "10010", "10100", "11000", "10100", "10010", "10001"],
    "Q": ["01110", "10001", "10001", "10001", "10101", "10010", "01101"],
    "&": ["01100", "10010", "10100", "01000", "10101", "10010", "01101"],
    "X": ["10001", "10001", "01010", "00100", "01010", "10001", "10001"],
    "Z": ["11111", "00001", "00010", "00100", "01000", "10000", "11111"],
    "1": ["00100", "01100", "00100", "00100", "00100", "00100", "01110"],
    "4": ["00010", "00110", "01010", "10010", "11111", "00010", "00010"],
    "5": ["11111", "10000", "11110", "00001", "00001", "10001", "01110"],
    "6": ["00110", "01000", "10000", "11110", "10001", "10001", "01110"],
    "7": ["11111", "00001", "00010", "00100", "01000", "01000", "01000"],
    "8": ["01110", "10001", "10001", "01110", "10001", "10001", "01110"],
    "9": ["01110", "10001", "10001", "01111", "00001", "00010", "01100"],
    ":": ["00000", "00100", "00100", "00000", "00100", "00100", "00000"],
    "/": ["00001", "00010", "00010", "00100", "01000", "01000", "10000"],
    ".": ["00000", "00000", "00000", "00000", "00000", "00110", "00110"],
    ",": ["00000", "00000", "00000", "00000", "00110", "00110", "00100"],
    "(": ["00010", "00100", "01000", "01000", "01000", "00100", "00010"],
    ")": ["01000", "00100", "00010", "00010", "00010", "00100", "01000"],
}
FONT = {**BASE_FONT, **EXTRA}


def plot(px, x, y, c):
    """Local plot: preview_environment.plot clamps to its own canvas size."""
    if 0 <= x < W and 0 <= y < H:
        o = (y * W + x) * 3
        px[o:o + 3] = bytes(max(0, min(255, int(v))) for v in c)


def label(px, text, x, y, scale=2, colour=(235, 240, 246)):
    for ch in text.upper():
        glyph = FONT.get(ch, FONT[" "])
        for row, bits in enumerate(glyph):
            for col, bit in enumerate(bits):
                if bit == "1":
                    for sy in range(scale):
                        for sx in range(scale):
                            plot(px, x + col * scale + sx, y + row * scale + sy, colour)
        x += 6 * scale
    return x


def rect(px, x, y, w, h, c):
    for yy in range(y, y + h):
        for xx in range(x, x + w):
            plot(px, xx, yy, c)


def parse_modes():
    src = RULES.read_text()
    out = []
    for m in re.finditer(r'Mode\("([^"]+)",\s*"([^"]+)",\s*Objective\.(\w+)([^)]*)\)', src):
        mid, label_txt, objective, body = m.groups()
        num = lambda key: re.search(rf"{key}\s*=\s*([0-9.]+)f?", body)  # noqa: E731
        score = num("scoreLimit")
        time = num("timeLimit")
        rnd = num("roundTime")
        out.append({
            "id": mid,
            "label": label_txt,
            "objective": objective,
            "score": int(float(score.group(1))) if score else 0,
            "time": float(time.group(1)) if time else 0.0,
            "round": float(rnd.group(1)) if rnd else 0.0,
        })
    return out


OBJECTIVE_COLOUR = {
    "LAST_ALIVE": (126, 200, 255),
    "SCORE": (255, 190, 110),
    "LADDER": (214, 160, 255),
    "ROUNDS": (128, 226, 168),
    "CAPTURE": (255, 140, 140),
    "HILL": (255, 214, 120),
    "TICKETS": (255, 160, 200),
    "SURVIVE": (150, 220, 140),
}

PERSONAS = [
    ("AGGRESSIVE", 1, (255, 138, 120),
     "PUSHES PLAYS, CLOSES TO 0.8X RANGE, LONG BURSTS", 0.9),
    ("BALANCED", 0, (140, 200, 255),
     "HOLDS MID RANGE, TRADES, USES UTILITY", 0.62),
    ("CAUTIOUS", 2, (168, 226, 178),
     "ANGLES AND TAPS, CROUCHES, SMOKES OUT", 0.34),
]


def main() -> int:
    modes = parse_modes()
    px = bytearray((16, 20, 26) * (W * H))
    label(px, "SEND OFF - RULES", 24, 18, 3, (255, 214, 120))
    label(px, "PARSED FROM ArenaRules.kt", 24, 46, 2, (130, 145, 165))

    cols = 4
    cw, ch = 282, 154
    for i, m in enumerate(modes[:12]):
        cx = 24 + (i % cols) * (cw + 8)
        cy = 76 + (i // cols) * (ch + 12)
        accent = OBJECTIVE_COLOUR.get(m["objective"], (200, 200, 200))
        rect(px, cx, cy, cw, ch, (26, 31, 39))
        rect(px, cx, cy, 4, ch, accent)
        label(px, m["label"][:18], cx + 14, cy + 12, 2, (238, 242, 248))
        label(px, m["objective"], cx + 14, cy + 34, 1, accent)
        rows = []
        if m["score"]:
            rows.append(("SCORE LIMIT", str(m["score"])))
        if m["time"]:
            rows.append(("MATCH CLOCK", f"{int(m['time'] // 60)}:{int(m['time'] % 60):02d}"))
        if m["round"]:
            rows.append(("ROUND CLOCK", f"{int(m['round'] // 60)}:{int(m['round'] % 60):02d}"))
        if not rows:
            rows.append(("END", "LAST ALIVE"))
        for r, (k, v) in enumerate(rows):
            label(px, k, cx + 14, cy + 54 + r * 42, 1, (130, 145, 165))
            label(px, v, cx + 14, cy + 66 + r * 42, 2, (245, 248, 252))
        # Objective bar: how close the limit sits relative to the clock budget.
        bar_w = 150
        fill = 0.0
        if m["score"]:
            fill = min(1.0, m["score"] / 300.0)
        elif m["time"]:
            fill = min(1.0, m["time"] / 900.0)
        rect(px, cx + 14, cy + ch - 18, bar_w, 8, (44, 50, 60))
        rect(px, cx + 14, cy + ch - 18, int(bar_w * fill), 8, accent)

    # Bot personalities.
    label(px, "BOT PERSONALITIES", 24, 582, 3, (255, 214, 120))
    for i, (name, _idx, colour, blurb, aggression) in enumerate(PERSONAS):
        cy = 620 + i * 92
        rect(px, 24, cy, W - 48, 66, (26, 31, 39))
        rect(px, 24, cy, 4, 66, colour)
        label(px, name, 38, cy + 12, 2, colour)
        label(px, blurb, 38, cy + 36, 1, (168, 180, 196))
        # Aim and aggression dials.
        rect(px, 760, cy + 14, 200, 10, (44, 50, 60))
        rect(px, 760, cy + 14, int(200 * aggression), 10, colour)
        label(px, "ENGAGE RANGE", 760, cy + 30, 1, (130, 145, 165))
        rect(px, 760, cy + 44, 200, 10, (44, 50, 60))
        rect(px, 760, cy + 44, int(200 * (1.0 - aggression * 0.6)), 10, mix(colour, (60, 70, 90), 0.35))
        label(px, "PATIENCE", 760, cy + 58, 1, (130, 145, 165))

    write_png(OUT, W, H, bytes(px))
    print(f"wrote {OUT} ({W}x{H}) with {len(modes)} modes")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
