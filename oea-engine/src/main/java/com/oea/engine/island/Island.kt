package com.oea.engine.island

import android.os.SystemClock
import com.oea.engine.EngineSettings
import com.oea.engine.core.GameDescriptor

/**
 * The two Dynamic Island styles of the OEA launcher:
 * - [CARD]: the capsule card under the status bar;
 * - [CAMERA]: the black pill around the front camera that expands when tapped.
 */
enum class IslandStyle(val label: String) { CARD("Card"), CAMERA("Camera island") }

/**
 * Where one game's live activity goes when the user shows both styles.
 * With a single style enabled, everything uses that style.
 */
enum class IslandPlacement(val label: String, val description: String) {
    AUTO("Automatic", "The game picks: compact status in the camera island, rich details in the card"),
    CARD("Card", "Always in the card under the status bar"),
    CAMERA("Camera island", "Always in the island around the camera"),
    BOTH("Both", "Compact status around the camera and details in the card at the same time"),
}

/** A button on the expanded island; tapping it calls [com.oea.engine.core.OeaGame.onIslandAction]. */
data class IslandAction(val id: String, val label: String)

/**
 * A game's ongoing status for the island, e.g. "Chess vs Ada • Your move • 4:32".
 * Publish with `ctx.island.update(...)`; the engine only forwards changes.
 */
data class LiveActivity(
    val title: String,
    val subtitle: String = "",
    /** Short status for the collapsed camera pill (defaults to [title]). */
    val compactText: String = "",
    /** Right side of the pill: a timer, rank or score. */
    val trailing: String = "",
    /** Leading glyph; defaults to the game's glyph. */
    val glyph: String = "",
    /** ARGB accent; 0 uses the game's accent. */
    val accent: Int = 0,
    /** 0..1 progress bar, or negative for none. */
    val progress: Float = -1f,
    val actions: List<IslandAction> = emptyList(),
    /** Overrides the game's preferred style for [IslandPlacement.AUTO]. */
    val preferred: IslandStyle? = null,
    /** Something needs the player's attention (their turn, low time, under attack). */
    val urgent: Boolean = false,
)

/** A short notice (a few seconds), e.g. "Opponent moved Nf3" or "Zone shrinking". */
data class IslandEvent(
    val text: String,
    val glyph: String = "",
    val durationMs: Long = 2600,
    val accent: Int = 0,
    val important: Boolean = false,
)

/** What the host app (the OEA launcher) implements to display game activities. */
interface IslandHost {
    /** Styles the user currently shows on Home. Empty = island disabled. */
    fun enabledStyles(): Set<IslandStyle>
    /** The user's placement choice for [gameId] (used when both styles are enabled). */
    fun placement(gameId: String): IslandPlacement
    /** Shows, updates or (with null) removes a game's live activity on [surfaces]. */
    fun publish(game: GameDescriptor, activity: LiveActivity?, surfaces: Set<IslandStyle>, onAction: ((String) -> Unit)?)
    /** Shows a transient event on [surfaces]. */
    fun event(game: GameDescriptor, event: IslandEvent, surfaces: Set<IslandStyle>)
}

/** Default host when the engine runs outside the launcher: nothing is shown. */
object NoIslandHost : IslandHost {
    override fun enabledStyles(): Set<IslandStyle> = emptySet()
    override fun placement(gameId: String) = IslandPlacement.AUTO
    override fun publish(game: GameDescriptor, activity: LiveActivity?, surfaces: Set<IslandStyle>, onAction: ((String) -> Unit)?) = Unit
    override fun event(game: GameDescriptor, event: IslandEvent, surfaces: Set<IslandStyle>) = Unit
}

/** Shared routing rule, so the engine, games and the launcher always agree. */
object IslandRouting {
    fun resolve(enabled: Set<IslandStyle>, placement: IslandPlacement, preferred: IslandStyle): Set<IslandStyle> {
        if (enabled.isEmpty()) return emptySet()
        if (enabled.size == 1) return enabled
        return when (placement) {
            IslandPlacement.CARD -> setOf(IslandStyle.CARD)
            IslandPlacement.CAMERA -> setOf(IslandStyle.CAMERA)
            IslandPlacement.BOTH -> setOf(IslandStyle.CARD, IslandStyle.CAMERA)
            IslandPlacement.AUTO -> setOf(preferred)
        }
    }
}

/**
 * A running game's handle to the Dynamic Island. Supports the card style, the
 * camera style, or both at once, following the user's launcher setting and
 * per-game placement.
 */
class GameIsland internal constructor(
    private val descriptor: GameDescriptor,
    @Suppress("unused") private val settings: EngineSettings,
    private val host: () -> IslandHost,
    private val toGameThread: (() -> Unit) -> Unit,
    private val onActionCallback: (String) -> Unit,
) {
    private var last: LiveActivity? = null
    private var lastPublishAt = 0L
    private var pending: LiveActivity? = null
    private var ended = true

    /** Styles currently enabled on the device. */
    val enabledStyles: Set<IslandStyle> get() = runCatching { host().enabledStyles() }.getOrDefault(emptySet())
    val placement: IslandPlacement get() = runCatching { host().placement(descriptor.id) }.getOrDefault(IslandPlacement.AUTO)
    val isAvailable get() = enabledStyles.isNotEmpty()

    /** Where [activity] would appear right now. */
    fun surfacesFor(activity: LiveActivity? = last): Set<IslandStyle> =
        IslandRouting.resolve(enabledStyles, placement, activity?.preferred ?: descriptor.preferredIsland)

    /** Publishes (or updates) the live activity. Cheap to call every frame: only changes are sent, at most 4×/s. */
    fun update(activity: LiveActivity) {
        if (activity == last && !ended) return
        pending = activity
        flushIfDue()
    }

    /** Removes the live activity. */
    fun end() {
        pending = null
        if (ended) return
        ended = true
        last = null
        runCatching { host().publish(descriptor, null, emptySet(), null) }
    }

    /** Shows a transient notice. */
    fun event(text: String, glyph: String = "", durationMs: Long = 2600, important: Boolean = false) {
        val e = IslandEvent(text, glyph.ifEmpty { descriptor.glyph }, durationMs, descriptor.accent, important)
        runCatching { host().event(descriptor, e, IslandRouting.resolve(enabledStyles, placement, descriptor.preferredIsland)) }
    }

    internal fun tick() = flushIfDue()

    private fun flushIfDue() {
        val p = pending ?: return
        val now = SystemClock.uptimeMillis()
        if (now - lastPublishAt < 250L && !ended) return
        pending = null
        last = p
        ended = false
        lastPublishAt = now
        val withDefaults = p.copy(glyph = p.glyph.ifEmpty { descriptor.glyph }, accent = if (p.accent != 0) p.accent else descriptor.accent)
        runCatching {
            host().publish(descriptor, withDefaults, surfacesFor(p)) { id -> toGameThread { onActionCallback(id) } }
        }
    }
}
