package com.oea.launcher.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.ImageView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The controls an app needs that a settings kit does not have: a real slider, a
 * scrubber with a drag bubble, an icon button that can light up, a skeleton that
 * shimmers while data loads, and a pinch-zoom picture view.
 *
 * These are drawn by hand instead of styling framework widgets for one reason: a
 * stock SeekBar inside a black full-screen player looks exactly like a stock
 * SeekBar inside a black full-screen player. Everything here paints from the
 * [OeaPalette], so an OEA app looks like OEA even where OEA's theme cannot reach.
 */

/** A continuous or stepped slider with a filled track, a thumb and a drag bubble. */
class OeaSlider(context: Context) : View(context) {

    private val density = context.resources.displayMetrics.density

    var trackColor: Int = blendGray()
    var fillColor: Int = 0xFF8AB4F8.toInt()
    var thumbColor: Int = Color.WHITE
    var bubbleTextColor: Int = Color.WHITE
    var minValue: Float = 0f
    var maxValue: Float = 100f

    /** When > 0 the value snaps to this many divisions and ticks are drawn. */
    var steps: Int = 0
        set(v) {
            field = max(0, v)
            invalidate()
        }

    /** Text shown in the bubble while dragging. */
    var format: (Float) -> String = { it.toInt().toString() }

    /** (value, isFinal) — final fires once, on release. */
    var onValue: ((Float, Boolean) -> Unit)? = null

    var value: Float = minValue
        set(v) {
            val clamped = v.coerceIn(minValue, maxValue)
            if (clamped == field) return
            field = clamped
            invalidate()
        }

    private val groove = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bubble = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bubbleText = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var dragging = false

    private fun blendGray(): Int = Color.argb(0x33, 128, 128, 128)
    private fun side(): Float = 13f * density
    private fun trackHalf(): Float = 3.5f * density

    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        bubbleText.typeface = Typeface.DEFAULT_BOLD
    }

    /** Applies a palette; [onDark] keeps the light-on-black player look. */
    fun setColors(palette: OeaPalette, onDark: Boolean) {
        if (onDark) {
            trackColor = Color.argb(0x40, 255, 255, 255)
            thumbColor = Color.WHITE
            bubbleTextColor = Color.WHITE
        } else {
            trackColor = palette.cardHigh
            thumbColor = if (palette.dark) Color.WHITE else palette.accent
            bubbleTextColor = palette.text
        }
        fillColor = palette.accent
        invalidate()
    }

    private fun fraction(): Float =
        if (maxValue <= minValue) 0f else ((value - minValue) / (maxValue - minValue)).coerceIn(0f, 1f)

    private fun valueAt(x: Float): Float {
        val usable = width - side() * 2f
        if (usable <= 0f) return minValue
        val ratio = ((x - side()) / usable).coerceIn(0f, 1f)
        val raw = minValue + ratio * (maxValue - minValue)
        if (steps <= 0) return raw
        val stepSize = (maxValue - minValue) / steps
        if (stepSize <= 0f) return raw
        return (minValue + Math.round((raw - minValue) / stepSize) * stepSize).coerceIn(minValue, maxValue)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY)
            MeasureSpec.getSize(heightMeasureSpec) else (38 * density).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val y = height / 2f
        val half = trackHalf()
        val x = side() + fraction() * (width - side() * 2f)

        groove.color = trackColor
        rect.set(side(), y - half, width - side(), y + half)
        canvas.drawRoundRect(rect, half, half, groove)

        if (x - side() > half * 2f) {
            fill.color = fillColor
            rect.set(side(), y - half, x, y + half)
            canvas.drawRoundRect(rect, half, half, fill)
        }

        if (steps > 0) {
            val usable = width - side() * 2f
            tick.color = Color.argb(0x66, 0, 0, 0)
            for (i in 0..steps) {
                val tickX = side() + usable * i / steps
                val passed = tickX <= x + 0.5f
                tick.color = if (passed) Color.argb(0x77, 255, 255, 255) else Color.argb(0x33, 128, 128, 128)
                canvas.drawCircle(tickX, y, 1.7f * density, tick)
            }
        }

        val radius = if (dragging) 10f * density else 7.5f * density
        shadow.color = Color.argb(0x55, 0, 0, 0)
        shadow.setShadowLayer(6f * density, 0f, 1f * density, Color.argb(0x88, 0, 0, 0))
        canvas.drawCircle(x, y, radius, shadow)
        shadow.clearShadowLayer()
        thumb.color = thumbColor
        canvas.drawCircle(x, y, radius, thumb)

        if (dragging) {
            val label = format(value)
            bubbleText.color = bubbleTextColor
            bubbleText.textSize = 12.5f * density
            bubbleText.textAlign = Paint.Align.CENTER
            val bw = bubbleText.measureText(label) + 20f * density
            val bh = 26f * density
            val bx = (x - bw / 2f).coerceIn(4f * density, max(4f * density, width - bw - 4f * density))
            val by = max(2f * density, y - radius - bh - 12f * density)
            bubble.color = Color.argb(0xF0, 10, 12, 16)
            rect.set(bx, by, bx + bw, by + bh)
            canvas.drawRoundRect(rect, bh / 2f, bh / 2f, bubble)
            val pointer = Path()
            pointer.moveTo(x - 5f * density, by + bh - 0.5f)
            pointer.lineTo(x + 5f * density, by + bh - 0.5f)
            pointer.lineTo(x, by + bh + 5f * density)
            pointer.close()
            canvas.drawPath(pointer, bubble)
            canvas.drawText(label, bx + bw / 2f, by + bh / 2f - (bubbleText.ascent() + bubbleText.descent()) / 2f, bubbleText)
        }
        contentDescription = format(value)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                value = valueAt(event.x)
                onValue?.invoke(value, false)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                val before = value
                value = valueAt(event.x)
                if (steps > 0 && before != value) {
                    runCatching { performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
                }
                onValue?.invoke(value, false)
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    onValue?.invoke(value, true)
                    performClick()
                    invalidate()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}

/**
 * A media scrubber: elapsed, buffered, and a bubble showing the time you are
 * dragging to. MediaPlayer speaks in milliseconds, so that is the unit here.
 */
class OeaScrubBar(context: Context) : View(context) {

    private val density = context.resources.displayMetrics.density
    private val groove = Paint(Paint.ANTI_ALIAS_FLAG)
    private val buffered = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbShadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint()
    private val bubble = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var dragging = false
    private var targetMs = 0L
    private var targetX = 0f

    var durationMs: Long = 0L
        set(v) {
            field = max(0L, v)
            invalidate()
        }

    var positionMs: Long = 0L
        set(v) {
            val clamped = if (durationMs > 0L) v.coerceIn(0L, durationMs) else max(0L, v)
            if (clamped == field && !dragging) return
            field = clamped
            if (!dragging) invalidate()
        }

    var bufferedMs: Long = 0L
        set(v) {
            field = max(0L, v)
            invalidate()
        }

    var accentColor: Int = 0xFF8AB4F8.toInt()
    var trackColor: Int = Color.argb(0x40, 255, 255, 255)
    var bufferedColor: Int = Color.argb(0x66, 255, 255, 255)

    /** The bubble's text; the player gives it the same format as its labels. */
    var format: (Long, Long) -> String = { pos, dur ->
        "%s / %s".format(clock(pos), clock(dur))
    }

    /** (target, isFinal). */
    var onSeek: ((Long, Boolean) -> Unit)? = null

    init {
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        text.typeface = Typeface.DEFAULT_BOLD
        thumbShadow.color = Color.argb(0x66, 0, 0, 0)
        thumbShadow.setShadowLayer(4f * density, 0f, 1f * density, Color.argb(0x99, 0, 0, 0))
    }

    private fun side(): Float = 11f * density

    private fun clock(ms: Long): String {
        val total = (ms / 1000L).coerceAtLeast(0L)
        val h = total / 3600L
        val m = (total % 3600L) / 60L
        val s = total % 60L
        return if (h > 0L) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    private fun timeAt(x: Float): Long {
        if (durationMs <= 0L) return 0L
        val usable = width - side() * 2f
        if (usable <= 0f) return 0L
        return (((x - side()) / usable).coerceIn(0f, 1f) * durationMs).toLong()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY)
            MeasureSpec.getSize(heightMeasureSpec) else (36 * density).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val y = height / 2f + (if (dragging) 7f * density else 0f)
        val half = (if (dragging) 4f * density else 2.6f * density)
        val left = side()
        val right = width - side()
        val span = max(1f, right - left)
        val f = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        val bf = if (durationMs > 0L) (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        val x = left + f * span

        groove.color = trackColor
        rect.set(left, y - half, right, y + half)
        canvas.drawRoundRect(rect, half, half, groove)

        if (bf > f) {
            buffered.color = bufferedColor
            rect.set(left, y - half, left + bf * span, y + half)
            canvas.drawRoundRect(rect, half, half, buffered)
        }
        if (x - left > half * 2f) {
            fill.color = accentColor
            rect.set(left, y - half, x, y + half)
            canvas.drawRoundRect(rect, half, half, fill)
        }

        glow.shader = LinearGradient(
            x - 30f * density, y, x, y,
            intArrayOf(Color.TRANSPARENT, Color.argb(0x55, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))),
            null,
            Shader.TileMode.CLAMP
        )
        rect.set(x - 30f * density, y - half, x, y + half)
        canvas.drawRect(rect, glow)

        val radius = if (dragging) 8.5f * density else 6f * density
        canvas.drawCircle(x, y, radius, thumbShadow)
        thumb.color = Color.WHITE
        canvas.drawCircle(x, y, radius, thumb)

        if (dragging && durationMs > 0L) {
            val label = format(targetMs, durationMs)
            text.color = Color.WHITE
            text.textSize = 12.5f * density
            text.textAlign = Paint.Align.CENTER
            val bw = text.measureText(label) + 20f * density
            val bh = 26f * density
            val bx = (targetX - bw / 2f).coerceIn(6f * density, max(6f * density, width - bw - 6f * density))
            val by = max(2f * density, y - radius - bh - 12f * density)
            bubble.color = Color.argb(0xF0, 10, 12, 16)
            rect.set(bx, by, bx + bw, by + bh)
            canvas.drawRoundRect(rect, bh / 2f, bh / 2f, bubble)
            canvas.drawText(label, bx + bw / 2f, by + bh / 2f - (text.ascent() + text.descent()) / 2f, text)
        }
        contentDescription = if (durationMs > 0L) "${clock(positionMs)} of ${clock(durationMs)}" else "Progress"
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                targetX = event.x
                targetMs = timeAt(event.x)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                targetX = event.x
                val next = timeAt(event.x)
                if (next != targetMs) {
                    targetMs = next
                    runCatching { performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
                }
                onSeek?.invoke(targetMs, false)
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    onSeek?.invoke(targetMs, true)
                    performClick()
                    invalidate()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}

/** A round icon button with a real selected state — the player's control chips. */
class OeaIconToggle(context: Context) : View(context) {

    private val density = context.resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val callback = object : Drawable.Callback {
        override fun invalidateDrawable(who: Drawable) {
            invalidate()
        }

        override fun scheduleDrawable(who: Drawable, what: Runnable, w: Long) {
            postDelayed(what, w)
        }

        override fun unscheduleDrawable(who: Drawable, what: Runnable) {
            removeCallbacks(what)
        }
    }

    var icon: Drawable? = null
        set(v) {
            field?.callback = null
            field = v
            v?.callback = callback
            v?.setTint(if (selected) selectedTint else tint)
            requestLayout()
            invalidate()
        }

    var tint: Int = Color.WHITE
        set(v) {
            field = v
            if (!selected) icon?.setTint(v)
            invalidate()
        }

    var selectedTint: Int = Color.BLACK
    var selectedBg: Int = 0xFF8AB4F8.toInt()
    var idleBg: Int = Color.argb(0x33, 255, 255, 255)

    /**
     * Whether this control is the one the user is on. The JVM names are moved aside
     * because View already owns setSelected(boolean): the Kotlin call sites stay exactly
     * as they read, and the clash stops being an accidental override.
     */
    @get:JvmName("isToggleSelected")
    @set:JvmName("setToggleSelected")
    var selected: Boolean = false
        set(v) {
            if (field == v) return
            field = v
            icon?.setTint(if (v) selectedTint else tint)
            invalidate()
        }

    var onChanged: (() -> Unit)? = null
    var circleSizeDp: Int = 48

    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = (circleSizeDp * density).toInt()
        setMeasuredDimension(resolveSize(size, widthMeasureSpec), resolveSize(size, heightMeasureSpec))
    }

    override fun performClick(): Boolean {
        if (!isEnabled) return false
        selected = !selected
        runCatching { performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
        onChanged?.invoke()
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val r = min(width, height) / 2f
        bg.color = if (selected) selectedBg else idleBg
        bg.alpha = if (isEnabled) 255 else 90
        if (bg.alpha < 255) bg.alpha = (bg.alpha * 0.9f).toInt()
        canvas.drawCircle(width / 2f, height / 2f, r, bg)
        val d = icon ?: return
        val target = (min(width, height) * 0.45f).toInt()
        val left = ((width - target) / 2f).toInt()
        val top = ((height - target) / 2f).toInt()
        d.setBounds(left, top, left + target, top + target)
        d.draw(canvas)
    }
}

/** The skeleton a screen shows while its first real data loads. */
class OeaShimmer(context: Context) : View(context) {

    private val density = context.resources.displayMetrics.density
    private val paint = Paint()
    private val rect = RectF()
    private var animator: ValueAnimator? = null
    private var phase = 0f

    var cornerRadius: Float = 12f * density
    var baseColor: Int = Color.argb(0x22, 128, 128, 128)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (animator?.isRunning != true) {
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1150L
                repeatCount = ValueAnimator.INFINITE
                interpolator = android.view.animation.LinearInterpolator()
                addUpdateListener {
                    phase = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        paint.shader = LinearGradient(
            -width + 2f * width * phase, 0f, phase * width * 2f - width * 0.5f, height.toFloat(),
            intArrayOf(baseColor and 0xFFFFFF, baseColor or (0x55 shl 24), baseColor and 0xFFFFFF),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)
    }
}

/**
 * A zoomable, pannable picture view. Fit-to-screen is 1×; pinch scales to 8×;
 * a double tap cycles 1× → 2.5× → 5× → 1× where you tapped; [zoomBy] steps in
 * or out for on-screen controls. While zoomed, the view takes the drag away
 * from its parent, but hands it back at the picture's edge so a pager still
 * turns the page when there is nothing left to pan.
 *
 * Two traps are closed here after they cost a release:
 *  * the view renders through its own matrix, so it *is* [ScaleType.MATRIX]
 *    whatever a caller asks for — setting FIT_CENTER used to silently unhook
 *    the matrix and with it all zooming;
 *  * the drawable's *intrinsic* size drives the maths, never its bounds:
 *    ImageView sizes bounds to the view for other scale types, which made a
 *    full-screen photo compute a fit scale of 1 and draw cropped.
 */
class OeaZoomImageView(context: Context) : ImageView(context) {

    private val matrix = Matrix()
    private var userScale = 1f
    private var tx = 0f
    private var ty = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var maxScale = 8f
    private var animator: ValueAnimator? = null

    var onZoomChanged: ((Boolean) -> Unit)? = null
    var onTap: (() -> Unit)? = null

    val isZoomed: Boolean get() = userScale > 1.02f

    /** The current zoom relative to fit-to-screen (1 = whole picture visible). */
    val zoomScale: Float get() = userScale

    private val tap = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            performClick()
            onTap?.invoke()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val target = when {
                userScale <= 1.02f -> 2.5f
                userScale < 4f -> min(5f, maxScale)
                else -> 1f
            }
            animateTo(target, e.x, e.y)
            return true
        }
    })

    private val pinch = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val focusX = if (detector.focusX.isNaN()) width / 2f else detector.focusX
            val focusY = if (detector.focusY.isNaN()) height / 2f else detector.focusY
            zoomTo(userScale * detector.scaleFactor, focusX, focusY)
            return true
        }
    })

    init {
        super.setScaleType(ScaleType.MATRIX)
    }

    /** Matrix rendering is the zoom mechanism; no other scale type is honoured. */
    override fun setScaleType(scaleType: ScaleType?) {
        super.setScaleType(ScaleType.MATRIX)
    }

    override fun performClick(): Boolean = super.performClick()

    fun setMaxZoom(factor: Float) {
        maxScale = factor.coerceIn(1f, 12f)
        if (userScale > maxScale) zoomTo(maxScale, width / 2f, height / 2f)
    }

    fun reset() {
        animator?.cancel()
        userScale = 1f
        tx = 0f
        ty = 0f
        applyMatrix()
    }

    /** Animated reset, for an on-screen "fit" control. */
    fun resetAnimated() = animateTo(1f, width / 2f, height / 2f)

    /** One on-screen zoom step: positive zooms in, negative out. */
    fun zoomBy(steps: Int) {
        if (steps == 0 || width <= 0 || height <= 0) return
        var factor = 1f
        repeat(kotlin.math.abs(steps)) { factor *= 1.6f }
        val target = if (steps > 0) userScale * factor else userScale / factor
        animateTo(target.coerceIn(1f, maxScale), width / 2f, height / 2f)
    }

    private fun zoomTo(scale: Float, focusX: Float, focusY: Float) {
        val before = userScale
        userScale = scale.coerceIn(1f, maxScale)
        if (userScale <= 1.001f) {
            userScale = 1f
            tx = 0f
            ty = 0f
        } else if (before > 0f) {
            // (tx, ty) is the picture centre's offset from the view centre.
            // Keeping the image point under the focus pinned to the focus when
            // the scale grows by `grow` gives tx' = c - (c - tx) * grow, with
            // c the focus measured from the centre — no drift, no jump.
            val grow = userScale / before
            val cx = focusX - width / 2f
            val cy = focusY - height / 2f
            tx = cx - (cx - tx) * grow
            ty = cy - (cy - ty) * grow
        }
        clampAndApply()
    }

    private fun animateTo(targetScale: Float, focusX: Float, focusY: Float) {
        animator?.cancel()
        val startScale = userScale
        val startTx = tx
        val startTy = ty
        val endScale = targetScale.coerceIn(1f, maxScale)
        // The end translation, from the same anchor maths as a pinch step.
        val grow = if (startScale > 0f) endScale / startScale else 1f
        val cx = focusX - width / 2f
        val cy = focusY - height / 2f
        var endTx = cx - (cx - startTx) * grow
        var endTy = cy - (cy - startTy) * grow
        if (endScale <= 1.001f) {
            endTx = 0f
            endTy = 0f
        }
        val running = ValueAnimator.ofFloat(0f, 1f)
        running.duration = 230L
        running.interpolator = android.view.animation.DecelerateInterpolator(1.6f)
        running.addUpdateListener { value ->
            val t = value.animatedValue as Float
            userScale = startScale + (endScale - startScale) * t
            tx = startTx + (endTx - startTx) * t
            ty = startTy + (endTy - startTy) * t
            clampAndApply()
        }
        animator = running
        running.start()
    }

    override fun setImageDrawable(d: Drawable?) {
        super.setImageDrawable(d)
        if (d != null) reset()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clampAndApply()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    /** The picture's own pixel size — never the drawable bounds ImageView manages. */
    private fun drawableSize(): Pair<Int, Int> {
        val d = drawable ?: return 0 to 0
        return max(0, d.intrinsicWidth) to max(0, d.intrinsicHeight)
    }

    private fun fitScale(iw: Int, ih: Int): Float =
        if (iw <= 0 || ih <= 0 || width <= 0 || height <= 0) 1f
        else min(width.toFloat() / iw, height.toFloat() / ih)

    private fun maxOffset(): Pair<Float, Float> {
        val (iw, ih) = drawableSize()
        if (iw <= 0 || ih <= 0) return 0f to 0f
        val s = fitScale(iw, ih) * userScale
        return max(0f, (iw * s - width) / 2f) to max(0f, (ih * s - height) / 2f)
    }

    private fun clampAndApply() {
        val (maxTx, maxTy) = maxOffset()
        tx = tx.coerceIn(-maxTx, maxTx)
        ty = ty.coerceIn(-maxTy, maxTy)
        applyMatrix()
    }

    private fun applyMatrix() {
        val (iw, ih) = drawableSize()
        if (iw <= 0 || ih <= 0 || width <= 0 || height <= 0) return
        val s = fitScale(iw, ih) * userScale
        matrix.reset()
        matrix.postScale(s, s)
        matrix.postTranslate((width - iw * s) / 2f + tx, (height - ih * s) / 2f + ty)
        imageMatrix = matrix
        onZoomChanged?.invoke(isZoomed)
        invalidate()
    }

    /** True when a horizontal drag of [dx] has no picture left to pan into. */
    private fun atHorizontalEdge(dx: Float): Boolean {
        val (maxTx, _) = maxOffset()
        if (maxTx <= 0.5f) return true
        return (dx > 0f && tx >= maxTx - 0.5f) || (dx < 0f && tx <= -maxTx + 0.5f)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animator?.cancel()
                lastX = event.x
                lastY = event.y
                if (isZoomed) parent?.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                animator?.cancel()
                parent?.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_MOVE -> {
                if (isZoomed && !pinch.isInProgress && event.pointerCount == 1) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    lastX = event.x
                    lastY = event.y
                    if (abs(dx) > abs(dy) && atHorizontalEdge(dx)) {
                        // The pan is finished in this direction; let a parent
                        // pager take the rest of the swipe and turn the page.
                        parent?.requestDisallowInterceptTouchEvent(false)
                    } else {
                        tx += dx
                        ty += dy
                        clampAndApply()
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (userScale <= 1.06f && userScale > 1f) {
                    userScale = 1f
                    tx = 0f
                    ty = 0f
                    clampAndApply()
                }
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        pinch.onTouchEvent(event)
        tap.onTouchEvent(event)
        return true
    }

    /** Called by a parent pager so an un-zoomed picture still swipes. */
    fun shouldParentIntercept(): Boolean = !isZoomed
}
