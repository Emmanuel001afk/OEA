# Game Boost identity, real crash isolation, and network accuracy — 2026-10-19

**Verification status.** This sandbox still has no JDK, no Android SDK and no
Maven access, so nothing here is compile- or device-verified. Every file was
re-read in full before and after editing, `tools/check-kotlin.py` passes on
the whole tree, and the existing `OeaFaultPolicyTest` unit tests still cover
the policy table unchanged (only the main-thread *delivery* mechanism in
`OeaFaultShield` changed, not the decision table). Real-device confirmation
is required for everything below, especially the Looper-resume recovery.

## 1. Game Boost no longer forces games into its own list to "see" them

The previous build's automatic detection worked backwards: a game had to be
pushed into Game Boost's persisted `games` set before `isGame()` would ever
return true for it, so the "Automatic" picker showed every Android-declared
game (correctly, since the picker reads the category directly) while the
*session engine* only ever recognised the subset that happened to already be
sitting in that persisted set. A freshly installed game, or one whose entry
was never written into `games` by an earlier code path, showed up in the list
but the floating control and Do Not Disturb never activated for it — exactly
the "shows them but does not identify them" complaint.

**Fixed in `OeaGameBoostStore.isGame`.** A game's identity is no longer a
membership check against a stored set:

- OEA's own games (`oeagame/<id>`) are recognised straight from the engine
  registry — they do not need to be "added" to Game Boost at all, by design,
  since they are games by construction.
- Any installed app Android itself declares `CATEGORY_GAME` is recognised
  directly from `PackageManager`, every time, with no dependency on a
  persisted selection.
- Only in **Auto + manual** mode does an explicitly added non-game app ride
  along in the persisted `games` set; that is the one case where a human
  really did ask Game Boost to treat something as a game.
- `dismissedGames` is still the single way to say "not for me" and is checked
  first in every path.

`OeaGameBoostActivity.selectedGames()` and the Settings summary
(`activeGameCount`) were rebuilt on the same identity rule, so "Your games",
the automatic detection list and the session engine can never disagree again
about which apps are games.

`oeaGameTitles()` also now falls back to the three built-in games' real
titles (Endless Days, Send Off, Chess) if the engine registry has not finished
initialising yet when Game Boost's UI asks for it, instead of returning an
empty map and silently hiding the hub games for one frame.

## 2. A game crash must never be a launcher crash — now actually true

The stability doc already drew the architecture correctly: a fault on a
game's own thread (GL thread, engine workers) was already contained without
touching Home. The gap was a fault that reached the **main thread**'s own
default uncaught-exception handler while a game (or any OEA screen) was in
front, which is the scenario the user hit:

An uncaught exception on the main thread means `Looper.loop()` has already
*returned* (the exception propagated out of it). The previous code correctly
reasoned that "posting a recovery action to that Looper would never run" —
but then only ever reached for `escalate()`, which hands the thread back to
Android's own handler and kills the process, for every main-thread fault,
game or Home alike. Killing the process is what let Android occasionally
re-ask "set default launcher" instead of simply landing the user back on
OEA's own Home.

**Fixed in `OeaFaultShield.onUncaught`.** When the fault's thread is the main
thread *and* a game or other OEA screen (not Home itself) was in front, OEA
now:

1. Classifies and records the fault exactly as before (same `OeaFaultPolicy`
   table — `MAIN_SCREEN` + `onOeaScreen` always decides `CLOSE_SCREEN`).
2. Runs that action (`act(decision.action)`), which closes the front
   activity — this is safe because the crashed call stack has already fully
   unwound, so nothing it was doing is still running.
3. **Resumes the same Looper in place** (`Looper.loop()` again, on the same
   thread). The Looper's `MessageQueue` was never destroyed — only the pump
   loop returned — so calling `Looper.loop()` again simply keeps the process,
   and Home, running exactly as before the game's own fault.

A counter caps this at 5 consecutive resumes before falling through to a real
process restart, so a game that is faulting in a tight loop still cannot wedge
the main thread forever; the counter forgives itself after 15 quiet seconds.
This is a known (if uncommon) recovery technique and is the only way to avoid
"nothing pumps the message queue any more" — it is called out here explicitly
because it has no unit-test coverage possible without a device and deserves a
real crash test before being trusted.

**Not changed:** a fault whose thread or stack is Home's own (`MAIN_HOME`)
still goes through `escalate()` exactly as before — Home's own crash story is
unaffected by this change.

## 3. Network quality: no longer optimistic when OEA has no real evidence

The Slow/Medium/Fast badge in the Game Boost panel is Android's own bandwidth
and signal estimate, not a live measurement of the current match's traffic —
that was always documented, and still is. What was wrong is the *fallback*:
when neither a bandwidth estimate nor a signal reading were available, the
code guessed "Fast" for anything that was not clearly Ethernet. That is
exactly backwards for the complaint ("shows fast while the game is clearly
struggling") — an unknown link should never default to the best label.

**Fixed in `OeaNetworkQualityReader`:** the no-evidence fallback is now the
weakest category instead of an optimistic guess, and the Fast/Medium
thresholds were raised (12 Mbps / 2.5 Mbps, signal −60 dBm / −75 dBm) so a
borderline link reads as Medium rather than Fast. True millisecond-accurate
"this game's network" measurement would need to read a live match's own RTT
(`NetSession.rttTo`), which is a larger change left for the next pass since it
only applies while a networked match is actually running.

## 4. Send Off: vault assist over obstacles and window sills

`ArenaMap`'s houses already cut a real physical opening for each window (an
open gap between the sill and the lintel), but the character controller's
step-up height (0.45 m) was shorter than the sill (1.0 m), so the opening was
unreachable in practice — jumping into a window just bounced off the sill like
a solid wall.

**Fixed in `ArenaSim.move`:** jumping while pressed against an obstacle
(`CharacterController.hitWall`) now gets extra lift, enough to clear a window
sill or a low wall/crate instead of just hopping in place. This does not
change normal jump height when there is nothing to climb.

## Still needs a device

- The Looper-resume recovery in §2 is the one change in this pass that is
  genuinely novel and must be confirmed by deliberately faulting a game (the
  Engine Lab is the usual place) with OEA set as the default launcher, to
  confirm Home is still in front afterwards and Android never reprompts for a
  default launcher.
- Game Boost's identity fix needs a real install to confirm the floating
  control now appears immediately for a freshly installed, Android-declared
  game with no manual setup.
- The network quality thresholds are a heuristic improvement, not a
  ground-truth fix; a future pass should read `NetSession.rttTo` while a match
  is live for a genuinely per-game number.
