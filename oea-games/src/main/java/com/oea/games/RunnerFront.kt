package com.oea.games

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.oea.engine.core.LocalPlayer
import com.oea.engine.core.PlayMode
import com.oea.games.ui.GameArt
import com.oea.games.ui.GameColors
import com.oea.games.ui.GameType

/**
 * Endless Days' front screen: the arcade cabinet.
 *
 * A runner's front end is a score marquee over a start button. So the top of
 * this screen is the machine's backglass — best distance, coins banked, the
 * last run — and directly under it a single huge RUN bar. Below that sit the two
 * ways this game is actually competitive (a duel on the phone in your hand, and
 * a ghost race against a friend), the swipe deck, and the runner's own colours.
 *
 * It has no aim, no ADS and no turn limit: a runner is three lanes and a swipe.
 */
class RunnerFront(host: GameFrontHost) : GameFront(host) {

    override fun build(): View {
        val page = kit.page(padBottomDp = 18)
        val content = page.content

        // ------------------------------------------------------------ backglass
        val marquee = LinearLayout(kit.context).apply { orientation = LinearLayout.VERTICAL }
        val best = savedBest("score")
        val coins = prefs.getLong("total_coins", 0L)
        val record = LinearLayout(kit.context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        record.addView(
            kit.text(best.toString(), 34f, palette.accent, GameType.mono).apply { letterSpacing = -0.02f },
            LinearLayout.LayoutParams(-2, -2)
        )
        record.addView(
            LinearLayout(kit.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(kit.dp(10), 0, 0, 0)
            }.also { labels ->
                labels.addView(caption("Best score"))
                labels.addView(
                    kit.text(if (best > 0) "Personal best" else "New machine", 11.5f, palette.textSecondary)
                )
            },
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        record.addView(
            kit.text("◉ ${pretty(coins)}", 14f, palette.signal, GameType.mono).apply {
                setPadding(kit.dp(10), kit.dp(6), kit.dp(10), kit.dp(6))
                background = kit.rounded(GameColors.alpha(palette.signal, 0x1F), 12, GameColors.alpha(palette.signal, 0x55), 1)
            },
            LinearLayout.LayoutParams(-2, -2)
        )
        marquee.addView(record, LinearLayout.LayoutParams(-1, -2))
        marquee.addView(
            kit.text("COINS BANKED", 9.5f, palette.textTertiary, GameType.medium).apply { letterSpacing = 0.14f },
            LinearLayout.LayoutParams(-1, -2)
        )

        kit.heroBand(
            content,
            heightDp = 190,
            title = "Endless Days",
            eyebrow = "Night yard · endless rail",
            subtitle = "One track, no finish line. Dodge, jump, roll.",
            art = GameArt.hero("runner", scheme),
            leftInsetDp = 22,
            footer = marquee
        )

        // ---------------------------------------------------------------- run
        val run = kit.button("RUN", palette.accent, palette.onAccent) {
            startSolo()
        }
        run.textSize = 24f
        run.letterSpacing = 0.22f
        run.typeface = GameType.black
        content.addView(run, LinearLayout.LayoutParams(-1, kit.dp(76)).apply { topMargin = kit.dp(14) })
        content.addView(
            kit.text(
                if (best > 0) "Last best $best · the track reshuffles every run" else "The first run sets the record",
                11.5f,
                palette.textTertiary
            ).apply { gravity = Gravity.CENTER; setPadding(0, kit.dp(8), 0, 0) },
            LinearLayout.LayoutParams(-1, -2)
        )

        // --------------------------------------------------------------- duel
        section(content, "Duel on this phone")
        val duelCard = panel(content)
        kit.segmented(
            duelCard,
            listOf("Split screen", "Take turns"),
            if (value("duel") == "turns") 1 else 0
        ) { pick ->
            host.frontSetOption("duel", if (pick == 1) "turns" else "split")
            host.frontRebuild()
        }
        duelCard.addView(
            kit.text(duelBlurb(), 12f, palette.textSecondary).apply {
                setLineSpacing(kit.dpf(2f), 1f)
                setPadding(kit.dp(2), kit.dp(10), kit.dp(2), kit.dp(4))
            },
            LinearLayout.LayoutParams(-1, -2)
        )
        val duelPlayers = LinearLayout(kit.context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        duelPlayers.addView(playerChip("P1", settings.playerName, settings.playerColor), LinearLayout.LayoutParams(0, -2, 1f))
        duelPlayers.addView(
            kit.text("VS", 12f, palette.textTertiary, GameType.black).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(kit.dp(38), -2)
        )
        val second = secondPlayer()
        duelPlayers.addView(playerChip("P2", second.first, second.second), LinearLayout.LayoutParams(0, -2, 1f))
        duelCard.addView(duelPlayers, LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(6) })
        val playDuel = kit.tonalButton(if (value("duel") == "turns") "Take turns" else "Start duel") {
            host.frontPlay(
                PlayMode.SAME_DEVICE,
                listOf(LocalPlayer(settings.playerName, settings.playerColor), LocalPlayer(second.first, second.second))
            )
        }
        duelCard.addView(playDuel, LinearLayout.LayoutParams(-1, kit.dp(50)).apply { topMargin = kit.dp(12) })

        // --------------------------------------------------------- ghost race
        section(content, "Ghost race")
        val raceCard = panel(content)
        kit.chipRow(
            raceCard,
            listOf("Last runner", "First to 2 km", "First to 5 km"),
            raceIndex(),
            { pick ->
                host.frontSetOption("race", listOf("survival", "2k", "5k")[pick])
                host.frontRebuild()
            }
        )
        raceCard.addView(
            kit.text(
                "Everyone runs the identical track, seeded on the host's phone. Friends appear as ghosts beside you — " +
                    "the same obstacles, the same coins, the same lanes.",
                12f,
                palette.textSecondary
            ).apply { setLineSpacing(kit.dpf(2f), 1f); setPadding(kit.dp(2), kit.dp(10), kit.dp(2), 0) },
            LinearLayout.LayoutParams(-1, -2)
        )
        raceCard.addView(
            kit.buttonRow(
                kit.quietButton("Wi-Fi / hotspot") { host.frontFriends() },
                kit.tonalButton("Online code") { host.frontFriends() }
            ),
            LinearLayout.LayoutParams(-1, -2)
        )

        // -------------------------------------------------------------- deck
        section(content, "The deck")
        val deck = panel(content)
        deck.addView(swipeGlyph("◀ ▶", "Swipe left or right", "Change lane"), LinearLayout.LayoutParams(-1, -2))
        deck.addView(swipeGlyph("▲", "Swipe up", "Jump low hurdles; swipe just before landing to jump again"), LinearLayout.LayoutParams(-1, -2))
        deck.addView(swipeGlyph("▼", "Swipe down", "Roll under barriers"), LinearLayout.LayoutParams(-1, -2))
        deck.addView(swipeGlyph("⇅", "Ride a ramp", "Land on a train roof — a whole clear line"), LinearLayout.LayoutParams(-1, -2))
        deck.addView(
            kit.text("Arrow keys, WASD or gamepad buttons also work — the same three lanes, the same swipe timing.", 11.5f, palette.textTertiary)
                .apply { setPadding(kit.dp(2), kit.dp(8), kit.dp(2), 0) },
            LinearLayout.LayoutParams(-1, -2)
        )
        deck.addView(kit.buttonRow(kit.quietButton("Full control guide") { host.frontControls() }), LinearLayout.LayoutParams(-1, -2))

        section(content, "Run setup")
        val setup = panel(content)
        setup.addView(kit.text("SWIPE SENSITIVITY", 11f, palette.textSecondary, GameType.medium))
        kit.segmented(setup, listOf("Responsive", "Deliberate"), if (value("swipes") == "deliberate") 1 else 0) { pick ->
            host.frontSetOption("swipes", if (pick == 1) "deliberate" else "responsive")
            host.frontRebuild()
        }
        setup.addView(kit.text("YARD MUSIC", 11f, palette.textSecondary, GameType.medium).apply { setPadding(0, kit.dp(14), 0, 0) })
        kit.chipRow(setup, listOf("Night drive", "Sunrise", "Off"), listOf("night", "sunrise", "off").indexOf(value("track")).coerceAtLeast(0), { pick ->
            host.frontSetOption("track", listOf("night", "sunrise", "off")[pick])
            host.frontRebuild()
        })
        setup.addView(kit.text("Collect coins in a quick streak to build FLOW and earn more points per coin. A stumble breaks it.", 12f, palette.textSecondary).apply { setPadding(0, kit.dp(10), 0, 0) })

        // ------------------------------------------------------------ runner
        section(content, "Your runner")
        val identity = panel(content)
        identity.addView(
            kit.text("Colour", 13.5f, palette.text, GameType.medium).apply { setPadding(kit.dp(2), 0, 0, kit.dp(8)) },
            LinearLayout.LayoutParams(-1, -2)
        )
        colourRow(identity, com.oea.engine.EngineSettings.PLAYER_COLORS, settings.playerColor) { colour ->
            settings.playerColor = colour
            host.frontRebuild()
        }
        identity.addView(
            kit.text("You run as ${settings.playerName}", 12f, palette.textSecondary).apply { setPadding(kit.dp(2), kit.dp(10), kit.dp(2), 0) },
            LinearLayout.LayoutParams(-1, -2)
        )
        kit.valueRow(identity, "Name", settings.playerName, "Shown on ghosts and in the duel header") {
            kit.prompt("Your name", "Name", settings.playerName) { picked ->
                if (!picked.isNullOrBlank()) {
                    settings.playerName = picked.trim().take(com.oea.engine.EngineSettings.MAX_NAME)
                    host.frontRebuild()
                }
            }
        }

        // ------------------------------------------------------------ cabinet
        section(content, "Cabinet")
        kit.valueRow(content, "Sound", if (settings.sound) "On" else "Off", "Coins, jumps, crashes — the arcade's own noises") { audioSheet("Endless Days audio") }
        kit.valueRow(content, "Music", if (settings.music) "On" else "Off", "The yard's running theme") {
            settings.music = !settings.music
            host.frontRebuild()
        }
        kit.valueRow(content, "Graphics", qualityLabel(), "Quality, frame rate and the FPS counter") { graphicsSheet("Endless Days graphics") }
        kit.valueRow(content, "Screen & access", "Menu size, contrast, motion", "Every OEA game on this phone") { host.frontAccess() }
        kit.valueRow(content, "About Endless Days", "OEA Engine ${com.oea.engine.OeaEngine.VERSION}", "The yard, the trains and the rules") { aboutSheet() }

        kit.note(
            content,
            "Survive the yard: trains come at you on three lines, hurdles jump, barriers roll, and ramps put you on the roofs above the rails. " +
                "Coins bank to this phone; the best score is kept per device."
        )

        // Keep the last line clear of the system bar and the thumb.
        val base = content.paddingBottom
        page.root.setOnApplyWindowInsetsListener { _, insets ->
            content.setPadding(content.paddingLeft, content.paddingTop, content.paddingRight, base + com.oea.games.ui.GameInsets.bars(insets)[3])
            insets
        }
        return page.root
    }

    // -------------------------------------------------------------- actions

    private fun startSolo() {
        host.frontPlay(PlayMode.SOLO)
    }

    private fun secondPlayer(): Pair<String, Int> {
        val name = prefs.getString("p2_name", "Player 2")?.takeIf { it.isNotBlank() } ?: "Player 2"
        val mine = settings.playerColor
        val colour = com.oea.engine.EngineSettings.PLAYER_COLORS.firstOrNull { it != mine } ?: 0xFFFF7043.toInt()
        return name to colour
    }

    private fun raceIndex(): Int = when (value("race")) {
        "2k" -> 1
        "5k" -> 2
        else -> 0
    }

    private fun duelBlurb(): String = if (value("duel") == "turns") {
        "The same seeded track, one after the other. The higher score takes the duel — the loser can demand a rematch on the spot."
    } else {
        "Both players run at once, split down the middle and turned face to face. Two sets of thumbs, one phone."
    }

    // --------------------------------------------------------------- pieces

    private fun section(parent: LinearLayout, title: String) {
        parent.addView(
            kit.text(title.uppercase(), 10f, palette.textTertiary, GameType.medium).apply {
                letterSpacing = 0.18f
                setPadding(kit.dp(4), kit.dp(18), kit.dp(4), kit.dp(8))
            },
            LinearLayout.LayoutParams(-1, -2)
        )
    }

    private fun panel(parent: LinearLayout): LinearLayout {
        val card = LinearLayout(kit.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(kit.dp(14), kit.dp(12), kit.dp(14), kit.dp(14))
            background = kit.rounded(palette.card, 20, palette.stroke, 1)
        }
        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(4) })
        return card
    }

    private fun playerChip(tag: String, name: String, colour: Int): LinearLayout =
        LinearLayout(kit.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                View(kit.context).apply {
                    background = kit.rounded(colour, 6)
                },
                LinearLayout.LayoutParams(kit.dp(6), kit.dp(30)).apply { rightMargin = kit.dp(8) }
            )
            addView(
                LinearLayout(kit.context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(kit.text(tag, 9.5f, palette.textTertiary, GameType.medium).apply { letterSpacing = 0.12f })
                    addView(kit.text(trim(name, 14), 13f, palette.text, GameType.medium))
                },
                LinearLayout.LayoutParams(0, -2, 1f)
            )
        }

    private fun swipeGlyph(glyph: String, title: String, detail: String): LinearLayout =
        LinearLayout(kit.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, kit.dp(7), 0, kit.dp(7))
            addView(
                kit.text(glyph, 15f, palette.accent, GameType.black).apply {
                    gravity = Gravity.CENTER
                    background = kit.rounded(palette.well, 10)
                },
                LinearLayout.LayoutParams(kit.dp(52), kit.dp(36)).apply { rightMargin = kit.dp(12) }
            )
            addView(
                LinearLayout(kit.context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(kit.text(title, 13.5f, palette.text, GameType.medium))
                    addView(kit.text(detail, 11.5f, palette.textTertiary))
                },
                LinearLayout.LayoutParams(0, -2, 1f)
            )
        }

    private fun qualityLabel(): String = when (settings.quality) {
        "low" -> "Low"
        "medium" -> "Medium"
        "high" -> "High"
        "ultra" -> "Ultra"
        else -> "Auto"
    }
}
