package com.oea.launcher.split

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

object OeaSplitLauncher {
    fun launchPair(context: Context, first: String, second: String): Boolean {
        val pm = context.packageManager
        val a = pm.getLaunchIntentForPackage(first)?.apply { addCategory(Intent.CATEGORY_LAUNCHER) } ?: return false
        val b = pm.getLaunchIntentForPackage(second)?.apply { addCategory(Intent.CATEGORY_LAUNCHER) } ?: return false
        a.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        b.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
        return runCatching { context.startActivity(a); context.startActivity(b); true }.getOrDefault(false)
    }
}
