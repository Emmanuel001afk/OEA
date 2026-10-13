package com.oea.engine.core

import android.content.Context
import com.oea.engine.EngineSettings
import com.oea.engine.audio.Audio
import com.oea.engine.graphics.Frame
import com.oea.engine.graphics.Graphics
import com.oea.engine.input.Haptics
import com.oea.engine.input.Input
import com.oea.engine.island.GameIsland
import com.oea.engine.island.IslandStyle
import com.oea.engine.net.session.NetSession
import com.oea.engine.perf.PerformanceManager

/** How a match was started. The hub builds this; the game reads it in [OeaGame.onCreate]. */
enum class PlayMode(val label: String) {
    /** One player (against AI or bots, or just for score). */
    SOLO("Solo"),
    /** Several people on this one device: pass-and-play, split screen or face-to-face. */
    SAME_DEVICE("Same device"),
    /** Local network: same Wi-Fi router or one phone's hotspot. */
    LAN("Wi-Fi / hotspot"),
    /** Remote play over WebRTC, connected by exchanging a code or QR. */
    ONLINE("Online code / QR"),
}

enum class GameOrientation { PORTRAIT, LANDSCAPE, ANY }

/** What the game renders natively. Higher dimensions are projected (see `math.nd`). */
enum class GameDimension(val label: String) { D2("2D"), D3("3D"), D4("4D"), DN("N-D") }

/** One selectable value of a [GameOption]. */
data class Choice(val value: String, val label: String, val description: String? = null)

/**
 * A setting the hub shows before a match (time control, difficulty, map ...).
 * The host's choice is sent to every player, so all devices start identically.
 */
data class GameOption(
    val id: String,
    val label: String,
    val choices: List<Choice>,
    val default: String = choices.first().value,
    /** Modes in which this option is shown. */
    val modes: Set<PlayMode> = PlayMode.entries.toSet(),
)

/**
 * Everything the hub needs to list, configure and start a game. Register one
 * with [com.oea.engine.OeaEngine.registry]; the hub, multiplayer lobbies and
 * Dynamic Island routing work automatically from it.
 */
data class GameDescriptor(
    /** Stable id, also used for save data and to check both players run the same game. */
    val id: String,
    val title: String,
    val tagline: String,
    val description: String,
    /** ARGB accent used by the hub and the island. */
    val accent: Int,
    /** Short glyph shown in the island and hub (emoji or symbol). */
    val glyph: String,
    val orientation: GameOrientation,
    val dimension: GameDimension,
    val minPlayers: Int = 1,
    val maxPlayers: Int = 1,
    val modes: Set<PlayMode> = setOf(PlayMode.SOLO),
    val options: List<GameOption> = emptyList(),
    /** Label of the same-device mode, e.g. "Pass & play" or "Split-screen duel". */
    val sameDeviceLabel: String = "Pass & play",
    /** Players supported on one device in [PlayMode.SAME_DEVICE]. */
    val sameDevicePlayers: Int = 2,
    /** Island style this game's live activity prefers when the user shows both styles. */
    val preferredIsland: IslandStyle = IslandStyle.CAMERA,
    /** Bumped when the network protocol changes; players must match. */
    val version: Int = 1,
    val factory: () -> OeaGame,
) {
    val isMultiplayer get() = maxPlayers > 1
    fun supports(mode: PlayMode) = mode in modes
}

/** A player sharing this device (same-device modes). */
data class LocalPlayer(val name: String, val color: Int)

/** Parameters of one match. */
class GameLaunch(
    val mode: PlayMode,
    val options: Map<String, String> = emptyMap(),
    /** Shared random seed: identical on every device of a multiplayer match. */
    val seed: Long = System.nanoTime(),
    val localPlayers: List<LocalPlayer> = emptyList(),
    /** Connected session for [PlayMode.LAN] and [PlayMode.ONLINE]; null otherwise. */
    val session: NetSession? = null,
) {
    fun option(id: String, default: String): String = options[id] ?: default
    fun intOption(id: String, default: Int): Int = options[id]?.toIntOrNull() ?: default
    val isNetworked get() = session != null
}

/** Result reported back to the hub when a game exits. */
data class GameResult(val summary: String, val score: Long = 0, val won: Boolean? = null)

/** A native menu entry (pause menus and similar), shown by the host activity. */
data class MenuItem(val id: String, val label: String, val description: String? = null, val destructive: Boolean = false)

/**
 * Services the host activity provides. Every callback is delivered on the
 * game thread, so games never need locks.
 */
interface HostUi {
    fun toast(message: String)
    fun share(text: String, title: String = "Share")
    fun menu(title: String, subtitle: String?, items: List<MenuItem>, onPick: (String?) -> Unit)
    fun confirm(title: String, message: String, positive: String, negative: String = "Cancel", onResult: (Boolean) -> Unit)
    fun prompt(title: String, hint: String, initial: String, onResult: (String?) -> Unit)
    fun exit(result: GameResult? = null)
}

/** What a running game can reach. Provided by [GameHost]. */
interface GameContext {
    /** Application context (resources, files). Never hold an Activity. */
    val android: Context
    val descriptor: GameDescriptor
    val launch: GameLaunch
    val graphics: Graphics
    val input: Input
    val audio: Audio
    val haptics: Haptics
    val island: GameIsland
    val storage: GameStorage
    val jobs: Jobs
    val perf: PerformanceManager
    val settings: EngineSettings
    val time: GameTime
    val ui: HostUi
    /** Network session for LAN/online matches, else null. */
    val net: NetSession?
    /** Queues [action] to run on the game thread before the next update. */
    fun runOnGameThread(action: () -> Unit)
}

/**
 * Base class of every OEA game. All callbacks run on the game thread (the GL
 * thread), so game state needs no synchronisation.
 *
 * Frame order: input and network messages are collected → [onFixedUpdate] at
 * [fixedStepHz] (deterministic simulation) → [onUpdate] once per frame
 * (animation, camera) → [onRender].
 */
abstract class OeaGame {
    private var context: GameContext? = null

    /** Available from [onCreate] on. */
    val ctx: GameContext get() = context ?: throw IllegalStateException("Game is not attached to a host yet")

    internal fun attach(host: GameContext) { context = host }

    /** Simulation rate for [onFixedUpdate]. */
    open val fixedStepHz: Int get() = 60

    /** Keep simulating (not rendering) while the app is in the background, e.g. while hosting a match. */
    open fun keepsRunningInBackground(): Boolean = false

    /** Called once, with a live GPU context, before the first frame. */
    open fun onCreate() {}
    open fun onResize(width: Int, height: Int) {}
    open fun onFixedUpdate(dt: Float) {}
    open fun onUpdate(dt: Float) {}
    abstract fun onRender(frame: Frame)
    open fun onPause() {}
    open fun onResume() {}
    /** Return true when the game handled Back (closed a menu, paused ...). */
    open fun onBack(): Boolean = false
    /** The GPU context was recreated; engine resources restore themselves. */
    open fun onGpuContextRestored() {}
    /** A Dynamic Island action button was tapped (see [com.oea.engine.island.LiveActivity.actions]). */
    open fun onIslandAction(actionId: String) {}
    open fun onDestroy() {}
}
