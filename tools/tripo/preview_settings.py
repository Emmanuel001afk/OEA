#!/usr/bin/env python3
"""Preview the Send Off settings display and the control-layout editor.

Two panels, both read from the real code so the sheet cannot drift from the
game:

  * the pause-screen hub and the pages behind it (Controls, Audio, Display,
    Gameplay) — the section names come straight out of `OeaArena.openPauseMenu`,
    `openAimPage`, `openAudioPage`, `openDisplayPage`, `openGameplayPage`;
  * the control layout, with each button's factory anchor and radius parsed from
    `btnDefaults` / `btnDefaultRadius` / `btnPortraitDefaults` in `OeaArena.kt`,
    drawn at the same normalised positions the game uses.

Not a screenshot — a design sheet, on the same pixel grid as the other
`tools/tripo` previews.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from concept_to_oeac import write_png  # noqa: E402
from preview_environment import FONT as BASE_FONT  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
ARENA = ROOT / "oea-games/src/main/java/com/oea/games/arena/OeaArena.kt"
FRONT = ROOT / "oea-games/src/main/java/com/oea/games/ArenaFront.kt"
OUT = ROOT / "tools/tripo/concepts/settings-preview.png"
W, H = 1240, 1330

EXTRA = {
    "F": ["11111", "10000", "10000", "11110", "10000", "10000", "10000"],
    "J": ["00111", "00010", "00010", "00010", "10010", "10010", "01100"],
    "K": ["10001", "10010", "10100", "11000", "10100", "10010", "10001"],
    "Q": ["01110", "10001", "10001", "10001", "10101", "10010", "01101"],
    "X": ["10001", "10001", "01010", "00100", "01010", "10001", "10001"],
    "Z": ["11111", "00001", "00010", "00100", "01000", "10000", "11111"],
    "1": ["00100", "01100", "00100", "00100", "00100", "00100", "01110"],
    "4": ["00010", "00110", "01010", "10010", "11111", "00010", "00010"],
    "5": ["11111", "10000", "11110", "00001", "00001", "10001", "01110"],
    "6": ["00110", "01000", "10000", "11110", "10001", "10001", "01110"],
    "7": ["11111", "00001", "00010", "00100", "01000", "01000", "01000"],
    "8": ["01110", "10001", "10001", "01110", "10001", "10001", "01110"],
    "9": ["01110", "10001", "10001", "01111", "00001", "00010", "01100"],
    "%": ["11001", "11010", "00010", "00100", "01000", "01011", "10011"],
    "+": ["00000", "00100", "00100", "11111", "00100", "00100", "00000"],
    "-": ["00000", "00000", "00000", "11111", "00000", "00000", "00000"],
    ".": ["00000", "00000", "00000", "00000", "00000", "00110", "00110"],
    "/": ["00001", "00010", "00010", "00100", "01000", "01000", "10000"],
    "(": ["00010", "00100", "01000", "01000", "01000", "00100", "00010"],
    ")": ["01000", "00100", "00010", "00010", "00010", "00100", "01000"],
    ":": ["00000", "00100", "00100", "00000", "00100", "00100", "00000"],
    "&": ["01100", "10010", "10100", "01000", "10101", "10010", "01101"],
    "!": ["00100", "00100", "00100", "00100", "00100", "00000", "00100"],
    "?": ["01110", "10001", "00010", "00100", "00100", "00000", "00100"],
    "=": ["00000", "11111", "00000", "00000", "11111", "00000", "00000"],
    "'": ["00100", "00100", "00000", "00000", "00000", "00000", "00000"],
}

# Every Latin letter, so a label parsed from source can never render as gaps.
for _ch, _bits in {
    "F": ["11111", "10000", "10000", "11110", "10000", "10000", "10000"],
    "J": ["00111", "00010", "00010", "00010", "10010", "10010", "01100"],
    "K": ["10001", "10010", "10100", "11000", "10100", "10010", "10001"],
    "Q": ["01110", "10001", "10001", "10001", "10101", "10010", "01101"],
    "X": ["10001", "10001", "01010", "00100", "01010", "10001", "10001"],
    "Z": ["11111", "00001", "00010", "00100", "01000", "10000", "11111"],
}.items():
    EXTRA.setdefault(_ch, _bits)
FONT = {**BASE_FONT, **EXTRA}

BG = (14, 18, 24)
CARD = (25, 30, 38)
CARD_HI = (33, 40, 50)
STROKE = (48, 56, 68)
TEXT = (238, 242, 248)
DIM = (132, 146, 166)
AMBER = (255, 190, 92)
MINT = (150, 232, 178)
CYAN = (110, 214, 226)
RED = (255, 110, 96)


def plot(px, x, y, c):
    if 0 <= x < W and 0 <= y < H:
        o = (y * W + x) * 3
        px[o:o + 3] = bytes(max(0, min(255, int(v))) for v in c)


def rect(px, x, y, w, h, c):
    for yy in range(max(0, y), min(H, y + h)):
        o = (yy * W + max(0, x)) * 3
        row = bytes(c) * max(0, min(W, x + w) - max(0, x))
        px[o:o + len(row)] = row


def frame(px, x, y, w, h, c=CARD, border=STROKE):
    rect(px, x, y, w, h, c)
    rect(px, x, y, w, 1, border)
    rect(px, x, y + h - 1, w, 1, border)
    rect(px, x, y, 1, h, border)
    rect(px, x + w - 1, y, 1, h, border)


def text(px, s, x, y, scale=1, colour=TEXT):
    for ch in str(s).upper():
        glyph = FONT.get(ch, FONT[" "])
        for row, bits in enumerate(glyph):
            for col, bit in enumerate(bits):
                if bit == "1":
                    for sy in range(scale):
                        for sx in range(scale):
                            plot(px, x + col * scale + sx, y + row * scale + sy, colour)
        x += 6 * scale
    return x


def text_w(s, scale=1):
    return len(str(s)) * 6 * scale


def circle(px, cx, cy, r, c, ring=None):
    for y in range(cy - r, cy + r + 1):
        for x in range(cx - r, cx + r + 1):
            d2 = (x - cx) ** 2 + (y - cy) ** 2
            if d2 <= r * r:
                inside = ring is None or d2 <= (r - ring) ** 2
                if inside:
                    plot(px, x, y, c)


# ------------------------------------------------------------------ parsing


def parse_controls():
    """(key, x, y) from btnDefaults, and key -> radius from btnDefaultRadius."""
    src = ARENA.read_text()
    anchors = {}
    block = re.search(r"private val btnDefaults: Map<String, FloatArray> = mapOf\((.*?)\n    \)", src, re.S)
    if block:
        for key, x, y in re.findall(r'"(\w+)" to floatArrayOf\(([\d.]+)f, ([\d.]+)f\)', block.group(1)):
            anchors[key] = (float(x), float(y))
    radii = {}
    rblock = re.search(r"private val btnDefaultRadius: Map<String, Float> = mapOf\((.*?)\n    \)", src, re.S)
    if rblock:
        for key, r in re.findall(r'"(\w+)" to ([\d.]+)f', rblock.group(1)):
            radii[key] = float(r)
    return anchors, radii


def parse_settings_defaults():
    """The engine's factory values, so the hub shows real numbers, not placeholders."""
    src = (ROOT / "oea-engine/src/main/java/com/oea/engine/OeaEngine.kt").read_text()
    floats = {k: float(v) for k, v in re.findall(r'getFloat\("(\w+)", ([\d.]+)f\)', src)}
    bools = {k: v == "true" for k, v in re.findall(r'getBoolean\("(\w+)", (true|false)\)', src)}
    strings = {k: v for k, v in re.findall(r'getString\("(\w+)", "([^"]*)"\)', src)}
    return floats, bools, strings


def parse_menu(block: str):
    """Labels of the rows a menu page builds, in source order."""
    return re.findall(r'MenuItem\.(?:header|choice|toggle|slider|\w+)\("\w*",\s*"([^"]+)"', block)


def parse_grouped_hub():
    src = ARENA.read_text()
    m = re.search(r"private fun openPauseMenu\(\) \{(.*?)\n    \}\n", src, re.S)
    body = m.group(1) if m else ""
    # Walk the source in order, keeping headers and rows together.
    rows = []
    for item in re.finditer(r'MenuItem\.(header|choice|toggle|slider|action)?\(?\s*"?([^"\n]*)', body):
        pass
    for line in body.splitlines():
        line = line.strip()
        h = re.match(r'MenuItem\.header\("([^"]+)"\)', line)
        if h:
            rows.append(("header", h.group(1)))
            continue
        c = re.match(r'MenuItem\.(choice|toggle|slider|toggleOr)\("(\w+)",\s*"([^"]+)"', line)
        if c:
            rows.append(("row", c.group(3)))
            continue
        a = re.match(r'MenuItem\("(\w+)",\s*"([^"]+)"', line)
        if a:
            rows.append(("row", a.group(2)))
    return rows


# --------------------------------------------------------------------- draw


def hub_value(label, floats, bools, strings):
    """The live summary a hub row shows, from the engine's factory values."""
    look = floats.get("look_sensitivity", 1.0)
    ads = floats.get("ads_sensitivity", 0.85)
    vol = floats.get("volume", 0.9)
    hud = floats.get("hud_opacity", 1.0)
    assist = floats.get("aim_assist", 0.35)
    low = label.lower()
    if "look & aim" in low:
        return f"{look:.1f}X · ADS {ads * 100:.0f}%"
    if "layout" in low:
        return "FACTORY"
    if "left-handed" in low:
        return "ON" if bools.get("left_handed", False) else "OFF"
    if "hold to aim" in low:
        return "ON" if bools.get("tap_fire", False) else "OFF"
    if low.startswith("sound"):
        on = [n for n, k in (("EFFECTS", "sfx"), ("MUSIC", "music"), ("ANN", "voice")) if bools.get(k, True)]
        return f"{vol * 100:.0f}% · " + ("+".join(on) if on else "ALL OFF")
    if low == "display":
        return f"{strings.get('quality', 'auto').upper()} · HUD {hud * 100:.0f}%"
    if low == "gameplay":
        return f"ASSIST {assist * 100:.0f}%"
    if "pack" in low:
        return "2 SLOTS"
    return None


def draw_hub(px, x0, y0, w):
    rows = parse_grouped_hub()
    floats, bools, strings = parse_settings_defaults()
    frame(px, x0, y0, w, 52, CARD_HI)
    text(px, "SEND OFF - LEVEL 12", x0 + 16, y0 + 12, 2, TEXT)
    text(px, "MATCH KEEPS RUNNING", x0 + 16, y0 + 34, 1, DIM)

    y = y0 + 68
    for kind, label in rows:
        if kind == "header":
            text(px, label, x0 + 6, y + 4, 1, AMBER)
            y += 20
            continue
        if "QUIT" in label.upper() or "LEAVE" in label.upper():
            frame(px, x0, y, w, 34, CARD)
            text(px, label.upper(), x0 + 12, y + 12, 1, RED)
            y += 40
            continue
        value = hub_value(label, floats, bools, strings)
        frame(px, x0, y, w, 34, CARD)
        shown = label[:30]
        text(px, shown.upper(), x0 + 12, y + 12, 1, TEXT)
        if value:
            text(px, value, x0 + w - 14 - text_w(value), y + 12, 1, AMBER)
        else:
            text(px, ">", x0 + w - 16, y + 12, 1, DIM)
        y += 40
    return y


def draw_page(px, x0, y0, w, title, rows, accent):
    frame(px, x0, y0, w, 44, CARD_HI)
    text(px, title, x0 + 14, y0 + 14, 2, TEXT)
    y = y0 + 58
    for kind, label, value in rows:
        if kind == "header":
            text(px, label, x0 + 4, y + 2, 1, accent)
            y += 20
            continue
        frame(px, x0, y, w, 32, CARD)
        if value is None:
            text(px, label[:46], x0 + 10, y + 12, 1, TEXT)
        else:
            text(px, label[:34], x0 + 10, y + 12, 1, TEXT)
            text(px, value, x0 + w - 10 - text_w(value), y + 12, 1, accent)
        y += 37
    return y


def draw_layout(px, x0, y0, w, h):
    anchors, radii = parse_controls()
    frame(px, x0, y0, w, h, CARD)
    text(px, "BUTTON LAYOUT - LANDSCAPE", x0 + 14, y0 + 12, 2, TEXT)
    text(px, "PARSED FROM btnDefaults / btnDefaultRadius", x0 + 14, y0 + 34, 1, DIM)
    ix, iy = x0 + 12, y0 + 52
    iw, ih = w - 24, h - 126
    rect(px, ix, iy, iw, ih, (11, 14, 19))
    frame(px, ix, iy, iw, ih, (11, 14, 19), (40, 48, 58))

    # Stick zone (left) and look zone (right) — the regions the game uses.
    stick_r = (0.0, 0.32, 0.42, 1.0)
    rect(px, ix + int(stick_r[0] * iw), iy + int(stick_r[1] * ih),
         int((stick_r[2] - stick_r[0]) * iw), int((stick_r[3] - stick_r[1]) * ih), (18, 24, 31))
    text(px, "STICK", ix + 10, iy + ih - 16, 1, (70, 84, 100))
    text(px, "LOOK", ix + iw - 34, iy + 8, 1, (70, 84, 100))

    # A horizon line hints at the 3D scene behind the HUD.
    for xx in range(ix, ix + iw):
        yy = iy + int(ih * 0.46)
        plot(px, xx, yy, (30, 38, 48))

    for key, (ax, ay) in anchors.items():
        r = int(radii.get(key, 26.0) * 1.05)
        cx = ix + int(ax * iw)
        cy = iy + int(ay * ih)
        # Safe-area offset the game applies to left-anchored buttons.
        circle(px, cx, cy, r, (22, 27, 34), ring=max(1, r // 8))
        ringc = AMBER if key == "fire" else (86, 98, 116)
        for a in range(0, 3600, 4):
            import math
            th = a * 3.14159265 / 1800.0
            plot(px, cx + int(math.cos(th) * r), cy + int(math.sin(th) * r), ringc)
        label = key[:5].upper()
        text(px, label, cx - text_w(label) // 2, cy - 3, 1, TEXT)

    # Sizes legend: the radius in dp next to each key, in two columns.
    ly = iy + ih + 16
    items = sorted(anchors.keys(), key=lambda k: -radii.get(k, 0.0))
    for i, key in enumerate(items):
        col = i % 3
        row = i // 3
        text(px, f"{key} {int(radii.get(key, 0))}", ix + col * (iw // 3), ly + row * 12, 1, DIM)


def main() -> int:
    px = bytearray(BG * (W * H))
    text(px, "SEND OFF - SETTINGS DISPLAY & CONTROL LAYOUT", 24, 18, 3, AMBER)
    text(px, "GROUPED HUB + PAGES · EVERY VALUE ON THE PAGE THAT CHANGES IT", 24, 44, 1, DIM)

    # Left column: the grouped pause hub.
    hub_w = 340
    text(px, "1 PAUSE - GROUPED HUB", 24, 66, 2, MINT)
    end = draw_hub(px, 24, 88, hub_w)

    # Middle column: three of the pages behind it.
    page_x = 24 + hub_w + 22
    page_w = 380
    text(px, "2 PAGES", page_x, 66, 2, MINT)
    y = 88
    y = draw_page(px, page_x, y, page_w, "LOOK & AIM",
                  [("header", "SENSITIVITY", None),
                   ("row", "Look sensitivity", "1.25X"),
                   ("row", "ADS sensitivity", "85%"),
                   ("header", "TURN & SMOOTHING", None),
                   ("row", "Turn speed limit", "180F/S"),
                   ("row", "Look smoothing", "50%"),
                   ("header", "ORIENTATION", None),
                   ("row", "Invert vertical look", "OFF")], AMBER) + 14
    y = draw_page(px, page_x, y, page_w, "SOUND",
                  [("header", "MASTER", None),
                   ("row", "Sound", "ON"),
                   ("row", "Master volume", "90%"),
                   ("header", "CHANNELS", None),
                   ("row", "Sound effects", "ON"),
                   ("row", "Effects level", "100%"),
                   ("row", "Music", "ON"),
                   ("row", "Music level", "45%"),
                   ("row", "Announcer", "ON"),
                   ("row", "Announcer level", "85%"),
                   ("row", "Duck music for announcer", "ON"),
                   ("header", "TOUCH", None),
                   ("row", "Haptics", "ON"),
                   ("row", "Haptic strength", "100%")], CYAN) + 14

    # Right column: the control layout, to scale.
    lay_x = page_x + page_w + 22
    lay_w = W - lay_x - 24
    text(px, "3 CONTROL LAYOUT", lay_x, 66, 2, MINT)
    draw_layout(px, lay_x, 88, lay_w, 640)

    # Notes under the layout.
    y = 88 + 660
    notes = [
        (AMBER, "DRAG A CIRCLE TO MOVE IT; TAP ONE AND USE - / + TO RESIZE IT."),
        (MINT, "EACH BUTTON KEEPS ITS OWN POSITION AND SIZE, PER ORIENTATION"),
        (MINT, "AND PER HANDEDNESS - A LEFT-HANDED LAYOUT IS ITS OWN SET."),
        (CYAN, "RESET ALL IS ONE ROW. RESET ONE IS A ROW PER CONTROL."),
        (DIM, "RADII IN DP, AS THE GAME APPLIES THEM."),
    ]
    page_end = 0
    for i, (c, line) in enumerate(notes):
        # Wrap at the column edge instead of clipping the line.
        words, row, rows = line.split(" "), "", []
        for word in words:
            probe = (row + " " + word).strip()
            if text_w(probe) + 8 > lay_w:
                rows.append(row)
                row = word
            else:
                row = probe
        rows.append(row)
        for r, part in enumerate(rows):
            text(px, part, lay_x, y + i * 30 + r * 12, 1, c)
        page_end = y + i * 30 + (len(rows) - 1) * 12 + 26

    draw_page(px, lay_x, page_end, lay_w, "DISPLAY",
              [("header", "PERFORMANCE", None),
               ("row", "Quality", "HIGH"),
               ("row", "Frame rate", "MAX 120 HZ"),
               ("row", "Adaptive resolution", "ON"),
               ("row", "FPS counter", "OFF"),
               ("header", "HUD", None),
               ("row", "HUD opacity", "100%"),
               ("row", "Button opacity", "82%"),
               ("header", "RETICLE", None),
               ("row", "Crosshair", "CROSS"),
               ("row", "Crosshair colour", "MINT")], (255, 170, 190))

    OUT.parent.mkdir(parents=True, exist_ok=True)
    write_png(OUT, W, H, bytes(px))
    print(f"wrote {OUT.relative_to(ROOT)} ({W}x{H})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
