package com.oea.launcher.games

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import com.oea.engine.core.GameDescriptor
import com.oea.launcher.ui.OeaPalette
import com.oea.launcher.ui.OeaTheme
import kotlin.math.sin

/** Dark, console-style palette for OEA Games (amber accent, distinct from Game Boost's blue). */
object OeaGamesTheme {
    const val ACCENT = 0xFFFFA53D.toInt()

    fun palette(): OeaPalette {
        val background = Color.rgb(8, 9, 13)
        val card = Color.rgb(20, 22, 30)
        return OeaPalette(
            dark = true,
            accent = ACCENT,
            onAccent = Color.rgb(20, 12, 4),
            accentSoft = OeaTheme.blend(card, ACCENT, 0.2f),
            onAccentSoft = OeaTheme.blend(Color.WHITE, ACCENT, 0.3f),
            background = background,
            drawer = background,
            card = card,
            cardHigh = Color.rgb(32, 35, 46),
            stroke = OeaTheme.alpha(Color.WHITE, 0x16),
            text = Color.rgb(242, 244, 248),
            textSecondary = Color.rgb(158, 164, 178),
            textTertiary = Color.rgb(104, 110, 124),
            homeText = Color.WHITE,
            homeTextSecondary = Color.WHITE,
            homeShadow = Color.BLACK,
            wallpaperLight = false,
            glass = OeaTheme.alpha(Color.WHITE, 0x1A),
            glassStrong = OeaTheme.alpha(Color.BLACK, 0x99),
            glassStroke = OeaTheme.alpha(Color.WHITE, 0x22),
            scrimTop = Color.TRANSPARENT,
            scrimBottom = Color.TRANSPARENT,
            danger = Color.rgb(255, 110, 120),
            success = Color.rgb(80, 225, 160),
            warning = Color.rgb(255, 196, 100),
        )
    }
}

/**
 * Procedural cover art for a game card: the game's accent as a lit gradient,
 * a motif per game and the glyph, so new games get a good-looking card
 * without shipping images.
 */
class GameCoverDrawable(private val game: GameDescriptor, private val radiusPx: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-black", Typeface.BOLD); textAlign = Paint.Align.CENTER }
    private val clip = Path()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat(); val h = b.height().toFloat()
        val r = RectF(b)
        clip.reset(); clip.addRoundRect(r, radiusPx, radiusPx, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        val dark = OeaTheme.blend(Color.rgb(8, 9, 13), game.accent, 0.35f)
        val light = OeaTheme.blend(game.accent, Color.WHITE, 0.08f)
        paint.shader = LinearGradient(0f, h, w, 0f, intArrayOf(dark, OeaTheme.blend(dark, light, 0.55f), light), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(r, paint)
        paint.shader = null
        when (game.id) {
            "chess" -> {
                val s = h / 6f
                for (row in 0 until 7) for (col in 0 until 16) if ((row + col) % 2 == 0) {
                    paint.color = OeaTheme.alpha(Color.WHITE, 0x14)
                    canvas.drawRect(b.left + col * s, b.top + row * s, b.left + (col + 1) * s, b.top + (row + 1) * s, paint)
                }
            }
            "runner" -> {
                paint.strokeWidth = h * 0.02f
                for (i in 0 until 9) {
                    paint.color = OeaTheme.alpha(Color.WHITE, 0x22 - i * 2)
                    val y = b.top + h * (0.2f + i * 0.09f)
                    canvas.drawLine(b.left - w * 0.1f + i * 18f, y, b.left + w * (0.55f + i * 0.04f), y - h * 0.05f, paint)
                }
            }
            "arena" -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = h * 0.018f
                for (i in 1..4) {
                    paint.color = OeaTheme.alpha(Color.WHITE, 0x30 - i * 6)
                    canvas.drawCircle(b.left + w * 0.72f, b.top + h * 0.5f, h * 0.16f * i, paint)
                }
                paint.style = Paint.Style.FILL
            }
            else -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = h * 0.012f
                paint.color = OeaTheme.alpha(Color.WHITE, 0x26)
                for (i in 0 until 12) {
                    val x = b.left + w * (0.4f + 0.05f * i)
                    canvas.drawLine(x, b.top + h * (0.5f + 0.4f * sin(i.toFloat())), x + w * 0.1f, b.top + h * (0.5f - 0.4f * sin(i * 1.7f)), paint)
                }
                paint.style = Paint.Style.FILL
            }
        }
        paint.shader = RadialGradient(b.left + w * 0.78f, b.top + h * 0.45f, h * 0.7f, OeaTheme.alpha(Color.WHITE, 0x44), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawRect(r, paint)
        paint.shader = null
        glyphPaint.textSize = h * 0.62f
        glyphPaint.color = OeaTheme.alpha(Color.WHITE, 0xE6)
        glyphPaint.setShadowLayer(h * 0.06f, 0f, h * 0.02f, OeaTheme.alpha(Color.BLACK, 0x66))
        canvas.drawText(game.glyph, b.left + w * 0.78f, b.top + h * 0.5f - (glyphPaint.ascent() + glyphPaint.descent()) / 2f, glyphPaint)
        paint.shader = LinearGradient(0f, b.top + h * 0.45f, 0f, b.bottom.toFloat(), Color.TRANSPARENT, OeaTheme.alpha(Color.BLACK, 0x99), Shader.TileMode.CLAMP)
        canvas.drawRect(r, paint)
        paint.shader = null
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
