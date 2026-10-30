package com.oea.launcher.multitask

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.ScrollView
import android.widget.LinearLayout
import com.oea.launcher.R
import com.oea.launcher.model.OeaAppInfo
import com.oea.launcher.multitask.projection.OeaLiveWindowManager
import com.oea.launcher.multitask.projection.OeaLiveWindows
import kotlin.math.abs

/**
 * OEA Floating Window: a custom, draggable and resizable floating
 * multitasking container for Android Go devices (e.g. Infinix
 * Smart 8) whose native multi-window frameworks are disabled.
 *
 * WHY AN ACCESSIBILITY SERVICE?
 * ----------------------------
 * On these devices the OS multi-window APIs are switched off, so
 * the only reliable way to keep an interactive window above every
 * app (including games) is a system overlay. An AccessibilityService
 * is used because:
 *   1. It is a user-granted, explicit opt-in (Android Settings >
 *      Accessibility), which keeps OEA honest about it;
 *   2. It survives while other apps are in front, and can filter
 *      key events (flagRequestFilterKeyEvents) so the floating
 *      window can react to hardware keys without reading screen
 *      content;
 *   3. TYPE_ACCESSIBILITY_OVERLAY lets this service draw its own
 *      touchable window without the separate Display-over-other-apps
 *      special access; it does not grant control of other apps' tasks.
 *
 * The service hosts an OEA-owned floating workspace with launchable
 * app shortcuts. Android does not allow an ordinary app to embed arbitrary
 * third-party Activities inside this overlay. Android 13+ cross-app activity
 * embedding also requires each target app to opt in. For arbitrary third-party
 * apps, actual floating tasks must be created by Android's freeform window manager.
 *
 * THE CONTAINER (vertical stack)
 * ------------------------------
 *   [ TITLE BAR ]      <- drag anywhere here to move the window
 *   [ CONTENT AREA ]   <- the floating workspace itself
 *   (bottom-right corner handle) <- drag to resize the box live
 */
class OeaFloatingWindowService : AccessibilityService() {

    // ------------------------------------------------------------------ state

    private val handler = Handler(Looper.getMainLooper())

    /** The single window this service owns. Null while hidden/removed. */
    private var windowRoot: FrameLayout? = null
    private var windowParams: WindowManager.LayoutParams? = null

    /** Title bar (the drag area) and the resize handle. */
    private var titleBar: View? = null
    private var resizeHandle: View? = null

    /** Current size in pixels, remembered across show/hide cycles. */
    private var windowWidth = 0
    private var windowHeight = 0

    /** Resting position restored from the previous session. */
    private var pendingX: Int? = null
    private var pendingY: Int? = null

    /** True while a drag or resize gesture is recognised (not a tap). */
    private var dragging = false

    /** Last tap on the title bar / handle, for double-tap gestures. */
    private var lastTitleTap = 0L
    private var lastHandleTap = 0L

    // ------------------------------------------------------------------ lifecycle

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // Live app windows (experimental) also live in this service: they
        // render a real app on an app-created virtual display inside OEA
        // floating windows. The manager only keeps state; it draws nothing
        // until the user starts a live window from the workspace.
        OeaLiveWindowManager.attach(this)
        // The accessibility config (res/xml/oea_floating_window.xml)
        // is applied by the framework before this runs. The flags are
        // re-asserted at runtime so a config change can never leave
        // the service in an inconsistent state: filter key events
        // (hardware-key reactions) and include not-important views
        // (the overlay's own nodes stay exposed to TalkBack users).
        runCatching {
            serviceInfo?.let { info ->
                info.flags = info.flags or
                    android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
                    android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                serviceInfo = info
            }
        }
        // Restore the size/position the user last dragged/resized to.
        OeaFloatingWindows.restoreGeometry(this) { width, height, x, y ->
            windowWidth = width
            windowHeight = height
            pendingX = x
            pendingY = y
        }
        // Show the window only when the user turned the feature on in
        // OEA Settings. Android binds the service whenever it is enabled
        // in Accessibility, so the two switches stay separate: the
        // accessibility switch is the *permission*, the OEA switch is
        // the *preference*.
        if (OeaFloatingWindows.enabled(this)) showWindow(animate = true)
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        hideWindow(animate = false)
        OeaLiveWindowManager.detach()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        hideWindow(animate = false)
        OeaLiveWindowManager.detach()
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    /**
     * Required by AccessibilityService. The workspace itself needs no
     * window-content events, so they are dropped unless a live app window
     * is running — then they are forwarded (they carry the target app's
     * state changes on the private display, which keeps projections from
     * looking live when they are stale).
     */
    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        if (event != null) OeaLiveWindowManager.onAccessibilityEvent(event)
    }

    /**
     * Hardware-key filter (enabled by flagRequestFilterKeyEvents).
     * Returns false so keys are never swallowed: the app or game
     * underneath keeps its input. Override the body to react to,
     * e.g., volume keys while the floating window is visible.
     */
    override fun onKeyEvent(event: android.view.KeyEvent?): Boolean = false

    // ------------------------------------------------------------------ window construction

    /**
     * Builds and attaches the floating window.
     *
     * LayoutParams notes (exactly as specified for this feature):
     *  - TYPE_ACCESSIBILITY_OVERLAY: an overlay owned by this enabled
     *    AccessibilityService; no separate overlay special access is needed;
     *  - FLAG_NOT_TOUCH_MODAL: touches OUTSIDE this window fall
     *    through to the app/game underneath — the floating window
     *    never blocks the app beneath it;
     *  - FLAG_LAYOUT_IN_SCREEN: the window is laid out against the
     *    whole screen, ignoring system decor insets, so it can be
     *    dragged edge to edge;
     *  - FLAG_NOT_FOCUSABLE: the underlying app keeps focus and
     *    keyboard input; the overlay is touch-only.
     */
    private fun showWindow(animate: Boolean) {
        if (windowRoot != null) {
            // Already showing; just make sure it is visible.
            windowRoot?.visibility = View.VISIBLE
            return
        }
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val metrics = resources.displayMetrics

        // Remember the last size, or start at a comfortable default
        // for small Go-device screens: 60% width, 45% height.
        val defaultWidth = (metrics.widthPixels * 0.60f).toInt()
        val defaultHeight = (metrics.heightPixels * 0.45f).toInt()
        if (windowWidth <= 0) windowWidth = defaultWidth
        if (windowHeight <= 0) windowHeight = defaultHeight
        windowWidth = windowWidth.coerceIn(minWindowWidth(), metrics.widthPixels)
        windowHeight = windowHeight.coerceIn(minWindowHeight(), metrics.heightPixels)

        val root = buildContainer()

        val params = WindowManager.LayoutParams(
            windowWidth,
            windowHeight,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            // Start at the restored position, or docked towards the
            // lower right; the user can drag it anywhere afterwards.
            gravity = Gravity.TOP or Gravity.START
            x = pendingX?.coerceIn(0, metrics.widthPixels - windowWidth)
                ?: (metrics.widthPixels - windowWidth)
            y = pendingY?.coerceIn(0, metrics.heightPixels - windowHeight)
                ?: (metrics.heightPixels - windowHeight) / 3
            // Smooth window-level transitions where the platform allows.
            if (Build.VERSION.SDK_INT >= 24) {
                windowAnimations = android.R.style.Animation_Dialog
            }
        }

        windowRoot = root
        windowParams = params

        runCatching { wm.addView(root, params) }.onFailure { error ->
            android.util.Log.w("OeaFloatingWindow", "attach failed", error)
            windowRoot = null
            windowParams = null
            return
        }

        if (animate) {
            // Premium entrance: fade + gentle rise + slight scale,
            // with an ease-out curve so it feels physical, not linear.
            root.alpha = 0f
            root.translationY = dp(24).toFloat()
            root.scaleX = 0.96f
            root.scaleY = 0.96f
            root.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(240L)
                .setInterpolator(PathInterpolator(0.18f, 0.9f, 0.2f, 1f))
                .start()
        }
    }

    /** Removes the window with a short fade, then detaches it. */
    private fun hideWindow(animate: Boolean) {
        val root = windowRoot ?: return
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val detach = {
            runCatching { wm?.removeView(root) }
            windowRoot = null
            windowParams = null
            titleBar = null
            resizeHandle = null
        }
        if (animate) {
            root.animate()
                .alpha(0f)
                .translationY(dp(12).toFloat())
                .setDuration(160L)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction(detach)
                .start()
        } else {
            detach()
        }
    }

    /**
     * The whole floating window: a vertical container with the
     * title bar on top, one content area, and a bottom-right
     * resize handle floating over the last corner.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun buildContainer(): FrameLayout {
        val root = FrameLayout(this).apply {
            // A dark glass card with a subtle blue rim, matching OEA.
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xF2131C2E.toInt(), 0xEE0C1220.toInt())
            ).apply {
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), 0x8855BFFF.toInt())
            }
            // Clip children so content never paints past the rounded
            // corners while the box is being resized.
            clipChildren = true
            clipToOutline = true
        }

        // ---- title bar (the drag handle of the whole window) ----------
        val title = TextView(this).apply {
            text = "◈  OEA FLOAT"
            setTextColor(0xFFF1F8FF.toInt())
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(dp(4).toFloat(), 0f, 0f, 0x6657C8FF)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
            contentDescription = "Floating window title bar. Drag to move the window."
        }
        val titleLayout = FrameLayout(this).apply {
            // Tall enough for a comfortable one-finger grip.
            setPadding(0, dp(4), 0, dp(4))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFF182238.toInt(), 0x00182238.toInt())
            )
            addView(title, FrameLayout.LayoutParams(-2, dp(34), Gravity.START or Gravity.CENTER_VERTICAL))
        }
        root.addView(titleLayout, FrameLayout.LayoutParams(-1, dp(42), Gravity.TOP))
        titleBar = titleLayout

        // ---- content area: the floating workspace itself --------------
        val content = FrameLayout(this).apply {
            id = R.id.oea_floating_content_slot
            setBackgroundColor(0x0AFFFFFF)
        }
        // Fills everything between the title bar and the bottom edge.
        root.addView(content, FrameLayout.LayoutParams(-1, -1, Gravity.TOP).apply {
            topMargin = dp(42)
            bottomMargin = dp(6)
        })
        buildWorkspaceContent(content)

        // ---- bottom-right resize handle --------------------------------
        val handle = View(this).apply {
            id = R.id.oea_floating_resize_handle
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF2357FF.toInt(), 0xFF0B1735.toInt())
            ).apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setStroke(dp(1), 0xAA4D74FF.toInt())
            }
            contentDescription = "Resize handle. Drag to change the window size."
        }
        root.addView(handle, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.END or Gravity.BOTTOM))
        resizeHandle = handle

        // ---- title-bar actions: collapse and close ------------------
        // Two small round buttons at the end of the title bar. They
        // share nothing: each gets its own LayoutParams, because a
        // single shared instance would move both views at once.
        val collapseBtn = iconButton(
            R.drawable.oic_expand_more,
            "Collapse the floating window"
        ) { collapse() }
        titleLayout.addView(collapseBtn, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.END or Gravity.CENTER_VERTICAL).apply {
            marginEnd = dp(42)
        })
        val closeBtn = iconButton(
            R.drawable.oic_close,
            "Close the floating window until it is turned on again"
        ) { closeWindow() }
        titleLayout.addView(closeBtn, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.END or Gravity.CENTER_VERTICAL).apply {
            marginEnd = dp(2)
        })

        // Wire the physics: title bar moves the window, the corner
        // handle resizes it.
        titleLayout.setOnTouchListener(TitleDragTouchListener(root))
        handle.setOnTouchListener(ResizeTouchListener(root))

        return root
    }

    /**
     * OEA's overlay workspace. Tapping an app requests a real Android
     * freeform task by default; when the user turns on live app windows
     * (experimental), tapping instead starts an OEA live window: the real
     * app on a private virtual display, presented and controlled through
     * this overlay while the phone screen keeps the user's current app.
     */
    private fun buildWorkspaceContent(content: FrameLayout) {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(28))
        }
        body.addView(TextView(this).apply {
            text = "OEA FLOATING WORKSPACE"
            setTextColor(0xFF9CCEFF.toInt())
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), dp(2), dp(4), dp(6))
        })
        body.addView(TextView(this).apply {
            text = if (OeaLiveWindows.enabled(this@OeaFloatingWindowService)) {
                "Live windows are on: tap an app to open it here for real, on a private display. Your current app stays on the phone screen. Long-press an app for the old freeform request."
            } else {
                "Tap an app to request Android freeform. This panel is a floating launcher, not an embedded app window. Android/OEM may ignore the request and open the app full-screen; tap the arrow above to hide this panel and check. Reboot after enabling freeform in Developer options."
            }
            setTextColor(0xFFD6E2F3.toInt())
            textSize = 11f
            setPadding(dp(4), 0, dp(4), dp(8))
        })
        buildLiveWindowsHeader(body)

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val launchIntent = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        val apps = runCatching {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(launchIntent, 0)
                .filter { it.activityInfo.packageName != packageName }
                .distinctBy { it.activityInfo.packageName }
                .sortedBy { it.loadLabel(packageManager).toString().lowercase() }
        }.getOrDefault(emptyList())
        if (apps.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "No launchable apps were found. Check Android app visibility and try again."
                setTextColor(0xFFD6E2F3.toInt())
                textSize = 12f
                setPadding(dp(8), dp(12), dp(8), dp(12))
            })
        } else {
            val liveMode = OeaLiveWindows.enabled(this)
            apps.forEach { info ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    isClickable = true
                    isFocusable = true
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(0x182F80ED)
                        cornerRadius = dp(10).toFloat()
                    }
                    setPadding(dp(8), dp(6), dp(8), dp(6))
                    contentDescription = "Request ${info.loadLabel(packageManager)} in an Android freeform window"
                }
                val icon = ImageView(this).apply {
                    setImageDrawable(info.loadIcon(packageManager))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                row.addView(icon, LinearLayout.LayoutParams(dp(34), dp(34)))
                row.addView(TextView(this).apply {
                    text = info.loadLabel(packageManager).toString()
                    setTextColor(0xFFF1F8FF.toInt())
                    textSize = 13f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(10), 0, 0, 0)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                row.setOnClickListener {
                    val label = info.loadLabel(packageManager).toString()
                    if (liveMode) {
                        val message = OeaLiveWindowManager.start(
                            this, info.activityInfo.packageName, label
                        )
                        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
                    } else {
                        val result = OeaMultitaskLauncher.launchFloating(
                            this,
                            OeaAppInfo(info.activityInfo.packageName, info.activityInfo.name, label)
                        )
                        android.widget.Toast.makeText(this, result.message, android.widget.Toast.LENGTH_LONG).show()
                    }
                }
                // Escape hatch: long-press always keeps the old freeform path.
                row.setOnLongClickListener {
                    val label = info.loadLabel(packageManager).toString()
                    val result = OeaMultitaskLauncher.launchFloating(
                        this,
                        OeaAppInfo(info.activityInfo.packageName, info.activityInfo.name, label)
                    )
                    android.widget.Toast.makeText(this, result.message, android.widget.Toast.LENGTH_LONG).show()
                    true
                }
                list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(5) })
            }
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(body) }
        body.addView(list, LinearLayout.LayoutParams(-1, -2))
        content.addView(scroll, FrameLayout.LayoutParams(-1, -1))
    }

    /**
     * The live-windows section of the workspace: an on/off pill, a capacity
     * line, and a Restore row per running session. Toggling rebuilds the
     * workspace so the app rows change meaning immediately.
     */
    private fun buildLiveWindowsHeader(body: LinearLayout) {
        val liveOn = OeaLiveWindows.enabled(this)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(6))
        }
        header.addView(TextView(this).apply {
            text = "LIVE APP WINDOWS"
            setTextColor(if (liveOn) 0xFF7AE7C7.toInt() else 0xFF9CCEFF.toInt())
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val toggle = TextView(this).apply {
            text = if (liveOn) "ON ✓" else "OFF"
            setTextColor(0xFFF1F8FF.toInt())
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            isClickable = true
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(if (liveOn) 0x332DE1A0 else 0x33FFFFFF)
                cornerRadius = dp(12).toFloat()
            }
            contentDescription = if (liveOn) "Turn live app windows off" else "Turn live app windows on"
        }
        toggle.setOnClickListener {
            haptic()
            OeaLiveWindows.setEnabled(this, !liveOn)
            android.widget.Toast.makeText(
                this,
                if (!liveOn)
                    "Live windows on. Tapping an app opens it here for real, on a private display."
                else
                    "Live windows off. Tapping an app requests Android freeform again.",
                android.widget.Toast.LENGTH_LONG
            ).show()
            rebuildWorkspace()
        }
        header.addView(toggle, LinearLayout.LayoutParams(-2, -2))
        body.addView(header, LinearLayout.LayoutParams(-1, -2))

        val sessions = OeaLiveWindowManager.list()
        if (liveOn) {
            body.addView(TextView(this).apply {
                text = "${OeaLiveWindowManager.count()} of ${OeaLiveWindowManager.MAX_WINDOWS} windows • " +
                        (if (OeaLiveWindowManager.canStart()) "tap an app below to add one" else "close one to add another")
                setTextColor(0xFF9CCEFF.toInt())
                textSize = 10f
                setPadding(dp(4), 0, dp(4), dp(4))
            })
            sessions.forEach { session ->
                val row = TextView(this).apply {
                    text = "• ${session.describe()}  —  tap to ${if (session.minimised) "restore" else "hide"}"
                    setTextColor(0xFFD6E2F3.toInt())
                    textSize = 11f
                    isClickable = true
                    setPadding(dp(8), dp(6), dp(8), dp(6))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(0x142DE1A0)
                        cornerRadius = dp(8).toFloat()
                    }
                }
                row.setOnClickListener {
                    haptic()
                    if (session.minimised) session.restore() else session.onMinimise()
                    rebuildWorkspace()
                }
                row.setOnLongClickListener {
                    OeaLiveWindowManager.stop(session)
                    android.widget.Toast.makeText(this, "Closed the ${session.appLabel} live window.", android.widget.Toast.LENGTH_SHORT).show()
                    rebuildWorkspace()
                    true
                }
                body.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
            }
        }
    }

    /** Rebuilds the whole workspace (used when the live-windows mode changes). */
    private fun rebuildWorkspace() {
        if (windowRoot == null) return
        hideWindow(animate = false)
        showWindow(animate = false)
    }

    private fun iconButton(res: Int, description: String, onClick: () -> Unit): ImageView = ImageView(this).apply {
        setImageResource(res)
        setColorFilter(0xFFF1F8FF.toInt())
        contentDescription = description
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(6), dp(6), dp(6), dp(6))
        isClickable = true
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(0x33FFFFFF)
        }
        setOnClickListener { haptic(); runCatching { onClick() } }
    }

    // ------------------------------------------------------------------ drag physics (title bar)

    /**
     * Drag the whole window by its title bar.
     *
     * Physics: raw screen deltas move the window immediately (no
     * smoothing on the way down — lag feels cheap); on release, a
     * fling is computed from the VelocityTracker and the window
     * glides to the nearest screen edge with a decelerating
     * animator. Everything is clamped to the screen so the window
     * can never be lost off-screen.
     */
    private inner class TitleDragTouchListener(private val root: View) : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0
        private var velocity: VelocityTracker? = null
        private val touchSlop = ViewConfiguration.get(this@OeaFloatingWindowService).scaledTouchSlop

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    val lp = root.layoutParams as? WindowManager.LayoutParams ?: return false
                    startX = lp.x
                    startY = lp.y
                    velocity = VelocityTracker.obtain()
                    velocity?.addMovement(event)
                    dragging = false
                    root.animate().cancel()
                    haptic()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    velocity?.addMovement(event)
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                    }
                    if (dragging) {
                        val lp = root.layoutParams as? WindowManager.LayoutParams ?: return true
                        val bounds = screenBounds(root)
                        lp.x = (startX + dx).toInt().coerceIn(bounds[0], bounds[2])
                        lp.y = (startY + dy).toInt().coerceIn(bounds[1], bounds[3])
                        updateWindow(root, lp)
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    velocity?.addMovement(event)
                    if (dragging) {
                        velocity?.computeCurrentVelocity(1000)
                        val vx = velocity?.xVelocity ?: 0f
                        val vy = velocity?.yVelocity ?: 0f
                        flingWindow(root, vx, vy)
                    } else {
                        // A clean lift: two of these inside the
                        // double-tap window collapse the floating
                        // window, the way the title bar's own
                        // button does.
                        val now = android.os.SystemClock.uptimeMillis()
                        if (now - lastTitleTap < DOUBLE_TAP_MS) {
                            lastTitleTap = 0L
                            haptic()
                            collapse()
                        } else {
                            lastTitleTap = now
                        }
                    }
                    velocity?.recycle()
                    velocity = null
                    dragging = false
                    return true
                }
            }
            return false
        }
    }

    /**
     * A fling carries the window towards the nearest screen edge
     * and settles there with a smooth decelerating glide. Slow
     * releases simply snap to the closest edge instead of travelling.
     */
    private fun flingWindow(root: View, vx: Float, vy: Float) {
        val lp = root.layoutParams as? WindowManager.LayoutParams ?: return
        val bounds = screenBounds(root)

        // Decide the destination: use velocity when it is meaningful,
        // otherwise snap to whichever edge is nearer.
        val targetX = when {
            abs(vx) > FLING_MIN_VELOCITY -> if (vx > 0) bounds[2] else bounds[0]
            else -> if (lp.x < bounds[2] / 2) bounds[0] else bounds[2]
        }
        val targetY = when {
            abs(vy) > FLING_MIN_VELOCITY -> if (vy > 0) bounds[3] else bounds[1]
            else -> if (lp.y < bounds[3] / 2) bounds[1] else bounds[3]
        }

        val startX = lp.x
        val startY = lp.y
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = FLING_DURATION_MS
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { animation ->
                val t = animation.animatedValue as Float
                // Interpolate from the release point to the destination,
                // re-clamping every frame so the glide can never leave
                // the screen.
                lp.x = (startX + (targetX - startX) * t).toInt().coerceIn(bounds[0], bounds[2])
                lp.y = (startY + (targetY - startY) * t).toInt().coerceIn(bounds[1], bounds[3])
                updateWindow(root, lp)
            }
            addListener(object : android.animation.Animator.AnimatorListener {
                override fun onAnimationStart(animation: android.animation.Animator) = Unit
                override fun onAnimationCancel(animation: android.animation.Animator) = Unit
                override fun onAnimationRepeat(animation: android.animation.Animator) = Unit
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    // Persist the resting position for the next show.
                    OeaFloatingWindows.savePosition(this@OeaFloatingWindowService, lp.x, lp.y)
                }
            })
        }
        anim.start()
    }

    // ------------------------------------------------------------------ resize physics (corner handle)

    /**
     * Drag the bottom-right corner to resize the window live.
     *
     * Width and height grow/shrink with the finger, clamped to sane
     * minimums (so the title bar never collapses) and to the screen
     * size (so the box can never exceed the display). Every move is
     * applied with updateViewLayout, which is cheap and keeps the
     * content area reflowing in real time.
     */
    private inner class ResizeTouchListener(private val root: View) : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var startWidth = 0
        private var startHeight = 0
        private var resizing = false
        private val touchSlop = ViewConfiguration.get(this@OeaFloatingWindowService).scaledTouchSlop

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startWidth = root.width
                    startHeight = root.height
                    resizing = false
                    root.animate().cancel()
                    haptic()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!resizing && (abs(dx) >= touchSlop || abs(dy) >= touchSlop)) resizing = true
                    if (!resizing) return true
                    val lp = root.layoutParams as? WindowManager.LayoutParams ?: return true
                    val metrics = resources.displayMetrics
                    val newWidth = (startWidth + dx).toInt()
                        .coerceIn(minWindowWidth(), metrics.widthPixels)
                    val newHeight = (startHeight + dy).toInt()
                        .coerceIn(minWindowHeight(), metrics.heightPixels)
                    if (newWidth != lp.width || newHeight != lp.height) {
                        lp.width = newWidth
                        lp.height = newHeight
                        // Keep the top-left anchored: resizing from the
                        // bottom-right corner must not shift the window.
                        val bounds = screenBounds(root)
                        lp.x = lp.x.coerceIn(bounds[0], bounds[2])
                        lp.y = lp.y.coerceIn(bounds[1], bounds[3])
                        updateWindow(root, lp)
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    windowWidth = root.width
                    windowHeight = root.height
                    OeaFloatingWindows.saveSize(this@OeaFloatingWindowService, windowWidth, windowHeight)
                    if (!resizing) {
                        // Two taps on the corner put the box back to
                        // the size it starts at: 60% × 45% of the
                        // screen, the comfortable default on Go devices.
                        val now = android.os.SystemClock.uptimeMillis()
                        if (now - lastHandleTap < DOUBLE_TAP_MS) {
                            lastHandleTap = 0L
                            haptic()
                            resetSize()
                        } else {
                            lastHandleTap = now
                        }
                    }
                    resizing = false
                    return true
                }
            }
            return false
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun updateWindow(root: View, params: WindowManager.LayoutParams) {
        windowParams = params
        runCatching {
            (getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.updateViewLayout(root, params)
        }.onFailure { android.util.Log.w("OeaFloatingWindow", "updateViewLayout failed", it) }
    }

    /** Left, top, maxLeft, maxTop for the window, in pixels. */
    private fun screenBounds(root: View): IntArray {
        val metrics = resources.displayMetrics
        val width = root.width.takeIf { it > 0 } ?: windowWidth
        val height = root.height.takeIf { it > 0 } ?: windowHeight
        return intArrayOf(
            0,
            0,
            (metrics.widthPixels - width).coerceAtLeast(0),
            (metrics.heightPixels - height).coerceAtLeast(0)
        )
    }

    private fun minWindowWidth(): Int = dp(240)
    private fun minWindowHeight(): Int = dp(200)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    // ------------------------------------------------------------------ gestures

    /**
     * Back to the starting size: 60% of the screen wide, 45% tall.
     * Reached by double-tapping the resize handle; the window is
     * re-clamped to the screen and the new size is remembered.
     */
    private fun resetSize() {
        val root = windowRoot ?: return
        val lp = root.layoutParams as? WindowManager.LayoutParams ?: return
        val metrics = resources.displayMetrics
        windowWidth = (metrics.widthPixels * 0.60f).toInt()
        windowHeight = (metrics.heightPixels * 0.45f).toInt()
        lp.width = windowWidth
        lp.height = windowHeight
        val bounds = screenBounds(root)
        lp.x = lp.x.coerceIn(bounds[0], bounds[2])
        lp.y = lp.y.coerceIn(bounds[1], bounds[3])
        updateWindow(root, lp)
        OeaFloatingWindows.saveSize(this, windowWidth, windowHeight)
    }

    /** A short tick, so gestures and buttons confirm themselves. */
    private fun haptic() {
        runCatching {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createOneShot(8, 40))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(12)
            }
        }
    }

    // ------------------------------------------------------------------ public API

    /** Collapse (hide) the floating window; the service stays alive. */
    fun collapse() = hideWindow(animate = true)

    /** Expand (show) the floating window. */
    fun expand() = showWindow(animate = true)

    /**
     * Close the window and switch the feature off, so it stays
     * closed until the user turns it back on in OEA Settings.
     * The accessibility service itself stays enabled — that is
     * Android's switch, not OEA's.
     */
    fun closeWindow() {
        OeaFloatingWindows.setEnabled(this, false)
        collapse()
    }

    fun isWindowShowing(): Boolean = windowRoot?.parent != null

    companion object {
        private const val FLING_MIN_VELOCITY = 240f // dp/sec
        private const val FLING_DURATION_MS = 220L

        /** Two taps this far apart count as a double-tap. */
        private const val DOUBLE_TAP_MS = 300L

        /**
         * The bound service, or null while Android has not bound it
         * (feature off in Accessibility, or the process was killed).
         * OEA settings/panels read this instead of binding themselves.
         */
        @Volatile private var instance: OeaFloatingWindowService? = null
        internal fun instance(): OeaFloatingWindowService? = instance
    }
}
