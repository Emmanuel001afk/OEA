package com.oea.engine

import android.content.Context
import android.content.SharedPreferences
import com.oea.engine.core.GameDescriptor
import com.oea.engine.island.IslandHost
import com.oea.engine.island.IslandPlacement
import com.oea.engine.island.NoIslandHost
import com.oea.engine.net.rtc.IceServerConfig
import com.oea.engine.perf.DeviceProfile
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

/**
 * Process-wide entry point of the OEA Game Engine.
 *
 * ```
 * OeaEngine.init(context)
 * OeaEngine.registry.register(GameDescriptor(id = "my-game", ..., factory = { MyGame() }))
 * ```
 * The host app (the OEA launcher) may install an [islandHost] so games can
 * publish Dynamic Island live activities.
 */
object OeaEngine {
    const val VERSION = "1.0.0"
    /** Network protocol version; peers with a different value cannot join each other. */
    const val PROTOCOL = 1

    @Volatile private var appContext: Context? = null
    private var settingsInstance: EngineSettings? = null
    private var profileInstance: DeviceProfile? = null

    /** Registered games, in registration order. */
    val registry = GameRegistry()

    /** Bridge to the host app's Dynamic Island (card and/or camera style). */
    @Volatile var islandHost: IslandHost = NoIslandHost

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    val context: Context get() = appContext ?: throw IllegalStateException("OeaEngine.init(context) has not been called")

    val settings: EngineSettings
        get() = settingsInstance ?: synchronized(this) {
            settingsInstance ?: EngineSettings(context).also { settingsInstance = it }
        }

    /**
     * Why a game surface cannot be created on this phone, or null when it can.
     *
     * A launcher calls this *before* opening a game so the answer becomes a
     * readable sentence on the failure screen, instead of the process dying inside
     * GLSurfaceView's own thread where nothing can catch it.
     */
    fun surfaceProblem(): String? = com.oea.engine.graphics.SurfaceProbe.reason()

    /** Hardware capabilities and the recommended quality tier, measured once. */
    val device: DeviceProfile
        get() = profileInstance ?: synchronized(this) {
            profileInstance ?: DeviceProfile.measure(context).also { profileInstance = it }
        }

    internal fun updateGpuInfo(renderer: String, vendor: String, version: String, maxTexture: Int, maxSamples: Int, extensions: String) {
        device.updateGpu(renderer, vendor, version, maxTexture, maxSamples, extensions)
    }
}

/** All games known to the engine. */
class GameRegistry internal constructor() {
    private val games = CopyOnWriteArrayList<GameDescriptor>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun register(descriptor: GameDescriptor) {
        games.removeAll { it.id == descriptor.id }
        games += descriptor
        listeners.forEach { it() }
    }

    fun all(): List<GameDescriptor> = games.toList()
    fun find(id: String): GameDescriptor? = games.firstOrNull { it.id == id }
    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }
}

/** Engine-wide player preferences, shared by every game. */
class EngineSettings internal constructor(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("oea_engine", Context.MODE_PRIVATE)

    fun registerListener(l: SharedPreferences.OnSharedPreferenceChangeListener) = prefs.registerOnSharedPreferenceChangeListener(l)
    fun unregisterListener(l: SharedPreferences.OnSharedPreferenceChangeListener) = prefs.unregisterOnSharedPreferenceChangeListener(l)

    /** "auto", "low", "medium", "high" or "ultra". */
    var quality: String
        get() = prefs.getString("quality", "auto")?.takeIf { it in QUALITY_VALUES } ?: "auto"
        set(v) = prefs.edit().putString("quality", v).apply()

    /** Target frame rate; 0 = the display's highest refresh rate. */
    var fpsTarget: Int
        get() = prefs.getInt("fps_target", 0).coerceIn(0, 240)
        set(v) = prefs.edit().putInt("fps_target", v).apply()

    /** Scale rendering resolution up or down to hold the frame rate. */
    var adaptiveResolution: Boolean
        get() = prefs.getBoolean("adaptive_resolution", true)
        set(v) = prefs.edit().putBoolean("adaptive_resolution", v).apply()

    var showFps: Boolean
        get() = prefs.getBoolean("show_fps", false)
        set(v) = prefs.edit().putBoolean("show_fps", v).apply()

    /** Android's sustained performance mode: steadier clocks, less heat, lower peaks. */
    var sustainedPerformance: Boolean
        get() = prefs.getBoolean("sustained", false)
        set(v) = prefs.edit().putBoolean("sustained", v).apply()

    var haptics: Boolean
        get() = prefs.getBoolean("haptics", true)
        set(v) = prefs.edit().putBoolean("haptics", v).apply()

    var sound: Boolean
        get() = prefs.getBoolean("sound", true)
        set(v) = prefs.edit().putBoolean("sound", v).apply()

    var music: Boolean
        get() = prefs.getBoolean("music", true)
        set(v) = prefs.edit().putBoolean("music", v).apply()

    var volume: Float
        get() = prefs.getFloat("volume", 0.9f).coerceIn(0f, 1f)
        set(v) = prefs.edit().putFloat("volume", v.coerceIn(0f, 1f)).apply()

    /**
     * Extra multiplier applied only while aiming down sights. Below 1 slows the
     * view down for precision; 1 keeps hip-fire feel.
     */
    var adsSensitivity: Float
        get() = prefs.getFloat("ads_sensitivity", 0.85f).coerceIn(0.3f, 1.5f)
        set(v) = prefs.edit().putFloat("ads_sensitivity", v.coerceIn(0.3f, 1.5f)).apply()

    /** Invert the vertical look axis (flight-style controls). */
    var invertLookY: Boolean
        get() = prefs.getBoolean("invert_look_y", false)
        set(v) = prefs.edit().putBoolean("invert_look_y", v).apply()

    /**
     * Hard cap on how fast the view can turn, in degrees per second. 0 = no
     * limit. Touch drags and gamepads both respect it, so a glitched pointer
     * can never spin the camera faster than a human hand.
     */
    var turnSpeedLimit: Float
        get() = prefs.getFloat("turn_speed_limit", 0f).coerceIn(0f, 360f)
        set(v) = prefs.edit().putFloat("turn_speed_limit", v.coerceIn(0f, 360f)).apply()

    /** Touch aiming sensitivity multiplier (Arena and other shooters). */
    var lookSensitivity: Float
        get() = prefs.getFloat("look_sensitivity", 1f).coerceIn(0.2f, 3f)
        set(v) = prefs.edit().putFloat("look_sensitivity", v.coerceIn(0.2f, 3f)).apply()

    var playerName: String
        get() = prefs.getString("player_name", null)?.takeIf { it.isNotBlank() } ?: defaultName()
        set(v) = prefs.edit().putString("player_name", v.trim().take(MAX_NAME)).apply()

    var playerColor: Int
        get() = prefs.getInt("player_color", PLAYER_COLORS[0])
        set(v) = prefs.edit().putInt("player_color", v).apply()

    /** Stable anonymous id, so a player who drops can reclaim their seat. */
    val playerToken: Long
        get() {
            if (!prefs.contains("player_token")) prefs.edit().putLong("player_token", Random.nextLong()).apply()
            return prefs.getLong("player_token", 1L)
        }

    /**
     * The user's own STUN/TURN servers for online play. Empty by default: OEA
     * never contacts a third-party server on its own.
     */
    var iceServers: List<IceServerConfig>
        get() = IceServerConfig.parseList(prefs.getString("ice_servers", "").orEmpty())
        set(v) = prefs.edit().putString("ice_servers", IceServerConfig.formatList(v)).apply()

    /** Show the Dynamic Island inside games (events, calls, music). */
    var islandInGames: Boolean
        get() = prefs.getBoolean("island_in_games", true)
        set(v) = prefs.edit().putBoolean("island_in_games", v).apply()

    var islandMediaInGames: Boolean
        get() = prefs.getBoolean("island_media_in_games", true)
        set(v) = prefs.edit().putBoolean("island_media_in_games", v).apply()

    /** Where [gameId]'s live activity goes when the launcher shows both island styles. */
    fun islandPlacement(gameId: String): IslandPlacement =
        runCatching { IslandPlacement.valueOf(prefs.getString("island_placement_$gameId", null) ?: "AUTO") }.getOrDefault(IslandPlacement.AUTO)

    fun setIslandPlacement(gameId: String, placement: IslandPlacement) =
        prefs.edit().putString("island_placement_$gameId", placement.name).apply()

    /** Last option values chosen for a game, so the hub remembers them. */
    fun lastOption(gameId: String, optionId: String): String? = prefs.getString("opt_${gameId}_$optionId", null)
    fun setLastOption(gameId: String, optionId: String, value: String) = prefs.edit().putString("opt_${gameId}_$optionId", value).apply()

    private fun defaultName(): String {
        val model = android.os.Build.MODEL?.trim().orEmpty()
        return (if (model.isNotEmpty()) model else "Player").take(MAX_NAME)
    }

    companion object {
        val QUALITY_VALUES = listOf("auto", "low", "medium", "high", "ultra")
        const val MAX_NAME = 20
        val PLAYER_COLORS = intArrayOf(
            0xFF4FC3F7.toInt(), 0xFFFF7043.toInt(), 0xFF66BB6A.toInt(), 0xFFFFCA28.toInt(),
            0xFFAB47BC.toInt(), 0xFFEC407A.toInt(), 0xFF26A69A.toInt(), 0xFFFFFFFF.toInt(),
        )
    }
}
