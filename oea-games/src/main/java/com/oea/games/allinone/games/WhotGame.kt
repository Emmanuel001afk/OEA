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
import kotlin.math.min

/**
 * Whot, played the way it is actually played.
 *
 * The deck is the familiar one: 1–14 in circle, triangle, cross, square and star,
 * plus five Whot cards. You may follow the number, the shape, or the shape a Whot
 * called. The specials are all here — 1 holds on, 2 picks two, 5 picks three,
 * 8 is suspension, 14 is general market, and Whot (20) is wild — with the
 * opponent's turn skipped exactly as the table expects.
 *
 * The OEA AI keeps the specials for when they hurt, calls a shape it can follow,
 * and gets sharper with the level. In two-player mode the phone is passed over
 * and only the player whose turn it is sees their own hand.
 */
class WhotGame : ArcadeBaseGame() {

    private data class Card(val number: Int, val shape: Int) {
        val isWhot: Boolean get() = number == 20
    }

    private val shapeNames = arrayOf("CIRCLE", "TRIANGLE", "CROSS", "SQUARE", "STAR")
    private val shapeGlyphs = arrayOf("●", "▲", "✚", "■", "★")
    private val shapeColors = intArrayOf(
        0xFFE3566E.toInt(), 0xFFF6C64B.toInt(), 0xFF43D6A2.toInt(), 0xFF5AA6E8.toInt(), 0xFFB39DFF.toInt(),
    )

    private val hands = arrayOf(ArrayList<Card>(), ArrayList<Card>())
    private var deck = ArrayList<Card>()
    private var discard = ArrayList<Card>()
    private var turn = 0
    private var calledShape = -1
    private var message = ""
    private var aiDelay = 0f
    private var awaitingShape = false
    private var pendingWhot: Card? = null
    private var handRects = ArrayList<RectF>()
    private var drawPileRect = RectF()
    private var shapeRects = ArrayList<RectF>()
    private var lastPlayed: Card? = null

    private val twoPlayer: Boolean get() = config.mode == ArcadePlayMode.TWO_PLAYER

    override fun onStart() {
        deck = ArrayList()
        for (shape in 0..4) for (number in 1..14) deck.add(Card(number, shape))
        repeat(5) { deck.add(Card(20, -1)) }
        deck.shuffle(random)
        hands[0].clear()
        hands[1].clear()
        repeat(6) {
            hands[0].add(deck.removeAt(deck.size - 1))
            hands[1].add(deck.removeAt(deck.size - 1))
        }
        discard = ArrayList()
        var first = deck.removeAt(deck.size - 1)
        while (first.isWhot) {
            deck.add(0, first)
            first = deck.removeAt(deck.size - 1)
        }
        discard.add(first)
        calledShape = -1
        turn = 0
        message = "You start. Match ${shapeNames[first.shape].lowercase()} or the number ${first.number}."
        awaitingShape = false
        pendingWhot = null
        aiDelay = 0f
        lastPlayed = null
        sort(hands[0])
        sort(hands[1])
    }

    private fun sort(hand: MutableList<Card>) {
        hand.sortWith(compareBy<Card>({ it.shape }, { it.number }))
    }

    private val top: Card? get() = discard.lastOrNull()

    private fun playable(card: Card, topCard: Card): Boolean {
        if (card.isWhot) return true
        if (calledShape >= 0) return card.shape == calledShape
        return card.shape == topCard.shape || card.number == topCard.number || card.isWhot
    }

    private fun hasMove(hand: List<Card>): Boolean {
        val topCard = top ?: return false
        return hand.any { playable(it, topCard) }
    }

    // -------------------------------------------------------------------- turns

    private fun drawTo(player: Int, count: Int) {
        repeat(count) {
            if (deck.isEmpty()) {
                if (discard.size <= 1) return@repeat
                val topCard = discard.removeAt(discard.size - 1)
                deck.addAll(discard)
                discard.clear()
                discard.add(topCard)
                deck.shuffle(random)
            }
            if (deck.isNotEmpty()) hands[player].add(deck.removeAt(deck.size - 1))
        }
        sort(hands[player])
    }

    private fun playCard(player: Int, card: Card, shapeCall: Int = -1) {
        if (!hands[player].remove(card)) return
        discard.add(card)
        lastPlayed = card
        if (card.isWhot) {
            calledShape = shapeCall.coerceIn(0, 4)
        } else {
            calledShape = -1
        }
        if (hands[player].isEmpty()) {
            settle(player)
            return
        }
        val other = 1 - player
        when (card.number) {
            1 -> {
                message = "Hold on — ${name(other)} misses a turn."
            }
            8 -> {
                message = "Suspension — ${name(other)} sits this one out."
            }
            2 -> {
                drawTo(other, 2)
                message = "Pick two — ${name(other)} draws two and misses a turn."
            }
            5 -> {
                drawTo(other, 3)
                message = "Pick three — ${name(other)} draws three and misses a turn."
            }
            14 -> {
                drawTo(other, 1)
                message = "General market — ${name(other)} draws one."
                nextTurn()
            }
            else -> nextTurn()
        }
        if (card.isWhot) message = "Whot! ${shapeNames[calledShape]} called."
    }

    private fun nextTurn() {
        turn = 1 - turn
        if (!twoPlayer && turn == 1) aiDelay = 0.9f
        val topCard = top ?: return
        if (!hasMove(hands[turn])) {
            message = if (turn == 0) "Nothing to play — tap the deck to draw." else "The AI draws."
            if (turn == 1) aiDelay = 0.7f
        }
    }

    private fun name(player: Int): String =
        if (twoPlayer) "Player ${player + 1}" else if (player == 0) "you" else "the AI"

    private fun settle(winner: Int) {
        val won = if (twoPlayer) winner == 0 else winner == 0
        finish(
            won = if (twoPlayer) null else won,
            headline = if (twoPlayer) "Player ${winner + 1} wins the hand" else if (winner == 0) "You win the hand" else "The AI wins the hand",
            detail = "${hands[0].size + hands[1].size} cards left across the table. " +
                "${if (calledShape >= 0) "The called shape was ${shapeNames[calledShape]}." else "No shape call decided it."} " +
                "Remember to keep a special for the last two cards.",
            score = (10 + hands[1 - winner].size * 15).toLong(),
            stars = if (won) 3 else 1
        )
    }

    // ---------------------------------------------------------------------- AI

    private fun aiTurn() {
        if (done()) return
        val topCard = top ?: return
        val hand = hands[1]
        if (!hasMove(hand)) {
            drawTo(1, 1)
            message = "The AI draws a card."
            turn = 0
            message = "Your turn."
            return
        }
        val playableCards = hand.filter { playable(it, topCard) }
        val opponentClose = hands[0].size <= 2
        val chosen = when {
            opponentClose -> playableCards.maxByOrNull { threat(it) } ?: playableCards.first()
            else -> playableCards.maxByOrNull { it.number.coerceAtMost(14) } ?: playableCards.first()
        }
        if (chosen.isWhot) {
            val counts = IntArray(5)
            hand.forEach { if (!it.isWhot) counts[it.shape]++ }
            val call = counts.indices.maxByOrNull { counts[it] } ?: 0
            playCard(1, chosen, call)
        } else {
            playCard(1, chosen)
        }
        if (!done() && turn == 1) {
            // A special kept the turn: play again after a beat.
            aiDelay = 0.8f
        }
    }

    private fun threat(card: Card): Int = when (card.number) {
        2 -> 60
        5 -> 80
        1 -> 70
        8 -> 55
        14 -> 40
        20 -> 90
        else -> card.number
    }

    override fun update(dt: Float) {
        super.update(dt)
        if (done() || awaitingShape) return
        if (aiDelay > 0f) {
            aiDelay -= dt
            if (aiDelay <= 0f && !twoPlayer && turn == 1) aiTurn()
        }
    }

    // ------------------------------------------------------------------- input

    override fun touch(phase: ArcadeTouch, x: Float, y: Float, width: Float, height: Float): Boolean {
        if (done() || phase != ArcadeTouch.DOWN) return false
        if (awaitingShape) {
            val index = shapeRects.indexOfFirst { ArcadeDraw.inside(it, x, y) }
            if (index >= 0) {
                val call = index
                awaitingShape = false
                val card = pendingWhot
                pendingWhot = null
                if (card != null) playCard(turn, card, call)
                return true
            }
            return false
        }
        if (turn != 0 && !twoPlayer) return false
        val cardIndex = handRects.indexOfFirst { ArcadeDraw.inside(it, x, y) }
        if (cardIndex >= 0) {
            val hand = hands[turn]
            val card = hand.getOrNull(cardIndex) ?: return false
            val topCards = top ?: return false
            if (!playable(card, topCards)) {
                message = "You can't follow that card — match the shape, the number or the called shape."
                return true
            }
            if (card.isWhot) {
                pendingWhot = card
                awaitingShape = true
                return true
            }
            playCard(turn, card)
            return true
        }
        if (ArcadeDraw.inside(drawPileRect, x, y)) {
            val hand = hands[turn]
            if (!hasMove(hand)) {
                drawTo(turn, 1)
                message = "You drew a card."
                if (twoPlayer) {
                    turn = 1 - turn
                } else {
                    turn = 1
                    aiDelay = 0.7f
                }
            } else {
                message = "You have a card to play."
            }
            return true
        }
        return false
    }

    override fun status(): String {
        if (done()) return "Hand over"
        val whose = when {
            twoPlayer -> "Player ${turn + 1}'s turn"
            turn == 0 -> "Your turn"
            else -> "OEA AI thinking"
        }
        val called = if (calledShape >= 0) " · ${shapeNames[calledShape]}" else ""
        return "$whose · you ${hands[0].size}, AI ${hands[1].size}$called"
    }

    override fun hint(): String = message

    override fun score(): Long = (hands[1].size * 15L)

    // -------------------------------------------------------------------- draw

    override fun draw(canvas: Canvas, width: Float, height: Float) {
        canvas.drawColor(0xFF3A1240.toInt())
        val glow = ArcadeDraw.paint(Color.TRANSPARENT)
        glow.shader = android.graphics.RadialGradient(width * 0.5f, height * 0.2f, width,
            intArrayOf(0x555C2A6E, 0x003A1240), null, android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width, height, glow)

        val cardW = min(width * 0.16f, height * 0.1f)
        val cardH = cardW * 1.45f

        // Opponent's hand, face down.
        val other = 1 - turn
        val otherCount = hands[other].size
        val otherTop = height * 0.1f
        ArcadeDraw.text(canvas, if (twoPlayer) "PLAYER ${other + 1}" else "OEA AI", width * 0.06f, otherTop - height * 0.014f,
            height * 0.013f, 0xAAFFFFFF.toInt(), bold = true)
        val otherSpread = min(cardW * 0.55f, (width * 0.8f) / otherCount.coerceAtLeast(1))
        for (i in 0 until otherCount) {
            val rect = RectF(width * 0.08f + i * otherSpread, otherTop, width * 0.08f + i * otherSpread + cardW, otherTop + cardH)
            ArcadeDraw.card(canvas, rect, "", "", ArcadeColors.WHITE, faceUp = false)
        }

        // Deck and discard.
        val deckTop = height * 0.31f
        drawPileRect.set(width * 0.07f, deckTop, width * 0.07f + cardW, deckTop + cardH)
        ArcadeDraw.card(canvas, drawPileRect, "", "", ArcadeColors.WHITE, faceUp = false)
        ArcadeDraw.panel(canvas, drawPileRect, 0x22000000, cardW * 0.12f)
        ArcadeDraw.text(canvas, "${deck.size}", drawPileRect.centerX(), drawPileRect.bottom + height * 0.02f,
            height * 0.014f, Color.WHITE, bold = true, align = Paint.Align.CENTER)
        ArcadeDraw.text(canvas, "DRAW", drawPileRect.centerX(), drawPileRect.bottom + height * 0.038f,
            height * 0.011f, 0x99FFFFFF.toInt(), bold = true, align = Paint.Align.CENTER)

        val topCard = top
        if (topCard != null) {
            val rect = RectF(width * 0.42f, deckTop - cardH * 0.1f, width * 0.42f + cardW * 1.25f, deckTop - cardH * 0.1f + cardH * 1.25f)
            ArcadeDraw.panel(canvas, rect, 0x22FFFFFF, cardW * 0.14f)
            if (topCard.isWhot) {
                ArcadeDraw.text(canvas, "WHOT", rect.centerX(), rect.centerY() - rect.height() * 0.08f, rect.width() * 0.26f,
                    ArcadeColors.WHITE, bold = true, align = Paint.Align.CENTER)
                if (calledShape >= 0) {
                    ArcadeDraw.text(canvas, "→ ${shapeGlyphs[calledShape]}", rect.centerX(), rect.centerY() + rect.height() * 0.18f,
                        rect.width() * 0.3f, shapeColors[calledShape], bold = true, align = Paint.Align.CENTER)
                }
            } else {
                ArcadeDraw.text(canvas, shapeGlyphs[topCard.shape], rect.centerX(), rect.centerY() - rect.height() * 0.1f,
                    rect.width() * 0.46f, shapeColors[topCard.shape], bold = true, align = Paint.Align.CENTER)
                ArcadeDraw.text(canvas, topCard.number.toString(), rect.left + rect.width() * 0.16f, rect.top + rect.height() * 0.26f,
                    rect.height() * 0.16f, shapeColors[topCard.shape], bold = true)
            }
            if (calledShape >= 0) {
                ArcadeDraw.text(canvas, "CALLED", width * 0.42f + cardW * 0.6f, deckTop - cardH * 0.24f, height * 0.011f,
                    0x99FFFFFF.toInt(), bold = true, align = Paint.Align.CENTER)
            }
        }

        // Message.
        ArcadeDraw.paragraph(canvas, message, width * 0.06f, height * 0.55f, height * 0.019f, Color.WHITE, width * 0.88f, lineGap = 1.35f)

        // Own hand. In a game against the AI the player's hand stays on screen
        // even while the AI thinks — showing the AI's cards would be cheating.
        val handTop = height * 0.64f
        val hand = if (twoPlayer) hands[turn] else hands[0]
        ArcadeDraw.text(canvas, "YOUR HAND · ${hand.size}", width * 0.06f, handTop - height * 0.02f,
            height * 0.013f, ArcadeColors.GOLD, bold = true)
        val spread = if (hand.isEmpty()) cardW else min(cardW * 0.62f, (width * 0.86f - cardW) / maxOf(1, hand.size - 1))
        handRects.clear()
        hand.forEachIndexed { index, card ->
            val x = width * 0.07f + index * spread
            val rect = RectF(x, handTop + (if (index % 2 == 0) 0f else height * 0.008f), x + cardW, handTop + cardH + (if (index % 2 == 0) 0f else height * 0.008f))
            handRects += rect
            if (card.isWhot) {
                ArcadeDraw.card(canvas, rect, "20", "★", ArcadeColors.INK, faceUp = true, backColor = ArcadeColors.PLUM)
                ArcadeDraw.text(canvas, "WHOT", rect.centerX(), rect.top + rect.height() * 0.78f, rect.width() * 0.24f,
                    ArcadeColors.PLUM, bold = true, align = Paint.Align.CENTER)
            } else {
                ArcadeDraw.card(canvas, rect, card.number.toString(), shapeGlyphs[card.shape], shapeColors[card.shape])
            }
            val isPlayable = topCard?.let { playable(card, it) } == true
            if (!isPlayable) ArcadeDraw.panel(canvas, rect, 0x66000000, cardW * 0.12f)
        }
        if (hand.isEmpty()) {
            ArcadeDraw.text(canvas, "No cards — pass", width * 0.5f, handTop + cardH / 2f, height * 0.018f, 0x99FFFFFF.toInt(), align = Paint.Align.CENTER)
        }

        if (awaitingShape) {
            ArcadeDraw.panel(canvas, RectF(0f, height * 0.36f, width, height * 0.56f), 0xEE1A0E22.toInt().toInt(), height * 0.03f)
            ArcadeDraw.text(canvas, "CALL A SHAPE", width * 0.5f, height * 0.41f, height * 0.02f, Color.WHITE, bold = true, align = Paint.Align.CENTER)
            shapeRects.clear()
            val size = width * 0.15f
            for (i in 0..4) {
                val x = width * 0.5f - (size * 5 + width * 0.03f * 4) / 2f + i * (size + width * 0.03f)
                val rect = RectF(x, height * 0.44f, x + size, height * 0.44f + size)
                shapeRects += rect
                ArcadeDraw.panel(canvas, rect, shapeColors[i], size * 0.22f)
                ArcadeDraw.text(canvas, shapeGlyphs[i], rect.centerX(), rect.centerY() + size * 0.14f, size * 0.44f,
                    ArcadeColors.WHITE, bold = true, align = Paint.Align.CENTER)
            }
        }
    }
}
