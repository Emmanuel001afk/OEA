package com.oea.launcher.miniapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * "Weekly" used to be a label the views never read: a repeating event appeared once,
 * on the day it was created. These are the rules the fix has to keep, including the
 * two that calendars usually get wrong — the 31st in a short month, and 29 February.
 */
class OeaCalendarRecurrenceTest {

    private fun event(
        year: Int,
        month: Int,
        day: Int,
        recurrence: String = "NONE",
        time: String = "10:00",
        allDay: Boolean = false,
        reminder: Int = 0,
        title: String = "Event",
        id: Long = 1L
    ) = OeaCalendarApp.Event(
        id = id, year = year, month = month, day = day, title = title,
        time = if (allDay) "" else time, endTime = "", location = "", desc = "",
        category = "OTHER", reminderMin = reminder, recurrence = recurrence, allDay = allDay
    )

    private fun days(events: List<OeaCalendarApp.Event>, from: LocalDate, to: LocalDate) =
        OeaCalendarRecurrence.occurrences(events, from, to).map { it.date }

    @Test fun aOneOffAppearsOnItsOwnDayAndNowhereElse() {
        val e = event(2026, 0, 15)
        assertEquals(listOf(LocalDate.of(2026, 1, 15)), days(listOf(e), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)))
    }

    @Test fun aDailyEventAppearsEveryDayFromItsAnchor() {
        val e = event(2026, 2, 10, recurrence = "DAILY")
        assertEquals(
            listOf(LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 11), LocalDate.of(2026, 3, 12)),
            days(listOf(e), LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 13))
        )
    }

    @Test fun nothingIsRepeatedBeforeItsAnchor() {
        val e = event(2026, 5, 20, recurrence = "DAILY")
        assertTrue(days(listOf(e), LocalDate.of(2026, 4, 1), LocalDate.of(2026, 5, 20)).isEmpty())
    }

    @Test fun aWeeklyEventKeepsItsWeekday() {
        // 10 March 2026 is a Tuesday.
        val e = event(2026, 2, 10, recurrence = "WEEKLY")
        val found = days(listOf(e), LocalDate.of(2026, 3, 1), LocalDate.of(2026, 4, 1))
        assertEquals(listOf(10, 17, 24, 31).map { LocalDate.of(2026, 3, it) }, found)
        assertTrue(found.all { it.dayOfWeek == LocalDate.of(2026, 3, 10).dayOfWeek })
    }

    @Test fun aMonthlyEventOnTheThirtyFirstUsesTheLastDayOfShortMonths() {
        // 31 January, then February has 28 days in 2026, then back to 31 March.
        val e = event(2026, 0, 31, recurrence = "MONTHLY")
        assertEquals(
            listOf(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 31), LocalDate.of(2026, 4, 30)),
            days(listOf(e), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 5, 1))
        )
    }

    @Test fun aMonthlyEventStaysOnItsDayAfterAClampedMonth() {
        // The clamp must not move the series: April's 31st is not the 3rd of a later month.
        val e = event(2026, 0, 31, recurrence = "MONTHLY")
        val found = days(listOf(e), LocalDate.of(2026, 6, 1), LocalDate.of(2026, 8, 1))
        assertEquals(listOf(LocalDate.of(2026, 6, 30), LocalDate.of(2026, 7, 31)), found)
    }

    @Test fun aYearlyEventOnTheTwentyNinthOfFebruaryFallsBackInCommonYears() {
        val e = event(2024, 1, 29, recurrence = "YEARLY")
        assertEquals(
            listOf(
                LocalDate.of(2024, 2, 29), LocalDate.of(2025, 2, 28),
                LocalDate.of(2026, 2, 28), LocalDate.of(2027, 2, 28), LocalDate.of(2028, 2, 29)
            ),
            days(listOf(e), LocalDate.of(2024, 1, 1), LocalDate.of(2029, 1, 1))
        )
    }

    @Test fun aYearlyEventKeepsItsMonth() {
        val e = event(2026, 10, 3, recurrence = "YEARLY")
        assertEquals(
            listOf(LocalDate.of(2026, 11, 3), LocalDate.of(2027, 11, 3)),
            days(listOf(e), LocalDate.of(2026, 1, 1), LocalDate.of(2027, 12, 31))
        )
    }

    @Test fun theAnchorOfAMonthIsReportedOnce() {
        // A monthly event whose anchor is in the range is both the anchor and a repeat.
        val e = event(2026, 2, 10, recurrence = "MONTHLY")
        val found = OeaCalendarRecurrence.occurrences(listOf(e), LocalDate.of(2026, 2, 1), LocalDate.of(2026, 4, 1))
        assertEquals(listOf(LocalDate.of(2026, 2, 10), LocalDate.of(2026, 3, 10)), found.map { it.date })
        assertEquals(listOf(true, false), found.map { it.anchor })
    }

    @Test fun occurrencesAreOrderedByDayThenTime() {
        val early = event(2026, 2, 10, time = "08:00", title = "Early")
        val late = event(2026, 2, 10, time = "18:00", title = "Late", id = 2L)
        val found = OeaCalendarRecurrence.occurrences(
            listOf(late, early), LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 11)
        )
        assertEquals(listOf("Early", "Late"), found.map { it.event.title })
    }

    @Test fun theLimitIsRespectedForAnEndlessSeries() {
        val e = event(2020, 0, 1, recurrence = "DAILY")
        val found = OeaCalendarRecurrence.occurrences(listOf(e), LocalDate.of(2026, 0, 1), LocalDate.of(2030, 0, 1), limit = 25)
        assertEquals(25, found.size)
    }

    @Test fun nextStartSkipsAnHourThatHasAlreadyPassedToday() {
        val zone = ZoneId.systemDefault()
        val e = event(2026, 2, 10, recurrence = "DAILY", time = "09:00")
        val at = ZonedDateTime.of(2026, 3, 10, 12, 0, 0, 0, zone)
        val next = OeaCalendarRecurrence.nextStart(e, at)
        assertEquals(ZonedDateTime.of(2026, 3, 11, 9, 0, 0, 0, zone), next)
    }

    @Test fun nextStartIsNullForAOneOffInThePast() {
        val e = event(2026, 0, 5)
        assertNull(OeaCalendarRecurrence.nextStart(e, ZonedDateTime.now()))
    }

    @Test fun nextStartGivesAnAllDayEventNineInTheMorning() {
        val zone = ZoneId.systemDefault()
        val e = event(2026, 5, 1, allDay = true)
        val at = ZonedDateTime.of(2026, 6, 1, 8, 0, 0, 0, zone)
        assertEquals(ZonedDateTime.of(2026, 6, 1, 9, 0, 0, 0, zone), OeaCalendarRecurrence.nextStart(e, at))
    }

    @Test fun aBrokenTimeFallsBackInsteadOfThrowing() {
        assertEquals(java.time.LocalTime.of(9, 0), OeaCalendarRecurrence.parseTime("", 9))
        assertEquals(java.time.LocalTime.of(23, 59), OeaCalendarRecurrence.parseTime("99:99"))
    }

    @Test fun clampedDayNeverExceedsTheMonth() {
        assertEquals(28, OeaCalendarRecurrence.clampedDay(31, LocalDate.of(2026, 2, 1)))
        assertEquals(29, OeaCalendarRecurrence.clampedDay(31, LocalDate.of(2024, 2, 1)))
        assertEquals(30, OeaCalendarRecurrence.clampedDay(31, LocalDate.of(2026, 4, 1)))
        assertEquals(31, OeaCalendarRecurrence.clampedDay(31, LocalDate.of(2026, 1, 1)))
    }
}
