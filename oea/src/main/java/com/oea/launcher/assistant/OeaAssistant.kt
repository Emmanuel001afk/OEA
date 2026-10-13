package com.oea.launcher.assistant

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * Entry point for everything OEA Assistant can do. Callers never talk to the
 * service directly, so every action degrades gracefully when the user has not
 * enabled it yet.
 */
object OeaAssistant {
    private val listeners = LinkedHashSet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun component(context: Context) = ComponentName(context, OeaAssistantService::class.java)

    fun isConnected(): Boolean = OeaAssistantService.instance != null

    /** True when the user switched OEA Assistant on (even if Android has not bound it yet). */
    fun isEnabled(context: Context): Boolean {
        if (isConnected()) return true
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val me = component(context)
        return enabled.split(':').any { ComponentName.unflattenFromString(it.trim()) == me }
    }

    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }
    internal fun notifyStateChanged() {
        main.post { listeners.toList().forEach { runCatching { it() } } }
    }

    private fun perform(action: Int): Boolean =
        OeaAssistantService.instance?.performGlobalAction(action) == true

    fun lockScreen(): Boolean =
        Build.VERSION.SDK_INT >= 28 && perform(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)

    fun openNotifications(): Boolean = perform(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
    fun openQuickSettings(): Boolean = perform(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
    fun openRecents(): Boolean = perform(AccessibilityService.GLOBAL_ACTION_RECENTS)
    fun toggleSplitScreen(): Boolean = perform(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)

    fun isForceStopping(): Boolean = OeaAssistantService.instance?.isForceStopping() == true

    /**
     * Force-stops [packages] through Android's App info page. [returnTo] is
     * opened when the run finishes. Returns false when OEA Assistant is off or busy.
     */
    fun forceStop(
        packages: List<String>,
        returnTo: Intent?,
        callback: ((stopped: List<String>, failed: List<String>) -> Unit)? = null,
    ): Boolean = OeaAssistantService.instance?.startForceStop(packages, returnTo, callback) == true

    /** Opens the most direct Android page for switching OEA Assistant on. */
    fun openSetup(context: Context) {
        val flags = if (context is Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK
        if (Build.VERSION.SDK_INT >= 31) {
            val details = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                .putExtra(Intent.EXTRA_COMPONENT_NAME, component(context).flattenToString())
                .addFlags(flags)
            if (runCatching { context.startActivity(details) }.isSuccess) return
        }
        runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(flags)) }
    }

    /** Android 13+ blocks accessibility for side-loaded apps until "Allow restricted settings". */
    fun needsRestrictedSettingsHint(): Boolean = Build.VERSION.SDK_INT >= 33

    const val RESTRICTED_SETTINGS_HINT =
        "If Android shows \"Restricted setting\", open OEA's App info, tap ⋮ (top right) and choose " +
            "\"Allow restricted settings\". Then come back and switch on OEA Assistant."

    fun openOeaAppInfo(context: Context) {
        val flags = if (context is Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(flags),
            )
        }
    }
}

/** System-surface actions with the best available path on each device. */
object OeaSystemActions {
    fun expandNotifications(context: Context): Boolean =
        OeaAssistant.openNotifications() || statusBar(context, "expandNotificationsPanel")

    fun expandQuickSettings(context: Context): Boolean =
        OeaAssistant.openQuickSettings() || statusBar(context, "expandSettingsPanel")

    @SuppressLint("WrongConstant", "PrivateApi")
    private fun statusBar(context: Context, method: String): Boolean = runCatching {
        val service = context.getSystemService("statusbar") ?: return false
        Class.forName("android.app.StatusBarManager").getMethod(method).invoke(service)
        true
    }.getOrDefault(false)
}
