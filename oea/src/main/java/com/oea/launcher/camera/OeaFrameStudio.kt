package com.oea.launcher.camera

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** Optional composition aids. Guides never become part of the saved photograph. */
class OeaFrameStudio(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var echo: Bitmap? = null
    private var dual = false
    private var caption = false
    private var edge = false
    private var orbit = false

    fun configure(prefs: SharedPreferences, reference: Bitmap?) {
        if (echo !== reference) echo?.recycle()
        echo = reference
        dual = prefs.getBoolean("studio_dual", false)
        caption = prefs.getBoolean("studio_caption", false)
        edge = prefs.getBoolean("studio_edge", false)
        orbit = prefs.getBoolean("studio_orbit", false)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        echo?.recycle()
        echo = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        paint.style = Paint.Style.FILL
        paint.alpha = 45
        echo?.takeUnless { it.isRecycled }?.let { canvas.drawBitmap(it, null, RectF(0f, 0f, w, h), paint) }
        paint.alpha = 255
        paint.color = Color.argb(170, 170, 235, 222)
        paint.strokeWidth = resources.displayMetrics.density
        paint.style = Paint.Style.STROKE
        fun frame(ratio: Float) {
            val fw = minOf(w, h * ratio)
            val fh = fw / ratio
            canvas.drawRect((w-fw)/2, (h-fh)/2, (w+fw)/2, (h+fh)/2, paint)
        }
        if (dual) { frame(1f); frame(if (h >= w) 3f/4f else 4f/3f) }
        if (edge) canvas.drawRoundRect(RectF(w*.08f, h*.08f, w*.92f, h*.92f), 18f, 18f, paint)
        if (orbit) {
            canvas.drawOval(RectF(w*.35f, h*.22f, w*.65f, h*.45f), paint)
            canvas.drawArc(RectF(w*.18f, h*.43f, w*.82f, h*.78f), 185f, 170f, false, paint)
        }
        if (caption) {
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(65, 0, 0, 0)
            canvas.drawRect(w*.12f, h*.62f, w*.88f, h*.75f, paint)
            paint.color = Color.WHITE
            paint.textSize = 12f * resources.displayMetrics.scaledDensity
            canvas.drawText("CAPTION NEST · guide only", w*.15f, h*.69f, paint)
        }
    }
}
