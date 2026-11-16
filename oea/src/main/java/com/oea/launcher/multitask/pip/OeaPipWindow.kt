package com.oea.launcher.multitask.pip

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.oea.launcher.R
import com.oea.launcher.multitask.OeaFloatingWindowService
import kotlin.math.abs

/**
 * OEA's own picture-in-picture window.
 *
 * Android's PiP is a *system* window: the platform takes an activity, moves it
 * into a special root task and draws it in a corner. That is why
 * `PackageManager.FEATURE_PICTURE_IN_PICTURE` is a device feature and not an
 * app setting — and why a phone without it has no PiP at all, for any app,
 * ever. Nothing OEA does inside its own process can conjure that task.
 *
 * What OEA can do is exactly what its multitasking system already does: draw
 * its own floating window above everything else. So PiP here is a floating
 * window from the same family as OEA's floating workspace and live windows —
 * same window type, same drag/fling-to-edge physics, same corner resize, same
 * rule that "close" really closes.
 *
 * The difference from a live window is the pixels. A live window has to
 * project another app through a virtual display because OEA cannot otherwise
 * get at its picture. VIP is OEA's own player: OEA already owns the decoder,
 * so the video goes straight into a SurfaceView held by this window — no
 * virtual display, no trampoline, no launch ladder, no per-frame copy. Fewer
 * moving parts, and it works on a phone that refuses secondary displays.
 *
 * The window type is a deliberate ladder:
 *  1. [WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY] when OEA's
 *     Floating Window service is bound — the type every other OEA floating
 *     surface uses, and the most reliable one for a window that outlives an
 *     activity;
 *  2. TYPE_APPLICATION_OVERLAY when the user has granted "display over other
 *     apps", so the feature still works without the accessibility service;
 *  3. otherwise we say so, rather than failing silently.
 */
@SuppressLint("ClickableViewAccessibility")
class OeaPipWindow(
    private val context: Context,
    private val controls: Controls
) {

    interface Controls {
        /** The video surface is ready; attach the decoder to it. */
        fun onSurfaceCreated(holder: SurfaceHolder)

        /** The video surface went away; stop feeding it. */
        fun onSurfaceDestroyed()

        fun onPlayPause()

        /** @return true when there was somewhere to go. */
        fun onPrevious(): Boolean

        /** @return true when there was somewhere to go. */
        fun onNext(): Boolean

        /** Reopen the clip in the full player. */
        fun onExpand()

        /** Close the window and stop playback. */
        fun onClose()

        fun isPlaying(): Boolean
    }

    private val wm: WindowManager? get() = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density + 0.5f).toInt()
    private val handler = Handler(Looper.getMainLooper())

    private var root: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var surfaceView: SurfaceView? = null
    private var chromeTop: View? = null
    private var chromeBottom: View? = null
    private var titleView: TextView? = null
    private var playButton: ImageView? = null
    private var prevButton: ImageView? = null
    private var nextButton: ImageView? = null
    private var handle: View? = null

    @Volatile var showing = false
        private set

    /** Which overlay type this phone would actually accept, and why. */
    fun availability(): Availability {
        if (OeaFloatingWindowService.instance() != null) {
            return Availability.OK(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, "accessibility")
        }
        if (Build.VERSION.SDK_INT < 26) {
            return Availability.NO("Android 8.0 or newer is needed for an OEA picture-in-picture window.")
        }
        return if (Settings.canDrawOverlays(context)) {
            Availability.OK(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, "draw-over-apps")
        } else {
            Availability.NO(
                "OEA needs one window permission to draw a picture-in-picture window: turn on " +
                    "OEA Floating Window in Android Accessibility (recommended), or grant OEA " +
                    "\"display over other apps\"."
            )
        }
    }

    class Availability private constructor(
        val ok: Boolean,
        val type: Int,
        val route: String,
        val reason: String
    ) {
        companion object {
            fun OK(type: Int, route: String) = Availability(true, type, route, "")
            fun NO(reason: String) = Availability(false, 0, "", reason)
        }
    }

    /** Shows the window. Returns null on success, or the reason it could not be shown. */
    fun show(title: String, width: Int, height: Int, x: Int, y: Int): String? {
        if (showing) return null
        val available = availability()
        if (!available.ok) return available.reason
        val manager = wm ?: return "This phone's window manager is unavailable."

        val metrics = context.resources.displayMetrics
        val w = width.coerceIn(dp(160), metrics.widthPixels)
        val h = height.coerceIn(dp(90), metrics.heightPixels)

        val container = buildContent(title)
        val p = WindowManager.LayoutParams(
            w, h,
            available.type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x.coerceIn(0, (metrics.widthPixels - w).coerceAtLeast(0))
            this.y = y.coerceIn(0, (metrics.heightPixels - h).coerceAtLeast(0))
        }
        return try {
            manager.addView(container, p)
            root = container
            params = p
            showing = true
            scheduleChromeHide()
            null
        } catch (t: Throwable) {
            "OEA could not open a picture-in-picture window here: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    fun dismiss() {
        showing = false
        handler.removeCallbacks(hideChrome)
        val r = root ?: return
        try {
            wm?.removeView(r)
        } catch (_: Throwable) {
        }
        root = null
        params = null
        surfaceView = null
        chromeTop = null
        chromeBottom = null
        titleView = null
        playButton = null
        prevButton = null
        nextButton = null
        handle = null
    }

    fun setTitle(text: String) {
        titleView?.post { titleView?.text = text }
    }

    fun setPlaying(playing: Boolean) {
        playButton?.post {
            playButton?.setImageResource(if (playing) R.drawable.oic_pause else R.drawable.oic_play_arrow)
            playButton?.contentDescription = if (playing) "Pause" else "Play"
        }
    }

    /** Where the window currently sits, for callers that persist it. */
    fun placement(): Triple<Int, Int, Int>? {
        val p = params ?: return null
        return Triple(p.x, p.y, p.width)
    }

    fun setQueueControls(hasPrevious: Boolean, hasNext: Boolean) {
        prevButton?.post { prevButton?.visibility = if (hasPrevious) View.VISIBLE else View.GONE }
        nextButton?.post { nextButton?.visibility = if (hasNext) View.VISIBLE else View.GONE }
    }

    // ------------------------------------------------------------------ layout

    private fun buildContent(title: String): FrameLayout {
        val layout = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(0xF20A0E16.toInt())
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), 0x5555BFFF.toInt())
            }
            clipChildren = true
            clipToOutline = true
        }

        // The video surface sits at the bottom of the stack; every other view
        // in this window draws on top of it, which is what a normal (not
        // z-ordered) SurfaceView gives us for free.
        val video = SurfaceView(context).apply {
            holder.setFormat(PixelFormat.TRANSPARENT)
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    runCatching { controls.onSurfaceCreated(holder) }
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    runCatching { controls.onSurfaceDestroyed() }
                }
            })
        }
        layout.addView(video, FrameLayout.LayoutParams(-1, -1))
        surfaceView = video

        // ---- top chrome: title, expand, close ---------------------------
        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(4), dp(4))
            background = GradientDrawable().apply {
                setColor(0xAA101B2E.toInt())
                cornerRadius = dp(12).toFloat()
            }
        }
        val label = TextView(context).apply {
            text = title
            setTextColor(0xFFF1F8FF.toInt())
            textSize = 11f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(4), 0, dp(4), 0)
        }
        top.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
        titleView = label
        top.addView(iconButton(R.drawable.oic_open_in_full, "Open in VIP") { controls.onExpand() })
        top.addView(iconButton(R.drawable.oic_close, "Close picture in picture") { controls.onClose() })
        layout.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
            leftMargin = dp(4); rightMargin = dp(4); topMargin = dp(4)
        })
        chromeTop = top

        // ---- bottom chrome: transport -----------------------------------
        val bottom = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(4), dp(6), dp(4))
            background = GradientDrawable().apply {
                setColor(0xAA101B2E.toInt())
                cornerRadius = dp(12).toFloat()
            }
        }
        prevButton = iconButton(R.drawable.oic_skip_previous, "Previous clip") { controls.onPrevious() }
        playButton = iconButton(R.drawable.oic_play_arrow, "Play") { controls.onPlayPause() }
        nextButton = iconButton(R.drawable.oic_skip_next, "Next clip") { controls.onNext() }
        bottom.addView(prevButton, LinearLayout.LayoutParams(dp(34), dp(34)))
        bottom.addView(playButton, LinearLayout.LayoutParams(dp(38), dp(38)))
        bottom.addView(nextButton, LinearLayout.LayoutParams(dp(34), dp(34)))
        layout.addView(bottom, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(6)
        })
        chromeBottom = bottom

        // ---- resize handle ----------------------------------------------
        val corner = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCC2357FF.toInt())
                setStroke(dp(1), 0xAA4D74FF.toInt())
            }
            contentDescription = "Resize the picture-in-picture window"
        }
        layout.addView(corner, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.END or Gravity.BOTTOM))
        handle = corner
        corner.setOnTouchListener(ResizeListener(layout))

        layout.setOnTouchListener(WindowTouchListener(layout))
        return layout
    }

    private fun iconButton(res: Int, description: String, onClick: () -> Unit): ImageView =
        ImageView(context).apply {
            setImageResource(res)
            setColorFilter(0xFFF1F8FF.toInt())
            contentDescription = description
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(5), dp(5), dp(5), dp(5))
            isClickable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x33FFFFFF)
            }
            setOnClickListener { runCatching { onClick() }; scheduleChromeHide() }
        }

    // ------------------------------------------------------------------ chrome

    private val hideChrome = Runnable { setChrome(false) }

    private fun scheduleChromeHide() {
        handler.removeCallbacks(hideChrome)
        handler.postDelayed(hideChrome, CHROME_HIDE_MS)
    }

    private fun setChrome(show: Boolean) {
        handler.removeCallbacks(hideChrome)
        chromeTop?.visibility = if (show) View.VISIBLE else View.GONE
        chromeBottom?.visibility = if (show) View.VISIBLE else View.GONE
        handle?.visibility = if (show) View.VISIBLE else View.GONE
        if (show) handler.postDelayed(hideChrome, CHROME_HIDE_MS)
    }

    // ----------------------------------------------------------------- physics

    /**
     * Drag anywhere that is not a button, tap to bring the controls back,
     * fling to the nearest edge — the same physics language as OEA's floating
     * workspace and live windows.
     */
    private inner class WindowTouchListener(private val layout: View) : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var velocity: VelocityTracker? = null
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val visible = chromeTop?.visibility == View.VISIBLE
                setChrome(!visible)
                return true
            }
        })

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            detector.onTouchEvent(event)
            velocity?.addMovement(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    val p = params ?: return true
                    startX = p.x
                    startY = p.y
                    dragging = false
                    velocity?.recycle()
                    velocity = VelocityTracker.obtain()
                    velocity?.addMovement(event)
                    scheduleChromeHide()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && (abs(dx) >= slop || abs(dy) >= slop)) dragging = true
                    if (!dragging) return true
                    val p = params ?: return true
                    val metrics = context.resources.displayMetrics
                    p.x = (startX + dx).toInt().coerceIn(0, (metrics.widthPixels - layout.width).coerceAtLeast(0))
                    p.y = (startY + dy).toInt().coerceIn(0, (metrics.heightPixels - layout.height).coerceAtLeast(0))
                    updateLayout(layout)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    velocity?.computeCurrentVelocity(1000)
                    val vx = velocity?.xVelocity ?: 0f
                    val vy = velocity?.yVelocity ?: 0f
                    velocity?.recycle()
                    velocity = null
                    if (dragging && (abs(vx) > 2400f || abs(vy) > 2400f)) flingToEdge(layout, vx, vy)
                    dragging = false
                    if (event.actionMasked == MotionEvent.ACTION_UP) scheduleChromeHide()
                    return true
                }
            }
            return false
        }
    }

    private fun flingToEdge(layout: View, vx: Float, vy: Float) {
        val p = params ?: return
        val metrics = context.resources.displayMetrics
        val maxX = (metrics.widthPixels - layout.width).coerceAtLeast(0)
        val maxY = (metrics.heightPixels - layout.height).coerceAtLeast(0)
        val targetX = if (vx >= 0) maxX else 0
        val targetY = if (abs(vy) > 2400f) (if (vy > 0) maxY else 0) else p.y
        val startX = p.x
        val startY = p.y
        val animator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200L
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                p.x = (startX + (targetX - startX) * t).toInt().coerceIn(0, maxX)
                p.y = (startY + (targetY - startY) * t).toInt().coerceIn(0, maxY)
                updateLayout(layout)
            }
        }
        animator.start()
    }

    private inner class ResizeListener(private val layout: View) : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startW = 0
        private var startH = 0
        private var resizing = false
        private val slop = ViewConfiguration.get(context).scaledTouchSlop

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startW = layout.width
                    startH = layout.height
                    resizing = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!resizing && (abs(dx) >= slop || abs(dy) >= slop)) resizing = true
                    if (!resizing) return true
                    val p = params ?: return true
                    val metrics = context.resources.displayMetrics
                    p.width = (startW + dx).toInt().coerceIn(dp(160), metrics.widthPixels)
                    p.height = (startH + dy).toInt().coerceIn(dp(90), metrics.heightPixels)
                    updateLayout(layout)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    resizing = false
                    scheduleChromeHide()
                    return true
                }
            }
            return false
        }
    }

    private fun updateLayout(layout: View) {
        val p = params ?: return
        try {
            wm?.updateViewLayout(layout, p)
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val CHROME_HIDE_MS = 2_500L
        private const val PREFS = "oea_pip_window"
        private const val KEY_X = "x"
        private const val KEY_Y = "y"
        private const val KEY_W = "w"

        /** Remembers where the user last left the window, so it comes back there. */
        fun remember(context: Context, x: Int, y: Int, width: Int) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_X, x).putInt(KEY_Y, y).putInt(KEY_W, width).apply()
        }

        fun restore(context: Context, defaultWidth: Int): Triple<Int, Int, Int> {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val metrics = context.resources.displayMetrics
            val w = p.getInt(KEY_W, defaultWidth).takeIf { it > 0 } ?: defaultWidth
            val width = w.coerceIn(160, metrics.widthPixels)
            val height = (width * 9f / 16f).toInt().coerceIn(90, metrics.heightPixels)
            val x = p.getInt(KEY_X, Int.MIN_VALUE).let {
                if (it == Int.MIN_VALUE) metrics.widthPixels - width - (12 * metrics.density).toInt()
                else it.coerceIn(0, (metrics.widthPixels - width).coerceAtLeast(0))
            }
            val y = p.getInt(KEY_Y, Int.MIN_VALUE).let {
                if (it == Int.MIN_VALUE) (metrics.heightPixels * 0.12f).toInt()
                else it.coerceIn(0, (metrics.heightPixels - height).coerceAtLeast(0))
            }
            return Triple(x, y, width)
        }

        /** Default window width for a fresh window: a little under half the screen. */
        fun defaultWidth(context: Context): Int =
            (context.resources.displayMetrics.widthPixels * 0.46f).toInt()
    }
}
