# Mini-app response and fault review — 23 November 2026

Scope: what OEA does between a fingertip and a frame **inside its own apps** — the
mini-app screens and the shared kit they are built from. The Home/app-drawer pass in
[speed and reaction speed](responsiveness-review-2026-11-23.md) and the two VIP passes
([review](vip-review-2026-11-23.md), [playback session](vip-playback-session-2026-11-23.md))
already covered the launcher surface, the camera and the player; nothing here repeats
them.

Every finding is a code fact from this tree, cited by file and line. Everything in §1
was repaired in this change set; §2 is what was checked and already right; §3 is what
was left alone and why; §5 is what only a phone can settle.

---

## 1. What was wrong, and what changed

### 1.1 Every search field rebuilt its whole screen once per character

`OeaAppKit.searchField` (`ui/OeaAppKit.kt:628-697`) called `onQuery` from
`afterTextChanged` — no debounce, no coalescing — and every screen that uses it answers
by rebuilding itself:

* **Calendar** (the worst one): `onQuery` → `refresh()`, whose body runs a
  `CalendarContract.Instances` provider query and re-creates the entire month/week/day
  grid of views (`miniapps/OeaCalendarApp.kt:444-449`, `refresh = {` at `:644`). Typing
  "meeting" was six provider queries and six full rebuilds.
* **Notes** re-filters every note and rebuilds the list per keystroke
  (`miniapps/OeaNotesApp.kt:194-198`).
* **Pictures and video** re-scan and rebuild their grids
  (`miniapps/OeaImageApp.kt:87`, `miniapps/OeaVideoApp.kt:69`), and **Phone**
  re-runs its contacts filter (`phone/OeaPhoneActivity.kt:424`).

Six screens use the field — Calendar, Notes, Messaging, Pictures, Video and Phone — so
this was one line of kit work being paid by every one of them.

**Changed.** The debounce lives in the field itself (`OeaAppKit.kt:650-686`): 140 ms,
which is longer than a fast typist's gap between keys and shorter than a pause that
feels like lag. The clear button and the IME action still answer immediately
(`:668-675`), because those are deliberate. Every screen inherits the fix without
touching its own code.

### 1.2 Calendar read the phone's Calendar Provider on the UI thread, on every rebuild

`refresh()` called `loadDeviceEvents(host, currentCal)` inline
(`OeaCalendarApp.kt:81-126`). That is a
`CalendarContract.Instances` query: the provider expands every recurring event of every
account for the month. On a phone with a work calendar and a few hundred events it is
hundreds of milliseconds, and it ran on the main thread — on open, on every month step,
on every day tap, on every filter chip and (before §1.1) on every character typed.

**Changed.** Device events are read **once per month, off the UI thread**, into a cache
the paint reads (`OeaCalendarApp.kt:356-394`):

* `deviceMonths: HashMap<String, List<Event>>` keyed by year-month, plus a `DEVICE`
  single-thread executor at object level (`:68-71`);
* `requestDeviceMonth()` refuses to start a second load for a month already loading or
  loaded, and drops the answer if the screen has gone or the user has moved on;
* the paint uses `deviceMonths[deviceKey(currentCal)].orEmpty()`, so the first frame of
  a month now shows OEA's own events immediately and the phone's events appear a moment
  later — instead of the screen freezing until the provider answers;
* a permission granted after the first paint clears the cache and repaints
  (`:376-382`), because a month cached while access was denied is an empty list;
* coming back to the app clears it too (`:383-390`), so an event added in another
  calendar app while the user was away is not hidden behind a stale month.

### 1.3 The countdown could run two tickers and fire two notifications

The timer's `Runnable` was created **inside the Start/Resume button's click handler**
(`OeaClockApp.kt`, previously `:441-478`), and each instance re-posted itself. A second
tap on the button before it relabelled itself started a second chain — two tickers
painting the same label and, at zero, two "Timer finished" notifications from one timer.

**Changed.** One ticker, owned by the screen like the stopwatch's already was
(`OeaClockApp.kt:245-276`): `timerTicker` reads `timerRunning`/`timerEndAt`/`timerText`,
so starting, pausing and resuming only post or cancel one object. The completion path
(notification, toast, refresh) lives in that single ticker.

### 1.4 The stopwatch repainted an invisible screen twenty times a second

`swTicker` posts itself every 50 ms (`OeaClockApp.kt:224-231`) to animate
hundredths, and it stopped only when the stopwatch stopped or the host was destroyed.
Leaving Clock with a stopwatch running — Home, another app, screen on — kept waking the
main looper twenty times a second to write into a `TextView` nobody could see. The
countdown had the same shape with better manners: five wakes a second to keep a promise
that needs one.

**Changed.** The screen now uses the host's existing lifecycle hooks
(`OeaMiniAppActivity.pauseHook`/`resumeHook`, `miniapps/OeaMiniAppHooks.kt:35-44`):

* `onHostPaused()` cancels the stopwatch ticker and, for a running countdown, cancels
  the 5 Hz ticker and posts **one** wakeup at the moment the timer ends
  (`OeaClockApp.kt:278-283`);
* `onHostResumed()` repaints the stopwatch from `swStartAt` (the elapsed time is
  computed, never accumulated, so nothing drifts) and restores the fast ticker
  (`:285-295`).

### 1.5 The countdown rewrote the same string five times a second

`timerText?.text = formatTimerMs(rem)` ran on every 200 ms tick, including the four
ticks a second where the whole-second label had not changed — five `String.format`
calls per second to paint one second.

**Changed.** The label is written only when it differs (`OeaClockApp.kt:253`).

### 1.6 The two clock faces lived in the middle of a 1,200-line view

`formatMs` (stopwatch, truncating, `MM:SS.hh`) and `formatTimerMs` (countdown, rounding
**up**, `MM:SS`) were private functions inside `OeaClockApp`, where the only way to
check the rounding rule was to start a timer by hand.

**Changed.** They are `OeaClockFormat.stopwatch()` and `OeaClockFormat.countdown()`
(`miniapps/OeaClockFormat.kt`), pure functions of a `Long`, pinned by
`OeaClockFormatTest`: a five-minute timer does not read 00:00 for its last second, a
stopwatch reads 00:00.99 at 999 ms rather than 00:01.00, hour fields appear at the right
point, and a deadline in the past is 0, never negative.

### 1.7 A burst of messages queued one conversation reload per message

`ChatScreen.scheduleRefresh()` posted a fresh `{ reload() }` 600 ms later on every sent
and delivered broadcast (and on a retry), never cancelling the previous one. Five
messages in a second ran five conversation queries through `chatIo` — four of which the
generation guard then discarded (`miniapps/OeaMessagingApp.kt:2574-2582`).

**Changed.** One reusable `refreshTask`; each event moves the same timer forward.

---

## 2. What was already right

* **Messaging's heavy reads are already off the main thread**, with real design behind
  it: one `PhoneLookup`-free contacts pass (`OeaMessagingApp.kt:191-220`), and
  `reload()` runs the conversation query on `chatIo` behind a `loadGeneration` token
  (`:2920-2960`).
* **The app drawer's search already debounces** (90 ms) and compares pre-decomposed
  keys — the model this pass followed for the kit field
  ([responsiveness review](responsiveness-review-2026-11-23.md) §1.2).
* **The Dynamic Island overlay is event-driven, not polled**: geometry refreshes on
  `ACTION_CONFIGURATION_CHANGED` and on window-configuration callbacks with a 200-250 ms
  debounce (`workspace/OeaDynamicIslandOverlayService.kt:65-78`, `:268-279`). The
  `postDelayed` calls I found there are debounces, not ticks.
* **Game Boost's session monitor stops itself** when the screen goes off and resumes on
  `ACTION_SCREEN_ON` (`gameboost/OeaGameBoostService.kt:328-354`).
* **Every `Thread.sleep` in the tree is on a worker**: the fault shield's watchdog
  (`fault/OeaFaultShield.kt:480`), the projection probe/diagnostics
  (`multitask/projection/*`), and `OeaShell`'s retry pause (`privilege/OeaShell.kt:170`).
  None of them is a UI thread, and none is called from `onDraw`/`onBind`.
* **Receivers are paired.** All 32 `registerReceiver` sites either unregister
  (`gameboost`, `power`, the island views and services, VIP, Messaging) or are the
  sticky-battery read (`registerReceiver(null, …)`, which registers nothing).

---

## 3. What this pass did not change, and why

* **The countdown still cannot survive the process being killed.** A countdown that
  keeps its promise across a low-memory kill needs `AlarmManager` plus the existing
  ring service, with `SCHEDULE_EXACT_ALARM` handled on Android 12+; Clock's alarms
  already work that way, a timer does not. That is a feature with its own failure
  modes (exact-alarm denial, Doze), not a one-line fix, and it belongs in its own pass
  with its own tests.
* **The stopwatch still ticks at 20 Hz while it is visible.** Hundredths are what the
  face shows; slowing it would make the last two digits move in 5s. What changed is
  that it now stops when nobody is looking.
* **Calendar still rebuilds its grid of views on the main thread.** After §1.2 that
  rebuild no longer waits on a provider, and it is tens of small views for the visible
  month — a RecyclerView rewrite would change the look and feel of a screen that is
  currently correct, for a cost that is no longer the one users feel.
* **The wallpaper/theme tiles render their scene bitmaps on the main thread**
  (`themes/OeaThemesActivity.kt:434-474`). They are one-time, small and bounded by the
  number of scenes; the alternative is an async thumbnail queue that has to cancel on
  scroll. Worth doing when that grid grows, not now.

---

## 4. Verification

* `Clock formatting regression tests` — `:oea:testDebugUnitTest` of the new
  `OeaClockFormatTest` (six cases over the rounding rules and the hour/negative edges).
* `Verify the mini-app response work` — 21 guard checks that fail the build if any of
  this is undone: the kit's debounce constant and both `removeCallbacks`/`postDelayed`
  calls, Calendar's `DEVICE` executor and month cache with **no** inline
  `loadDeviceEvents` call left in the paint, Clock's single `timerTicker` with no
  `post(object : Runnable` left in the click handler, both lifecycle hooks, the
  unchanged-label guard, `OeaClockFormat` in the view, and Messaging's single
  `refreshTask` with no per-event `postDelayed({ reload() })` left.
* The existing `Static check` (`tools/check-kotlin.py`, 302 sources), Kotlin compile,
  camera/search/VIP/responsiveness tests and guards and both APK builds all run in the
  same job.

**The run that carries this change set:** [`45296502034`](https://github.com/Emmanuel001afk/OEA/actions/runs/45296502034)
went green for branch `arena/28ebef8e-oea` at commit `2538de6`, with `185 tests` in the
unit-test step (179 before, plus the six `OeaClockFormatTest` cases) and the new
`Verify the mini-app response work` step among them. PR #156 merged as `2f8319c` at
2026-11-24T00:28:07Z.

Nothing here was compiled on this machine: this workspace has no Android SDK and no
Kotlin compiler, so the CI job's green result is the claim, not mine. Every guard was
executed locally against the working tree before pushing.

## 5. What to check on a device

1. **Calendar, typing.** Open Calendar with a Google/Exchange account synced, tap
   search and type six letters fast. Keys should appear instantly and the grid should
   settle once; the phone's events may appear a beat after the first frame — that is
   §1.2, not a stall.
2. **Calendar, months.** Step through a year of months quickly. Each month paints
   immediately and its device events fill in; going back to a month is instant (cached).
3. **Calendar, permission.** Deny calendar access, open Calendar, then grant it from
   the card: the events should appear without reopening the app.
4. **Clock, stopwatch in the background.** Start the stopwatch, press Home, wait a
   minute, come back. The time must be right (it is computed from the start instant) and
   `adb shell top -H -p $(pidof com.oea.launcher)` should show the main thread idle while
   you are away.
5. **Clock, countdown.** Start a 1-minute timer and press Start twice quickly: exactly
   one "Timer finished" notification, one second apart from nothing. Then start a timer,
   leave the app, and wait — it must still fire on time.
6. **Messaging, a burst.** Send or receive several messages quickly in one conversation:
   the thread should update once, not once per message, and the extra queries should not
   appear in `adb shell dumpsys gfxinfo` frame times.
