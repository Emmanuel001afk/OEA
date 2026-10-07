package com.oea.launcher.applock

import android.content.Context
import java.security.MessageDigest

object OeaAppLockStore {
    private const val PREFS = "oea_app_lock"
    private const val KEY_LOCKED = "locked_apps"
    private const val KEY_PIN = "pin_hash"

    fun lockedApps(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_LOCKED, emptySet()).orEmpty()

    fun isLocked(context: Context, key: String) = lockedApps(context).contains(key)

    fun setLocked(context: Context, key: String, locked: Boolean) {
        val values = lockedApps(context).toMutableSet()
        if (locked) values.add(key) else values.remove(key)
        prefs(context).edit().putStringSet(KEY_LOCKED, values).apply()
    }

    fun hasPin(context: Context) = prefs(context).contains(KEY_PIN)

    fun setPin(context: Context, pin: String) {
        prefs(context).edit().putString(KEY_PIN, hash(pin)).apply()
    }

    fun verifyPin(context: Context, pin: String): Boolean =
        prefs(context).getString(KEY_PIN, null) == hash(pin)

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
