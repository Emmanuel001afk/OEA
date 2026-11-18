package com.oea.games.allinone

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface

/**
 * Drawing helpers shared by the hub and every cabinet.
 *
 * The arcade deliberately does not look like a photo album or a stock game kit:
 * everything on screen is drawn with paths and paint, so a tile is crisp at any
 * size, an icon costs no download, and a game's own materials (felt, boards,
 * cards, roads, torchlight) stay consistent across the wall.
 */
object ArcadeDraw {

    fun paint(color: Int, stroke: Float = 0f): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = if (stroke > 0f) Paint.Style.STROKE else Paint.Style.FILL
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private fun typeface(bold: Boolean, mono: Boolean): Typeface = when {
        mono -> Typeface.create("monospace", Typeface.BOLD)
        bold -> Typeface.create("sans-serif", Typeface.BOLD)
        else -> Typeface.create("sans-serif", Typeface.NORMAL)
    }

    fun text(
        c: Canvas,
        value: String,
        x: Float,
        y: Float,
        size: Float,
        color: Int,
        bold: Boolean = false,
        align: Paint.Align = Paint.Align.LEFT,
        mono: Boolean = false,
    ) {
        val p = paint(color)
        p.textSize = size
        p.textAlign = align
        p.typeface = typeface(bold, mono)
        c.drawText(value, x, y, p)
    }

    fun measure(value: String, size: Float, bold: Boolean = false, mono: Boolean = false): Float {
        val p = paint(Color.WHITE)
        p.textSize = size
        p.typeface = typeface(bold, mono)
        return p.measureText(value)
    }

    /** Text scaled down until it fits [maxWidth]; never smaller than [minSize]. */
    fun fitted(value: String, size: Float, maxWidth: Float, bold: Boolean = false, minSize: Float = 8f): Float {
        var s = size
        while (s > minSize && measure(value, s, bold) > maxWidth) s -= 0.5f
        return s
    }

    fun panel(c: Canvas, l: Float, t: Float, r: Float, b: Float, color: Int, radius: Float, stroke: Int = 0, strokeWidth: Float = 0f) {
        c.drawRoundRect(RectF(l, t, r, b), radius, radius, paint(color))
        if (strokeWidth > 0f) c.drawRoundRect(RectF(l, t, r, b), radius, radius, paint(stroke, strokeWidth))
    }

    fun panel(c: Canvas, rect: RectF, color: Int, radius: Float, stroke: Int = 0, strokeWidth: Float = 0f) =
        panel(c, rect.left, rect.top, rect.right, rect.bottom, color, radius, stroke, strokeWidth)

    fun circle(c: Canvas, cx: Float, cy: Float, r: Float, color: Int, stroke: Int = 0, strokeWidth: Float = 0f) {
        c.drawCircle(cx, cy, r, paint(color))
        if (strokeWidth > 0f) c.drawCircle(cx, cy, r, paint(stroke, strokeWidth))
    }

    fun line(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, color: Int, width: Float) {
        c.drawLine(x1, y1, x2, y2, paint(color, width))
    }

    fun shadow(c: Canvas, rect: RectF, radius: Float, alpha: Float = 0.16f) {
        val p = paint(Color.argb((255 * alpha).toInt().coerceIn(0, 255), 60, 40, 20))
        p.setShadowLayer(radius * 0.35f, 0f, radius * 0.16f, p.color)
        c.drawRoundRect(rect, radius, radius, p)
    }

    /** Wraps text into lines that fit [maxWidth]. */
    fun wrap(value: String, size: Float, maxWidth: Float, bold: Boolean = false): List<String> {
        val p = paint(Color.WHITE)
        p.textSize = size
        p.typeface = typeface(bold, false)
        val out = ArrayList<String>()
        for (paragraph in value.split('\n')) {
            if (paragraph.isBlank()) {
                out += ""
                continue
            }
            var line = ""
            for (word in paragraph.trim().split(' ')) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (p.measureText(candidate) <= maxWidth || line.isEmpty()) {
                    line = candidate
                } else {
                    out += line
                    line = word
                }
            }
            if (line.isNotEmpty()) out += line
        }
        return out
    }

    /** Draws wrapped text and returns the y after the last line. */
    fun paragraph(
        c: Canvas,
        value: String,
        x: Float,
        y: Float,
        size: Float,
        color: Int,
        maxWidth: Float,
        bold: Boolean = false,
        lineGap: Float = 1.34f,
    ): Float {
        var cursor = y
        for (line in wrap(value, size, maxWidth, bold)) {
            text(c, line, x, cursor, size, color, bold)
            cursor += size * lineGap
        }
        return cursor
    }

    /** A gradient-filled tile, used behind every cabinet picture. */
    fun gradient(c: Canvas, rect: RectF, radius: Float, top: Int, bottom: Int) {
        val p = paint(top)
        p.shader = LinearGradient(rect.left, rect.top, rect.left, rect.bottom, top, bottom, Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, radius, radius, p)
        p.shader = null
    }

    /**
     * The arcade's human figure. Where a scene needs a person — an archer, a
     * worker, someone hiding — it is this stickman rather than a badly drawn
     * imitation of a person. Clean, readable and honest about being a drawing.
     *
     * [x],[y] is between the feet; [height] is head-to-toe.
     */
    fun stickman(c: Canvas, x: Float, y: Float, height: Float, color: Int, pose: Int = 0, width: Float = 0f) {
        val pen = paint(color, (width.takeIf { it > 0f } ?: height * 0.075f))
        val headR = height * 0.13f
        val headY = y - height + headR
        c.drawCircle(x, headY, headR, paint(color))
        val neck = headY + headR
        val hip = y - height * 0.42f
        val shoulder = neck + height * 0.04f
        c.drawLine(x, neck, x, hip, pen)
        val armLen = height * 0.26f
        when (pose) {
            1 -> { // walking
                c.drawLine(x, shoulder, x - armLen * 0.9f, shoulder + armLen * 0.7f, pen)
                c.drawLine(x, shoulder, x + armLen * 0.8f, shoulder + armLen * 0.4f, pen)
                c.drawLine(x, hip, x - armLen * 0.75f, y - height * 0.03f, pen)
                c.drawLine(x, hip, x + armLen * 0.9f, y, pen)
            }
            2 -> { // drawing a bow / throwing
                c.drawLine(x, shoulder, x - armLen, shoulder, pen)
                c.drawLine(x, shoulder, x + armLen * 0.2f, shoulder - armLen * 0.35f, pen)
                c.drawLine(x, hip, x - armLen * 0.55f, y, pen)
                c.drawLine(x, hip, x + armLen * 0.65f, y, pen)
            }
            3 -> { // scared, arms up
                c.drawLine(x, shoulder, x - armLen * 0.85f, shoulder - armLen * 0.6f, pen)
                c.drawLine(x, shoulder, x + armLen * 0.85f, shoulder - armLen * 0.6f, pen)
                c.drawLine(x, hip, x - armLen * 0.5f, y, pen)
                c.drawLine(x, hip, x + armLen * 0.5f, y, pen)
            }
            else -> { // standing, one arm out
                c.drawLine(x, shoulder, x - armLen * 0.65f, shoulder + armLen * 0.75f, pen)
                c.drawLine(x, shoulder, x + armLen * 0.8f, shoulder + armLen * 0.5f, pen)
                c.drawLine(x, hip, x - armLen * 0.4f, y, pen)
                c.drawLine(x, hip, x + armLen * 0.4f, y, pen)
            }
        }
    }

    /** A playing card. [symbol] is drawn in [suitColor]; face-down cards keep one colour. */
    fun card(c: Canvas, rect: RectF, rank: String, symbol: String, suitColor: Int, faceUp: Boolean = true, backColor: Int = ArcadeColors.PLUM) {
        val radius = rect.width() * 0.12f
        if (!faceUp) {
            panel(c, rect, backColor, radius)
            val inner = RectF(rect.left + rect.width() * 0.1f, rect.top + rect.height() * 0.08f,
                rect.right - rect.width() * 0.1f, rect.bottom - rect.height() * 0.08f)
            panel(c, inner, Color.argb(70, 255, 255, 255), radius * 0.7f)
            text(c, "OEA", rect.centerX(), rect.centerY() + rect.height() * 0.06f, rect.height() * 0.17f,
                ArcadeColors.WHITE, bold = true, align = Paint.Align.CENTER)
            return
        }
        panel(c, rect, ArcadeColors.WHITE, radius)
        panel(c, rect, Color.TRANSPARENT, radius, Color.argb(28, 0, 0, 0), 1f)
        val s = rect.height() * 0.2f
        text(c, rank, rect.left + rect.width() * 0.09f, rect.top + rect.height() * 0.24f, s, suitColor, bold = true)
        text(c, symbol, rect.left + rect.width() * 0.09f, rect.top + rect.height() * 0.29f + s, s * 0.92f, suitColor, bold = true)
        text(c, symbol, rect.centerX(), rect.centerY() + s * 0.4f, s * 1.5f, suitColor, bold = true, align = Paint.Align.CENTER)
    }

    /** A filled, labelled button; games hit-test it with [inside]. */
    fun button(c: Canvas, rect: RectF, label: String, bg: Int, fg: Int, size: Float = rect.height() * 0.38f) {
        panel(c, rect, bg, rect.height() * 0.34f)
        val fitted = fitted(label, size, rect.width() - rect.width() * 0.12f, bold = true, minSize = size * 0.6f)
        text(c, label, rect.centerX(), rect.centerY() + fitted * 0.36f, fitted, fg, bold = true, align = Paint.Align.CENTER)
    }

    fun inside(rect: RectF, x: Float, y: Float): Boolean =
        x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom

    /** A die with pips; a six always reads at a glance. */
    fun die(c: Canvas, x: Float, y: Float, size: Float, value: Int, face: Int = ArcadeColors.WHITE, pip: Int = ArcadeColors.INK) {
        panel(c, x, y, x + size, y + size, face, size * 0.22f, Color.argb(30, 0, 0, 0), 1.5f)
        val r = size * 0.085f
        val a = size * 0.26f
        val b = size * 0.5f
        val d = size * 0.74f
        fun p(px: Float, py: Float) = circle(c, x + px, y + py, r, pip)
        val v = value.coerceIn(1, 6)
        if (v == 1 || v == 3 || v == 5) p(b, b)
        if (v >= 2) { p(a, a); p(d, d) }
        if (v >= 4) { p(d, a); p(a, d) }
        if (v == 6) { p(a, b); p(d, b) }
    }

    /** A ball with an optional number, used by pool, snooker and ludo. */
    fun ball(c: Canvas, cx: Float, cy: Float, r: Float, color: Int, number: Int = 0, label: Int = ArcadeColors.WHITE) {
        circle(c, cx, cy, r, color)
        circle(c, cx - r * 0.3f, cy - r * 0.32f, r * 0.28f, Color.argb(90, 255, 255, 255))
        if (number > 0) {
            circle(c, cx, cy, r * 0.52f, ArcadeColors.WHITE)
            text(c, number.toString(), cx, cy + r * 0.2f, r * 0.62f, ArcadeColors.INK, bold = true, align = Paint.Align.CENTER)
        }
        circle(c, cx, cy, r, Color.TRANSPARENT, Color.argb(45, 0, 0, 0), 1.2f)
    }

    /**
     * A cabinet's front-of-box picture, drawn inside [rect]. These are the tiles
     * on the wall, so each scene is built to be recognised at a glance from
     * across a room: a road, a board, a fan of cards, a pair of eyes in a maze.
     */
    fun art(c: Canvas, kind: ArcadeArtKind, rect: RectF, accent: Int) {
        c.save()
        val clip = Path().apply { addRoundRect(rect, rect.width() * 0.06f, rect.width() * 0.06f, Path.Direction.CW) }
        c.clipPath(clip)
        when (kind) {
            ArcadeArtKind.RACING -> racingArt(c, rect, accent)
            ArcadeArtKind.DARTS -> dartsArt(c, rect)
            ArcadeArtKind.ARCHERY -> archeryArt(c, rect)
            ArcadeArtKind.BLOCKS -> blocksArt(c, rect)
            ArcadeArtKind.WORD -> wordArt(c, rect)
            ArcadeArtKind.WHOT -> whotArt(c, rect)
            ArcadeArtKind.SOLITAIRE -> solitaireArt(c, rect)
            ArcadeArtKind.LUDO -> ludoArt(c, rect)
            ArcadeArtKind.LIFE -> lifeArt(c, rect)
            ArcadeArtKind.MEMORY -> memoryArt(c, rect)
            ArcadeArtKind.TICTACTOE -> ticTacToeArt(c, rect)
            ArcadeArtKind.POOL, ArcadeArtKind.SNOOKER -> poolArt(c, rect, kind == ArcadeArtKind.SNOOKER)
            ArcadeArtKind.HIDE -> hideArt(c, rect)
            ArcadeArtKind.PINGPONG -> pingPongArt(c, rect)
            ArcadeArtKind.PUZZLE -> puzzleArt(c, rect)
            ArcadeArtKind.QUIZ -> quizArt(c, rect)
            ArcadeArtKind.CHESS -> chessArt(c, rect)
            ArcadeArtKind.FARM -> farmArt(c, rect)
        }
        c.restore()
    }

    // ------------------------------------------------------------- tile scenes

    private fun racingArt(c: Canvas, r: RectF, accent: Int) {
        gradient(c, r, 0f, 0xFF2B1B44.toInt(), 0xFFE2583E.toInt())
        val sun = 0xFFFFD07A.toInt()
        c.drawCircle(r.left + r.width() * 0.72f, r.top + r.height() * 0.3f, r.width() * 0.16f, paint(sun))
        // Distant hills.
        val hill = Path().apply {
            moveTo(r.left, r.top + r.height() * 0.56f)
            lineTo(r.left + r.width() * 0.28f, r.top + r.height() * 0.34f)
            lineTo(r.left + r.width() * 0.52f, r.top + r.height() * 0.56f)
            lineTo(r.left + r.width() * 0.74f, r.top + r.height() * 0.38f)
            lineTo(r.right, r.top + r.height() * 0.56f)
            lineTo(r.right, r.bottom)
            lineTo(r.left, r.bottom)
            close()
        }
        c.drawPath(hill, paint(0xFF3A2350.toInt()))
        // Road.
        val road = Path().apply {
            moveTo(r.left + r.width() * 0.35f, r.top + r.height() * 0.58f)
            lineTo(r.left + r.width() * 0.65f, r.top + r.height() * 0.58f)
            lineTo(r.right + r.width() * 0.08f, r.bottom)
            lineTo(r.left - r.width() * 0.08f, r.bottom)
            close()
        }
        c.drawPath(road, paint(0xFF2F2B36.toInt()))
        var t = 0f
        while (t < 1f) {
            val y1 = r.top + r.height() * (0.58f + 0.42f * t)
            val y2 = r.top + r.height() * (0.58f + 0.42f * (t + 0.07f))
            val w1 = r.width() * (0.006f + 0.016f * t)
            val w2 = r.width() * (0.006f + 0.016f * (t + 0.07f))
            c.drawRect(r.centerX() - w2, y1, r.centerX() + w2, y2, paint(0xFFFFE8B0.toInt()))
            t += 0.15f
        }
        // Rival ahead and the player's car.
        carShape(c, r.centerX() - r.width() * 0.06f, r.top + r.height() * 0.64f, r.width() * 0.12f, 0xFF7BD3FF.toInt())
        carShape(c, r.centerX(), r.bottom - r.height() * 0.16f, r.width() * 0.3f, accent)
    }

    private fun carShape(c: Canvas, cx: Float, cy: Float, w: Float, color: Int) {
        val h = w * 0.58f
        panel(c, cx - w * 0.5f, cy - h * 0.5f, cx + w * 0.5f, cy + h * 0.5f, color, w * 0.16f)
        panel(c, cx - w * 0.32f, cy - h * 0.72f, cx + w * 0.32f, cy - h * 0.18f, Color.argb(150, 255, 255, 255), w * 0.1f)
        circle(c, cx - w * 0.34f, cy + h * 0.52f, w * 0.11f, 0xFF1E1B24.toInt())
        circle(c, cx + w * 0.34f, cy + h * 0.52f, w * 0.11f, 0xFF1E1B24.toInt())
        circle(c, cx - w * 0.36f, cy + h * 0.32f, w * 0.055f, 0xFFFFF2B0.toInt())
        circle(c, cx + w * 0.36f, cy + h * 0.32f, w * 0.055f, 0xFFFFF2B0.toInt())
    }

    private fun dartsArt(c: Canvas, r: RectF) {
        gradient(c, r, 0f, 0xFF241826.toInt(), 0xFF4A2036.toInt())
        val cx = r.centerX()
        val cy = r.top + r.height() * 0.5f
        val radius = minOf(r.width(), r.height()) * 0.42f
        circle(c, cx, cy, radius, 0xFF1F1A22.toInt())
        circle(c, cx, cy, radius * 0.88f, 0xFFE8D8C4.toInt())
        val sectors = 20
        val colors = intArrayOf(0xFF1E1B22.toInt(), 0xFFE8D8C4.toInt())
        val alt = intArrayOf(0xFF2E7D5B.toInt(), 0xFFB7384B.toInt())
        for (i in 0 until sectors) {
            val start = -90f + i * (360f / sectors)
            val sweep = 360f / sectors
            val p = paint(if (i % 2 == 0) halfColor(colors[0]) else halfColor(colors[1]))
            c.drawArc(RectF(cx - radius * 0.88f, cy - radius * 0.88f, cx + radius * 0.88f, cy + radius * 0.88f), start, sweep, true, p)
            val q = paint(if (i % 2 == 0) alt[0] else alt[1])
            c.drawArc(RectF(cx - radius * 0.66f, cy - radius * 0.66f, cx + radius * 0.66f, cy + radius * 0.66f), start, sweep, true, q)
        }
        circle(c, cx, cy, radius * 0.56f, 0xFF1E1B22.toInt())
        circle(c, cx, cy, radius * 0.54f, 0xFFE8D8C4.toInt())
        circle(c, cx, cy, radius * 0.34f, 0xFF2E7D5B.toInt())
        circle(c, cx, cy, radius * 0.3f, 0xFFB7384B.toInt())
        circle(c, cx, cy, radius * 0.1f, 0xFF2E7D5B.toInt())
        circle(c, cx, cy, radius * 0.07f, 0xFFFF5C6C.toInt())
        // A dart in the treble twenty.
        val tipX = cx - radius * 0.42f
        val tipY = cy - radius * 0.42f
        line(c, tipX, tipY, tipX + radius * 0.42f, tipY + radius * 0.42f, 0xFFBFC6D4.toInt(), radius * 0.03f)
        val flight = Path().apply {
            moveTo(tipX + radius * 0.42f, tipY + radius * 0.42f)
            lineTo(tipX + radius * 0.58f, tipY + radius * 0.36f)
            lineTo(tipX + radius * 0.5f, tipY + radius * 0.56f)
            close()
        }
        c.drawPath(flight, paint(0xFFFFC24B.toInt()))
    }

    private fun halfColor(color: Int): Int = Color.argb(150, Color.red(color), Color.green(color), Color.blue(color))

    private fun archeryArt(c: Canvas, r: RectF) {
        gradient(c, r, 0f, 0xFF8ED8E8.toInt(), 0xFFD9F0C8.toInt())
        // Distant hills and the target.
        val hill = Path().apply {
            moveTo(r.left, r.top + r.height() * 0.72f)
            quadTo(r.left + r.width() * 0.35f, r.top + r.height() * 0.5f, r.right, r.top + r.height() * 0.76f)
            lineTo(r.right, r.bottom)
            lineTo(r.left, r.bottom)
            close()
        }
        c.drawPath(hill, paint(0xFF9BD07A.toInt()))
        val tx = r.left + r.width() * 0.68f
        val ty = r.top + r.height() * 0.42f
        val tr = r.width() * 0.19f
        val rings = listOf(
            0xFFF7F7F2.toInt() to 1f, 0xFFF7F7F2.toInt() to 0.86f,
            0xFF2E4C8A.toInt() to 0.7f, 0xFF2E4C8A.toInt() to 0.56f,
            0xFFD94F4F.toInt() to 0.42f, 0xFFD94F4F.toInt() to 0.3f,
            0xFFFFC24B.toInt() to 0.18f,
        )
        rings.forEachIndexed { i, (color, scale) ->
            if (i % 2 == 1 || i == 0) circle(c, tx, ty, tr * scale, color)
        }
        circle(c, tx, ty, tr, Color.TRANSPARENT, 0xFF8A6A4F.toInt(), tr * 0.05f)
        // Arrow already in the gold.
        line(c, tx, ty, tx + tr * 0.9f, ty + tr * 0.5f, 0xFF5B4636.toInt(), tr * 0.05f)
        // Archer.
        stickman(c, r.left + r.width() * 0.22f, r.bottom - r.height() * 0.09f, r.height() * 0.52f, 0xFF2B2635.toInt(), pose = 2)
        val bow = Path().apply {
            arcTo(RectF(r.left + r.width() * 0.03f, r.top + r.height() * 0.43f, r.left + r.width() * 0.2f, r.top + r.height() * 0.73f), -90f, 180f, false)
        }
        c.drawPath(bow, paint(0xFF7A4E2D.toInt(), r.width() * 0.02f))
    }

    private fun blocksArt(c: Canvas, r: RectF) {
        gradient(c, r, 0f, 0xFF2A2438.toInt(), 0xFF41385A.toInt())
        val cell = r.width() / 9f
        val colors = intArrayOf(ArcadeColors.PINK, ArcadeColors.GOLD, ArcadeColors.MINT, ArcadeColors.SKY, ArcadeColors.LILAC)
        val layout = listOf(
            Triple(1, 5, 2), Triple(3, 5, 1), Triple(4, 4, 3), Triple(7, 6, 1),
            Triple(1, 6, 1), Triple(2, 6, 1), Triple(3, 6, 2), Triple(5, 5, 1), Triple(6, 5, 2),
        )
        layout.forEachIndexed { i, (x, y, w) ->
            repeat(w) { k ->
                val l = r.left + cell * (x + k)
                val t = r.top + cell * y
                panel(c, l + cell * 0.06f, t + cell * 0.06f, l + cell * 0.94f, t + cell * 0.94f,
                    colors[i % colors.size], cell * 0.2f)
            }
        }
        // A piece being dropped.
        panel(c, r.left + cell * 5.2f, r.top + cell * 2.2f, r.left + cell * 6.8f, r.top + cell * 3.8f,
            ArcadeColors.CORAL, cell * 0.22f, ArcadeColors.WHITE, cell * 0.06f)
    }

    private fun wordArt(c: Canvas, r: RectF) {
        gradient(c, r, 0f, 0xFF3A5BD9.toInt(), 0xFF7FD8F0.toInt())
        val cols = 5
        val rows = 4
        val cell = minOf(r.width() / (cols + 1.4f), r.height() / (rows + 1.2f))
        val left = r.centerX() - cell * cols / 2f
        val top = r.centerY() - cell * rows / 2f
        val letters = listOf("OEAGM", "RDLNO", "AWORD", "FINDX")
        for (y in 0 until rows) {
            for (x in 0 until cols) {
                val l = left + x * cell
                val t = top + y * cell
                val lit = (y == 2 && x in 0..3)
                panel(c, l + cell * 0.06f, t + cell * 0.06f, l + cell * 0.94f, t + cell * 0.94f,
                    if (lit) ArcadeColors.CORAL else Color.argb(235, 255, 255, 255), cell * 0.16f)
                text(c, letters[y][x].toString(), l + cell / 2f, t + cell * 0.68f, cell * 0.52f,
                    if (lit) ArcadeColors.WHITE else ArcadeColors.INK, bold = true, align = Paint.Align.CENTER)
            }
        }
        // The finger swiping the word.
        circle(c, left + cell * 2.2f, top + cell * 2.5f, cell * 0.2f, ArcadeColors.WHITE)
    }

    private fun whotArt(c: Canvas, r: RectF) {
        gradient(c, r, 0f, 0xFF3C1F45.toInt(), 0xFF8A2E5B.toInt())
        val w = r.width() * 0.34f
        val h = w * 1.42f
        val cx = r.centerX()
        val cy = r.centerY() + h * 0.1f
        val cards = listOf(
            Triple(-1, -0.32f, 0xFFE3566E.toInt()) to "★",
            Triple(0, 0f, 0xFFF6C64B.toInt()) to "▲",
            Triple(1, 0.32f, 0xFF5AC8E8.toInt()) to "●",
        )
        cards.forEach { (data, symbol) ->
            val (slot, _, back) = data
            c.save()
            c.rotate(slot * 13f, cx, cy)
            val rect = RectF(cx - w / 2f + slot * w * 0.34f, cy - h / 2f, cx + w / 2f + slot * w * 0.34f, cy + h / 2f)
            panel(c, rect, ArcadeColors.WHITE, w * 0.12f)
            panel(c, rect.left + w * 0.06f, rect.top + h * 0.05f, rect.right - w * 0.06f, rect.bottom - h * 0.05f,
                ArcadeColors.WHITE, w * 0.1f, Color.argb(60, 0, 0, 0), 1f)
            text(c, symbol, rect.centerX(), rect.centerY() + w * 0.22f, w * 0.7f, back, bold = true, align = Paint.Align.CENTER)
            text(c, (7 + slot * 3).toString(), rect.left + w * 0.13f, rect.top + h * 0.24f, w * 0.28f, back, bold = true)
            c.restore()
        }
    }

    private fun solitaireArt(c: Canvas, r: RectF) {
        gradient(c, r, 0f, 0xFF1E5B45.toInt(), 0xFF2F8C63.toInt())
        val w = r.width() * 0.2f
        val h = w * 1.4f
        val cardColors = intArrayOf(0xFFE3566E.toInt(), 0xFF2B2635.toInt(), 0xFF2B2635.toInt(), 0xFFE3566E.toInt())
        for (col in 0..3) {
            val x = r.left + r.width() * 0.08f + col * w * 1.12f
            val baseY = r.top + r.height() * (0.18f + col * 0.04f)
            for (row in 0..(2 + col % 2)) {
                val rect = RectF(x, baseY + row * h * 0.2f, x + w, baseY + row * h * 0.2f + h)
                val up = row == 2 + col % 2
                card(c, rect, listOf("A", "7", "K", "4")[col], if (col % 2 == 0) "♥" else "♠",
                    cardColors[col], faceUp = up)
            }
        }
    }

    private fun ludoArt(c: Canvas, r: RectF) {
        panel(c, r, 0xFFF7EFE3.toInt(), 0f)
        val size = minOf(r.width(), r.height()) * 0.92f
        val left = r.centerX() - size / 2f
        val top = r.centerY() - size / 2f
        val grid = size / 3f
        val colors = intArrayOf(0xFFE3566E.toInt(), 0xFF43C08B.toInt(), 0xFFF6C64B.toInt(), 0xFF5AA6E8.toInt())
        val quad = listOf(left to top, left + grid * 2 to top, left to top + grid * 2, left + grid * 2 to top + grid * 2)
        quad.forEachIndexed { i, (x, y) ->
            panel(c, x, y, x + grid, y + grid, colors[i], size * 0.03f)
            panel(c, x + grid * 0.2f, y + grid * 0.2f, x + grid * 0.8f, y + grid * 0.8f, Color.argb(220, 255, 255, 255), size * 0.02f)
            circle(c, x + grid / 2f, y + grid / 2f, size * 0.045f, colors[i])
        }
        // Cross paths.
        panel(c, left + grid, top, left + grid * 2, top + grid * 3, 0xFFFFFFFF.toInt(), 0f)
        panel(c, left, top + grid, left + grid * 3, top + grid * 2, 0xFFFFFFFF.toInt(), 0f)
        for (i in 0 until 6) {
            val p = size / 6f
            line(c, left + grid + i * p, top, left + grid + i * p, top + grid, Color.argb(50, 0, 0, 0), 1.5f)
            line(c, left + grid + i * p, top + grid * 2, left + grid + i * p, top + grid * 3, Color.argb(50, 0, 0, 0), 1.5f)
            line(c, left, top + grid + i * p, left + grid, top + grid + i * p, Color.argb(50, 0, 0, 0), 1.5f)
            line(c, left + grid * 2, top + grid + i * p, left + grid * 3, top + grid + i * p, Color.argb(50, 0, 0, 0), 1.5f)
        }
        // Centre and a token plus die.
        val tri = Path().apply {
            moveTo(left + grid, top + grid); lineTo(left + grid * 1.5f, top + grid * 1.5f)
            lineTo(left + grid, top + grid * 2); close()
        }
        c.drawPath(tri, paint(0xFFE3566E.toInt()))
        circle(c, left + grid * 2.2f, top + grid * 1.6f, size * 0.055f, colors[0])
        circle(c, left + grid * 2.2f, top + grid * 1.6f, size * 0.055f, Color.TRANSPARENT, ArcadeColors.WHITE, 2f)
        die(c, r.right - size * 0.28f, r.bottom - size * 0.3f, size * 0.22f, 6)
    }

    private fun lifeArt(c: Canvas, r: RectF) {
        gradient(c, r, 0f, 0xFFB7EFDA.toInt(), 0xFFFFE2A8.toInt())
        // A sun, a city line and a person with a future.
        circle(c, r.right - r.width() * 0.18f, r.top + r.height() * 0.22f, r.width() * 0.1f, 0xFFFFC24B.toInt())
        panel(c, r.left + r.width() * 0.04f, r.top + r.height() * 0.52f, r.left + r.width() * 0.22f, r.bottom, Color.argb(60, 255, 255, 255), r.width() * 0.02f)
        panel(c, r.left + r.width() * 0.24f, r.top + r.height() * 0.42f, r.left + r.width() * 0.38f, r.bottom, Color.argb(90, 255, 255, 255), r.width() * 0.02f)
        panel(c, r.right - r.width() * 0.3f, r.top + r.height() * 0.46f, r.right - r.width() * 0.14f, r.bottom, Color.argb(70, 255, 255, 255), r.width() * 0.02f)
        stickman(c, r.centerX(), r.bottom - r.height() * 0.08f, r.height() * 0.52f, ArcadeColors.INK, pose = 1)
        // Briefcase.
        panel(c, r.centerX() + r.width() * 0.09f, r.top + r.height() * 0.66f, r.centerX() + r.width() * 0.22f, r.top + r.height() * 0.81f,
            0xFF8A5A3B.toInt(), r.width() * 0.02f)
        // Hearts and coins floating.
        text(c, "♥", r.left + r.width() * 0.16f, r.top + r.height() * 0.3f, r.width() * 0.12f, 0xFFE3566E.toInt(), bold = true)
        circle(c, r.left + r.width() * 0.34f, r.top + r.height() * 0.24f, r.width() * 0.05f, 0xFFF6C64B.toInt(), 0xFFB8862B.toInt(), 2f)
        text(c, "★", r.left + r.width() * 0.34f, r.top + r.height() * 0.28f, r.width() * 0.06f, 0xFFFFFFFF.toInt(), bold = true, align = Paint.Align.CENTER)
    }

    private fun memoryArt(c: Canvas, r: RectF) {
        gradient(c, r, 0f, 0xFF2E4FD8.toInt(), 0xFF5FC3E4.toInt())
        val cols = 4
        val rows = 3
        val cell = minOf(r.width() / (cols + 1.2f), r.height() / (rows + 1.2f))
        val left = r.centerX() - cell * cols / 2f
        val top = r.centerY() - cell * rows / 2f
        val symbols = listOf("★", "♥", "●", "▲", "★", "◆", "◆", "●", "♥", "▲", "✦", "✦")
        for (i in 0 until cols * rows) {
            val x = left + (i % cols) * cell
            val y = top + (i / cols) * cell
            val shown = i == 0 || i == 4 || i == 5
            panel(c, x + cell * 0.07f, y + cell * 0.07f, x + cell * 0.93f, y + cell * 0.93f,
                if (shown) ArcadeColors.WHITE else 0xFF2A3E9E.toInt(), cell * 0.18f)
            if (shown) text(c, symbols[i], x + cell / 2f, y + cell * 0.68f, cell * 0.44f,
                intArrayOf(ArcadeColors.PINK, ArcadeColors.CORAL, ArcadeColors.GOLD)[i % 3], bold = true, align = Paint.Align.CENTER)
            else text(c, "?", x + cell / 2f, y + cell * 0.68f, cell * 0.4f, Color.argb(130, 255, 255, 255), bold = true, align = Paint.Align.CENTER)
        }
    }

    private fun ticTacToeArt(c: Canvas, r: RectF) {
        gradient(c, r, 0f, 0xFF1F2B5E.toInt(), 0xFF4158C8.toInt())
        val size = minOf(r.width(), r.height()) * 0.8f
        val left = r.centerX() - size / 2f
        val top = r.centerY() - size / 2f
        val cell = size / 3f
        for (i in 1..2) {
            line(c, left + i * cell, top, left + i * cell, top + size, Color.argb(160, 255, 255, 255), size * 0.02f)
            line(c, left, top + i * cell, left + size, top + i * cell, Color.argb(160, 255, 255, 255), size * 0.02f)
        }
        fun cross(cx: Float, cy: Float, color: Int) {
            line(c, cx - cell * 0.26f, cy - cell * 0.26f, cx + cell * 0.26f, cy + cell * 0.26f, color, size * 0.055f)
            line(c, cx + cell * 0.26f, cy - cell * 0.26f, cx - cell * 0.26f, cy + cell * 0.26f, color, size * 0.055f)
        }
        fun ring(cx: Float, cy: Float, color: Int) {
            circle(c, cx, cy, cell * 0.3f, Color.TRANSPARENT, color, size * 0.05f)
        }
        cross(left + cell * 0.5f, top + cell * 0.5f, 0xFFFF7A59.toInt())
        ring(left + cell * 1.5f, top + cell * 1.5f, 0xFF7BE0C0.toInt())
        ring(left + cell * 2.5f, top + cell * 0.5f, 0xFF7BE0C0.toInt())
        cross(left + cell * 1.5f, top + cell * 2.5f, 0xFFFF7A59.toInt())
        cross(left + cell * 2.5f, top + cell * 2.5f, 0xFFFF7A59.toInt())
        ring(left + cell * 0.5f, top + cell * 1.5f, 0xFF7BE0C0.toInt())
    }

    private fun poolArt(c: Canvas, r: RectF, snooker: Boolean) {
        panel(c, r, if (snooker) 0xFF0F3B36.toInt() else 0xFF174F41.toInt(), 0f)
        val inset = r.width() * 0.08f
        val felt = RectF(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset)
        panel(c, felt, if (snooker) 0xFF15705C.toInt() else 0xFF1F8A66.toInt(), r.width() * 0.03f)
        // Pockets.
        val pr = r.width() * 0.035f
        listOf(
            felt.left to felt.top, felt.centerX() to felt.top, felt.right to felt.top,
            felt.left to felt.bottom, felt.centerX() to felt.bottom, felt.right to felt.bottom,
        ).forEach { (x, y) -> circle(c, x, y, pr, 0xFF101418.toInt()) }
        // Balls.
        val colors = if (snooker) intArrayOf(0xFFC0392B.toInt(), 0xFFF1C40F.toInt(), 0xFF2E86C1.toInt()) else
            intArrayOf(0xFFEF5B6E.toInt(), 0xFFF6C64B.toInt(), 0xFF3E8EE0.toInt(), 0xFF9B59B6.toInt())
        colors.forEachIndexed { i, color ->
            ball(c, felt.centerX() + (i - 1) * pr * 2.4f, felt.centerY() - pr * 2f, pr * 0.72f, color, if (snooker) 0 else i + 1)
        }
        ball(c, felt.centerX() + felt.width() * 0.22f, felt.centerY() + felt.height() * 0.22f, pr * 0.72f, ArcadeColors.WHITE)
        // Cue.
        line(c, felt.left - inset * 0.4f, felt.bottom + inset * 0.2f, felt.left + felt.width() * 0.36f, felt.centerY() + felt.height() * 0.18f,
            0xFFE3C08A.toInt(), r.width() * 0.018f)
    }

    private fun hideArt(c: Canvas, r: RectF) {
        panel(c, r, 0xFF0B0E16.toInt(), 0f)
        // Walls.
        val w = r.width() / 7f
        for (i in 0..7) {
            line(c, r.left + i * w, r.top, r.left + i * w, r.bottom, Color.argb(50, 130, 190, 255), 1.4f)
        }
        for (i in 0..6) {
            line(c, r.left, r.top + i * w, r.right, r.top + i * w, Color.argb(50, 130, 190, 255), 1.4f)
        }
        panel(c, r.left + w * 0.7f, r.top + w * 0.5f, r.left + w * 2.2f, r.top + w * 0.9f, Color.argb(200, 40, 70, 110), 2f)
        panel(c, r.left + w * 3.6f, r.top + w * 2.4f, r.left + w * 4.1f, r.top + w * 5.1f, Color.argb(200, 40, 70, 110), 2f)
        panel(c, r.left + w * 1.1f, r.top + w * 4.4f, r.left + w * 4.0f, r.top + w * 4.9f, Color.argb(200, 40, 70, 110), 2f)
        // Torchlight.
        val glow = paint(Color.TRANSPARENT)
        glow.shader = RadialGradient(r.left + r.width() * 0.68f, r.top + r.height() * 0.62f, r.width() * 0.42f,
            intArrayOf(0x88FFE6A8.toInt(), 0x22FFD98A.toInt(), 0x00000000), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(r.left + r.width() * 0.68f, r.top + r.height() * 0.62f, r.width() * 0.42f, glow)
        // A stickman hiding and two eyes watching.
        stickman(c, r.left + r.width() * 0.7f, r.top + r.height() * 0.8f, r.height() * 0.28f, 0xFFE9D9B8.toInt(), pose = 0)
        circle(c, r.left + r.width() * 0.22f, r.top + r.height() * 0.33f, r.width() * 0.02f, 0xFFFF4D5E.toInt())
        circle(c, r.left + r.width() * 0.28f, r.top + r.height() * 0.33f, r.width() * 0.02f, 0xFFFF4D5E.toInt())
    }

    private fun pingPongArt(c: Canvas, r: RectF) {
        gradient(c, r, 0f, 0xFF163B57.toInt(), 0xFF1F6E8C.toInt())
        val table = RectF(r.left + r.width() * 0.08f, r.top + r.height() * 0.16f, r.right - r.width() * 0.08f, r.bottom - r.height() * 0.12f)
        panel(c, table, 0xFF2E86A8.toInt(), r.width() * 0.03f, ArcadeColors.WHITE, r.width() * 0.008f)
        line(c, table.centerX(), table.top, table.centerX(), table.bottom, Color.argb(190, 255, 255, 255), r.width() * 0.012f)
        // Paddles.
        circle(c, table.left + table.width() * 0.18f, table.centerY() + table.height() * 0.16f, r.width() * 0.075f, 0xFFE3566E.toInt())
        panel(c, table.left + table.width() * 0.1f, table.centerY() + table.height() * 0.22f, table.left + table.width() * 0.16f, table.centerY() + table.height() * 0.36f,
            0xFFC9A227.toInt(), r.width() * 0.01f)
        circle(c, table.right - table.width() * 0.18f, table.centerY() - table.height() * 0.16f, r.width() * 0.075f, 0xFF43D6A2.toInt())
        panel(c, table.right - table.width() * 0.22f, table.centerY() - table.height() * 0.34f, table.right - table.width() * 0.16f, table.centerY() - table.height() * 0.22f,
            0xFFC9A227.toInt(), r.width() * 0.01f)
        circle(c, table.centerX() + table.width() * 0.08f, table.centerY(), r.width() * 0.035f, 0xFFFFFFFF.toInt())
        line(c, table.left + table.width() * 0.18f, table.centerY() + table.height() * 0.16f,
            table.centerX(), table.centerY(), Color.argb(120, 255, 255, 255), r.width() * 0.006f)
    }

    private fun puzzleArt(c: Canvas, r: RectF) {
        gradient(c, r, 0f, 0xFF4B3A8E.toInt(), 0xFF9B7BEF.toInt())
        val size = minOf(r.width(), r.height()) * 0.8f
        val left = r.centerX() - size / 2f
        val top = r.centerY() - size / 2f
        val cell = size / 3f
        val colors = intArrayOf(0xFFFFC24B.toInt(), 0xFFFF7A59.toInt(), 0xFF43D6D6.toInt(), 0xFF9BE36D.toInt(), 0xFFF2547D.toInt(), 0xFF6BB7FF.toInt(), 0xFFF7E7A8.toInt(), 0xFFB39DFF.toInt())
        var n = 1
        for (y in 0 until 3) for (x in 0 until 3) {
            if (x == 2 && y == 2) continue
            val l = left + x * cell
            val t = top + y * cell
            panel(c, l + cell * 0.05f, t + cell * 0.05f, l + cell * 0.95f, t + cell * 0.95f, colors[(n - 1) % colors.size], cell * 0.16f)
            text(c, n.toString(), l + cell / 2f, t + cell * 0.66f, cell * 0.42f, ArcadeColors.INK, bold = true, align = Paint.Align.CENTER)
            n++
        }
    }

    private fun quizArt(c: Canvas, r: RectF) {
        gradient(c, r, 0f, 0xFF6B4BC9.toInt(), 0xFFB39DFF.toInt())
        val bubble = RectF(r.left + r.width() * 0.08f, r.top + r.height() * 0.14f, r.left + r.width() * 0.58f, r.top + r.height() * 0.52f)
        panel(c, bubble, ArcadeColors.WHITE, r.width() * 0.09f)
        val tail = Path().apply {
            moveTo(bubble.left + bubble.width() * 0.2f, bubble.bottom)
            lineTo(bubble.left + bubble.width() * 0.16f, bubble.bottom + r.height() * 0.1f)
            lineTo(bubble.left + bubble.width() * 0.36f, bubble.bottom)
            close()
        }
        c.drawPath(tail, paint(ArcadeColors.WHITE))
        text(c, "?", bubble.centerX(), bubble.centerY() + r.height() * 0.09f, r.height() * 0.26f, ArcadeColors.LILAC, bold = true, align = Paint.Align.CENTER)
        val bubble2 = RectF(r.left + r.width() * 0.46f, r.top + r.height() * 0.42f, r.right - r.width() * 0.06f, r.top + r.height() * 0.78f)
        panel(c, bubble2, 0xFF2B2635.toInt(), r.width() * 0.08f)
        text(c, "A", bubble2.centerX(), bubble2.centerY() + r.height() * 0.08f, r.height() * 0.22f, ArcadeColors.MINT, bold = true, align = Paint.Align.CENTER)
        // A tick badge.
        circle(c, r.right - r.width() * 0.14f, r.top + r.height() * 0.2f, r.width() * 0.09f, ArcadeColors.MINT)
        line(c, r.right - r.width() * 0.19f, r.top + r.height() * 0.2f, r.right - r.width() * 0.15f, r.top + r.height() * 0.24f, ArcadeColors.WHITE, r.width() * 0.014f)
        line(c, r.right - r.width() * 0.15f, r.top + r.height() * 0.24f, r.right - r.width() * 0.08f, r.top + r.height() * 0.15f, ArcadeColors.WHITE, r.width() * 0.014f)
    }

    private fun farmArt(c: Canvas, r: RectF) {
        gradient(c, r, 0f, 0xFF8FD9B0.toInt(), 0xFF3E7A4E.toInt())
        // Sun and horizon.
        circle(c, r.right - r.width() * 0.18f, r.top + r.height() * 0.2f, r.width() * 0.09f, 0xFFFFD36B.toInt())
        panel(c, r.left, r.top + r.height() * 0.52f, r.right, r.bottom, 0xFF6B4A2E.toInt(), 0f)
        // Furrows.
        for (row in 0..3) {
            val y = r.top + r.height() * (0.58f + row * 0.1f)
            line(c, r.left + r.width() * 0.06f, y, r.right - r.width() * 0.06f, y, Color.argb(90, 40, 24, 12), r.width() * 0.012f)
        }
        // Sprouts: stem, leaf, leaf.
        val plants = listOf(0.16f to 0.2f, 0.42f to 0.32f, 0.68f to 0.22f, 0.28f to 0.44f, 0.58f to 0.46f)
        plants.forEach { (fx, fy) ->
            val x = r.left + r.width() * fx
            val y = r.top + r.height() * (0.56f + fy * 0.3f)
            line(c, x, y, x, y - r.height() * 0.08f, 0xFF2E5B34.toInt(), r.width() * 0.012f)
            circle(c, x - r.width() * 0.025f, y - r.height() * 0.075f, r.width() * 0.028f, 0xFF43D6A2.toInt())
            circle(c, x + r.width() * 0.025f, y - r.height() * 0.05f, r.width() * 0.022f, 0xFF9EDB4C.toInt())
        }
        // Basket of produce.
        panel(c, r.right - r.width() * 0.3f, r.bottom - r.height() * 0.26f, r.right - r.width() * 0.08f, r.bottom - r.height() * 0.08f,
            0xFFB9813F.toInt(), r.width() * 0.04f)
        circle(c, r.right - r.width() * 0.24f, r.bottom - r.height() * 0.27f, r.width() * 0.04f, 0xFFE3566E.toInt())
        circle(c, r.right - r.width() * 0.16f, r.bottom - r.height() * 0.29f, r.width() * 0.038f, 0xFFF6C64B.toInt())
    }

    private fun chessArt(c: Canvas, r: RectF) {
        gradient(c, r, 0f, 0xFF241F2E.toInt(), 0xFF4A3A2A.toInt())
        val size = minOf(r.width(), r.height()) * 0.86f
        val left = r.centerX() - size / 2f
        val top = r.centerY() - size / 2f
        val cell = size / 5f
        for (y in 0 until 5) for (x in 0 until 5) {
            val l = left + x * cell
            val t = top + y * cell
            c.drawRect(l, t, l + cell, t + cell, paint(if ((x + y) % 2 == 0) 0xFFF1E2C8.toInt() else 0xFF9C7A52.toInt()))
        }
        text(c, "♞", left + cell * 2.5f, top + cell * 4.2f, cell * 2.6f, 0xFF2B2635.toInt(), bold = true, align = Paint.Align.CENTER)
        text(c, "♟", left + cell * 1.5f, top + cell * 2.4f, cell * 1.4f, 0xFFFFFFFF.toInt(), bold = true, align = Paint.Align.CENTER)
    }
}
