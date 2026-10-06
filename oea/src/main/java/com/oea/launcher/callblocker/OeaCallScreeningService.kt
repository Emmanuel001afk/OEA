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
        if (number.isEmpty() || !OeaCallBlockRules.shouldBlock(this, number)) {
            respondToCall(callDetails, CallResponse.Builder().build())
            return
        }
        OeaCallBlockRules.recordBlocked(this, number)
        respondToCall(callDetails, CallResponse.Builder().setDisallowCall(true).setRejectCall(true).setSkipNotification(true).setSkipCallLog(false).build())
    }
    private fun normalize(value: String): String = value.filter(Char::isDigit).takeLast(15)
}

object OeaCallBlockRules {
    private const val PREFS = "oea_call_blocker"
    private const val ENABLED = "enabled"
    private const val EXACT = "exact"
    private const val PREFIX = "prefix"
    private const val SUFFIX = "suffix"
    private const val ALLOW_CONTACTS = "allow_contacts"
    private const val ALLOW_STARRED = "allow_starred"
    private const val HISTORY = "history"

    fun enabled(context: Context) = prefs(context).getBoolean(ENABLED, false)
    fun setEnabled(context: Context, value: Boolean) = prefs(context).edit().putBoolean(ENABLED, value).apply()
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
    fun shouldBlock(context: Context, number: String): Boolean {
        if (!enabled(context)) return false
        if (allowContacts(context) && isInContacts(context, number, false)) return false
        if (allowStarred(context) && isInContacts(context, number, true)) return false
        val p = prefs(context)
        val exact = p.getStringSet(EXACT, emptySet()).orEmpty()
        val prefix = p.getStringSet(PREFIX, emptySet()).orEmpty()
        val suffix = p.getStringSet(SUFFIX, emptySet()).orEmpty()
        return exact.contains(number) || prefix.any(number::startsWith) || suffix.any(number::endsWith)
    }
    fun recordBlocked(context: Context, number: String) {
        val old = prefs(context).getStringSet(HISTORY, emptySet()).orEmpty().toMutableSet()
        old.add(number + "|" + System.currentTimeMillis())
        while (old.size > 100) old.remove(old.first())
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