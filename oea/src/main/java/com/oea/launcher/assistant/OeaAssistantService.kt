package com.oea.launcher.assistant

import android.accessibilityservice.AccessibilityService
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * OEA Assistant: the accessibility service behind OEA's system actions.
 *
 * It stays idle (no screen content is read) until the user triggers an OEA
 * action. Global actions (lock, notifications, Recents, split screen) are one
 * call. The App Freezer's no-root mode drives Android's own App info page and
 * presses Force stop, one app at a time, then returns to OEA. While it does,
 * a full-screen accessibility overlay shows a clean "Freezing apps" progress
 * screen instead of Settings pages flashing past.
 *
 * Extended: when a frozen app (OEA Freeze) appears in foreground via external
 * link or download action, this service blocks it by sending the user back to
 * Home and force-stopping it, closing the bypass route reported.
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
        val callback: ((stopped: List<String>, failed: List<String>) -> Unit)?
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
        OeaAssistant.onServiceConnected()
    }

    private var lastWindowKey: String? = null
    private var lastFrozenBlockAt = 0L
    private var lastFrozenPackage: String? = null
    /** A frozen-app interception that is currently playing out; kept apart from the user's own force-stop job. */
    private var frozenJob: FrozenBlock? = null

    private class FrozenBlock(val packageName: String)

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        com.oea.launcher.fault.OeaFaultShield.guard("assistant:event") {
            // Access Doctor is waiting for the Wireless debugging screens: read them.
            if (com.oea.launcher.privilege.OeaWirelessPairing.isWatching()) {
                runCatching { com.oea.launcher.privilege.OeaWirelessPairing.scan(rootInActiveWindow, event?.packageName?.toString()) }
                    .onFailure { com.oea.launcher.fault.OeaFaultShield.note("assistant:pairing", it) }
            }
            if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                val changed = event.packageName?.toString()
                if (!changed.isNullOrEmpty()) {
                    val cls = event.className?.toString()
                    val key = "$changed/$cls"
                    if (key != lastWindowKey) {
                        lastWindowKey = key
                        runCatching { com.oea.launcher.gameboost.OeaGameBoost.onWindowChanged(this, changed, cls) }
                            .onFailure { com.oea.launcher.fault.OeaFaultShield.note("assistant:boost-window", it) }
                        // Block frozen apps that slipped through via external intents
                        runCatching { maybeBlockFrozenApp(changed) }
                    }
                }
            }
            val watch = foregroundWatch
            if (watch != null && event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                event.packageName?.toString() == watch.packageName) {
                finishForegroundWatch(true)
            }
            val active = job ?: return@guard
            val pkg = event?.packageName?.toString() ?: return@guard
            if (pkg in active.settingsPackages) scheduleStep(90L)
        }
    }

    private fun maybeBlockFrozenApp(packageName: String) {
        if (packageName.isBlank() || packageName == this.packageName) return
        if (packageName in setOf("com.android.systemui", "android", "com.google.android.gms")) return
        // Never race an explicit user-driven force-stop run: fumbling BOTH jobs
        // would mix their states, so a frozen app that arrived while the user
        // is deliberately force-stopping something is left alone.
        if (job != null) return
        // One frozen package per window: a home-then-forcestop sequence takes a
        // moment; a second one would clobber it.
        if (frozenJob != null) return
        // Check if package is frozen via OEA Freeze (hibernated)
        val frozen = try {
            com.oea.launcher.applock.OeaAppFreezer.isFrozen(this, packageName) &&
                com.oea.launcher.applock.OeaAppFreezer.mode(this) == com.oea.launcher.applock.OeaAppFreezer.Mode.OEA
        } catch (_: Throwable) { false }
        if (!frozen) return
        // Also check if it's in hibernated set specifically (not system suspended)
        val hibernated = try { packageName in com.oea.launcher.applock.OeaAppFreezer.hibernatedPackages(this) } catch (_: Throwable) { false }
        if (!hibernated) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (packageName == lastFrozenPackage && now - lastFrozenBlockAt < 3000L) return
        lastFrozenPackage = packageName
        lastFrozenBlockAt = now
        // Block: kick back, bring Home up, then force-stop the escaped app so
        // it drops fully out of Android's cached state until it is unfrozen.
        // This is tracked apart from a user-driven force-stop job.
        val label = runCatching {
            @Suppress("DEPRECATION") packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)
        frozenJob = FrozenBlock(packageName)
        handler.post {
            runCatching { performGlobalAction(GLOBAL_ACTION_BACK) }
            handler.postDelayed({
                runCatching {
                    startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }, 200L)
            handler.postDelayed({
                val jobNow = frozenJob
                frozenJob = null
                runCatching {
                    OeaAssistant.forceStopWhenReady(this, listOf(packageName), null) { _, _ -> }
                }
                if (jobNow == null || jobNow.packageName != packageName) return@postDelayed
            }, 600L)
        }
        // Show brief explanation via toast on main
        handler.post {
            runCatching { android.widget.Toast.makeText(this, "$label is frozen in OEA — unfreeze it in the Freezer to use it", android.widget.Toast.LENGTH_LONG).show() }
        }
        com.oea.launcher.fault.OeaFaultShield.note("assistant:frozen-block", IllegalStateException("Blocked frozen $packageName via external route"))
    }

    // ======================================================= foreground watch

    private class ForegroundWatch(val packageName: String, val callback: (Boolean) -> Unit)

    private var foregroundWatch: ForegroundWatch? = null
    private val foregroundTimeout = Runnable { finishForegroundWatch(false) }

    fun watchForeground(packageName: String, timeoutMs: Long, callback: (Boolean) -> Unit): Boolean {
        if (job != null) return false
        foregroundWatch?.let {
            foregroundWatch = null
            handler.removeCallbacks(foregroundTimeout)
            runCatching { it.callback(false) }
        }
        val front = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
        if (front == packageName) {
            handler.post { callback(true) }
            return true
        }
        foregroundWatch = ForegroundWatch(packageName, callback)
        applyEventMask(active = false)
        handler.postDelayed(foregroundTimeout, timeoutMs)
        return true
    }

    private fun finishForegroundWatch(found: Boolean) {
        val watch = foregroundWatch ?: return
        foregroundWatch = null
        handler.removeCallbacks(foregroundTimeout)
        applyEventMask(active = job != null)
        runCatching { watch.callback(found) }
    }

    fun isSplitScreenActive(): Boolean {
        val all = runCatching { windows }.getOrNull().orEmpty()
        if (all.any { it.type == AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER }) return true
        val metrics = resources.displayMetrics
        val screenArea = metrics.widthPixels.toLong() * metrics.heightPixels.toLong()
        if (screenArea <= 0L) return false
        val bounds = android.graphics.Rect()
        val partialApps = all.count { window ->
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) return@count false
            window.getBoundsInScreen(bounds)
            val area = bounds.width().toLong() * bounds.height().toLong()
            area > 0L && area < screenArea * 8 / 10
        }
        return partialApps >= 2
    }

    override fun onInterrupt() = Unit

    fun currentForegroundPackage(): String? = runCatching {
        val node = rootInActiveWindow ?: return null
        val pkg = node.packageName?.toString()
        if (Build.VERSION.SDK_INT < 33) {
            @Suppress("DEPRECATION")
            node.recycle()
        }
        pkg
    }.getOrNull()

    fun refreshEventMask() {
        applyEventMask(active = job != null)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        finishForegroundWatch(false)
        abortJob()
        hideProgressOverlay(immediately = true)
        if (instance === this) instance = null
        OeaAssistant.notifyStateChanged()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        abortJob()
        hideProgressOverlay(immediately = true)
        if (instance === this) instance = null
        OeaAssistant.notifyStateChanged()
        super.onDestroy()
    }

    fun isForceStopping(): Boolean = job != null

    fun startForceStop(
        packages: List<String>,
        returnTo: Intent?,
        callback: ((stopped: List<String>, failed: List<String>) -> Unit)?
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
            callback = callback
)
        applyEventMask(active = true)
        showProgressOverlay()
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
        updateProgressOverlay(active, pkg)
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_HISTORY or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
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

    private fun settingsRoot(active: ForceStopJob): AccessibilityNodeInfo? {
        val root = runCatching { rootInActiveWindow }.getOrNull()
        if (root?.packageName?.toString() in active.settingsPackages) return root
        val fromWindows = runCatching {
            windows.asSequence()
                .mapNotNull { window -> runCatching { window.root }.getOrNull() }
                .firstOrNull { it.packageName?.toString() in active.settingsPackages }
        }.getOrNull()
        return fromWindows ?: root
    }

    private fun step() {
        val active = job ?: return
        val pkg = active.current ?: return
        if (active.stage == Stage.SETTLE) return
        val root = settingsRoot(active)
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
                    scheduleStep(400L)
                    return
                }
                if (!button.isEnabled) {
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
            val root = settingsRoot(active)
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
        showProgressDone(finished)
        hideProgressOverlay(immediately = false)
        finished.callback?.invoke(finished.stopped.toList(), finished.failed.toList())
        OeaAssistant.notifyStateChanged()
    }

    private fun cancelForceStop() {
        val running = job ?: return
        val back = running.returnTo
        abortJob()
        applyEventMask(active = false)
        if (back != null) {
            runCatching { startActivity(Intent(back).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { performGlobalAction(GLOBAL_ACTION_BACK) }
        } else {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
        hideProgressOverlay(immediately = false)
        OeaAssistant.notifyStateChanged()
    }

    private var progressOverlay: View? = null
    private var progressTitle: TextView? = null
    private var progressApp: TextView? = null
    private var progressCount: TextView? = null
    private var progressIcon: ImageView? = null
    private var progressBar: View? = null
    private var progressTrack: FrameLayout? = null
    private var progressSpinner: ObjectAnimator? = null

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun showProgressOverlay() {
        if (progressOverlay != null) return
        val wm = getSystemService(WindowManager::class.java) ?: return
        val root = FrameLayout(this).apply {
            setBackgroundColor(0xF2070C16.toInt())
            isClickable = true
            isFocusable = false
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(32), dp(24), dp(32), dp(24))
        }
        val badge = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF0E4C8F.toInt(), 0xFF3FD5F5.toInt())
).apply { shape = GradientDrawable.OVAL }
            elevation = dp(8).toFloat()
        }
        val flake = ImageView(this).apply {
            setImageDrawable(getDrawable(com.oea.launcher.R.drawable.oic_ac_unit_fill)?.mutate()?.apply { setTint(Color.WHITE) })
        }
        badge.addView(flake, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
        column.addView(badge, LinearLayout.LayoutParams(dp(84), dp(84)).apply { bottomMargin = dp(22) })
        val title = TextView(this).apply {
            text = "Freezing apps"
            textSize = 22f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
        }
        column.addView(title, LinearLayout.LayoutParams(-2, -2))
        val appRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply {
                setColor(0x1AFFFFFF)
                cornerRadius = dp(18).toFloat()
            }
        }
        val icon = ImageView(this)
        appRow.addView(icon, LinearLayout.LayoutParams(dp(32), dp(32)).apply { rightMargin = dp(12) })
        val appLabel = TextView(this).apply {
            textSize = 15f
            setTextColor(0xFFE6EEF8.toInt())
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        appRow.addView(appLabel, LinearLayout.LayoutParams(-2, -2))
        column.addView(appRow, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(18) })
        val track = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(0x22FFFFFF)
                cornerRadius = dp(3).toFloat()
            }
        }
        val bar = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xFF3FA9F5.toInt(), 0xFF7BE7FF.toInt())
).apply { cornerRadius = dp(3).toFloat() }
        }
        track.addView(bar, FrameLayout.LayoutParams(0, -1))
        column.addView(track, LinearLayout.LayoutParams(dp(220), dp(6)).apply { topMargin = dp(22) })
        val count = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF9FB2C8.toInt())
            gravity = Gravity.CENTER
        }
        column.addView(count, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        val note = TextView(this).apply {
            text = "OEA is stopping each app through Android's App info so it stays asleep. This only takes a moment."
            textSize = 12.5f
            setTextColor(0xFF7F8EA3.toInt())
            gravity = Gravity.CENTER
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        column.addView(note, LinearLayout.LayoutParams(dp(280), -2).apply { topMargin = dp(18) })
        root.addView(column, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        val cancel = TextView(this).apply {
            text = "Cancel"
            textSize = 15f
            setTextColor(0xFFBFD3EA.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(12), dp(28), dp(12))
            background = GradientDrawable().apply {
                setColor(0x14FFFFFF)
                cornerRadius = dp(22).toFloat()
                setStroke(maxOf(1, dp(1) / 2), 0x33FFFFFF)
            }
            isClickable = true
            setOnClickListener { cancelForceStop() }
        }
        root.addView(cancel, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(56) })

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        val added = runCatching { wm.addView(root, params) }.isSuccess
        if (!added) return
        progressOverlay = root
        progressTitle = title
        progressApp = appLabel
        progressCount = count
        progressIcon = icon
        progressBar = bar
        progressTrack = track
        root.alpha = 0f
        root.animate().alpha(1f).setDuration(160L).start()
        progressSpinner = ObjectAnimator.ofFloat(flake, View.ROTATION, 0f, 360f).apply {
            duration = 4_000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun updateProgressOverlay(active: ForceStopJob, pkg: String) {
        if (progressOverlay == null) return
        val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }
            .getOrDefault(pkg)
        progressApp?.text = label
        progressIcon?.setImageDrawable(runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull())
        val index = (active.total - active.queue.size).coerceIn(1, active.total)
        progressCount?.text = if (active.total == 1) "Stopping $label" else "$index of ${active.total}"
        setProgressFraction((index - 1).toFloat() / active.total.coerceAtLeast(1))
    }

    private fun showProgressDone(finished: ForceStopJob) {
        if (progressOverlay == null) return
        progressTitle?.text = if (finished.failed.isEmpty()) "Apps frozen" else "Frozen in OEA"
        progressCount?.text = when {
            finished.failed.isEmpty() -> if (finished.stopped.size == 1) "1 app is asleep" else "${finished.stopped.size} apps are asleep"
            else -> "${finished.stopped.size} stopped • ${finished.failed.size} not confirmed by Android"
        }
        setProgressFraction(1f)
    }

    private fun setProgressFraction(fraction: Float) {
        val bar = progressBar ?: return
        val track = progressTrack ?: return
        track.post {
            val lp = bar.layoutParams ?: return@post
            lp.width = (track.width * fraction.coerceIn(0f, 1f)).toInt()
            bar.layoutParams = lp
        }
    }

    private fun hideProgressOverlay(immediately: Boolean) {
        val overlay = progressOverlay ?: return
        progressOverlay = null
        progressSpinner?.cancel()
        progressSpinner = null
        progressTitle = null
        progressApp = null
        progressCount = null
        progressIcon = null
        progressBar = null
        progressTrack = null
        val remove = { runCatching { getSystemService(WindowManager::class.java)?.removeView(overlay) } }
        if (immediately) {
            remove()
        } else {
            overlay.animate().alpha(0f).setStartDelay(PROGRESS_LINGER_MS).setDuration(180L)
                .withEndAction { remove() }.start()
        }
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
        val pairing = com.oea.launcher.privilege.OeaWirelessPairing.isWatching()
        // Pairing reads the dialog once its code appears, which is a content change.
        info.eventTypes = if (active || pairing) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        } else {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        }
        info.packageNames = when {
            active -> job?.settingsPackages?.toTypedArray()
            foregroundWatch != null -> null
            com.oea.launcher.gameboost.OeaGameBoostStore.enabled(this) -> null
            else -> null // Watch all windows for frozen-app blocking and game boost, not just OEA
        }
        info.notificationTimeout = when {
            pairing -> 100L
            active -> 60L
            else -> 250L
        }
        runCatching { serviceInfo = info }
    }

    companion object {
        private const val STAGE_TIMEOUT_MS = 5_000L
        private const val PROGRESS_LINGER_MS = 650L

        @Volatile
        var instance: OeaAssistantService? = null
            private set
    }
}
