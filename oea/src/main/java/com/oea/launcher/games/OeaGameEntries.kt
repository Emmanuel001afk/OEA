package com.oea.launcher.games

/**
 * The three games as standalone mini-apps: each one has its own launcher icon,
 * label and task, and opens straight on its own front menu (Play, match setup,
 * With friends, About). The shared "OEA Games" container is gone; the engine,
 * the match hand-off and the live game screen are the same as before.
 *
 * Game Boost finds these games through the engine registry (ids below), so
 * they are detected as games without any extra setup.
 */

/** Endless Days — the train-yard runner. */
class OeaEndlessDaysActivity : OeaGamesActivity() {
    override val pinnedGameId: String? get() = "runner"
}

/** Send Off — the competitive shooter. */
class OeaSendOffActivity : OeaGamesActivity() {
    override val pinnedGameId: String? get() = "arena"
}

/** Chess — complete rules, AI levels, pass & play and multiplayer. */
class OeaChessGameActivity : OeaGamesActivity() {
    override val pinnedGameId: String? get() = "chess"
}
