package com.oea.games.allinone.games

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.oea.games.allinone.ArcadeBaseGame
import com.oea.games.allinone.ArcadeColors
import com.oea.games.allinone.ArcadeDraw
import com.oea.games.allinone.ArcadeTouch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Word Finder: a word-search cabinet with real words and a real clock.
 *
 * Each level has its own shelf of words — home, animals, market, streets, weather,
 * travel, work — so the game teaches a little vocabulary instead of filling the
 * grid with noise. Diagonals arrive at level three and backwards words at level
 * five; later levels make the grid bigger and give you less time. Every board is
 * generated from the level's seed, so a networked challenge sees the same grid on
 * both phones.
 */
class WordFinderGame : ArcadeBaseGame() {

    private val themes: List<List<String>> = listOf(
        listOf("HOME", "DOOR", "ROOF", "BED", "CUP", "LAMP", "TABLE", "CHAIR", "FLOOR", "GLASS"),
        listOf("CAT", "DOG", "GOAT", "FISH", "BIRD", "LION", "DUCK", "HORSE", "MOUSE", "SNAKE"),
        listOf("RICE", "BEAN", "YAM", "SOUP", "BREAD", "SALT", "PEPPER", "FISH", "MILK", "FRUIT"),
        listOf("ROAD", "BUS", "TAXI", "MARKET", "SHOP", "BANK", "STREET", "BRIDGE", "PARK", "HOTEL"),
        listOf("RAIN", "SUN", "WIND", "CLOUD", "STORM", "FOG", "HEAT", "COLD", "HARM", "DUST"),
        listOf("HAND", "HEAD", "EYE", "EAR", "NOSE", "MOUTH", "ARM", "LEG", "FOOT", "HEART"),
        listOf("TRAIN", "PLANE", "BOAT", "TICKET", "BAG", "MAP", "ROUTE", "HARBOUR", "AIRPORT", "TRIP"),
        listOf("BOOK", "PEN", "DESK", "CLASS", "TEACHER", "STUDENT", "LESSON", "EXAM", "SCORE", "RULE"),
        listOf("WORK", "TEAM", "BOSS", "SALARY", "OFFICE", "MEETING", "EMAIL", "DEADLINE", "REPORT", "SKILL"),
        listOf("OEARCADE", "VARIETY", "PUZZLE", "MEMORY", "SPORT", "TACTIC", "VICTORY", "CHAMPION", "LEVEL", "STARS"),
        listOf("COURAGE", "PATIENCE", "MANNERS", "RESPECT", "HONEST", "KINDNESS", "LISTEN", "PROMISE", "THANKS", "WELCOME"),
        listOf("MASTER", "GENIUS", "LEGEND", "HORIZON", "JOURNEY", "FREEDOM", "PURPOSE", "BALANCE", "GROWTH", "WONDER"),
    )

    private var gridSize = 6
    private var letters = CharArray(36)
    private var words = ArrayList<String>()
    private var found = HashSet<String>()
    private var foundPaths = ArrayList<List<Int>>()
    private var timeLeft = 120f
    private var mistakes = 0
    private var startCell = -1
    private var endCell = -1
    private var cells = ArrayList<RectF>()
    private var wordRects = ArrayList<RectF>()
    private var dragging = false

    override fun onStart() {
        gridSize = (6 + (config.level - 1) / 3).coerceAtMost(10)
        letters = CharArray(gridSize * gridSize)
        words = ArrayList()
        found = HashSet()
        foundPaths = ArrayList()
        mistakes = 0
        startCell = -1
        endCell = -1
        dragging = false
        timeLeft = (160f - config.level * 5f).coerceAtLeast(65f) * (1.1f - skill * 0.25f)
        build()
    }

    private fun build() {
        val theme = themes[(config.level - 1) % themes.size]
        val want = (4 + config.level / 2).coerceIn(4, 8)
        val candidates = theme.shuffled(random).filter { it.length <= gridSize }
        val chosen = ArrayList<String>()
        val allowed = ArrayList<IntArray>()
        for (dr in -1..1) for (dc in -1..1) {
            if (dr == 0 && dc == 0) continue
            val diagonal = dr != 0 && dc != 0
            if (diagonal && config.level < 3) continue
            if (dr < 0 || (dr == 0 && dc < 0)) if (config.level < 5) continue
            allowed += intArrayOf(dr, dc)
        }
        for (word in candidates) {
            if (chosen.size >= want) break
            if (tryPlace(word, allowed)) chosen += word
        }
        // Fallback: always place at least three words by shortening the list.
        if (chosen.size < 3) {
            for (word in candidates) {
                if (chosen.size >= 3) break
                if (word in chosen) continue
                if (tryPlace(word, allowed)) chosen += word
            }
        }
        words = chosen
        val pool = "ABCDEFGHIJKLMNOPRSTUVWY"
        for (i in letters.indices) if (letters[i] == '\u0000') letters[i] = pool[random.nextInt(pool.length)]
    }

    private fun tryPlace(word: String, allowed: List<IntArray>): Boolean {
        repeat(90) {
            val direction = allowed[random.nextInt(allowed.size)]
            val dr = direction[0]
            val dc = direction[1]
            val span = word.length - 1
            val rowRange = if (dr >= 0) 0..(gridSize - 1 - span * dr) else (span * -dr)..(gridSize - 1)
            val colRange = if (dc >= 0) 0..(gridSize - 1 - span * dc) else (span * -dc)..(gridSize - 1)
            if (rowRange.isEmpty() || colRange.isEmpty()) return@repeat
            val row = rowRange.random(random)
            val col = colRange.random(random)
            val indices = ArrayList<Int>()
            var ok = true
            for (i in word.indices) {
                val r = row + dr * i
                val c = col + dc * i
                val index = r * gridSize + c
                val existing = letters[index]
                if (existing != '\u0000' && existing != word[i]) {
                    ok = false
                    break
                }
                indices += index
            }
            if (!ok) return@repeat
            word.forEachIndexed { i, ch -> letters[indices[i]] = ch }
            return true
        }
        return false
    }

    // ------------------------------------------------------------------- touch

    override fun touch(phase: ArcadeTouch, x: Float, y: Float, width: Float, height: Float): Boolean {
        if (done()) return false
        if (cells.isEmpty()) return false
        val index = cells.indexOfFirst { it.contains(x, y) }
        when (phase) {
            ArcadeTouch.DOWN -> {
                if (index < 0) return false
                dragging = true
                startCell = index
                endCell = index
                return true
            }
            ArcadeTouch.MOVE -> {
                if (!dragging || index < 0) return false
                endCell = index
                return true
            }
            ArcadeTouch.UP -> {
                if (!dragging) return false
                dragging = false
                if (startCell >= 0 && endCell >= 0) checkSelection()
                startCell = -1
                endCell = -1
                return true
            }
        }
        return false
    }

    private fun path(start: Int, end: Int): List<Int>? {
        val r1 = start / gridSize
        val c1 = start % gridSize
        val r2 = end / gridSize
        val c2 = end % gridSize
        val dr = r2 - r1
        val dc = c2 - c1
        if (dr != 0 && dc != 0 && abs(dr) != abs(dc)) return null
        val steps = max(abs(dr), abs(dc))
        if (steps == 0) return listOf(start)
        val sr = dr / steps
        val sc = dc / steps
        val list = ArrayList<Int>()
        for (i in 0..steps) list += (r1 + sr * i) * gridSize + (c1 + sc * i)
        return list
    }

    private fun checkSelection() {
        val cellsInLine = path(startCell, endCell) ?: return
        if (cellsInLine.size < 3) return
        val text = cellsInLine.joinToString("") { letters[it].toString() }
        val reversed = text.reversed()
        val hit = words.firstOrNull { it == text || it == reversed }
        if (hit == null) {
            mistakes++
            return
        }
        if (hit in found) return
        found += hit
        foundPaths += cellsInLine
        if (found.size == words.size) settle()
    }

    private fun settle() {
        val seconds = timeLeft
        val score = (words.size * 150 + seconds.toInt() * 2 - mistakes * 20).coerceAtLeast(1)
        val stars = when {
            seconds > timeLeftTotal * 0.45f && mistakes == 0 -> 3
            seconds > timeLeftTotal * 0.18f -> 2
            else -> 1
        }
        finish(
            won = true,
            headline = "Every word found",
            detail = "${words.size} words with ${seconds.toInt()}s left and $mistakes wrong swipe${if (mistakes == 1) "" else "s"}. " +
                "The next level's shelf is longer.",
            score = score.toLong(),
            stars = stars
        )
    }

    private val timeLeftTotal: Float
        get() = (160f - config.level * 5f).coerceAtLeast(65f) * (1.1f - skill * 0.25f)

    override fun update(dt: Float) {
        super.update(dt)
        if (done()) return
        timeLeft = max(0f, timeLeft - dt)
        if (timeLeft <= 0f) {
            finish(
                won = false,
                headline = "The clock beat you",
                detail = "You found ${found.size} of ${words.size} words. Look for the first letter last — start from the unusual letters in the list.",
                score = found.size * 100L,
                stars = 1
            )
        }
    }

    override fun score(): Long = (found.size * 150L + timeLeft.toInt())

    override fun status(): String {
        if (done()) return "Words ${found.size}/${words.size}"
        val minutes = (timeLeft / 60).toInt()
        val seconds = (timeLeft % 60).toInt()
        return "${found.size}/${words.size} words · $mistakes missed · ${minutes}:${seconds.toString().padStart(2, '0')}"
    }

    override fun hint(): String =
        if (found.isEmpty() && config.level == 1) "Swipe across a whole word in one straight line." else ""

    override fun draw(canvas: Canvas, width: Float, height: Float) {
        canvas.drawColor(0xFF1B3A9E.toInt())
        val glow = ArcadeDraw.paint(Color.TRANSPARENT)
        glow.shader = android.graphics.RadialGradient(width * 0.8f, height * 0.1f, width * 1.1f,
            intArrayOf(0x553ED0E8, 0x001B3A9E), null, android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width, height, glow)

        // Word list: chips that wrap over the top of the grid.
        val listTop = height * 0.085f
        var chipX = width * 0.05f
        var chipY = listTop
        val chipH = height * 0.036f
        val chipText = height * 0.017f
        wordRects.clear()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = chipText
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
        }
        for (word in words) {
            val chipW = paint.measureText(word) + width * 0.06f
            if (chipX + chipW > width * 0.95f) {
                chipX = width * 0.05f
                chipY += chipH + height * 0.008f
            }
            val rect = RectF(chipX, chipY, chipX + chipW, chipY + chipH)
            wordRects += rect
            val done = word in found
            ArcadeDraw.panel(canvas, rect, if (done) 0xFF3ED6A2.toInt() else 0x33FFFFFF, chipH / 2f)
            val p = ArcadeDraw.paint(if (done) 0xFF083B2B.toInt() else Color.WHITE)
            p.textSize = chipText
            p.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            if (done) p.flags = p.flags or Paint.STRIKE_THRU_TEXT_FLAG
            canvas.drawText(word, rect.centerX(), rect.centerY() + chipText * 0.35f, p)
            paint.textAlign = Paint.Align.LEFT
            chipX += chipW + width * 0.012f
        }

        val gridTop = chipY + chipH + height * 0.03f
        val available = height * 0.86f - gridTop
        val boardSize = min(width * 0.88f, available)
        val left = (width - boardSize) / 2f
        val cell = boardSize / gridSize
        cells.clear()
        for (i in letters.indices) {
            val r = i / gridSize
            val c = i % gridSize
            cells += RectF(left + c * cell, gridTop + r * cell, left + (c + 1) * cell, gridTop + (r + 1) * cell)
        }

        // Found words keep a marker on their cells.
        val foundCells = HashSet<Int>()
        foundPaths.forEach { foundCells.addAll(it) }
        val selected = if (dragging) path(startCell.coerceAtLeast(0), endCell.coerceAtLeast(0)) else null
        ArcadeDraw.panel(canvas, left - cell * 0.05f, gridTop - cell * 0.05f, left + boardSize + cell * 0.05f,
            gridTop + boardSize + cell * 0.05f, 0x22000000, cell * 0.12f)
        for (i in letters.indices) {
            val rect = cells[i]
            val inFound = i in foundCells
            val inSelection = selected?.contains(i) == true
            when {
                inSelection -> ArcadeDraw.panel(canvas, rect, ArcadeColors.CORAL, cell * 0.14f)
                inFound -> ArcadeDraw.panel(canvas, rect, 0x553ED6A2, cell * 0.14f)
                else -> ArcadeDraw.panel(canvas, rect, 0x1AFFFFFF, cell * 0.14f)
            }
            ArcadeDraw.text(canvas, letters[i].toString(), rect.centerX(), rect.centerY() + cell * 0.2f, cell * 0.48f,
                Color.WHITE, bold = true, align = Paint.Align.CENTER)
        }

        // Timer.
        val rail = RectF(width * 0.06f, height * 0.93f, width * 0.94f, height * 0.945f)
        ArcadeDraw.panel(canvas, rail, 0x33FFFFFF, rail.height() / 2f)
        val fraction = (timeLeft / timeLeftTotal).coerceIn(0f, 1f)
        ArcadeDraw.panel(canvas, RectF(rail.left, rail.top, rail.left + rail.width() * fraction, rail.bottom),
            if (fraction > 0.3f) ArcadeColors.GOLD else ArcadeColors.CORAL, rail.height() / 2f)
        ArcadeDraw.text(canvas, "${timeLeft.toInt()}s", width * 0.5f, height * 0.975f, height * 0.016f,
            0xCCFFFFFF.toInt(), bold = true, align = Paint.Align.CENTER)
    }
}
