package com.oea.launcher.split

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.widget.Toast
import com.oea.launcher.assistant.OeaAssistant

/**
 * Opens two apps side by side using Android's own split screen.
 *
 * Android only pairs an adjacent launch with the task that is *currently* in
 * front. Launching the second app on a fixed timer therefore fails whenever the
 * first app is still starting: the second one simply opens full screen on top
 * (and Back then flips between them). OEA instead:
 *
 * 1. opens the first app full screen;
 * 2. waits until Android reports it is really in front (OEA Assistant window
 *    events, else Usage access, else a cold-start-safe delay);
 * 3. Android 12L+ (API 32): opens the second app with
 *    FLAG_ACTIVITY_LAUNCH_ADJACENT, which makes Android enter split screen with
 *    the first app in the other half.
 *    Android 7–12 (API 24–31): LAUNCH_ADJACENT only works once split screen is
 *    active, so OEA Assistant first docks the first app with the system split
 *    toggle, then the second app fills the other half;
 * 4. with OEA Assistant, verifies that two app windows really share the screen
 *    and, if the device kept them full screen, recovers once through the system
 *    split-screen toggle.
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
        val modern = Build.VERSION.SDK_INT >= 32
        if (!modern && !OeaAssistant.isConnected()) {
            // Opening both apps without split support only stacks them full
            // screen, which is exactly what split screen is meant to avoid.
            return Result(false, "Turn on OEA Assistant so OEA can enter split screen on this Android version.")
        }
        // Preserve existing task state; CLEAR_TOP can destroy/recreate an app's
        // activity stack and is not required to request adjacent placement.
        val a = Intent(firstIntent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val b = Intent(secondIntent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
        return runCatching {
            app.startActivity(a)
            SplitSession(app, a, b, modern).start()
            Result(true, "Opening split screen…")
        }.getOrElse {
            Result(false, "Android/OEM rejected split screen: ${it.message ?: "unsupported"}")
        }
    }

    private class SplitSession(
        private val app: Context,
        private val first: Intent,
        private val second: Intent,
        private val modern: Boolean
) {
        private val firstPackage = first.component?.packageName ?: first.`package`
        private val launchedAt = System.currentTimeMillis()
        private var recovered = false

        fun start() {
            awaitFirstInFront { main.postDelayed({ openSecond() }, SETTLE_MS) }
        }

        private fun openSecond() {
            if (modern) {
                launch(second)
            } else {
                OeaAssistant.toggleSplitScreen()
                main.postDelayed({ launch(second) }, DOCK_SETTLE_MS)
            }
            if (OeaAssistant.isConnected()) main.postDelayed({ verify() }, VERIFY_DELAY_MS)
        }

        private fun verify() {
            if (OeaAssistant.isSplitScreenActive()) return
            if (recovered) {
                toast("Android kept these apps full screen. One of them may not support split screen.")
                return
            }
            recovered = true
            // The second app opened full screen. Dock it with the system split
            // toggle, then bring the first app into the other half.
            if (!OeaAssistant.toggleSplitScreen()) {
                toast("This device didn't allow split screen for these apps.")
                return
            }
            main.postDelayed({
                launch(Intent(first).addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT))
                main.postDelayed({ verify() }, VERIFY_DELAY_MS)
            }, DOCK_SETTLE_MS)
        }

        private fun launch(intent: Intent) {
            runCatching { app.startActivity(intent) }
                .onFailure { toast("Android/OEM rejected split screen: ${it.message ?: "unsupported"}") }
        }

        /** Calls [then] once the first app is really in front, or after a cold-start-safe fallback. */
        private fun awaitFirstInFront(then: () -> Unit) {
            val pkg = firstPackage
            if (pkg == null) {
                main.postDelayed(then, FALLBACK_DELAY_MS)
                return
            }
            if (OeaAssistant.isConnected() && OeaAssistant.watchForeground(pkg, FOREGROUND_TIMEOUT_MS) { then() }) return
            if (hasUsageAccess()) {
                pollUsage(pkg, SystemClock.uptimeMillis() + FOREGROUND_TIMEOUT_MS, then)
                return
            }
            main.postDelayed(then, FALLBACK_DELAY_MS)
        }

        private fun pollUsage(pkg: String, deadline: Long, then: () -> Unit) {
            if (resumedSinceLaunch(pkg) || SystemClock.uptimeMillis() >= deadline) {
                then()
                return
            }
            main.postDelayed({ pollUsage(pkg, deadline, then) }, USAGE_POLL_MS)
        }

        private fun resumedSinceLaunch(pkg: String): Boolean = runCatching {
            val usm = app.getSystemService(UsageStatsManager::class.java) ?: return false
            val events = usm.queryEvents(launchedAt - 1_000L, System.currentTimeMillis())
            val event = UsageEvents.Event()
            var seen = false
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val resumed = if (Build.VERSION.SDK_INT >= 29) {
                    event.eventType == UsageEvents.Event.ACTIVITY_RESUMED
                } else {
                    event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
                }
                if (resumed && event.packageName == pkg && event.timeStamp >= launchedAt - 250L) seen = true
            }
            seen
        }.getOrDefault(false)

        // One capability layer decides what OEA can read; this keeps split screen
        // in step with the Permissions & access screen instead of its own check.
        private fun hasUsageAccess(): Boolean = com.oea.launcher.privilege.OeaPrivileges.canReadUsage(app)

        private fun toast(message: String) {
            main.post { Toast.makeText(app, message, Toast.LENGTH_LONG).show() }
        }
    }

    /** Small pause after the first app is in front, so its task is fully resumed. */
    private const val SETTLE_MS = 220L
    private const val DOCK_SETTLE_MS = 900L
    private const val VERIFY_DELAY_MS = 1_400L
    private const val FOREGROUND_TIMEOUT_MS = 3_000L
    private const val FALLBACK_DELAY_MS = 1_100L
    private const val USAGE_POLL_MS = 120L
}
