package com.oea.games.allinone.games

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.oea.games.allinone.ArcadeBaseGame
import com.oea.games.allinone.ArcadeColors
import com.oea.games.allinone.ArcadeDraw
import com.oea.games.allinone.ArcadePlayMode
import com.oea.games.allinone.ArcadeTouch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Darts: 501 (and 301 from level three), three darts a turn.
 *
 * The player aims on the board; a live drift means the release has to be timed
 * rather than merely tapped, which is the closest a thumb gets to a real throw.
 * The AI throws are simulated with the same scoring and a scatter set by the
 * level and difficulty, so a hard leg genuinely needs trebles.
 *
 * The numbers and ring values are the real ones: single, double, treble, outer
 * bull and bull, and a bust drops you back to the score you started the turn on.
 */
class DartsGame : ArcadeBaseGame() {

    private data class Dart(val x: Float, val y: Float, val player: Int, val value: Int, val label: String)

    private val order = intArrayOf(20, 1, 18, 4, 13, 6, 10, 15, 2, 17, 3, 19, 7, 16, 8, 11, 14, 9, 12, 5)
    private val scores = intArrayOf(501, 501)
    private var turn = 0
    private var dartsLeft = 3
    private var turnStart = 501
    private var aiDelay = 0f
    private val darts = ArrayList<Dart>()
    private var aimX = 0f
    private var aimY = 0f
    private var aiming = false
    private var cx = 0f
    private var cy = 0f
    private var radius = 1f
    private var lastMessage = ""
    private var legs = 0
    private val twoPlayer: Boolean get() = config.mode == ArcadePlayMode.TWO_PLAYER

    override fun onStart() {
        val start = if (config.level >= 3) 301 else 501
        scores[0] = start
        scores[1] = start
        turn = 0
        dartsLeft = 3
        turnStart = start
        aiDelay = 0f
        darts.clear()
        aiming = false
        lastMessage = ""
        legs = 0
    }

    // ------------------------------------------------------------------- throw

    private fun valueAt(x: Float, y: Float): Triple<Int, String, Boolean> {
        val dx = x - cx
        val dy = y - cy
        val distance = sqrt(dx * dx + dy * dy)
        val r = distance / radius
        if (r > 1.04f) return Triple(0, "MISS", false)
        if (r < 0.035f) return Triple(50, "BULL", true)
        if (r < 0.095f) return Triple(25, "25", false)
        var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat() + 90f
        angle = ((angle % 360f) + 360f) % 360f
        val sector = order[(((angle + 9f) / 18f).toInt()) % 20]
        return when {
            r in 0.42f..0.5f -> Triple(sector * 3, "T$sector", true)
            r in 0.9f..1.0f -> Triple(sector * 2, "D$sector", true)
            else -> Triple(sector, sector.toString(), true)
        }
    }

    private fun throwDart(x: Float, y: Float, player: Int) {
        val (value, label, isDouble) = valueAt(x, y)
        darts.add(Dart(x, y, player, value, label))
        dartsLeft--
        val remaining = scores[player] - value
        lastMessage = if (value == 0) "Missed the board" else "$label · $value"

        // 501/301 is double-out: the dart that reaches zero must be a double or
        // the bull. Reaching zero any other way is a bust, exactly as on a real
        // oche, and a bust ends the visit immediately.
        if (remaining == 0 && isDouble) {
            scores[player] = 0
            settle(player)
            return
        }
        if (remaining <= 1 || (remaining == 0 && !isDouble)) {
            scores[player] = turnStart
            lastMessage = if (remaining == 0) "Bust — $label is not a double" else "Bust — back to $turnStart"
            endVisit()
            return
        }
        scores[player] = remaining
        if (dartsLeft <= 0) endVisit()
    }

    private fun endVisit() {
        dartsLeft = 3
        turn = 1 - turn
        turnStart = scores[turn]
        if (!twoPlayer && turn == 1) aiDelay = 0.6f
    }

    private fun settle(winner: Int) {
        val won = winner == 0
        finish(
            won = won,
            headline = when {
                twoPlayer -> "Player ${winner + 1} checks out"
                winner == 0 -> "You check out"
                else -> "The AI checks out"
            },
            detail = "Finished on ${lastMessage.lowercase()} with the ${(3 - dartsLeft).coerceAtLeast(1)} dart throw. " +
                "Aim at treble twenty while the score is high, then switch to the double that leaves zero.",
            score = (if (winner == 0) 300L else 80L) + config.level * 10L,
            stars = if (winner == 0) 3 else 1
        )
    }

    override fun update(dt: Float) {
        super.update(dt)
        if (done()) return
        if (aiDelay > 0f) {
            aiDelay -= dt
            if (aiDelay <= 0f && !twoPlayer && turn == 1) aiThrow()
            return
        }
    }

    private fun aiThrow() {
        if (done()) return
        val remaining = scores[1]
        val aim = aimFor(remaining)
        val scatter = radius * (0.17f - skill * 0.13f).coerceAtLeast(0.016f) + radius * config.level * 0.0015f
        val angle = rand() * Math.PI.toFloat() * 2f
        val distance = sqrt(rand()) * scatter
        val x = aim.first + cos(angle) * distance
        val y = aim.second + sin(angle) * distance
        throwDart(x, y, 1)
        if (dartsLeft == 3 && !done()) aiDelay = 0.75f
        else if (!done()) aiDelay = 0.65f
    }

    /** The AI's target: a treble while there is room, the right double when closing. */
    private fun aimFor(remaining: Int): Pair<Float, Float> {
        val (sector, ring) = when {
            remaining <= 40 && remaining % 2 == 0 -> (remaining / 2) to "double"
            remaining <= 50 && remaining % 2 == 1 -> (20) to "single"
            else -> (20) to "treble"
        }
        var angle = 0f
        val index = order.indexOf(sector)
        angle = index * 18f - 90f
        val r = when (ring) {
            "double" -> radius * 0.95f
            "treble" -> radius * 0.46f
            else -> radius * 0.7f
        }
        return (cx + cos(Math.toRadians(angle.toDouble())).toFloat() * r) to
            (cy + sin(Math.toRadians(angle.toDouble())).toFloat() * r)
    }

    // ------------------------------------------------------------------- touch

    override fun touch(phase: ArcadeTouch, x: Float, y: Float, width: Float, height: Float): Boolean {
        if (done()) return false
        if (!twoPlayer && turn == 1) return false
        when (phase) {
            ArcadeTouch.DOWN -> {
                aiming = true
                aimX = x
                aimY = y
                return true
            }
            ArcadeTouch.MOVE -> {
                if (!aiming) return false
                aimX = x
                aimY = y
                return true
            }
            ArcadeTouch.UP -> {
                if (!aiming) return false
                aiming = false
                val drift = radius * (0.05f + skill * 0.02f)
                val releaseX = aimX + sin(elapsed * 3.4f) * drift
                val releaseY = aimY + cos(elapsed * 2.6f) * drift
                throwDart(releaseX, releaseY, turn)
                return true
            }
        }
        return false
    }

    override fun status(): String {
        if (done()) return "Leg over"
        val left = 3 - dartsLeft
        return when {
            twoPlayer -> "Player ${turn + 1}: ${scores[turn]} · ${3 - left} dart(s) thrown"
            turn == 0 -> "You ${scores[0]} · AI ${scores[1]} · ${3 - left} dart(s) thrown"
            else -> "OEA AI to throw · AI ${scores[1]}"
        }
    }

    override fun hint(): String = when {
        darts.isEmpty() -> "Press on the board to aim, then release to throw."
        lastMessage.isNotEmpty() -> lastMessage
        else -> ""
    }

    override fun score(): Long = (501 - scores[0]).toLong().coerceAtLeast(0)

    override fun draw(canvas: Canvas, width: Float, height: Float) {
        canvas.drawColor(0xFF191423.toInt())
        val back = ArcadeDraw.paint(Color.TRANSPARENT)
        back.shader = android.graphics.RadialGradient(width * 0.5f, height * 0.42f, width * 0.8f,
            intArrayOf(0xFF3A2036.toInt(), 0xFF191423.toInt()), null, android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width, height, back)

        cx = width * 0.5f
        cy = height * 0.44f
        radius = min(width * 0.42f, height * 0.31f)

        // Board rings.
        ArcadeDraw.circle(canvas, cx, cy, radius, 0xFFE9D8C2.toInt())
        ArcadeDraw.circle(canvas, cx, cy, radius * 0.9f, 0xFF191423.toInt())
        ArcadeDraw.circle(canvas, cx, cy, radius * 0.5f, 0xFFE9D8C2.toInt())
        ArcadeDraw.circle(canvas, cx, cy, radius * 0.42f, 0xFF191423.toInt())
        ArcadeDraw.circle(canvas, cx, cy, radius * 0.095f, 0xFF2E7D5B.toInt())
        ArcadeDraw.circle(canvas, cx, cy, radius * 0.035f, 0xFFB7384B.toInt())

        val wire = ArcadeDraw.paint(0xFFB9B2A6.toInt(), 1.4f)
        for (i in 0 until 20) {
            val angle = Math.toRadians((i * 18f - 99f).toDouble())
            val x1 = cx + cos(angle).toFloat() * radius * 0.095f
            val y1 = cy + sin(angle).toFloat() * radius * 0.095f
            val x2 = cx + cos(angle).toFloat() * radius
            val y2 = cy + sin(angle).toFloat() * radius
            canvas.drawLine(x1, y1, x2, y2, wire)
        }
        // Doubles, trebles and numbers.
        for (i in 0 until 20) {
            val start = i * 18f - 99f
            val paintColor = if (i % 2 == 0) 0xFFB7384B.toInt() else 0xFF2E7D5B.toInt()
            val doubleRect = RectF(cx - radius * 0.95f, cy - radius * 0.95f, cx + radius * 0.95f, cy + radius * 0.95f)
            canvas.drawArc(doubleRect, start, 18f, false, ArcadeDraw.paint(paintColor, radius * 0.1f))
            val trebleRect = RectF(cx - radius * 0.46f, cy - radius * 0.46f, cx + radius * 0.46f, cy + radius * 0.46f)
            canvas.drawArc(trebleRect, start, 18f, false, ArcadeDraw.paint(paintColor, radius * 0.08f))
            val angle = Math.toRadians((i * 18f - 90f).toDouble())
            val nx = cx + cos(angle).toFloat() * radius * 1.1f
            val ny = cy + sin(angle).toFloat() * radius * 1.1f
            ArcadeDraw.text(canvas, order[i].toString(), nx, ny + radius * 0.045f, radius * 0.11f, Color.WHITE, bold = true, align = Paint.Align.CENTER)
        }
        ArcadeDraw.circle(canvas, cx, cy, radius, Color.TRANSPARENT, 0xFFE9D8C2.toInt(), radius * 0.018f)

        // Thrown darts.
        darts.forEach { dart ->
            val color = if (dart.player == 0) ArcadeColors.CORAL else 0xFF8FD6FF.toInt()
            val dirX = (dart.x - cx)
            val dirY = (dart.y - cy)
            val len = sqrt(dirX * dirX + dirY * dirY).coerceAtLeast(1f)
            val tailX = dart.x + dirX / len * radius * 0.13f
            val tailY = dart.y + dirY / len * radius * 0.13f
            ArcadeDraw.line(canvas, dart.x, dart.y, tailX, tailY, color, radius * 0.022f)
            ArcadeDraw.circle(canvas, dart.x, dart.y, radius * 0.026f, color)
            ArcadeDraw.text(canvas, dart.label, tailX + dirX / len * radius * 0.07f, tailY + dirY / len * radius * 0.07f,
                radius * 0.075f, color, bold = true, align = Paint.Align.CENTER)
        }

        // Aim crosshair with its live drift.
        if (aiming && !done()) {
            val driftX = sin(elapsed * 3.4f) * radius * (0.05f + skill * 0.02f)
            val driftY = cos(elapsed * 2.6f) * radius * (0.05f + skill * 0.02f)
            val px = aimX + driftX
            val py = aimY + driftY
            val cross = ArcadeDraw.paint(0xB3FFFFFF.toInt(), radius * 0.012f)
            canvas.drawLine(px - radius * 0.1f, py, px + radius * 0.1f, py, cross)
            canvas.drawLine(px, py - radius * 0.1f, px, py + radius * 0.1f, cross)
            canvas.drawCircle(px, py, radius * 0.07f, ArcadeDraw.paint(Color.TRANSPARENT, radius * 0.012f).apply { color = 0xB3FFFFFF.toInt() })
        }

        // Scoreboard.
        val panel = RectF(width * 0.05f, height * 0.06f, width * 0.95f, height * 0.115f)
        ArcadeDraw.panel(canvas, panel, 0x22FFFFFF, height * 0.026f)
        ArcadeDraw.text(canvas, "YOU", width * 0.09f, height * 0.09f, height * 0.014f, ArcadeColors.CORAL, bold = true)
        ArcadeDraw.text(canvas, scores[0].toString(), width * 0.09f, height * 0.11f, height * 0.03f, Color.WHITE, bold = true)
        ArcadeDraw.text(canvas, if (twoPlayer) "PLAYER 2" else "OEA AI", width * 0.91f, height * 0.09f, height * 0.014f,
            ArcadeColors.MINT, bold = true, align = Paint.Align.RIGHT)
        ArcadeDraw.text(canvas, scores[1].toString(), width * 0.91f, height * 0.11f, height * 0.03f, Color.WHITE, bold = true, align = Paint.Align.RIGHT)
        ArcadeDraw.text(canvas, "DARTS ${3 - dartsLeft}/3 · ${config.difficulty.label.uppercase()}", width * 0.5f, height * 0.093f,
            height * 0.013f, 0x88FFFFFF.toInt(), bold = true, align = Paint.Align.CENTER)
        val active = if (turn == 0) ArcadeColors.CORAL else ArcadeColors.MINT
        ArcadeDraw.panel(canvas, RectF(width * 0.5f - width * 0.14f, height * 0.118f, width * 0.5f + width * 0.14f, height * 0.126f),
            active, height * 0.004f)
    }
}
