package com.oea.launcher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import com.oea.launcher.data.OeaDataStore

/**
 * What a launcher mode *feels* like, as opposed to what it looks like.
 *
 * A mode used to change the wallpaper, the glass style and the icon shape — which
 * is why Depth and Ink read as "just a wallpaper": the interface itself behaved
 * exactly as it did before. Everything here is the missing half. One table decides
 * how a mode moves, and Home asks it for every motion it makes:
 *
 *  - how far the backdrop drifts against the pages (parallax),
 *  - how far icons lag behind the page they are on (trail),
 *  - which page effect the mode uses (liquid, depth, paper, classic),
 *  - whether the glass has moving light inside it (caustics),
 *  - whether presses bloom like a brush touching paper (ink),
 *  - and whether the whole surface carries a paper grain.
 *
 * The numbers are progressive: a mode is unmistakable next to another one, and
 * every value is small enough that the launcher still feels quick.
 */
object OeaModeCharacter {

    /** One mode's motion. Values are deliberately readable rather than tuned by eye. */
    data class P(
        /** Pager effect this mode uses. See OeaHomeViews.applyEffect. */
        val pageEffect: String,
        /** 0..1 — how far the backdrop drifts as pages move. */
        val parallax: Float = 0.45f,
        /** 0..1 — how much icons lag the page. 0 for a plain slide. */
        val trail: Float = 0f,
        /** Moving light inside the glass panes. */
        val caustics: Boolean = false,
        /** Presses spread like ink on paper. */
        val ink: Boolean = false,
        /** Paper grain and a warm tint over the whole surface. */
        val paper: Boolean = false,
        /** Extra elevation for icons and cards (Depth reads as height). */
        val lift: Int = 0,
        /** Milliseconds of settle time for a page; lower is crisper. */
        val settle: Long = 220L
)

    val CLASSIC = P(pageEffect = "classic", parallax = 0.45f, settle = 220L)
    val LIQUID = P(pageEffect = "liquid", parallax = 0.6f, trail = 1f, caustics = true, settle = 240L)
    val TIDE = P(pageEffect = "liquid", parallax = 0.95f, trail = 0.75f, caustics = true, settle = 300L)
    val DEPTH = P(pageEffect = "depth", parallax = 1f, trail = 0.35f, lift = 5, settle = 260L)
    val INK = P(pageEffect = "paper", parallax = 0.2f, ink = true, paper = true, settle = 190L)
    // New modes with distinct visual character
    val STUDY = P(pageEffect = "fade", parallax = 0.1f, trail = 0f, settle = 180L)
    val MUSIC = P(pageEffect = "liquid", parallax = 0.7f, trail = 0.6f, caustics = true, lift = 3, settle = 280L)
    val SLEEPING = P(pageEffect = "none", parallax = 0f, trail = 0f, settle = 150L)
    val FOCUS = P(pageEffect = "none", parallax = 0f, trail = 0f, settle = 150L)
    val GAMING = P(pageEffect = "zoom", parallax = 0.8f, trail = 0.5f, lift = 4, settle = 200L)
    val READING = P(pageEffect = "paper", parallax = 0.05f, paper = true, settle = 180L)
    val WORK = P(pageEffect = "stack", parallax = 0.3f, trail = 0.2f, settle = 220L)
    val DRIVING = P(pageEffect = "none", parallax = 0f, lift = 2, settle = 180L)

    fun of(modeId: String?): P = when (modeId) {
        OeaMode.LIQUID.id -> LIQUID
        OeaMode.TIDE.id -> TIDE
        OeaMode.DEPTH.id -> DEPTH
        OeaMode.INK.id -> INK
        OeaMode.STUDY.id -> STUDY
        OeaMode.MUSIC.id -> MUSIC
        OeaMode.SLEEPING.id -> SLEEPING
        OeaMode.FOCUS.id -> FOCUS
        OeaMode.GAMING.id -> GAMING
        OeaMode.READING.id -> READING
        OeaMode.WORK.id -> WORK
        OeaMode.DRIVING.id -> DRIVING
        else -> CLASSIC
    }

    /** The character of the mode in use right now. */
    fun current(context: Context): P = of(OeaDataStore.get(context).launcherMode())

    /**
     * Ink on paper: a soft grain and a warmth that makes a light page read as a
     * sheet rather than as a white background. Drawn with a few hundred very
     * translucent dots, generated once per size and reused, so it costs nothing
     * per frame.
     */
    class PaperOverlay(context: Context) : View(context) {
        private val paint = Paint()
        private val dots = ArrayList<FloatArray>(0)
        private var seed = 0

        init {
            isClickable = false
            isFocusable = false
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            // A warm wash top to bottom: the page is not pure white anywhere.
            paint.shader = LinearGradient(
                0f, 0f, w * 0.4f, h,
                intArrayOf(Color.argb(0x0E, 120, 96, 64), Color.argb(0x05, 120, 96, 64), Color.argb(0x12, 60, 52, 40)),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
)
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null
            if (seed != width * 31 + height) build(w, h)
            for (dot in dots) {
                paint.color = Color.argb((dot[3]).toInt(), 90, 78, 60)
                canvas.drawCircle(dot[0], dot[1], dot[2], paint)
            }
        }

        private fun build(w: Float, h: Float) {
            seed = width * 31 + height
            dots.clear()
            val random = java.util.Random(seed.toLong())
            val count = ((w * h) / 5200f).toInt().coerceIn(120, 900)
            repeat(count) {
                dots.add(
                    floatArrayOf(
                        random.nextFloat() * w,
                        random.nextFloat() * h,
                        0.6f + random.nextFloat() * 1.4f,
                        4f + random.nextFloat() * 9f
)
)
            }
        }
    }

    /**
     * Ink bloom: what a brush does when it touches paper.
     *
     * A press draws an expanding, fading stain under the finger — the launcher's
     * own press feedback in Ink mode, in place of a glass highlight that would look
     * wrong on a sheet of paper. It fades to nothing in a quarter of a second and
     * is drawn over the wallpaper layer, below the icons.
     */
    class InkBloomView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var x = 0f
        private var y = 0f
        private var progress = 1f
        private var running = false

        init {
            isClickable = false
            isFocusable = false
            setWillNotDraw(false)
        }

        /** Starts a bloom at a point in this view's coordinates. */
        fun bloom(atX: Float, atY: Float, radiusPx: Float) {
            x = atX
            y = atY
            maxRadius = radiusPx
            progress = 0f
            running = true
            invalidate()
            postOnAnimation(tick)
        }

        private var maxRadius = 200f
        private val tick = object : Runnable {
            override fun run() {
                progress += 0.085f
                if (progress >= 1f) {
                    running = false
                    invalidate()
                    return
                }
                invalidate()
                postOnAnimation(this)
            }
        }

        override fun onDraw(canvas: Canvas) {
            if (!running && progress >= 1f) return
            val radius = maxRadius * (0.25f + 0.75f * progress)
            val alpha = (0x50 * (1f - progress)).toInt().coerceIn(0, 0x50)
            // Two stops: a dense core and a fast-fading rim, like ink wicking out.
            paint.shader = android.graphics.RadialGradient(
                x, y, radius,
                intArrayOf(Color.argb(alpha, 24, 22, 20), Color.argb(alpha / 3, 24, 22, 20), Color.TRANSPARENT),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
)
            canvas.drawCircle(x, y, radius, paint)
            paint.shader = null
        }
    }
}
