package com.oea.launcher.split

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

object OeaSplitLauncher {
    data class Result(val success: Boolean, val message: String)

    fun launchPair(context: Context, first: String, second: String): Result {
        if (first.isBlank() || second.isBlank() || first == second) return Result(false, "Choose two different apps.")
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT < 24) return Result(false, "Android 7.0 or newer is required for split screen.")
        val a = pm.getLaunchIntentForPackage(first) ?: return Result(false, "The first app cannot be launched.")
        val b = pm.getLaunchIntentForPackage(second) ?: return Result(false, "The second app cannot be launched.")
        a.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        b.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return runCatching {
            context.startActivity(a)
            context.startActivity(b)
            Result(true, "Split-screen pair requested. Android controls the final layout.")
        }.getOrElse {
            Result(false, "Android/OEM rejected split screen: ${it.message ?: "unsupported"}")
        }
    }
}
