# OEA — App Design, Areas, Activity & Modes Comparison
_2026-10-27_

This document compares every area of the OEA platform — the app design itself, the app icon,
the launcher's tool surface, and all four game areas (Send Off, Endless Days, Chess, Engine Lab)
— by activity, functionality and modes, then lists the gaps and what to build next. It is
grounded in the current source of `oea/` (launcher) and `oea-games/`.

---

## 1. App design — the OEA launcher

The launcher (`oea/src/main/java/com/oea/launcher/`) is one dark, console-styled home screen:

- **Workspace-based home.** The home screen is a single engine-rendered workspace
  (`OeaHomeSurfaceController` attaches `OeaLauncherEngine.workspace`), so the wallpaper,
  app grid and widgets redraw at full frame rate and survive activity recreation.
- **Dark console palette.** The Games hub theme (`OeaGamesTheme`) is the reference:
  background `#08090D`, cards `#14161E`, amber accent `#FFA53D`, white text `#F2F4F8`,
  glass `alpha(white, 0x1A)`, danger `#FF6E78`, success `#50E1A0`, warning `#FFC464`.
  Game Boost uses the same structure in blue — deliberate visual separation between areas.
- **App grid + pinned icons.** Every mini-app launches as its own activity with its own icon
  (AndroidManifest), but all render inside OEA Home, so design language stays consistent.
- **Dynamic Island.** `OeaDynamicIslandOverlayService` runs on resume — game sessions and
  calls surface as island alerts, which is the launcher's signature interaction.
- **Game Boost.** `OeaGameBoost` sessions track third-party apps, boost their render/window
  settings while the game is open and end cleanly when the user leaves home.
- **Themes.** A standalone OEA Themes app (`themes/`, `OeaThemePreviewView`) restyles the
  whole launcher, including lock screen previews — the design is theme-driven, not hardcoded.
- **Character Studio.** A first-party mini-app (`CharacterStudio.kt`) that reshapes the
  playable character in real time on a turntable; the same character returns in matches.
- **Mini-apps suite.** Clock, Calendar, Calculator, Notes, Messaging, Camera, Video, Weather,
  Voice, Console, Phone, Call Blocker, AppLock, Folders, Freezer, Assistant, Emage… — the
  launcher is the platform's "console," and the games are first-class citizens on it.

**Design strengths:** consistent dark UI, one accent per area, procedural art instead of
shipped bitmaps, fault-shielded screens (`OeaFaultShield`), everything relaunches cleanly.

**Design gaps:** no first-party wallpaper/theme store, no search/global index of mini-apps,
and the mini-apps themselves have no cross-linking (e.g. Weather → notes).

---

## 2. App icon

The launcher icon is a fully vector adaptive icon (no bitmaps anywhere in the repo):

- **`oea_logo.xml` / `ic_oea_launcher_background`** — a midnight rounded field
  (linear gradient `#17265E → #0B1224 → #05070F`) with a teal/blue radial glow
  (`#4A2BC7D8 → #262B4FD8 → transparent`).
- **`ic_oea_launcher_foreground`** — the OEA glyph: a Home-screen app grid (rounded tiles),
  with the top-right tile **lifted** the way Android shows a picked-up app, and one accent
  tile carrying the teal→blue identity gradient (`#3BF2E4 → #3F7DFF`). All shapes stay inside
  the adaptive-icon safe circle (radius 33 around 54,54).
- **`ic_oea_launcher_monochrome`** — a single-colour layer for Android 13+ themed icons.
- The same badge (`oea_logo.xml`) is reused for in-app headers, About and loading states,
  so one design system carries the whole app.

**Icon gap:** only the launcher icon exists as a full adaptive set; the games and mini-apps
use flat vector drawables without background/foreground separation, so they cannot theme
adaptively the way the launcher icon does.

---

## 3. Game areas — activity, functionality, modes

All four first-party games register through `OeaGames.registerBuiltIns` and appear in the
Games hub, on the Dynamic Island and in the standalone APKs (`only = id`).

### 3.1 Send Off (`arena/`) — the flagship
| Aspect | What it has |
|---|---|
| **Activity** | 3D competitive shooter on the engine's full pipeline: skybox + bloom, sun shadows, terrain meshes, road/river/bridge/temple/bridge ruins, particle launch smoke, audio middleware |
| **Modes (6)** | Battle Royale (zone shrink, last standing), Team Deathmatch (2 teams, respawns, spawn protection, first to 40), Gun Game (kill = next gun, pan finish), Last Day (day→night cycles, endless waves), Lights Out (permanent night, tight fog), Haunted (green fog, endless undead) |
| **Levels** | 3 islands — Green Island, Highlands (snow), Badlands (red rock) — each with own landform, palette, roads, vegetation |
| **Functionality** | 8 weapon classes (pistols, SMGs, rifles, shotguns, revolvers, marksman rifles, LMGs, snipers), loot, ADS/crouch/sprint, per-weapon handling, weather (rain/fog/dust), wetness tint, Character Studio integration (gender/build/face/skin/hair/clothing, saved per-player), scoreboards, respawn countdowns, pause menu with real camera options |
| **Players** | 1–12 · SOLO / LAN / ONLINE + bots (0/5/9/15) × 3 difficulties |
| **Controls** | Twin-stick touch, gamepad, keyboard; look/ADS sensitivity, invert-Y, turn-speed limit |

### 3.2 Endless Days (`runner/`)
| Aspect | What it has |
|---|---|
| **Activity** | Endless-runner survival race in a rail yard: lane switching, jumps, rolls, ramps onto roofs, power-ups, moving trains |
| **Modes** | Solo survival; Duel on one phone (split-screen or take-turns); Race over LAN/online (last runner standing, first to 2 km, first to 5 km) |
| **Functionality** | Score + coins, distance tracking, split-screen HUD, rotated-phone layout, pause |
| **Players** | 1 solo / 2 same-device / up to 8 networked · SOLO / SAME_DEVICE / LAN / ONLINE |
| **Gap** | No AI opponents — a solo player only has a ghost-less empty track; adding bots (like Send Off's) is the obvious parity win |

### 3.3 Chess (`chess/`)
| Aspect | What it has |
|---|---|
| **Activity** | Full-rules chess with 3D or 2D board views |
| **Modes** | Solo vs 10-level AI (Beginner → Maximum), same-device, LAN, online |
| **Functionality** | Castling, en passant, promotion, every draw rule, clocks (bullet/blitz/rapid/classical), colour choice, board flip, move history |
| **Players** | 1–2 · SOLO / SAME_DEVICE / LAN / ONLINE |
| **Gap** | No puzzles/training, no undo-in-game toggle, no simultaneous boards; AI is pure engine (no opening book beyond best-move variance) |

### 3.4 Engine Lab (`lab/`)
| Aspect | What it has |
|---|---|
| **Activity** | Live engine demos: 2D, 3D and 4D+ geometry, ECS, particles |
| **Modes** | Solo only — it is a showcase/reference |
| **Functionality** | Doubles as the compact source reference for building new games |
| **Gap** | No interactive playground — every demo is a fixed scene; a "custom shader" panel would make it a real lab |

### 3.5 Cross-area mode matrix
| Game | Solo | Bots | Same-device | LAN | Online | Levels/Variety |
|---|---|---|---|---|---|---|
| Send Off | ✔ | ✔ (3 difficulty) | — | ✔ | ✔ | 6 modes × 3 islands × 5 weapon pools |
| Endless Days | ✔ | ✘ | ✔ (split/turns) | ✔ | ✔ | 3 race modes + survival |
| Chess | ✔ | ✔ (10 levels) | ✔ | ✔ | ✔ | 7 clock formats × 3D/2D |
| Engine Lab | ✔ | ✘ | ✘ | ✘ | ✘ | Fixed demos |

---

## 4. Comparison verdict

- **Send Off is the platform's showcase** — the only area with real modes beyond "play,"
  the only one with characters you reshape (Character Studio), and the only one that
  exercises the full engine (3D skinned characters, zones, weather, audio).
- **Endless Days and Chess are complete games but mode-light:** they cover every play-type
  (solo/local/network) but lack bots for Endless Days and depth (puzzles, books) for Chess.
- **Engine Lab is a demo, not a game** — it has no activity beyond watching.
- **The launcher itself is the strongest design asset:** dark console look, per-area
  accents, procedural cover art, adaptive vector icon, Dynamic Island, fault shielding.
- **The icon is the weakest visual asset by scope:** one adaptive set for the launcher,
  flat drawables elsewhere.

### Recommended next steps (priority order)
1. **Endless Days bots** — a difficulty-scaled AI runner so solo mode has opponents (Send Off parity).
2. **Chess depth** — puzzles (mate-in-N), opening book, undo; then online lobby ranking.
3. **Adaptive icons for every mini-app and game** — split each flat drawable into
   background/foreground so they theme on Android 13+ and look consistent in the grid.
4. **Engine Lab playground** — parameter sliders for terrain/character/particle demos,
   which also serves as a live character-studio complement.
5. **Global search + cross-links** in the launcher (apps → mini-apps).

---

## 5. Images

Mockups of how each area looks are generated alongside this report:
- App icon (adaptive, on a home screen grid)
- OEA launcher home screen (dark console workspace)
- OEA Games hub (cards with procedural cover art)
- Send Off — Battle Royale arena + HUD (with skinned human soldiers)
- Endless Days — rail-yard run + HUD
- Chess — 3D board
- Engine Lab — 4D+ demo scene
