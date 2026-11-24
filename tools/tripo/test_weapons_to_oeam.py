#!/usr/bin/env python3
"""Smoke test for the generated .oeam weapon assets."""

import struct
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import weapons_to_oeam as w  # noqa: E402


def validate(path: Path) -> None:
    data = path.read_bytes()
    assert data[:4] == b"OEAM", path
    version, vc, ic = struct.unpack_from("<HHI", data, 4)
    assert version == 1 and vc > 0 and ic > 0 and ic % 3 == 0, path
    assert len(data) == 12 + vc * 48 + ic * 4, path
    idx = struct.unpack_from("<%dI" % ic, data, 12 + vc * 48)
    assert max(idx) < vc, path


def test() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        out = Path(tmp) / "weapons" / "rifle.oeam"
        b = w.Builder()
        w.detail_gun(b, "rifle")
        w.write_oeam(out, b)
        validate(out)

    shipped = Path(__file__).resolve().parents[2] / "oea-engine/src/main/assets/weapons"
    files = sorted(shipped.glob("*.oeam"))
    assert len(files) == 13, f"expected 13 shipped weapon assets, found {len(files)}"
    for path in files:
        validate(path)
    print("weapons_to_oeam smoke test passed")


if __name__ == "__main__":
    test()
