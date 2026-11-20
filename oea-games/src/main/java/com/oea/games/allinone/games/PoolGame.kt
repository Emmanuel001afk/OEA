package com.oea.games.allinone.games

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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
 * Pool (8-ball) and Snooker, with the rules each game actually uses.
 *
 * Pool is a real 8-ball rack: fifteen object balls, solids and stripes claimed by
 * the first legal pot after the break, the 8-ball only after a player has cleared
 * their group, and potting the 8 early losing the frame.
 *
 * Snooker is scored, not counted: fifteen reds worth one each, the six colours
 * worth two to seven, red-then-colour while reds remain, colours respotted, and
 * the final clearance in ascending order. Fouls give the opponent at least four
 * points.
 *
 * Positions live on a 100×50 table so the physics does not change with the
 * phone's size, and every frame is integrated in small steps so a hard break
 * cannot tunnel through a ball. The AI aims through the ghost-ball position at a
 * legal target and adds the sort of error a club player makes — less of it as the
 * level rises.
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
        /** Pool: 1 solids, 2 stripes, 3 the eight. Snooker: 4 red, 5 colour. */
        val group: Int = 0,
        /** Snooker points for this ball (reds 1, colours 2–7). */
        val value: Int = 0,
        val spotX: Float = 0f,
        val spotY: Float = 0f,
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
    private val pottedBalls = ArrayList<Ball>()
    private var firstHit: Ball? = null
    private var cuePotted = false
    private var breakShot = true
    private var aiDelay = 0f
    private var radius = 1.4f
    private var pocketRadius = 3.2f
    private var tableRect = RectF()
    private var lastMessage = ""
    private var ballsLeft = 0

    private val twoPlayer: Boolean get() = config.mode == ArcadePlayMode.TWO_PLAYER

    /** Pool groups, or the snooker colour order for the closing clearance. */
    private val groups = intArrayOf(-1, -1)
    private var openTable = true
    private var ballOn = 0          // 0 red, 1 colour after a red, 2 closing clearance
    private var colourIndex = 0
    private val colourOrder = intArrayOf(2, 3, 4, 5, 6, 7)

    private val ballColors = intArrayOf(
        0,
        0xFFF6C64B.toInt(), 0xFF3E8EE0.toInt(), 0xFFE3566E.toInt(), 0xFF9B59B6.toInt(),
        0xFFFF9F43.toInt(), 0xFF2E9E76.toInt(), 0xFF8E3B46.toInt(), 0xFF2B2635.toInt(),
        0xFFF6C64B.toInt(), 0xFF3E8EE0.toInt(), 0xFFE3566E.toInt(), 0xFF9B59B6.toInt(),
        0xFFFF9F43.toInt(), 0xFF2E9E76.toInt(), 0xFF8E3B46.toInt(),
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
        pottedBalls.clear()
        firstHit = null
        breakShot = true
        cuePotted = false
        aiDelay = 0f
        lastMessage = ""
        openTable = true
        groups[0] = -1
        groups[1] = -1
        ballOn = 0
        colourIndex = 0
        radius = if (snooker) 1.15f else 1.4f
        pocketRadius = if (snooker) {
            (2.75f - config.level * 0.02f).coerceAtLeast(2.4f)
        } else {
            (3.15f - config.level * 0.035f).coerceAtLeast(2.75f)
        }
        for (x in floatArrayOf(0f, 50f, 100f)) {
            pockets += x to 0f
            pockets += x to 50f
        }
        balls += Ball(0, ArcadeColors.WHITE, 24f, 25f, cue = true)
        if (snooker) rackSnooker() else rackPool()
    }

    private fun rackPool() {
        // A legal 8-ball rack: the 8 in the middle of the third row, one solid
        // and one stripe on the two back corners.
        val rack = listOf(
            listOf(1),
            listOf(9, 2),
            listOf(10, 8, 3),
            listOf(11, 4, 12, 5),
            listOf(7, 6, 14, 13, 15),
        )
        val gap = radius * 2.06f
        rack.forEachIndexed { row, numbers ->
            numbers.forEachIndexed { i, number ->
                balls += Ball(
                    number = number,
                    color = ballColors[number],
                    x = 68f + row * gap * 0.88f,
                    y = 25f + (i - row / 2f) * gap,
                    group = when {
                        number == 8 -> 3
                        number < 8 -> 1
                        else -> 2
                    },
                )
            }
        }
        ballsLeft = 15
    }

    private fun rackSnooker() {
        val gap = radius * 2.08f
        var number = 1
        for (row in 0 until 5) {
            for (i in 0..row) {
                balls += Ball(
                    number = number++,
                    color = 0xFFB3222C.toInt(),
                    x = 70f + row * gap * 0.86f,
                    y = 25f + (i - row / 2f) * gap,
                    group = 4,
                    value = 1,
                )
            }
        }
        val colours = listOf(
            Triple(2, 0xFFF2C94C.toInt(), 20f to 36f),
            Triple(3, 0xFF2E9E76.toInt(), 20f to 14f),
            Triple(4, 0xFF8E5A2B.toInt(), 20f to 25f),
            Triple(5, 0xFF3E8EE0.toInt(), 50f to 25f),
            Triple(6, 0xFFE38FB6.toInt(), 75f to 25f),
            Triple(7, 0xFF23202B.toInt(), 90f to 25f),
        )
        for ((value, color, spot) in colours) {
            balls += Ball(
                number = value,
                color = color,
                x = spot.first,
                y = spot.second,
                group = 5,
                value = value,
                spotX = spot.first,
                spotY = spot.second,
            )
        }
        ballsLeft = 21
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
                        if (ball.cue) {
                            cuePotted = true
                        } else {
                            pottedBalls += ball
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
                    if (firstHit == null && shotLive) {
                        if (a.cue) firstHit = b else if (b.cue) firstHit = a
                    }
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
        val potted = ArrayList(pottedBalls)
        pottedBalls.clear()
        val shooter = turn
        val scratch = cuePotted
        if (scratch) {
            cue.alive = true
            cue.x = 24f
            cue.y = 25f
            cue.vx = 0f
            cue.vy = 0f
        }
        if (snooker) resolveSnookerShot(shooter, potted, scratch)
        else resolvePoolShot(shooter, potted, scratch)
        breakShot = false
        queueNext()
        cuePotted = false
        pottedThisShot = 0
        firstHit = null
        if (snooker && !done() && balls.none { it.alive && !it.cue }) endFrame()
    }

    private fun queueNext() {
        if (done()) return
        if (!twoPlayer && turn == 1) aiDelay = 0.85f
    }

    // ------------------------------------------------------------------- 8-ball

    private fun nameOf(seat: Int): String = when {
        twoPlayer -> "Player ${seat + 1}"
        seat == 0 -> "You"
        else -> "the AI"
    }

    private fun groupName(group: Int): String = when (group) {
        1 -> "solids"
        2 -> "stripes"
        3 -> "the eight"
        else -> "open table"
    }

    private fun groupLeft(group: Int): Int = balls.count { it.alive && !it.cue && it.group == group }

    private fun groupLabel(seat: Int): String = when (groups[seat]) {
        1 -> "SOLIDS"
        2 -> "STRIPES"
        else -> "OPEN"
    }

    private fun resolvePoolShot(shooter: Int, potted: List<Ball>, scratch: Boolean) {
        val opponent = 1 - shooter
        val eight = potted.firstOrNull { it.group == 3 }

        if (eight != null) {
            if (openTable && !breakShot) {
                winPool(opponent, "the 8 went down while the table was still open")
                return
            }
            if (openTable) {
                respot(eight)
                if (scratch) {
                    fouls++
                    lastMessage = "8 and the white on the break — foul. Ball in hand for ${nameOf(opponent)}."
                    switchTurn()
                } else {
                    lastMessage = "8 on the break — respotted. The table is still open."
                }
                return
            }
            val cleared = groups[shooter] != -1 && groupLeft(groups[shooter]) == 0
            if (cleared && !scratch) {
                winPool(shooter, "8-ball on the back of a cleared group")
            } else {
                winPool(opponent, if (scratch) "the white and the 8 went down together" else "the 8 went down early")
            }
            return
        }

        if (scratch) {
            fouls++
            lastMessage = "Foul — the white dropped. Ball in hand for ${nameOf(opponent)}."
            switchTurn()
            return
        }
        if (firstHit == null) {
            fouls++
            lastMessage = "Foul — no contact. ${nameOf(opponent)} to play."
            switchTurn()
            return
        }
        if (!openTable && firstHit?.group != groups[shooter]) {
            fouls++
            lastMessage = "Foul — the wrong group was hit first. ${nameOf(opponent)} to play."
            switchTurn()
            return
        }

        if (openTable) {
            val claimed = potted.firstOrNull { it.group == 1 || it.group == 2 }
            if (claimed == null) {
                lastMessage = "No pot — the table stays open. ${nameOf(opponent)} to play."
                switchTurn()
                return
            }
            groups[shooter] = claimed.group
            groups[opponent] = if (claimed.group == 1) 2 else 1
            openTable = false
            pots[shooter] += potted.count { it.group == claimed.group }
            lastMessage = "Groups set: ${nameOf(shooter)} on ${groupName(claimed.group)} — shoot again."
            return
        }

        val own = potted.count { it.group == groups[shooter] }
        pots[shooter] += own
        if (own > 0) {
            lastMessage = if (own == 1) "Potted — shoot again." else "$own of yours — shoot again."
        } else {
            lastMessage = "Nothing of yours — ${nameOf(opponent)} to play."
            switchTurn()
        }
    }

    private fun winPool(seat: Int, why: String) {
        val won = seat == 0
        finish(
            won = won,
            headline = when {
                twoPlayer -> "Player ${seat + 1} wins the frame"
                won -> "You win the frame"
                else -> "The AI wins the frame"
            },
            detail = "${nameOf(seat)} took the 8-ball after $why. " +
                "Solids are 1–7, stripes 9–15; the 8 only goes down after your group is cleared, and an early 8 loses the frame.",
            score = pots[0] * 40L + (if (won) 80 else 0) + (if (fouls == 0) 40 else 0),
            stars = when {
                !won -> 1
                fouls == 0 -> 3
                else -> 2
            }
        )
    }

    // ------------------------------------------------------------------ snooker

    private fun colourName(number: Int): String = when (number) {
        2 -> "Yellow"
        3 -> "Green"
        4 -> "Brown"
        5 -> "Blue"
        6 -> "Pink"
        else -> "Black"
    }

    private fun respot(ball: Ball) {
        ball.alive = true
        ball.vx = 0f
        ball.vy = 0f
        if (ball.spotX != 0f || ball.spotY != 0f) {
            ball.x = ball.spotX
            ball.y = ball.spotY
        } else {
            ball.x = 68f
            ball.y = 25f
        }
        ballsLeft++
    }

    private fun nextAliveColour(): Int {
        for (index in colourOrder.indices) {
            val number = colourOrder[index]
            if (balls.any { it.alive && it.number == number }) return index
        }
        return colourOrder.lastIndex
    }

    private fun resolveSnookerShot(shooter: Int, potted: List<Ball>, scratch: Boolean) {
        val opponent = 1 - shooter
        val redsLeft = balls.any { it.alive && it.group == 4 }
        val on = if (ballOn == 2) colourOrder[colourIndex.coerceIn(0, colourOrder.lastIndex)] else ballOn
        var foul = scratch || firstHit == null

        if (!foul) {
            when (on) {
                0 -> {
                    foul = firstHit?.group != 4 || potted.any { it.group == 5 }
                    if (!foul) {
                        val reds = potted.count { it.group == 4 }
                        if (reds == 0) {
                            lastMessage = "No pot — ${nameOf(opponent)} to play."
                            switchTurn()
                            return
                        }
                        pots[shooter] += reds
                        ballOn = 1
                        lastMessage = "${if (reds == 1) "Red" else "$reds reds"} — ${nameOf(shooter)} +$reds. Colour next."
                        return
                    }
                }
                1 -> {
                    val colour = potted.firstOrNull { it.group == 5 }
                    foul = firstHit?.group != 5 ||
                        potted.any { it.group == 4 } ||
                        potted.count { it.group == 5 } > 1
                    if (!foul) {
                        if (colour == null) {
                            ballOn = if (redsLeft) 0 else 2
                            if (ballOn == 2) colourIndex = nextAliveColour()
                            lastMessage = "No pot — ${nameOf(opponent)} to play."
                            switchTurn()
                            return
                        }
                        pots[shooter] += colour.value
                        respot(colour)
                        ballOn = if (balls.any { it.alive && it.group == 4 }) 0 else 2
                        if (ballOn == 2) colourIndex = 0
                        lastMessage = "${colourName(colour.number)} — ${nameOf(shooter)} +${colour.value}. Red next."
                        return
                    }
                }
                else -> {
                    val need = colourOrder[colourIndex.coerceIn(0, colourOrder.lastIndex)]
                    val colour = potted.firstOrNull { it.group == 5 }
                    foul = firstHit?.number != need ||
                        potted.any { it.group == 4 } ||
                        potted.count { it.group == 5 } > 1 ||
                        (colour != null && colour.number != need)
                    if (!foul && colour == null) {
                        lastMessage = "No pot — ${nameOf(opponent)} to play."
                        switchTurn()
                        return
                    }
                    if (!foul && colour != null) {
                        pots[shooter] += colour.value
                        colourIndex++
                        ballOn = 2
                        val cleared = colourIndex > colourOrder.lastIndex
                        lastMessage = "${colourName(need)} — ${nameOf(shooter)} +${colour.value}."
                        if (cleared) endFrame() else colourIndex = nextAliveColour()
                        return
                    }
                }
            }
        }

        // Foul: the opponent gets at least four points, and any colour potted
        // illegally goes back on its spot.
        val onValue = if (on == 0) 1 else if (on == 1) 4 else colourOrder[colourIndex.coerceIn(0, colourOrder.lastIndex)]
        val penalty = maxOf(4, onValue, potted.maxOfOrNull { it.value } ?: 0)
        pots[opponent] += penalty
        fouls++
        potted.filter { it.group == 5 }.forEach { respot(it) }
        ballOn = if (balls.any { it.alive && it.group == 4 }) 0 else 2
        if (ballOn == 2) colourIndex = nextAliveColour()
        lastMessage = "Foul — $penalty to ${nameOf(opponent)}."
        switchTurn()
    }

    private fun endFrame() {
        val winner = when {
            pots[0] == pots[1] -> -1
            pots[0] > pots[1] -> 0
            else -> 1
        }
        val won = winner == 0
        finish(
            won = if (winner == -1) null else won,
            headline = when (winner) {
                -1 -> "Frame tied"
                0 -> if (twoPlayer) "Player 1 takes the frame" else "You take the frame"
                else -> if (twoPlayer) "Player 2 takes the frame" else "The AI takes the frame"
            },
            detail = "Points ${pots[0]} — ${pots[1]} with $fouls foul${if (fouls == 1) "" else "s"}. " +
                "Reds are one each, colours two to seven, and the closing clearance runs yellow to black.",
            score = pots[0] * 10L + (if (won) 50 else 0),
            stars = when {
                !won -> 1
                fouls <= 1 -> 3
                else -> 2
            }
        )
    }

    private fun switchTurn() {
        turn = 1 - turn
        if (!twoPlayer && turn == 1) aiDelay = 0.85f
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
        pottedBalls.clear()
        firstHit = null
        cuePotted = false
        power = strength
    }

    // ---------------------------------------------------------------------- AI

    /** The balls the player to shoot may legally hit first. */
    private fun legalTargets(): List<Ball> {
        val live = balls.filter { it.alive && !it.cue }
        if (snooker) {
            return when (ballOn) {
                0 -> live.filter { it.group == 4 }
                1 -> live.filter { it.group == 5 }
                else -> {
                    val need = colourOrder[colourIndex.coerceIn(0, colourOrder.lastIndex)]
                    live.filter { it.number == need }
                }
            }
        }
        val mine = groups[1]
        return when {
            mine == -1 -> live.filter { it.group != 3 }
            live.any { it.group == mine } -> live.filter { it.group == mine }
            else -> live.filter { it.number == 8 }
        }
    }

    private fun aiShoot() {
        if (done()) return
        val cue = balls.firstOrNull { it.cue && it.alive } ?: return
        val targets = legalTargets().ifEmpty { balls.filter { it.alive && !it.cue } }
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
                val score = cueToGhost + toPocket * 0.8f
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
            angle += (rand() - 0.5) * (0.1f - skill * 0.085f - config.level * 0.0012f).coerceAtLeast(0.012f)
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
        if (snooker) {
            val on = when (ballOn) {
                0 -> "red"
                1 -> "a colour"
                else -> colourName(colourOrder[colourIndex.coerceIn(0, colourOrder.lastIndex)]).lowercase()
            }
            val whose = if (twoPlayer) "Player ${turn + 1}" else if (turn == 0) "You" else "AI"
            return "$whose to play · ${pots[0]} — ${pots[1]} · on: $on"
        }
        val mine = groups[turn]
        val whose = if (twoPlayer) "P${turn + 1}" else if (turn == 0) "You" else "AI"
        val group = if (mine == -1) "open table" else "${groupName(mine)} · ${groupLeft(mine)} left"
        return "$whose · $group · $ballsLeft on the table"
    }

    override fun hint(): String = lastMessage.ifBlank {
        when {
            snooker -> "Pot a red, then a colour. Colours come back until the reds are gone; the final clearance runs yellow to black."
            turn != 0 && !twoPlayer -> ""
            else -> "Drag from the white to aim; the further you drag, the harder the shot. Clear your group before the 8."
        }
    }

    override fun score(): Long = if (snooker) pots[0] * 10L else pots[0] * 40L

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

        // Balls. Stripes are drawn as a white ball with a coloured band, the way
        // a real stripe reads from above.
        balls.forEach { ball ->
            if (!ball.alive) return@forEach
            val bx = toTableX(ball.x)
            val by = toTableY(ball.y)
            if (ball.group == 2) {
                ArcadeDraw.circle(canvas, bx, by, ballPixel, 0xFFFBF6EC.toInt())
                val clip = Path().apply { addCircle(bx, by, ballPixel * 0.96f, Path.Direction.CW) }
                canvas.save()
                canvas.clipPath(clip)
                canvas.drawRect(bx - ballPixel, by - ballPixel * 0.48f, bx + ballPixel, by + ballPixel * 0.48f,
                    ArcadeDraw.paint(ball.color))
                canvas.restore()
                ArcadeDraw.circle(canvas, bx, by, ballPixel, Color.TRANSPARENT, 0x33000000, 1.2f)
                ArcadeDraw.circle(canvas, bx, by, ballPixel * 0.5f, 0xFFFBF6EC.toInt())
                ArcadeDraw.text(canvas, ball.number.toString(), bx, by + ballPixel * 0.2f, ballPixel * 0.62f,
                    ArcadeColors.INK, bold = true, align = Paint.Align.CENTER)
            } else {
                val label = if (snooker || ball.cue) 0 else ball.number
                ArcadeDraw.ball(canvas, bx, by, ballPixel, ball.color, label)
            }
        }

        // Scoreboard above the table.
        ArcadeDraw.text(canvas, if (twoPlayer) "P1" else "YOU", width * 0.08f, height * 0.09f, height * 0.015f,
            ArcadeColors.CORAL, bold = true)
        ArcadeDraw.text(canvas, pots[0].toString(), width * 0.08f, height * 0.125f, height * 0.032f, Color.WHITE, bold = true)
        ArcadeDraw.text(canvas, if (twoPlayer) "P2" else "AI", width * 0.92f, height * 0.09f, height * 0.015f,
            ArcadeColors.MINT, bold = true, align = Paint.Align.RIGHT)
        ArcadeDraw.text(canvas, pots[1].toString(), width * 0.92f, height * 0.125f, height * 0.032f, Color.WHITE, bold = true, align = Paint.Align.RIGHT)
        val centre = when {
            snooker -> {
                val on = when (ballOn) {
                    0 -> "RED"
                    1 -> "COLOUR"
                    else -> colourName(colourOrder[colourIndex.coerceIn(0, colourOrder.lastIndex)]).uppercase()
                }
                "SNOOKER · ON: $on · LEVEL ${config.level}"
            }
            else -> "POOL · ${groupLabel(0)} v ${groupLabel(1)} · LEVEL ${config.level}"
        }
        ArcadeDraw.text(canvas, centre, width * 0.5f, height * 0.115f,
            height * 0.013f, 0xAAFFFFFF.toInt(), bold = true, align = Paint.Align.CENTER)

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
