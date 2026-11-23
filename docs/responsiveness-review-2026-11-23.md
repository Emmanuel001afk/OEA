# Speed and reaction speed — 23 November 2026

Scope: the work OEA does between a fingertip and a frame. Home's bind and rebuild
path, the app drawer (open, type, scroll), the icon engine, and the liquid-glass
drift — plus the faults found while reading them.

Method: read the hot paths, then check them mechanically. A scanner walked every
method that runs inside a frame or a bind (`onDraw`, `onMeasure`, `onLayout`,
`getView`, `onTouchEvent`, `onInterceptTouchEvent`) — 86 of them — looking for a
bitmap decode, a `SharedPreferences` read, a JSON parse, a `PackageManager`
query, a file stream or a `Typeface.create`. **It found none.** Drawing and touch
handling in this tree are clean, which is worth saying before the list below: the
cost that a user feels is not in `onDraw`, it is in what runs once per *bind*.

Verification: this branch is compiled and its tests run by the repository's own
workflow — see §4. Nothing here replaces a device measurement; §5 says what to
watch on a phone.

---

## 1. What was wrong, and what changed

### 1.1 Binding one icon could decode a PNG, call PackageManager and render a bitmap — inside Home's frame

`OeaWorkspace.bindTile` assigned `tile.icon = icons.icon(...)`, and
`OeaIconController.iconBitmap` does real work on a memory miss:

```
rendered LruCache hit            → free
miss → readDisk()                → BitmapFactory.decodeFile  (disk read + PNG decode)
miss → rawIcon()                 → PackageManager.getActivityIcon (binder)
miss → render()                  → new Bitmap + Canvas + shape path
```

Every one of those ran on the caller's thread, and for tiles the caller is the UI
thread. It bites in the situations users describe as "the launcher is slow":

* after Android kills OEA and Home comes back — the memory cache is empty while
  the persistent icon cache is warm, so the first frame of Home decodes a PNG per
  tile;
* after `onTrimMemory` — the memory cache is trimmed, so a rebind re-renders;
* the first frame after a theme, icon-pack or icon-shape change (`invalidate()`
  empties the memory cache);
* any drawer row that scrolls to an app whose icon is not warm.

**Changed.** `OeaIconController` gained two calls. `peek()` reads only the
memory cache — it cannot decode, cannot call PackageManager and cannot draw, so it
cannot cost a frame. `request()` resolves on its own background thread and hands
the drawable back on the main thread, dropping duplicate requests for the icon
already in flight. `bindTile`, `bindFolderTile` (the four preview icons),
`bindShortcutTile` and `OeaKit.appIconAsync` (the app-picker lists in Settings and
Game Boost) now use them. A warm Home is bit-for-bit the old behaviour because
every `peek()` hits; a cold one shows the label immediately and the icon arrives
without touching the main thread. Each callback re-checks the tile's key or the
row's tag, so a recycled view can never display the previous app's icon.

### 1.2 Typing in the app drawer re-normalized every label on every keystroke

`OeaDrawerView.rank()` ran, per app, per key pressed:

```kotlin
val label = normalize(app.label)        // ICU NFD + \p{Mn}+ regex + lowercase
val words = label.split(' ', '-', '_', '.')
val initials = words.joinToString("") { it.take(1) }
```

None of that depends on the query; only the five comparisons after it do. So the
most expensive part of search was repeated for nothing, exactly while the user
types.

**Changed.** `search/OeaSearchIndex` computes the normalized label, its words,
its initials, the lowered package name and the alphabet letter once per app, and
a keystroke now normalizes only the query. Measured on a JVM with 120 labels:

| | per keystroke, 120 apps |
| --- | --- |
| before (re-normalize every label) | 0.53 – 0.65 ms, 1.74 ms on the first pass |
| after (`OeaSearchIndex`) | 0.06 – 0.11 ms |

That is ~8× less CPU per key press. It is sub-millisecond on a desktop JVM; on a
low-end phone, where ART is slower and the frame is shorter, the same ratio is
what decides whether a fast typist drops frames. The ranking is deliberately
unchanged — prefix > word start > initials > substring > package — because the
order a user has learned is part of the feature, and `OeaSearchIndexTest` pins it,
including the diacritic folding (`Café` → `cafe`) and the two-character rule for
initials.

### 1.3 Every Home rebuild parsed the workspace JSON once per page

`rebuild()` loops over the pages and called `ws.items()` and `ws.folders()` from
inside `buildPage`, and each of those decodes the stored `JSONArray`. Ten pages
meant ten full parses of the same unchanged data, on the UI thread, on every
theme change, icon-shape change and catalogue refresh.

**Changed.** `rebuild()` parses the workspace once and passes the item, folder and
widget lists down to `buildPage`. Adding a page reads them once too.

### 1.4 The liquid-glass drift allocated on every frame and redrew 60 times a second

`OeaGlassDrawable`'s caustic built an `intArrayOf`, a `floatArrayOf` and a
`LinearGradient` inside a `Choreographer` frame callback, and invalidated the
view every frame — for a highlight that takes nine seconds to cross the pane.

**Changed.** The colour stops are computed once per tint (they only change when
the palette does), the positions are a constant, and the drift is redrawn at
30 fps. A nine-second sweep at 30 fps is indistinguishable from one at 60, and
every skipped frame is a gradient and a pane of glass the GPU does not have to
draw. It still stops the moment the drawable is not visible.

### 1.5 The same blocking icon path in three more lists

`OeaKit.appIcon` — used by the app chooser in Settings and in Game Boost — went
straight to `OeaIconController.icon`, so scrolling those lists paid the same
decode-per-row as Home. Fixed with `appIconAsync` in the same pass (§1.1).

---

## 2. What was already right

So that this reads as a review and not a complaint:

* **The tap path does no I/O.** `launchApp` → `LauncherApps.startMainActivity`,
  `recordLaunch` (`apply()`, asynchronous) and `OeaGameBoost.onAppLaunched`
  (returns immediately for anything that is not a selected game). Nothing waits
  on disk between the finger and the intent.
* **The catalogue is never enumerated on the main thread.**
  `OeaLauncherEngine` owns a single worker; `model.query()` runs there and only
  the bind is posted back.
* **Search actions are debounced and generation-guarded** (90 ms, and a stale
  result is dropped), and the screen-time query runs on a worker with a 60-second
  throttle and the same generation guard.
* **Nothing spins.** Animators stop on detach and on visibility change
  (`EqualizerView`, `OeaControls`, the recording pill), and the glass drift stops
  on `setVisible(false)`.
* **86 frame-time methods with no I/O, no binder call and no allocation of
  typefaces** — checked mechanically, not by eye.

---

## 3. What this pass did not change

* **Widget hosts are rebuilt with the pages.** Every `rebuild()` re-inflates each
  `AppWidgetHostView` through its provider. That is a real cost on a Home with
  several widgets, but reusing a host across rebuilds risks stale widget state and
  provider crashes, so it needs its own pass and its own tests.
* **`shortcuts.find()` / `shortcuts.icon()`** are `LauncherApps` binder calls. They
  run once per pinned shortcut per process (cached afterwards) and the result is a
  real icon for a real shortcut, so they stay as they are.
* **Locale-dependent folding.** `normalize` lowercases with the default locale,
  exactly as before; changing it would change search results for Turkish users,
  which is not a performance change.
* **Launch-count tie-breaking** is still read at sort time — it changes as the user
  uses the phone, so caching it would be wrong.

Two things that look like faults and are not: `OeaTapRouter` schedules a 270 ms
no-op runnable for every tap (`onSingleTap` has no action — single taps on Home
have never done anything), and Home consumes Back when nothing is open, which is
what a launcher's Home button does.

---

## 4. Verification

* **CI, on this branch**: `Compile Kotlin (launcher, engine, arcade)`,
  `Camera geometry regression tests`, the new `Search ranking regression tests`,
  `Verify the responsiveness work` (13 guards: `peek`/`request` exist and the tile
  binder uses them, no `icons.icon(` left in the workspace, `appIconAsync` in the
  kit, `searchIndex.score` in the drawer and no `Normalizer` left there, one
  workspace parse per rebuild, cached caustic stops), the static check, both APK
  builds — see the pull request for the run.
* **Locally**: `OeaSearchIndexTest` 9/9 on a JVM (stub JUnit, run before CI saw it);
  the responsiveness guard step executed from the workflow file, 13/13; the
  benchmark quoted in §1.2; `tools/check-kotlin.py` green over 297 sources; every
  changed Kotlin file parsed by the 2.x front end with no syntax errors (this
  workspace has no Android SDK, so type resolution needs CI).

---

## 5. What to check on a device

1. **Cold start after a kill.** Force-stop OEA, press Home: labels and the dock
   appear immediately, icons fill in without the screen freezing. Compare against
   the previous build — this is the change in §1.1.
2. **Fast typing.** Open All Apps and type a 6-letter query quickly on the slowest
   phone you have. No dropped keys, no lag between the key and the list.
3. **Right after a theme or icon-shape change.** The first Home frame should still
   be interactive; icons may pop in.
4. **Idle cost with Liquid glass.** On Home with the drawer closed, `adb shell
   top -H -p $(pidof com.oea.launcher)` should show the render thread at rest when
   nothing animates.
5. **Frame timing, on any of the above**: `adb shell dumpsys gfxinfo
   com.oea.launcher framestats` after the interaction — watch for frames over
   16 ms during Home's first bind and during typing, which is what this pass is
   about.
