#!/usr/bin/env python3
"""Reference sheets for Tripo3D image-to-model, rendered from the shipped mesh.

Image-to-3D is only as good as the reference it is given, and a single
three-quarter beauty shot is close to the worst thing you can feed it: the model
cannot tell depth from shading and invents the back of the character. What it
wants is a small, consistent set of orthographic views on a plain background,
in a pose whose joints are visible, with no weapon and no scenery.

This script writes that set from the `.oeac` files the game actually loads, so
the reference and the shipped asset cannot drift apart:

    send-off-hero-front.png          / -side / -back / -three-quarter
    send-off-hero-tpose-front.png    (arms out, for the rigger)
    send-off-hero-tpose-side.png
    send-off-hero-head-front.png     (face detail, for the texture)
    send-off-hero-head-three-quarter.png
    send-off-hero-sheet.png          all six on one page

Usage:
  python3 tools/tripo/reference_sheet.py hero
  python3 tools/tripo/reference_sheet.py hero --pose tpose --tile 768x1152
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import render_oeac as R  # noqa: E402
from concept_to_oeac import write_png  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
OUT_DIR = ROOT / "tools/tripo/concepts"

WHITE = (255, 255, 255)
INK = (40, 44, 50)


def one(name: str, out: Path, yaw: float, pitch: float, tile_w: int, tile_h: int,
        pose: str, zoom: float = 1.0, centre_y: float = 0.90) -> None:
    parts, inverse_bind, tex_dir = R.load(name)
    mats = R.bone_matrices(inverse_bind, R.POSE_PRESETS.get(pose, {}))
    px = R.render(parts, tex_dir, tile_w, tile_h, yaw, pitch, WHITE,
                  matrices=mats, zoom=zoom, centre_y=centre_y)
    write_png(out, tile_w, tile_h, px)
    print(f"  {out.relative_to(ROOT)}  ({tile_w}x{tile_h}, yaw {yaw:g}, pose {pose})")


def sheet(name: str, out: Path, tiles: list[tuple[str, Path]], tile_h: int) -> None:
    """Concatenates already-rendered tiles into one labelled contact sheet."""
    widths = []
    for _label, path in tiles:
        w, h, _rows = _read(path)
        widths.append(w)
    total_w = sum(widths)
    canvas = bytearray(b"".join([bytes(WHITE)] * (total_w * tile_h)))
    x0 = 0
    for (label, path), w in zip(tiles, widths):
        _w, h, rows = _read(path)
        for y in range(min(h, tile_h)):
            dst = (y * total_w + x0) * 3
            canvas[dst:dst + w * 3] = rows[y][:w * 3]
        R.draw_label(canvas, total_w, tile_h, x0 + w // 2, tile_h - 26, label, WHITE, INK, 2)
        x0 += w
    write_png(out, total_w, tile_h, bytes(canvas))
    print(f"  {out.relative_to(ROOT)}  ({total_w}x{tile_h}, {len(tiles)} views)")


def _read(path: Path):
    from concept_to_oeac import read_png_rgb
    return read_png_rgb(path)


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("character", nargs="?", default="hero")
    p.add_argument("--tile", default="600x900", help="WxH per view")
    p.add_argument("--out-dir", type=Path, default=OUT_DIR)
    p.add_argument("--prefix", default=None, help="filename prefix (default send-off-<character>)")
    p.add_argument("--sheet-only", action="store_true", help="reassemble from existing tiles")
    args = p.parse_args()

    tile_w, tile_h = (int(v) for v in args.tile.lower().split("x"))
    name = args.character
    prefix = args.prefix or f"send-off-{name}"
    out_dir = args.out_dir
    out_dir.mkdir(parents=True, exist_ok=True)

    views = [
        ("FRONT", (0.0, 0.0)),
        ("THREE-QUARTER", (38.0, 4.0)),
        ("SIDE", (90.0, 0.0)),
        ("BACK", (180.0, 0.0)),
    ]
    tiles: list[tuple[str, Path]] = []
    if not args.sheet_only:
        print(f"{name}: pose sheets")
        for label, (yaw, pitch) in views:
            slug = label.lower().replace(" ", "-").replace("/", "-")
            path = out_dir / f"{prefix}-{slug}.png"
            one(name, path, yaw, pitch, tile_w, tile_h, "bind")
            tiles.append((label, path))

        print(f"{name}: T-pose (the pose a rigger wants)")
        for label, (yaw, pitch) in (("FRONT", (0.0, 0.0)), ("SIDE", (90.0, 0.0))):
            slug = label.lower()
            path = out_dir / f"{prefix}-tpose-{slug}.png"
            one(name, path, yaw, pitch, tile_w, tile_h, "tpose")
            tiles.append((f"T-POSE {label}", path))

        print(f"{name}: head detail")
        head_w, head_h = tile_w // 2, int(tile_w * 1.05)
        for label, (yaw, pitch) in (("FRONT", (0.0, 0.0)), ("THREE-QUARTER", (34.0, 2.0))):
            slug = label.lower().replace(" ", "-")
            path = out_dir / f"{prefix}-head-{slug}.png"
            one(name, path, yaw, pitch, head_w, head_h, "bind", zoom=4.4, centre_y=1.66)
        head_paths = [(f"HEAD {label.upper()}",
                       out_dir / f"{prefix}-head-{label.lower().replace(' ', '-')}.png")
                      for label in ("FRONT", "THREE-QUARTER")]

    print(f"{name}: contact sheet")
    sheet(name, out_dir / f"{prefix}-sheet.png", tiles, tile_h)
    if not args.sheet_only:
        sheet(name, out_dir / f"{prefix}-head-sheet.png", head_paths, head_h)
    print("done")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
