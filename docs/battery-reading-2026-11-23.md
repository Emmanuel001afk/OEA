# Battery readings: the percentage OEA shows is the phone's, and it knows about the cable

*Pass over every surface in OEA settings and Game Boost that shows a battery figure, from `f92dff0` on `main`. Line references are to that commit unless noted.*

A battery percentage is the one number on a phone that everyone believes without checking — which makes it the worst place to guess. Before this pass OEA had **six** different readings of the same battery, and three of them could print a number the phone never said:

| Where | Before (`f92dff0`) | What it did |
| --- | --- | --- |
| Game Boost tab, "Battery" tile | `gameboost/OeaGameBoostActivity.kt` l.454-468 | `EXTRA_SCALE` defaulted to **100**, so a device that reports no scale got a plausible-looking lie; `level * 100 / scale` **truncated**, printing 66% for 2/3 and 99% for 995/1000; `CHARGING \| FULL` printed as "charging"; no unknown state |
| In-game status strip | `gameboost/OeaGameBoostService.kt` l.1417-1426 | `BATTERY_PROPERTY_CAPACITY` (answers `Integer.MIN_VALUE` when unsupported) plus `BatteryManager.isCharging`, which is true for a **full** battery — so a phone on a charge limiter read "CHARGING" |
| LOW mode auto switch | `perf/OeaLowMode.kt` l.80-94 vs l.168-190 | `battery()` treated FULL as charging; `observe()` also counted `EXTRA_PLUGGED` — two disagreeing answers for "is the phone on power?" |
| Settings → LOW mode banner | `OeaSystemToolsActivity.kt` l.1049-1084 | printed `"$percent%"` from that reading, with `-1` falling through to a sentence about "the battery level"; the page never updated when you plugged the phone in |
| Console → device details | `miniapps/OeaConsoleApp.kt` l.474-478 | `?: 0` then `if (capacity > 0)` — a phone at **0%** looked like a phone with no battery, and an unsupported property looked like no battery either |
| Dynamic Island | `workspace/OeaDynamicIslandView.kt` l.509-519 | a fourth copy of the parsing, `-1` sentinel rendered as `⚡`, FULL counted as charging |

Only At a Glance (`workspace/OeaGlanceView.kt`) already used `power/OeaBatteryState.kt`, which was correct — but it had no way to say "plugged in, not charging", and nothing else used it.

## What changed

**One arithmetic, tested.** `power/OeaBatteryMath.kt` is pure Kotlin with no Android import: `percent(level, scale)` returns `null` for a missing or non-positive scale instead of defaulting to 100, **rounds** instead of truncating, and clamps a level above the scale to 100; `percentProperty(value)` turns `Integer.MIN_VALUE`, `-1` and `101` into `null`; `description`/`note`/`shortLabel`/`hudCaption`/`label` decide the words, including the em dash for unknown. `OeaBatteryMathTest` (18 tests) pins the cases that used to be visible lies — `2/3 → 67`, `995/1000 → 100`, scale 0 → unknown, `0%` is a real answer and `null` is not, FULL is charging but NOT_CHARGING with a cable in is **"Plugged in, not charging"**, and the in-game caption is `CHARGING` / `PLUGGED` / `BATTERY` from the reading itself.

**One reading.** `power/OeaBatteryState.kt` gains `percent: Int?`, `plugged: Boolean`, `isFull`, `chargingNote`, `label`, `shortLabel` and `text`, and its `read(context)` now asks the sticky `ACTION_BATTERY_CHANGED` first and falls back to `BatteryManager` properties (`BATTERY_PROPERTY_CAPACITY`/`_STATUS`, both validated) only when the broadcast arrives without a usable level or status. `observe(view)` was fixed to work for a view that is **already** in the window — the attach listener alone never fires there, which is how a page could stay painted with an old reading.

**Every surface now asks it.** The Game Boost tile (`OeaGameBoostActivity.updateStats()`), the in-game strip (`OeaGameBoostService`, feeding the caption `hudCaption` returns), the LOW mode helper (`OeaLowMode.battery()` and its receiver), the Settings → LOW mode banner (live: `OeaBatteryState.observe(box)`, repainting only when the reading actually changed), the console's device details, At a Glance and the Dynamic Island. The percentage on screen and the "Charging" beside it now come from the *same* object, so they cannot disagree with each other or with the phone.

Charging has four honest states in the copy: **Charging**, **Full**, **Plugged in, not charging** (cable in, battery holding), and **Discharging**. An unreadable level is `—`, never `0%`, and the settings banner says so in words rather than inventing a number.

## Guards

`.github/workflows/oea-apk.yml`: **Battery reading tests** runs `OeaBatteryMathTest` on the JVM and annotates any failure; **Verify the battery work** fails the build if

* the arithmetic stops being Android-free or loses `percent`/`percentProperty`/`hudCaption`/`NO_PERCENT`;
* `EXTRA_LEVEL`, `EXTRA_SCALE, 100` or `level * 100 /` reappears anywhere outside `power/`;
* `BATTERY_PROPERTY_CAPACITY` is read in `gameboost/`, `perf/` or `miniapps/`, or `BatteryManager.isCharging` anywhere;
* the Game Boost tile, in-game strip, LOW mode helper, settings banner or console stop calling `OeaBatteryState.read(...)`, or the island/LOW mode receiver stop using `OeaBatteryState.from(intent)`;
* the test file drops below 15 tests or loses the scale/charge-limit/unknown cases.

The guard was negative-tested locally: re-adding one `EXTRA_LEVEL` read to `OeaLowMode` makes it fail, removing it makes it pass again.

## What to check on a phone

1. Game Boost → System: the percentage matches Settings → Battery, and the tile caption reads `Charging` while plugged in, `Full` at 100%, nothing on battery.
2. Plug in while that tab is open: the figure and caption change **immediately**, not at the next two-second tick.
3. Settings → System tools → LOW mode: plug and unplug — the banner repaints, and a phone at its charge limit says "plugged in (not charging)" rather than "charging".
4. Start a game with the in-game strip on: caption `CHARGING` / `PLUGGED` / `BATTERY` matches the phone; unplug mid-game and it drops to `BATTERY` without restarting the game.
5. Console → System → device details: the Battery row reads `82% · Charging` (or `— · Status unknown` on a build that reports nothing) — it never disappears because the level is 0.
6. A phone that reports no level at all (some OEM builds in ultra power saving): every surface shows `—`, and the Settings banner explains it instead of printing `-1%`.

## §4 Verification

CI runs on this branch (each one compiles the APK, runs the JVM tests, then the guard steps):

| Run | Result |
| --- | --- |
| `45352328116` (PR #170, first push) | **green** — compiled, `OeaBatteryMathTest` passed, `Verify the battery work` passed |
