package com.oea.launcher.multitask

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
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
        // Do not hard-stop on FEATURE_FREEFORM_WINDOW_MANAGEMENT. Some OEMs expose
        // floating-window behavior without advertising the framework feature flag.
        // The system gets the actual request and decides whether the window can float.
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
            context.startActivity(intent, options.toBundle())
            Result(true, if (pm.hasSystemFeature("android.software.freeform_window_management")) "Floating task requested." else "Floating-window request sent to Android.")
        }.getOrElse {
            Result(false, "Android/OEM rejected the floating task: ${it.message ?: "unsupported"}")
        }
    }
}
