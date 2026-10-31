# OEA Floating App Projection — Research, Architecture and Incremental Implementation (2026-10-30)

This document records Stage A (investigation) and Stage B (the feasibility
instrument) for OEA's floating multitasking system, plus the implementation
that came out of them. It separates **confirmed facts** (verified in AOSP
source or established references this session) from **hypotheses** that only
the target device can settle.

**Status of evidence in this document:**

- ✅ verified in AOSP source, tag `android-13.0.0_r1`, fetched and read this session
- ✅ verified by compiling the implementation against a real AOSP-built `android-33` jar
- ❌ NOT verified: any on-device behaviour. No device was available in this
  session. Every device-behaviour claim below is explicitly a hypothesis until
  the Stage-B experiment runs on the Infinix Smart 8 (XOS, Android 13 Go).

---

## 1. The required experience (restated so we cannot drift)

The user is in app A (say Chrome). They open OEA's floating workspace and
select WhatsApp. The real WhatsApp — its actual code, its actual account and
data, its actual current screen — must be presented **inside an OEA-managed
floating window**, and the user must be able to tap, scroll, navigate, type
and send messages through that window, while **app A stays visible and usable
on the rest of the screen**. A screenshot, static preview, replica, or
shortcut is not acceptable; a message sent through the window must be sent by
the real WhatsApp and appear in the real conversation.

## 2. Stage A — What Android 13 actually permits (confirmed facts)

### 2.1 Why the two "obvious" mechanisms cannot deliver it

**Mirror-projection (screenshot the app + `dispatchGesture` at mapped
coordinates).** Both capture APIs (`MediaProjection`,
`AccessibilityService.takeScreenshot`) capture **the display**, not an app.
A backgrounded app is not rendered anywhere capturable — its windows leave
the composition, `onStop()` fires, its surface is destroyed. `dispatchGesture`
injects input at the **display** level: it reaches the top-most touchable
window at the coordinates, which is by definition the foreground app. There
is no API to direct a gesture at a background app's windows. Consequence: a
mirror of "WhatsApp behind the overlay" is possible, but it is a mirror of a
**full-screen** WhatsApp — the user's original app is gone (stopped and
covered). This violates the foreground-preservation pillar and cannot be
repaired within this architecture. (The task statement anticipated exactly
this: "OCR alone does not create an interactive application, and dispatching
gestures does not automatically provide an independent input channel to a
background app.")

**Freeform windows.** Real, system-managed app windows — the ideal mechanism —
but gated by `mSupportsFreeformWindowManagement`
(`services/core/java/com/android/server/wm/Task.java`:
`supportsFreeformInDisplayArea` requires the system feature
`android.software.freeform_window_management`). The existing OEA research
records this feature as absent on the Infinix Smart 8 (XOS, Android 13 Go),
and OEA already reports that honestly (`OeaMultitaskLauncher`). Freeform also
remains Android-owned, not OEA-managed.

**Split screen.** Puts two real apps on one screen, but the screen is
consumed by both halves and the experience is Android's, not an OEA floating
window. `GLOBAL_ACTION_*` is not reliably programmable to enter split screen
with two chosen apps. Rejected as a redesign (per the brief) — though a
future variant could combine split screen + projection, that is a different
trade-off and is not implemented here.

**Cross-app activity embedding (Jetpack).** Android 13 requires the *target*
app to opt in (`PROPERTY_ACTIVITY_EMBEDDING_ALLOW_SYSTEM_OVERRIDE` /
allowEmbedded policies). WhatsApp/Chrome do not opt into OEA. Not viable.

### 2.2 The one architecture that can satisfy all three pillars without root, Shizuku, ADB or freeform

**Put the real target application on an app-created secondary (virtual)
display; present that display's pixels inside OEA's floating window; drive
the app through accessibility node actions.**

Confirmed facts that make this architecture possible:

1. **Any app can create a virtual display**
   (`DisplayManager.createVirtualDisplay`) backed by a surface it owns
   (`ImageReader`, `SurfaceTexture`). No special permission for the ordinary
   flag bits. ✅ `DisplayManager.java`, android-13.0.0_r1.

2. **Whatever renders on that display arrives in OEA's own buffers**, live,
   at the app's own frame rate. This is *live presentation of the real app*
   with no screenshot involved. ✅ (API semantics; used by existing
   open-source reference "GhostDisplay" and OEA's own Game Capture service
   uses the same ImageReader mechanics.)

3. **The user's foreground app is untouched.** A virtual display is a
   separate display; the default display keeps its foreground app, its
   focus, its IME. This is the only no-root mechanism where
   **foreground preservation is structurally guaranteed**, not hoped for.
   ✅ (AOSP multi-display model.)

4. **The launch gate.** `ActivityTaskSupervisor.isCallerAllowedToLaunchOnDisplay`
   (read in full, android-13.0.0_r1; identical logic in android-12.0.0_r1):
   - `INTERNAL_SYSTEM_WINDOW` holders (system/shell) may launch anything
     anywhere — not OEA.
   - On an **untrusted** display (every app-created VD: `TRUSTED`,
     `OWN_DISPLAY_GROUP`, `ALWAYS_UNLOCKED` require signature-level
     permissions — `DisplayManagerService.createVirtualDisplayInternal`
     enforces `ADD_TRUSTED_DISPLAY`), the target activity must set
     `android:allowEmbedded="true"` **and** the caller must hold
     `ACTIVITY_EMBEDDING` *or its uid must already be present on the display*.
   - A **public** display allows "anyone" — still behind the untrusted/
     allowEmbedded branch.
   - A **private** display allows the owner and already-present uids.
   - `moveRootTaskToDisplay` (the hidden task-mover) enforces
     `INTERNAL_SYSTEM_WINDOW` → unusable by ordinary apps.

   The **trampoline pattern** threads this needle: OEA puts **its own**
   activity (declared `allowEmbedded`) on the display it owns, launched from
   a display-scoped context (a `Presentation` first makes OEA's uid *present*
   on the display). A plain `startActivity` from that activity with no
   options targets the source task's display, so the real third-party app is
   started *into the task that already lives on the virtual display*.
   ✅ (AOSP `ActivityStarter`/`RootWindowContainer.getOrCreateRootTask`
   routing; empirically this is exactly how the reference implementation
   works on stock Android 12–14.)

   **Hypothesis (device-dependent):** whether a specific OEM honours this
   path for a specific third-party app. Xiaomi HyperOS is documented (by the
   reference project) to refuse it for ordinary uids on Android 15, needing
   Shizuku's shell identity. Transsion XOS on Android 13 is **unknown**. This
   is the single most important Stage-B measurement.

5. **Input: node actions are the only channel — and they work across
   displays.** `AccessibilityService.dispatchGesture` has **no display
   parameter** (confirmed: `GestureDescription` carries no display;
   injection targets the default display). But
   `AccessibilityNodeInfo.performAction(ACTION_CLICK / ACTION_SET_TEXT /
   ACTION_SCROLL_FORWARD / ACTION_PASTE / …)` is a binder call into the
   target app's view hierarchy, independent of display and input focus.
   `AccessibilityWindowInfo.getDisplayId()` (API 30+) lets the service find
   the target's windows specifically on the virtual display and map
   touch coordinates to node bounds (`getBoundsInScreen` returns coordinates
   in the window's display space). ✅ (API semantics; this is the reference
   implementation's "node-first, gesture fallback" controller, gestures
   being useless for VDs.)

6. **Text entry without the IME problem.** A system IME attaches to the
   focused window on the *physical* display; it cannot be summoned into the
   overlay for a VD app. `ACTION_SET_TEXT` (and `ACTION_PASTE`) put text into
   the real app's real fields through accessibility. OEA's live window
   therefore provides a text strip: type → `ACTION_SET_TEXT` into the
   focused/first editable field of the projected app → the user taps the
   app's own send button through the projection. The message is sent by the
   real app. ✅ (mechanism) / ❌ (per-app field compatibility — Stage B).

7. **Accessibility screenshots** exist (`AccessibilityService.takeScreenshot`,
   API 30+, requires `android:canTakeScreenshot="true"`; platform rate limit
   333 ms — `ACCESSIBILITY_TAKE_SCREENSHOT_REQUEST_INTERVAL_TIMES_MS = 333`,
   confirmed in source). They are **not needed** for the virtual display
   (OEA owns its surface) — they are used only by the diagnostics to
   characterise the device's default-display capture.

8. **A second instance caveat.** An app that is *already open on the phone*
   may be re-fronted on the phone instead of starting on the private
   display (its task already lives on display 0). The ladder detects this
   beforehand and reports it ("close the app on the phone first").

### 2.3 Known, accepted limitations of this architecture

- **View-only apps.** Apps that expose no accessibility tree (many games,
  canvas apps, camera previews) can be *presented* but not *controlled* —
  there is no input channel into a virtual display for third-party apps
  (no touch injection API). OCR cannot fix this: OCR can *read* a frame but
  cannot *act* on it. Such apps are honestly labelled VIEW-ONLY.
- **DRM/secure content** may render black on a virtual display.
- **OEM launch-gate variance** (the Stage-B question): if XOS refuses, live
  windows fail loudly per app; OEA reports and cancels — never fakes.
- **Keyboard feel.** Typing goes through the strip (set-text), not a live
  system keyboard inside the projection; per-field behaviour varies.
- **Not isolation.** A private display hides the picture, not the app: it
  still uses the network and notifications.
- **Memory.** Each projection is a live second app instance rendering to its
  own buffer (~4.5 MB per 720×1600 RGBA frame queue) — on a Go device the
  app processes themselves dominate. Measured in Stage E.

## 3. What was implemented (this session)

New package `oea/src/main/java/com/oea/launcher/multitask/projection/`:

| File | Role |
|---|---|
| `OeaProjectionTypes.kt` | States (`LIVE`/`VIEW_ONLY`/`FAILED`/`STOPPED`), action outcomes, evidence lines |
| `OeaVirtualDisplayHost.kt` | Creates the VD with an honest flag ladder (no permission-gated bits requested), owns the ImageReader surface, frame counters, resize/release |
| `OeaProjectionTrampolineActivity.kt` | Transparent starter activity (`allowEmbedded`, own taskAffinity, excluded from Recents). Verifies the display it *actually* landed on before starting the target; fails loudly if the OEM put it on the phone |
| `OeaProjectionLaunch.kt` | The launch ladder with per-step timings and evidence: occupancy Presentation → `isActivityStartAllowedOnDisplay` probe → trampoline with `launchDisplayId` → verification via the accessibility window list → leaked-launch detection (incl. "already open on phone") |
| `OeaProjectionController.kt` | Node-first controller: click/long-press/scroll/set-text/paste/back-in-app on the app living on a given display; smallest-bounds hit testing with clickable/scrollable ancestor walk |
| `OeaLiveWindowOverlay.kt` | The floating window: title bar (drag), live content (TextureView with automatic ImageReader fallback, aspect-correct letterbox + matching coordinate mapping), touch translation, text strip with focus borrowing, resize handle, status badge/line |
| `OeaLiveWindowSession.kt` | State machine per projected app: start ladder, frame pipeline, staleness watchdog (demotes to STALE/ENDED when frames or the target's window disappear), all node actions on one thread |
| `OeaLiveWindowManager.kt` | Up to 3 concurrent sessions, service wiring, event-mask widening only while sessions run (same runtime pattern as the App Freezer) |
| `OeaProjectionDiagnostics.kt` | **The Stage-B experiment** (see §4): platform facts, VD creation, launch ladder, frame count, node-control probe, foreground-preservation check, screenshot capability, memory, cleanup verification, verdict + copyable report |
| `OeaLiveWindows.kt` | Preferences; feature off by default |

Integration changes (all additive):

- `OeaFloatingWindowService.kt` — service lifecycle wiring to the manager,
  event forwarding, and a **Live app windows** toggle inside the existing
  workspace (off by default; when on, tapping an app starts a live window;
  long-press always keeps the old freeform request; running sessions are
  listed with restore/close). Existing behaviour when the toggle is off is
  unchanged.
- `OeaSystemToolsActivity.kt` — Multitasking page gains a "Live app windows
  (experimental)" section: enable switch, **Run the Stage-B experiment**
  (app picker → background run → scrollable report with Copy), copy last
  report, live-window status + stop-all.
- `AndroidManifest.xml` — the trampoline activity (`allowEmbedded`,
  `resizeableActivity`, own `taskAffinity`, `excludeFromRecents`,
  translucent `OeaCaptureTheme`).
- `res/xml/oea_floating_window.xml` — adds `canTakeScreenshot="true"`
  (needed only by the diagnostics; justified above).
- `res/values/strings.xml` — the accessibility service description now
  truthfully describes the live-window capability (required for
  accessibility transparency).
- `tools/projection-compilecheck/` — the reproducible type-check harness.

**Verification performed here:** the full projection package, the modified
service, `OeaFloatingWindows` and `OeaMultitaskLauncher` compile with **zero
errors** against a real AOSP-built `android-33` jar (see
`tools/projection-compilecheck/README.md`). The new Settings functions were
compiled verbatim in a harness the same way. The authoritative build check
is the repository's APK workflow. **No device test has been run** — nothing
below claims otherwise.

## 4. Stage B — the experiment to run on the Infinix Smart 8

The diagnostics run the smallest experiment that validates or refutes all
three pillars, and write a report:

1. OEA Settings › Multitasking › **Live app windows** → make sure the OEA
   Floating Window accessibility service is on.
2. Tap **Run the Stage-B experiment**, pick an app
   (recommend order: Chrome first — it is the known-most-compatible real
   app; then WhatsApp, the actual target experience).
3. Read the report's **Verdict**: pillar 1 (app really on the private
   display), pillar 2 (interactive nodes present), pillar 3 (foreground app
   preserved). Copy it back for review.
4. Then use the real thing: open the floating workspace, switch **Live
   windows ON**, tap the app, and perform the user checklist that the report
   prints (open a conversation, scroll, type via the strip, tap send,
   confirm the message exists in the real app, confirm the original app
   stayed visible and usable).

The acceptance criteria from the brief map as follows:

| Criterion | Measured by |
|---|---|
| Real target app in an OEA-managed floating window | Ladder evidence + window-list verification (not a shortcut, not a static image) |
| Content derived from the real application | Frame pump counters (live frames counted over 3 s) + the fact that interactions change what is shown |
| Supported interactions affect the real app | Node-action outcomes + the user checklist (send a real message) |
| Text entry and keyboard behaviour actually tested | Text strip → `ACTION_SET_TEXT`/`ACTION_PASTE`; checklist item 2–3; IME focus borrowing is itself device-tested |
| Displayed state updates after interaction | Watchdog staleness rules; a window never claims LIVE without fresh frames or a visible target window |
| User keeps the original foreground app | Pillar 3: foreground window package re-read and compared; plus manual confirmation |
| Limitations, permissions, failure cases documented | The report itself + §2.3 here |
| Existing launcher functionality intact | Toggle-off restores the exact previous workspace behaviour; event mask narrows when no sessions run; no existing file's behaviour modified while the feature is off |

## 5. Stages D & E — incremental integration plan (after Stage B passes)

- **D1 (per app):** run the experiment + user checklist per app; record
  per-app results (nodes, editable fields, quirks) in this document.
  Candidate order: Chrome → WhatsApp → one more (e.g. Notes/YouTube).
  An app is "supported" only with a filled checklist, never by assumption.
- **D2:** stabilise per-app findings (latency, field compatibility), tune
  display spec (size/dpi) per app if needed.
- **E (three windows):** the manager already enforces `MAX_WINDOWS = 3` with
  cascaded placement. Validate concurrency on the device: input routing per
  window (node actions are per-display — must not cross), stale detection
  per window, focus behaviour when typing into one while another runs,
  memory/CPU of three live apps on a Go device, and recovery (kill one app,
  rotate, lock/unlock, OEA process death). Only after that may "three
  simultaneous floating app windows" be claimed.

## 6. Honest failure modes to expect (and what OEA does)

| Failure | OEA behaviour |
|---|---|
| XOS refuses the trampoline on the virtual display | Launch fails with the exact step and OEM verdict; the app is never opened on the phone as a "success" |
| App already open on the phone | Reported with instructions; no silent re-front counted as success |
| App lands on the phone anyway | Detected; projection cancelled; message says so |
| App renders but exposes no nodes | Window is explicitly VIEW-ONLY |
| Frames stop (app frozen/died) | Watchdog demotes the badge to STALE/ENDED — a frozen picture never poses as live |
| OEM blocks IME for overlay windows | The strip still works through set-text; if even that fails, per-field rejection is shown in the status line |
| VD creation capped by OEM | Creation ladder reports every attempt; feature reports unavailable |

## 7. First real device run (Infinix Smart 8, XOS, Android 13 Go) — findings and fixes

Observed by the user on 2026-10-30 (PR #124 build):

1. **WhatsApp live window FAILED.** Header "WhatsApp — FAILED", black
   content, status "This window is no longer live" after tapping. The
   session failed during the launch ladder, before any frame existed.
2. **Andronix attempt: "Permission Denial: starting Intent { flg=0x18000000
   cmp=com.oea.launcher/.multitask.projec… }".** `0x18000000` is exactly
   `FLAG_ACTIVITY_NEW_TASK|FLAG_ACTIVITY_MULTIPLE_TASK` — the flags the
   ladder's Step 3 sets on OEA's own trampoline — so the refused start is
   the trampoline launch from the display-scoped context. The message was
   truncated by the settings row; the full text is in the (now fixed)
   Stage-B report and the Live-windows details dialog.
3. **The Stage-B experiment produced no report at all.** "Copy last
   Stage-B report" kept saying there was none.

### Analysis (AOSP android-13.0.0_r1, fetched and quoted during this fix)

- `ActivityTaskSupervisor.checkStartAnyActivityPermission` is the ONLY
  place in Android 13 that throws `SecurityException("Permission Denial:
  starting Intent { … }")` for an activity start, with three branches
  (revoked action permission / not-exported / requires permission).
  Starting our own non-exported activity from our own uid is always
  granted on stock AOSP (`checkComponentPermission`: same app ⇒ granted).
- `isCallerAllowedToLaunchOnDisplay` (the secondary-display gate) NEVER
  throws — it returns false and the launch is aborted or redirected
  silently. Its rules: DEFAULT_DISPLAY always OK; `INTERNAL_SYSTEM_WINDOW`
  bypasses; untrusted display ⇒ target needs `FLAG_ALLOW_EMBEDDED` and the
  caller needs `ACTIVITY_EMBEDDING` **or** its uid present on the display;
  public display ⇒ anyone; private display ⇒ owner or uid-present.
- Therefore the observed SecurityException is **not stock AOSP behaviour**
  for this call: it comes from an XOS/Transsion framework addition (most
  plausibly their background-activity-start / pop-up firewall for ordinary
  apps). Which exact branch denied it can only be read from the full
  exception text — which the fixed Stage-B report now captures verbatim
  (class + message + first stack frames, for every route tried).

### Fixes in this revision

- **Stage-B report finalization (the missing-report bug):** the experiment
  now (a) writes the report to storage after **every section**, so a hang
  or process death still leaves everything measured so far; (b) runs
  inside a catch-all that records the exception (class, message, stack),
  saves and returns; (c) marks every stage PASSED / FAILED / INCONCLUSIVE
  and prints unreached stages as **NOT RUN** (never as failed); (d) ends
  with a stage table naming the exact failed stage and an overall verdict.
  The Settings side gained a live progress dialog during the run, a
  report dialog with **Copy and Share** buttons (no clipboard dependency),
  a "View / copy last Stage-B report" row that refreshes after each run,
  and a last-resort catch in the runner thread itself.
- **Launch ladder:** the occupancy Presentation is now shown and its
  window's attach to the display is **confirmed before any launch**
  (accessibility window list or the Presentation's own decor view), which
  is what makes OEA's uid "present on the display" for the AOSP gate. The
  starter is then tried through several legal routes in order — display
  context, presentation context (owns a window on the display), NEW_TASK
  only — each recorded separately with the complete exception text. A
  route counts only when the trampoline confirms the display it landed on.
- **Live-window details:** Settings › "Live windows now" opens a dialog
  with each session's state and full evidence, so a truncated status line
  can be read in full.
- No new permissions, no manifest changes, no exported components. The
  architecture is unchanged; only the diagnostics workflow and the launch
  routes were hardened.

### Still unverified on the device

- Whether any launch route now lands the starter on the virtual display
  (the next Stage-B run answers this and identifies the exact denial if
  none do).
- The interaction/frames/preservation pillars (unreachable until the
  launch succeeds).
- Whether the XOS per-app control ("Display pop-up windows while running
  in the background" / auto-launch for OEA) is what denied the start —
  the report's full exception text will say.

---

## Investigation update — 2026-10-31: the device denial decoded

### What the device said

The PR #125 build ran on the Infinix Smart 8 (XOS, Android 13). The Stage-B
report renders inside the OEA FLOAT overlay (working), and every launch
route for every target (Calculator, Andronix, Attend) failed with the same
platform refusal:

```
java.lang.SecurityException: Permission Denial: starting Intent
{ flg=0x18000000 cmp=com.oea.launcher/.multitask.projection.OeaProjectionTrampolineActivity }
from ProcessRecord{… 4882:com.oea.launcher/u0a410} (pid=4882, uid=10410)
with launchDisplayId=2        (other runs: 3, 4 — per-run virtual-display ids)
```

`flg=0x18000000` = NEW_TASK|MULTIPLE_TASK — our intent, unmodified. The
refusing check names `launchDisplayId` explicitly and fires for OEA's own
activity, not just third-party targets.

### What the AOSP sources establish (13 r1 & r84, 14 r75, 15 r36)

The "with launchDisplayId=N" throw exists in **no** stock AOSP version
checked (searched ActivityStarter, ActivityTaskSupervisorService,
ActivityTaskManagerService, ActivityOptions, ActivityManager,
LauncherAppsService, LaunchParamsController, TaskLaunchParamsModifier,
RootWindowContainer across those five tags). It is this manufacturer's
hardening of a stock rule that normally does NOT throw. The stock rule:

1. **`ActivityTaskSupervisorService.isCallerAllowedToLaunchOnDisplay`**
   (identical in 13 r1 and r84): on the default display → allowed;
   INTERNAL_SYSTEM_WINDOW holders → allowed; on an **untrusted** display
   (every app-created virtual display — `VirtualDisplayAdapter` sets
   FLAG_TRUSTED only for callers that passed VIRTUAL_DISPLAY_FLAG_TRUSTED,
   which requires the signature permission ADD_TRUSTED_DISPLAY):
   - the **target activity itself** must declare
     `android:allowEmbedded="true"`, otherwise the placement is refused —
     unconditionally, regardless of display ownership, FLAG_PUBLIC, or the
     caller having a window there;
   - and the caller needs the signature permission ACTIVITY_EMBEDDING **or**
     an **activity record** of its uid already on that display. A
     Presentation window does not count (`DisplayContent.isUidPresent`
     counts ActivityRecords only) — so the occupancy Presentation premise
     of the first ladder was wrong, and the first OEA activity on the
     display is a chicken-and-egg problem: it cannot be launched there
     directly.
2. **`RootWindowContainer.getOrCreateRootTask`** (13 r84 :2799) gates
   EVERY placement candidate — `launchDisplayId`, launch task display
   area, and **task inheritance** — through
   `ActivityRecord.canBeLaunchedOnDisplay` (:3150) → the rule above, with
   the target's real `launchedFromPid/Uid`. Android 15's
   `ActivityRecord.canBeLaunchedOnDisplay` is identical. So "start the
   target with no options so it inherits the trampoline's display" is
   gated by the **target's** allowEmbedded bit too.
3. **Consequences, now source-proven rather than assumed:**
   - a **third-party** target (which never declares allowEmbedded) cannot
     be placed on an app-created display by any ordinary app on stock
     Android 13–15 — not by launchDisplayId, not by task inheritance;
   - OEA's **own** activities (which do declare allowEmbedded) can be
     placed there **once one OEA activity record is already on the
     display**;
   - stock Android silently falls back to the phone screen on refusal;
     this manufacturer instead throws the observed SecurityException;
   - `moveRootTaskToDisplay`/`moveTaskToDisplay` require the signature
     permission INTERNAL_SYSTEM_WINDOW on stock 13 (ATMS :2976), but
     several OEM builds expose `ActivityManager.moveTaskToDisplay` to
     ordinary apps — that API is the one remaining legal way to get the
     first OEA activity record onto the display on this device.
4. The manifest parser still honours `android:allowEmbedded`
   (PackageParser 13 r84 :4441 sets FLAG_ALLOW_EMBEDDED from it) and the
   gate still reads the bit (ATS 13 r84 :1177), so the attribute is live
   on Android 13 despite its deprecation notice.
5. Dead routes confirmed and closed: LauncherApps keeps the real caller
   identity (same gate); `isActivityStartAllowedOnDisplay` probes the same
   rule; the "with launchDisplayId" throw fires before background-start
   (BAL) evaluation, so BAL status on this device is still unknown and is
   now measured separately by the no-options start.

### The evidence-based correction (this change)

- **New re-parent route** (`OeaTaskMove.kt`): after the direct
  launchDisplayId routes are refused, the starter is started with **no
  launch options at all** (transparent, non-focusable, non-touchable, so
  the user never notices it on the phone), its task id is taken from the
  handshake, and every task-move API the device exposes is tried in order:
  OEM-public `ActivityManager.moveTaskToDisplay`, the same move calls
  through the raw `activity_task` binder (the platform blocklist forbids
  reflecting into the `ActivityTaskManager.getService()` wrapper for apps
  targeting API 36+, so that route is dead by construction and is not
  attempted — the binder handle reaches the same system service and the
  system server enforces its own permissions on every call), and finally
  `startActivityFromRecents(taskId, options with launchDisplayId)`. Each
  call is recorded with its exact outcome — including which permission the
  platform demanded. If any of them moves the starter, the chicken-and-egg
  problem is solved legally: the starter is an activity record on the
  display, OEA's uid is present, and the target inherits the display.
- **Foreground activity context route:** the Stage-B experiment now runs
  the ladder with the Settings activity as an additional launch context
  (some manufacturers key their checks on the caller being a visible
  activity; the no-options start also needs a foreground caller where
  background starts are policed).
- **`allowEmbedded` on OEA's own apps:** every launchable OEA app (the
  workspace's "OEA APPS" set, home excluded) now declares
  `android:allowEmbedded="true"` — the single source-verified condition
  under which an activity may ever be placed on an app-created display.
  No permission is granted and nothing changes on the phone screen; the
  attribute is inert until a placement the platform already permits
  happens. Third-party apps cannot be given this bit (their manifests are
  not ours) — that limit is a platform rule, reported honestly.
- **Starter hardening:** its window is now non-focusable and
  non-touchable, and its self-timeout rose to 16 s so it survives the
  whole re-parent sequence.
- **Report:** a "The launch rule this experiment is measured against"
  section (with the target's own allowEmbedded bit read from its
  manifest), decoded display flags (PRIVATE/PRESENTATION/SECURE and the
  TRUSTED bit that must be absent), the probe answers per context, every
  reparent API outcome, and failure headlines that name the platform rule
  instead of a generic error.
- **Honesty guarantees unchanged:** a target that ends up on the phone is
  reported as leaked, never as success; a stage that never ran is NOT RUN.

### What the next device run must decide

1. Does any reparent API on this XOS build accept a task move to our
   display? (If yes: OEA's own apps become live windows — the full
   pillars then get tested for real. If no: the direct routes plus the
   reparent ladder together prove that this device's platform gives an
   ordinary app no path to its own virtual display, and the report will
   say exactly that, with every refusal.)
2. Is the no-options starter start allowed from the foreground Settings
   activity (BAL status on this device)?
3. For a third-party target, the report now separates "the starter could
   not be placed" from "the target could not be placed because it does
   not declare allowEmbedded" — the latter is expected to be a hard
   platform NO on every device.

If (1) fails on this device, the OEA-managed floating window for
**third-party apps** is platform-blocked on Android 13–15 in general
(source-proven, not device-specific), and the product decision is: OEA
apps through the live-window pipeline where the platform allows it, and
the native freeform method for everything else — with the Stage-B report
as the evidence the user can read, copy and share.
