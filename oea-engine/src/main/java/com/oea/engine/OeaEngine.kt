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

    /**
     * Haptic strength, 0.25 to 1. A phone in a case on a table needs the top of
     * the range; a phone held in a quiet room does not. Multiplied into every
     * vibration amplitude, never into the duration — a weaker tap is still a tap.
     */
    var hapticStrength: Float
        get() = prefs.getFloat("haptic_strength", 1f).coerceIn(0.25f, 1f)
        set(v) = prefs.edit().putFloat("haptic_strength", v.coerceIn(0.25f, 1f)).apply()

    /**
     * Master mute. Every channel below is *also* gated on this, so one switch
     * silences the phone without losing the mix the player set.
     */
    var sound: Boolean
        get() = prefs.getBoolean("sound", true)
        set(v) = prefs.edit().putBoolean("sound", v).apply()

    /** Effects channel: weapons, steps, impacts, UI clicks. */
    var sfx: Boolean
        get() = prefs.getBoolean("sfx", true)
        set(v) = prefs.edit().putBoolean("sfx", v).apply()

    /** Music channel: soundtracks and menu themes. */
    var music: Boolean
        get() = prefs.getBoolean("music", true)
        set(v) = prefs.edit().putBoolean("music", v).apply()

    /** Announcer channel: round calls, kill callouts, match start. */
    var voice: Boolean
        get() = prefs.getBoolean("voice", true)
        set(v) = prefs.edit().putBoolean("voice", v).apply()

    /** Master volume, 0..1. Every channel is a fraction of this. */
    var volume: Float
        get() = prefs.getFloat("volume", 0.9f).coerceIn(0f, 1f)
        set(v) = prefs.edit().putFloat("volume", v.coerceIn(0f, 1f)).apply()

    /** Effects level, 0..1. Independent of [volume] so a loud match can be tamed. */
    var sfxVolume: Float
        get() = prefs.getFloat("sfx_volume", 1f).coerceIn(0f, 1f)
        set(v) = prefs.edit().putFloat("sfx_volume", v.coerceIn(0f, 1f)).apply()

    /** Music level, 0..1. */
    var musicVolume: Float
        get() = prefs.getFloat("music_volume", 0.45f).coerceIn(0f, 1f)
        set(v) = prefs.edit().putFloat("music_volume", v.coerceIn(0f, 1f)).apply()

    /** Announcer level, 0..1. */
    var voiceVolume: Float
        get() = prefs.getFloat("voice_volume", 0.85f).coerceIn(0f, 1f)
        set(v) = prefs.edit().putFloat("voice_volume", v.coerceIn(0f, 1f)).apply()

    /** Duck music while the announcer is talking (or a kill callout plays). */
    var duckMusicForVoice: Boolean
        get() = prefs.getBoolean("duck_music", true)
        set(v) = prefs.edit().putBoolean("duck_music", v).apply()

    /**
     * Menu and HUD scale, 0.85 to 1.5. Type, padding and touch targets grow
     * together on purpose: a layout that enlarges only its text ends up with the
     * buttons moved out from under the thumb that was aiming at them.
     */
    var uiScale: Float
        get() = prefs.getFloat("ui_scale", 1f).coerceIn(0.85f, 1.5f)
        set(v) = prefs.edit().putFloat("ui_scale", v.coerceIn(0.85f, 1.5f)).apply()

    /** Pure-black grounds, white type and hard strokes for low-vision reading. */
    var highContrastMenus: Boolean
        get() = prefs.getBoolean("hc_menus", false)
        set(v) = prefs.edit().putBoolean("hc_menus", v).apply()

    /** No cross-fades, no slides — the screen just changes. */
    var reducedMotion: Boolean
        get() = prefs.getBoolean("reduced_motion", false)
        set(v) = prefs.edit().putBoolean("reduced_motion", v).apply()

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

    /**
     * How much the reported look drag is eased before it turns the camera:
     * 0 = raw finger tracking, 0.7 = silky. Games that read `LookPad` deltas
     * apply it; the default keeps aiming responsive while removing jitter.
     */
    var lookSmoothing: Float
        get() = prefs.getFloat("look_smoothing", 0.35f).coerceIn(0f, 0.7f)
        set(v) = prefs.edit().putFloat("look_smoothing", v.coerceIn(0f, 0.7f)).apply()

    /** Touch aiming sensitivity multiplier (Arena and other shooters). */
    var lookSensitivity: Float
        get() = prefs.getFloat("look_sensitivity", 1f).coerceIn(0.2f, 3f)
        set(v) = prefs.edit().putFloat("look_sensitivity", v.coerceIn(0.2f, 3f)).apply()

    /**
     * Touch controls drawn for a left-handed grip: the movement stick moves to
     * the right thumb and the button cluster mirrors to the left. Layouts are
     * stored per handedness, so switching back restores the old arrangement
     * instead of overwriting it.
     */
    var leftHanded: Boolean
        get() = prefs.getBoolean("left_handed", false)
        set(v) = prefs.edit().putBoolean("left_handed", v).apply()

    /** Opacity of the on-screen buttons and stick, 0.35..1. */
    var buttonOpacity: Float
        get() = prefs.getFloat("button_opacity", 0.82f).coerceIn(0.35f, 1f)
        set(v) = prefs.edit().putFloat("button_opacity", v.coerceIn(0.35f, 1f)).apply()

    /** Opacity of the game HUD (health bar, minimap, kill feed), 0.4..1. */
    var hudOpacity: Float
        get() = prefs.getFloat("hud_opacity", 1f).coerceIn(0.4f, 1f)
        set(v) = prefs.edit().putFloat("hud_opacity", v.coerceIn(0.4f, 1f)).apply()

    /**
     * How strongly the scene is film-graded, 0..1.5. 1 is the look the
     * environment presets intend; 0 is the raw render, for players who want the
     * flat image and a couple of extra frames; above 1 is a stylised, punchier
     * picture for screenshots.
     */
    var colourGrade: Float
        get() = prefs.getFloat("colour_grade", 1f).coerceIn(0f, 1.5f)
        set(v) = prefs.edit().putFloat("colour_grade", v.coerceIn(0f, 1.5f)).apply()

    /** Full-screen vignette on top of the grade. Some players hate it; make it a choice. */
    var vignette: Boolean
        get() = prefs.getBoolean("vignette", true)
        set(v) = prefs.edit().putBoolean("vignette", v).apply()

    /**
     * Camera perspective for shooters: "first" or "third".
     *
     * First person is the tight, competitive view — the crosshair is the world,
     * and the body is implied. Third person pulls the camera over the shoulder
     * so you can see the soldier, the cover they are hugging and what is behind
     * them. Both aim from the same eye point, so switching never changes where a
     * shot lands, only where it is seen from.
     */
    var cameraPerspective: String
        get() = prefs.getString("camera_perspective", DEFAULT_PERSPECTIVE).let { v ->
            if (v == "first" || v == "third") v else DEFAULT_PERSPECTIVE
        }
        set(v) = prefs.edit().putString("camera_perspective", if (v == "first") "first" else "third").apply()

    /** How far over the shoulder the third-person camera sits, 1.4..3.2 m. */
    var thirdPersonDistance: Float
        get() = prefs.getFloat("third_distance", 2.6f).coerceIn(1.4f, 3.2f)
        set(v) = prefs.edit().putFloat("third_distance", v.coerceIn(1.4f, 3.2f)).apply()

    /** "cross", "dot", "t", "circle" — the shape of the aim reticle. */
    var crosshairStyle: String
        get() = prefs.getString("crosshair_style", "cross").takeIf { it in CROSSHAIR_STYLES } ?: "cross"
        set(v) = prefs.edit().putString("crosshair_style", v).apply()

    /** Reticle tint as 0xAARRGGBB; a bright green reads best on most terrain. */
    var crosshairColor: Int
        get() = prefs.getInt("crosshair_color", 0xFFE9F7EF.toInt())
        set(v) = prefs.edit().putInt("crosshair_color", v).apply()

    /** Floating damage numbers over bodies. */
    var damageNumbers: Boolean
        get() = prefs.getBoolean("damage_numbers", true)
        set(v) = prefs.edit().putBoolean("damage_numbers", v).apply()

    /** Camera shake from explosions and hard landings. Off for sensitivity. */
    var cameraShake: Boolean
        get() = prefs.getBoolean("camera_shake", true)
        set(v) = prefs.edit().putBoolean("camera_shake", v).apply()

    /** Stick past its outer ring runs; no need to hold the sprint button. */
    var autoSprint: Boolean
        get() = prefs.getBoolean("auto_sprint", false)
        set(v) = prefs.edit().putBoolean("auto_sprint", v).apply()

    /** Keep the trigger held to fire, or tap once for a burst. */
    var tapFire: Boolean
        get() = prefs.getBoolean("tap_fire", false)
        set(v) = prefs.edit().putBoolean("tap_fire", v).apply()

    /** Soft aim assist strength, 0 = off. Bends the shot toward a body on the line. */
    var aimAssist: Float
        get() = prefs.getFloat("aim_assist", 0.35f).coerceIn(0f, 1f)
        set(v) = prefs.edit().putFloat("aim_assist", v.coerceIn(0f, 1f)).apply()

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
        /**
         * Third person is the default. A phone held in two thumbs sees more of
         * the world over the shoulder, and a player who has never set the
         * option should meet the view that shows them their own soldier.
         */
        const val DEFAULT_PERSPECTIVE = "third"
        val CAMERA_PERSPECTIVES = listOf("first", "third")
        val QUALITY_VALUES = listOf("auto", "low", "medium", "high", "ultra")
        val CROSSHAIR_STYLES = listOf("cross", "dot", "circle", "t")
        const val MAX_NAME = 20
        val PLAYER_COLORS = intArrayOf(
            0xFF4FC3F7.toInt(), 0xFFFF7043.toInt(), 0xFF66BB6A.toInt(), 0xFFFFCA28.toInt(),
            0xFFAB47BC.toInt(), 0xFFEC407A.toInt(), 0xFF26A69A.toInt(), 0xFFFFFFFF.toInt(),
        )
    }
}
