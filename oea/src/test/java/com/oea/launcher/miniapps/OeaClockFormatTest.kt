package com.oea.launcher.miniapps

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The clock faces, which used to be two private functions inside a 1,200-line view.
 *
 * They disagree on purpose, and that is what these tests are for: a countdown rounds
 * up (a five-minute timer must not read 00:00 for its last second, and a timer paused
 * at 4:59.4 still reads 05:00), a stopwatch truncates (00:00.99 at 999 ms, not
 * 00:01.00), and neither shows a negative time if a deadline has already passed.
 */
class OeaClockFormatTest {

    @Test fun aCountdownRoundsUpSoTheLastSecondStillReadsOne() {
        assertEquals("05:00", OeaClockFormat.countdown(300_000L))
        assertEquals("05:00", OeaClockFormat.countdown(299_001L))
        assertEquals("04:59", OeaClockFormat.countdown(299_000L))
        assertEquals("00:01", OeaClockFormat.countdown(1L))
        assertEquals("00:00", OeaClockFormat.countdown(0L))
    }

    @Test fun aCountdownPastItsDeadlineIsZeroNotNegative() {
        assertEquals("00:00", OeaClockFormat.countdown(-5_000L))
    }

    @Test fun aLongCountdownGainsAnHoursField() {
        assertEquals("1:00:00", OeaClockFormat.countdown(3_600_000L))
        assertEquals("10:00:00", OeaClockFormat.countdown(36_000_000L))
    }

    @Test fun aStopwatchTruncatesToHundredths() {
        assertEquals("00:00.00", OeaClockFormat.stopwatch(0L))
        assertEquals("00:00.99", OeaClockFormat.stopwatch(999L))
        assertEquals("00:01.00", OeaClockFormat.stopwatch(1_000L))
        assertEquals("00:01.23", OeaClockFormat.stopwatch(1_239L))
    }

    @Test fun aStopwatchPastAnHourKeepsCountingMinutes() {
        assertEquals("1:01:01.05", OeaClockFormat.stopwatch(3_661_050L))
    }

    @Test fun aStopwatchPastItsRangeIsZeroNotNegative() {
        assertEquals("00:00.00", OeaClockFormat.stopwatch(-1L))
    }
}
