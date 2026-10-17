# Independent games, same polish as the launcher, smaller APKs — 2026-10-17

This is the answer to *"look into the history and find how it all went wrong"*
for OEA's games, plus the polish pass over the in-launcher apps and the APK
size work.

**Verification status (read this first).** This sandbox has no JDK, no Android
SDK and no Maven access, so nothing here is compile- or device-verified — the
same limitation the previous sessions recorded. Everything was checked with
`tools/check-kotlin.py` (new: a real Kotlin lexer for bracket balance, toolkit
call resolution, OEA symbol resolution, and manifest/registration
consistency), and CI compiles, tests and builds all four APKs before anything
merges. Device confirmation of feel and of Game Boost timing is still required.

---

## 1. How it went wrong

The trail is in the PR history, not in the code.

| PR | Title | State | What it carried |
| --- | --- | --- | --- |
| #45 | OEA Game Engine + OEA Games (Runner, Arena, Chess) | merged 10-13 | Games as an in-launcher hub |
| #57 | Build independent games and polish OEA systems | merged 10-16 | The three standalone app modules |
| #59 | EMAGE merge, VIP player, per-mini-app switches, **standalone games** (UNCOMPILED) | merged 10-16 | `OeaStandaloneGameActivity` |
| **#60** | **Launch built-in games through their standalone activity shell** | **draft, never merged** | **the fix that went missing** |
| #63 | Wireless ADB pairing end to end; games open as true standalone apps | merged 10-16 | Entries became the standalone shell |
| #64 | Access Doctor: automatic wireless pairing and OEM layout fixes | merged 10-17 | current `main` |

### Root cause 1 — a draft PR that the auto-merge workflow cannot merge

`.github/workflows/auto-merge-successful-pr-builds.yml` merges a PR only when
its build succeeds **and** it is not a draft:

```sh
gh pr list --state open --json number,headRefOid,isDraft --jq \
  ".[] | select(.headRefOid == \"$TESTED_SHA\" and .isDraft == false) | .number"
```

PR #60 was opened as a **draft** on 10-16 and its build passed, but a draft is
skipped by that filter, so it was never merged and never re-opened. Its
content was the companion to #63: it taught Game Boost to recognise the new
game entry activities.

#63 then landed *without* it. That single omission is what made the games feel
inactive:

```kotlin
// OeaGameBoostService.kt before this change
private fun isOeaGameScreen(className: String?): Boolean = className != null && (
    className.startsWith("com.oea.launcher.games.OeaGameActivity") ||
        className.startsWith("com.oea.games.OeaStandaloneGameActivity")
)
```

After #63 the three launcher icons are `OeaEndlessDaysActivity`,
`OeaSendOffActivity` and `OeaChessGameActivity`. None of them starts with
either prefix, so the moment Game Boost saw OEA in the foreground it fell
through to `endSession("left the game")`. The session, the overlay and Do Not
Disturb died one poll after the game opened — a game that is open but whose
Game Boost is dead reads exactly as "inactive".

**Fixed.** The known screens are now derived from the classes themselves, so
the detection cannot drift from the entries again:

* `OeaGameEntries.kt` exports `oeaGameEntryScreens` built from
  `OeaEndlessDaysActivity::class.java.name` and friends;
* `OeaGameBoostService` builds its set from the in-hub player, the shared shell
  and that list;
* a foreground report that names OEA's package **without** an activity class is
  treated as incomplete evidence (`cancelLeave()`) instead of "the user left",
  which is the Usage-access behaviour #60 also fixed;
* `tools/check-kotlin.py` fails if an entry activity is missing from the list,
  and CI greps for it too.

### Root cause 2 — the standalone shell had no screen of its own

`OeaStandaloneGameActivity` built its front menu from `android.app.AlertDialog`
and **never called `setContentView`** for it. The result: a stock platform
dialog floating over an activity with no content view — a black window with a
system popup on it. That is why the installed game apps looked nothing like
OEA, and it is the direct cause of the "the independent apps don't look like
the launcher" complaint.

**Fixed.** The shell now renders a real home screen (see §2).

### Root cause 3 — the game APKs were built but never shipped

Both workflows ran
`./gradlew :oea:assembleRelease :oea-game-arena:assembleRelease :oea-game-chess:assembleRelease :oea-game-runner:assembleRelease`
but only uploaded **and published `oea/build/outputs/apk/**/*.apk`**. The three
game APKs were compiled, verified by nobody, and thrown away when the job
ended. Nothing in the `oea-latest` release ever contained a game, so the
"independent apps" could not be installed at all.

**Fixed.** Game APKs are now verified (`aapt2 dump badging` for
`com.oea.runner` / `com.oea.arena` / `com.oea.chess`, plus `apksigner verify`),
uploaded as the `oea-game-apks` artifact, and attached to the `oea-latest`
release next to the launcher.

### Root cause 4 — no size controls anywhere

`minifyEnabled false` on the launcher, no R8 on the games, no ABI filter on the
games (so WebRTC's native libraries shipped for x86 and x86_64 as well), and
2.5 MB of bundled wallpapers stored as JPEGs. See §3.

---

## 2. The independent games now look and behave like the launcher

### `com.oea.games.ui.GameUi` — the games' own copy of OEA's visual language

`:oea-games` cannot depend on `:oea`, so a game app could never use `OeaKit`.
The new kit closes that gap: the same dark console palette, the same 20–30 dp
radii, the same bottom-sheet dialogs with the little drag handle, the same
ripple and type ramp. The one deliberate difference is the accent: it is the
**game's own colour**, so Send Off, Chess and Endless Days keep their identity
inside one consistent chrome.

It contains `GamePalette` / `GameColors` / `GameType` / `GameInsets`,
`GameKit` (cards, rows, buttons, sliders, fields, sheets, pagers, the
edge-to-edge page frame with real system-bar insets), `GameToggle` (drawn by
OEA rather than the deprecated platform `Switch`), and `GameCoverDrawable`
(procedural cover art from a game's accent and glyph — no image bytes).

### `OeaStandaloneGameActivity` — a real front screen

Instead of a dialog over an empty window, every standalone game now opens on:

* cover art, title, tagline and a match summary line (players · rendering · modes);
* a full-width **Play** button;
* OEA rows for **Match setup**, **Play with friends** (multiplayer games only),
  **Controls & aim** and **About**;
* a footer line that says what the app is.

Everything that used to be an `AlertDialog` is now an OEA sheet: option
pickers that remember the choice per game, the sensitivity/invert-Y panel, the
host/join menus, the self-updating lobby, the online invite sheet, the nearby
browser (one sheet that refreshes as beacons arrive rather than stacking
pickers), and the `HostUi` menu/confirm/prompt contract the engine calls.

Behaviour that was already correct is preserved exactly: direct-launch extras
(`EXTRA_GAME_ID`, `EXTRA_GAME_SEED`, `EXTRA_GAME_OPTIONS`, `EXTRA_GAME_MODE`,
`EXTRA_GAME_PLAYERS`, `EXTRA_AUTO_START`), `onMatchStarted(gameId)` for Game
Boost, host-authoritative match settings on `onMatchStart`, and session
teardown on destroy.

### Registration is per app

`OeaGames.registerBuiltIns(context, only = ...)` registers just the game the
app ships. The Chess app's engine registry now holds chess, not chess plus a
shooter — which is what "this app *is* the game" means. The launcher hub keeps
registering everything.

### Theme

`OeaStandaloneGameTheme` (in `:oea-games`) makes the window dark and edge to
edge before the first frame paints, killing the light-background flash. The
three installed apps and the three launcher entries all use it.

---

## 3. APK size

| Change | Where | Effect |
| --- | --- | --- |
| 15 bundled wallpapers JPEG → WebP (q82) | `oea/src/main/res/drawable-nodpi` | **2.53 MB → 1.35 MB (−1.18 MB)**, resource names unchanged |
| R8 (`minifyEnabled true`, `shrinkResources true`) | `:oea` + the 3 games | Removes unreached code and resources; conservative `-keep`/`-keepnames` rules in `oea/proguard-rules.pro` and `gradle/proguard-game.pro` |
| `ndk.abiFilters` = arm64-v8a, armeabi-v7a | the 3 games (the launcher already had it) | Drops WebRTC's x86 / x86_64 native libraries — the largest single item in a game APK |
| `packaging.jniLibs.useLegacyPackaging = true` | all four apps | Native libraries are compressed in the APK and extracted on install: much smaller download, same device behaviour |
| `packaging.resources.excludes` | all four apps | Drops Kotlin metadata, tooling files and duplicate licences |
| APK size table in the workflow summary | CI | Makes any future size regression visible on the PR |

The wallpaper conversion is the only number that can be measured without a
build; the rest is reported by the new **Report APK sizes** step on every CI
run, so the first build after this merge will put real figures on it.

---

## 4. In-launcher apps

The mini-apps were already in decent shape — background executors, palette
colours, per-app containment. The pass here is on the shared host, so it lands
on all twelve at once:

* **Scroll position survives a refresh.** `OeaMiniAppActivity` rebuilds the
  page to apply a change, which threw the list back to the top after saving a
  note, ticking a reminder or sending a message. The page now restores its
  scroll offset (and resets it when a *different* mini-app is shown).
* **A rise-and-fade on every render** instead of a bare alpha fade, so a
  rebuild reads as a transition rather than a flicker.
* **Stricter render intent**: `false` for a new screen (create, new intent,
  switching apps) and `true` for a rebuild of the same screen (permission
  change, theme change, in-app refresh).

The games hub (`OeaGamesActivity`) is untouched and still reachable from OEA
Settings; the three game icons no longer route through it.

---

## 5. New guard rails

`tools/check-kotlin.py` runs as the first CI step in both workflows and fails
the build on:

* unbalanced brackets, computed with a Kotlin lexer that understands comments,
  raw strings, escapes and nested `${...}` templates;
* a call through the game kit that does not resolve to a real member;
* an `Oea*` / `Game*` symbol used without being declared or imported;
* a manifest component that has no source file;
* **a mini-app listed in `OeaMiniApps` but not declared in the manifest**;
* **a game entry activity missing from `oeaGameEntryScreens`** (the exact
  regression from #60/#63);
* a standalone app whose `standaloneGameId` is not a built-in game.

Both were verified against deliberate breakage before being trusted.

---

## 6. Still needs a device

* that Game Boost keeps a session for the full length of a built-in game and
  ends it on returning to OEA Home;
* the feel of the new game home screen and sheets (touch targets, sheet
  heights, the nearby-browser refresh cadence);
* that R8 removed what it should and kept what it must — the rules pin every
  name OEA stores or reports, but only a device proves it;
* the re-encoded wallpapers at full screen brightness (they are visually
  equivalent at q82, but "equivalent" is a judgement);
* the real APK size figures from the first CI build after this merge.
