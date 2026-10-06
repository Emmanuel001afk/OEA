package com.oea.launcher.applock

import android.content.Context

object OeaAppLockStore {
    private const val PREFS = "oea_app_lock"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PACKAGES = "packages"
    private const val KEY_UNLOCKED_UNTIL = "unlocked_until"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(context: Context) = prefs(context).getBoolean(KEY_ENABLED, false)
    fun setEnabled(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_ENABLED, value).apply()
    fun packages(context: Context) = prefs(context).getStringSet(KEY_PACKAGES, emptySet()).orEmpty()
    fun setPackages(context: Context, value: Set<String>) =
        prefs(context).edit().putStringSet(KEY_PACKAGES, value).apply()
    fun isLocked(context: Context, packageName: String) =
        enabled(context) && packageName in packages(context)
    fun unlockFor(context: Context, durationMs: Long = 5 * 60_000L) =
        prefs(context).edit().putLong(KEY_UNLOCKED_UNTIL, System.currentTimeMillis() + durationMs).apply()
    fun isTemporarilyUnlocked(context: Context) =
        System.currentTimeMillis() < prefs(context).getLong(KEY_UNLOCKED_UNTIL, 0L)
}
