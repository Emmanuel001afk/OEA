package com.oea.games.allinone.games

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.oea.games.allinone.ArcadeBaseGame
import com.oea.games.allinone.ArcadeColors
import com.oea.games.allinone.ArcadeDraw
import com.oea.games.allinone.ArcadeTouch
import kotlin.math.min

/**
 * Slide Puzzle: one empty square and a promise that every shuffle can be solved.
 *
 * The board is shuffled by making hundreds of legal moves from the solved state,
 * never by dropping numbers at random — that is the difference between a puzzle
 * and a dead board nobody can finish. Levels grow the grid from 3×3 to 5×5 and
 * set a par move count for the stars.
 */
class SlidingGame : ArcadeBaseGame() {

    private var size = 3
    private var tiles = IntArray(9)
    private var moves = 0
    private var par = 60
    private var blank = 8
    private val cells = ArrayList<RectF>()

    override fun onStart() {
        size = when {
            config.level <= 3 -> 3
            config.level <= 7 -> 4
            else -> 5
        }
        tiles = IntArray(size * size)
        for (i in tiles.indices) tiles[i] = i + 1
        tiles[tiles.lastIndex] = 0
        blank = tiles.lastIndex
        moves = 0
        par = (size * size * 9 * (0.9f + skill * 0.3f)).toInt()
        val steps = size * size * 70
        repeat(steps) {
            val neighbours = neighbours(blank)
            val pick = neighbours[random.nextInt(neighbours.size)]
            swap(pick)
        }
        // An untouched solved board would be an empty level; make one more move.
        if (solved()) swap(neighbours(blank).first())
    }

    private fun neighbours(index: Int): List<Int> {
        val r = index / size
        val c = index % size
        val out = ArrayList<Int>(4)
        if (r > 0) out += index - size
        if (r < size - 1) out += index + size
        if (c > 0) out += index - 1
        if (c < size - 1) out += index + 1
        return out
    }

    private fun swap(index: Int) {
        val value = tiles[index]
        tiles[index] = tiles[blank]
        tiles[blank] = value
        blank = index
    }

    private fun solved(): Boolean = tiles.indices.all { i ->
        if (i == tiles.lastIndex) tiles[i] == 0 else tiles[i] == i + 1
    }

    override fun touch(phase: ArcadeTouch, x: Float, y: Float, width: Float, height: Float): Boolean {
        if (done() || phase != ArcadeTouch.DOWN) return false
        val index = cells.indexOfFirst { ArcadeDraw.inside(it, x, y) }
        if (index < 0) return false
        if (neighbours(blank).contains(index)) {
            swap(index)
            moves++
            if (solved()) settle()
            return true
        }
        return false
    }

    private fun settle() {
        val stars = when {
            moves <= par -> 3
            moves <= par * 1.6f -> 2
            else -> 1
        }
        finish(
            won = true,
            headline = "Order restored",
            detail = "$moves moves, par $par. " +
                if (stars == 3) "A clean solve — the next grid is bigger." else "Solve the first row first, then the first column, and the rest falls in.",
            score = (par * 10 - moves).coerceAtLeast(10).toLong(),
            stars = stars
        )
    }

    override fun status(): String = "Moves $moves · par $par · ${size}×$size"

    override fun hint(): String = if (moves == 0) "Tap a tile next to the empty square." else ""

    override fun score(): Long = maxOf(0, par - moves).toLong()

    override fun draw(canvas: Canvas, width: Float, height: Float) {
        canvas.drawColor(0xFF2B2153.toInt())
        val glow = ArcadeDraw.paint(Color.TRANSPARENT)
        glow.shader = android.graphics.RadialGradient(width * 0.5f, height * 0.35f, width, intArrayOf(0x559B7BEF, 0x002B2153), null, android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width, height, glow)
        val boardSize = min(width * 0.88f, height * 0.68f)
        val left = (width - boardSize) / 2f
        val top = height * 0.15f + (height * 0.66f - boardSize) / 2f
        val cell = boardSize / size
        val colors = intArrayOf(ArcadeColors.GOLD, ArcadeColors.CORAL, ArcadeColors.MINT, ArcadeColors.SKY,
            ArcadeColors.LILAC, ArcadeColors.PINK, ArcadeColors.LIME, ArcadeColors.TANGERINE)
        cells.clear()
        ArcadeDraw.panel(canvas, left - cell * 0.05f, top - cell * 0.05f, left + boardSize + cell * 0.05f,
            top + boardSize + cell * 0.05f, 0x33000000, cell * 0.14f)
        for (i in tiles.indices) {
            val c = i % size
            val r = i / size
            val rect = RectF(left + c * cell, top + r * cell, left + (c + 1) * cell, top + (r + 1) * cell)
            cells += rect
            if (tiles[i] == 0) continue
            val color = colors[(tiles[i] - 1) % colors.size]
            ArcadeDraw.panel(canvas, rect.left + cell * 0.05f, rect.top + cell * 0.05f, rect.right - cell * 0.05f, rect.bottom - cell * 0.05f,
                color, cell * 0.14f)
            ArcadeDraw.panel(canvas, rect.left + cell * 0.14f, rect.top + cell * 0.1f, rect.right - cell * 0.3f, rect.top + cell * 0.22f,
                0x33FFFFFF, cell * 0.07f)
            ArcadeDraw.text(canvas, tiles[i].toString(), rect.centerX(), rect.centerY() + cell * 0.18f, cell * 0.36f,
                ArcadeColors.INK, bold = true, align = Paint.Align.CENTER)
        }
        ArcadeDraw.text(canvas, "Moves $moves · par $par", width * 0.5f, height * 0.9f, height * 0.018f, 0xCCFFFFFF.toInt(), bold = true, align = Paint.Align.CENTER)
        ArcadeDraw.text(canvas, "${size}×$size · ${config.difficulty.label.uppercase()}", width * 0.5f, height * 0.93f, height * 0.013f, 0x77FFFFFF, bold = true, align = Paint.Align.CENTER)
    }
}
