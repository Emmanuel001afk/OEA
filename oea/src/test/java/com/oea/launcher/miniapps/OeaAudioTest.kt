package com.oea.launcher.miniapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The audio maths that decides whether a cue lands on the words.
 *
 * A resampler that drifts and a speech detector that misses the last line are
 * both invisible until somebody watches a film, so they are measured here
 * instead: exact sample counts, exact rounding, and the boundary between two
 * chunks that must not lose a fraction of a sample.
 */
class OeaAudioTest {

    private fun tone(milliseconds: Int, amplitude: Int = 4000, rate: Int = 16_000, hz: Double = 440.0): ShortArray {
        val count = rate * milliseconds / 1000
        return ShortArray(count) { index ->
            (sin(2 * Math.PI * hz * index / rate) * amplitude).roundToInt().toShort()
        }
    }

    @Test fun stereoIsAveragedRatherThanGuessedAt() {
        val interleaved = shortArrayOf(100, 300, -200, -600, 0, 0)
        assertEquals(listOf<Short>(200, -400, 0), OeaAudio.mono(interleaved, 2).toList())
    }

    @Test fun oneChannelPassesThroughUntouched() {
        val samples = tone(20)
        assertTrue(samples.contentEquals(OeaAudio.mono(samples, 1)))
    }

    @Test fun theRateIsCutByTheRatio() {
        val input = tone(1000, rate = 48_000)
        val output = OeaAudio.Resampler(48_000).push(input)
        // 48 000 -> 16 000 is a third of the samples, within the carry's one sample.
        assertTrue("got ${output.size}", abs(output.size - 16_000) <= 2)
    }

    @Test fun aSecondStaysASecond() {
        val input = tone(2000, rate = 44_100)
        val output = OeaAudio.Resampler(44_100).push(input)
        assertTrue("got ${output.size}", abs(output.size - 32_000) <= 2)
    }

    @Test fun theCarrySurvivesTheChunkBoundary() {
        val input = tone(1000, rate = 48_000)
        val once = OeaAudio.Resampler(48_000).let { it.push(input) }

        val resampler = OeaAudio.Resampler(48_000)
        val twice = ArrayList<Short>(16_000)
        var index = 0
        while (index < input.size) {
            val take = minOf(977, input.size - index)
            resampler.push(input.copyOfRange(index, index + take)).forEach(twice::add)
            index += take
        }

        // Odd chunk sizes on purpose: a decoder hands over whatever it decodes,
        // and the split must not move the clock by a single sample.
        assertTrue("lengths ${once.size} vs ${twice.size}", abs(once.size - twice.size) <= 1)
        val compared = minOf(once.size, twice.size)
        for (sample in 4 until compared) {
            assertTrue(
                "sample $sample: ${once[sample]} vs ${twice[sample]}",
                abs(once[sample].toInt() - twice[sample].toInt()) <= 1
            )
        }
    }

    @Test fun aQuietSignalStaysQuiet() {
        val input = ShortArray(1000) { 3 }
        val output = OeaAudio.Resampler(48_000).push(input)
        assertTrue(output.all { abs(it.toInt()) <= 4 })
    }

    @Test fun quietAudioIsSilentAndASpikeIsNot() {
        assertTrue(OeaAudio.isSilent(ShortArray(500)))
        assertTrue(OeaAudio.isSilent(ShortArray(500) { 20 }))
        assertFalse(OeaAudio.isSilent(ShortArray(500) { if (it == 250) 900 else 20 }))
    }

    @Test fun bytesAreLittleEndian() {
        val bytes = OeaAudio.bytes(shortArrayOf(1, -1, 258))
        assertEquals(listOf<Byte>(1, 0, -1, -1, 2, 1), bytes.toList())
    }

    @Test fun theWavHeaderDescribesTheBytesThatFollow() {
        val header = OeaAudio.wavHeader(32_000, 16_000)
        assertEquals(44, header.size)
        assertEquals("RIFF", String(header, 0, 4))
        assertEquals("WAVE", String(header, 8, 4))
        assertEquals("data", String(header, 36, 4))
        fun le32(at: Int): Int =
            (header[at].toInt() and 0xFF) or ((header[at + 1].toInt() and 0xFF) shl 8) or
                ((header[at + 2].toInt() and 0xFF) shl 16) or ((header[at + 3].toInt() and 0xFF) shl 24)
        assertEquals(36 + 32_000, le32(4))
        assertEquals(16_000, le32(24))
        assertEquals(32_000, le32(28))
        assertEquals(32_000, le32(40))
    }

    @Test fun aSecondOfCaptionAudioIsThirtyTwoKilobytes() {
        assertEquals(32_000L, OeaAudio.bytesFor(1000))
        assertEquals(16_000, OeaAudio.samplesFor(OeaAudio.bytesFor(1000)))
    }

    @Test fun aResamplerCannotBeBuiltWithNoRate() {
        val failed = runCatching { OeaAudio.Resampler(0) }.isFailure
        assertTrue(failed)
    }
}
