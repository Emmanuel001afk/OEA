# Send Off — data-driven rules and human-like bots

This pass makes the match rules readable as data and gives bots memory,
objectives and distinct personalities instead of single-purpose aiming code.

## Rules engine

`oea-games/src/main/java/com/oea/games/arena/ArenaRules.kt` is now the single
source of truth for every mode:

| Field | Meaning |
| --- | --- |
| `objective` | `LAST_ALIVE`, `SCORE`, `LADDER`, `ROUNDS`, `CAPTURE`, `HILL`, `TICKETS`, `SURVIVE` |
| `scoreLimit` | Team score, captures, hill points, defender tickets or round wins |
| `timeLimit` | Overall match clock; `0` means the objective decides |
| `roundTime` | Per-round clock for Clash Squad / Lone Wolf |
| `respawn`, `teams`, `roundBased`, `survival` | Existing simulation flags, now declared once |
| `respawnDelay` | Respawn wait, shared by Resurgence and team respawns |
| `captureDrain` | Attack & Defend: tickets each captured sector drains |
| `hillRate` | King of the Hill: points per second while holding the hill |

`ArenaSim` reads `modeRules` for TDM's 40 kills, Gun Game's ladder length,
Clash Squad / Lone Wolf round targets, Attack & Defend tickets and the HUD
clock. No scattered score literals remain in the win checks.

### New clock behaviour

- Every timed mode shows a `MATCH m:ss` clock and ends when it expires. A team
  objective reached early still wins immediately.
- Round modes show a `ROUND m:ss` clock. When it expires, alive count decides;
  remaining health + armour is the tiebreak.
- Winner selection at time-out uses the mode's real score (team kills,
  captures, hill points, round wins or Gun Game stage), then kills as the
  fallback. Defenders win an expired Attack & Defend match.
- `finishRound()` and `finishOnTime()` centralise payout, round advance and
  match end so the multiple win paths cannot drift apart.
- Attack & Defend no longer keeps its own clock: `adTimer` mirrors
  `matchTimeLeft`, so the HUD countdown and the round-ending rule are literally
  the same number. At zero, the defenders take the objective.
- Team respawns are staggered by up to 1.5 s so a wiped squad does not arrive
  in one stacked pile.
- Clocks are sent in the network snapshot, so clients display the host's
  remaining time instead of a locally invented countdown.
- The lobby's mode list is generated from `ArenaRules.all`; the pause-screen
  briefing appends the match/round clocks from the same table.

## Human-like bot logic

`BotBrain` in `ArenaSim.kt` was upgraded with:

- **Personality**: balanced, aggressive or cautious, derived from bot id and
  skill. It changes engagement range, burst length, strafe timing, crouching
  and whether the bot retreats when hurt.
- **Memory**: an enemy seen or heard is followed to the last known position
  for 4.5–6 seconds. Bots no longer fire through walls or track enemies they
  cannot perceive.
- **Callout priorities**: wounded enemies and players recently hit by a
  teammate are preferred targets, like a human calling "one shot" or "crossing".
- **Objective play**: Warfare bots head for the objective that most needs
  their team instead of roaming towns.
- **Retreat and heal**: wounded bots break contact, smoke themselves off and
  heal only once the shooter has lost the trail.
- **Grenade discipline**: frag grenades are cooked before being thrown at
  cover, smokes are saved for bad trades, stuns are used on close pushes, with
  a cooldown so bots do not empty their pack in one second.
- **Lead shots**: moving targets are led slightly, scaled by distance and skill.
- **Weapon-aware movement**: snipers strafe less; SMG bots close distance;
  artillery bots keep their range.
- **Objective discipline**: Warfare and King of the Hill bots commit to the
  point that needs them, then hold its edge crouched instead of running laps
  around the island.
- **Zone sense**: a bot caught in the gas runs for the circle and only shoots
  back when it has a line — no more duelling inside the storm.
- **Flashed behaviour**: stunned bots back off and spray blind rather than
  snapping onto target.
- **Reload discipline**: an empty mag means breaking line of fire and ducking
  out, not standing still in the open.
- **Shop logic**: round-shop bots buy armour first if broke, then a weapon for
  their role (aggressive = SMG/shotgun, cautious = sniper/marksman), and only
  then utility.
- **Skill spread**: each bot rolls ±0.08 around the difficulty setting, so a
  lobby has aimers and sprayers rather than nine clones.
- **No pre-aim through walls**: bots pre-aim the last heard position and hold
  the angle, but cannot fire on information they do not have.

## Files changed

- `oea-games/src/main/java/com/oea/games/arena/ArenaRules.kt` — new rules table.
- `oea-games/src/main/java/com/oea/games/arena/ArenaSim.kt` — clock/tiebreak/round helpers and bot upgrades.
- `oea-games/src/main/java/com/oea/games/arena/OeaArena.kt` — live match/round clocks in the mode card, clock sync, generated mode list.
- `tools/check-rules.py` — no-JVM consistency check: every exposed mode has a
  rule entry, every mode has a win condition, and the HUD contains no stale
  hardcoded limits.
- `tools/tripo/preview_rules.py` and `tools/tripo/concepts/rules-preview.png` —
  design sheet generated by parsing `ArenaRules.kt`.

## Validation and limits

- `python3 tools/check-kotlin.py` passes (305 sources).
- `python3 tools/check-rules.py` → `RULES OK — 12 modes, 12 exposed in the lobby`.
- No JDK / Android SDK is available here, so the changes are not device-tested.
- Bot memory is per-bot and local to the authoritative sim; network clients
  receive inputs/snapshots as before.
- The round clock decided by health is a deliberate anti-stalemate rule, not a
  simulation of a referee; if both teams hide it simply ends the round.
