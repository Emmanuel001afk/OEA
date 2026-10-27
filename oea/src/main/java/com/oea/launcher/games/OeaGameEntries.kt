package com.oea.launcher.games

import com.oea.games.OeaStandaloneGameActivity

/**
 * The three games as true standalone mini-apps: each launcher entry IS the
 * game's own app — its own front menu (Play, Match setup, Play with friends,
 * Controls & aim, About), its own remembered settings, its own multiplayer and
 * its own task — built on the shared [OeaStandaloneGameActivity] from
 * `:oea-games`. Nothing routes through the old hub anymore: tapping the icon
 * opens the game, and Play starts the match in this app.
 *
 * The stable engine ids (`runner`, `arena`, `chess`) are unchanged, so Game
 * Boost keeps detecting these games exactly as before: the boost session is
 * reported from [onMatchStarted], which fires when a match really reaches the
 * screen (same hook the in-hub player uses).
 */

/**
 * Every OEA screen one of the built-in games can play on.
 *
 * Game Boost watches the foreground window to know when a session has ended,
 * and it must count these activities as "still in the game". The list lives
 * next to the classes themselves so a new entry cannot silently fall out of
 * it — which is exactly what happened when the games became standalone apps
 * and the detection still only knew the old hub and the shared shell.
 */
val oeaGameEntryScreens: List<String> = listOf(
    OeaEndlessDaysActivity::class.java.name,
    OeaSendOffActivity::class.java.name,
    OeaChessGameActivity::class.java.name,
)

/** Endless Days — the train-yard runner. */
class OeaEndlessDaysActivity : OeaStandaloneGameActivity() {
    override val standaloneGameId: String = "runner"

    override fun onMatchStarted(gameId: String) {
        runCatching { com.oea.launcher.gameboost.OeaGameBoost.onOeaGameOpened(applicationContext, gameId) }
    }
}

/** Send Off — the competitive shooter. */
class OeaSendOffActivity : OeaStandaloneGameActivity() {
    override val standaloneGameId: String = "arena"

    /** Every finger and key the match answers to, on one screen. */
    override val controlLegend: List<Pair<String, String>> = listOf(
        "✋" to "Drag the left stick to move — it floats, so it never runs out",
        "🖐" to "Drag anywhere on the right side of the screen to look around",
        "🔥" to "Hold FIRE to shoot; drag it while held to fine-aim",
        "⊕" to "ADS: aim down the sights — zoomed in, steadier, slower",
        "↑" to "Jump",
        "▼" to "Crouch: smaller, steadier, harder to spot",
        "R" to "Reload the active weapon",
        "+" to "Use a medkit to heal",
        "💣" to "Throw a frag grenade along your aim (also G)",
        "✳" to "Throw a smoke grenade to hide (also H)",
        "◎" to "Pick up loot when the PICK button appears",
        "1 / 2" to "Switch weapon slot (also Y on gamepad)",
    )

    override val keyLegend: List<String> = listOf(
        "Mouse look · Shift ADS · Space jump · C crouch · R reload · G frag · H smoke · E pick · 1/2 weapons · Esc pause",
    )

    override fun onMatchStarted(gameId: String) {
        runCatching { com.oea.launcher.gameboost.OeaGameBoost.onOeaGameOpened(applicationContext, gameId) }
    }
}

/** Chess — complete rules, AI levels, pass & play and multiplayer. */
class OeaChessGameActivity : OeaStandaloneGameActivity() {
    override val standaloneGameId: String = "chess"

    override fun onMatchStarted(gameId: String) {
        runCatching { com.oea.launcher.gameboost.OeaGameBoost.onOeaGameOpened(applicationContext, gameId) }
    }
}
