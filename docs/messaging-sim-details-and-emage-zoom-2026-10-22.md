# Messaging as a real messaging app, per-message SIM details, EMAGE zoom and month sections — 2026-10-22

## What was asked for

1. Messaging should look and work like other messaging apps (Google Messages and peers), not like a settings page.
2. Long-pressing a message should show the SIM/network it belongs to — for a received message, the SIM it was received on; for a sent message, the SIM used to send it.
3. EMAGE pictures should be zoomable.
4. EMAGE categorisation showed the same month over and over ("October", "October", …) with no date. Everything in one month should be in one section for that month.

## Messaging (`OeaMessagingApp`, now a full-bleed mini-app)

The old surface was a settings-style page: rows in one scrolling column, a conversation crammed into a bottom sheet, and a Reply button that opened a *second* sheet to type in. It is now a `MessagingScreen` in the same shape as EMAGE/VIP:

- **Conversation list** on a virtualised `RecyclerView`: coloured initial avatars (person glyph for unknown numbers), saved contact names, two-line snippets with `You:` / `Not sent:` / `Draft:` prefixes, bold unread rows with an unread-count badge (the thread index now counts unread messages, not just a boolean), and row dates that read as a time today, `Yesterday`, a weekday this week, then a real date — with a two-digit year whenever it is not this year (`OeaMessageDates`, unit-tested).
- **Live updates**: a `ContentObserver` on the SMS provider reloads the list and any open conversation (debounced); there is no manual refresh step left in the main UI.
- **Search** is an inline pill field filtering by number, contact name, snippet and full message body (the body query is debounced onto the IO thread).
- **Tabs** All / Unread (with count) / Archived / Blocked / Spam, and a "Start chat" FAB.
- **Long-press a conversation**: open, pin to top, mark read/unread (default app only, and labelled as such), mute (honoured by `OeaSmsDeliveryReceiver`), archive, block, delete conversation (default app only).
- **Setup** collapsed from up to four banners into one slim banner plus a setup sheet with per-permission rows, each saying what it unlocks: history, sending, contact names, **SIM naming (Phone)**, default-SMS role, alerts.

### Conversation screen

A full-screen dialog in the palette (the EMAGE viewer pattern): avatar header with name/number/SIM subtitle, call and overflow actions, dated day pills ("Monday, 19 October" / "3 October 2026" — never a bare month), bubbles with grouped spacing, and an inline composer with:

- multi-line text (to 5 lines), IME Send, a GSM part counter (`SmsMessage.calculateLength`) that appears when a message nears or exceeds one SMS;
- drafts saved per address (shown as `Draft:` in the list, restored when the chat reopens);
- a **SIM chip on dual-SIM phones** ("SIM 1 • MTN Nigeria") that defaults to the SIM saved for that address, else the SIM that handled the conversation's latest message, else Android's default SMS SIM;
- sending through `SmsManager.createForSubscriptionId` (API 31+) / `getSmsManagerForSubscriptionId` (below). When OEA is the default SMS app it files the sent copy itself **with `sub_id`** (the platform does not file one for the default app); when it is not default, it re-reads the provider after a short delay, since Android files non-default sends in its own time.

### Long-press a message → details (the SIM feature)

Android stores the subscription that handled each SMS in the provider's `sub_id` column. `OeaSimDirectory` resolves it to slot, Settings name, carrier (network) name and — only with `READ_PHONE_NUMBERS`, asked for solely in setup — the SIM's own number. The details sheet leads with **"Received on SIM 1 • MTN Nigeria"** or **"Sent with SIM 2 • Airtel"**, then network, the other party, received/sent-by-sender times, read/delivery status (delivery only when the provider's status column actually says so) and size in characters/SMS parts, plus Copy / Forward / Share / Delete and "Send again" for failed messages.

Honesty rules, all as named states rather than guesses: no `sub_id` recorded → "SIM not recorded" (explained); SIM removed since → "That SIM isn't in this phone now"; no Phone permission → the row itself asks for it. OEA never prints a SIM name it could not read.

Supporting changes:

- Provider reads (`querySms`) request `sub_id`, `date_sent`, type, read and status by name with a fallback to basic columns if an OEM provider rejects the full projection; unknown columns read as "not recorded" instead of crashing. Unread counting now only counts incoming messages.
- `OeaSmsDeliveryReceiver` records the subscription from the `SMS_DELIVER` intent (historical and current extra names) into `sub_id`, so messages OEA receives as default are labelled from day one, and skips the alert for muted addresses.
- Manifest: `READ_PHONE_NUMBERS` (optional, only for the number line) and an `ACTION_VIEW` `sms:`/`smsto:` filter alongside `SENDTO`, so "message this number" links from other apps open the conversation with any text prefilled.
- New icons: `oic_send`, `oic_sim_card`, `oic_zoom_in`, `oic_zoom_out`.
- Still true and still said in-app: MMS pictures/group content are not downloaded by OEA, so the default-app warning stays.

## EMAGE zoom

Zoom was *implemented* but disabled by its caller: the viewer's page view set `scaleType = FIT_CENTER` on `OeaZoomImageView`, which renders through its own matrix — any other scale type makes `ImageView` ignore that matrix, so pinch and double-tap updated numbers nobody drew. The size maths also read the drawable's **bounds** (which `ImageView` sizes to the view) instead of its intrinsic size, and the fit scale was only computed on clamp, not on draw.

`OeaZoomImageView` is rewritten around those traps:

- it coerces every `setScaleType` call to `MATRIX`, so a caller cannot unhook zoom again;
- intrinsic size only, fit scale recomputed on every apply;
- correct centre-anchored focus maths (`tx' = c − (c − tx)·grow`), pinch to 8×, double-tap cycling 1× → 2.5× → 5× → 1× with a short animation;
- panning hands the gesture back to a parent pager at the picture's horizontal edge, so "swipe to next photo" still works from a zoomed edge;
- `zoomBy()` / `resetAnimated()` / `zoomScale` for on-screen controls.

The viewer now also has **visible + / − / fit buttons with a live percentage**, fading with the rest of the chrome, and a one-time "Pinch or double-tap to zoom" hint. The OEA-pictures viewer (Gallery shelves) swaps its centre-cropped still for the same zoomable view (rendered larger, 900×640, so zooming has pixels to show), and the slideshow inherits working zoom.

## EMAGE month sections

`OeaMediaLibrary.days()` bucketed by **day at every age** but labelled buckets older than 31 days with only `MMMM yyyy` — twenty busy October days became twenty sections all named "October 2026" with no date anywhere. The rule now lives in `OeaMediaDateGroups` (pure `java.util`, unit-tested):

- Today, Yesterday as themselves;
- the **current month day by day**, each heading carrying its date ("Monday, 19 October", "5 October");
- **every earlier month as exactly one section** ("September 2026", "October 2025") — the month is named once and everything from that month is inside it.

Day-age maths is rounded instead of exact-millisecond, so a 23/25-hour DST day no longer shifts Yesterday.

## Tests

- `OeaMediaDateGroupsTest` — the duplicate-October regression (two days of one past month = one bucket, start = the 1st), current-month day buckets, Yesterday across a month boundary.
- `OeaMessageDatesTest` — thread/sparator/full labels including cross-year cases.
- `OeaSimLabelsTest` — SIM label wording and the no-duplicate-carrier rule.
- `OeaSmsThreadIndexTest` — plus unread counting and newest-message SIM/type tracking.

## Verification checklist (on device)

- Dual-SIM phone: receive one SMS per SIM, long-press each → correct "Received on SIM n • network"; send from SIM 2 via the chip → bubble meta and details say SIM 2; remove a SIM → its messages say the SIM is not present, not a wrong name.
- Single-SIM phone: no SIM chip in the composer; details still name the SIM/network.
- Deny Phone permission: details ask for it; everything else still works; replies still leave on the conversation's last SIM.
- Old messages with no `sub_id`: details say "SIM not recorded".
- EMAGE: pinch, double-tap cycle, +/−/fit buttons, percentage updates; pan to an edge and keep swiping to change photo; a past month appears exactly once; OEA-pictures viewer zooms too.
- TalkBack: message bubbles announce sender, text, time and SIM, with a long-press hint.

## Known limits, stated plainly

- Group MMS remains unimplemented (unchanged); the default-app sheet says so before asking.
- A sent message's "Delivered" only shows when the provider recorded a delivery status; OEA does not request delivery reports, so most sent rows correctly show no delivery line.
