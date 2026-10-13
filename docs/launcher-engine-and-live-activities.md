# OEA launcher engine and live-activity plan

## Scope and investigation notes

OEA's expanded workspace remains the product surface: its pages, dock, folders, drawer, widgets, shortcuts, search, gestures and existing OEA apps are not being replaced. The work here hardens the route into that surface and makes notification activity an ordinary part of the workspace rather than a separate system window.

There is no device Logcat or crash report in this checkout, so the exact historical exception behind the reported force-closes cannot be proven from source alone. The source audit did find several concrete failure paths that can produce an unresponsive or fragile start:

1. `OeaApplication.onCreate()` eagerly constructed `OeaLauncherEngine`, which in turn constructed the complete `OeaWorkspace` view tree using an Application context. That happened even when the user opened Phone, Call Blocker, Game Boost or Settings. A Home UI initialization problem could therefore affect unrelated app entries.
2. The first launcher start queried every launchable activity synchronously on the UI thread. The engine also treated an empty catalogue as “not started”, repeating the query on every Home resume. A large/slow package catalogue could leave Home black while it waited; an exceptional package-manager result had no user-visible recovery path.
3. The app catalogue mapped every `ResolveInfo` without isolating malformed entries. One unexpected package result could fail the entire catalogue refresh.
4. The earlier notification listener tried to create a `TYPE_APPLICATION_OVERLAY` window for media only, swallowed `WindowManager` failures, and depended on special overlay access. It was outside the Home view hierarchy and did not provide a consistent call, charging, media and generic-notification model.

The new screen error boundary writes a stack trace to Logcat under `OeaScreenFailure`. If the original failure is reproduced, that stack trace can confirm the exact exception rather than relying on symptoms. The code changes below address the source-level risks whether or not they were the specific device-side trigger.

## Runtime boundaries

```text
OeaApplication
  └─ registers the application context only (no Home views)

OeaLauncherActivity
  └─ OeaHomeController
      └─ OeaRuntime (lazy, process-scoped)
          └─ OeaLauncherEngine
              ├─ OeaAppModel (immutable app snapshots)
              ├─ LauncherApps package callback / icon invalidation
              ├─ EngineState StateFlow (loading, count, last success, error)
              └─ OeaWorkspace (lazy, existing persistent workspace)
                   ├─ pages, dock, drawer, widgets, shortcuts and gestures
                   ├─ At a Glance
                   └─ in-workspace live-activity capsule
```

The engine now owns Home's catalogue lifecycle; the workspace continues to own layout and user interaction. Its initial and changed-package queries run on one serialized worker. Results are bound on the main thread, empty/error snapshots never erase the last good workspace, and a small retry affordance is shown instead of leaving Home as an unexplained blank surface. Standalone Settings app-catalogue and UsageStats lookups also run in the background. The launcher, Settings and Game Boost screens have a last-resort error surface, while normal rendering and all existing controls remain unchanged.

### Rules for future additions

- Keep one owner for each concern: package/catalogue work in the engine/model, placement and interaction in the workspace, and platform authority in the relevant Android service.
- Do not query PackageManager, load a large icon set, or scan usage history synchronously during activity creation.
- Publish immutable state; perform UI binding on the main thread; retain the last known-good value when a platform query fails.
- Keep feature-specific permissions opt-in and checked at the point of use. A denied optional permission must not prevent Home or another OEA app from opening.
- Persist user layout independently of transient app/notification snapshots. Version stored formats before evolving their structure.
- Add a feature-specific engine contract only when there is a second real implementation to share. Avoid a plugin framework or a second launcher UI until OEA has a concrete multi-module use case.
- Test install/uninstall callbacks, an empty catalogue, a malformed package result, permission-denied paths, activity recreation, and cold/warm Home starts before adding more background services.

## Dynamic Island-style area: platform research and OEA behavior

Apple's Dynamic Island is a system-owned presentation of Live Activities. Apps publish a structured, time-bounded activity; the system chooses compact/minimal/expanded presentations and places their content in fixed regions. It is not a general-purpose overlay window. Apple's ActivityKit and design guidance describe those states and the long-press expanded presentation: [Meet ActivityKit (WWDC23)](https://developer.apple.com/videos/play/wwdc2023/10184/) and [Design dynamic Live Activities (WWDC23)](https://developer.apple.com/videos/play/wwdc2023/10194/).

Android does not expose one universal “Dynamic Island” API for launchers. Its compatible building blocks are:

- `NotificationListenerService`, which receives posted/removed notifications only after the user explicitly grants Notification access ([Android reference](https://developer.android.com/reference/android/service/notification/NotificationListenerService)).
- `MediaSession`/`MediaController` transport controls for apps that publish a media session, along with MediaStyle notification actions as a compatibility fallback ([Android media controls](https://developer.android.com/media/implement/surfaces/mobile), [MediaSession](https://developer.android.com/reference/android/media/session/MediaSession)).
- Ordinary `Notification.Action` PendingIntents for apps that expose actions in their notification ([Notification.Action](https://developer.android.com/reference/android/app/Notification.Action)).
- Android's battery broadcasts for charging state; this does not require notification-listener or overlay access.

By default OEA renders the capsule as a child of its existing Home view hierarchy. It cannot cover another app, cannot steal touch outside Home, and does not request `SYSTEM_ALERT_WINDOW` just to show the Home capsule. An optional **Show over other apps** setting starts a separate foreground service only after the user enables it and grants Android's Display over other apps permission. That service owns small, repositionable windows for the selected island styles and suppresses duplicate in-Home/in-game islands while active. Android keeps a foreground-service notice; its notification includes a Turn off action when notification visibility is allowed. Notification activities can be shown for all apps or an explicitly selected app set. This system-wide option is opt-in and is not required for OEA Home to work.

OEA selects one useful current activity (OEA's own call state when OEA is the default dialer; otherwise a call/media/actionable ongoing notification; charging when no higher-priority activity is active). Tapping expands the capsule. Media sessions get previous/play-or-pause/next when the session advertises those actions; compatible notifications get available ordinary action buttons and Open. Clearable notifications can be swiped away or dismissed from the expanded card. Text-entry/RemoteInput actions are deliberately omitted because a compact island is not an appropriate place to collect a reply. OEA default-dialer calls get answer/decline/end/hold/resume actions; other dialers can supply their own call actions via their notification.

Notification-based details and third-party controls require Notification access; charging status and OEA's own active call state do not. An app that publishes neither a media session nor notification actions cannot be controlled by another app, which is an Android platform boundary rather than an OEA limitation.

## Practical verification checklist

1. Open Phone, Call Blocker, Game Boost and OEA Settings without starting Home first; confirm they do not allocate the Home workspace.
2. Cold-launch OEA Home with a populated, empty, or temporarily failing app catalogue; confirm there is no synchronous package scan on main, no layout wipe and a retry state on failure.
3. Add/remove an app while Home is open; confirm the catalogue refreshes in the background and icons invalidate without disturbing saved pages or folders.
4. With notification access off, verify charging appears and media/call notification data remains hidden. With access on, test a MediaStyle app that advertises previous/play/pause/next, successive notifications, ordinary notification actions, a call notification, Open, and swipe/dismiss of a clearable notification.
5. In the expanded capsule, test controls without opening the full media app; verify action taps work and RemoteInput is not exposed.
6. Toggle the live-activity area in OEA Settings, leave/return Home, rotate or resize, and verify the default Home capsule stays in the workspace view hierarchy.
7. Separately grant Display over other apps and enable Show over other apps. Verify the foreground notice and, when notifications are allowed, its Turn off action; test both island styles and per-app filtering, confirm the Home/game copy is suppressed, and revoke permission to ensure the service stops cleanly.


## Follow-up: warning accuracy, live-activity styles and widgets

### When Home shows a warning

- `OeaScreenFailure` (the full-screen "couldn't start" page) is a **startup boundary**. `OeaLauncherActivity` uses it only until the Home surface has been attached once. After that, resume/rotation/action errors are logged and the running workspace is kept.
- The engine shows its centred retry pill only when there is no usable Home. A bind is judged by its result: if a late stage throws after pages were rebuilt (`OeaWorkspace.hasUsableHome()`), Home is treated as working. Empty or failed refreshes with a bound workspace are logged and retried quietly.
- `OeaWorkspace.rebuild()` isolates its stages (layout, each page, each widget/icon, dock, drawer, folder, hidden apps), so one broken provider or tile cannot blank Home.

### Two live-activity styles

- `OeaDynamicIslandView(context)` is the card under the status bar; `OeaDynamicIslandView(context, Surface.CAMERA)` is the island under the front camera. Both share the payload, MediaController, call and Notification.Action logic.
- The camera island is centred on the detected top display cutout and anchored just below the greater of the cutout's lower edge or the status bar. Stored horizontal/vertical offsets let users reposition it for other camera layouts; the system-owned status-bar strip itself is not touchable by this overlay.
- Style is "card", "camera" or "both". With both, `OeaIslandRouting` sends assigned apps to their style; unassigned apps, OEA calls and charging use the chosen default.
- Both styles float in `homeLayer` above the Home column; a spacer reserves only their collapsed height, so expanding never shrinks the grid or resizes widgets.

### Widgets

- Placement is stored per widget (`page`, `x`, `y`, `spanX`, `spanY`). Legacy full-width bands are migrated in order, preserving the old layout.
- Spans are clamped to the provider's `minResize*`/`maxResize*` limits and `resizeMode` (with a measured cell size), which prevents the squeezed/overlapping content seen with the old row-only stepper. Size changes are reported with the Android 12+ `SizeF` API.
- Long-press shows the widget menu; moving after the long-press drags the widget (across pages at the screen edges). "Resize" opens an in-place frame with edge handles; icons under a moved or resized widget move to the nearest free cell.
