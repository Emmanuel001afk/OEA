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
 * THE RULE THIS LADDER IS BUILT AROUND (verified in the Android 13/14/15
 * AOSP sources — `ActivityTaskSupervisorService.isCallerAllowedToLaunchOnDisplay`,
 * `RootWindowContainer.getOrCreateRootTask`, `ActivityRecord.canBeLaunchedOnDisplay`):
 *
 *  1. An app-created virtual display is NEVER a trusted display — the
 *     FLAG_TRUSTED bit requires the signature permission ADD_TRUSTED_DISPLAY
 *     (`VirtualDisplayAdapter` only sets it for callers who passed
 *     VIRTUAL_DISPLAY_FLAG_TRUSTED, which ordinary apps cannot).
 *  2. On an untrusted display, Android only places an activity that itself
 *     declares `android:allowEmbedded="true"`.
 *  3. Even then, the caller must either hold the signature permission
 *     ACTIVITY_EMBEDDING or already have one of ITS OWN ACTIVITY RECORDS on
 *     that display. A Presentation window does NOT count — only an activity
 *     record does (`DisplayContent.isUidPresent`).
 *  4. The rule gates EVERY placement path, including "inherit the display of
 *     the task you are launched into" — so a third-party app, which never
 *     declares allowEmbedded, cannot be put on an app-created display by any
 *     ordinary app on stock Android 13–15. Stock Android silently falls back
 *     to the phone screen; the first Infinix/XOS device run showed that this
 *     manufacturer instead THROWS the SecurityException
 *     "…with launchDisplayId=N" that the user reported.
 *
 * What an ordinary app CAN still do, and what this ladder therefore tries,
 * in this order (each step measured and recorded as evidence):
 *
 *  A. The direct routes — start OEA's own allowEmbedded starter activity
 *     with `launchDisplayId` from several contexts (display-scoped context,
 *     occupancy-Presentation context, and a foreground OEA activity when one
 *     exists). On a permissive build the display-owner check in the rule
 *     lets our own activity through; on XOS it throws — which is itself the
 *     evidence that settles the question for this device.
 *  B. The re-parent route (the one remaining legal architecture): start the
 *     starter with NO launch options at all (it lands on the phone, invisible
 *     and non-touchable), take its task id, and ask the system to move that
 *     task to the display through every OEM-exposed API
 *     ([OeaTaskMove]). If any of them works, the starter is on the display
 *     as a real activity record — OEA's uid becomes "present" there, the
 *     first condition of the rule is finally satisfiable, and the target is
 *     started from the starter so it inherits the display.
 *  C. Once landed, verify through the accessibility window list that the
 *     target package really has a window on the virtual display. If the
 *     package appears on the physical screen instead, the launch is reported
 *     as leaked, never as success.
 *
 * If everything is refused the display is released and the failure states
 * exactly which route was refused, the complete exception text, and — for a
 * third-party target — that the platform rule above (not an OEA bug) is what
 * blocks it. A full-screen launch of the target on the phone is never
 * counted as success.
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

    /** How long the no-options starter may take to report its task id. */
    private const val REPARENT_START_MS = 3_000L

    /** How long to wait after each reparent API call before judging it. */
    private const val REPARENT_VERIFY_MS = 900L

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

    /** Live handshake state for one starter start. */
    private class StarterHandshake(spec: OeaProjectionTrampolineSpec) {
        val spec = spec
        val landed = CountDownLatch(1)
        val error = arrayOfNulls<String>(1)

        init {
            spec.onLanded = { landed.countDown() }
            spec.onFailure = { reason ->
                error[0] = reason
                landed.countDown()
            }
        }

        /**
         * Waits for the starter's verdict. Returns (landed, failureReason).
         * The callbacks are installed before the start, so a verdict that
         * fires while we are busy waiting elsewhere is never missed.
         */
        fun await(budgetMs: Long): Pair<Boolean, String?> {
            val reached = try {
                landed.await(budgetMs, TimeUnit.MILLISECONDS)
            } catch (_: Throwable) {
                false
            }
            return (reached && error[0] == null) to error[0]
        }
    }

    /**
     * One trampoline start attempt with fresh handshake state.
     *
     * @param label      how this route is named in the report
     * @param ctx        the context the starter is started from
     * @param flags      intent flags for the starter
     * @param options    launch options; null means "no options at all"
     *                   (the re-parent route — the starter then lands on the
     *                   phone and its task is moved afterwards)
     * @return the attempt evidence plus the live handshake, so the re-parent
     *         route can keep waiting on the same starter after its moves.
     */
    private fun startStarter(
        label: String,
        ctx: Context,
        flags: Int,
        options: Bundle?,
        host: OeaVirtualDisplayHost,
        packageName: String,
        activityName: String,
        budgetMs: Long = TRAMPOLINE_LAND_MS
    ): Pair<OeaLaunchRouteAttempt, StarterHandshake> {
        // Clear any leftover starter from a previous route first, so a late
        // landing can never report into the wrong handshake.
        OeaProjectionTrampolineActivity.finishLiving()
        OeaProjectionTrampolineSpec.current = null

        val handshake =
            StarterHandshake(OeaProjectionTrampolineSpec(packageName, activityName, host.displayId))
        OeaProjectionTrampolineSpec.current = handshake.spec

        val t0 = SystemClock.uptimeMillis()
        var threw: String? = null
        try {
            val trampolineIntent = Intent(ctx, OeaProjectionTrampolineActivity::class.java).apply {
                this.flags = flags
            }
            if (options != null) {
                ctx.startActivity(trampolineIntent, options)
            } else {
                ctx.startActivity(trampolineIntent)
            }
        } catch (t: Throwable) {
            threw = describeThrowable(t)
        }
        var landedOk = false
        var note = ""
        if (threw == null) {
            // Background thread: safe to block while the trampoline boots.
            val (reached, failure) = handshake.await(budgetMs)
            landedOk = reached
            if (!landedOk) {
                note = failure
                    ?: "startActivity returned normally but the starter never confirmed landing " +
                        "within ${budgetMs / 1000}s (this manufacturer may have silently dropped it)"
            }
        }
        val attempt = if (landedOk) {
            OeaLaunchRouteAttempt(label, null, true, "task ${handshake.spec.taskId}")
        } else {
            OeaLaunchRouteAttempt(label, threw, false, note)
        }
        return attempt to handshake
    }

    /**
     * Runs the ladder for the target app on an already-created [host].
     * Call from a background thread (the session's projection thread).
     *
     * @param hostContext a FOREGROUND OEA activity context when one exists
     *   (the Stage-B experiment runs from OEA Settings). Some manufacturers
     *   key their launch checks on the caller being a visible activity, and
     *   the re-parent route's plain start also needs a foreground caller on
     *   devices that police background activity starts. Null when OEA has no
     *   activity of its own in the foreground (live-window sessions).
     */
    fun launch(
        service: AccessibilityService,
        host: OeaVirtualDisplayHost,
        packageName: String,
        activityName: String,
        appLabel: String,
        hostContext: Context? = null
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
        // NOTE (measured on the first device run and read in the AOSP
        // sources): this window does NOT by itself satisfy the launch rule —
        // only an activity record makes a uid "present" on a display. It is
        // still shown because it (a) proves OEA can render on the display
        // and (b) gives the ladder a context that owns a window there, which
        // at least one manufacturer's probe is known to consider.
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
                if (occupancyAttached) "yes (a window — not an activity record — is on the display)" else
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
            } catch (_: Throwable) {
                null
            }
            val allowedServiceCtx = try {
                am.isActivityStartAllowedOnDisplay(service, host.displayId, launchIntent)
            } catch (_: Throwable) {
                null
            }
            val allowedHostCtx = hostContext?.let { ctx ->
                try {
                    am.isActivityStartAllowedOnDisplay(ctx, host.displayId, launchIntent)
                } catch (_: Throwable) {
                    null
                }
            }
            evidence += OeaEvidenceLine(
                "isActivityStartAllowedOnDisplay (Android's own answer)",
                "display-context=$allowedDisplayCtx, service-context=$allowedServiceCtx" +
                        (allowedHostCtx?.let { ", foreground-activity-context=$it" } ?: "") +
                        " (advisory; OEMs may disagree)",
                SystemClock.uptimeMillis() - t0
            )
        }

        // ---- Step 3a: the direct routes (launchDisplayId) ------------------
        val newTaskMultiple =
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
        val routeContexts = mutableListOf<Pair<String, Context>>()
        routeContexts += "display context" to displayContext
        val occ = presentation
        if (occupancyShown && occ != null) {
            routeContexts += "presentation context (owns a window on the display)" to occ.context
        }
        if (hostContext != null) {
            routeContexts += "foreground OEA activity context" to hostContext
        }

        val attempts = mutableListOf<OeaLaunchRouteAttempt>()
        var anyLanded = false
        outer@ for ((ctxLabel, ctx) in routeContexts) {
            val options = ActivityOptions.makeBasic()
            options.launchDisplayId = host.displayId
            val (attempt, _) = startStarter(
                "$ctxLabel, NEW_TASK|MULTIPLE_TASK, launchDisplayId",
                ctx, newTaskMultiple, options.toBundle(), host, packageName, activityName
            )
            attempts += attempt
            evidence += attempt.toEvidenceLine()
            if (attempt.landed) {
                anyLanded = true
                break@outer
            }
        }
        if (!anyLanded) {
            // Last direct variant: NEW_TASK only, from the display context.
            val options = ActivityOptions.makeBasic()
            options.launchDisplayId = host.displayId
            val (attempt, _) = startStarter(
                "display context, NEW_TASK only, launchDisplayId",
                displayContext, Intent.FLAG_ACTIVITY_NEW_TASK, options.toBundle(),
                host, packageName, activityName
            )
            attempts += attempt
            evidence += attempt.toEvidenceLine()
            if (attempt.landed) anyLanded = true
        }

        // ---- Step 3b: the re-parent route ----------------------------------
        // Start the starter with NO launch options (it lands on the phone,
        // transparent and untouchable), then move its task to the display.
        // This is the only remaining legal way to get the FIRST OEA activity
        // record onto an app-created display on a build that refuses
        // launchDisplayId outright.
        var reparentAttempts: List<OeaTaskMove.Attempt> = emptyList()
        if (!anyLanded) {
            val reparentCtx = hostContext ?: displayContext
            val ctxLabel = if (hostContext != null) "foreground OEA activity context" else "display context"
            val (start, handshake) = startStarter(
                "$ctxLabel, no launch options (started on the phone to be re-parented)",
                reparentCtx, newTaskMultiple, null, host, packageName, activityName,
                budgetMs = REPARENT_START_MS
            )
            attempts += start
            // A "did not land" verdict here is EXPECTED (the starter is on
            // the phone; it only reports failure at its own 16s timeout).
            // What matters is whether it started at all and reported a task
            // id, because that is the task the system can be asked to move.
            val taskId = handshake.spec.taskId
            val startedOk = start.threw == null && taskId != -1
            evidence += if (startedOk) {
                OeaEvidenceLine(
                    "Starter started on the phone without display options",
                    "yes — its task id is $taskId; now asking the system to move that task to " +
                        "display ${host.displayId}",
                    0L
                )
            } else {
                start.toEvidenceLine()
            }
            if (startedOk) {
                val moveResults = OeaTaskMove.moveTaskToDisplay(
                    displayContext, taskId, host.displayId
                )
                reparentAttempts = moveResults.toMutableList()
                moveResults.forEach { evidence += it.toEvidenceLine() }
                // After each accepted call, give the starter's poll (100ms
                // period) a moment to notice it now lives on the display —
                // it then starts the target and reports the landing.
                for (move in moveResults) {
                    if (!move.ok) continue
                    if (handshake.spec.lastDisplayId == host.displayId) break
                    waitQuiet(REPARENT_VERIFY_MS)
                }
                // Final re-launch option: take the task through Recents.
                if (handshake.spec.lastDisplayId != host.displayId) {
                    val relaunchOptions = ActivityOptions.makeBasic()
                    relaunchOptions.launchDisplayId = host.displayId
                    val relaunch = OeaTaskMove.relaunchOnDisplay(
                        displayContext, taskId, relaunchOptions.toBundle()
                    )
                    reparentAttempts += relaunch
                    evidence += relaunch.toEvidenceLine()
                    if (relaunch.ok) waitQuiet(REPARENT_VERIFY_MS)
                }
                // The final verdict of the whole re-parent route: did the
                // starter confirm it is on the virtual display?
                val (reached, failure) = handshake.await(TRAMPOLINE_LAND_MS)
                val decision = if (reached) {
                    OeaLaunchRouteAttempt(
                        "re-parent route outcome", null, true, "task $taskId reached the display"
                    )
                } else {
                    OeaLaunchRouteAttempt(
                        "re-parent route outcome", null, false,
                        failure ?: "the starter never confirmed the move within " +
                            "${TRAMPOLINE_LAND_MS / 1000}s"
                    )
                }
                attempts += decision
                evidence += decision.toEvidenceLine()
                if (decision.landed) {
                    anyLanded = true
                } else {
                    // Never leave the invisible starter on the phone.
                    OeaProjectionTrampolineActivity.finishLiving()
                }
            }
        }

        if (!anyLanded) {
            OeaProjectionTrampolineSpec.current = null
            OeaProjectionTrampolineActivity.finishLiving()
            dismissOccupancy()
            val refusal = attempts.firstNotNullOfOrNull { it.threw }
            val displayRefused = attempts.any {
                it.threw?.contains("launchDisplayId") == true
            }
            val noOptionsStart = attempts.firstOrNull {
                it.label.contains("no launch options")
            }
            val reparentTried = reparentAttempts.isNotEmpty()
            val reparentAccepted = reparentAttempts.any { it.ok }
            val headline = when {
                displayRefused && reparentTried && !reparentAccepted ->
                    "This phone blocks the projection architecture: it refuses to start OEA's own " +
                        "starter on OEA's private display (the SecurityException mentioning " +
                        "launchDisplayId) and none of the ${reparentAttempts.size} task-move APIs " +
                        "it exposes would move the starter's task there either. Android itself only " +
                        "lets activities that declare allowEmbedded run on an app-created display, " +
                        "so third-party apps cannot be projected this way on this device. The full " +
                        "route-by-route evidence is in the Stage-B report (OEA Settings › " +
                        "Multitasking)."
                displayRefused && reparentAccepted ->
                    "The phone refused the direct projection routes (the SecurityException " +
                        "mentioning launchDisplayId) and although a task-move API accepted the " +
                        "call, the projected app still never reached the private display. The " +
                        "starter's own report line says exactly where it stopped; this is recorded " +
                        "evidence, not a silent failure. Full details: Stage-B report."
                displayRefused && noOptionsStart?.threw != null ->
                    "This phone refuses to start OEA's starter on OEA's private display " +
                        "(the SecurityException mentioning launchDisplayId), and even a plain " +
                        "no-options start was refused with: ${noOptionsStart.threw.substringBefore('\n')} " +
                        "(that is the phone's background-launch control, a separate rule OEA cannot " +
                        "and will not bypass). The complete evidence is in the Stage-B report."
                displayRefused ->
                    "This phone refuses to start OEA's starter on OEA's private display " +
                        "(the SecurityException mentioning launchDisplayId). The complete error and " +
                        "every route tried are in the Stage-B report."
                refusal != null ->
                    "OEA's starter could not be launched — the platform refused it with: " +
                        "${refusal.substringBefore('\n')}. The complete error and every route tried " +
                        "are in the Stage-B report."
                else ->
                    "OEA's starter never reached the virtual display: ${attempts.size} legal launch " +
                        "routes were tried and none landed."
            }
            return fail(
                host, evidence, headline, appLabel, elapsed(),
                occupancyShown, occupancyAttached, attempts, reparentAttempts
            )
        }

        // ---- Step 4: verify the target package on the virtual display -----
        // The starter has started the target by now; poll the window list.
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
            val thirdParty = packageName != OEA_SELF_PACKAGE
            val reason = when {
                leakedToPhone && wasAlreadyOnPhone ->
                    "$appLabel was already open on the phone, so Android brought that window " +
                        "forward instead of starting a copy on the private display. Close " +
                        "$appLabel on the phone first, then try again."
                leakedToPhone && thirdParty ->
                    "$appLabel opened on the phone screen instead of the private display. This is " +
                        "the Android 13–15 rule, not a glitch: an app-created display is untrusted, " +
                        "and only activities that themselves declare allowEmbedded may run on it — " +
                        "third-party apps never do, so this device sends them back to the phone. " +
                        "Nothing was faked: OEA will not pretend a leaked launch succeeded."
                leakedToPhone ->
                    "This manufacturer opened $appLabel on the phone screen, so the projection was " +
                        "cancelled. Nothing was faked: OEA will not pretend a leaked launch succeeded."
                thirdParty ->
                    "$appLabel never appeared on the virtual display. Android only allows " +
                        "activities that declare allowEmbedded to run on an app-created display, " +
                        "and third-party apps never declare it — on this device that rule blocks " +
                        "them from being projected."
                else ->
                    "$appLabel never appeared on the virtual display. It may refuse secondary " +
                        "displays (some apps do), or this manufacturer blocks it."
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
            routeAttempts = attempts,
            reparentAttempts = reparentAttempts
        )
    }

    private fun waitQuiet(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: Throwable) {
        }
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
        routeAttempts: List<OeaLaunchRouteAttempt> = emptyList(),
        reparentAttempts: List<OeaTaskMove.Attempt> = emptyList()
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
            routeAttempts = routeAttempts,
            reparentAttempts = reparentAttempts
        )
    }

    /**
     * A Presentation that shows a 1×1 invisible window on the virtual
     * display. Its job is to prove OEA can put a window there — and to give
     * the ladder a context that owns a window on the display. It does NOT
     * by itself satisfy Android's launch rule: only an activity record
     * makes a uid "present" on a display (AOSP 13
     * `ActivityTaskSupervisorService.isCallerAllowedToLaunchOnDisplay` /
     * `DisplayContent.isUidPresent`). It is never visible and is dismissed
     * as soon as the ladder finishes.
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
