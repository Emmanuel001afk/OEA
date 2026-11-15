# Three games, three front screens

_2026-11-15_

The three OEA games shipped their own launcher icons, their own modes and their
own save data — and then opened on **one** front screen. Tapping Endless Days,
Send Off or Chess landed you on the same vertical page: the same cover art, the
same title, the same `Play` button, the same "Before you start" list. The only
thing that changed was an accent colour, and all three accents were variations
of orange, so even that barely showed.

That screen also lied in two ways. It offered **shooter aim settings** —
look sensitivity, ADS sensitivity, a turn-speed limit, invert-Y — to a chess app
and a runner, because the check was "does this game render in 3D?". And `Play`
always started a **solo** match, so Chess's pass-and-play and Endless Days'
split-screen duel were unreachable from the icon; you had to go through the hub.

This is the redesign: each game now has a front screen that behaves like its own
platform, in its own materials, with its own settings and its own ways in.

## What each game got

### Send Off — the command deck (landscape)

Send Off is a shooter, and a shooter's front end is the screen you sit on between
matches. It is laid out for a phone held sideways: career and loadout down the
left, war map and squad down the right, one amber **DEPLOY** bar along the bottom.

- **Soldier career** — level, XP-to-next, matches, wins, eliminations, read from
  the match's own save keys (`xp`, `level`, `matches`, `wins`, `kills_total`) and
  turned into a rank (Recruit → Marshal) with an XP progress bar.
- **Loadout** — a soldier silhouette drawn from the saved `CharSpec` (skin, top,
  bottom, helmet glint), the outfit named in words, plus **Randomise** and a
  preset sheet (Operator / Scout / Heavy). Full character editing still happens
  on the live model in-match (Pause › Character studio).
- **Game mode** — all twelve modes as a chip strip in the game's amber signal
  colour, with the selected mode's own description underneath, and the three
  islands as a second strip.
- **Match** — bots, bot skill, weapon pool, zone speed as value rows.
- **Squad** — Wi-Fi/hotspot and online code go to the real lobby flow.
- **Shooter settings** — look/ADS sensitivity, turn limit, invert-Y, ADS
  tap-or-hold (`ads_hold`), and a button sheet that reports how many of the 14
  controls have been moved and resets them (`btn_<key>_x/y/r`).

Material: `#101D35` hull, `#38C8EE` tactical cyan, `#FFB24D` amber visor,
14 dp radii, hairline strokes — the same palette as the launcher icon.

Orientation is locked to landscape **from the icon in**, so the deck no longer
opens in portrait and then flips as the match starts.

### Chess — the club room (portrait, light)

Chess is read, not operated, so its front screen is a room rather than a cockpit:
warm ivory paper, walnut and brass, serif signage.

- **Board centrepiece** — a real 8×8 in the same ivory-and-walnut as the in-game
  2D board, with the starting position oriented to the side you chose; tapping
  it turns the board.
- **Record strip** — wins, games played and win rate, from `played` and
  `wins_level_<n>`.
- **Three ways in**, as tabs: **Vs OEA AI** (a ten-tile strength ladder showing
  the level, its name and a plain-English description, plus your record at that
  level), **Pass & play** (board turning, face-to-face), and **A friend**
  (Wi-Fi/hotspot and online codes).
- **Time controls** as named tiles: Bullet 1+0, Blitz 3+2, Blitz 5+0, Rapid
  10+0, Rapid 15+10, Classical 30+0, no clock.
- **Side** selector, board view (3D/2D) and turning, sound, and access.

No aim, no ADS, no turn limit. Ever.

### Endless Days — the arcade cabinet (portrait)

A runner's front end is a score marquee over a start button, so that is exactly
what it is.

- **Backglass** — best score in large monospaced digits over the night-yard
  art, with coins banked from `total_coins`.
- **One huge RUN bar** under it.
- **Duel on this phone** — split screen or take turns (`duel`), with both
  players' names and colour bars, then straight into `SAME_DEVICE`.
- **Ghost race** — last-runner / 2 km / 5 km, then Wi-Fi or online. Everyone
  runs the identical seeded track.
- **The deck** — swipe left/right, up, down and ramps, drawn as a control panel.
- **Your runner** — colour swatches and name, applied to ghosts and the duel
  header.
- **Cabinet** — sound, music, graphics, access.

Material: `#182132` night slate, `#6BCBFF` rail cyan, `#FF9354` sunset orange,
26 dp radii.

## How it is built

- `GameScheme` / `GameSchemes` (`ui/GameUi.kt`) turn each game's three icon
  colours into a full surface set (ground, panel, well, accent, signal, strokes,
  type). `GameColors.from(scheme)` gives the existing kit that palette, so every
  row, sheet and button is drawn in the game's own materials.
- `GameFront` + `GameFrontHost` (`OeaGameFront.kt`) are the contract: the shell
  hands a front screen its scheme, palette, remembered options, save prefs, the
  match launcher, the friends/lobby flow and the settings sheets. The **layout**
  stays inside each game's class.
- `ArenaFront`, `ChessFront`, `RunnerFront` build the three screens.
  `OeaStandaloneGameActivity.showHome()` now only picks one; a game without its
  own front falls back to a deliberately plain console page.
- New kit primitives: `heroBand` (per-game front-of-box art with a scrim for
  contrast), `deck` (two-column landscape deck with a sticky action bar, which
  collapses to one column in portrait), `statTile`/`statStrip`, `segmented`,
  `chipRow`, `valueRow`, `hscroll`, `prompt`, and a progress bar that sizes from
  its measured width instead of a one-shot `post{}`.
- New art (`GameArt`): a sight picture with range rings, a walnut board with
  pieces, and receding rails under neon — plus the hub's covers in
  `OeaGamesArt.kt` using the same materials.
- The Games hub now draws each card in the game's own palette with the game's own
  verb (`Deploy` / `New game` / `Run`) and its own friend label (`Squad up` /
  `A friend` / `Ghost race`), and its per-game page offers that game's solo and
  same-device modes, not just hosting.
- The hub's engine settings only show the aim block when a shooter is installed.

## Verification

- `python3 tools/check-kotlin.py` — passes; the four new files are now in the
  checker's strict set and its `GameKit` receiver check.
- CI gained assertions that the three front classes, `GameSchemes`, the three
  palettes and the orientation lock stay in the tree.
- The whole `:oea-engine` + `:oea-games` pair and the launcher's
  `com/oea/launcher/games` package were compiled with kotlinc 2.4.21 against an
  Android 36 `android.jar` (stubs only for external libraries: ZXing, WebRTC).
  No errors. The Gradle build in CI remains authoritative.
