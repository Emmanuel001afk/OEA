package com.oea.engine.graphics

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.opengl.GLES30
import com.oea.engine.math.Colors
import com.oea.engine.math.Mat4
import com.oea.engine.math.MathX
import java.nio.IntBuffer
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

enum class Align { LEFT, CENTER, RIGHT }

/**
 * Batched 2D quads with premultiplied alpha. Used for HUDs (pixel
 * coordinates, origin top-left) and 2D worlds ([Camera2D]). Consecutive draws
 * with the same texture become one draw call.
 */
class SpriteBatch internal constructor() {
    private val maxQuads = 4096
    private val staging = IntArray(maxQuads * 4 * 5)
    private var quads = 0
    private var texture: Texture? = null
    private var buffer: IntBuffer? = null
    private var vao = 0
    private var vbo = 0
    private var ibo = 0
    private var generation = -1
    private var drawing = false
    private val shader = Shader("sprites", Shaders.SPRITE_VERT, Shaders.SPRITE_FRAG)
    private val proj = Mat4()
    var drawCalls = 0
        private set

    /** Shared textures for shapes. */
    val white get() = Textures.white
    private val circleTex by lazy { Textures.circle(256) }
    private val panelTex by lazy { Textures.roundedPanel(64) }
    private val glowTex by lazy { Textures.softCircle(128) }

    /** Starts a batch with pixel coordinates (origin top-left). */
    fun beginPixels(width: Float, height: Float) {
        proj.ortho(0f, width, height, 0f, -1f, 1f)
        begin(proj)
    }

    fun begin(projection: Mat4) {
        if (drawing) end()
        if (projection !== proj) proj.set(projection)
        drawing = true
        quads = 0
        texture = null
        drawCalls = 0
        ensureBuffers()
        shader.bind()
        shader.setMat4("u_proj", proj.m)
        shader.set1i("u_tex", 0)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
    }

    fun end() {
        if (!drawing) return
        flush()
        drawing = false
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
    }

    fun flush() {
        if (quads == 0) return
        val tex = texture ?: white
        tex.bind(0)
        val buf = buffer!!
        buf.position(0)
        buf.put(staging, 0, quads * 20)
        buf.position(0)
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, maxQuads * 80, null, GLES30.GL_STREAM_DRAW)
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, quads * 80, buf)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, quads * 6, GLES30.GL_UNSIGNED_SHORT, 0)
        GLES30.glBindVertexArray(0)
        drawCalls++
        quads = 0
    }

    private fun ensureBuffers() {
        if (generation == Gpu.generation && vao != 0) return
        generation = Gpu.generation
        if (buffer == null) buffer = Gpu.intBuffer(maxQuads * 20)
        vao = Gpu.genVertexArray()
        vbo = Gpu.genBuffer()
        ibo = Gpu.genBuffer()
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, maxQuads * 80, null, GLES30.GL_STREAM_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 20, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 20, 8)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 4, GLES30.GL_UNSIGNED_BYTE, true, 20, 16)
        val idx = Gpu.shortBuffer(maxQuads * 6)
        for (q in 0 until maxQuads) {
            val b = q * 4
            idx.put(b.toShort()); idx.put((b + 1).toShort()); idx.put((b + 2).toShort())
            idx.put(b.toShort()); idx.put((b + 2).toShort()); idx.put((b + 3).toShort())
        }
        idx.position(0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, maxQuads * 12, idx, GLES30.GL_STATIC_DRAW)
        GLES30.glBindVertexArray(0)
    }

    private fun use(tex: Texture) {
        if (texture !== tex) {
            flush()
            texture = tex
        }
        if (quads >= maxQuads) flush()
    }

    private fun put(x: Float, y: Float, u: Float, v: Float, abgr: Int, o: Int) {
        staging[o] = java.lang.Float.floatToRawIntBits(x)
        staging[o + 1] = java.lang.Float.floatToRawIntBits(y)
        staging[o + 2] = java.lang.Float.floatToRawIntBits(u)
        staging[o + 3] = java.lang.Float.floatToRawIntBits(v)
        staging[o + 4] = abgr
    }

    /** Arbitrary textured quad (corners in order: top-left, top-right, bottom-right, bottom-left) with per-corner colours. */
    fun quad(
        tex: Texture,
        x0: Float, y0: Float, u0: Float, v0: Float, c0: Int,
        x1: Float, y1: Float, u1: Float, v1: Float, c1: Int,
        x2: Float, y2: Float, u2: Float, v2: Float, c2: Int,
        x3: Float, y3: Float, u3: Float, v3: Float, c3: Int,
    ) {
        use(tex)
        val o = quads * 20
        put(x0, y0, u0, v0, abgr(c0), o)
        put(x1, y1, u1, v1, abgr(c1), o + 5)
        put(x2, y2, u2, v2, abgr(c2), o + 10)
        put(x3, y3, u3, v3, abgr(c3), o + 15)
        quads++
    }

    fun draw(tex: Texture, x: Float, y: Float, w: Float, h: Float, color: Int = Colors.WHITE, u0: Float = 0f, v0: Float = 0f, u1: Float = 1f, v1: Float = 1f) =
        quad(tex, x, y, u0, v0, color, x + w, y, u1, v0, color, x + w, y + h, u1, v1, color, x, y + h, u0, v1, color)

    /** Rotated sprite around its centre (radians). */
    fun drawRotated(tex: Texture, cx: Float, cy: Float, w: Float, h: Float, rotation: Float, color: Int = Colors.WHITE) {
        val c = cos(rotation); val s = sin(rotation)
        val hw = w / 2f; val hh = h / 2f
        fun rx(x: Float, y: Float) = cx + x * c - y * s
        fun ry(x: Float, y: Float) = cy + x * s + y * c
        quad(tex, rx(-hw, -hh), ry(-hw, -hh), 0f, 0f, color, rx(hw, -hh), ry(hw, -hh), 1f, 0f, color,
            rx(hw, hh), ry(hw, hh), 1f, 1f, color, rx(-hw, hh), ry(-hw, hh), 0f, 1f, color)
    }

    fun rect(x: Float, y: Float, w: Float, h: Float, color: Int) = draw(white, x, y, w, h, color)

    fun gradientRect(x: Float, y: Float, w: Float, h: Float, top: Int, bottom: Int) =
        quad(white, x, y, 0f, 0f, top, x + w, y, 1f, 0f, top, x + w, y + h, 1f, 1f, bottom, x, y + h, 0f, 1f, bottom)

    /** Anti-aliased rounded rectangle (9-slice of a round texture). */
    fun roundRect(x: Float, y: Float, w: Float, h: Float, radius: Float, color: Int) {
        val r = minOf(radius, w / 2f, h / 2f).coerceAtLeast(0f)
        if (r < 0.5f) { rect(x, y, w, h, color); return }
        val t = panelTex
        val xs = floatArrayOf(x, x + r, x + w - r, x + w)
        val ys = floatArrayOf(y, y + r, y + h - r, y + h)
        val us = floatArrayOf(0f, 0.5f, 0.5f, 1f)
        for (j in 0 until 3) for (i in 0 until 3) {
            if (xs[i + 1] <= xs[i] || ys[j + 1] <= ys[j]) continue
            draw(t, xs[i], ys[j], xs[i + 1] - xs[i], ys[j + 1] - ys[j], color, us[i], us[j], us[i + 1], us[j + 1])
        }
    }

    fun circle(cx: Float, cy: Float, r: Float, color: Int) = draw(circleTex, cx - r, cy - r, r * 2f, r * 2f, color)

    /** Soft radial glow. */
    fun glow(cx: Float, cy: Float, r: Float, color: Int) = draw(glowTex, cx - r, cy - r, r * 2f, r * 2f, color)

    /** Anti-aliased line with a 1px feather. */
    fun line(x1: Float, y1: Float, x2: Float, y2: Float, thickness: Float, color: Int) {
        val dx = x2 - x1; val dy = y2 - y1
        val len = sqrt(dx * dx + dy * dy)
        if (len < 1e-3f) return
        val nx = -dy / len; val ny = dx / len
        val h = thickness / 2f
        val f = 1f
        val clear = Colors.withAlpha(color, 0)
        quad(white, x1 + nx * h, y1 + ny * h, 0f, 0f, color, x2 + nx * h, y2 + ny * h, 0f, 0f, color,
            x2 - nx * h, y2 - ny * h, 0f, 0f, color, x1 - nx * h, y1 - ny * h, 0f, 0f, color)
        quad(white, x1 + nx * (h + f), y1 + ny * (h + f), 0f, 0f, clear, x2 + nx * (h + f), y2 + ny * (h + f), 0f, 0f, clear,
            x2 + nx * h, y2 + ny * h, 0f, 0f, color, x1 + nx * h, y1 + ny * h, 0f, 0f, color)
        quad(white, x1 - nx * h, y1 - ny * h, 0f, 0f, color, x2 - nx * h, y2 - ny * h, 0f, 0f, color,
            x2 - nx * (h + f), y2 - ny * (h + f), 0f, 0f, clear, x1 - nx * (h + f), y1 - ny * (h + f), 0f, 0f, clear)
    }

    /**
     * Anti-aliased arc (progress rings, timers). Angles in radians, 0 = up,
     * clockwise positive.
     */
    fun arc(cx: Float, cy: Float, radius: Float, thickness: Float, start: Float, sweep: Float, color: Int) {
        if (sweep == 0f) return
        val segs = max(6, ceil(kotlin.math.abs(sweep) / MathX.TAU * 72f).toInt())
        val inner = radius - thickness / 2f
        val outer = radius + thickness / 2f
        val clear = Colors.withAlpha(color, 0)
        for (i in 0 until segs) {
            val a0 = start + sweep * i / segs
            val a1 = start + sweep * (i + 1) / segs
            val s0 = sin(a0); val c0 = -cos(a0); val s1 = sin(a1); val c1 = -cos(a1)
            quad(white, cx + s0 * outer, cy + c0 * outer, 0f, 0f, color, cx + s1 * outer, cy + c1 * outer, 0f, 0f, color,
                cx + s1 * inner, cy + c1 * inner, 0f, 0f, color, cx + s0 * inner, cy + c0 * inner, 0f, 0f, color)
            quad(white, cx + s0 * (outer + 1f), cy + c0 * (outer + 1f), 0f, 0f, clear, cx + s1 * (outer + 1f), cy + c1 * (outer + 1f), 0f, 0f, clear,
                cx + s1 * outer, cy + c1 * outer, 0f, 0f, color, cx + s0 * outer, cy + c0 * outer, 0f, 0f, color)
            quad(white, cx + s0 * inner, cy + c0 * inner, 0f, 0f, color, cx + s1 * inner, cy + c1 * inner, 0f, 0f, color,
                cx + s1 * (inner - 1f), cy + c1 * (inner - 1f), 0f, 0f, clear, cx + s0 * (inner - 1f), cy + c0 * (inner - 1f), 0f, 0f, clear)
        }
    }

    fun ring(cx: Float, cy: Float, radius: Float, thickness: Float, color: Int) = arc(cx, cy, radius, thickness, 0f, MathX.TAU, color)

    /** Filled convex polygon (triangle fan) from interleaved x,y pairs. */
    fun polygon(points: FloatArray, color: Int) {
        val n = points.size / 2
        if (n < 3) return
        for (i in 1 until n - 1) {
            quad(white, points[0], points[1], 0f, 0f, color, points[i * 2], points[i * 2 + 1], 0f, 0f, color,
                points[i * 2 + 2], points[i * 2 + 3], 0f, 0f, color, points[i * 2 + 2], points[i * 2 + 3], 0f, 0f, color)
        }
    }

    fun text(font: BitmapFont, text: CharSequence, x: Float, y: Float, size: Float, color: Int, align: Align = Align.LEFT) =
        font.draw(this, text, x, y, size, color, align)

    companion object {
        /** ARGB → bytes R,G,B,A in memory (little-endian int ABGR). */
        fun abgr(c: Int): Int = (c and 0xFF00FF00.toInt()) or ((c shr 16) and 0xFF) or ((c and 0xFF) shl 16)
    }
}

/**
 * Glyph atlas baked from an Android [Typeface] at [bakeSize] px, drawn with
 * mipmaps so text stays crisp from small HUD labels to large titles.
 */
class BitmapFont(typeface: Typeface = Typeface.DEFAULT, val bakeSize: Int = 64, extraChars: String = DEFAULT_EXTRA) {
    private class Glyph(val u0: Float, val v0: Float, val u1: Float, val v1: Float, val w: Float, val h: Float, val xOff: Float, val yOff: Float, val advance: Float)

    private val glyphs = HashMap<Char, Glyph>()
    private val ascent: Float
    private val lineHeightBake: Float
    val texture: Texture

    init {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            this.typeface = typeface
            textSize = bakeSize.toFloat()
            color = Color.WHITE
        }
        val fm = paint.fontMetrics
        ascent = -fm.ascent
        lineHeightBake = fm.descent - fm.ascent + fm.leading
        val chars = buildString {
            for (c in 32..126) append(c.toChar())
            extraChars.forEach { if (paint.hasGlyph(it.toString())) append(it) }
        }
        val pad = 4
        val atlasW = 1024
        // Layout pass.
        data class Place(val c: Char, val x: Int, val y: Int, val b: Rect, val adv: Float)
        val places = ArrayList<Place>()
        var penX = pad; var penY = pad; var rowH = 0
        val bounds = Rect()
        for (c in chars) {
            val s = c.toString()
            paint.getTextBounds(s, 0, 1, bounds)
            val gw = max(1, bounds.width()) + 2
            val gh = max(1, bounds.height()) + 2
            if (penX + gw + pad > atlasW) { penX = pad; penY += rowH + pad; rowH = 0 }
            places += Place(c, penX, penY, Rect(bounds), paint.measureText(s))
            penX += gw + pad
            rowH = max(rowH, gh)
        }
        val atlasH = MathX.nextPowerOfTwo(penY + rowH + pad)
        for (p in places) {
            val gw = max(1, p.b.width()) + 2
            val gh = max(1, p.b.height()) + 2
            glyphs[p.c] = Glyph(
                p.x / atlasW.toFloat(), p.y / atlasH.toFloat(), (p.x + gw) / atlasW.toFloat(), (p.y + gh) / atlasH.toFloat(),
                gw.toFloat(), gh.toFloat(), p.b.left - 1f, ascent + p.b.top - 1f, p.adv,
            )
        }
        texture = Texture.generated(mipmaps = true) {
            val bmp = Bitmap.createBitmap(atlasW, atlasH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            for (p in places) {
                canvas.drawText(p.c.toString(), (p.x - p.b.left + 1).toFloat(), (p.y - p.b.top + 1).toFloat(), paint)
            }
            bmp
        }
    }

    fun lineHeight(size: Float) = lineHeightBake * size / bakeSize

    /** Width in pixels of the widest line of [text] at [size] px. */
    fun measure(text: CharSequence, size: Float): Float {
        val scale = size / bakeSize
        var best = 0f; var w = 0f
        for (ch in text) {
            if (ch == '\n') { best = max(best, w); w = 0f; continue }
            w += (glyphs[ch] ?: glyphs['?'])?.advance ?: 0f
        }
        return max(best, w) * scale
    }

    /** Draws [text] with its top edge at [y]. Multi-line text is aligned per line. */
    fun draw(batch: SpriteBatch, text: CharSequence, x: Float, y: Float, size: Float, color: Int, align: Align = Align.LEFT) {
        val scale = size / bakeSize
        var lineStart = 0
        var lineY = y
        val tex = texture
        while (lineStart <= text.length) {
            var lineEnd = lineStart
            while (lineEnd < text.length && text[lineEnd] != '\n') lineEnd++
            val line = text.subSequence(lineStart, lineEnd)
            val width = measure(line, size)
            var penX = when (align) {
                Align.LEFT -> x
                Align.CENTER -> x - width / 2f
                Align.RIGHT -> x - width
            }
            for (ch in line) {
                val g = glyphs[ch] ?: glyphs['?'] ?: continue
                if (ch != ' ') {
                    batch.draw(tex, penX + g.xOff * scale, lineY + g.yOff * scale, g.w * scale, g.h * scale, color, g.u0, g.v0, g.u1, g.v1)
                }
                penX += g.advance * scale
            }
            lineY += lineHeight(size)
            lineStart = lineEnd + 1
        }
    }

    /** Text with a soft drop shadow for legibility over 3D scenes. */
    fun drawShadowed(batch: SpriteBatch, text: CharSequence, x: Float, y: Float, size: Float, color: Int, align: Align = Align.LEFT, shadow: Int = 0x99000000.toInt()) {
        val off = max(1f, size * 0.06f)
        draw(batch, text, x + off, y + off, size, Colors.withAlpha(shadow, Colors.alpha(shadow) * Colors.alpha(color) / 255), align)
        draw(batch, text, x, y, size, color, align)
    }

    companion object {
        const val DEFAULT_EXTRA = "•·×÷±°…←→↑↓★☆♥✓✗■●○◆▲▼—–€£¥⚡♪"
    }
}
