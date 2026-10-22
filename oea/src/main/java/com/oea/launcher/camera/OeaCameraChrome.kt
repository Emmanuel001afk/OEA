package com.oea.launcher.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.OrientationEventListener
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * The parts of a camera screen that are drawn rather than wired: the shaded
 * edges, the level, the recording clock, the zoom readout and the timer
 * countdown.
 *
 * They live in their own file so the camera screen itself stays about the
 * camera — and so each one can be dropped if a phone cannot support it, without
 * taking the viewfinder down with it.
 */
object OeaCameraChrome {

    /** A soft edge for the control bands: controls stay readable over a bright sky. */
    fun scrim(forTop: Boolean): GradientDrawable {
        val colours = if (forTop) {
            intArrayOf(0xD9040608.toInt(), 0x8A040608.toInt(), 0x00040608)
        } else {
            intArrayOf(0x00040608, 0x9E040608.toInt(), 0xE6040608.toInt())
        }
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colours)
    }

    /** A short, quiet tap — the shutter should feel like a shutter. */
    fun buzz(context: Context, millis: Long = 12L) {
        runCatching {
            val vibrator = context.getSystemService(Vibrator::class.java) ?: return
            if (!vibrator.hasVibrator()) return
            vibrator.vibrate(VibrationEffect.createOneShot(millis, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    /** "0:07", "12:04" or "1:02:09" — the same shape a recorder shows. */
    fun duration(millis: Long): String {
        val total = (millis.coerceAtLeast(0L)) / 1000L
        val hours = total / 3600L
        val minutes = (total % 3600L) / 60L
        val seconds = total % 60L
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    fun pill(context: Context, fill: Int, radiusDp: Float = 100f): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * context.resources.displayMetrics.density
            setColor(fill)
        }

    fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()
}

/**
 * A horizon level.
 *
 * Camera screens show one because a phone held "level" is usually not level, and
 * a tilted horizon is the mistake you cannot fix afterwards. The line turns
 * green inside a degree of level and tilts with the phone while you get there.
 * It switches its sensor listener on only while it is visible, so it costs
 * nothing in the modes that hide it.
 */
class OeaLevelView(context: Context) : View(context) {

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
    }
    private var roll = 0f
    private var active = true
    private var listening = false

    private val listener = object : OrientationEventListener(context) {
        override fun onOrientationChanged(orientation: Int) {
            if (orientation == ORIENTATION_UNKNOWN) {
                roll = 0f
                invalidate()
                return
            }
            // 0 is upright; anything past 180 is the same tilt the other way.
            roll = if (orientation <= 180) orientation.toFloat() else (orientation - 360).toFloat()
            invalidate()
        }
    }

    /** Which modes want a level; video and QR do not. */
    fun setActive(on: Boolean) {
        active = on
        visibility = if (on) View.VISIBLE else View.GONE
        syncListener()
    }

    private fun syncListener() {
        val want = active && isAttachedToWindow
        if (want == listening) return
        listening = want
        runCatching { if (want) listener.enable() else listener.disable() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncListener()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        listening = false
        runCatching { listener.disable() }
    }

    override fun onDraw(canvas: Canvas) {
        val level = kotlin.math.abs(roll) < 0.9f
        stroke.color = if (level) Color.rgb(120, 230, 150) else 0xD9FFFFFF.toInt()
        stroke.alpha = if (level) 235 else 170
        val cx = width / 2f
        val cy = height / 2f
        val reach = width * 0.16f
        val gap = width * 0.035f
        canvas.save()
        // The horizon tilts opposite to the phone, which is what the eye expects.
        canvas.rotate(-roll, cx, cy)
        canvas.drawLine(cx - reach, cy, cx - gap, cy, stroke)
        canvas.drawLine(cx + gap, cy, cx + reach, cy, stroke)
        // Two end ticks make the line read as a level even on a busy scene.
        canvas.drawLine(cx - reach, cy - reach * 0.10f, cx - reach, cy + reach * 0.10f, stroke)
        canvas.drawLine(cx + reach, cy - reach * 0.10f, cx + reach, cy + reach * 0.10f, stroke)
        canvas.restore()
    }
}

/**
 * The recording indicator: a blinking dot and the elapsed time.
 *
 * Before this existed the only sign that a clip was running was a red shutter,
 * with no idea how long it had been going and no readout at all once the button
 * was out of sight.
 */
class OeaRecordingPill(context: Context) : LinearLayout(context) {

    private val dot = View(context)
    private val label = TextView(context)
    private val ticker = Handler(Looper.getMainLooper())
    private var startedAt = 0L
    private var running = false
    private var bright = true

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = OeaCameraChrome.pill(context, 0xC0101216.toInt())
        setPadding(
            OeaCameraChrome.dp(context, 12f), OeaCameraChrome.dp(context, 7f),
            OeaCameraChrome.dp(context, 14f), OeaCameraChrome.dp(context, 7f)
        )
        dot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.rgb(255, 72, 66))
        }
        addView(dot, LinearLayout.LayoutParams(OeaCameraChrome.dp(context, 9f), OeaCameraChrome.dp(context, 9f)).apply {
            rightMargin = OeaCameraChrome.dp(context, 8f)
        })
        label.setTextColor(Color.WHITE)
        label.textSize = 13f
        label.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        label.text = "0:00"
        addView(label, LinearLayout.LayoutParams(-2, -2))
        visibility = GONE
    }

    fun start() {
        startedAt = SystemClock.elapsedRealtime()
        running = true
        visibility = VISIBLE
        step()
    }

    fun stop() {
        running = false
        ticker.removeCallbacksAndMessages(null)
        visibility = GONE
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        running = false
        ticker.removeCallbacksAndMessages(null)
    }

    private fun step() {
        if (!running) return
        label.text = OeaCameraChrome.duration(SystemClock.elapsedRealtime() - startedAt)
        bright = !bright
        dot.alpha = if (bright) 1f else 0.25f
        ticker.postDelayed({ step() }, 600L)
    }
}

/** The zoom readout: what the lens is actually doing, for a moment after it changes. */
class OeaZoomBadge(context: Context) : TextView(context) {

    private val fade = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null

    init {
        background = OeaCameraChrome.pill(context, 0x8C101216.toInt())
        setTextColor(Color.WHITE)
        textSize = 13f
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        gravity = Gravity.CENTER
        setPadding(
            OeaCameraChrome.dp(context, 12f), OeaCameraChrome.dp(context, 6f),
            OeaCameraChrome.dp(context, 12f), OeaCameraChrome.dp(context, 6f)
        )
        visibility = GONE
    }

    fun show(zoom: Float) {
        text = if (zoom < 1.05f) "1×" else String.format(Locale.US, "%.1f×", zoom)
        animate().cancel()
        alpha = 1f
        visibility = VISIBLE
        pending?.let { fade.removeCallbacks(it) }
        val hide = Runnable {
            animate().alpha(0f).setDuration(220).withEndAction { visibility = GONE }.start()
        }
        pending = hide
        fade.postDelayed(hide, 1100L)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pending?.let { fade.removeCallbacks(it) }
        pending = null
    }
}

/**
 * The self-timer, shown where the user is looking.
 *
 * It used to be a toast that said "Capturing in 3s" and then fired regardless —
 * there was no way to see the count, and no way to take it back.
 */
class OeaCountdownOverlay(context: Context) : FrameLayout(context) {

    private val number = TextView(context)
    private val hint = TextView(context)
    private val ticker = Handler(Looper.getMainLooper())
    private var remaining = 0
    private var running = false

    /** Called once when the count reaches zero. */
    var onFinished: (() -> Unit)? = null

    /** Called when the user taps to take the shot back. */
    var onCancelled: (() -> Unit)? = null

    init {
        number.setTextColor(Color.WHITE)
        number.textSize = 84f
        number.typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
        number.gravity = Gravity.CENTER
        addView(number, LayoutParams(-2, -2, Gravity.CENTER))
        hint.setTextColor(0xCCFFFFFF.toInt())
        hint.textSize = 13f
        hint.gravity = Gravity.CENTER
        hint.text = "Tap anywhere to cancel"
        addView(hint, LayoutParams(-2, -2, Gravity.CENTER).apply { topMargin = OeaCameraChrome.dp(context, 96f) })
        visibility = GONE
        isClickable = true
        setOnClickListener { cancel() }
    }

    fun start(seconds: Int) {
        if (seconds <= 0) {
            onFinished?.invoke()
            return
        }
        remaining = seconds
        running = true
        visibility = VISIBLE
        alpha = 1f
        render()
    }

    fun cancel() {
        if (!running) return
        running = false
        ticker.removeCallbacksAndMessages(null)
        visibility = GONE
        onCancelled?.invoke()
    }

    fun stopQuietly() {
        running = false
        ticker.removeCallbacksAndMessages(null)
        visibility = GONE
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        running = false
        ticker.removeCallbacksAndMessages(null)
    }

    private fun render() {
        number.text = remaining.toString()
        number.scaleX = 1.25f
        number.scaleY = 1.25f
        number.animate().scaleX(1f).scaleY(1f).setDuration(240).start()
        ticker.postDelayed({
            if (!running) return@postDelayed
            remaining -= 1
            if (remaining > 0) {
                render()
            } else {
                running = false
                visibility = GONE
                onFinished?.invoke()
            }
        }, 1000L)
    }
}
