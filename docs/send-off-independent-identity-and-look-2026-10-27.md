# Send Off — Independence, Icon Identity & Current Look
_2026-10-27_

Ground rules established by the user, confirmed against the code:

1. **Send Off is the only area of work** — the game itself, not the launcher or other games.
2. **"The app icon" means the Send Off icon** — `ic_oea_game_arena` — and it is Send Off's
   identity everywhere it appears.
3. **Send Off stands independent** — like Phone, Call Blocker, Game Boost and the other
   standalone apps, each with its own id, icon and task.

---

## 1. Send Off stands independent (verified in code)

`oea/src/main/AndroidManifest.xml` — Send Off is its own launcher app entry:

| Entry | Value |
|---|---|
| Activity | `com.oea.launcher.games.OeaSendOffActivity` |
| Label | `Send Off` |
| Icon / roundIcon | `@drawable/ic_oea_game_arena` (adaptive: bg `ic_oea_game_arena_bg` + fg `ic_oea_game_arena_fg`) |
| Intent filter | `MAIN` + `LAUNCHER` — tapping the icon opens Send Off directly, no hub in between |
| Task | `taskAffinity="com.oea.launcher.game.arena"`, `singleTask`, `OeaGameTheme` |

`oea/src/main/java/com/oea/launcher/games/OeaGameEntries.kt`:

- `OeaSendOffActivity : OeaStandaloneGameActivity()` with `standaloneGameId = "arena"` —
  each standalone game owns its front menu (Play, Match setup, Play with friends,
  Controls & aim, About), remembered settings, multiplayer and task.
- The stable engine id `arena` is unchanged, so Game Boost still detects it:
  `onMatchStarted` → `OeaGameBoost.onOeaGameOpened("arena")`.
- `oeaGameEntryScreens` lists Send Off's activities so Game Boost counts the session
  correctly.

One APK ships everything; each game keeps its own identity exactly like the other
standalone apps. **Send Off already stands on its own — this is the arrangement, not a
change request.**

## 2. The Send Off icon is its identity everywhere

`ic_oea_game_arena` (adaptive icon):

- **Background:** deep navy `#101D35`.
- **Foreground:** glowing concentric arena rings (cyan `#54D6FF`, ring stroke `#7FE5FF`),
  a dark core, an amber `#FFB24D` central tile, and white highlight dots — the respawn
  ring / battle-zone motif, the same language as the zone chevron in the game HUD.

Usage rules: launcher entry icon, round icon, app image, anywhere Send Off's identity is
shown (Dynamic Island, Game Boost, front menu). The amber-on-cyan ring is the icon; the
orange accent in the UI is the same family.

## 3. Engine Lab — what it is and why it exists

**What it is:** a first-party game registered in the engine registry with id `lab`,
title `Engine Lab`, glyph `⚗`, portrait, solo-only — live demos of the OEA Engine:
2D, 3D and 4D+ geometry, ECS, particles. Its source doubles as the compact reference for
building new games.

**Recognition status — unlike the other three games:**

| Property | Send Off | Endless Days | Chess | Engine Lab |
|---|---|---|---|---|
| Engine registry id | `arena` | `runner` | `chess` | `lab` |
| Standalone launcher activity + icon + task | ✔ | ✔ | ✔ | ✘ — **no manifest entry at all** |
| Appears in Games hub (launcher) | ✔ | ✔ | ✔ | ✔ |
| Modes | 6 + bots | 3 race modes + duel | full rules vs AI/local/net | solo demos only |
| Treat it as | a game | a game | a game | an **engine demo / reference**, not a game |

**Bottom line:** Engine Lab is not a game like the others — it is a showcase of the
engine and a coding reference. It is "recognized" only in the engine registry and the
Games hub; it has no icon, no standalone entry and no Game Boost detection. It is not
comparable to Send Off in activity, functionality or modes.

## 4. How Send Off looks now

Design renders of the current implementation (single APK, standalone entry, adaptive
arena-ring icon, 6 modes, Character Studio). Images in `docs/images/`:

| Image | What it shows |
|---|---|
| `send-off-icon-grid.png` | Send Off standing alone in the device app grid — its own icon, identity |
| `send-off-front-menu.png` | The standalone front screen: Play, Match setup, Play with friends, Controls & aim, About |
| `send-off-arena.png` | Battle Royale on the Green Island: skinned human soldiers, shrinking zone |
| `send-off-zone-boundary.png` | The glowing boundary curtain + ground ring as implemented |
| `send-off-hud.png` | The in-game HUD: minimap, kill feed, zone chevron, weapon slots |
| `send-off-combat.png` | Combat polish: muzzle flash, tracers, sparks on impact |
| `send-off-night-flashlight.png` | Lights Out / Haunted: the player's warm light in the permanent night |
| `send-off-haunted.png` | Haunted survival mode: green fog, night, glowing undead |
| `send-off-character-studio.png` | Character Studio inside Send Off — the live turntable |

> Note: this sandbox has no Android toolchain, so these are design renders of the
> implemented look — the actual game runs from the same code on a device.

## 5. Visuals — brought to match the target images exactly

The target images (`docs/images/send-off-*.png`) are the look. The code now produces it:

| Target image element | Implementation |
|---|---|
| Glowing zone boundary line | `drawWorld` zone block rebuilt: tall open additive curtain at the zone radius (was a solid pillar at 2× the radius) + bright ground ring at the boundary + faint target ring during shrink |
| Top-left player card | `drawTopBar` now draws portrait circle, health bar, armour bar and level chip top-left |
| Top-center zone chevron | Chevron arrow projected toward the safe zone via `camera.worldToScreen` + "ZONE" label and closing timer |
| Top-right kill feed | Unchanged (already top-right); mode card (TDM/Gun Game/Wave) moved top-right above it |
| Bottom-left minimap | `drawMinimap` relocated bottom-left, above the lifted joystick |
| Bottom-right weapon slots | `drawControls` slots moved bottom-right; bottom button row lifted so slots and buttons never overlap |
| Bottom teammate bars | New `drawTeammates`: health bars for living teammates along the bottom edge |
| Haunted undead glow | `drawSoldier` adds a sick-green additive aura on bots in Haunted, readable through the fog |
| Smooth skinned soldiers | `rig.drawSkinned` (skeletal pipeline) — unchanged, the smooth human look |
| Dramatic sky with sun | Engine sky shader sun disc + shadows at `shadowDistance 42` — unchanged |

Touch layout after the change: joystick home lifted to (0.13, 0.58); crouch/reload/heal buttons lifted to y ≈ 0.78–0.80 so the bottom strip is clean.

## 5b. Interaction & combat — enhanced to full capability

With the free-rein directive, the following was added on top of the target look:

| Enhancement | What it does |
|---|---|
| Smooth camera | Position and target now follow through `MathX.damp` exponential smoothing — the world glides instead of snapping; first frame snaps so the match starts clean |
| Weapon bob | Camera sway keyed to joystick magnitude — still hands when standing, a live body when moving |
| ADS blend | The whole camera transitions: field of view, offset and stance glide between hip-fire and scoped views instead of popping |
| Recoil recovery | Recoil kick now rises and recovers smoothly (decayed through `recoilPitch`) — the crosshair recovers like a real gun; aim and camera both follow the kick so shots stay glued to the sight |
| Reload dip | Camera dips slightly while reloading — the reload reads in the picture, not only in text |
| Switch jolt | A brief camera dip when swapping weapon slots |
| Blood hit sparks | Red spray particles on body hits (brighter on headshots), on top of the existing terrain sparks |
| Kill feed slide-in | Entries slide in from the right and settle |
| Scope reticle | Centre dot, stadia marks and mildots for ranging when ADS-zoomed |
| Footstep dust | Dust puffs on each local footstep, synced to the step sound |
| Night flashlight | Lights Out and Haunted now carry a warm point light with the player — the muzzle flash is no longer the only light in the permanent night |
| Muzzle light | (pre-existing) the muzzle adds a dynamic point light — flashes light the scene on every shot |

## 5c. Controls & inventory — enhanced to full capability

The user's "Controls and inventory" directive took two systems from partial to complete:

| Enhancement | What it does |
|---|---|
| Send Off control legend | `OeaSendOffActivity` overrides `controlLegend`/`keyLegend` — the "How you play" sheet now shows every gesture (move, look, fire, ADS, jump, crouch, reload, heal, grenades, pickup, weapon slots) instead of the empty placeholder |
| Keyboard legend | One line of the real bindings: mouse look · Shift ADS · Space jump · C crouch · R reload · G frag · H smoke · E pick · 1/2 weapons · Esc pause |
| Keyboard crouch | `C` now toggles crouch (was gamepad-only) |
| Grenade loot type | `LootKind.GRENADE` spawns at r≥0.97 — 60% frag / 40% smoke — and renders as a small spinning egg |
| Frag grenade | Explodes on a 2.4s fuse: 4.6 m damage radius with squared falloff (~80 max), kills attributed to "a grenade" in the feed |
| Smoke grenade | Clouds that block line of sight and stop fire through them for 10 s — a real tactical screen, not just VFX; anyone standing inside is hidden |
| Throw buttons | FRAG and SMK buttons bottom-left with live counts, one-shot latch (no auto-repeat), gated to alive + grounded |
| Throw keys | `G` frag, `H` smoke |
| Explosion VFX | Frag: 40-spark burst, bright point light, smoke column, camera shake (0.5 if within 12 m, else a faint 0.12); smoke: a tall grey column + a lingering translucent cloud volume |
| Fuse warning | The last 0.7 s of the fuse blinks a red additive sphere on the grenade |
| Net sync | Grenade counts in snapshots (reconciled), throw bits in the command stream, explosions delivered through `onGrenade` |
| TDM kit | Team Deathmatch loadouts now carry one frag — the deliberate kit includes a throwable |
| Pickup | The PICK prompt picks up weapons and grenades |

## 5d. Controls & inventory — second pass (reference-informed)

Investigated PUBG Mobile / CoD Mobile / Free Fire conventions (see
`docs/send-off-controls-inventory-intelligence-2026-10-27.md`) and applied the
five adopted enhancements:

| Enhancement | What it does |
|---|---|
| Stun grenade | Third throwable: white flash + 3 s of crawling movement (×0.45 speed, ×1.8 spread, no firing) inside 3.2 m; STUN button + I key; loot mix is now frag 60% / smoke 24% / stun 16% |
| Cook-off | Hold FRAG past 0.3 s cooks the fuse (button glows), release to throw early — the pull-pin mechanic from PUBG / Free Fire |
| Prone | Long-press crouch 0.5 s or Z to lie flat: 1.8 m/s, steadiest aim (×0.55 spread), camera and body drop low |
| Reload-all | Pause → Pack → Reload all weapons tops up every gun from reserves |
| Pack screen | Pause → Pack lists weapons with mag/reserve, medkits and all three throwables — the Free Fire bag / PUBG inventory |

Controls legend (Send Off activity) now documents all of the above, including
the hold-to-cook and long-press-to-prone gestures.

## 6. Send Off mode & content checklist (current)

| Mode | Zone | Respawn | Notes |
|---|---|---|---|
| Battle Royale | shrinking | — | skydive, loot, last standing |
| Team Deathmatch | none | ✔ | 2 teams, first to 40 |
| Gun Game | none | — | kill = next gun, pan finish |
| Clash Squad | none | round | 4v4, best-of-7, buy shop between rounds |
| Lone Wolf | none | round | 1v1, best-of-5, weapon pick every 2 rounds |
| Warfare | none | ✔ | 6v6, three capture points, first to 5 |
| King of the Hill | none | ✔ | 6v6, one hill, score while holding, first to 200 |
| Attack & Defend | none | ✔ | 6v6, assault vs defend, drain 300 tickets, 10 min clock |
| Resurgence Royale | shrinking | ✔ 20s | BR zone + comeback with loadout intact |
| Last Day | none | ✔ | day→night cycles, endless waves |
| Lights Out | none | ✔ | permanent night, tight fog |
| Haunted | none | ✔ | green fog, endless undead |

Levels: Green Island / Highlands / Badlands. Weapons: 8 classes with per-weapon handling.
Players: 1–12, SOLO / LAN / ONLINE, bots 0/5/9/15 × 3 difficulties.
Controls: twin-stick touch, gamepad, keyboard; ADS/crouch/prone/sprint; throwables (frag/smoke/stun) with buttons, keys and cook-off; pause pack with reload-all.
