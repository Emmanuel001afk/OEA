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

class OeaGameBoostService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var overlay: View? = null
    private var wakeOverlay: View? = null
    private var lastWakeTapAt: Long = 0L
    private var activeGame: String? = null
    private var previousInterruptionFilter: Int? = null
        private val tick = object : Runnable {
        override fun run() {
            if (!OeaGameBoostStore.enabled(this@OeaGameBoostService) || !isUsageAccessGranted()) { stopSelf(); return }
            OeaGameBoostStore.syncDetectedGames(this@OeaGameBoostService)
            val game = foregroundPackage()
            if (game != null && OeaGameBoostStore.isGame(this@OeaGameBoostService, game)) {
                if (activeGame != game) { activeGame?.let(::deactivate); activeGame = game; activate(game) } else updateOverlay()
            } else if (activeGame != null && game != null) {
                // A positively identified non-selected foreground app means the
                // game was actually left. A null/unknown result is not allowed
                // to hide OEA RAM while the player may still be in the game.
                deactivate(activeGame!!)
                activeGame = null
            } else if (activeGame != null) {
                updateOverlay()
            }
            handler.postDelayed(this, 1000)
        }
    }
    override fun onCreate() {
        super.onCreate()
        createChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentTitle("OEA RAM").setContentText("Game session active").setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        else startForeground(NOTIFICATION_ID, notification)
        handler.post(tick)
    }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); activeGame?.let(::deactivate); removeWakeOverlay(); removeOverlay(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    private fun activate(packageName: String) {
        if (OeaGameBoostStore.prefs(this).getBoolean("dnd", true)) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.isNotificationPolicyAccessGranted) { previousInterruptionFilter = nm.currentInterruptionFilter; nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY) }
        }
        if (Settings.canDrawOverlays(this)) {
            showOverlay(packageName)
            if (OeaGameBoostStore.prefs(this).getBoolean("boost", true)) setKeepScreenOn(true)
        }
    }
    private fun deactivate(@Suppress("UNUSED_PARAMETER") packageName: String) {
        val nm = getSystemService(NotificationManager::class.java)
        restoreDnd()
        setKeepScreenOn(false)
        removeOverlay()
    }
    private fun showOverlay(packageName: String) {
        if (overlay != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        val panel = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(16), dp(13), dp(16), dp(13))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xF21B1D22.toInt())
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), 0x553F51FF)
            }
            elevation = dp(10).toFloat()
            visibility = View.GONE
        }
        val title = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            text = "OEA RAM"
        }
        val gameName = TextView(this).apply {
            setTextColor(0xFFB9C7FF.toInt()); textSize = 11f
            text = packageName.substringAfterLast('.')
            setPadding(0, dp(3), 0, dp(9))
        }
        val metrics = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 12f
            text = "RAM • reading…"; setPadding(0, 0, 0, dp(4))
        }
        val device = TextView(this).apply {
            setTextColor(0xFFD0D0D0.toInt()); textSize = 11f
            text = "Battery • reading…"; setPadding(0, 0, 0, dp(8))
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
        val hide = chip("⌄  HIDE")
        rowOne.addView(dnd, chipParams())
        rowOne.addView(boost, chipParams())
        rowOne.addView(screenshot, chipParams())
        rowTwo.addView(record, chipParams())
        rowTwo.addView(cleanup, chipParams())
        rowTwo.addView(hide, chipParams())
        controls.addView(rowOne)
        controls.addView(rowTwo, android.widget.LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(5) })
        dnd.setOnClickListener { toggleDnd(dnd) }
        screenshot.setOnClickListener { launchCapture(OeaGameCaptureActivity.MODE_SCREENSHOT) }
        record.setOnClickListener { toggleRecording(record) }
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
        hide.setOnClickListener { hidePanel(panel) }
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
        handle.setOnClickListener {
            if (panel.visibility == View.VISIBLE) hidePanel(panel) else showPanel(panel)
        }
        root.addView(panel, android.widget.LinearLayout.LayoutParams(dp(286), -2))
        root.addView(handle, android.widget.LinearLayout.LayoutParams(handleSize, handleSize).apply {
            gravity = Gravity.END
            topMargin = dp(6)
        })

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
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

            handle.setOnTouchListener(object : View.OnTouchListener {
                private var downRawX = 0f
                private var downRawY = 0f
                private var startX = 0
                private var startY = 0
                private var dragging = false

                override fun onTouch(v: View, event: android.view.MotionEvent): Boolean {
                    when (event.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN -> {
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
                                handle.animate().cancel()
                                handle.animate().scaleX(0.92f).scaleY(0.92f).setDuration(90L).start()
                            }
                            if (dragging) {
                                val next = root.layoutParams as? WindowManager.LayoutParams ?: return true
                                val bounds = screenBounds(root)
                                next.gravity = Gravity.TOP or Gravity.START
                                next.x = (startX + dx.toInt()).coerceIn(bounds[0], bounds[2])
                                next.y = (startY + dy.toInt()).coerceIn(bounds[1], bounds[3])
                                runCatching { wm.updateViewLayout(root, next) }
                            }
                            return true
                        }
                        android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                            if (dragging) {
                                snapHandleToEdge(root, handle, wm)
                                return true
                            }
                            v.performClick()
                            return true
                        }
                    }
                    return false
                }
            })
            handle.alpha = 0f
            handle.translationX = dp(10).toFloat()
            handle.animate().alpha(1f).translationX(0f).setDuration(260L)
                .setInterpolator(android.view.animation.PathInterpolator(0.18f, 0.9f, 0.2f, 1f)).withEndAction { animateHandle(handle) }.start()
            updateOverlay()
        }
    }

    private fun ensureWakeOverlay() {
        if (wakeOverlay != null || !OeaGameBoostStore.prefs(this).getBoolean("ram_handle_visible", true)) return
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
                        val handle = (root as? android.widget.LinearLayout)?.getChildAt(1) as? android.widget.FrameLayout
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
        }
    }

    private fun removeWakeOverlay() {
        val wake = wakeOverlay ?: return
        runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(wake) }
        wakeOverlay = null
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
        runCatching { wm.updateViewLayout(root, params) }
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
        android.widget.LinearLayout.LayoutParams(0, dp(40), 1f).apply { rightMargin = dp(5) }

    private fun launchCapture(mode: String) {
        OeaGameBoostStore.prefs(this).edit().putBoolean("capture_active", true).putString("capture_mode", mode).apply()
        overlay?.alpha = 0f
        val intent = Intent(this, OeaGameCaptureActivity::class.java).apply {
            putExtra(OeaGameCaptureActivity.EXTRA_MODE, mode)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        runCatching { startActivity(intent) }
    }

    private fun toggleRecording(button: TextView) {
        val prefs = OeaGameBoostStore.prefs(this)
        if (prefs.getBoolean("recording", false)) {
            startService(Intent(this, OeaGameCaptureService::class.java).setAction(OeaGameCaptureService.ACTION_STOP))
            prefs.edit().putBoolean("recording", false).apply()
            button.text = "RECORD"
        } else {
            prefs.edit().putBoolean("recording", true).apply()
            launchCapture(OeaGameCaptureActivity.MODE_RECORD)
            button.text = "RECORDING"
        }
    }

    private fun cleanBackgroundMemory(button: TextView) {
        val am = getSystemService(ActivityManager::class.java)
        val active = activeGame
        var attempted = 0
        runCatching {
            am.runningAppProcesses.orEmpty()
                .filter { it.importance >= ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND }
                .filter { it.pkgList?.none { pkg -> pkg == packageName || pkg == active } == true }
                .take(24)
                .forEach { process ->
                    process.pkgList.orEmpty().distinct().forEach { pkg ->
                        runCatching { am.killBackgroundProcesses(pkg); attempted++ }
                    }
                }
        }
        button.text = if (attempted > 0) "✓ CLEANED" else "CLEAN RAM"
        handler.postDelayed({ button.text = "CLEAN RAM" }, 1200L)
        updateOverlay()
    }

    private fun chip(label: String): TextView = TextView(this).apply {
        text = label; gravity = Gravity.CENTER; setTextColor(Color.WHITE); textSize = 10f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFF2B2E35.toInt()); cornerRadius = dp(13).toFloat()
            setStroke(dp(1), 0x223F51FF)
        }
        isClickable = true; isFocusable = true
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
        if (next) {
            if (previousInterruptionFilter == null) previousInterruptionFilter = nm.currentInterruptionFilter
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        } else restoreDnd()
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
        val root = overlay as? android.widget.LinearLayout ?: run {
            activeGame?.let { if (Settings.canDrawOverlays(this)) showOverlay(it) }
            return
        }
        val panel = root.getChildAt(0) as? android.widget.LinearLayout ?: return
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
        metrics.text = String.format(
            Locale.US,
            "RAM  %.0f / %.0f MB  •  %.0f%%%s\nVirtual RAM  %s",
            usedMb, totalMb, usedPct, state, if (virtualTotal > 0) String.format(Locale.US, "%.0f / %.0f MB", virtualUsed, virtualTotal) else "not exposed by Android"
        )

        val battery = getSystemService(BATTERY_SERVICE) as BatteryManager
        val level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        device.text = if (level in 0..100) "Battery $level% • Game focus active" else "Game focus active"
        gameName.text = activeGame?.substringAfterLast('.') ?: "Game"
        updateDndButton(dnd)
        updateBoostButton(boost)
        root.alpha = if (OeaGameBoostStore.prefs(this).getBoolean("capture_active", false)) 0f else 1f
        val captureActive = OeaGameBoostStore.prefs(this).getBoolean("capture_active", false)
        val captureMode = OeaGameBoostStore.prefs(this).getString("capture_mode", "")
        val recording = OeaGameBoostStore.prefs(this).getBoolean("recording", false)
        (controls.getChildAt(0) as? android.widget.LinearLayout)?.getChildAt(2)?.let { (it as? TextView)?.text = if (captureActive && captureMode == OeaGameCaptureActivity.MODE_SCREENSHOT) "SHOT…" else "▣  SHOT" }
        (controls.getChildAt(1) as? android.widget.LinearLayout)?.getChildAt(0)?.let { (it as? TextView)?.text = if (recording) "●  RECORDING" else "●  RECORD" }
        setKeepScreenOn(OeaGameBoostStore.prefs(this).getBoolean("boost", true))
        val handle = root.getChildAt(1) as? android.widget.FrameLayout
        val handleVisible = OeaGameBoostStore.prefs(this).getBoolean("ram_handle_visible", true)
        handle?.visibility = if (handleVisible) View.VISIBLE else View.GONE
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
                if (handleVisible && t.visibility == View.VISIBLE) animateHandleText(t)
            }
        }
    }

    private fun updateBoostButton(button: TextView) {
        button.text = if (OeaGameBoostStore.prefs(this).getBoolean("boost", true)) "BOOST ON" else "BOOST OFF"
    }

    private fun animateHandleText(text: TextView) {
        text.animate().cancel()
        text.scaleX = 0.96f
        text.scaleY = 0.96f
        text.alpha = 0.86f
        text.animate()
            .scaleX(1.04f)
            .scaleY(1.04f)
            .alpha(1f)
            .setDuration(700L)
            .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
            .withEndAction {
                if (overlay != null && text.visibility == View.VISIBLE) {
                    text.animate().scaleX(0.96f).scaleY(0.96f).alpha(0.88f)
                        .setDuration(700L)
                        .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
                        .withEndAction { if (overlay != null && text.visibility == View.VISIBLE) animateHandleText(text) }
                        .start()
                }
            }.start()
    }

    private fun animateHandle(handle: View) {
        handle.animate().cancel()
        val bg = handle.background as? android.graphics.drawable.GradientDrawable
        val pulse = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1400L
            repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE
            addUpdateListener { animator ->
                val p = animator.animatedValue as Float
                val accent = android.animation.ArgbEvaluator().evaluate(p, 0xFF2357FF.toInt(), 0xFF56B7FF.toInt()) as Int
                val deep = android.animation.ArgbEvaluator().evaluate(p, 0xFF0A1020.toInt(), 0xFF102A5A.toInt()) as Int
                bg?.setColors(intArrayOf(deep, accent, 0xFF0B1735.toInt()))
                val text = (handle as? android.view.ViewGroup)?.getChildAt(0) as? TextView
                text?.setShadowLayer(dp(3 + (p * 3).toInt()).toFloat(), 0f, 0f, accent)
                bg?.setStroke(dp(1), android.animation.ArgbEvaluator().evaluate(p, 0x884D74FF.toInt(), 0xEE7BC8FF.toInt()) as Int)
            }
        }
        pulse.start()
        handle.animate().scaleX(0.94f).scaleY(0.94f).setDuration(700L)
            .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator()).withEndAction {
                handle.animate().scaleX(1f).scaleY(1f).setDuration(700L)
                    .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator()).withEndAction {
                        if (overlay != null && handle.visibility == View.VISIBLE) {
                            pulse.cancel()
                            animateHandle(handle)
                        } else {
                            pulse.cancel()
                        }
                    }.start()
            }.start()
    }

    private fun showPanel(panel: View) {
        if (panel.visibility == View.VISIBLE) return
        panel.animate().cancel()
        panel.visibility = View.VISIBLE
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
                val controls = (panel as? android.widget.LinearLayout)?.getChildAt(4) as? android.widget.LinearLayout
                if (controls != null) {
                    for (index in 0 until controls.childCount) {
                        val child = (controls as android.view.ViewGroup).getChildAt(index)
                        child.alpha = 0f
                        child.translationY = dp(8).toFloat()
                        child.animate().alpha(1f).translationY(0f).setStartDelay(index * 45L).setDuration(180L).start()
                    }
                }
            }.start()
    }

    private fun hidePanel(panel: View) {
        if (panel.visibility != View.VISIBLE) return
        panel.animate().cancel()
        panel.animate()
            .alpha(0f)
            .scaleX(0.96f)
            .scaleY(0.96f)
            .translationX(dp(12).toFloat())
            .setDuration(145L)
            .setInterpolator(android.view.animation.PathInterpolator(0.4f, 0f, 1f, 1f))
            .withEndAction {
                panel.visibility = View.GONE
                panel.alpha = 1f
                panel.scaleX = 1f
                panel.scaleY = 1f
                panel.translationX = 0f
            }.start()
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
        }
    }

    /** Returns used and total swap/zRAM-backed virtual memory in MB. */
    private fun virtualRamMb(): Pair<Double, Double> {
        var totalKb = 0.0
        var freeKb = 0.0
        runCatching {
            java.io.File("/proc/meminfo").forEachLine { line ->
                when {
                    line.startsWith("SwapTotal:") -> totalKb = line.filter { it.isDigit() }.toDoubleOrNull() ?: 0.0
                    line.startsWith("SwapFree:") -> freeKb = line.filter { it.isDigit() }.toDoubleOrNull() ?: 0.0
                }
            }
        }
        val usedKb = (totalKb - freeKb).coerceAtLeast(0.0)
        return Pair(usedKb / 1024.0, totalKb / 1024.0)
    }

    private fun restoreDnd() {
        val nm = getSystemService(NotificationManager::class.java)
        previousInterruptionFilter?.let {
            if (nm.isNotificationPolicyAccessGranted) nm.setInterruptionFilter(it)
        }
        previousInterruptionFilter = null
    }

    private fun removeOverlay() { overlay?.let { runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } }; overlay = null }
    private fun foregroundPackage(): String? {
        val usm = getSystemService(UsageStatsManager::class.java) ?: return null
        val end = System.currentTimeMillis()
        val start = end - 30_000L

        runCatching {
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
                }
            }
            if (latestPackage != null) return latestPackage
        }

        val stats = runCatching {
            usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)
        }.getOrNull() ?: return null
        return stats.maxByOrNull { it.lastTimeUsed }?.packageName
    }
    private fun isUsageAccessGranted() = runCatching {
        val appOps = getSystemService(AppOpsManager::class.java)
        appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)
    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "OEA Game Boost", NotificationManager.IMPORTANCE_LOW))
    }
    companion object { private const val CHANNEL = "oea_game_boost"; private const val NOTIFICATION_ID = 4107 }
}
object OeaGameBoostStore {
    private const val PREFS = "oea_game_boost"
    fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun enabled(context: Context) = prefs(context).getBoolean("enabled", false)
    fun setEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean("enabled", enabled).apply()
    fun games(context: Context) = prefs(context).getStringSet("games", emptySet()).orEmpty()
    fun setGames(context: Context, games: Set<String>) = prefs(context).edit().putStringSet("games", games).apply()
    fun dismissedGames(context: Context) = prefs(context).getStringSet("dismissed_games", emptySet()).orEmpty()
    fun isGame(context: Context, packageName: String) = games(context).contains(packageName)

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