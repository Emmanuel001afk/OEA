package com.oea.launcher.games

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.oea.engine.OeaEngine
import com.oea.engine.core.GameDescriptor
import com.oea.engine.island.IslandEvent
import com.oea.engine.island.IslandHost
import com.oea.engine.island.IslandPlacement
import com.oea.engine.island.IslandStyle
import com.oea.engine.island.LiveActivity
import com.oea.launcher.data.OeaDataStore
import com.oea.launcher.workspace.OeaDynamicIslandView
import java.util.concurrent.CopyOnWriteArraySet

/**
 * In-process state of game live activities, mirrored by both Dynamic Island
 * styles (the card under the status bar and the camera island) on Home and
 * inside games. Works like [com.oea.launcher.phone.OeaCallState].
 */
object OeaGameLiveState {
    class Entry(val game: GameDescriptor, val activity: LiveActivity, val surfaces: Set<IslandStyle>, val onAction: ((String) -> Unit)?)
    class Event(val game: GameDescriptor, val event: IslandEvent, val surfaces: Set<IslandStyle>, val until: Long)

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    @Volatile var current: Entry? = null
        private set
    @Volatile var event: Event? = null
        private set

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun notifyChanged() = main.post { listeners.forEach { runCatching { it() } } }

    internal fun publish(game: GameDescriptor, activity: LiveActivity?, surfaces: Set<IslandStyle>, onAction: ((String) -> Unit)?) {
        current = if (activity == null) { if (current?.game?.id == game.id) null else current } else Entry(game, activity, surfaces, onAction)
        notifyChanged()
    }

    internal fun post(game: GameDescriptor, e: IslandEvent, surfaces: Set<IslandStyle>) {
        val ev = Event(game, e, surfaces, SystemClock.uptimeMillis() + e.durationMs)
        event = ev
        notifyChanged()
        main.postDelayed({ if (event === ev) { event = null; notifyChanged() } }, e.durationMs + 50L)
    }

    fun activityFor(surface: OeaDynamicIslandView.Surface): Entry? = current?.takeIf { styleOf(surface) in it.surfaces }

    fun eventFor(surface: OeaDynamicIslandView.Surface): Event? =
        event?.takeIf { styleOf(surface) in it.surfaces && SystemClock.uptimeMillis() < it.until }

    fun styleOf(surface: OeaDynamicIslandView.Surface) = if (surface == OeaDynamicIslandView.Surface.CAMERA) IslandStyle.CAMERA else IslandStyle.CARD

    /** Brings the running game back to the front (the island's Resume / long-press). */
    fun resume(context: Context) {
        val intent = Intent(context, OeaGamesActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        runCatching { context.startActivity(intent) }
    }
}

/** The launcher's implementation of the engine's island bridge: follows the user's island style setting. */
class OeaLauncherIslandHost(context: Context) : IslandHost {
    private val store = OeaDataStore.get(context.applicationContext)

    override fun enabledStyles(): Set<IslandStyle> {
        if (!store.dynamicIslandEnabled()) return emptySet()
        return when (store.islandStyle()) {
            OeaDataStore.ISLAND_CAMERA -> setOf(IslandStyle.CAMERA)
            OeaDataStore.ISLAND_BOTH -> setOf(IslandStyle.CARD, IslandStyle.CAMERA)
            else -> setOf(IslandStyle.CARD)
        }
    }

    override fun placement(gameId: String): IslandPlacement = OeaEngine.settings.islandPlacement(gameId)

    override fun publish(game: GameDescriptor, activity: LiveActivity?, surfaces: Set<IslandStyle>, onAction: ((String) -> Unit)?) =
        OeaGameLiveState.publish(game, activity, surfaces, onAction)

    override fun event(game: GameDescriptor, event: IslandEvent, surfaces: Set<IslandStyle>) =
        OeaGameLiveState.post(game, event, surfaces)
}
