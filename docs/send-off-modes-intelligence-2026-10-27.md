# Send Off — game-mode intelligence (2026-10-27)

Investigation of the requested reference modes — Delta Force Warfare, Free Fire's
Lone Wolf / Clash Squad / Blood Strike, PUBG's mode family, Farlight 84, and the
Call of Duty / Free Fire battle royales — and what was built in Send Off from it.

## 1. Reference-mode facts

| Mode / game | Shape |
|---|---|
| **Delta Force Warfare** (Havoc Warfare) | 32v32 (64 players), Battlefield-style combined arms; Attack & Defend (attackers capture sectors with finite tickets, defenders unlimited), King of the Hill, Hill of Iron; vehicles (tanks, helos, jeeps); respawn with loadout, no gear loss |
| **Free Fire Clash Squad (CS)** | 4v4, best-of-7 (first to 4 rounds); buy weapons/items each round with in-match currency (Balance); round ends when a squad is wiped; survivors keep their items |
| **Free Fire Lone Wolf** | 1v1 (or 2v2 duo) duels, Iron Cage arena; best-of-5; both sides pick the weapon for the next two rounds; start with walls, a grenade, 2 medkits |
| **Free Fire Blood Strike** | Free Fire's newer mode family: solo BR + 4-player squad fight (Squad Fight), ranked separately |
| **PUBG modes** | BR (shrinking zone, loot, last standing), plus arena modes (TDM, Gun Game variants); throwables family, attachments |
| **Farlight 84** | 60-player BR with vertical mobility and vehicles; free-to-play competitive shooter |
| **CoD BR / Warzone Mobile** | 100-player royale; **Resurgence** (respawns, Gulag 1v1, cash buy stations); loadouts |
| **Free Fire BR** | 50-player royale; short matches, aggressive loot |

## 2. What was adopted in Send Off

| Send Off mode | Source | Shape |
|---|---|---|
| **Clash Squad** (`cs`) | Free Fire CS | 4v4, best-of-7, first to 4 rounds; buy shop between rounds (cash from kills + round results); deploy starts each round |
| **Lone Wolf** (`wolf`) | Free Fire Lone Wolf | 1v1, best-of-5; weapon pick every 2 rounds; pistol kit + medkits/grenades |
| **Warfare** (`warfare`) | Delta Force Warfare | 6v6 objective play: three capture points, first to 5 captures; respawn with kit; points render as rings + beams and show on the minimap |
| **Resurgence Royale** (`br_res`) | CoD Resurgence | The royale zone closes, but death is a 20-second comeback with the loadout intact; last one standing wins |

Retained: Battle Royale, Team Deathmatch, Gun Game, Last Day / Lights Out / Haunted.

## 3. Implementation notes

- Round flow is sim-authoritative: `roundPhase` (combat → round-over → prep/shop),
  `roundsWon`, `startRound()`; the game drives the dialogs (result → shop → deploy).
- The shop rides the command stream (`buySlot`/`buyMedkits`/… + `deployRound`), so
  buys work in LAN matches; cash and kit sync through snapshots.
- Dead round-makers still shop and deploy: the client's command path was opened to
  non-combat phases (input copy is no longer gated on `alive`).
- Bots shop automatically each prep (`botBuy`), one run per prep.
- Warfare capture logic: standing inside a point fills the bar toward your team;
  a point flips when attackers outnumber holders; holding decays enemy progress.

## 4. Deferred

- Delta Force's vehicles (tanks/helos) — no vehicle system in the engine.
- Attack & Defend tickets / King of the Hill variants (warfare covers the objective feel).
- Blood Strike's separate ranked ladders — ranking systems are out of scope.
- Extraction (Hazard Operations) — a different game shape, not a mode variant.
