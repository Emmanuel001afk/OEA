# OEA Launcher

OEA is an independent Android launcher and Android-skin layer built around its own engine and workspace.

## Architecture

The active launcher path is:

`OeaLauncherActivity → OeaHomeController → OeaRuntime → OeaLauncherEngine → OeaWorkspace`

OEA owns the home surface, app model, workspace persistence, drawer, dock, folders, shortcuts, gestures, themes, and system-feature integrations. The engine is initialized lazily, loads the app catalogue away from the UI thread, retains the last good workspace on failure, and publishes its state for future engine-owned features.

See [Launcher engine and live activities](docs/launcher-engine-and-live-activities.md) for the lifecycle boundaries, Dynamic Island-style notification design, platform constraints, and verification checklist, [EMAGE platform integration](docs/emage-platform-integration-2026-10-20.md) for the image-app identity, Android 14 access model, picker/share contracts and device verification checklist, [Messaging, SIM details and EMAGE zoom](docs/messaging-sim-details-and-emage-zoom-2026-10-22.md) for the conversation UI, per-message SIM/network details, the working pinch zoom and one-section-per-month grouping, and [Stability, permissions and wallpapers](docs/stability-and-privileges.md) for the fault shield, the capability system and the wallpaper/theme engine. [Launch speed and default Home](docs/launch-speed-and-default-home-investigation-2026-10-22.md) traces the tap-to-app pipeline, what OEA does before Home's first frame on a cold start, and the four-step path that puts the default-Home choice in front of you. [Camera infrastructure review](docs/camera-infrastructure-review-2026-10-22.md) compares the camera against the engine's own Camera2 code and the rest of the launcher, and records what was broken (sideways preview, screenshot stills, leaking Pro exposure, an external QR client that no phone has, a widget that promised controls it never drew, five torches that disagreed), what changed, how it was verified (CI build plus JVM tests for the camera maths and the torch decision), and what is still limited.

Modules:

- `oea` — the launcher (`com.oea.launcher`), including the games hub that sits behind the game entries **and the three first-party games themselves**: Endless Days, Send Off and Chess each have their own launcher entry (own icon, label, task and front menu — Play, Match setup, Play with friends, controls & aim where a game uses them, gesture legend, About), their own remembered settings and their own multiplayer, exactly like Game Boost, Phone or Call Blocker have their own identity inside OEA. The game code lives in `oea-games`; the entries in `com.oea.launcher.games.OeaGameEntries` are the games' faces. **OEA builds exactly one APK** — the games are inside it, not separate installs.
- `oea-engine` — the OEA Game Engine (`com.oea.engine`), with no launcher dependency.
- `oea-games` — first-party games (`com.oea.games`), written only against the engine's public API, plus `com.oea.games.ui` (the games' own copy of the launcher's visual language, so the games look like OEA).

## OEA Games and the OEA Game Engine

OEA ships its own game engine and games. **Send Off, Chess and Endless Days live inside the launcher as their own apps** (the package and protocol ids stay stable for existing installs): tapping a game's icon opens a real game with its own front menu (Play, Match setup, Play with friends, the aim controls where a game uses them and each game's own gesture legend, About), its own multiplayer (host on Wi-Fi/hotspot or with an online code, join nearby or with a code) and its own settings. Send Off also exposes a live **Character Studio** and a shared physics-driven VFX pipeline: modular face/body/clothing specs apply to the mesh in real time, fluid gas carries smoke/fog/fire around terrain and walls, sparks collide, weather changes the character and dynamic point lights reach skin and clothing. Each game is its own activity inside OEA; Game Boost finds all three through the engine registry, and its list of game screens is generated from those classes so the two cannot drift apart. The shared games hub sits behind these entries. Hub-hosted networked lobbies keep running in the hub process. See [OEA Game Engine](docs/oea-game-engine.md) for the architecture, API, multiplayer protocol and a step-by-step guide to writing new games, and [Games independent, Send Off competitive](docs/games-independent-and-competitive-2026-10-16.md) for the game branding and structure.

- **Engine**: OpenGL ES 3 renderer with automatic instancing, frustum culling, sun shadow maps, procedural sky, bloom, FXAA, dynamic resolution and supersampling; sprites, fonts and an immediate-mode HUD; particles; touch gestures, virtual joystick/buttons/look pad, gamepad and keyboard; synthesised sound effects and music; physics with ray casts, terrain and a character controller; ECS; deterministic RNG and noise; N-dimensional geometry for 4D+ (rotations in any plane, projection to 3D).
- **Performance**: highest display refresh rate, Android ADPF hint sessions, device tiering, thermal and Game Mode awareness, optional FPS overlay.
- **Multiplayer, two methods only**: local Wi-Fi / hotspot (automatic discovery, TCP + UDP, QR or address fallback) and online WebRTC connected by exchanging an invite and a reply code or QR — no accounts or game servers; STUN/TURN only if you add your own.
- **Dynamic Island**: games publish live activities and events to the card style, the camera island, or both (automatic or per-game placement), on Home and inside games; an optional Android-overlay mode can show compatible activities across apps after explicit permission, with all-app or per-app notification selection.
- **Games**: Endless Days (endless train-yard runner; split-screen duel, take turns, races with ghost runners), Send Off (competitive shooter: Battle Royale with a shrinking zone, Team Deathmatch with respawns and team scores, Gun Game with a weapon ladder and a pan finish, and three survival rules — Last Day, Lights Out and Haunted; nine weapons including revolvers, marksman rifles and LMGs with per-weapon handling; three real island levels — Green Island, snow-capped Highlands and red-ridge Badlands — with roads, watchtowers and per-level palettes; human characters with faces, hair, body types and gear; **Character Studio** with live facial sliders, body proportions, gender/build, skin/hair, texture-backed layered outfits and saved presets; physics VFX with a fluid grid for smoke/fog/fire, terrain-colliding particles, weather-wet/dusty clothing, wind-blown hair and dynamic point lights on skin/terrain; XP levels; look/ADS sensitivity, invert-Y and a turn-speed limit that keeps the camera glitch-free; host-authoritative netcode), Chess (complete rules, 10 AI levels, 3D/2D boards, clocks, pass & play, Wi-Fi/online) and the Engine Lab.

## OEA Systems

- Freezer, a standalone app (own icon and task): Android package suspension with Device Owner/root, otherwise OEA Freeze (blocked in OEA and force-stopped by OEA Assistant behind a progress screen). A granted OEA Assistant that Android paused is reconnected, never re-requested.
- Call blocker using Android's call-screening role with exact/prefix/suffix rules and contact exceptions.
- Phone with full-screen (over the wallpaper), popup and floating-card call screens, Telecom-backed mute/speaker/Bluetooth routing, proximity screen-off and an incoming-call notification with Answer/Decline. Telecom plays the ringtone.
- Split screen that waits for the first app to be in front before launching the adjacent app, verifies the split with OEA Assistant and recovers once through the system split toggle.
- Game Boost runs only while one of your selected games is open. A session starts when the game is opened from OEA (Home, drawer, folders, search, the Game Boost app), when OEA Assistant reports a game window (event driven, no polling), or — optionally — after one Usage access check right after you leave OEA Home. While no game is open there is no Game Boost service, no notification and no Usage access use; during a session it checks every two seconds whether you left the game (or follows OEA Assistant's window events) and pauses while the screen is off. Its draggable floating RAM control toggles one stable panel with a Slow/Medium/Fast network-quality category inside the panel, not under the button.
- Faulty power button alternatives (OEA Settings › Buttons & screen): wave to wake with the proximity sensor (sensitivity, two-wave mode, pocket guard and a live sensor test) and lift to wake on phones that expose a low-power pick-up sensor — sensors are used only while the screen is off; lock screen and Android's power menu through OEA Assistant (Device admin lock as a fallback); Quick controls (volume sliders, sound/vibrate/silent, Do Not Disturb, notification settings) and Lock screen / Quick controls Quick Settings tiles. Android does not let any app switch on a powered-off phone, and OEA does not pretend to.
- Built-in apps are real, independently launchable entries with stable task identities, branded round icons and explicit Android intent/permission handling: Endless Days, Send Off, Chess, Game Boost, Phone, Call Blocker, Freezer, Themes, OEA Settings, Clock, Calendar (with real .ics backup and import), Camera (night, portrait and face-blur passes that change the pixels), EMAGE (photos, albums and the OEA pictures; replaces Image and Gallery; pinch/double-tap/button zoom and one section per past month), VIP (Video Instant Player: video and audio, full-screen, rotation, screen lock, PIP, brightness and volume, ±10 s seek, subtitles, equaliser, background audio, a queue and a watch-together sheet), Calculator, Messaging (conversation list and chat screen with drafts, live updates, dual-SIM sending, and long-press message details naming the SIM and network each SMS used), Notes, Voice and Console. Only OEA Settings keeps the "OEA" prefix. Each mini-app can be turned on or off in OEA Settings › Mini-apps. Calendar can merge the device Calendar Provider after permission; EMAGE/VIP request device media access; Camera requests camera access; Messaging exposes the default-SMS role flow; Console reports real hardware/device info and accessibility/overlay/role state. Themes can import/export a complete `.oea-theme.json` with embedded pictures to any Android document/upload destination.
- Launcher navigation remains integrated with Android's own Home and Recents/Overview surfaces.

## Home and app drawer

- Persistent home pages, dock, folders and drag/drop.
- Widgets own a rectangle of cells: drag them between pages like icons, and resize them with Android-style handles limited to each widget's own min/max size.
- App search and All Apps search.
- Grid, vertical-list and horizontal All Apps layouts.
- Long-press app actions, hiding and app-freezer access.
- Hidden apps as their own space (drawer ⋮ › Hidden apps): open and use hidden apps there, optionally behind the App Lock PIN.
- **Themes** is its own app (own icon and task): ten original built-in themes with coordinated wallpapers, colours, icon shape, dock and glass style and page motion — five use the same picture on Home and the lock screen, five pair two different pictures. Every theme can be previewed on Home and the lock screen (side by side or full screen) before applying, and applying lets you choose wallpapers, colours and style separately and Home, lock or both. Your own themes can be created from your pictures (the same picture for both screens or one each, colours matched to the picture). A wallpaper library holds the bundled pictures and pictures you add; preview them and apply to Home, lock or both, or turn them into a theme. Themes never touch pages, icons, folders, widgets, the grid or other settings.
- Ten more wallpapers are drawn in code (mountain dawn, forest river, desert coast, dusk city, aurora, night sky, ocean, terrazzo, linen, deep mesh) and applied from **Settings › Appearance › OEA wallpapers** to Home, the lock screen or both, with a dim control, live previews and a theme that takes its accent and Home text colour from the scene.
- Wallpaper-derived launcher theme colors when no OEA theme is applied (Settings › Appearance or "Use device colours" in Themes).
- Liquid glass surfaces (dock, search bar, island card, drawer search) and Home page transitions: Classic, Liquid (pages flow and icons trail like liquid), Cube, Stack, Zoom, Fade or None.
- All Apps keeps its current opaque/plain look by default; optional settings reveal the wallpaper behind All Apps, or behind search (the search area at the top of All Apps, and the whole screen while searching from Home or All Apps) with a contrast scrim that stays readable on any wallpaper, plus frosted window blur where Android enables it.
- Native Android launcher shortcuts.
- Live activities in two styles — the card under the status bar and an expandable island under the front camera — usable alone or together with per-app routing. The island expands by itself when something needs attention (stays open while a call rings; opens briefly for a new message, a new track, a charger or a game event; never reopens for something you closed), shows album art and playback position for media, and shows green/orange camera and microphone dots while any app uses them (on Android 11 and older, where Android has no indicator, it can show them on their own). Home expansions do not resize the grid; an optional, permission-gated overlay can show compatible activities across apps and follows rotation through an overlay window context (notification access is required for app notification content).
- Cold starts show Home immediately from the last app catalogue and persisted icons, then refresh in the background; Home entries are only removed for apps that are really uninstalled.
- The full-screen launcher error page appears only when Home genuinely fails to start; later recoverable errors are logged and the running Home is kept.

## Stability and permissions

- A fault in any OEA part — a game, a widget provider, a socket or audio thread, an accessibility or notification callback — is contained: the failing subsystem stops, Home keeps running, and the problem is recorded. Only a `VirtualMachineError` on the UI thread, or Home failing repeatedly *in safe mode*, is handed back to Android.
- Games fail politely: the game screen explains what happened and offers the fixes that match the diagnosis (lower quality, reset that game's saved data, leave a CPU core free, or open the standalone game APK). Two faults in two minutes mark the game as unstable, and the hub then leads with the repair.
- Home rebuilds itself from the saved layout after a fault, and a *stall watchdog* records a blocked UI thread with its stack instead of letting Android show "isn't responding". Repeated problems start **safe mode** (Home without widgets, At a Glance and live activities) with a card that explains it and a way out.
- **Settings › Stability** lists every contained fault, what OEA did about it, whether a problem log is kept, and offers rebuild/restart. Nothing is ever sent anywhere.
- Permissions are handled as capabilities: each one shows what it unlocks and what OEA does without it. OEA opens the exact Android page, verifies the result, and distinguishes "off" from "switched on but not bound yet" — so it never asks twice for the same thing.
- No helper app and no PC: OEA grants by itself whatever Android allows (device owner via `DevicePolicyManager`, or root through its own `su` channel, scoped to OEA's own package and always verified), and falls back to a Home-side or notification presentation otherwise. "Display over other apps" and "Usage access" are the user's to give on a stock device — no third-party app can change that, and OEA says so plainly instead of pretending.
- Bundled wallpapers are drawn in code at the phone's resolution, applied through Android's wallpaper service (Home, lock screen or both), with a dim control, live previews, and a theme that takes its accent and Home text colour from the scene — and stops following it when another app changes the wallpaper.

## Independence

OEA does not build, run, or import Launcher3/Lawnchair source code. The repository contains the standalone `oea` application module, the OEA-owned `oea-engine` and `oea-games` modules, and OEA-owned launcher code. Online play uses the open-source WebRTC library (data channels only) and ZXing for QR codes; no proprietary SDKs or third-party services are involved.

## Build

`./gradlew :oea:assembleRelease`

One APK: the launcher **and** Endless Days, Send Off and Chess (each with its own icon and identity inside it).

Unit tests: `./gradlew :oea-engine:testDebugUnitTest :oea-games:testDebugUnitTest :oea:testDebugUnitTest`
(the launcher's tests cover the stability rules in `OeaFaultPolicy`, the wave detector and the theme catalogue, which are pure Kotlin)

Static check: `python3 tools/check-kotlin.py` — bracket balance over a real Kotlin lexer, toolkit call resolution, OEA symbol resolution, and the registration invariants that a compiler cannot see (a mini-app or game listed in Kotlin but missing from the manifest, a game entry Game Boost does not recognise). It runs as the first step of both APK workflows.

The resulting application ID is `com.oea.launcher`.

Release builds run R8 (`minifyEnabled` + `shrinkResources`) with the rules in `oea/proguard-rules.pro` and `gradle/proguard-game.pro`, ship only the `arm64-v8a` and `armeabi-v7a` native libraries, and store native libraries compressed. Every CI run prints the resulting APK sizes in the workflow summary. See [Independent games, same polish, smaller APKs](docs/games-independent-polish-and-apk-size-2026-10-17.md).

### Updating without losing your Home

Android only installs an update over an existing app when both are signed with the same key. GitHub's build machines create a new random debug key for every run, so CI-built APKs can't update each other in place, and uninstalling first erases OEA's layout and settings. To make every build use one stable key, create a keystore once and store it as a repository secret (it never appears in the repository):

1. `keytool -genkeypair -keystore oea-debug.keystore -alias androiddebugkey -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=OEA Debug,O=OEA,C=US"`
2. `base64 -w0 oea-debug.keystore` and copy the output.
3. GitHub › Settings › Secrets and variables › Actions › New repository secret: name `OEA_DEBUG_KEYSTORE_BASE64`, value: the copied text.

Both APK workflows then sign with it. The first install after this change needs one last uninstall (the installed OEA was signed with an old random key); after that, updates install in place.
