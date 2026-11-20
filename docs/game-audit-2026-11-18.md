# All in One — game audit, 18 November 2026

Why the cabinets felt wrong, what the real games are, and what changed.

This audit was written after the feedback that "most of the games aren't making
sense — Tic Tac Toe doesn't make sense, Ludo is just stupid". That feedback was
right. The first version of the arcade was designed from the **titles** of the
games, not from their rules. A player who knows Ludo, pool or darts was looking at
a board that did not match the one in their head, so the game read as arbitrary
rather than simplified.

It also found one hard bug: Tic Tac Toe never gave the turn back after the AI
moved. The AI played on by itself, so the player got one move and then watched the
board fill up. That is why the cabinet felt meaningless rather than merely easy.

## The three faults behind almost every complaint

1. **Rules were invented from the title.** Ludo was a circular ring with no yards,
   no home columns, no safe squares and no four-token race. Pool was "pot more
   balls than the opponent", not 8-ball. Snooker used pool rules and added four
   points for a foul. These are not simplifications a player can forgive; they are
   a different game wearing the name.
2. **Levels changed the rules instead of the challenge.** Tic Tac Toe switched to
   a 4x4 board from level 6; darts only enforced double-out from level 5; ping
   pong was first to 7 for two levels and then first to 11. A player cannot learn
   a game when the rule set moves underneath them. Levels should scale the
   opponent, the clock or the target score — never the rules.
3. **No visual grammar.** A Ludo board, a pool table and a darts board are
   recognisable from across a room. The cabinets were drawn as generic game
   surfaces, so even the parts that were right were hard to read.

## What the real games require (sources)

| Game | Canonical features the cabinet must show |
|---|---|
| Ludo | 15x15 cross board, four 6x6 yards, 52-square loop, five-square coloured home column per seat, four-triangle centre, four tokens a side, six to leave the yard, six earns another roll, three sixes lose the turn, capture to the yard, eight safe squares, exact roll to finish. ([Wikipedia](https://en.wikipedia.org/wiki/Ludo), [rules summary](https://pg7.fun/tutorials/ludo-rules-winning-strategies)) |
| Tic Tac Toe | 3x3, X moves first, X and O alternate one mark per turn, three in a row; a perfect game is a draw. ([Wikipedia](https://en.wikipedia.org/wiki/Tic-tac-toe)) |
| 8-ball pool | Open table after the break, groups (solids 1–7 / stripes 9–15) claimed by the first legal pot, 8-ball last, potting the 8 early loses, scratch is a foul. ([WPA rules](https://www.tribilliards.com/information/official-8-ball-rules.html)) |
| Snooker | 15 reds (1 point) and six colours (2–7), red then colour while reds remain, colours respot, final clearance in ascending order, fouls 4–7. ([scoring guide](https://pandataps.com/snooker-scoreboard/guides/how-to-keep-score-in-snooker)) |
| Darts | 501/301, three darts a visit, count down to exactly zero, the last dart must be a double or the bull, bust reverts the whole visit. ([501 rules](https://cleverdarts.com/501-darts-rules/)) |
| Table tennis | Game to 11, win by two, serve changes every two points and every point at 10–10. ([ITTF laws](https://topspin11.com/rules/scoring/)) |
| Archery | Ends of three or six arrows, usually five ends in a round, ten-ring target face, inner ten for recurve. ([World Archery rules](https://extranet.worldarchery.sport/documents/index.php/Rules/Rule_Book_versions/2026-01-27/EN-Book_3_-_2026-01-27_Version.pdf)) |
| Whot | 54-card Nigerian deck, 1 Hold On, 2 Pick Two, 5 Pick Three (common house rule), 8 Suspension, 14 General Market, 20 Whot wild. ([card meanings](https://naijagamearena.com/guides/whot/card-meanings), [street rules](https://whotstreet.vercel.app/)) |
| Klondike solitaire | Seven tableau columns, build down in alternating colours, four foundations Ace–King by suit, draw one or three, only Kings to empty columns. ([rules](https://trysolitaire.com/en/play/solitaire)) |
| The Game of Life | Spinner, career/salary, paydays, house, retirement, richest wins. ([rules](https://www.geekyhobbies.com/the-game-of-life-rules/)) |
| Block Blast | 8x8 grid, three offered shapes, no rotation, full rows and columns clear. ([rules](https://blockblastplay.com/)) |
| Word search | Words run across, down and diagonally, forwards and backwards, sharing letters. ([rules](https://www.puzzlerules.com/wordsearch-rules)) |
| Sliding puzzle | One blank, tiles in order, only a solvable shuffle. ([15-puzzle](https://www.casualgames.dev/play/sliding-puzzle)) |
| Memory | Two cards a turn, a pair scores and keeps the turn, a miss passes it. ([rules](https://dare-play.com/memory/)) |

## Cabinet-by-cabinet verdict

### Fixed in this change

**Ludo — rebuilt.** The old cabinet drew a ring of coloured dots. There were no
yards, no home columns, no safe squares and no capture, so nothing about it
matched Ludo. It is now a real 15x15 cross board: four yards at the corners, the
52-square clockwise track with the four start squares, five home-column squares
per seat, the four-triangle centre, four tokens a side, six to enter, six for a
bonus roll, three sixes losing the turn, captures back to the yard, eight safe
squares and an exact roll to finish. The AI weighs finishing, capture, safety and
progress; Easy adds noise. Two-player is red against green, opposite corners.

**Tic Tac Toe — rebuilt and de-bugged.** The AI never handed the turn back; that
was the actual defect. Turn order is now explicit, one mark per turn, and the
status line says whose move it is. Levels 1–5 stay classic 3x3 (where perfect play
is a draw, as it should be); the 4x4 board from level 6 is labelled "4 in a row"
instead of being passed off as the same game. The board is now a cream plate with
separate rounded cells, a chunky X and O, a gold winning line and a last-move
highlight, instead of thin lines on a dark grid.

**Darts — double-out enforced.** The old code let any dart finish the leg and only
checked busts at level 5 and above. It is now 501/301 with a real double-out: the
dart that reaches zero must be a double or the bull, reaching exactly 1 is a bust
and a bust ends the visit and reverts the score.

**Ping Pong — real scoring.** First to 7 for early levels is gone. Every match is
first to 11, win by two, with serve rotating every two points and every point at
10–10, as the ITTF laws require.

### Matches the real game already

- **Blocks** — 8x8 grid, three offered shapes, no rotation, rows and columns clear
  together. That is Block Blast, and the cabinet is honest about it.
- **Word Finder** — real themes, eight directions from level three and backwards
  words from level five, seeded boards.
- **Whot** — the 54-card deck and the specials (1, 2, 5, 8, 14, 20) are right,
  including the common Nigerian 5 = Pick Three house rule.
- **Solitaire** — Klondike with correct tableau and foundation builds, draw one or
  three, and real shuffling.
- **Tap Match** — pairs, a match keeps the turn, a miss passes it.
- **Slide Puzzle** — shuffled by legal moves, so it is always solvable, with 3x3,
  4x4 and 5x5 grids and a par score.
- **Farm** — an original market simulation rather than a copy of a board game; the
  economy (seed cost, grow days, watering, moving price, weekly rent) is coherent.
- **Manners** and **Interview** — original quiz cabinets with explanations; they do
  not claim to be a specific board game.
- **Chess** — opens the separate OEA chess app; not a canvas cabinet.

### Still to fix (in priority order)

1. **Pool and Snooker.** This is the same class of fault as the old Ludo. Pool is
   currently "pot more balls than the opponent" with no groups and no 8-ball
   finish; Snooker uses the same rules plus a four-point foul, with no reds and
   colours. Both need to be rebuilt around the real rules. This is the next change.
2. **Car Racing.** The cabinet is an endless traffic dodger, not a race: no laps,
   no grid, no opponents racing to a finish. Either add a lap counter and a
   position, or rename it to what it is.
3. **Hide & Seek.** The game is a torch-lit stealth maze with watchers and three
   keys. That is a good game, but it is not hide-and-seek; the name sets the wrong
   expectation. Rename it (for example "The Dark House") and say what it is.
4. **Archery.** Five arrows in a single end is a practice round, not a World
   Archery round. Use five ends of three arrows and score each end.
5. **Life Path.** It is a career-and-life simulation, not The Game of Life's
   spinner-and-board. Keep the simulation but make the description say so.
6. **In-game coaching.** The how-to lists exist on the front screens, but a first
   player should get one short, game-specific line on the canvas — especially for
   aiming controls (darts, pool, archery).

## The rule this audit leaves behind

A cabinet is allowed to simplify a game. It is not allowed to change what the
game is. Levels scale the opponent, the clock, the target score or the board size
where the real game does that; they never switch off a rule. Every cabinet should
be recognisable from the board alone, and the first line of the hint should tell
you the one thing you must do next.

## Files changed

- `oea-games/.../games/LudoGame.kt` — full rewrite.
- `oea-games/.../games/TicTacToeGame.kt` — full rewrite (turn bug fixed).
- `oea-games/.../games/DartsGame.kt` — double-out and bust rules.
- `oea-games/.../games/PingPongGame.kt` — 11-point games and serve rotation.
- this document.
