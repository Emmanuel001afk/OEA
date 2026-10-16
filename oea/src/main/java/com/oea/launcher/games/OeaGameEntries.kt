package com.oea.launcher.games

import com.oea.games.OeaStandaloneGameActivity
import com.oea.launcher.gameboost.OeaGameBoost

/**
 * Dedicated launcher entries for OEA's built-in games.
 *
 * These activities intentionally inherit the standalone game shell rather
 * than the OEA Games hub. Each opens its own game front menu and remains
 * independent of the hub's navigation and settings screens.
 *
 * Keep the ids aligned with the OEA Engine registry so Game Boost can identify
 * the games consistently.
 */

/** Endless Days — the train-yard runner. */
class OeaEndlessDaysActivity : OeaStandaloneGameActivity() {
    override val standaloneGameId: String get() = "runner"
    override fun onStandaloneMatchStarted(gameId: String) { OeaGameBoost.onOeaGameOpened(this, gameId) }
}

/** Send Off — the competitive shooter. */
class OeaSendOffActivity : OeaStandaloneGameActivity() {
    override val standaloneGameId: String get() = "arena"
    override fun onStandaloneMatchStarted(gameId: String) { OeaGameBoost.onOeaGameOpened(this, gameId) }
}

/** Chess — rules, AI levels, pass & play and multiplayer. */
class OeaChessGameActivity : OeaStandaloneGameActivity() {
    override val standaloneGameId: String get() = "chess"
    override fun onStandaloneMatchStarted(gameId: String) { OeaGameBoost.onOeaGameOpened(this, gameId) }
}
