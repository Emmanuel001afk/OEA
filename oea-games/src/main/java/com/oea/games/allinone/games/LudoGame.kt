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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Ludo: four tokens a side, one die, and the capture everyone remembers.
 *
 * The board is drawn as a round track of the real 52 squares instead of a
 * rectangle of tiny cells, so on a phone every square is big enough to tap. The
 * rules are the ones played on the street: a six brings a token out, a six rolls
 * again, landing on an opponent sends it home unless it stands on a star, the
 * home column needs the exact number, and all four tokens must arrive.
 *
 * The OEA AI takes a capture when it is offered, brings a token out when it has
 * one waiting and moves the token closest to home when nothing better exists.
 */
class LudoGame : ArcadeBaseGame() {

    private val starts = intArrayOf(0, 26)
    private val colors = intArrayOf(ArcadeColors.CORAL, 0xFF2E9E76.toInt())
    private val positions = Array(2) { IntArray(4) { -1 } } // -1 base, 0..51 track, 52..56 home stretch, 57 home
    private var turn = 0
    private var dice = 0
    private var sixes = 0
    private var extraRoll = false
    private var aiDelay = 0f
    private var passDelay = 0f
    private var message = ""
    private var rollRect = RectF()
    private var tokenRects = ArrayList<Triple<Int, Int, RectF>>()
    private var rolled = false
    private var finished = IntArray(2)
    private var lastDice = 0

    private val twoPlayer: Boolean get() = config.mode == ArcadePlayMode.TWO_PLAYER

    override fun onStart() {
        positions.forEach { it.fill(-1) }
        turn = 0
        dice = 0
        sixes = 0
        extraRoll = false
        aiDelay = 0f
        passDelay = 0f
        rolled = false
        finished = IntArray(2)
        lastDice = 0
        message = "Roll a six to bring a token out."
    }

    // ------------------------------------------------------------------- rules

    private fun legalMoves(player: Int): List<Int> {
        if (dice == 0) return emptyList()
        return (0..3).filter { index ->
            val position = positions[player][index]
            when {
                position == -1 -> dice == 6
                position >= 57 -> false
                else -> position + dice <= 57
            }
        }
    }

    private fun roll() {
        if (rolled) return
        lastDice = random.nextInt(1, 7)
        dice = lastDice
        rolled = true
        if (lastDice == 6) sixes++ else sixes = 0
        message = "${name(turn)} rolled $lastDice."
        val moves = legalMoves(turn)
        if (moves.isEmpty()) {
            message = "${name(turn)} rolled $dice — no move."
            passDelay = 0.9f
            return
        }
        if (lastDice != 6 && moves.size == 1) {
            // One legal move: play it, so a simple turn never needs a second tap.
            passDelay = 0.55f
        }
        if (turn == 1 && !twoPlayer) aiDelay = 0.65f
    }

    private fun move(player: Int, token: Int) {
        val from = positions[player][token]
        val target = if (from == -1) 0 else from + dice
        if (from == -1 && dice != 6) return
        if (target > 57) return
        positions[player][token] = target
        if (target < 52) {
            val absolute = (starts[player] + target) % 52
            val safe = absolute % 13 == 0
            if (!safe) {
                for (other in 0..1) {
                    if (other == player) continue
                    for (index in 0..3) {
                        val op = positions[other][index]
                        if (op in 0..51 && (starts[other] + op) % 52 == absolute) {
                            positions[other][index] = -1
                            message = "${name(player)} sent ${name(other)}'s token home!"
                        }
                    }
                }
            }
        }
        if (target == 57) {
            finished[player]++
            message = "${name(player)} brought a token home (${finished[player]}/4)."
        }
        if (finished[player] == 4) {
            settle(player)
            return
        }
        val again = lastDice == 6 && sixes < 3
        if (!message.contains("sent") && !message.contains("home")) message = "${name(player)} moved ${dice}."
        dice = 0
        rolled = false
        if (again) {
            message += " Six — roll again."
            if (turn == 1 && !twoPlayer) aiDelay = 0.6f
        } else {
            sixes = 0
            turn = 1 - turn
            if (turn == 1 && !twoPlayer) aiDelay = 0.7f
        }
    }

    private fun settle(winner: Int) {
        val won = if (twoPlayer) null else winner == 0
        finish(
            won = won,
            headline = if (twoPlayer) "Player ${winner + 1} brings them all home" else if (winner == 0) "All four home" else "The AI gets them home first",
            detail = "Tokens home: ${finished[0]} — ${finished[1]}. " +
                if (winner == 0) "A star square is safe; a six is a second turn. Use both." else "Keep one token near your home column so a capture cannot undo the race.",
            score = (finished[0] * 100 + if (winner == 0) 150 else 0).toLong(),
            stars = if (winner == 0) 3 else 1
        )
    }

    private fun name(player: Int): String =
        if (twoPlayer) "Player ${player + 1}" else if (player == 0) "You" else "The AI"

    // ---------------------------------------------------------------------- AI

    private fun aiStep() {
        if (done()) return
        if (!rolled) {
            roll()
            return
        }
        val moves = legalMoves(1)
        if (moves.isEmpty()) return
        var best = moves.first()
        var bestScore = Int.MIN_VALUE
        for (index in moves) {
            val from = positions[1][index]
            val target = if (from == -1) 0 else from + dice
            var score = target
            if (target == 57) score += 500
            if (target < 52) {
                val absolute = (starts[1] + target) % 52
                for (i in 0..3) {
                    val op = positions[0][i]
                    if (op in 0..51 && (starts[0] + op) % 52 == absolute && absolute % 13 != 0) score += 400
                }
                if (absolute % 13 == 0) score += 40
            }
            if (from == -1) score += 60
            if (score > bestScore) {
                bestScore = score
                best = index
            }
        }
        move(1, best)
    }

    // ------------------------------------------------------------------- input

    override fun touch(phase: ArcadeTouch, x: Float, y: Float, width: Float, height: Float): Boolean {
        if (done() || phase != ArcadeTouch.DOWN) return false
        if (turn == 1 && !twoPlayer) return false
        if (!rolled) {
            if (ArcadeDraw.inside(rollRect, x, y)) {
                roll()
                return true
            }
            return false
        }
        val hit = tokenRects.firstOrNull { (player, _, rect) -> player == turn && ArcadeDraw.inside(rect, x, y) }
        if (hit != null) {
            val (_, index, _) = hit
            if (legalMoves(turn).contains(index)) {
                move(turn, index)
            } else {
                message = if (positions[turn][index] == -1) "You need a six to leave the base." else "That token cannot move ${dice}."
            }
            return true
        }
        return false
    }

    override fun update(dt: Float) {
        super.update(dt)
        if (done()) return
        if (passDelay > 0f) {
            passDelay -= dt
            if (passDelay <= 0f) {
                if (dice > 0 && legalMoves(turn).size == 1 && lastDice != 6) {
                    move(turn, legalMoves(turn).first())
                } else if (dice > 0 && legalMoves(turn).isEmpty()) {
                    dice = 0
                    rolled = false
                    sixes = 0
                    turn = 1 - turn
                    if (turn == 1 && !twoPlayer) aiDelay = 0.6f
                }
            }
            return
        }
        if (aiDelay > 0f) {
            aiDelay -= dt
            if (aiDelay <= 0f) {
                if (!twoPlayer && turn == 1) {
                    if (!rolled) roll() else aiStep()
                }
            }
        }
        if (twoPlayer && rolled && legalMoves(turn).size == 1 && lastDice != 6 && passDelay <= 0f) {
            passDelay = 0.5f
        }
    }

    override fun status(): String {
        if (done()) return "Race over"
        return if (twoPlayer) "Player ${turn + 1} · ${if (rolled) "tap a token" else "roll the die"} · home ${finished[0]}—${finished[1]}"
        else if (turn == 0) "Your turn · ${if (rolled) "dice $dice — tap a token" else "roll"} · home ${finished[0]}—${finished[1]}"
        else "OEA AI turn · home ${finished[0]}—${finished[1]}"
    }

    override fun hint(): String = message

    override fun score(): Long = finished[0] * 100L

    // -------------------------------------------------------------------- draw

    override fun draw(canvas: Canvas, width: Float, height: Float) {
        canvas.drawColor(0xFFF6ECDF.toInt())
        val cx = width * 0.5f
        val cy = height * 0.47f
        val ring = min(width * 0.4f, height * 0.29f)
        val cell = 2f * PI.toFloat() * ring / 52f * 0.78f

        // Ring squares.
        for (i in 0..51) {
            val angle = Math.toRadians((-90 + i * (360f / 52f)).toDouble())
            val x = cx + cos(angle).toFloat() * ring
            val y = cy + sin(angle).toFloat() * ring
            val quadrant = when {
                i < 13 -> 0
                i < 26 -> 1
                i < 39 -> 2
                else -> 3
            }
            val base = when (quadrant) {
                0 -> 0xFFFFE0E3.toInt()
                1 -> 0xFFDFF6EA.toInt()
                2 -> 0xFFFFF0D4.toInt()
                else -> 0xFFE3ECFF.toInt()
            }
            val safe = i % 13 == 0
            ArcadeDraw.panel(canvas, x - cell / 2f, y - cell / 2f, x + cell / 2f, y + cell / 2f,
                if (safe) 0xFFFFF7E8.toInt() else base, cell * 0.24f, 0x33000000, 1.3f)
            if (safe) ArcadeDraw.text(canvas, "★", x, y + cell * 0.16f, cell * 0.5f, 0xFFB08A2E.toInt(), bold = true, align = Paint.Align.CENTER)
        }

        // Home columns for the two players.
        for (player in 0..1) {
            val angle = Math.toRadians((-90 + starts[player] * (360f / 52f)).toDouble())
            for (step in 1..5) {
                val r = ring - (ring * 0.16f) * step
                val x = cx + cos(angle).toFloat() * r
                val y = cy + sin(angle).toFloat() * r
                ArcadeDraw.circle(canvas, x, y, cell * 0.38f, colors[player].let { Color.argb(70, Color.red(it), Color.green(it), Color.blue(it)) })
                ArcadeDraw.circle(canvas, x, y, cell * 0.38f, Color.TRANSPARENT, colors[player], cell * 0.07f)
            }
        }
        // Centre home.
        ArcadeDraw.circle(canvas, cx, cy, ring * 0.2f, 0xFFFBF4E8.toInt())
        ArcadeDraw.circle(canvas, cx, cy, ring * 0.2f, Color.TRANSPARENT, 0xFFD8C6AC.toInt(), 2f)
        for (player in 0..1) {
            val path = Path()
            val angle = Math.toRadians((-90 + starts[player] * (360f / 52f)).toDouble())
            val tip = ring * 0.19f
            path.moveTo(cx, cy)
            path.lineTo(cx + cos(angle - 0.35).toFloat() * tip, cy + sin(angle - 0.35).toFloat() * tip)
            path.lineTo(cx + cos(angle + 0.35).toFloat() * tip, cy + sin(angle + 0.35).toFloat() * tip)
            path.close()
            canvas.drawPath(path, ArcadeDraw.paint(colors[player]))
        }

        // Bases with waiting tokens.
        tokenRects.clear()
        for (player in 0..1) {
            val baseX = if (player == 0) width * 0.12f else width * 0.88f
            val baseY = if (player == 0) height * 0.12f else height * 0.82f
            val baseR = width * 0.12f
            ArcadeDraw.panel(canvas, baseX - baseR, baseY - baseR, baseX + baseR, baseY + baseR,
                Color.argb(40, Color.red(colors[player]), Color.green(colors[player]), Color.blue(colors[player])),
                baseR * 0.35f, colors[player], 2f)
            var slot = 0
            for (index in 0..3) {
                val position = positions[player][index]
                if (position != -1) continue
                val ox = (slot % 2 - 0.5f) * baseR
                val oy = (slot / 2 - 0.5f) * baseR
                val rect = RectF(baseX + ox - baseR * 0.22f, baseY + oy - baseR * 0.22f,
                    baseX + ox + baseR * 0.22f, baseY + oy + baseR * 0.22f)
                tokenRects += Triple(player, index, rect)
                drawToken(canvas, rect, player, index)
                slot++
            }
        }

        // Tokens on the track and home stretch.
        for (player in 0..1) for (index in 0..3) {
            val position = positions[player][index]
            if (position < 0) continue
            val (x, y) = pointFor(player, position, cx, cy, ring)
            val rect = RectF(x - cell * 0.4f, y - cell * 0.4f, x + cell * 0.4f, y + cell * 0.4f)
            tokenRects += Triple(player, index, rect)
            drawToken(canvas, rect, player, index)
        }

        // Die / roll button.
        rollRect.set(width * 0.5f - width * 0.16f, height * 0.865f, width * 0.5f + width * 0.16f, height * 0.935f)
        ArcadeDraw.panel(canvas, rollRect, if (rolled) 0xFFE9DCC9.toInt() else ArcadeColors.INK, rollRect.height() * 0.5f)
        if (rolled) {
            ArcadeDraw.die(canvas, width * 0.5f - rollRect.height() * 0.32f, rollRect.centerY() - rollRect.height() * 0.32f,
                rollRect.height() * 0.64f, dice, face = ArcadeColors.WHITE)
            ArcadeDraw.text(canvas, "TAP A TOKEN", width * 0.5f + width * 0.1f, rollRect.centerY() + height * 0.008f,
                height * 0.014f, ArcadeColors.INK, bold = true, align = Paint.Align.CENTER)
        } else {
            ArcadeDraw.text(canvas, "ROLL", rollRect.centerX(), rollRect.centerY() + height * 0.008f, height * 0.022f,
                ArcadeColors.WHITE, bold = true, align = Paint.Align.CENTER)
        }
        ArcadeDraw.text(canvas, "YOU ${'●'} ${positions[0].count { it == 57 }}/4", width * 0.06f, height * 0.97f, height * 0.014f,
            colors[0], bold = true)
        ArcadeDraw.text(canvas, "${if (twoPlayer) "P2" else "AI"} ${positions[1].count { it == 57 }}/4", width * 0.94f, height * 0.97f,
            height * 0.014f, colors[1], bold = true, align = Paint.Align.RIGHT)
    }

    private fun pointFor(player: Int, position: Int, cx: Float, cy: Float, ring: Float): Pair<Float, Float> {
        return if (position < 52) {
            val absolute = (starts[player] + position) % 52
            val angle = Math.toRadians((-90 + absolute * (360f / 52f)).toDouble())
            (cx + cos(angle).toFloat() * ring) to (cy + sin(angle).toFloat() * ring)
        } else if (position < 57) {
            val angle = Math.toRadians((-90 + starts[player] * (360f / 52f)).toDouble())
            val r = ring - ring * 0.16f * (position - 51).coerceIn(1, 5)
            (cx + cos(angle).toFloat() * r) to (cy + sin(angle).toFloat() * r)
        } else {
            val angle = Math.toRadians((-90 + starts[player] * (360f / 52f) + 180f).toDouble())
            (cx + cos(angle).toFloat() * ring * 0.06f) to (cy + sin(angle).toFloat() * ring * 0.06f)
        }
    }

    private fun drawToken(canvas: Canvas, rect: RectF, player: Int, index: Int) {
        ArcadeDraw.circle(canvas, rect.centerX() + rect.width() * 0.06f, rect.centerY() + rect.height() * 0.08f, rect.width() * 0.52f, 0x33000000)
        ArcadeDraw.circle(canvas, rect.centerX(), rect.centerY(), rect.width() * 0.5f, colors[player])
        ArcadeDraw.circle(canvas, rect.centerX(), rect.centerY(), rect.width() * 0.5f, Color.TRANSPARENT, 0x99FFFFFF.toInt(), 1.5f)
        ArcadeDraw.text(canvas, (index + 1).toString(), rect.centerX(), rect.centerY() + rect.height() * 0.16f, rect.width() * 0.45f,
            ArcadeColors.WHITE, bold = true, align = Paint.Align.CENTER)
    }
}
