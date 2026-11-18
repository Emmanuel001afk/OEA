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

/**
 * Tic Tac Toe, with a ladder that actually changes the opponent.
 *
 * Levels 1–5 are the classic 3×3 board: easy wanders, normal sees one move
 * ahead, hard and expert play a perfect minimax and cannot be beaten. From level
 * six the board is 4×4 and needs four in a row, which makes the centre and the
 * corners genuinely different squares instead of a solved game.
 */
class TicTacToeGame : ArcadeBaseGame() {

    private var size = 3
    private var need = 3
    private var board = IntArray(9)
    private var player = 1
    private var turn = true
    private var aiDelay = 0f
    private var winLine = IntArray(0)
    private var drawn = 0
    private var yourWins = 0
    private var theirWins = 0
    private var rounds = 1
    private var round = 1
    private val cells = ArrayList<RectF>()

    override fun onStart() {
        size = if (config.level >= 6) 4 else 3
        need = size
        rounds = 1
        round = 1
        yourWins = 0
        theirWins = 0
        startRound(true)
    }

    private fun startRound(playerStarts: Boolean) {
        board = IntArray(size * size)
        player = 1
        turn = playerStarts
        winLine = IntArray(0)
        aiDelay = 0f
        drawn = 0
        if (!turn) aiDelay = 0.45f
    }

    override fun update(dt: Float) {
        super.update(dt)
        if (done()) return
        if (aiDelay > 0f) {
            aiDelay -= dt
            if (aiDelay <= 0f) aiMove()
        }
    }

    override fun touch(phase: ArcadeTouch, x: Float, y: Float, width: Float, height: Float): Boolean {
        if (done() || phase != ArcadeTouch.DOWN) return false
        if (!turn && config.mode != ArcadePlayMode.TWO_PLAYER) return false
        val index = cells.indexOfFirst { ArcadeDraw.inside(it, x, y) }
        if (index < 0 || index >= board.size || board[index] != 0) return false
        place(index, if (config.mode == ArcadePlayMode.TWO_PLAYER) player else 1)
        return true
    }

    private fun place(index: Int, mark: Int) {
        board[index] = mark
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
        if (config.mode == ArcadePlayMode.TWO_PLAYER) {
            player = if (player == 1) 2 else 1
            turn = true
        } else {
            turn = false
            aiDelay = 0.4f - skill * 0.2f
        }
    }

    private fun settle(winner: Int) {
        val twoPlayer = config.mode == ArcadePlayMode.TWO_PLAYER
        when {
            winner == 0 -> finish(
                won = null,
                headline = "A draw",
                detail = "Nobody could break through. A draw is a real result here — the perfect game ends level.",
                score = 40L,
                stars = 2
            )
            twoPlayer -> finish(
                won = winner == 1,
                headline = "Player $winner wins",
                detail = if (winner == 1) "Player 1 found the line." else "Player 2 found the line.",
                score = 100L,
                stars = 3
            )
            winner == 1 -> finish(
                won = true,
                headline = "You win",
                detail = "Level ${config.level} beaten on a ${size}×${size} board. Try the next difficulty before moving on.",
                score = 100L + config.level * 10L,
                stars = 3
            )
            else -> finish(
                won = false,
                headline = "The AI wins",
                detail = if (config.level >= 6) {
                    "On a 4×4 board the AI sees the open lines a move before you do. Block the long diagonal early."
                } else {
                    "The AI took the middle and the fork. Take the centre first and answer every threat."
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
            config.difficulty == ArcadeDifficulty.NORMAL && rand() > 0.68f -> empty.random(random)
            size == 3 -> perfectMove(empty)
            else -> heuristicMove(empty)
        }
        place(choice, 2)
    }

    // ------------------------------------------------------------------ search

    private fun bestLine(mark: Int): IntArray {
        val lines = allLines()
        for (line in lines) if (line.all { board[it] == mark }) return line
        return IntArray(0)
    }

    private fun allLines(): List<IntArray> {
        val lines = ArrayList<IntArray>()
        for (r in 0 until size) lines += IntArray(size) { c -> r * size + c }
        for (c in 0 until size) lines += IntArray(size) { r -> r * size + c }
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
     * 4×4 is too wide for a naive full search on a phone frame, so the AI looks
     * for immediate wins and blocks, then scores every empty square by how many
     * open lines of its own and of yours pass through it.
     */
    private fun heuristicMove(empty: List<Int>): Int {
        val win = immediate(2)
        if (win != null) return win
        val block = immediate(1)
        if (block != null) return block
        return empty.maxByOrNull { index ->
            var score = 0f
            for (line in allLines()) {
                if (index !in line) continue
                var mine = 0
                var theirs = 0
                var blocked = 0
                for (cell in line) {
                    when (board[cell]) {
                        2 -> mine++
                        1 -> theirs++
                        0 -> Unit
                        else -> blocked++
                    }
                }
                if (blocked > 0) continue
                if (theirs > 0 && mine > 0) continue
                score += if (mine > 0) Math.pow(2.0, mine.toDouble()).toFloat() * 0.7f else Math.pow(2.0, theirs.toDouble()).toFloat()
                if (cellCenter(index) == cellCenter(line[line.size / 2])) score += 0.4f
            }
            score
        } ?: empty.first()
    }

    private fun cellCenter(index: Int): Float = index.toFloat()

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

    // -------------------------------------------------------------------- draw

    override fun status(): String {
        if (done()) return if (winLine.isNotEmpty()) "Line complete" else "Board drawn"
        val whose = if (config.mode == ArcadePlayMode.TWO_PLAYER) "Player $player" else if (turn) "Your turn" else "OEA AI is thinking"
        return if (config.mode == ArcadePlayMode.TWO_PLAYER) "$whose · X and O on one phone" else "$whose · ${config.difficulty.label}"
    }

    override fun hint(): String = if (drawn == 0 && config.mode != ArcadePlayMode.TWO_PLAYER) {
        "You are X. The centre is the strongest first move."
    } else ""

    override fun draw(canvas: Canvas, width: Float, height: Float) {
        canvas.drawColor(0xFF171F3D.toInt())
        val p = ArcadeDraw.paint(0x335B79FF)
        canvas.drawCircle(width * 0.15f, height * 0.2f, width * 0.3f, p)
        canvas.drawCircle(width * 0.9f, height * 0.75f, width * 0.36f, p)

        val boardSize = minOf(width * 0.88f, height * 0.66f)
        val left = (width - boardSize) / 2f
        val top = height * 0.16f + (height * 0.62f - boardSize) / 2f
        val cell = boardSize / size
        cells.clear()
        for (i in 0 until size * size) {
            val c = i % size
            val r = i / size
            cells += RectF(left + c * cell, top + r * cell, left + (c + 1) * cell, top + (r + 1) * cell)
        }

        ArcadeDraw.panel(canvas, left - cell * 0.05f, top - cell * 0.05f, left + boardSize + cell * 0.05f,
            top + boardSize + cell * 0.05f, 0xFF202A55.toInt(), cell * 0.1f)
        for (i in 1 until size) {
            ArcadeDraw.line(canvas, left + i * cell, top, left + i * cell, top + boardSize, 0x66FFFFFF, cell * 0.02f)
            ArcadeDraw.line(canvas, left, top + i * cell, left + boardSize, top + i * cell, 0x66FFFFFF, cell * 0.02f)
        }
        for (i in board.indices) {
            val rect = cells[i]
            val mark = board[i]
            if (mark == 0) continue
            val color = if (mark == 1) ArcadeColors.CORAL else 0xFF6FD6FF.toInt()
            val pad = cell * 0.24f
            if (mark == 1) {
                ArcadeDraw.line(canvas, rect.left + pad, rect.top + pad, rect.right - pad, rect.bottom - pad, color, cell * 0.075f)
                ArcadeDraw.line(canvas, rect.right - pad, rect.top + pad, rect.left + pad, rect.bottom - pad, color, cell * 0.075f)
            } else {
                canvas.drawCircle(rect.centerX(), rect.centerY(), cell * 0.27f, ArcadeDraw.paint(Color.TRANSPARENT, cell * 0.075f).apply { this.color = color })
            }
        }
        if (winLine.isNotEmpty()) {
            val a = cells[winLine.first()]
            val b = cells[winLine.last()]
            ArcadeDraw.line(canvas, a.centerX(), a.centerY(), b.centerX(), b.centerY(), 0xFFFFD34D.toInt(), cell * 0.06f)
        }

        if (config.mode == ArcadePlayMode.TWO_PLAYER) {
            val chipW = width * 0.36f
            for (i in 0..1) {
                val x = width * 0.08f + i * (chipW + width * 0.12f)
                val rect = RectF(x, height * 0.875f, x + chipW, height * 0.925f)
                val active = (i == 0) == turn
                ArcadeDraw.panel(canvas, rect, if (active) ArcadeColors.WHITE else 0x22FFFFFF, rect.height() / 2f,
                    0x33FFFFFF, 1.5f)
                ArcadeDraw.text(canvas, if (i == 0) "P1 · X" else "P2 · O", rect.centerX(), rect.centerY() + rect.height() * 0.18f,
                    rect.height() * 0.44f, if (active) ArcadeColors.INK else Color.WHITE, bold = true, align = Paint.Align.CENTER)
            }
        } else {
            ArcadeDraw.text(canvas, "${config.difficulty.label.uppercase()} · ${if (size == 3) "3 IN A ROW" else "4 IN A ROW"}",
                width / 2f, height * 0.925f, height * 0.016f, 0x88FFFFFF.toInt(), bold = true, align = Paint.Align.CENTER)
        }
    }
}
