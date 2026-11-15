package com.oea.launcher.games

import android.content.Context
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
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.oea.engine.core.GameDescriptor
import com.oea.engine.core.PlayMode
import com.oea.games.ui.GameColors
import com.oea.games.ui.GameScheme
import com.oea.games.ui.GameSchemes
import com.oea.launcher.ui.OeaKit
import com.oea.launcher.ui.OeaPalette

/**
 * The Games hub's own art and card furniture.
 *
 * The hub used to draw every game as the same card: one gradient in the game's
 * accent, a shared motif and a title. That is why the three games looked like
 * one product with three names. Now each card is drawn in the game's own
 * materials and carries that game's own front-of-box picture — a sight picture,
 * a club board, a rail at night — and the card's actions are worded the way
 * that game is played ("Deploy", "New game", "Run").
 */
object OeaGamesArt {

    /** The scheme for a game, in the launcher's vocabulary. */
    fun scheme(id: String): GameScheme = GameSchemes.forId(id)

    /** A game's palette expressed for the launcher's own kit. */
    fun palette(scheme: GameScheme, light: Boolean = scheme.light): OeaPalette = OeaPalette(
        dark = !light,
        accent = scheme.accent,
        onAccent = scheme.onAccent,
        accentSoft = scheme.accentSoft,
        onAccentSoft = scheme.onAccentSoft,
        background = scheme.ground,
        drawer = scheme.ground,
        card = scheme.panel,
        cardHigh = scheme.well,
        stroke = scheme.stroke,
        text = scheme.text,
        textSecondary = scheme.textSecondary,
        textTertiary = scheme.textTertiary,
        homeText = Color.WHITE,
        homeTextSecondary = Color.WHITE,
        homeShadow = Color.BLACK,
        wallpaperLight = false,
        glass = GameColors.alpha(Color.WHITE, 0x1A),
        glassStrong = GameColors.alpha(Color.BLACK, 0x99),
        glassStroke = GameColors.alpha(Color.WHITE, 0x22),
        scrimTop = Color.TRANSPARENT,
        scrimBottom = Color.TRANSPARENT,
        danger = scheme.danger,
        success = scheme.success,
        warning = scheme.signal
    )

    /** The verb a game's front door uses, printed on its card. */
    fun verb(id: String): String = when (id) {
        "arena" -> "Deploy"
        "chess" -> "New game"
        "runner" -> "Run"
        else -> "Play"
    }

    /** A one-line description of what pressing the card starts, per game. */
    fun startLine(d: GameDescriptor, rememberedMode: String?): String = when (d.id) {
        "arena" -> "Solo against bots · ${rememberedMode ?: "Battle Royale"}"
        "chess" -> "Vs the AI · level 5 · Rapid 10 min"
        "runner" -> "One run on the night yard"
        else -> d.tagline
    }

    /** The mode chips a game wants to show on its card. */
    fun chips(d: GameDescriptor): List<String> = when (d.id) {
        "arena" -> listOf("Battle Royale", "Clash Squad", "Lone Wolf", "Warfare", "Gun Game", "Wi-Fi · 12")
        "chess" -> listOf("10 levels", "Pass & play", "Clocks", "3D / 2D", "Wi-Fi · 2")
        "runner" -> listOf("Endless", "Duel split", "Take turns", "Ghost race", "Wi-Fi · 8")
        else -> d.modes.map { if (it == PlayMode.SOLO) "Solo" else it.label }
    }

    /** Each game's front-of-box picture, drawn in code. */
    fun cover(id: String, scheme: GameScheme, radiusPx: Float): Drawable = when (id) {
        "arena" -> ArenaCover(scheme, radiusPx)
        "chess" -> ChessCover(scheme, radiusPx)
        "runner" -> RunnerCover(scheme, radiusPx)
        else -> GenericCover(scheme, radiusPx)
    }

    // ------------------------------------------------------------------ covers

    private abstract class BaseCover(protected val r: Float) : Drawable() {
        protected val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        protected val clip = Path()

        protected fun begin(canvas: Canvas): RectF? {
            val b = bounds
            if (b.width() <= 0 || b.height() <= 0) return null
            val rect = RectF(b)
            clip.reset()
            clip.addRoundRect(rect, r, r, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clip)
            return rect
        }

        protected fun end(canvas: Canvas) = canvas.restore()

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    /** Send Off: a scope reticle over a navy op map, with a warm visor sweep. */
    private class ArenaCover(private val s: GameScheme, r: Float) : BaseCover(r) {
        override fun draw(canvas: Canvas) {
            val rect = begin(canvas) ?: return
            val w = rect.width()
            val h = rect.height()
            paint.shader = LinearGradient(
                rect.left, rect.top, rect.right, rect.bottom,
                intArrayOf(GameColors.blend(s.ground, Color.BLACK, 0.25f), s.ground, GameColors.blend(s.ground, s.accent, 0.20f)),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawRect(rect, paint)
            paint.shader = null
            // Topographic contour lines: an island, read from above.
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = h * 0.006f
            for (i in 0 until 5) {
                paint.color = GameColors.alpha(s.accent, 0x28 - i * 4)
                canvas.drawOval(
                    RectF(
                        rect.left + w * 0.18f - i * w * 0.02f,
                        rect.top + h * 0.30f - i * h * 0.04f,
                        rect.left + w * 0.62f + i * w * 0.02f,
                        rect.top + h * 1.02f + i * h * 0.04f
                    ),
                    paint
                )
            }
            paint.style = Paint.Style.FILL
            // Grid.
            paint.strokeWidth = h * 0.004f
            paint.color = GameColors.alpha(s.accent, 0x1E)
            for (k in 1..7) canvas.drawLine(rect.left + w * k / 8f, rect.top, rect.left + w * k / 8f, rect.bottom, paint)
            for (k in 1..3) canvas.drawLine(rect.left, rect.top + h * k / 4f, rect.right, rect.top + h * k / 4f, paint)
            // Reticle.
            val cx = rect.left + w * 0.72f
            val cy = rect.top + h * 0.46f
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = h * 0.014f
            for (k in 1..3) {
                paint.color = GameColors.alpha(s.signal, 0x66 - k * 0x16)
                canvas.drawCircle(cx, cy, h * 0.12f * k, paint)
            }
            paint.color = GameColors.alpha(s.accent, 0xE0)
            paint.strokeWidth = h * 0.01f
            canvas.drawLine(cx - h * 0.22f, cy, cx - h * 0.05f, cy, paint)
            canvas.drawLine(cx + h * 0.05f, cy, cx + h * 0.22f, cy, paint)
            canvas.drawLine(cx, cy - h * 0.22f, cx, cy - h * 0.05f, paint)
            canvas.drawLine(cx, cy + h * 0.05f, cx, cy + h * 0.22f, paint)
            paint.style = Paint.Style.FILL
            paint.color = s.signal
            canvas.drawCircle(cx, cy, h * 0.014f, paint)
            // Amber scan sweep.
            paint.shader = LinearGradient(
                rect.left, 0f, rect.right, 0f,
                intArrayOf(GameColors.alpha(s.signal, 0), GameColors.alpha(s.signal, 0x70), GameColors.alpha(s.signal, 0)),
                floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawRect(rect.left, rect.top + h * 0.93f, rect.right, rect.bottom, paint)
            paint.shader = null
            end(canvas)
        }
    }

    /** Chess: a walnut board seen at an angle, with ivory pieces and brass rule. */
    private class ChessCover(private val s: GameScheme, r: Float) : BaseCover(r) {
        override fun draw(canvas: Canvas) {
            val rect = begin(canvas) ?: return
            val w = rect.width()
            val h = rect.height()
            paint.shader = LinearGradient(
                rect.left, rect.top, rect.right, rect.bottom,
                intArrayOf(s.ground, GameColors.blend(s.ground, s.signal, 0.20f)),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawRect(rect, paint)
            paint.shader = null
            // Walnut table.
            val tableTop = rect.top + h * 0.34f
            paint.shader = LinearGradient(
                rect.left, tableTop, rect.left, rect.bottom,
                intArrayOf(GameColors.blend(s.accent, Color.BLACK, 0.10f), GameColors.blend(s.accent, Color.BLACK, 0.42f)),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawRect(rect.left, tableTop, rect.right, rect.bottom, paint)
            paint.shader = null
            paint.color = GameColors.alpha(s.signal, 0xCC)
            canvas.drawRect(rect.left, tableTop - h * 0.008f, rect.right, tableTop + h * 0.006f, paint)
            // The board.
            val sq = h * 0.15f
            val top = tableTop + h * 0.10f
            for (row in 0 until 4) for (col in 0 until (w / sq).toInt() + 1) {
                if ((row + col) % 2 != 0) continue
                paint.color = GameColors.alpha(Color.WHITE, 0x33)
                canvas.drawRect(rect.left + col * sq, top + row * sq, rect.left + (col + 1) * sq, top + (row + 1) * sq, paint)
            }
            // Two pieces, ivory and dark, in silhouette.
            piece(canvas, rect.left + w * 0.66f, top + sq * 1.4f, sq * 1.5f, GameColors.blend(s.panel, Color.WHITE, 0.1f))
            piece(canvas, rect.left + w * 0.44f, top + sq * 2.1f, sq * 1.15f, GameColors.blend(s.accent, Color.BLACK, 0.35f))
            // Warm lamp light from the top left.
            paint.shader = RadialGradient(
                rect.left + w * 0.12f, rect.top, h * 0.95f,
                GameColors.alpha(Color.WHITE, 0x44), Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
            canvas.drawRect(rect, paint)
            paint.shader = null
            end(canvas)
        }

        private fun piece(canvas: Canvas, cx: Float, baseY: Float, size: Float, colour: Int) {
            paint.style = Paint.Style.FILL
            paint.color = colour
            val p = Path()
            p.addRect(cx - size * 0.30f, baseY - size * 0.14f, cx + size * 0.30f, baseY, Path.Direction.CW)
            p.addRect(cx - size * 0.17f, baseY - size * 0.62f, cx + size * 0.15f, baseY - size * 0.12f, Path.Direction.CW)
            p.moveTo(cx - size * 0.16f, baseY - size * 0.58f)
            p.lineTo(cx - size * 0.09f, baseY - size * 1.02f)
            p.lineTo(cx + size * 0.25f, baseY - size * 0.94f)
            p.lineTo(cx + size * 0.29f, baseY - size * 0.80f)
            p.lineTo(cx + size * 0.05f, baseY - size * 0.74f)
            p.lineTo(cx + size * 0.17f, baseY - size * 0.58f)
            p.close()
            canvas.drawPath(p, paint)
            paint.color = GameColors.alpha(Color.BLACK, 0x40)
            canvas.drawRect(cx - size * 0.34f, baseY - size * 0.05f, cx + size * 0.34f, baseY + size * 0.02f, paint)
        }
    }

    /** Endless Days: three rails running into a neon dusk under the yard skyline. */
    private class RunnerCover(private val s: GameScheme, r: Float) : BaseCover(r) {
        override fun draw(canvas: Canvas) {
            val rect = begin(canvas) ?: return
            val w = rect.width()
            val h = rect.height()
            paint.shader = LinearGradient(
                rect.left, rect.top, rect.left, rect.top + h * 0.75f,
                intArrayOf(GameColors.blend(s.ground, s.signal, 0.22f), s.ground),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawRect(rect, paint)
            paint.shader = null
            paint.shader = RadialGradient(
                rect.left + w * 0.5f, rect.top + h * 0.66f, h * 0.72f,
                GameColors.alpha(s.signal, 0x70), Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
            canvas.drawRect(rect, paint)
            paint.shader = null
            // Skyline of the yard.
            paint.color = GameColors.alpha(Color.BLACK, 0x66)
            var x = rect.left
            var i = 0
            while (x < rect.right) {
                val bw = w / 15f * (0.6f + ((i * 37) % 10) / 12f)
                val bh = h * (0.08f + ((i * 53) % 13) / 55f)
                canvas.drawRect(x, rect.top + h * 0.64f - bh, x + bw * 0.8f, rect.top + h * 0.64f, paint)
                x += bw
                i++
            }
            // Rails into the vanishing point.
            val vx = rect.left + w * 0.5f
            val vy = rect.top + h * 0.60f
            paint.strokeWidth = h * 0.02f
            for (k in intArrayOf(-1, 0, 1)) {
                paint.color = if (k == 0) GameColors.alpha(s.accent, 0xE6) else GameColors.alpha(s.accent, 0x84)
                canvas.drawLine(vx + k * w * 0.05f, vy, vx + k * w * 0.42f, rect.bottom, paint)
            }
            paint.strokeWidth = h * 0.008f
            var t = 0.05f
            while (t < 1f) {
                val y = vy + (rect.bottom - vy) * t * t
                val half = w * 0.035f + w * 0.42f * t * t
                paint.color = GameColors.alpha(s.text, (0x34 * (1f - t * 0.5f)).toInt())
                canvas.drawLine(vx - half, y, vx + half, y, paint)
                t += 0.05f
            }
            // Sunset lamps.
            for (k in 0 until 4) {
                val p = 0.2f + k * 0.2f
                val y = vy + (rect.bottom - vy) * p * p
                val half = w * 0.035f + w * 0.42f * p * p
                paint.color = GameColors.alpha(s.signal, 0xCC)
                canvas.drawCircle(vx - half, y - h * 0.015f, h * 0.01f + h * 0.012f * p, paint)
                paint.color = GameColors.alpha(s.signal, 0x55)
                canvas.drawCircle(vx + half, y - h * 0.015f, h * 0.01f + h * 0.012f * p, paint)
            }
            end(canvas)
        }
    }

    /** The Engine Lab and any future game: the accent as a lit gradient. */
    private class GenericCover(private val s: GameScheme, r: Float) : BaseCover(r) {
        override fun draw(canvas: Canvas) {
            val rect = begin(canvas) ?: return
            paint.shader = LinearGradient(
                rect.left, rect.bottom, rect.right, rect.top,
                intArrayOf(GameColors.blend(s.ground, s.accent, 0.35f), s.ground, GameColors.blend(s.accent, Color.WHITE, 0.08f)),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawRect(rect, paint)
            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = rect.height() * 0.012f
            paint.color = GameColors.alpha(s.accent, 0x40)
            for (i in 0 until 12) {
                val x = rect.left + rect.width() * (0.3f + 0.05f * i)
                canvas.drawLine(
                    x, rect.top + rect.height() * (0.5f + 0.3f * kotlin.math.sin(i.toFloat())),
                    x + rect.width() * 0.1f, rect.top + rect.height() * (0.5f - 0.3f * kotlin.math.sin(i * 1.7f)),
                    paint
                )
            }
            paint.style = Paint.Style.FILL
            end(canvas)
        }
    }
}

/**
 * One game's card in the Games hub, built in that game's own palette.
 *
 * It is not a shared "cover + title + Play" tile: the picture, the words and
 * the buttons come from the game, so Send Off's card reads like a deployment
 * order while Chess's reads like a fixture on a club noticeboard.
 */
class OeaGameCard(
    private val context: Context,
    private val kit: OeaKit,
    private val d: GameDescriptor,
    private val onOpen: () -> Unit,
    private val onPlay: () -> Unit,
    private val onFriends: (() -> Unit)?
) {
    private val scheme = OeaGamesArt.scheme(d.id)
    private val palette = OeaGamesArt.palette(scheme)
    private val card = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = kit.rounded(palette.card, if (scheme.light) 22 else 18, palette.stroke, 1)
        clipToOutline = true
    }
    private val density = context.resources.displayMetrics.density

    private fun dp(value: Int): Int = (value * density + 0.5f).toInt()

    fun view(): View = card.apply { build(kit) }

    private fun build(kit: OeaKit) {
        // Picture.
        val cover = View(context).apply {
            background = OeaGamesArt.cover(d.id, scheme, dp(if (scheme.light) 22 else 18).toFloat())
        }
        card.addView(cover, LinearLayout.LayoutParams(-1, dp(152)))

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }

        // Eyebrow + title in the game's own type.
        body.addView(
            TextView(context).apply {
                text = OeaGameCopy.eyebrow(d)
                textSize = 10.5f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(palette.accent)
                letterSpacing = 0.16f
            }
        )
        body.addView(
            TextView(context).apply {
                text = d.title
                textSize = 24f
                typeface = if (d.id == "chess") Typeface.create("serif", Typeface.BOLD) else Typeface.create("sans-serif-black", Typeface.BOLD)
                setTextColor(palette.text)
                setPadding(0, dp(2), 0, 0)
            }
        )
        body.addView(
            TextView(context).apply {
                text = OeaGameCopy.line(d)
                textSize = 12.5f
                setTextColor(palette.textSecondary)
                setLineSpacing(dp(2).toFloat(), 1f)
                setPadding(0, dp(4), 0, dp(10))
            }
        )

        // Chips: what this game actually offers.
        for (chips in OeaGamesArt.chips(d).chunked(3)) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            chips.forEach { label ->
                row.addView(
                    TextView(context).apply {
                        text = label
                        textSize = 10.5f
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        setTextColor(palette.textSecondary)
                        background = kit.rounded(palette.cardHigh, 10, palette.stroke, 1)
                        setPadding(dp(8), dp(4), dp(8), dp(4))
                    },
                    LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(5) }
                )
            }
            body.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(5) })
        }

        // Actions, in the game's own verb.
        val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(
            TextView(context).apply {
                text = OeaGameCopy.verb(d)
                textSize = 14.5f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
                setTextColor(palette.onAccent)
                background = kit.ripple(kit.rounded(palette.accent, 16), 16)
                isClickable = true
                setOnClickListener { onPlay() }
            },
            LinearLayout.LayoutParams(0, dp(46), 1.2f).apply { rightMargin = dp(8) }
        )
        if (onFriends != null) {
            actions.addView(
                TextView(context).apply {
                    text = OeaGameCopy.friends(d)
                    textSize = 13f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    gravity = Gravity.CENTER
                    setTextColor(palette.text)
                    background = kit.ripple(kit.rounded(palette.cardHigh, 16, palette.stroke, 1), 16)
                    isClickable = true
                    setOnClickListener { onFriends.invoke() }
                },
                LinearLayout.LayoutParams(0, dp(46), 0.9f).apply { rightMargin = dp(8) }
            )
        }
        actions.addView(
            TextView(context).apply {
                text = "Menu"
                textSize = 13f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
                setTextColor(palette.textSecondary)
                background = kit.ripple(kit.rounded(Color.TRANSPARENT, 16, palette.stroke, 1), 16)
                isClickable = true
                setOnClickListener { onOpen() }
            },
            LinearLayout.LayoutParams(0, dp(46), 0.8f)
        )
        body.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        card.addView(body, LinearLayout.LayoutParams(-1, -2))
    }
}


/** The card copy for a game, written per game rather than generically. */
object OeaGameCopy {
    fun eyebrow(d: GameDescriptor): String = when (d.id) {
        "arena" -> "TACTICAL SHOOTER"
        "chess" -> "THE CLUB"
        "runner" -> "RAIL ARCADE"
        else -> "OEA ENGINE"
    }

    /** One-line identity under the title. */
    fun line(d: GameDescriptor): String = when (d.id) {
        "arena" -> "Drop in, loot up and outlast the zone. Three islands, twelve modes, bots or friends."
        "chess" -> "Complete rules, ten AI levels and real clocks — a board you can sit at."
        "runner" -> "No finish line. Three lanes, janky trains and a record that keeps climbing."
        else -> d.tagline
    }

    /** The verb on the card's main button. */
    fun verb(d: GameDescriptor): String = when (d.id) {
        "arena" -> "Deploy"
        "chess" -> "New game"
        "runner" -> "Run"
        else -> "Play"
    }

    /** What the friends button says in this game's own world. */
    fun friends(d: GameDescriptor): String = when (d.id) {
        "arena" -> "Squad up"
        "chess" -> "A friend"
        "runner" -> "Ghost race"
        else -> "Friends"
    }
}
