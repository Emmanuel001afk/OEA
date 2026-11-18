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
 * Farm: twenty-four days, twelve plots, four crops and a market that moves.
 *
 * The simulation is small but honest. Seeds cost money, crops take a known
 * number of days, watering a plot that day nearly doubles its growth, and the
 * price you are paid depends on the market on the day you harvest — so the
 * question is never "tap the green thing" but "sell now, or wait a day and risk
 * the price". Bills are deducted at the end of each week. Reach the season
 * target before the last day.
 */
class FarmGame : ArcadeBaseGame() {

    private data class Crop(
        val name: String,
        val glyph: String,
        val cost: Int,
        val days: Int,
        val price: Int,
        val color: Int,
    )

    private data class Plot(val crop: Int = -1, val growth: Int = 0, val watered: Boolean = false)

    private val crops = listOf(
        Crop("Pepper", "🌶️", 12, 2, 34, 0xFFE3566E.toInt()),
        Crop("Maize", "🌽", 22, 3, 62, 0xFFF6C64B.toInt()),
        Crop("Tomato", "🍅", 34, 4, 96, 0xFFFF7A59.toInt()),
        Crop("Melon", "🍈", 52, 6, 170, 0xFF43D6A2.toInt()),
    )

    private var plots = Array(12) { Plot() }
    private var market = FloatArray(4) { 1f }
    private var selected = 0
    private var money = 160
    private var day = 1
    private var harvest = 0
    private var goal = 600
    private var message = ""
    private var weather = ""
    private var lastIncome = 0

    private val plotRects = ArrayList<RectF>()
    private val seedRects = ArrayList<RectF>()
    private var dayRect = RectF()

    private val maxDays = 24

    override fun onStart() {
        plots = Array(12) { Plot() }
        market = FloatArray(4) { 1f + (random.nextFloat() - 0.5f) * 0.12f }
        selected = 0
        money = 150 + config.level * 12
        day = 1
        harvest = 0
        goal = 520 + config.level * 80
        message = "Plant a seed, water it, harvest when it is ready."
        weather = ""
        lastIncome = 0
    }

    private fun priceOf(crop: Int): Int = (crops[crop].price * market[crop]).toInt()

    private fun plant(plot: Int) {
        if (plots[plot].crop >= 0) return
        val crop = crops[selected]
        if (money < crop.cost) {
            message = "Not enough money for ${crop.name} seed."
            return
        }
        money -= crop.cost
        plots[plot] = Plot(crop = selected, growth = 0, watered = false)
        message = "${crop.name} planted. ${crop.days} days to harvest."
    }

    private fun water(plot: Int) {
        val p = plots[plot]
        if (p.crop < 0 || p.watered) return
        plots[plot] = p.copy(watered = true)
        message = "Watered. Overnight growth doubles."
    }

    private fun harvest(plot: Int) {
        val p = plots[plot]
        if (p.crop < 0 || p.growth < crops[p.crop].days) return
        val paid = priceOf(p.crop) + if (p.watered) 6 else 0
        money += paid
        lastIncome = paid
        harvest++
        message = "Sold ${crops[p.crop].name} for ₦$paid (market ${(market[p.crop] * 100).toInt()}%)."
        plots[plot] = Plot()
        checkEnd()
    }

    private fun nextDay() {
        if (done()) return
        day++
        for (index in plots.indices) {
            val p = plots[index]
            if (p.crop < 0) continue
            plots[index] = p.copy(growth = p.growth + if (p.watered) 2 else 1, watered = false)
        }
        for (crop in market.indices) {
            market[crop] = (market[crop] + (random.nextFloat() - 0.5f) * 0.22f).coerceIn(0.6f, 1.7f)
        }
        weather = when {
            day % 7 == 0 -> {
                money -= 30 + config.level * 4
                "Rent day: ₦${30 + config.level * 4} for the stall."
            }
            day % 5 == 0 -> {
                for (index in plots.indices) {
                    val p = plots[index]
                    if (p.crop >= 0) plots[index] = p.copy(watered = true)
                }
                "Rain overnight — every plot is watered."
            }
            day % 6 == 0 -> {
                for (crop in market.indices) market[crop] = (market[crop] + 0.12f).coerceAtMost(1.7f)
                "Market day — prices are up."
            }
            else -> ""
        }
        if (money < 0) money = 0
        checkEnd()
        if (done()) return
        message = if (weather.isNotBlank()) weather else "Day $day. Check what is ready."
    }

    private fun checkEnd() {
        if (done()) return
        if (money >= goal) {
            finish(
                won = true,
                headline = "Target reached on day $day",
                detail = "You turned ₦${150 + config.level * 12} into ₦$money with $harvest harvests. " +
                    "The market pays for patience: the next season starts with dearer seeds and a higher target.",
                score = money.toLong() + harvest * 40L,
                stars = when {
                    day <= 16 -> 3
                    day <= 20 -> 2
                    else -> 1
                }
            )
            return
        }
        if (day > maxDays) {
            finish(
                won = false,
                headline = "The season closed at ₦$money",
                detail = "Goal was ₦$goal across $maxDays days; you brought in $harvest harvests. " +
                    "Plant the cheap crop early, water every day, and hold the expensive crop for a market spike.",
                score = money.toLong() + harvest * 30L,
                stars = 1
            )
            return
        }
        val anyCrop = plots.any { it.crop >= 0 }
        val canAfford = crops.any { money >= it.cost }
        if (!anyCrop && !canAfford) {
            finish(
                won = false,
                headline = "Out of seed money",
                detail = "No crops in the ground and not enough for a seed. " +
                    "Pepper is the cheapest way back into the market; harvest even at a low price to restart.",
                score = money.toLong(),
                stars = 1
            )
        }
    }

    override fun touch(phase: ArcadeTouch, x: Float, y: Float, width: Float, height: Float): Boolean {
        if (done() || phase != ArcadeTouch.DOWN) return false
        seedRects.forEachIndexed { index, rect ->
            if (ArcadeDraw.inside(rect, x, y)) {
                selected = index
                message = "${crops[index].name}: seed ₦${crops[index].cost}, ${crops[index].days} days, sells around ₦${priceOf(index)}."
                return true
            }
        }
        plotRects.forEachIndexed { index, rect ->
            if (ArcadeDraw.inside(rect, x, y)) {
                val plot = plots[index]
                when {
                    plot.crop < 0 -> plant(index)
                    plot.growth >= crops[plot.crop].days -> harvest(index)
                    else -> water(index)
                }
                return true
            }
        }
        if (ArcadeDraw.inside(dayRect, x, y)) {
            nextDay()
            return true
        }
        return false
    }

    override fun status(): String {
        if (done()) return "Season over"
        return "Day $day/$maxDays · ₦$money · goal ₦$goal · $harvest harvests"
    }

    override fun hint(): String = message

    override fun score(): Long = money.toLong() + harvest * 30L

    override fun draw(canvas: Canvas, width: Float, height: Float) {
        canvas.drawColor(0xFF12281F.toInt())
        val sky = ArcadeDraw.paint(Color.TRANSPARENT)
        sky.shader = android.graphics.LinearGradient(0f, 0f, 0f, height * 0.5f,
            intArrayOf(0xFF1E4B3A.toInt(), 0xFF12281F.toInt()), null, android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width, height * 0.5f, sky)

        ArcadeDraw.text(canvas, "FARM", width * 0.06f, height * 0.055f, height * 0.022f, ArcadeColors.CREAM, bold = true)
        ArcadeDraw.text(canvas, "DAY $day/$maxDays", width * 0.94f, height * 0.055f, height * 0.015f,
            ArcadeColors.LIME, bold = true, align = Paint.Align.RIGHT)
        ArcadeDraw.text(canvas, "₦$money  ·  goal ₦$goal  ·  $harvest harvests", width * 0.06f, height * 0.085f,
            height * 0.015f, 0xCCFFFFFF.toInt())

        // Plots: 3 columns, 4 rows.
        val gap = width * 0.022f
        val gridLeft = width * 0.05f
        val gridTop = height * 0.11f
        val plotW = (width * 0.9f - gap * 2) / 3f
        val plotH = min(plotW * 0.82f, (height * 0.47f - gap * 3) / 4f)
        plotRects.clear()
        for (index in plots.indices) {
            val col = index % 3
            val row = index / 3
            val l = gridLeft + col * (plotW + gap)
            val t = gridTop + row * (plotH + gap)
            val rect = RectF(l, t, l + plotW, t + plotH)
            plotRects += rect
            val p = plots[index]
            val soil = when {
                p.crop < 0 -> 0xFF3A2A20.toInt()
                p.growth >= crops[p.crop].days -> 0xFF4A3627.toInt()
                else -> 0xFF403026.toInt()
            }
            ArcadeDraw.panel(canvas, rect, soil, plotH * 0.16f, 0x33000000, 1.5f)
            if (p.crop < 0) {
                ArcadeDraw.text(canvas, "+", rect.centerX(), rect.centerY() + plotH * 0.1f, plotH * 0.34f,
                    0x55FFFFFF, bold = true, align = Paint.Align.CENTER)
                continue
            }
            val crop = crops[p.crop]
            val ready = p.growth >= crop.days
            if (ready) {
                ArcadeDraw.panel(canvas, rect, 0x33FFD34D, plotH * 0.16f, 0xFFFFD34D.toInt(), 2f)
            }
            ArcadeDraw.text(canvas, crop.glyph, rect.centerX(), rect.centerY() + plotH * 0.12f, plotH * 0.42f,
                Color.WHITE, align = Paint.Align.CENTER)
            val progress = (p.growth.toFloat() / crop.days).coerceIn(0f, 1f)
            val bar = RectF(rect.left + plotW * 0.15f, rect.bottom - plotH * 0.16f, rect.right - plotW * 0.15f,
                rect.bottom - plotH * 0.09f)
            ArcadeDraw.panel(canvas, bar, 0x44000000, bar.height() / 2f)
            ArcadeDraw.panel(canvas, RectF(bar.left, bar.top, bar.left + bar.width() * progress, bar.bottom),
                if (ready) 0xFFFFD34D.toInt() else ArcadeColors.LIME, bar.height() / 2f)
            if (p.watered) {
                ArcadeDraw.text(canvas, "💧", rect.right - plotW * 0.16f, rect.top + plotH * 0.24f, plotH * 0.16f,
                    Color.WHITE, align = Paint.Align.CENTER)
            }
            if (ready) {
                ArcadeDraw.text(canvas, "₦${priceOf(p.crop)}", rect.centerX(), rect.top + plotH * 0.2f, plotH * 0.14f,
                    ArcadeColors.CREAM, bold = true, align = Paint.Align.CENTER)
            }
        }

        // Seed selector.
        ArcadeDraw.text(canvas, "SEEDS (tap to choose)", width * 0.05f, height * 0.615f, height * 0.012f,
            0x99FFFFFF.toInt(), bold = true)
        seedRects.clear()
        val seedGap = width * 0.018f
        val seedW = (width * 0.9f - seedGap * 3) / 4f
        for (index in crops.indices) {
            val crop = crops[index]
            val l = width * 0.05f + index * (seedW + seedGap)
            val rect = RectF(l, height * 0.63f, l + seedW, height * 0.735f)
            seedRects += rect
            val active = index == selected
            ArcadeDraw.panel(canvas, rect, if (active) crop.color else 0xFF2A3B32, seedW * 0.2f,
                if (active) ArcadeColors.WHITE else 0x33FFFFFF, 1.4f)
            ArcadeDraw.text(canvas, crop.glyph, rect.centerX(), rect.top + height * 0.035f, height * 0.026f,
                Color.WHITE, align = Paint.Align.CENTER)
            ArcadeDraw.text(canvas, "₦${crop.cost} · ${crop.days}d", rect.centerX(), rect.bottom - height * 0.012f,
                height * 0.0115f, if (active) Color.WHITE else 0xCCFFFFFF.toInt(), bold = true, align = Paint.Align.CENTER)
        }

        // Next day.
        dayRect.set(width * 0.05f, height * 0.76f, width * 0.95f, height * 0.835f)
        ArcadeDraw.button(canvas, dayRect, "NEXT DAY", ArcadeColors.LIME, ArcadeColors.INK)

        val readout = when {
            weather.isNotBlank() -> weather
            else -> message
        }
        ArcadeDraw.paragraph(canvas, readout, width * 0.05f, height * 0.865f, height * 0.0145f,
            0xCCFFFFFF.toInt(), width * 0.9f, lineGap = 1.25f)
        ArcadeDraw.text(canvas, "Market " + crops.indices.joinToString("   ") { index ->
            "${crops[index].name.take(3)} ${(market[index] * 100).toInt()}%"
        }, width * 0.5f, height * 0.965f, height * 0.0115f, 0x88FFFFFF.toInt(), align = Paint.Align.CENTER)
    }
}
