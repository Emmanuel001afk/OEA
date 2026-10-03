package app.lawnchair.oea.callblocker

import android.Manifest
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallScreeningService

class OeaCallScreeningService : CallScreeningService() {
    override fun onScreenCall(callDetails: Call.Details) {
        val number = callDetails.handle?.schemeSpecificPart?.let(OeaCallBlockEngine::normalize) ?: ""
        if (!OeaCallBlockEngine.screen(this, callDetails)) {
            respondToCall(callDetails, CallResponse.Builder().build())
            return
        }
        OeaCallBlockRules.recordBlocked(this, number)
        respondToCall(callDetails, CallResponse.Builder()
            .setDisallowCall(true).setRejectCall(true).setSkipNotification(true)
            .setSkipCallLog(false).build())
    }
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

    fun enabled(context: Context) = context.getSharedPreferences(PREFS, 0).getBoolean(ENABLED, false)
    fun setEnabled(context: Context, value: Boolean) =
        context.getSharedPreferences(PREFS, 0).edit().putBoolean(ENABLED, value).apply()

    fun setRules(context: Context, exact: Set<String>, prefix: Set<String>, suffix: Set<String>) {
        context.getSharedPreferences(PREFS, 0).edit()
            .putStringSet(EXACT, exact.map(::normalize).filter(String::isNotEmpty).toSet())
            .putStringSet(PREFIX, prefix.map(::digitsRule).filter { it.isNotEmpty() }.filter { it.length <= 5 }.toSet())
            .putStringSet(SUFFIX, suffix.map(::digitsRule).filter { it.isNotEmpty() }.filter { it.length <= 5 }.toSet())
            .apply()
    }
    fun getExact(context: Context) = context.getSharedPreferences(PREFS, 0).getStringSet(EXACT, emptySet()).orEmpty()
    fun getPrefix(context: Context) = context.getSharedPreferences(PREFS, 0).getStringSet(PREFIX, emptySet()).orEmpty()
    fun getSuffix(context: Context) = context.getSharedPreferences(PREFS, 0).getStringSet(SUFFIX, emptySet()).orEmpty()
    fun allowContacts(context: Context) = context.getSharedPreferences(PREFS, 0).getBoolean(ALLOW_CONTACTS, true)
    fun allowStarred(context: Context) = context.getSharedPreferences(PREFS, 0).getBoolean(ALLOW_STARRED, true)
    fun setAllowContacts(context: Context, value: Boolean) =
        context.getSharedPreferences(PREFS, 0).edit().putBoolean(ALLOW_CONTACTS, value).apply()
    fun setAllowStarred(context: Context, value: Boolean) =
        context.getSharedPreferences(PREFS, 0).edit().putBoolean(ALLOW_STARRED, value).apply()

    fun shouldBlock(context: Context, number: String): Boolean {
        if (!enabled(context)) return false
        if (allowContacts(context) && isInContacts(context, number, false)) return false
        if (allowStarred(context) && isInContacts(context, number, true)) return false
        val p = context.getSharedPreferences(PREFS, 0)
        val exact = p.getStringSet(EXACT, emptySet()).orEmpty()
        val prefix = p.getStringSet(PREFIX, emptySet()).orEmpty()
        val suffix = p.getStringSet(SUFFIX, emptySet()).orEmpty()
        return exact.contains(number) || prefix.any { number.startsWith(it) } || suffix.any { number.endsWith(it) }
    }

    fun matches(context: Context, number: String): Boolean {
        val p = context.getSharedPreferences(PREFS, 0)
        val exact = p.getStringSet(EXACT, emptySet()).orEmpty()
        val prefix = p.getStringSet(PREFIX, emptySet()).orEmpty()
        val suffix = p.getStringSet(SUFFIX, emptySet()).orEmpty()
        return exact.contains(number) || prefix.any { number.startsWith(it) } || suffix.any { number.endsWith(it) }
    }

    fun recordBlocked(context: Context, number: String) {
        val p = context.getSharedPreferences(PREFS, 0)
        val old = p.getStringSet(HISTORY, emptySet()).orEmpty().toMutableSet()
        old.add(number + "|" + System.currentTimeMillis())
        while (old.size > 100) old.remove(old.first())
        p.edit().putStringSet(HISTORY, old).apply()
    }

    private fun isInContacts(context: Context, number: String, starredOnly: Boolean): Boolean {
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) return false
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        val projection = arrayOf(ContactsContract.PhoneLookup._ID, ContactsContract.PhoneLookup.STARRED)
        return runCatching {
            context.contentResolver.query(uri, projection, null, null, null)?.use { c: Cursor ->
                if (!c.moveToFirst()) return@use false
                !starredOnly || c.getInt(c.getColumnIndexOrThrow(ContactsContract.PhoneLookup.STARRED)) != 0
            } ?: false
        }.getOrDefault(false)
    }
    private fun normalize(value: String) = value.filter(Char::isDigit).takeLast(15)
    private fun digitsRule(value: String) = value.filter(Char::isDigit).take(5)
}
