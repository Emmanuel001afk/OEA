# Mini-apps, games and Access Doctor — 2026-10-16

Status: **uncompiled.** This sandbox has no JDK, Android SDK or Gradle, and
services.gradle.org / Google Maven are unreachable. Every change below was
written carefully and checked by reading, not by building. Run
`./gradlew :oea:testDebugUnitTest :oea-games:compileDebugKotlin` and install
a debug build before relying on any of it.

## What changed

**Mini-apps**
- Names: only OEA Settings keeps "OEA". Image → **EMAGE**, VIP (Video Instant
  Player) → **VIP**, Weather, Calculator, Messaging, Notes, Camera, Console,
  Voice lose the prefix.
- **EMAGE** absorbs **Gallery**. An "OEA pictures" tab embeds the old Gallery
  view (wallpapers, lock screens, live scenes). The `OeaGalleryActivity`
  component name is kept as an alias that opens EMAGE, so Home icons pinned
  to it keep working.
- EMAGE pictures open in a **full-screen viewer** (swipe through the album,
  share, edit, favourite, delete). "Wallpaper" hands the picture to Themes.
- EMAGE and VIP handle pictures and videos opened from other apps (`VIEW`,
  `ATTACH_DATA`).
- **Themes**: the "cannot read images" bug was in
  `OeaThemeStore.decodeScaled`. The bounds-only decode returns `null` by design,
  so every import reported "couldn't be read". Fixed. Themes also takes
  `ATTACH_DATA` / `SET_WALLPAPER` pictures from EMAGE or other galleries, and
  uses the system Photo Picker on Android 13+ (no storage permission needed).
- **VIP** plays in a dedicated full-screen player (`OeaVipPlayerActivity`):
  play/pause, ±10 s (buttons and double-tap), seek bar, brightness and media
  volume sliders, rotate (playback continues), screen lock with long-press
  unlock, Picture-in-Picture (also when leaving the app mid-video), audio
  focus handling. An Audio tab lists audio files.
- **Camera** is full-screen: full-bleed viewfinder, a top bar (flash, timer,
  grid, filter, settings), and a bottom band (zoom presets, modes, shutter row).
  Capture logic is unchanged.
- Per-mini-app **on/off** in OEA Settings › Mini-apps. Off disables the
  launcher component through PackageManager (leaves drawer, search, Recents and
  the Home picker; data is kept).
- "Mini-apps" removed from the long-press Home menu.

**Games**
- The "OEA Games" launcher container is gone. Endless Days, Send Off and Chess
  each have their own launcher entry (own icon, label and task). Each opens the
  hub on that game's front menu (`OeaGamesActivity.pinnedGameId`).
- Game Boost still detects them: it reads the engine registry (`oeagame/` keys),
  which did not change.
- **Endless Days**: swipes need 44 dp of travel (was the engine's 26 dp), and a
  runner can change lane at most every 0.22 s, so drifting fingers don't zip
  across lanes. Obstacle generation: trains that run past their chunk no
  longer let hurdles, blocks, coins or power-ups spawn inside them; pattern 2
  and the roll section always leave a lane open; the roll section is now two
  overhead rows, 11 units apart, with the open lane moving between them.

**Access Doctor — Wireless debugging**
- `OeaWirelessPairing` reads, through OEA Assistant (Accessibility), the
  6-digit pairing code and pairing address from "Pair device with pairing
  code", and the connection port from the Wireless debugging screen. Reading
  is only active while the user is watching (Start reading), stops after five
  minutes or once both values are read, and only reads Settings windows.
- Values stay in memory. The pairing code expires after two minutes.
- Typing them in is a fallback button, not the main path.
- Unit tests: `oea/src/test/.../privilege/OeaWirelessPairingTest.kt`.

**Other**
- Floating-window message now states when the manufacturer has freeform off.

## Known gaps (not done)

- **The ADB pairing handshake is not implemented.** The values are read and
  stored; turning them into a paired ADB session needs the SPAKE2 exchange over
  TLS with a keying-material exporter, which the JDK's SSL stack does not
  expose. This is an open design risk, not solved here.
- **Phone call-screen background** (custom wallpaper/picture) is not done.
  It needs a preference, a settings row and a picture picker, and it was not
  verifiable here.
- Split-screen and floating: messaging was improved only for the freeform-off
  case. The OEM-block detection is unchanged.
- The games ship inside the launcher APK: each has its own launcher entry
  (icon, label, task and front menu) next to the other OEA apps.
- The Room database is not touched; no schema migration is needed.
