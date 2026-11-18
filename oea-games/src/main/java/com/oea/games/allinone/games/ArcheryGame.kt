package com.oea.games.allinone.games

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.oea.games.allinone.ArcadeBaseGame
import com.oea.games.allinone.ArcadeColors
import com.oea.games.allinone.ArcadeDraw
import com.oea.games.allinone.ArcadePlayMode
import com.oea.games.allinone.ArcadeTouch
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Archery: five arrows each, an honest target and a wind arrow.
 *
 * The player aims on the face; the crosshair drifts while held, so a good shot is
 * a timed one. Wind pushes the arrow after release and is shown before you shoot —
 * aim into it, never at the middle and hope. Levels widen the wind and tighten the
 * AI's grouping; the target itself keeps the real ten rings.
 */
class ArcheryGame : ArcadeBaseGame() {

    private data class Arrow(val x: Float, val y: Float, val player: Int, val value: Int)

    private val arrows = ArrayList<Arrow>()
    private val scores = intArrayOf(0, 0)
    private val left = intArrayOf(5, 5)
    private var turn = 0
    private var wind = 0f
    private var aiming = false
    private var aimX = 0f
    private var aimY = 0f
    private var animating = 0f
    private var pending: Pair<Float, Float>? = null
    private var pendingPlayer = 0
    private var aiDelay = 0f
    private var targetCx = 0f
    private var targetCy = 0f
    private var targetR = 1f

    private val twoPlayer: Boolean get() = config.mode == ArcadePlayMode.TWO_PLAYER

    override fun onStart() {
        scores[0] = 0
        scores[1] = 0
        left[0] = 5
        left[1] = 5
        turn = 0
        arrows.clear()
        aiming = false
        animating = 0f
        pending = null
        aiDelay = 0f
        val strength = 0.25f + config.level * 0.11f + skill * 0.4f
        wind = (rand() * 2f - 1f) * strength
    }

    // ------------------------------------------------------------------- shots

    private fun shoot(x: Float, y: Float, player: Int) {
        // The wind acts on the whole flight; the aim drift is the player's own hand.
        val drift = targetR * (0.3f + config.level * 0.02f) * wind
        val jitter = targetR * (0.035f + (1f - skill) * 0.03f)
        val hx = x + drift + (rand() - 0.5f) * jitter
        val hy = y + (rand() - 0.5f) * jitter
        pending = hx to hy
        pendingPlayer = player
        animating = 0.0001f
    }

    private fun ringValue(x: Float, y: Float): Int {
        val dx = x - targetCx
        val dy = y - targetCy
        val d = sqrt(dx * dx + dy * dy) / targetR
        if (d > 1f) return 0
        return (10 - (d * 10f).toInt()).coerceIn(1, 10)
    }

    private fun land() {
        val hit = pending ?: return
        pending = null
        val value = ringValue(hit.first, hit.second)
        arrows.add(Arrow(hit.first, hit.second, pendingPlayer, value))
        scores[pendingPlayer] += value
        left[pendingPlayer]--
        if (left[0] == 0 && left[1] == 0) {
            settle()
            return
        }
        turn = if (twoPlayer) 1 - turn else if (left[1] > 0) 1 else 0
        if (!twoPlayer && turn == 1) aiDelay = 0.55f
    }

    private fun settle() {
        val won = if (twoPlayer) scores[0] >= scores[1] else scores[0] > scores[1]
        val gold = arrows.count { it.value == 10 }
        finish(
            won = won,
            headline = if (twoPlayer) {
                when {
                    scores[0] == scores[1] -> "A shoot-off draw"
                    scores[0] > scores[1] -> "Player 1 takes it"
                    else -> "Player 2 takes it"
                }
            } else if (won) "You outshot the AI" else "The AI outshot you",
            detail = "${scores[0]} — ${scores[1]}. $gold gold${if (gold == 1) "" else "s"}. " +
                "The wind arrow was showing ${String.format("%.1f", wind)} — the next round's wind is stronger.",
            score = scores[0] * 25L + gold * 10,
            stars = when {
                scores[0] > scores[1] + 6 -> 3
                won -> 2
                else -> 1
            }
        )
    }

    override fun update(dt: Float) {
        super.update(dt)
        if (done()) return
        if (animating > 0f) {
            animating += dt * 1.9f
            if (animating >= 1f) {
                animating = 0f
                land()
            }
            return
        }
        if (aiDelay > 0f) {
            aiDelay -= dt
            if (aiDelay <= 0f && !twoPlayer && turn == 1 && left[1] > 0) aiShoot()
        }
    }

    private fun aiShoot() {
        if (done()) return
        val grouping = targetR * (0.38f - skill * 0.3f).coerceAtLeast(0.06f)
        val angle = rand() * Math.PI.toFloat() * 2f
        val distance = sqrt(rand()) * grouping
        val x = targetCx + cos(angle) * distance - targetR * 0.3f * wind * 0.8f
        val y = targetCy + sin(angle) * distance
        shoot(x, y, 1)
    }

    override fun touch(phase: ArcadeTouch, x: Float, y: Float, width: Float, height: Float): Boolean {
        if (done() || animating > 0f) return false
        if (!twoPlayer && turn == 1) return false
        when (phase) {
            ArcadeTouch.DOWN, ArcadeTouch.MOVE -> {
                aiming = true
                aimX = x
                aimY = y
                return true
            }
            ArcadeTouch.UP -> {
                if (!aiming) return false
                aiming = false
                val drift = targetR * 0.06f * (1f + skill)
                shoot(aimX + sin(elapsed * 3f) * drift, aimY + cos(elapsed * 2.2f) * drift, turn)
                return true
            }
        }
        return false
    }

    override fun status(): String {
        if (done()) return "Round over"
        return if (twoPlayer) {
            "Player ${turn + 1} · arrows ${left[turn]} · ${scores[0]} — ${scores[1]}"
        } else {
            "You ${scores[0]} · AI ${scores[1]} · arrows ${left[0]} · wind ${String.format("%.1f", wind)}"
        }
    }

    override fun hint(): String = when {
        arrows.isEmpty() -> "Aim on the target; hold to steady, release to shoot."
        else -> ""
    }

    override fun score(): Long = scores[0].toLong()

    override fun draw(canvas: Canvas, width: Float, height: Float) {
        val sky = ArcadeDraw.paint(Color.TRANSPARENT)
        sky.shader = android.graphics.LinearGradient(0f, 0f, 0f, height,
            intArrayOf(0xFF7ACBE0.toInt(), 0xFFD8F0C6.toInt()), null, android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width, height, sky)

        targetCx = width * 0.54f
        targetCy = height * 0.3f
        targetR = min(width * 0.26f, height * 0.16f)

        // Field and target stand.
        val ground = ArcadeDraw.paint(0xFF8CC46B.toInt())
        canvas.drawRect(0f, height * 0.6f, width, height, ground)
        ArcadeDraw.panel(canvas, targetCx - targetR * 0.08f, targetCy + targetR * 0.8f,
            targetCx + targetR * 0.08f, height * 0.62f, 0xFF8A6A4F.toInt(), targetR * 0.03f)

        // Ten rings, outermost first.
        val ringColors = intArrayOf(
            0xFFF7F7F2.toInt(), 0xFFF7F7F2.toInt(),
            0xFF2B2635.toInt(), 0xFF2B2635.toInt(),
            0xFF3E8EE0.toInt(), 0xFF3E8EE0.toInt(),
            0xFFE3566E.toInt(), 0xFFE3566E.toInt(),
            0xFFFFC24B.toInt(), 0xFFFFC24B.toInt(),
        )
        for (index in ringColors.indices) {
            val fraction = 1f - index / 10f
            ArcadeDraw.circle(canvas, targetCx, targetCy, targetR * fraction, ringColors[index])
        }
        ArcadeDraw.circle(canvas, targetCx, targetCy, targetR, Color.TRANSPARENT, 0xFF5B4636.toInt(), targetR * 0.035f)

        // Arrows already shot.
        arrows.forEach { arrow ->
            val color = if (arrow.player == 0) ArcadeColors.CORAL else ArcadeColors.INK
            val len = targetR * 0.34f
            ArcadeDraw.line(canvas, arrow.x, arrow.y, arrow.x + len * 0.75f, arrow.y + len, color, targetR * 0.035f)
            ArcadeDraw.circle(canvas, arrow.x, arrow.y, targetR * 0.045f, color)
        }

        // Flight.
        if (animating > 0f && pending != null) {
            val hit = pending!!
            val t = animating.coerceIn(0f, 1f)
            val bowX = width * 0.2f
            val bowY = height * 0.72f
            val x = bowX + (hit.first - bowX) * t
            val y = bowY + (hit.second - bowY) * t - sin(t * Math.PI.toFloat()) * height * 0.14f
            ArcadeDraw.line(canvas, x, y, x + width * 0.05f, y + height * 0.02f, 0xFF5B4636.toInt(), height * 0.008f)
            ArcadeDraw.line(canvas, x, y, x - width * 0.05f, y - height * 0.02f, 0xFFE3566E.toInt(), height * 0.01f)
        }

        // Archer.
        ArcadeDraw.stickman(canvas, width * 0.18f, height * 0.8f, height * 0.22f, ArcadeColors.INK, pose = 2)
        val bow = android.graphics.Path().apply {
            arcTo(RectF(width * 0.05f, height * 0.6f, width * 0.22f, height * 0.78f), -80f, 160f, false)
        }
        canvas.drawPath(bow, ArcadeDraw.paint(0xFF7A4E2D.toInt(), width * 0.012f))

        // Aim.
        if (aiming && !done()) {
            val cross = ArcadeDraw.paint(0xCC2B2635.toInt(), targetR * 0.02f)
            val px = aimX + sin(elapsed * 3f) * targetR * 0.06f * (1f + skill)
            val py = aimY + cos(elapsed * 2.2f) * targetR * 0.06f * (1f + skill)
            canvas.drawLine(px - targetR * 0.22f, py, px + targetR * 0.22f, py, cross)
            canvas.drawLine(px, py - targetR * 0.22f, px, py + targetR * 0.22f, cross)
            canvas.drawCircle(px, py, targetR * 0.16f, ArcadeDraw.paint(Color.TRANSPARENT, targetR * 0.02f).apply { color = 0xCC2B2635.toInt() })
            // Wind hint on the aim line.
            ArcadeDraw.text(canvas, "wind", px, py - targetR * 0.28f, targetR * 0.13f, 0xCC2B2635.toInt(), bold = true, align = Paint.Align.CENTER)
        }

        // Scoreboard.
        ArcadeDraw.panel(canvas, RectF(width * 0.05f, height * 0.055f, width * 0.95f, height * 0.11f), 0x33000000, height * 0.02f)
        ArcadeDraw.text(canvas, "YOU ${scores[0]}", width * 0.09f, height * 0.09f, height * 0.02f, Color.WHITE, bold = true)
        ArcadeDraw.text(canvas, "${if (twoPlayer) "P2" else "AI"} ${scores[1]}", width * 0.91f, height * 0.09f, height * 0.02f,
            Color.WHITE, bold = true, align = Paint.Align.RIGHT)
        ArcadeDraw.text(canvas, "ARROWS ${left[0]}  ·  WIND ${String.format("%.1f", wind)}", width * 0.5f, height * 0.088f,
            height * 0.013f, 0xDDFFFFFF.toInt(), bold = true, align = Paint.Align.CENTER)
        // Wind arrow.
        val wx = width * 0.5f
        val wy = height * 0.135f
        if (wind != 0f) {
            val dir = if (wind > 0) 1f else -1f
            ArcadeDraw.line(canvas, wx - dir * width * 0.06f, wy, wx + dir * width * 0.06f, wy, 0x66000000, height * 0.004f)
            ArcadeDraw.line(canvas, wx + dir * width * 0.06f, wy, wx + dir * width * 0.03f, wy - height * 0.008f, 0x66000000, height * 0.004f)
            ArcadeDraw.line(canvas, wx + dir * width * 0.06f, wy, wx + dir * width * 0.03f, wy + height * 0.008f, 0x66000000, height * 0.004f)
        }
    }
}
