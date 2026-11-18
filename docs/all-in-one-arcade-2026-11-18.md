# All in One — the OEA variety arcade

**Status:** implemented in the working tree, statically checked, not yet compiled or
run here (this workspace has no JDK, Kotlin compiler or Android SDK). Build and
install with the normal Gradle path — `./gradlew :oea:assembleDebug` and
`adb install -r oea/build/outputs/apk/debug/oea-debug.apk` — or
`./gradlew :oea:assembleRelease` for a release build.

All in One is a launcher app of its own (`All in One`, with its own adaptive icon)
that opens on a wall of colourful cabinets modelled on the reference screenshot:
warm paper background, tall poster cards in each game's own colours, badges for
the loud ones (`MOST POPULAR`, `NEW!`), a "keep going" card for the last game
played, category chips, a coin/stars counter and a profile sheet.

Every game is its **own** game: its own front screen with its own levels, its own
records, its own controls, and the same three ways in everywhere.

## The cabinets

| # | Cabinet | Levels | Two players | Notes |
|---|---------|--------|-------------|-------|
| 1 | Car Racing | 12 circuits | – | Perspective road, traffic, three lives; drag to steer; AI difficulty changes rival pace |
| 2 | Life Path | 12 months × 10 levels | – | Choose one of six jobs, twelve monthly dilemmas, a scored job interview halfway, money/health/happiness/skill/respect |
| 3 | Pool | 10 | ✅ pass & play | Real ball physics, six pockets, fouls, ghost-ball aim, AI that searches pocket lines |
| 4 | Snooker | 10 | ✅ pass & play | Same engine, smaller balls, more of them |
| 5 | Darts | 10 | – | Full 20-sector board, doubles/trebles/bulls, 501/301, double-out at higher levels |
| 6 | Archery | 10 | – | Ten rings, wind drift, five arrows, AI grouping by difficulty |
| 7 | Blocks | 10 | – | Falling-block puzzle; level sets speed and garbage |
| 8 | Word Finder | 10 | – | Themed word shelves, diagonals from level 3, backwards from level 5, countdown |
| 9 | Whot | 8 | ✅ pass & play | Full 70-card deck, 1/2/5/8/14 specials, Whot wild with a called shape, AI keeps specials for the endgame |
| 10 | Ludo | 8 | ✅ pass & play | 52-square round track, four tokens each, six to enter, captures, stars, exact home roll, AI |
| 11 | Solitaire | 10 | – | Klondike, tap-to-pick/tap-to-place, draw 1 or draw 3, par-based stars |
| 12 | Tap Match | 8 | ✅ pass & play | Memory pairs, larger grids and more pairs per level |
| 13 | Tic-Tac-Toe | 6 | ✅ pass & play | AI levels go from careless to unbeatable |
| 14 | Hide & Seek | 10 | – | Procedural maze, torch lighting, three keys, watchers that chase on sight, three lives, stickman hero |
| 15 | Sliding Puzzle | 10 | – | 3×3 to 5×5, shuffled by legal moves only, par-based stars |
| 16 | Ping Pong | 8 | ✅ pass & play | Paddle physics, AI reaction and placement by level |
| 17 | Farm | 10 | – | Twelve plots, four crops, a market that moves, rent days, rain and market days, 24-day season |
| 18 | Manners & Etiquette | 4 | – | Courtesy, the table, the phone, the shared office; every answer explains itself |
| 19 | Job Interview | 4 | – | Real interview questions, CV/presentation/deadline scenarios, salary conversation, panel |
| 20 | Chess | existing app | ✅ | Opens OEA's own Chess app (`externalApp = "chess"`), with its clocks, ten AI levels and pass & play |

(Chess is listed as the twentieth entry because it is the one cabinet that hands
off to an existing OEA app instead of running its own engine.)

## Modes, levels and difficulty — everywhere

Each cabinet's front screen offers the same three doors:

1. **Vs OEA AI** — the cabinet's own levels (above) plus the shared difficulty
   ladder (`Easy 0.32` … `Expert 1.0`). Difficulty is used as accuracy, reaction
   time, opponent search depth or AI error, not as a label.
2. **Two players on this phone** — pass-and-play. Card games show only the active
   player's hand; turn-based games name whose turn it is; real-time games split
   the screen or alternate rounds.
3. **A friend on another phone** — the arcade's single lobby.

Progress is per device: highest level unlocked, stars per level, best score,
coins, recent games. A level is only marked cleared when it is *won*; a loss
stores the score and the play count but never unlocks the next level. Resetting
clears only All in One's own data.

## Host and join — one path for all games

`ArcadeLobby` is shared by every cabinet, so "host" cannot work in one game and
silently fail in the next:

- **Host on Wi-Fi / hotspot** opens the real `NetSession` host, advertises the
  room with `LanBeacon`, and shows a join code that still works when discovery is
  blocked.
- **Join nearby** runs `LanBrowser`, lists only compatible, open rooms, and can
  join by the host's IP if discovery fails.
- **Join with a code** accepts a Wi-Fi join code or an online invite/reply pair.
- **Host online with a code** uses the engine's WebRTC invite/reply flow.
- The host only starts when someone has actually joined, and both phones receive
  the same `game`, `level`, `difficulty` and seed. Scores are exchanged as
  `SCORE_TYPE = 40` messages, and the result card compares them ("you lead",
  "ahead", "level"), with the same board guaranteed by the shared seed.

Previous sessions are closed before a new host starts, the LAN browser is
stopped while connected, and addresses are refreshed explicitly — the three
failure modes that used to leave a lobby silent.

## Design and controls

- **Canvas-first.** Every cabinet draws in proportional coordinates into one
  `ArcadeGameView`; the same game fills a small phone, a tall phone or a tablet.
- **Code-drawn art.** Poster tiles and in-game scenery are drawn with
  `ArcadeDraw`; no bitmaps to keep in sync.
- **Stickmen where people are needed, designed figures where they work.** The
  Hide & Seek hero, the Life Path poster and the goal celebrations use stickmen;
  balls, cards, dice, boards and crops are drawn properly.
- **HUD.** The shared activity draws title, level, difficulty, score and pause.
  Pause offers resume, restart, difficulty, how-to, level picker and leave.

## Files

- `oea-games/src/main/java/com/oea/games/allinone/` — arcade shell:
  `ArcadeCore.kt` (catalogue + contracts), `ArcadeDraw.kt` (canvas kit + tile art),
  `ArcadeProgress.kt`, `ArcadeGameView.kt`, `ArcadeFrontScreen.kt`,
  `ArcadeHubScreen.kt`, `ArcadeLobby.kt`, `ArcadeUi.kt`, `ArcadeGameActivity.kt`.
- `.../allinone/games/` — the nineteen cabinet engines (`RacingGame.kt`,
  `LifePathGame.kt`, `FarmGame.kt`, `PoolGame.kt`, `DartsGame.kt`,
  `ArcheryGame.kt`, `BlocksGame.kt`, `WordFinderGame.kt`, `WhotGame.kt`,
  `LudoGame.kt`, `SolitaireGame.kt`, `MemoryGame.kt`, `TicTacToeGame.kt`,
  `HideSeekGame.kt`, `SlidingGame.kt`, `PingPongGame.kt`, `QuizGame.kt` with
  `track = 0` for manners and `track = 1` for the interview).
- `oea/src/main/java/com/oea/launcher/games/OeaAllInOneActivity.kt` — the app's
  launcher activity and hub host (opens cabinets, opens the shared lobby, profile
  sheet: difficulty, motion, per-cabinet progress, reset).
- `oea/src/main/res/drawable/ic_oea_allinone{,_bg,_fg,_mono}.xml` — the app's own
  adaptive icon (four cabinet tiles on a teal field, with a monochrome layer).
- `oea/src/main/AndroidManifest.xml` — `All in One` launcher activity and the
  internal `ArcadeGameActivity` in the same task, so Back returns to the wall.

## Verification done here

- `python3 tools/check-kotlin.py` → `STATIC CHECK OK — 294 sources: brackets
  balanced, kit calls resolve, OEA symbols declared, manifests consistent`.
- A scan for Int-literal overflow (`0x…` above `Int.MAX_VALUE` used where an
  `Int` is expected) over every new arcade file.
- Manual review of catalogue/factory wiring: every cabinet id has a `create`
  factory or a valid external app, and every level count matches its level-name
  list.

## Known limits / next device pass

- No compiler in this sandbox, so this must be built once on a machine with the
  Android SDK; expect small compile fixes on the first run.
- The AI opponents are heuristics tuned by the difficulty ladder, not search
  engines (except the pool ghost-ball search); they are honest but beatable.
- Farm, Life Path, quiz and solitaire have no two-player mode; they support the
  shared-seed friend challenge instead.
- There are no ads and no purchases, so the reference's "REMOVE ADS" button is
  represented by the profile/stars counter instead.
