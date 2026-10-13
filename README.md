# OEA Launcher

OEA is an independent Android launcher and Android-skin layer built around its own engine and workspace.

## Architecture

The active launcher path is:

`OeaLauncherActivity → OeaHomeController → OeaRuntime → OeaLauncherEngine → OeaWorkspace`

OEA owns the home surface, app model, workspace persistence, drawer, dock, folders, shortcuts, gestures, themes, and system-feature integrations. The engine is initialized lazily, loads the app catalogue away from the UI thread, retains the last good workspace on failure, and publishes its state for future engine-owned features.

See [Launcher engine and live activities](docs/launcher-engine-and-live-activities.md) for the lifecycle boundaries, stability rules, Dynamic Island-style notification design, platform constraints, and verification checklist.

Modules:

- `oea` — the launcher (`com.oea.launcher`), including the OEA Games hub.
- `oea-engine` — the OEA Game Engine (`com.oea.engine`), with no launcher dependency.
- `oea-games` — first-party games (`com.oea.games`), written only against the engine's public API.

## OEA Games and the OEA Game Engine

OEA ships its own game engine and games, opened from the **OEA Games** app (own icon and task, addable to Home). See [OEA Game Engine](docs/oea-game-engine.md) for the architecture, API, multiplayer protocol and a step-by-step guide to writing new games.

- **Engine**: OpenGL ES 3 renderer with automatic instancing, frustum culling, sun shadow maps, procedural sky, bloom, FXAA, dynamic resolution and supersampling; sprites, fonts and an immediate-mode HUD; particles; touch gestures, virtual joystick/buttons/look pad, gamepad and keyboard; synthesised sound effects and music; physics with ray casts, terrain and a character controller; ECS; deterministic RNG and noise; N-dimensional geometry for 4D+ (rotations in any plane, projection to 3D).
- **Performance**: highest display refresh rate, Android ADPF hint sessions, device tiering, thermal and Game Mode awareness, optional FPS overlay.
- **Multiplayer, two methods only**: local Wi-Fi / hotspot (automatic discovery, TCP + UDP, QR or address fallback) and online WebRTC connected by exchanging an invite and a reply code or QR — no accounts or game servers; STUN/TURN only if you add your own.
- **Dynamic Island**: games publish live activities and events to the card style, the camera island, or both (automatic or per-game placement), on Home and inside games.
- **Games**: Runner (endless train-yard runner; split-screen duel, take turns, races with ghost runners), Arena (battle-royale foundation with bots and host-authoritative netcode), Chess (complete rules, 10 AI levels, 3D/2D boards, clocks, pass & play, Wi-Fi/online) and the Engine Lab.

## OEA Systems

- Freezer, a standalone app (own icon and task): Android package suspension with Device Owner/root, otherwise OEA Freeze (blocked in OEA and force-stopped by OEA Assistant behind a progress screen). A granted OEA Assistant that Android paused is reconnected, never re-requested.
- Call blocker using Android's call-screening role with exact/prefix/suffix rules and contact exceptions.
- Phone with full-screen (over the wallpaper), popup and floating-card call screens, Telecom-backed mute/speaker/Bluetooth routing, proximity screen-off and an incoming-call notification with Answer/Decline. Telecom plays the ringtone.
- Split screen that waits for the first app to be in front before launching the adjacent app, verifies the split with OEA Assistant and recovers once through the system split toggle.
- Game Boost monitoring selected games with usage access, optional DND handling, and a floating control with RAM gauges, network speed (down/up) and a cycling rainbow colour option.
- Built-in apps (OEA Games, Game Boost, Phone, Call Blocker, Freezer, OEA Settings) are ordinary launcher entries that can be added to Home like any app.
- Launcher navigation remains integrated with Android's own Home and Recents/Overview surfaces.

## Home and app drawer

- Persistent home pages, dock, folders and drag/drop.
- Widgets own a rectangle of cells: drag them between pages like icons, and resize them with Android-style handles limited to each widget's own min/max size.
- App search and All Apps search.
- Grid, vertical-list and horizontal All Apps layouts.
- Long-press app actions, hiding and app-freezer access.
- Hidden apps as their own space (drawer ⋮ › Hidden apps): open and use hidden apps there, optionally behind the App Lock PIN.
- Wallpaper-derived launcher theme colors.
- Native Android launcher shortcuts.
- Home-only live activities in two styles — the card under the status bar and an expandable island under the front camera — usable alone or together with per-app routing. Both expand over Home without resizing the grid (requires notification access for app notification content).
- The full-screen launcher error page appears only when Home genuinely fails to start; later recoverable errors are logged and the running Home is kept.

## Independence

OEA does not build, run, or import Launcher3/Lawnchair source code. The repository contains the standalone `oea` application module, the OEA-owned `oea-engine` and `oea-games` modules, and OEA-owned launcher code. Online play uses the open-source WebRTC library (data channels only) and ZXing for QR codes; no proprietary SDKs or third-party services are involved.

## Build

`./gradlew :oea:assembleRelease`

Engine and game unit tests: `./gradlew :oea-engine:testDebugUnitTest :oea-games:testDebugUnitTest`

The resulting application ID is `com.oea.launcher`.
