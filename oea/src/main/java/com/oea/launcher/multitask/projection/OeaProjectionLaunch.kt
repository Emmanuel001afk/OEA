package com.oea.launcher.multitask.projection

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.Presentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Places a real, installed application on an app-created virtual display.
 *
 * The ladder (each step measured and recorded as evidence):
 *
 *  0. Create the virtual display ([OeaVirtualDisplayHost.create]) — done by
 *     the caller before this runs.
 *  1. Show a 1×1 occupancy Presentation on it. This both proves OEA can put
 *     *any* window on the display and makes OEA's uid "present" on it, which
 *     the AOSP launch check (`isCallerAllowedToLaunchOnDisplay`) asks for on
 *     untrusted displays.
 *  2. Ask Android up front whether the launch is allowed
 *     ([ActivityManager.isActivityStartAllowedOnDisplay]) — a public,
 *     non-destructive probe that is recorded even when it disagrees with
 *     what actually happens, because OEMs differ.
 *  3. Start the trampoline activity with launchDisplayId pointing at the
 *     virtual display, from the display-scoped context.
 *  4. Wait until the trampoline reports it really is on the virtual display,
 *     then let it start the target application into its own task.
 *  5. Verify through the accessibility window list that the target package
 *     now has a window on the virtual display — the ground truth the user
 *     can trust. If the package instead appears on the physical screen, the
 *     launch is reported as leaked, not successful.
 *
 * If any step fails the display is released and the failure says exactly
 * which step and why. A full-screen launch of the target on the phone is
 * never counted as success.
 *
 * Threading: runs on the caller's background thread (it waits); the few
 * main-thread-only operations are hopped via [onMain].
 */
object OeaProjectionLaunch {

    /** How long the trampoline may take to land on the display. */
    private const val TRAMPOLINE_LAND_MS = 5_000L

    /** How long the target app may take to appear on the display. */
    private const val TARGET_LAND_MS = 6_500L

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Runs [block] on the main thread and blocks until it finishes. */
    private fun <T> onMain(block: () -> T): T {
        val latch = CountDownLatch(1)
        val holder = arrayOfNulls<Any>(1)
        val error = arrayOfNulls<Throwable>(1)
        mainHandler.post {
            try {
                holder[0] = block()
            } catch (t: Throwable) {
                error[0] = t
            } finally {
                latch.countDown()
            }
        }
        latch.await(8, TimeUnit.SECONDS)
        error[0]?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return holder[0] as T
    }

    /**
     * Runs the ladder for the target app on an already-created [host].
     * Call from a background thread (the session's projection thread).
     */
    fun launch(
        service: AccessibilityService,
        host: OeaVirtualDisplayHost,
        packageName: String,
        activityName: String,
        appLabel: String
    ): OeaProjectionLaunchResult {
        val evidence = mutableListOf<OeaEvidenceLine>()
        val start = SystemClock.uptimeMillis()
        fun elapsed() = SystemClock.uptimeMillis() - start

        // The display-scoped context: everything launched from here is
        // associated with the virtual display.
        val display: Display? = try {
            (service.getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager)
                ?.getDisplay(host.displayId)
        } catch (_: Throwable) {
            null
        }
        if (display == null) {
            return fail(host, evidence, "Virtual display ${host.displayId} disappeared before launch.", appLabel)
        }
        val displayContext = try {
            service.createDisplayContext(display)
        } catch (t: Throwable) {
            return fail(
                host, evidence,
                "Could not create a display context: ${t.message ?: t.javaClass.simpleName}", appLabel
            )
        }

        // ---- Step 1: occupancy window (Presentation) on the display -------
        var occupancyShown = false
        var t0 = SystemClock.uptimeMillis()
        try {
            onMain {
                val presentation = OccupancyPresentation(displayContext, display)
                presentation.show()
                activeOccupancy = presentation
            }
            occupancyShown = true
            evidence += OeaEvidenceLine(
                "Occupancy presentation on display ${host.displayId}",
                "shown", SystemClock.uptimeMillis() - t0
            )
        } catch (t: Throwable) {
            evidence += OeaEvidenceLine(
                "Occupancy presentation on display ${host.displayId}",
                "failed: ${t.message ?: t.javaClass.simpleName} (continuing; the starter may still be allowed)",
                SystemClock.uptimeMillis() - t0
            )
        }

        // ---- Step 2: the public pre-flight probe ---------------------------
        val am = service.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val launchIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = ComponentName(packageName, activityName)
        }
        if (am != null && android.os.Build.VERSION.SDK_INT >= 29) {
            t0 = SystemClock.uptimeMillis()
            val allowedDisplayCtx = try {
                am.isActivityStartAllowedOnDisplay(displayContext, host.displayId, launchIntent)
            } catch (t: Throwable) {
                null
            }
            val allowedServiceCtx = try {
                am.isActivityStartAllowedOnDisplay(service, host.displayId, launchIntent)
            } catch (t: Throwable) {
                null
            }
            evidence += OeaEvidenceLine(
                "isActivityStartAllowedOnDisplay (Android's own answer)",
                "display-context=$allowedDisplayCtx, service-context=$allowedServiceCtx (advisory; OEMs may disagree)",
                SystemClock.uptimeMillis() - t0
            )
        }

        // ---- Step 3+4: trampoline onto the display, then the target --------
        val spec = OeaProjectionTrampolineSpec(packageName, activityName, host.displayId)
        val landed = CountDownLatch(1)
        val trampolineError = arrayOfNulls<String>(1)
        val landedTaskId = intArrayOf(-1)
        spec.onLanded = {
            landedTaskId[0] = spec.taskId
            landed.countDown()
        }
        spec.onFailure = { reason ->
            trampolineError[0] = reason
            landed.countDown()
        }
        OeaProjectionTrampolineSpec.current = spec

        t0 = SystemClock.uptimeMillis()
        var trampolineStartError: String? = null
        try {
            val trampolineIntent = Intent(displayContext, OeaProjectionTrampolineActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
            }
            val options = ActivityOptions.makeBasic()
            options.launchDisplayId = host.displayId
            displayContext.startActivity(trampolineIntent, options.toBundle())
        } catch (t: Throwable) {
            trampolineStartError = "OEA's starter could not be launched: ${t.message ?: t.javaClass.simpleName}"
        }
        if (trampolineStartError != null) {
            OeaProjectionTrampolineSpec.current = null
            dismissOccupancy()
            return fail(host, evidence, trampolineStartError, appLabel, elapsed())
        }
        // Background thread: safe to block while the trampoline boots.
        val reached = try {
            landed.await(TRAMPOLINE_LAND_MS, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
            false
        }
        val trampolineOk = reached && trampolineError[0] == null
        evidence += OeaEvidenceLine(
            "OEA starter on display ${host.displayId}",
            if (trampolineOk) "landed on the virtual display (task ${landedTaskId[0]})"
            else "did not land: ${trampolineError[0] ?: "no report within ${TRAMPOLINE_LAND_MS / 1000}s"}",
            SystemClock.uptimeMillis() - t0
        )
        if (!trampolineOk) {
            OeaProjectionTrampolineSpec.current = null
            OeaProjectionTrampolineActivity.finishLiving()
            dismissOccupancy()
            return fail(
                host, evidence,
                trampolineError[0]
                    ?: "OEA's starter never reached the virtual display within ${TRAMPOLINE_LAND_MS / 1000}s.",
                appLabel, elapsed()
            )
        }

        // ---- Step 5: verify the target package on the virtual display ------
        // The trampoline has started the target by now; poll the window list.
        t0 = SystemClock.uptimeMillis()
        val controller = OeaProjectionController { OeaLiveWindowManager.accessibilityService() }
        // An app that already runs full screen on the phone may simply come
        // to the front there instead of starting fresh on the private
        // display (its task already lives on display 0). Record that before
        // judging the outcome, so the failure message is truthful.
        val wasAlreadyOnPhone = controller.packageOnDisplay(Display.DEFAULT_DISPLAY, packageName)
        var onDisplay = false
        var leakedToPhone = false
        var waited = 0L
        while (waited <= TARGET_LAND_MS) {
            onDisplay = controller.packageOnDisplay(host.displayId, packageName)
            if (onDisplay) break
            leakedToPhone = controller.packageOnDisplay(Display.DEFAULT_DISPLAY, packageName)
            if (leakedToPhone) break
            try {
                Thread.sleep(150)
            } catch (_: Throwable) {
            }
            waited += 150
        }
        evidence += OeaEvidenceLine(
            "$appLabel on the virtual display (verified via accessibility windows)",
            when {
                onDisplay -> "yes"
                leakedToPhone -> "no — the app opened on the physical screen instead"
                else -> "no window for this package on any display within ${TARGET_LAND_MS / 1000}s"
            },
            SystemClock.uptimeMillis() - t0
        )

        dismissOccupancy()

        if (!onDisplay) {
            OeaProjectionTrampolineActivity.finishLiving()
            val reason = when {
                leakedToPhone && wasAlreadyOnPhone ->
                    "$appLabel was already open on the phone, so Android brought that window forward instead of starting a copy on the private display. Close $appLabel on the phone first, then try again."
                leakedToPhone ->
                    "This manufacturer opened $appLabel on the phone screen, so the projection was cancelled. Nothing was faked: OEA will not pretend a leaked launch succeeded."
                else ->
                    "$appLabel never appeared on the virtual display. It may refuse secondary displays (some apps do), or this manufacturer blocks it."
            }
            return fail(host, evidence, reason, appLabel, elapsed())
        }

        return OeaProjectionLaunchResult(
            success = true,
            displayId = host.displayId,
            state = OeaProjectionState.LIVE,
            headline = "$appLabel is live on the virtual display. The phone screen is untouched.",
            evidence = evidence
        )
    }

    // Keeps at most one occupancy presentation; dismissed between attempts.
    @Volatile private var activeOccupancy: Presentation? = null

    private fun dismissOccupancy() {
        val occ = activeOccupancy ?: return
        activeOccupancy = null
        try {
            onMain { occ.dismiss() }
        } catch (_: Throwable) {
        }
    }

    private fun fail(
        host: OeaVirtualDisplayHost,
        evidence: MutableList<OeaEvidenceLine>,
        reason: String,
        appLabel: String,
        elapsed: Long = -1L
    ): OeaProjectionLaunchResult {
        evidence += OeaEvidenceLine("Result", reason, elapsed)
        host.release()
        return OeaProjectionLaunchResult(
            success = false,
            displayId = host.displayId,
            state = OeaProjectionState.FAILED,
            headline = reason,
            evidence = evidence
        )
    }

    /**
     * A Presentation that shows a 1×1 invisible window on the virtual
     * display. Its only jobs: prove OEA can put a window there, and make
     * OEA's uid "present" on the display for the launch check. It is never
     * visible and is dismissed as soon as the ladder finishes.
     */
    private class OccupancyPresentation(context: Context, display: Display) :
        Presentation(context, display) {

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            val nothing = android.view.View(context).apply {
                visibility = android.view.View.INVISIBLE
            }
            setContentView(nothing)
            window?.let { w ->
                w.setLayout(1, 1)
                w.setGravity(Gravity.TOP or Gravity.START)
                w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            }
        }
    }
}
