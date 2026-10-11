package com.oea.launcher.multitask

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.view.WindowManager
import com.oea.launcher.model.OeaAppInfo

/**
 * Requests a real Android freeform task. OEA does not pretend that an overlay or
 * an app icon is a running floating app: the target app remains its own Android task.
 * Android/OEM window policy is the final authority and may ignore launch bounds.
 */
object OeaMultitaskLauncher {
    data class Result(
        val success: Boolean,
        val message: String,
        val freeformAdvertised: Boolean = false,
    )

    fun launchFloating(context: Context, app: OeaAppInfo): Result {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return Result(false, "Floating tasks require Android 7.0 or newer.")
        }

        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(app.packageName)
            ?: return Result(false, "This app has no launchable activity.")

        val freeformAdvertised = pm.hasSystemFeature("android.software.freeform_window_management")
        val bounds = calculateLaunchBounds(context)
            ?: return Result(false, "OEA could not determine the screen size for a floating window.", freeformAdvertised)

        // Do not use CLEAR_TOP: it can destroy/recreate an existing app task and
        // does not help freeform placement. NEW_TASK keeps the app in Android's task system.
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return runCatching {
            val options = ActivityOptions.makeBasic()
            options.launchBounds = bounds
            context.startActivity(launchIntent, options.toBundle())
            val message = if (freeformAdvertised) {
                "Android accepted the floating-window request. Use the system window controls to move or resize it. If it opens full-screen, the device or app declined freeform mode."
            } else {
                "Launch request sent. This device does not advertise freeform support; floating mode works only if its firmware enables it. If the app opens full-screen, Android did not honor the floating request."
            }
            Result(true, message, freeformAdvertised)
        }.getOrElse { error ->
            Result(
                false,
                "Android rejected the floating-window request: ${error.message ?: error.javaClass.simpleName}",
                freeformAdvertised,
            )
        }
    }

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

        // Leave enough room to see the underlying app and keep practical minimum
        // dimensions on small phones. launchBounds are screen pixels, not dp.
        val minWidth = (280f * context.resources.displayMetrics.density).toInt()
        val minHeight = (320f * context.resources.displayMetrics.density).toInt()
        val floatingWidth = (width * 0.78f).toInt().coerceIn(minWidth.coerceAtMost(width), width)
        val floatingHeight = (height * 0.66f).toInt().coerceIn(minHeight.coerceAtMost(height), height)
        val left = ((width - floatingWidth) / 2).coerceAtLeast(0)
        val top = ((height - floatingHeight) / 3).coerceAtLeast(0)
        return Rect(left, top, left + floatingWidth, top + floatingHeight)
    }
}
