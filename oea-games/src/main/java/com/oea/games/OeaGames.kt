package com.oea.games

import android.content.Context
import com.oea.engine.OeaEngine
import com.oea.games.arena.OeaArena
import com.oea.games.chess.OeaChess
import com.oea.games.lab.EngineLab
import com.oea.games.runner.OeaRunner

/**
 * First-party games. The public names are Endless Days, Send Off, Chess and Engine Lab;
 * internal ids stay stable for saves and network compatibility. Each one is a [com.oea.engine.core.GameDescriptor]
 * registered with the engine; to add your own game, write an
 * `OeaGame` subclass with a descriptor and register it here (or from any
 * module) — the Games hub, lobbies, multiplayer and the Dynamic Island pick
 * it up automatically.
 */
object OeaGames {
    @Volatile private var registered = false

    fun registerBuiltIns(context: Context) {
        OeaEngine.init(context)
        if (registered) return
        registered = true
        OeaEngine.registry.register(OeaRunner.descriptor)
        OeaEngine.registry.register(OeaArena.descriptor)
        OeaEngine.registry.register(OeaChess.descriptor)
        OeaEngine.registry.register(EngineLab.descriptor)
    }
}
