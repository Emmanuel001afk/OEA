package com.oea.launcher.multitask.projection

import android.content.Context

/**
 * Preferences and small helpers for OEA's live app windows (experimental).
 *
 * Live windows present a *real installed application* on an app-created
 * virtual display inside an OEA floating window, while the user's foreground
 * app keeps the physical screen. Interaction happens through accessibility
 * node actions — the only input channel Android grants an ordinary app into
 * a second application on a secondary display.
 *
 * The feature is OFF by default and every launch is verified: if the target
 * does not really land on the private display, OEA says so instead of
 * pretending.
 */
object OeaLiveWindows {
    private const val PREFS = "oea_live_windows"
    private const val KEY_ENABLED = "live_windows_enabled"

    /** True when the user turned live app windows on in OEA Settings. */
    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, on).apply()
        if (!on) OeaLiveWindowManager.stopAll()
    }

    /** Status line for settings surfaces. */
    fun statusSummary(context: Context): String {
        val running = OeaLiveWindowManager.list()
        return when {
            running.isEmpty() -> "No live windows"
            else -> running.joinToString(" · ") { it.describe() }
        }
    }

    /** Honest preconditions, used for settings copy. */
    fun ready(): Boolean = OeaLiveWindowManager.accessibilityService() != null
}
