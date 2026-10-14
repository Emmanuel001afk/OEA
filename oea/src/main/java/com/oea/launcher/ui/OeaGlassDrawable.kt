package com.oea.launcher.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * OEA "liquid glass" surface: a translucent body that is lighter at the top,
 * a bright specular rim that fades down the edge, a soft inner sheen and a
 * faint accent glow along the bottom. Used for the dock, search bar, island
 * card and drawer search when the Liquid glass style is on. It draws only
 * gradients (no live blur), so it costs the same as a plain rounded shape.
 */
class OeaGlassDrawable(
    private val fill: Int,
    private val radius: Float,
    private val density: Float,
    private val tint: Int = 0,
    private val rimAlpha: Int = 0xB0,
) : Drawable() {
    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var alphaValue = 255

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        val top = bounds.top.toFloat()
        val bottom = bounds.bottom.toFloat()
        val lighter = OeaTheme.blend(fill, Color.argb(Color.alpha(fill), 255, 255, 255), 0.10f)
        body.shader = LinearGradient(0f, top, 0f, bottom, lighter, fill, Shader.TileMode.CLAMP)
        rim.strokeWidth = maxOf(1f, density * 1.1f)
        rim.shader = LinearGradient(0f, top, 0f, bottom,
            intArrayOf(Color.argb(rimAlpha, 255, 255, 255), Color.argb(rimAlpha / 4, 255, 255, 255), Color.argb(rimAlpha / 3, 255, 255, 255)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        sheen.shader = RadialGradient(bounds.left + w * 0.3f, top + h * 0.05f, maxOf(w, h) * 0.6f,
            Color.argb(0x2E, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
        glow.shader = if (tint != 0) LinearGradient(0f, bottom - h * 0.45f, 0f, bottom,
            Color.argb(0, Color.red(tint), Color.green(tint), Color.blue(tint)),
            Color.argb(0x30, Color.red(tint), Color.green(tint), Color.blue(tint)), Shader.TileMode.CLAMP) else null
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val r = minOf(radius, b.height() / 2f, b.width() / 2f)
        rect.set(b)
        listOf(body, sheen, glow, rim).forEach { it.alpha = alphaValue }
        canvas.drawRoundRect(rect, r, r, body)
        canvas.drawRoundRect(rect, r, r, sheen)
        if (glow.shader != null) canvas.drawRoundRect(rect, r, r, glow)
        val inset = rim.strokeWidth / 2f
        rect.inset(inset, inset)
        canvas.drawRoundRect(rect, r - inset, r - inset, rim)
    }

    override fun getOutline(outline: Outline) {
        val b = bounds
        outline.setRoundRect(b, minOf(radius, b.height() / 2f, b.width() / 2f))
        outline.alpha = Color.alpha(fill) / 255f * 0.6f
    }

    override fun setAlpha(alpha: Int) {
        alphaValue = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        listOf(body, rim, sheen, glow).forEach { it.colorFilter = colorFilter }
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
