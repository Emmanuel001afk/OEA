# Send Off — perspective, orientation, and the HUD rework

_2026-11-23_

Everything here is on branch `arena/f9221963-oea`. No device test was possible
(there is no JDK or Android SDK in this sandbox), so the claims below are
structural: source checks, arithmetic checks and rendered sheets. Where a claim
needs two phones or a real GPU, it says so.

---

## 1. The rotate question, answered

**The problem.** Send Off shipped a `⟳ Auto / Landscape / Portrait` pill on the
front screen, an orientation row in the pause menu that cycled through the same
three, and a `portrait` branch in the control layout. That advertised portrait as
a way to play. It was not: the HUD was landscape-shaped — minimap anchored
bottom-left under the movement thumb, kill feed at a fixed `safeTop + 142dp`,
slot row bottom-right — so holding the phone upright produced a cramped,
overlapping screen with no explanation of why. That is worse than refusing.

**What replaced it.** The game no longer offers the choice, because there is no
choice to offer.

| Surface | Before | Now |
| --- | --- | --- |
| Front screen header | `⟳ Auto` / `⟳ Landscape` / `⟳ Portrait` pill | removed |
| Front screen Display | — | a stated row: *Screen orientation · Landscape (locked)* — "plays sideways, either way up — rotate the phone to hold it left or right handed" |
| Pause menu (in match) | row that cycles Auto → Landscape → Portrait | a stated row; if the phone is physically upright it reads "turn the phone sideways to play" |
| Activity | `FULL_USER` for arena, honouring a saved `portrait` | `SENSOR_LANDSCAPE` — locked, either way up |
| `frontSetOrientationMode` | accepted `portrait` | refuses `portrait` for a landscape game |
| Game window | swapped to a portrait button set + portrait stick region | one landscape layout, loaded once |
| Held upright | squashed landscape HUD | match pauses (`time.timeScale = 0`) behind one prompt: **TURN THE PHONE SIDEWAYS** |

`SENSOR_LANDSCAPE` rather than `LANDSCAPE` is deliberate: the phone may be held
either way up, which matters for a left-handed player.

Deleting the portrait branch also fixed a latent bug. `applyOrientationControls`
was keyed on `width < height`, so a transient rotation mid-match — a split-screen
resize, a fold, the launch animation — would swap every button anchor and the
joystick region under the player's thumb, then swap back. There is now one
layout, loaded once, and a `tools/check-control-layout.py` that proves it fits.

## 2. First person and third person

Both are selectable in three places that write one setting
(`EngineSettings.cameraPerspective`, default `third`):

* **Pause → Display → Camera → Perspective** (with a live *Shoulder distance*
  slider, 1.4–3.2 m);
* **Front screen → Controls → Perspective**;
* **in match:** a `◉ 3rd / ◉ 1st` pill at the bottom of the top-right readout
  column, tapped through a hotspot rather than a button so it does not punch a
  dead circle in the look-drag surface.

**Both views aim from the same eye.** The camera moves; the shot origin never
does. Switching perspective cannot change where a bullet lands, which is the one
property a view toggle must have in a shooter.

Camera, from `OeaArena.updateCamera`:

| | third person | first person |
| --- | --- | --- |
| distance behind eye | hip 2.6 m (1.4–3.2 setting), 1.1 m aimed, 7 m skydiving | 0 |
| shoulder offset | 0.78 m hip → 0.46 m aimed → 0 when scoped | 0 |
| vertical lift | +0.30 m over the eye | 0 |
| field of view | landscape 70° / portrait 82° | +6° wider |
| near plane | 0.10 m | 0.06 m (so a barrel tip at full ADS does not clip) |

The switch is eased, not cut: `thirdBlend` damps toward the target at 9/s, and
every offset above is a function of that blend. Mid-switch the camera is between
the two views rather than teleporting.

**First person has a body.** A viewmodel (`drawViewModel`) puts the weapon in
the soldier's hands: low-right at hip, brought to centre when aiming, with walk
bob, breathing sway, a three-beat dip-and-roll on reload (`sin(πt)` over the real
reload duration) and the existing swap dip. It scales down as the camera pulls
away, so a switch reads as lowering the gun instead of deleting it.

**Third person keeps the wall fix that mattered.** The occlusion ray now starts
at the *eye* offset along the shoulder direction, not at the feet — a ray from the
body origin would happily put the lens inside the wall the shoulder is hugging.
The fraction of the intended distance that survived is stored in
`thirdDistance`, and the body is skipped (`localBodyHidden`) when the lens is
inside the soldier's skull, alongside the first-person and scoped cases. One
rule, used by both the host and client draw paths, so the two views cannot
disagree.

Skydiving is always third person, seven metres out — the free fall is played for
the landing and cannot be read from inside your own helmet. The pill is hidden
during the fall rather than offering a toggle that would lie.

## 3. The HUD rework

The rendered sheet exposed four things that had been wrong for a while. All four
are layout, not taste, and all four are now checked by
`tools/check-control-layout.py`.

1. **The minimap sat under the movement thumb.** 118 dp, bottom-left, 12 dp off
   the corner; the joystick ring ran straight through it. The map is now 84 dp
   at `safeTop + 124dp`, under the location card — the top of the screen is
   information, the bottom is thumbs. The stick is 46 dp at `0.135, 0.735`, clear
   of the map, the throwable row and the bottom edge on every supported shape.
2. **The vitals block moved to the corner** (`safeLeft + 14dp`), because it now
   has it to itself: portrait, HP, vest, level, location, surface and wind stack
   in one readable column.
3. **The kill feed collided with the mode card.** A fixed `safeTop + 142dp`
   assumed a landscape window with no perspective pill. It now starts below
   whatever the right column actually ended at, and drops entries rather than
   stacking them over the weapon slots when there is no room.
4. **The teammate strip was drawn under the slot row.** It was anchored at
   `safeBottom + 6dp`; on a 640x300 window the slot row starts above that. It now
   stacks above the slots whenever the two would touch.

Plus: slot cards shrink to 84x34 dp below 720 dp of width instead of running into
the fire button, and the factory button layout was re-tuned — anchors and radii
both — until the checker reports no overlaps on six shapes from 640x360 to
1280x600. Fire is 34 dp, aim 22, reload/jump/crouch/prone/sprint between 20 and
23, and the throwables sit in a row along the bottom-left where the left thumb
can reach without leaving the stick.

## 4. Visual

`tools/tripo/concepts/sendoff-look-preview.png` — generated by the new
`tools/tripo/preview_sendoff.py`:

* two hero panels (third-person hip fire, first-person aiming) with the full HUD;
* an annotated HUD strip with ten numbered callouts and a legend spelling out
  each anchor;
* a footer stating the orientation decision.

Anchors, radii, the stick region and every camera distance are **parsed out of
the Kotlin**, not typed into the script, and the script fails if the orientation
facts in the source stop being true. If the layout changes and the picture does
not, the parse broke and the tool says so.

## Checks run

```
tools/check-kotlin.py            OK — 306 sources
tools/check-rules.py             OK — 12 modes, 12 exposed in the lobby
tools/check-host-join.py         OK — join code, LAN discovery, host authority
tools/check-gameplay-rules.py    OK — regen, medkit, shield, burst, loot, 16-byte upgrades
tools/check-control-layout.py    OK — 14 controls on 6 screen shapes  (new)
tools/tripo/test_glb_to_oeac.py  OK
tools/tripo/test_weapons_to_oeam.py OK
tools/tripo/preview_hud_status.py   OK — 3 screen shapes
tools/tripo/preview_settings.py     OK
tools/tripo/preview_sendoff.py      OK — 2464x1516
```

`check-control-layout.py` was verified to fail when it should: moving `fire` onto
the stick home produced `fire hits stick` on all six shapes and exit 1.

## Not verified

* **Nothing ran on a device.** No Gradle, no JDK, no Android SDK here. The
  camera maths, the viewmodel placement and the rotation prompt are reasoned and
  checked in source, not seen on a screen.
* **First-person feel** — whether the viewmodel sits where a real player wants a
  gun, and whether 6° of extra FOV is the right amount — needs a phone and a
  thumb. The numbers are in one place (`drawViewModel`, `fovNow`) and are meant
  to be tuned against a real screen.
* **Host/join** still needs two phones on one router. The structural checks
  cover the transport, not the latency.
* **The rotate prompt** cannot be exercised without an upright window, which the
  landscape lock makes rare. It is drawn on every frame where `width < height`
  and the match holds still behind it.
