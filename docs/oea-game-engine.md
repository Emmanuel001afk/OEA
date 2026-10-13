# OEA Game Engine

The OEA Game Engine is a mobile-first game engine that lives inside the OEA
launcher. It runs OEA's own games (Runner, Arena, Chess and the Engine Lab) and
is meant for building new ones. Everything runs on the players' phones: there
are no accounts, game servers, analytics or third-party services.

```
:oea-engine   com.oea.engine     the engine (no launcher code)
:oea-games    com.oea.games      first-party games, written only against the engine's public API
:oea          com.oea.launcher   the launcher; com.oea.launcher.games = Games hub, game activity, island bridge
```

`oea-games` depends on `oea-engine` and sees only its public API (Kotlin
`internal` keeps engine internals out of reach). The launcher depends on both.

## Contents

1. [Architecture](#architecture)
2. [Writing a game](#writing-a-game)
3. [Rendering](#rendering)
4. [Input](#input)
5. [Audio](#audio)
6. [Physics](#physics)
7. [ECS](#ecs)
8. [Math and N-dimensional space](#math-and-n-dimensional-space)
9. [Performance system](#performance-system)
10. [Dynamic Island](#dynamic-island)
11. [Multiplayer](#multiplayer)
12. [The built-in games](#the-built-in-games)
13. [Testing and CI](#testing-and-ci)
14. [Limits and next steps](#limits-and-next-steps)

## Architecture

```
OeaGamesActivity (hub)  ──OeaGameLauncher──▶  OeaGameActivity
   lobbies, invites,                              │ owns
   QR, settings                                   ▼
                                               GameHost  (engine)
                                                  │ creates per game:
        Graphics · Renderer3D · SpriteBatch · Hud · Input · Audio · Haptics
        PerformanceManager · GameIsland · GameStorage · Jobs · GameTime
                                                  │ runs
                                               OeaGame  (your code)
```

### Threads

| Thread | What runs there |
| --- | --- |
| UI thread | Android views, touch/key events (queued), hub and lobby screens |
| Game thread (GL thread) | every `OeaGame` callback, all rendering, all game state |
| `oea-job-*` pool | `ctx.jobs.run { }` background work (AI search, generation) |
| `oea-session` | multiplayer protocol (lobby, routing, clock sync) |
| transport threads | LAN sockets, WebRTC callbacks |

Game code never needs locks: input, network messages and background results
are handed to the game thread once per frame.

### Frame

1. Input events from the UI thread are applied; gestures are detected.
2. Finished background jobs and `runOnGameThread` tasks run.
3. `onFixedUpdate(dt)` runs 0..n times at `fixedStepHz` (default 60 Hz).
   Use it for deterministic simulation and for `ctx.net?.poll { }`.
4. `onUpdate(dt)` runs once (animation, cameras, effects).
5. `onRender(frame)`: any number of world passes, then UI passes.
6. Frame time is reported to the performance system (ADPF, dynamic resolution).

When the app goes to the background the GL thread pauses. A game that returns
`true` from `keepsRunningInBackground()` (for example while hosting a match)
keeps receiving `onFixedUpdate` from a background ticker, under the same lock.

### Robustness

- GPU context loss is invisible to games: meshes, textures, shaders and render
  targets keep CPU-side data and re-upload themselves.
- Exceptions thrown by game code stop the game cleanly (toast + close) instead
  of crashing the launcher process.
- Every GL feature has a fallback: no FBO → direct rendering; no shadow-map
  support → blob shadows; a broken shader logs once and is skipped.

## Writing a game

A game is an `OeaGame` subclass plus a `GameDescriptor`. Register the
descriptor and the hub, solo/same-device launches, lobbies, Wi-Fi/hotspot and
online multiplayer, and the Dynamic Island all work for it automatically.

```kotlin
class Orbit : OeaGame() {
    private val camera = PerspectiveCamera(60f)
    private val ball = Material(color = 0xFFFF7043.toInt(), specular = 0.8f, shininess = 90f)
    private lateinit var ping: Sound
    private var score = 0
    private var angle = 0f

    override fun onCreate() {
        ping = ctx.audio.load("orbit-ping", Sfx.COIN)
        camera.target.set(0f, 1f, 0f)
    }

    override fun onFixedUpdate(dt: Float) {
        angle += dt
        ctx.net?.poll { msg -> if (msg.type == MSG_SCORE) score = msg.reader().varint() }
    }

    override fun onUpdate(dt: Float) {
        for (tap in ctx.input.taps) {
            score++
            ctx.audio.play(ping)
            ctx.haptics.tick()
            ctx.net?.broadcast(MSG_SCORE) { it.varint(score) }
        }
        camera.orbit(angle * 0.3f, 0.4f, 8f)
        ctx.island.update(LiveActivity(title = "Orbit", subtitle = "$score points", trailing = "$score"))
    }

    override fun onRender(frame: Frame) {
        frame.world(camera) { r ->
            r.draw(Mesh.unitSphere, ball, 0f, 1f + sin(angle * 3f) * 0.3f, 0f, scale = 1.5f)
        }
        frame.ui { hud ->
            hud.text("Score $score", hud.width / 2f, hud.safeTop + hud.dp(16f), 24f, align = Align.CENTER, bold = true)
            if (hud.button("quit", "Quit", hud.dp(16f), hud.height - hud.dp(72f), hud.dp(120f), hud.dp(48f))) ctx.ui.exit()
        }
    }

    companion object {
        const val MSG_SCORE = NetSession.FIRST_GAME_TYPE

        val descriptor = GameDescriptor(
            id = "orbit", title = "Orbit", tagline = "Tap to score", description = "A tiny example game.",
            accent = 0xFFFF7043.toInt(), glyph = "●",
            orientation = GameOrientation.PORTRAIT, dimension = GameDimension.D3,
            maxPlayers = 4, modes = setOf(PlayMode.SOLO, PlayMode.LAN, PlayMode.ONLINE),
            options = listOf(GameOption("speed", "Speed", listOf(Choice("1", "Normal"), Choice("2", "Fast")))),
            factory = { Orbit() },
        )
    }
}
```

Register it next to the built-ins (`com.oea.games.OeaGames`) or from any module:

```kotlin
OeaEngine.registry.register(Orbit.descriptor)
```

### Lifecycle

| Callback | When |
| --- | --- |
| `onCreate()` | once, with a live GPU context, before the first frame |
| `onResize(w, h)` | surface size changes (rotation) |
| `onFixedUpdate(dt)` | fixed-rate simulation (`fixedStepHz`) |
| `onUpdate(dt)` | once per frame |
| `onRender(frame)` | once per frame |
| `onPause()` / `onResume()` | app leaves / returns |
| `onBack(): Boolean` | Back pressed; return true if handled |
| `onIslandAction(id)` | a button on the game's island was tapped |
| `onGpuContextRestored()` | only for games issuing raw GL calls |
| `onDestroy()` | the game is closing |

### `ctx` — services

| Property | Purpose |
| --- | --- |
| `graphics` | renderer, sprite batch, fonts, default `Environment`, quality, safe insets |
| `input` | pointers, swipes, taps, keys, gamepad |
| `audio` | sound effects (assets or synthesised recipes) and music |
| `haptics` | tick / click / heavy / double / custom buzz |
| `island` | live activity and events for the Dynamic Island |
| `net` | `NetSession` in LAN/online matches, else `null` |
| `launch` | mode, chosen options, shared seed, local players |
| `storage` | per-game save data, best scores, private files |
| `jobs` | background work with results on the game thread |
| `perf` | frame statistics, render scale, tier, thermal state |
| `settings` | engine-wide player preferences |
| `time` | elapsed, delta, fixed delta, interpolation alpha, frame counters |
| `ui` | toasts, native menus, confirm/prompt sheets, share, exit (callbacks arrive on the game thread) |

### Descriptor and options

`GameDescriptor` declares orientation, dimension, player counts, supported
`PlayMode`s (`SOLO`, `SAME_DEVICE`, `LAN`, `ONLINE`), the label of the
same-device mode, the preferred island style and the options the hub shows.
Option values (from the start sheet, or the host's lobby in multiplayer) arrive
in `ctx.launch.options` / `ctx.net.matchSettings`. In multiplayer the host's
choice and the shared `seed` are identical on every device.

## Rendering

OpenGL ES 3.0, linear lighting with ACES tone mapping, in passes:

```kotlin
override fun onRender(frame: Frame) {
    frame.world(camera, Viewport.FULL, environment) { r -> /* 3D */ }
    frame.world2D(camera2d) { batch -> /* 2D world */ }
    frame.ui { hud -> /* HUD at native resolution */ }
}
```

World passes go through dynamic resolution and post-processing; UI passes are
drawn afterwards at native resolution so text stays sharp. Several world passes
with different `Viewport`s make split screen; `Viewport(rotate180 = true)`
turns a half upside down for a player sitting opposite (the Runner duel).

### Renderer3D

```kotlin
r.draw(mesh, material, transform /* Mat4 */, tint)
r.draw(mesh, material, x, y, z, yaw, scale, tint)
r.draw(mesh, material, x, y, z, yaw, pitch, roll, sx, sy, sz, tint)
r.drawLine(ax, ay, az, bx, by, bz, radius, material)   // tubes: tracers, edges
r.blobShadow(x, y, z, radius, opacity)
r.particles(system)
```

Call `draw` as often as you like: at the end of the pass the renderer culls
against the frustum, renders the sun's shadow map (High/Ultra), groups
identical mesh+material pairs into single **instanced** draw calls, draws the
sky into the remaining pixels and blends transparent objects back to front.

- `Material`: colour, texture, emissive (+strength, feeds bloom), specular,
  shininess, rim light, unlit, transparent, additive, double-sided, shadow
  flags, fog, alpha cutoff, UV scale/offset.
- `Mesh` / `MeshBuilder`: boxes, planes, spheres, cylinders, cones, capsules,
  tori, lathes (surfaces of revolution with crease detection), polygon
  extrusion (ear clipping), merging, transforms, smooth normals.
  `Mesh.unitCube`, `unitSphere`, `unitCylinder`, `unitQuad` are shared.
- `Texture`: from bitmaps, assets, pixel functions or an Android `Canvas`;
  mipmaps and anisotropic filtering. `Textures` has checker, noise, wood,
  windows, soft circles, rings, glows and gradients.
- Cameras: `PerspectiveCamera` (with `orbit`), `OrthographicCamera`,
  `Camera2D`; `screenRay` for picking, `worldToScreen` for labels.
- `Environment`: sun, hemisphere ambient, fog, procedural sky with sun disc,
  exposure, shadows (presets `day`, `sunset`, `night`, `studio`).
- `ParticleSystem`: CPU-simulated, GPU-instanced billboards, additive or
  alpha, velocity-aligned streaks, presets (sparks, dust, smoke, confetti,
  muzzle flash, hits), density scaled by quality.
- Post-processing: dynamic-resolution scene target, FXAA, sharpening when
  upscaling, bloom, vignette and dithering.

### 2D and HUD

`SpriteBatch` (premultiplied alpha, one draw call per texture run): sprites,
rotated sprites, rects, gradients, anti-aliased rounded rects, circles, rings,
arcs, lines, polygons, text. `BitmapFont` bakes a glyph atlas from any
Android `Typeface`.

`Hud` is an immediate-mode toolkit on top: text with shadow, panels, bars,
buttons, icon buttons and invisible hotspots. Widgets report their areas to
`Input`, so a finger that lands on a button never also steers the game.

## Input

- `input.pointers`: every finger with position, start, per-frame delta, held
  time, `justPressed` / `justReleased`, `onUi` and `owner`.
- `input.swipes`: fire the moment the finger travels `swipeThresholdDp`
  (not on release); several swipes per touch are possible.
  `consumeSwipe()` takes one.
- `input.taps`: single and double taps.
- `input.keys`, `input.gamepad`: hardware keyboards and controllers (sticks
  with dead zones, triggers, buttons).
- Virtual controls: `VirtualJoystick` (floating, follows the finger),
  `VirtualButton` (hold, tap, drag-to-aim, cooldown ring), `LookPad`.
  Update buttons first, then the joystick, then the look pad: each claims its
  finger.

## Audio

`ctx.audio.load(name, recipe)` synthesises a sound once into the app cache
(`SfxRecipe`: oscillator, pitch sweep, envelope, noise, filter, vibrato,
layers) and loads it into `SoundPool`; `Sfx` contains ready recipes (coins,
jumps, crashes, gunshots, chess moves, UI...). `loadAsset` plays files.
`play(sound, volume, pan, pitch)` and `play3D(sound, x, y, z, camera)`
(distance attenuation and stereo panning). `playMusic(name, MusicTrack(...))`
composes and loops a drums/bass/lead/pad track. Everything respects the
engine's sound/music/volume settings.

## Physics

`PhysicsWorld` holds static boxes, vertical cylinders and spheres in an XZ
spatial hash, plus an optional `HeightField` terrain:

- `raycast(origin, dir, maxDist, hit, layerMask, ignore)` with hit point,
  normal and collider (`RayHit.terrain` for the height field);
- `lineOfSight`, `query(aabb)`, `groundHeight(x, z, fromY, radius)`.

`CharacterController` is an upright capsule: collide-and-slide in sub-steps,
step-up for small ledges, standing on boxes and terrain, ceilings, jumping.
`Intersect` has ray/AABB, sphere, capsule, vertical cylinder and plane tests.

## ECS

```kotlin
val world = World().addSystem(MovementSystem()).addSystem(LifetimeSystem())
val e = world.create()
world.add(e, Transform(Vec3(0f, 5f, 0f)))
world.add(e, Velocity(gravity = -9.8f))
world.add(e, MeshRenderer(Mesh.unitCube, material))
world.add(e, Lifetime(3f))
world.update(dt)
world.each<Transform, Velocity> { e, t, v -> /* ... */ }
RenderSystem.render(world, renderer)
```

Sparse-set storage, generational entity ids, deferred destruction, prioritised
systems. Components are plain classes.

## Math and N-dimensional space

`Vec2/3/4`, `Quat`, `Mat4` (column-major, GL layout), `Aabb`, `Ray`, `Plane`,
`Frustum`, `MathX` (damping, springs, angles), `Ease`, `Colors`, `Rng`
(deterministic PCG32: identical sequences on every device — the base of seeded
multiplayer worlds) and `Noise` (Perlin, fBm, ridged).

`com.oea.engine.math.nd` is what makes 4D and higher possible without
renderer changes:

- `VecN`, `MatN` with `rotatePlane(a, b, angle)` — N·(N−1)/2 rotation
  planes (4D adds XW, YW, ZW) — and re-orthonormalisation;
- `HyperMesh` and `Polytopes.hypercube / simplex / crossPolytope(dim)`;
- `HyperTransform` (position, rotation, scale in N-D);
- `HyperProjector`: perspective (or orthographic) projection from N to 3D, one
  dimension at a time, returning the depth along the first hidden axis for
  colouring.

A 4D game simulates in `VecN(4)`, projects every frame and draws with the
normal 3D renderer. The Engine Lab's 4D+ page shows 4D–6D polytopes rotating
in any plane.

## Performance system

`PerformanceManager` pushes the device to its maximum and keeps it there:

- **Refresh rate**: picks the display's highest mode at the current
  resolution (or the user's cap), sets `preferredDisplayModeId`, calls
  `Surface.setFrameRate`, and paces lower caps exactly with the EGL swap
  interval.
- **Device tier**: `DeviceProfile` scores RAM, CPU frequency, core count,
  Android media performance class, refresh rate and the GPU (Adreno, Mali,
  Immortalis, Xclipse, PowerVR) into Low / Medium / High / Ultra.
- **Quality**: per tier — dynamic resolution range (Ultra supersamples up to
  1.35×, capped by a pixel budget), FXAA, bloom, shadow maps (1K/2K),
  particle density, detail distance, anisotropy.
- **Dynamic resolution**: lowers the scene scale when frames miss their
  deadline, raises it again (into supersampling) when there is headroom.
- **ADPF**: a `PerformanceHintManager` session for the game thread, fed with
  the real work time of every frame, so the CPU governor ramps correctly.
- **Thermal**: thermal status and headroom cap resolution and drop bloom and
  shadows before the phone throttles hard. Android Game Mode (battery /
  performance) is respected; game state is reported with `GameManager`.
- `settings.showFps` overlays fps, frame time, 1 % lows, render scale, tier,
  draw calls and triangles.

## Dynamic Island

The launcher has two island styles: the **card** under the status bar and the
**camera island** around the front camera. Games support the card only, the
camera island only, or both at once:

```kotlin
ctx.island.update(LiveActivity(
    title = "Chess vs Ada", subtitle = "Your move",
    compactText = "Your move", trailing = "4:32",
    urgent = myTime < 30_000, preferred = IslandStyle.CAMERA,
    actions = listOf(IslandAction("draw", "Offer draw")),
))
ctx.island.event("Opponent: Nf3", "♞")
ctx.island.end()
```

- With one style enabled in OEA Settings, everything uses it.
- With both: each game has a placement (OEA Games › Dynamic Island in games):
  **Automatic** (the activity's `preferred` style, else the descriptor's
  `preferredIsland`), **Card**, **Camera island**, or **Both** (compact status
  around the camera and details in the card at the same time).
- `IslandRouting.resolve` is the single rule used by the engine and launcher.
- On Home the running game's live activity appears with *Resume* (and its
  actions); long-press resumes too. Inside games the same island styles show
  game events, calls (with Answer/Decline) and, optionally, music.
- `update` is cheap to call every frame: only changes are sent, at most 4×/s.

The launcher implements the engine's `IslandHost` (`OeaLauncherIslandHost`);
outside the launcher a game simply runs without an island.

## Multiplayer

Exactly two methods are supported, and a host may mix both in one lobby.

### 1. Local Wi-Fi / hotspot (primary)

- **Discovery**: hosts broadcast UDP beacons (port 47811) on every interface —
  Wi-Fi, the phone's own hotspot, USB tethering — and answer client queries;
  an mDNS/NSD service (`_oeagame._tcp`) is a second path. A Wi-Fi multicast
  lock keeps broadcasts arriving.
- **Transport**: TCP (default port 47812) for reliable ordered messages, UDP on
  the same port for unreliable ones (positions, inputs). Clients learn a UDP
  token from the host; if UDP is blocked, unreliable messages fall back to TCP.
- **Joining**: games appear automatically under *Join nearby*. Otherwise scan
  the host's Wi-Fi QR (`OEAL-…`: addresses, port, session, game; CRC-checked)
  or type the address.

### 2. Online: WebRTC with manual codes and QR

- One `PeerConnection` per remote player with two pre-negotiated data channels:
  `r` (reliable, ordered) and `u` (unordered, no retransmits).
- **No signalling server**: the host's offer becomes an *invite code*, the
  joiner's answer a *reply code*. Codes are `OEA-` + Base32 (letters and digits
  only — survives messengers and auto-capitalisation, and packs densely into a
  QR in alphanumeric mode). Inside: version, kind, protocol, game id and version,
  a session nonce, creation time, player name, the SDP compressed with a
  WebRTC-specific deflate dictionary (a typical SDP becomes ~450 characters),
  and a CRC32.
- **Sharing**: QR on screen, copy, Android share sheet; the receiver pastes,
  scans with the camera, or scans a screenshot. Codes are found inside longer
  messages, case and spacing don't matter.
- **Clear errors** for every failure: not an OEA code, damaged/incomplete,
  invite vs reply mix-ups, wrong game, different OEA version, expired invite,
  reply for a different invite, WebRTC unavailable, and an ICE-failure
  diagnosis (no IPv6 and no STUN/TURN, strict NAT, TURN credentials wrong...).
- **Status**: preparing → finding routes → waiting for the reply → connecting →
  connected (direct on the same network / direct / relayed via your TURN) →
  reconnecting. Failed invites offer *New invite*.
- **Resilience**: after connecting, the reliable channel doubles as the
  signalling path; on network changes (Wi-Fi ↔ mobile data) the host restarts
  ICE over it and new candidates trickle across, so connections survive
  handovers without new codes. Disconnects get a reconnect window.
- **Servers are yours**: online play contacts no server unless the user adds
  their own STUN/TURN (Games › Online connection servers; e.g. coturn).
  CI fails if a public STUN server is ever hardcoded. Without servers, direct
  connections work on the same network and across IPv6 networks.
  *Check online readiness* gathers this phone's routes and explains them.

### Sessions

`NetSession` sits on top of both transports: lobby, ready states, match
settings, start countdown, routing, reconnection and clock sync. Topology is a
star: clients talk to the host, which relays messages addressed to others.

```kotlin
val s = ctx.net ?: return
s.broadcast(MSG_STATE, reliable = false) { w -> w.f32(x).q16(y, 0f, 20f).angle(yaw) }
s.send(playerId, MSG_HIT) { w -> w.u8(damage) }
s.sendToHost(MSG_INPUT, reliable = false) { w -> ... }
s.poll { msg ->
    when (msg.type) {
        MSG_STATE -> { val r = msg.reader(); /* ... */ }
        NetSession.MSG_PLAYER_JOINED, NetSession.MSG_PLAYER_LEFT -> { /* msg.from */ }
        NetSession.MSG_MATCH_ENDED -> ctx.ui.exit()
    }
}
s.now()          // shared clock (ms), synced from low-latency ping samples
s.players        // id, name, colour, host, ready, connected, ping, connection path
s.seed, s.matchSettings, s.startAt
```

Game message types are `NetSession.FIRST_GAME_TYPE` (32) to 255.
`ByteWriter` / `ByteReader` provide fixed ints, varints, zig-zag, strings,
quantised floats and angles. Players who drop can rejoin into their seat (a
stable anonymous player token).

### Patterns (all three are in the built-in games)

- **Turn-based** (Chess): send moves reliably; validate on receipt; include a
  position hash and resynchronise the full game on mismatch or reconnect.
- **Shared seed + state broadcast** (Runner): every device builds the identical
  world from `seed`, simulates its own player, broadcasts state at 15 Hz;
  others are rendered with `SnapshotBuffer` interpolation; the host publishes
  results.
- **Host-authoritative** (Arena): clients send input commands at 30 Hz (each
  packet repeats the last three); the host simulates everything and sends
  snapshots at 20 Hz; clients predict their own movement with the same code and
  reconcile (`InputHistory`); remote players are interpolated; shots are
  resolved on the host with lag compensation (`PositionHistory`).

## The built-in games

### Runner
Endless rail yard: three lanes, swipe to switch/jump/roll, trains (static and
oncoming), hurdles, overhead bars, barriers, ramps onto train roofs, coins
(with magnet pull) and power-ups (magnet, jetpack with sky coins, super jump,
2× score, shield). Seeded chunk patterns always leave a passable lane and get
harder with distance. Procedurally animated robot runner, sunset city with
neon signs, bloom, particles, speed lines and a procedural soundtrack.
Competition: split-screen duel on one phone (face to face), take turns on the
same track, or race over Wi-Fi/hotspot/online (survival, 2 km, 5 km) with ghost
runners and live standings.

### Arena
Battle-royale foundation: skydive and parachute onto a seeded island
(height-field terrain, towns of enterable houses with doors and windows,
containers, crates, rocks, trees), loot pistols, SMGs, rifles, shotguns,
snipers, the pan, ammo, medkits, vests and helmets, survive a shrinking zone.
Twin-stick controls with floating joystick, look area, two fire buttons
(drag to aim while firing), aim-down-sights and scope, jump, crouch, reload,
heal and pick-up; third-person camera with collision; minimap, kill feed, hit
markers, damage numbers and direction. Bots loot, rotate into the zone, and
fight with reaction time, aim error, strafing and bursts (three skill levels).
Networking as described above. Weapons are data-driven (`Weapon` enum).

### Chess
Complete rules (castling, en passant, promotion, check/checkmate/stalemate,
threefold repetition, fifty-move rule, insufficient material, time-out
rules), SAN/FEN/UCI/PGN, verified by perft. Ten AI levels (iterative
deepening alpha-beta, PVS, transposition table, quiescence, null-move, LMR,
killer/history ordering, tapered evaluation). 3D board with turned Staunton
pieces (orbit, pinch zoom, drag and drop, animated moves and captures) or a
classic 2D board drawn from the same piece profiles. Clocks with increment,
hints, undo/takebacks, draw offers, resignation, rematch with colour swap,
pass & play (auto-flip or face to face), PGN sharing, network resync.

### Engine Lab
Live reference of the engine: 2D world, 3D materials/shadows/particles/ECS and
4D–6D polytopes rotating in any plane.

## Testing and CI

`./gradlew :oea-engine:testDebugUnitTest :oea-games:testDebugUnitTest`

- Chess move generation against the standard perft suites (start position,
  Kiwipete, positions 3–5), FEN, SAN, PGN round-trips, mate/stalemate/
  repetition/material rules, the AI finding mate in one.
- Connection codes: round-trips through lowercase, grouping and surrounding
  text; damaged and truncated codes rejected; Base32; binary codec;
  Wi-Fi codes; STUN/TURN parsing.
- Math: matrix inverse, look-at, deterministic RNG, quaternions, polytope
  counts, N-D rotations staying orthonormal.

The APK workflow runs these tests, verifies the engine and game files exist,
and rejects hardcoded public STUN servers.

## Limits and next steps

- The engine targets OpenGL ES 3.0 (every current phone). A Vulkan backend
  can be added behind `Graphics`/`Renderer3D` without changing games.
- Arena characters are built from primitives; skeletal animation and glTF
  loading are natural next steps (`Mesh` already accepts arbitrary vertices).
- Online play without your own STUN/TURN server connects on the same network
  and across IPv6; strict IPv4 carrier NAT needs a TURN server you run.
- Games run in the launcher's process; game exceptions are contained, native
  crashes would restart OEA Home.
