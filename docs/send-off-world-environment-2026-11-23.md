# Send Off — world environment, seasons and day/night

This branch adds a world layer on top of Send Off's existing procedural island.
The match simulation is untouched: sky, light, weather and named places are
presentation state, so network matches stay deterministic.

## What is implemented

### 1. Four seasons

A new **Season** option (Spring, Summer, Autumn, Winter) is available in the
Send Off lobby and in match options:

- `ArenaMap` recolours ground and foliage through `seasonal()`.
- Winter adds snow cover on level ground (`snowCover`), keeps steep rock
  exposed, and adds snow/cold to the VFX system.
- Spring adds rain and wind, Autumn adds dry dust and stronger crosswind,
  Summer keeps the original warm haze.
- `ArenaWorld` tints the daytime sky, fog and sun; night fog and moonlight get a
  matching seasonal shift.

### 2. Named 3D locations

The three island levels keep their distinct landforms, but each town is now a
named point of interest with a physical landmark:

| Location | Place names | Landmark types |
| --- | --- | --- |
| Green Island | Port Raven, Palm Grove, Old Pier, Hilltop, Camp Kodo | lighthouse, windmill, supply tower |
| Highlands | Northwatch, Frost Pass, Radio Ridge, Mill Base, Summit Camp | radio mast, windmill, supply tower |
| Badlands | Rust Yard, Dust Mesa, Mine 7, Red Ridge, Scrap Town | mine rig, radio mast, supply tower |

`ArenaMap.Landmark` carries the position, name and type. Each landmark is built
with real geometry (painted meshes), has a physics collider and adds a supply
loot cache to `map.lootSpots` — so the place is interactable, not just a label.
Beacons and windows have a separate emissive mesh.

### 3. Day/night in every mode

`ArenaWorld.kt` replaces the old one-mode day/night switch:

- New **Time of day** option: Mode default, Full cycle, Daylight, Dusk, Night.
- `time` is a 0–1 cycle fraction: dawn, morning, midday, afternoon, dusk, night.
- Sun colour warms near dawn/dusk; sky, fog, ambient light, exposure and sun
  size blend smoothly instead of snapping between two palettes.
- The sun follows an arc; once it sets, the moon becomes the key light.
- At night, nearby landmarks glow and contribute point lights, and the player
  still carries a warm light in Lights Out / Haunted.
- Mode defaults preserve competitive readability: competitive modes start in
  daylight, Last Day starts at dawn and cycles, Lights Out / Haunted are night.

### 4. HUD

The top-left now shows the nearest named location, the current time phase
(Dawn, Morning, Midday, Afternoon, Dusk, Night) and the season.

## Files changed

- `oea-games/src/main/java/com/oea/games/arena/ArenaWorld.kt` — new environment director.
- `oea-games/src/main/java/com/oea/games/arena/ArenaMap.kt` — seasons, POI list, landmark geometry/colliders/loot.
- `oea-games/src/main/java/com/oea/games/arena/OeaArena.kt` — options, wiring, night lights, POI drawing, HUD label.
- `oea-games/src/main/java/com/oea/games/ArenaFront.kt` — Season and Time lobby chips.
- `tools/tripo/preview_environment.py` — dependency-free season × time swatch sheet.

## Visual preview

`tools/tripo/concepts/environment-preview.png` shows all four seasons at dawn,
noon, dusk and night. It is a palette preview, not a game screenshot.

## Validation and limits

- `python3 tools/check-kotlin.py` passes (304 sources).
- Python source compilation and the existing `.oeac`/`.oeam` smoke tests pass.
- No JDK / Android SDK is available in this sandbox, so nothing has been built
  or run on a device. Shader behaviour, frame cost and the look of the
  landmarks in-engine are unverified.
- The world clock is visual only; it is not synchronised across network peers.
  Each client renders its own sky from the same match settings and time reset.
- Landmark beacons use the renderer's shared point-light budget (max 8), so
  only the nearest lights win when many effects overlap.
