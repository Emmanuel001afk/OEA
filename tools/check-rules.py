#!/usr/bin/env python3
"""Consistency check for Send Off's data-driven mode rules.

Runs without a JVM: verifies that every rule exposed in the game descriptor has
a mode definition, and that every mode declares an objective and a way to end.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
GAMES = ROOT.parent / "oea-games/src/main/java/com/oea/games/arena"


def main() -> int:
    rules_src = (GAMES / "ArenaRules.kt").read_text()
    arena_src = (GAMES / "OeaArena.kt").read_text()

    modes = re.findall(r'Mode\("([^"]+)",\s*"[^"]+",\s*Objective\.(\w+)([^)]*)\)', rules_src)
    defined = {m[0]: m for m in modes}
    if len(defined) != len(modes):
        print("FAIL: duplicate mode ids", file=sys.stderr)
        return 1

    start = arena_src.find('GameOption("rules"')
    end = arena_src.find('GameOption("bots"', start)
    if start < 0 or end < 0:
        print("FAIL: could not locate rules options", file=sys.stderr)
        return 1
    block = arena_src[start:end]
    if "ArenaRules.all.map" in block:
        # The lobby builds its choices from the rules table, so they always match.
        exposed = set(defined)
    else:
        exposed = set(re.findall(r'Choice\("([^"]+)"', block))

    missing = sorted(exposed - defined.keys())
    if missing:
        print(f"FAIL: modes exposed without rules: {missing}", file=sys.stderr)
        return 1

    for mid, objective, body in modes:
        if objective not in {"LAST_ALIVE", "SCORE", "LADDER", "ROUNDS", "CAPTURE", "HILL", "TICKETS", "SURVIVE"}:
            print(f"FAIL: {mid} has unknown objective {objective}", file=sys.stderr)
            return 1
        ends = any(k in body for k in ("scoreLimit =", "timeLimit =", "roundTime =")) or objective == "LAST_ALIVE" or objective == "SURVIVE"
        if not ends:
            print(f"FAIL: {mid} has no win condition", file=sys.stderr)
            return 1

    # The HUD must read limits from the sim, never repeat stale numbers.
    stale = [lit for lit in ("first to 40", "first to 5", "first to 200", "drain 300") if lit in arena_src]
    if stale:
        print(f"FAIL: HUD hardcodes rule text {stale}", file=sys.stderr)
        return 1

    print(f"RULES OK — {len(modes)} modes, {len(exposed)} exposed in the lobby")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
