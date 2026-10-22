package com.oea.launcher.multitask

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.provider.Settings

/**
 * Entry point for OEA's Floating Multitasking Window.
 *
 * The window itself lives in [OeaFloatingWindowService], an
 * AccessibilityService, using Android's accessibility-overlay window type.
 * This permission is separate from OEA Assistant, and it only grants OEA's
 * own overlay surface—not the ability to embed arbitrary apps. This object is what the rest of
 * OEA talks to: it answers "is the feature on?", "is the service
 * bound?", opens the right Settings page, and forwards show/hide
 * commands to the running service.
 *
 * Two switches are involved, on purpose:
 *   1. Android's Accessibility switch for "OEA Floating Window"
 *      (the permission to host the overlay and filter key events);
 *   2. OEA's own preference ([setEnabled]), which decides whether
 *      the window should actually be drawn once the service binds.
 */
object OeaFloatingWindows {
    private const val PREFS = "oea_floating_window"
    private const val KEY_ENABLED = "floating_window_enabled"
    private const val KEY_DEFAULT_MODE = "floating_window_default_mode"

    /** The selected default is explicit; never silently request the other service as fallback. */
    const val MODE_NATIVE = "native"
    const val MODE_OEA = "oea"
    fun defaultMode(context: Context): String =
        prefs(context).getString(KEY_DEFAULT_MODE, MODE_NATIVE)?.takeIf { it == MODE_NATIVE || it == MODE_OEA } ?: MODE_NATIVE
    fun setDefaultMode(context: Context, mode: String) {
        prefs(context).edit().putString(KEY_DEFAULT_MODE, if (mode == MODE_OEA) MODE_OEA else MODE_NATIVE).apply()
    }
    private const val KEY_WIDTH = "floating_window_width"
    private const val KEY_HEIGHT = "floating_window_height"
    private const val KEY_X = "floating_window_x"
    private const val KEY_Y = "floating_window_y"

    /** The service component, used for Settings deep-links and checks. */
    fun component(context: Context) = ComponentName(context, OeaFloatingWindowService::class.java)

    /** True when the user wants the floating window on. */
    fun enabled(context: Context) = prefs(context).getBoolean(KEY_ENABLED, false)

    /** Routes floating-window requests through the user's selected default. */
    fun launchDefault(context: Context, app: com.oea.launcher.model.OeaAppInfo): OeaMultitaskLauncher.Result {
        if (defaultMode(context) == MODE_NATIVE) return OeaMultitaskLauncher.launchFloating(context, app)
        if (!isAccessibilityEnabled(context)) {
            return OeaMultitaskLauncher.Result(false,
                "OEA floating workspace is your selected default, but its optional accessibility service is off. Enable OEA Floating Window in OEA Settings only if you want to use this method.")
        }
        setEnabled(context, true)
        val service = instance()
        if (service == null) return OeaMultitaskLauncher.Result(false,
            "OEA Floating Window accessibility is enabled, but Android has not connected the service yet. Reopen OEA Settings and check its status.")
        service.expand()
        return OeaMultitaskLauncher.Result(true,
            "Opened OEA Floating Workspace. Tap an app to request a real Android freeform task; Android/OEM policy may refuse or ignore that request. The accessibility overlay itself cannot contain another app's Activity.")
    }

    /** Turns the OEA-side preference on/off. */
    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        // If Android has not bound the service yet, the window appears
        // as soon as the user finishes enabling it in Accessibility.
        val service = instance()
        if (enabled) service?.expand() else service?.collapse()
    }

    /** True when Android has bound the accessibility service right now. */
    fun isRunning(context: Context): Boolean = instance() != null

    /** True when the user has granted OEA Floating Window in Accessibility. */
    fun isAccessibilityEnabled(context: Context): Boolean {
        val me = component(context).flattenToString()
        val enabled = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it.trim())?.flattenToString() == me }
    }

    /**
     * True when the service is enabled AND its config asks for the
     * capabilities OEA needs (key filtering, not-important views).
     */
    fun isFullyConfigured(context: Context): Boolean {
        if (!isAccessibilityEnabled(context)) return false
        return runCatching {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager
            val enabled = am?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                ?: return false
            enabled.any { it.id == component(context).flattenToString() }
        }.getOrDefault(false)
    }

    /** Opens Android's Accessibility page, focused on this service. */
    fun openSetup(context: Context) {
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            val details = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").apply {
                putExtra(Intent.EXTRA_COMPONENT_NAME, component(context).flattenToString())
                addFlags(flags)
            }
            if (runCatching { context.startActivity(details) }.isSuccess) return
        }
        runCatching {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(flags))
        }
    }

    /** Collapses the window (keeps the service alive). */
    fun collapse() = instance()?.collapse()

    /** Expands the window. */
    fun expand() = instance()?.expand()

    /** True while the floating window is actually on screen. */
    fun isWindowShowing(): Boolean = instance()?.isWindowShowing() == true

    /** Persists the resting position after a drag/fling. */
    internal fun savePosition(context: Context, x: Int, y: Int) {
        prefs(context).edit().putInt(KEY_X, x).putInt(KEY_Y, y).apply()
    }

    /** Persists the size after a corner-resize gesture. */
    internal fun saveSize(context: Context, width: Int, height: Int) {
        prefs(context).edit().putInt(KEY_WIDTH, width).putInt(KEY_HEIGHT, height).apply()
    }

    /**
     * Restores the last geometry (width, height, x, y) into [callback].
     * Falls back to the defaults (0,0 = the service docks itself) when
     * nothing was saved yet or the saved values are impossible.
     */
    internal fun restoreGeometry(context: Context, callback: (width: Int, height: Int, x: Int?, y: Int?) -> Unit) {
        val p = prefs(context)
        val width = p.getInt(KEY_WIDTH, 0)
        val height = p.getInt(KEY_HEIGHT, 0)
        val x = p.getInt(KEY_X, Int.MIN_VALUE)
        val y = p.getInt(KEY_Y, Int.MIN_VALUE)
        callback(
            if (width > 0) width else 0,
            if (height > 0) height else 0,
            if (x != Int.MIN_VALUE) x else null,
            if (y != Int.MIN_VALUE) y else null
        )
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun instance(): OeaFloatingWindowService? = OeaFloatingWindowService.instance()
}
