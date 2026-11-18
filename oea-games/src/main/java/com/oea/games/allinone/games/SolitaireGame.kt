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
 * Solitaire (Klondike), dealt with fingers in mind.
 *
 * Tap a card to pick it up (a card in a column takes the cards under it), then
 * tap where it should go: another column, or a foundation. Tap the stock to deal
 * one or three; tap it when it is empty to turn the waste back over. The seven
 * columns build down in alternating colours, the foundations build up by suit,
 * and the arithmetic of a real deal is never faked.
 */
class SolitaireGame : ArcadeBaseGame() {

    private data class Card(val suit: Int, val rank: Int, var up: Boolean = false)

    private val suitGlyphs = arrayOf("♠", "♥", "♦", "♣")
    private val suitRed = booleanArrayOf(false, true, true, false)

    private var stock = ArrayList<Card>()
    private var waste = ArrayList<Card>()
    private var foundations = Array(4) { ArrayList<Card>() }
    private var tableau = Array(7) { ArrayList<Card>() }
    private var drawCount = 1
    private var moves = 0
    private var selColumn = -1
    private var selIndex = -1
    private var fromWaste = false
    private var par = 120
    private var cardsLeft = 52

    private var stockRect = RectF()
    private var wasteRect = RectF()
    private val foundationRects = ArrayList<RectF>()
    private val tableauRects = Array(7) { ArrayList<RectF>() }
    private var cardW = 1f
    private var cardH = 1f
    private var columnGap = 1f

    override fun onStart() {
        val deck = ArrayList<Card>()
        for (suit in 0..3) for (rank in 1..13) deck.add(Card(suit, rank))
        deck.shuffle(random)
        stock = ArrayList()
        waste = ArrayList()
        foundations = Array(4) { ArrayList() }
        tableau = Array(7) { ArrayList() }
        var index = 0
        for (column in 0..6) {
            for (row in 0..column) {
                val card = deck[index++]
                card.up = row == column
                tableau[column].add(card)
            }
        }
        while (index < deck.size) {
            val card = deck[index++]
            card.up = false
            stock.add(card)
        }
        drawCount = if (config.level <= 3) 1 else 3
        moves = 0
        selColumn = -1
        selIndex = -1
        fromWaste = false
        cardsLeft = 52
        par = ((100 + config.level * 12) * (0.9f + skill * 0.25f)).toInt()
    }

    // ------------------------------------------------------------------- moves

    private fun foundationAccepts(pile: Int, card: Card): Boolean {
        val target = foundations[pile]
        return if (target.isEmpty()) card.rank == 1 else {
            target.last().suit == card.suit && target.last().rank + 1 == card.rank
        }
    }

    private fun columnAccepts(column: Int, card: Card): Boolean {
        val target = tableau[column]
        return if (target.isEmpty()) card.rank == 13 else {
            val top = target.last()
            top.up && top.rank == card.rank + 1 && suitRed[top.suit] != suitRed[card.suit]
        }
    }

    private fun sequenceValid(cards: List<Card>): Boolean {
        for (i in 1 until cards.size) {
            val previous = cards[i - 1]
            val current = cards[i]
            if (!current.up || previous.rank != current.rank + 1 || suitRed[previous.suit] == suitRed[current.suit]) return false
        }
        return cards.isNotEmpty() && cards.first().up
    }

    private fun deal() {
        if (stock.isEmpty()) {
            if (waste.isEmpty()) return
            stock = ArrayList(waste.reversed())
            stock.forEach { it.up = false }
            waste.clear()
            return
        }
        repeat(drawCount) {
            if (stock.isEmpty()) return@repeat
            val card = stock.removeAt(stock.size - 1)
            card.up = true
            waste.add(card)
        }
    }

    private fun selectedCards(): List<Card> {
        if (fromWaste) return waste.takeLast(1)
        val column = tableau.getOrNull(selColumn) ?: return emptyList()
        return column.drop(selIndex)
    }

    private fun clearSelection() {
        selColumn = -1
        selIndex = -1
        fromWaste = false
    }

    private fun removeSelected(): List<Card> {
        if (fromWaste) {
            val card = waste.removeAt(waste.size - 1)
            return listOf(card)
        }
        val column = tableau[selColumn]
        val cards = ArrayList(column.drop(selIndex))
        while (column.size > selIndex) column.removeAt(column.size - 1)
        if (column.isNotEmpty()) column.last().up = true
        return cards
    }

    private fun tryMoveToColumn(column: Int) {
        val cards = selectedCards()
        if (cards.isEmpty()) return
        if (!sequenceValid(cards) || !columnAccepts(column, cards.first())) return
        if (!fromWaste && column == selColumn) {
            clearSelection()
            return
        }
        removeSelected().forEach { tableau[column].add(it) }
        moves++
        clearSelection()
        checkWin()
    }

    private fun tryMoveToFoundation(pile: Int) {
        val cards = selectedCards()
        if (cards.size != 1) return
        if (!foundationAccepts(pile, cards.first())) return
        removeSelected().forEach { foundations[pile].add(it) }
        moves++
        clearSelection()
        checkWin()
    }

    private fun checkWin() {
        if (foundations.sumOf { it.size } == 52) {
            val stars = when {
                moves <= par -> 3
                moves <= par * 1.5f -> 2
                else -> 1
            }
            finish(
                won = true,
                headline = "Deal complete",
                detail = "52 cards home in $moves moves (par $par). " +
                    if (stars == 3) "Textbook patience — the next deal draws three." else "Try to uncover the aces early; they unlock the foundations.",
                score = (600 - moves * 2).coerceAtLeast(50).toLong(),
                stars = stars
            )
        }
    }

    // ------------------------------------------------------------------- input

    override fun touch(phase: ArcadeTouch, x: Float, y: Float, width: Float, height: Float): Boolean {
        if (done() || phase != ArcadeTouch.DOWN) return false
        if (ArcadeDraw.inside(stockRect, x, y)) {
            clearSelection()
            deal()
            return true
        }
        // Foundations.
        foundationRects.forEachIndexed { pile, rect ->
            if (ArcadeDraw.inside(rect, x, y)) {
                if (fromWaste || selColumn >= 0) tryMoveToFoundation(pile)
                return true
            }
        }
        // Tableau: find the topmost card under the finger.
        for (column in 0..6) {
            val rects = tableauRects[column]
            for (index in rects.indices.reversed()) {
                if (!ArcadeDraw.inside(rects[index], x, y)) continue
                val card = tableau[column].getOrNull(index) ?: return false
                if (fromWaste || selColumn >= 0) {
                    tryMoveToColumn(column)
                    return true
                }
                if (!card.up) return true
                selColumn = column
                selIndex = index
                fromWaste = false
                return true
            }
        }
        if (ArcadeDraw.inside(wasteRect, x, y)) {
            if (waste.isEmpty()) return true
            if (fromWaste || selColumn >= 0) {
                clearSelection()
            } else {
                fromWaste = true
                selColumn = -1
                selIndex = 0
            }
            return true
        }
        clearSelection()
        return true
    }

    override fun status(): String {
        if (done()) return "Deal complete"
        return "Foundations ${foundations.sumOf { it.size }}/52 · $moves moves · ${if (drawCount == 1) "draw one" else "draw three"}"
    }

    override fun hint(): String = when {
        selColumn >= 0 || fromWaste -> "Now tap a column or a foundation."
        foundations.sumOf { it.size } == 0 -> "Tap the stock to deal. Aces start the foundations."
        else -> ""
    }

    override fun score(): Long = foundations.sumOf { it.size } * 10L

    // -------------------------------------------------------------------- draw

    override fun draw(canvas: Canvas, width: Float, height: Float) {
        canvas.drawColor(0xFF1E5B45.toInt())
        val p = ArcadeDraw.paint(Color.TRANSPARENT)
        p.shader = android.graphics.RadialGradient(width * 0.5f, height * 0.4f, width,
            intArrayOf(0x332F9C6E, 0x001E5B45), null, android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width, height, p)

        columnGap = width * 0.012f
        cardW = (width * 0.94f - columnGap * 6) / 7f
        cardH = cardW * 1.42f
        val topY = height * 0.13f
        val left = width * 0.03f
        foundationRects.clear()

        // Stock.
        stockRect.set(left, topY, left + cardW, topY + cardH)
        if (stock.isEmpty()) {
            ArcadeDraw.panel(canvas, stockRect, 0x22000000, cardW * 0.1f, 0x55FFFFFF, 1.5f)
            ArcadeDraw.text(canvas, "↻", stockRect.centerX(), stockRect.centerY() + cardH * 0.1f, cardH * 0.3f, 0x88FFFFFF.toInt(), bold = true, align = Paint.Align.CENTER)
        } else {
            ArcadeDraw.card(canvas, stockRect, "", "", ArcadeColors.WHITE, faceUp = false)
            ArcadeDraw.text(canvas, "${stock.size}", stockRect.centerX(), stockRect.bottom + height * 0.017f, height * 0.012f,
                0xAAFFFFFF.toInt(), bold = true, align = Paint.Align.CENTER)
        }
        // Waste.
        wasteRect.set(left + cardW + columnGap, topY, left + cardW * 2 + columnGap, topY + cardH)
        ArcadeDraw.panel(canvas, wasteRect, 0x22000000, cardW * 0.1f, 0x33FFFFFF, 1.5f)
        waste.lastOrNull()?.let { card ->
            drawCard(canvas, wasteRect, card, selected = fromWaste)
        }

        // Foundations across the right side.
        for (pile in 0..3) {
            val x = left + (3 + pile) * (cardW + columnGap)
            val rect = RectF(x, topY, x + cardW, topY + cardH)
            foundationRects += rect
            ArcadeDraw.panel(canvas, rect, 0x22000000, cardW * 0.1f, 0x33FFFFFF, 1.5f)
            ArcadeDraw.text(canvas, suitGlyphs[pile], rect.centerX(), rect.centerY() + cardH * 0.1f, cardH * 0.26f,
                0x55FFFFFF, align = Paint.Align.CENTER)
            foundations[pile].lastOrNull()?.let { card -> drawCard(canvas, rect, card, selected = false) }
        }

        // Columns.
        val columnTop = topY + cardH * 1.3f
        val available = height * 0.97f - columnTop - cardH
        for (column in 0..6) {
            val x = left + column * (cardW + columnGap)
            val rect = RectF(x, columnTop, x + cardW, columnTop + cardH)
            ArcadeDraw.panel(canvas, rect, 0x14000000, cardW * 0.1f)
            val cards = tableau[column]
            val offset = if (cards.size <= 1) cardH * 0.26f else min(cardH * 0.28f, available / (cards.size - 1).coerceAtLeast(1))
            tableauRects[column].clear()
            cards.forEachIndexed { index, card ->
                val cardRect = RectF(x, columnTop + index * offset, x + cardW, columnTop + index * offset + cardH)
                tableauRects[column] += cardRect
                drawCard(canvas, cardRect, card,
                    selected = !fromWaste && selColumn == column && index >= selIndex && selIndex >= 0)
            }
        }

        ArcadeDraw.text(canvas, "FOUNDATIONS ${foundations.sumOf { it.size }}/52", width * 0.5f, height * 0.075f,
            height * 0.014f, 0xAAFFFFFF.toInt(), bold = true, align = Paint.Align.CENTER)
        if (selColumn >= 0 || fromWaste) {
            ArcadeDraw.panel(canvas, RectF(width * 0.5f - width * 0.25f, height * 0.04f, width * 0.5f + width * 0.25f, height * 0.058f),
                0xCC000000.toInt(), height * 0.009f)
            ArcadeDraw.text(canvas, "TAP WHERE IT GOES", width * 0.5f, height * 0.053f, height * 0.011f,
                Color.WHITE, bold = true, align = Paint.Align.CENTER)
        }
    }

    private fun drawCard(canvas: Canvas, rect: RectF, card: Card, selected: Boolean) {
        if (!card.up) {
            ArcadeDraw.card(canvas, rect, "", "", ArcadeColors.WHITE, faceUp = false)
        } else {
            ArcadeDraw.card(canvas, rect, rankName(card.rank), suitGlyphs[card.suit],
                if (suitRed[card.suit]) 0xFFD9414F.toInt() else 0xFF2B2635.toInt())
        }
        if (selected) {
            ArcadeDraw.panel(canvas, rect, 0x33FFD34D, cardW * 0.12f, 0xFFFFD34D.toInt(), 2.5f)
        }
    }

    private fun rankName(rank: Int): String = when (rank) {
        1 -> "A"
        11 -> "J"
        12 -> "Q"
        13 -> "K"
        else -> rank.toString()
    }
}
