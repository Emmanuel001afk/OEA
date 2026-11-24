# Calendar review — 24 November 2026

The launcher's own surface, the mini-apps in general and the player each had a pass
([speed](responsiveness-review-2026-11-23.md),
[mini-app response](miniapp-response-review-2026-11-23.md),
[VIP](vip-playback-session-2026-11-23.md)). This is the Calendar on its own, and it is
less a speed story than a *promise* story: three of the things the app offers — repeat
rules, reminders, and calendar files — were stored, labelled, exported and never acted
on.

Everything in §1 changed in this pass. §2 is what was checked and already right, §3 is
what is deliberately still missing and why, §4 is how it is verified, §5 is what only a
phone can settle.

---

## 1. What was wrong, and what changed

### 1.1 A reminder was a label

The editor has offered 5m / 15m / 30m / 1h / 1d since the first version. The value was
stored in the event, written into the `.ics` export as a `VALARM` — and nothing in the
app ever read it. `grep -rn "reminderMin"` found the editor, the store and the exporter,
and no scheduler at all. A reminder that does not ring is worse than no reminder: it is
a promise the calendar breaks silently, in the one situation where the user is trusting
it and not looking.

**Changed.** `OeaCalendarReminders` (`miniapps/OeaCalendarReminders.kt`) arms one alarm
per event with a reminder, through `AlarmManager` so it fires with the app closed:

* `sync()` reads the cached store, takes each event's **next** occurrence
  ([`nextStart`](miniapps/OeaCalendarRecurrence.kt)), subtracts the reminder and arms
  `setExactAndAllowWhileIdle` when Android allows exact alarms, `setAndAllowWhileIdle`
  when it does not. It is called on open, on resume, after every save, after an import
  and from a restore receiver, so an alarm table is never more than one of those away
  from the store;
* `OeaCalendarReminderReceiver` posts the notification (channel
  `oea_calendar_reminders`, tap opens the Calendar) and then re-arms the next
  occurrence of a repeating event;
* `OeaCalendarRestoreReceiver` re-arms everything after `BOOT_COMPLETED`, `TIME_SET`,
  `TIMEZONE_CHANGED` and the exact-alarm permission change, because all four drop every
  pending alarm;
* alarms are capped at 50 (Android's own limit is 500) and at a 45-day horizon; stale
  codes for deleted or edited events are cancelled by diffing against the set stored in
  prefs;
* the first time a reminder is saved, the app asks for the notification permission on
  Android 13+, the way Clock already does when an alarm is set — an alarm that is not
  allowed to post a notification is the same silent broken promise in a new costume.

The calendar does not ask for the exact-alarm permission on its own behalf: an inexact
reminder that may be a few minutes late is the right trade for an event reminder, and
asking a user to grant alarm-clock privileges to a calendar is the kind of thing that
gets an app uninstalled.

### 1.2 A repeat rule was a label

Same shape, bigger hole. `Recurrence` had five values, the editor offered them, the
export wrote `RRULE:FREQ=WEEKLY` — and every view drew the event exactly once, on the
day it was created. A weekly stand-up was one row. The Agenda listed it once, the month
grid dotted one day, the year view counted it as one event in one month.

**Changed.** `OeaCalendarRecurrence` (`miniapps/OeaCalendarRecurrence.kt`) is the rules
as pure date arithmetic, and every view now asks it for the appearances in the range it
is drawing:

* Month dots and the day list use the 42-day grid range; Week uses its seven days; Day
  uses one; Agenda shows **a year of real appearances** instead of every stored event
  ever saved; the Year view counts appearances per month;
* occurrences are generated, never written: an endless daily event costs nothing in the
  store and editing the series edits one event;
* the two rules calendars usually get wrong are handled explicitly and tested: a
  monthly event on the 31st appears on the last day of shorter months and *returns* to
  the 31st afterwards (31 Jan → 28 Feb → 31 Mar, not "the 3rd of March"); a yearly
  event on 29 February falls back to 28 February in common years and returns to the
  29th in leap years.

### 1.3 The store was parsed on every paint

`refresh()` called `decode(prefs(host).getString(KEY_EVENTS, ""))` — the whole blob,
split, re-parsed, re-sorted — on every day tap, month step, filter chip and (before the
previous pass's debounce) every keystroke. A few hundred events is a few hundred `Event`
allocations and a sort per finger.

**Changed.** `events(ctx)` decodes once per change, keyed on the raw string; `saveEvents`
is the single write door. Because it is the single door it also owns the two things
that read the store behind the app's back: `saveEvents` re-arms the reminders and
refreshes the home-screen widget. Every writer now goes through it — the editor, the
delete button, the `ACTION_INSERT` hand-off from another app, and the file importer —
which is also how the earlier `decode(prefs(...))` spread was removed.

### 1.4 Two events could share an id, and an import could arrive read-only

* New events took `System.currentTimeMillis()` as their id. Two events created in the
  same millisecond shared one, and deleting either deleted both.
* Imports took `System.nanoTime() + hash`. `nanoTime` has no defined origin and is
  allowed to be **negative**; a negative id means "event from the phone's own calendar"
  to every view, so an imported event with one appeared read-only, could not be edited,
  and was skipped by the export.

**Changed.** `freshEventId()` is monotonic, positive and above every id already stored;
ICS imports seed it from the file's numeric `UID` when there is one, so re-importing
OEA's own export does not churn ids.

### 1.5 Calendar files were read and written for the easy case only

`toIcs`/`fromIcs` had five real interop faults:

| Fault | Effect |
|-------|--------|
| `DTSTAMP` formatted **local** time and appended `Z` | every other calendar was told the event was created hours off |
| all-day events written `DTEND` = `DTSTART` | a zero-length day; some clients drop it entirely |
| no line folding | a long description or title went out as one line, past RFC 5545's 75 octets, and strict parsers truncate it |
| `DTSTART;TZID=Europe/Berlin:…` read as if Berlin were the phone's zone | imported Google/Outlook events landed at the wrong hour |
| escaping applied replacements in an order that could eat its own output | a title containing `\,` or a literal `\n` unescaped wrongly |

**Changed.** `OeaCalendarIcs` (`miniapps/OeaCalendarIcs.kt`):

* `stampUtc()` really is UTC;
* all-day events end the day after they start (and on the 1st of the next month when
  they are the 31st);
* `fold()` splits at 73 **UTF-8 octets** — never mid-code-point, so a title in Yoruba or
  an emoji survives — and `fromIcs` unfolds continuations;
* `TZID` and trailing `Z` are honoured by converting the wall clock on that zone's clock
  into the phone's; a floating time is still taken as local;
* escaping is a single pass in both directions, so `\,`, `\;`, `\\` and `\n` round-trip;
* `VTIMEZONE` blocks are skipped by depth, so their `DTSTART`/`RRULE` never become
  events;
* `VALARM` survives a round trip as the reminder it is.

### 1.6 An event's day could not be edited

The editor could change the title, the times, the location, the notes, the category, the
reminder and the repeat — everything except **when**. Moving an event meant deleting it
and typing it again, which also lost its category and reminder.

**Changed.** A "Date" row opens a three-wheel date sheet. The day wheel is rebuilt
against the month and year on screen, so 31 January → February becomes 28/29 instead of
an invalid date.

### 1.7 The widget counted every event ever saved

`OeaCalendarWidgetProvider` split the raw blob and called the count "N events" under a
heading that said "November 2026", and it put **today's date** in the row where the next
event belongs.

**Changed.** The count is the current month's appearances through the same recurrence
(the app and the widget cannot disagree), and the second line is the next occurrence in
the following fourteen days — "Today · 10:00 · Stand-up" — falling back to today's date
when there is nothing ahead.

### 1.8 Every import leaked a thread

`handleResult` created a `newSingleThreadExecutor()` per picked file and never shut it
down: one thread and its queue per import, for the life of the process. It now uses one
daemon importer for the app, so a second import waits its turn instead of racing the
first one's read-modify-write of the store.

---

## 2. What was already right

* **Device-calendar reads** were moved off the UI thread, once per month, in the
  previous pass (`OeaCalendarApp.kt` `requestDeviceMonth`, `DEVICE`); this pass kept that
  shape and only routed the paint through the new store.
* **The month grid arithmetic** was sound: no off-by-one at the start of the month, week
  start respected, days outside the month not clickable. The rewrite keeps those
  properties and adds the "no empty sixth row" rule.
* **Search and category filters** apply to what is drawn, including device events.
* **The export already contained** `RRULE` and `VALARM`; the writer was wrong about
  their surroundings, not about them.
* **The alarm/restore pattern** for `OeaClockAlarmReceiver` and `OeaClockRestoreReceiver`
  was the model for the reminder receivers — same permission model, same boot re-arm.
* **Receivers are paired and exported flags are set** on every new receiver here
  (`exported="false"` for the alarm target, `exported="true"` only for the system
  broadcasts the restore receiver must hear).

---

## 3. What this pass did not change, and why

* **Events have one day, not a range.** A 22:00–02:00 event still ends on the day it
  starts, because the model has no end date. Adding one changes the store format and
  every view's layout; it is a feature with a migration, not a fix.
* **Editing a repeated event edits the series.** There is no "this event" / "this and
  following" choice, and no per-occurrence exception table. Without those, editing one
  appearance would silently rewrite history in no way the user could see; editing the
  series is at least honest and predictable.
* **The exact-alarm permission is not requested.** See §1.1 — inexact is the correct
  trade for event reminders.
* **Stored times are floating local time**, like most calendar apps without a stored
  zone. A flight booked across timezones keeps its wall-clock time when the phone moves,
  which is what the app's model can express today.
* **Device-calendar events stay read-only** and never get a reminder of their own: the
  provider's own reminder would fire twice.

---

## 4. Verification

* `OeaCalendarRecurrenceTest` — 16 JVM cases: one-off, daily, weekly weekday, monthly
  clamping both ways, 29 February across a leap boundary, anchor reporting, ordering,
  the limit, `nextStart` skipping a passed hour and returning null for a past one-off,
  all-day 09:00, broken time strings, `clampedDay` for four month lengths.
* `OeaCalendarIcsTest` — 13 cases: the stamp really is UTC, all-day `DTEND` (including
  across a month boundary), folding at 75 octets and unfolding back, a full round trip
  of text with commas/semicolons/newlines/non-ASCII, escape symmetry including a
  literal `\n`, `Z` and `TZID` conversion, `VTIMEZONE` skipping, `VALARM` round trip, an
  event with no date not becoming an event, and imported ids staying positive and
  increasing.
* `Verify the calendar work` — 25 guard checks that fail the build if any of this is
  undone: recurrence functions and both call sites, no per-day `filteredEvents.filter`
  left in the views, the reminder object and both receivers with their manifest
  declarations, the single write door (exactly one `putString(KEY_EVENTS)`), no
  `decode(prefs(host)...)` and no `System.nanoTime()` ids, the ICS object with UTC
  stamp and `DTEND;VALUE=DATE`, and the date picker.
* `tools/check-kotlin.py` — `STATIC CHECK OK — 307 sources`.

Nothing here was compiled on this machine (no Android SDK, no Kotlin compiler in this
workspace): the CI job is the compile signal, and every guard was run locally against
the working tree first. The run that carries this change set is named in the pull
request.

## 5. What to check on a device

1. **A reminder that rings.** Create an event ten minutes out with a 5m reminder, close
   OEA from recents, lock the phone. The notification should arrive about five minutes
   later; tapping it opens the Calendar. Repeat with the phone in Doze (screen off,
   unplugged, ten minutes) — inexact alarms may arrive a few minutes late, never early.
2. **A repeating event.** Create a "Weekly" event on a Tuesday starting four weeks ago.
   The month grid should dot every Tuesday including past ones, Week view should show it
   in each of its seven days where it falls, Agenda should list the next year of
   Tuesdays, and the Year view should count four or five in the current month.
3. **The 31st.** Create a monthly event on 31 January and open February: it must appear
   on the 28th (or 29th), and March must show the 31st again.
4. **Moving an event.** Edit an event, tap "Date", pick another day, save. It must move,
   keep its category and reminder, and the reminder must fire on the new day (the old
   alarm is cancelled by the diff in `sync`).
5. **Interop.** Export a calendar as `.ics`, open it in Google Calendar: all-day events
   should be one day, not zero; times should match; a long description should not be
   truncated. Then import a Google-exported `.ics` with `TZID` entries and check the
   hour.
6. **The widget.** With events in this month and in the next, the widget should count
   only this month's and name the next upcoming one. Add a weekly event and watch the
   count rise.
7. **Notifications denied.** On Android 13+, deny OEA notification permission: the
   alarm must not crash the app, and the reminder simply does not appear — the Calendar
   still works.
8. **A big calendar.** Import a few hundred events and scroll the Agenda: it should stay
   smooth, because the store is parsed once per change rather than per paint.
