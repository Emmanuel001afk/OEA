package com.oea.launcher.miniapps

/**
 * The rules behind Messaging that can be written down without a phone: what a
 * reply actually sends, what the character counter says about it, when a bubble
 * is a failure, and which notification a conversation owns.
 *
 * These are the places a messaging app is silently wrong. Every one of them was
 * wrong here, and none of them crashed:
 *
 *  * a reply quote up to 120 characters long was prepended to the message and
 *    never counted, so "10 remaining" was a two-part SMS;
 *  * a message the network refused kept its optimistic `SENT` row, so the thread
 *    said Sent, the toast said "tap the failed bubble", and there was no failed
 *    bubble to tap;
 *  * a confirmed delivery was listened for and thrown away, so the details
 *    sheet's "Delivered" row could never appear;
 *  * every incoming SMS was posted under `System.currentTimeMillis()`, so a
 *    conversation left one notification per message.
 *
 * The type and status numbers mirror `android.provider.Telephony.Sms` (they are
 * that class's own values) and are repeated here so this object — and its tests
 * — need no Android framework at all, exactly like [OeaSmsMatcher].
 */
internal object OeaMessagingFacts {

    // ------------------------------------------------------------ Sms.TYPE mirrors
    const val TYPE_INBOX = 1
    const val TYPE_SENT = 2
    const val TYPE_DRAFT = 3
    const val TYPE_OUTBOX = 4
    const val TYPE_FAILED = 5
    const val TYPE_QUEUED = 6

    // ---------------------------------------------------------- Sms.STATUS mirrors
    const val STATUS_NONE = -1
    const val STATUS_COMPLETE = 0
    const val STATUS_PENDING = 64
    const val STATUS_FAILED = 32

    /** Where a send report carries the message it belongs to. */
    const val EXTRA_BODY = "oea.sms.report.body"
    const val EXTRA_ADDRESS = "oea.sms.report.address"

    /** The longest piece of the original a reply quotes. */
    const val QUOTE_LIMIT = 120

    /** Under one segment, the counter only appears when this few characters are left. */
    const val COUNTER_WARNING = 25

    // ------------------------------------------------------------------ composing

    /**
     * What actually goes down the wire for [typed] while replying to [quoted]:
     * the quote in curly quotes, a newline, then the typed text.
     *
     * This is the single source of the sent body. The counter used to count only
     * the typed text while the send prepended up to 123 more characters, so a
     * message the composer called "one SMS, 10 remaining" arrived as two parts.
     */
    fun composeBody(typed: String, quoted: String?): String = quotePrefix(quoted) + typed

    /** The `“…”` line a reply starts with, or empty when there is nothing quoted. */
    fun quotePrefix(quoted: String?): String {
        val text = quoted?.trim().orEmpty()
        if (text.isEmpty()) return ""
        val clipped = if (text.length > QUOTE_LIMIT) text.take(QUOTE_LIMIT).trimEnd() + "…" else text
        return "\u201C$clipped\u201D\n"
    }

    /**
     * The counter beside the send button, or null when it should stay hidden.
     *
     * [parts] and [remaining] are `SmsMessage.calculateLength`'s first and third
     * results — computed from the *composed* body, not the typed text.
     */
    fun counterLabel(parts: Int, remaining: Int): String? = when {
        parts <= 0 -> null
        parts <= 1 && remaining > COUNTER_WARNING -> null
        parts <= 1 -> remaining.toString()
        else -> "$remaining • $parts SMS"
    }

    // ------------------------------------------------------------------- statuses

    /**
     * True for a message that did not reach the network: either the platform wrote
     * the row as failed, or OEA filed it optimistically as sent and the network
     * then refused it.
     */
    fun isFailed(type: Int, status: Int): Boolean =
        type == TYPE_FAILED || (type == TYPE_SENT && status == STATUS_FAILED)

    /**
     * The suffix a bubble puts after its time: ` • Not sent — tap to retry`,
     * ` • Delivered`, ` • Draft`, ` • Sending…`, or nothing at all.
     */
    fun statusNote(type: Int, status: Int): String = when {
        type == TYPE_DRAFT -> " • Draft"
        type == TYPE_OUTBOX || type == TYPE_QUEUED -> " • Sending…"
        isFailed(type, status) -> " • Not sent — tap to retry"
        type == TYPE_SENT && status == STATUS_COMPLETE -> " • Delivered"
        type == TYPE_SENT && status == STATUS_PENDING -> " • Sent"
        else -> ""
    }

    // --------------------------------------------------------------- notifications

    /**
     * The notification slot one conversation owns.
     *
     * Every address form of the same correspondent shares it (`+234…`, `0708…`,
     * `MTN ` and `mtn`), so replying twice updates one notification instead of
     * stacking two. The old code used `System.currentTimeMillis() % Int.MAX_VALUE`
     * as the id, which is a new slot for every message that ever arrives.
     */
    fun notificationId(address: String): Int {
        val key = OeaSmsMatcher.canonicalAddressKey(address).ifEmpty { address.trim().lowercase() }
        val hash = key.hashCode() and 0x7FFFFFFF
        return if (hash == 0) 1 else hash
    }

    /** The name a notification leads with: the contact, else the number, else a fallback. */
    fun notificationTitle(contactName: String?, address: String): String {
        val name = contactName?.trim().orEmpty()
        if (name.isNotEmpty() && name != address.trim()) return name
        val number = address.trim()
        return if (number.isEmpty()) "New message" else number
    }
}
