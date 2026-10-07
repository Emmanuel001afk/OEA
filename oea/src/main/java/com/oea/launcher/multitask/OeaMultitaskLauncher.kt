package com.oea.launcher.multitask

import android.app.ActivityOptions
import android.app.WindowConfiguration
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import com.oea.launcher.model.OeaAppInfo

object OeaMultitaskLauncher {
    data class Result(val success: Boolean, val message: String)

    fun launchFloating(context: Context, app: OeaAppInfo): Result {
        val pm = context.packageManager
        val intent = pm.getLaunchIntentForPackage(app.packageName)
            ?: return Result(false, "That app has no launchable activity.")
        if (Build.VERSION.SDK_INT < 24) return Result(false, "Android 7.0 or newer is required for floating tasks.")
        if (!pm.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT)) {
            return Result(false, "This device does not advertise Android freeform-window support.")
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val floatingWidth = (width * 0.72f).toInt().coerceAtLeast((width * 0.60f).toInt())
        val floatingHeight = (height * 0.58f).toInt().coerceAtLeast((height * 0.45f).toInt())
        val left = ((width - floatingWidth) / 2).coerceAtLeast(0)
        val top = (height * 0.10f).toInt()
        val bounds = Rect(left, top, left + floatingWidth, top + floatingHeight)
        return runCatching {
            val options = ActivityOptions.makeBasic()
            options.launchBounds = bounds
            if (Build.VERSION.SDK_INT >= 26) options.setLaunchWindowingMode(WindowConfiguration.WINDOWING_MODE_FREEFORM)
            context.startActivity(intent, options.toBundle())
            Result(true, "Floating task requested.")
        }.getOrElse {
            Result(false, "Android/OEM rejected the floating task: ${it.message ?: "unsupported"}")
        }
    }
}
