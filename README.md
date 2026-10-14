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
- `oea-game-arena`, `oea-game-chess`, `oea-game-runner` — separate game APKs with their own launcher icons and Android's Game app category; they reuse `oea-games` and the engine.

## OEA Games and the OEA Game Engine

OEA ships its own game engine and games, opened from the **OEA Games** app (own icon and task, addable to Home). Arena, Chess and Runner also have separately installable APK modules with their own icons and Android Game category; the hub launches an installed standalone app for solo play and keeps the in-hub engine path as a fallback and for multiplayer. Build them with `:oea-game-arena:assembleDebug`, `:oea-game-chess:assembleDebug` and `:oea-game-runner:assembleDebug`. See [OEA Game Engine](docs/oea-game-engine.md) for the architecture, API, multiplayer protocol and a step-by-step guide to writing new games.

- **Engine**: OpenGL ES 3 renderer with automatic instancing, frustum culling, sun shadow maps, procedural sky, bloom, FXAA, dynamic resolution and supersampling; sprites, fonts and an immediate-mode HUD; particles; touch gestures, virtual joystick/buttons/look pad, gamepad and keyboard; synthesised sound effects and music; physics with ray casts, terrain and a character controller; ECS; deterministic RNG and noise; N-dimensional geometry for 4D+ (rotations in any plane, projection to 3D).
- **Performance**: highest display refresh rate, Android ADPF hint sessions, device tiering, thermal and Game Mode awareness, optional FPS overlay.
- **Multiplayer, two methods only**: local Wi-Fi / hotspot (automatic discovery, TCP + UDP, QR or address fallback) and online WebRTC connected by exchanging an invite and a reply code or QR — no accounts or game servers; STUN/TURN only if you add your own.
- **Dynamic Island**: games publish live activities and events to the card style, the camera island, or both (automatic or per-game placement), on Home and inside games; an optional Android-overlay mode can show compatible activities across apps after explicit permission, with all-app or per-app notification selection.
- **Games**: Runner (endless train-yard runner; split-screen duel, take turns, races with ghost runners), Arena (battle-royale foundation with bots and host-authoritative netcode), Chess (complete rules, 10 AI levels, 3D/2D boards, clocks, pass & play, Wi-Fi/online) and the Engine Lab.

## OEA Systems

- Freezer, a standalone app (own icon and task): Android package suspension with Device Owner/root, otherwise OEA Freeze (blocked in OEA and force-stopped by OEA Assistant behind a progress screen). A granted OEA Assistant that Android paused is reconnected, never re-requested.
- Call blocker using Android's call-screening role with exact/prefix/suffix rules and contact exceptions.
- Phone with full-screen (over the wallpaper), popup and floating-card call screens, Telecom-backed mute/speaker/Bluetooth routing, proximity screen-off and an incoming-call notification with Answer/Decline. Telecom plays the ringtone.
- Split screen that waits for the first app to be in front before launching the adjacent app, verifies the split with OEA Assistant and recovers once through the system split toggle.
- Game Boost runs only while one of your selected games is open. A session starts when the game is opened from OEA (Home, drawer, folders, search, the Game Boost app), when OEA Assistant reports a game window (event driven, no polling), or — optionally — after one Usage access check right after you leave OEA Home. While no game is open there is no Game Boost service, no notification and no Usage access use; during a session it checks every two seconds whether you left the game (or follows OEA Assistant's window events) and pauses while the screen is off. Its draggable floating RAM control toggles one stable panel with a Slow/Medium/Fast network-quality category inside the panel, not under the button.
- Faulty power button alternatives (OEA Settings › Buttons & screen): wave to wake with the proximity sensor (sensitivity, two-wave mode, pocket guard and a live sensor test) and lift to wake on phones that expose a low-power pick-up sensor — sensors are used only while the screen is off; lock screen and Android's power menu through OEA Assistant (Device admin lock as a fallback); Quick controls (volume sliders, sound/vibrate/silent, Do Not Disturb, notification settings) and Lock screen / Quick controls Quick Settings tiles. Android does not let any app switch on a powered-off phone, and OEA does not pretend to.
- Built-in apps (OEA Games, Game Boost, Phone, Call Blocker, Freezer, Themes, OEA Settings) are ordinary launcher entries that can be added to Home like any app.
- Launcher navigation remains integrated with Android's own Home and Recents/Overview surfaces.

## Home and app drawer

- Persistent home pages, dock, folders and drag/drop.
- Widgets own a rectangle of cells: drag them between pages like icons, and resize them with Android-style handles limited to each widget's own min/max size.
- App search and All Apps search.
- Grid, vertical-list and horizontal All Apps layouts.
- Long-press app actions, hiding and app-freezer access.
- Hidden apps as their own space (drawer ⋮ › Hidden apps): open and use hidden apps there, optionally behind the App Lock PIN.
- **Themes** is its own app (own icon and task): ten original built-in themes with coordinated wallpapers, colours, icon shape, dock and glass style and page motion — five use the same picture on Home and the lock screen, five pair two different pictures. Every theme can be previewed on Home and the lock screen (side by side or full screen) before applying, and applying lets you choose wallpapers, colours and style separately and Home, lock or both. Your own themes can be created from your pictures (the same picture for both screens or one each, colours matched to the picture). A wallpaper library holds the bundled pictures and pictures you add; preview them and apply to Home, lock or both, or turn them into a theme. Themes never touch pages, icons, folders, widgets, the grid or other settings.
- Wallpaper-derived launcher theme colors when no OEA theme is applied (Settings › Appearance or "Use device colours" in Themes).
- Liquid glass surfaces (dock, search bar, island card, drawer search) and Home page transitions: Classic, Liquid (pages flow and icons trail like liquid), Cube, Stack, Zoom, Fade or None.
- All Apps keeps its current opaque/plain look by default; optional settings reveal the wallpaper behind All Apps, or behind search (the search area at the top of All Apps, and the whole screen while searching from Home or All Apps) with a contrast scrim that stays readable on any wallpaper, plus frosted window blur where Android enables it.
- Native Android launcher shortcuts.
- Live activities in two styles — the card under the status bar and an expandable island under the front camera — usable alone or together with per-app routing. The island expands by itself when something needs attention (stays open while a call rings; opens briefly for a new message, a new track, a charger or a game event; never reopens for something you closed), shows album art and playback position for media, and shows green/orange camera and microphone dots while any app uses them (on Android 11 and older, where Android has no indicator, it can show them on their own). Home expansions do not resize the grid; an optional, permission-gated overlay can show compatible activities across apps and follows rotation through an overlay window context (notification access is required for app notification content).
- Cold starts show Home immediately from the last app catalogue and persisted icons, then refresh in the background; Home entries are only removed for apps that are really uninstalled.
- The full-screen launcher error page appears only when Home genuinely fails to start; later recoverable errors are logged and the running Home is kept.

## Independence

OEA does not build, run, or import Launcher3/Lawnchair source code. The repository contains the standalone `oea` application module, the OEA-owned `oea-engine` and `oea-games` modules, and OEA-owned launcher code. Online play uses the open-source WebRTC library (data channels only) and ZXing for QR codes; no proprietary SDKs or third-party services are involved.

## Build

`./gradlew :oea:assembleRelease`

Unit tests: `./gradlew :oea-engine:testDebugUnitTest :oea-games:testDebugUnitTest :oea:testDebugUnitTest`

The resulting application ID is `com.oea.launcher`.
