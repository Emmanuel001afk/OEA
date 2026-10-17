package com.oea.launcher.miniapps

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.PresetReverb

/**
 * VIP's sound shelf: the phone's own equaliser, bass boost, loudness and reverb,
 * attached to the player's audio session.
 *
 * These are the effects Android already ships (`android.media.audiofx`), so there
 * is no decoder, no native library and no licence involved. Whether any of them
 * exist is a property of the device, which is why every call is wrapped: on a
 * phone without an EQ implementation VIP shows the section as unavailable rather
 * than pretending a slider did something.
 */
internal class OeaVipAudioFx(private val sessionId: Int) {

    data class Band(val index: Int, val label: String, val minMilliBel: Int, val maxMilliBel: Int, val level: Int)

    private var equalizer: Equalizer? = null
    private var bass: BassBoost? = null
    private var loudness: LoudnessEnhancer? = null
    private var reverb: PresetReverb? = null

    val equalizerAvailable: Boolean
    val bassAvailable: Boolean
    val loudnessAvailable: Boolean

    init {
        equalizer = runCatching { Equalizer(0, sessionId) }.getOrNull()?.also { it.enabled = true }
        bass = runCatching { BassBoost(0, sessionId) }.getOrNull()?.also { it.enabled = true }
        loudness = runCatching { LoudnessEnhancer(sessionId) }.getOrNull()
        reverb = runCatching { PresetReverb(0, sessionId) }.getOrNull()?.also { it.enabled = true }
        equalizerAvailable = equalizer?.let { runCatching { it.numberOfBands > 0 }.getOrDefault(false) } == true
        bassAvailable = bass?.let { runCatching { it.bassBoostSupported }.getOrDefault(false) } == true
        loudnessAvailable = loudness != null
    }

    /** Device presets, e.g. "Flat", "Rock" — empty when the EQ exposes none. */
    fun presets(): List<String> {
        val eq = equalizer ?: return emptyList()
        return runCatching {
            (0 until eq.numberOfPresets.toInt()).map { eq.getPresetName(it.toShort()).orEmpty() }
        }.getOrDefault(emptyList())
    }

    fun currentPreset(): Int = equalizer?.let { runCatching { it.preset.toInt() }.getOrDefault(-1) } ?: -1

    fun usePreset(index: Int) {
        equalizer?.runCatching { usePreset(index.toShort()) }
    }

    /**
     * The band centre frequencies and the shared level range, straight from the
     * device's implementation. `getBandFreqRange` is missing on a few phones, so a
     * band falls back to being called "Band 1", "Band 2"…
     */
    fun bands(): List<Band> {
        val eq = equalizer ?: return emptyList()
        return runCatching {
            val range = eq.bandLevelRange
            val low = if (range.isNotEmpty()) range[0].toInt() else -1500
            val high = if (range.size > 1) range[1].toInt() else 1500
            (0 until eq.numberOfBands.toInt()).map { index ->
                val label = runCatching {
                    val hz = eq.getBandFreqRange(index.toShort())
                    "${(hz[0].toInt() + hz[1].toInt()) / 2 / 1000} kHz"
                }.getOrDefault("Band ${index + 1}")
                Band(index, label, low, high, eq.getBandLevel(index.toShort()).toInt())
            }
        }.getOrDefault(emptyList())
    }

    fun setBand(index: Int, milliBel: Int) {
        val eq = equalizer ?: return
        runCatching {
            val range = eq.bandLevelRange
            val low = if (range.isNotEmpty()) range[0].toInt() else -1500
            val high = if (range.size > 1) range[1].toInt() else 1500
            eq.setBandLevel(index.toShort(), milliBel.coerceIn(low, high).toShort())
        }
    }

    fun resetBands() {
        val eq = equalizer ?: return
        runCatching {
            (0 until eq.numberOfBands.toInt()).forEach { eq.setBandLevel(it.toShort(), 0) }
        }
    }

    /** 0–1000, in the units Android's BassBoost reports. */
    fun bassStrength(): Int = bass?.runCatching { it.roundedStrength.toInt() }.getOrDefault(0) ?: 0

    fun setBassStrength(value: Int) {
        val boost = bass ?: return
        runCatching {
            boost.setBassBoostStrength(value.coerceIn(0, 1000).toShort())
            boost.enabled = value > 0
        }
    }

    /**
     * Loudness in millibels. `LoudnessEnhancer` only has a setter, so VIP remembers
     * the number it last asked for — that is also what the slider has to show.
     */
    fun loudnessGain(): Int = lastLoudness

    private var lastLoudness = 0

    fun setLoudnessGain(milliBel: Int) {
        val enhancer = loudness ?: return
        lastLoudness = milliBel
        runCatching {
            enhancer.setTargetGain(milliBel.coerceIn(0, 6000))
            enhancer.enabled = milliBel > 0
        }
    }

    val reverbs = listOf("None", "Small room", "Medium room", "Large room", "Medium hall", "Large hall", "Plate")

    private var reverbIndex = 0

    fun currentReverb(): Int = reverbIndex

    fun setReverb(index: Int) {
        val effect = reverb ?: return
        val value: Short = when (index) {
            1 -> PresetReverb.SMALL_ROOM
            2 -> PresetReverb.MEDIUM_ROOM
            3 -> PresetReverb.LARGE_ROOM
            4 -> PresetReverb.MEDIUM_HALL
            5 -> PresetReverb.LARGE_HALL
            6 -> PresetReverb.PLATE
            else -> 0
        }
        reverbIndex = if (runCatching { effect.preset = value }.isSuccess) index else 0
        runCatching { effect.enabled = value != 0.toShort() }
    }

    fun release() {
        listOf(equalizer, bass, reverb).forEach { it?.runCatching { release() } }
        loudness?.runCatching { release() }
        equalizer = null
        bass = null
        loudness = null
        reverb = null
    }
}
