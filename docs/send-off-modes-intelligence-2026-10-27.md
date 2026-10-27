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

## 4. Vehicles (Warfare motor pool)

Delta Force's M1A4 / LAV-G1 family and Farlight 84's vehicle system informed this:

| Vehicle | Role | Health | Notes |
|---|---|---|---|
| Jeep | fast transport | 180 | rams enemies at speed, no weapon |
| Tank (M1A4-style) | breakthrough | 420 | 105 mm splash cannon (4.5 m blast), slow |
| Mobile turret | missile support | 240 | fires lock-on homing missiles, nearly stationary |

- Mount with **E** / the MOUNT touch button when standing next to an empty vehicle;
  press again to dismount. The driver's stick steers (moveX) and throttles (moveY).
- Bullets damage vehicles; warheads (rockets, missiles, frags) hit them hardest.
- A destroyed vehicle kills its crew and explodes into a 5 m blast; warfare
  vehicles respawn at their spawn point after 12 s.

## 5. Weapons & how they work

New launcher classes (Farlight 84's Anti-Vehicle Rocket / Homing Cannon, Delta
Force's 105 mm gun-launched missile and ATGMs):

| Weapon | How it works |
|---|---|
| **Rocket launcher** | Fires an unguided rocket at 55 m/s; explodes on contact with a 4.5 m splash (140 dmg falloff). Anti-vehicle tool — bullets glance off tanks, rockets wreck them. |
| **Guided missile** | The "tap to fire, it homes" missile: launch toward an enemy and it steers (up to 45 m/s) onto the nearest living target in front, re-locking if the target dies. 3.5 m splash (120 dmg). |

Both use special ammo (6 per pack), single-shot mags, and appear in the Clash
Squad shop for $2,200 / $2,600.

## 6. Upgrades & enhancements

Clash Squad cash now buys weapon upgrades (up to level 3 each):

| Level | Damage | Mag | Reload |
|---|---|---|---|
| 0 (base) | — | — | — |
| 1 | +10% | +10% | −8% |
| 2 | +20% | +20% | −16% |
| 3 | +30% | +30% | −24% |

Costs: $250 / $500 / $750 per weapon. Upgrades persist through the match and
re-apply to the round kit at each round start. Snapshots sync upgrade levels and
vehicle state for LAN play.

## 7. Movement & terrain fixes

- **Wall sliding**: the character controller now resolves each movement axis
  separately, so a blocked axis still slides along the wall — no push-out
  "clutching" jitter against buildings.
- **Jumpable windows**: house windows are now a 2.7 m opening (0.5 m sill, lintel
  at the wall top) — a vaulting player clears them mid-arc instead of bonking.
- **Terrain**: relief amplitude raised, a mid-frequency micro-relief layer added,
  the height grid refined (116 → 140 cells), and per-vertex colour jitter so the
  ground no longer reads as a flat solid.

## 8. Deferred

- Delta Force's air vehicles (helos/jets) and boats — the vehicle system is ground-only.
- Attack & Defend tickets / King of the Hill variants (warfare covers the objective feel).
- Blood Strike's separate ranked ladders — ranking systems are out of scope.
- Extraction (Hazard Operations) — a different game shape, not a mode variant.
