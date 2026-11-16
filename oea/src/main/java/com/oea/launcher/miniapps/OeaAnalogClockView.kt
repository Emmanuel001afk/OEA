package com.oea.launcher.miniapps

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import com.oea.launcher.ui.OeaPalette
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** A quiet, local-time face; the containing Clock screen invalidates it once a second. */
internal class OeaAnalogClockView(context: Context, private val palette: OeaPalette) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f; val cy = height / 2f; val r = min(width, height) * 0.45f
        paint.style = Paint.Style.FILL; paint.color = palette.card
        canvas.drawCircle(cx, cy, r, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = r * 0.025f; paint.color = palette.stroke
        canvas.drawCircle(cx, cy, r, paint)
        for (i in 0 until 12) {
            val angle = i * Math.PI / 6 - Math.PI / 2
            paint.color = palette.textTertiary; paint.strokeWidth = r * if (i % 3 == 0) 0.025f else 0.012f
            val length = if (i % 3 == 0) 0.09f else 0.045f
            canvas.drawLine(cx + cos(angle).toFloat() * r * (0.87f - length), cy + sin(angle).toFloat() * r * (0.87f - length), cx + cos(angle).toFloat() * r * 0.87f, cy + sin(angle).toFloat() * r * 0.87f, paint)
        }
        val now = Calendar.getInstance()
        fun hand(turn: Float, length: Float, thickness: Float, color: Int) {
            val angle = turn * 2 * Math.PI - Math.PI / 2
            paint.color = color; paint.strokeWidth = r * thickness
            canvas.drawLine(cx, cy, cx + cos(angle).toFloat() * r * length, cy + sin(angle).toFloat() * r * length, paint)
        }
        hand((now.get(Calendar.HOUR) + now.get(Calendar.MINUTE) / 60f) / 12f, 0.48f, 0.045f, palette.text)
        hand((now.get(Calendar.MINUTE) + now.get(Calendar.SECOND) / 60f) / 60f, 0.69f, 0.03f, palette.text)
        hand(now.get(Calendar.SECOND) / 60f, 0.77f, 0.014f, palette.accent)
        paint.style = Paint.Style.FILL; paint.color = palette.accent
        canvas.drawCircle(cx, cy, r * 0.045f, paint)
    }
}
