package com.oea.launcher.split

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.oea.launcher.assistant.OeaAssistant

/**
 * Opens two apps side by side using Android's own split-screen.
 *
 * Android 12L+ (API 32): the first app opens full screen, then the second is
 * launched with FLAG_ACTIVITY_LAUNCH_ADJACENT, which makes Android enter split
 * screen with the second app in the other half.
 *
 * Android 7–12 (API 24–31): LAUNCH_ADJACENT only works once split screen is
 * active, so OEA Assistant first docks the first app (the system split-screen
 * toggle), then the second app fills the other half.
 */
object OeaSplitLauncher {
    data class Result(val success: Boolean, val message: String)

    private val main = Handler(Looper.getMainLooper())

    fun launchPair(context: Context, first: String, second: String): Result {
        if (first.isBlank() || second.isBlank() || first == second) return Result(false, "Choose two different apps.")
        val pm = context.packageManager
        val a = pm.getLaunchIntentForPackage(first) ?: return Result(false, "The first app cannot be launched.")
        val b = pm.getLaunchIntentForPackage(second) ?: return Result(false, "The second app cannot be launched.")
        return launchPair(context, a, b)
    }

    fun launchPair(context: Context, firstIntent: Intent, secondIntent: Intent): Result {
        if (Build.VERSION.SDK_INT < 24) return Result(false, "Android 7.0 or newer is required for split screen.")
        val app = context.applicationContext
        // Preserve existing task state; CLEAR_TOP can destroy/recreate an app's
        // activity stack and is not required to request adjacent placement.
        val a = Intent(firstIntent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val b = Intent(secondIntent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
        val modern = Build.VERSION.SDK_INT >= 32
        val canDock = !modern && OeaAssistant.isConnected()
        return runCatching {
            app.startActivity(a)
            when {
                modern -> main.postDelayed({ runCatching { app.startActivity(b) } }, SECOND_APP_DELAY_MS)
                canDock -> main.postDelayed({
                    OeaAssistant.toggleSplitScreen()
                    main.postDelayed({ runCatching { app.startActivity(b) } }, DOCK_SETTLE_MS)
                }, SECOND_APP_DELAY_MS)
                else -> main.postDelayed({ runCatching { app.startActivity(b) } }, SECOND_APP_DELAY_MS)
            }
            when {
                modern || canDock -> Result(true, "Opening split screen…")
                else -> Result(true, "Turn on OEA Assistant so OEA can enter split screen on this Android version.")
            }
        }.getOrElse {
            Result(false, "Android/OEM rejected split screen: ${it.message ?: "unsupported"}")
        }
    }

    private const val SECOND_APP_DELAY_MS = 520L
    private const val DOCK_SETTLE_MS = 900L
}
