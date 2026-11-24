package com.oea.games.arena

import com.oea.engine.graphics.Environment
import com.oea.engine.math.Colors
import com.oea.engine.math.MathX
import kotlin.math.cos
import kotlin.math.sin

/**
 * Drives the world's sky, light and atmosphere. Send Off already had a fixed
 * day/night switch for one mode; this makes time a real world system shared by
 * every mode, with named seasons and location palettes feeding the same state.
 *
 * The cycle is visual only — it never touches simulation state, so replays and
 * network matches stay deterministic. `time` is a fraction of a full day:
 * 0 = dawn, 0.25 = noon, 0.7 = dusk, 0.8–1 = night.
 */
internal class ArenaWorld(
    private val env: Environment,
    private val day: Environment,
    private val night: Environment,
) {
    var season: String = "summer"
        private set
    var location: String = "island"
        private set
    var timeSetting: String = "auto"
        private set
    var rules: String = "br"
        private set

    /** Fraction of the day cycle, 0..1. */
    var time: Float = 0.25f
        private set

    /** 0 = full daylight, 1 = full night; smoothly interpolated at dawn/dusk. */
    var darkness: Float = 0f
        private set

    /** Seconds for a complete cycle when time is set to Cycle. */
    var cycleSeconds: Float = 210f

    /** Season-tinted copies; the base levels stay pristine across matches. */
    private val workDay = Environment()
    private val workNight = Environment()

    val isNight get() = darkness > 0.55f
    val lanternsOn get() = darkness > 0.32f

    /** Short label for the HUD clock. */
    val phase: String get() = when {
        time < 0.06f -> "Dawn"
        time < 0.16f -> "Morning"
        time < 0.58f -> "Midday"
        time < 0.68f -> "Afternoon"
        time < 0.79f -> "Dusk"
        else -> "Night"
    }

    /** Configures palettes and the starting time. Call once after the level env is set. */
    fun configure(location: String, season: String, timeSetting: String, rules: String) {
        // Tint copies only: a game object can host several matches and season
        // multipliers must never compound from the previous one.
        workDay.copyFrom(day)
        workNight.copyFrom(night)
        this.location = location
        this.season = season
        this.timeSetting = timeSetting
        this.rules = rules
        this.time = when (timeSetting) {
            "day" -> 0.30f
            "dusk" -> 0.72f
            "night" -> 0.88f
            "cycle" -> 0.08f
            else -> when (rules) {
                "last_day" -> 0.08f // the classic mode starts at dawn and turns to night
                "lights_out", "haunted" -> 0.88f
                "tdm", "gun", "cs", "wolf", "warfare", "warfare_koth", "warfare_ad" -> 0.30f
                else -> 0.30f
            }
        }
        cycleSeconds = if (rules == "last_day") 150f else 210f
        applySeason()
        update(0f)
    }

    /** The mode's default can be overridden by the player; this says if it moves. */
    private val cycles: Boolean get() = timeSetting == "cycle" || (timeSetting == "auto" && rules == "last_day")

    private fun applySeason() {
        when (season) {
            "spring" -> {
                workDay.skyHorizon = Colors.lerp(workDay.skyHorizon, 0xFFD8EFD0.toInt(), 0.28f)
                workDay.fogColor = Colors.lerp(workDay.fogColor, 0xFFD8E7D2.toInt(), 0.3f)
                workDay.sunIntensity *= 1.04f
            }
            "autumn" -> {
                workDay.skyHorizon = Colors.lerp(workDay.skyHorizon, 0xFFFFC493.toInt(), 0.42f)
                workDay.fogColor = Colors.lerp(workDay.fogColor, 0xFFE0BFA0.toInt(), 0.45f)
                workDay.groundAmbient = Colors.lerp(workDay.groundAmbient, 0xFF7A5434.toInt(), 0.35f)
                workDay.sunIntensity *= 0.94f
            }
            "winter" -> {
                workDay.skyTop = Colors.lerp(workDay.skyTop, 0xFF8FB4D8.toInt(), 0.3f)
                workDay.skyHorizon = Colors.lerp(workDay.skyHorizon, 0xFFE6F1F8.toInt(), 0.5f)
                workDay.fogColor = Colors.lerp(workDay.fogColor, 0xFFDCE8F0.toInt(), 0.45f)
                workDay.fogDensity *= 1.25f
                workDay.sunColor = Colors.lerp(workDay.sunColor, 0xFFE8F2FF.toInt(), 0.4f)
            }
        }
        // Night keeps its identity, but seasons still tint its moonlight.
        when (season) {
            "autumn" -> workNight.fogColor = Colors.lerp(workNight.fogColor, 0xFF2A211C.toInt(), 0.3f)
            "winter" -> {
                workNight.skyHorizon = Colors.lerp(workNight.skyHorizon, 0xFF2A3A56.toInt(), 0.3f)
                workNight.fogColor = Colors.lerp(workNight.fogColor, 0xFF27384C.toInt(), 0.35f)
            }
        }
    }

    /** Advance the clock and rebake the environment for this frame. */
    fun update(dt: Float) {
        if (cycles && dt > 0f) time = (time + dt / cycleSeconds) % 1f
        darkness = when {
            time < 0.06f -> 1f - MathX.smoothstep(0f, 0.06f, time)
            time < 0.62f -> 0f
            time < 0.78f -> MathX.smoothstep(0.62f, 0.78f, time)
            else -> 1f
        }
        val d = darkness
        env.sunIntensity = MathX.lerp(workDay.sunIntensity, workNight.sunIntensity, d)
        env.ambientIntensity = MathX.lerp(workDay.ambientIntensity, workNight.ambientIntensity, d)
        env.fogDensity = MathX.lerp(workDay.fogDensity, workNight.fogDensity, d)
        env.exposure = MathX.lerp(1f, 0.86f, d)
        env.sunSize = MathX.lerp(1f, 0.55f, d)

        // Warm the low sun at dawn/dusk instead of snapping to a night palette.
        val lowSun = (MathX.smoothstep(0.02f, 0.10f, time) * (1f - MathX.smoothstep(0.10f, 0.22f, time))) +
            (MathX.smoothstep(0.55f, 0.70f, time) * (1f - MathX.smoothstep(0.76f, 0.84f, time)))
        val warmth = (lowSun * (1f - d * 0.5f)).coerceIn(0f, 1f)
        env.sunColor = Colors.lerp(Colors.lerp(workDay.sunColor, workNight.sunColor, d), 0xFFFF9E62.toInt(), warmth * 0.65f)
        env.skyAmbient = Colors.lerp(Colors.lerp(workDay.skyAmbient, workNight.skyAmbient, d), 0xFFB08374.toInt(), warmth * 0.35f)
        env.groundAmbient = Colors.lerp(workDay.groundAmbient, workNight.groundAmbient, d)
        env.skyTop = Colors.lerp(Colors.lerp(workDay.skyTop, workNight.skyTop, d), 0xFF695EA4.toInt(), warmth * 0.35f)
        env.skyHorizon = Colors.lerp(Colors.lerp(workDay.skyHorizon, workNight.skyHorizon, d), 0xFFFFB07E.toInt(), warmth * 0.65f)
        env.skyGround = Colors.lerp(workDay.skyGround, workNight.skyGround, d)
        env.fogColor = Colors.lerp(Colors.lerp(workDay.fogColor, workNight.fogColor, d), 0xFFE2A47E.toInt(), warmth * 0.5f)

        // Sun sweeps an arc; after it sets, the moon takes over as the key light.
        val a = (time - 0.25f) * MathX.TAU
        val sx = sin(a) * 0.9f
        val sy = cos(a)
        val sz = -0.35f + sin(a * 0.5f) * 0.12f
        if (sy < -0.04f) env.sunDirection.set(-sx, -sy, -sz).nor()
        else env.sunDirection.set(sx, kotlin.math.max(0.06f, sy), sz).nor()
    }
}
