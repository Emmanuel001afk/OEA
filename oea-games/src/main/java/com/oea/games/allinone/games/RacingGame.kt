package com.oea.games.allinone.games

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.oea.games.allinone.ArcadeBaseGame
import com.oea.games.allinone.ArcadeColors
import com.oea.games.allinone.ArcadeDraw
import com.oea.games.allinone.ArcadeTouch
import kotlin.math.abs
import kotlin.math.min

/**
 * Car Racing: a circuit in a cabinet.
 *
 * The car steers with a thumb — drag anywhere and it follows — while speed builds
 * on its own, so the game is about the racing line and reading traffic, not about
 * holding an accelerator. Twelve circuits raise the field's pace, the density of
 * traffic and the distance; three clean laps is a level well driven.
 */
class RacingGame : ArcadeBaseGame() {

    private data class Traffic(var x: Float, var y: Float, var pace: Float, val color: Int, val lane: Int)

    private var playerX = 0.5f
    private var targetX = 0.5f
    private var distance = 0f
    private var goal = 1200f
    private var metresPerSecond = 90f
    private var lives = 3
    private var invulnerable = 0f
    private var spawnIn = 1.2f
    private var traffic = ArrayList<Traffic>()
    private var scroll = 0f
    private var shake = 0f
    private var topSpeed = 0f
    private var overtakes = 0

    private val carColors = intArrayOf(
        0xFF7BD3FF.toInt(), 0xFFF6C64B.toInt(), 0xFFB39DFF.toInt(),
        0xFF43D6A2.toInt(), 0xFFFF9F43.toInt(), 0xFFE8E8F0.toInt(),
    )

    override fun onStart() {
        playerX = 0.5f
        targetX = 0.5f
        distance = 0f
        goal = 900f + config.level * 260f
        metresPerSecond = 86f + config.level * 9f + skill * 34f
        lives = 3
        invulnerable = 0f
        spawnIn = 1.1f
        traffic = ArrayList()
        scroll = 0f
        shake = 0f
        topSpeed = 0f
        overtakes = 0
    }

    override fun update(dt: Float) {
        super.update(dt)
        if (done()) return
        if (invulnerable > 0f) invulnerable -= dt
        if (shake > 0f) shake = (shake - dt * 2f).coerceAtLeast(0f)

        // The car leans toward where your thumb is pointing.
        val steer = (targetX - playerX) * min(1f, dt * 6f)
        playerX = (playerX + steer * 1.9f).coerceIn(0.06f, 0.94f)
        topSpeed = maxOf(topSpeed, metresPerSecond)

        distance += metresPerSecond * dt
        scroll += (0.5f + config.level * 0.02f) * dt
        if (distance >= goal) {
            settle(true)
            return
        }

        // Traffic.
        spawnIn -= dt
        if (spawnIn <= 0f) {
            spawnTraffic()
            spawnIn = (1.55f - config.level * 0.07f - skill * 0.35f).coerceAtLeast(0.42f) *
                (0.75f + rand() * 0.6f)
        }
        val iterator = traffic.iterator()
        while (iterator.hasNext()) {
            val car = iterator.next()
            car.y += (1f - car.pace) * (0.5f + config.level * 0.02f) * dt
            if (car.y > 1.12f) {
                iterator.remove()
                overtakes++
                continue
            }
            if (invulnerable <= 0f && abs(car.x - playerX) < 0.105f && car.y > 0.66f && car.y < 0.9f) {
                crash(car)
                iterator.remove()
            }
        }
    }

    private fun spawnTraffic() {
        val lanes = intArrayOf(12, 30, 50, 70, 88)
        val lane = lanes[random.nextInt(lanes.size)]
        traffic += Traffic(
            x = lane / 100f,
            y = -0.15f,
            pace = 0.35f + rand() * 0.4f,
            color = carColors[random.nextInt(carColors.size)],
            lane = lane,
        )
        if (config.level >= 8 && rand() > 0.5f) {
            val other = lanes.filter { it != lane }.random(random)
            traffic += Traffic(other / 100f, -0.35f, 0.4f + rand() * 0.35f, carColors[random.nextInt(carColors.size)], other)
        }
    }

    private fun crash(car: Traffic) {
        lives--
        invulnerable = 1.4f
        shake = 1f
        metresPerSecond = (metresPerSecond * 0.86f).coerceAtLeast(60f)
        if (lives <= 0) settle(false)
    }

    private fun settle(won: Boolean) {
        val score = distance.toLong() + lives * 120L + overtakes * 15L
        val stars = when {
            won && lives == 3 -> 3
            won && lives == 2 -> 2
            won -> 1
            else -> 1
        }
        finish(
            won = won,
            headline = if (won) "Chequered flag" else "The car is out",
            detail = "${distance.toInt()} m of ${goal.toInt()} m, $overtakes car${if (overtakes == 1) "" else "s"} passed, $lives ${if (lives == 1) "life" else "lives"} left. " +
                if (won) "The next circuit is faster — brake earlier into traffic." else "Leave a lane between you and the traffic; the shoulder is not a race line.",
            score = score,
            stars = stars
        )
    }

    override fun touch(phase: ArcadeTouch, x: Float, y: Float, width: Float, height: Float): Boolean {
        if (done()) return false
        if (phase == ArcadeTouch.DOWN || phase == ArcadeTouch.MOVE) {
            targetX = (x / width).coerceIn(0.06f, 0.94f)
            return true
        }
        return false
    }

    override fun status(): String {
        val kmh = (metresPerSecond * 1.35f).toInt()
        return if (done()) "Race over" else "$kmh km/h · ${(distance / goal * 100).toInt()}% · $lives ${if (lives == 1) "life" else "lives"}"
    }

    override fun hint(): String = if (distance < 10f) "Drag left and right to steer." else ""

    override fun score(): Long = distance.toLong()

    override fun draw(canvas: Canvas, width: Float, height: Float) {
        if (shake > 0f) {
            canvas.save()
            canvas.translate((rand() - 0.5f) * width * 0.02f * shake, (rand() - 0.5f) * height * 0.01f * shake)
        }
        // Night sky and town edges.
        val sky = ArcadeDraw.paint(Color.TRANSPARENT)
        sky.shader = android.graphics.LinearGradient(0f, 0f, 0f, height,
            intArrayOf(0xFF241633.toInt(), 0xFF6E2B4A.toInt(), 0xFFC55A3B.toInt()), null, android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width, height, sky)
        val horizon = height * 0.16f
        val sun = 0xFFFFCE7A.toInt()
        canvas.drawCircle(width * 0.72f, horizon - height * 0.02f, width * 0.1f, ArcadeDraw.paint(sun))

        val roadLeft = width * 0.15f
        val roadRight = width * 0.85f
        val road = Path().apply {
            moveTo(width * 0.43f, horizon)
            lineTo(width * 0.57f, horizon)
            lineTo(roadRight + width * 0.06f, height)
            lineTo(roadLeft - width * 0.06f, height)
            close()
        }
        canvas.drawPath(road, ArcadeDraw.paint(0xFF2A2733.toInt()))
        // Shoulders.
        val shoulder = ArcadeDraw.paint(0xFFE4DCCB.toInt())
        for (side in 0..1) {
            val x1 = if (side == 0) width * 0.43f else width * 0.57f
            val x2 = if (side == 0) roadLeft - width * 0.06f else roadRight + width * 0.06f
            val stripe = Path().apply {
                moveTo(x1, horizon)
                lineTo(x1 + (if (side == 0) width * 0.012f else -width * 0.012f), horizon)
                lineTo(x2 + (if (side == 0) width * 0.012f else -width * 0.012f), height)
                lineTo(x2, height)
                close()
            }
            canvas.drawPath(stripe, shoulder)
        }
        // Lane markings, scrolling with distance.
        val dashPaint = ArcadeDraw.paint(0xFFFFE6A8.toInt())
        var t = (scroll % 1f)
        while (t < 1f) {
            val y1 = horizon + (height - horizon) * (t * t)
            val y2 = horizon + (height - horizon) * ((t + 0.055f) * (t + 0.055f))
            val w1 = width * (0.004f + 0.012f * t)
            val w2 = width * (0.004f + 0.012f * (t + 0.055f))
            for (lane in intArrayOf(-1, 1)) {
                val cx1 = width * 0.5f + lane * width * (0.02f + 0.09f * t)
                val cx2 = width * 0.5f + lane * width * (0.02f + 0.09f * (t + 0.055f))
                canvas.drawRect(cx2 - w2, y1, cx2 + w2, y2, dashPaint)
                @Suppress("UNUSED_EXPRESSION")
                cx1
                w1
            }
            t += 0.17f
        }

        // Traffic.
        traffic.forEach { car ->
            val y = horizon + (height - horizon) * car.y
            val scale = 0.35f + car.y * 0.75f
            carShape(canvas, xFor(car.x, car.y, width), y, width * 0.15f * scale, car.color, false)
        }

        // Player.
        val playerY = height * 0.8f
        val blink = invulnerable > 0f && ((invulnerable * 8f).toInt() % 2 == 0)
        if (!blink) carShape(canvas, xFor(playerX, 0.86f, width), playerY, width * 0.24f, ArcadeColors.CORAL, true)

        // Progress rail.
        val rail = RectF(width * 0.08f, height * 0.055f, width * 0.92f, height * 0.069f)
        ArcadeDraw.panel(canvas, rail, 0x55000000, rail.height() / 2f)
        ArcadeDraw.panel(canvas, RectF(rail.left, rail.top, rail.left + rail.width() * (distance / goal).coerceIn(0f, 1f), rail.bottom),
            ArcadeColors.MINT, rail.height() / 2f)
        ArcadeDraw.text(canvas, "LAP ${(distance / goal * 3).toInt().coerceAtMost(3) + 1}/3", width * 0.08f, height * 0.095f,
            height * 0.014f, Color.WHITE, bold = true)
        ArcadeDraw.text(canvas, "${(metresPerSecond * 1.35f).toInt()} km/h", width * 0.92f, height * 0.095f,
            height * 0.016f, Color.WHITE, bold = true, align = Paint.Align.RIGHT)
        for (i in 0..2) {
            val filled = i < lives
            ArcadeDraw.text(canvas, if (filled) "♥" else "♡", width * 0.5f - width * 0.05f + i * width * 0.05f, height * 0.098f,
                height * 0.022f, if (filled) ArcadeColors.CORAL else 0x55FFFFFF, bold = true, align = Paint.Align.CENTER)
        }
        if (shake > 0f) canvas.restore()
    }

    /** Perspective x: lanes converge toward the horizon. */
    private fun xFor(normalized: Float, depth: Float, width: Float): Float {
        val centre = width * 0.5f
        val spread = 0.5f + depth * 0.42f
        return centre + (normalized - 0.5f) * width * spread * 1.55f
    }

    private fun carShape(canvas: Canvas, cx: Float, cy: Float, w: Float, color: Int, player: Boolean) {
        val h = w * 0.62f
        ArcadeDraw.panel(canvas, cx - w * 0.5f, cy - h * 0.55f, cx + w * 0.5f, cy + h * 0.55f, color, w * 0.2f)
        val glass = if (player) 0xCC9BE8FF.toInt() else 0xAA223344.toInt()
        ArcadeDraw.panel(canvas, cx - w * 0.34f, cy - h * 0.4f, cx + w * 0.34f, cy - h * 0.02f, glass, w * 0.09f)
        ArcadeDraw.panel(canvas, cx - w * 0.3f, cy + h * 0.08f, cx + w * 0.3f, cy + h * 0.34f, 0x33FFFFFF, w * 0.07f)
        ArcadeDraw.panel(canvas, cx - w * 0.58f, cy - h * 0.24f, cx - w * 0.4f, cy + h * 0.24f, 0xFF1A1720.toInt(), w * 0.07f)
        ArcadeDraw.panel(canvas, cx + w * 0.4f, cy - h * 0.24f, cx + w * 0.58f, cy + h * 0.24f, 0xFF1A1720.toInt(), w * 0.07f)
        if (player) {
            ArcadeDraw.circle(canvas, cx - w * 0.3f, cy + h * 0.5f, w * 0.06f, 0xFFFFF3B0.toInt())
            ArcadeDraw.circle(canvas, cx + w * 0.3f, cy + h * 0.5f, w * 0.06f, 0xFFFFF3B0.toInt())
        } else {
            ArcadeDraw.circle(canvas, cx - w * 0.28f, cy + h * 0.44f, w * 0.055f, 0xFFFF6E5A.toInt())
            ArcadeDraw.circle(canvas, cx + w * 0.28f, cy + h * 0.44f, w * 0.055f, 0xFFFF6E5A.toInt())
        }
    }
}
