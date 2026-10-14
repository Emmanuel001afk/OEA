package com.oea.launcher.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import com.oea.launcher.fault.OeaFaultShield
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * OEA's live wallpapers, drawn by Home itself.
 *
 * These are the scenes that cannot be a picture: [TIDE] flows, [AURORA] drifts,
 * [DEPTH] moves with the page you are on, and [INK] is drawn as brush work.
 * Home draws them under its own icons, so a live wallpaper needs no wallpaper
 * service call, no extra permission and no battery-hungry system service — and
 * Android's own wallpaper is left exactly as the user had it.
 *
 * Everything here is deliberately cheap to draw: a handful of gradients and
 * paths per frame, at most one redraw per [FRAME_MS], and nothing at all while
 * the view is off screen, in safe mode or in Low mode.
 */
object OeaLiveScenes {
    const val TIDE = "tide"
    const val DEPTH = "depth"
    const val INK = "ink"
    const val AURORA = "aurora"

    /** Longest edge of a still frame; larger screens are downscaled by Android. */
    const val MAX_EDGE = 2400

    private const val FRAME_MS = 33L

    val ids = listOf(TIDE, DEPTH, INK, AURORA)

    fun isLive(id: String?): Boolean = id != null && id in ids

    fun label(id: String): String = when (id) {
        TIDE -> "Tide"
        DEPTH -> "Depth"
        INK -> "Ink"
        AURORA -> "Aurora"
        else -> id
    }

    fun blurb(id: String): String = when (id) {
        TIDE -> "A living surface: layered water and light that never repeats, drawn by Home as it runs."
        DEPTH -> "Four ridges at different distances that shift as you move between Home pages."
        INK -> "Paper, brush washes and a red seal, drawn the way a hand would ink it."
        AURORA -> "Night sky with slow ribbons of aurora drifting across it."
        else -> ""
    }

    /** The accent a scene asks OEA's interface to use, so the whole look follows the artwork. */
    fun accent(id: String, dark: Boolean): Int = when (id) {
        TIDE -> if (dark) Color.rgb(94, 226, 214) else Color.rgb(0, 122, 128)
        DEPTH -> if (dark) Color.rgb(255, 176, 132) else Color.rgb(168, 74, 36)
        INK -> Color.rgb(178, 58, 46)
        AURORA -> if (dark) Color.rgb(122, 240, 190) else Color.rgb(0, 118, 92)
        else -> if (dark) Color.rgb(148, 187, 255) else Color.rgb(36, 86, 168)
    }

    /** Ink is drawn on paper, so it asks Home for its light text and panels. */
    fun isLight(id: String): Boolean = id == INK

    /** A single frame of [id], for the lock screen, previews and thumbnails. */
    fun still(id: String, width: Int, height: Int, timeMs: Long = 4_000L): Bitmap {
        val w = max(1, min(width, MAX_EDGE))
        val h = max(1, min(height, MAX_EDGE))
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        drawScene(id, canvas, paint, w.toFloat(), h.toFloat(), timeMs / 1000f, 0f)
        return bitmap
    }

    /** A live view of [id] for Home's backdrop. */
    fun view(context: Context, id: String): OeaLiveSceneView = OeaLiveSceneView(context, id)

    // ------------------------------------------------------------------ drawing

    internal fun drawScene(id: String, canvas: Canvas, paint: Paint, w: Float, h: Float, t: Float, parallax: Float) {
        when (id) {
            TIDE -> tide(canvas, paint, w, h, t)
            DEPTH -> depth(canvas, paint, w, h, t, parallax)
            INK -> ink(canvas, paint, w, h, t)
            AURORA -> aurora(canvas, paint, w, h, t)
            else -> {
                paint.shader = LinearGradient(0f, 0f, 0f, h, Color.rgb(14, 20, 34), Color.rgb(5, 8, 14), Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, w, h, paint)
                paint.shader = null
            }
        }
    }

    /** Layered water: bands whose phase and height drift, over a deep gradient. */
    private fun tide(canvas: Canvas, paint: Paint, w: Float, h: Float, t: Float) {
        paint.shader = LinearGradient(
            0f, 0f, w * 0.35f, h,
            intArrayOf(Color.rgb(4, 10, 22), Color.rgb(6, 32, 48), Color.rgb(8, 62, 74), Color.rgb(12, 96, 104)),
            floatArrayOf(0f, 0.42f, 0.74f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, paint)

        // A wide, soft light source low on the horizon.
        paint.shader = RadialGradient(
            w * 0.72f, h * 0.30f, max(w, h) * 0.55f,
            intArrayOf(Color.argb(120, 150, 245, 235), Color.argb(40, 60, 170, 190), Color.TRANSPARENT),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, paint)

        val bands = 6
        for (i in 0 until bands) {
            val depth = (i + 1) / bands.toFloat()
            val base = h * (0.30f + 0.13f * i)
            val amplitude = h * (0.022f + 0.016f * depth)
            val speed = 0.22f + 0.13f * (bands - i)
            val path = Path()
            path.moveTo(0f, h)
            path.lineTo(0f, base)
            var x = 0f
            while (x <= w) {
                val u = x / w
                val y = base +
                    sin((u * 2.1f + t * speed) * Math.PI.toFloat()) * amplitude +
                    sin((u * 4.7f - t * speed * 0.7f) * Math.PI.toFloat()) * amplitude * 0.42f
                path.lineTo(x, y)
                x += w / 26f
            }
            path.lineTo(w, h)
            path.close()
            val alpha = (36 + 26 * i).coerceAtMost(150)
            paint.shader = LinearGradient(
                0f, base, 0f, h,
                Color.argb(alpha, 90, 220, 220), Color.argb((alpha * 0.55f).toInt(), 8, 40, 60),
                Shader.TileMode.CLAMP,
            )
            canvas.drawPath(path, paint)
        }

        // Two caustic highlights sliding along the surface.
        paint.shader = null
        for (k in 0 until 2) {
            val cx = w * (0.16f + 0.62f * frac(t * (0.06f + 0.03f * k) + 0.37f * k))
            val cy = h * (0.46f + 0.22f * k) + sin(t * 0.5f + k) * h * 0.01f
            paint.shader = RadialGradient(
                cx, cy, h * 0.12f,
                intArrayOf(Color.argb(80, 210, 255, 250), Color.TRANSPARENT),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, w, h, paint)
        }
        paint.shader = null
    }

    /** Mountain ridges at four distances; [parallax] (-1..1) slides the near ones most. */
    private fun depth(canvas: Canvas, paint: Paint, w: Float, h: Float, t: Float, parallax: Float) {
        paint.shader = LinearGradient(
            0f, 0f, 0f, h * 0.85f,
            intArrayOf(Color.rgb(24, 32, 62), Color.rgb(64, 48, 84), Color.rgb(146, 86, 96), Color.rgb(214, 138, 96)),
            floatArrayOf(0f, 0.38f, 0.68f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, paint)

        // A low sun that keeps its place while the ridges move.
        paint.shader = RadialGradient(
            w * 0.30f, h * 0.60f, h * 0.34f,
            intArrayOf(Color.argb(150, 255, 214, 168), Color.argb(40, 255, 160, 120), Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        val layers = 4
        for (i in 0 until layers) {
            val far = i / (layers - 1).toFloat()          // 0 = furthest
            val base = h * (0.56f + 0.11f * i)
            val shift = -parallax * w * (0.04f + 0.10f * far)
            val path = Path()
            path.moveTo(0f, h)
            path.lineTo(shift - w * 0.1f, base)
            var x = -0.1f
            while (x <= 1.1f) {
                val u = x
                val ridge = base -
                    sin((u * (1.5f + far * 1.6f) + i * 1.7f) * Math.PI.toFloat()) * h * (0.035f + 0.055f * far) -
                    sin((u * (4.2f + far) + t * 0.05f * (1f + far)) * Math.PI.toFloat()) * h * 0.012f
                path.lineTo(shift + w * u, ridge)
                x += 0.045f
            }
            path.lineTo(shift + w * 1.1f, h)
            path.close()
            val shade = Color.rgb(
                (18 + 44 * far).toInt(),
                (20 + 30 * far).toInt(),
                (40 + 30 * far).toInt(),
            )
            paint.color = Color.argb((210 - 20 * i).coerceIn(120, 255), Color.red(shade), Color.green(shade), Color.blue(shade))
            canvas.drawPath(path, paint)
        }

        // Mist that softens the far ridges.
        paint.shader = LinearGradient(
            0f, h * 0.5f, 0f, h * 0.72f,
            Color.argb(70, 240, 220, 220), Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
    }

    /** Paper with brush washes: light, monochrome, and a single red seal. */
    private fun ink(canvas: Canvas, paint: Paint, w: Float, h: Float, t: Float) {
        canvas.drawColor(Color.rgb(246, 243, 236))

        // Fibre: a few very faint horizontal lines, so the paper is not a flat fill.
        paint.color = Color.argb(10, 90, 84, 70)
        var y = h * 0.06f
        var i = 0
        while (y < h * 0.96f) {
            canvas.drawRect(0f, y, w, y + max(1f, h * 0.0016f), paint)
            y += h * 0.045f + (i % 3) * h * 0.01f
            i++
        }

        // Brush washes: soft radial blobs, heavier toward the bottom right.
        val washes = listOf(
            Triple(0.26f, 0.30f, 0.30f),
            Triple(0.72f, 0.24f, 0.22f),
            Triple(0.55f, 0.52f, 0.34f),
            Triple(0.20f, 0.72f, 0.26f),
            Triple(0.80f, 0.78f, 0.30f),
        )
        washes.forEachIndexed { index, (fx, fy, fr) ->
            val breathe = 1f + 0.04f * sin(t * 0.18f + index * 1.3f)
            val cx = w * fx
            val cy = h * fy
            val r = min(w, h) * fr * breathe
            paint.shader = RadialGradient(
                cx, cy, r,
                intArrayOf(Color.argb(38, 20, 20, 24), Color.argb(14, 20, 20, 24), Color.TRANSPARENT),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, w, h, paint)
        }
        paint.shader = null

        // One decisive brush stroke, drawn as a tapered band.
        val stroke = Path()
        val sx = w * 0.10f
        val sy = h * 0.62f
        stroke.moveTo(sx, sy)
        stroke.cubicTo(w * 0.30f, h * 0.50f, w * 0.52f, h * 0.74f, w * 0.88f, h * 0.56f)
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = max(2f, min(w, h) * 0.028f)
        paint.color = Color.argb(200, 22, 22, 26)
        canvas.drawPath(stroke, paint)
        paint.strokeWidth = max(1f, min(w, h) * 0.012f)
        paint.color = Color.argb(90, 22, 22, 26)
        canvas.drawPath(stroke, paint)
        paint.style = Paint.Style.FILL

        // The seal: a small red square, the one warm note on the page.
        val seal = min(w, h) * 0.085f
        val left = w - seal - w * 0.10f
        val top = h - seal - h * 0.12f
        paint.color = Color.rgb(178, 58, 46)
        canvas.drawRoundRect(left, top, left + seal, top + seal, seal * 0.16f, seal * 0.16f, paint)
        paint.color = Color.argb(70, 255, 255, 255)
        canvas.drawRect(left + seal * 0.18f, top + seal * 0.18f, left + seal * 0.82f, top + seal * 0.82f, paint)
        paint.color = Color.rgb(178, 58, 46)
        canvas.drawRect(left + seal * 0.30f, top + seal * 0.30f, left + seal * 0.44f, top + seal * 0.70f, paint)
        canvas.drawRect(left + seal * 0.56f, top + seal * 0.30f, left + seal * 0.70f, top + seal * 0.70f, paint)
        canvas.drawRect(left + seal * 0.30f, top + seal * 0.46f, left + seal * 0.70f, top + seal * 0.54f, paint)

        // Vignette, so the page has edges.
        paint.shader = RadialGradient(
            w * 0.5f, h * 0.45f, max(w, h) * 0.72f,
            intArrayOf(Color.TRANSPARENT, Color.argb(45, 60, 54, 44)),
            floatArrayOf(0.6f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
    }

    /** Night sky with slow aurora ribbons and a field of stars. */
    private fun aurora(canvas: Canvas, paint: Paint, w: Float, h: Float, t: Float) {
        paint.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(Color.rgb(4, 8, 22), Color.rgb(8, 18, 42), Color.rgb(6, 26, 40), Color.rgb(3, 8, 16)),
            floatArrayOf(0f, 0.42f, 0.7f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        // Stars: a deterministic field, twinkling very slowly.
        val starCount = 90
        for (i in 0 until starCount) {
            val fx = frac(i * 0.61803398875f)
            val fy = frac(i * 0.38196601125f * 1.7f) * 0.72f
            val twinkle = 0.55f + 0.45f * sin(t * 0.35f + i)
            val radius = max(0.7f, min(w, h) * (0.0012f + 0.0016f * frac(i * 0.2718f)))
            paint.color = Color.argb((200 * twinkle).toInt().coerceIn(30, 235), 235, 240, 255)
            canvas.drawCircle(w * fx, h * fy, radius, paint)
        }

        // Three ribbons, each a wide sine band with its own colour and speed.
        val ribbons = listOf(
            Triple(Color.rgb(70, 225, 168), 0.26f, 0.030f),
            Triple(Color.rgb(96, 190, 255), 0.36f, 0.022f),
            Triple(Color.rgb(178, 140, 255), 0.46f, 0.017f),
        )
        ribbons.forEachIndexed { index, (color, fy, amp) ->
            val base = h * fy
            val path = Path()
            path.moveTo(0f, h)
            var x = 0f
            while (x <= w) {
                val u = x / w
                val y = base +
                    sin((u * 1.6f + t * (0.05f + 0.02f * index) + index * 0.7f) * Math.PI.toFloat()) * h * (amp * 6f) +
                    sin((u * 3.4f - t * 0.03f) * Math.PI.toFloat()) * h * amp * 2.2f
                path.lineTo(x, y)
                x += w / 30f
            }
            path.lineTo(w, h)
            path.close()
            paint.shader = LinearGradient(
                0f, base - h * 0.1f, 0f, base + h * 0.26f,
                Color.argb(120, Color.red(color), Color.green(color), Color.blue(color)),
                Color.argb(0, Color.red(color), Color.green(color), Color.blue(color)),
                Shader.TileMode.CLAMP,
            )
            canvas.drawPath(path, paint)
        }
        paint.shader = null

        // A cool glow hanging over the whole sky.
        paint.shader = RadialGradient(
            w * 0.5f, h * 0.34f, max(w, h) * 0.6f,
            intArrayOf(Color.argb(46, 90, 220, 190), Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
    }

    private fun frac(value: Float): Float = value - kotlin.math.floor(value)
}

/**
 * Home's live backdrop. One view, one scene, redrawn only while it is actually
 * visible; [animated] is switched off by Home when the phone is in Low mode, in
 * safe mode or when the screen is off.
 */
class OeaLiveSceneView(context: Context, var sceneId: String) : View(context) {
    var animated = true

    /** Home's page position, -1..1, so Depth (and any layered scene) can parallax. */
    var parallax = 0f
        set(value) {
            val next = value.coerceIn(-1f, 1f)
            if (abs(next - field) < 0.004f) return
            field = next
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val started = SystemClock.uptimeMillis()
    private var lastFrame = 0L
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.uptimeMillis()
            if (now - lastFrame >= FRAME_MS) {
                lastFrame = now
                invalidate()
            }
            postOnAnimation(this)
        }
    }

    fun release() {
        running = false
        removeCallbacks(tick)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = animated
        if (running) postOnAnimation(tick)
    }

    override fun onDetachedFromWindow() {
        release()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        val shouldRun = animated && visibility == VISIBLE
        if (shouldRun && !running) {
            running = true
            postOnAnimation(tick)
        } else if (!shouldRun) {
            release()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        // A fault while drawing a backdrop must never reach Home: a failed frame
        // simply leaves the previous one on screen.
        OeaFaultShield.guard("home:scene-draw") {
            val t = if (animated) (SystemClock.uptimeMillis() - started) / 1000f else 4f
            OeaLiveScenes.drawScene(sceneId, canvas, paint, w, h, t, parallax)
        }
        paint.shader = null
        paint.style = Paint.Style.FILL
    }
}
