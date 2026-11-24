# Send Off — review pass: what was blunt, and what it cost (2026-11-23)

This is the follow-up to `send-off-arsenal-and-looks-2026-11-23.md`. That pass
added the grade, the three new guns, regen and the host/join check. This one
went back through all of it looking for things that *worked* but were wrong,
fragile, or unpleasant to play — and fixed them at the root instead of patching
the symptom.

Everything here is verified by a script in `tools/`, not by eye. Where a claim
could only be checked on a device, it says so.

## The serious ones

### 1. The five newest guns never dropped on the island
`Weapon.lootPool(loadout)` was a **second, hand-written table** of specific guns,
while the front-end armory filtered by *families*. The two disagreed: the Carbine,
Burst rifle and Double barrel were buyable in the shop and listed in the armory,
but **could never spawn as loot**.

Fixed by making the pool a list of families (`POOL_FAMILIES`) and deriving the
gun list from it:

```kotlin
fun lootPool(loadout: String): List<Weapon> {
    val families = POOL_FAMILIES[loadout] ?: POOL_FAMILIES.getValue(POOL_DEFAULT)
    return guns.filter { it.family in families }
}
fun poolFamilies(loadout: String): List<String> = POOL_FAMILIES[loadout] ?: ...
```

`ArenaArmory.pool()` now *asks the loot table* rather than re-applying the
filter, so the armory sheet lists exactly the guns that can be found. A new gun
joins its pool the moment it declares a family — no second edit to remember.

### 2. Upgrade state for those guns was silently dropped over the network
The snapshot sent `for (k in 0 until 11) w.u8(p.upLevels[k])` while the roster is
**16**. The reader built `IntArray(11)`. Every client lost the upgrade levels for
the five newest weapons, and because the field count was fixed, adding a gun
would have shifted the wire format.

Now `p.upLevels.forEach { w.u8(it) }` / `IntArray(Weapon.entries.size)`, checked
by `check-gameplay-rules.py`.

### 3. Frames were composited twice when the post chain was unavailable
`Graphics.endFrame()` called `composite()` whenever a world pass had run, without
checking whether that pass had drawn straight to the screen. On any device where
the post chain fails, `composite()` clears to the backdrop — a black screen over
a finished frame. Now it only composites when the scene actually went to the
offscreen target, and the grade follows the same rule. `gradedWith` is also reset
per frame, so a menu frame cannot inherit the last match's grade.

### 4. Particles could draw through walls
`ParticleSystem.render` never enabled `GL_DEPTH_TEST`. The world pass enables it,
but a 2D pass earlier in the frame disables it, so depending on frame order every
spark, smoke puff and heal mote could render on top of geometry. Now enabled
explicitly.

### 5. A dropped stack was one pickup, one item
Bodies drop counted piles (5 medkits, 3 frags). `take()` treated every pile as a
single item, so five medkits became one — and a partial pickup marked the whole
pile taken, deleting loot the host had already told every client about.

Piles now carry `remaining`, pickups transfer *up to the carry limit*, leftovers
stay on the floor, and a new `EV_LOOT_CHANGED` event keeps clients in sync. The
explicit pickup path was duplicated logic with slightly different rules (it
ignored upgrades and always used the active slot) and now shares one `take()`.

### 6. Regen started a second late, every time
The calm clock was incremented **after** the regen gate, so the first gain came
at `REGEN_DELAY + one frame` and, worse, the HUD's "Regenerating" condition and
the simulation used different orders. The clock now ticks before the gate — 8.0 s
after the last hit, measured at 8.02 s in the harness.

### 7. Burst fire could leak a round into the next weapon
`burstLeft` survived a reload, a weapon switch or a stun, so the owed rounds
fired later — out of whatever gun you were holding then. It is now cleared by all
three. Verified: a burst from a 1-round magazine fires exactly one round.

## The ones you feel rather than see

### 8. Hold-to-heal was a handcuff
Healing required holding a button in the corner with one thumb while the other
had to aim *and* move, and letting go by accident threw away the kit's progress.
It is now a **latch**: tap to start, tap again (or fire, or swap) to cancel. The
button reads `STOP` while it runs.

### 9. "Regenerating" never explained itself
There was no way to tell whether regen was working or broken. The strip now shows
`Regen in 5s` with a countdown bar during the delay, then `Regenerating` with a
bar toward 60.

### 10. The status strip collided with the weapon slots
The first version of the strip anchored at `h * 0.60` and grew *downward*, so on
a short landscape screen it drew over the slot row. It is now built as a
prioritised list (reload, then heal/regen, then shield, then burst), anchored to
the slot row and grown *upward*, and it **drops the least urgent lines** when the
space runs out rather than overlapping.

`tools/tripo/preview_hud_status.py` renders the strip against the real anchors on
a 360×780 portrait, a 780×360 landscape and a 640×300 short screen, measures the
strip's bounding box, and fails if it crosses the slot row. Portrait fits all
five lines; landscape drops two; short drops three — and nothing overlaps.

### 11. Sniper, shotgun and pan bots were free kills
Bot fire logic was `if (w.auto) … else rng.chance(0.5f)`, so a bot with a double
barrel pulled the trigger at 30 m, and a bot with a pan shot at rifle range. Fire
decisions are now per weapon: pan only inside 2.4 m, double barrel inside 11 m,
shotgun inside 16 m, sniper/revolver only past 8 m, and a burst-rifle bot
releases the trigger between bursts instead of re-arming it every cooldown.

### 12. Body FX trailed the soldier
The heal ring, shield ring and wind streaks hung off the simulation position
while the body was drawn at an interpolated one — up to a frame ahead at sprint
speed. They now share `localDrawPos`, and stand down entirely in a scoped view
where the body is not drawn.

### 13. Two smaller smoothing fixes
The wind-streak threshold was a magic `7.2 m/s`; it now derives from the real
sprint speed and the held weapon's weight, so an LMG at a walk does not spray
streaks. Streak count follows the quality tier (3 on low-end, 7 otherwise), and
the player's grade/vignette prefs are read **once per frame** instead of six
times.

## Checks

```
python3 tools/check-kotlin.py         # STATIC CHECK OK — 306 sources
python3 tools/check-rules.py          # RULES OK — 12 modes
python3 tools/check-host-join.py      # HOST/JOIN OK
python3 tools/check-gameplay-rules.py # GAMEPLAY RULES OK (new)
python3 tools/tripo/preview_hud_status.py
python3 tools/tripo/preview_armory.py
python3 tools/tripo/preview_grade.py
```

`check-gameplay-rules.py` is new: it transcribes the healing, regen, burst and
loot rules from `ArenaSim.kt` and steps them at 60 Hz, so the numbers a player
feels are asserted rather than assumed. It also fails if magazines or wire sizes
are computed in more than one place.

**Not verified:** anything that needs a device — GPU behaviour of the grade, real
touch ergonomics of the heal latch, and LAN join on an actual router. The code
paths are checked; the hardware is not.
