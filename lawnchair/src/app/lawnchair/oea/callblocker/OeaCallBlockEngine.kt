package app.lawnchair.oea.callblocker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telecom.Call
import androidx.core.content.ContextCompat

object OeaCallBlockEngine {
    fun screen(context: Context, details: Call.Details): Boolean {
        if (!OeaCallBlockRules.enabled(context)) return false
        val handle = details.handle?.schemeSpecificPart ?: return false
        if (handle.isBlank()) return true
        val number = normalize(handle)
        if (number.isBlank()) return false
        if (OeaCallBlockRules.allowContacts(context) && isContact(context, number, false)) return false
        if (OeaCallBlockRules.allowStarred(context) && isContact(context, number, true)) return false
        return OeaCallBlockRules.matches(context, number)
    }

    fun normalize(value: String): String = value.filter(Char::isDigit).takeLast(15)

    private fun isContact(context: Context, number: String, starredOnly: Boolean): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return false
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        val projection = arrayOf(ContactsContract.PhoneLookup._ID, ContactsContract.PhoneLookup.STARRED)
        return runCatching {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use false
                !starredOnly || cursor.getInt(cursor.getColumnIndexOrThrow(ContactsContract.PhoneLookup.STARRED)) != 0
            } ?: false
        }.getOrDefault(false)
    }
}
