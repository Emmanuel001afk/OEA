package com.oea.launcher.multitask

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import com.oea.launcher.model.OeaAppInfo

object OeaMultitaskLauncher {
    fun launchFloating(context: Context, app: OeaAppInfo): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(app.packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val sideMargin = (width * 0.06f).toInt()
        val top = (height * 0.12f).toInt()
        val floatingWidth = (width * 0.72f).toInt().coerceAtLeast((width * 0.60f).toInt())
        val floatingHeight = (height * 0.58f).toInt().coerceAtLeast((height * 0.45f).toInt())
        val left = width - floatingWidth - sideMargin
        val bounds = Rect(left, top, left + floatingWidth, top + floatingHeight)
        return runCatching {
            if (Build.VERSION.SDK_INT >= 24) {
                val options = ActivityOptions.makeBasic()
                options.launchBounds = bounds
                context.startActivity(intent, options.toBundle())
            } else {
                context.startActivity(intent)
            }
            true
        }.getOrDefault(false)
    }
}
