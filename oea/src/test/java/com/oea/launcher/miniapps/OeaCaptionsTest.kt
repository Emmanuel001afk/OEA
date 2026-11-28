package com.oea.launcher.miniapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a written-from-sound track looks like once the recognizer has answered.
 *
 * The timing is somebody else's job (OeaSpeechRegions); this pins the text: the
 * capital that has to arrive, the "[MUSIC]" that has to leave, the line that has
 * to break in two, and the cue that must not be invented for a stretch where
 * nobody spoke.
 */
class OeaCaptionsTest {

    private fun region(start: Long, end: Long) = OeaSpeechRegions.Region(start, end)

    @Test fun aRecognisersSentenceBecomesASentence() {
        assertEquals("Nobody tells me anything.", OeaCaptions.tidy("  nobody tells me anything  "))
    }

    @Test fun punctuationThatIsAlreadyThereIsLeftAlone() {
        assertEquals("Where were you?", OeaCaptions.tidy("Where were you?"))
        assertEquals("Hello.", OeaCaptions.tidy("hello."))
    }

    @Test fun theSoundsARecognizerReportsAreNotDialogue() {
        assertEquals("", OeaCaptions.tidy("[MUSIC]"))
        assertEquals("", OeaCaptions.tidy("(applause)"))
        assertEquals("", OeaCaptions.tidy("♪ ♪"))
        assertEquals("", OeaCaptions.tidy("   "))
        assertEquals("", OeaCaptions.tidy(null))
    }

    @Test fun aLongLineIsBrokenInTwoRatherThanAcrossTheWholePicture() {
        val wrapped = OeaCaptions.wrap("I have been waiting for you since the day the boats came in", 30)
        val lines = wrapped.split("\n")
        assertEquals(2, lines.size)
        lines.forEach { assertTrue(it, it.length <= 34) }
        assertEquals("I have been waiting for you since the day the boats came in", lines.joinToString(" "))
    }

    @Test fun aShortLineIsNotBrokenAtAll() {
        assertEquals("Come here.", OeaCaptions.wrap("Come here.", 42))
    }

    @Test fun aCueTakesItsTimingFromTheMeasuredRegion() {
        val cues = OeaCaptions.cues(
            listOf(region(1000, 2200), region(3000, 4100)),
            listOf("nobody tells me anything", "where were you"),
            60_000
        )
        assertEquals(2, cues.size)
        assertEquals(1000L, cues[0].startMs)
        assertEquals(2200L, cues[0].endMs)
        assertEquals(3000L, cues[1].startMs)
        // A recognizer reports the words, not the intent: what it gives back is a
        // statement unless the service punctuated it itself.
        assertEquals("Where were you.", cues[1].text)
    }

    @Test fun aRegionNobodySpokeInDoesNotBecomeACue() {
        val cues = OeaCaptions.cues(
            listOf(region(1000, 2000), region(3000, 4000)),
            listOf("first line", null),
            60_000
        )
        assertEquals(1, cues.size)
        assertEquals("First line.", cues[0].text)
    }

    @Test fun theSameLineHeardTwiceIsDrawnOnce() {
        val cues = OeaCaptions.cues(
            listOf(region(1000, 1800), region(2000, 2800)),
            listOf("run", "Run."),
            60_000
        )
        assertEquals(1, cues.size)
    }

    @Test fun aCueNeverRunsPastTheEndOfTheFilm() {
        val cues = OeaCaptions.cues(listOf(region(59_500, 61_000)), listOf("the end"), 60_000)
        assertEquals(1, cues.size)
        assertTrue(cues[0].endMs <= 60_000L)
    }

    @Test fun aCueMeasuredBeforeTheFilmStartsIsClampedToZero() {
        val cues = OeaCaptions.cues(listOf(region(-140, 900)), listOf("opening line"), 60_000)
        assertEquals(0L, cues[0].startMs)
    }

    @Test fun theCuesSayWhoWroteThemAndWhetherTheAudioLeftThePhone() {
        assertTrue(OeaCaptions.provenance(true).contains("never leaves"))
        assertTrue(OeaCaptions.provenance(false).contains("audio left the phone"))
    }
}
