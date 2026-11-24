# Notes, phone, call block, theme, freezer, All in One, Chess, Endless Days and Voice

*Pass over the nine screens, from `ebf5de0` on `main`. Line references are to that commit.*

Nine features, one question each: **does it do what it says on the screen?** Three of them did not. The other six held up, and this records what was checked so the next pass does not have to guess.

| Area | What it promised | What it did |
| --- | --- | --- |
| **Notes** | "Reminders: set reminder time, notification via AlarmManager" (`miniapps/OeaNotesApp.kt` l.42) | Stored the time, listed it, filtered by it — and set **no alarm anywhere**. The word `AlarmManager` appeared only in that comment. |
| **Voice** | Own command grammar for "open X, search X, call X, set alarm, timer, flashlight, play, navigate" (`voice/OeaSpeechEngine.kt` l.35-48) | 5 of its 17 command types were run. "call mum" opened an **empty** dialer (the name went into an intent extra `ACTION_DIAL` ignores, l.396). "go to Ikeja" was eaten by the app opener (l.240). "cancel my alarm" **set** an alarm (l.280). "set a timer" and "set an alarm" returned false, silently. |
| **Call block** | A decision log that answers "what happened to that call?" (`callblocker/OeaCallScreeningService.kt`) | `noteDecision(this, verdict)` (l.43) never passed the number, so **every** decision was logged as "Unknown/private". A payphone was classed as a private number (l.264). |
| **Phone** | A call timer that counts the call | Ran on `System.currentTimeMillis()` (l.106, l.206, l.702). A network time correction mid-call — normal on a roaming phone — made the timer jump or fall back to `0:00`. |
| **Freezer** | An overflow menu and a Close button | Both were empty handlers: `circleIconButton(… "Freezer options" …) {}` (`OeaSystemToolsActivity.kt` l.2216) and `quietButton("Close") { }` (l.3673). Controls that look live and do nothing. |
| **All in One** | "SAME SEED FOR ALL · everyone plays the same board today" (`allinone/ArcadeHubScreen.kt` l.190-196) | The seed was shared, but the **level** was each player's own progress (`OeaAllInOneActivity.kt` l.73), and the ★ shown was that level's stars (l.276) — so two people playing "the same board" were not. |
| **Theme** | Import/export, apply, delete | Verified: imports get a fresh id and validate the version marker and a 14 MB image cap; delete removes only the theme's private copies; apply records the applied id. No change. |
| **Chess** | Complete rules and an AI | Verified: castling rights, en passant, promotion, checkmate, stalemate, threefold repetition, the fifty-move rule and insufficient material are all in `chess/ChessRules.kt` with `ChessRulesTest`; the AI searches on a background job, not the frame. No change. |
| **Endless Days** | A run that stops when you leave | Verified: `onPause` pauses a solo run, scores bank through `submitBest`, coins accumulate per device, split and network runs keep their own rules. No change. |

## What changed

**Notes: the reminder rings.** `miniapps/OeaNoteReminders.kt` mirrors the calendar's proven shape: `sync(context)` reads the notes store and arms one `AlarmManager` alarm per future reminder (exact when Android allows it, inexact otherwise — a note is not an alarm clock, so OEA does not ask for that permission on its behalf), a receiver posts the notification, and a boot/time-set receiver re-arms everything after a reboot or a clock change. `OeaNoteReminderReceiver` and `OeaNoteRestoreReceiver` are declared in the manifest.

Which reminder gets what is decided by `miniapps/OeaNoteReminderPlan.kt`, which is **pure and tested** (no `import android`):

* a time still ahead is **armed**;
* a time that passed while the phone was off is **rung once**, on the next sync, if it is less than a day old — waking up to eleven alarms from last week is not a reminder;
* anything older is **dropped**;
* the `id|at` of what already rang is kept, so opening Notes and then receiving a boot broadcast does not ring the same note twice;
* armed alarms are capped at 50 (Android's own limit is 500), and the rest are picked up as earlier ones fire.

`OeaNotesApp.reminderNotes()` feeds it — the normal, private and archived lists, never the trash. Every write goes through `OeaNoteReminders.sync`: setting, clearing, archiving, trashing, restoring, deleting, emptying the trash, and opening the app.

**Voice: one grammar, and every command answered.** `voice/OeaVoiceGrammar.kt` is the grammar in a file with no Android import: what a sentence means, plus `clockTime()` ("7", "6:30 pm", "half past seven", "quarter past six") and `durationSeconds()` ("10 minutes", "1 hour 30 minutes", "45 seconds"). `OeaSpeechEngine.parseCommand` supplies the installed app names and maps the answer onto `LauncherCommand`; `executeCommand` now runs every type the grammar can produce:

| Spoken | Runs |
| --- | --- |
| "open calculator", "go to Notes" | the app, or OEA's own mini-app |
| "go to Ikeja" | maps (`geo:`), because no app is called Ikeja — the fix for the hijack |
| "call mum", "message Tunde" | the contact's number, resolved through the contacts provider, ready in the dialer / SMS composer; a number spoken outright works too |
| "set an alarm for 6:30", "wake me at seven" | `AlarmClock.ACTION_SET_ALARM` |
| "set a timer for 10 minutes" | `AlarmClock.ACTION_SET_TIMER` |
| "play Burna Boy" | `INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH`, falling back to VIP |
| "do not disturb on" | the interruption filter, with an honest refusal when OEA lacks notification policy access |
| "turn on low mode" | Low mode, through the store and `OeaLowMode.apply` |
| "study mode", "change theme", "wallpaper", "take a photo", "record video", "settings" | the matching OEA screen or Android picker |
| "cancel my alarm", "stop the timer" | nothing — reported as a command OEA cannot run, not turned into a web search |

**Call block: the log keeps the number.** `noteDecision(this, verdict, rawNumber)`, so the diagnostics screen can finally say *which* call was allowed or blocked. A payphone is no longer treated as a private number: it presents its own number, so it is screened against the rules like any other call.

**Phone: the timer counts, whatever the clock does.** The in-call duration now runs on `SystemClock.elapsedRealtime()`. Telecom's `connectTimeMillis` is wall clock, so it is converted **once**, when the call becomes active, into the monotonic clock — after that the timer cannot jump when the network corrects the time.

**Freezer: the two dead controls work.** The overflow button opens a real sheet: re-read Android's frozen state, restore every frozen app (with a confirmation and a refused/failed count), and a paragraph that explains which backend this phone actually has. The capability sheet's Close button closes.

**All in One: "today's board" is one board.** The daily card pins the level (`dailyLevel()`), the host takes an explicit `level`, and the ★ shown is that level's stars — so the sentence on the card is true for a new player and a veteran alike. Every other cabinet still opens at your own progress.

## Tests and guards

* `OeaNoteReminderPlanTest` — 10 JVM tests: armed / rung now / dropped, the grace period ending exactly at a day, the "already rung" log, a duplicate note, the 50-alarm cap keeping the rest for later, and a mixed list sorted into all three answers.
* `OeaVoiceGrammarTest` — 14 JVM tests: "go to Notes" opens the app while "go to Ikeja" navigates, "cancel my alarm" is not an alarm, the clock and duration parsers, whole-word flashlight direction so "off" wins, and the search fallback announced as a search.
* Workflow: **Note reminder and voice grammar tests** runs both; **Verify notes, phone, call block, freezer, voice and the arcade** fails the build if the reminder stops using `AlarmManager`, if the plan stops being pure or loses `RING_NOW`, if the receivers leave the manifest, if the grammar stops being Android-free, if any command type stops being answered by a named handler, if the empty-dialer `tel:` intent comes back, if "go to" returns to the app patterns, if the decision log drops the number again, if `PAYPHONE` returns to the private list, if the in-call timer returns to `activeSinceMs`, if a Close or overflow handler is empty again, or if the daily card stops pinning its level. Negative-tested locally: dropping the number from `noteDecision` and removing one `sync` call makes it fail.

## What to check on a phone

1. Notes → open a note → Reminder → Set for two minutes ahead → close the app → the notification arrives with the note's title; tapping it opens Notes.
2. Set a reminder, reboot the phone before it is due — it still rings (the restore receiver re-arms it).
3. Set a reminder, then put the note in the trash — nothing rings.
4. Voice → "go to Ikeja" opens maps; "open calculator" opens the calculator; "call mum" opens the dialer with the number filled in; "set an alarm for 6:30" opens the alarm already set.
5. Call Blocker → diagnostics after a call: the row shows the number, not "Unknown/private".
6. Start a call, then change the phone's time (Settings → Date & time) while it is connected: the timer keeps counting from where it was.
7. Freezer → ⋮ → Restore all → confirm: every app is launchable again and the count goes to zero.
8. All in One → today's challenge: the level named on the card is the level that opens, and the ★ matches that level.

## §4 Verification

CI runs on this branch (compile, JVM tests, then the guard steps):

| Run | Result |
| --- | --- |
| `45355672496` (first push) | **red** — compiled, but three `OeaVoiceGrammarTest` cases failed: `goToAPlaceNavigates`, `callingAndMessaging`, `appMatchingPrefersTheExactName` |
| `45356522486` (after the fix) | **green** — both jobs: compiled, all JVM tests passed, every guard step passed |

The red run was the grammar being wrong about case, not the tests being wrong:

* the sentence was lowercased before the query was sliced out of it, so "go to Ikeja" reached the map lookup as `ikeja` and "send a message to Tunde" as `tunde`. The sentence is now folded for matching and sliced from a copy that keeps its case.
* `matchingApp("chr", …)` found nothing because `"Chrome".startsWith("chr")` is false. App names are compared case-insensitively now.
