package com.oea.games.allinone.games

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import com.oea.games.allinone.ArcadeBaseGame
import com.oea.games.allinone.ArcadeColors
import com.oea.games.allinone.ArcadeDraw
import com.oea.games.allinone.ArcadeTouch
import com.oea.games.allinone.ArcadePlayMode
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Tap Match: the memory cabinet.
 *
 * Levels add pairs rather than changing the rules — the quickest, clearest way
 * to make a memory game harder. Difficulty shortens the clock and the pause in
 * which a wrong pair stays visible. In two-player mode a pair keeps the turn, so
 * a good memory can take the whole board, exactly like the card game it comes
 * from.
 */
class MemoryGame : ArcadeBaseGame() {

    private data class Tile(val symbol: Int, var up: Boolean = false, var matched: Boolean = false)

    private val glyphs = listOf("★", "♥", "●", "▲", "◆", "✦", "■", "☀", "☘", "✚", "✿", "☂", "♫", "⚑", "✈", "☯")

    private var tiles = ArrayList<Tile>()
    private var cols = 4
    private var rows = 2
    private var pairs = 4
    private var moves = 0
    private var found = 0
    private var turn = 0
    private val scores = intArrayOf(0, 0)
    private var first = -1
    private var second = -1
    private var revealPause = 0f
    private var timeLeft = 0f
    private var timed = true
    private val cells = ArrayList<RectF>()

    override fun onStart() {
        val wanted = (4 + config.level - 1).coerceAtMost(15)
        cols = if (wanted * 2 > 16) 5 else 4
        rows = ceil(wanted * 2f / cols).toInt()
        if ((cols * rows) % 2 != 0) rows += 1
        pairs = cols * rows / 2
        val deck = ArrayList<Int>()
        for (i in 0 until pairs) {
            deck += i
            deck += i
        }
        deck.shuffle(random)
        tiles = ArrayList(deck.map { Tile(it % glyphs.size) })
        moves = 0
        found = 0
        turn = 0
        scores[0] = 0
        scores[1] = 0
        first = -1
        second = -1
        revealPause = 0f
        timed = config.mode != ArcadePlayMode.TWO_PLAYER
        timeLeft = (105f - config.level * 3f).coerceAtLeast(40f) * (1.15f - skill * 0.35f)
        cells.clear()
    }

    override fun update(dt: Float) {
        super.update(dt)
        if (done()) return
        if (timed) {
            timeLeft = max(0f, timeLeft - dt)
            if (timeLeft <= 0f) {
                finish(
                    won = false,
                    headline = "Time's up",
                    detail = "You paired ${found} of $pairs sets. Fewer pairs per board, or a calmer difficulty, gives you longer.",
                    score = found * 90L
                )
                return
            }
        }
        if (revealPause > 0f) {
            revealPause -= dt
            if (revealPause <= 0f && first >= 0 && second >= 0) {
                val a = tiles[first]
                val b = tiles[second]
                if (!a.matched) a.up = false
                if (!b.matched) b.up = false
                first = -1
                second = -1
                turn = 1 - turn
            }
        }
    }

    override fun touch(phase: ArcadeTouch, x: Float, y: Float, width: Float, height: Float): Boolean {
        if (done() || phase != ArcadeTouch.DOWN || revealPause > 0f) return false
        val index = cells.indexOfFirst { ArcadeDraw.inside(it, x, y) }
        if (index < 0) return false
        val tile = tiles.getOrNull(index) ?: return false
        if (tile.up || tile.matched) return false
        tile.up = true
        if (first < 0) {
            first = index
            return true
        }
        second = index
        moves++
        val a = tiles[first]
        val b = tiles[second]
        if (a.symbol == b.symbol) {
            a.matched = true
            b.matched = true
            found++
            scores[turn]++
            first = -1
            second = -1
            if (found >= pairs) settle()
        } else {
            revealPause = (0.75f - skill * 0.28f).coerceAtLeast(0.32f)
        }
        return true
    }

    private fun settle() {
        val twoPlayer = config.mode == ArcadePlayMode.TWO_PLAYER
        val score = if (twoPlayer) scores[turn] * 120L else (found * 100 - moves * 4 + timeLeft.toInt()).toLong()
        val won = if (twoPlayer) scores[turn] > scores[1 - turn] else true
        val stars = when {
            twoPlayer -> if (won) 3 else 1
            moves <= (pairs * 1.4f).toInt() -> 3
            moves <= pairs * 2 -> 2
            else -> 1
        }
        finish(
            won = won,
            headline = if (twoPlayer) {
                if (scores[0] == scores[1]) "It's a draw" else "Player ${turn + 1} wins"
            } else "Every pair found",
            detail = if (twoPlayer) {
                "Pairs ${scores[0]} — ${scores[1]}. A pair keeps the turn, so keep watching where the cards were."
            } else {
                "$moves moves for $pairs pairs, with ${timeLeft.toInt()}s to spare."
            },
            score = score,
            stars = stars
        )
    }

    override fun status(): String {
        if (done()) return "Paired $found of $pairs"
        if (config.mode == ArcadePlayMode.TWO_PLAYER) {
            return "Player ${turn + 1}'s turn · ${scores[0]} — ${scores[1]} · pairs $found/$pairs"
        }
        val minutes = (timeLeft / 60).toInt()
        val seconds = (timeLeft % 60).toInt()
        return "Moves $moves · pairs $found/$pairs · ${minutes}:${seconds.toString().padStart(2, '0')}"
    }

    override fun hint(): String =
        if (moves < 3) "Tap two tiles. A pair stays open; a miss turns back over." else ""

    override fun draw(canvas: Canvas, width: Float, height: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = android.graphics.LinearGradient(0f, 0f, width, height,
            intArrayOf(0xFF1D2C6E.toInt(), 0xFF2F7FB8.toInt()), null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width, height, p)
        p.shader = null

        val top = height * 0.14f
        val bottom = height * 0.84f
        val side = width * 0.055f
        val areaW = width - side * 2
        val areaH = bottom - top
        val gap = min(areaW, areaH) * 0.022f
        val cellW = (areaW - gap * (cols - 1)) / cols
        val cellH = (areaH - gap * (rows - 1)) / rows
        val cell = min(cellW, cellH)
        val gridW = cell * cols + gap * (cols - 1)
        val gridH = cell * rows + gap * (rows - 1)
        val left = (width - gridW) / 2f
        val gridTop = top + (areaH - gridH) / 2f

        cells.clear()
        for (i in tiles.indices) {
            val c = i % cols
            val r = i / cols
            val rect = RectF(left + c * (cell + gap), gridTop + r * (cell + gap),
                left + c * (cell + gap) + cell, gridTop + r * (cell + gap) + cell)
            cells += rect
            val tile = tiles[i]
            when {
                tile.matched -> {
                    ArcadeDraw.panel(canvas, rect, 0xFFE6FBF1.toInt(), cell * 0.16f)
                    ArcadeDraw.text(canvas, glyphs[tile.symbol], rect.centerX(), rect.centerY() + cell * 0.14f,
                        cell * 0.42f, 0xFF2E9E76.toInt(), bold = true, align = Paint.Align.CENTER)
                }
                tile.up -> {
                    ArcadeDraw.panel(canvas, rect, ArcadeColors.WHITE, cell * 0.16f)
                    val ink = intArrayOf(ArcadeColors.PINK, ArcadeColors.CORAL, ArcadeColors.BLUE, ArcadeColors.PLUM)[tile.symbol % 4]
                    ArcadeDraw.text(canvas, glyphs[tile.symbol], rect.centerX(), rect.centerY() + cell * 0.15f,
                        cell * 0.44f, ink, bold = true, align = Paint.Align.CENTER)
                }
                else -> {
                    ArcadeDraw.panel(canvas, rect, 0xFF203A8C.toInt(), cell * 0.16f, 0x33FFFFFF, cell * 0.02f)
                    // A small chevron pattern, so a face-down tile looks like a card back.
                    val mark = Path().apply {
                        moveTo(rect.centerX() - cell * 0.16f, rect.centerY() - cell * 0.1f)
                        lineTo(rect.centerX(), rect.centerY() + cell * 0.08f)
                        lineTo(rect.centerX() + cell * 0.16f, rect.centerY() - cell * 0.1f)
                    }
                    canvas.drawPath(mark, ArcadeDraw.paint(0x55FFFFFF, cell * 0.035f))
                    ArcadeDraw.text(canvas, "?", rect.centerX(), rect.centerY() + cell * 0.16f, cell * 0.34f,
                        0x66FFFFFF, bold = true, align = Paint.Align.CENTER)
                }
            }
        }

        if (timed) {
            val fraction = (timeLeft / (105f - config.level * 3f).coerceAtLeast(40f)).coerceIn(0f, 1f)
            val rail = RectF(width * 0.08f, height * 0.905f, width * 0.92f, height * 0.925f)
            ArcadeDraw.panel(canvas, rail, 0x33000000, rail.height() / 2f)
            ArcadeDraw.panel(canvas, RectF(rail.left, rail.top, rail.left + rail.width() * fraction, rail.bottom),
                if (fraction > 0.3f) ArcadeColors.MINT else ArcadeColors.CORAL, rail.height() / 2f)
            ArcadeDraw.text(canvas, "TAP MATCH", width * 0.08f, height * 0.96f, height * 0.016f, 0x99FFFFFF.toInt(), bold = true)
            ArcadeDraw.text(canvas, "${timeLeft.toInt()}s", width * 0.92f, height * 0.96f, height * 0.018f, Color.WHITE, bold = true, align = Paint.Align.RIGHT)
        }
        if (config.mode == ArcadePlayMode.TWO_PLAYER) {
            val chipW = width * 0.3f
            for (i in 0..1) {
                val active = i == turn
                val rect = RectF(width * 0.08f + i * (chipW + width * 0.04f), height * 0.895f,
                    width * 0.08f + i * (chipW + width * 0.04f) + chipW, height * 0.935f)
                ArcadeDraw.panel(canvas, rect, if (active) ArcadeColors.WHITE else 0x33FFFFFF, rect.height() / 2f)
                ArcadeDraw.text(canvas, "P${i + 1} · ${scores[i]}", rect.centerX(), rect.centerY() + rect.height() * 0.2f,
                    rect.height() * 0.42f, if (active) ArcadeColors.INK else Color.WHITE, bold = true, align = Paint.Align.CENTER)
            }
        }
    }

    private fun <T> MutableList<T>.shuffle(rng: kotlin.random.Random) {
        for (i in size - 1 downTo 1) {
            val j = rng.nextInt(i + 1)
            val tmp = this[i]
            this[i] = this[j]
            this[j] = tmp
        }
    }
}
