package com.oea.launcher.multitask

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.provider.Settings
import android.view.WindowManager
import com.oea.launcher.model.OeaAppInfo
import com.oea.launcher.data.OeaDataStore

/**
 * Requests a real Android freeform task. OEA does not pretend that an overlay or
 * an app icon is a running floating app: the target app remains its own Android task.
 * Android/OEM window policy is the final authority and may ignore launch bounds.
 */
object OeaMultitaskLauncher {
    data class Result(
        val success: Boolean,
        val message: String,
        val freeformAdvertised: Boolean = false
)

    /**
     * Freeform needs either the system feature or Developer options →
     * "Enable freeform windows" (then a restart on most phones).
     */
    fun freeformEnabled(context: Context): Boolean {
        val pm = context.packageManager
        if (pm.hasSystemFeature("android.software.freeform_window_management")) return true
        val resolver = context.contentResolver
        return runCatching { Settings.Global.getInt(resolver, "enable_freeform_support", 0) != 0 }.getOrDefault(false)
    }

    fun openDeveloperOptions(context: Context): Boolean = runCatching {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    fun launchFloating(context: Context, app: OeaAppInfo): Result {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return Result(false, "Floating tasks require Android 7.0 or newer.")
        }

        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(app.packageName)
            ?: return Result(false, "This app has no launchable activity.")

        val freeformAdvertised = pm.hasSystemFeature("android.software.freeform_window_management") ||
            freeformEnabled(context)
        val bounds = calculateLaunchBounds(context)
            ?: return Result(false, "OEA could not determine the screen size for a floating window.", freeformAdvertised)

        // Do not use CLEAR_TOP: it can destroy/recreate an existing app task and
        // does not help freeform placement. NEW_TASK keeps the app in Android's task system.
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return runCatching {
            val options = ActivityOptions.makeBasic()
            options.launchBounds = bounds
            val bundle = options.toBundle()
            // Phones keep their display in full-screen mode, where Android ignores
            // launch bounds unless the task itself asks for freeform windowing.
            bundle.putInt(KEY_LAUNCH_WINDOWING_MODE, WINDOWING_MODE_FREEFORM)
            context.startActivity(launchIntent, bundle)
            val sizeLabel = OeaDataStore.get(context).floatingWindowSize()
            val message = if (freeformAdvertised) {
                "Requested a $sizeLabel floating window for ${app.label}. If Android grants freeform, use its system title bar to move and its resize edge/handle to resize; this phone may ignore freeform requests."
            } else {
                "${app.label} will open full screen: this phone's manufacturer has freeform windows turned off. To allow floating windows, turn on Developer options → \"Enable freeform windows\" and restart. OEA's own floating window works without freeform — turn it on in Settings › Multitasking. Split screen works on most phones without it."
            }
            Result(true, message, freeformAdvertised)
        }.getOrElse { error ->
            Result(
                false,
                "Android rejected the floating-window request: ${error.message ?: error.javaClass.simpleName}",
                freeformAdvertised
)
        }
    }

    private const val KEY_LAUNCH_WINDOWING_MODE = "android.activity.windowingMode"
    private const val WINDOWING_MODE_FREEFORM = 5

    private fun calculateLaunchBounds(context: Context): Rect? {
        val width: Int
        val height: Int

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val manager = context.getSystemService(WindowManager::class.java) ?: return null
            val bounds = manager.currentWindowMetrics.bounds
            width = bounds.width()
            height = bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val display = (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
                ?.defaultDisplay ?: return null
            val size = Point()
            @Suppress("DEPRECATION")
            display.getRealSize(size)
            width = size.x
            height = size.y
        }

        if (width <= 0 || height <= 0) return null

        // Offer distinct launch bounds as a starting affordance; actual dragging
        // and resizing stay system-owned and can be disabled by an OEM.
        val density = context.resources.displayMetrics.density
        val (preferredWidthDp, preferredHeightDp) = when (OeaDataStore.get(context).floatingWindowSize()) {
            "small" -> 320 to 440
            "medium" -> 420 to 560
            "large" -> 520 to 700
            else -> 420 to 560
        }
        val horizontalMargin = (24f * density).toInt()
        val verticalMargin = (32f * density).toInt()
        val preferredWidth = (preferredWidthDp * density).toInt()
        val preferredHeight = (preferredHeightDp * density).toInt()
        val availableWidth = (width - horizontalMargin * 2).coerceAtLeast(1)
        val availableHeight = (height - verticalMargin * 2).coerceAtLeast(1)
        val floatingWidth = preferredWidth.coerceAtMost(availableWidth)
        val floatingHeight = preferredHeight.coerceAtMost(availableHeight)
        val left = ((width - floatingWidth) / 2).coerceAtLeast(0)
        val top = ((height - floatingHeight) / 4).coerceAtLeast(0)
        return Rect(left, top, left + floatingWidth, top + floatingHeight)
    }
}
