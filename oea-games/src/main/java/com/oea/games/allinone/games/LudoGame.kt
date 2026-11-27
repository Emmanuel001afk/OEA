package com.oea.games.allinone.games

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.Shader
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
 * Two dice are rolled each turn and the total is the number of squares one
 * token moves. A six on either die brings a token out of the yard (the token
 * takes the whole roll to leave); doubles earn another roll, and three doubles in
 * a row lose the turn. A token needs an exact total to enter the centre.
 *
 * Positions are measured from a seat's own start square:
 *   -1        in the yard
 *   0..50     the 51 shared squares the token must travel
 *   51..55    its five private home-column squares
 *   56        home, in its triangle in the centre
 *
 * A token on 55 (the last home-column square) steps into the centre with any
 * total: the dice cannot make the one-square move on their own.
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
    private var dieOne = 0
    private var dieTwo = 0
    private var shownOne = 6
    private var shownTwo = 6
    private var doubles = false
    private var doubleCount = 0
    private var rolled = false
    private var rolling = 0f
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

    // Hop animation state for moving tokens square-by-square.
    private var animSeat = -1
    private var animToken = -1
    private var animFrom = -1
    private var animTo = -1
    private var animTime = 0f
    private var animDuration = 0f
    private var animExtraRoll = false
    private var animDistance = 0

    override fun onStart() {
        positions = Array(4) { IntArray(4) { -1 } }
        captures = IntArray(4)
        turn = 0
        dieOne = 0
        dieTwo = 0
        shownOne = 6
        shownTwo = 6
        doubles = false
        doubleCount = 0
        rolled = false
        rolling = 0f
        aiThink = 0f
        passDelay = 0f
        animSeat = -1
        animToken = -1
        animFrom = -1
        animTo = -1
        animTime = 0f
        animDuration = 0f
        legal = ArrayList()
        message = "Roll two dice — the total is your move. A six on either die brings a token out."
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

    private val dice: Int get() = dieOne + dieTwo

    private fun legalMoves(seat: Int): List<Int> {
        if (!rolled || rolling > 0f || done()) return emptyList()
        val moves = ArrayList<Int>()
        val canEnter = dieOne == 6 || dieTwo == 6
        for (token in 0..3) {
            val position = positions[seat][token]
            when {
                position == 56 -> Unit
                position == -1 -> if (canEnter) moves += token
                position == 55 -> moves += token
                position + dice <= 56 -> moves += token
            }
        }
        return moves
    }

    // -------------------------------------------------------------------- input

    override fun update(dt: Float) {
        super.update(dt)
        if (done()) return
        if (animSeat >= 0) {
            animTime += dt
            if (animTime >= animDuration) {
                commitMove()
            }
            return
        }
        if (rolling > 0f) {
            rolling -= dt
            shownOne = 1 + (elapsed * 17f).toInt() % 6
            shownTwo = 1 + (elapsed * 11f + 2f).toInt() % 6
            if (rolling <= 0f) {
                shownOne = dieOne
                shownTwo = dieTwo
                legal = ArrayList(legalMoves(turn))
                val total = "$dieOne + $dieTwo = $dice"
                if (legal.isEmpty()) {
                    message = "${nameOf(turn)} rolled $total — no legal move."
                    passDelay = 0.9f
                } else if (isAi(turn)) {
                    aiThink = 0.55f
                } else {
                    message = "${nameOf(turn)} rolled $total. Tap a highlighted token."
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
        if (isAi(turn) || passDelay > 0f || animSeat >= 0) return false
        if (!rolled && rolling <= 0f && ArcadeDraw.inside(rollRect, x, y)) {
            roll()
            return true
        }
        if (rolled && rolling <= 0f) {
            val hit = tokenRects
                .filter { (seat, token, rect) -> seat == turn && ArcadeDraw.inside(rect, x, y) && legal.contains(token) }
                .minByOrNull { (_, _, rect) ->
                    val dx = rect.centerX() - x
                    val dy = rect.centerY() - y
                    dx * dx + dy * dy
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
        dieOne = random.nextInt(6) + 1
        dieTwo = random.nextInt(6) + 1
        shownOne = dieOne
        shownTwo = dieTwo
        rolled = true
        doubles = dieOne == dieTwo
        if (doubles) {
            doubleCount++
        } else {
            doubleCount = 0
        }
        if (doubleCount == 3) {
            message = "${nameOf(turn)} rolled a third double — turn lost."
            doubleCount = 0
            doubles = false
            rolled = false
            dieOne = 0
            dieTwo = 0
            passDelay = 1.0f
            return
        }
        rolling = 0.5f
        legal = ArrayList()
    }

    private fun move(seat: Int, token: Int) {
        if (dieOne == 0 || done() || animSeat >= 0) return
        val position = positions[seat][token]
        if (position == 56) return
        val target = when {
            position == -1 -> 0
            position == 55 -> 56
            else -> position + dice
        }
        if (target > 56) return
        animSeat = seat
        animToken = token
        animFrom = position
        animTo = target
        val steps = if (position == -1) 1 else (target - position).coerceAtLeast(1)
        animDuration = (steps * 0.065f).coerceIn(0.14f, 0.52f)
        animTime = 0f
        animExtraRoll = doubles
        animDistance = dice
        rolled = false
        legal = ArrayList()
        dieOne = 0
        dieTwo = 0
        doubles = false
    }

    private fun commitMove() {
        val seat = animSeat
        val token = animToken
        val target = animTo
        animSeat = -1
        animToken = -1
        animFrom = -1
        animTo = -1
        if (seat < 0 || token < 0) return
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

        if (animExtraRoll) {
            message = listOfNotNull(
                announcement.takeIf { it.isNotEmpty() },
                "Doubles — $animDistance moved, roll again."
            ).joinToString(" ")
            if (isAi(turn)) aiThink = 0.7f
        } else {
            doubleCount = 0
            turn = nextSeat(turn)
            message = listOfNotNull(announcement.takeIf { it.isNotEmpty() }, "${nameOf(turn)}'s turn.").joinToString(" ")
            if (isAi(turn)) aiThink = 0.9f
        }
    }

    private fun passTurn() {
        rolled = false
        dieOne = 0
        dieTwo = 0
        doubles = false
        doubleCount = 0
        legal = ArrayList()
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
                ". All four of your tokens must reach the centre; a token needs an exact total to get in.",
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
            val target = when {
                position == -1 -> 0
                position == 55 -> 56
                else -> position + dice
            }
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
            dieOne == 0 -> "ready"
            else -> "$dieOne + $dieTwo = $dice"
        }
        val homes = activeSeats.joinToString(" · ") { "${seats[it].short} ${homeCount(it)}/4" }
        return "$whose · $diceText · $homes"
    }

    override fun hint(): String = when {
        done() -> ""
        isAi(turn) -> "The AI is thinking."
        !rolled -> "Tap ROLL. Two dice decide the move; a six on either die brings a token out."
        legal.isEmpty() -> "No legal move — the turn passes."
        else -> "Tap a highlighted token to move it $dice squares. A six on either die can also leave the yard."
    }

    override fun score(): Long = captures[0] * 25L + homeCount(0) * 60L

    // --------------------------------------------------------------------- draw

    override fun draw(canvas: Canvas, width: Float, height: Float) {
        val glow = ArcadeDraw.paint(Color.TRANSPARENT)
        glow.shader = RadialGradient(
            width * 0.5f, height * 0.3f, width * 1.1f,
            intArrayOf(0xFF2A3557.toInt(), 0xFF0D1120.toInt()), null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width, height, glow)

        val boardSize = min(width * 0.94f, height * 0.6f)
        cell = boardSize / 15f
        boardLeft = (width - boardSize) / 2f
        boardTop = height * 0.045f

        // Ivory board with a warm bevel, a soft drop shadow and an inner rule.
        val plate = RectF(boardLeft - cell * 0.22f, boardTop - cell * 0.22f,
            boardLeft + boardSize + cell * 0.22f, boardTop + boardSize + cell * 0.22f)
        ArcadeDraw.shadow(canvas, plate, cell * 0.9f, 0.55f)
        ArcadeDraw.panel(canvas, plate, 0xFFF7EEDD.toInt(), cell * 0.6f, 0xFFD8C5A4.toInt(), cell * 0.055f)
        ArcadeDraw.panel(canvas,
            boardLeft - cell * 0.05f, boardTop - cell * 0.05f,
            boardLeft + boardSize + cell * 0.05f, boardTop + boardSize + cell * 0.05f,
            0x00000000, cell * 0.3f, 0x33FFFFFF, cell * 0.02f)

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
            val active = seat == turn && !done()
            if (active) {
                ArcadeDraw.panel(canvas,
                    RectF(rect.left - cell * 0.11f, rect.top - cell * 0.11f,
                        rect.right + cell * 0.11f, rect.bottom + cell * 0.11f),
                    0x22FFD34D, cell * 0.65f, ArcadeColors.GOLD, cell * 0.07f)
            }
            ArcadeDraw.panel(canvas, rect, info.color, cell * 0.6f)
            // A lighter inner rim, then the cream resting square.
            ArcadeDraw.panel(canvas,
                RectF(rect.left + cell * 0.22f, rect.top + cell * 0.22f,
                    rect.right - cell * 0.22f, rect.bottom - cell * 0.22f),
                0x22FFFFFF, cell * 0.5f)
            val inset = cell * 0.75f
            val inner = RectF(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset)
            ArcadeDraw.panel(canvas, inner, 0xFFFDF8EE.toInt(), cell * 0.45f, 0x22FFFFFF, cell * 0.02f)

            val label = when {
                twoPlayer && seat == 0 -> "P1"
                twoPlayer && seat == 2 -> "P2"
                twoPlayer -> ""
                seat == 0 -> "YOU"
                else -> info.short
            }
            if (label.isNotEmpty()) {
                val pill = RectF(rect.centerX() - cell * 0.95f, rect.top + cell * 0.3f,
                    rect.centerX() + cell * 0.95f, rect.top + cell * 1.02f)
                ArcadeDraw.panel(canvas, pill, 0x2EFFFFFF, pill.height() / 2f, 0x44FFFFFF, cell * 0.02f)
                ArcadeDraw.text(canvas, label, pill.centerX(), pill.centerY() + pill.height() * 0.27f,
                    pill.height() * 0.46f, 0xFFFFFFFF.toInt(), bold = true, align = Paint.Align.CENTER)
            }

            for (socket in 0..3) {
                val point = yardPoint(seat, socket)
                ArcadeDraw.circle(canvas, point.x, point.y + cell * 0.045f, cell * 0.37f, 0x1A000000)
                ArcadeDraw.circle(canvas, point.x, point.y, cell * 0.35f, 0xFFFFFBF2.toInt(),
                    info.color, cell * 0.045f)
                ArcadeDraw.circle(canvas, point.x, point.y, cell * 0.12f, 0x22000000)
            }
        }
    }

    private fun drawTrack(canvas: Canvas) {
        for ((index, point) in track.withIndex()) {
            val rect = cellRect(point.first, point.second, cell * 0.07f)
            val owner = seats.firstOrNull { it.start == index }
            val star = index in safeSquares && owner == null
            val fill = when {
                owner != null -> owner.color
                star -> 0xFFFFF0C2.toInt()
                else -> 0xFFFFFDF8.toInt()
            }
            ArcadeDraw.panel(canvas, rect, fill, cell * 0.18f, 0xFFE1D3B8.toInt(), cell * 0.03f)
            if (owner != null) {
                drawArrow(canvas, rect.centerX(), rect.centerY(), cell * 0.19f, trackDirection(index), 0xE6FFFFFF.toInt())
            } else if (star) {
                drawStar(canvas, rect.centerX(), rect.centerY(), cell * 0.2f, 0xFFC08A1E.toInt())
            } else {
                ArcadeDraw.circle(canvas, rect.centerX(), rect.centerY(), cell * 0.045f, 0x22000000)
            }
        }

        // Private home columns, with chevrons pointing at the centre.
        for (seat in seats.indices) {
            val direction = when (seat) {
                0 -> 0   // up
                1 -> 1   // right
                2 -> 2   // down
                else -> 3 // left
            }
            for (step in 0..4) {
                val spot = homeCell(seat, step)
                val rect = cellRect(spot.first, spot.second, cell * 0.07f)
                val alpha = 0xC8 + step * 10
                ArcadeDraw.panel(canvas, rect, seats[seat].color, cell * 0.18f, 0x33FFFFFF, cell * 0.028f)
                drawChevron(canvas, rect.centerX(), rect.centerY(), cell * 0.16f, direction,
                    Color.argb(alpha.coerceAtMost(255), 255, 255, 255))
            }
        }

        // Coloured arrows on the four home-entrance squares.
        for (seat in seats.indices) {
            val entry = when (seat) {
                0 -> 7 to 14
                1 -> 0 to 7
                2 -> 7 to 0
                else -> 14 to 7
            }
            val rect = cellRect(entry.first, entry.second)
            val direction = when (seat) { 0 -> 0; 1 -> 1; 2 -> 2; else -> 3 }
            drawArrow(canvas, rect.centerX(), rect.centerY(), cell * 0.26f, direction, seats[seat].color)
        }
    }

    private fun trackDirection(index: Int): Int {
        val here = track[index]
        val next = track[(index + 1) % track.size]
        return when {
            next.first > here.first -> 1
            next.first < here.first -> 3
            next.second > here.second -> 2
            else -> 0
        }
    }

    private fun drawCentre(canvas: Canvas) {
        val left = boardLeft + 6 * cell
        val top = boardTop + 6 * cell
        val right = left + 3 * cell
        val bottom = top + 3 * cell
        val mid = PointF((left + right) / 2f, (top + bottom) / 2f)
        ArcadeDraw.panel(canvas, RectF(left, top, right, bottom), 0xFFF3E8D4.toInt(), cell * 0.24f,
            0xFFD8C5A4.toInt(), cell * 0.05f)
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
            canvas.drawPath(path, ArcadeDraw.paint(0xCCFFFDF8.toInt(), cell * 0.03f))
        }
        ArcadeDraw.circle(canvas, mid.x, mid.y, cell * 0.26f, 0xFFFFFBF0.toInt(), 0x33000000, cell * 0.02f)
        drawStar(canvas, mid.x, mid.y, cell * 0.2f, 0xFFC08A1E.toInt())
    }

    private fun drawTokens(canvas: Canvas) {
        tokenRects.clear()
        val pulse = (0.5f + 0.5f * sin(elapsed * 4.2f))
        for (seat in activeSeats) {
            for (token in 0..3) {
                val point = tokenPoint(seat, token)
                val finished = positions[seat][token] == 56
                val radius = if (finished) cell * 0.21f else cell * 0.3f
                val hitRadius = cell * 0.62f
                val rect = RectF(point.x - hitRadius, point.y - hitRadius, point.x + hitRadius, point.y + hitRadius)
                val movable = seat == turn && !isAi(turn) && rolled && rolling <= 0f && animSeat < 0 && legal.contains(token)
                if (movable) {
                    ArcadeDraw.circle(canvas, point.x, point.y, radius + cell * (0.1f + pulse * 0.09f),
                        0x33FFD34D, ArcadeColors.GOLD, cell * 0.05f)
                }
                ArcadeDraw.circle(canvas, point.x + cell * 0.02f, point.y + cell * 0.08f, radius, 0x40000000)
                ArcadeDraw.circle(canvas, point.x, point.y, radius, seats[seat].color)
                ArcadeDraw.circle(canvas, point.x, point.y, radius, Color.TRANSPARENT, 0xFFFFFFFF.toInt(), cell * 0.055f)
                ArcadeDraw.circle(canvas, point.x - radius * 0.28f, point.y - radius * 0.32f, radius * 0.26f, 0x59FFFFFF)
                ArcadeDraw.circle(canvas, point.x, point.y, radius * 0.16f, 0x33000000)
                if (legal.contains(token) && seat == turn) tokenRects += Triple(seat, token, rect)
            }
        }
    }

    private fun drawArrow(canvas: Canvas, cx: Float, cy: Float, size: Float, direction: Int, color: Int) {
        val path = Path()
        path.moveTo(0f, -size)
        path.lineTo(size * 0.82f, size * 0.28f)
        path.lineTo(size * 0.3f, size * 0.28f)
        path.lineTo(size * 0.3f, size)
        path.lineTo(-size * 0.3f, size)
        path.lineTo(-size * 0.3f, size * 0.28f)
        path.lineTo(-size * 0.82f, size * 0.28f)
        path.close()
        val matrix = Matrix()
        matrix.postRotate(direction * 90f)
        matrix.postTranslate(cx, cy)
        path.transform(matrix)
        canvas.drawPath(path, ArcadeDraw.paint(color))
    }

    private fun drawChevron(canvas: Canvas, cx: Float, cy: Float, size: Float, direction: Int, color: Int) {
        val path = Path()
        path.moveTo(-size, size * 0.55f)
        path.lineTo(0f, -size * 0.55f)
        path.lineTo(size, size * 0.55f)
        val matrix = Matrix()
        matrix.postRotate(direction * 90f)
        matrix.postTranslate(cx, cy)
        path.transform(matrix)
        canvas.drawPath(path, ArcadeDraw.paint(color, size * 0.34f))
    }

    private fun squareCenter(seat: Int, token: Int, position: Int): PointF = when {
        position == -1 -> yardPoint(seat, token)
        position == 56 -> finishedPoint(seat, token)
        position <= 50 -> {
            val index = absolute(seat, position)
            val spot = track[index]
            cellRect(spot.first, spot.second).let { PointF(it.centerX(), it.centerY()) }
        }
        else -> {
            val spot = homeCell(seat, position - 51)
            cellRect(spot.first, spot.second).let { PointF(it.centerX(), it.centerY()) }
        }
    }

    private fun tokenPoint(seat: Int, token: Int): PointF {
        if (seat == animSeat && token == animToken && animDuration > 0f) {
            val t = (animTime / animDuration).coerceIn(0f, 1f)
            if (animFrom == -1) {
                val a = yardPoint(seat, token)
                val b = squareCenter(seat, token, animTo)
                val arc = sin(t * Math.PI).toFloat() * cell * 0.45f
                return PointF(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t - arc)
            }
            val steps = (animTo - animFrom).coerceAtLeast(1)
            val scaled = t * steps
            val stepIdx = scaled.toInt().coerceAtMost(steps - 1)
            val frac = scaled - stepIdx
            val a = squareCenter(seat, token, animFrom + stepIdx)
            val b = squareCenter(seat, token, animFrom + stepIdx + 1)
            val arc = sin(frac * Math.PI).toFloat() * cell * 0.28f
            return PointF(a.x + (b.x - a.x) * frac, a.y + (b.y - a.y) * frac - arc)
        }
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
        val top = boardTop + boardSize + height * 0.032f
        val diceSize = min(width * 0.1f, height * 0.07f)
        val diceGap = diceSize * 0.3f
        val left = width * 0.055f
        val total = when {
            rolling > 0f -> "…"
            dieOne == 0 -> "—"
            else -> "$dice"
        }

        // Dice tray: two dice, a plus, and the total that is this turn's move.
        val trayRight = left + diceSize * 2f + diceGap + width * 0.13f
        ArcadeDraw.panel(canvas, RectF(left - width * 0.02f, top - height * 0.012f,
            trayRight, top + diceSize + height * 0.012f), 0x18FFFFFF, diceSize * 0.22f, 0x22FFFFFF, 1f)
        ArcadeDraw.die(canvas, left, top, diceSize, shownOne, 0xFFFFFBF2.toInt(), ArcadeColors.INK)
        ArcadeDraw.text(canvas, "+", left + diceSize + diceGap / 2f, top + diceSize * 0.58f,
            diceSize * 0.5f, 0xCCFFFFFF.toInt(), bold = true, align = Paint.Align.CENTER)
        ArcadeDraw.die(canvas, left + diceSize + diceGap, top, diceSize, shownTwo,
            0xFFFFFBF2.toInt(), ArcadeColors.INK)
        ArcadeDraw.text(canvas, "= $total", left + diceSize * 2f + diceGap * 1.6f, top + diceSize * 0.58f,
            diceSize * 0.55f, ArcadeColors.GOLD, bold = true)
        ArcadeDraw.text(canvas, if (dieOne == 6 || dieTwo == 6) "A six can leave the yard" else "Total is your move",
            left, top + diceSize + height * 0.022f, height * 0.0115f, 0x99FFFFFF.toInt(), bold = true)

        // Roll button.
        val rollH = maxOf(diceSize * 1.18f, height * 0.076f)
        val rollTop = top - (rollH - diceSize) * 0.5f
        rollRect = RectF(width * 0.545f, rollTop, width * 0.945f, rollTop + rollH)
        val canRoll = !rolled && rolling <= 0f && animSeat < 0 && !isAi(turn) && !done()
        val activeColor = seats[turn].color
        if (canRoll) {
            ArcadeDraw.panel(canvas, RectF(rollRect.left - 2f, rollRect.top - 2f, rollRect.right + 2f, rollRect.bottom + 2f),
                0x33FFD34D, rollRect.height() / 2f, ArcadeColors.GOLD, 1.5f)
        }
        ArcadeDraw.panel(canvas, rollRect,
            if (canRoll) activeColor else 0x1FFFFFFF,
            rollRect.height() / 2f,
            if (canRoll) 0x66FFFFFF else 0x22FFFFFF, 1.2f)
        val label = when {
            done() -> "GAME OVER"
            isAi(turn) -> "${seats[turn].name.uppercase()} AI · THINKING"
            rolled -> "$total SQUARES"
            else -> "ROLL"
        }
        ArcadeDraw.text(canvas, label, rollRect.centerX(), rollRect.centerY() + rollRect.height() * 0.17f,
            ArcadeDraw.fitted(label, rollRect.height() * 0.36f, rollRect.width() * 0.86f, bold = true),
            if (canRoll) 0xFF18121F.toInt() else 0xCCFFFFFF.toInt(), bold = true, align = Paint.Align.CENTER)
        if (canRoll) {
            ArcadeDraw.text(canvas, nameOf(turn).uppercase(), rollRect.centerX(),
                rollRect.centerY() - rollRect.height() * 0.22f, rollRect.height() * 0.2f,
                0xCC2A1E12.toInt(), bold = true, align = Paint.Align.CENTER)
        }

        // Seat chips with home progress.
        val chipY = top + diceSize + height * 0.055f
        val gap = width * 0.016f
        val totalWidth = width * 0.89f
        val chipWidth = (totalWidth - gap * (activeSeats.size - 1)) / activeSeats.size
        activeSeats.forEachIndexed { index, seat ->
            val chipLeft = width * 0.055f + index * (chipWidth + gap)
            val rect = RectF(chipLeft, chipY, chipLeft + chipWidth, chipY + height * 0.044f)
            val active = seat == turn && !done()
            ArcadeDraw.panel(canvas, rect,
                if (active) seats[seat].color else 0x16FFFFFF,
                rect.height() / 2f,
                if (active) 0xFFE8C77A.toInt() else 0x22FFFFFF,
                if (active) 1.6f else 1f)
            ArcadeDraw.circle(canvas, rect.left + rect.height() * 0.62f, rect.centerY(),
                rect.height() * 0.19f, if (active) 0xFF18121F.toInt() else seats[seat].color)
            val name = when {
                twoPlayer && seat == 0 -> "P1"
                twoPlayer -> "P2"
                seat == 0 -> "YOU"
                else -> seats[seat].name.uppercase()
            }
            ArcadeDraw.text(canvas, name, rect.left + rect.height() * 0.95f,
                rect.centerY() + rect.height() * 0.16f, rect.height() * 0.36f,
                if (active) 0xFF18121F.toInt() else 0xE6FFFFFF.toInt(), bold = true)
            // Four pips: filled pips are tokens home.
            val pips = homeCount(seat)
            val pipR = rect.height() * 0.09f
            val startX = rect.right - rect.height() * 0.9f
            for (i in 0..3) {
                val x = startX + i * (pipR * 2.6f)
                if (i < pips) {
                    ArcadeDraw.circle(canvas, x, rect.centerY(), pipR,
                        if (active) 0xFF18121F.toInt() else 0xFFFFFBF2.toInt())
                } else {
                    ArcadeDraw.circle(canvas, x, rect.centerY(), pipR, Color.TRANSPARENT,
                        if (active) 0x6618121F else 0x66FFFFFF, 1.2f)
                }
            }
        }

        ArcadeDraw.text(canvas, "TWO DICE · THE TOTAL IS THE MOVE · DOUBLES ROLL AGAIN",
            width / 2f, chipY + height * 0.075f, height * 0.011f, 0x77FFFFFF.toInt(),
            bold = true, align = Paint.Align.CENTER)

        if (message.isNotEmpty()) {
            val size = ArcadeDraw.fitted(message, height * 0.0135f, width * 0.9f)
            ArcadeDraw.text(canvas, message, width / 2f, height * 0.972f, size,
                0xE6FFFFFF.toInt(), align = Paint.Align.CENTER)
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
