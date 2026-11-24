# Send Off — settings display, control layout and audio, 2026-11-23

The configuration surface of Send Off, rebuilt as a **display** rather than a
list. Three things changed shape:

1. a paused player now sees a grouped hub and named pages instead of one flat
   column of eleven switches;
2. the control layout is a real editor — drag, select, resize, reset one or
   reset all, per orientation *and* per handedness;
3. audio is six channels with their own switches and levels under one master,
   not a single on/off.

Design sheet: `tools/tripo/concepts/settings-preview.png`
(`python3 tools/tripo/preview_settings.py`). It reads its numbers out of
`OeaEngine.kt` and `OeaArena.kt`, so the sheet cannot drift from the code.

---

## 1. The pause screen is a hub

Before, `openPauseMenu()` was a flat `MenuItem` list:

```
Resume · Look sensitivity · ADS sensitivity · Turn limit · Look smoothing ·
Invert look · Character studio · Pack · Controls · Match & controls · Quit
```

Every row had the same weight, so "Resume" and "Turn limit" looked like
comparable decisions, and the value a row owned was baked into its label.

Now the menu has **headers** (`MenuKind.HEADER`) and **live-valued rows**
(`MenuKind.CHOICE` / `TOGGLE` / `SLIDER`), rendered by both host activities:

```
MATCH        Resume · Pack · Character studio
CONTROLS     Look & aim · Button layout · Left-handed · Hold to aim
AUDIO        Sound
DISPLAY      Display
GAMEPLAY     Gameplay
THIS MATCH   Match & controls · Quit
```

Each row carries the state of the thing it opens, so the hub is a summary you
can read without opening anything:

| Row | Shows |
| --- | --- |
| Look & aim | `1.2× · ADS 85% · 180°/s cap` |
| Button layout | `Factory layout` / `3 of 14 moved` |
| Sound | `90% · effects, music, announcer, haptics` (or `Muted`) |
| Display | `High · 120 fps · HUD 100%` |
| Gameplay | `assist 35% · manual sprint · numbers on` |

### The pages

| Page | Rows |
| --- | --- |
| **Look & aim** | look sensitivity (10 steps, 0.2–3.0×), ADS sensitivity (9 steps, 30–150%), turn speed limit (off/90/140/180/240/320 °/s), look smoothing (0–86%), invert Y |
| **Sound** | master on/level; effects on/level; music on/level; announcer on/level; duck music for the announcer; haptics on; haptic strength |
| **Display** | quality (auto/low/medium/high/ultra), frame rate (max Hz or a fixed target), adaptive resolution, FPS counter, sustained performance; HUD opacity, button opacity; crosshair shape (cross/dot/circle/T), crosshair colour (6 tints) |
| **Gameplay** | aim assist 0–100%, tap-to-fire, auto sprint, damage numbers, camera shake, reduce motion |
| **Button layout** | handedness, aim tap/hold, one row per control (reset that control), reset all; the live drag editor is one tap away |

Menus are *not* sliders, so continuous values on the in-match pages are stepped
lists (`sliderPage`) with the current step marked, while the front screen uses
real SeekBar sliders. Both write the same `EngineSettings` fields.

---

## 2. Control layout

### Storage and handedness

Layouts are stored per orientation and now per handedness:

```
btn_<key>_x|y|r        landscape, right-handed
btn_p_<key>_x|y|r      portrait, right-handed
btn_l_<key>_x|y|r      landscape, left-handed
btn_pl_<key>_x|y|r     portrait, left-handed
```

`layoutPrefix()` picks the set. A left-handed default is the **mirror** of the
factory layout (`1f - x`), not a mirror of whatever the player dragged, so
flipping the setting gives a sane layout on each side and flipping back restores
the old one. The stick territory and look area swap sides with it, and the look
area is derived from the stick region rather than hard-coded, so the two can
never overlap.

### The editor

Opened from Pause › Button layout › *Open drag editor*, or from the front
screen's layout page. On screen:

* every control is drawn with a dashed handle ring (`VirtualButton.editing`),
  so it is obvious all of them can be picked up;
* **drag** moves a button (saved immediately);
* **tap** selects it — the selected button gets a bright halo and the header
  line names it and its size in dp;
* **− / +** resize the selection between 16 and 72 dp, with a haptic tick;
* **RESET** restores the factory layout, **DONE** saves and plays, **ADS** flips
  tap-or-hold.

The header states what is selected, so the buttons never act invisibly:

```
CONTROLS · drag to move · tap to select · − + to size
Editing: Fire · size 44dp · RESET = factory · ADS = tap or hold
```

### The overview page

Pause › Button layout, and Front › Controls › Button layout, list all fourteen
controls with their factory hint and whether they are customised:

```
Fire              Factory      Right thumb
Crouch / prone    Moved · reset Bottom right
…
Reset all buttons Factory · right-handed
```

Tapping a moved control resets **only that control**, in both orientations and
both handednesses.

---

## 3. Audio

`EngineSettings` gained per-channel state; `Audio` now honours it and exposes a
single place for the announcer:

| Setting | Default | Effect |
| --- | --- | --- |
| `sound` | true | master mute; mix preserved |
| `volume` | 0.90 | master level |
| `sfx` / `sfxVolume` | true / 1.00 | weapons, steps, impacts |
| `music` / `musicVolume` | true / 0.45 | soundtrack |
| `voice` / `voiceVolume` | true / 0.85 | announcer lines |
| `duckMusicForVoice` | true | music drops to 35% for 1.2 s under a callout |
| `haptics` / `hapticStrength` | true / 1.00 | vibration, scaled 0.25–1 |

Implementation notes:

* `play()` multiplies by `sfxVolume × volume`, and `soundEnabled()` gates it;
* `playVoice()` gates on the voice channel, scales by `voiceVolume`, and calls
  `duckFor(1.2f)`; `GameHost.frame()` ticks the duck timer once a frame;
* `musicVolume` on `Audio` is now the *player's* channel level; a game trims its
  own theme with `musicGain` (the Runner sets 0.7 instead of overwriting the mix
  it used to);
* `Haptics.predefined` keeps Android's predefined waveforms at full strength
  and applies `hapticStrength` to the amplitude path, so a softer setting is a
  shorter buzz rather than a mangled system effect.

The front screen and both other OEA fronts show a channel summary
(`90% · effects, music, announcer, haptics`) instead of "On"/"Off", and the
Sound page has a **Reset mix** button that restores the factory levels without
touching the switches.

---

## 4. Display and gameplay settings that are actually applied

| Setting | Read by |
| --- | --- |
| `hudOpacity` | `drawHud` sets `SpriteBatch.alpha` around the top bar, minimap, kill feed, status and teammates, then restores it — the reticle keeps its own contrast |
| `buttonOpacity` | every `VirtualButton.opacity`, applied in `applyOrientationControls` |
| `crosshairStyle` / `crosshairColor` | `drawCrosshair` draws cross, dot, circle or T in the chosen tint, dimming as spread grows |
| `damageNumbers` | floating hit numbers |
| `cameraShake` | explosion/hard-landing view kick |
| `tapFire` | `buildInput`: `fire` becomes one pulse per tap |
| `autoSprint` | `buildInput`: stick past 86% runs |
| `aimAssist` | `ArenaSim.aimAssist` — see below |

`SpriteBatch.alpha` is new: a batch-wide multiplier applied in `quad()` and
premultiplied into each vertex, so a HUD group fades as one piece instead of
element by element.

### Aim assist

Deliberately conservative, and authoritative (it runs in the simulation, so
host and client agree):

* only for humans — `p.isBot` gets none;
* the candidate must be within a ~9° half-angle of the aim line and inside the
  weapon's range;
* the shot is bent toward the body by a blend of the assist value (max 60% at
  point blank, less with distance) — it can never turn a shot that was not
  already close, and it cannot invent a hit through a wall, because the world
  raycast still runs first;
* set to 0 it is a no-op.

---

## Validation

```
python3 tools/check-kotlin.py   → STATIC CHECK OK — 305 sources
python3 tools/check-rules.py    → RULES OK — 12 modes, 12 exposed in the lobby
python3 tools/tripo/preview_settings.py → tools/tripo/concepts/settings-preview.png
```

No Gradle build or device run is possible in this workspace (no JDK, no Android
SDK), so this is static verification plus the design sheet.
