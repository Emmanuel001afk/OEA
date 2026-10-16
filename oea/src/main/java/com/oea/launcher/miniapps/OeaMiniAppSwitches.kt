package com.oea.launcher.miniapps

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * Turns each mini-app on or off on its own.
 *
 * "Off" really switches the app's launcher component off with Android's
 * PackageManager, so it leaves the app drawer, search, Recents and the Home
 * picker like an uninstalled app would, without deleting its data. "On" puts
 * it back. Nothing is removed and nothing is restarted: Android keeps the
 * state across reboots and updates.
 *
 * The EMAGE switch also covers the old Gallery alias, so a Home icon pinned
 * before the merge disappears together with EMAGE.
 */
object OeaMiniAppSwitches {

    private const val PREFS = "oea_miniapp_switches"
    private const val TAG = "OeaMiniAppSwitches"

    /** Old component names that open a mini-app and follow its switch. */
    private val aliases = mapOf("emage" to listOf("com.oea.launcher.miniapps.OeaGalleryActivity"))

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** True unless the user turned this mini-app off. Every mini-app starts on. */
    fun isOn(context: Context, id: String): Boolean = prefs(context).getBoolean(id, true)

    fun countOn(context: Context): Int = OeaMiniApps.all.count { isOn(context, it.id) }

    /** Saves the choice and applies it to the launcher components. Returns false when Android refused. */
    fun set(context: Context, id: String, on: Boolean): Boolean {
        val app = OeaMiniApps.byId(id) ?: return false
        prefs(context).edit().putBoolean(id, on).apply()
        val components = listOf(ComponentName(context, app.activity)) +
            (aliases[id].orEmpty().map { ComponentName(context, it) })
        var ok = true
        components.forEach { component ->
            val state = if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            ok = runCatching {
                context.packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
                true
            }.onFailure { Log.w(TAG, "Could not switch ${component.shortClassName}", it) }
                .getOrDefault(false) && ok
        }
        return ok
    }

    /** Whether Android actually reports the component as enabled (used to show the truth in Settings). */
    fun isEnabledInAndroid(context: Context, id: String): Boolean {
        val app = OeaMiniApps.byId(id) ?: return false
        return runCatching {
            val state = context.packageManager.getComponentEnabledSetting(ComponentName(context, app.activity))
            state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED &&
                state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
        }.getOrDefault(true)
    }
}
