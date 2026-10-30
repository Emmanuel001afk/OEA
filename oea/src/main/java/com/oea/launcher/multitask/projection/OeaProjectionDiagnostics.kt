package com.oea.launcher.multitask.projection

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
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
 * copy back for review:
 *
 *   1. LIVE PRESENTATION — the real app renders on an app-created virtual
 *      display and its frames reach OEA (frames counted over 3 seconds).
 *   2. REAL INTERACTION — the target exposes interactive accessibility
 *      nodes on that display (clickable/editable/scrollable counts), which
 *      is the only input channel Android gives an ordinary app there.
 *   3. FOREGROUND PRESERVATION — the app that was in front *before* the
 *      experiment still owns the physical screen afterwards (its window
 *      package is re-read and compared).
 *
 * It also records the honest "what this device cannot do" facts: freeform
 * feature, secondary-display feature, a11y screenshot capability and rate,
 * and memory cost.
 *
 * The harness never performs a destructive action in the target app: it
 * launches it, measures, and releases the display. Typed-input and
 * send-a-message tests are left to the user with the live window — the
 * report has a checklist section for exactly that.
 */
object OeaProjectionDiagnostics {

    /** Report is stored so it can be copied later. */
    const val PREFS = "oea_projection_diagnostics"
    const val KEY_REPORT = "last_report"
    const val KEY_AT = "last_report_at"

    interface Progress {
        fun onProgress(line: String)
    }

    /**
     * Runs the whole experiment. Must be called from a background thread.
     * [targetPackage] must be installed and launchable.
     */
    fun run(
        context: Context,
        targetPackage: String,
        appLabel: String,
        progress: Progress?
    ): String {
        val report = StringBuilder()
        val evidence = mutableListOf<OeaEvidenceLine>()
        fun log(line: String) {
            progress?.let { p -> Handler(Looper.getMainLooper()).post { p.onProgress(line) } }
        }
        fun section(title: String) {
            report.append("\n== $title ==\n")
            log(title)
        }
        fun line(text: String) {
            report.append(text).append('\n')
            log(text)
        }

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val pm = context.packageManager

        section("Stage B — Floating app projection experiment")
        line("Time: ${android.text.format.DateFormat.format("yyyy-MM-dd HH:mm:ss", System.currentTimeMillis())}")
        line("Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})")
        line("Target: $appLabel ($targetPackage)")
        val isGo = am?.isLowRamDevice == true
        line("Low-RAM/Go profile reported: $isGo")

        // ---------------------------------------------------------------- facts
        section("Platform facts (from the OS, not assumptions)")
        val freeform = pm.hasSystemFeature("android.software.freeform_window_management")
        val secondaryDisplays = pm.hasSystemFeature(PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS)
        line("Freeform window management feature: $freeform")
        line("Activities on secondary displays feature: $secondaryDisplays")

        val service = OeaLiveWindowManager.accessibilityService()
        if (service == null) {
            line("Accessibility service: NOT BOUND — the experiment cannot run.")
            line("Enable OEA Floating Window in Android Accessibility first (OEA Settings › Multitasking).")
            report.append("\nVERDICT: INCOMPLETE — service off.\n")
            return save(context, report.toString())
        }
        // The trampoline handshake is global: stop any running live windows
        // so the experiment owns the whole pipeline for its duration.
        if (OeaLiveWindowManager.count() > 0) {
            line("Stopping ${OeaLiveWindowManager.count()} running live window(s) for a clean experiment…")
            OeaLiveWindowManager.stopAll()
            Thread.sleep(500)
        }

        // A11y screenshot capability (used only for the default display here;
        // the virtual display needs no screenshot because OEA owns its surface).
        section("Capture capability")
        val screenshotCapable = runCatching {
            val info = service.serviceInfo
            info != null && (info.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT) != 0
        }.getOrDefault(false)
        line("Accessibility screenshot capability: $screenshotCapable")
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
                line("takeScreenshot threw: ${t.message}")
                latch.countDown()
            }
            latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
            val took = SystemClock.uptimeMillis() - shotStart
            line(
                when {
                    shotOk[0] == 1 -> "Screenshot of the physical screen: OK in ${took}ms (platform rate limit is 1 frame / 333ms)"
                    shotOk[0] < 0 -> "Screenshot refused with error ${-shotOk[0]} (secure content or interval)"
                    else -> "Screenshot did not answer in 3s"
                }
            )
        }

        // ---------------------------------------------------------------- memory before
        val memBefore = readMemory(am)

        // ---------------------------------------------------------------- pillar 3 pre-read
        section("Foreground preservation (pillar 3)")
        val controller = OeaProjectionController { OeaLiveWindowManager.accessibilityService() }
        val foregroundBefore = controller.activePackageOnDisplay(android.view.Display.DEFAULT_DISPLAY)
        line("Foreground app before the experiment: ${foregroundBefore ?: "unknown (no active window reported)"}")

        // ---------------------------------------------------------------- create VD
        section("Virtual display")
        var t0 = SystemClock.uptimeMillis()
        val host = when (val r = OeaVirtualDisplayHost.create(context, "OEA Stage-B")) {
            is OeaVirtualDisplayHost.CreateResult.Created -> {
                line("Created: display ${r.host.displayId}, ${r.host.width}x${r.host.height} @${r.host.densityDpi}dpi, flags=${r.host.flagsLabel}")
                evidence += OeaEvidenceLine("Virtual display", "created (flags ${r.host.flagsLabel})", SystemClock.uptimeMillis() - t0)
                r.host
            }
            is OeaVirtualDisplayHost.CreateResult.Failed -> {
                line("FAILED: ${r.reason}")
                r.attempts.forEach { line("  attempt: $it") }
                evidence += OeaEvidenceLine("Virtual display", "failed: ${r.reason}", SystemClock.uptimeMillis() - t0)
                report.append(verdict(foregroundBefore, null, null, memBefore, null, evidence))
                return save(context, report.toString())
            }
        }

        // ---------------------------------------------------------------- launch
        section("Launch ladder (pillar 1)")
        val launchIntent = pm.getLaunchIntentForPackage(targetPackage)
        val activityName = launchIntent?.component?.className
        if (activityName == null) {
            line("FAILED: no launchable activity for $targetPackage")
            host.release()
            report.append(verdict(foregroundBefore, null, null, memBefore, null, evidence))
            return save(context, report.toString())
        }
        t0 = SystemClock.uptimeMillis()
        val launch = OeaProjectionLaunch.launch(service, host, targetPackage, activityName, appLabel)
        val launchMs = SystemClock.uptimeMillis() - t0
        launch.evidence.forEach { line(it.toString()) }
        evidence += OeaEvidenceLine("Launch ladder", if (launch.success) "succeeded" else "failed", launchMs)
        if (!launch.success) {
            line("Launch failed after ${launchMs}ms — ${launch.headline}")
            val leaked = controller.packageOnDisplay(android.view.Display.DEFAULT_DISPLAY, targetPackage)
            if (leaked) line("Note: $appLabel ended up on the physical screen; OEA did not count that as success.")
            report.append(verdict(foregroundBefore, false, null, memBefore, null, evidence))
            return save(context, report.toString())
        }

        // ---------------------------------------------------------------- pillar 1: frames
        section("Live frames (pillar 1)")
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
        line("Frames received in ${frameWindow / 1000}s: $frames ${if (frames > 0) "(live rendering confirmed)" else "(no frames — the app may render nothing on this display)"}")
        evidence += OeaEvidenceLine("Frames in 3s", "$frames", frameWindow)

        // ---------------------------------------------------------------- pillar 2: nodes
        section("Interaction capability (pillar 2)")
        val probe = controller.probe(host.displayId, targetPackage)
        line("Windows on the virtual display: ${probe.windowsOnDisplay}")
        line("Accessibility nodes: ${probe.nodesOnDisplay}")
        line("Clickable controls: ${probe.clickableNodes}")
        line("Editable fields: ${probe.editableNodes}")
        line("Scrollable containers: ${probe.scrollableNodes}")
        line(
            if (probe.nodeControlAvailable)
                "Node control: AVAILABLE — taps, scrolls and typing can reach the real app."
            else
                "Node control: NOT AVAILABLE — the window would be view-only for this app on this device."
        )
        evidence += OeaEvidenceLine(
            "Node control",
            if (probe.nodeControlAvailable) "available (${probe.clickableNodes} clickable)" else "unavailable"
        )

        // ---------------------------------------------------------------- pillar 3: re-read
        section("Foreground after the experiment (pillar 3)")
        Thread.sleep(600)
        val foregroundAfter = controller.activePackageOnDisplay(android.view.Display.DEFAULT_DISPLAY)
        val preserved = foregroundBefore != null && foregroundAfter == foregroundBefore
        line("Foreground app after the experiment: ${foregroundAfter ?: "unknown"}")
        line(
            if (preserved)
                "PRESERVED: the same app still owns the physical screen while $appLabel runs on the private display."
            else
                "NOT PRESERVED: the foreground app changed (${foregroundBefore} → ${foregroundAfter}). This device does not keep the user's app in front."
        )
        evidence += OeaEvidenceLine("Foreground preserved", "$preserved")

        // ---------------------------------------------------------------- memory after
        val memAfter = readMemory(am)
        line("Memory: used ${memBefore.first}MB → ${memAfter.first}MB (device total ${memAfter.second}MB)")

        // ---------------------------------------------------------------- cleanup
        section("Cleanup")
        t0 = SystemClock.uptimeMillis()
        OeaProjectionTrampolineActivity.finishLiving()
        host.release()
        Thread.sleep(400)
        val stillOnDisplay = controller.packageOnDisplay(host.displayId, targetPackage)
        val stillOnPhone = controller.packageOnDisplay(android.view.Display.DEFAULT_DISPLAY, targetPackage)
        line(
            "Display released in ${SystemClock.uptimeMillis() - t0}ms. Target still visible: " +
                    "private display=${stillOnDisplay}, physical screen=${stillOnPhone}"
        )
        line(
            if (stillOnDisplay)
                "Note: the app's window outlived the display release on this device — it may reappear in Recents."
            else
                "The projected window was cleaned up with the display."
        )

        report.append(verdict(foregroundBefore, preserved, probe, memBefore, memAfter, evidence))
        return save(context, report.toString())
    }

    // ---------------------------------------------------------------- verdict

    private fun verdict(
        foregroundBefore: String?,
        preserved: Boolean?,
        probe: OeaProjectionController.Probe?,
        memBefore: Pair<Long, Long>?,
        memAfter: Pair<Long, Long>?,
        evidence: List<OeaEvidenceLine>
    ): String {
        val sb = StringBuilder("\n== Verdict ==\n")
        val p1 = probe != null
        val p2 = probe?.nodeControlAvailable == true
        val p3 = preserved == true
        sb.append("Pillar 1 — real app presented on a private display: ${if (p1) "PASS (on this device)" else "FAIL"}\n")
        sb.append("Pillar 2 — real interaction channel available:      ${if (p2) "PASS (node actions)" else "FAIL/UNKNOWN"}\n")
        sb.append("Pillar 3 — original foreground app preserved:       ${when (preserved) { null -> "NOT MEASURED"; true -> "PASS"; false -> "FAIL" }}\n")
        sb.append("Overall: ${if (p1 && p2 && p3) "the concept is VIABLE on this device" else "NOT PROVEN on this device — see the lines above"}\n")
        sb.append("\nEvidence:\n")
        evidence.forEach { sb.append(it.toString()).append('\n') }
        sb.append(
            "\nUser checklist (not automatable):\n" +
                    "1. Open a live window, tap a conversation, scroll the list.\n" +
                    "2. Tap the message field, use the text strip to type, tap the app's send button.\n" +
                    "3. Confirm the message really arrives in the app (open the real app afterwards).\n" +
                    "4. Confirm the app you were using before is still visible and usable throughout.\n"
        )
        if (foregroundBefore != null && preserved == false) {
            sb.append("Foreground changed during the run — record what the user saw: ").append(foregroundBefore).append('\n')
        }
        if (memBefore != null && memAfter != null) {
            sb.append("Memory delta: ${memAfter.first - memBefore.first}MB used.\n")
        }
        return sb.toString()
    }

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
