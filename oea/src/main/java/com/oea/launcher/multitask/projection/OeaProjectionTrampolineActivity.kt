package com.oea.launcher.multitask.projection

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display

/**
 * The OEA activity that must already sit on the virtual display before the
 * target application is started, and must stay there as the launch source.
 *
 * Why this exists (AOSP multi-display launch policy, verified in the
 * Android 13 sources): an ordinary app may place *its own* activity on a
 * virtual display it created (it is the display owner), but it cannot aim a
 * *third-party* activity at that display with launchDisplayId. However, an
 * activity started with no launch options inherits the display of the task
 * it is launched into. So OEA:
 *
 *   1. puts this transparent activity on the virtual display;
 *   2. verifies it really landed there (OEMs can ignore launchDisplayId);
 *   3. only then starts the real target application from it, into the same
 *      task, so the target inherits the virtual display;
 *   4. keeps this activity beneath the target as the launch source record.
 *
 * The activity draws nothing and never appears to the user. If the OEM puts
 * it on the physical screen instead, it finishes itself immediately and the
 * launch is reported as failed — never silently degraded.
 *
 * Manifest requirements (all declared there):
 *   - android:allowEmbedded="true" — required for an activity to be placed
 *     on an untrusted (app-created) display at all;
 *   - android:resizeableActivity="true";
 *   - its own taskAffinity so the projected task never mixes with OEA's;
 *   - excludeFromRecents so the projected app leaves no phantom card.
 */
class OeaProjectionTrampolineActivity : Activity() {

    private var startedTarget = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        live.add(this)
        try {
            maybeStart("onCreate")
            window?.decorView?.post { maybeStart("post") }
            pollUntilStarted()
            window?.decorView?.postDelayed({
                if (!startedTarget && !isFinishing) {
                    val spec = OeaProjectionTrampolineSpec.current
                    if (spec != null) {
                        spec.reportFailure(
                            "OEA's starter landed on the phone screen (display ${currentDisplayId()}). " +
                                "This manufacturer does not let OEA place its starter on the virtual display."
                        )
                    }
                    finish()
                }
            }, SELF_TIMEOUT_MS)
        } catch (t: Throwable) {
            Log.w(TAG, "trampoline onCreate failed", t)
            OeaProjectionTrampolineSpec.current?.reportFailure(
                "Starter failed: ${t.message ?: t.javaClass.simpleName}"
            )
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        maybeStart("onResume")
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        maybeStart("onAttachedToWindow")
    }

    override fun onDestroy() {
        live.remove(this)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- launch

    private fun maybeStart(where: String) {
        val spec = OeaProjectionTrampolineSpec.current ?: return
        val display = currentDisplayId()
        spec.noteLanded(display, taskId)
        Log.i(TAG, "$where display=$display expected=${spec.displayId} task=$taskId")
        if (display != spec.displayId) return
        if (startedTarget) return
        startedTarget = true
        val error = startTarget(spec.packageName, spec.activityName)
        if (error != null) {
            spec.reportFailure(error)
            finish()
        } else {
            spec.reportLanded(display, taskId)
        }
    }

    private fun startTarget(packageName: String, activityName: String): String? {
        // Same-task launch first: the target inherits this activity's task
        // (and therefore its display). No NEW_TASK, no launch options.
        val sameTask = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = ComponentName(packageName, activityName)
        }
        try {
            startActivity(sameTask)
            return null
        } catch (first: Throwable) {
            val newTask = Intent(sameTask).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
            }
            try {
                startActivity(newTask)
                return null
            } catch (second: Throwable) {
                val fallback = packageManager.getLaunchIntentForPackage(packageName)
                    ?: return "Could not start $packageName: ${second.message}"
                fallback.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                return try {
                    startActivity(fallback)
                    null
                } catch (third: Throwable) {
                    "Could not start $packageName: ${third.message}"
                }
            }
        }
    }

    private fun currentDisplayId(): Int {
        val fromDecor = window?.decorView?.display?.displayId
        val fromActivity = if (android.os.Build.VERSION.SDK_INT >= 30) display?.displayId else null
        return when {
            fromDecor != null && fromDecor != Display.DEFAULT_DISPLAY -> fromDecor
            fromActivity != null && fromActivity != Display.DEFAULT_DISPLAY -> fromActivity
            fromDecor != null -> fromDecor
            fromActivity != null -> fromActivity
            else -> Display.DEFAULT_DISPLAY
        }
    }

    private fun pollUntilStarted() {
        maybeStart("poll")
        if (!startedTarget && !isFinishing) {
            mainHandler.postDelayed({ pollUntilStarted() }, POLL_MS)
        }
    }

    companion object {
        private const val TAG = "OeaProjectionTramp"
        private const val SELF_TIMEOUT_MS = 12_000L
        private const val POLL_MS = 100L
        private val mainHandler = Handler(Looper.getMainLooper())
        private val live = java.util.concurrent.CopyOnWriteArraySet<OeaProjectionTrampolineActivity>()

        /** Finishes every living trampoline (session close / diagnostics cleanup). */
        fun finishLiving() {
            live.toList().forEach { activity ->
                try {
                    if (!activity.isFinishing && !activity.isDestroyed) activity.finish()
                } catch (_: Throwable) {
                }
            }
        }
    }
}

/**
 * Handshake between the launch orchestrator and the trampoline instance.
 * `startActivity` returning without an exception does NOT prove the starter
 * reached the virtual display — the OEM can put it on the phone anyway. The
 * trampoline reports the display it actually landed on, and the target is
 * only started after that display is the virtual one.
 */
class OeaProjectionTrampolineSpec(
    val packageName: String,
    val activityName: String,
    val displayId: Int
) {
    @Volatile var lastDisplayId: Int = Display.DEFAULT_DISPLAY
        private set
    @Volatile var taskId: Int = -1
        private set

    @Volatile private var reported = false

    var onLanded: (() -> Unit)? = null
    var onFailure: ((String) -> Unit)? = null

    fun noteLanded(displayId: Int, taskId: Int) {
        lastDisplayId = displayId
        this.taskId = taskId
    }

    fun reportLanded(displayId: Int, taskId: Int) {
        if (reported) return
        reported = true
        this.lastDisplayId = displayId
        this.taskId = taskId
        onLanded?.invoke()
    }

    fun reportFailure(reason: String) {
        if (reported) return
        reported = true
        onFailure?.invoke(reason)
    }

    companion object {
        @Volatile var current: OeaProjectionTrampolineSpec? = null
    }
}
