package com.oea.launcher.multitask.projection

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.TextureView
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.oea.launcher.R
import kotlin.math.abs

/**
 * The floating window chrome for one live app projection.
 *
 * Layout (vertical):
 *   [ TITLE BAR ]  app icon + label + status badge · drag to move · back / keyboard / close
 *   [ CONTENT ]    the app's live frames + touch translation layer
 *   [ TEXT STRIP ] EditText + Set + Paste (shown while typing); never steals
 *                  focus from the user's app unless the user opens it
 *   (corner)       resize handle
 *
 * The window is a TYPE_ACCESSIBILITY_OVERLAY owned by OEA's enabled
 * accessibility service, exactly like the existing OEA floating workspace —
 * so it needs no new permission. It is NOT_FOCUSABLE while idle, so the
 * user's foreground app keeps the keyboard and hardware keys; focus is
 * borrowed only while the text strip is open.
 *
 * Touch translation: taps/drags on the content are mapped from window
 * pixels to virtual-display pixels (letterbox-aware) and handed to
 * [callbacks] which performs node actions on the target application.
 */
@SuppressLint("ClickableViewAccessibility")
class OeaLiveWindowOverlay(
    private val service: Context,
    private val appLabel: String,
    private val appIcon: android.graphics.drawable.Drawable?,
    private val displayWidth: Int,
    private val displayHeight: Int,
    private val callbacks: Callbacks
) {

    interface Callbacks {
        /** Tap at virtual-display pixel. */
        fun onTap(x: Int, y: Int)

        /** Long-press at virtual-display pixel. */
        fun onLongPress(x: Int, y: Int)

        /** Scroll gesture starting at (x, y); forward = down. */
        fun onScroll(x: Int, y: Int, forward: Boolean)

        /** Text strip: set text in the target's focused field. */
        fun onSetText(text: String)

        /** Text strip: paste the clipboard into the target's focused field. */
        fun onPaste()

        /** In-app back button. */
        fun onBack()

        /** Keyboard strip wants focus (true) or is done (false). */
        fun onTextStripFocus(want: Boolean)

        /** The presenter surface died (window hidden/resized); frames must
         *  be re-routed through the ImageReader so liveness tracking stays
         *  honest. */
        fun onSurfaceLost()

        /** Close button. */
        fun onClose()

        /** Minimise button. */
        fun onMinimise()
    }

    // ---------------------------------------------------------------- views

    var root: FrameLayout? = null
        private set
    private var params: WindowManager.LayoutParams? = null
    private var textureView: TextureView? = null
    private var imageView: ImageView? = null
    private var touchLayer: View? = null
    private var textStrip: LinearLayout? = null
    private var textInput: EditText? = null
    private var statusBadge: TextView? = null
    private var statusLine: TextView? = null
    private var titleBar: View? = null
    private var resizeHandle: View? = null

    private val wm: WindowManager?
        get() = service.getSystemService(Context.WINDOW_SERVICE) as? WindowManager

    private val dp: Float = service.resources.displayMetrics.density
    private fun dp(v: Int) = (v * dp + 0.5f).toInt()

    var windowWidth = 0
        private set
    var windowHeight = 0
        private set

    /** Listener for the TextureView's SurfaceTexture, set before attach. */
    var onSurfaceTextureReady: ((android.graphics.SurfaceTexture) -> Unit)? = null

    /** Called once per rendered frame in texture mode (liveness pulse). */
    var onFramePulse: (() -> Unit)? = null

    // ---------------------------------------------------------------- attach

    fun attach(startX: Int, startY: Int) {
        if (root != null) return
        val metrics = service.resources.displayMetrics
        windowWidth = (metrics.widthPixels * 0.72f).toInt().coerceAtLeast(dp(260))
        windowHeight = (metrics.heightPixels * 0.58f).toInt().coerceAtLeast(dp(320))

        val container = buildContent()
        val p = WindowManager.LayoutParams(
            windowWidth,
            windowHeight,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = startX.coerceIn(0, (metrics.widthPixels - windowWidth).coerceAtLeast(0))
            y = startY.coerceIn(0, (metrics.heightPixels - windowHeight).coerceAtLeast(0))
            if (Build.VERSION.SDK_INT >= 24) {
                windowAnimations = android.R.style.Animation_Dialog
            }
        }
        root = container
        params = p
        val windowManager = wm ?: return
        try {
            windowManager.addView(container, p)
        } catch (t: Throwable) {
            root = null
            params = null
            throw t
        }
    }

    fun detach() {
        val r = root ?: return
        try {
            wm?.removeView(r)
        } catch (_: Throwable) {
        }
        root = null
        params = null
        textureView = null
        imageView = null
        touchLayer = null
        textStrip = null
        textInput = null
        statusBadge = null
        statusLine = null
        titleBar = null
        resizeHandle = null
    }

    /** Shows/hides the window without destroying it (minimise/restore). */
    fun setVisible(visible: Boolean) {
        root?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun isAttached(): Boolean = root?.parent != null

    // ---------------------------------------------------------------- frames

    /** Reader-mode presenter: draw one bitmap frame. */
    fun showFrame(bitmap: android.graphics.Bitmap) {
        val iv = imageView ?: return
        iv.post { iv.setImageBitmap(bitmap) }
    }

    /** Switch the visible presenter between texture and reader mode. */
    fun setPresenterMode(mode: OeaFrameMode) {
        val tv = textureView ?: return
        val iv = imageView ?: return
        tv.post {
            when (mode) {
                OeaFrameMode.TEXTURE -> {
                    tv.visibility = View.VISIBLE
                    iv.visibility = View.GONE
                }
                OeaFrameMode.READER -> {
                    tv.visibility = View.GONE
                    iv.visibility = View.VISIBLE
                }
                OeaFrameMode.NONE -> {
                    tv.visibility = View.GONE
                    iv.visibility = View.VISIBLE
                }
            }
        }
    }

    // ---------------------------------------------------------------- status

    /** Big status word shown in the title bar (LIVE / VIEW-ONLY / …). */
    fun setStatusBadge(text: String, color: Int) {
        statusBadge?.post {
            statusBadge?.text = text
            statusBadge?.setTextColor(color)
        }
    }

    /** One-line feedback area under the content ("Typed into …", errors). */
    fun setStatusLine(text: String) {
        statusLine?.post {
            statusLine?.text = text
        }
    }

    /** Show or hide the text strip, borrowing window focus while shown. */
    fun openTextStrip(open: Boolean) {
        val strip = textStrip ?: return
        strip.post {
            strip.visibility = if (open) View.VISIBLE else View.GONE
            if (open) {
                makeFocusable(true)
                textInput?.requestFocus()
                textInput?.let { field ->
                    val imm = service.getSystemService(Context.INPUT_METHOD_SERVICE)
                        as? android.view.inputmethod.InputMethodManager
                    imm?.showSoftInput(field, 0)
                }
            } else {
                textInput?.clearFocus()
                makeFocusable(false)
            }
        }
    }

    private fun makeFocusable(focusable: Boolean) {
        val r = root ?: return
        val p = params ?: return
        try {
            p.flags = if (focusable) {
                p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            } else {
                p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            }
            p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            wm?.updateViewLayout(r, p)
        } catch (_: Throwable) {
        }
    }

    // ---------------------------------------------------------------- content

    private fun buildContent(): FrameLayout {
        val rootLayout = FrameLayout(service).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xF2131C2E.toInt(), 0xEE0C1220.toInt())
            ).apply {
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), 0x8855BFFF.toInt())
            }
            clipChildren = true
            clipToOutline = true
        }

        // ---- title bar ---------------------------------------------------
        val title = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(4), dp(6), dp(4))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFF182238.toInt(), 0x00182238.toInt())
            )
        }
        if (appIcon != null) {
            val icon = android.widget.ImageView(service).apply {
                setImageDrawable(appIcon)
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            }
            title.addView(icon, LinearLayout.LayoutParams(dp(22), dp(22)))
        }
        title.addView(TextView(service).apply {
            text = appLabel
            setTextColor(0xFFF1F8FF.toInt())
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(6), 0, dp(6), 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val badge = TextView(service).apply {
            text = "…"
            setTextColor(0xFF9CCEFF.toInt())
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(6), 0, dp(6), 0)
        }
        title.addView(badge, LinearLayout.LayoutParams(-2, -2))
        statusBadge = badge
        fun addTitleButton(button: android.widget.ImageView) {
            title.addView(button, LinearLayout.LayoutParams(dp(28), dp(28)))
        }
        addTitleButton(iconButton(R.drawable.oic_arrow_back, "Back inside the projected app") { callbacks.onBack() })
        addTitleButton(
            iconButton(R.drawable.oic_text_fields, "Open the text strip to type into the projected app") {
                val strip = textStrip
                if (strip != null) {
                    val open = strip.visibility != View.VISIBLE
                    openTextStrip(open)
                }
            }
        )
        addTitleButton(iconButton(R.drawable.oic_expand_more, "Minimise this live window") { callbacks.onMinimise() })
        addTitleButton(iconButton(R.drawable.oic_close, "Close this live window and stop the projection") { callbacks.onClose() })
        rootLayout.addView(title, FrameLayout.LayoutParams(-1, dp(38), Gravity.TOP))
        titleBar = title

        // ---- content: presenters + touch layer ---------------------------
        val content = FrameLayout(service).apply {
            setBackgroundColor(0xFF050A14.toInt())
        }
        val texture = TextureView(service).apply {
            visibility = View.VISIBLE
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(
                    st: android.graphics.SurfaceTexture, width: Int, height: Int
                ) {
                    onSurfaceTextureReady?.invoke(st)
                }

                override fun onSurfaceTextureSizeChanged(
                    st: android.graphics.SurfaceTexture, width: Int, height: Int
                ) = Unit

                override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                    // The session must point the display at the ImageReader
                    // again, or frames silently stop and the window would
                    // look frozen while claiming to be live.
                    callbacks.onSurfaceLost()
                    return true
                }

                override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {
                    // Cheap per-frame pulse for liveness tracking.
                    onFramePulse?.invoke()
                }
            }
        }
        content.addView(
            texture,
            FrameLayout.LayoutParams(-1, -1)
        )
        textureView = texture

        val image = android.widget.ImageView(service).apply {
            visibility = View.GONE
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
        }
        content.addView(image, FrameLayout.LayoutParams(-1, -1))
        imageView = image

        val touch = View(service)
        content.addView(touch, FrameLayout.LayoutParams(-1, -1))
        touchLayer = touch
        wireTouch(touch)

        // Keep the projected picture aspect-correct (letterboxed) inside the
        // window, exactly like the coordinate mapping assumes. Without this
        // the TextureView would stretch the app to the window's aspect.
        content.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val cw = right - left
            val ch = bottom - top
            if (cw > 0 && ch > 0 && displayWidth > 0 && displayHeight > 0) {
                val scale = minOf(cw.toFloat() / displayWidth, ch.toFloat() / displayHeight)
                texture.scaleX = (displayWidth * scale) / cw
                texture.scaleY = (displayHeight * scale) / ch
            }
        }

        rootLayout.addView(content, FrameLayout.LayoutParams(-1, -1, Gravity.TOP).apply {
            topMargin = dp(38)
            leftMargin = dp(6)
            rightMargin = dp(6)
            bottomMargin = dp(2)
        })

        // ---- status line -------------------------------------------------
        val line = TextView(service).apply {
            text = "Starting…"
            setTextColor(0xFF9FB4CC.toInt())
            textSize = 10f
            maxLines = 2
            setPadding(dp(10), dp(2), dp(10), dp(2))
        }
        rootLayout.addView(
            line,
            FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = dp(4) }
        )
        statusLine = line

        // ---- text strip --------------------------------------------------
        val strip = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply {
                setColor(0xCC101B2E.toInt())
                cornerRadius = dp(12).toFloat()
            }
        }
        val input = EditText(service).apply {
            hint = "Type here, then tap Set"
            setTextColor(0xFFF1F8FF.toInt())
            setHintTextColor(0x809CCEFF.toInt())
            textSize = 12f
            maxLines = 1
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    callbacks.onSetText(text.toString())
                    true
                } else false
            }
        }
        textInput = input
        strip.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        strip.addView(smallButton("Set") { callbacks.onSetText(input.text.toString()) })
        strip.addView(smallButton("Paste") { callbacks.onPaste() })
        strip.addView(smallButton("✕") { openTextStrip(false) })
        rootLayout.addView(
            strip,
            FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = dp(20) }
        )
        textStrip = strip

        // ---- resize handle -------------------------------------------------
        val handle = View(service).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF2357FF.toInt(), 0xFF0B1735.toInt())
            ).apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(1), 0xAA4D74FF.toInt())
            }
            contentDescription = "Resize handle for the live window."
        }
        rootLayout.addView(
            handle,
            FrameLayout.LayoutParams(dp(34), dp(34), Gravity.END or Gravity.BOTTOM)
        )
        resizeHandle = handle

        // Physics.
        title.setOnTouchListener(DragTouchListener(rootLayout))
        handle.setOnTouchListener(ResizeListener(rootLayout))

        return rootLayout
    }

    // ---------------------------------------------------------------- input

    /** Maps overlay-content pixels to virtual-display pixels (letterbox-aware). */
    fun mapToDisplay(viewX: Float, viewY: Float): Pair<Float, Float> {
        val w = touchLayer?.width?.toFloat() ?: 0f
        val h = touchLayer?.height?.toFloat() ?: 0f
        if (w <= 0f || h <= 0f || displayWidth <= 0 || displayHeight <= 0) return 0f to 0f
        val scale = minOf(w / displayWidth, h / displayHeight)
        val contentW = displayWidth * scale
        val contentH = displayHeight * scale
        val offX = (w - contentW) / 2f
        val offY = (h - contentH) / 2f
        val x = ((viewX - offX) / scale).coerceIn(0f, (displayWidth - 1).toFloat())
        val y = ((viewY - offY) / scale).coerceIn(0f, (displayHeight - 1).toFloat())
        return x to y
    }

    private fun wireTouch(touch: View) {
        val detector = GestureDetector(service, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val (x, y) = mapToDisplay(e.x, e.y)
                callbacks.onTap(x.toInt(), y.toInt())
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                val (x, y) = mapToDisplay(e.x, e.y)
                callbacks.onLongPress(x.toInt(), y.toInt())
            }

            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float
            ): Boolean {
                // Finger moving up (negative dy) scrolls the list forward.
                if (abs(dy) < dp(8)) return true
                val start = e1 ?: e2
                val (x, y) = mapToDisplay(start.x, start.y)
                callbacks.onScroll(x.toInt(), y.toInt(), dy < 0)
                return true
            }
        })
        touch.setOnTouchListener { v, event ->
            detector.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) v.performClick()
            true
        }
    }

    private fun iconButton(res: Int, description: String, onClick: () -> Unit): android.widget.ImageView {
        return android.widget.ImageView(service).apply {
            setImageResource(res)
            setColorFilter(0xFFF1F8FF.toInt())
            contentDescription = description
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(4), dp(4), dp(4), dp(4))
            isClickable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x33FFFFFF)
            }
            setOnClickListener { runCatching { onClick() } }
        }
    }

    private fun smallButton(label: String, onClick: () -> Unit): TextView {
        return TextView(service).apply {
            text = label
            setTextColor(0xFFF1F8FF.toInt())
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(6), dp(10), dp(6))
            isClickable = true
            background = GradientDrawable().apply {
                setColor(0x2E4D74FF)
                cornerRadius = dp(10).toFloat()
            }
            setOnClickListener { runCatching { onClick() } }
        }
    }

    // ---------------------------------------------------------------- physics

    /**
     * Drag the window by its title bar, edge-clamped, with fling-to-edge —
     * the same physics language as OEA's floating workspace.
     */
    private inner class DragTouchListener(private val rootLayout: View) : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startLpX = 0
        private var startLpY = 0
        private var dragging = false
        private var velocity: VelocityTracker? = null
        private val slop = ViewConfiguration.get(service).scaledTouchSlop

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            velocity?.let { it.addMovement(event) }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    val p = params ?: return true
                    startLpX = p.x
                    startLpY = p.y
                    dragging = false
                    velocity?.recycle()
                    velocity = VelocityTracker.obtain()
                    velocity?.addMovement(event)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && (abs(dx) >= slop || abs(dy) >= slop)) dragging = true
                    if (!dragging) return true
                    val p = params ?: return true
                    val metrics = service.resources.displayMetrics
                    p.x = (startLpX + dx).toInt()
                        .coerceIn(0, (metrics.widthPixels - rootLayout.width).coerceAtLeast(0))
                    p.y = (startLpY + dy).toInt()
                        .coerceIn(0, (metrics.heightPixels - rootLayout.height).coerceAtLeast(0))
                    updateLayout(rootLayout)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    velocity?.computeCurrentVelocity(1000)
                    val vx = velocity?.xVelocity ?: 0f
                    val vy = velocity?.yVelocity ?: 0f
                    velocity?.recycle()
                    velocity = null
                    if (dragging && (abs(vx) > 2400f || abs(vy) > 2400f)) {
                        flingToEdge(rootLayout, vx, vy)
                    }
                    dragging = false
                    return true
                }
            }
            return false
        }
    }

    private fun flingToEdge(rootLayout: View, vx: Float, vy: Float) {
        val p = params ?: return
        val metrics = service.resources.displayMetrics
        val maxX = (metrics.widthPixels - rootLayout.width).coerceAtLeast(0)
        val targetX = if (vx >= 0) maxX else 0
        val startY = p.y
        val targetY = if (abs(vy) > 2400f) {
            if (vy > 0) (metrics.heightPixels - rootLayout.height).coerceAtLeast(0) else 0
        } else startY
        val startX = p.x
        val start = SystemClock.uptimeMillis()
        val duration = 200L
        val animator = android.animation.ValueAnimator.ofFloat(0f, 1f)
        animator.duration = duration
        animator.interpolator = android.view.animation.DecelerateInterpolator(1.5f)
        animator.addUpdateListener { anim ->
            val t = anim.animatedValue as Float
            p.x = (startX + (targetX - startX) * t).toInt().coerceIn(0, maxX)
            p.y = if (targetY == startY) startY
            else (startY + (targetY - startY) * t).toInt()
                .coerceIn(0, (metrics.heightPixels - rootLayout.height).coerceAtLeast(0))
            updateLayout(rootLayout)
        }
        animator.start()
    }

    /** Resize from the bottom-right corner, clamped to sane minimums. */
    private inner class ResizeListener(private val rootLayout: View) : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startW = 0
        private var startH = 0
        private var resizing = false
        private val slop = ViewConfiguration.get(service).scaledTouchSlop

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startW = rootLayout.width
                    startH = rootLayout.height
                    resizing = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!resizing && (abs(dx) >= slop || abs(dy) >= slop)) resizing = true
                    if (!resizing) return true
                    val p = params ?: return true
                    val metrics = service.resources.displayMetrics
                    p.width = (startW + dx).toInt()
                        .coerceIn(dp(240), metrics.widthPixels)
                    p.height = (startH + dy).toInt()
                        .coerceIn(dp(240), metrics.heightPixels)
                    windowWidth = p.width
                    windowHeight = p.height
                    updateLayout(rootLayout)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    resizing = false
                    return true
                }
            }
            return false
        }
    }

    private fun updateLayout(rootLayout: View) {
        val p = params ?: return
        try {
            wm?.updateViewLayout(rootLayout, p)
        } catch (_: Throwable) {
        }
    }

    /** Content slot bounds, for callers that need to place extra views. */
    fun contentBounds(): android.graphics.Rect? {
        val layer = touchLayer ?: return null
        return android.graphics.Rect(0, 0, layer.width, layer.height)
    }
}
