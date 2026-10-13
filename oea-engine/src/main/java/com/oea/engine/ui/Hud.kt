package com.oea.engine.ui

import com.oea.engine.graphics.Align
import com.oea.engine.graphics.BitmapFont
import com.oea.engine.graphics.Graphics
import com.oea.engine.graphics.SpriteBatch
import com.oea.engine.input.Input
import com.oea.engine.math.Colors
import com.oea.engine.math.MathX

enum class ButtonStyle { PRIMARY, SECONDARY, DANGER, GHOST }

/**
 * Immediate-mode HUD drawn at native resolution: text, panels, bars and
 * buttons. Widgets report their hit areas to [Input], so a touch that starts
 * on a button never also moves the player.
 *
 * ```
 * frame.ui { hud ->
 *     hud.text("Score $score", hud.width - hud.dp(16f), hud.safeTop + hud.dp(12f), 22f, align = Align.RIGHT)
 *     if (hud.button("pause", "II", ...)) paused = true
 * }
 * ```
 * Sizes passed as `*Dp` are density-independent.
 */
class Hud internal constructor(private val graphics: Graphics, private val input: Input) {
    val batch: SpriteBatch get() = graphics.sprites
    val font: BitmapFont get() = graphics.font
    val fontBold: BitmapFont get() = graphics.fontBold
    val density get() = graphics.density
    var width = 0f; private set
    var height = 0f; private set
    var accent: Int = 0xFF4FC3F7.toInt()
    val safeLeft get() = graphics.safeInsets[0].toFloat()
    val safeTop get() = graphics.safeInsets[1].toFloat()
    val safeRight get() = graphics.safeInsets[2].toFloat()
    val safeBottom get() = graphics.safeInsets[3].toFloat()

    private var activeId: String? = null
    private var activePointer = -1

    fun dp(v: Float) = v * graphics.density

    internal fun begin(w: Float, h: Float) {
        width = w; height = h
        // Forget a pressed widget whose finger lifted elsewhere.
        if (activeId != null && input.pointer(activePointer)?.isDown != true && input.pointer(activePointer)?.justReleased != true) {
            activeId = null; activePointer = -1
        }
    }

    internal fun end() = Unit

    // ---------------------------------------------------------------- drawing

    /** Text with its top at [y]; [sizeDp] is the font size in dp. */
    fun text(text: CharSequence, x: Float, y: Float, sizeDp: Float, color: Int = Colors.WHITE, align: Align = Align.LEFT, bold: Boolean = false, shadow: Boolean = true) {
        val f = if (bold) fontBold else font
        val size = dp(sizeDp)
        if (shadow) f.drawShadowed(batch, text, x, y, size, color, align) else f.draw(batch, text, x, y, size, color, align)
    }

    fun measure(text: CharSequence, sizeDp: Float, bold: Boolean = false): Float = (if (bold) fontBold else font).measure(text, dp(sizeDp))
    fun lineHeight(sizeDp: Float, bold: Boolean = false): Float = (if (bold) fontBold else font).lineHeight(dp(sizeDp))

    fun panel(x: Float, y: Float, w: Float, h: Float, color: Int = 0xB80E1116.toInt(), radiusDp: Float = 16f) =
        batch.roundRect(x, y, w, h, dp(radiusDp), color)

    /** Panel that also blocks touches from reaching the game. */
    fun solidPanel(x: Float, y: Float, w: Float, h: Float, color: Int = 0xE60E1116.toInt(), radiusDp: Float = 20f) {
        panel(x, y, w, h, color, radiusDp)
        input.addUiRect(x, y, w, h)
    }

    fun bar(x: Float, y: Float, w: Float, h: Float, value: Float, fg: Int, bg: Int = 0x66000000, radius: Float = h / 2f) {
        batch.roundRect(x, y, w, h, radius, bg)
        val v = MathX.clamp01(value)
        if (v > 0f) batch.roundRect(x, y, maxOf(h, w * v), h, radius, fg)
    }

    /** Full-screen dim layer, e.g. behind a pause or results panel; blocks game touches. */
    fun dim(alpha: Float = 0.55f) {
        batch.rect(0f, 0f, width, height, Colors.withAlpha(0xFF000000.toInt(), alpha))
        input.addUiRect(0f, 0f, width, height)
    }

    // ---------------------------------------------------------------- widgets

    /** A button; returns true on the frame it is clicked (finger released inside). */
    fun button(id: String, label: String, x: Float, y: Float, w: Float, h: Float, style: ButtonStyle = ButtonStyle.PRIMARY, enabled: Boolean = true, sizeDp: Float = 16f): Boolean {
        input.addUiRect(x, y, w, h)
        val clicked = interact(id, x, y, w, h, enabled)
        val pressed = activeId == id && input.pointer(activePointer)?.isDown == true
        val (bg, fg, stroke) = when (style) {
            ButtonStyle.PRIMARY -> Triple(accent, if (Colors.luminance(accent) > 0.55f) 0xFF0B0E12.toInt() else Colors.WHITE, 0)
            ButtonStyle.SECONDARY -> Triple(0xCC1C2129.toInt(), Colors.WHITE, 0x33FFFFFF)
            ButtonStyle.DANGER -> Triple(0xFFE5484D.toInt(), Colors.WHITE, 0)
            ButtonStyle.GHOST -> Triple(0x22FFFFFF, Colors.WHITE, 0x66FFFFFF)
        }
        val a = if (enabled) 1f else 0.4f
        val r = minOf(h / 2f, dp(18f))
        if (stroke != 0) batch.roundRect(x - 1f, y - 1f, w + 2f, h + 2f, r + 1f, Colors.withAlpha(stroke, Colors.alpha(stroke) / 255f * a))
        batch.roundRect(x, y, w, h, r, Colors.withAlpha(if (pressed) Colors.scale(bg, 0.82f) else bg, Colors.alpha(bg) / 255f * a))
        val size = dp(sizeDp)
        fontBold.draw(batch, label, x + w / 2f, y + (h - fontBold.lineHeight(size)) / 2f, size, Colors.withAlpha(fg, a), Align.CENTER)
        return clicked
    }

    /** Round icon button centred at (cx, cy). */
    fun iconButton(id: String, glyph: String, cx: Float, cy: Float, radiusDp: Float = 22f, color: Int = 0xAA10141A.toInt(), enabled: Boolean = true): Boolean {
        val r = dp(radiusDp)
        input.addUiRect(cx - r, cy - r, r * 2f, r * 2f)
        val clicked = interact(id, cx - r, cy - r, r * 2f, r * 2f, enabled)
        val pressed = activeId == id && input.pointer(activePointer)?.isDown == true
        batch.circle(cx, cy, r, if (pressed) Colors.scale(color, 1.6f) else color)
        batch.ring(cx, cy, r, dp(1.2f), 0x44FFFFFF)
        val size = r * 0.9f
        fontBold.draw(batch, glyph, cx, cy - fontBold.lineHeight(size) / 2f, size, if (enabled) Colors.WHITE else 0x66FFFFFF, Align.CENTER)
        return clicked
    }

    /** Invisible touch area (e.g. a board square); returns true when tapped. */
    fun hotspot(id: String, x: Float, y: Float, w: Float, h: Float): Boolean {
        input.addUiRect(x, y, w, h)
        return interact(id, x, y, w, h, true)
    }

    private fun interact(id: String, x: Float, y: Float, w: Float, h: Float, enabled: Boolean): Boolean {
        if (!enabled) return false
        for (p in input.pointers) {
            if (p.justPressed && p.onUi && p.owner == null && inside(p.x, p.y, x, y, w, h) && activeId == null) {
                activeId = id; activePointer = p.id
                p.owner = this
            }
        }
        if (activeId != id) return false
        val p = input.pointer(activePointer) ?: run { activeId = null; return false }
        if (p.justReleased) {
            activeId = null; activePointer = -1
            p.owner = null
            return inside(p.x, p.y, x, y, w, h)
        }
        return false
    }

    private fun inside(px: Float, py: Float, x: Float, y: Float, w: Float, h: Float) = px >= x && px <= x + w && py >= y && py <= y + h
}
