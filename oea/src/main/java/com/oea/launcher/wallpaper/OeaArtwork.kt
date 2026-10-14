package com.oea.launcher.wallpaper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import java.util.Random
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * OEA's own artwork beyond the first ten scenes.
 *
 * Everything here is a *recipe*: a small number of parameterised paintings that
 * produce pictures which are clearly different from each other and from
 * anything a theme uses. Two families come out of it:
 *
 *  - **wallpapers** — the still pictures in the wallpaper library, both for Home
 *    ([OeaWallpaper.homeSet]) and a separate set drawn for the lock screen
 *    ([OeaWallpaper.lockSet], where the middle of the screen must stay quiet for
 *    the clock);
 *  - **generated theme art** — pictures OEA draws for a theme's own lock screen,
 *    so a theme never shows the same picture on Home and on the lock screen, and
 *    never borrows a wallpaper.
 *
 * The work is pure Canvas drawing with a stable seed, so the same id always
 * produces the same picture and it is safe to call on a background thread.
 */
object OeaArtwork {

    // ------------------------------------------------------------------ palettes

    /** One named look: a top colour, a bottom colour and an accent for the light. */
    private class Look(val top: Int, val bottom: Int, val accent: Int, val glow: Int, val dark: Boolean)

    private fun look(top: Int, bottom: Int, accent: Int, glow: Int, dark: Boolean = true) =
        Look(top, bottom, accent, glow, dark)

    private val LOOKS: Map<String, Look> = mapOf(
        // ---- wallpaper set: Home
        "canyon_ember" to look(Color.rgb(20, 12, 18), Color.rgb(120, 44, 30), Color.rgb(255, 170, 90), Color.rgb(255, 122, 60)),
        "glass_dusk" to look(Color.rgb(24, 20, 54), Color.rgb(88, 62, 150), Color.rgb(198, 170, 255), Color.rgb(140, 110, 240)),
        "orchid_bloom" to look(Color.rgb(26, 10, 34), Color.rgb(112, 32, 96), Color.rgb(255, 160, 220), Color.rgb(226, 96, 190)),
        "ribbon_sea" to look(Color.rgb(6, 26, 34), Color.rgb(14, 86, 100), Color.rgb(130, 240, 226), Color.rgb(60, 220, 200)),
        "strata_sand" to look(Color.rgb(238, 224, 200), Color.rgb(196, 158, 116), Color.rgb(120, 82, 46), Color.rgb(255, 226, 176), dark = false),
        "mist_valley" to look(Color.rgb(28, 42, 40), Color.rgb(96, 126, 116), Color.rgb(196, 226, 208), Color.rgb(150, 196, 176)),
        "orbit_dawn" to look(Color.rgb(48, 26, 46), Color.rgb(226, 124, 96), Color.rgb(255, 214, 170), Color.rgb(255, 168, 120)),
        "slate_lines" to look(Color.rgb(14, 16, 22), Color.rgb(52, 60, 74), Color.rgb(158, 176, 200), Color.rgb(110, 132, 160)),
        // ---- wallpaper set: lock screen (drawn to keep the clock area clear)
        "lock_midnight" to look(Color.rgb(4, 6, 16), Color.rgb(14, 22, 54), Color.rgb(140, 178, 255), Color.rgb(70, 110, 220)),
        "lock_ember" to look(Color.rgb(12, 8, 10), Color.rgb(56, 22, 16), Color.rgb(255, 148, 84), Color.rgb(210, 90, 40)),
        "lock_frost" to look(Color.rgb(226, 234, 242), Color.rgb(168, 190, 210), Color.rgb(70, 100, 130), Color.rgb(255, 255, 255), dark = false),
        "lock_violet" to look(Color.rgb(10, 8, 22), Color.rgb(48, 30, 84), Color.rgb(206, 176, 255), Color.rgb(120, 80, 200)),
        "lock_noir" to look(Color.rgb(6, 6, 8), Color.rgb(24, 24, 28), Color.rgb(200, 204, 212), Color.rgb(120, 126, 138)),
        "lock_halo" to look(Color.rgb(8, 14, 22), Color.rgb(20, 44, 62), Color.rgb(178, 240, 255), Color.rgb(90, 200, 240)),
        // ---- generated theme artwork (lock screens for OEA's own themes)
        "aurora_night" to look(Color.rgb(6, 16, 28), Color.rgb(14, 52, 62), Color.rgb(120, 240, 208), Color.rgb(60, 200, 190)),
        "glass_deep" to look(Color.rgb(6, 10, 26), Color.rgb(20, 40, 88), Color.rgb(150, 200, 255), Color.rgb(70, 130, 240)),
        "ridge_night" to look(Color.rgb(8, 10, 22), Color.rgb(40, 34, 68), Color.rgb(214, 170, 255), Color.rgb(120, 100, 220)),
        "forest_mist" to look(Color.rgb(8, 20, 20), Color.rgb(28, 62, 56), Color.rgb(160, 230, 200), Color.rgb(80, 180, 150)),
        "dune_dusk" to look(Color.rgb(26, 14, 10), Color.rgb(110, 56, 34), Color.rgb(255, 190, 130), Color.rgb(240, 130, 70)),
    )

    /** Every generated picture OEA can draw for a theme. */
    val ids: List<String> = listOf("aurora_night", "glass_deep", "ridge_night", "forest_mist", "dune_dusk")

    fun isKnown(id: String?): Boolean = id != null && LOOKS.containsKey(id)

    fun label(id: String): String = when (id) {
        "aurora_night" -> "Northern night"
        "glass_deep" -> "Deep glass"
        "ridge_night" -> "Ridge night"
        "forest_mist" -> "Forest mist"
        "dune_dusk" -> "Dune dusk"
        else -> "OEA artwork"
    }

    /** Renders a generated picture. Returns null for an unknown id. */
    fun render(id: String, width: Int, height: Int): Bitmap? {
        if (!isKnown(id)) return null
        val w = max(64, width)
        val h = max(96, height)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint(canvas, paint, id, w.toFloat(), h.toFloat())
        return bitmap
    }

    // ------------------------------------------------------------------ recipes

    /**
     * Draws the picture named [id] at [w]×[h]. Wallpapers reuse this directly; the
     * ids are shared with the palettes above, so one implementation serves both the
     * library and a theme's generated artwork.
     */
    fun paint(canvas: Canvas, paint: Paint, id: String, w: Float, h: Float) {
        val spec = LOOKS[id] ?: LOOKS.getValue("slate_lines")
        val random = Random(id.hashCode().toLong() * 2654435761L)
        base(canvas, paint, spec, w, h)
        when (id) {
            "canyon_ember", "lock_ember" -> canyon(canvas, paint, spec, w, h, random)
            "glass_dusk", "lock_violet", "glass_deep" -> glassPanels(canvas, paint, spec, w, h, random)
            "orchid_bloom", "lock_halo" -> bloom(canvas, paint, spec, w, h, random)
            "ribbon_sea", "aurora_night" -> ribbons(canvas, paint, spec, w, h, random)
            "strata_sand", "lock_noir", "slate_lines" -> strata(canvas, paint, spec, w, h, random)
            "mist_valley", "lock_frost", "forest_mist" -> mist(canvas, paint, spec, w, h, random)
            "orbit_dawn", "lock_midnight", "ridge_night", "dune_dusk" -> orbit(canvas, paint, spec, w, h, random)
            else -> strata(canvas, paint, spec, w, h, random)
        }
        // Lock-screen art gets a vignette and a clear middle band, so the clock,
        // the weather line and the notifications all sit on calm pixels.
        if (id.startsWith("lock_") || id in ids) lockFrame(canvas, paint, spec, w, h)
    }

    private fun base(canvas: Canvas, paint: Paint, spec: Look, w: Float, h: Float) {
        paint.shader = LinearGradient(
            0f, 0f, w * 0.3f, h,
            intArrayOf(spec.top, OeaWallpaperRenderer.blend(spec.top, spec.bottom, 0.55f), spec.bottom),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
    }

    private fun glow(canvas: Canvas, paint: Paint, x: Float, y: Float, radius: Float, color: Int, strength: Int) {
        paint.shader = RadialGradient(
            x, y, radius,
            intArrayOf(OeaWallpaperRenderer.alpha(color, strength), OeaWallpaperRenderer.alpha(color, strength / 3), Color.TRANSPARENT),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(x, y, radius, paint)
        paint.shader = null
    }

    /** Dark rock columns with a shaft of light between them. */
    private fun canyon(canvas: Canvas, paint: Paint, spec: Look, w: Float, h: Float, random: Random) {
        val shaftX = w * (0.42f + random.nextFloat() * 0.16f)
        glow(canvas, paint, shaftX, h * 0.5f, max(w, h) * 0.55f, spec.glow, 0x66)
        paint.shader = LinearGradient(
            shaftX, 0f, shaftX, h,
            intArrayOf(OeaWallpaperRenderer.alpha(spec.accent, 0x55), OeaWallpaperRenderer.alpha(spec.glow, 0x2A)),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawRect(shaftX - w * 0.1f, 0f, shaftX + w * 0.1f, h, paint)
        paint.shader = null
        // Columns: tall, flat-topped silhouettes, darker and wider towards the edges.
        for (side in listOf(-1f, 1f)) {
            var x = if (side < 0) -w * 0.05f else shaftX + w * 0.12f
            var width = w * (0.1f + random.nextFloat() * 0.1f)
            var guard = 0
            while (guard++ < 12 && (if (side < 0) x < shaftX - w * 0.08f else x < w + w * 0.05f)) {
                val top = h * (0.06f + random.nextFloat() * 0.3f)
                val path = Path().apply {
                    moveTo(x, h)
                    lineTo(x + width * 0.08f, top + h * 0.04f)
                    lineTo(x + width, top)
                    lineTo(x + width * 1.05f, h)
                    close()
                }
                paint.color = OeaWallpaperRenderer.blend(Color.rgb(6, 4, 8), spec.glow, 0.16f - guard * 0.01f)
                canvas.drawPath(path, paint)
                x += width * 0.86f
                width = w * (0.08f + random.nextFloat() * 0.12f)
            }
        }
    }

    /** Soft panels of frosted glass with light streaks across them. */
    private fun glassPanels(canvas: Canvas, paint: Paint, spec: Look, w: Float, h: Float, random: Random) {
        glow(canvas, paint, w * 0.2f, h * 0.2f, max(w, h) * 0.6f, spec.glow, 0x50)
        val panels = 5
        for (i in 0 until panels) {
            val top = h * (i / panels.toFloat()) - h * 0.04f
            val bottom = top + h * (0.16f + random.nextFloat() * 0.08f)
            val inset = w * (random.nextFloat() * 0.16f)
            val path = Path().apply {
                moveTo(-inset, top)
                lineTo(w + inset, top + h * 0.03f)
                lineTo(w + inset, bottom)
                lineTo(-inset, bottom + h * 0.02f)
                close()
            }
            paint.color = OeaWallpaperRenderer.alpha(
                OeaWallpaperRenderer.blend(spec.accent, spec.bottom, 0.4f),
                0x1E + random.nextInt(0x1C),
            )
            canvas.drawPath(path, paint)
        }
        // A couple of long light streaks: what makes glass read as glass.
        for (i in 0 until 2) {
            val startX = w * (-0.2f + random.nextFloat())
            paint.shader = LinearGradient(
                startX, 0f, startX + w * 0.5f, h,
                intArrayOf(Color.TRANSPARENT, OeaWallpaperRenderer.alpha(spec.accent, 0x3C), Color.TRANSPARENT),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null
        }
    }

    /** A soft rosette of petals around a bright core. */
    private fun bloom(canvas: Canvas, paint: Paint, spec: Look, w: Float, h: Float, random: Random) {
        val cx = w * (0.45f + random.nextFloat() * 0.1f)
        val cy = h * (0.42f + random.nextFloat() * 0.1f)
        val petals = 7
        for (i in 0 until petals) {
            val angle = (i / petals.toFloat()) * 2f * Math.PI.toFloat() + random.nextFloat() * 0.2f
            val distance = w * (0.16f + random.nextFloat() * 0.22f)
            glow(
                canvas, paint,
                cx + cos(angle) * distance, cy + sin(angle) * distance,
                w * (0.3f + random.nextFloat() * 0.2f),
                if (i % 2 == 0) spec.accent else spec.glow,
                0x3A,
            )
        }
        glow(canvas, paint, cx, cy, w * 0.34f, spec.glow, 0x5A)
        paint.color = OeaWallpaperRenderer.alpha(OeaWallpaperRenderer.blend(spec.accent, Color.WHITE, 0.4f), 0x66)
        canvas.drawCircle(cx, cy, w * 0.05f, paint)
    }

    /** Long translucent ribbons sweeping across the frame (aurora, tides). */
    private fun ribbons(canvas: Canvas, paint: Paint, spec: Look, w: Float, h: Float, random: Random) {
        val count = 5
        for (i in 0 until count) {
            val y = h * (0.12f + i * 0.15f) + random.nextFloat() * h * 0.04f
            val thickness = h * (0.06f + random.nextFloat() * 0.06f)
            val path = Path().apply {
                moveTo(-w * 0.1f, y)
                var x = -w * 0.1f
                var yy = y
                while (x < w * 1.1f) {
                    val step = w / 6f
                    x += step
                    yy = y + sin((x / w) * 3.4f + i) * h * 0.07f
                    lineTo(x, yy)
                }
                lineTo(w * 1.1f, yy + thickness)
                x = w * 1.1f
                yy += thickness
                while (x > -w * 0.1f) {
                    val step = w / 6f
                    x -= step
                    lineTo(x, y + sin((x / w) * 3.4f + i) * h * 0.07f + thickness * 0.72f)
                }
                close()
            }
            paint.shader = LinearGradient(
                -w * 0.1f, 0f, w * 1.1f, 0f,
                intArrayOf(Color.TRANSPARENT, OeaWallpaperRenderer.alpha(if (i % 2 == 0) spec.accent else spec.glow, 0x44), Color.TRANSPARENT),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
            )
            canvas.drawPath(path, paint)
            paint.shader = null
        }
        glow(canvas, paint, w * 0.7f, h * 0.32f, max(w, h) * 0.5f, spec.glow, 0x40)
    }

    /** Horizontal bands of colour with a fine grain, calm and graphic. */
    private fun strata(canvas: Canvas, paint: Paint, spec: Look, w: Float, h: Float, random: Random) {
        val bands = 9
        for (i in 0 until bands) {
            val top = h * (i / bands.toFloat())
            val height = h / bands * (0.9f + random.nextFloat() * 0.5f)
            val ratio = 0.2f + random.nextFloat() * 0.5f
            paint.color = OeaWallpaperRenderer.alpha(
                OeaWallpaperRenderer.blend(spec.top, spec.accent, ratio),
                0x1A + random.nextInt(0x1C),
            )
            canvas.drawRect(0f, top, w, top + height, paint)
        }
        // One bright band: a horizon line or a seam of light.
        val seam = h * (0.42f + random.nextFloat() * 0.2f)
        paint.shader = LinearGradient(
            0f, seam - h * 0.02f, 0f, seam + h * 0.02f,
            intArrayOf(Color.TRANSPARENT, OeaWallpaperRenderer.alpha(spec.accent, 0x66), Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, seam - h * 0.02f, w, seam + h * 0.02f, paint)
        paint.shader = null
        grain(canvas, paint, w, h, spec, random, if (spec.dark) 0x14 else 0x18)
    }

    /** Layers of fog over a glowing horizon. */
    private fun mist(canvas: Canvas, paint: Paint, spec: Look, w: Float, h: Float, random: Random) {
        glow(canvas, paint, w * 0.5f, h * 0.38f, max(w, h) * 0.5f, spec.glow, 0x4A)
        for (layer in 0 until 6) {
            val y = h * (0.34f + layer * 0.1f) + random.nextFloat() * h * 0.02f
            val path = Path().apply {
                moveTo(-w * 0.05f, y)
                var x = -w * 0.05f
                while (x < w * 1.05f) {
                    val step = w / 8f
                    x += step
                    lineTo(x, y + sin((x / w) * 5f + layer * 1.7f) * h * 0.02f)
                }
                lineTo(w * 1.05f, h)
                lineTo(-w * 0.05f, h)
                close()
            }
            paint.color = OeaWallpaperRenderer.alpha(
                OeaWallpaperRenderer.blend(spec.bottom, spec.accent, 0.1f + layer * 0.06f),
                if (spec.dark) 0x2E else 0x30,
            )
            canvas.drawPath(path, paint)
        }
        grain(canvas, paint, w, h, spec, random, 0x12)
    }

    /** Concentric rings around a bright core: depth without noise. */
    private fun orbit(canvas: Canvas, paint: Paint, spec: Look, w: Float, h: Float, random: Random) {
        val cx = w * (0.4f + random.nextFloat() * 0.2f)
        val cy = h * (0.36f + random.nextFloat() * 0.14f)
        glow(canvas, paint, cx, cy, max(w, h) * 0.5f, spec.glow, 0x46)
        paint.style = Paint.Style.STROKE
        for (i in 1..6) {
            paint.strokeWidth = max(1f, w * (0.004f + random.nextFloat() * 0.01f))
            paint.color = OeaWallpaperRenderer.alpha(spec.accent, 0x2A + (6 - i) * 0x08)
            canvas.drawCircle(cx, cy, w * (0.12f + i * 0.13f), paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = OeaWallpaperRenderer.alpha(OeaWallpaperRenderer.blend(spec.accent, Color.WHITE, 0.5f), 0x88)
        canvas.drawCircle(cx, cy, w * 0.035f, paint)
    }

    /** A light grain so flat areas read as a picture rather than a fill. */
    private fun grain(canvas: Canvas, paint: Paint, w: Float, h: Float, spec: Look, random: Random, strength: Int) {
        val dots = 900
        val light = OeaWallpaperRenderer.blend(spec.accent, Color.WHITE, 0.5f)
        for (i in 0 until dots) {
            val x = random.nextFloat() * w
            val y = random.nextFloat() * h
            paint.color = OeaWallpaperRenderer.alpha(if (i % 2 == 0) light else Color.BLACK, strength)
            canvas.drawCircle(x, y, 1f + random.nextFloat(), paint)
        }
    }

    /**
     * The lock-screen frame: a soft vignette and a clear, quiet band across the
     * middle. Android draws the clock and every notification on top of this area,
     * so nothing important may be painted there.
     */
    private fun lockFrame(canvas: Canvas, paint: Paint, spec: Look, w: Float, h: Float) {
        paint.shader = RadialGradient(
            w * 0.5f, h * 0.45f, max(w, h) * 0.72f,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, OeaWallpaperRenderer.alpha(Color.BLACK, if (spec.dark) 0xB4 else 0x58)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
        paint.shader = LinearGradient(
            0f, h * 0.18f, 0f, h * 0.72f,
            intArrayOf(Color.TRANSPARENT, OeaWallpaperRenderer.alpha(Color.BLACK, if (spec.dark) 0x3A else 0x22), Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, h * 0.18f, w, h * 0.72f, paint)
        paint.shader = null
    }

    // ------------------------------------------------------------------ helpers

    /** Blends the picture towards the given colour; used by the picker swatches. */
    fun swatch(id: String): Int {
        val spec = LOOKS[id] ?: return Color.rgb(120, 130, 150)
        return OeaWallpaperRenderer.blend(spec.accent, spec.bottom, 0.35f)
    }

    /** True when the picture is bright enough that Home's icons need dark text. */
    fun isLight(id: String): Boolean = LOOKS[id]?.dark == false
}
