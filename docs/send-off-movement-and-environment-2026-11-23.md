# Send Off — smoothed movement and environment coupling

This pass makes movement read as a body in a place rather than a capsule on a
heightfield: input is eased, the body accelerates instead of snapping, the
ground under the boots changes how it moves, and the air that moves the trees
is the same air that pushes the player.

## 1. The ground is a surface, not a height

`ArenaMap` now answers what the boots are standing on:

| API | Meaning |
| --- | --- |
| `Surface` | `GRASS`, `DIRT`, `ROCK`, `SAND`, `SNOW`, `WATER`, `WOOD` |
| `surfaceAt(x, z)` | Surface at a point: houses read as timber, shore as sand/water, roads and badlands as dirt, steep ground as rock |
| `gripAt(surface)` | Traction 0.62–1.0: snow and wet grass slide, rock and timber hold |
| `slopeAt(x, z)` / `downhillAt(x, z, out)` | 0 = flat, 1 = cliff, plus the fall line |
| `windAt(time, out)` | Prevailing wind with a slow gust cycle, in m/s |

These are used by movement, footsteps, bot routes, spawn selection, grenades
and the HUD, so no two systems can disagree about the world.

## 2. Movement smoothing

- **Velocity ramp.** Input asks for a speed; the body ramps toward it (15 m/s²
  on flat ground, 11 climbing, 4.5 in the air). Releasing the stick brakes at
  24 m/s². Strafes no longer snap between directions.
- **Surface-aware speed.** Snow 0.78, water 0.62, sand 0.86, grass 0.94; the
  same numbers the HUD shows.
- **Slope coupling.** Walking down the fall line is up to 6% faster, uphill
  costs up to 38%. Past a slope threshold the player slides until the ground
  flattens, with a leaning camera and a dust trail.
- **Wind push.** A grounded, exposed player drifts slightly downwind; the same
  vector sways the canopies, drifts grenades, and drives the weather particles.
- **Wall contact** bleeds velocity instead of pinning it against the wall.
- **Landing state**: `airTime` and `landImpact` are first-class player state.
  A hard landing fires one dust puff, shakes the view, dips the camera and
  tucks the rig's legs; nothing else fires on the same touchdown.
- **Camera**: horizontal follow spring 11, vertical spring 8.5 (stairs and
  ledges no longer step-jitter the view). Recoil now walks sideways as it
  climbs and settles back slower than it rose.
- **Body**: the render-only torso yaw follows the camera with a short lag, so a
  flick shows a turn; aim, shots and hit tests keep the exact angle.
- **Animation**: gait speed is smoothed per player, bodies bank into strafes,
  lean while sliding and tuck in the air.

## 3. Input smoothing

- **Stick response curve**: linear below a 45% knee, softened above, so fine
  adjustments are possible without losing top speed.
- **Stick vector easing** (frame-rate independent), so reversals feel like a
  body changing direction rather than a switch.
- **Look smoothing**: `LookPad` eases reported drag; a new pause-menu setting
  `Look smoothing` (0–70%, default 35%, read from `EngineSettings.lookSmoothing`)
  controls it. Large flicks still deliver the full rotation, just over a couple
  of frames.

## 4. Environment relation

- **Wind is one system**: fog density, particle drift, foliage, water swell and
  the player's own drift all come from `ArenaMap.windAt`.
- **Footsteps are surface-specific**: splash in water, crunch in snow, hollow
  knock on timber, scuff on dirt/rock, soft grass — each with its own pitch and
  volume, plus a dust puff coloured by the surface.
- **Foliage sways** on a per-tree phase, leaning downwind with the gust.
- **The sea breathes**: a slow vertical swell and drift instead of a static
  plane.
- **Ambient debris** (leaves, blossom, spindrift, sand) rides the same wind.
- **Spawning** picks a flat, dry, walkable spot instead of dropping players
  into water or on a cliff, so the first second of control is not a slide.

## 5. Bots use the same world

- `routeSpeed()` asks for a speed the ground can hold (slower uphill and in
  snow/water, faster downhill) instead of a flat 0.9.
- Cover strafe picks the side that is actually walkable (raycast both ways) and
  refuses to crouch on a slope, which used to slide them out of cover.
- Spawn and loot routes use surface and slope, not just distance.

## 6. Networking

- Local reconciliation resets the velocity ramp when it replaces the body, so
  corrections do not fight the acceleration.
- Duplicate repeated commands are dropped before the host consumes them, which
  removes the periodic double-step seen when packets overlapped.

## Files

- `oea-games/src/main/java/com/oea/games/arena/ArenaMap.kt` — surface, grip,
  slope, wind, level style.
- `oea-games/src/main/java/com/oea/games/arena/ArenaSim.kt` — velocity ramp,
  slope/wind coupling, landing state, spawn scoring, bot routes.
- `oea-games/src/main/java/com/oea/games/arena/OeaArena.kt` — camera springs,
  recoil drift, surface footsteps, landing/slide effects, foliage and sea
  motion, surface/wind HUD, look-smoothing setting, reconciliation fixes.
- `oea-engine/src/main/java/com/oea/engine/input/Controls.kt` — stick curve and
  easing, look smoothing.
- `oea-engine/src/main/java/com/oea/engine/OeaEngine.kt` — `lookSmoothing`.

## Design sheet

`tools/tripo/preview_movement.py` draws `tools/tripo/concepts/movement-preview.png`:
surface grip bars (with the resulting walk speeds), the three acceleration
ramps, the signed slope-cost chart with the slide threshold, and the shared wind
field. The numbers are hardcoded next to the same constants used in
`ArenaSim.move` and `ArenaMap.gripAt`; keep them in step when tuning.

## Validation

- `python3 tools/check-kotlin.py` passes (305 sources).
- No JDK / Android SDK here, so this is static validation only: no device
  frames, no Gradle build, no measured frame time.
- Feel was tuned against the movement values already in the game (4.8 m/s walk,
  8.4 m/s sprint, 7.4 m/s jump, −24 m/s² gravity); if the ramp reads too heavy
  in play, `accel` in `ArenaSim.move` is the single dial.
