package com.oea.launcher.multitask.projection

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

/**
 * The Stage-B experiment, runnable on the real device from OEA Settings.
 *
 * Research established the architecture; only the device can prove it. This
 * harness runs the *smallest experiment that validates or refutes* the three
 * pillars of the floating-app concept, and writes a report the user can
 * view, copy and share:
 *
 *   1. LIVE PRESENTATION — the real app renders on an app-created virtual
 *      display and its frames reach OEA (frames counted over 3 seconds).
 *   2. REAL INTERACTION — the target exposes interactive accessibility
 *      nodes on that display (clickable/editable/scrollable counts), and a
 *      non-destructive focus probe proves the node channel really reaches
 *      the app. That channel is the only input Android gives an ordinary
 *      app there.
 *   3. FOREGROUND PRESERVATION — the app that was in front *before* the
 *      experiment still owns the physical screen afterwards (its window
 *      package is re-read and compared).
 *
 * REPORT GUARANTEES (learned from the first real device run, where a silent
 * failure produced NO report at all):
 *   - the report is written to storage after EVERY section, not only at the
 *     end, so even a hang or a process death leaves everything measured
 *     so far readable in Settings;
 *   - the whole experiment runs inside a catch-all finalization path: any
 *     exception that escapes a stage is recorded with its full class,
 *     message and stack, the report is saved, and the run still returns a
 *     report string;
 *   - every stage ends as exactly one of PASSED / FAILED / INCONCLUSIVE,
 *     and stages that were never reached print as NOT RUN — a stage that
 *     did not run is never reported as a failed test.
 *
 * The harness never performs a destructive action in the target app: it
 * launches it, measures, and releases the display. Typed-input and
 * send-a-message tests are left to the user with the live window — the
 * report has a checklist section for exactly that.
 */
object OeaProjectionDiagnostics {

    /** Report is stored so it can be viewed/copied later. */
    const val PREFS = "oea_projection_diagnostics"
    const val KEY_REPORT = "last_report"
    const val KEY_AT = "last_report_at"

    interface Progress {
        fun onProgress(line: String)
    }

    /** Stage keys in execution order; the summary table uses this order. */
    private val STAGE_ORDER = listOf(
        "service-check", "capture-capability", "foreground-before", "virtual-display",
        "occupancy", "starter-launch", "target-on-display", "live-frames",
        "interaction-capability", "node-interaction", "foreground-after", "cleanup"
    )

    private fun stageTitle(key: String): String = when (key) {
        "service-check" -> "Accessibility service"
        "capture-capability" -> "Capture capability"
        "foreground-before" -> "Foreground before"
        "virtual-display" -> "Virtual display"
        "occupancy" -> "Occupancy presentation"
        "starter-launch" -> "Starter launch"
        "target-on-display" -> "Target on the display"
        "live-frames" -> "Live frames"
        "interaction-capability" -> "Interaction capability"
        "node-interaction" -> "Node interaction probe"
        "foreground-after" -> "Foreground preserved"
        "cleanup" -> "Cleanup"
        "crash" -> "Experiment crash"
        else -> key
    }

    /**
     * Accumulates the report, marks stages and persists after every section.
     * Kept as a small class so [run] can wrap the whole experiment in one
     * finalization path without threading a dozen parameters around.
     */
    private class Recorder(private val context: Context, private val progress: Progress?) {
        val sb = StringBuilder()
        val stages = LinkedHashMap<String, String>()
        private val main = Handler(Looper.getMainLooper())

        fun log(line: String) {
            val p = progress ?: return
            main.post { runCatching { p.onProgress(line) } }
        }

        fun section(title: String) {
            sb.append("\n== ").append(title).append(" ==\n")
            log(title)
        }

        fun line(text: String) {
            sb.append(text).append('\n')
            log(text)
        }

        fun stage(key: String, status: String, note: String = "") {
            stages[key] = if (note.isBlank()) status else "$status — $note"
        }

        /** Persists the report so far. Called after every section. */
        fun saveNow() {
            save(context, sb.toString())
        }

        override fun toString(): String = sb.toString()
    }

    /**
     * Runs the whole experiment. Must be called from a background thread.
     * [targetPackage] must be installed and launchable. This method never
     * lets an exception escape without first writing a report that contains
     * it, and it never returns without a saved report.
     */
    fun run(
        context: Context,
        targetPackage: String,
        appLabel: String,
        progress: Progress?
    ): String {
        val rec = Recorder(context, progress)
        try {
            runExperiment(rec, context, targetPackage, appLabel)
        } catch (t: Throwable) {
            // The finalization path: a crash anywhere in the experiment is
            // itself reportable evidence, not a reason to lose the report.
            rec.section("Experiment terminated abnormally")
            rec.line("The experiment crashed here. Everything above this line really happened;")
            rec.line("everything below it did NOT run and is reported as NOT RUN.")
            rec.line("Exception: ${t.javaClass.name}: ${t.message}")
            t.stackTrace.take(15).forEach { rec.line("    at $it") }
            rec.stage("crash", "FAILED", "${t.javaClass.simpleName}: ${t.message}")
            rec.saveNow()
        }
        buildSummary(rec)
        rec.saveNow()
        return rec.toString()
    }

    private fun buildSummary(rec: Recorder) {
        rec.section("Stage summary")
        val firstFailed = STAGE_ORDER.firstOrNull { rec.stages[it]?.startsWith("FAILED") == true }
        for (key in STAGE_ORDER) {
            val status = rec.stages[key] ?: "NOT RUN"
            rec.sb.append(stageTitle(key).padEnd(26)).append(' ').append(status).append('\n')
        }
        val p1 = rec.stages["target-on-display"]?.startsWith("PASSED") == true
        val p2 = rec.stages["node-interaction"]?.startsWith("PASSED") == true ||
                rec.stages["interaction-capability"]?.startsWith("PASSED") == true
        val p3 = rec.stages["foreground-after"]?.startsWith("PASSED") == true
        rec.sb.append('\n')
        rec.line(
            "Pillar 1 — real app presented on a private display: " +
                    if (p1) "PASS (on this device)" else if (firstFailed == "starter-launch" || firstFailed == "virtual-display") "FAIL" else "NOT PROVEN"
        )
        rec.line(
            "Pillar 2 — real interaction channel available:      " +
                    if (p2) "PASS (node actions)" else "FAIL/UNKNOWN"
        )
        rec.line(
            "Pillar 3 — original foreground app preserved:       " +
                    when (rec.stages["foreground-after"]) {
                        null -> "NOT RUN"
                        "PASSED" -> "PASS"
                        else -> "FAIL"
                    }
        )
        rec.line(
            "OVERALL: " + when {
                firstFailed != null ->
                    "FAILED at the '${stageTitle(firstFailed)}' stage; every stage after it is NOT RUN. See the section above for the exact error."
                rec.stages.values.any { it.startsWith("INCONCLUSIVE") } ->
                    "INCONCLUSIVE — no stage failed hard, but not everything could be proven on this device."
                else -> "every reached stage PASSED."
            }
        )
        if (firstFailed == "starter-launch") {
            rec.line(
                "Hint for a starter-launch denial on this phone: check the phone's own per-app launch " +
                        "controls for OEA (Infinix/XOS: Settings \u203a Apps \u203a OEA \u203a notifications & other permissions, " +
                        "'Display pop-up windows while running in the background' / auto-launch), then run the experiment " +
                        "again. OEA cannot and will not bypass a platform security control."
            )
        }
    }

    // ---------------------------------------------------------------- experiment

    private fun runExperiment(
        rec: Recorder,
        context: Context,
        targetPackage: String,
        appLabel: String
    ) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val pm = context.packageManager

        rec.section("Stage B — Floating app projection experiment")
        rec.line("Started: ${android.text.format.DateFormat.format("yyyy-MM-dd HH:mm:ss", System.currentTimeMillis())}")
        rec.line("Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})")
        rec.line("Target: $appLabel ($targetPackage)")
        val isGo = am?.isLowRamDevice == true
        rec.line("Low-RAM/Go profile reported: $isGo")

        // ---------------------------------------------------------------- facts
        rec.section("Platform facts (from the OS, not assumptions)")
        val freeform = pm.hasSystemFeature("android.software.freeform_window_management")
        val secondaryDisplays = pm.hasSystemFeature(PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS)
        rec.line("Freeform window management feature: $freeform")
        rec.line("Activities on secondary displays feature: $secondaryDisplays")

        val service = OeaLiveWindowManager.accessibilityService()
        if (service == null) {
            rec.line("Accessibility service: NOT BOUND — the experiment cannot run.")
            rec.line("Enable OEA Floating Window in Android Accessibility first (OEA Settings › Multitasking).")
            rec.stage("service-check", "FAILED", "service not bound")
            return
        }
        rec.stage("service-check", "PASSED", "OEA Floating Window service is bound")
        rec.saveNow()
        // The trampoline handshake is global: stop any running live windows
        // so the experiment owns the whole pipeline for its duration.
        if (OeaLiveWindowManager.count() > 0) {
            rec.line("Stopping ${OeaLiveWindowManager.count()} running live window(s) for a clean experiment…")
            OeaLiveWindowManager.stopAll()
            Thread.sleep(500)
        }

        // A11y screenshot capability (used only for the default display here;
        // the virtual display needs no screenshot because OEA owns its surface).
        rec.section("Capture capability")
        val screenshotCapable = runCatching {
            val info = service.serviceInfo
            info != null && (info.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT) != 0
        }.getOrDefault(false)
        rec.line("Accessibility screenshot capability: $screenshotCapable")
        if (screenshotCapable) {
            val shotStart = SystemClock.uptimeMillis()
            val shotOk = intArrayOf(0)
            val latch = java.util.concurrent.CountDownLatch(1)
            try {
                service.takeScreenshot(
                    android.view.Display.DEFAULT_DISPLAY,
                    { r -> r.run() },
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                            shotOk[0] = 1
                            try {
                                screenshot.hardwareBuffer?.close()
                            } catch (_: Throwable) {
                            }
                            latch.countDown()
                        }

                        override fun onFailure(errorCode: Int) {
                            shotOk[0] = -errorCode
                            latch.countDown()
                        }
                    }
                )
            } catch (t: Throwable) {
                rec.line("takeScreenshot threw: ${t.javaClass.name}: ${t.message}")
                latch.countDown()
            }
            latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
            val took = SystemClock.uptimeMillis() - shotStart
            rec.line(
                when {
                    shotOk[0] == 1 -> "Screenshot of the physical screen: OK in ${took}ms (platform rate limit is 1 frame / 333ms)"
                    shotOk[0] < 0 -> "Screenshot refused with error ${-shotOk[0]} (secure content or interval)"
                    else -> "Screenshot did not answer in 3s"
                }
            )
            rec.stage(
                "capture-capability", if (shotOk[0] == 1) "PASSED" else "INCONCLUSIVE",
                if (shotOk[0] == 1) "captured in ${took}ms" else "not needed for the experiment; recorded only"
            )
        } else {
            rec.stage("capture-capability", "INCONCLUSIVE", "capability not granted; not needed for the experiment")
        }
        rec.saveNow()

        // ---------------------------------------------------------------- memory before
        val memBefore = readMemory(am)

        // ---------------------------------------------------------------- pillar 3 pre-read
        rec.section("Foreground preservation (pillar 3)")
        val controller = OeaProjectionController { OeaLiveWindowManager.accessibilityService() }
        val foregroundBefore = controller.activePackageOnDisplay(android.view.Display.DEFAULT_DISPLAY)
        rec.line("Foreground app before the experiment: ${foregroundBefore ?: "unknown (no active window reported)"}")
        rec.stage(
            "foreground-before", if (foregroundBefore != null) "PASSED" else "INCONCLUSIVE",
            foregroundBefore ?: "no active window reported"
        )

        // ---------------------------------------------------------------- create VD
        rec.section("Virtual display")
        var t0 = SystemClock.uptimeMillis()
        val host = when (val r = OeaVirtualDisplayHost.create(context, "OEA Stage-B")) {
            is OeaVirtualDisplayHost.CreateResult.Created -> {
                val displayDesc = try {
                    (context.getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager)
                        ?.getDisplay(r.host.displayId)?.let { d ->
                            "display ${d.displayId}, flags=${d.flags} (${d})"
                        }
                } catch (_: Throwable) {
                    null
                } ?: "display ${r.host.displayId}"
                rec.line("Created: ${r.host.width}x${r.host.height} @${r.host.densityDpi}dpi, flags=${r.host.flagsLabel}")
                rec.line("Display state as the OS sees it: $displayDesc")
                rec.stage("virtual-display", "PASSED", "display ${r.host.displayId} created (${r.host.flagsLabel})")
                rec.saveNow()
                r.host
            }
            is OeaVirtualDisplayHost.CreateResult.Failed -> {
                rec.line("FAILED: ${r.reason}")
                r.attempts.forEach { rec.line("  attempt: $it") }
                rec.stage("virtual-display", "FAILED", r.reason)
                return
            }
        }

        // ---------------------------------------------------------------- launch
        rec.section("Launch ladder (pillar 1)")
        val launchIntent = pm.getLaunchIntentForPackage(targetPackage)
        val activityName = launchIntent?.component?.className
        if (activityName == null) {
            rec.line("FAILED: no launchable activity for $targetPackage")
            rec.stage("starter-launch", "FAILED", "no launchable activity")
            host.release()
            return
        }
        t0 = SystemClock.uptimeMillis()
        val launch = OeaProjectionLaunch.launch(service, host, targetPackage, activityName, appLabel)
        val launchMs = SystemClock.uptimeMillis() - t0
        launch.evidence.forEach { rec.line(it.toString()) }
        rec.stage(
            "occupancy",
            when {
                launch.occupancyShown && launch.occupancyAttached -> "PASSED"
                launch.occupancyShown -> "INCONCLUSIVE"
                else -> "FAILED"
            },
            when {
                launch.occupancyShown && launch.occupancyAttached -> "shown and attached"
                launch.occupancyShown -> "shown but attach not confirmed"
                else -> "could not be shown"
            }
        )
        rec.stage(
            "starter-launch",
            if (launch.success) "PASSED" else "FAILED",
            if (launch.success) "a legal launch route landed the starter on the private display"
            else launch.routeAttempts.firstNotNullOfOrNull { it.threw }?.substringBefore('\n')
                ?: "no route landed the starter"
        )
        if (!launch.success) {
            rec.line("Launch failed after ${launchMs}ms — ${launch.headline}")
            val leaked = controller.packageOnDisplay(android.view.Display.DEFAULT_DISPLAY, targetPackage)
            if (leaked) rec.line("Note: $appLabel ended up on the physical screen; OEA did not count that as success.")
            rec.saveNow()
            return
        }
        rec.saveNow()

        // ---------------------------------------------------------------- pillar 1: frames
        rec.section("Live frames (pillar 1)")
        t0 = SystemClock.uptimeMillis()
        var frames = 0
        val frameWindow = 3_000L
        while (SystemClock.uptimeMillis() - t0 < frameWindow) {
            val b = host.acquireLatestBitmap()
            if (b != null) {
                frames++
                b.recycle()
            }
            Thread.sleep(100)
        }
        rec.line("Frames received in ${frameWindow / 1000}s: $frames ${if (frames > 0) "(live rendering confirmed)" else "(no frames — the app may render nothing on this display)"}")
        rec.stage(
            "live-frames", if (frames > 0) "PASSED" else "FAILED",
            "$frames frames in ${frameWindow / 1000}s"
        )
        rec.saveNow()

        // ---------------------------------------------------------------- pillar 2: nodes
        rec.section("Interaction capability (pillar 2)")
        val probe = controller.probe(host.displayId, targetPackage)
        rec.line("Windows on the virtual display: ${probe.windowsOnDisplay}")
        rec.line("Accessibility nodes: ${probe.nodesOnDisplay}")
        rec.line("Clickable controls: ${probe.clickableNodes}")
        rec.line("Editable fields: ${probe.editableNodes}")
        rec.line("Scrollable containers: ${probe.scrollableNodes}")
        rec.line(
            if (probe.nodeControlAvailable)
                "Node control: AVAILABLE — taps, scrolls and typing can reach the real app."
            else
                "Node control: NOT AVAILABLE — the window would be view-only for this app on this device."
        )
        rec.stage(
            "interaction-capability",
            if (probe.nodeControlAvailable) "PASSED" else "INCONCLUSIVE",
            if (probe.nodeControlAvailable)
                "${probe.clickableNodes} clickable, ${probe.editableNodes} editable, ${probe.scrollableNodes} scrollable"
            else "no actionable nodes exposed (view-only for this app)"
        )

        // A real (but harmless) node action, so the report can say whether
        // the interaction channel works end to end, not just that it exists.
        val interaction = controller.interactionProbe(host.displayId, targetPackage)
        rec.line("Node interaction probe (focus + release, nothing clicked or typed): ${when (interaction) {
            is OeaNodeOutcome.Ok -> "PASSED — ${interaction.detail}"
            is OeaNodeOutcome.Rejected -> "REFUSED — ${interaction.reason}"
            is OeaNodeOutcome.Failed -> "NOT POSSIBLE — ${interaction.reason}"
        }}")
        rec.stage(
            "node-interaction",
            when (interaction) {
                is OeaNodeOutcome.Ok -> "PASSED"
                is OeaNodeOutcome.Rejected -> "INCONCLUSIVE"
                is OeaNodeOutcome.Failed -> "INCONCLUSIVE"
            },
            when (interaction) {
                is OeaNodeOutcome.Ok -> interaction.detail
                is OeaNodeOutcome.Rejected -> interaction.reason
                is OeaNodeOutcome.Failed -> interaction.reason
            }
        )
        rec.saveNow()

        // ---------------------------------------------------------------- pillar 3: re-read
        rec.section("Foreground after the experiment (pillar 3)")
        Thread.sleep(600)
        val foregroundAfter = controller.activePackageOnDisplay(android.view.Display.DEFAULT_DISPLAY)
        val preserved = foregroundBefore != null && foregroundAfter == foregroundBefore
        rec.line("Foreground app after the experiment: ${foregroundAfter ?: "unknown"}")
        rec.line(
            if (preserved)
                "PRESERVED: the same app still owns the physical screen while $appLabel runs on the private display."
            else
                "NOT PRESERVED: the foreground app changed (${foregroundBefore} → ${foregroundAfter}). This device does not keep the user's app in front."
        )
        rec.stage(
            "foreground-after", if (preserved) "PASSED" else "FAILED",
            "${foregroundBefore ?: "unknown"} → ${foregroundAfter ?: "unknown"}"
        )

        // ---------------------------------------------------------------- memory after
        val memAfter = readMemory(am)
        rec.line("Memory: used ${memBefore.first}MB → ${memAfter.first}MB (device total ${memAfter.second}MB)")

        // ---------------------------------------------------------------- cleanup
        rec.section("Cleanup")
        t0 = SystemClock.uptimeMillis()
        OeaProjectionTrampolineActivity.finishLiving()
        host.release()
        Thread.sleep(400)
        val stillOnDisplay = controller.packageOnDisplay(host.displayId, targetPackage)
        val stillOnPhone = controller.packageOnDisplay(android.view.Display.DEFAULT_DISPLAY, targetPackage)
        rec.line(
            "Display released in ${SystemClock.uptimeMillis() - t0}ms. Target still visible: " +
                    "private display=${stillOnDisplay}, physical screen=${stillOnPhone}"
        )
        rec.line(
            if (stillOnDisplay)
                "Note: the app's window outlived the display release on this device — it may reappear in Recents."
            else
                "The projected window was cleaned up with the display."
        )
        rec.stage(
            "cleanup", if (stillOnDisplay) "INCONCLUSIVE" else "PASSED",
            if (stillOnDisplay) "app window outlived the display release" else "cleaned up with the display"
        )

        rec.line("")
        rec.line("User checklist (not automatable):")
        rec.line("1. Open a live window, tap a conversation, scroll the list.")
        rec.line("2. Tap the message field, use the text strip to type, tap the app's send button.")
        rec.line("3. Confirm the message really arrives in the app (open the real app afterwards).")
        rec.line("4. Confirm the app you were using before is still visible and usable throughout.")
        rec.line("Memory delta: ${memAfter.first - memBefore.first}MB used.")
    }

    // ---------------------------------------------------------------- helpers

    private fun readMemory(am: ActivityManager?): Pair<Long, Long> {
        val info = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(info)
        val usedMb = (info.totalMem - info.availMem) / (1024 * 1024)
        val totalMb = info.totalMem / (1024 * 1024)
        return usedMb to totalMb
    }

    // ---------------------------------------------------------------- storage

    fun save(context: Context, report: String): String {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_REPORT, report)
            .putLong(KEY_AT, System.currentTimeMillis())
            .apply()
        return report
    }

    fun lastReport(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_REPORT, null)

    fun lastReportAt(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_AT, 0L)
}
