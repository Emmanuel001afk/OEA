package com.oea.launcher.split

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Handler
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityOptionsCompat

object OeaSplitModeUtil {
    private const val KEY_LAUNCH_WINDOWING_MODE = "android.activity.launchWindowingMode"
    private const val KEY_SPLIT_SCREEN_CREATE_MODE = "android.activity.splitScreenCreateMode"
    private const val WINDOWING_MODE_SPLIT_SCREEN_PRIMARY = 3
    private const val SPLIT_SCREEN_CREATE_MODE_TOP_OR_LEFT = 0

    @RequiresApi(Build.VERSION_CODES.P)
    fun launch(activity: Activity, top: Intent, bottom: Intent): Boolean = runCatching {
        val options = ActivityOptionsCompat.makeBasic().toBundle()?.apply {
            putInt(KEY_LAUNCH_WINDOWING_MODE, WINDOWING_MODE_SPLIT_SCREEN_PRIMARY)
            putInt(KEY_SPLIT_SCREEN_CREATE_MODE, SPLIT_SCREEN_CREATE_MODE_TOP_OR_LEFT)
        }
        top.addCategory(Intent.CATEGORY_LAUNCHER)
        bottom.addCategory(Intent.CATEGORY_LAUNCHER)
        top.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        bottom.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        Handler().postDelayed({ activity.startActivities(arrayOf(bottom, top), options) }, 100)
        true
    }.getOrDefault(false)
}
