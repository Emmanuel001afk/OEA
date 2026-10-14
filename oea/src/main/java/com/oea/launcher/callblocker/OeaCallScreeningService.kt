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
        if (!OeaCallBlockRules.shouldBlockIncomingCall(this, rawNumber, presentation)) {
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
    private const val HISTORY = "history"

    data class BlockedCall(val number: String, val timestamp: Long)

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

    fun blockedHistory(context: Context): List<BlockedCall> = prefs(context).getStringSet(HISTORY, emptySet()).orEmpty()
        .mapNotNull { entry ->
            val split = entry.lastIndexOf('|')
            if (split <= 0) null else {
                val timestamp = entry.substring(split + 1).toLongOrNull() ?: return@mapNotNull null
                BlockedCall(entry.substring(0, split), timestamp)
            }
        }.sortedByDescending { it.timestamp }

    fun clearBlockedHistory(context: Context) {
        prefs(context).edit().remove(HISTORY).apply()
    }

    fun shouldBlockIncomingCall(context: Context, rawNumber: String?, presentation: Int): Boolean {
        if (!enabled(context)) return false
        val normalized = normalize(rawNumber.orEmpty())
        val isPrivate = presentation == TelecomManager.PRESENTATION_RESTRICTED ||
            presentation == TelecomManager.PRESENTATION_UNKNOWN ||
            presentation == TelecomManager.PRESENTATION_PAYPHONE
        if (normalized.isNotEmpty() && shouldBlock(context, rawNumber.orEmpty())) {
            return true
        }
        if ((normalized.isEmpty() || isPrivate) && blockUnknown(context)) {
            if (normalized.isNotEmpty() && matchesAnyExact(numberVariants(rawNumber.orEmpty()), getAllowList(context))) {
                return false
            }
            return true
        }
        return false
    }

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
        val old = prefs(context).getStringSet(HISTORY, emptySet()).orEmpty().toMutableSet()
        old.add(normalize(number).ifEmpty { "Unknown/private" } + "|" + timestamp)
        while (old.size > 100) {
            val oldest = old.minByOrNull { it.substringAfterLast('|').toLongOrNull() ?: Long.MIN_VALUE } ?: break
            old.remove(oldest)
        }
        prefs(context).edit().putStringSet(HISTORY, old).apply()
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
    private fun normalize(value: String) = value.filter(Char::isDigit).takeLast(15)
    private fun digitsRule(value: String) = value.filter(Char::isDigit).take(5)
}
