package com.oea.launcher.miniapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale

class OeaMessageDatesTest {

    private fun at(year: Int, month: Int, day: Int, hour: Int = 12, minute: Int = 0): Long {
        return Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis
    }

    // Thursday 22 October 2026, mid-afternoon.
    private val now = at(2026, 10, 22, 15, 30)

    @Test fun threadLabelsGoTimeYesterdayWeekdayThenDate() {
        assertEquals("09:05", OeaMessageDates.threadLabel(at(2026, 10, 22, 9, 5), now, Locale.US))
        assertEquals("Yesterday", OeaMessageDates.threadLabel(at(2026, 10, 21, 23, 59), now, Locale.US))
        assertEquals("Mon", OeaMessageDates.threadLabel(at(2026, 10, 19), now, Locale.US))
        assertEquals("30 Sep", OeaMessageDates.threadLabel(at(2026, 9, 30), now, Locale.US))
    }

    @Test fun olderYearsAreNeverLabelledLikeThisYear() {
        // "Oct 12" for two different Octobers is the ambiguity being removed.
        assertEquals("12 Oct", OeaMessageDates.threadLabel(at(2026, 10, 12), now, Locale.US))
        assertEquals("22 Oct 25", OeaMessageDates.threadLabel(at(2025, 10, 22), now, Locale.US))
        assertEquals("25 December 2025", OeaMessageDates.separatorLabel(at(2025, 12, 25), now, Locale.US))
    }

    @Test fun separatorsNameRecentDaysAndDateOlderOnes() {
        assertEquals("Today", OeaMessageDates.separatorLabel(at(2026, 10, 22, 0, 1), now, Locale.US))
        assertEquals("Yesterday", OeaMessageDates.separatorLabel(at(2026, 10, 21), now, Locale.US))
        assertEquals("Monday, 19 October", OeaMessageDates.separatorLabel(at(2026, 10, 19), now, Locale.US))
        assertEquals("3 October 2026", OeaMessageDates.separatorLabel(at(2026, 10, 3), now, Locale.US))
    }

    @Test fun fullLabelCarriesDayDateYearAndTime() {
        val label = OeaMessageDates.fullLabel(at(2026, 10, 19, 14, 5), Locale.US)
        assertTrue(label.contains("19 Oct 2026"))
        assertTrue(label.contains("14:05"))
        assertEquals("Not recorded", OeaMessageDates.fullLabel(0L, Locale.US))
    }
}
