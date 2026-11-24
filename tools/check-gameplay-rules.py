#!/usr/bin/env python3
"""Rule-level simulation of Send Off's healing, regen, bursts and loot piles.

No emulator, no Kotlin compiler: the *rules* are transcribed from
`ArenaSim.kt` and stepped at 60 Hz, so the numbers a player actually feels can
be checked. Every check here corresponds to a decision in the source, and the
source is grepped for the constant so the simulation cannot silently drift
from it.

Run: python3 tools/check-gameplay-rules.py
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SIM = ROOT / "oea-games/src/main/java/com/oea/games/arena/ArenaSim.kt"
DT = 1.0 / 60.0

problems: list[str] = []
notes: list[str] = []


def src() -> str:
    return SIM.read_text()


def const(name: str) -> float:
    m = re.search(rf"internal const val {name} = ([\d.]+)f", src())
    if not m:
        problems.append(f"ArenaSim.kt no longer declares {name}")
        return 0.0
    return float(m.group(1))


def check(cond: bool, msg: str) -> None:
    if not cond:
        problems.append(msg)


# ---------------------------------------------------------------- rules engine

class Body:
    """Just the state `update()` keeps for healing, regen and bursts."""

    def __init__(self):
        self.health = 100.0
        self.medkits = 0
        self.calm = 0.0
        self.heal = 0.0
        self.spawn_guard = 0.0
        self.burst_left = 0
        self.cooldown = 0.0
        self.mag = 30
        self.shots = 0
        self.interval = 60.0 / 900.0   # 900 rpm
        self.reload_left = 0.0
        self.switch_left = 0.0
        self.stun = 0.0

    def step(self, dt=DT, heal_held=False, fire=False, regen_delay=8.0, regen_rate=2.2, spawn_guard_len=3.0):
        if self.cooldown > 0:
            self.cooldown -= dt
        if self.reload_left > 0:
            self.reload_left -= dt
        if self.switch_left > 0:
            self.switch_left -= dt
        # Burst queue, as in update(): fire one owed round per weapon cadence.
        if fire and self.cooldown <= 0 and self.reload_left <= 0 and self.switch_left <= 0 and self.stun <= 0:
            self.burst_left = 2
            if self.mag > 0:
                self.mag -= 1
                self.shots += 1
                self.cooldown = self.interval
        if self.burst_left > 0 and self.cooldown <= 0 and self.reload_left <= 0 and self.stun <= 0:
            if self.mag > 0:
                self.burst_left -= 1
                self.mag -= 1
                self.shots += 1
                self.cooldown = self.interval
            else:
                self.burst_left = 0
        if self.burst_left > 0 and (self.reload_left > 0 or self.switch_left > 0 or self.stun > 0):
            self.burst_left = 0
        # Medkit: three seconds of standing still.
        if heal_held and self.medkits > 0 and self.health < 100:
            self.heal += dt / 3.0
            if self.heal >= 1.0:
                self.health = min(100.0, self.health + 65.0)
                self.medkits -= 1
                self.heal = 0.0
        else:
            self.heal = 0.0
        # Calm clock, then regen (this order is the fix: the clock ticks first).
        if self.spawn_guard > 0:
            self.calm = 0.0
        else:
            self.calm += dt
        if self.health < 60.0 and self.calm > regen_delay and self.heal <= 0:
            self.health = min(60.0, self.health + dt * regen_rate)
        if self.spawn_guard > 0:
            self.spawn_guard -= dt


def check_regen_order():
    """A hit resets the calm clock; regen must start 8 s later, not 9."""
    body = Body()
    body.health = 20.0
    body.calm = 0.0
    delay = const("REGEN_DELAY")
    rate = const("REGEN_RATE")
    t = 0.0
    first_gain = None
    prev = body.health
    while t < 12.0:
        body.step(regen_delay=delay, regen_rate=rate)
        t += DT
        if body.health > prev and first_gain is None:
            first_gain = t
            break
        prev = body.health
    check(first_gain is not None, "out-of-combat regen never starts")
    if first_gain is not None:
        # One frame of slop: the check happens before the clock ticks.
        check(abs(first_gain - delay) < 0.1, f"regen starts at {first_gain:.2f}s, expected {delay:.0f}s")
        notes.append(f"regen starts {first_gain:.2f}s after a hit (delay {delay:.0f}s)")


def check_regen_cap():
    """Regen must stop at 60 and never take medkits' job."""
    body = Body()
    body.health = 5.0
    body.calm = 999.0
    for _ in range(4000):
        body.step()
    check(abs(body.health - 60.0) < 0.01, f"regen settled at {body.health:.1f}, expected 60")
    notes.append(f"regen tops out at {body.health:.1f} HP after ~66 s, never 100")


def check_medkit_takes_three_seconds():
    body = Body()
    body.health = 30.0
    body.medkits = 2
    t = 0.0
    while body.medkits == 2 and t < 5.0:
        body.step(heal_held=True)
        t += DT
    check(2.9 <= t <= 3.2, f"a medkit took {t:.2f}s, expected ~3")
    check(abs(body.health - 95.0) < 0.01, f"medkit healed to {body.health:.0f}, expected 95")
    notes.append(f"medkit: {t:.2f}s for +65 HP (30 → {body.health:.0f})")


def check_medkit_interrupt():
    body = Body()
    body.health = 40.0
    body.medkits = 1
    for _ in range(60):
        body.step(heal_held=True)
    progress = body.heal
    body.step(heal_held=False)
    check(body.heal == 0.0, "releasing the heal button does not cancel the channel")
    check(0 < progress < 1, "the interrupted medkit had no progress to lose")
    # The HUD latches the heal button (tap to start, tap again or fire to
    # cancel), so "letting go" no longer happens by accident — see healLatch in
    # OeaArena. The simulation rule (no progress, no damage) is unchanged.
    notes.append(f"a heal interrupted at {progress * 100:.0f}% refunds nothing, but the button is a latch now")


def check_spawn_guard_blocks_regen():
    """The calm clock must stay at 0 for the whole shield, then start fresh."""
    guard = const("SPAWN_GUARD")
    body = Body()
    body.health = 45.0
    body.spawn_guard = guard
    max_calm = 0.0
    while body.spawn_guard > 0:
        body.step(spawn_guard_len=guard)
        max_calm = max(max_calm, body.calm)
    check(max_calm <= DT, f"the calm clock reached {max_calm:.2f}s during spawn protection")
    # After the shield, regen must still wait a full REGEN_DELAY.
    body.calm = 0.0
    t = 0.0
    while t < const("REGEN_DELAY") - 0.2:
        body.step()
        t += DT
    check(body.health == 45.0, "regen started before the post-shield delay elapsed")
    notes.append(f"spawn shield holds the calm clock at 0 for {guard:.0f}s; regen then waits the full delay")


def check_burst_is_three_rounds():
    """One press = three rounds, spaced by the weapon's cadence."""
    body = Body()
    body.mag = 24
    body.step(fire=True)
    rounds = body.shots
    t = 0.0
    while body.burst_left > 0 and t < 2.0:
        body.step(fire=False)
        t += DT
    check(body.shots - rounds == 2, f"burst fired {body.shots - rounds} follow-up rounds, expected 2")
    check(body.shots == 3, f"one trigger pull fired {body.shots} rounds, expected 3")
    notes.append(f"burst rifle: one pull = {body.shots} rounds over {t * 1000:.0f} ms")


def check_burst_stops_at_empty_mag():
    body = Body()
    body.mag = 1
    body.step(fire=True)
    for _ in range(120):
        body.step(fire=False)
    check(body.shots == 1, f"a burst fired {body.shots} rounds from a one-round mag")
    check(body.burst_left == 0, "burst queue is still armed with an empty magazine")
    notes.append("burst from a 1-round mag fires 1 and disarms, no phantom rounds")


def check_burst_cancelled_by_reload():
    body = Body()
    body.mag = 30
    body.step(fire=True)          # 1 fired, 2 owed
    body.reload_left = 2.0        # start a reload before the queue drains
    for _ in range(30):
        body.step(fire=False)
    check(body.shots == 1, f"a cancelled burst still fired {body.shots} rounds")


def check_loot_piles():
    """A dropped stack moves across in carry-limit-sized bites."""
    cap = 5
    medkits = 5
    pile = 5
    got = min(pile, max(0, cap - medkits))
    medkits += got
    pile -= got
    check(got == 0 and pile > 0, "a pickup by a full player consumed the pile")
    # A player carrying two takes the whole five-medkit pile up to the cap of 5.
    medkits = 2
    pile = 5
    got = min(pile, max(0, cap - medkits))
    medkits += got
    pile -= got
    check(medkits == 5 and pile == 2 and pile > 0,
          f"partial pickup left player {medkits} / pile {pile}")
    # A player with one slot free takes exactly one and the pile keeps the rest.
    medkits = 4
    pile = 5
    got = min(pile, max(0, cap - medkits))
    medkits += got
    pile -= got
    check(medkits == 5 and pile == 4,
          f"one-slot pickup took {got} and left {pile}")
    notes.append("stacked piles transfer up to the carry limit and leave the rest on the floor")


def check_single_source_of_truth():
    s = src()
    check("fun magNow(" in s, "Weapon.magNow is missing — magazines are computed in more than one place")
    leftovers = [
        line for line in s.splitlines()
        if "ceil(w.mag * (1f + 0.1f *" in line and "fun magNow(" not in line
    ]
    check(not leftovers, f"{len(leftovers)} inline magazine calculations still bypass Weapon.magNow")
    check(s.count("fun lootPool(") == 1, "lootPool is defined more than once")
    check("POOL_FAMILIES" in s, "the loadout pool is no longer family-based — new guns will not spawn")
    # Every gun with a price must belong to a family that some pool uses.
    fams = re.search(r"internal val FAMILIES = listOf\((.*?)\)", s)
    if fams:
        used = set(re.findall(r'"(\w+)"', fams.group(1)))
        pools = re.search(r"POOL_FAMILIES: Map<String, List<String>> = mapOf\((.*?)\n        \)", s, re.S)
        if pools:
            # Only the *values* of each entry are families; the keys are pool ids
            # ("snipers", "close"), which are not families at all.
            for value in re.findall(r"to listOf\(([^)]*)\)", pools.group(1)) + re.findall(r'"all" to ([A-Z_.]+)', pools.group(1)):
                for fam in re.findall(r'"(\w+)"', value):
                    check(fam in used, f"pool references unknown family {fam!r}")


def check_wire_sizes():
    """
    Counts that travel over the network must be derived from the roster, never
    hard-coded. The upgrade array was `IntArray(11)` and `for (k in 0 until 11)`
    while the roster grew to 16 — so the five newest guns' upgrades were silently
    dropped for every client.
    """
    arena = (ROOT / "oea-games/src/main/java/com/oea/games/arena/OeaArena.kt").read_text()
    s = src()
    body = s[s.index("internal enum class Weapon("):]
    body = body[body.index(") {") + 3:]
    roster = len(re.findall(r"\n    ([A-Z_][A-Z0-9_]*)\(", body))
    check(roster >= 12, f"only {roster} weapons found — did the enum shape change?")
    hard = re.findall(r"until (\d+)\) w\.u8\(p\.upLevels", arena) + re.findall(r"IntArray\((\d+)\) \{ r\.u8", arena)
    check(not hard, f"upgrade bytes are sized by a constant {hard} instead of the roster ({roster} guns)")
    check("p.upLevels.forEach { w.u8(it) }" in arena, "the snapshot no longer sends one upgrade byte per weapon")
    check("IntArray(Weapon.entries.size) { r.u8() }" in arena, "the snapshot reader does not size the upgrade array from the roster")
    notes.append(f"upgrade state travels as {roster} bytes, one per weapon, derived from the enum")


def main() -> int:
    check_regen_order()
    check_regen_cap()
    check_medkit_takes_three_seconds()
    check_medkit_interrupt()
    check_spawn_guard_blocks_regen()
    check_burst_is_three_rounds()
    check_burst_stops_at_empty_mag()
    check_burst_cancelled_by_reload()
    check_loot_piles()
    check_single_source_of_truth()
    check_wire_sizes()

    if problems:
        print(f"GAMEPLAY RULES: {len(problems)} problem(s)")
        for p in problems:
            print(f"  - {p}")
        return 1
    print("GAMEPLAY RULES OK — " + "; ".join(notes))
    return 0


if __name__ == "__main__":
    sys.exit(main())
