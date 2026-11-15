package com.oea.games

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.oea.engine.core.LocalPlayer
import com.oea.engine.core.PlayMode
import com.oea.games.chess.ChessAi
import com.oea.games.ui.GameArt
import com.oea.games.ui.GameColors
import com.oea.games.ui.GameType

/**
 * Chess's front screen: the club room, not a cockpit.
 *
 * A chess app is read, not operated. So this screen leads with the board
 * itself — a real 8×8 in walnut and ivory, oriented to the side you are about
 * to play — then the record card that a club player actually wants (games
 * played, wins against the level you are facing), then the three ways to get a
 * game: the AI at one of ten strengths, pass-and-play on this phone, or a
 * friend over Wi-Fi or a code. Clocks are chosen as named time controls
 * (Bullet, Blitz, Rapid, Classical), not as sliders.
 *
 * This game gets none of the shooter's aim/ADS/turn-limit settings; its own
 * settings are board (3D/2D), orientation, and board turning.
 */
class ChessFront(host: GameFrontHost) : GameFront(host) {

    private val tabAi = 0
    private val tabSame = 1
    private val tabFriends = 2

    private val keyTab = "front_tab"

    override fun build(): View {
        val page = kit.page(padBottomDp = 18)
        val content = page.content

        // ------------------------------------------------------------- header
        kit.paletteTypeface = GameType.serif
        kit.heroBand(
            content,
            heightDp = 158,
            title = "Chess",
            eyebrow = "The OEA chess club",
            subtitle = "Complete rules · ten levels · clocks · pass & play",
            art = GameArt.hero("chess", scheme),
            leftInsetDp = 22
        )

        // -------------------------------------------------------------- record
        val played = prefs.getInt("played", 0)
        val level = value("level").toIntOrNull()?.coerceIn(1, 10) ?: 5
        val wins = prefs.getInt("wins_level_$level", 0)
        val record = LinearLayout(kit.context).apply { orientation = LinearLayout.VERTICAL }
        record.setPadding(kit.dp(2), kit.dp(12), kit.dp(2), kit.dp(2))
        kit.statStrip(
            record,
            listOf(
                wins.toString() to "Wins",
                played.toString() to "Played",
                if (played > 0) "${wins * 100 / played}%" to "Win rate" else "—" to "Win rate"
            ),
            accentFirst = true
        )
        content.addView(record, LinearLayout.LayoutParams(-1, -2))

        // --------------------------------------------------------------- board
        section(content, "Board · ${sideLabel()}")
        val boardCard = LinearLayout(kit.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(kit.dp(10), kit.dp(10), kit.dp(10), kit.dp(10))
            background = kit.rounded(palette.card, 18, palette.stroke, 1)
        }
        val board = BoardPreview(kit.context)
        boardCard.addView(board, LinearLayout.LayoutParams(-1, kit.dp(268)))
        boardCard.addView(
            kit.text("Tap the board to turn it · ${if (value("view") == "2d") "2D" else "3D"} pieces", 11.5f, palette.textTertiary)
                .apply { gravity = Gravity.CENTER; setPadding(0, kit.dp(8), 0, kit.dp(2)) },
            LinearLayout.LayoutParams(-1, -2)
        )
        content.addView(boardCard, LinearLayout.LayoutParams(-1, -2))

        val views = LinearLayout(kit.context).apply { orientation = LinearLayout.HORIZONTAL }
        views.addView(
            segment("3D board", "2D board", value("view") == "2d") { set("view", if (value("view") == "2d") "3d" else "2d") },
            LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = kit.dp(8) }
        )
        views.addView(
            segment(flipLabel(), "Board turning", value("flip") == "auto") {
                set("flip", if (value("flip") == "auto") "face" else "auto")
            },
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        content.addView(views, LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(8) })

        // ---------------------------------------------------------------- mode
        section(content, "A game")
        kit.segmented(content, listOf("Vs OEA AI", "Pass & play", "A friend"), tab()) { pick ->
            prefs.edit().putString(keyTab, pick.toString()).apply()
            host.frontRebuild()
        }
        when (tab()) {
            tabSame -> sameDeviceBlock(content)
            tabFriends -> friendsBlock(content)
            else -> aiBlock(content)
        }

        // --------------------------------------------------------------- clock
        section(content, "Clock · ${label("time")}")
        timeGrid(content)

        // -------------------------------------------------------------- colour
        section(content, "You play")
        kit.segmented(content, listOf("White", "Random", "Black"), colourIndex()) { pick ->
            set("color", listOf("white", "random", "black")[pick])
        }

        // ------------------------------------------------------------ settings
        section(content, "The club room")
        val settingsBlock = LinearLayout(kit.context).apply { orientation = LinearLayout.VERTICAL }
        content.addView(settingsBlock, LinearLayout.LayoutParams(-1, -2))
        kit.valueRow(settingsBlock, "Sound", if (settings.sound) "On" else "Off", "Piece moves, captures, the clock") { audioSheet("Chess sound") }
        kit.valueRow(settingsBlock, "Board & pieces", if (value("view") == "2d") "2D, flat" else "3D, wooden", "How the board is drawn") { piecesSheet() }
        kit.valueRow(settingsBlock, "Rules & controls", "Castling, en passant, promotion", "What the game enforces for you") { host.frontControls() }
        kit.valueRow(settingsBlock, "Screen & access", "Menu size, contrast, motion", "Every OEA game on this phone") { host.frontAccess() }
        kit.valueRow(settingsBlock, "About Chess", "OEA Engine ${com.oea.engine.OeaEngine.VERSION}", "What this app is") { aboutSheet() }

        kit.note(
            content,
            "Everything is enforced: castling rights, en passant, threefold repetition, the fifty-move rule, insufficient material and promotion. " +
                "Pass & play turns the board to whoever is to move, so two people can share one phone without reaching across."
        )

        // ----------------------------------------------------------------- bar
        page.root.addView(bottomBar(), FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        content.setPadding(kit.dp(20), kit.dp(12), kit.dp(20), kit.dp(96))
        return page.root
    }

    // ------------------------------------------------------------------ blocks

    private fun aiBlock(parent: LinearLayout) {
        val level = value("level").toIntOrNull()?.coerceIn(1, 10) ?: 5
        val block = LinearLayout(kit.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(kit.dp(12), kit.dp(12), kit.dp(12), kit.dp(12))
            background = kit.rounded(palette.card, 16, palette.stroke, 1)
        }
        block.addView(
            kit.text("AI strength", 14f, palette.text, GameType.medium),
            LinearLayout.LayoutParams(-1, -2)
        )
        block.addView(
            kit.text(
                "Level $level · ${ChessAi.LEVEL_NAMES.getOrElse(level - 1) { "Club" }} — " + strengthBlurb(level),
                12f,
                palette.textSecondary
            ).apply { setPadding(0, kit.dp(3), 0, kit.dp(10)); setLineSpacing(kit.dpf(2f), 1f) },
            LinearLayout.LayoutParams(-1, -2)
        )
        // Ten levels, as two rows of five: a strength ladder you can see at once.
        for (rowStart in intArrayOf(1, 6)) {
            val row = LinearLayout(kit.context).apply { orientation = LinearLayout.HORIZONTAL }
            for (n in rowStart until rowStart + 5) {
                val active = n == level
                row.addView(
                    kit.text(n.toString(), 15f, if (active) palette.onAccent else palette.textSecondary, GameType.mono).apply {
                        gravity = Gravity.CENTER
                        background = kit.rounded(if (active) palette.accent else palette.well, 10, if (active) 0 else palette.stroke, 1)
                        isClickable = true
                        contentDescription = "Level $n, ${ChessAi.LEVEL_NAMES.getOrElse(n - 1) { "" }}"
                        setOnClickListener { set("level", n.toString()) }
                    },
                    LinearLayout.LayoutParams(0, kit.dp(42), 1f).apply { rightMargin = kit.dp(6) }
                )
            }
            block.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(6) })
        }
        val wins = prefs.getInt("wins_level_$level", 0)
        block.addView(
            kit.text("Against level $level: $wins win${if (wins == 1) "" else "s"}", 11.5f, palette.textTertiary)
                .apply { setPadding(kit.dp(2), kit.dp(4), 0, 0) },
            LinearLayout.LayoutParams(-1, -2)
        )
        parent.addView(block, LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(10) })
    }

    private fun sameDeviceBlock(parent: LinearLayout) {
        val block = LinearLayout(kit.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(kit.dp(12), kit.dp(12), kit.dp(12), kit.dp(12))
            background = kit.rounded(palette.card, 16, palette.stroke, 1)
        }
        kit.valueRow(block, "Board turning", flipLabel(), "Who the board faces after each move") {
            val order = listOf("auto", "face", "off")
            val current = order.indexOf(value("flip")).coerceAtLeast(0)
            kit.choose("Board turning", null, listOf("Turn to the player to move", "Face to face", "Fixed"), current) { pick ->
                set("flip", order[pick])
            }
        }
        block.addView(
            kit.text("Two people, one phone. Names and colours come from your profile; \"Face to face\" puts the board between you with the pieces the right way up for each side.", 12f, palette.textSecondary)
                .apply { setLineSpacing(kit.dpf(2f), 1f); setPadding(kit.dp(2), kit.dp(4), kit.dp(2), 0) },
            LinearLayout.LayoutParams(-1, -2)
        )
        parent.addView(block, LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(10) })
    }

    private fun friendsBlock(parent: LinearLayout) {
        val block = LinearLayout(kit.context).apply { orientation = LinearLayout.VERTICAL }
        kit.valueRow(block, "Wi-Fi / hotspot", "Host or join", "Same router, or your phone's hotspot", true) { host.frontFriends() }
        kit.valueRow(block, "Online with a code", "Host or join", "A QR or a code, on any network") { host.frontFriends() }
        block.addView(
            kit.text("A friend needs the same Chess app. The host picks the clock and both sides start on the same move count.", 12f, palette.textSecondary)
                .apply { setLineSpacing(kit.dpf(2f), 1f); setPadding(kit.dp(2), kit.dp(6), kit.dp(2), 0) },
            LinearLayout.LayoutParams(-1, -2)
        )
        parent.addView(block, LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(10) })
    }

    /** Named time controls on a grid, the way a club scoresheet lists them. */
    private fun timeGrid(parent: LinearLayout) {
        val entries = listOf(
            Triple("1+0", "Bullet", "1 min"),
            Triple("3+2", "Blitz", "3 + 2"),
            Triple("5+0", "Blitz", "5 min"),
            Triple("10+0", "Rapid", "10 min"),
            Triple("15+10", "Rapid", "15 + 10"),
            Triple("30+0", "Classical", "30 min"),
            Triple("none", "No clock", "Untimed")
        )
        val chosen = value("time")
        val grid = LinearLayout(kit.context).apply { orientation = LinearLayout.VERTICAL }
        var row: LinearLayout? = null
        entries.forEachIndexed { index, (valueId, name, sub) ->
            if (index % 2 == 0) {
                row = LinearLayout(kit.context).apply { orientation = LinearLayout.HORIZONTAL }
                grid.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })
            }
            val active = valueId == chosen
            val tile = LinearLayout(kit.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(kit.dp(12), kit.dp(10), kit.dp(12), kit.dp(10))
                background = kit.rounded(if (active) palette.accent else palette.card, 14, if (active) 0 else palette.stroke, 1)
                isClickable = true
                contentDescription = "$name $sub"
                setOnClickListener { set("time", valueId) }
            }
            tile.addView(kit.text(name, 13.5f, if (active) palette.onAccent else palette.text, GameType.medium))
            tile.addView(
                kit.text(sub, 11.5f, if (active) GameColors.alpha(palette.onAccent, 0xCC) else palette.textTertiary)
                    .apply { setPadding(0, kit.dp(2), 0, 0) },
                LinearLayout.LayoutParams(-1, -2)
            )
            row?.addView(tile, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = if (index % 2 == 0) 0 else kit.dp(8) })
        }
        parent.addView(grid, LinearLayout.LayoutParams(-1, -2))
    }

    /** A single two-state tile used for board view and turning. */
    private fun segment(title: String, caption: String, on: Boolean, toggle: () -> Unit): LinearLayout =
        LinearLayout(kit.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(kit.dp(12), kit.dp(10), kit.dp(12), kit.dp(10))
            background = kit.rounded(palette.card, 14, if (on) palette.accent else palette.stroke, if (on) 2 else 1)
            isClickable = true
            contentDescription = "$title, $caption"
            setOnClickListener { toggle() }
            addView(kit.text(title, 13f, palette.text, GameType.medium))
            addView(
                kit.text(caption, 11f, palette.textTertiary).apply { setPadding(0, kit.dp(2), 0, 0) },
                LinearLayout.LayoutParams(-1, -2)
            )
        }

    private fun bottomBar(): View {
        val bar = LinearLayout(kit.context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(kit.dp(16), kit.dp(10), kit.dp(16), kit.dp(16))
            setBackgroundColor(GameColors.blend(palette.background, palette.card, 0.9f))
        }
        bar.addView(
            kit.quietButton("Settings") { host.frontAccess() },
            LinearLayout.LayoutParams(0, kit.dp(52), 0.9f).apply { rightMargin = kit.dp(10) }
        )
        val action = when (tab()) {
            tabFriends -> kit.tonalButton("Find a table") { host.frontFriends() }
            tabSame -> kit.primaryButton("Pass & play") {
                host.frontPlay(
                    PlayMode.SAME_DEVICE,
                    listOf(
                        LocalPlayer(settings.playerName, settings.playerColor),
                        LocalPlayer("Player 2", com.oea.engine.EngineSettings.PLAYER_COLORS.firstOrNull { it != settings.playerColor }
                            ?: 0xFFFF7043.toInt())
                    )
                )
            }
            else -> kit.primaryButton("Play the AI · L${value("level")}") { host.frontPlay(PlayMode.SOLO) }
        }
        action.textSize = 15.5f
        bar.addView(action, LinearLayout.LayoutParams(0, kit.dp(52), 1.8f))
        bar.setOnApplyWindowInsetsListener { v, insets ->
            val bars = com.oea.games.ui.GameInsets.bars(insets)
            v.setPadding(kit.dp(16), kit.dp(10), kit.dp(16), kit.dp(16) + bars[3])
            insets
        }
        return bar
    }

    // ------------------------------------------------------------------ pieces

    private fun tab(): Int = (prefs.getString(keyTab, "0") ?: "0").toIntOrNull()?.coerceIn(0, 2) ?: 0

    private fun colourIndex(): Int = when (value("color")) {
        "black" -> 2
        "random" -> 1
        else -> 0
    }

    private fun sideLabel(): String = when (value("color")) {
        "black" -> "you play black"
        "random" -> "random side"
        else -> "you play white"
    }

    /** Board view, orientation and turning, in one place. */
    private fun piecesSheet() {
        kit.sheet("Board & pieces", "The board is the same game either way — this is how it is drawn and which way up.") { panel, dialog ->
            val body = LinearLayout(kit.context).apply { orientation = LinearLayout.VERTICAL }
            kit.valueRow(body, "View", if (value("view") == "2d") "2D board" else "3D board", "Flat pieces, or carved ones on a table") {
                set("view", if (value("view") == "2d") "3d" else "2d")
                dialog.dismiss()
            }
            kit.valueRow(body, "Board turning", flipLabel(), "Pass & play only") {
                val order = listOf("auto", "face", "off")
                val current = order.indexOf(value("flip")).coerceAtLeast(0)
                kit.choose("Board turning", null, listOf("Turn to the player to move", "Face to face", "Fixed"), current) { pick ->
                    set("flip", order[pick])
                    dialog.dismiss()
                }
            }
            kit.valueRow(body, "Side", sideLabel(), "You play this colour against the AI") {
                set("color", when (value("color")) { "black" -> "white"; "random" -> "black"; else -> "random" })
                dialog.dismiss()
            }
            kit.note(body, "In a 3D game one finger drags to orbit and two fingers pinch to zoom; in 2D the board stays put and a tap-drag moves a piece.")
            panel.addView(kit.boundedScroll(body, 0.66f))
            panel.addView(kit.buttonRow(kit.primaryButton("Done") { dialog.dismiss(); host.frontRebuild() }))
        }
    }

    private fun flipLabel(): String = when (value("flip")) {
        "face" -> "Face to face"
        "off" -> "Fixed"
        else -> "Turns to move"
    }

    private fun section(parent: LinearLayout, title: String) {
        parent.addView(
            kit.text(title.uppercase(), 10f, palette.textTertiary, GameType.medium).apply {
                letterSpacing = 0.18f
                setPadding(kit.dp(4), kit.dp(18), kit.dp(4), kit.dp(8))
            },
            LinearLayout.LayoutParams(-1, -2)
        )
    }

    private fun strengthBlurb(level: Int): String = when {
        level <= 2 -> "sees one move ahead, and misses things"
        level <= 4 -> "trades pieces sensibly"
        level <= 6 -> "counts captures and knows openings"
        level <= 8 -> "deep search with a real endgame"
        else -> "maximum depth — no mercy"
    }

    /**
     * The board on the table.
     *
     * Drawn in code from the same colours the in-game 2D board uses (ivory and
     * walnut), with the starting position and brass file/rank labels. Tapping it
     * turns the board, which doubles as the orientation control for the match.
     */
    private inner class BoardPreview(context: android.content.Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("serif", Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        private val frame = RectF()
        private var flipped = false

        /** Rank 0 is white's back rank; the board reads from white's side unless flipped. */
        private val home = arrayOf(
            "♖♘♗♕♔♗♘♖",
            "♙♙♙♙♙♙♙♙",
            "        ",
            "        ",
            "        ",
            "        ",
            "♟♟♟♟♟♟♟♟",
            "♜♞♝♛♚♝♞♜"
        )
        private val whitePieces = "♖♘♗♕♔♙"

        init {
            isClickable = true
            contentDescription = "Chess board preview"
            setOnClickListener {
                flipped = !flipped
                invalidate()
            }
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val side = minOf(w, h) - 8f
            val left = (w - side) / 2f
            val top = (h - side) / 2f
            frame.set(left, top, left + side, top + side)
            // Walnut frame around the board.
            paint.color = 0xFF6B452B.toInt()
            canvas.drawRoundRect(
                RectF(left - 8f, top - 8f, left + side + 8f, top + side + 8f),
                10f, 10f, paint
            )
            val sq = side / 8f
            for (row in 0 until 8) for (col in 0 until 8) {
                val r = if (flipped) 7 - row else row
                val c = if (flipped) 7 - col else col
                paint.color = if ((r + c) % 2 == 0) 0xFFF0D9B5.toInt() else 0xFFB58863.toInt()
                canvas.drawRect(left + col * sq, top + row * sq, left + (col + 1) * sq, top + (row + 1) * sq, paint)
            }
            // The centre, highlit, so the preview reads as a live board.
            paint.color = 0x33FFFFFF
            canvas.drawRect(left + 3 * sq, top + 3 * sq, left + 5 * sq, top + 5 * sq, paint)
            // The starting position, oriented to the side you chose to play.
            for (rank in 0 until 8) {
                val row = home[rank]
                for (file in 0 until 8) {
                    val glyphChar = row[file]
                    if (glyphChar == ' ') continue
                    val screenRow = if (flipped) 7 - rank else rank
                    val screenCol = if (flipped) 7 - file else file
                    drawGlyph(canvas, glyphChar.toString(), screenCol, screenRow, left, top, sq)
                }
            }
            // Brass coordinates.
            glyph.textSize = sq * 0.2f
            glyph.color = 0x99FFF3D6.toInt()
            for (i in 0 until 8) {
                canvas.drawText("${'a' + i}", left + (i + 0.5f) * sq, top + side + 1f, glyph)
                canvas.drawText("${8 - i}", left - 12f, top + (i + 0.66f) * sq, glyph)
            }
        }

        private fun drawGlyph(canvas: Canvas, g: String, col: Int, row: Int, left: Float, top: Float, sq: Float) {
            glyph.textSize = sq * 0.82f
            glyph.color = if (g[0] in whitePieces) 0xFFFDF8EC.toInt() else 0xFF211C15.toInt()
            glyph.setShadowLayer(sq * 0.08f, 0f, sq * 0.05f, 0x66000000)
            val x = left + (col + 0.5f) * sq
            val y = top + (row + 0.78f) * sq
            canvas.drawText(g, x, y, glyph)
            glyph.clearShadowLayer()
        }
    }
}
