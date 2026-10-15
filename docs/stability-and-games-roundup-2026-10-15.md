# Stability & games roundup — 2026-10-15 (second pass)

Second follow-up to the investigation of `Issues.pdf` and the black-screen report.
Everything below was implemented against the real sources in this checkout. This
sandbox has no JDK/Android SDK and outbound access to Maven is blocked, so
**nothing here is compile- or device-verified**; every change is reviewed twice
against its APIs and the surrounding code, and the static checker
(repo-structure/manifest/class-reference scan, 0 unique errors / 16 known
`android.R` framework warnings) was run after each change batch. Real-device
confirmation is still required before release.

## Root causes verified in code and fixed

### Black screen / zombie Home (the screenshot)
`OeaFaultShield.onUncaught` previously swallowed uncaught faults **even on the
main thread**. An uncaught exception on the UI thread has already unwound
`Looper.loop()` — posting a "rebuild Home" task to that dead looper did nothing,
leaving a frozen window on the wallpaper that Home/Recents/reboots failed to
recover properly. Fix: a main-thread uncaught fault is recorded (with repeat
count → auto safe-mode next start) and then delegated back to Android so the
process dies and Home restarts clean. Containment (GL/binder/worker threads,
guarded `runCatching`-style guards) is unchanged.

### Games not recognized by Game Boost
In-hub games run inside `com.oea.launcher`, whose package is deliberately not a
"game" to Game Boost — so sessions never triggered for OEA's own games. Now:
`oeagame/<id>` keys exist end-to-end, hub launches (`GameLaunch.start`) and
in-hub game screens (`OeaGameActivity.startGame`) report via
`OeaGameBoost.onOeaGameOpened`, the service keeps / ends sessions by window class
(game screen vs hub vs Home), the picker lists OEA games regardless of detection
mode, and OSD labels resolve engine titles. Standalone game APKs already had the
game category and continue to work.

### Runner two-player input conflict (same screen)
Touches were routed purely by where a swipe *chain* currently started; a stroke
that drifted across the middle line hopped lanes, and recycled pointer ids could
carry a lane over. Implemented true touch ownership: at pointer-down each finger
is bound to the half it started in for the whole stroke; swipe dispatch consults
the binding, released pointers are unbound. This is simulation-side, not a
visual workaround.

### Chess orientation control
The launch option existed, but a manual flip in "turn to the player to move"
mode was silently undone by the next move — the exact "feels broken" behavior.
Manual flips now pause auto-turning (with an on-screen explanation), and
auto board turning can be toggled mid-game from the menu without restarting.

### Shot Out (`OeaArena`) modes + sensitivity that actually change gameplay
- Sensitivity was already real (`EngineSettings.lookSensitivity` ×3, with the
  Games-hub slider reaching camera math) — verified, kept.
- New `rules` option (solo): **Last Day Survival** (zone disabled simulation-side,
  150 s day/night cycle with light/fog transitions, growing wave drops every 40 s),
  **Lights Out** (permanent night, tighter fog), **Haunted** (green-fog night,
  extra undead at start and per wave). Wave spawn reuses the network-exempt solo
  path so the u8 player-id snapshot budget is untouched (ids stay in-band in net
  play because these modes are solo-only by option scoping).

### Characters ("plane and bumpy")
Both the runner and arena soldiers used unarticulated crate-piles. New
`CharRig` in `oea-engine`: a proportioned humanoid built from smooth primitives
(capsule limbs, joint spheres, squashed-sphere feet) with procedural animation —
real run cycle with opposing arm/leg swing and hip bob, airborne scissor,
crouch fold, two-hand aim pose, idle breathing sway. Runner player + ghost
runners and every arena soldier (bots, teammates, free-fallers) now ride it.
Gear stays on top (visor, tool tools, plate carrier, helmet, weapons, canopy).

### Android app roles
- Clock: `SET_ALARM` (creates a real scheduled alarm, incl. repeat-day mapping),
  `SET_TIMER` (ISO-8601 duration presets the timer), `SHOW_ALARMS`; consumed at
  entry so theme/rebuild passes never re-apply them.
- Calendar: `ACTION_INSERT` for `vnd.android.cursor.item/event` stores the
  handed-over event into the app's own store (no provider permission needed),
  and shows that day.
- (Phone/dialer role filters + in-call services were already correct on main;
  nothing changed.)

### Frozen apps opened sideways
Interception for hibernated apps via links/recents already existed, but it ran
inline in the assistant with a raw package-name toast and could collide with a
deliberate user force-stop job. It is now a separately tracked short flow with
an honest message ("… is frozen in OEA — unfreeze it in the Freezer") and never
interleaves with the user's own freeze/hibernate runs.

## Additions to earlier segments of this round (already committed in d305849)
Per-app mini-app activities (`OeaClockActivity` … `OeaVoiceActivity`, own
task/icon/Recents identity, container demoted to an internal index), camera-island
settle-guard against mid-rotation geometry, touch-gesture sensitivity setting
(Low/Standard/High) scaling only gesture thresholds (drawer, page flings, drag
starts) — stored in the main prefs so it survives reboot + Ultra Power Saving,
theme/wallpaper application stopping stale Home scenes (the "Tide still shows"
bug), mini-app widget id fixes (`image`→`emage`, `video`→`vip`), and the frozen
toast honesty pass above.

## What was verified vs. what remains untested
- **Verified statically:** API signatures against the surrounding engine code
  (draw/material/settings primitives), id/pref/manifest-name consistency,
  file-structure/braces balance, no duplicated permission blocks, no
  unregistered manifest classes, static checker 0 errors.
- **NOT verified:** actual compile (`gradle` unavailable in this sandbox),
  runtime behavior on device, GLES performance of the new rig draw counts
  (soldiers ~14 primitives each instead of ~10 cubes — slightly heavier,
  still trivially within a phone GPU's budget, but must be watched on the
  weakest devices), multi-touch ownership feel on real hardware.
