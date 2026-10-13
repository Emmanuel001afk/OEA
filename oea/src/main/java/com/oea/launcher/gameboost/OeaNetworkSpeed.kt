package com.oea.launcher.gameboost

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.net.TrafficStats
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import java.util.Locale

/**
 * Device-wide download/upload speed for the Game Boost floating control.
 *
 * Samples Android's TrafficStats totals at most once per [MIN_INTERVAL_MS] and
 * lightly smooths the result, so the readout is steady instead of flickering
 * between wildly different values every tick.
 */
internal class OeaNetworkSpeedMeter {
    /** Smoothed download speed in bytes/second; null until two samples exist or when unsupported. */
    var downBytesPerSecond: Double? = null
        private set
    var upBytesPerSecond: Double? = null
        private set
    var supported = true
        private set

    private var lastRx = -1L
    private var lastTx = -1L
    private var lastAt = 0L

    fun sample(now: Long = SystemClock.elapsedRealtime()) {
        if (lastAt != 0L && now - lastAt < MIN_INTERVAL_MS) return
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        if (rx < 0L || tx < 0L) {
            supported = false
            downBytesPerSecond = null
            upBytesPerSecond = null
            lastAt = now
            return
        }
        supported = true
        if (lastAt != 0L && lastRx >= 0L && lastTx >= 0L) {
            val seconds = ((now - lastAt) / 1000.0).coerceAtLeast(0.25)
            // Counters can reset (interface restart); never report a negative speed.
            val down = (rx - lastRx).coerceAtLeast(0L) / seconds
            val up = (tx - lastTx).coerceAtLeast(0L) / seconds
            downBytesPerSecond = smooth(downBytesPerSecond, down)
            upBytesPerSecond = smooth(upBytesPerSecond, up)
        }
        lastRx = rx
        lastTx = tx
        lastAt = now
    }

    fun reset() {
        lastRx = -1L
        lastTx = -1L
        lastAt = 0L
        downBytesPerSecond = null
        upBytesPerSecond = null
    }

    private fun smooth(previous: Double?, next: Double): Double {
        val value = if (previous == null) next else previous * 0.35 + next * 0.65
        return if (value < IDLE_BYTES_PER_SECOND) 0.0 else value
    }

    companion object {
        private const val MIN_INTERVAL_MS = 900L
        private const val IDLE_BYTES_PER_SECOND = 48.0

        /** "0 KB/s", "8.4 KB/s", "312 KB/s", "1.2 MB/s", "48 MB/s". */
        fun formatRate(bytesPerSecond: Double?): String {
            val value = bytesPerSecond ?: return "—"
            val kb = value / 1024.0
            if (kb < 0.05) return "0 KB/s"
            if (kb < 10.0) return String.format(Locale.US, "%.1f KB/s", kb)
            if (kb < 1000.0) return String.format(Locale.US, "%.0f KB/s", kb)
            val mb = kb / 1024.0
            if (mb < 10.0) return String.format(Locale.US, "%.1f MB/s", mb)
            if (mb < 1000.0) return String.format(Locale.US, "%.0f MB/s", mb)
            return String.format(Locale.US, "%.1f GB/s", mb / 1024.0)
        }

        /** At most four characters, for the badge under the floating button: "0K", "8.4K", "312K", "1.2M". */
        fun formatCompact(bytesPerSecond: Double?): String {
            val value = bytesPerSecond ?: return "—"
            val kb = value / 1024.0
            if (kb < 0.05) return "0K"
            if (kb < 10.0) return String.format(Locale.US, "%.1fK", kb)
            if (kb < 1000.0) return String.format(Locale.US, "%.0fK", kb)
            val mb = kb / 1024.0
            if (mb < 10.0) return String.format(Locale.US, "%.1fM", mb)
            if (mb < 1000.0) return String.format(Locale.US, "%.0fM", mb)
            return String.format(Locale.US, "%.1fG", mb / 1024.0)
        }
    }
}

/** Shared drawing helpers for the two network readouts. */
private fun View.sp(value: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

private fun View.dpf(value: Float): Float = value * resources.displayMetrics.density

private fun Int.withAlphaByte(alpha: Int): Int = (this and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

/** Small filled arrow: pointing down for download, up for upload. */
private fun arrowPath(path: Path, cx: Float, cy: Float, size: Float, down: Boolean) {
    val half = size / 2f
    path.reset()
    if (down) {
        path.moveTo(cx - half, cy - half * 0.55f)
        path.lineTo(cx + half, cy - half * 0.55f)
        path.lineTo(cx, cy + half * 0.75f)
    } else {
        path.moveTo(cx - half, cy + half * 0.55f)
        path.lineTo(cx + half, cy + half * 0.55f)
        path.lineTo(cx, cy - half * 0.75f)
    }
    path.close()
}

/**
 * Two-line speed badge shown directly under the floating button:
 *
 *     ▼ 1.2M
 *     ▲  84K
 *
 * Same width as the button, tabular digits and right-aligned values, so the
 * numbers stay in place as they change.
 */
internal class OeaNetworkBadgeView(context: Context) : View(context) {
    private var down = "—"
    private var up = "—"
    private var base = 0xFF0A1020.toInt()
    private var accent = 0xFF2357FF.toInt()
    private val rect = RectF()
    private val path = Path()
    private val background = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val arrow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val value = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.RIGHT
        fontFeatureSettings = "tnum"
    }

    init {
        contentDescription = "Network speed"
        isClickable = true
    }

    fun update(downText: String, upText: String) {
        if (downText == down && upText == up) return
        down = downText
        up = upText
        contentDescription = "Download $downText per second, upload $upText per second"
        invalidate()
    }

    fun setColors(baseColor: Int, accentColor: Int) {
        if (baseColor == base && accentColor == accent) return
        base = baseColor
        accent = accentColor
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val strokeWidth = dpf(1f)
        rect.set(strokeWidth / 2f, strokeWidth / 2f, w - strokeWidth / 2f, h - strokeWidth / 2f)
        val radius = minOf(dpf(9f), h / 2f)
        background.color = base.withAlphaByte(0xE8)
        stroke.color = accent.withAlphaByte(0x99)
        stroke.strokeWidth = strokeWidth
        canvas.drawRoundRect(rect, radius, radius, background)
        canvas.drawRoundRect(rect, radius, radius, stroke)

        val pad = dpf(5f)
        val rowHeight = h / 2f
        value.textSize = minOf(sp(8.5f), rowHeight * 0.62f)
        arrow.color = accent
        val arrowSize = value.textSize * 0.62f
        drawRow(canvas, down, rowHeight * 0.5f + dpf(0.5f), pad, w - pad, arrowSize, downArrow = true)
        drawRow(canvas, up, rowHeight * 1.5f - dpf(0.5f), pad, w - pad, arrowSize, downArrow = false)
    }

    private fun drawRow(canvas: Canvas, text: String, centerY: Float, left: Float, right: Float, arrowSize: Float, downArrow: Boolean) {
        arrowPath(path, left + arrowSize / 2f, centerY, arrowSize, downArrow)
        canvas.drawPath(path, arrow)
        val metrics = value.fontMetrics
        val baseline = centerY - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(text, right, baseline, value)
    }
}

/**
 * Labelled status strip inside the Game Boost panel: download, upload and
 * battery in three equal columns with thin dividers.
 */
internal class OeaGameStatusStripView(context: Context) : View(context) {
    private var down = "—"
    private var up = "—"
    private var battery = "—"
    private var batteryCaption = "BATTERY"
    private var base = 0xFF0A1020.toInt()
    private var accent = 0xFF2357FF.toInt()
    private val rect = RectF()
    private val path = Path()
    private val background = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val divider = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arrow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9FB2C8.toInt()
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.08f
    }
    private val value = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
        fontFeatureSettings = "tnum"
    }

    init {
        contentDescription = "Network speed and battery"
    }

    fun update(downText: String, upText: String, batteryText: String, charging: Boolean) {
        val nextCaption = if (charging) "CHARGING" else "BATTERY"
        if (downText == down && upText == up && batteryText == battery && nextCaption == batteryCaption) return
        down = downText
        up = upText
        battery = batteryText
        batteryCaption = nextCaption
        contentDescription = "Download $downText, upload $upText, battery $batteryText"
        invalidate()
    }

    fun setColors(baseColor: Int, accentColor: Int) {
        if (baseColor == base && accentColor == accent) return
        base = baseColor
        accent = accentColor
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, resolveSize(dpf(46f).toInt(), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val strokeWidth = dpf(1f)
        rect.set(strokeWidth / 2f, strokeWidth / 2f, w - strokeWidth / 2f, h - strokeWidth / 2f)
        val radius = dpf(12f)
        background.color = base.withAlphaByte(0xB8)
        stroke.color = accent.withAlphaByte(0x55)
        stroke.strokeWidth = strokeWidth
        canvas.drawRoundRect(rect, radius, radius, background)
        canvas.drawRoundRect(rect, radius, radius, stroke)

        val column = w / 3f
        divider.color = Color.WHITE.withAlphaByte(0x1E)
        divider.strokeWidth = maxOf(1f, dpf(0.6f))
        canvas.drawLine(column, h * 0.22f, column, h * 0.78f, divider)
        canvas.drawLine(column * 2f, h * 0.22f, column * 2f, h * 0.78f, divider)

        caption.textSize = sp(7.5f)
        value.textSize = sp(11.5f)
        arrow.color = accent
        drawColumn(canvas, 0f, column, h, "DOWN", down, arrowDown = true)
        drawColumn(canvas, column, column, h, "UP", up, arrowDown = false)
        drawColumn(canvas, column * 2f, column, h, batteryCaption, battery, arrowDown = null)
    }

    private fun drawColumn(canvas: Canvas, left: Float, width: Float, height: Float, label: String, text: String, arrowDown: Boolean?) {
        val pad = dpf(10f)
        val captionBaseline = height * 0.40f
        var captionX = left + pad
        if (arrowDown != null) {
            val size = caption.textSize * 0.9f
            arrowPath(path, captionX + size / 2f, captionBaseline - caption.textSize * 0.36f, size, arrowDown)
            canvas.drawPath(path, arrow)
            captionX += size + dpf(4f)
        }
        canvas.drawText(label, captionX, captionBaseline, caption)
        val valueBaseline = height * 0.78f
        val available = width - pad * 2f
        var shown = text
        if (value.measureText(shown) > available) {
            // Very narrow panels: drop the "/s" before ever overlapping a neighbour.
            shown = text.removeSuffix("/s")
        }
        canvas.drawText(shown, left + pad, valueBaseline, value)
    }
}
