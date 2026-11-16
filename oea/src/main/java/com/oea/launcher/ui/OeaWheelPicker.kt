package com.oea.launcher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Palette-native drum wheel for times and numbers.
 *
 * Platform `NumberPicker` inherits text colours from the activity's base
 * Material theme instead of [OeaPalette], turning digits white-on-white in
 * light themes and letting parent bottom sheets steal vertical drags. This
 * wheel draws every surface, border and digit directly from [palette], locks
 * touch interception while spinning, supports tap-to-step on the upper/lower
 * slots, optional tap-to-type on the active slot, keyboard arrows and TalkBack
 * scroll actions.
 */
class OeaWheelPicker(
    context: Context,
    private val palette: OeaPalette,
    min: Int = 0,
    max: Int = 59,
    initial: Int = min,
    var wrap: Boolean = true,
    var label: String = "",
    var formatter: (Int) -> String = { it.toString() }
) : View(context) {

    var minValue: Int = min
        set(v) {
            field = v
            if (maxValue < v) maxValue = v
            setValue(value, notify = false)
        }

    var maxValue: Int = max.coerceAtLeast(min)
        set(v) {
            field = v.coerceAtLeast(minValue)
            setValue(value, notify = false)
        }

    var value: Int = initial.coerceIn(minValue, maxValue)
        private set

    var onValueChanged: ((Int) -> Unit)? = null
    var onCenterTap: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val maxFlingVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity
    private val scroller = OverScroller(context)
    private var velocityTracker: VelocityTracker? = null

    /** Fractional slot offset while dragging or flinging; 0 means centred on [value]. */
    private var offsetPx = 0f
    private var downY = 0f
    private var lastY = 0f
    private var dragging = false

    private val cardRect = RectF()
    private val bandRect = RectF()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = (1.2f * density).coerceAtLeast(1f)
    }
    private val activeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = OeaType.medium
        textSize = sp(26f)
    }
    private val neighborTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = OeaType.regular
        textSize = sp(18f)
    }

    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        updateAccessibilityDescription()
    }

    private fun dp(v: Float): Float = v * density
    private fun sp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private fun rangeSize(): Int = (maxValue - minValue + 1).coerceAtLeast(1)

    private fun slotHeight(): Float = (height / 3f).coerceAtLeast(dp(40f))

    fun setValue(newValue: Int, notify: Boolean = false) {
        val clamped = clampOrWrap(newValue)
        val changed = clamped != value
        value = clamped
        offsetPx = 0f
        scroller.forceFinished(true)
        updateAccessibilityDescription()
        invalidate()
        if (changed && notify) onValueChanged?.invoke(value)
    }

    fun stepBy(delta: Int, fromUser: Boolean = true) {
        if (rangeSize() <= 1) return
        val raw = value + delta
        val next = if (wrap) wrapValue(raw) else raw.coerceIn(minValue, maxValue)
        if (next == value) return
        value = next
        offsetPx = 0f
        updateAccessibilityDescription()
        if (fromUser) {
            runCatching { performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
            onValueChanged?.invoke(value)
        }
        invalidate()
    }

    private fun wrapValue(raw: Int): Int {
        val count = rangeSize()
        val shifted = (raw - minValue) % count
        return minValue + if (shifted < 0) shifted + count else shifted
    }

    private fun clampOrWrap(raw: Int): Int =
        if (wrap && rangeSize() > 1) wrapValue(raw) else raw.coerceIn(minValue, maxValue)

    private fun valueAtOffset(steps: Int): Int? {
        val raw = value + steps
        if (wrap && rangeSize() > 1) return wrapValue(raw)
        return raw.takeIf { it in minValue..maxValue }
    }

    private fun updateAccessibilityDescription() {
        val formatted = formatter(value)
        contentDescription = if (label.isBlank()) formatted else "$label, $formatted"
    }

    private fun activeInk(bandColor: Int): Int {
        return when {
            OeaTheme.contrast(palette.text, bandColor) >= 4.5f -> palette.text
            OeaTheme.contrast(palette.onAccentSoft, bandColor) >= 4.5f -> palette.onAccentSoft
            else -> OeaTheme.onColor(bandColor)
        }
    }

    private fun neighborInk(cardColor: Int): Int {
        return when {
            OeaTheme.contrast(palette.textSecondary, cardColor) >= 3.5f -> palette.textSecondary
            OeaTheme.contrast(palette.text, cardColor) >= 4.5f -> OeaTheme.alpha(palette.text, 0xBF)
            else -> OeaTheme.alpha(OeaTheme.onColor(cardColor), 0xC8)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredW = dp(84f).roundToInt()
        val desiredH = dp(152f).roundToInt()
        setMeasuredDimension(
            resolveSize(desiredW, widthMeasureSpec),
            resolveSize(desiredH, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 2f || h <= 2f) return

        val inset = strokePaint.strokeWidth
        val cardRadius = dp(18f)
        cardRect.set(inset, inset, w - inset, h - inset)

        // Wheel surface with explicit contrast-checked border.
        val surface = palette.card
        fillPaint.color = surface
        canvas.drawRoundRect(cardRect, cardRadius, cardRadius, fillPaint)
        strokePaint.color = palette.stroke
        canvas.drawRoundRect(cardRect, cardRadius, cardRadius, strokePaint)

        // Centre selection capsule: clearly distinguishes the chosen digit from neighbours.
        val slot = slotHeight()
        val cy = h / 2f
        val bandHalf = (slot * 0.48f).coerceAtMost(h * 0.24f)
        val bandPad = dp(6f)
        bandRect.set(bandPad, cy - bandHalf, w - bandPad, cy + bandHalf)
        val bandFill = palette.accentSoft
        fillPaint.color = bandFill
        val bandRadius = dp(13f)
        canvas.drawRoundRect(bandRect, bandRadius, bandRadius, fillPaint)
        strokePaint.color = OeaTheme.alpha(palette.accent, if (palette.dark) 0x99 else 0x80)
        canvas.drawRoundRect(bandRect, bandRadius, bandRadius, strokePaint)

        // Clip digits cleanly inside the rounded wheel frame.
        val save = canvas.save()
        canvas.clipRect(inset, dp(4f), w - inset, h - dp(4f))

        val selectedColor = activeInk(bandFill)
        val dimColor = neighborInk(surface)
        val cx = w / 2f

        for (step in -2..2) {
            val item = valueAtOffset(step) ?: continue
            val itemY = cy + step * slot + offsetPx
            if (itemY < -slot || itemY > h + slot) continue
            val distance = (abs(itemY - cy) / slot).coerceIn(0f, 1.6f)
            val isCenter = distance < 0.45f
            val paint = if (isCenter) activeTextPaint else neighborTextPaint
            paint.color = if (isCenter) {
                selectedColor
            } else {
                val fade = (1f - (distance - 0.35f) * 0.48f).coerceIn(0.32f, 0.92f)
                OeaTheme.alpha(dimColor, (fade * 255f).roundToInt())
            }
            val text = formatter(item)
            val fm = paint.fontMetrics
            val baseline = itemY - (fm.ascent + fm.descent) / 2f
            canvas.drawText(text, cx, baseline, paint)
        }
        canvas.restoreToCount(save)
    }

    private fun consumeScrollDelta(dy: Float) {
        if (rangeSize() <= 1) return
        val slot = slotHeight()
        offsetPx += dy
        if (!wrap) {
            if (value == minValue && offsetPx > slot * 0.38f) offsetPx = slot * 0.38f
            if (value == maxValue && offsetPx < -slot * 0.38f) offsetPx = -slot * 0.38f
        }
        var changed = false
        while (offsetPx <= -slot * 0.5f) {
            val next = valueAtOffset(1)
            if (next == null) {
                offsetPx = -slot * 0.38f
                break
            }
            value = next
            offsetPx += slot
            changed = true
        }
        while (offsetPx >= slot * 0.5f) {
            val prev = valueAtOffset(-1)
            if (prev == null) {
                offsetPx = slot * 0.38f
                break
            }
            value = prev
            offsetPx -= slot
            changed = true
        }
        if (changed) {
            updateAccessibilityDescription()
            runCatching { performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
            onValueChanged?.invoke(value)
        }
        invalidate()
    }

    private var lastScrollerY = 0

    private fun settleToSlot() {
        val target = -offsetPx.roundToInt()
        if (target == 0) {
            offsetPx = 0f
            invalidate()
            return
        }
        lastScrollerY = 0
        scroller.startScroll(0, 0, 0, target, 180)
        postInvalidateOnAnimation()
    }

    override fun computeScroll() {
        if (!scroller.computeScrollOffset()) {
            if (!dragging && abs(offsetPx) > 0.5f) settleToSlot()
            return
        }
        val currY = scroller.currY
        val dy = (currY - lastScrollerY).toFloat()
        lastScrollerY = currY
        if (dy != 0f) consumeScrollDelta(dy)
        if (!scroller.isFinished) {
            postInvalidateOnAnimation()
        } else if (!dragging && abs(offsetPx) > 0.5f) {
            settleToSlot()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        if (velocityTracker == null) velocityTracker = VelocityTracker.obtain()
        velocityTracker?.addMovement(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                if (!scroller.isFinished) scroller.abortAnimation()
                downY = event.y
                lastY = event.y
                dragging = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dy = event.y - lastY
                if (!dragging && abs(event.y - downY) > touchSlop) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (dragging) {
                    lastY = event.y
                    consumeScrollDelta(dy)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (dragging) {
                    val vt = velocityTracker
                    vt?.computeCurrentVelocity(1000, maxFlingVelocity.toFloat())
                    val vy = vt?.yVelocity?.roundToInt() ?: 0
                    recycleVelocity()
                    dragging = false
                    if (abs(vy) >= minFlingVelocity && rangeSize() > 1) {
                        lastScrollerY = 0
                        val travel = (slotHeight() * (rangeSize().coerceAtMost(24))).roundToInt()
                        scroller.fling(0, 0, 0, vy, 0, 0, -travel, travel)
                        postInvalidateOnAnimation()
                    } else {
                        settleToSlot()
                    }
                } else {
                    recycleVelocity()
                    val third = height / 3f
                    when {
                        event.y < third -> stepBy(-1, fromUser = true)
                        event.y > third * 2f -> stepBy(1, fromUser = true)
                        else -> {
                            performClick()
                            onCenterTap?.invoke()
                        }
                    }
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                recycleVelocity()
                dragging = false
                settleToSlot()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> { stepBy(-1, fromUser = true); true }
        KeyEvent.KEYCODE_DPAD_DOWN -> { stepBy(1, fromUser = true); true }
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
            onCenterTap?.invoke()
            true
        }
        else -> super.onKeyDown(keyCode, event)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.SeekBar"
        info.isScrollable = rangeSize() > 1
        if (wrap || value < maxValue) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        }
        if (wrap || value > minValue) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        }
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean = when (action) {
        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> {
            stepBy(1, fromUser = true)
            announceForAccessibility(contentDescription)
            true
        }
        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> {
            stepBy(-1, fromUser = true)
            announceForAccessibility(contentDescription)
            true
        }
        else -> super.performAccessibilityAction(action, arguments)
    }

    private fun recycleVelocity() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    override fun onDetachedFromWindow() {
        scroller.abortAnimation()
        recycleVelocity()
        super.onDetachedFromWindow()
    }
}
