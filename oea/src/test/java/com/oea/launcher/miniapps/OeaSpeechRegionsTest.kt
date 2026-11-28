package com.oea.launcher.miniapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Caption timing, before a phone is involved.
 *
 * Every one of these would otherwise be a bug somebody notices forty minutes
 * into a film: a cue that starts on silence, a cough that becomes a line, a
 * sentence cut in half, a line of dialogue dropped for being last.
 */
class OeaSpeechRegionsTest {

    private val rate = 16_000

    private fun silence(milliseconds: Int): ShortArray = ShortArray(rate * milliseconds / 1000)

    private fun voice(milliseconds: Int, amplitude: Int = 6000): ShortArray {
        val count = rate * milliseconds / 1000
        // A buzz at 180 Hz with a wobble, close enough in shape to a voice that
        // the energy of a frame swings the way speech does.
        return ShortArray(count) { index ->
            val t = index.toDouble() / rate
            val envelope = 0.6 + 0.4 * sin(2 * PI * 3.1 * t)
            (sin(2 * PI * 180 * t) * amplitude * envelope).roundToInt().toShort()
        }
    }

    private fun clip(vararg parts: ShortArray): ShortArray {
        val total = parts.sumOf { it.size }
        val out = ShortArray(total)
        var at = 0
        parts.forEach { part ->
            System.arraycopy(part, 0, out, at, part.size)
            at += part.size
        }
        return out
    }

    private val settings = OeaSpeechRegions.Settings()

    @Test fun twoSentencesBecomeTwoRegionsOnTheirOwnBounds() {
        val audio = clip(silence(1000), voice(1200), silence(900), voice(800), silence(1000))
        val regions = OeaSpeechRegions.find(audio, rate, settings)
        assertEquals(2, regions.size)
        assertEquals(1000L, regions[0].startMs, 200L)
        assertEquals(2200L, regions[0].endMs, 200L)
        assertEquals(3100L, regions[1].startMs, 200L)
        assertEquals(3900L, regions[1].endMs, 200L)
    }

    @Test fun aCoughIsNotALineOfDialogue() {
        val audio = clip(silence(800), voice(120), silence(900))
        assertTrue(OeaSpeechRegions.find(audio, rate, settings).isEmpty())
    }

    @Test fun aBreathInsideASentenceDoesNotSplitIt() {
        // 300 ms of quiet in the middle of one line: two cues here would read
        // like a stutter on screen.
        val audio = clip(silence(600), voice(900), silence(300), voice(900), silence(600))
        assertEquals(1, OeaSpeechRegions.find(audio, rate, settings).size)
    }

    @Test fun aMonologueIsCutIntoLinesNobodyHasToReadAtOnce() {
        val audio = clip(silence(400), voice(20_000), silence(400))
        val regions = OeaSpeechRegions.find(audio, rate, settings)
        assertTrue("got ${regions.size}", regions.size >= 3)
        regions.forEach { assertTrue("${it.lengthMs} ms", it.lengthMs <= settings.maximumMs + 400) }
        // No gaps invented, no audio skipped: the pieces cover the speech.
        regions.zipWithNext().forEach { (first, second) -> assertTrue(second.startMs >= first.endMs) }
    }

    @Test fun aLoudFilmIsStillCutWhereTheVoicesAre() {
        // A music bed that is loud, and dialogue louder. The floor of this clip
        // is not a quiet room, so the threshold has to come from the clip itself.
        val bed = ShortArray(rate * 3) { index ->
            (sin(2 * PI * 90 * index / rate) * 1500).roundToInt().toShort()
        }
        val audio = clip(bed, voice(1500, 9000), bed)
        val regions = OeaSpeechRegions.find(audio, rate, settings)
        assertTrue("found ${regions.size}", regions.isNotEmpty())
        assertTrue(regions.any { it.overlaps(3000, 4500) })
    }

    @Test fun nothingIsFoundInSilence() {
        assertTrue(OeaSpeechRegions.find(silence(5000), rate, settings).isEmpty())
    }

    @Test fun streamingTheSameAudioFindsTheSameRegions() {
        val audio = clip(silence(700), voice(900), silence(800), voice(1100), silence(600))
        val once = OeaSpeechRegions.find(audio, rate, settings)

        val detector = OeaSpeechRegions.Detector(rate, settings)
        var at = 0
        // A decoder hands over odd sizes; the answer must not depend on them.
        while (at < audio.size) {
            val take = minOf(1501, audio.size - at)
            detector.push(audio.copyOfRange(at, at + take))
            at += take
        }
        assertEquals(once, detector.finish())
    }

    @Test fun theLastLineOfAFilmIsNotDroppedForBeingLast() {
        // A partial final frame — the tail of a sentence, then the file ends.
        val audio = clip(silence(500), voice(1000), silence(600), voice(700))
        val regions = OeaSpeechRegions.find(audio, rate, settings)
        assertEquals(2, regions.size)
        assertTrue("ended at ${regions.last().endMs}", regions.last().endMs >= 2700L)
    }

    @Test fun theDetectorReportsHowMuchAudioItHeard() {
        val detector = OeaSpeechRegions.Detector(rate, settings)
        detector.push(silence(1000))
        assertEquals(1000L, detector.durationMs, 30L)
        detector.push(silence(500))
        assertEquals(1500L, detector.durationMs, 30L)
    }
}
