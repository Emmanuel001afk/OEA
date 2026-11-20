package com.oea.games.allinone.games

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import com.oea.games.allinone.ArcadeBaseGame
import com.oea.games.allinone.ArcadeColors
import com.oea.games.allinone.ArcadeDraw
import com.oea.games.allinone.ArcadePlayMode
import com.oea.games.allinone.ArcadeTouch
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Ludo, on the board everyone recognises.
 *
 * This is the real thing rather than the stylised ring the first version drew:
 * a 15×15 board with four corner yards, a 52-square cross-shaped track, four
 * private home columns and the four-triangle centre. Four tokens a side, six to
 * leave the yard, a six for a bonus roll, three sixes losing the turn, captures
 * back to the yard, eight safe squares (four starts plus four star squares) and
 * an exact roll to finish.
 *
 * Positions are measured from a seat's own start square:
 *   -1        in the yard
 *   0..50     the 51 shared squares the token must travel
 *   51..55    its five private home-column squares
 *   56        home, in its triangle in the centre
 *
 * Modes:
 *  - vs AI: you (red, bottom-left) against three computer seats; level sets how
 *    well the AI picks its move.
 *  - two players: red against green, opposite corners, pass the phone.
 *
 * The old circular board was the reason Ludo read as "stupid": it made a
 * familiar game unrecognisable and could not show yards, home columns or safe
 * squares. The rules and layout here follow the standard board; sources and the
 * rest of the audit live in docs/game-audit-2026-11-18.md.
 */
class LudoGame : ArcadeBaseGame() {

    private data class Seat(val name: String, val short: String, val color: Int, val start: Int,
                            val yardCol: Int, val yardRow: Int)

    private val seats = listOf(
        Seat("Red", "R", 0xFFE3566E.toInt(), 0, 0, 9),
        Seat("Yellow", "Y", 0xFFF2B93B.toInt(), 13, 0, 0),
        Seat("Green", "G", 0xFF2E9E76.toInt(), 26, 9, 0),
        Seat("Blue", "B", 0xFF3E8EE0.toInt(), 39, 9, 9),
    )

    /**
     * The 52 shared squares, clockwise as (column, row) on the 15×15 board.
     * Index 0 is red's start (6,13); the other starts are index 13 = (1,6),
     * 26 = (8,1) and 39 = (13,8). The four squares at the outer middle of each
     * arm — (7,14), (0,7), (7,0), (14,7) — are the entrances to the home
     * columns, so a token turns off the loop after 50 steps.
     */
    private val track: List<Pair<Int, Int>> = buildList {
        addAll(listOf(6 to 13, 6 to 12, 6 to 11, 6 to 10, 6 to 9))
        addAll(listOf(5 to 8, 4 to 8, 3 to 8, 2 to 8, 1 to 8, 0 to 8))
        add(0 to 7)
        addAll(listOf(0 to 6, 1 to 6, 2 to 6, 3 to 6, 4 to 6, 5 to 6))
        addAll(listOf(6 to 5, 6 to 4, 6 to 3, 6 to 2, 6 to 1, 6 to 0))
        add(7 to 0)
        addAll(listOf(8 to 0, 8 to 1, 8 to 2, 8 to 3, 8 to 4, 8 to 5))
        addAll(listOf(9 to 6, 10 to 6, 11 to 6, 12 to 6, 13 to 6, 14 to 6))
        add(14 to 7)
        addAll(listOf(14 to 8, 13 to 8, 12 to 8, 11 to 8, 10 to 8, 9 to 8))
        addAll(listOf(8 to 9, 8 to 10, 8 to 11, 8 to 12, 8 to 13, 8 to 14))
        add(7 to 14)
        add(6 to 14)
    }

    /** The four starts plus the four star squares marked on a standard board. */
    private val safeSquares = setOf(0, 13, 26, 39, 8, 21, 34, 47)

    private val activeSeats: List<Int>
        get() = if (config.mode == ArcadePlayMode.TWO_PLAYER) listOf(0, 2) else listOf(0, 1, 2, 3)

    private var positions = Array(4) { IntArray(4) { -1 } }
    private var captures = IntArray(4)
    private var turn = 0
    private var dice = 0
    private var shownDice = 6
    private var rolled = false
    private var rolling = 0f
    private var sixes = 0
    private var aiThink = 0f
    private var passDelay = 0f
    private var message = ""

    private var boardLeft = 0f
    private var boardTop = 0f
    private var cell = 1f
    private var rollRect = RectF()
    private var tokenRects = ArrayList<Triple<Int, Int, RectF>>()
    private var legal = ArrayList<Int>()

    private val twoPlayer: Boolean get() = config.mode == ArcadePlayMode.TWO_PLAYER

    /** Both seats are people in pass-and-play; otherwise seats 1–3 are the AI. */
    private fun isAi(seat: Int): Boolean = !twoPlayer && seat != 0

    override fun onStart() {
        positions = Array(4) { IntArray(4) { -1 } }
        captures = IntArray(4)
        turn = 0
        dice = 0
        shownDice = 6
        rolled = false
        rolling = 0f
        sixes = 0
        aiThink = 0f
        passDelay = 0f
        legal = ArrayList()
        message = "Roll a six to bring a token out of the yard."
    }

    // -------------------------------------------------------------------- rules

    private fun nameOf(seat: Int): String = when {
        twoPlayer && seat == 0 -> "Player 1"
        twoPlayer -> "Player 2"
        seat == 0 -> "You"
        else -> "${seats[seat].name} AI"
    }

    private fun nextSeat(from: Int): Int {
        var seat = from
        do {
            seat = (seat + 1) % 4
        } while (seat !in activeSeats)
        return seat
    }

    /** Shared-track index for a seat's position 0..50. */
    private fun absolute(seat: Int, position: Int): Int = (seats[seat].start + position) % track.size

    /** The five coloured squares between a seat's last track square and the centre. */
    private fun homeCell(seat: Int, step: Int): Pair<Int, Int> = when (seat) {
        0 -> 7 to (13 - step)
        1 -> (1 + step) to 7
        2 -> 7 to (1 + step)
        else -> (13 - step) to 7
    }

    private fun home(seat: Int, token: Int): Boolean = positions[seat][token] == 56

    private fun homeCount(seat: Int): Int = (0..3).count { home(seat, it) }

    /**
     * A token on a star square or a coloured start square cannot be captured.
     * A square holding two of your own tokens is treated as a block: an
     * opponent cannot capture either of them there.
     */
    private fun isSafeDestination(seat: Int, target: Int): Boolean {
        if (target > 50) return true
        val index = absolute(seat, target)
        if (index in safeSquares) return true
        var sameSeat = 0
        for (token in 0..3) {
            val other = positions[seat][token]
            if (other in 0..50 && absolute(seat, other) == index) sameSeat++
        }
        return sameSeat >= 2
    }

    private fun legalMoves(seat: Int): List<Int> {
        if (!rolled || rolling > 0f || done()) return emptyList()
        val moves = ArrayList<Int>()
        for (token in 0..3) {
            val position = positions[seat][token]
            when {
                position == 56 -> Unit
                position == -1 -> if (dice == 6) moves += token
                position + 1 <= 56 -> moves += token
            }
        }
        return moves
    }

    // -------------------------------------------------------------------- input

    override fun update(dt: Float) {
        super.update(dt)
        if (done()) return
        if (rolling > 0f) {
            rolling -= dt
            shownDice = 1 + (elapsed * 18f).toInt() % 6
            if (rolling <= 0f) {
                shownDice = dice
                legal = ArrayList(legalMoves(turn))
                if (legal.isEmpty()) {
                    message = "${nameOf(turn)} rolled $dice — no legal move."
                    passDelay = 0.9f
                } else if (isAi(turn)) {
                    aiThink = 0.55f
                } else {
                    message = "${nameOf(turn)} rolled $dice. Tap a highlighted token."
                }
            }
            return
        }
        if (passDelay > 0f) {
            passDelay -= dt
            if (passDelay <= 0f) passTurn()
            return
        }
        if (isAi(turn) && aiThink > 0f) {
            aiThink -= dt
            if (aiThink <= 0f) {
                if (!rolled) roll() else chooseAiMove()
            }
        }
    }

    override fun touch(phase: ArcadeTouch, x: Float, y: Float, width: Float, height: Float): Boolean {
        if (done() || phase != ArcadeTouch.DOWN) return false
        if (isAi(turn) || passDelay > 0f) return false
        if (!rolled && rolling <= 0f && ArcadeDraw.inside(rollRect, x, y)) {
            roll()
            return true
        }
        if (rolled && rolling <= 0f) {
            val hit = tokenRects.firstOrNull { (seat, token, rect) ->
                seat == turn && ArcadeDraw.inside(rect, x, y) && legal.contains(token)
            }
            if (hit != null) {
                move(turn, hit.second)
                return true
            }
        }
        return false
    }

    private fun roll() {
        if (rolled || rolling > 0f || done() || passDelay > 0f) return
        dice = random.nextInt(6) + 1
        shownDice = dice
        rolled = true
        if (dice == 6) sixes++ else sixes = 0
        if (sixes == 3) {
            message = "${nameOf(turn)} rolled three sixes — turn lost."
            sixes = 0
            rolled = false
            dice = 0
            passDelay = 1.0f
            return
        }
        rolling = 0.42f
        legal = ArrayList()
    }

    private fun move(seat: Int, token: Int) {
        if (dice == 0 || done()) return
        val position = positions[seat][token]
        if (position == 56) return
        val target = if (position == -1) 0 else position + 1
        if (target > 56) return
        var announcement = ""

        if (target == 56) {
            positions[seat][token] = 56
            announcement = "${nameOf(seat)} brought a token home."
        } else if (target <= 50) {
            val destination = absolute(seat, target)
            positions[seat][token] = target
            if (!isSafeDestination(seat, target)) {
                for (other in activeSeats) {
                    if (other == seat) continue
                    for (otherToken in 0..3) {
                        val otherPosition = positions[other][otherToken]
                        if (otherPosition in 0..50 && absolute(other, otherPosition) == destination) {
                            positions[other][otherToken] = -1
                            captures[seat]++
                            announcement = "${nameOf(seat)} captured ${nameOf(other)}!"
                        }
                    }
                }
            }
        } else {
            positions[seat][token] = target
        }

        if (homeCount(seat) == 4) {
            winner(seat)
            return
        }

        val rolledSix = dice == 6
        rolled = false
        legal = ArrayList()
        if (rolledSix) {
            message = listOfNotNull(announcement.takeIf { it.isNotEmpty() }, "Six — roll again.").joinToString(" ")
            if (isAi(turn)) aiThink = 0.7f
            dice = 0
        } else {
            sixes = 0
            dice = 0
            turn = nextSeat(turn)
            message = listOfNotNull(announcement.takeIf { it.isNotEmpty() }, "${nameOf(turn)}'s turn.").joinToString(" ")
            if (isAi(turn)) aiThink = 0.9f
        }
    }

    private fun passTurn() {
        rolled = false
        dice = 0
        legal = ArrayList()
        sixes = 0
        turn = nextSeat(turn)
        message = "${nameOf(turn)}'s turn."
        if (isAi(turn)) aiThink = 0.9f
    }

    private fun winner(seat: Int) {
        finish(
            won = seat == 0,
            headline = if (twoPlayer) "${nameOf(seat)} wins" else if (seat == 0) "You win" else "${nameOf(seat)} wins",
            detail = "Four tokens home" +
                (if (captures[seat] > 0) " with ${captures[seat]} capture${if (captures[seat] == 1) "" else "s"}" else "") +
                ". All four of your tokens must reach the centre; a token needs an exact roll to get in.",
            score = 320L + captures[seat] * 25L + config.level * 20L,
            stars = if (seat == 0) 3 else 1
        )
    }

    // ----------------------------------------------------------------------- ai

    private fun chooseAiMove() {
        val moves = legalMoves(turn)
        if (moves.isEmpty()) {
            passDelay = 0.5f
            return
        }
        var best = moves.first()
        var bestScore = -Float.MAX_VALUE
        for (token in moves) {
            val position = positions[turn][token]
            val target = if (position == -1) 0 else position + 1
            var value = target * 0.5f
            if (target == 56) value += 1000f
            if (position == -1) value += 140f
            if (target <= 50) {
                val destination = absolute(turn, target)
                if (destination in safeSquares) value += 70f
                for (other in activeSeats) {
                    if (other == turn) continue
                    for (otherToken in 0..3) {
                        val otherPosition = positions[other][otherToken]
                        if (otherPosition in 0..50 && absolute(other, otherPosition) == destination) {
                            value += if (isSafeDestination(turn, target)) 30f else 520f
                        }
                    }
                }
            }
            // A little noise at low skill, so Easy really does wander.
            value += rand() * (1.1f - skill) * 70f
            if (value > bestScore) {
                bestScore = value
                best = token
            }
        }
        move(turn, best)
    }

    // -------------------------------------------------------------------- status

    override fun status(): String {
        if (done()) return "Game over"
        val whose = if (isAi(turn)) "${seats[turn].name} AI" else nameOf(turn)
        val diceText = when {
            rolling > 0f -> "rolling…"
            dice == 0 -> "ready"
            else -> "rolled $dice"
        }
        val homes = activeSeats.joinToString(" · ") { "${seats[it].short} ${homeCount(it)}/4" }
        return "$whose · $diceText · $homes"
    }

    override fun hint(): String = when {
        done() -> ""
        isAi(turn) -> "The AI is thinking."
        !rolled -> "Tap ROLL. You need a six to leave the yard."
        legal.isEmpty() -> "No legal move — the turn passes."
        else -> "Tap one of the highlighted tokens to move it $dice."
    }

    override fun score(): Long = captures[0] * 25L + homeCount(0) * 60L

    // --------------------------------------------------------------------- draw

    override fun draw(canvas: Canvas, width: Float, height: Float) {
        canvas.drawColor(0xFF1C2233.toInt())

        val boardSize = min(width * 0.94f, height * 0.6f)
        cell = boardSize / 15f
        boardLeft = (width - boardSize) / 2f
        boardTop = height * 0.05f

        // Board plate.
        val plate = RectF(boardLeft - cell * 0.18f, boardTop - cell * 0.18f,
            boardLeft + boardSize + cell * 0.18f, boardTop + boardSize + cell * 0.18f)
        ArcadeDraw.shadow(canvas, plate, cell * 0.6f, 0.35f)
        ArcadeDraw.panel(canvas, plate, 0xFFFBF4E6.toInt(), cell * 0.5f, 0x22000000, 1.5f)

        drawYards(canvas)
        drawTrack(canvas)
        drawCentre(canvas)
        drawTokens(canvas)
        drawControls(canvas, width, height, boardSize)
    }

    private fun cellRect(column: Int, row: Int, inset: Float = 0f): RectF = RectF(
        boardLeft + column * cell + inset,
        boardTop + row * cell + inset,
        boardLeft + (column + 1) * cell - inset,
        boardTop + (row + 1) * cell - inset
    )

    private fun drawYards(canvas: Canvas) {
        for (seat in seats.indices) {
            val info = seats[seat]
            val rect = cellRect(info.yardCol, info.yardRow).let {
                RectF(it.left, it.top, it.right + cell * 5f, it.bottom + cell * 5f)
            }
            ArcadeDraw.panel(canvas, rect, info.color, cell * 0.55f)
            val inset = cell * 0.75f
            val inner = RectF(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset)
            ArcadeDraw.panel(canvas, inner, 0xFFFDF8EE.toInt(), cell * 0.45f)
            for (socket in 0..3) {
                val point = yardPoint(seat, socket)
                ArcadeDraw.circle(canvas, point.x, point.y, cell * 0.36f, 0x14000000)
                ArcadeDraw.circle(canvas, point.x, point.y, cell * 0.31f, info.color, 0xFFFDF8EE.toInt(), cell * 0.05f)
            }
        }
    }

    private fun drawTrack(canvas: Canvas) {
        for ((index, point) in track.withIndex()) {
            val rect = cellRect(point.first, point.second, cell * 0.075f)
            val owner = seats.firstOrNull { it.start == index }
            val color = when {
                owner != null -> owner.color
                index in safeSquares -> 0xFFFFE7AE.toInt()
                else -> 0xFFFFFDF8.toInt()
            }
            ArcadeDraw.panel(canvas, rect, color, cell * 0.16f, 0xFFDCCEB6.toInt(), cell * 0.035f)
            if (owner != null) {
                ArcadeDraw.circle(canvas, rect.centerX(), rect.centerY(), cell * 0.16f, 0x33FFFFFF)
            } else if (index in safeSquares) {
                drawStar(canvas, rect.centerX(), rect.centerY(), cell * 0.2f, 0xFFC79A2E.toInt())
            }
        }

        // Home columns.
        for (seat in seats.indices) {
            for (step in 0..4) {
                val spot = homeCell(seat, step)
                val rect = cellRect(spot.first, spot.second, cell * 0.075f)
                ArcadeDraw.panel(canvas, rect, seats[seat].color, cell * 0.16f, 0x33FFFFFF, cell * 0.03f)
            }
        }

        // Small arrows on the four home entrances, so the turn-off is obvious.
        for (seat in activeSeats) {
            val entry = when (seat) {
                0 -> 7 to 14
                1 -> 0 to 7
                2 -> 7 to 0
                else -> 14 to 7
            }
            val rect = cellRect(entry.first, entry.second)
            ArcadeDraw.text(canvas, "▲", rect.centerX(), rect.centerY() + cell * 0.12f, cell * 0.34f,
                seats[seat].color, bold = true, align = Paint.Align.CENTER)
        }
    }

    private fun drawCentre(canvas: Canvas) {
        val left = boardLeft + 6 * cell
        val top = boardTop + 6 * cell
        val right = left + 3 * cell
        val bottom = top + 3 * cell
        val mid = PointF((left + right) / 2f, (top + bottom) / 2f)
        ArcadeDraw.panel(canvas, RectF(left, top, right, bottom), 0xFFFBF4E6.toInt(), cell * 0.2f,
            0xFFDCCEB6.toInt(), cell * 0.05f)
        val tl = PointF(left, top)
        val tr = PointF(right, top)
        val bl = PointF(left, bottom)
        val br = PointF(right, bottom)
        val triangles = listOf(
            0 to listOf(bl, br, mid),   // red, bottom arm
            1 to listOf(tl, bl, mid),   // yellow, left arm
            2 to listOf(tl, tr, mid),   // green, top arm
            3 to listOf(tr, br, mid)    // blue, right arm
        )
        for ((seat, points) in triangles) {
            val path = Path()
            path.moveTo(points[0].x, points[0].y)
            path.lineTo(points[1].x, points[1].y)
            path.lineTo(points[2].x, points[2].y)
            path.close()
            canvas.drawPath(path, ArcadeDraw.paint(seats[seat].color))
            canvas.drawPath(path, ArcadeDraw.paint(0xCCFFFDF8.toInt(), cell * 0.025f))
        }
        ArcadeDraw.circle(canvas, mid.x, mid.y, cell * 0.16f, 0xFFFFFDF8.toInt(), 0x55221100, cell * 0.02f)
    }

    private fun drawTokens(canvas: Canvas) {
        tokenRects.clear()
        val pulse = (0.5f + 0.5f * sin(elapsed * 4.2f))
        for (seat in activeSeats) {
            for (token in 0..3) {
                val point = tokenPoint(seat, token)
                val radius = cell * 0.3f
                val rect = RectF(point.x - radius, point.y - radius, point.x + radius, point.y + radius)
                if (seat == turn && !isAi(turn) && rolled && rolling <= 0f && legal.contains(token)) {
                    ArcadeDraw.circle(canvas, point.x, point.y, radius + cell * (0.1f + pulse * 0.08f),
                        0x33FFD34D, ArcadeColors.GOLD, cell * 0.05f)
                }
                ArcadeDraw.circle(canvas, point.x + cell * 0.03f, point.y + cell * 0.06f, radius,
                    0x33000000)
                ArcadeDraw.circle(canvas, point.x, point.y, radius, seats[seat].color, 0xFFFFFFFF.toInt(), cell * 0.055f)
                ArcadeDraw.circle(canvas, point.x - radius * 0.3f, point.y - radius * 0.34f, radius * 0.22f, 0x66FFFFFF)
                if (legal.contains(token) && seat == turn) tokenRects += Triple(seat, token, rect)
            }
        }
    }

    private fun tokenPoint(seat: Int, token: Int): PointF {
        val position = positions[seat][token]
        return when {
            position == -1 -> yardPoint(seat, token)
            position == 56 -> finishedPoint(seat, token)
            position <= 50 -> {
                val index = absolute(seat, position)
                val spot = track[index]
                val rect = cellRect(spot.first, spot.second)
                val occupants = occupancy(index)
                spread(rect.centerX(), rect.centerY(), occupants.indexOf(seat to token), occupants.size)
            }
            else -> {
                val spot = homeCell(seat, position - 51)
                val rect = cellRect(spot.first, spot.second)
                val occupants = homeOccupancy(seat, position)
                spread(rect.centerX(), rect.centerY(), occupants.indexOf(token), occupants.size)
            }
        }
    }

    private fun spread(cx: Float, cy: Float, slot: Int, total: Int): PointF {
        if (total <= 1 || slot < 0) return PointF(cx, cy)
        val angle = (Math.PI * 2.0 * slot / total + 0.5).toFloat()
        return PointF(cx + cos(angle) * cell * 0.14f, cy + sin(angle) * cell * 0.14f)
    }

    private fun occupancy(index: Int): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        for (seat in activeSeats) for (token in 0..3) {
            val position = positions[seat][token]
            if (position in 0..50 && absolute(seat, position) == index) out += seat to token
        }
        return out
    }

    private fun homeOccupancy(seat: Int, position: Int): List<Int> =
        (0..3).filter { positions[seat][it] == position }

    private fun yardPoint(seat: Int, token: Int): PointF {
        val info = seats[seat]
        val col = info.yardCol + if (token % 2 == 0) 1.6f else 4.4f
        val row = info.yardRow + if (token < 2) 1.6f else 4.4f
        return PointF(boardLeft + col * cell, boardTop + row * cell)
    }

    private fun finishedPoint(seat: Int, token: Int): PointF {
        val centreX = boardLeft + 7.5f * cell
        val centreY = boardTop + 7.5f * cell
        val angle = when (seat) {
            0 -> Math.PI / 2
            1 -> Math.PI
            2 -> -Math.PI / 2
            else -> 0.0
        }
        val spread = (token - 1.5f) * cell * 0.26f
        val radius = cell * 0.72f
        return PointF(
            centreX + (cos(angle) * radius).toFloat() + cos(angle + Math.PI / 2).toFloat() * spread,
            centreY + (sin(angle) * radius).toFloat() + sin(angle + Math.PI / 2).toFloat() * spread
        )
    }

    private fun drawControls(canvas: Canvas, width: Float, height: Float, boardSize: Float) {
        val top = boardTop + boardSize + height * 0.035f
        val diceSize = min(width * 0.15f, height * 0.085f)
        val diceLeft = width * 0.08f
        ArcadeDraw.die(canvas, diceLeft, top, diceSize, shownDice, 0xFFFFFDF8.toInt(), ArcadeColors.INK)
        ArcadeDraw.text(canvas, when {
            rolling > 0f -> "ROLLING"
            dice == 0 -> "TAP TO ROLL"
            else -> "ROLLED $dice"
        }, diceLeft + diceSize / 2f, top + diceSize + height * 0.022f, height * 0.013f,
            0xAAFFFFFF.toInt(), bold = true, align = Paint.Align.CENTER)

        val buttonLeft = diceLeft + diceSize + width * 0.07f
        rollRect = RectF(buttonLeft, top, width * 0.92f, top + diceSize)
        val canRoll = !rolled && rolling <= 0f && !isAi(turn) && !done()
        val seatColor = if (isAi(turn)) 0x33FFFFFF else seats[turn].color
        ArcadeDraw.panel(canvas, rollRect,
            if (canRoll) seatColor else 0x22FFFFFF,
            rollRect.height() / 2f,
            if (canRoll) 0x66FFFFFF else 0x11FFFFFF, 1.5f)
        val label = when {
            done() -> "GAME OVER"
            isAi(turn) -> "${seats[turn].name.uppercase()} AI…"
            rolled -> "MOVING"
            else -> "${nameOf(turn).uppercase()} · ROLL"
        }
        ArcadeDraw.text(canvas, label, rollRect.centerX(), rollRect.centerY() + rollRect.height() * 0.17f,
            ArcadeDraw.fitted(label, rollRect.height() * 0.34f, rollRect.width() * 0.9f, bold = true),
            if (canRoll) 0xFF1B1524.toInt() else 0x99FFFFFF.toInt(), bold = true, align = Paint.Align.CENTER)

        // Progress chips, one per seat: dots for tokens home.
        val chipY = top + diceSize + height * 0.045f
        val chipWidth = (width * 0.86f) / activeSeats.size
        activeSeats.forEachIndexed { index, seat ->
            val left = width * 0.07f + index * chipWidth
            val rect = RectF(left, chipY, left + chipWidth - width * 0.02f, chipY + height * 0.045f)
            val active = seat == turn && !done()
            ArcadeDraw.panel(canvas, rect, if (active) seats[seat].color else 0x22FFFFFF, rect.height() / 2f,
                if (active) 0x66FFFFFF else 0x11FFFFFF, 1f)
            val label = if (twoPlayer) (if (seat == 0) "P1" else "P2") else seats[seat].short
            ArcadeDraw.text(canvas, "$label ${homeCount(seat)}/4", rect.centerX(), rect.centerY() + rect.height() * 0.18f,
                rect.height() * 0.42f, if (active) 0xFF1B1524.toInt() else 0xCCFFFFFF.toInt(),
                bold = true, align = Paint.Align.CENTER)
        }

        // Message line.
        if (message.isNotEmpty()) {
            ArcadeDraw.text(canvas, message, width / 2f, height * 0.975f, height * 0.0135f,
                0xCCFFFFFF.toInt(), align = Paint.Align.CENTER)
        }
    }

    private fun drawStar(canvas: Canvas, cx: Float, cy: Float, radius: Float, color: Int) {
        val path = Path()
        for (i in 0 until 10) {
            val r = if (i % 2 == 0) radius else radius * 0.44f
            val angle = -Math.PI / 2 + i * Math.PI / 5
            val x = cx + (cos(angle) * r).toFloat()
            val y = cy + (sin(angle) * r).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        canvas.drawPath(path, ArcadeDraw.paint(color))
    }
}
