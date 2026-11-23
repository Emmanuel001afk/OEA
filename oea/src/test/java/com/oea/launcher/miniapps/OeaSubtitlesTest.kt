package com.oea.launcher.miniapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The subtitle reader, which is the one part of VIP that can be checked without a
 * phone: it takes text in and hands cue times out.
 *
 * These are the answers that were wrong before. A file written on Windows starts
 * with a byte-order mark, and the mark was treated as the first character of the
 * cue number, so "1" stopped looking like an index and became the first line of
 * dialogue. The same filter that removed cue numbers also removed any line of
 * dialogue that happened to be a number. WebVTT cue identifiers were painted on
 * screen because everything but the timing line was treated as text.
 */
class OeaSubtitlesTest {

    private val srt = """
        1
        00:00:01,000 --> 00:00:02,500
        Hello

        2
        00:00:03,000 --> 00:00:04,000
        Two
    """.trimIndent()

    @Test fun cueNumbersAreNotDialogue() {
        val track = OeaSubtitles.parse("film.srt", srt)!!
        assertEquals(2, track.count)
        assertEquals("Hello", track.cues[0].text)
        assertEquals("Two", track.cues[1].text)
    }

    @Test fun byteOrderMarkDoesNotBecomeDialogue() {
        val track = OeaSubtitles.parse("film.srt", "\uFEFF" + srt)!!
        assertEquals(2, track.count)
        assertEquals("Hello", track.cues[0].text)
        assertEquals(1_000L, track.cues[0].startMs)
    }

    @Test fun dialogueThatIsOnlyANumberSurvives() {
        val number = """
            1
            00:00:01,000 --> 00:00:02,000
            42

            2
            00:00:03,000 --> 00:00:04,000
            1984
        """.trimIndent()
        val track = OeaSubtitles.parse("film.srt", number)!!
        assertEquals("42", track.cues[0].text)
        assertEquals("1984", track.cues[1].text)
    }

    @Test fun multiLineCueKeepsEveryLine() {
        val track = OeaSubtitles.parse("film.srt", "1\n00:00:01,000 --> 00:00:04,000\nFirst line\nSecond line")!!
        assertEquals("First line\nSecond line", track.cues[0].text)
    }

    @Test fun webVttIsReadAndItsCueIdentifierIsNotText() {
        val vtt = """
            WEBVTT

            cue-1
            00:00:01.000 --> 00:00:02.000
            Hi there
        """.trimIndent()
        val track = OeaSubtitles.parse("film.vtt", vtt)!!
        assertEquals(1, track.count)
        assertEquals("Hi there", track.cues[0].text)
    }

    @Test fun timingIsReadInMilliseconds() {
        val track = OeaSubtitles.parse("film.srt", "1\n00:01:02,345 --> 00:01:04,500\nLong one")!!
        assertEquals(62_345L, track.cues[0].startMs)
        assertEquals(64_500L, track.cues[0].endMs)
    }

    @Test fun shortTimestampsInMinutesAreAccepted() {
        val track = OeaSubtitles.parse("film.srt", "1\n01:30,000 --> 01:32,000\nLate")!!
        assertEquals(90_000L, track.cues[0].startMs)
        assertEquals(92_000L, track.cues[0].endMs)
    }

    @Test fun inlineMarkupIsStripped() {
        val track = OeaSubtitles.parse("film.srt", "1\n00:00:01,000 --> 00:00:02,000\n<i>Whispered</i>")!!
        assertEquals("Whispered", track.cues[0].text)
    }

    @Test fun anOffsetMovesEveryCueAndNeverGoesNegative() {
        val track = OeaSubtitles.parse("film.srt", srt)!!
        val later = OeaSubtitles.shifted(track, 1_000L)
        assertEquals(2_000L, later.cues[0].startMs)
        val earlier = OeaSubtitles.shifted(track, -5_000L)
        assertEquals(0L, earlier.cues[0].startMs)
        assertTrue(earlier.cues[0].endMs >= 1L)
    }

    @Test fun theCueAtATimeIsTheOnePlaying() {
        val track = OeaSubtitles.parse("film.srt", srt)!!
        assertEquals("Hello", track.at(1_500L))
        assertEquals("Two", track.at(3_500L))
    }

    @Test fun silenceIsNotFilledWithAnOldCue() {
        val track = OeaSubtitles.parse("film.srt", srt)!!
        assertNull(track.at(0L))
        assertNull(track.at(20_000L))
    }

    @Test fun filesThatCannotBeRenderedAreRefusedRatherThanGuessed() {
        assertNull(OeaSubtitles.parse("film.ass", "[Script Info]\nDialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Hello"))
        assertNull(OeaSubtitles.parse("empty.srt", ""))
        assertNull(OeaSubtitles.parse("noise.srt", "this file has no timings at all"))
    }
}
