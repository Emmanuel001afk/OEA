package com.oea.launcher.miniapps

/** Pure, uncapped conversation grouping over rows returned by Android's SMS provider. */
internal class OeaSmsThreadIndex {
    private data class Summary(
        var displayAddress: String,
        var snippet: String,
        var date: Long,
        var count: Int = 0,
        var unreadCount: Int = 0,
        var latestSubscriptionId: Int = -1,
        var latestType: Int = 0,
        var primaryThreadId: Long = -1L,
        val threadIds: LinkedHashSet<Long> = linkedSetOf(),
        val rawAddresses: LinkedHashSet<String> = linkedSetOf()
    )

    private val byKey = linkedMapOf<String, Summary>()
    private val threadIdToKey = HashMap<Long, String>()
    private val phoneKeyToGroup = HashMap<String, String>()

    fun add(
        addressValue: String?,
        body: String?,
        date: Long,
        isRead: Boolean,
        subscriptionId: Int = -1,
        type: Int = 0,
        threadId: Long = -1L
    ) {
        val raw = addressValue.orEmpty()
        val trimmed = raw.trim()
        if (trimmed.isEmpty() && threadId <= 0L) return
        val address = trimmed.ifEmpty { "Thread #$threadId" }

        // Preserve strict trimmed-address grouping when callers omit threadId (unit
        // tests and ad-hoc grouping), and unify provider thread_ids, padded shortcodes,
        // dual-SIM split threads, and E.164/local variants for real Telephony.Sms rows.
        val senderKey = if (threadId >= 0L && trimmed.isNotEmpty()) OeaSmsMatcher.canonicalAddressKey(trimmed) else null
        val key = when {
            threadId > 0L && threadIdToKey.containsKey(threadId) -> threadIdToKey.getValue(threadId)
            senderKey != null && phoneKeyToGroup.containsKey(senderKey) -> phoneKeyToGroup.getValue(senderKey)
            threadId > 0L -> "t:$threadId"
            else -> address
        }

        val summary = byKey[key]
        if (summary == null) {
            val created = Summary(
                displayAddress = address,
                snippet = body.orEmpty().take(140),
                date = date,
                count = 1,
                unreadCount = if (isRead) 0 else 1,
                latestSubscriptionId = subscriptionId,
                latestType = type,
                primaryThreadId = if (threadId > 0L) threadId else -1L
            )
            if (threadId > 0L) {
                created.threadIds.add(threadId)
                threadIdToKey[threadId] = key
            }
            if (raw.isNotEmpty()) created.rawAddresses.add(raw)
            if (trimmed.isNotEmpty()) created.rawAddresses.add(trimmed)
            if (senderKey != null) phoneKeyToGroup.putIfAbsent(senderKey, key)
            byKey[key] = created
        } else {
            summary.count++
            if (!isRead) summary.unreadCount++
            if (threadId > 0L) {
                if (summary.primaryThreadId <= 0L) summary.primaryThreadId = threadId
                summary.threadIds.add(threadId)
                threadIdToKey.putIfAbsent(threadId, key)
            }
            if (raw.isNotEmpty()) summary.rawAddresses.add(raw)
            if (trimmed.isNotEmpty()) {
                summary.rawAddresses.add(trimmed)
                // Prefer the richer international form (+234...) over local/placeholder display text.
                if (summary.displayAddress.startsWith("Thread #") ||
                    (trimmed.startsWith("+") && !summary.displayAddress.startsWith("+"))
                ) {
                    summary.displayAddress = trimmed
                }
            }
            if (senderKey != null) phoneKeyToGroup.putIfAbsent(senderKey, key)
            if (date > summary.date) {
                summary.date = date
                summary.snippet = body.orEmpty().take(140)
                summary.latestSubscriptionId = subscriptionId
                summary.latestType = type
                if (threadId > 0L) summary.primaryThreadId = threadId
            }
        }
    }

    fun threads(): List<OeaMessagingApp.SmsThread> = byKey.values.map { summary ->
        OeaMessagingApp.SmsThread(
            address = summary.displayAddress,
            snippet = summary.snippet,
            date = summary.date,
            count = summary.count,
            read = summary.unreadCount == 0,
            unreadCount = summary.unreadCount,
            latestSubscriptionId = summary.latestSubscriptionId,
            latestType = summary.latestType,
            threadId = summary.primaryThreadId,
            threadIds = summary.threadIds.toSet(),
            rawAddresses = summary.rawAddresses.toSet()
        )
    }.sortedByDescending { it.date }
}

/**
 * Pure phone-number, shortcode, alphanumeric-sender, OTP and category helpers
 * shared by Messaging and unit tests (zero Android framework dependency).
 */
internal object OeaSmsMatcher {

    private val OTP_KEYWORDS = listOf(
        "otp", "code", "verification", "verify", "passcode", "pin", "token",
        "auth", "login", "sign in", "sign-in", "one-time", "one time", "2fa", "security"
    )
    /**
     * A bare 4–8 digit run that is not part of a longer number.
     *
     * The lookahead deliberately allows `.` and `,` *after* the digits: real
     * verification messages end in a full stop ("Your OTP is 482913."), and
     * excluding punctuation there dropped the code from most of them. Amounts
     * are still excluded by the lookbehind, which rejects a run sitting behind
     * a digit, a currency mark or a thousands separator ("₦20,000", "1,234").
     */
    private val OTP_CODE_REGEX = Regex("""(?<![A-Za-z0-9#₦$£€.,])(\d{4,8})(?![A-Za-z0-9%])""")
    private val TXN_KEYWORDS = listOf(
        "acct", "account", "credited", "debited", "debit", "credit", "bal", "balance",
        "transfer", "txn", "trf", "ngn", "usd", "payment", "receipt", "withdrawal", "pos", "airtime", "data bundle"
    )
    private val PROMO_KEYWORDS = listOf(
        "promo", "discount", "offer", "win ", "bonus", "cashback", "subscribe",
        "unsubscribe", "deal", "sale", "dial *", "free data", "limited time"
    )

    /** True when [address] is an alphanumeric sender ID like "GTBank", "MTN" or "Google". */
    fun isAlphanumericSender(address: String): Boolean =
        address.any { it.isLetter() }

    /** Strips formatting characters from a dialable address, keeping digits and leading '+'. */
    fun cleanDialable(address: String): String {
        val trimmed = address.trim()
        if (trimmed.isEmpty() || isAlphanumericSender(trimmed)) return trimmed
        val out = StringBuilder(trimmed.length)
        trimmed.forEachIndexed { idx, ch ->
            when {
                ch.isDigit() -> out.append(ch)
                ch == '+' && idx == 0 -> out.append(ch)
                (ch == '*' || ch == '#') && trimmed.length <= 12 -> out.append(ch)
            }
        }
        return out.toString().ifEmpty { trimmed }
    }

    /** Digits only, used for tail comparison between local (0708...) and E.164 (+234708...). */
    fun digitsOnly(address: String): String {
        if (isAlphanumericSender(address)) return ""
        val out = StringBuilder(address.length)
        for (ch in address) if (ch.isDigit()) out.append(ch)
        return out.toString()
    }

    /**
     * Canonical comparison key for standard subscriber phone numbers (>= 9 digits
     * after removing trunk '0'), leaving shortcodes (e.g. "312", "131", "55019")
     * and alphanumeric senders untouched so distinct shortcodes never collide.
     */
    fun phoneMatchKey(address: String): String? {
        val trimmed = address.trim()
        if (trimmed.isEmpty() || isAlphanumericSender(trimmed)) return null
        if (trimmed.contains('*') || trimmed.contains('#')) return null
        val digits = digitsOnly(trimmed)
        if (digits.length < 10) return null
        val stripped = digits.trimStart('0')
        if (stripped.length < 9) return null
        // Last 10 national digits match local 11-digit Nigerian numbers (07089007507 -> 7089007507),
        // international (+2347089007507 -> 7089007507), US/UK/IN 10-digit numbers, etc.
        return "p:" + stripped.takeLast(10)
    }

    /** Last 7 digits for SQL `LIKE '%xxxxxxx'` candidate pre-filtering on subscriber numbers. */
    fun sqlPhoneSuffix(address: String): String? {
        if (phoneMatchKey(address) == null) return null
        val stripped = digitsOnly(address).trimStart('0')
        return if (stripped.length >= 7) stripped.takeLast(7) else null
    }

    /** Stable preference/storage key so +2347089007507 and 07089007507 share pin/read/mute state. */
    fun canonicalAddressKey(address: String): String {
        val trimmed = address.trim()
        if (trimmed.isEmpty()) return ""
        phoneMatchKey(trimmed)?.let { return it }
        if (isAlphanumericSender(trimmed)) return "a:${trimmed.lowercase()}"
        val clean = cleanDialable(trimmed)
        return "s:${clean.lowercase()}"
    }

    /**
     * True when two SMS `address` values belong to the same correspondent:
     *  - padded shortcodes ("312 " vs "312")
     *  - alphanumeric IDs differing only in case/whitespace ("MTN " vs "mtn")
     *  - formatted, local, and E.164 phone numbers ("0708 900 7507" vs "+2347089007507")
     */
    fun sameAddress(first: String?, second: String?): Boolean {
        val a = first?.trim().orEmpty()
        val b = second?.trim().orEmpty()
        if (a.isEmpty() || b.isEmpty()) return false
        if (a.equals(b, ignoreCase = true)) return true
        val aAlpha = isAlphanumericSender(a)
        val bAlpha = isAlphanumericSender(b)
        if (aAlpha || bAlpha) return false
        val ca = cleanDialable(a)
        val cb = cleanDialable(b)
        if (ca.isNotEmpty() && ca == cb) return true
        val ka = phoneMatchKey(a)
        val kb = phoneMatchKey(b)
        if (ka != null && kb != null) return ka == kb
        // Handle 9-digit national subscriber numbers (e.g. Ghana, Kenya, Francophone Africa).
        val da = digitsOnly(a).trimStart('0')
        val db = digitsOnly(b).trimStart('0')
        if (da.length >= 9 && db.length >= 9) {
            return da.takeLast(9) == db.takeLast(9)
        }
        return false
    }

    /** Extracts a 4–8 digit one-time verification code from an SMS body, or null if none. */
    fun extractOtpCode(body: String?): String? {
        val text = body?.trim().orEmpty()
        if (text.length < 4) return null
        val lower = text.lowercase()
        if (OTP_KEYWORDS.none { lower.contains(it) }) return null
        val matches = OTP_CODE_REGEX.findAll(text).map { it.groupValues[1] }.toList()
        if (matches.isEmpty()) return null
        // Avoid accidentally picking a bare 4-digit year like 2026 when a 6-digit code is also present.
        return matches.firstOrNull { it.length in 5..8 }
            ?: matches.firstOrNull { code ->
                val n = code.toIntOrNull() ?: 0
                n !in 1990..2035 || matches.size == 1
            }
    }

    /** Classifies a thread into `"personal"`, `"otp"`, `"transactions"`, or `"offers"`. */
    fun categoryOf(address: String, snippet: String, hasSavedContact: Boolean): String {
        val lower = snippet.lowercase()
        if (extractOtpCode(snippet) != null) return "otp"
        if (!hasSavedContact && TXN_KEYWORDS.any { lower.contains(it) }) return "transactions"
        if (!hasSavedContact && PROMO_KEYWORDS.any { lower.contains(it) }) return "offers"
        if (hasSavedContact) return "personal"
        val digits = digitsOnly(address)
        if (!isAlphanumericSender(address) && digits.length >= 10) return "personal"
        if (TXN_KEYWORDS.any { lower.contains(it) }) return "transactions"
        return "offers"
    }
}
