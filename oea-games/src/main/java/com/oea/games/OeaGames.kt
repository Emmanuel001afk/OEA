package com.oea.games

import android.content.Context
import com.oea.engine.OeaEngine
import com.oea.engine.core.GameDescriptor
import com.oea.games.arena.OeaArena
import com.oea.games.chess.OeaChess
import com.oea.games.lab.EngineLab
import com.oea.games.runner.OeaRunner

/**
 * First-party games. The public names are Endless Days, Send Off, Chess and Engine Lab;
 * internal ids stay stable for saves and network compatibility. Each one is a [GameDescriptor]
 * registered with the engine; to add your own game, write an
 * `OeaGame` subclass with a descriptor and register it here (or from any
 * module) — the Games hub, lobbies, multiplayer and the Dynamic Island pick
 * it up automatically.
 *
 * Registration can be narrowed with [only]. The separately installed apps each
 * ship one game, so their engine registry should hold that one game and
 * nothing else: it is what the front screen offers and what "which game is
 * this?" answers anywhere in the app. The launcher hub registers everything.
 */
object OeaGames {

    private val lock = Any()
    private val registered = LinkedHashSet<String>()

    /** Engine ids of every first-party game, in hub order. */
    val builtInIds: List<String> = listOf("runner", "arena", "chess", "lab")

    /**
     * Registers the built-in games whose id is in [only], or all of them when
     * [only] is null. Calling this again with a wider set adds the missing
     * ones; a game is never registered twice (the registry replaces by id).
     */
    fun registerBuiltIns(context: Context, only: Collection<String>? = null) {
        OeaEngine.init(context)
        val wanted = only
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
        synchronized(lock) {
            register("runner", wanted) { OeaRunner.descriptor }
            register("arena", wanted) { OeaArena.descriptor }
            register("chess", wanted) { OeaChess.descriptor }
            register("lab", wanted) { EngineLab.descriptor }
        }
    }

    /** True when [id] has already been handed to the engine registry. */
    fun isRegistered(id: String): Boolean = synchronized(lock) { id in registered }

    private inline fun register(id: String, wanted: Set<String>?, descriptor: () -> GameDescriptor) {
        if (wanted != null && id !in wanted) return
        if (registered.add(id)) OeaEngine.registry.register(descriptor())
    }
}
