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
import com.oea.games.allinone.ArcadePlayMode
import com.oea.games.allinone.ArcadeTouch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/**
 * Ping Pong — the cabinet built for two thumbs on one phone.
 *
 * Solo, the near paddle is yours and the OEA AI takes the far half. In
 * two-player mode the screen is split: the thumb that lands on the top half owns
 * the top paddle, the thumb on the bottom half owns the bottom one, and both can
 * move at once. Levels raise the ball's pace and the AI's reaction; difficulty
 * adds aim error to the AI and spin to your returns.
 */
class PingPongGame : ArcadeBaseGame() {

    private var ballX = 0.5f
    private var ballY = 0.5f
    private var vx = 0f
    private var vy = 0f
    private var speed = 0.55f
    private var paddleOne = 0.5f
    private var paddleTwo = 0.5f
    private var targetTwo = 0.5f
    private var scoreOne = 0
    private var scoreTwo = 0
    private var serveDelay = 0.9f
    private var target = 11
    private var lastHitter = 0
    private var trail = ArrayList<FloatArray>()
    private val court = RectF()
    private var paddleW = 0.2f
    private var paddleH = 0.018f
    private var ballR = 0.014f
    private var servePlayer = 0
    private var spin = 0f
    private var rallies = 0
    private var bestRally = 0

    private val twoPlayer: Boolean get() = config.mode == ArcadePlayMode.TWO_PLAYER

    override fun onStart() {
        target = 11
        speed = 0.5f + config.level * 0.032f + skill * 0.22f
        paddleW = if (twoPlayer) 0.24f else 0.2f
        paddleH = 0.018f
        ballR = 0.015f
        paddleOne = 0.5f
        paddleTwo = 0.5f
        scoreOne = 0
        scoreTwo = 0
        rallies = 0
        bestRally = 0
        servePlayer = 0
        resetServe(towardPlayerOne = false)
    }

    private fun resetServe(towardPlayerOne: Boolean) {
        ballX = 0.5f
        ballY = 0.5f
        vx = (rand() - 0.5f) * 0.35f
        vy = if (towardPlayerOne) 1f else -1f
        normalize(speed * 0.85f)
        serveDelay = if (twoPlayer) 0.55f else 0.85f
        trail.clear()
        spin = 0f
        lastHitter = 0
    }

    private fun normalize(magnitude: Float) {
        val length = max(0.0001f, kotlin.math.sqrt(vx * vx + vy * vy))
        vx = vx / length * magnitude
        vy = vy / length * magnitude
    }

    override fun update(dt: Float) {
        super.update(dt)
        if (done()) return
        if (serveDelay > 0f) {
            serveDelay -= dt
            return
        }
        ballX += vx * dt
        ballY += vy * dt
        trail.add(floatArrayOf(ballX, ballY))
        if (trail.size > 16) trail.removeAt(0)

        // Side walls.
        if (ballX < 0.03f) { ballX = 0.03f; vx = abs(vx) }
        if (ballX > 0.97f) { ballX = 0.97f; vx = -abs(vx) }

        // Paddles.
        val nearLine = 0.9f
        val farLine = 0.1f
        if (vy > 0 && ballY > nearLine - ballR && ballY < nearLine + paddleH) {
            if (abs(ballX - paddleOne) < paddleW / 2f + ballR) {
                bounce(up = true, paddleX = paddleOne)
            }
        }
        if (vy < 0 && ballY < farLine + ballR && ballY > farLine - paddleH) {
            val paddle = paddleTwo
            if (abs(ballX - paddle) < paddleW / 2f + ballR) {
                bounce(up = false, paddleX = paddle)
            }
        }

        if (ballY > 1.03f) point(toPlayerOne = false)
        if (ballY < -0.03f) point(toPlayerOne = true)

        // The AI eases toward where it thinks the ball will arrive.
        if (!twoPlayer) {
            targetTwo = predict()
            val reach = (0.28f + skill * 0.75f) * dt * (1f + config.level * 0.03f)
            paddleTwo += (targetTwo - paddleTwo).coerceIn(-reach, reach)
            paddleTwo = paddleTwo.coerceIn(paddleW / 2f, 1f - paddleW / 2f)
        }
    }

    private fun bounce(up: Boolean, paddleX: Float) {
        val offset = (ballX - paddleX) / (paddleW / 2f)
        val pace = min(1.6f, speed + 0.03f * rallies * 0.1f + config.level * 0.012f)
        vx = offset * 0.55f + spin * 0.35f
        vy = if (up) -abs(vy) else abs(vy)
        normalize(pace)
        ballY = if (up) 0.9f - ballR else 0.1f + ballR
        rallies++
        bestRally = max(bestRally, rallies)
        spin *= 0.4f
        lastHitter = if (up) 1 else 2
    }

    private fun point(toPlayerOne: Boolean) {
        if (toPlayerOne) scoreOne++ else scoreTwo++
        rallies = 0
        val doneScore = (scoreOne >= target || scoreTwo >= target) && abs(scoreOne - scoreTwo) >= 2
        if (doneScore) {
            val won = scoreOne > scoreTwo
            val score = scoreOne * 100L + bestRally * 10
            finish(
                won = won,
                headline = if (twoPlayer) (if (won) "Near side wins" else "Far side wins") else if (won) "You win the match" else "The AI takes it",
                detail = "$scoreOne — $scoreTwo. Longest rally: $bestRally hits. " +
                    if (won) "The next level adds pace; keep the paddle in front of the ball." else "Move earlier: the ball's angle is set by where it hits your paddle.",
                score = score,
                stars = if (won) 3 else 1
            )
            return
        }
        // ITTF service rotation: every two points, and every point at 10-10.
        val total = scoreOne + scoreTwo
        val every = if (scoreOne >= 10 && scoreTwo >= 10) 1 else 2
        if (total % every == 0) servePlayer = 1 - servePlayer
        resetServe(towardPlayerOne = servePlayer == 1)
    }

    private fun predict(): Float {
        if (vy <= 0f) return 0.5f
        val time = (0.9f - ballY) / max(0.0001f, vy)
        var x = ballX + vx * time
        // Reflect the prediction off the side walls.
        val span = 0.94f
        x -= 0.03f
        x = ((x % (span * 2f)) + span * 2f) % (span * 2f)
        if (x > span) x = span * 2f - x
        x += 0.03f
        val error = (rand() - 0.5f) * (0.32f - skill * 0.26f)
        return (x + error).coerceIn(paddleW / 2f, 1f - paddleW / 2f)
    }

    override fun touch(phase: ArcadeTouch, x: Float, y: Float, width: Float, height: Float): Boolean {
        if (done()) return false
        val nx = (x / width).coerceIn(0f, 1f)
        val ny = (y / height).coerceIn(0f, 1f)
        if (twoPlayer) {
            if (ny > 0.5f) {
                val previous = paddleOne
                paddleOne = nx.coerceIn(paddleW / 2f, 1f - paddleW / 2f)
                spin = ((paddleOne - previous) * 24f).coerceIn(-1f, 1f)
            } else {
                paddleTwo = nx.coerceIn(paddleW / 2f, 1f - paddleW / 2f)
            }
        } else {
            val previous = paddleOne
            paddleOne = nx.coerceIn(paddleW / 2f, 1f - paddleW / 2f)
            spin = ((paddleOne - previous) * 24f).coerceIn(-1f, 1f)
        }
        return true
    }

    override fun status(): String {
        if (done()) return "Match over"
        return if (twoPlayer) {
            "P1 $scoreOne — $scoreTwo P2 · serve ${if (servePlayer == 0) "P1" else "P2"} · first to $target, two clear"
        } else {
            "You $scoreOne — $scoreTwo OEA AI · ${if (servePlayer == 0) "your" else "AI"} serve · first to $target"
        }
    }

    override fun hint(): String = if (serveDelay > 0f) "Get ready — the serve is on its way." else ""

    override fun draw(canvas: Canvas, width: Float, height: Float) {
        val court = RectF(0f, 0f, width, height)
        this.court.set(court)
        val bg = ArcadeDraw.paint(0xFF0E2233.toInt())
        bg.shader = RadialGradient(width * 0.5f, height * 0.5f, width * 0.9f,
            intArrayOf(0xFF1D5E7A.toInt(), 0xFF0B1B29.toInt()), null, Shader.TileMode.CLAMP)
        canvas.drawRect(court, bg)

        // Table markings.
        val lineColor = 0x55E8FBFF
        val inset = width * 0.045f
        val table = RectF(inset, height * 0.09f, width - inset, height * 0.91f)
        ArcadeDraw.panel(canvas, table, 0x10FFFFFF, width * 0.03f, lineColor, width * 0.006f)
        val netY = table.centerY()
        canvas.drawLine(table.left, netY, table.right, netY, ArcadeDraw.paint(0x99FFFFFF.toInt(), width * 0.005f))
        for (i in 0 until 22) {
            val x = table.left + (i / 22f) * table.width()
            if (i % 2 == 0) canvas.drawLine(x, netY - width * 0.008f, x + table.width() / 44f, netY - width * 0.008f,
                ArcadeDraw.paint(0x55FFFFFF, width * 0.002f))
        }

        fun mapX(value: Float) = table.left + value * table.width()
        fun mapY(value: Float) = table.top + value * table.height()

        // Ball trail.
        trail.forEachIndexed { index, point ->
            val alpha = (index + 1) * 10
            canvas.drawCircle(mapX(point[0]), mapY(point[1]), width * ballR * (0.35f + index / 30f),
                ArcadeDraw.paint(Color.argb(alpha, 255, 255, 255)))
        }

        // Paddles.
        val paddlePixelW = paddleW * table.width()
        val paddlePixelH = paddleH * table.height() * 2.4f
        ArcadeDraw.panel(canvas, mapX(paddleOne) - paddlePixelW / 2f, mapY(0.9f),
            mapX(paddleOne) + paddlePixelW / 2f, mapY(0.9f) + paddlePixelH,
            ArcadeColors.CORAL, paddlePixelH / 2f)
        ArcadeDraw.panel(canvas, mapX(paddleTwo) - paddlePixelW / 2f, mapY(0.1f) - paddlePixelH,
            mapX(paddleTwo) + paddlePixelW / 2f, mapY(0.1f),
            ArcadeColors.MINT, paddlePixelH / 2f)

        // Ball.
        val ballPixel = width * ballR
        val glow = ArcadeDraw.paint(Color.TRANSPARENT)
        glow.shader = RadialGradient(mapX(ballX), mapY(ballY), ballPixel * 3.4f,
            intArrayOf(0x88FFFFFF.toInt(), 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(mapX(ballX), mapY(ballY), ballPixel * 3.4f, glow)
        canvas.drawCircle(mapX(ballX), mapY(ballY), ballPixel, ArcadeDraw.paint(Color.WHITE))
        if (serveDelay > 0f) {
            ArcadeDraw.text(canvas, "SERVE", mapX(0.5f), mapY(0.5f) - ballPixel * 4f, width * 0.035f,
                0x99FFFFFF.toInt(), bold = true, align = Paint.Align.CENTER)
        }

        // Scoreboard.
        ArcadeDraw.text(canvas, scoreOne.toString(), width * 0.5f, height * 0.075f, height * 0.035f, Color.WHITE, bold = true, align = Paint.Align.CENTER)
        ArcadeDraw.text(canvas, scoreTwo.toString(), width * 0.5f, height * 0.955f, height * 0.035f, 0xCCFFFFFF.toInt(), bold = true, align = Paint.Align.CENTER)
        if (twoPlayer) {
            ArcadeDraw.text(canvas, "P2", width * 0.12f, height * 0.075f, height * 0.016f, ArcadeColors.MINT, bold = true)
            ArcadeDraw.text(canvas, "P1", width * 0.12f, height * 0.955f, height * 0.016f, ArcadeColors.CORAL, bold = true)
        } else {
            ArcadeDraw.text(canvas, "OEA AI", width * 0.12f, height * 0.075f, height * 0.016f, ArcadeColors.MINT, bold = true)
            ArcadeDraw.text(canvas, "YOU", width * 0.12f, height * 0.955f, height * 0.016f, ArcadeColors.CORAL, bold = true)
        }
        val mid = height * 0.5f
        ArcadeDraw.text(canvas, "${config.difficulty.label.uppercase()} · RALLY $rallies", width * 0.5f, mid + height * 0.01f,
            height * 0.012f, 0x66FFFFFF, bold = true, align = Paint.Align.CENTER)
    }
}
