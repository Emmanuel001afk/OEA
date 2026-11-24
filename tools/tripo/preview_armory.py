#!/usr/bin/env python3
"""Preview the Send Off armory sheet — the front-end weapon browser.

The point of this sheet is that the *data* is read from the Kotlin, not typed
here: `ArenaSim.Weapon` is parsed for label, family, role, price, damage, rpm,
range, mag and reload, and the six bar lengths are computed with the same 0..1
normalisation the game uses (`Weapon.statBars`). If a gun is added, rebalanced
or re-priced, rerun this and the picture changes with it.

Row layout mirrors `ArenaFront.armorySheet()`: family headers, gun cards with a
name + role line, a price tag, and six labelled bars.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from concept_to_oeac import write_png  # noqa: E402
from preview_environment import FONT as BASE_FONT  # noqa: E402
from preview_settings import EXTRA as SETTINGS_EXTRA  # noqa: E402

EXTRA = {**SETTINGS_EXTRA, "…": ["00000", "00000", "00000", "00000", "00000", "11000", "00111"]}

ROOT = Path(__file__).resolve().parents[2]
SIM = ROOT / "oea-games/src/main/java/com/oea/games/arena/ArenaSim.kt"
OUT = ROOT / "tools/tripo/concepts/armory-preview.png"

W, H = 1240, 1560
BG = (14, 18, 24)
CARD = (25, 30, 38)
STROKE = (48, 56, 68)
TEXT = (238, 242, 248)
DIM = (132, 146, 166)
AMBER = (255, 190, 92)
MINT = (150, 232, 178)
CYAN = (110, 214, 226)
BAR_BG = (44, 51, 62)
FAMILY_COLOUR = {
    "pistol": CYAN, "smg": MINT, "rifle": AMBER, "shotgun": (255, 150, 110),
    "sniper": (170, 170, 255), "lmg": (240, 140, 200), "launcher": (255, 120, 120),
    "melee": (200, 200, 200), "special": (200, 230, 120),
}
FONT = {**BASE_FONT, **EXTRA}
for _c, _b in {
    "B": ["11110", "10001", "10001", "11110", "10001", "10001", "11110"],
    "C": ["01110", "10001", "10000", "10000", "10000", "10001", "01110"],
    "D": ["11110", "10001", "10001", "10001", "10001", "10001", "11110"],
    "G": ["01110", "10001", "10000", "10111", "10001", "10001", "01110"],
    "H": ["10001", "10001", "10001", "11111", "10001", "10001", "10001"],
    "I": ["11111", "00100", "00100", "00100", "00100", "00100", "11111"],
    "L": ["10000", "10000", "10000", "10000", "10000", "10000", "11111"],
    "M": ["10001", "11011", "10101", "10101", "10001", "10001", "10001"],
    "N": ["10001", "11001", "10101", "10011", "10001", "10001", "10001"],
    "O": ["01110", "10001", "10001", "10001", "10001", "10001", "01110"],
    "P": ["11110", "10001", "10001", "11110", "10000", "10000", "10000"],
    "R": ["11110", "10001", "10001", "11110", "10100", "10010", "10001"],
    "S": ["01111", "10000", "10000", "01110", "00001", "00001", "11110"],
    "T": ["11111", "00100", "00100", "00100", "00100", "00100", "00100"],
    "U": ["10001", "10001", "10001", "10001", "10001", "10001", "01110"],
    "V": ["10001", "10001", "10001", "10001", "10001", "01010", "00100"],
    "W": ["10001", "10001", "10001", "10101", "10101", "11011", "10001"],
    "Y": ["10001", "10001", "01010", "00100", "00100", "00100", "00100"],
    "0": ["01110", "10001", "10011", "10101", "11001", "10001", "01110"],
    "2": ["01110", "10001", "00001", "00110", "01000", "10000", "11111"],
    "3": ["11110", "00001", "00001", "01110", "00001", "00001", "11110"],
    "$": ["00100", "01111", "10100", "01110", "00101", "11110", "00100"],
    "·": ["00000", "00000", "00000", "00110", "00110", "00000", "00000"],
    "#": ["01010", "11111", "01010", "01010", "01010", "11111", "01010"],
    "→": ["00000", "00100", "00010", "11111", "00010", "00100", "00000"],
    ",": ["00000", "00000", "00000", "00000", "00110", "00110", "00100"],
}.items():
    FONT.setdefault(_c, _b)
FONT.setdefault(" ", ["00000"] * 7)


def plot(px, x, y, c):
    if 0 <= x < W and 0 <= y < H:
        o = (y * W + x) * 3
        px[o:o + 3] = bytes(max(0, min(255, int(v))) for v in c)


def rect(px, x, y, w, h, c):
    x0, x1 = max(0, x), min(W, x + w)
    if x1 <= x0:
        return
    for yy in range(max(0, y), min(H, y + h)):
        o = (yy * W + x0) * 3
        px[o:o + (x1 - x0) * 3] = bytes(c) * (x1 - x0)


def frame(px, x, y, w, h, c=CARD, border=STROKE):
    rect(px, x, y, w, h, c)
    rect(px, x, y, w, 1, border)
    rect(px, x, y + h - 1, w, 1, border)
    rect(px, x, y, 1, h, border)
    rect(px, x + w - 1, y, 1, h, border)


def text(px, s, x, y, scale=1, colour=TEXT):
    for ch in str(s).upper():
        for row, bits in enumerate(FONT.get(ch, FONT[" "])):
            for col, bit in enumerate(bits):
                if bit == "1":
                    for sy in range(scale):
                        for sx in range(scale):
                            plot(px, x + col * scale + sx, y + row * scale + sy, colour)
        x += 6 * scale


def text_w(s, scale=1):
    return len(str(s)) * 6 * scale


# ---------------------------------------------------------------- parse the guns

def parse_weapons():
    """One dict per `Weapon` entry, with the numbers read positionally.

    The enum's constructor is `label, glyph, damage, headshot, rpm, mag,
    reload, spreadHip, spreadAds, range, pellets, auto, ammo, zoom, recoil,
    [moveSpeed, price]` — a fixed order, so the entries are split by comma at
    bracket depth zero rather than by regex-guessing each field.
    """
    src = SIM.read_text()
    body = src[src.index("internal enum class Weapon("):]
    body = body[body.index(") {") + 3:]
    # Walk entries by hand: an entry starts at a line with NAME( and ends at the
    # first `)` that closes it. Defaults (price =, family =) run past the
    # positional arguments onto continuation lines, so bracket depth is the only
    # reliable terminator.
    guns = []
    entries = []
    i = 0
    while True:
        m = re.compile(r"\n    ([A-Z_][A-Z0-9_]*)\(").search(body, i)
        if not m:
            break
        depth, j = 0, m.end() - 1
        while j < len(body):
            if body[j] == "(":
                depth += 1
            elif body[j] == ")":
                depth -= 1
                if depth == 0:
                    break
            j += 1
        entries.append((m.group(1), body[m.end():j]))
        i = j + 1

    order = ["damage", "headshot", "rpm", "mag", "reload", "spread_hip",
             "spread_ads", "range", "pellets", "auto", "ammo", "zoom", "recoil"]
    for name, args in entries:
        parts, depth, cur = [], 0, ""
        for ch in args:
            if ch in "([":
                depth += 1
            elif ch in ")]":
                depth -= 1
            if ch == "," and depth == 0:
                parts.append(cur.strip())
                cur = ""
            else:
                cur += ch
        if cur.strip():
            parts.append(cur.strip())
        if len(parts) < 5:
            continue
        label = re.match(r'"(.*)"$', parts[0])
        if not label:
            continue
        label, glyph = label.group(1), parts[1].strip().strip('"')
        values = {}
        for key, raw in zip(order, parts[2:]):
            if "=" in raw and not raw.startswith('"'):
                break
            values[key] = raw

        def num(key):
            raw = values.get(key, "0f")
            if raw in ("true", "false"):
                return 1.0 if raw == "true" else 0.0
            raw = raw.rstrip("f")
            return float(raw) if re.fullmatch(r"-?\d+(\.\d+)?", raw) else 0.0

        fam = re.search(r'family = "(\w+)"', args)
        role = re.search(r'role = "([^"]+)"', args)
        price = re.search(r"price = (\d+)", args)
        guns.append({
            "name": name, "label": label, "glyph": glyph,
            "damage": num("damage") * max(1.0, num("pellets")),
            "rpm": num("rpm"), "range": num("range"), "mag": int(num("mag")),
            "spread_ads": num("spread_ads"), "reload": num("reload"),
            "family": fam.group(1) if fam else "rifle",
            "role": role.group(1) if role else "",
            "price": int(price.group(1)) if price else 0,
        })
    return guns


FAMILY_ORDER = ["pistol", "smg", "rifle", "shotgun", "sniper", "lmg", "launcher", "melee"]


def sort_like_game(guns):
    """`ArenaArmory.all()` order: family, then price — not declaration order."""
    def fam_rank(g):
        f = g["family"]
        return FAMILY_ORDER.index(f) if f in FAMILY_ORDER else 99
    return sorted(guns, key=lambda g: (fam_rank(g), g["price"]))


def stat_bars(g, all_guns):
    """Mirror of `Weapon.statBars`: absolute ceilings, not roster-relative."""
    def clamp(v):
        return max(0.05, min(1.0, v))
    move_speed = 0.85 + 0.0  # handled guns only; the sheet shows the default
    return [
        ("DMG", clamp(g["damage"] / 140.0)),
        ("RATE", clamp(g["rpm"] / 900.0)),
        ("RANGE", clamp(g["range"] / 260.0)),
        ("ACC", clamp(1.0 - g["spread_ads"] * 12.0)),
        ("HAND", clamp(move_speed * 1.0 * 0.85 + (1.0 - g["reload"] / 5.0) * 0.35)),
        ("MAG", clamp(g["mag"] / 75.0)),
    ]


# -------------------------------------------------------------------- draw sheet

def main() -> None:
    guns = sort_like_game(parse_weapons())
    if not guns:
        raise SystemExit("no weapons parsed — did the Weapon enum change shape?")
    buf = bytearray(BG * (W * H))
    px = buf

    # Header bar
    rect(px, 0, 0, W, 74, (19, 24, 31))
    rect(px, 0, 73, W, 1, STROKE)
    text(px, "ARMORY", 40, 22, 3, TEXT)
    text(px, f"{len(guns)} GUNS · POOL ALL", 40, 52, 1, DIM)
    text(px, "BUY IN MATCH FROM THE SHOP", W - 40 - text_w("BUY IN MATCH FROM THE SHOP"), 52, 1, AMBER)

    y = 96
    cur_family = None
    left, right = 40, W - 40
    card_w = (right - left - 20) // 2
    col = 0
    for g in guns:
        if g["family"] != cur_family:
            if col == 1:
                y += 104
                col = 0
            cur_family = g["family"]
            colour = FAMILY_COLOUR.get(cur_family, TEXT)
            y += 8 if y > 96 else 0
            rect(px, left, y, 4, 22, colour)
            text(px, cur_family.upper(), left + 14, y + 4, 2, colour)
            y += 34
            col = 0
        x = left + col * (card_w + 20)
        card_h = 96
        frame(px, x, y, card_w, card_h)
        colour = FAMILY_COLOUR.get(g["family"], TEXT)
        rect(px, x, y, 3, card_h, colour)
        # glyph square
        rect(px, x + 14, y + 14, 40, 40, (33, 40, 50))
        text(px, g["glyph"], x + 20, y + 24, 2, colour)
        text(px, g["label"], x + 66, y + 14, 2, TEXT)
        role = g["role"]
        max_chars = (card_w - 150) // 6
        if len(role) > max_chars:
            cut = role.rfind(" ", 0, max_chars)
            role = role[: cut if cut > 12 else max_chars] + "…"
        text(px, role, x + 66, y + 34, 1, DIM)
        price = f"${g['price']}" if g["price"] else "MELEE"
        text(px, price, x + card_w - 14 - text_w(price), y + 14, 1, AMBER)
        # bars
        bars = stat_bars(g, guns)
        by = y + 58
        bw = (card_w - 82) // 6
        for i, (name, v) in enumerate(bars):
            bx = x + 66 + i * (bw + 2)
            text(px, name, bx, by, 1, DIM)
            rect(px, bx, by + 9, bw - 3, 4, BAR_BG)
            rect(px, bx, by + 9, max(1, int((bw - 3) * v)), 4, colour)
        col += 1
        if col == 2:
            col = 0
            y += card_h + 12
    if col == 1:
        y += 96 + 12

    # Footer note about the bars being relative to the roster.
    y += 10
    text(px, "BARS USE THE SAME FIXED CEILINGS AS THE GAME: 140 DAMAGE, 900 RPM, 260 M, 75 ROUNDS.", left, y, 1, DIM)
    text(px, "IN GAME THE SHEET SHOWS ONLY THE FAMILIES THE CURRENT WEAPON POOL DRAWS FROM — THIS IS POOL: ALL.", left, y + 14, 1, DIM)
    y += 14
    text(px, "FAMILY ORDER: " + " · ".join(dict.fromkeys(g["family"].upper() for g in guns)), left, y + 14, 1, DIM)

    write_png(OUT, W, H, bytes(buf))
    print(f"wrote {OUT.relative_to(ROOT)} · {len(guns)} guns, {len(set(g['family'] for g in guns))} families")


if __name__ == "__main__":
    main()
