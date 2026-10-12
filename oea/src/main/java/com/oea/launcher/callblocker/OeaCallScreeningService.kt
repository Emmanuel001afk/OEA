package com.oea.launcher.callblocker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallScreeningService
import androidx.core.content.ContextCompat

class OeaCallScreeningService : CallScreeningService() {
    override fun onScreenCall(callDetails: Call.Details) {
        val number = callDetails.handle?.schemeSpecificPart?.let(::normalize).orEmpty()
        val blockUnknown = number.isEmpty() && OeaCallBlockRules.blockUnknown(this)
        if (!blockUnknown && (number.isEmpty() || !OeaCallBlockRules.shouldBlock(this, number))) {
            respondToCall(callDetails, CallResponse.Builder().build())
            return
        }
        OeaCallBlockRules.recordBlocked(this, number.ifEmpty { "Unknown/private" })
        respondToCall(callDetails, CallResponse.Builder().setDisallowCall(true).setRejectCall(true).setSkipNotification(true).setSkipCallLog(true).build())
    }
    private fun normalize(value: String): String = value.filter(Char::isDigit).takeLast(15)
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

    fun enabled(context: Context) = prefs(context).getBoolean(ENABLED, false)
    fun setEnabled(context: Context, value: Boolean) = prefs(context).edit().putBoolean(ENABLED, value).apply()
    fun blockUnknown(context: Context) = prefs(context).getBoolean(BLOCK_UNKNOWN, false)
    fun setBlockUnknown(context: Context, value: Boolean) = prefs(context).edit().putBoolean(BLOCK_UNKNOWN, value).apply()
    fun setRules(context: Context, exact: Set<String>, prefix: Set<String>, suffix: Set<String>) {
        prefs(context).edit()
            .putStringSet(EXACT, exact.map(::normalize).filter(String::isNotEmpty).toSet())
            .putStringSet(PREFIX, prefix.map(::digitsRule).filter { it.isNotEmpty() }.filter { it.length <= 5 }.toSet())
            .putStringSet(SUFFIX, suffix.map(::digitsRule).filter { it.isNotEmpty() }.filter { it.length <= 5 }.toSet())
            .apply()
    }
    fun getExact(context: Context) = prefs(context).getStringSet(EXACT, emptySet()).orEmpty()
    fun getPrefix(context: Context) = prefs(context).getStringSet(PREFIX, emptySet()).orEmpty()
    fun getSuffix(context: Context) = prefs(context).getStringSet(SUFFIX, emptySet()).orEmpty()
    fun allowContacts(context: Context) = prefs(context).getBoolean(ALLOW_CONTACTS, true)
    fun allowStarred(context: Context) = prefs(context).getBoolean(ALLOW_STARRED, true)
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
    fun clearBlockedHistory(context: Context) { prefs(context).edit().remove(HISTORY).apply() }
    fun shouldBlock(context: Context, number: String): Boolean {
        if (!enabled(context)) return false
        val normalized = normalize(number)
        if (normalized.isEmpty() || getAllowList(context).contains(normalized)) return false
        val p = prefs(context)
        val exact = p.getStringSet(EXACT, emptySet()).orEmpty()
        val prefix = p.getStringSet(PREFIX, emptySet()).orEmpty()
        val suffix = p.getStringSet(SUFFIX, emptySet()).orEmpty()
        // Contact exemptions must be evaluated before the blocking rule.
        // Otherwise an exact/prefix/suffix rule would always win and the exemption
        // controls would appear to work while never actually exempting contacts.
        if (allowContacts(context) && isInContacts(context, number, false)) return false
        if (allowStarred(context) && isInContacts(context, number, true)) return false
        return exact.contains(normalized) || prefix.any(normalized::startsWith) || suffix.any(normalized::endsWith)
    }
    fun recordBlocked(context: Context, number: String) = recordBlockedAt(context, number, System.currentTimeMillis())

    fun recordBlockedAt(context: Context, number: String, timestamp: Long) {
        val old = prefs(context).getStringSet(HISTORY, emptySet()).orEmpty().toMutableSet()
        old.add(normalize(number) + "|" + timestamp)
        while (old.size > 100) {
            val oldest = old.minByOrNull { it.substringAfterLast('|').toLongOrNull() ?: Long.MIN_VALUE } ?: break
            old.remove(oldest)
        }
        prefs(context).edit().putStringSet(HISTORY, old).apply()
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