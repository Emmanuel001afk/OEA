package com.oea.launcher.wallpaper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * OEA's bundled wallpapers, drawn as code instead of shipped as images.
 *
 * Why procedural: a scene is a few hundred bytes of Kotlin, renders at the
 * phone's exact aspect ratio with no upscaling artefacts, has a light and a dark
 * variant for free, and gives the theme an accurate accent and brightness to
 * work from (which a JPEG cannot). Every scene also states its own dominant
 * colour, so OEA's Material You-style palette can follow the bundled wallpaper
 * even when Android reports colours for a different one.
 */
enum class OeaWallpaper(
    val id: String,
    val label: String,
    val blurb: String,
    /** Dominant colour, used as the theme accent and for the picker swatch. */
    val accent: Int,
    /** True when the scene is bright enough that Home's icons need dark text. */
    val light: Boolean,
    /**
     * Drawn for the lock screen: the middle of the picture stays quiet so the
     * clock and notifications sit on calm pixels. Lock pictures are their own
     * artwork and are never offered as a Home wallpaper.
     */
    val forLock: Boolean = false,
) {
    MOUNTAIN_DAWN(
        "mountain_dawn", "Mountain dawn",
        "Layered ridges under a sunrise; OEA's default look.",
        Color.rgb(255, 168, 96), light = false,
    ),
    FOREST_RIVER(
        "forest_river", "Forest river",
        "Pines along a slow river, cool green and teal.",
        Color.rgb(96, 200, 168), light = false,
    ),
    DESERT_COAST(
        "desert_coast", "Desert coast",
        "Warm dunes meeting a flat sea at dusk.",
        Color.rgb(255, 140, 104), light = false,
    ),
    DUSK_CITY(
        "dusk_city", "Dusk city",
        "A skyline at blue hour with lit windows.",
        Color.rgb(160, 140, 255), light = false,
    ),
    AURORA(
        "aurora", "Aurora",
        "Ribbons of colour over a cold, quiet sky.",
        Color.rgb(120, 220, 200), light = false,
    ),
    NIGHT_SKY(
        "night_sky", "Night sky",
        "Deep space with a soft nebula; the calmest dark option.",
        Color.rgb(138, 180, 248), light = false,
    ),
    OCEAN(
        "ocean", "Ocean",
        "Rolling blue bands with light glinting off the water.",
        Color.rgb(90, 190, 255), light = false,
    ),
    TERRAZZO(
        "terrazzo", "Terrazzo",
        "Pale stone flecked with colour; bright and text-friendly.",
        Color.rgb(196, 120, 60), light = true,
    ),
    LINEN(
        "linen", "Linen",
        "A warm, quiet solid with a soft vignette.",
        Color.rgb(150, 110, 70), light = true,
    ),
    MESH_AMOLED(
        "mesh_amoled", "Deep mesh",
        "Near-black with faint colour clouds; made for OLED.",
        Color.rgb(120, 160, 255), light = false,
    ),

    // ------------------------------------------------ the second wallpaper set
    // Eight more pictures, each painted by OeaArtwork from a palette of its own,
    // so the wallpaper library is not a short list of variations on one idea.
    CANYON_EMBER(
        "canyon_ember", "Canyon ember",
        "Rock columns with a shaft of warm light between them.",
        Color.rgb(255, 150, 90), light = false,
    ),
    GLASS_DUSK(
        "glass_dusk", "Glass dusk",
        "Frosted panels and long light streaks in violet.",
        Color.rgb(186, 160, 255), light = false,
    ),
    ORCHID_BLOOM(
        "orchid_bloom", "Orchid bloom",
        "Soft petals of magenta light opening from a bright core.",
        Color.rgb(255, 150, 214), light = false,
    ),
    RIBBON_SEA(
        "ribbon_sea", "Ribbon sea",
        "Teal ribbons sweeping across a deep current.",
        Color.rgb(120, 236, 220), light = false,
    ),
    STRATA_SAND(
        "strata_sand", "Strata sand",
        "Warm stone bands with a bright seam; bright and readable.",
        Color.rgb(150, 104, 58), light = true,
    ),
    MIST_VALLEY(
        "mist_valley", "Mist valley",
        "Layers of morning fog over a green horizon glow.",
        Color.rgb(178, 220, 200), light = false,
    ),
    ORBIT_DAWN(
        "orbit_dawn", "Orbit dawn",
        "Warm rings around a low sun, drawn as clean lines.",
        Color.rgb(255, 196, 140), light = false,
    ),
    SLATE_LINES(
        "slate_lines", "Slate lines",
        "Cool graphite bands; the quietest dark option.",
        Color.rgb(150, 170, 196), light = false,
    ),

    // ------------------------------------------------ lock screen wallpapers
    // Pictures drawn for the lock screen: their middle band is deliberately kept
    // clear for the clock, and their own artwork means a lock screen never
    // repeats the picture that is behind the icons on Home.
    LOCK_MIDNIGHT(
        "lock_midnight", "Midnight",
        "Deep blue rings around a single star of light.",
        Color.rgb(132, 172, 255), light = false, forLock = true,
    ),
    LOCK_EMBER(
        "lock_ember", "Ember",
        "A low, warm glow between dark rock, made for night reading.",
        Color.rgb(255, 140, 80), light = false, forLock = true,
    ),
    LOCK_FROST(
        "lock_frost", "Frost",
        "Pale cold fog with a soft horizon; bright and legible.",
        Color.rgb(96, 126, 158), light = true, forLock = true,
    ),
    LOCK_VIOLET(
        "lock_violet", "Violet haze",
        "Violet glass panes and one long streak of light.",
        Color.rgb(200, 170, 255), light = false, forLock = true,
    ),
    LOCK_NOIR(
        "lock_noir", "Noir",
        "Near-black graphite with a thin seam of light.",
        Color.rgb(196, 202, 212), light = false, forLock = true,
    ),
    LOCK_HALO(
        "lock_halo", "Halo",
        "A cool halo of light above the clock, clear where it matters.",
        Color.rgb(170, 236, 255), light = false, forLock = true,
    );

    companion object {
        fun byId(id: String?): OeaWallpaper? = entries.firstOrNull { it.id == id }

        /**
         * The Home wallpaper library. Lock-screen pictures are excluded: they are a
         * set of their own, and offering them as a Home wallpaper would put the
         * quiet band built for the clock behind the user's icons.
         */
        val homeSet: List<OeaWallpaper> = entries.filter { !it.forLock }

        /** The lock screen library: its own artwork, none of it shared with Home. */
        val lockSet: List<OeaWallpaper> = entries.filter { it.forLock }
    }
}

/**
 * Draws the scenes. Everything here is pure Canvas work, deterministic for a
 * given size, and safe to call on a background thread; nothing touches the
 * screen or Android's wallpaper until [OeaWallpaperApplier] is asked to.
 */
object OeaWallpaperRenderer {
    /** Longest edge used for a rendered scene; larger screens are downscaled by Android. */
    const val MAX_EDGE = 2400

    /** Renders [scene] at [width]×[height] pixels. */
    fun render(scene: OeaWallpaper, width: Int, height: Int): Bitmap {
        val w = max(240, width)
        val h = max(320, height)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        // A stable seed per scene: stars and windows stay put between renders.
        val random = Random(scene.id.hashCode().toLong() * 31 + w + h)
        when (scene) {
            OeaWallpaper.MOUNTAIN_DAWN -> mountains(canvas, paint, w, h, random, dawn = true)
            OeaWallpaper.FOREST_RIVER -> forest(canvas, paint, w, h, random)
            OeaWallpaper.DESERT_COAST -> desert(canvas, paint, w, h, random)
            OeaWallpaper.DUSK_CITY -> city(canvas, paint, w, h, random)
            OeaWallpaper.AURORA -> aurora(canvas, paint, w, h, random)
            OeaWallpaper.NIGHT_SKY -> nightSky(canvas, paint, w, h, random)
            OeaWallpaper.OCEAN -> ocean(canvas, paint, w, h, random)
            OeaWallpaper.TERRAZZO -> terrazzo(canvas, paint, w, h, random)
            OeaWallpaper.LINEN -> linen(canvas, paint, w, h)
            OeaWallpaper.MESH_AMOLED -> mesh(canvas, paint, w, h, dark = true)
            // The second set and every lock-screen picture: painted from their own
            // palette by OeaArtwork, which also draws the generated theme artwork.
            OeaWallpaper.CANYON_EMBER, OeaWallpaper.GLASS_DUSK, OeaWallpaper.ORCHID_BLOOM,
            OeaWallpaper.RIBBON_SEA, OeaWallpaper.STRATA_SAND, OeaWallpaper.MIST_VALLEY,
            OeaWallpaper.ORBIT_DAWN, OeaWallpaper.SLATE_LINES,
            OeaWallpaper.LOCK_MIDNIGHT, OeaWallpaper.LOCK_EMBER, OeaWallpaper.LOCK_FROST,
            OeaWallpaper.LOCK_VIOLET, OeaWallpaper.LOCK_NOIR, OeaWallpaper.LOCK_HALO ->
            OeaArtwork.paint(canvas, paint, scene.id, w.toFloat(), h.toFloat())
        }
        dim(canvas, paint, w, h, scene)
        return bitmap
    }

    /** A tiny preview for the picker, cheap enough to build while scrolling a list. */
    fun preview(scene: OeaWallpaper, width: Int, height: Int): Bitmap = render(scene, min(width, 360), min(height, 640))

    /**
     * A quadratic curve drawn as an exact cubic.
     *
     * Android's `quadraticBezierTo` is not part of every SDK the app compiles
     * against, so the ridge is drawn with `cubicTo`, which has existed since
     * API 1: a quadratic's cubic form puts its control points two thirds of the
     * way from each end towards the quadratic control point.
     */
    private fun Path.quadCurve(fromX: Float, fromY: Float, cx: Float, cy: Float, toX: Float, toY: Float) {
        cubicTo(
            fromX + 2f / 3f * (cx - fromX), fromY + 2f / 3f * (cy - fromY),
            toX + 2f / 3f * (cx - toX), toY + 2f / 3f * (cy - toY),
            toX, toY,
        )
    }

    // ------------------------------------------------------------------ scenes

    private fun mountains(canvas: Canvas, paint: Paint, w: Int, h: Int, random: Random, dawn: Boolean) {
        val horizon = h * 0.62f
        val skyTop = if (dawn) Color.rgb(24, 32, 66) else Color.rgb(12, 16, 34)
        val skyMid = if (dawn) Color.rgb(96, 84, 128) else Color.rgb(34, 44, 84)
        val skyLow = if (dawn) Color.rgb(255, 168, 96) else Color.rgb(120, 120, 170)
        paint.shader = LinearGradient(0f, 0f, 0f, horizon, intArrayOf(skyTop, skyMid, skyLow), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w.toFloat(), horizon, paint)

        // Sun with a soft halo.
        val sunX = w * 0.68f
        val sunY = horizon - h * 0.06f
        val sunRadius = h * 0.045f
        paint.shader = RadialGradient(sunX, sunY, sunRadius * 6f, intArrayOf(alpha(skyLow, 0x88), Color.TRANSPARENT), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(sunX, sunY, sunRadius * 6f, paint)
        paint.shader = null
        paint.color = blend(Color.WHITE, skyLow, 0.35f)
        canvas.drawCircle(sunX, sunY, sunRadius, paint)

        // Layered ridges, back to front, getting darker and less hazy.
        val layers = 4
        for (layer in 0 until layers) {
            val base = horizon - h * 0.02f + layer * h * 0.035f
            val amplitude = h * (0.075f - layer * 0.012f)
            val path = Path()
            path.moveTo(0f, h.toFloat())
            path.lineTo(0f, base)
            var x = 0f
            var y = base
            while (x <= w) {
                val step = w / 14f
                y = base - amplitude * (0.4f + 0.6f * abs(sin((x / w) * (3.1f + layer) + layer)))
                path.lineTo(x, y)
                x += step
            }
            path.lineTo(w.toFloat(), base)
            path.lineTo(w.toFloat(), h.toFloat())
            path.close()
            paint.color = blend(Color.rgb(8, 10, 20), skyLow, 0.34f - layer * 0.09f)
            canvas.drawPath(path, paint)
        }

        // Foreground slope plus a hint of snow on the nearest ridge.
        paint.color = blend(Color.rgb(6, 8, 16), skyLow, 0.08f)
        val slope = Path().apply {
            moveTo(0f, h.toFloat())
            lineTo(0f, h * 0.86f)
            quadCurve(0f, h * 0.86f, w * 0.35f, h * 0.8f, w.toFloat(), h * 0.9f)
            lineTo(w.toFloat(), h.toFloat())
            close()
        }
        canvas.drawPath(slope, paint)
        paint.color = alpha(Color.WHITE, 0x33)
        canvas.drawRect(0f, horizon - h * 0.005f, w.toFloat(), horizon + h * 0.004f, paint)
    }

    private fun forest(canvas: Canvas, paint: Paint, w: Int, h: Int, random: Random) {
        val horizon = h * 0.58f
        paint.shader = LinearGradient(
            0f, 0f, 0f, horizon,
            intArrayOf(Color.rgb(8, 30, 34), Color.rgb(20, 74, 74), Color.rgb(96, 200, 168)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w.toFloat(), horizon, paint)
        paint.shader = null
        paint.color = alpha(Color.rgb(180, 255, 236), 0x44)
        canvas.drawCircle(w * 0.26f, h * 0.18f, h * 0.035f, paint)

        // Three rows of pines, each row darker and taller than the one behind it.
        for (row in 0 until 3) {
            val rowBase = horizon - h * (0.02f - row * 0.012f) + row * h * 0.03f
            val treeHeight = h * (0.1f + row * 0.05f)
            val treeWidth = w * (0.055f - row * 0.008f)
            paint.color = blend(Color.rgb(4, 18, 20), Color.rgb(96, 200, 168), 0.22f - row * 0.07f)
            var x = -treeWidth
            while (x < w + treeWidth) {
                val jitter = random.nextFloat() * treeWidth * 0.6f
                val top = rowBase - treeHeight * (0.85f + random.nextFloat() * 0.3f)
                val path = Path()
                path.moveTo(x + jitter, rowBase)
                path.lineTo(x + jitter + treeWidth / 2f, top)
                path.lineTo(x + jitter + treeWidth, rowBase)
                path.close()
                canvas.drawPath(path, paint)
                x += treeWidth * 0.85f
            }
        }

        // Water: a smooth band with horizontal glints.
        paint.shader = LinearGradient(
            0f, horizon, 0f, h.toFloat(),
            intArrayOf(Color.rgb(10, 40, 52), Color.rgb(4, 18, 28)),
            null, Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, horizon, w.toFloat(), h.toFloat(), paint)
        paint.shader = null
        paint.color = alpha(Color.rgb(180, 255, 236), 0x38)
        for (i in 0 until 26) {
            val y = horizon + (h - horizon) * (i / 26f) + random.nextFloat() * 6f
            val lineWidth = w * (0.08f + random.nextFloat() * 0.28f)
            val startX = random.nextFloat() * (w - lineWidth)
            canvas.drawRect(startX, y, startX + lineWidth, y + max(1f, h * 0.0016f), paint)
        }
    }

    private fun desert(canvas: Canvas, paint: Paint, w: Int, h: Int, random: Random) {
        val horizon = h * 0.48f
        paint.shader = LinearGradient(
            0f, 0f, 0f, horizon,
            intArrayOf(Color.rgb(28, 34, 74), Color.rgb(196, 104, 96), Color.rgb(255, 176, 112)),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w.toFloat(), horizon, paint)
        paint.shader = null
        paint.color = blend(Color.WHITE, Color.rgb(255, 190, 120), 0.4f)
        canvas.drawCircle(w * 0.5f, horizon - h * 0.03f, h * 0.05f, paint)

        // Sea band with shimmer, then dunes on top of it.
        paint.shader = LinearGradient(
            0f, horizon, 0f, horizon + h * 0.16f,
            intArrayOf(Color.rgb(60, 60, 110), Color.rgb(30, 34, 70)),
            null, Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, horizon, w.toFloat(), horizon + h * 0.16f, paint)
        paint.shader = null
        paint.color = alpha(Color.rgb(255, 214, 170), 0x55)
        for (i in 0 until 18) {
            val y = horizon + h * 0.16f * (i / 18f)
            val lineWidth = w * (0.05f + random.nextFloat() * 0.2f)
            val startX = random.nextFloat() * (w - lineWidth)
            canvas.drawRect(startX, y, startX + lineWidth, y + max(1f, h * 0.0014f), paint)
        }

        // Two dunes as smooth curves.
        for (layer in 0 until 2) {
            val base = horizon + h * (0.12f + layer * 0.16f)
            val path = Path().apply {
                moveTo(0f, h.toFloat())
                lineTo(0f, base)
                cubicTo(w * 0.3f, base - h * 0.09f, w * 0.62f, base + h * 0.06f, w.toFloat(), base - h * 0.05f)
                lineTo(w.toFloat(), h.toFloat())
                close()
            }
            paint.color = if (layer == 0) Color.rgb(196, 132, 84) else Color.rgb(150, 96, 62)
            canvas.drawPath(path, paint)
            paint.color = alpha(Color.rgb(255, 220, 180), 0x22)
            canvas.drawRect(0f, base - h * 0.002f, w.toFloat(), base + h * 0.002f, paint)
        }
    }

    private fun city(canvas: Canvas, paint: Paint, w: Int, h: Int, random: Random) {
        paint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(Color.rgb(20, 22, 54), Color.rgb(58, 44, 96), Color.rgb(120, 70, 110)),
            floatArrayOf(0f, 0.58f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        paint.shader = null

        // Two rows of buildings; the far row is hazier and shorter.
        for (row in 0 until 2) {
            val base = h * (0.72f + row * 0.08f)
            val maxHeight = h * (0.22f + row * 0.16f)
            var x = -w * 0.04f
            while (x < w + w * 0.04f) {
                val buildingWidth = w * (0.055f + random.nextFloat() * 0.06f)
                val buildingHeight = maxHeight * (0.4f + random.nextFloat() * 0.6f)
                val top = base - buildingHeight
                paint.color = blend(Color.rgb(10, 12, 26), Color.rgb(120, 90, 160), if (row == 0) 0.18f else 0.04f)
                canvas.drawRect(x, top, x + buildingWidth, base, paint)
                if (row == 1) {
                    // Lit windows, deterministic per building.
                    paint.color = alpha(Color.rgb(255, 214, 140), 0xAA)
                    val cols = max(2, (buildingWidth / (w * 0.014f)).toInt())
                    val rows = max(2, (buildingHeight / (h * 0.022f)).toInt())
                    for (col in 0 until cols) {
                        for (rowIndex in 0 until rows) {
                            if (random.nextFloat() > 0.42f) continue
                            val wx = x + buildingWidth * (col + 0.5f) / cols - w * 0.004f
                            val wy = top + buildingHeight * (rowIndex + 0.5f) / rows - h * 0.004f
                            canvas.drawRect(wx, wy, wx + w * 0.008f, wy + h * 0.008f, paint)
                        }
                    }
                }
                x += buildingWidth * 1.06f
            }
        }
        // Water/reflection strip at the bottom.
        paint.color = alpha(Color.rgb(255, 200, 150), 0x12)
        canvas.drawRect(0f, h * 0.86f, w.toFloat(), h.toFloat(), paint)
    }

    private fun aurora(canvas: Canvas, paint: Paint, w: Int, h: Int, random: Random) {
        paint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(Color.rgb(4, 10, 24), Color.rgb(8, 24, 44), Color.rgb(6, 14, 28)),
            null, Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        paint.shader = null

        // Stars.
        paint.color = alpha(Color.WHITE, 0xCC)
        for (i in 0 until 220) {
            val x = random.nextFloat() * w
            val y = random.nextFloat() * h * 0.75f
            val size = if (random.nextFloat() > 0.85f) w * 0.0022f else w * 0.0012f
            canvas.drawCircle(x, y, size, paint)
        }

        // Aurora ribbons: three translucent, curved bands.
        val colors = intArrayOf(Color.rgb(120, 255, 200), Color.rgb(140, 190, 255), Color.rgb(200, 140, 255))
        for (band in 0 until 3) {
            val path = Path()
            val baseY = h * (0.18f + band * 0.1f)
            path.moveTo(0f, baseY)
            var x = 0f
            val amplitude = h * (0.05f + band * 0.015f)
            while (x <= w) {
                val y = baseY - amplitude * sin((x / w) * (2.4f + band * 0.7f) + band)
                path.lineTo(x, y)
                x += w / 40f
            }
            path.lineTo(w.toFloat(), h.toFloat())
            path.lineTo(0f, h.toFloat())
            path.close()
            paint.shader = LinearGradient(
                0f, baseY - amplitude, 0f, baseY + h * 0.3f,
                intArrayOf(alpha(colors[band], 0x66), alpha(colors[band], 0x10), Color.TRANSPARENT),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
            )
            canvas.drawPath(path, paint)
            paint.shader = null
        }

        // Cold ground glow.
        paint.shader = LinearGradient(
            0f, h * 0.82f, 0f, h.toFloat(),
            intArrayOf(Color.TRANSPARENT, alpha(Color.rgb(90, 200, 200), 0x3C)),
            null, Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, h * 0.82f, w.toFloat(), h.toFloat(), paint)
        paint.shader = null
    }

    private fun nightSky(canvas: Canvas, paint: Paint, w: Int, h: Int, random: Random) {
        paint.shader = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(Color.rgb(6, 8, 20), Color.rgb(14, 12, 40), Color.rgb(4, 6, 16)),
            null, Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        paint.shader = null

        // Nebula blobs.
        repeat(4) { index ->
            val cx = random.nextFloat() * w
            val cy = random.nextFloat() * h * 0.8f
            val radius = w * (0.25f + random.nextFloat() * 0.35f)
            val tint = when (index % 3) {
                0 -> Color.rgb(90, 120, 255)
                1 -> Color.rgb(180, 90, 220)
                else -> Color.rgb(80, 200, 220)
            }
            paint.shader = RadialGradient(cx, cy, radius, intArrayOf(alpha(tint, 0x2E), Color.TRANSPARENT), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, radius, paint)
            paint.shader = null
        }

        // Stars with a few bright ones.
        for (i in 0 until 420) {
            val x = random.nextFloat() * w
            val y = random.nextFloat() * h
            val bright = random.nextFloat() > 0.9f
            paint.color = alpha(Color.WHITE, if (bright) 0xEE else 0x88)
            canvas.drawCircle(x, y, if (bright) w * 0.0026f else w * 0.0014f, paint)
        }
    }

    private fun ocean(canvas: Canvas, paint: Paint, w: Int, h: Int, random: Random) {
        val sky = LinearGradient(
            0f, 0f, 0f, h * 0.4f,
            intArrayOf(Color.rgb(10, 40, 70), Color.rgb(60, 140, 190)),
            null, Shader.TileMode.CLAMP,
        )
        paint.shader = sky
        canvas.drawRect(0f, 0f, w.toFloat(), h * 0.4f, paint)
        paint.shader = null
        paint.shader = LinearGradient(
            0f, h * 0.4f, 0f, h.toFloat(),
            intArrayOf(Color.rgb(20, 90, 150), Color.rgb(4, 26, 48)),
            null, Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, h * 0.4f, w.toFloat(), h.toFloat(), paint)
        paint.shader = null

        // Wave bands with a foam highlight per crest.
        for (band in 0 until 14) {
            val y = h * (0.4f + band * 0.045f)
            val amplitude = h * (0.004f + band * 0.0012f)
            val path = Path().apply {
                moveTo(0f, y)
                var x = 0f
                while (x <= w) {
                    lineTo(x, y + amplitude * sin((x / w) * (6f + band) + band))
                    x += w / 30f
                }
                lineTo(w.toFloat(), y + h * 0.5f)
                lineTo(0f, y + h * 0.5f)
                close()
            }
            paint.color = blend(Color.rgb(18, 80, 140), Color.WHITE, 0.06f * band)
            paint.alpha = 0x66
            canvas.drawPath(path, paint)
            paint.alpha = 0xFF
            paint.color = alpha(Color.WHITE, 0x30)
            canvas.drawRect(0f, y - max(1f, h * 0.0008f), w.toFloat(), y + max(1f, h * 0.0008f), paint)
        }
        // Sun glint.
        paint.shader = RadialGradient(w * 0.7f, h * 0.3f, w * 0.3f, intArrayOf(alpha(Color.WHITE, 0x44), Color.TRANSPARENT), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(w * 0.7f, h * 0.3f, w * 0.3f, paint)
        paint.shader = null
        random.nextInt() // keeps the sequence stable if the scene changes later
    }

    private fun terrazzo(canvas: Canvas, paint: Paint, w: Int, h: Int, random: Random) {
        paint.color = Color.rgb(244, 240, 234)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        val palette = intArrayOf(
            Color.rgb(232, 184, 154), Color.rgb(178, 208, 216), Color.rgb(214, 190, 230),
            Color.rgb(240, 224, 168), Color.rgb(198, 216, 188), Color.rgb(226, 168, 168),
        )
        repeat(180) {
            val cx = random.nextFloat() * w
            val cy = random.nextFloat() * h
            val radius = w * (0.008f + random.nextFloat() * 0.024f)
            paint.color = palette[random.nextInt(palette.size)]
            paint.alpha = 0xCC
            val sides = 5 + random.nextInt(4)
            val path = Path()
            for (side in 0 until sides) {
                val angle = (side / sides.toFloat()) * 2f * Math.PI.toFloat() + random.nextFloat() * 0.3f
                val px = cx + radius * kotlin.math.cos(angle)
                val py = cy + radius * kotlin.math.sin(angle)
                if (side == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            path.close()
            canvas.drawPath(path, paint)
        }
        paint.alpha = 0xFF
        // Soft top light so the drawer and cards stay readable.
        paint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(alpha(Color.WHITE, 0x55), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        paint.shader = null
    }

    private fun linen(canvas: Canvas, paint: Paint, w: Int, h: Int) {
        paint.shader = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(Color.rgb(250, 246, 240), Color.rgb(236, 228, 218)),
            null, Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        paint.shader = null
        // Woven texture: two sets of faint lines, cheap and subtle.
        paint.color = alpha(Color.rgb(180, 168, 150), 0x18)
        var x = 0f
        while (x < w) {
            canvas.drawRect(x, 0f, x + max(1f, w * 0.0022f), h.toFloat(), paint)
            x += w * 0.012f
        }
        var y = 0f
        while (y < h) {
            canvas.drawRect(0f, y, w.toFloat(), y + max(1f, h * 0.0022f), paint)
            y += h * 0.012f
        }
    }

    private fun mesh(canvas: Canvas, paint: Paint, w: Int, h: Int, dark: Boolean) {
        paint.color = if (dark) Color.rgb(4, 5, 9) else Color.rgb(248, 249, 252)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        val blobs = listOf(
            Triple(0.18f, 0.16f, Color.rgb(60, 90, 200)),
            Triple(0.85f, 0.28f, Color.rgb(140, 70, 200)),
            Triple(0.25f, 0.75f, Color.rgb(30, 120, 130)),
            Triple(0.75f, 0.9f, Color.rgb(180, 90, 90)),
        )
        blobs.forEach { (fx, fy, color) ->
            val cx = w * fx
            val cy = h * fy
            val radius = max(w, h) * 0.6f
            paint.shader = RadialGradient(cx, cy, radius, intArrayOf(alpha(color, if (dark) 0x4C else 0x2E), Color.TRANSPARENT), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, radius, paint)
            paint.shader = null
        }
    }

    /** Applies the user's dim preference; keeps icon contrast predictable. */
    private fun dim(canvas: Canvas, paint: Paint, w: Int, h: Int, scene: OeaWallpaper) {
        // Bundled scenes are already tuned for icon contrast, so only a light
        // top-to-bottom scrim is added: Home's own text colouring follows the
        // scene's brightness (see OeaTheme), so this stays subtle.
        val strength = if (scene.light) 0x14 else 0x24
        paint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(alpha(Color.BLACK, strength), Color.TRANSPARENT, alpha(Color.BLACK, strength / 2)),
            floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        paint.shader = null
    }

    // ------------------------------------------------------------------ colour

    fun alpha(color: Int, value: Int): Int = Color.argb(value.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    fun blend(from: Int, to: Int, ratio: Float): Int {
        val r = ratio.coerceIn(0f, 1f)
        return Color.argb(
            255,
            (Color.red(from) * (1 - r) + Color.red(to) * r).toInt(),
            (Color.green(from) * (1 - r) + Color.green(to) * r).toInt(),
            (Color.blue(from) * (1 - r) + Color.blue(to) * r).toInt(),
        )
    }
}
