package com.oea.games.allinone.games

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.oea.games.allinone.ArcadeBaseGame
import com.oea.games.allinone.ArcadeColors
import com.oea.games.allinone.ArcadeDraw
import com.oea.games.allinone.ArcadeTouch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Blocks: the "fit a shape into the grid" cabinet.
 *
 * A level is a target score. Rows and columns clear together and multi-line
 * clears pay much more, so the game rewards planning rather than frantic
 * fitting. Difficulty narrows the tray's shapes and raises the target. Later
 * levels move to a 9×9 board.
 */
class BlocksGame : ArcadeBaseGame() {

    private data class Piece(val cells: List<Pair<Int, Int>>, val color: Int)

    private val palette = intArrayOf(
        ArcadeColors.PINK, ArcadeColors.GOLD, ArcadeColors.MINT,
        ArcadeColors.SKY, ArcadeColors.LILAC, ArcadeColors.CORAL, ArcadeColors.LIME,
    )

    private var size = 8
    private var grid = IntArray(64)
    private var tray = arrayOfNulls<Piece>(3)
    private var points = 0
    private var target = 500
    private var cleared = 0
    private var placed = 0
    private var dragIndex = -1
    private var dragFx = 0f
    private var dragFy = 0f
    private var grabX = 0f
    private var grabY = 0f
    private var badDrop = 0f
    private var trayRects = ArrayList<RectF>()
    private var gridRect = RectF()
    private var cell = 1f
    private var pieceScale = 1f
    private var lastWidth = 0f
    private var lastHeight = 0f

    override fun onStart() {
        size = if (config.level >= 11) 9 else 8
        grid = IntArray(size * size)
        points = 0
        cleared = 0
        placed = 0
        target = ((170 + config.level * 145) * (0.9f + skill * 0.35f)).toInt()
        tray = arrayOfNulls(3)
        refill()
    }

    // ------------------------------------------------------------------ pieces

    private fun shapeBank(): List<List<Pair<Int, Int>>> {
        val cells = ArrayList<List<Pair<Int, Int>>>()
        cells += listOf(0 to 0)                                                   // dot
        cells += listOf(0 to 0, 1 to 0)                                           // domino
        cells += listOf(0 to 0, 0 to 1)
        cells += listOf(0 to 0, 1 to 0, 2 to 0)                                   // bar of three
        cells += listOf(0 to 0, 0 to 1, 0 to 2)
        cells += listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1)                           // square
        // L shapes, four ways.
        cells += listOf(0 to 0, 0 to 1, 1 to 1)
        cells += listOf(1 to 0, 0 to 1, 1 to 1)
        cells += listOf(0 to 0, 1 to 0, 0 to 1)
        cells += listOf(0 to 0, 1 to 0, 1 to 1)
        // T shapes.
        cells += listOf(0 to 0, 1 to 0, 2 to 0, 1 to 1)
        cells += listOf(1 to 0, 0 to 1, 1 to 1, 1 to 2)
        // Zigzags.
        cells += listOf(1 to 0, 2 to 0, 0 to 1, 1 to 1)
        cells += listOf(0 to 0, 1 to 0, 1 to 1, 2 to 1)
        if (config.level >= 4) cells += listOf(0 to 0, 1 to 0, 2 to 0, 3 to 0)
        if (config.level >= 6) cells += listOf(0 to 0, 1 to 0, 2 to 0, 0 to 1, 1 to 1, 2 to 1)
        return cells
    }

    private fun randomPiece(): Piece {
        val bank = shapeBank()
        val shape = bank[random.nextInt(bank.size)]
        return Piece(shape, palette[random.nextInt(palette.size)])
    }

    private fun refill() {
        val empty = tray.all { it == null }
        if (!empty) return
        for (i in 0 until 3) tray[i] = randomPiece()
    }

    // ------------------------------------------------------------------ layout

    private fun layout(width: Float, height: Float) {
        lastWidth = width
        lastHeight = height
        val boardSize = min(width * 0.9f, height * 0.54f)
        cell = boardSize / size
        val left = (width - boardSize) / 2f
        val top = height * 0.135f + (height * 0.52f - boardSize) * 0.25f
        gridRect.set(left, top, left + boardSize, top + boardSize)
        val trayH = height * 0.17f
        val trayTop = height - trayH - height * 0.045f
        val slot = width * 0.27f
        val gap = width * 0.035f
        val startX = (width - (slot * 3 + gap * 2)) / 2f
        trayRects.clear()
        for (i in 0 until 3) {
            trayRects += RectF(startX + i * (slot + gap), trayTop, startX + i * (slot + gap) + slot, trayTop + trayH)
        }
        pieceScale = slot / 4.4f
    }

    private fun pieceBounds(piece: Piece): Pair<Int, Int> {
        val w = piece.cells.maxOf { it.first } + 1
        val h = piece.cells.maxOf { it.second } + 1
        return w to h
    }

    // -------------------------------------------------------------------- touch

    override fun touch(phase: ArcadeTouch, x: Float, y: Float, width: Float, height: Float): Boolean {
        if (done()) return false
        if (width > 0f && height > 0f && (width != lastWidth || height != lastHeight)) layout(width, height)
        if (trayRects.isEmpty()) layout(width, height)
        when (phase) {
            ArcadeTouch.DOWN -> {
                val index = trayRects.indexOfFirst { it.contains(x, y) }
                if (index < 0 || tray[index] == null) return false
                dragIndex = index
                val piece = tray[index]!!
                val (pw, ph) = pieceBounds(piece)
                grabX = pieceScale * pw / 2f + min(width * 0.03f, cell)
                grabY = pieceScale * ph / 2f + min(height * 0.03f, cell)
                dragFx = x
                dragFy = y
                return true
            }
            ArcadeTouch.MOVE -> {
                if (dragIndex < 0) return false
                dragFx = x
                dragFy = y
                return true
            }
            ArcadeTouch.UP -> {
                if (dragIndex < 0) return false
                val piece = tray[dragIndex] ?: return false
                val (pw, _) = pieceBounds(piece)
                val anchorX = x - grabX + pieceScale * pw / 2f
                val anchorY = y - grabY + pieceScale * 2f / 2f
                val col = ((anchorX - gridRect.left - pieceScale / 2f) / cell).roundToInt()
                val row = ((anchorY - gridRect.top - pieceScale / 2f) / cell).roundToInt()
                if (canPlace(piece, col, row)) {
                    place(piece, col, row)
                    tray[dragIndex] = null
                    dragIndex = -1
                    if (tray.all { it == null }) refill()
                    if (!anyMove()) {
                        settle()
                    }
                } else {
                    badDrop = 1f
                    dragIndex = -1
                }
                return true
            }
        }
        return false
    }

    private fun canPlace(piece: Piece, col: Int, row: Int): Boolean {
        for ((dx, dy) in piece.cells) {
            val c = col + dx
            val r = row + dy
            if (c < 0 || r < 0 || c >= size || r >= size) return false
            if (grid[r * size + c] != 0) return false
        }
        return true
    }

    private fun place(piece: Piece, col: Int, row: Int) {
        for ((dx, dy) in piece.cells) {
            grid[(row + dy) * size + (col + dx)] = piece.color
        }
        placed += piece.cells.size
        points += piece.cells.size * 4
        val fullRows = (0 until size).filter { r -> (0 until size).all { c -> grid[r * size + c] != 0 } }
        val fullCols = (0 until size).filter { c -> (0 until size).all { r -> grid[r * size + c] != 0 } }
        val lines = fullRows.size + fullCols.size
        if (lines > 0) {
            fullRows.forEach { r -> for (c in 0 until size) grid[r * size + c] = 0 }
            fullCols.forEach { c -> for (r in 0 until size) grid[r * size + c] = 0 }
            cleared += lines
            points += lines * lines * 55
        }
    }

    private fun anyMove(): Boolean {
        for (i in 0 until 3) {
            val piece = tray[i] ?: continue
            for (r in 0 until size) for (c in 0 until size) {
                if (canPlace(piece, c, r)) return true
            }
        }
        return false
    }

    private fun settle() {
        val goal = target.toInt()
        val won = points >= goal
        val stars = when {
            points >= (goal * 1.35f).toInt() -> 3
            won -> 2
            points >= goal * 0.65f -> 1
            else -> 1
        }
        finish(
            won = won,
            headline = if (won) "Target cleared" else "No room left",
            detail = "You scored $points of $goal, placed $placed tiles and cleared $cleared lines. " +
                if (won) "The next level's tray gets tighter." else "Leave a corner free for the long bars — they are what ends a run.",
            score = points.toLong(),
            stars = stars
        )
    }

    override fun update(dt: Float) {
        super.update(dt)
        if (badDrop > 0f) badDrop = (badDrop - dt * 3f).coerceAtLeast(0f)
    }

    override fun score(): Long = points.toLong()

    override fun status(): String {
        if (done()) return "Score $points"
        val left = target.toInt() - points
        return "Score $points / ${target.toInt()} · ${if (left > 0) "$left to go" else "target reached"} · $cleared lines"
    }

    override fun hint(): String =
        if (placed == 0) "Drag a piece from the tray onto the grid." else ""

    override fun draw(canvas: Canvas, width: Float, height: Float) {
        layout(width, height)
        val bg = ArcadeDraw.paint(0xFF1B1630.toInt())
        canvas.drawRect(0f, 0f, width, height, bg)
        val glow = ArcadeDraw.paint(Color.TRANSPARENT)
        glow.shader = android.graphics.RadialGradient(width * 0.5f, height * 0.3f, width, intArrayOf(0x3345F5C8, 0x00000000), null, android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width, height, glow)

        ArcadeDraw.text(canvas, "TARGET", width * 0.06f, height * 0.085f, height * 0.014f, 0x88FFFFFF.toInt(), bold = true)
        ArcadeDraw.text(canvas, "${target.toInt()}", width * 0.06f, height * 0.115f, height * 0.03f, Color.WHITE, bold = true)
        ArcadeDraw.text(canvas, "SCORE", width * 0.94f, height * 0.085f, height * 0.014f, 0x88FFFFFF.toInt(), bold = true, align = Paint.Align.RIGHT)
        ArcadeDraw.text(canvas, points.toString(), width * 0.94f, height * 0.115f, height * 0.03f, ArcadeColors.GOLD, bold = true, align = Paint.Align.RIGHT)
        val fraction = (points / target.toFloat()).coerceIn(0f, 1f)
        ArcadeDraw.panel(canvas, width * 0.06f, height * 0.128f, width * 0.94f, height * 0.134f, 0x33FFFFFF, height * 0.003f)
        ArcadeDraw.panel(canvas, width * 0.06f, height * 0.128f, width * 0.06f + width * 0.88f * fraction, height * 0.134f,
            ArcadeColors.MINT, height * 0.003f)

        // Board.
        ArcadeDraw.panel(canvas, gridRect.left - cell * 0.06f, gridRect.top - cell * 0.06f,
            gridRect.right + cell * 0.06f, gridRect.bottom + cell * 0.06f, 0xFF2A2342.toInt(), cell * 0.18f)
        for (r in 0 until size) for (c in 0 until size) {
            val l = gridRect.left + c * cell
            val t = gridRect.top + r * cell
            val value = grid[r * size + c]
            if (value == 0) {
                ArcadeDraw.panel(canvas, l + cell * 0.06f, t + cell * 0.06f, l + cell * 0.94f, t + cell * 0.94f,
                    0x0DFFFFFF, cell * 0.16f)
            } else {
                ArcadeDraw.panel(canvas, l + cell * 0.06f, t + cell * 0.06f, l + cell * 0.94f, t + cell * 0.94f,
                    value, cell * 0.16f)
                ArcadeDraw.panel(canvas, l + cell * 0.16f, t + cell * 0.14f, l + cell * 0.84f, t + cell * 0.3f,
                    0x33FFFFFF, cell * 0.08f)
            }
        }

        // Ghost drop position while dragging.
        if (dragIndex >= 0) {
            val piece = tray[dragIndex]
            if (piece != null) {
                val (pw, _) = pieceBounds(piece)
                val anchorX = dragFx - grabX + pieceScale * pw / 2f
                val anchorY = dragFy - grabY + pieceScale / 2f
                val col = ((anchorX - gridRect.left - pieceScale / 2f) / cell).roundToInt()
                val row = ((anchorY - gridRect.top - pieceScale / 2f) / cell).roundToInt()
                val valid = canPlace(piece, col, row)
                for ((dx, dy) in piece.cells) {
                    val c = col + dx
                    val r = row + dy
                    if (c in 0 until size && r in 0 until size) {
                        val l = gridRect.left + c * cell
                        val t = gridRect.top + r * cell
                        ArcadeDraw.panel(canvas, l + cell * 0.08f, t + cell * 0.08f, l + cell * 0.92f, t + cell * 0.92f,
                            if (valid) piece.color else ArcadeColors.CORAL, cell * 0.16f,
                            Color.WHITE, cell * 0.04f)
                    }
                }
            }
        }

        // Tray.
        tray.forEachIndexed { index, piece ->
            val rect = trayRects.getOrNull(index) ?: return@forEachIndexed
            val active = index == dragIndex
            ArcadeDraw.panel(canvas, rect, if (active) 0x22FFFFFF else 0x14FFFFFF, rect.height() * 0.2f,
                if (badDrop > 0f) ArcadeColors.CORAL else 0x22FFFFFF, 2f)
            if (piece == null || active) return@forEachIndexed
            drawPiece(canvas, piece, rect.centerX(), rect.centerY(), pieceScale)
        }
        if (dragIndex >= 0) {
            val piece = tray[dragIndex]
            if (piece != null) {
                val (pw, ph) = pieceBounds(piece)
                drawPiece(canvas, piece, dragFx - grabX + pieceScale * pw / 2f, dragFy - grabY + pieceScale * ph / 2f, pieceScale, anchorTopLeft = true)
            }
        }
    }

    private fun drawPiece(canvas: Canvas, piece: Piece, cx: Float, cy: Float, scale: Float, anchorTopLeft: Boolean = false) {
        val (pw, ph) = pieceBounds(piece)
        val originX = if (anchorTopLeft) cx else cx - pw * scale / 2f
        val originY = if (anchorTopLeft) cy else cy - ph * scale / 2f
        for ((dx, dy) in piece.cells) {
            val l = originX + dx * scale
            val t = originY + dy * scale
            ArcadeDraw.panel(canvas, l + scale * 0.07f, t + scale * 0.07f, l + scale * 0.93f, t + scale * 0.93f,
                piece.color, scale * 0.2f)
            ArcadeDraw.panel(canvas, l + scale * 0.18f, t + scale * 0.14f, l + scale * 0.82f, t + scale * 0.28f,
                0x33FFFFFF, scale * 0.09f)
        }
    }
}
