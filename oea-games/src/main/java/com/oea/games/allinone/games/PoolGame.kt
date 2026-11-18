package com.oea.games.allinone.games

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.oea.games.allinone.ArcadeBaseGame
import com.oea.games.allinone.ArcadeColors
import com.oea.games.allinone.ArcadeDraw
import com.oea.games.allinone.ArcadePlayMode
import com.oea.games.allinone.ArcadeTouch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pool, and its long-table brother Snooker.
 *
 * Real balls, real cushions, real pockets: positions live on a 100×50 table so
 * the physics does not change with the phone's size, and every frame is
 * integrated in small steps so a hard break cannot tunnel through a ball.
 * A pot keeps the turn, a miss hands it over, and potting the white is a foul
 * that gives the opponent four points and a free table.
 *
 * The AI looks for a ball and a pocket it can line up, aims through the ghost
 * ball position, and adds the sort of error a club player makes — less of it as
 * the level rises.
 */
class PoolGame(private val snooker: Boolean = false) : ArcadeBaseGame() {

    private data class Ball(
        val number: Int,
        val color: Int,
        var x: Float,
        var y: Float,
        var vx: Float = 0f,
        var vy: Float = 0f,
        val cue: Boolean = false,
        var alive: Boolean = true,
    )

    private val balls = ArrayList<Ball>()
    private val pockets = ArrayList<Pair<Float, Float>>()
    private val pots = intArrayOf(0, 0)
    private var fouls = 0
    private var turn = 0
    private var aiming = false
    private var aimX = 0f
    private var aimY = 0f
    private var power = 0f
    private var shotLive = false
    private var pottedThisShot = 0
    private var cuePotted = false
    private var aiDelay = 0f
    private var radius = 1.4f
    private var pocketRadius = 3.2f
    private var tableRect = RectF()
    private var lastMessage = ""
    private var ballsLeft = 0

    private val twoPlayer: Boolean get() = config.mode == ArcadePlayMode.TWO_PLAYER

    private val colors = intArrayOf(
        0xFFF6C64B.toInt(), 0xFF3E8EE0.toInt(), 0xFFE3566E.toInt(), 0xFF9B59B6.toInt(),
        0xFFFF9F43.toInt(), 0xFF2E9E76.toInt(), 0xFF8E3B46.toInt(), 0xFF2B2635.toInt(),
        0xFFF6C64B.toInt(), 0xFF3E8EE0.toInt(),
    )

    override fun onStart() {
        balls.clear()
        pockets.clear()
        pots[0] = 0
        pots[1] = 0
        fouls = 0
        turn = 0
        aiming = false
        shotLive = false
        pottedThisShot = 0
        cuePotted = false
        aiDelay = 0f
        lastMessage = ""
        radius = if (snooker) 1.15f else 1.4f
        pocketRadius = if (snooker) 2.7f else 3.1f
        for (x in floatArrayOf(0f, 50f, 100f)) {
            pockets += x to 0f
            pockets += x to 50f
        }
        balls += Ball(0, ArcadeColors.WHITE, 24f, 25f, cue = true)
        val count = if (snooker) (8 + config.level).coerceAtMost(15) else (5 + config.level).coerceAtMost(9)
        ballsLeft = count
        var placed = 0
        var row = 0
        val gap = radius * 2.05f
        while (placed < count) {
            val inRow = row + 1
            for (i in 0..row) {
                if (placed >= count) break
                balls += Ball(
                    number = placed + 1,
                    color = colors[placed % colors.size],
                    x = 66f + row * gap * 0.88f,
                    y = 25f + (i - row / 2f) * gap,
                )
                placed++
            }
            row++
        }
    }

    // ----------------------------------------------------------------- physics

    override fun update(dt: Float) {
        super.update(dt)
        if (done()) return
        if (shotLive) {
            step(min(dt, 0.03f))
            if (!moving()) resolveShot()
            return
        }
        if (aiDelay > 0f) {
            aiDelay -= dt
            if (aiDelay <= 0f && !twoPlayer && turn == 1) aiShoot()
        }
    }

    private fun step(dt: Float) {
        val slices = 4
        val h = dt / slices
        repeat(slices) {
            balls.forEach { ball ->
                if (!ball.alive) return@forEach
                ball.x += ball.vx * h
                ball.y += ball.vy * h
                ball.vx *= 0.995f
                ball.vy *= 0.995f
                if (hypot(ball.vx, ball.vy) < 0.45f) {
                    ball.vx = 0f
                    ball.vy = 0f
                }
                if (ball.x < radius) { ball.x = radius; ball.vx = abs(ball.vx) * 0.93f }
                if (ball.x > 100f - radius) { ball.x = 100f - radius; ball.vx = -abs(ball.vx) * 0.93f }
                if (ball.y < radius) { ball.y = radius; ball.vy = abs(ball.vy) * 0.93f }
                if (ball.y > 50f - radius) { ball.y = 50f - radius; ball.vy = -abs(ball.vy) * 0.93f }
            }
            // Pockets.
            balls.forEach { ball ->
                if (!ball.alive) return@forEach
                for ((px, py) in pockets) {
                    if (hypot(ball.x - px, ball.y - py) < pocketRadius) {
                        ball.alive = false
                        if (ball.cue) cuePotted = true else {
                            pots[turn]++
                            pottedThisShot++
                            ballsLeft--
                        }
                        break
                    }
                }
            }
            // Ball-to-ball collisions.
            for (i in balls.indices) {
                val a = balls[i]
                if (!a.alive) continue
                for (j in i + 1 until balls.size) {
                    val b = balls[j]
                    if (!b.alive) continue
                    val dx = b.x - a.x
                    val dy = b.y - a.y
                    val distance = hypot(dx, dy)
                    if (distance <= 0f || distance >= radius * 2f) continue
                    val nx = dx / distance
                    val ny = dy / distance
                    val overlap = radius * 2f - distance
                    a.x -= nx * overlap / 2f
                    a.y -= ny * overlap / 2f
                    b.x += nx * overlap / 2f
                    b.y += ny * overlap / 2f
                    val relative = (b.vx - a.vx) * nx + (b.vy - a.vy) * ny
                    if (relative > 0f) continue
                    val impulse = relative * 0.96f
                    a.vx += impulse * nx
                    a.vy += impulse * ny
                    b.vx -= impulse * nx
                    b.vy -= impulse * ny
                }
            }
        }
    }

    private fun moving(): Boolean = balls.any { it.alive && (abs(it.vx) + abs(it.vy)) > 0.05f }

    private fun resolveShot() {
        shotLive = false
        val cue = balls.firstOrNull { it.cue } ?: return
        if (cuePotted) {
            cue.alive = true
            cue.x = 24f
            cue.y = 25f
            cue.vx = 0f
            cue.vy = 0f
            fouls++
            pots[1 - turn] += 4
            lastMessage = "Foul — the white dropped. ${if (turn == 0) "AI" else "You"} +4"
            switchTurn()
        } else if (pottedThisShot == 0) {
            lastMessage = "No pot — turn passes"
            switchTurn()
        } else {
            lastMessage = if (pottedThisShot == 1) "Potted — shoot again" else "Two in one — shoot again"
        }
        pottedThisShot = 0
        cuePotted = false
        if (balls.none { it.alive && !it.cue }) {
            settle()
        }
    }

    private fun switchTurn() {
        turn = 1 - turn
        if (!twoPlayer && turn == 1) aiDelay = 0.85f
    }

    private fun settle() {
        val winner = when {
            pots[0] == pots[1] -> -1
            pots[0] > pots[1] -> 0
            else -> 1
        }
        val won = winner == 0
        finish(
            won = if (winner == -1) null else won,
            headline = when (winner) {
                -1 -> "Honours even"
                0 -> if (twoPlayer) "Player 1 clears the table" else "You clear the table"
                else -> if (twoPlayer) "Player 2 clears the table" else "The AI clears the table"
            },
            detail = "Pots ${pots[0]} — ${pots[1]} with $fouls foul${if (fouls == 1) "" else "s"}. " +
                if (won) "The next level racks more balls and tightens the pockets." else "Aim for the ghost ball: the white must arrive where the object ball is now standing.",
            score = pots[0] * 40L + (if (won) 60 else 0),
            stars = when {
                !won -> 1
                fouls == 0 -> 3
                else -> 2
            }
        )
    }

    // ------------------------------------------------------------------- input

    override fun touch(phase: ArcadeTouch, x: Float, y: Float, width: Float, height: Float): Boolean {
        if (done() || shotLive) return false
        if (!twoPlayer && turn == 1) return false
        val cue = balls.firstOrNull { it.cue && it.alive } ?: return false
        val cx = toTableX(cue.x)
        val cy = toTableY(cue.y)
        when (phase) {
            ArcadeTouch.DOWN -> {
                aiming = true
                aimX = x
                aimY = y
                power = 0f
                return true
            }
            ArcadeTouch.MOVE -> {
                if (!aiming) return false
                aimX = x
                aimY = y
                power = ((hypot(x - cx, y - cy) / (width * 0.36f)).coerceIn(0f, 1f))
                return true
            }
            ArcadeTouch.UP -> {
                if (!aiming) return false
                aiming = false
                val dx = (x - cx).toDouble()
                val dy = (y - cy).toDouble()
                val length = hypot(dx.toFloat(), dy.toFloat())
                if (length < width * 0.02f) {
                    power = 0f
                    return true
                }
                val strength = (length / (width * 0.36f)).coerceIn(0.12f, 1f)
                strike(cue, dx / length, dy / length, strength)
                return true
            }
        }
        return false
    }

    private fun strike(cue: Ball, nx: Double, ny: Double, strength: Float) {
        val speed = 34f * strength
        cue.vx = (nx * speed).toFloat()
        cue.vy = (ny * speed).toFloat()
        shotLive = true
        pottedThisShot = 0
        cuePotted = false
        power = strength
    }

    // ---------------------------------------------------------------------- AI

    private fun aiShoot() {
        if (done()) return
        val cue = balls.firstOrNull { it.cue && it.alive } ?: return
        val targets = balls.filter { it.alive && !it.cue }
        if (targets.isEmpty()) return
        var best: Triple<Ball, Pair<Float, Float>, Float>? = null
        var bestScore = Float.MAX_VALUE
        for (ball in targets) {
            for (pocket in pockets) {
                val toPocketX = pocket.first - ball.x
                val toPocketY = pocket.second - ball.y
                val toPocket = hypot(toPocketX, toPocketY)
                if (toPocket < 0.1f) continue
                val ghostX = ball.x - toPocketX / toPocket * radius * 2.1f
                val ghostY = ball.y - toPocketY / toPocket * radius * 2.1f
                val cueToGhost = hypot(ghostX - cue.x, ghostY - cue.y)
                val score = cueToGhost + toPocket * 0.8f + if (ball.number == 8) 30f else 0f
                if (score < bestScore && pathClear(cue.x, cue.y, ghostX, ghostY, ball)) {
                    bestScore = score
                    best = Triple(ball, pocket, cueToGhost)
                }
            }
        }
        val direction: Pair<Float, Float>
        val strength: Float
        val chosen = best
        if (chosen != null) {
            val (ball, pocket, cueDistance) = chosen
            val toPocketX = pocket.first - ball.x
            val toPocketY = pocket.second - ball.y
            val toPocket = hypot(toPocketX, toPocketY).coerceAtLeast(0.1f)
            val ghostX = ball.x - toPocketX / toPocket * radius * 2.1f
            val ghostY = ball.y - toPocketY / toPocket * radius * 2.1f
            var angle = atan2((ghostY - cue.y).toDouble(), (ghostX - cue.x).toDouble())
            angle += (rand() - 0.5) * (0.1f - skill * 0.085f)
            direction = cos(angle).toFloat() to sin(angle).toFloat()
            strength = ((cueDistance / 60f) + 0.28f).coerceIn(0.2f, 1f)
        } else {
            val ball = targets.minByOrNull { hypot(it.x - cue.x, it.y - cue.y) } ?: return
            val angle = atan2((ball.y - cue.y).toDouble(), (ball.x - cue.x).toDouble()) + (rand() - 0.5) * 0.14
            direction = cos(angle).toFloat() to sin(angle).toFloat()
            strength = 0.55f
        }
        strike(cue, direction.first.toDouble(), direction.second.toDouble(), strength)
    }

    private fun pathClear(x1: Float, y1: Float, x2: Float, y2: Float, ignore: Ball): Boolean {
        for (ball in balls) {
            if (!ball.alive || ball.cue || ball === ignore) continue
            val distance = pointLineDistance(ball.x, ball.y, x1, y1, x2, y2)
            if (distance < radius * 2.05f) return false
        }
        return true
    }

    private fun pointLineDistance(px: Float, py: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        val lengthSq = dx * dx + dy * dy
        if (lengthSq < 0.0001f) return hypot(px - x1, py - y1)
        val t = (((px - x1) * dx + (py - y1) * dy) / lengthSq).coerceIn(0f, 1f)
        return hypot(px - (x1 + t * dx), py - (y1 + t * dy))
    }

    // ------------------------------------------------------------------ layout

    private fun toTableX(x: Float): Float = tableRect.left + x / 100f * tableRect.width()
    private fun toTableY(y: Float): Float = tableRect.top + y / 50f * tableRect.height()

    override fun status(): String {
        if (done()) return "Frame over"
        return when {
            twoPlayer -> "Player ${turn + 1} · pots ${pots[0]} — ${pots[1]} · ${ballsLeft} on the table"
            turn == 0 -> "Your shot · you ${pots[0]} — ${pots[1]} AI · $ballsLeft left"
            else -> "OEA AI lining up · ${pots[0]} — ${pots[1]}"
        }
    }

    override fun hint(): String = lastMessage.ifBlank {
        if (turn == 0) "Drag from the white to aim; the further you drag, the harder the shot." else ""
    }

    override fun score(): Long = pots[0] * 40L

    override fun draw(canvas: Canvas, width: Float, height: Float) {
        canvas.drawColor(0xFF141B1D.toInt())
        val tableH = min(height * 0.5f, width * 0.62f)
        val tableW = min(width * 0.94f, tableH * 2f)
        val left = (width - tableW) / 2f
        val top = height * 0.2f
        tableRect.set(left + tableW * 0.035f, top + tableH * 0.05f, left + tableW * 0.965f, top + tableH * 0.95f)

        // Wooden frame and felt.
        ArcadeDraw.panel(canvas, left, top, left + tableW, top + tableH, 0xFF6B4423.toInt(), tableH * 0.06f)
        ArcadeDraw.panel(canvas, left + tableW * 0.018f, top + tableH * 0.03f, left + tableW * 0.982f, top + tableH * 0.97f,
            0xFF4A2E17.toInt(), tableH * 0.04f)
        ArcadeDraw.panel(canvas, tableRect, if (snooker) 0xFF126B58.toInt() else 0xFF1F8A66.toInt(), tableH * 0.02f)
        // Cushion shadow and baulk line.
        ArcadeDraw.panel(canvas, tableRect.left, tableRect.top, tableRect.right, tableRect.top + tableRect.height() * 0.04f,
            0x22000000, tableH * 0.02f)
        val baulkX = tableRect.left + tableRect.width() * 0.2f
        canvas.drawLine(baulkX, tableRect.top, baulkX, tableRect.bottom, ArcadeDraw.paint(0x33FFFFFF, tableH * 0.004f))

        // Pockets.
        pockets.forEach { (px, py) ->
            ArcadeDraw.circle(canvas, toTableX(px), toTableY(py), tableH * 0.075f, 0xFF0C1012.toInt())
            ArcadeDraw.circle(canvas, toTableX(px), toTableY(py), tableH * 0.075f, Color.TRANSPARENT, 0x33FFFFFF, tableH * 0.004f)
        }

        val ballPixel = tableRect.width() / 100f * radius
        // Aim guide.
        val cue = balls.firstOrNull { it.cue && it.alive }
        if (cue != null && aiming) {
            val cx = toTableX(cue.x)
            val cy = toTableY(cue.y)
            val guide = ArcadeDraw.paint(0x88FFFFFF.toInt(), tableH * 0.006f)
            canvas.drawLine(cx, cy, aimX, aimY, guide)
            val ghost = ballHit(cue, aimX, aimY)
            if (ghost != null) {
                val (gx, gy) = ghost
                ArcadeDraw.circle(canvas, gx, gy, ballPixel * 0.9f, Color.TRANSPARENT, 0xAAFFFFFF.toInt(), tableH * 0.004f)
            }
            // Cue stick.
            val angle = atan2((aimY - cy).toDouble(), (aimX - cx).toDouble())
            val backX = cx - cos(angle).toFloat() * tableH * (0.2f + 0.22f * power)
            val backY = cy - sin(angle).toFloat() * tableH * (0.2f + 0.22f * power)
            canvas.drawLine(backX, backY, cx - cos(angle).toFloat() * ballPixel * 1.2f, cy - sin(angle).toFloat() * ballPixel * 1.2f,
                ArcadeDraw.paint(0xFFE3C08A.toInt(), tableH * 0.016f))
        }

        // Balls.
        balls.forEach { ball ->
            if (!ball.alive) return@forEach
            ArcadeDraw.ball(canvas, toTableX(ball.x), toTableY(ball.y), ballPixel, ball.color,
                if (ball.cue) 0 else ball.number, if (ball.cue) ArcadeColors.WHITE else ArcadeColors.WHITE)
        }

        // Scoreboard above the table.
        ArcadeDraw.text(canvas, if (twoPlayer) "P1" else "YOU", width * 0.08f, height * 0.09f, height * 0.015f,
            ArcadeColors.CORAL, bold = true)
        ArcadeDraw.text(canvas, pots[0].toString(), width * 0.08f, height * 0.125f, height * 0.032f, Color.WHITE, bold = true)
        ArcadeDraw.text(canvas, if (twoPlayer) "P2" else "AI", width * 0.92f, height * 0.09f, height * 0.015f,
            ArcadeColors.MINT, bold = true, align = Paint.Align.RIGHT)
        ArcadeDraw.text(canvas, pots[1].toString(), width * 0.92f, height * 0.125f, height * 0.032f, Color.WHITE, bold = true, align = Paint.Align.RIGHT)
        ArcadeDraw.text(canvas, "${if (snooker) "SNOOKER" else "POOL"} · LEVEL ${config.level} · $ballsLeft LEFT", width * 0.5f, height * 0.115f,
            height * 0.014f, 0xAAFFFFFF.toInt(), bold = true, align = Paint.Align.CENTER)

        // Power meter and turn banner.
        if (aiming) {
            val meter = RectF(width * 0.08f, height * 0.855f, width * 0.92f, height * 0.875f)
            ArcadeDraw.panel(canvas, meter, 0x33FFFFFF, meter.height() / 2f)
            ArcadeDraw.panel(canvas, RectF(meter.left, meter.top, meter.left + meter.width() * power, meter.bottom),
                ArcadeColors.GOLD, meter.height() / 2f)
        }
        val active = if (turn == 0) ArcadeColors.CORAL else ArcadeColors.MINT
        ArcadeDraw.panel(canvas, RectF(width * 0.5f - width * 0.11f, height * 0.885f, width * 0.5f + width * 0.11f,
            height * 0.895f), active, height * 0.005f)
        ArcadeDraw.text(canvas, lastMessage, width * 0.5f, height * 0.92f, height * 0.015f, 0xAAFFFFFF.toInt(), align = Paint.Align.CENTER)
        ArcadeDraw.text(canvas, config.difficulty.label.uppercase(), width * 0.5f, height * 0.945f, height * 0.011f,
            0x66FFFFFF, bold = true, align = Paint.Align.CENTER)
    }

    /** Where the cue ball would first touch another ball along a screen-space line. */
    private fun ballHit(cue: Ball, sx: Float, sy: Float): Pair<Float, Float>? {
        val dx = sx - toTableX(cue.x)
        val dy = sy - toTableY(cue.y)
        val length = hypot(dx, dy)
        if (length < 1f) return null
        val nx = dx / length
        val ny = dy / length
        var best = Float.MAX_VALUE
        var hit: Pair<Float, Float>? = null
        balls.forEach { ball ->
            if (!ball.alive || ball === cue) return@forEach
            val bx = toTableX(ball.x) - toTableX(cue.x)
            val by = toTableY(ball.y) - toTableY(cue.y)
            val projection = bx * nx + by * ny
            if (projection <= 0f) return@forEach
            val perpendicular = abs(bx * ny - by * nx)
            val radiusPx = tableRect.width() / 100f * radius * 2.1f
            if (perpendicular < radiusPx) {
                val distance = projection - sqrt(max(0f, radiusPx * radiusPx - perpendicular * perpendicular))
                if (distance < best) {
                    best = distance
                    hit = (toTableX(cue.x) + nx * distance) to (toTableY(cue.y) + ny * distance)
                }
            }
        }
        return hit
    }
}
