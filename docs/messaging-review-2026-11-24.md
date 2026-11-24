# Messaging review — 24 November 2026

Messaging is the third-biggest surface in the launcher and the one where being wrong costs
the most: a message someone is waiting for. It had already been through the routing,
grouping, dual-SIM, read-state and search work in earlier passes — §2 records what was
checked and left alone, and it is genuinely careful code.

What was left was a single theme: **OEA asked the network what happened and then threw the
answer away.** A refused message kept the green "Sent" bubble it had optimistically filed.
A confirmed delivery never reached the details sheet that has a row for it. A reply sent
from the system UI was never told to OEA at all. And the two things a composer shows about
a message — how long it is, and that it has arrived — were both computed from the wrong
text and the wrong id.

Everything in §1 changed here. §3 is what is deliberately still missing, §4 how it is
verified, §5 what only a phone can settle.

The reusable half now lives in `miniapps/OeaMessagingFacts.kt` (one object, 14 tests):
the body a reply actually sends, the counter's wording, what a bubble calls a failure, and
which notification a conversation owns.

---

## 1. What was wrong, and what changed

### 1.1 The counter measured a different message than the one it sent

```kotlin
// sendCurrent()
val quoted = replyTo?.let { "“${it.body.take(120)}”\n" }.orEmpty()
val body = quoted + typed
// paintComposerState()
val info = runCatching { SmsMessage.calculateLength(body, false) }   // `body` = typed only
```

Replying prepends up to **123 characters** — two curly quotes, the newline, and a 120-character
clip of the original — and the counter was fed only what was typed. Replying to a long
message with 60 characters typed showed "60" or "160 remaining" on a message that was
already two segments, and the preview text in the reply bar (90 characters) was a *third*
number again. Setting or clearing the reply never repainted the counter at all.

**Changed.** `OeaMessagingFacts.composeBody(typed, quoted)` is now the single source of the
sent body: `sendCurrent` sends it, `paintComposerState` measures it, and `setReplyTo` repaints
as soon as the quote changes. `counterLabel(parts, remaining)` keeps the old politeness — the
counter stays hidden while there is more than 25 characters left of one segment — but is now
a pure rule with tests instead of a condition buried in a view builder.

### 1.2 A refused message said "Sent" forever

`recordSentIfDefault` files the row as `TYPE_SENT` the moment `sendTextMessage` returns. The
`SMS_SENT` broadcast then carries the network's real answer, and the conversation did this
with it:

```kotlin
if (resultCode == Activity.RESULT_OK) scheduleRefresh()
else say("Message not sent — tap the failed bubble to retry", …)
```

There was no failed bubble: the row was `TYPE_SENT` and nothing ever updated it. The bubble,
the thread list and the details sheet all agreed the message had gone. The toast pointed at
a thing that did not exist. (The adapter already had `TYPE_FAILED` handling, and the details
sheet already had a "Delivery — the network reported a delivery failure" row; both were
unreachable.)

**Changed.** The send and delivery `PendingIntent`s now carry the conversation and the exact
text (`EXTRA_ADDRESS`, `EXTRA_BODY`), and `OeaMessagingApp.markSendResult(ctx, address, body,
status)` writes the verdict onto the row it belongs to — the newest `sms/sent` row matching
that address and that body. A failure sets `STATUS_FAILED`, and the bubble is rendered from
`OeaMessagingFacts.isFailed(type, status)`, which counts **sent-but-failed** as failed. The
bubble turns into the danger-coloured, tappable-to-retry bubble the old toast was already
promising, and the meta line says ` • Not sent — tap to retry`.

A report that belongs to a different conversation (two threads open at once share one
pending intent per part, because pending intents are identified by action and request code)
is ignored rather than written to the wrong row.

### 1.3 A confirmed delivery was thrown away

`deliveredReceiver` was one line: `scheduleRefresh()`. The details sheet has three delivery
states and could only ever show none of them, because nothing wrote `STATUS_COMPLETE`. So
"Delivered" never appeared on a bubble and "Delivered — the network confirmed it" never
appeared in a sheet, even on a network that had said so.

**Changed.** A successful delivery report writes `STATUS_COMPLETE` to the same row, so the
bubble says ` • Delivered` and the sheet's row is reachable. `STATUS_PENDING` now reads
` • Sent` rather than nothing, so the three states — sent, delivered, failed — are visibly
different.

### 1.4 A reply from the system UI vanished

`OeaSmsRespondViaMessageService` is OEA's implementation of `ACTION_RESPOND_VIA_MESSAGE` — the
path a watch, Android Auto, a driving-mode UI or another app's reply button uses when OEA
holds the SMS role. It called `SmsManager` directly:

```kotlin
if (parts.size <= 1) manager.sendTextMessage(address, null, body, null, null)
else manager.sendMultipartTextMessage(address, null, parts, null, null)
```

`SmsManager` does not write to the SMS provider; that is the role holder's job, and every other
send path in the app goes through `sendNow`, which calls `recordSentIfDefault`. So a reply sent
this way left the phone and then vanished: not in the conversation, not in Android's Sent
folder, not in any backup.

**Changed.** The service calls `OeaMessagingApp.sendNow(applicationContext, address, body, -1)`
— the same sender the conversation, the quick reply and the floating window use, so it splits
the text identically, records the row, and honours the same permission check.

### 1.5 Ten texts from one person left ten notifications

```kotlin
manager.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
```

The id was the current time, so every message ever received got its own notification slot:
a conversation of five replies left five notifications, each leading with the **raw address**
even when the phone knew the contact, each icon an **outgoing send arrow**, each stamped with
the moment it was posted rather than when the message arrived, and each with a `PendingIntent`
request code derived from the clock.

**Changed.** `OeaMessagingFacts.notificationId(address)` gives one slot per conversation —
and every address form of the same correspondent shares it (`MTN Nigeria` / `mtn nigeria`,
`312 ` / `312`, and the phone-number variants `OeaSmsMatcher` already unifies), so a second
message updates the first notification instead of stacking under it. The title is the contact
name (`notificationTitle`), the timestamp is the message's own, and the icon is the new
`oic_message` bubble instead of the send arrow.

The name is a contacts lookup, so the whole post now runs on the inbox executor with
`goAsync()`, holding the broadcast open until it finishes, rather than querying contacts on
the thread that is filing an incoming SMS.

---

## 2. What was already right

* **Segment counting** uses `SmsMessage.calculateLength` rather than `length / 160`, so GSM-7
  extension characters, the UCS-2 switch for emoji, and the 153-character multipart budget
  are the platform's own. The fault in §1.1 was the argument, not the method.
* **MMS dates** are multiplied by 1000 — the `mms.date` column is seconds while `sms.date` is
  milliseconds, and getting that wrong dates every picture message to 1970.
* **Multipart SMS** are joined with `joinToString("")` and dated from the first part, so a
  long text arrives as one message and not as three broken ones.
* **Thread grouping** (`OeaSmsThreadIndex` + `OeaSmsMatcher`) unifies provider thread ids,
  dual-SIM splits, padded shortcodes, alphanumeric senders and E.164/local forms — and has
  its own three test classes.
* **Dates** (`OeaMessageDates`) are day-based rather than millisecond-based, so a DST change
  cannot turn "yesterday" into "23 hours ago".
* **Delete, block, mute and read overrides** all use the default-SMS role properly, with a
  local fallback when OEA is not the role holder.
* **The OTP chip**, the draft store, the SIM picker and the quick-reply row are unchanged.

---

## 3. What this pass did not change, and why

* **MMS download.** `OeaMmsDeliveryReceiver` still records only that a WAP push arrived; a
  full MMS retrieval agent (notification → `content://mms` fetch → part assembly) is a
  feature, not a fix, and is called out in the app's own notes.
* **`latestType` in the thread list.** The list can say "Not sent: …" for a row the platform
  itself wrote as `TYPE_FAILED`, but a row OEA marked failed carries `TYPE_SENT` with
  `STATUS_FAILED`. Rewriting `type` on someone else's row is not ours to do, so the list
  keeps showing the snippet; the bubble and the details sheet carry the truth.
* **No scheduled send, no RCS, no encrypted messaging, no per-message notification grouping
  beyond one slot per conversation.** Unchanged scope.
* **The 24-hour clock** in message times follows the rest of OEA's readouts by design.

---

## 4. Verification

* **`OeaMessagingFactsTest` — 14 methods**: the plain body; the quote prefix in curly quotes
  with its newline; a 200-character quote clipped to 120 plus an ellipsis, and a clip that
  does not carry trailing spaces into the ellipsis; the "60 typed characters are one segment,
  the same 60 behind a 120-character quote are not" property; the counter's hide/build rules
  at the 25-character boundary and its `10 • 2 SMS` wording; sent-but-failed as a failure and
  an incoming row never being one; every `statusNote` (draft, outbox, queued, failed,
  delivered, pending, none); the mirrored `Telephony.Sms` type and status numbers; the
  notification id stable per address, shared by every form of one sender, distinct between
  senders, never zero; and the title that prefers a name and falls back honestly.
* **`Verify the messaging work` — 22 checks** in CI: the facts object and its signatures,
  both `composeBody(typed, replyTo?.body)` call sites with the old inline quote gone, the
  `setReplyTo` repaint, `markSendResult` and its two report call sites, the extras on the
  pending intents, the per-conversation notification id and title, the new drawable, the
  timestamp, no `System.currentTimeMillis() % Int.MAX_VALUE`, and the reply service going
  through `sendNow` with no bare `SmsManager.getDefault()`.
* `tools/check-kotlin.py` — `STATIC CHECK OK — 314 sources`.
* Every guard was extracted from the workflow and run locally before pushing.
* CI run **[45316274405](https://github.com/Emmanuel001afk/OEA/actions/runs/45316274405)** —
  both jobs green on the first push, with `Messaging detail tests` and
  `Verify the messaging work` in the step list between the EMAGE pair and the launcher-engine
  pair. Merged as `1b2043a` (PR [#166](https://github.com/Emmanuel001afk/OEA/pull/166)).

## 5. What to check on a device

1. **Reply to a long message.** Type enough to bring the counter to, say, "5" — then quote a
   message. The counter must jump to a second segment immediately, and the message must arrive
   with the quote.
2. **Send in airplane mode**, then turn the radio back on. The bubble must turn into the
   red "Not sent — tap to retry" bubble; tapping it must send again, and the retry must appear
   as a new row rather than editing the failed one.
3. **A normal send with delivery reports enabled** (Settings → SMS → delivery reports) must
   end with " • Delivered" on the bubble, and "Delivered — the network confirmed it" in the
   long-press sheet.
4. **Reply from a notification** (or a watch / Android Auto if available) while OEA is the
   default SMS app: the reply must appear in the conversation and in the phone's Sent folder.
5. **Two texts from one contact.** One notification, not two, led by the contact's name, its
   time the time the message arrived.
6. **A sender the phone does not have saved.** The notification shows the number.
