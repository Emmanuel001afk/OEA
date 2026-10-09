package com.oea.launcher.gameboost

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

class OeaGameBoostService : Service() {
    private class MemoryMetricsView(context: Context) : TextView(context) {
        var ramFraction = 0f
            set(value) { field = value.coerceIn(0f, 1f); invalidate() }
        var virtualFraction = 0f
            set(value) { field = value.coerceIn(0f, 1f); invalidate() }
        var ramUsedMb = 0.0
            set(value) { field = value; invalidate() }
        var ramTotalMb = 0.0
            set(value) { field = value; invalidate() }
        var virtualUsedMb = 0.0
            set(value) { field = value; invalidate() }
        var virtualTotalMb = 0.0
            set(value) { field = value; invalidate() }
        var lowMemory = false
            set(value) { field = value; invalidate() }

        private val track = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = dp(5).toFloat()
            strokeCap = android.graphics.Paint.Cap.ROUND
            color = 0x55343A48
        }
        private val progress = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = dp(6).toFloat()
            strokeCap = android.graphics.Paint.Cap.ROUND
        }
        private val label = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            color = Color.WHITE
        }
        private val percent = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            color = Color.WHITE
        }
        private val detail = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = android.graphics.Paint.Align.CENTER
            color = 0xFFBFC6D8.toInt()
        }
        private val glow = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = dp(10).toFloat()
            strokeCap = android.graphics.Paint.Cap.ROUND
        }
        private val highlight = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
            strokeCap = android.graphics.Paint.Cap.ROUND
            color = 0xFFEAFBFF.toInt()
        }
        private var pulseAngle = 0f
        private var ringAnimator: android.animation.ValueAnimator? = null

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (ringAnimator?.isRunning == true) return
            ringAnimator = android.animation.ValueAnimator.ofFloat(0f, 360f).apply {
                duration = 2400L
                repeatCount = android.animation.ValueAnimator.INFINITE
                interpolator = android.view.animation.LinearInterpolator()
                addUpdateListener {
                    pulseAngle = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        override fun onDetachedFromWindow() {
            ringAnimator?.cancel()
            ringAnimator = null
            super.onDetachedFromWindow()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(dp(220))
            val desiredHeight = dp(72)
            setMeasuredDimension(width, resolveSize(desiredHeight, heightMeasureSpec))
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            val available = width - paddingLeft - paddingRight
            val diameter = minOf(dp(58), ((available - dp(14)) / 2).coerceAtLeast(dp(52)))
            val radius = diameter / 2f - dp(3)
            val centerY = dp(29).toFloat()
            val leftCenterX = paddingLeft + available / 4f
            val rightCenterX = paddingLeft + available * 3f / 4f

            drawGauge(
                canvas, leftCenterX, centerY, radius,
                ramFraction, "RAM",
                String.format(Locale.US, "%.0f%%", ramFraction * 100.0),
                String.format(Locale.US, "%.0f / %.0f MB", ramUsedMb, ramTotalMb),
                0xFF4D7CFF.toInt()
            )
            drawGauge(
                canvas, rightCenterX, centerY, radius,
                virtualFraction, "VIRTUAL RAM",
                if (virtualTotalMb > 0) String.format(Locale.US, "%.0f%%", virtualFraction * 100.0) else "—",
                if (virtualTotalMb > 0) String.format(Locale.US, "%.0f / %.0f MB", virtualUsedMb, virtualTotalMb) else "not exposed",
                0xFF27D9B7.toInt()
            )
        }

        private fun drawGauge(
            canvas: android.graphics.Canvas,
            cx: Float,
            cy: Float,
            radius: Float,
            fraction: Float,
            name: String,
            percentage: String,
            value: String,
            color: Int
        ) {
            canvas.drawCircle(cx, cy, radius, track)
            val sweep = fraction.coerceIn(0f, 1f) * 360f
            val bounds = android.graphics.RectF(cx - radius, cy - radius, cx + radius, cy + radius)
            if (sweep > 0f) {
                // Soft colored halo beneath the crisp progress arc.
                glow.color = (color and 0x00FFFFFF) or (0x48 shl 24)
                canvas.drawArc(bounds, -90f, sweep, false, glow)
                progress.color = color
                canvas.drawArc(bounds, -90f, sweep, false, progress)

                // A bright traveling glint makes the live reading feel active
                // without changing or exaggerating the underlying percentage.
                val glintSweep = minOf(26f, sweep)
                val glintStart = -90f + ((pulseAngle / 360f) * sweep)
                glow.color = (color and 0x00FFFFFF) or (0x88 shl 24)
                canvas.drawArc(bounds, glintStart, glintSweep, false, glow)
                canvas.drawArc(bounds, glintStart, glintSweep, false, highlight)
            }

            label.textSize = dp(if (name.length > 6) 6 else 8).toFloat()
            label.color = 0xFFCBD3E6.toInt()
            canvas.drawText(name, cx, cy - dp(3).toFloat(), label)

            percent.textSize = dp(14).toFloat()
            percent.color = Color.WHITE
            canvas.drawText(percentage, cx, cy + dp(15).toFloat(), percent)

            detail.textSize = dp(8).toFloat()
            canvas.drawText(value, cx, cy + radius + dp(11).toFloat(), detail)
        }

        private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    }

    private var lastForegroundEventAt = 0L
    private var lastForegroundSource = "not-queried"
    private var lastForegroundQueryError: String? = null

    // Keep diagnostics in Logcat only; temporary Downloads file generation has
    // been removed now that the overlay failure has been isolated.
    private fun diagnostic(priority: Int, message: String) {
        android.util.Log.println(priority, "OeaGameBoost", message)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var overlay: View? = null
    private var panelView: View? = null
    private var wakeOverlay: View? = null
    private var lastWakeTapAt: Long = 0L
    private var activeGame: String? = null
    private var foregroundActivityClass: String? = null
    // UsageEvents reports lifecycle transitions, not continuous foreground state.
    // Keep the last resumed package so a long-running game does not look like
    // "no foreground app" merely because its last resume was over 30 seconds ago.
    private var lastKnownForegroundPackage: String? = null
    private var nonGameForegroundSamples = 0
    private var lastNonGamePackage: String? = null
    private var diagnosticHeartbeatTicks = 0L
    private var lastMonitorErrorSignature: String? = null
    private var lastLoggedForegroundPackage: String? = null
    private var lastForegroundDecisionLogAt = 0L
    private var lastLoggedHandleVisibility: Int? = null
    private val tick = object : Runnable {
        override fun run() {
            diagnosticHeartbeatTicks++
            if (diagnosticHeartbeatTicks % 10L == 0L) {
                val root = overlay
                val handle = ((root as? android.widget.LinearLayout)?.getChildAt(0) as? android.view.ViewGroup)
                diagnostic(android.util.Log.INFO, "heartbeat tick=$diagnosticHeartbeatTicks enabled=${OeaGameBoostStore.enabled(this@OeaGameBoostService)} usageAccess=${isUsageAccessGranted()} overlayExists=${root != null} overlayAttached=${root?.parent != null} overlayVisibility=${root?.visibility} handleVisibility=${handle?.visibility} handleAlpha=${handle?.alpha} handleAttached=${handle?.parent != null} panelExists=${panelView != null} panelAttached=${panelView?.parent != null} panelVisibility=${panelView?.visibility} wakeOverlayAttached=${wakeOverlay?.parent != null} overlayPermission=${Settings.canDrawOverlays(this@OeaGameBoostService)} handlePreferenceVisible=${OeaGameBoostStore.prefs(this@OeaGameBoostService).getBoolean("ram_handle_visible", true)} activeGame=$activeGame lastForeground=$lastKnownForegroundPackage")
            }
            if (!OeaGameBoostStore.enabled(this@OeaGameBoostService)) {
                diagnostic(android.util.Log.WARN, "monitor stopping because enabled=false activeGame=$activeGame overlayAttached=${overlay?.parent != null}")
                stopSelf()
                return
            }

            // Usage access can temporarily report unavailable after Settings or
            // permission-controller transitions. Do not destroy a live game
            // overlay merely because one permission check failed.
            if (!isUsageAccessGranted()) {
                nonGameForegroundSamples = 0
                lastNonGamePackage = null
                activeGame?.let { ensureOverlayForActiveGame() }
                handler.postDelayed(this, 1500L)
                return
            }

            try {
                runCatching { OeaGameBoostStore.syncDetectedGames(this@OeaGameBoostService) }
                val game = foregroundPackage()
                if (game != lastLoggedForegroundPackage) {
                    diagnostic(android.util.Log.INFO, "foreground package changed from=$lastLoggedForegroundPackage to=$game activeGame=$activeGame samples=$nonGameForegroundSamples source=$lastForegroundSource eventAt=$lastForegroundEventAt activity=$foregroundActivityClass queryError=$lastForegroundQueryError")
                    lastLoggedForegroundPackage = game
                }
                if (activeGame != null && game != activeGame &&
                    System.currentTimeMillis() - lastForegroundDecisionLogAt >= 5000L) {
                    lastForegroundDecisionLogAt = System.currentTimeMillis()
                    diagnostic(android.util.Log.WARN, "foreground decision candidate activeGame=$activeGame candidate=$game source=$lastForegroundSource eventAt=$lastForegroundEventAt activity=$foregroundActivityClass transient=${game?.let(::isTransientForegroundPackage)} samples=$nonGameForegroundSamples")
                }
                val captureActive = OeaGameBoostStore.prefs(this@OeaGameBoostService)
                    .getBoolean("capture_active", false)

                // Once a game session is active, its package remains authoritative
                // for that session. Do not re-run automatic category detection
                // against the same package every poll: OEM/app metadata can make
                // isGame() fluctuate and falsely trigger deactivate(), which
                // removes the floating handle when the panel is dismissed.
                val isActiveSessionForeground = game != null && game == activeGame
                if (isActiveSessionForeground ||
                    (game != null && OeaGameBoostStore.isGame(this@OeaGameBoostService, game))) {
                    nonGameForegroundSamples = 0
                    lastNonGamePackage = null
                    if (activeGame != game) {
                        restoreDnd()
                        setKeepScreenOn(false)
                        activeGame = game
                        activate(game)
                        panelView?.let(::closePanel)
                    } else {
                        applyGameDndPreference()
                        ensureOverlayForActiveGame()
                    }
                    updateOverlay()
                } else if (activeGame != null) {
                    // Keep the overlay briefly during genuine Android transition
                    // surfaces, but do not treat the Home launcher as a transient:
                    // returning Home means the selected game/app has been left.
                    if (captureActive) {
                        applyGameDndPreference()
                        nonGameForegroundSamples = 0
                        lastNonGamePackage = null
                        ensureOverlayForActiveGame()
                        updateOverlay()
                    } else if (game == null || isTransientForegroundPackage(game)) {
                        applyGameDndPreference()
                        // Missing UsageStats data and temporary system/OEA UI are
                        // not proof that the user left the game. Keep the session
                        // and its independent handle until Android reports a real,
                        // non-transient foreground app (Home or another app).
                        nonGameForegroundSamples = 0
                        lastNonGamePackage = null
                        ensureOverlayForActiveGame()
                        updateOverlay()
                    } else {
                        if (lastNonGamePackage == game) {
                            nonGameForegroundSamples++
                        } else {
                            lastNonGamePackage = game
                            nonGameForegroundSamples = 1
                        }
                        // A confirmed different foreground app ends the session
                        // quickly. Closing the panel never extends game eligibility.
                        // End promptly after two consecutive confirmed samples outside
                        // the selected game; the old five-sample delay left the handle
                        // visible for several seconds after returning Home.
                        if (nonGameForegroundSamples < 2) {
                            ensureOverlayForActiveGame()
                            updateOverlay()
                        } else {
                            val endingGame = activeGame
                            diagnostic(android.util.Log.WARN, "foreground-monitor ending session=$endingGame foreground=$game samples=$nonGameForegroundSamples source=$lastForegroundSource eventAt=$lastForegroundEventAt activity=$foregroundActivityClass")
                            activeGame = null
                            nonGameForegroundSamples = 0
                            lastNonGamePackage = null
                            endingGame?.let(::deactivate)
                        }
                    }
                }
            } catch (error: Throwable) {
                // Record errors that were previously swallowed; avoid repeating the same
                // exception to the Downloads file every second.
                val signature = "${error.javaClass.name}:${error.message}"
                if (signature != lastMonitorErrorSignature) {
                    lastMonitorErrorSignature = signature
                    diagnostic(android.util.Log.ERROR, "foreground-monitor exception=$signature stack=${android.util.Log.getStackTraceString(error).take(1800)}")
                }
                activeGame?.let {
                    runCatching { ensureOverlayForActiveGame() }.onFailure { recoveryError ->
                        diagnostic(android.util.Log.ERROR, "overlay recovery exception=${recoveryError.javaClass.name}:${recoveryError.message}")
                    }
                    runCatching { updateOverlay() }.onFailure { updateError ->
                        diagnostic(android.util.Log.ERROR, "updateOverlay exception=${updateError.javaClass.name}:${updateError.message}")
                    }
                }
            } finally {
                if (OeaGameBoostStore.enabled(this@OeaGameBoostService)) {
                    handler.postDelayed(this, 1000L)
                }
            }
        }
    }
    override fun onCreate() {
        super.onCreate()
        diagnostic(android.util.Log.WARN, "service onCreate sdk=${Build.VERSION.SDK_INT} process=${android.os.Process.myPid()} overlayPermission=${Settings.canDrawOverlays(this)} usageAccess=${isUsageAccessGranted()} enabled=${OeaGameBoostStore.enabled(this)}")
        createChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentTitle("OEA RAM").setContentText("Game session active").setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        else startForeground(NOTIFICATION_ID, notification)
        handler.post(tick)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        diagnostic(android.util.Log.INFO, "service onStartCommand action=${intent?.action} flags=$flags startId=$startId activeGame=$activeGame overlayAttached=${overlay?.parent != null}")
        if (intent?.action == ACTION_REFRESH) {
            // Settings edits should refresh the existing session, not tear down
            // the WindowManager handle by stopping and recreating this service.
            activeGame?.let {
                ensureOverlayForActiveGame()
                updateOverlay()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        diagnostic(android.util.Log.WARN, "service onDestroy activeGame=$activeGame overlayAttached=${overlay?.parent != null} panelExists=${panelView != null}")
        handler.removeCallbacksAndMessages(null)
        activeGame?.let(::deactivate)
        removeWakeOverlay()
        removeOverlay()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    private fun activate(packageName: String) {
        diagnostic(android.util.Log.INFO, "activate requested game=$packageName overlayPermission=${Settings.canDrawOverlays(this)} handlePreferenceVisible=${OeaGameBoostStore.prefs(this).getBoolean("ram_handle_visible", true)} existingOverlayAttached=${overlay?.parent != null}")
        applyGameDndPreference()
        if (Settings.canDrawOverlays(this)) {
            // The RAM control is a persistent part of the in-game overlay.
            // A previous game/session may have removed the WindowManager view
            // while leaving a stale reference behind. Rebuild that detached
            // overlay instead of treating the stale reference as valid.
            val currentOverlay = overlay
            if (currentOverlay != null && currentOverlay.parent == null) {
                overlay = null
            }
            showOverlay(packageName)
            (overlay as? android.widget.LinearLayout)?.getChildAt(0)?.let { handle ->
                val visible = OeaGameBoostStore.prefs(this).getBoolean("ram_handle_visible", true)
                handle.visibility = if (visible) View.VISIBLE else View.GONE
                if (visible) removeWakeOverlay() else ensureWakeOverlay()
            }
            if (OeaGameBoostStore.prefs(this).getBoolean("boost", true)) setKeepScreenOn(true)
        } else {
            diagnostic(android.util.Log.ERROR, "activate cannot create overlay: overlay permission denied game=$packageName")
        }
    }
    private fun deactivate(@Suppress("UNUSED_PARAMETER") packageName: String) {
        diagnostic(android.util.Log.WARN, "deactivate called for=$packageName activeGame=$activeGame overlayAttached=${overlay?.parent != null} panelExists=${panelView != null}")
        restoreDnd()
        setKeepScreenOn(false)
        removeWakeOverlay()
        removeOverlay()
    }

    private fun isTransientForegroundPackage(packageName: String): Boolean {
        if (packageName == this.packageName) return true
        // Android can surface these short-lived UI packages while the game
        // remains underneath (permission sheets, recents, keyboards and launchers).
        // They are treated as transition evidence, never as proof that a game ended.
        return packageName in setOf(
            "com.android.systemui",
            "com.android.settings",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.google.android.inputmethod.latin",
            "com.google.android.apps.inputmethod",
            "com.android.inputmethod.latin",
            // Do not include Home launcher packages here. Their resumed
            // activity is positive evidence that the selected app was left.
        )
    }

    /**
     * Recover the floating control if Android detached its window or a
     * transient WindowManager failure left a stale View reference behind.
     * This repairs the existing handle; it does not add another container.
     */
    private fun ensureOverlayForActiveGame() {
        val game = activeGame ?: return
        if (!Settings.canDrawOverlays(this)) return
        val current = overlay
        val root = current as? android.widget.LinearLayout
        val handle = root?.getChildAt(0) as? android.widget.FrameLayout
        val handleLabel = handle?.getChildAt(0) as? TextView
        // An attached but damaged root is not a healthy overlay. Checking only
        // View.parent can leave Game Boost believing its button still exists.
        val structureHealthy = root != null && handle != null && handleLabel != null
        if (current != null && current.parent != null && structureHealthy) return

        diagnostic(android.util.Log.WARN, "overlay recovery required game=$game rootExists=${current != null} rootAttached=${current?.parent != null} rootVisibility=${current?.visibility} handleVisibility=${handle?.visibility} handleAlpha=${handle?.alpha} structureHealthy=$structureHealthy panelExists=${panelView != null} panelAttached=${panelView?.parent != null} panelVisibility=${panelView?.visibility} overlayPermission=${Settings.canDrawOverlays(this)}")
        if (current != null) {
            panelView?.let { panel ->
                panel.animate().cancel()
                detachPanelWindow(panel)
            }
            runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(current) }
                .onFailure { error -> diagnostic(android.util.Log.ERROR, "overlay recovery removeView failed=${error.javaClass.name}:${error.message}") }
            overlay = null
            panelView = null
        }
        showOverlay(game)
    }
    private fun showOverlay(packageName: String) {
        val existing = overlay
        if (existing != null) {
            // Only reuse the overlay while it is actually attached to the
            // WindowManager. A stale View reference must never suppress
            // recreation of the OEA RAM button.
            if (existing.parent != null) return
            overlay = null
        }
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                diagnostic(android.util.Log.INFO, "overlay root attached game=$packageName visibility=${view.visibility} isShown=${view.isShown}")
            }
            override fun onViewDetachedFromWindow(view: View) {
                diagnostic(android.util.Log.ERROR, "overlay root DETACHED game=$packageName activeGame=$activeGame visibility=${view.visibility} isShown=${view.isShown} handleVisibility=${((view as? android.widget.LinearLayout)?.getChildAt(0)?.visibility)}")
            }
        })
        val panel = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xF51A263A.toInt(), 0xF21A1F2B.toInt(), 0xF5101725.toInt())
            ).apply {
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), 0x9955BFFF.toInt())
            }
            elevation = dp(12).toFloat()
            visibility = View.GONE
        }
        val title = TextView(this).apply {
            setTextColor(0xFFF1F8FF.toInt()); textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            text = "◈  OEA GAME BOOST"
            setShadowLayer(dp(5).toFloat(), 0f, 0f, 0x6657C8FF)
        }
        val gameName = TextView(this).apply {
            setTextColor(0xFF78D9FF.toInt()); textSize = 10f
            text = packageName.substringAfterLast('.')
            setPadding(0, dp(2), 0, dp(5))
        }
        val metrics = MemoryMetricsView(this).apply {
            setPadding(dp(4), 0, dp(4), 0)
            contentDescription = "Live RAM and virtual RAM circular gauges"
        }
        val device = TextView(this).apply {
            setTextColor(0xFFD7E8F8.toInt()); textSize = 10f
            text = "Battery • reading…"
            setPadding(dp(8), dp(5), dp(8), dp(5))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0x332D8EC9)
                cornerRadius = dp(9).toFloat()
                setStroke(dp(1), 0x3349BFFF)
            }
        }
        val controls = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            alpha = 0.98f
        }
        val rowOne = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
        val rowTwo = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
        val dnd = chip("◉  DND")
        val boost = chip("⚡  BOOST")
        val screenshot = chip("▣  SHOT")
        val record = chip("●  RECORD")
        val cleanup = chip("↻  CLEAN RAM")
        rowOne.addView(dnd, chipParams())
        rowOne.addView(boost, chipParams())
        rowOne.addView(screenshot, chipParams())
        val wifi = chip("⌁  WI-FI")
        rowOne.addView(wifi, chipParams())
        rowTwo.addView(record, chipParams())
        rowTwo.addView(cleanup, chipParams())
        controls.addView(rowOne)
        controls.addView(rowTwo, android.widget.LinearLayout.LayoutParams(-1, dp(36)).apply { topMargin = dp(4) })
        dnd.setOnClickListener { toggleDnd(dnd) }
        screenshot.setOnClickListener { launchCapture(OeaGameCaptureActivity.MODE_SCREENSHOT) }
        record.setOnClickListener { toggleRecording(record) }
        wifi.setOnClickListener { openWifiPanel() }
        cleanup.setOnClickListener { cleanBackgroundMemory(cleanup) }
        boost.setOnClickListener {
            val next = !OeaGameBoostStore.prefs(this).getBoolean("boost", true)
            OeaGameBoostStore.prefs(this).edit().putBoolean("boost", next).apply()
            setKeepScreenOn(next)
            boost.animate().scaleX(0.94f).scaleY(0.94f).setDuration(70L).withEndAction {
                boost.animate().scaleX(1f).scaleY(1f).setDuration(120L).start()
            }.start()
            updateBoostButton(boost)
        }
        panel.addView(title); panel.addView(gameName); panel.addView(metrics); panel.addView(device); panel.addView(controls)

        val handleSize = handleSizePx()
        val handle = android.widget.FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF0A1020.toInt(), 0xFF2357FF.toInt(), 0xFF0B1735.toInt())
            ).apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setStroke(dp(1), 0xAA4D74FF.toInt())
            }
            elevation = dp(7).toFloat()
            contentDescription = "Open OEA RAM controls"
            isClickable = true
            isFocusable = true
        }
        val handleText = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 9f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            text = "OEA\nRAM"
            includeFontPadding = false
            setShadowLayer(dp(4).toFloat(), 0f, 0f, 0xCC6FB8FF.toInt())
        }
        handle.addView(handleText, android.widget.FrameLayout.LayoutParams(handleSize, handleSize).apply {
            gravity = Gravity.CENTER
        })
        panelView = panel
        root.addView(handle, android.widget.LinearLayout.LayoutParams(handleSize, handleSize).apply {
            gravity = Gravity.END
            topMargin = dp(6)
        })
        // Keep the same floating handle above the panel if their bounds overlap.
        handle.bringToFront()
        // A single click on the floating handle toggles the same panel instance.
        // Closing always funnels through closePanel(), shared with CLOSE PANEL.
        handle.setOnClickListener { togglePanel(panel) }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = if (OeaGameBoostStore.prefs(this@OeaGameBoostService).getBoolean("ram_handle_dragged", false)) {
                Gravity.TOP or Gravity.START
            } else {
                overlayGravity()
            }
            if (gravity == (Gravity.TOP or Gravity.START)) {
                x = OeaGameBoostStore.prefs(this@OeaGameBoostService).getInt("ram_handle_x", dp(8))
                y = OeaGameBoostStore.prefs(this@OeaGameBoostService).getInt("ram_handle_y", dp(8))
            } else {
                x = dp(2)
                y = 0
            }
        }
        runCatching {
            wm.addView(root, params)
            overlay = root
            diagnostic(android.util.Log.INFO, "overlay added game=$packageName rootAttached=${root.parent != null} handleVisibility=${handle.visibility} handleAlpha=${handle.alpha} paramsType=${params.type} flags=${params.flags}")

                handle.setOnTouchListener(object : View.OnTouchListener {
                private var downRawX = 0f
                private var downRawY = 0f
                private var startX = 0
                private var startY = 0
                private var downTime = 0L
                private var dragging = false

                override fun onTouch(v: View, event: android.view.MotionEvent): Boolean {
                    when (event.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            downTime = event.eventTime
                            downRawX = event.rawX
                            downRawY = event.rawY
                            val current = root.layoutParams as? WindowManager.LayoutParams ?: return false
                            startX = current.x
                            startY = current.y
                            dragging = false
                            return true
                        }
                        android.view.MotionEvent.ACTION_MOVE -> {
                            val dx = event.rawX - downRawX
                            val dy = event.rawY - downRawY
                            if (!dragging && (kotlin.math.abs(dx) > dp(6) || kotlin.math.abs(dy) > dp(6))) {
                                dragging = true
                                // Once movement crosses the drag threshold, ACTION_UP must not click.
                                handle.animate().cancel()
                                handle.animate().scaleX(0.92f).scaleY(0.92f).setDuration(90L).start()
                            }
                            if (dragging) {
                                val next = root.layoutParams as? WindowManager.LayoutParams ?: return true
                                val bounds = screenBounds(root)
                                next.gravity = Gravity.TOP or Gravity.START
                                next.x = (startX + dx.toInt()).coerceIn(bounds[0], bounds[2])
                                next.y = (startY + dy.toInt()).coerceIn(bounds[1], bounds[3])
                                runCatching { wm.updateViewLayout(root, next) }.onFailure { error -> diagnostic(android.util.Log.ERROR, "handle drag updateViewLayout failed=${error.javaClass.name}:${error.message} attached=${root.parent != null}") }
                                syncPanelToHandle(root)
                            }
                            return true
                        }
                        android.view.MotionEvent.ACTION_UP -> {
                            if (dragging) {
                                snapHandleToEdge(root, handle, wm)
                            } else {
                                // The touch listener owns this gesture, so dispatch exactly one click.
                                handle.performClick()
                            }
                            dragging = false
                            return true
                        }
                        android.view.MotionEvent.ACTION_CANCEL -> {
                            // A cancelled gesture is not a tap. Never synthesize
                            // clicks here: that could count a drag/window transition
                            // as the second tap needed to close the panel.
                            if (dragging) {
                                handle.animate().cancel()
                                handle.animate().scaleX(1f).scaleY(1f).setDuration(120L).start()
                            }
                            dragging = false
                            return true
                        }
                    }
                    return false
                }
            })
            handle.alpha = 0f
            handle.translationX = dp(10).toFloat()
            handle.animate().alpha(1f).translationX(0f).setDuration(260L)
                .setInterpolator(android.view.animation.PathInterpolator(0.18f, 0.9f, 0.2f, 1f)).start()
            applyHandlePalette(handle)
            applyPanelPalette(panel)
            updateOverlay()
        }.onFailure { error -> diagnostic(android.util.Log.ERROR, "overlay creation failed game=$packageName error=${error.javaClass.name}:${error.message} stack=${android.util.Log.getStackTraceString(error).take(1800)}") }
    }

    private fun ensureWakeOverlay() {
        if (wakeOverlay != null || OeaGameBoostStore.prefs(this).getBoolean("ram_handle_visible", true)) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val wake = android.view.View(this).apply {
            alpha = 0.01f
            setOnTouchListener { _, event ->
                if (event.actionMasked == android.view.MotionEvent.ACTION_OUTSIDE) {
                    val now = System.currentTimeMillis()
                    if (now - lastWakeTapAt in 1..380L) {
                        lastWakeTapAt = 0L
                        OeaGameBoostStore.prefs(this@OeaGameBoostService).edit().putBoolean("ram_handle_visible", true).apply()
                        removeWakeOverlay()
                        updateOverlay()
                        val root = overlay
                        val handle = (root as? android.widget.LinearLayout)?.getChildAt(0) as? android.widget.FrameLayout
                        handle?.alpha = 0f
                        handle?.animate()?.alpha(1f)?.scaleX(1f)?.scaleY(1f)?.setDuration(220L)?.start()
                    } else {
                        lastWakeTapAt = now
                    }
                }
                false
            }
        }
        val params = WindowManager.LayoutParams(
            1, 1,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = overlayGravity()
            x = if (gravity and Gravity.RIGHT == Gravity.RIGHT) dp(2) else 0
            y = if (gravity and Gravity.BOTTOM == Gravity.BOTTOM) dp(2) else 0
        }
        runCatching {
            wm.addView(wake, params)
            wakeOverlay = wake
            diagnostic(android.util.Log.INFO, "wake overlay attached visiblePreference=false activeGame=$activeGame")
        }.onFailure { error ->
            diagnostic(android.util.Log.ERROR, "wake overlay attach failed=${error.javaClass.name}:${error.message} overlayAttached=${overlay?.parent != null}")
        }
    }

    private fun removeWakeOverlay() {
        val wake = wakeOverlay ?: return
        runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(wake) }
            .onFailure { error -> diagnostic(android.util.Log.ERROR, "wake overlay removeView failed=${error.javaClass.name}:${error.message} attached=${wake.parent != null}") }
        wakeOverlay = null
        diagnostic(android.util.Log.INFO, "wake overlay removal requested activeGame=$activeGame")
        lastWakeTapAt = 0L
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** Returns left, top, maxLeft and maxTop bounds for the overlay window. */
    private fun screenBounds(root: View): IntArray {
        val metrics = resources.displayMetrics
        val width = root.width.takeIf { it > 0 } ?: handleSizePx()
        val height = root.height.takeIf { it > 0 } ?: handleSizePx()
        return intArrayOf(
            0,
            0,
            (metrics.widthPixels - width).coerceAtLeast(0),
            (metrics.heightPixels - height).coerceAtLeast(0)
        )
    }

    private fun snapHandleToEdge(root: View, handle: View, wm: WindowManager) {
        val params = root.layoutParams as? WindowManager.LayoutParams ?: return
        val bounds = screenBounds(root)
        val currentX = params.x.coerceIn(bounds[0], bounds[2])
        val currentY = params.y.coerceIn(bounds[1], bounds[3])
        val distances = intArrayOf(
            currentX,
            bounds[2] - currentX,
            currentY,
            bounds[3] - currentY
        )
        val nearest = distances.indices.minByOrNull { distances[it] } ?: 3
        when (nearest) {
            0 -> params.x = 0
            1 -> params.x = bounds[2]
            2 -> params.y = 0
            else -> params.y = bounds[3]
        }
        params.x = params.x.coerceIn(bounds[0], bounds[2])
        params.y = params.y.coerceIn(bounds[1], bounds[3])
        params.gravity = Gravity.TOP or Gravity.START
        runCatching { wm.updateViewLayout(root, params) }.onFailure { error -> diagnostic(android.util.Log.ERROR, "handle snap updateViewLayout failed=${error.javaClass.name}:${error.message} attached=${root.parent != null}") }
        syncPanelToHandle(root)
        OeaGameBoostStore.prefs(this).edit()
            .putBoolean("ram_handle_dragged", true)
            .putInt("ram_handle_x", params.x)
            .putInt("ram_handle_y", params.y)
            .apply()
        handle.animate().cancel()
        handle.animate().scaleX(1f).scaleY(1f).setDuration(120L).start()
    }

    private fun handleSizePx(): Int = when (OeaGameBoostStore.prefs(this).getString("ram_handle_size", "medium")) {
        "small" -> dp(40)
        "large" -> dp(58)
        else -> dp(48)
    }

    private fun overlayGravity(): Int = when (OeaGameBoostStore.prefs(this).getString("ram_handle_corner", "bottom_right")) {
        "top_left" -> Gravity.TOP or Gravity.START
        "top_right" -> Gravity.TOP or Gravity.END
        "bottom_left" -> Gravity.BOTTOM or Gravity.START
        else -> Gravity.BOTTOM or Gravity.END
    }

    private fun chipParams(): android.widget.LinearLayout.LayoutParams =
        android.widget.LinearLayout.LayoutParams(0, dp(36), 1f).apply { rightMargin = dp(4) }

    private fun launchCapture(mode: String) {
        OeaGameBoostStore.prefs(this).edit()
            .putBoolean("capture_active", true)
            .putString("capture_mode", mode)
            .apply()
        // Keep the in-game OEA control alive. Recording must remain stoppable
        // from the same floating control after the Android consent sheet closes.
        overlay?.alpha = 1f
        val intent = Intent(this, OeaGameCaptureActivity::class.java).apply {
            putExtra(OeaGameCaptureActivity.EXTRA_MODE, mode)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        runCatching { startActivity(intent) }.onFailure {
            OeaGameBoostStore.prefs(this).edit()
                .putBoolean("capture_active", false)
                .putBoolean("recording", false)
                .apply()
            updateOverlay()
        }
    }

    private fun openWifiPanel() {
        val intent = if (Build.VERSION.SDK_INT >= 29) {
            Intent(Settings.Panel.ACTION_WIFI)
        } else {
            Intent(Settings.ACTION_WIFI_SETTINGS)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
    }

    private fun toggleRecording(button: TextView) {
        val prefs = OeaGameBoostStore.prefs(this)
        if (prefs.getBoolean("recording", false)) {
            startService(Intent(this, OeaGameCaptureService::class.java).setAction(OeaGameCaptureService.ACTION_STOP))
            prefs.edit().putBoolean("recording", false).apply()
            button.text = "RECORD"
        } else if (
            prefs.getBoolean("capture_active", false) &&
            prefs.getString("capture_mode", "") == OeaGameCaptureActivity.MODE_RECORD
        ) {
            // Consent/startup is already in progress; don't launch a second capture session.
            button.text = "STARTING…"
        } else {
            // Do not claim recording has started before Android consent and MediaRecorder.start succeed.
            launchCapture(OeaGameCaptureActivity.MODE_RECORD)
            button.text = "STARTING…"
        }
    }

    private fun cleanBackgroundMemory(button: TextView) {
        val am = getSystemService(ActivityManager::class.java)
        val active = activeGame
        // Android may expose only a subset of other apps' processes, and a
        // successful API call does not prove that RAM was freed. Report requests,
        // not a false "cleaned" success.
        val targets = runCatching {
            am.runningAppProcesses.orEmpty()
                .filter { it.importance >= ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND }
                .flatMap { it.pkgList.orEmpty().asList() }
                .filter { it != packageName && it != active }
                .distinct()
                .take(24)
        }.getOrDefault(emptyList())
        var requested = 0
        targets.forEach { pkg ->
            if (runCatching { am.killBackgroundProcesses(pkg) }.isSuccess) requested++
        }
        button.text = if (requested > 0) "REQUESTED $requested" else "NO TARGETS"
        handler.postDelayed({ button.text = "↻  CLEAN RAM" }, 1600L)
        updateOverlay()
    }

    private fun chip(label: String): TextView = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(0xFFF2F8FF.toInt())
        textSize = 9.5f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFF1B2B40.toInt(), 0xFF172234.toInt())
        ).apply {
            cornerRadius = dp(12).toFloat()
            setStroke(dp(1), 0x7750BFFF)
        }
        elevation = dp(2).toFloat()
        isClickable = true
        isFocusable = true
        setPadding(dp(3), 0, dp(3), 0)
        // Responsive press feedback; returning false preserves the normal click action.
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    view.animate().cancel()
                    view.animate().scaleX(0.96f).scaleY(0.96f).alpha(0.88f)
                        .setDuration(80L).start()
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    view.animate().cancel()
                    view.animate().scaleX(1f).scaleY(1f).alpha(1f)
                        .setDuration(150L)
                        .setInterpolator(android.view.animation.DecelerateInterpolator())
                        .start()
                }
            }
            false
        }
    }

    private fun actionAccent(label: String): Int = when {
        label.contains("DND", ignoreCase = true) -> 0xFF39D8FF.toInt()
        label.contains("BOOST", ignoreCase = true) -> 0xFF568BFF.toInt()
        label.contains("SHOT", ignoreCase = true) -> 0xFF66BFFF.toInt()
        label.contains("WI-FI", ignoreCase = true) -> 0xFF35D9C2.toInt()
        label.contains("RECORD", ignoreCase = true) -> 0xFFFFC15A.toInt()
        label.contains("CLEAN RAM", ignoreCase = true) -> 0xFF6BE6A5.toInt()
        else -> 0xFF62C8FF.toInt()
    }

    private fun applyActionChipPalette(chip: TextView, panelBase: Int, panelAccent: Int) {
        val background = chip.background as? android.graphics.drawable.GradientDrawable ?: return
        val accent = actionAccent(chip.text.toString())
        val base = android.animation.ArgbEvaluator().evaluate(0.20f, panelBase, accent) as Int
        val edge = android.animation.ArgbEvaluator().evaluate(0.18f, accent, panelAccent) as Int
        val surface = android.animation.ArgbEvaluator().evaluate(0.12f, base, edge) as Int
        background.setColors(intArrayOf(base, surface))
        background.setStroke(dp(1), edge.withAlpha(190))
    }

    private fun toggleDnd(button: TextView) {
        val nm = getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) {
            button.text = "DND ACCESS"
            val detail = if (Build.VERSION.SDK_INT >= 30) Intent("android.settings.NOTIFICATION_POLICY_ACCESS_DETAIL_SETTINGS").apply {
                data = android.net.Uri.parse("package:$packageName")
            } else null
            runCatching {
                startActivity((detail ?: Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure {
                runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            return
        }
        val next = !OeaGameBoostStore.prefs(this).getBoolean("dnd", true)
        OeaGameBoostStore.prefs(this).edit().putBoolean("dnd", next).apply()
        if (activeGame != null) {
            applyGameDndPreference()
        } else {
            // The setting is a per-game policy, not a request to leave system
            // DND enabled while the user is outside a selected game.
            runCatching { nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL) }
        }
        updateDndButton(button)
    }

    private fun updateDndButton(button: TextView) {
        val nm = getSystemService(NotificationManager::class.java)
        button.text = when {
            !nm.isNotificationPolicyAccessGranted -> "DND ACCESS"
            OeaGameBoostStore.prefs(this).getBoolean("dnd", true) -> "DND ON"
            else -> "DND OFF"
        }
    }

    private fun updateOverlay() {
        (overlay as? android.widget.LinearLayout)?.getChildAt(0)?.let { applyHandlePalette(it) }
        val root = overlay as? android.widget.LinearLayout
        if (root == null || root.parent == null) {
            diagnostic(android.util.Log.WARN, "updateOverlay found missing/detached root rootExists=${root != null} rootAttached=${root?.parent != null} activeGame=$activeGame")
            ensureOverlayForActiveGame()
            return
        }
        val panel = panelView as? android.widget.LinearLayout ?: run {
            diagnostic(android.util.Log.ERROR, "updateOverlay missing panel view activeGame=$activeGame rootAttached=${root.parent != null}")
            return
        }
        val gameName = panel.getChildAt(1) as? TextView ?: return
        val metrics = panel.getChildAt(2) as? TextView ?: return
        val device = panel.getChildAt(3) as? TextView ?: return
        val panelLayout = panel as? android.widget.LinearLayout
                val controls = panelLayout?.getChildAt(4) as? android.widget.LinearLayout ?: return
        val rowOne = controls.getChildAt(0) as? android.widget.LinearLayout ?: return
        val rowTwo = controls.getChildAt(1) as? android.widget.LinearLayout ?: return
        val dnd = rowOne.getChildAt(0) as? TextView ?: return
        val boost = rowOne.getChildAt(1) as? TextView ?: return

        val info = ActivityManager.MemoryInfo()
        getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        val totalMb = info.totalMem / (1024.0 * 1024.0)
        val availableMb = info.availMem / (1024.0 * 1024.0)
        val usedMb = (totalMb - availableMb).coerceAtLeast(0.0)
        val usedPct = if (totalMb > 0) usedMb / totalMb * 100.0 else 0.0
        val state = if (info.lowMemory) " • LOW MEMORY" else ""
        val virtual = virtualRamMb()
        val virtualUsed = virtual.first
        val virtualTotal = virtual.second
        (metrics as? MemoryMetricsView)?.apply {
            ramUsedMb = usedMb
            ramTotalMb = totalMb
            virtualUsedMb = virtualUsed
            virtualTotalMb = virtualTotal
            lowMemory = info.lowMemory
            ramFraction = (if (totalMb > 0) usedMb / totalMb else 0.0).toFloat()
            virtualFraction = (if (virtualTotal > 0) virtualUsed / virtualTotal else 0.0).toFloat()
        }

        val battery = getSystemService(BATTERY_SERVICE) as BatteryManager
        val level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        device.text = if (level in 0..100) "Battery $level% • Game focus active" else "Game focus active"
        gameName.text = activeGame?.substringAfterLast('.') ?: "Game"
        updateDndButton(dnd)
        updateBoostButton(boost)
        // Capture consent/recording must never remove the in-game OEA controls.
        root.alpha = 1f
        val captureActive = OeaGameBoostStore.prefs(this).getBoolean("capture_active", false)
        val captureMode = OeaGameBoostStore.prefs(this).getString("capture_mode", "")
        val recording = OeaGameBoostStore.prefs(this).getBoolean("recording", false)
        (controls.getChildAt(0) as? android.widget.LinearLayout)?.getChildAt(2)?.let { (it as? TextView)?.text = if (captureActive && captureMode == OeaGameCaptureActivity.MODE_SCREENSHOT) "▣  SAVING…" else "▣  SHOT" }
        (controls.getChildAt(1) as? android.widget.LinearLayout)?.getChildAt(0)?.let { (it as? TextView)?.text =
            when {
                recording -> "■  STOP REC"
                captureActive && captureMode == OeaGameCaptureActivity.MODE_RECORD -> "●  STARTING…"
                else -> "●  RECORD"
            }
        }
        setKeepScreenOn(OeaGameBoostStore.prefs(this).getBoolean("boost", true))
        val handle = root.getChildAt(0) as? android.widget.FrameLayout
        val handleVisible = OeaGameBoostStore.prefs(this).getBoolean("ram_handle_visible", true)
        handle?.visibility = if (handleVisible) View.VISIBLE else View.GONE
        val handleVisibilityNow = handle?.visibility
        if (handleVisibilityNow != lastLoggedHandleVisibility) {
            diagnostic(android.util.Log.INFO, "handle visibility changed old=$lastLoggedHandleVisibility new=$handleVisibilityNow preferenceVisible=$handleVisible activeGame=$activeGame overlayAttached=${root.parent != null} overlayIsShown=${root.isShown} handleIsShown=${handle?.isShown}")
            lastLoggedHandleVisibility = handleVisibilityNow
        }
        if (handleVisible) removeWakeOverlay() else ensureWakeOverlay()
        handle?.let { h ->
            val size = handleSizePx()
            val lp = h.layoutParams as? android.widget.LinearLayout.LayoutParams
            if (lp != null && (lp.width != size || lp.height != size)) {
                lp.width = size
                lp.height = size
                h.layoutParams = lp
                h.requestLayout()
            }
            val t = h.getChildAt(0) as? TextView
            if (t != null) {
                t.textSize = if (size <= dp(44)) 8f else 9f
                t.text = String.format(Locale.US, "OEA\nRAM\n%.0f%%", usedPct)
                val childLp = t.layoutParams as? android.widget.FrameLayout.LayoutParams
                if (childLp != null && (childLp.width != size || childLp.height != size)) {
                    childLp.width = size
                    childLp.height = size
                    t.layoutParams = childLp
                }
                if (handleVisible && t.visibility == View.VISIBLE) applyHandlePalette(h)
            }
        }
    }

    private fun updateBoostButton(button: TextView) {
        button.text = if (OeaGameBoostStore.prefs(this).getBoolean("boost", true)) "BOOST ON" else "BOOST OFF"
    }

    private fun applyHandlePalette(handle: View) {
        // The floating handle stays calm/readable. Palette animation belongs
        // to the opened Game Boost panel, not to this button.
        val palette = handlePalette()
        val bg = handle.background as? android.graphics.drawable.GradientDrawable ?: return
        bg.setColors(intArrayOf(palette[0], palette[1], palette[0]))
        bg.setStroke(dp(1), palette[1].withAlpha(170))
        val text = (handle as? android.view.ViewGroup)?.getChildAt(0) as? TextView
        text?.setShadowLayer(dp(3).toFloat(), 0f, 0f, palette[1])
        text?.paint?.shader = null
        text?.alpha = 1f
        text?.scaleX = 1f
        text?.scaleY = 1f
        text?.translationY = 0f
    }

    private fun applyPanelPalette(panel: View) {
        val content = panel as? android.widget.LinearLayout ?: return
        val palette = handlePalette()
        val bg = content.background as? android.graphics.drawable.GradientDrawable
        bg?.setColors(intArrayOf(palette[0], palette[0], palette[1].withAlpha(75)))
        bg?.setStroke(dp(1), palette[1].withAlpha(150))

        val controls = content.getChildAt(4) as? android.widget.LinearLayout ?: return
        for (rowIndex in 0 until controls.childCount) {
            val row = controls.getChildAt(rowIndex) as? android.view.ViewGroup ?: continue
            for (index in 0 until row.childCount) {
                val chip = row.getChildAt(index) as? TextView ?: continue
                applyActionChipPalette(chip, palette[0], palette[1])
            }
        }
    }

    private fun startPanelColorAnimation(panel: View) {
        val content = panel as? android.widget.LinearLayout ?: return
        val palette = handlePalette()
        val bg = content.background as? android.graphics.drawable.GradientDrawable ?: return
        val controls = content.getChildAt(4) as? android.widget.LinearLayout

        val start = palette[0]
        val accent = palette[1]
        val end = palette[2]
        val isRgb = OeaGameBoostStore.prefs(this).getString("ram_color_mode", "blue") == "rgb"

        (content.tag as? android.animation.ValueAnimator)?.cancel()
        val animator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (isRgb) 1800L else 1500L
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.REVERSE
            addUpdateListener { value ->
                val t = value.animatedFraction
                val middle = android.animation.ArgbEvaluator().evaluate(t, start, if (isRgb) accent else start) as Int
                val highlight = android.animation.ArgbEvaluator().evaluate(t, accent, end) as Int
                bg.setColors(intArrayOf(middle, middle, highlight.withAlpha(if (isRgb) 105 else 70)))
                bg.setStroke(dp(1), highlight.withAlpha(170))
                if (controls != null) {
                    for (rowIndex in 0 until controls.childCount) {
                        val row = controls.getChildAt(rowIndex) as? android.view.ViewGroup ?: continue
                        for (index in 0 until row.childCount) {
                            val chip = row.getChildAt(index) as? TextView ?: continue
                            applyActionChipPalette(chip, middle, highlight)
                        }
                    }
                }
            }
        }
        content.tag = animator
        animator.start()
    }

    private fun stopPanelColorAnimation(panel: View) {
        val content = panel as? android.widget.LinearLayout ?: return
        (content.tag as? android.animation.ValueAnimator)?.cancel()
        content.tag = null
        applyPanelPalette(content)
    }

    private fun handlePalette(): IntArray {
        return when (OeaGameBoostStore.prefs(this).getString("ram_color_mode", "blue") ?: "blue") {
            "green" -> intArrayOf(0xFF0B281B.toInt(), 0xFF27E37A.toInt(), 0xFF9BFFC5.toInt())
            "purple" -> intArrayOf(0xFF170B2C.toInt(), 0xFF9B5CFF.toInt(), 0xFFE1C4FF.toInt())
            "cyan" -> intArrayOf(0xFF071F27.toInt(), 0xFF25D9FF.toInt(), 0xFFB9F5FF.toInt())
            "red" -> intArrayOf(0xFF2B0B10.toInt(), 0xFFFF3D68.toInt(), 0xFFFFB3C2.toInt())
            "amber" -> intArrayOf(0xFF2A1A05.toInt(), 0xFFFFB52E.toInt(), 0xFFFFE0A0.toInt())
            "rgb" -> intArrayOf(0xFF171126.toInt(), 0xFF9C55FF.toInt(), 0xFF31D8FF.toInt())
            else -> intArrayOf(0xFF0A1020.toInt(), 0xFF2357FF.toInt(), 0xFF78B9FF.toInt())
        }
    }

    private fun Int.withAlpha(alpha: Int): Int =
        (this and 0x00FFFFFF) or ((alpha.coerceIn(0, 255)) shl 24)

    private fun togglePanel(panel: View) {
        if (panel.visibility == View.VISIBLE) {
            closePanel(panel)
        } else {
            showPanel(panel)
        }
    }

    private fun showPanel(panel: View) {
        if (panel.visibility == View.VISIBLE) return
        panel.animate().cancel()
        panel.visibility = View.VISIBLE
        if (!attachPanelWindow(panel)) {
            panel.visibility = View.GONE
            return
        }
        applyPanelPalette(panel)
        startPanelColorAnimation(panel)
        panel.alpha = 0f
        panel.scaleX = 0.96f
        panel.scaleY = 0.96f
        panel.translationX = dp(20).toFloat()
        panel.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationX(0f)
            .setDuration(280L)
            .setInterpolator(android.view.animation.PathInterpolator(0.18f, 0.9f, 0.2f, 1f))
            .withEndAction {
                animateGameBoostPanel(panel)
            }.start()
    }

    /**
     * The panel must have its own WindowManager window for Android to report
     * ACTION_OUTSIDE for taps outside the panel bounds while passing those
     * taps through to the app/game underneath. The handle remains in its own
     * small overlay window and is never dismissed by this listener.
     */
    private fun attachPanelWindow(panel: View): Boolean {
        // Reuse the same WindowManager window while hidden. Repeated remove/add
        // cycles can trigger OEM input/foreground transitions unrelated to the game.
        if (panel.parent != null) {
            panel.setOnTouchListener { _, event ->
                if (event.actionMasked == android.view.MotionEvent.ACTION_OUTSIDE) {
                    // Tapping the floating handle also counts as an outside tap
                    // for this separate panel window. Ignore that event so the
                    // handle's ACTION_UP can toggle the panel closed exactly once.
                    if (!isPointInsideFloatingHandle(event.rawX, event.rawY)) {
                        closePanel(panel)
                    }
                    true
                } else {
                    false
                }
            }
            overlay?.let(::syncPanelToHandle)
            return true
        }
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val root = overlay as? android.widget.LinearLayout ?: return false
        if (root.layoutParams !is WindowManager.LayoutParams) return false
        val panelParams = WindowManager.LayoutParams(
            dp(286), WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        panel.setOnTouchListener { _, event ->
            if (event.actionMasked == android.view.MotionEvent.ACTION_OUTSIDE) {
                // A tap on the handle is a toggle, not a generic outside dismissal.
                // All other outside taps still dismiss the panel.
                if (!isPointInsideFloatingHandle(event.rawX, event.rawY)) {
                    closePanel(panel)
                }
                true
            } else {
                false
            }
        }
        return runCatching {
            wm.addView(panel, panelParams)
            diagnostic(android.util.Log.INFO, "panel window attached panelParent=${panel.parent != null} rootAttached=${root.parent != null} activeGame=$activeGame")
            syncPanelToHandle(root)
            true
        }.getOrElse { error ->
            diagnostic(android.util.Log.ERROR, "panel window attach failed=${error.javaClass.name}:${error.message} stack=${android.util.Log.getStackTraceString(error).take(1800)}")
            panel.setOnTouchListener(null)
            false
        }
    }

    private fun isPointInsideFloatingHandle(rawX: Float, rawY: Float): Boolean {
        val root = overlay as? android.widget.LinearLayout ?: return false
        val handle = root.getChildAt(0) ?: return false
        if (handle.visibility != View.VISIBLE || !handle.isShown || handle.width <= 0 || handle.height <= 0) {
            return false
        }
        val location = IntArray(2)
        handle.getLocationOnScreen(location)
        val x = rawX.toInt()
        val y = rawY.toInt()
        return x >= location[0] && x < location[0] + handle.width &&
            y >= location[1] && y < location[1] + handle.height
    }

    /**
     * Keep the panel anchored to the floating handle while it is open.
     * The panel uses a separate Android window only so Android can deliver
     * outside-window touch events; its coordinates always follow the handle.
     */
    private fun syncPanelToHandle(root: View) {
        val panel = panelView ?: return
        if (panel.visibility != View.VISIBLE || panel.parent == null) return
        val panelParams = panel.layoutParams as? WindowManager.LayoutParams ?: return
        val handle = (root as? android.widget.LinearLayout)?.getChildAt(0) ?: return
        val screen = resources.displayMetrics
        val panelWidth = dp(286).coerceAtMost(screen.widthPixels)
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(panelWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(screen.heightPixels, View.MeasureSpec.AT_MOST)
        )
        val panelHeight = panel.measuredHeight.coerceAtMost(screen.heightPixels)
        val handleLocation = IntArray(2)
        handle.getLocationOnScreen(handleLocation)
        val gap = dp(8)
        val belowY = handleLocation[1] + handle.height + gap
        val aboveY = handleLocation[1] - panelHeight - gap
        val fitsBelow = belowY + panelHeight <= screen.heightPixels
        val fitsAbove = aboveY >= 0
        val targetY = when {
            fitsBelow -> belowY
            fitsAbove -> aboveY
            (screen.heightPixels - (handleLocation[1] + handle.height)) >= handleLocation[1] -> belowY
            else -> aboveY
        }.coerceIn(0, (screen.heightPixels - panelHeight).coerceAtLeast(0))
        val handleCenterX = handleLocation[0] + handle.width / 2
        panelParams.gravity = Gravity.TOP or Gravity.START
        panelParams.x = (handleCenterX - panelWidth / 2).coerceIn(0, (screen.widthPixels - panelWidth).coerceAtLeast(0))
        panelParams.y = targetY
        runCatching {
            (getSystemService(WINDOW_SERVICE) as WindowManager).updateViewLayout(panel, panelParams)
        }.onFailure { error ->
            diagnostic(android.util.Log.ERROR, "syncPanelToHandle updateViewLayout failed=${error.javaClass.name}:${error.message} panelAttached=${panel.parent != null} rootAttached=${root.parent != null}")
        }
    }

    private fun detachPanelWindow(panel: View) {
        if (panel.parent == null) return
        diagnostic(android.util.Log.WARN, "detachPanelWindow called panelVisibility=${panel.visibility} activeGame=$activeGame overlayAttached=${overlay?.parent != null}")
        runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(panel) }
            .onFailure { error -> diagnostic(android.util.Log.ERROR, "detachPanelWindow failed=${error.javaClass.name}:${error.message} panelStillAttached=${panel.parent != null}") }
    }

    private fun animateGameBoostPanel(panel: View) {
        val content = panel as? android.widget.LinearLayout ?: return
        val title = content.getChildAt(0) as? TextView
        val game = content.getChildAt(1) as? TextView
        val metrics = content.getChildAt(2) as? TextView
        val battery = content.getChildAt(3) as? TextView
        val controls = content.getChildAt(4) as? android.widget.LinearLayout

        listOf(title, game, metrics, battery).forEachIndexed { index, view ->
            view ?: return@forEachIndexed
            view.animate().cancel()
            view.alpha = 0f
            view.translationY = dp(5).toFloat()
            view.animate().alpha(1f).translationY(0f)
                .setStartDelay(index * 45L)
                .setDuration(220L)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }

        listOf(metrics, battery).forEach { view ->
            view ?: return@forEach
            view.animate().cancel()
            view.animate().alpha(0.72f).translationX(dp(2).toFloat())
                .setDuration(520L)
                .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
                .withEndAction {
                    view.animate().alpha(1f).translationX(0f)
                        .setDuration(520L)
                        .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
                        .withEndAction {
                            if (panel.visibility == View.VISIBLE) animateGameBoostTelemetry(view)
                        }.start()
                }.start()
        }

        if (controls != null) {
            for (rowIndex in 0 until controls.childCount) {
                val row = controls.getChildAt(rowIndex) as? android.view.ViewGroup ?: continue
                for (index in 0 until row.childCount) {
                    val child = row.getChildAt(index)
                    child.animate().cancel()
                    child.alpha = 0f
                    child.scaleX = 0.88f
                    child.scaleY = 0.88f
                    child.translationY = dp(10).toFloat()
                    child.animate()
                        .alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                        .setStartDelay((rowIndex * 90L) + (index * 55L))
                        .setDuration(260L)
                        .setInterpolator(android.view.animation.OvershootInterpolator(1.1f))
                        .start()
                }
            }
        }
    }

    private fun animateGameBoostTelemetry(view: View) {
        if (view.visibility != View.VISIBLE) return
        view.animate().cancel()
        view.animate().alpha(0.82f).translationX(dp(2).toFloat())
            .setDuration(650L)
            .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
            .withEndAction {
                if (view.visibility == View.VISIBLE) {
                    view.animate().alpha(1f).translationX(0f)
                        .setDuration(650L)
                        .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
                        .withEndAction {
                            if (overlay != null && view.visibility == View.VISIBLE) animateGameBoostTelemetry(view)
                        }.start()
                }
            }.start()
    }

    private fun closePanel(panel: View) {
        diagnostic(android.util.Log.INFO, "closePanel entered visibility=${panel.visibility} panelAttached=${panel.parent != null} activeGame=$activeGame overlayAttached=${overlay?.parent != null} overlayVisibility=${overlay?.visibility}")
        if (panel.visibility != View.VISIBLE && panel.parent == null) {
            diagnostic(android.util.Log.INFO, "closePanel no-op already hidden and detached activeGame=$activeGame overlayAttached=${overlay?.parent != null}")
            return
        }
        // Panel-dismiss must not be able to abort halfway through and leave an
        // unrecorded failure. Isolate optional animation/palette cleanup, then
        // always force the panel hidden while leaving the handle window untouched.
        runCatching { stopPanelColorAnimation(panel) }.onFailure { error ->
            diagnostic(android.util.Log.ERROR, "closePanel palette cleanup failed=${error.javaClass.name}:${error.message}")
        }
        runCatching { panel.animate().cancel() }.onFailure { error ->
            diagnostic(android.util.Log.ERROR, "closePanel animation cancel failed=${error.javaClass.name}:${error.message}")
        }
        runCatching { panel.clearAnimation() }.onFailure { error ->
            diagnostic(android.util.Log.ERROR, "closePanel clearAnimation failed=${error.javaClass.name}:${error.message}")
        }
        runCatching { panel.alpha = 0f; panel.visibility = View.GONE }
            .onFailure { error -> diagnostic(android.util.Log.ERROR, "closePanel hide failed=${error.javaClass.name}:${error.message}") }
        runCatching { panel.alpha = 1f; panel.scaleX = 1f; panel.scaleY = 1f; panel.translationX = 0f }
            .onFailure { error -> diagnostic(android.util.Log.ERROR, "closePanel reset animation properties failed=${error.javaClass.name}:${error.message}") }
        diagnostic(android.util.Log.INFO, "closePanel completed activeGame=$activeGame overlayAttached=${overlay?.parent != null} overlayVisibility=${overlay?.visibility} panelAttached=${panel.parent != null} panelVisibility=${panel.visibility} handleVisibility=${((overlay as? android.widget.LinearLayout)?.getChildAt(0)?.visibility)} handleAlpha=${((overlay as? android.widget.LinearLayout)?.getChildAt(0)?.alpha)}")
        // Deliberately do not call ensureOverlayForActiveGame() here.
        // That recovery function is allowed to remove and rebuild the entire
        // overlay root. A panel-dismiss action must never invoke root recovery:
        // the polling loop independently repairs a genuinely detached handle.
    }

    private fun setKeepScreenOn(enabled: Boolean) {
        val root = overlay as? android.widget.LinearLayout ?: return
        val params = root.layoutParams as? WindowManager.LayoutParams ?: return
        val nextFlags = if (enabled) {
            params.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        } else {
            params.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        }
        if (nextFlags != params.flags) {
            params.flags = nextFlags
            runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).updateViewLayout(root, params) }
                .onFailure { error -> diagnostic(android.util.Log.ERROR, "setKeepScreenOn updateViewLayout failed=${error.javaClass.name}:${error.message} enabled=$enabled attached=${root.parent != null}") }
        }
    }

    /** Returns used and total swap/zRAM-backed virtual memory in MB.
     * Uses the live swap capacity and, when exposed, the zRAM block capacity
     * so the displayed virtual-memory percentage is based on real capacity.
     */
    private fun virtualRamMb(): Pair<Double, Double> {
        var swapTotalKb = 0.0
        var swapFreeKb = 0.0
        runCatching {
            java.io.File("/proc/meminfo").forEachLine { line ->
                when {
                    line.startsWith("SwapTotal:") -> swapTotalKb = line.filter { it.isDigit() }.toDoubleOrNull() ?: 0.0
                    line.startsWith("SwapFree:") -> swapFreeKb = line.filter { it.isDigit() }.toDoubleOrNull() ?: 0.0
                }
            }
        }
        var zramCapacityKb = 0.0
        runCatching {
            java.io.File("/sys/block/zram0/disksize").takeIf { it.exists() }?.readText()
                ?.trim()?.toDoubleOrNull()?.let { zramCapacityKb = it / 1024.0 }
        }
        val totalKb = maxOf(swapTotalKb, zramCapacityKb)
        val usedKb = (totalKb - swapFreeKb).coerceAtLeast(0.0)
        return Pair(usedKb / 1024.0, totalKb / 1024.0)
    }

    private fun applyGameDndPreference() {
        val nm = getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) {
            diagnostic(android.util.Log.WARN, "game-session DND cannot be applied: notification policy access is not granted")
            return
        }
        val shouldEnable = activeGame != null &&
            OeaGameBoostStore.prefs(this).getBoolean("dnd", true)
        val targetFilter = if (shouldEnable) {
            NotificationManager.INTERRUPTION_FILTER_PRIORITY
        } else {
            NotificationManager.INTERRUPTION_FILTER_ALL
        }
        if (nm.currentInterruptionFilter != targetFilter) {
            runCatching { nm.setInterruptionFilter(targetFilter) }
                .onFailure { diagnostic(android.util.Log.ERROR, "game-session DND apply failed enabled=$shouldEnable error=${it.javaClass.simpleName}:${it.message}") }
        }
    }

    private fun restoreDnd() {
        val nm = getSystemService(NotificationManager::class.java)
        // OEA's DND toggle means "DND while a selected game is active".
        // Do not restore a previously active filter after leaving the game.
        if (nm.isNotificationPolicyAccessGranted) {
            runCatching { nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL) }
                .onFailure { diagnostic(android.util.Log.ERROR, "game-session DND reset failed error=${it.javaClass.simpleName}:${it.message}") }
        }
    }

    private fun removeOverlay() {
        diagnostic(android.util.Log.WARN, "removeOverlay entered activeGame=$activeGame overlayExists=${overlay != null} overlayAttached=${overlay?.parent != null} handleVisibility=${((overlay as? android.widget.LinearLayout)?.getChildAt(0)?.visibility)} panelExists=${panelView != null} panelAttached=${panelView?.parent != null}")
        panelView?.let { panel ->
            runCatching { panel.animate().cancel() }.onFailure { error -> diagnostic(android.util.Log.ERROR, "removeOverlay panel animation cancel failed=${error.javaClass.name}:${error.message}") }
            detachPanelWindow(panel)
        }
        overlay?.let { root ->
            runCatching { root.animate().cancel() }.onFailure { error -> diagnostic(android.util.Log.ERROR, "removeOverlay root animation cancel failed=${error.javaClass.name}:${error.message}") }
            runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(root) }
                .onFailure { error -> diagnostic(android.util.Log.ERROR, "removeOverlay removeView failed=${error.javaClass.name}:${error.message} rootAttached=${root.parent != null}") }
        }
        overlay = null
        panelView = null
        diagnostic(android.util.Log.WARN, "removeOverlay completed activeGame=$activeGame overlayExists=${overlay != null} panelExists=${panelView != null}")
    }
    private fun foregroundPackage(): String? {
        val usm = getSystemService(UsageStatsManager::class.java) ?: return lastKnownForegroundPackage
        val end = System.currentTimeMillis()
        val start = end - 30_000L

        return runCatching {
            val events = usm.queryEvents(start, end)
            val event = android.app.usage.UsageEvents.Event()
            var latestPackage: String? = null
            var latestTime = 0L
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val resumed = if (Build.VERSION.SDK_INT >= 29) {
                    event.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED
                } else {
                    event.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND
                }
                if (resumed && event.timeStamp >= latestTime) {
                    latestTime = event.timeStamp
                    latestPackage = event.packageName
                    foregroundActivityClass = event.className
                }
            }
            if (latestPackage != null) {
                lastKnownForegroundPackage = latestPackage
                lastForegroundEventAt = latestTime
                lastForegroundSource = "usage-events-resumed"
                lastForegroundQueryError = null
                return latestPackage
            }

            // No lifecycle event in this rolling window is normal when a game
            // remains open for minutes. It is not evidence that the user left.
            // A real transition to Home/another app produces a new resumed event
            // and replaces this cache before the next foreground decision.
            lastKnownForegroundPackage?.let {
                lastForegroundSource = "cached-last-resumed"
                return it
            }

            // On service start, there may be no cached event yet. Use UsageStats
            // once as a bootstrap rather than treating an empty event window as
            // proof that no app is foreground.
            val stats = runCatching {
                usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)
            }.getOrNull().orEmpty()
            val fallbackStat = stats.maxByOrNull { it.lastTimeUsed }
            val fallback = fallbackStat?.packageName
            if (fallback != null) {
                lastKnownForegroundPackage = fallback
                lastForegroundEventAt = fallbackStat?.lastTimeUsed ?: 0L
                lastForegroundSource = "usage-stats-bootstrap"
            }
            fallback
        }.getOrElse { error ->
            lastForegroundQueryError = "${error.javaClass.simpleName}:${error.message}"
            val stats = runCatching {
                usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)
            }.getOrNull().orEmpty()
            val fallbackStat = stats.maxByOrNull { it.lastTimeUsed }
            val fallback = fallbackStat?.packageName
            if (fallback != null) {
                lastKnownForegroundPackage = fallback
                lastForegroundEventAt = fallbackStat?.lastTimeUsed ?: 0L
                lastForegroundSource = "usage-stats-after-query-error"
            } else {
                lastForegroundSource = "cached-after-query-error"
            }
            diagnostic(android.util.Log.ERROR, "foregroundPackage query failed=${error.javaClass.name}:${error.message} fallback=$fallback cached=$lastKnownForegroundPackage")
            fallback ?: lastKnownForegroundPackage
        }
    }
    private fun isUsageAccessGranted() = runCatching {
        val appOps = getSystemService(AppOpsManager::class.java)
        appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)
    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "OEA Game Boost", NotificationManager.IMPORTANCE_LOW))
    }
    companion object {
        const val ACTION_REFRESH = "com.oea.launcher.gameboost.REFRESH"
        private const val CHANNEL = "oea_game_boost"
        private const val NOTIFICATION_ID = 4107
    }
}
object OeaGameBoostStore {
    private const val PREFS = "oea_game_boost"
    fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun enabled(context: Context) = prefs(context).getBoolean("enabled", false)
    fun setEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean("enabled", enabled).apply()
    fun games(context: Context) = prefs(context).getStringSet("games", emptySet()).orEmpty()
    fun setGames(context: Context, games: Set<String>) = prefs(context).edit().putStringSet("games", games).apply()
    fun mode(context: Context) = prefs(context).getString("game_selection_mode", "automatic") ?: "automatic"
    fun setMode(context: Context, mode: String) {
        prefs(context).edit().putString("game_selection_mode", if (mode == "manual_automatic") mode else "automatic").apply()
    }
    fun dismissedGames(context: Context) = prefs(context).getStringSet("dismissed_games", emptySet()).orEmpty()
    fun isGame(context: Context, packageName: String): Boolean {
        if (!games(context).contains(packageName)) return false
        return mode(context) == "manual_automatic" || detectedGames(context).contains(packageName)
    }

    /** Uses Android's declared application category where available, with a conservative legacy fallback. */
    fun detectedGames(context: Context): Set<String> {
        val pm = context.packageManager
        return pm.getInstalledApplications(0).asSequence()
            .filter { it.packageName != context.packageName }
            .filter {
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    it.category == android.content.pm.ApplicationInfo.CATEGORY_GAME
                } else {
                    pm.getApplicationLabel(it).toString().lowercase().let { label ->
                        label.contains("game") || label.contains("arcade")
                    }
                }
            }
            .map { it.packageName }
            .toSet()
    }

    fun syncDetectedGames(context: Context): Set<String> {
        val detected = detectedGames(context)
        val dismissed = dismissedGames(context)
        val merged = games(context).toMutableSet().apply { addAll(detected.filterNot(dismissed::contains)) }
        setGames(context, merged)
        return merged
    }

    fun removeGame(context: Context, packageName: String) {
        setGames(context, games(context).toMutableSet().apply { remove(packageName) })
        prefs(context).edit().putStringSet("dismissed_games", dismissedGames(context).toMutableSet().apply { add(packageName) }).apply()
    }

    fun restoreGame(context: Context, packageName: String) {
        prefs(context).edit().putStringSet("dismissed_games", dismissedGames(context).toMutableSet().apply { remove(packageName) }).apply()
    }
}