package com.oea.launcher.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * Material 3 style switch drawn natively: a pill track with a thumb that grows
 * when checked. Replaces the dated framework Switch on every OEA surface.
 */
class OeaToggle(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var progress = 0f
    private var animator: ValueAnimator? = null

    var accent: Int = Color.rgb(138, 180, 248)
        set(value) { field = value; invalidate() }
    var onAccent: Int = Color.rgb(16, 20, 28)
        set(value) { field = value; invalidate() }
    var trackOff: Int = Color.rgb(48, 52, 60)
        set(value) { field = value; invalidate() }
    var thumbOff: Int = Color.rgb(150, 156, 166)
        set(value) { field = value; invalidate() }

    var isChecked: Boolean = false
        private set

    var onCheckedChange: ((Boolean) -> Unit)? = null

    init {
        isClickable = true
        isFocusable = true
        setOnClickListener { toggle(fromUser = true) }
    }

    fun setChecked(checked: Boolean, animate: Boolean = false) {
        if (checked == isChecked && (animate || progress == if (checked) 1f else 0f)) {
            contentDescription = if (checked) "On" else "Off"
            return
        }
        isChecked = checked
        animator?.cancel()
        val target = if (checked) 1f else 0f
        if (!animate || !isAttachedToWindow) {
            progress = target
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(progress, target).apply {
            duration = 180L
            interpolator = DecelerateInterpolator()
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            start()
        }
        contentDescription = if (checked) "On" else "Off"
    }

    fun toggle(fromUser: Boolean = false) {
        setChecked(!isChecked, animate = true)
        if (fromUser) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        onCheckedChange?.invoke(isChecked)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Keep a comfortable 48 dp accessibility target while retaining the
        // compact 32 dp switch track inside it.
        setMeasuredDimension((52 * density).toInt(), (48 * density).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = (32f * density).coerceAtMost(height.toFloat())
        val top = (height - h) / 2f
        val stroke = 2 * density
        rect.set(stroke / 2f, top + stroke / 2f, w - stroke / 2f, top + h - stroke / 2f)
        trackPaint.color = OeaTheme.blend(trackOff, accent, progress)
        canvas.drawRoundRect(rect, h / 2f, h / 2f, trackPaint)
        if (progress < 1f) {
            strokePaint.strokeWidth = stroke
            strokePaint.color = OeaTheme.alpha(thumbOff, (255 * (1f - progress)).toInt())
            canvas.drawRoundRect(rect, h / 2f, h / 2f, strokePaint)
        }
        val radius = (8f + 4f * progress) * density
        val inset = 16f * density
        val cx = inset + (w - inset * 2) * progress
        thumbPaint.color = OeaTheme.blend(thumbOff, onAccent, progress)
        canvas.drawCircle(cx, height / 2f, radius, thumbPaint)
    }
}
