package com.oea.games.allinone.games

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.oea.games.allinone.ArcadeBaseGame
import com.oea.games.allinone.ArcadeColors
import com.oea.games.allinone.ArcadeDifficulty
import com.oea.games.allinone.ArcadeDraw
import com.oea.games.allinone.ArcadePlayMode
import com.oea.games.allinone.ArcadeTouch
import kotlin.math.min

/**
 * Tic Tac Toe, with the turn order fixed.
 *
 * The first version had a real bug: after the AI moved it never handed the turn
 * back, so it kept filling the board on its own and the player only ever got one
 * move. That is why the game felt meaningless. The turn now belongs to exactly
 * one side at a time, and the board makes it obvious whose move it is.
 *
 * Levels 1–5 are classic 3×3. From level 6 the board is 4×4 and needs four in a
 * row. Easy wanders, Normal makes mistakes, Hard and Expert play a perfect
 * minimax on 3×3 and a threat search on 4×4.
 */
class TicTacToeGame : ArcadeBaseGame() {

    private var size = 3
    private var board = IntArray(9)
    private var player = 1
    private var turn = true
    private var aiDelay = 0f
    private var winLine = IntArray(0)
    private var drawn = 0
    private var lastMove = -1
    private val cells = ArrayList<RectF>()

    private val twoPlayer: Boolean get() = config.mode == ArcadePlayMode.TWO_PLAYER

    override fun onStart() {
        size = if (config.level >= 6) 4 else 3
        board = IntArray(size * size)
        player = 1
        turn = true
        winLine = IntArray(0)
        aiDelay = 0f
        drawn = 0
        lastMove = -1
        cells.clear()
    }

    override fun update(dt: Float) {
        super.update(dt)
        if (done() || twoPlayer) return
        if (!turn && aiDelay > 0f) {
            aiDelay -= dt
            if (aiDelay <= 0f) aiMove()
        }
    }

    override fun touch(phase: ArcadeTouch, x: Float, y: Float, width: Float, height: Float): Boolean {
        if (done() || phase != ArcadeTouch.DOWN || !turn) return false
        val index = cells.indexOfFirst { ArcadeDraw.inside(it, x, y) }
        if (index < 0 || index >= board.size || board[index] != 0) return false
        place(index, if (twoPlayer) player else 1)
        return true
    }

    private fun place(index: Int, mark: Int) {
        board[index] = mark
        lastMove = index
        drawn++
        val line = bestLine(mark)
        if (line.isNotEmpty()) {
            winLine = line
            settle(mark)
            return
        }
        if (drawn == board.size) {
            settle(0)
            return
        }
        if (twoPlayer) {
            player = if (player == 1) 2 else 1
            turn = true
        } else if (mark == 1) {
            // The human moved: the AI's turn, after a beat so the move is seen.
            turn = false
            aiDelay = (0.5f - skill * 0.25f).coerceAtLeast(0.18f)
        } else {
            // The AI moved: hand the board straight back to the player.
            turn = true
            aiDelay = 0f
        }
    }

    private fun settle(winner: Int) {
        when {
            winner == 0 -> finish(
                won = null,
                headline = "A draw",
                detail = "Nobody could break through. On 3×3 a perfect game is always a draw — the next level adds the 4×4 board.",
                score = 40L,
                stars = 2
            )
            twoPlayer -> finish(
                won = winner == 1,
                headline = "Player ${if (winner == 1) 1 else 2} wins",
                detail = if (winner == 1) "Player 1 found the line with X." else "Player 2 found the line with O.",
                score = 100L,
                stars = 3
            )
            winner == 1 -> finish(
                won = true,
                headline = "You win",
                detail = "A line on ${size}×${size} at ${config.difficulty.label.lowercase()} difficulty. " +
                    "Win a level to unlock the next one; the AI gets sharper as you climb.",
                score = 100L + config.level * 10L,
                stars = 3
            )
            else -> finish(
                won = false,
                headline = "The AI wins",
                detail = if (size == 4) {
                    "On 4×4 the AI counts open lines a move ahead. Take the centre and block every three."
                } else {
                    "The AI took the centre and forked you. Take the centre first and answer every threat."
                },
                score = 20L,
                stars = 1
            )
        }
    }

    private fun aiMove() {
        if (done()) return
        val empty = board.indices.filter { board[it] == 0 }
        if (empty.isEmpty()) return
        val choice = when {
            config.difficulty == ArcadeDifficulty.EASY && rand() > 0.3f -> empty.random(random)
            config.difficulty == ArcadeDifficulty.NORMAL && rand() > 0.7f -> empty.random(random)
            size == 3 -> perfectMove(empty)
            else -> heuristicMove(empty)
        }
        place(choice, 2)
    }

    // ------------------------------------------------------------------ search

    private fun bestLine(mark: Int): IntArray {
        for (line in allLines()) if (line.all { board[it] == mark }) return line
        return IntArray(0)
    }

    private fun allLines(): List<IntArray> {
        val lines = ArrayList<IntArray>()
        for (row in 0 until size) lines += IntArray(size) { col -> row * size + col }
        for (col in 0 until size) lines += IntArray(size) { row -> row * size + col }
        lines += IntArray(size) { i -> i * size + i }
        lines += IntArray(size) { i -> i * size + (size - 1 - i) }
        return lines
    }

    private fun perfectMove(empty: List<Int>): Int {
        var bestScore = Int.MIN_VALUE
        var best = empty.first()
        for (index in empty) {
            board[index] = 2
            val score = minimax(depth = 0, maximizing = false, alpha = Int.MIN_VALUE + 1, beta = Int.MAX_VALUE - 1)
            board[index] = 0
            if (score > bestScore) {
                bestScore = score
                best = index
            }
        }
        return best
    }

    private fun minimax(depth: Int, maximizing: Boolean, alpha: Int, beta: Int): Int {
        bestLine(2).takeIf { it.isNotEmpty() }?.let { return 10 - depth }
        bestLine(1).takeIf { it.isNotEmpty() }?.let { return depth - 10 }
        if (board.none { it == 0 }) return 0
        var a = alpha
        var b = beta
        return if (maximizing) {
            var value = Int.MIN_VALUE
            for (i in board.indices) if (board[i] == 0) {
                board[i] = 2
                value = maxOf(value, minimax(depth + 1, false, a, b))
                board[i] = 0
                a = maxOf(a, value)
                if (b <= a) break
            }
            value
        } else {
            var value = Int.MAX_VALUE
            for (i in board.indices) if (board[i] == 0) {
                board[i] = 1
                value = minOf(value, minimax(depth + 1, true, a, b))
                board[i] = 0
                b = minOf(b, value)
                if (b <= a) break
            }
            value
        }
    }

    /**
     * 4×4 is too wide for a naive full search on a phone frame, so the AI takes
     * an immediate win, blocks an immediate loss, then scores every empty square
     * by the open lines that pass through it.
     */
    private fun heuristicMove(empty: List<Int>): Int {
        immediate(2)?.let { return it }
        immediate(1)?.let { return it }
        return empty.maxByOrNull { index ->
            var score = 0f
            for (line in allLines()) {
                if (index !in line) continue
                var mine = 0
                var theirs = 0
                for (cell in line) {
                    when (board[cell]) {
                        2 -> mine++
                        1 -> theirs++
                    }
                }
                if (theirs > 0 && mine > 0) continue
                score += if (mine > 0) Math.pow(2.0, mine.toDouble()).toFloat() * 0.75f
                else Math.pow(2.0, theirs.toDouble()).toFloat()
            }
            if (isCentre(index)) score += 0.6f
            score
        } ?: empty.first()
    }

    private fun isCentre(index: Int): Boolean {
        val row = index / size
        val col = index % size
        val middle = size / 2
        return (row == middle || row == middle - 1) && (col == middle || col == middle - 1)
    }

    private fun immediate(mark: Int): Int? {
        for (index in board.indices) {
            if (board[index] != 0) continue
            board[index] = mark
            val wins = bestLine(mark).isNotEmpty()
            board[index] = 0
            if (wins) return index
        }
        return null
    }

    // ------------------------------------------------------------------- status

    override fun status(): String {
        if (done()) return if (winLine.isNotEmpty()) "Line complete" else "Board drawn"
        val whose = when {
            twoPlayer -> "Player $player · ${if (player == 1) "X" else "O"}"
            turn -> "Your turn · X"
            else -> "OEA AI thinking · O"
        }
        return "$whose · level ${config.level}"
    }

    override fun hint(): String = when {
        done() -> ""
        twoPlayer -> "Pass the phone: ${if (player == 1) "Player 1 is X" else "Player 2 is O"}."
        drawn == 0 -> "You are X. The centre is the strongest first move."
        else -> ""
    }

    override fun score(): Long = drawn.toLong() * 5L

    // --------------------------------------------------------------------- draw

    override fun draw(canvas: Canvas, width: Float, height: Float) {
        canvas.drawColor(0xFF141B36.toInt())
        val glow = ArcadeDraw.paint(Color.TRANSPARENT)
        glow.shader = android.graphics.RadialGradient(width * 0.2f, height * 0.15f, width * 0.9f,
            intArrayOf(0x335B79FF, 0x00141B36), null, android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width, height, glow)

        // Turn banner.
        val headline = when {
            done() && winLine.isNotEmpty() -> "LINE COMPLETE"
            done() -> "DRAW"
            twoPlayer -> "PLAYER $player · ${if (player == 1) "X" else "O"}"
            turn -> "YOUR TURN · X"
            else -> "AI THINKING · O"
        }
        val bannerColor = when {
            done() -> ArcadeColors.GOLD
            twoPlayer && player == 2 -> 0xFF6FD6FF.toInt()
            turn -> ArcadeColors.CORAL
            else -> 0xFF6FD6FF.toInt()
        }
        val bannerWidth = ArcadeDraw.measure(headline, height * 0.019f, bold = true) + width * 0.09f
        ArcadeDraw.panel(canvas, width * 0.5f - bannerWidth / 2f, height * 0.06f,
            width * 0.5f + bannerWidth / 2f, height * 0.115f, 0x33000000, height * 0.03f, bannerColor, 1.6f)
        ArcadeDraw.text(canvas, headline, width * 0.5f, height * 0.098f, height * 0.019f, bannerColor,
            bold = true, align = Paint.Align.CENTER)

        // Board: a cream plate with one rounded cell per square, so the grid is
        // built from cells rather than from thin rules.
        val boardSize = min(width * 0.9f, height * 0.62f)
        val left = (width - boardSize) / 2f
        val top = height * 0.16f + (height * 0.64f - boardSize) / 2f
        val cell = boardSize / size
        val gap = cell * 0.06f
        ArcadeDraw.panel(canvas, left - gap * 2f, top - gap * 2f, left + boardSize + gap * 2f, top + boardSize + gap * 2f,
            0xFFFDF8EF.toInt(), cell * 0.12f, 0x33000000, 2f)
        cells.clear()
        for (index in board.indices) {
            val col = index % size
            val row = index / size
            val rect = RectF(left + col * cell + gap, top + row * cell + gap,
                left + (col + 1) * cell - gap, top + (row + 1) * cell - gap)
            cells += rect
            val inWin = winLine.contains(index)
            val fill = when {
                inWin -> 0xFFFFF0C2.toInt()
                index == lastMove -> 0xFFFFF6E6.toInt()
                else -> 0xFFF7EEDF.toInt()
            }
            ArcadeDraw.panel(canvas, rect, fill, cell * 0.14f,
                if (inWin) ArcadeColors.GOLD else 0xFFE6D7C2.toInt(), if (inWin) 2f else 1f)
        }

        // Marks.
        for (index in board.indices) {
            val mark = board[index]
            if (mark == 0) continue
            val rect = cells[index]
            val color = if (mark == 1) ArcadeColors.CORAL else 0xFF3E8EE0.toInt()
            val pad = cell * 0.24f
            if (mark == 1) {
                ArcadeDraw.line(canvas, rect.left + pad, rect.top + pad, rect.right - pad, rect.bottom - pad,
                    color, cell * 0.085f)
                ArcadeDraw.line(canvas, rect.right - pad, rect.top + pad, rect.left + pad, rect.bottom - pad,
                    color, cell * 0.085f)
            } else {
                ArcadeDraw.circle(canvas, rect.centerX(), rect.centerY(), cell * 0.28f, Color.TRANSPARENT,
                    color, cell * 0.085f)
            }
        }

        // Winning line over the marks.
        if (winLine.isNotEmpty()) {
            val a = cells[winLine.first()]
            val b = cells[winLine.last()]
            ArcadeDraw.line(canvas, a.centerX(), a.centerY(), b.centerX(), b.centerY(), 0xCCFFB020.toInt(), cell * 0.05f)
        }

        // Footer: mode, difficulty and the size of the board.
        val footer = when {
            twoPlayer -> "Pass and play · X and O on one phone"
            else -> "${config.difficulty.label} AI · ${if (size == 3) "3 in a row" else "4 in a row"}"
        }
        ArcadeDraw.text(canvas, footer, width * 0.5f, height * 0.925f, height * 0.014f,
            0xAAFFFFFF.toInt(), bold = true, align = Paint.Align.CENTER)
        ArcadeDraw.text(canvas, if (size == 3) "3×3" else "4×4", width * 0.5f, height * 0.955f, height * 0.012f,
            0x66FFFFFF, align = Paint.Align.CENTER)
    }
}
