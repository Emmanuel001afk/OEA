# OEA stability, permissions and wallpapers

This document covers the three systems added to make OEA trustworthy as a home
screen: fault containment, capability-based permissions, and the wallpaper and
theme engine. It is written so a future change can be checked against the rules
the code assumes.

## 1. A sub-app fault must never be a launcher crash

OEA runs Home, the games hub, Game Boost, Phone, Call Blocker, Freezer, Settings
and the game engine inside one process. That is deliberate: the launcher has to
reach its own apps synchronously (live activities, the call card, the boost
panel, network sessions handed from the hub to a game), and Android gives a Home
app one process to work with.

Sharing a process means a fault anywhere can end Home, so every entry point is
wrapped and a process-wide boundary catches the rest.

```
                       ┌─────────────────────────── OeaFaultShield ───────────────────────────┐
 engine threads ──▶ OeaGuard.sink ──┐                                                       │
 sockets / WebRTC / audio / decode  │        ┌── FaultInput + OeaFaultPolicy.decide ──┐     │
 GL thread (framework-owned)  ──────┼───────▶│ CONTAIN / CLOSE_SCREEN / REBUILD_HOME / │─────┤
 accessibility + notification svc ──┤        │ SAFE_MODE / ESCALATE                    │     │
 activities, services, workers ─────┘        └─────────────────────────────────────────┘     │
                       │                                                                     │
                       ├─ OeaFaultLog (memory + files/diagnostics/faults.tsv, capped)        │
                       ├─ Home rebuild / safe mode / screen close (OeaFaultShield.Recovery)   │
                       └─ Settings › Stability (history, safe mode, rebuild, clear)          │
```

### Where faults are caught

| Layer | File | What it does |
| --- | --- | --- |
| Engine | `oea-engine/…/core/Guard.kt` | `OeaGuard` wraps every engine thread, listener dispatch, queued task and GL callback; counts faults per source and reports them to the host sink. |
| Launcher policy | `oea/…/fault/OeaFaultPolicy.kt` | Pure Kotlin rules: thread role → action. Unit-tested in `oea/src/test/…/OeaFaultPolicyTest.kt`. |
| Launcher boundary | `oea/…/fault/OeaFaultShield.kt` | Installs the process default uncaught handler, classifies by thread role, records, and asks Home to recover. Also samples Home's UI thread to catch *hangs*. |
| Launcher log | `oea/…/fault/OeaFaultLog.kt` | Bounded, tab-separated ring file plus an in-memory list; survives a process restart so a force-close can still be explained. |
| Recovery surface | `oea/…/ui/OeaScreenFailure.kt` | Themed in-place failure screen for a sub-app (game, hub, boost, settings) with Copy details and, when relevant, Stability. It never replaces a Home that is already working. |

### The rules

1. **Home outranks everything.** A fault whose thread or stack belongs to a game,
   a widget provider, a socket loop or a service closes or disables *that*, never
   Home.
2. **A game fault closes the game screen.** `GameHost` funnels every engine
   fault into `onFatalError`; `OeaGameActivity` then shows what failed and offers
   only the fixes that match the diagnosis (lower quality, reset that game's save
   data, leave a CPU core free, or run the standalone APK, which is real process
   isolation the user already has).
3. **Two faults in two minutes marks a game unstable**, so the hub leads with the
   repair card instead of letting the user rediscover the crash.
4. **Home is rebuilt, not replaced.** The workspace re-renders from its saved
   layout (`OeaWorkspace.recoverSurface`) and keeps the screen the user is on.
5. **Safe mode exists and is honest.** After repeated Home faults OEA starts Home
   without widgets, At a Glance and live activities, says so in a card, and offers
   the way out. Safe mode is a normal setting, not a hidden state.
6. **Escalation is a last resort.** Only a `VirtualMachineError` on the UI thread
   or Home failing repeatedly *in safe mode* is handed back to Android.
7. **A hang is a fault too.** A token posted to Home's UI thread that is not
   served within six seconds is recorded with the blocking stack, and three such
   stalls switch safe mode on.
8. **Memory pressure is handled before it becomes an error.** `onTrimMemory`
   shrinks the icon cache, drops previews, and inside a game caps the render
   scale (`PerformanceManager.applyMemoryPressure`) and tells the game so it can
   release its own caches.
9. **Nothing silently disappears.** Every contained fault is visible in
   Settings › Stability, with the action OEA took and a one-tap copy for a bug
   report. OEA never sends it anywhere.

### Verification checklist

1. Fault a game deliberately (the Engine Lab is the place): the game screen shows
   the failure with a retry, the hub marks it, Home is untouched, and
   Settings › Stability lists the fault with its action.
2. Kill an OEA Assistant run with OEA in the background and let Android drop the
   service: Home still resumes, and the permission row shows "switched on, waiting
   for Android" rather than asking again.
3. Unplug an active LAN match mid-game: the lobby closes with a message; Home,
   the island and notifications keep working.
4. Force a widget provider to fail (uninstall the app that owns a placed widget):
   the page keeps its icons and shows the widget as unavailable.
5. Turn safe mode on in Stability: Home drops glance and islands but keeps the
   grid, dock, folders, drawer and search; turn it off and the widgets come back.
6. Read `files/diagnostics/faults.tsv` on a device after a crash: the entry must
   name the subsystem, the thread, the exception and the action OEA took.

## 2. Permissions: capability, not denial

OEA does not require a companion app. Its current built-in authority layer is not a self-elevating ADB daemon: Android's app sandbox does not let an ordinary APK start a shell-privileged process by itself. Android does not allow an ordinary app to grant itself `SYSTEM_ALERT_WINDOW` (Display over other apps) or `PACKAGE_USAGE_STATS` (Usage access). On a stock unrooted device, OEA must use Android's own approval screen for these special accesses; root can change supported AppOps, while Device Owner can perform only the management operations Android explicitly permits. Neither Device Admin nor Device Owner is a universal bypass for every special permission.

What OEA does instead:

| Situation | What OEA does |
| --- | --- |
| Any permission OEA needs | `OeaPrivileges.grant` opens the exact Android page (with the per-app deep link where one exists) and **verifies the result** when the user returns. |
| Switched on but not bound (accessibility, notification access) | Reported as *pending*, never as missing, and OEA nudges Android to rebind (root channel) instead of asking the user to switch it on again. |
| Device is rooted | `OeaShell` uses the already-authorized `su` binary for supported `appops`, `settings`, `pm` and `cmd` operations. It verifies state after writes. It cannot create root on an unrooted device. |
| OEA is device owner | `DevicePolicyManager` can grant eligible runtime permissions and perform supported device-management actions. This does not universally grant overlay, usage access, or unrestricted shell authority. |
| No privilege available | The feature degrades: `OeaFeature` maps each feature to a preferred surface and a fallback (inside Home, or a notification), so the user loses *placement*, not the feature. |
| Battery managers killing services | `OeaPrivileges` detects the exemption state, and Home restarts Game Boost monitoring on resume if the setting is on. |

`OeaPrivileges.snapshot` reads real state (appops, `Settings.Secure`,
`DevicePolicyManager`, `RoleManager`, `PowerManager`, `su`) with a 500 ms cache,
and a `ContentObserver` plus an appops listener refresh the UI the moment the
user changes something in Android's own settings.

### What is deliberately not attempted

* No fake overlay, hidden activity or accessibility trick is used to work around
  a denied permission: those break on OEM builds and would be dishonest about
  what OEA can do.
* No third-party privileged service is bundled or required.
* Any ADB command shown is documentation only; it is not run by OEA. Features with no available authority must use Android's permission UI or a documented fallback. A future embedded ADB-compatible bridge would still require Android Wireless Debugging to be enabled and OEA to be paired/authorized; it cannot be silently bootstrapped by an ordinary APK.

## 3. Wallpapers and the theme that follows them

OEA's bundled wallpapers are **drawn in code** (`OeaWallpaperRenderer`), not
shipped as images:

* each scene renders at the phone's own resolution, so there is no upscaling;
* scenes carry their own dominant colour and brightness, which the theme uses for
  the accent and for Home's text colour — something a JPEG cannot provide;
* they are applied through Android's wallpaper service (`SET_WALLPAPER` is granted
  at install, so no prompt), to Home, the lock screen or both;
* `OeaWallpaperStore.syncWithSystem` notices when another app or the stock picker
  replaces the wallpaper and stops the theme from following a scene that is no
  longer on screen;
* a **Dim** control darkens the scene for icon contrast, and the gallery shows a
  real preview of every scene.

Theme rules:

| Rule | Where |
| --- | --- |
| Panels follow the theme mode (system/dark/light); text over the wallpaper follows the *wallpaper's* brightness. | `OeaTheme.resolve`, `OeaPalette.homeText*` |
| The accent is Material You palette, a fixed OEA accent (blue, teal, violet, rose, amber, green, indigo, coral, lime, slate), or the bundled scene's colour. | `OeaTheme.resolve`, `OeaSystemToolsActivity.accentRow` |
| Home's text colour flips with the scene's brightness, so a light scene gets dark icon labels. | `OeaWallpaper.light` → `OeaPalette.wallpaperLight` |

### Verification checklist

1. Apply each bundled scene to Home, then to the lock screen, then to both: the
   wallpaper changes where asked for and Settings says which target is in use.
2. Set the accent to *Wallpaper colours*, apply `Mountain dawn`: the accent and
   the icon labels follow the scene. Change the wallpaper from Android's own
   picker: the row in OEA falls back to Android's colours.
3. Raise Dim to 30%: the gallery re-applies the current scene dimmed.
4. Use a photo as wallpaper: the theme stops following the scene (the row says so)
   and falls back to Android's palette.
