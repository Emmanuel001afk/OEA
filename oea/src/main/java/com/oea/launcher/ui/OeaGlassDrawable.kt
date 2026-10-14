package com.oea.launcher.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
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
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.min

/**
 * OEA's liquid glass surface, built the way a pane of glass actually behaves.
 *
 * A single translucent fill reads as "grey rectangle". Real glass shows several
 * things at once, and each one is a pass here, so a liquid surface looks like a
 * material rather than a colour:
 *
 *  1. **Body** — a vertical gradient, light at the top and deeper at the bottom,
 *     so the pane has a hanging weight.
 *  2. **Specular** — an off-centre highlight where the light source would be,
 *     which [specular] slides and [press] grows.
 *  3. **Lensing band** — a thin dark stroke just inside the rim, strongest at the
 *     top: what the eye reads as thickness and refraction.
 *  4. **Rim** — a bright, top-heavy edge, plus an outer hairline so the pane
 *     separates from whatever sits behind it.
 *  5. **Grain** — a tiled frosted noise at very low alpha, which stops large flat
 *     areas from banding into visible steps.
 *
 * It is all gradients, two strokes and one shared 48×48 noise tile: nothing is
 * blurred per instance and no bitmap is allocated per surface, so a liquid Home
 * costs about what the plain rounded shape cost.
 *
 * Motion is what separates liquid glass from a static blob, so the surface can be
 * animated without allocating: [press] (feedback under a finger), [specular]
 * (light following a drag) and [liquify] (bending while a page moves).
 */
class OeaGlassDrawable(
    private val fill: Int,
    private val radius: Float,
    private val density: Float,
    private val tint: Int = 0,
    private val rimAlpha: Int = 0xB0,
) : Drawable() {
    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lens = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val outer = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grain = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = noiseShader() }
    private val rect = RectF()
    private var alphaValue = 255

    /** Where the light hits the pane, 0 = left edge, 1 = right edge. */
    private var lightX = 0.32f
    private var lightY = 0.06f

    /** Live press state: 0 at rest, 1 fully pressed. */
    private var pressAmount = 0f

    /** How far the pane is bent by motion, −1..1; 0 leaves it flat. */
    private var bend = 0f

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        rebuild()
    }

    /** Recomputes every shader from the current bounds and state. */
    private fun rebuild() {
        val b = bounds
        if (b.isEmpty) return
        val top = b.top.toFloat()
        val bottom = b.bottom.toFloat()
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        val pressed = pressAmount
        val alpha = Color.alpha(fill)

        // 1. Body: lighter at the top, deeper at the bottom, deeper still while a
        //    finger is down, which is what the finger's own shadow does.
        val lighter = OeaTheme.blend(fill, Color.argb(alpha, 255, 255, 255), 0.11f + 0.05f * pressed)
        val deeper = OeaTheme.blend(fill, Color.argb(alpha, 0, 0, 0), 0.10f + 0.10f * pressed)
        body.shader = LinearGradient(
            0f, top, 0f, bottom,
            intArrayOf(lighter, fill, deeper),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP,
        )

        // 2. Specular: an off-centre blob that grows with pressure and slides with
        //    [specular].
        val strong = (0x3A + 0x26 * pressed).toInt().coerceAtMost(0xFF)
        sheen.shader = RadialGradient(
            b.left + w * lightX,
            top + h * lightY,
            maxOf(w, h) * (0.52f + 0.16f * pressed),
            intArrayOf(Color.argb(strong, 255, 255, 255), Color.argb(0, 255, 255, 255)),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )

        // 3. Lensing: dark just inside the top edge, bright just inside the
        //    bottom, so the pane reads as thick.
        lens.strokeWidth = maxOf(1f, density * 1.6f)
        lens.shader = LinearGradient(
            0f, top, 0f, bottom,
            intArrayOf(
                Color.argb((0x38 + 0x28 * (1f - pressed)).toInt(), 0, 0, 0),
                Color.argb(0, 0, 0, 0),
                Color.argb(0x1E, 255, 255, 255),
            ),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )

        // 4. Rim: bright on top, dimmer at the bottom, a hair tighter while
        //    pressed; the outer hairline sits outside it.
        rim.strokeWidth = maxOf(1f, density * (1.1f - 0.25f * pressed))
        rim.shader = LinearGradient(
            0f, top, 0f, bottom,
            intArrayOf(
                Color.argb(rimAlpha, 255, 255, 255),
                Color.argb(rimAlpha / 4, 255, 255, 255),
                Color.argb(rimAlpha / 3, 255, 255, 255),
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        outer.strokeWidth = maxOf(1f, density * 0.8f)
        outer.shader = LinearGradient(
            0f, top, 0f, bottom,
            intArrayOf(Color.argb(0x2E, 0, 0, 0), Color.argb(0x14, 0, 0, 0)),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )

        // Accent bleed along the bottom edge, so the pane picks up the scene.
        glow.shader = if (tint != 0) LinearGradient(
            0f, bottom - h * 0.45f, 0f, bottom,
            Color.argb(0, Color.red(tint), Color.green(tint), Color.blue(tint)),
            Color.argb(0x30, Color.red(tint), Color.green(tint), Color.blue(tint)),
            Shader.TileMode.CLAMP,
        ) else null
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val r = min(radius, min(b.height() / 2f, b.width() / 2f))
        listOf(body, grain, sheen, glow, lens, rim, outer).forEach { it.alpha = alphaValue }

        // A motion bend squashes the pane vertically around its centre: the
        // cheapest convincing way to say "fluid" rather than "card".
        val squeeze = 1f - 0.03f * abs(bend)

        rect.set(b)
        bendVertically(rect, squeeze)
        val rr = min(r, rect.height() / 2f)
        canvas.drawRoundRect(rect, rr, rr, body)
        canvas.drawRoundRect(rect, rr, rr, grain)
        canvas.drawRoundRect(rect, rr, rr, sheen)
        if (glow.shader != null) canvas.drawRoundRect(rect, rr, rr, glow)

        // 3. Lensing band, inside the bright rim.
        val lensInset = lens.strokeWidth / 2f + rim.strokeWidth * 1.6f
        rect.set(b)
        bendVertically(rect, squeeze)
        rect.inset(lensInset, lensInset)
        val lensRadius = (rr - lensInset).coerceAtLeast(0f)
        if (rect.height() >= lensRadius * 2f && rect.width() >= lensRadius * 2f) {
            canvas.drawRoundRect(rect, lensRadius, lensRadius, lens)
        }

        // 4a. Bright rim, inset by half its width so it stays inside the bounds.
        val rimInset = rim.strokeWidth / 2f
        rect.set(b)
        bendVertically(rect, squeeze)
        rect.inset(rimInset, rimInset)
        val rimRadius = (rr - rimInset).coerceAtLeast(0f)
        canvas.drawRoundRect(rect, rimRadius, rimRadius, rim)

        // 4b. Outer hairline, so the pane separates from the wallpaper behind it.
        rect.set(b)
        val outerInset = outer.strokeWidth / 2f
        rect.inset(outerInset, outerInset)
        val outerRadius = (rr - outerInset).coerceAtLeast(0f)
        canvas.drawRoundRect(rect, outerRadius, outerRadius, outer)
    }

    /** Squashes a rectangle towards its own centre line. */
    private fun bendVertically(target: RectF, squeeze: Float) {
        if (squeeze == 1f) return
        val centre = target.centerY()
        target.top = centre + (target.top - centre) * squeeze
        target.bottom = centre + (target.bottom - centre) * squeeze
    }

    override fun getOutline(outline: Outline) {
        val b = bounds
        outline.setRoundRect(b, min(radius, min(b.height() / 2f, b.width() / 2f)))
        outline.alpha = Color.alpha(fill) / 255f * 0.6f
    }

    override fun setAlpha(alpha: Int) {
        alphaValue = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        listOf(body, grain, sheen, glow, lens, rim, outer).forEach { it.colorFilter = colorFilter }
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    // ------------------------------------------------------------------ motion

    /**
     * Press feedback: 0 at rest, 1 fully pressed. The drawable only rebuilds its
     * shaders when the value moves by a visible step, so a slow animation is not
     * a repaint storm.
     */
    fun press(fraction: Float) {
        val clamped = fraction.coerceIn(0f, 1f)
        if (abs(clamped - pressAmount) < 0.02f) return
        pressAmount = clamped
        rebuild()
        invalidateSelf()
    }

    /** Moves the specular highlight, 0 = left, 1 = right. */
    fun specular(x: Float, y: Float = lightY) {
        val nextX = x.coerceIn(-0.2f, 1.2f)
        val nextY = y.coerceIn(-0.2f, 1.2f)
        if (abs(nextX - lightX) < 0.02f && abs(nextY - lightY) < 0.02f) return
        lightX = nextX
        lightY = nextY
        rebuild()
        invalidateSelf()
    }

    /** Bends the pane while its page moves, −1..1. */
    fun liquify(amount: Float) {
        val clamped = amount.coerceIn(-1f, 1f)
        if (abs(clamped - bend) < 0.01f) return
        bend = clamped
        invalidateSelf()
    }

    /** Returns the surface to rest; called when a touch is released or a page settles. */
    fun reset() {
        if (pressAmount == 0f && bend == 0f) return
        pressAmount = 0f
        bend = 0f
        rebuild()
        invalidateSelf()
    }

    companion object {
        /** One frosted noise tile, shared by every liquid surface in the process. */
        @Volatile private var noiseTile: Bitmap? = null

        private fun noiseShader(): Shader {
            val tile = noiseTile ?: buildNoise() ?: return LinearGradient(
                0f, 0f, 0f, 1f, Color.TRANSPARENT, Color.TRANSPARENT, Shader.TileMode.CLAMP,
            )
            return BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }

        /**
         * A 48×48 tile where roughly 2% of the pixels carry a whisper of alpha. It
         * exists only to break banding in the body gradient, so it is deliberately
         * almost invisible; the same tile is reused for every surface, which is why
         * the grain never shimmers as views are recycled.
         */
        private fun buildNoise(): Bitmap? = runCatching {
            val size = 48
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
            val pixels = ByteArray(size * size)
            var seed = 0x2F6E2B1
            for (i in pixels.indices) {
                seed = seed * 1103515245 + 12345
                val value = (seed ushr 16) and 0xFF
                pixels[i] = if (value < 6) ((value * 2) and 0x0F).toByte() else 0
            }
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels))
            noiseTile = bitmap
            bitmap
        }.getOrNull()
    }
}
