package com.oea.games.allinone.games

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.oea.games.allinone.ArcadeBaseGame
import com.oea.games.allinone.ArcadeColors
import com.oea.games.allinone.ArcadeDraw
import com.oea.games.allinone.ArcadeTouch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Hide & Seek: a dark house, three keys, and something that heard you.
 *
 * The maze is generated fresh from the level's seed with a real backtracker, so
 * it always has exactly one route between any two points. You can only see inside
 * the torch. Watchers move on their own beat: if they can hear or see you they
 * take the shortest path, otherwise they wander. Three keys open the exit.
 *
 * The house is drawn as floor and shadow rather than walls on walls — at torch
 * distance that is what a corridor looks like, and it keeps the screen readable.
 */
class HideSeekGame : ArcadeBaseGame() {

    private data class Watcher(var x: Int, var y: Int, var timer: Float = 0f, var alert: Float = 0f)

    private var size = 11
    private var open = BooleanArray(121)
    private var playerX = 1
    private var playerY = 1
    private var exitX = 1
    private var exitY = 1
    private val keys = ArrayList<Pair<Int, Int>>()
    private var keysFound = 0
    private var lives = 3
    private var watchers = ArrayList<Watcher>()
    private var moveCooldown = 0f
    private var caughtFlash = 0f
    private var gameOver = false
    private var dpad = ArrayList<Pair<RectF, Int>>()
    private var torchCells = 3.4f
    private var cellSize = 1f
    private var boardLeft = 0f
    private var boardTop = 0f

    override fun onStart() {
        size = (9 + config.level).let { if (it % 2 == 0) it + 1 else it }.coerceAtMost(19)
        open = BooleanArray(size * size)
        carve()
        playerX = 1
        playerY = 1
        keys.clear()
        keysFound = 0
        lives = 3
        moveCooldown = 0f
        caughtFlash = 0f
        gameOver = false
        torchCells = (4.2f - config.level * 0.12f).coerceAtLeast(2.2f)
        placeExitAndKeys()
        watchers = ArrayList()
        val count = (1 + config.level / 3).coerceAtMost(3)
        val spots = openCells().filter { hypot((it.first - playerX).toFloat(), (it.second - playerY).toFloat()) > size * 0.4f }
        repeat(count) {
            val spot = spots.random(random)
            watchers += Watcher(spot.first, spot.second)
        }
    }

    private fun index(x: Int, y: Int) = y * size + x
    private fun isOpen(x: Int, y: Int) = x in 0 until size && y in 0 until size && open[index(x, y)]

    private fun carve() {
        val stack = ArrayDeque<Pair<Int, Int>>()
        open[index(1, 1)] = true
        stack.addLast(1 to 1)
        while (stack.isNotEmpty()) {
            val (x, y) = stack.last()
            val options = ArrayList<Pair<Int, Int>>()
            for ((dx, dy) in listOf(2 to 0, -2 to 0, 0 to 2, 0 to -2)) {
                val nx = x + dx
                val ny = y + dy
                if (nx in 1 until size - 1 && ny in 1 until size - 1 && !open[index(nx, ny)]) options += nx to ny
            }
            if (options.isEmpty()) {
                stack.removeLast()
                continue
            }
            val (nx, ny) = options.random(random)
            open[index((x + nx) / 2, (y + ny) / 2)] = true
            open[index(nx, ny)] = true
            stack.addLast(nx to ny)
        }
    }

    private fun openCells(): List<Pair<Int, Int>> =
        (1 until size - 1).flatMap { y -> (1 until size - 1).map { x -> x to y } }.filter { isOpen(it.first, it.second) }

    private fun placeExitAndKeys() {
        val distances = bfs(playerX, playerY)
        val farthest = distances.maxOf { it }
        val exitIndex = distances.indexOfFirst { it == farthest }
        exitX = exitIndex % size
        exitY = exitIndex / size
        val candidates = openCells().filter { cell ->
            val d = distances[index(cell.first, cell.second)]
            d > size && cell != (exitX to exitY)
        }.shuffled(random)
        keys.clear()
        candidates.take(3).forEach { keys += it }
        // If the maze is tight, allow nearer keys rather than none.
        if (keys.size < 3) {
            openCells().filter { it != (1 to 1) && it != (exitX to exitY) }.shuffled(random).take(3 - keys.size).forEach { keys += it }
        }
    }

    private fun bfs(fromX: Int, fromY: Int): IntArray {
        val distances = IntArray(size * size) { -1 }
        if (!isOpen(fromX, fromY)) return distances
        val queue = ArrayDeque<Pair<Int, Int>>()
        distances[index(fromX, fromY)] = 0
        queue.addLast(fromX to fromY)
        while (queue.isNotEmpty()) {
            val (x, y) = queue.removeFirst()
            for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                val nx = x + dx
                val ny = y + dy
                if (!isOpen(nx, ny)) continue
                if (distances[index(nx, ny)] >= 0) continue
                distances[index(nx, ny)] = distances[index(x, y)] + 1
                queue.addLast(nx to ny)
            }
        }
        return distances
    }

    // ------------------------------------------------------------------- input

    override fun touch(phase: ArcadeTouch, x: Float, y: Float, width: Float, height: Float): Boolean {
        if (done() || gameOver || phase != ArcadeTouch.DOWN) return false
        if (moveCooldown > 0f) return false
        val direction = dpad.firstOrNull { ArcadeDraw.inside(it.first, x, y) }?.second
        if (direction != null) {
            step(direction)
            return true
        }
        // Tapping next to the hero also walks, for players who dislike a D-pad.
        if (x > boardLeft && y > boardTop) {
            val col = ((x - boardLeft) / cellSize).toInt()
            val row = ((y - boardTop) / cellSize).toInt()
            val dx = col - playerX
            val dy = row - playerY
            if (abs(dx) + abs(dy) == 1) {
                step(if (abs(dx) == 1) (if (dx > 0) 3 else 2) else (if (dy > 0) 1 else 0))
                return true
            }
        }
        return false
    }

    /** 0 up, 1 down, 2 left, 3 right. */
    private fun step(direction: Int) {
        val (dx, dy) = when (direction) {
            0 -> 0 to -1
            1 -> 0 to 1
            2 -> -1 to 0
            else -> 1 to 0
        }
        val nx = playerX + dx
        val ny = playerY + dy
        moveCooldown = 0.12f
        if (!isOpen(nx, ny)) return
        playerX = nx
        playerY = ny
        keys.firstOrNull { it.first == playerX && it.second == playerY }?.let { key ->
            keys.remove(key)
            keysFound++
        }
        if (playerX == exitX && playerY == exitY && keysFound >= 3) {
            finish(
                won = true,
                headline = "Out of the house",
                detail = "Three keys, ${lives} ${if (lives == 1) "life" else "lives"} left and ${(elapsed).toInt()} seconds inside. " +
                    "The next house is bigger and there are more watchers.",
                score = 400L + lives * 120L + keysFound * 60L,
                stars = when {
                    lives == 3 -> 3
                    lives == 2 -> 2
                    else -> 1
                }
            )
        }
    }

    override fun update(dt: Float) {
        super.update(dt)
        if (done() || gameOver) return
        if (moveCooldown > 0f) moveCooldown -= dt
        if (caughtFlash > 0f) caughtFlash -= dt
        watchers.forEach { watcher ->
            watcher.timer -= dt
            watcher.alert = (watcher.alert - dt).coerceAtLeast(0f)
            if (watcher.timer <= 0f) {
                watcher.timer = (0.62f - config.level * 0.02f).coerceAtLeast(0.3f)
                moveWatcher(watcher)
            }
            if (hypot((watcher.x - playerX).toFloat(), (watcher.y - playerY).toFloat()) < 0.8f && caughtFlash <= 0f) {
                caught()
            }
        }
    }

    private fun moveWatcher(watcher: Watcher) {
        val distance = abs(watcher.x - playerX) + abs(watcher.y - playerY)
        val seesPlayer = distance <= 6 + config.level / 2 && (watcher.alert > 0f || lineOfSight(watcher.x, watcher.y))
        if (seesPlayer) watcher.alert = 1.5f

        if (seesPlayer) {
            // Shortest path toward the player.
            val distances = bfs(playerX, playerY)
            var best: Pair<Int, Int>? = null
            var bestDistance = Int.MAX_VALUE
            for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                val nx = watcher.x + dx
                val ny = watcher.y + dy
                if (!isOpen(nx, ny)) continue
                val d = distances[index(nx, ny)]
                if (d >= 0 && d < bestDistance) {
                    bestDistance = d
                    best = nx to ny
                }
            }
            best?.let {
                watcher.x = it.first
                watcher.y = it.second
            }
        } else {
            val options = ArrayList<Pair<Int, Int>>()
            for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                val nx = watcher.x + dx
                val ny = watcher.y + dy
                if (isOpen(nx, ny)) options += nx to ny
            }
            options.randomOrNull(random)?.let {
                watcher.x = it.first
                watcher.y = it.second
            }
        }
    }

    private fun lineOfSight(x: Int, y: Int): Boolean {
        if (x != playerX && y != playerY) return false
        if (x == playerX) {
            val range = if (y < playerY) y..playerY else playerY..y
            return range.all { isOpen(x, it) }
        }
        val range = if (x < playerX) x..playerX else playerX..x
        return range.all { isOpen(it, y) }
    }

    private fun caught() {
        lives--
        caughtFlash = 2f
        playerX = 1
        playerY = 1
        if (lives <= 0) {
            gameOver = true
            finish(
                won = false,
                headline = "The watchers found you",
                detail = "You had ${keysFound} of 3 keys. " +
                    "Break line of sight before moving again — a watcher only chases what it can see or hear.",
                score = keysFound * 80L,
                stars = 1
            )
        }
    }

    override fun status(): String {
        if (done()) return "House closed"
        return "Keys $keysFound/3 · ${lives} ${if (lives == 1) "life" else "lives"} · ${if (keysFound >= 3) "reach the green door" else "find the keys"}"
    }

    override fun hint(): String = when {
        keysFound == 0 && elapsed < 6f -> "Tap the arrows to move. The torch is all you can see by."
        keysFound >= 3 -> "All three keys — the green door is your way out."
        else -> ""
    }

    override fun score(): Long = keysFound * 80L + lives * 20L

    // -------------------------------------------------------------------- draw

    override fun draw(canvas: Canvas, width: Float, height: Float) {
        canvas.drawColor(0xFF070A12.toInt())
        val boardSize = min(width * 0.94f, height * 0.6f)
        boardLeft = (width - boardSize) / 2f
        boardTop = height * 0.1f
        cellSize = boardSize / size
        val lightX = boardLeft + (playerX + 0.5f) * cellSize
        val lightY = boardTop + (playerY + 0.5f) * cellSize

        // Floor.
        for (y in 0 until size) for (x in 0 until size) {
            if (!isOpen(x, y)) continue
            val l = boardLeft + x * cellSize
            val t = boardTop + y * cellSize
            ArcadeDraw.panel(canvas, l + cellSize * 0.04f, t + cellSize * 0.04f, l + cellSize * 0.96f, t + cellSize * 0.96f,
                0xFF26313F.toInt(), cellSize * 0.1f)
            ArcadeDraw.panel(canvas, l + cellSize * 0.08f, t + cellSize * 0.08f, l + cellSize * 0.92f, t + cellSize * 0.92f,
                0xFF33404F.toInt(), cellSize * 0.08f)
        }

        // Exit and keys.
        val exitL = boardLeft + exitX * cellSize
        val exitT = boardTop + exitY * cellSize
        ArcadeDraw.panel(canvas, exitL + cellSize * 0.1f, exitT + cellSize * 0.1f, exitL + cellSize * 0.9f, exitT + cellSize * 0.9f,
            if (keysFound >= 3) 0xFF43D6A2.toInt() else 0xFF2C4C42.toInt(), cellSize * 0.16f)
        ArcadeDraw.text(canvas, if (keysFound >= 3) "EXIT" else "🔒", exitL + cellSize * 0.5f, exitT + cellSize * 0.66f,
            cellSize * 0.34f, Color.WHITE, bold = true, align = Paint.Align.CENTER)
        keys.forEach { key ->
            val l = boardLeft + key.first * cellSize
            val t = boardTop + key.second * cellSize
            ArcadeDraw.circle(canvas, l + cellSize * 0.5f, t + cellSize * 0.5f, cellSize * 0.28f, 0x55FFD34D)
            ArcadeDraw.text(canvas, "🗝", l + cellSize * 0.5f, t + cellSize * 0.66f, cellSize * 0.4f,
                0xFFFFD34D.toInt(), bold = true, align = Paint.Align.CENTER)
        }

        // Watchers.
        watchers.forEach { watcher ->
            val cx = boardLeft + (watcher.x + 0.5f) * cellSize
            val cy = boardTop + (watcher.y + 0.5f) * cellSize
            ArcadeDraw.circle(canvas, cx, cy, cellSize * 0.36f, 0xFF120E18.toInt())
            ArcadeDraw.circle(canvas, cx - cellSize * 0.11f, cy - cellSize * 0.05f, cellSize * 0.05f,
                if (watcher.alert > 0f) 0xFFFF3B4E.toInt() else 0xFFFF8A5C.toInt())
            ArcadeDraw.circle(canvas, cx + cellSize * 0.11f, cy - cellSize * 0.05f, cellSize * 0.05f,
                if (watcher.alert > 0f) 0xFFFF3B4E.toInt() else 0xFFFF8A5C.toInt())
        }

        // Hero.
        val heroX = boardLeft + (playerX + 0.5f) * cellSize
        val heroY = boardTop + (playerY + 0.72f) * cellSize
        ArcadeDraw.stickman(canvas, heroX, heroY, cellSize * 0.66f, 0xFFEFE4C8.toInt(), pose = if (moveCooldown > 0f) 1 else 0,
            width = cellSize * 0.075f)

        // Darkness with a torch hole. The gradient is the whole lighting model:
        // inside the torch radius the house is visible, beyond it only eyes.
        val darkness = ArcadeDraw.paint(Color.TRANSPARENT)
        darkness.shader = RadialGradient(lightX, lightY, cellSize * torchCells,
            intArrayOf(0x00000000, 0x33000000, 0xF2070A12.toInt()),
            floatArrayOf(0.3f, 0.72f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width, boardTop + boardSize, darkness)

        // HUD.
        ArcadeDraw.panel(canvas, RectF(width * 0.05f, height * 0.035f, width * 0.95f, height * 0.08f), 0x66070A12, height * 0.02f)
        for (i in 0..2) {
            ArcadeDraw.text(canvas, if (i < lives) "♥" else "♡", width * 0.09f + i * width * 0.055f, height * 0.07f,
                height * 0.024f, ArcadeColors.CORAL, bold = true)
        }
        ArcadeDraw.text(canvas, "🗝 $keysFound/3", width * 0.5f, height * 0.068f, height * 0.019f, 0xFFFFD34D.toInt(),
            bold = true, align = Paint.Align.CENTER)
        ArcadeDraw.text(canvas, config.difficulty.label.uppercase(), width * 0.92f, height * 0.068f, height * 0.013f,
            0x88FFFFFF.toInt(), bold = true, align = Paint.Align.RIGHT)

        // D-pad.
        dpad.clear()
        val padSize = min(width * 0.15f, height * 0.08f)
        val padCx = width * 0.5f
        val padCy = height * 0.86f
        val gap = padSize * 1.1f
        val positions = listOf(
            RectF(padCx - padSize / 2f, padCy - gap, padCx + padSize / 2f, padCy - gap + padSize) to 0,
            RectF(padCx - padSize / 2f, padCy + gap - padSize, padCx + padSize / 2f, padCy + gap) to 1,
            RectF(padCx - gap, padCy - padSize / 2f, padCx - gap + padSize, padCy + padSize / 2f) to 2,
            RectF(padCx + gap - padSize, padCy - padSize / 2f, padCx + gap, padCy + padSize / 2f) to 3,
        )
        positions.forEach { (rect, direction) ->
            dpad += rect to direction
            ArcadeDraw.panel(canvas, rect, 0x33FFFFFF, padSize * 0.3f, 0x55FFFFFF, 1.5f)
            val glyph = when (direction) {
                0 -> "▲"
                1 -> "▼"
                2 -> "◀"
                else -> "▶"
            }
            ArcadeDraw.text(canvas, glyph, rect.centerX(), rect.centerY() + padSize * 0.16f, padSize * 0.4f,
                Color.WHITE, bold = true, align = Paint.Align.CENTER)
        }
        if (caughtFlash > 0f) {
            canvas.drawRect(0f, 0f, width, height, ArcadeDraw.paint(Color.argb((caughtFlash / 2f * 90).toInt().coerceIn(0, 90), 255, 40, 60)))
        }
    }
}
