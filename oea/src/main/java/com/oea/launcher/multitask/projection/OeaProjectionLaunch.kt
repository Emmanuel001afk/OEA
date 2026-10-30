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
 *  1. Show a 1×1 occupancy Presentation on it, then WAIT until that
 *     Presentation's window verifiably attached to the display. This both
 *     proves OEA can put *any* window on the display and makes OEA's uid
 *     "present" on it, which the AOSP launch check
 *     (`isCallerAllowedToLaunchOnDisplay`, Android 13) asks for on
 *     untrusted displays. The first device run showed a platform denial at
 *     the starter launch; attaching the occupancy window first is a legal
 *     way to satisfy even the strictest caller checks before asking again.
 *  2. Ask Android up front whether the launch is allowed
 *     ([ActivityManager.isActivityStartAllowedOnDisplay]) — a public,
 *     non-destructive probe that is recorded even when it disagrees with
 *     what actually happens, because OEMs differ.
 *  3. Start the trampoline activity with launchDisplayId pointing at the
 *     virtual display — through SEVERAL legal routes, in order, because the
 *     first Infinix/XOS run refused the plain display-context start:
 *       a. from the display-scoped context (NEW_TASK|MULTIPLE_TASK);
 *       b. from the occupancy Presentation's context — that context owns a
 *          real attached window on the display, which is what the AOSP
 *          caller check (and several OEM firewalls) wants to see;
 *       c. from the display-scoped context with NEW_TASK only.
 *     A route counts as successful ONLY when the trampoline reports it
 *     really landed on the virtual display (an startActivity that returns
 *     without throwing can still be silently dropped by the OEM).
 *  4. Once landed, the trampoline starts the target application into its
 *     own task.
 *  5. Verify through the accessibility window list that the target package
 *     now has a window on the virtual display — the ground truth the user
 *     can trust. If the package instead appears on the physical screen, the
 *     launch is reported as leaked, not successful.
 *
 * If every route fails the display is released and the failure says exactly
 * which route was refused and the complete exception text. A full-screen
 * launch of the target on the phone is never counted as success.
 *
 * Threading: runs on the caller's background thread (it waits); the few
 * main-thread-only operations are hopped via [onMain].
 */
object OeaProjectionLaunch {

    /** How long the trampoline may take to land on the display. */
    private const val TRAMPOLINE_LAND_MS = 5_000L

    /** How long the target app may take to appear on the display. */
    private const val TARGET_LAND_MS = 6_500L

    /** How long the occupancy Presentation may take to attach its window. */
    private const val OCCUPANCY_ATTACH_MS = 2_500L

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

    private fun describeThrowable(t: Throwable): String {
        val frames = t.stackTrace.take(3).joinToString("") { "\n      at $it" }
        return "${t.javaClass.name}: ${t.message}$frames"
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
        val controller = OeaProjectionController { OeaLiveWindowManager.accessibilityService() }

        // The display-scoped context: everything launched from here is
        // associated with the virtual display.
        val display: Display? = try {
            (service.getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager)
                ?.getDisplay(host.displayId)
        } catch (_: Throwable) {
            null
        }
        if (display == null) {
            return fail(
                host, evidence, "Virtual display ${host.displayId} disappeared before launch.",
                appLabel, elapsed = elapsed()
            )
        }
        val displayContext = try {
            service.createDisplayContext(display)
        } catch (t: Throwable) {
            return fail(
                host, evidence,
                "Could not create a display context: ${t.message ?: t.javaClass.simpleName}",
                appLabel, elapsed = elapsed()
            )
        }

        // ---- Step 1: occupancy window (Presentation) on the display -------
        var occupancyShown = false
        var occupancyAttached = false
        var presentation: Presentation? = null
        var t0 = SystemClock.uptimeMillis()
        try {
            presentation = onMain {
                val p = OccupancyPresentation(displayContext, display)
                p.show()
                activeOccupancy = p
                p
            }
            occupancyShown = true
            evidence += OeaEvidenceLine(
                "Occupancy presentation on display ${host.displayId}",
                "show() returned without error", SystemClock.uptimeMillis() - t0
            )
        } catch (t: Throwable) {
            evidence += OeaEvidenceLine(
                "Occupancy presentation on display ${host.displayId}",
                "failed: ${describeThrowable(t)} (continuing; the starter may still be allowed)",
                SystemClock.uptimeMillis() - t0
            )
        }
        // Wait until the occupancy window is really attached to the display.
        // "Attached" means either the accessibility window list can see an
        // OEA window on the display, or the Presentation's own decor view
        // reports the virtual display. This is what makes OEA's uid present
        // on the display before any launch is attempted.
        if (occupancyShown && presentation != null) {
            t0 = SystemClock.uptimeMillis()
            while (SystemClock.uptimeMillis() - t0 < OCCUPANCY_ATTACH_MS) {
                val viaA11y = controller.packageOnDisplay(host.displayId, OEA_SELF_PACKAGE)
                val viaDecor = try {
                    onMain {
                        presentation.isShowing &&
                                presentation.window?.decorView?.display?.displayId == host.displayId
                    }
                } catch (_: Throwable) {
                    false
                }
                if (viaA11y || viaDecor) {
                    occupancyAttached = true
                    break
                }
                try {
                    Thread.sleep(120)
                } catch (_: Throwable) {
                }
            }
            evidence += OeaEvidenceLine(
                "Occupancy window attached to display ${host.displayId}",
                if (occupancyAttached) "yes (uid present on display before the launch)" else
                    "not confirmed within ${OCCUPANCY_ATTACH_MS / 1000}s — continuing anyway",
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

        // ---- Step 3: the starter, through every legal route ----------------
        val routeContexts = mutableListOf<Pair<String, Context>>()
        routeContexts += "display context" to displayContext
        val occ = presentation
        if (occupancyShown && occ != null) {
            routeContexts += "presentation context (owns a window on the display)" to occ.context
        }
        val flagSets = listOf(
            "NEW_TASK|MULTIPLE_TASK" to
                (Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
            "NEW_TASK only" to Intent.FLAG_ACTIVITY_NEW_TASK
        )
        val attempts = mutableListOf<OeaLaunchRouteAttempt>()
        var landedTaskId = -1
        var anyLanded = false
        outer@ for ((ctxLabel, ctx) in routeContexts) {
            for ((flagLabel, flags) in flagSets) {
                val routeLabel = "$ctxLabel, $flagLabel"
                // Clear any leftover starter from a previous route first, so a
                // late landing can never report into the wrong handshake.
                OeaProjectionTrampolineActivity.finishLiving()
                OeaProjectionTrampolineSpec.current = null

                val spec = OeaProjectionTrampolineSpec(packageName, activityName, host.displayId)
                val landed = CountDownLatch(1)
                val trampolineError = arrayOfNulls<String>(1)
                spec.onLanded = {
                    landedTaskId = spec.taskId
                    landed.countDown()
                }
                spec.onFailure = { reason ->
                    trampolineError[0] = reason
                    landed.countDown()
                }
                OeaProjectionTrampolineSpec.current = spec

                t0 = SystemClock.uptimeMillis()
                var threw: String? = null
                try {
                    val trampolineIntent = Intent(ctx, OeaProjectionTrampolineActivity::class.java).apply {
                        this.flags = flags
                    }
                    val options = ActivityOptions.makeBasic()
                    options.launchDisplayId = host.displayId
                    ctx.startActivity(trampolineIntent, options.toBundle())
                } catch (t: Throwable) {
                    threw = describeThrowable(t)
                }
                var landedOk = false
                var note = ""
                if (threw == null) {
                    // Background thread: safe to block while the trampoline boots.
                    val reached = try {
                        landed.await(TRAMPOLINE_LAND_MS, TimeUnit.MILLISECONDS)
                    } catch (_: Throwable) {
                        false
                    }
                    landedOk = reached && trampolineError[0] == null
                    if (!landedOk) {
                        note = trampolineError[0]
                            ?: "startActivity returned normally but the starter never confirmed landing within ${TRAMPOLINE_LAND_MS / 1000}s (this manufacturer may have silently dropped it)"
                    }
                }
                val attempt = OeaLaunchRouteAttempt(routeLabel, threw, landedOk, note)
                attempts += attempt
                evidence += attempt.toEvidenceLine().copy(ms = SystemClock.uptimeMillis() - t0)
                if (landedOk) {
                    anyLanded = true
                    break@outer
                }
            }
        }

        if (!anyLanded) {
            OeaProjectionTrampolineSpec.current = null
            OeaProjectionTrampolineActivity.finishLiving()
            dismissOccupancy()
            val refusal = attempts.firstNotNullOfOrNull { it.threw }
            val headline = when {
                refusal != null ->
                    "OEA's starter could not be launched — the platform refused it with: ${refusal.substringBefore('\n')}. " +
                            "The complete error and every route tried are in the Stage-B report."
                else ->
                    "OEA's starter never reached the virtual display: ${attempts.size} legal launch routes were tried and none landed."
            }
            return fail(
                host, evidence, headline, appLabel, elapsed(),
                occupancyShown, occupancyAttached, attempts
            )
        }

        // ---- Step 5: verify the target package on the virtual display ------
        // The trampoline has started the target by now; poll the window list.
        t0 = SystemClock.uptimeMillis()
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
            evidence = evidence,
            occupancyShown = occupancyShown,
            occupancyAttached = occupancyAttached,
            routeAttempts = attempts
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
        elapsed: Long = -1L,
        occupancyShown: Boolean = false,
        occupancyAttached: Boolean = false,
        routeAttempts: List<OeaLaunchRouteAttempt> = emptyList()
    ): OeaProjectionLaunchResult {
        evidence += OeaEvidenceLine("Result", reason, elapsed)
        host.release()
        return OeaProjectionLaunchResult(
            success = false,
            displayId = host.displayId,
            state = OeaProjectionState.FAILED,
            headline = reason,
            evidence = evidence,
            occupancyShown = occupancyShown,
            occupancyAttached = occupancyAttached,
            routeAttempts = routeAttempts
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

    private const val OEA_SELF_PACKAGE = "com.oea.launcher"
}
