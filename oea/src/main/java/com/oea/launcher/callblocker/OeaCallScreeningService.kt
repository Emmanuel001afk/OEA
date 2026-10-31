package com.oea.launcher.callblocker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import androidx.core.content.ContextCompat
import kotlin.math.abs

class OeaCallScreeningService : CallScreeningService() {
    /**
     * Telecom calls this for every incoming call and expects an answer. A fault
     * here must never end the process: the call is let through (the safe default
     * when OEA cannot decide) and the fault is recorded.
     */
    override fun onScreenCall(callDetails: Call.Details) {
        if (!com.oea.launcher.fault.OeaFaultShield.guard("call-blocker:screen") { screen(callDetails) }) {
            runCatching { respondToCall(callDetails, CallResponse.Builder().build()) }
        }
    }

    private fun screen(callDetails: Call.Details) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            callDetails.callDirection == Call.Details.DIRECTION_OUTGOING
        ) {
            respondToCall(callDetails, CallResponse.Builder().build())
            return
        }
        val rawNumber = callDetails.handle?.schemeSpecificPart.orEmpty()
        val number = normalize(rawNumber)
        val presentation = callDetails.handlePresentation
        val verdict = OeaCallBlockRules.screeningVerdict(this, rawNumber, presentation)
        // Every decision is recorded (kept, disposable, separate from the
        // rules) so the blocker screen can answer the important diagnostic
        // question after the fact: was the screening service invoked at all?
        OeaCallBlockRules.noteDecision(this, verdict)
        OeaCallBlockRules.noteScreenedAt(this)
        OeaCallBlockRules.pruneHistory(this)
        OeaCallBlockRules.ensureDailyCleanup(this)
        if (!verdict.blocked) {
            respondToCall(callDetails, CallResponse.Builder().build())
            return
        }
        val label = if (number.isEmpty()) "Unknown/private" else number
        OeaCallBlockRules.recordBlocked(this, label)
        respondToCall(
            callDetails,
            CallResponse.Builder()
                .setDisallowCall(true)
                .setRejectCall(true)
                .setSkipNotification(true)
                .setSkipCallLog(true)
                .build()
        )
    }

    private fun normalize(value: String): String = OeaCallBlockRules.normalizeNumber(value)
}

object OeaCallBlockRules {
    private const val PREFS = "oea_call_blocker"
    private const val ENABLED = "enabled"
    private const val BLOCK_UNKNOWN = "block_unknown"
    private const val EXACT = "exact"
    private const val PREFIX = "prefix"
    private const val SUFFIX = "suffix"
    private const val ALLOW_CONTACTS = "allow_contacts"
    private const val ALLOW_STARRED = "allow_starred"
    private const val ALLOW_LIST = "allow_list"

    // History (and only history) lives in its OWN preferences file. Cleanup
    // can therefore never touch a rule, an exemption or the enabled state,
    // no matter what it removes or when it runs.
    private const val HISTORY_PREFS = "oea_call_blocker_history"
    private const val HISTORY = "history"
    private const val DECISIONS = "decisions"
    private const val LAST_SCREENED_AT = "last_screened_at"

    // Retention is a SETTING, so it lives with the rules — but it only ever
    // governs the history file above.
    private const val RETENTION = "history_retention"

    /** Hard safety cap so history can never grow without bound on any mode. */
    private const val HARD_CAP = 1000

    /** Decision-log cap (diagnostics, not history). */
    private const val DECISION_CAP = 50

    data class BlockedCall(val number: String, val timestamp: Long)

    /** One screening decision, recorded whether the call was blocked or not. */
    data class ScreeningDecision(val number: String, val blocked: Boolean, val reason: String, val timestamp: Long)

    /** Why a call was blocked or allowed, in one short readable string. */
    data class Verdict(val blocked: Boolean, val reason: String)

    // ------------------------------------------------------------ retention

    /** Retention modes for blocked-call history. */
    val RETENTIONS = listOf(
        "cap100" to "Keep latest 100",
        "d7" to "Delete after 7 days",
        "d30" to "Delete after 30 days",
        "d90" to "Delete after 90 days",
        "forever" to "Keep everything (up to 1000)"
    )

    fun retention(context: Context): String {
        val id = prefs(context).getString(RETENTION, "cap100") ?: "cap100"
        return if (RETENTIONS.any { it.first == id }) id else "cap100"
    }

    fun setRetention(context: Context, id: String) {
        val safe = if (RETENTIONS.any { it.first == id }) id else "cap100"
        prefs(context).edit().putString(RETENTION, safe).apply()
        pruneHistory(context)
    }

    /**
     * Applies the selected retention to the history (and nothing else).
     * Returns how many entries were removed. Cheap: when nothing expires,
     * no write happens at all.
     */
    fun pruneHistory(context: Context): Int {
        val p = historyPrefs(context)
        val entries = p.getStringSet(HISTORY, emptySet()).orEmpty()
        if (entries.isEmpty()) return 0
        val mode = retention(context)
        val now = System.currentTimeMillis()
        val maxAge = when (mode) {
            "d7" -> 7L * 24 * 60 * 60 * 1000
            "d30" -> 30L * 24 * 60 * 60 * 1000
            "d90" -> 90L * 24 * 60 * 60 * 1000
            else -> Long.MAX_VALUE
        }
        val cap = if (mode == "cap100") 100 else HARD_CAP
        var kept = entries.filter { entry ->
            val ts = entry.substringAfterLast('|').toLongOrNull() ?: Long.MAX_VALUE
            now - ts <= maxAge
        }.toMutableList()
        var removed = entries.size - kept.size
        if (kept.size > cap) {
            kept.sortByDescending { it.substringAfterLast('|').toLongOrNull() ?: 0L }
            removed += kept.size - cap
            kept = kept.take(cap).toMutableList()
        }
        if (removed > 0) {
            p.edit().putStringSet(HISTORY, kept.toSet()).apply()
        }
        return removed
    }

    // ------------------------------------------------------------ decisions

    /** Records a screening decision (kept for diagnostics, separate from rules). */
    fun noteDecision(context: Context, verdict: Verdict, number: String? = null, at: Long = System.currentTimeMillis()) {
        val p = historyPrefs(context)
        val normalized = normalize(number.orEmpty()).ifEmpty { "Unknown/private" }
        val old = p.getStringSet(DECISIONS, emptySet()).orEmpty().toMutableSet()
        val flag = if (verdict.blocked) "B" else "A"
        old.add("$flag|${verdict.reason.replace('|', ' ')}|$normalized|$at")
        while (old.size > DECISION_CAP) {
            val oldest = old.minByOrNull { it.substringAfterLast('|').toLongOrNull() ?: Long.MIN_VALUE } ?: break
            old.remove(oldest)
        }
        p.edit().putStringSet(DECISIONS, old).apply()
    }

    /** Recent screening decisions, newest first. */
    fun recentDecisions(context: Context): List<ScreeningDecision> =
        historyPrefs(context).getStringSet(DECISIONS, emptySet()).orEmpty()
            .mapNotNull { raw ->
                val parts = raw.split('|')
                if (parts.size != 4) return@mapNotNull null
                val ts = parts[3].toLongOrNull() ?: return@mapNotNull null
                ScreeningDecision(parts[2], parts[0] == "B", parts[1], ts)
            }.sortedByDescending { it.timestamp }

    /** When the system last invoked OEA's screening service (0 = never). */
    fun lastScreenedAt(context: Context): Long =
        historyPrefs(context).getLong(LAST_SCREENED_AT, 0L)

    fun noteScreenedAt(context: Context, at: Long = System.currentTimeMillis()) {
        historyPrefs(context).edit().putLong(LAST_SCREENED_AT, at).apply()
    }

    // ------------------------------------------------------------ daily alarm

    /**
     * Schedules the once-a-day history cleanup (inexact, battery-friendly).
     * Re-scheduled from every screening event, from the blocker screen and
     * after reboot, because Android alarms do not survive reboots. It only
     * ever prunes the history file — never rules.
     */
    fun ensureDailyCleanup(context: Context) {
        val am = context.getSystemService(android.app.AlarmManager::class.java) ?: return
        val intent = android.content.Intent(context, OeaCallBlockerCleanupReceiver::class.java)
        val pi = android.app.PendingIntent.getBroadcast(
            context, 4701, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        runCatching {
            val first = System.currentTimeMillis() + 6L * 60 * 60 * 1000
            am.setInexactRepeating(android.app.AlarmManager.RTC_WAKEUP, first, android.app.AlarmManager.INTERVAL_DAY, pi)
        }
    }

    fun enabled(context: Context) = prefs(context).getBoolean(ENABLED, true)
    fun setEnabled(context: Context, value: Boolean) = prefs(context).edit().putBoolean(ENABLED, value).apply()
    fun blockUnknown(context: Context) = prefs(context).getBoolean(BLOCK_UNKNOWN, false)
    fun setBlockUnknown(context: Context, value: Boolean) = prefs(context).edit().putBoolean(BLOCK_UNKNOWN, value).apply()

    fun setRules(context: Context, exact: Set<String>, prefix: Set<String>, suffix: Set<String>) {
        prefs(context).edit()
            .putStringSet(EXACT, exact.map(::normalize).filter(String::isNotEmpty).toSet())
            .putStringSet(PREFIX, prefix.map(::digitsRule).filter { it.isNotEmpty() && it.length <= 5 }.toSet())
            .putStringSet(SUFFIX, suffix.map(::digitsRule).filter { it.isNotEmpty() && it.length <= 5 }.toSet())
            .apply()
    }

    fun getExact(context: Context) = prefs(context).getStringSet(EXACT, emptySet()).orEmpty()
    fun getPrefix(context: Context) = prefs(context).getStringSet(PREFIX, emptySet()).orEmpty()
    fun getSuffix(context: Context) = prefs(context).getStringSet(SUFFIX, emptySet()).orEmpty()
    fun allowContacts(context: Context) = prefs(context).getBoolean(ALLOW_CONTACTS, false)
    fun allowStarred(context: Context) = prefs(context).getBoolean(ALLOW_STARRED, false)
    fun setAllowContacts(context: Context, value: Boolean) = prefs(context).edit().putBoolean(ALLOW_CONTACTS, value).apply()
    fun setAllowStarred(context: Context, value: Boolean) = prefs(context).edit().putBoolean(ALLOW_STARRED, value).apply()
    fun getAllowList(context: Context): Set<String> = prefs(context).getStringSet(ALLOW_LIST, emptySet()).orEmpty()

    fun setAllowList(context: Context, numbers: Set<String>) {
        prefs(context).edit().putStringSet(ALLOW_LIST, numbers.map(::normalize).filter(String::isNotEmpty).toSet()).apply()
    }

    fun blockedHistory(context: Context): List<BlockedCall> = historyPrefs(context).getStringSet(HISTORY, emptySet()).orEmpty()
        .mapNotNull { entry ->
            val split = entry.lastIndexOf('|')
            if (split <= 0) null else {
                val timestamp = entry.substring(split + 1).toLongOrNull() ?: return@mapNotNull null
                BlockedCall(entry.substring(0, split), timestamp)
            }
        }.sortedByDescending { it.timestamp }

    fun clearBlockedHistory(context: Context) {
        // History AND the disposable decision log — never the rules.
        historyPrefs(context).edit().remove(HISTORY).remove(DECISIONS).apply()
    }

    /**
     * The full screening decision with the reason it produced, so the log can
     * say more than "blocked" or "allowed".
     */
    fun screeningVerdict(context: Context, rawNumber: String?, presentation: Int): Verdict {
        if (!enabled(context)) return Verdict(false, "blocking paused")
        val normalized = normalize(rawNumber.orEmpty())
        val isPrivate = presentation == TelecomManager.PRESENTATION_RESTRICTED ||
            presentation == TelecomManager.PRESENTATION_UNKNOWN ||
            presentation == TelecomManager.PRESENTATION_PAYPHONE
        if (normalized.isNotEmpty() && shouldBlock(context, rawNumber.orEmpty())) {
            return Verdict(true, "matched a blocking rule")
        }
        if ((normalized.isEmpty() || isPrivate) && blockUnknown(context)) {
            if (normalized.isNotEmpty() && matchesAnyExact(numberVariants(rawNumber.orEmpty()), getAllowList(context))) {
                return Verdict(false, "on the allow list")
            }
            return Verdict(true, "unknown/private number")
        }
        return Verdict(false, "no rule matched")
    }

    fun shouldBlockIncomingCall(context: Context, rawNumber: String?, presentation: Int): Boolean =
        screeningVerdict(context, rawNumber, presentation).blocked

    fun shouldBlock(context: Context, number: String): Boolean {
        if (!enabled(context)) return false
        val variants = numberVariants(number)
        if (variants.isEmpty()) return false
        if (matchesAnyExact(variants, getAllowList(context))) return false
        val p = prefs(context)
        val exact = p.getStringSet(EXACT, emptySet()).orEmpty()
        val prefix = p.getStringSet(PREFIX, emptySet()).orEmpty()
        val suffix = p.getStringSet(SUFFIX, emptySet()).orEmpty()
        // Explicit exact-number rules always block unless the number is on the allow-list.
        if (matchesAnyExact(variants, exact)) return true
        val matchesWildcard = prefix.any { rule -> rule.isNotEmpty() && variants.any { it.startsWith(rule) } } ||
            suffix.any { rule -> rule.isNotEmpty() && variants.any { it.endsWith(rule) } }
        if (!matchesWildcard) return false
        // Contact exemptions protect saved/starred contacts from broad prefix/suffix rules.
        if (allowContacts(context) && isInContacts(context, number, false)) return false
        if (allowStarred(context) && isInContacts(context, number, true)) return false
        return true
    }

    fun isNumberBlocked(context: Context, number: String): Boolean {
        val variants = numberVariants(number)
        if (variants.isEmpty()) return false
        return matchesAnyExact(variants, getExact(context))
    }

    fun recordBlocked(context: Context, number: String) {
        val now = System.currentTimeMillis()
        val normalized = normalize(number).ifEmpty { "Unknown/private" }
        val recentDuplicate = blockedHistory(context).any {
            it.number == normalized && abs(now - it.timestamp) < 4000L
        }
        if (!recentDuplicate) {
            recordBlockedAt(context, normalized, now)
        }
    }

    fun recordBlockedAt(context: Context, number: String, timestamp: Long) {
        val p = historyPrefs(context)
        val old = p.getStringSet(HISTORY, emptySet()).orEmpty().toMutableSet()
        old.add(normalize(number).ifEmpty { "Unknown/private" } + "|" + timestamp)
        while (old.size > HARD_CAP) {
            val oldest = old.minByOrNull { it.substringAfterLast('|').toLongOrNull() ?: Long.MIN_VALUE } ?: break
            old.remove(oldest)
        }
        p.edit().putStringSet(HISTORY, old).apply()
    }

    fun normalizeNumber(value: String): String = normalize(value)

    internal fun numberVariants(value: String): Set<String> {
        val digits = normalize(value)
        if (digits.isEmpty()) return emptySet()
        val out = linkedSetOf(digits)
        if (digits.startsWith("0") && digits.length >= 8) {
            val national = digits.trimStart('0')
            if (national.isNotEmpty()) {
                out += national
                out += "234$national"
            }
        }
        if (digits.startsWith("234") && digits.length > 10) {
            val national = digits.removePrefix("234")
            if (national.isNotEmpty()) {
                out += national
                out += "0$national"
            }
        }
        if (digits.length > 10) {
            val last10 = digits.takeLast(10)
            out += last10
            out += "0$last10"
        }
        return out
    }

    private fun matchesAnyExact(candidateVariants: Set<String>, rules: Set<String>): Boolean {
        if (rules.isEmpty() || candidateVariants.isEmpty()) return false
        for (rule in rules) {
            val ruleVariants = numberVariants(rule)
            if (candidateVariants.any { it in ruleVariants }) return true
            if (candidateVariants.any { PhoneNumberUtils.compare(it, rule) }) return true
        }
        return false
    }

    private fun isInContacts(context: Context, number: String, starredOnly: Boolean): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return false
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        val projection = arrayOf(ContactsContract.PhoneLookup._ID, ContactsContract.PhoneLookup.STARRED)
        return runCatching {
            context.contentResolver.query(uri, projection, null, null, null)?.use { c: Cursor ->
                if (!c.moveToFirst()) return@use false
                !starredOnly || c.getInt(c.getColumnIndexOrThrow(ContactsContract.PhoneLookup.STARRED)) != 0
            } ?: false
        }.getOrDefault(false)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun historyPrefs(context: Context) = context.getSharedPreferences(HISTORY_PREFS, Context.MODE_PRIVATE)
    private fun normalize(value: String) = value.filter(Char::isDigit).takeLast(15)
    private fun digitsRule(value: String) = value.filter(Char::isDigit).take(5)
}

/**
 * Runs the once-a-day history cleanup (and re-schedules it after reboot,
 * since Android alarms do not survive reboots). It prunes the blocked-call
 * history according to the retention the user selected — and touches
 * NOTHING else: rules, exemptions and the enabled state live in a different
 * file this receiver has no access path to.
 */
class OeaCallBlockerCleanupReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: android.content.Intent) {
        com.oea.launcher.fault.OeaFaultShield.guard("call-blocker:cleanup") {
            OeaCallBlockRules.pruneHistory(context)
            OeaCallBlockRules.ensureDailyCleanup(context)
        }
    }
}
