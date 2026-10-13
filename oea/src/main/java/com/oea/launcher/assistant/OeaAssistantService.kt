package com.oea.launcher.assistant

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * OEA Assistant: the accessibility service behind OEA's system actions.
 *
 * It stays idle (no screen content is read) until the user triggers an OEA
 * action. Global actions (lock, notifications, Recents, split screen) are one
 * call. The App Freezer's no-root mode drives Android's own App info page and
 * presses Force stop, one app at a time, then returns to OEA.
 */
class OeaAssistantService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var job: ForceStopJob? = null
    private val stepRunnable = Runnable { step() }
    private val timeoutRunnable = Runnable { stageTimedOut() }

    private class ForceStopJob(
        val queue: ArrayDeque<String>,
        val total: Int,
        val settingsPackages: Set<String>,
        val stopLabels: List<String>,
        val confirmLabels: List<String>,
        val returnTo: Intent?,
        val callback: ((stopped: List<String>, failed: List<String>) -> Unit)?,
    ) {
        val stopped = mutableListOf<String>()
        val failed = mutableListOf<String>()
        var current: String? = null
        var stage = Stage.FIND_BUTTON
    }

    private enum class Stage { FIND_BUTTON, CONFIRM, SETTLE }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        applyEventMask(active = false)
        OeaAssistant.notifyStateChanged()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val active = job ?: return
        val pkg = event?.packageName?.toString() ?: return
        if (pkg in active.settingsPackages) scheduleStep(90L)
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        abortJob()
        if (instance === this) instance = null
        OeaAssistant.notifyStateChanged()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        abortJob()
        if (instance === this) instance = null
        OeaAssistant.notifyStateChanged()
        super.onDestroy()
    }

    fun isForceStopping(): Boolean = job != null

    /** Starts force-stopping [packages]; returns false when a run is already active. */
    fun startForceStop(
        packages: List<String>,
        returnTo: Intent?,
        callback: ((stopped: List<String>, failed: List<String>) -> Unit)?,
    ): Boolean {
        if (job != null) return false
        val targets = packages.filter { it.isNotBlank() && it != packageName }.distinct()
        if (targets.isEmpty()) return false
        val settings = settingsPackages()
        job = ForceStopJob(
            queue = ArrayDeque(targets),
            total = targets.size,
            settingsPackages = settings,
            stopLabels = labels(settings, listOf("force_stop"), listOf("Force stop", "Force Stop", "Force close", "Stop")),
            confirmLabels = labels(settings, listOf("dlg_ok", "okay"), listOf("OK", "Ok", "Force stop", "Force Stop", "Stop")),
            returnTo = returnTo,
            callback = callback,
        )
        applyEventMask(active = true)
        nextPackage()
        return true
    }

    private fun nextPackage() {
        val active = job ?: return
        handler.removeCallbacks(stepRunnable)
        handler.removeCallbacks(timeoutRunnable)
        val pkg = active.queue.removeFirstOrNull()
        if (pkg == null) {
            completeJob()
            return
        }
        active.current = pkg
        active.stage = Stage.FIND_BUTTON
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_HISTORY or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
            )
        if (runCatching { startActivity(details) }.isFailure) {
            active.failed += pkg
            handler.post { nextPackage() }
            return
        }
        scheduleStep(650L)
        restartTimeout()
    }

    private fun scheduleStep(delay: Long) {
        handler.removeCallbacks(stepRunnable)
        handler.postDelayed(stepRunnable, delay)
    }

    private fun restartTimeout() {
        handler.removeCallbacks(timeoutRunnable)
        handler.postDelayed(timeoutRunnable, STAGE_TIMEOUT_MS)
    }

    private fun step() {
        val active = job ?: return
        val pkg = active.current ?: return
        if (active.stage == Stage.SETTLE) return
        val root = rootInActiveWindow
        if (root == null) {
            scheduleStep(250L)
            return
        }
        val rootPackage = root.packageName?.toString()
        when (active.stage) {
            Stage.FIND_BUTTON -> {
                if (rootPackage !in active.settingsPackages) {
                    scheduleStep(250L)
                    return
                }
                val button = findLabeled(root, active.stopLabels)
                if (button == null) {
                    // The page may still be loading; content events re-trigger this step.
                    scheduleStep(400L)
                    return
                }
                if (!button.isEnabled) {
                    // Android greys out Force stop when the app is already stopped.
                    active.stopped += pkg
                    settleThenNext()
                    return
                }
                if (click(button)) {
                    active.stage = Stage.CONFIRM
                    restartTimeout()
                    scheduleStep(300L)
                } else {
                    scheduleStep(400L)
                }
            }
            Stage.CONFIRM -> {
                val positive = root.findAccessibilityNodeInfosByViewId("android:id/button1")
                    .firstOrNull { it.isEnabled && it.isVisibleToUser }
                if (positive != null && click(positive)) {
                    active.stopped += pkg
                    settleThenNext()
                    return
                }
                if (rootPackage in active.settingsPackages) {
                    val pageButton = findLabeled(root, active.stopLabels)
                    if (pageButton != null && !pageButton.isEnabled) {
                        // Some firmware stops the app without a confirmation dialog.
                        active.stopped += pkg
                        settleThenNext()
                        return
                    }
                    val labeled = findLabeled(root, active.confirmLabels)
                    if (labeled != null && labeled.isEnabled && pageButton == null && click(labeled)) {
                        active.stopped += pkg
                        settleThenNext()
                        return
                    }
                }
                scheduleStep(300L)
            }
            Stage.SETTLE -> Unit
        }
    }

    private fun settleThenNext() {
        val active = job ?: return
        active.stage = Stage.SETTLE
        handler.removeCallbacks(stepRunnable)
        handler.removeCallbacks(timeoutRunnable)
        handler.postDelayed({ nextPackage() }, 380L)
    }

    private fun stageTimedOut() {
        val active = job ?: return
        val pkg = active.current ?: return
        if (active.stage == Stage.CONFIRM) {
            val root = rootInActiveWindow
            val pageButton = root?.let { findLabeled(it, active.stopLabels) }
            if (pageButton != null && !pageButton.isEnabled) active.stopped += pkg else active.failed += pkg
        } else if (active.stage == Stage.FIND_BUTTON) {
            active.failed += pkg
        }
        settleThenNext()
    }

    private fun completeJob() {
        val finished = job ?: return
        job = null
        handler.removeCallbacks(stepRunnable)
        handler.removeCallbacks(timeoutRunnable)
        applyEventMask(active = false)
        val back = finished.returnTo
        if (back != null) {
            runCatching { startActivity(Intent(back).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { performGlobalAction(GLOBAL_ACTION_BACK) }
        } else {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
        finished.callback?.invoke(finished.stopped.toList(), finished.failed.toList())
        OeaAssistant.notifyStateChanged()
    }

    private fun abortJob() {
        val aborted = job ?: return
        job = null
        handler.removeCallbacks(stepRunnable)
        handler.removeCallbacks(timeoutRunnable)
        aborted.current?.let { if (it !in aborted.stopped) aborted.failed += it }
        aborted.failed += aborted.queue
        aborted.callback?.invoke(aborted.stopped.toList(), aborted.failed.toList())
    }

    private fun findLabeled(root: AccessibilityNodeInfo, labels: List<String>): AccessibilityNodeInfo? {
        for (label in labels) {
            val matches = runCatching { root.findAccessibilityNodeInfosByText(label) }.getOrNull().orEmpty()
            val exact = matches.firstOrNull { node ->
                val text = node.text?.toString()?.trim()
                val description = node.contentDescription?.toString()?.trim()
                (text != null && text.equals(label, ignoreCase = true)) ||
                    (description != null && description.equals(label, ignoreCase = true))
            }
            if (exact != null && exact.isVisibleToUser) return exact
        }
        return null
    }

    private fun click(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 5) {
            if (current.isClickable && current.isEnabled) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
            depth++
        }
        return false
    }

    private fun settingsPackages(): Set<String> {
        val result = linkedSetOf("com.android.settings")
        val probe = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        runCatching { packageManager.resolveActivity(probe, 0)?.activityInfo?.packageName }
            .getOrNull()?.let { result += it }
        result += "com.miui.securitycenter"
        return result
    }

    private fun labels(packages: Set<String>, names: List<String>, fallback: List<String>): List<String> {
        val found = LinkedHashSet<String>()
        packages.forEach { pkg ->
            val res = runCatching { packageManager.getResourcesForApplication(pkg) }.getOrNull() ?: return@forEach
            names.forEach { name ->
                val id = res.getIdentifier(name, "string", pkg)
                if (id != 0) {
                    runCatching { res.getString(id) }.getOrNull()
                        ?.trim()?.takeIf { it.isNotEmpty() }?.let { found += it }
                }
            }
        }
        found.addAll(fallback)
        return found.toList()
    }

    private fun applyEventMask(active: Boolean) {
        val info = serviceInfo ?: return
        info.eventTypes = if (active) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        } else {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        }
        // Idle: only OEA's own windows. Active: only the Settings app being driven.
        info.packageNames = if (active) job?.settingsPackages?.toTypedArray() else arrayOf(packageName)
        info.notificationTimeout = if (active) 60L else 250L
        runCatching { serviceInfo = info }
    }

    companion object {
        private const val STAGE_TIMEOUT_MS = 5_000L

        @Volatile
        var instance: OeaAssistantService? = null
            private set
    }
}
