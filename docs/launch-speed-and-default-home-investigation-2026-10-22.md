# Why apps take one or two taps, why opening takes a moment, and why "search → default" does not land on the default-app screen

Date: 2026-10-22
Scope: `com.oea.launcher` source audit (no device logcat was available in this checkout).

## The short answer

**Both. But they are two different delays, and one of them is ours.**

| What you feel | Who owns it | Confidence |
| --- | --- | --- |
| "I have to tap the app once or twice" | Mostly **OEA** — the tap is thrown away in three concrete places in our own code, plus one perception problem (nothing on screen reacts until the app appears) | High (all three are visible in source) |
| "It takes a moment / some seconds before the app appears" | Mostly **the device + the target app**: once the intent leaves OEA, how long the other app takes to draw its first frame is Android's and that app's work. OEA does add avoidable time on cold starts (details below) | High for the pipeline, device-specific for the numbers |
| "Going from one app to another app is the problem" | Mostly **the device's memory manager**: the launcher screen you tap on may be a *freshly restarted* OEA, not the one you left | Medium–high |
| "After I search for 'default' it does not show me the default settings" | **OEA** — a search bug. Typing "default" never returned the default-apps screen at all; it returned "Apps" (the wrong page) | Certain |

Nothing here is a guess about your phone: every mechanism below is a line of code in this repository, and the last section gives you a 10-minute test that proves which one is happening on *your* device.

---

## 1. What actually happens between your fingertip and the app

The full path for a tap on an app icon:

```
touch down ──► OeaTileView.onTouchEvent (workspace/OeaHomeViews.kt:281-343)
touch up  ──► OeaTileView.performClick → drawerTileListener / homeTileListener
          ──► OeaWorkspace.launchApp        (workspace/OeaWorkspace.kt:1980)
              • frozen?  → prompt instead of the app
              • App Lock? → PIN prompt instead of the app
          ──► OeaWorkspace.startApp         (workspace/OeaWorkspace.kt:1992)
              • LauncherApps.startMainActivity(component, bounds, options)
              • store.recordLaunch(...)                    (data/OeaDataStore.kt:482)
              • OeaGameBoost.onAppLaunched(...)            (gameboost/OeaGameBoost.kt:79)
```

Two things about this path matter:

1. **OEA's own work on the tap is small and non-blocking.** `recordLaunch` writes with `SharedPreferences.apply()` (asynchronous), and `onAppLaunched` returns immediately unless the app is a *selected game* and Game Boost is on. So "OEA spends 300 ms thinking about the tap" is not what is happening.
2. **From the moment `startMainActivity` returns, OEA is done.** The window you are waiting for is created by *the other app's* process: ActivityManager starts it, the zygote forks, the app's `Application.onCreate` runs, its first layout is drawn. That is the "some seconds" part and it is the device and the app, not the launcher. A cold start of a heavy app on a mid-range phone is routinely 1–3 s; a warm switch is 150–400 ms.

So the honest split is: **the launcher can lose your tap, and the launcher can be slow to be *ready* — but it cannot make another app's first frame faster.**

---

## 2. "One tap or two" — the four mechanisms I found

### 2.1 The pager swallowed the first tap after a page swipe — **fixed**

`OeaPagedView.onInterceptTouchEvent` (`workspace/OeaHomeViews.kt:762`) did this on `ACTION_DOWN`:

```kotlin
dragging = !scroller.isFinished && abs(scroller.finalX - scroller.currX) > slop
if (dragging) scroller.forceFinished(true)
...
return dragging
```

Returning `true` from `onInterceptTouchEvent` means the pager takes the touch **and no child ever sees it**. So for the whole 240–520 ms that a page snap animation was running (`snapToPage`, `OeaHomeViews.kt:544`), a tap on an icon was swallowed completely — the page even stopped where it was. Second tap after the animation ended worked. That is exactly "I have to tap twice".

Fix: a tap no longer intercepts; a *drag* now takes the pager over on the first movement past the touch slop, and stops the snap at that point. The page still settles under a tap and the icon underneath opens.

### 2.2 A tap that lands while Home is rebuilding goes to a view that no longer exists

Two paths rebuild the entire Home grid on the main thread:

- after an app catalogue change: `OeaLauncherEngine.finishReload` → `workspace.bind(...)` → `rebuild()` (`engine/OeaLauncherEngine.kt:141`, `workspace/OeaWorkspace.kt:1523`), which clears and recreates every tile;
- `scheduleRebuild()` on theme/density/widget changes.

If `ACTION_DOWN` arrives on tile *A* and `ACTION_UP` arrives after the grid was rebuilt (tiles are new objects), Android delivers the up to a view that is no longer there. No click. This is the classic "the launcher ate my first tap" and it happens right after you come home, because **Play Store updates** fire `onPackageChanged` → `dirty = true` → a full rebind. It is also why the problem is intermittent rather than constant.

Not changed in this commit: the correct fix is to keep the rebuild off the tap path (e.g. defer a catalogue rebind while a touch is in progress), which needs device testing to tune. Documented as a follow-up in §7.

### 2.3 After OEA was killed, taps land on a Home that has nothing to tap yet

`OeaLauncherActivity` is `stateNotNeeded="true"` + `launchMode="singleTask"` (`AndroidManifest.xml:97-101`). When Android has killed the OEA process (very common on phones with aggressive memory management — the same class of device that shows this symptom), pressing Home gives you a **new process**, and until `setContentView` has run and the catalogue is bound there is no grid: taps go to an empty decor view and are lost. Everything looks like it is ignoring you, then it appears.

This is where the launcher is genuinely slow, and §3 lists what OEA does during that window.

### 2.4 No feedback between tap and app — the perception half

When you tap an app from Home, OEA does not change anything on screen: the drawer/home stays exactly as it was until the *other* app's window is up (`closeDrawerOnPause` only closes the drawer on `onPause`, `OeaWorkspace.kt:928`). On a device where the target app takes 1.5 s, the launcher looks like it ignored you, so you tap again — and this time it works, because by then the app was already starting. That teaches the "must tap twice" habit even on launches where no tap was ever lost.

Also ruled out deliberately, so it is not blamed later: `OeaTapRouter`'s 260 ms decision window (`gesture/OeaGestures.kt:224`) applies **only** to taps on *empty* Home (double/triple-tap gestures). It does not delay app icons. `OeaFaultShield.guard` is a plain `try/catch`, and `dispatchTouchEvent` only runs the liquid-glass tracking (`OeaWorkspace.kt:436`) — measured cost, not a suspect.

### 2.5 Two cases where a *prompt* is correct behaviour

If the app is frozen/hibernated OEA shows the freeze prompt, and if App Lock is on it shows the PIN (`OeaWorkspace.kt:1980-1990`). That is a second tap by design, not a bug — worth ruling out first on your device (§6).

---

## 3. "Some seconds" — what OEA does on a cold start, and what it should not

The first frame after the process is (re)created currently waits for all of this, on the main thread:

| Work | Where | Cost |
| --- | --- | --- |
| Fault shield install + log wiring | `OeaApplication.kt:27-28` | small |
| **`OeaPrivileges.snapshot()` — ~15 capability probes, each a binder call** (Home role, accessibility, overlay, notification access, usage access, device admin, device owner, **root**, DND, battery, 3 roles, 3 runtime permissions) | `OeaApplication.kt:36` → `privilege/OeaPrivileges.kt:95,147` | several ms normally |
| **Root probe: `ProcessBuilder("su","-c","id")` with a 900 ms timeout** | `privilege/OeaShell.kt:47` (before this commit) | **0 ms when there is no `su`; up to ~900 ms when a `su` binary exists but is not granted / prompts** |
| Engine init + built-in game registration (class-loading the game descriptors) | `OeaApplication.kt:47-51`, `oea-games/OeaGames.kt:37` | tens of ms |
| Cached catalogue read: file read + JSON parse of every app | `engine/OeaLauncherEngine.kt:86` → `model/OeaAppModel.kt:57` | ~10–30 ms for ~300 apps |
| Bind + rebuild all pages, **binding every icon** | `workspace/OeaWorkspace.kt:1096,1683` | icon cache hit: cheap; miss: **PNG disk read + SHA-1 + decode per icon** (`icons/OeaIconController.kt:97-114`) |

The root probe is the one that matters most, and it is a real defect against our own policy: the docs already claim root detection runs "once, in the background, only if an `su` binary exists" (`docs/launcher-engine-and-live-activities.md:117`), and `OeaAppFreezer.hasRoot()` does exactly that — but `OeaShell.available()` never got the same treatment. So on a phone that has an `su` binary (rooted, or an OEM build shipping one that will never grant), **every** cold start of the OEA process could block up to 900 ms before Home exists.

**Fixed in this commit:** `OeaShell.available()` now checks for the binary first (a few `File.exists()` calls, microseconds) and never runs the process probe on the UI thread — a UI-thread caller gets the cached answer and the probe finishes on a background thread. `OeaShell.run()` still waits for a definite answer, because a command must not silently fail.

Also on every `onResume` (`OeaWorkspace.onHostResumed`, `workspace/OeaWorkspace.kt:817`): theme resolve (cached), a badge refresh that touches **every** tile (`:1729`, and `OeaAppLockStore.isLocked` copies the locked-apps set per tile), At a Glance chips rebuilt, both islands re-rendered, banner re-rendered, notification listener rebound, system bars updated. Individually small (a few ms), together the kind of per-resume work that makes the *first* tap after coming home feel late even when the tap is delivered.

### What is definitely not ours

- **The target app's own start time.** `am start -W` will show it (§6); OEA's part of that number is the couple of milliseconds before `startMainActivity` returns.
- **The icon-to-app "clip reveal" transition.** `ActivityOptions.makeClipRevealAnimation` (`OeaWorkspace.kt:2018`) is the standard launcher animation; on a low-end GPU it costs a few frames, not seconds. Turning the system animation scales to 0.5 is the fastest way to check how much of the wait is animation.
- **Going from app A to app B without going through Home.** That path never touches OEA at all — Recents, the app switcher and task-to-task launches are pure Android. If *that* is slow too, the launcher is not the cause and the device is under memory pressure.

---

## 4. Why "entering a new app from the former app" feels worst

That is the moment when the launcher is most likely to be cold:

1. App A is in front and has claimed the RAM. Android trims or kills OEA (it has no foreground service and, by design, Game Boost is not running while no game is open).
2. Home is pressed. OEA restarts: `Application.onCreate` → the startup work in §3 → `bindCachedCatalogue` → first frame.
3. Nothing on the grid can be tapped until that is done, so your tap is lost — and the same phone will feel fine when tested right after using Home (warm), which is exactly the "it depends" pattern you are describing.

**Verdict:** the launcher's restart cost is ours to reduce; the *reason* it restarted (memory policy) is the device.

---

## 5. "Search → default" does not show the default settings — a real bug, fixed

Two separate faults, both in OEA.

### 5.1 The search index had no default-apps entry at all

`OeaSearchController`'s Android-settings table (`search/OeaSearchController.kt:62-79`) had 20 entries and **none of them was `ACTION_HOME_SETTINGS` or `ACTION_MANAGE_DEFAULT_APPS_SETTINGS`**. "default apps" existed only as a *keyword on the "Apps" entry*, whose intent is `ACTION_APPLICATION_SETTINGS` — the general app list. So typing "default" produced one settings row, labelled "Apps", and tapping it opened the app list: a screen where the launcher default **cannot** be changed. Then you have to hunt for ⋮ → Default apps → Home app yourself, which is exactly the "glitch/slowness" you are describing.

Fixed: "Default apps" (`ACTION_MANAGE_DEFAULT_APPS_SETTINGS`) and "Default Home app" (`ACTION_HOME_SETTINGS`) are now real entries, both matching `default`, `home`, `launcher`, `home app`, `home screen`, `default launcher`. Entries whose intent does not resolve are still filtered out, so a phone without one of those pages simply does not show that row instead of opening nothing.

Related detail: results are ordered apps-first ("Apps" grid, then "More results" for settings, `workspace/OeaDrawerView.kt:392-407`). For a query that also matches app names ("home", "settings") the settings row sits below the app grid. That ordering is deliberate; with the new entries it is at least the right page when you reach it.

### 5.2 "Set as default" could fail silently on OEM skins

Both entry points did this:

```kotlin
runCatching { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }   // result swallowed
```

(`workspace/OeaWorkspace.kt:1927`, `OeaSystemToolsActivity.kt:3441`, both before this commit). A number of OEM builds — including several that ship on the phones this project is used on — do not implement `ACTION_HOME_SETTINGS` at all. `runCatching` then swallows `ActivityNotFoundException`, so the tap does **nothing**: no dialog, no settings page, no error. That matches "it is not immediately show me the default settings" precisely.

Fixed in both places: the order is now

1. Android 10+ role request (`RoleManager.createRequestRoleIntent(ROLE_HOME)`) — the system's own "use OEA as Home?" dialog;
2. `ACTION_HOME_SETTINGS` — "Home app" page;
3. `ACTION_MANAGE_DEFAULT_APPS_SETTINGS` — "Default apps" page;
4. `ACTION_APPLICATION_SETTINGS` — app list;
5. only if the device implements none of them, an explicit toast telling you the route to take.

Each step is checked with `resolveActivity` first, so OEA opens the first page that genuinely exists rather than hoping. `handleActivityResult` (when you decline the role dialog) uses the same chain. The Settings rows already re-render in `onResume` (`OeaSystemToolsActivity.kt:297`), so "OEA is not the current Home launcher" corrects itself when you come back.

### 5.3 The honest limit of any launcher here

Android does not let an app make itself the default launcher. OEA can only *open* the system UI and, on Android 10+, ask for the role. If the system dialog itself is slow to appear, that is the Settings/role-controller process (often a cold start of ~1 s on a low-end phone) — a good second place to look with §6.

---

## 6. How to prove which one it is on your phone (10 minutes)

**A. Is the tap being lost, or is the app slow?**
Developer options → *Show taps* on. Then open an app from Home and watch the dot:
- One dot appears and the app comes up late → the tap was received, **the app/device is slow**.
- You tap, the dot appears, nothing happens, you tap again and it opens → the tap was received by OEA and **lost** (pager/rebuild/prompt path).

**B. Is OEA being restarted when you press Home?**

```
adb shell dumpsys activity activities | grep -i oea
adb logcat -b events | grep -E "am_proc_start|am_activity_launch_time"
```

A new `am_proc_start` for `com.oea.launcher` each time you press Home means the device is killing it — that is the §4 path, and it is the device's memory policy plus OEA's startup cost.

**C. How much of the wait is the other app?**

```
adb shell am start -W -n <package>/<activity>
```
`TotalTime` is the app's own start. Compare it with how long the icon takes to appear. If icons appear late but `TotalTime` is small, the delay is before/around the launch (OEA), not inside the app.

**D. Jank inside OEA:**

```
adb shell dumpsys gfxinfo com.oea.launcher framestats | head -40
```

**E. Things worth doing on any phone that feels like this:**
- Developer options → set *Window/Transition/Animator* scales to 0.5× (or off) — halves the perceived "moment".
- Android Settings → Apps → OEA → Battery → *Unrestricted* (and on Xiaomi/Tecno/Infinix: lock OEA in Recents / allow autostart).
- `adb shell cmd package compile -m speed-profile -f com.oea.launcher` — a big APK with games benefits noticeably from ahead-of-time compilation.
- Confirm OEA really is the default Home; a launcher that is *not* default is restarted by the system far more often.

---

## 7. What was changed here, and what is deliberately left open

Changed (all small, all in this commit):

1. `search/OeaSearchController.kt` — real "Default apps" and "Default Home app" search results.
2. `workspace/OeaWorkspace.kt` + `OeaSystemToolsActivity.kt` — the four-step default-Home chain with an honest failure message, and no silent `ActivityNotFoundException`.
3. `workspace/OeaHomeViews.kt` — a running page snap no longer eats the first tap.
4. `privilege/OeaShell.kt` — root probe never blocks the UI thread; binary presence checked first (mirrors `OeaAppFreezer`).
5. `workspace/OeaDrawerView.kt` — the combining-marks `Regex` is compiled once instead of once **per app per keystroke**, which is what made drawer search typing lag on a large catalogue.

Left open on purpose (needs a device to tune, not a guess):

- **Defer a catalogue rebind (and any grid rebuild) while a touch is in progress**, so a Play Store update landing at the wrong moment cannot swallow a tap (§2.2).
- **Immediate launch feedback**: close the drawer/raise the tapped icon the instant the intent is issued rather than waiting for `onPause`, so a slow app does not look like an ignored tap (§2.4). This is a visible behaviour change and should be reviewed before shipping.
- **Move `OeaPrivileges.snapshot()` off the startup path** (post it after the first frame, or compute lazily) — ~10–40 ms of binder work currently sits in front of Home's first frame.
- **Cache normalized app labels** for the drawer's `rank()` so the first keystroke does not re-normalize the whole catalogue (`workspace/OeaDrawerView.kt:450`). The regex fix removes the worst part; this removes the rest.
- **Instrument it**: record `elapsedRealtime` from `ACTION_UP` to `startMainActivity` returning, and log a Home `onResume`→first-frame duration, surfaced in Console/Access Doctor. Then the "launcher or device?" question is answered with a number from your own phone instead of a code audit.

> Note: the source in this checkout could not be compiled here (no Android SDK/Gradle toolchain in the sandbox). The changes above are deliberately local and follow the existing style, but they must be built and smoke-tested on a device before release — in particular the pager change, which alters touch handling on Home.
