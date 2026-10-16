# Games independent, Arena competitive — 2026-10-16

Restructure of OEA's own games into complete, independent apps, and a
competitive pass over Arena (modes, guns, characters, levels, maps, controls,
aim tuning). Implemented against the real sources in this checkout. This
sandbox has no JDK/Android SDK and Maven is unreachable, so **nothing here is
compile- or device-verified**; every change was reviewed twice against the
surrounding APIs, and the static checker (brace/paren balance with a proper
Kotlin lexer, class-reference scan, manifest/package scan) passes on every
edited file. Real-device confirmation is still required.

## 1. Games out of the launcher — independent apps

The user's ask: *remove the in-launcher games from the general in-app OEA
launcher and make them independent apps.*

**Standalone game apps are now complete apps** (`OeaStandaloneGameActivity`,
shared by `com.oea.arena`, `com.oea.chess`, `com.oea.runner`):

- **Front menu** on icon launch: Play, Match setup, Play with friends,
  Controls & aim, About. Direct launches (hub, shortcuts) skip it via
  `EXTRA_AUTO_START`/`EXTRA_GAME_ID`/`EXTRA_GAME_SEED` and return to the
  launcher on exit instead of looping back to the menu.
- **Match setup** over the game's own options (modes, bots, terrain, loadout …),
  remembered per game through `EngineSettings.lastOption`.
- **Controls & aim** dialog: look sensitivity, ADS sensitivity, turn-speed
  limit (0 = off) and invert-Y — the same engine settings the games read live.
- **Its own multiplayer**: host on Wi-Fi/hotspot (with the OEA Wi-Fi join code)
  or online (invite code → reply code exchange), join nearby games
  (`LanBrowser`) or with any OEA code (`LanJoinCode`/`SignalCode`). Compact
  self-updating lobby with Start/Leave. The launcher is no longer required for
  any match.
- **Same-device play** through `EXTRA_GAME_MODE` + `EXTRA_GAME_PLAYERS`.

**Hub routing** (`OeaGamesActivity.launchGame`): solo and same-device matches
*always* open the game's own installed app. If it is not installed, the hub
says so plainly (package name and all) and only runs the match inside OEA
Games after an explicit "Run inside OEA Games just this once" choice — never
silently. Networked matches launched from a live hub lobby still run in the
hub process (the session socket lives there); the standalone apps can host and
join those matches by themselves now. The in-hub engine path remains for the
Engine Lab and hub-hosted sessions.

## 2. Arena — competitive restructure

### Modes with real simulation differences (`rules`, works solo and online)

| Mode | What actually changes in the sim |
| --- | --- |
| **Battle Royale** | zone, loot, last standing (unchanged core) |
| **Team Deathmatch** | two teams (blue/orange kits), 4 s respawns at team towns, 3 s spawn protection, friendly fire off, team score to 40, no zone |
| **Gun Game** | weapon ladder (pistol→SMG→rifle→shotgun→revolver→marksman→sniper→LMG→**pan**); one kill advances a stage, death drops one, a kill with the final pan wins; instant respawns; no loot |
| **Last Day Survival** | day/night cycle + waves (solo-scoped crowd spawns) |
| **Lights Out** | permanent night, tight fog |
| **Haunted** | green fog night, endless undead |

Protocol version bumped to **2** (snapshots carry `team`, `gunLevel`, team
scores). Bot ids are allocated in the u8 wire range with dead-bot recycling so
endless waves can never overflow the roster — which is what lets the modes run
in networked matches at all.

### Guns

Three new weapons appended to the enum (existing ordinals untouched):
**Revolver** (heavy hand-cannon, slow, high kick), **Marksman** (semi DMR with
a 2.8× glass), **LMG** (75-round belt, slow reload, heavy). Every weapon now
has a movement-speed weight (snipers and the LMG slow you down), per-weapon
sound pitch, its own mesh, and the loot tables respect a new **Weapon pool**
option (all / rifles & autos / long range / close quarters / pistols & SMGs).

### Levels

- **Terrain levels are real levels**: Green Island (rolling hills, broadleaf
  woods), Highlands (alpine ridges, **snowcaps**, pine forests), Badlands
  (red-rock ridges, pale caps, dry scrub, more rocks). Each has its own grass/
  rock/sand/snow palette, water colour, sky, haze and sun.
- **Dirt roads** now carve between the towns (flattened tracks, coloured into
  the terrain mesh) and **watchtowers** with solid stairs, railings and deck
  loot overlook them.
- **Player progression**: XP for eliminations (headshot bonus), victories,
  waves and match completion; a level curve (level *n* needs 400·(n−1)² XP
  total); a level chip on the HUD, level in the pause menu, XP row + progress
  bar on the end panel, island toast on level-up. Stored per device in the
  game's own storage (`xp`, `level`, `kills_total`, `matches`, `wins`).

### Characters ("human like")

`CharRig` (shared by Arena and Runner, source-compatible) gained:

- **Faces**: brows, dark eyes, nose and mouth riding the head yaw; ears stay.
- **Hair styles**: short cap, ponytail, top bun.
- **Body types**: a `feminine` build (narrower shoulders, wider hips, slimmer
  neck) — soldiers alternate builds per player id.
- **Gear**: optional day pack on the back.
- Arena soldiers get per-id scale variety (±5 %), team-coloured kits in TDM
  (helmets for everyone on a team) and per-variant rigs cached per
  (colour, id).

### Controls: sensitivity, turning without glitch, limits

- **Look sensitivity** (existing), plus **ADS sensitivity** (extra multiplier
  while aiming), **invert-Y** and a **turn-speed limit** (degrees/second, 0 =
  off) — new `EngineSettings` fields, editable live from Arena's pause menu
  (cycles with sensible steps) and from the standalone Controls dialog and the
  hub settings page.
- **Glitch-proof turning**: `LookPad` now swallows pointer-resync jumps above
  160–420 px/frame and ignores the frame a finger is claimed (no catch-up
  snap); Arena clamps per-frame look deltas to 28 % of the screen and — when
  the limit is set — clamps touch *and* gamepad yaw/pitch to `turnLimit × dt`,
  so the camera can never spin faster than the configured rate. Yaw stays
  wrapped to (−π, π] for long sessions.
- Deadzone gamepad turning, sprint/ADS/crouch movement weights per weapon.

### HUD & feel

Mode scoreboard (team scores / ladder stage / wave number / alive), level chip
with XP bar, respawn countdown panel in respawn modes (instead of a death
screen), teammate dots on the minimap, mode briefing in the pause menu
("Match & controls"), and end panels that show placement, kills, killer and
the XP won.

## 3. Engine changes (`oea-engine`)

- `EngineSettings`: `adsSensitivity` (0.3–1.5×), `invertLookY`,
  `turnSpeedLimit` (0–360°/s) next to `lookSensitivity`.
- `LookPad`: `maxDeltaPx` glitch clamp + claim-frame suppression.
- `CharRig`: faces, hair styles, `feminine`, `backpack` (all optional,
  default-off — Runner's existing constructor calls compile unchanged).

## Verification

- **Static (done)**: Kotlin lexer brace/paren/bracket balance over every edited
  file; `when` exhaustiveness over the grown `Weapon` enum (gunMesh, pitches,
  bot spacing); snapshot write/read field order mirrored; constructor arities
  checked against every call site (CharRig in Runner, ArenaSim in OeaArena,
  GameLaunch/NetSession in the standalone host); no other module references the
  moved `ArenaMap` materials.
- **Not verified here**: compilation, GLES behaviour, device touch feel of the
  turn limit and sensitivity steps, networked play across two phones.
