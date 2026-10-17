package com.oea.launcher.miniapps

import kotlin.math.roundToInt
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.PresetReverb

/**
 * VIP's sound shelf: the phone's own equaliser, its low end, loudness and reverb,
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
    private var loudness: LoudnessEnhancer? = null
    private var reverb: PresetReverb? = null

    val equalizerAvailable: Boolean
    val bassAvailable: Boolean
    val loudnessAvailable: Boolean

    init {
        equalizer = runCatching { Equalizer(0, sessionId) }.getOrNull()?.also { it.enabled = true }
        loudness = runCatching { LoudnessEnhancer(sessionId) }.getOrNull()
        reverb = runCatching { PresetReverb(0, sessionId) }.getOrNull()?.also { it.enabled = true }
        equalizerAvailable = equalizer?.let { runCatching { it.numberOfBands > 0 }.getOrDefault(false) } == true
        // Bass is an equaliser job here, see setBassStrength().
        bassAvailable = equalizerAvailable
        loudnessAvailable = loudness != null
    }

    /** Device presets, e.g. "Flat", "Rock" — empty when the EQ exposes none. */
    fun presets(): List<String> {
        val eq = equalizer ?: return emptyList()
        return runCatching {
            (0 until eq.numberOfPresets.toInt()).map { eq.getPresetName(it.toShort()).orEmpty() }
        }.getOrDefault(emptyList())
    }

    fun currentPreset(): Int = equalizer?.let { runCatching { it.currentPreset.toInt() }.getOrDefault(-1) } ?: -1

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

    /** The last figure the bass slider asked for, in the same 0–1000 scale. */
    private var lastBass = 0

    fun bassStrength(): Int = lastBass

    /**
     * Bass, lifted on the two lowest bands of the device equaliser. Android's own
     * BassBoost effect has had its strength setter reshaped twice and is not a call
     * this build can rely on, so the low end is raised through the band API instead —
     * the one every equaliser implementation has to provide, which means the slider
     * either moves the sound or the section is shown as unavailable.
     */
    fun setBassStrength(value: Int) {
        val eq = equalizer ?: return
        val lift = value.coerceIn(0, 1000)
        lastBass = lift
        runCatching {
            val range = eq.bandLevelRange
            val ceiling = if (range.size > 1) range[1].toInt() else 1500
            val count = eq.numberOfBands.toInt()
            if (count > 0) {
                val milliBel = (lift / 1000f * ceiling / 2f).roundToInt()
                eq.setBandLevel(0.toShort(), milliBel.toShort())
                if (count > 1) eq.setBandLevel(1.toShort(), (milliBel / 2).toShort())
            }
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
        val value: Int = when (index) {
            1 -> PresetReverb.PRESET_SMALLROOM.toInt()
            2 -> PresetReverb.PRESET_MEDIUMROOM.toInt()
            3 -> PresetReverb.PRESET_LARGEROOM.toInt()
            4 -> PresetReverb.PRESET_MEDIUMHALL.toInt()
            5 -> PresetReverb.PRESET_LARGEHALL.toInt()
            6 -> PresetReverb.PRESET_PLATE.toInt()
            else -> 0
        }
        reverbIndex = if (runCatching { effect.preset = value.toShort() }.isSuccess) index else 0
        runCatching { effect.enabled = value != 0 }
    }

    fun release() {
        listOf(equalizer, reverb).forEach { effect -> effect?.runCatching { release() } }
        loudness?.runCatching { release() }
        equalizer = null
        loudness = null
        reverb = null
    }
}
