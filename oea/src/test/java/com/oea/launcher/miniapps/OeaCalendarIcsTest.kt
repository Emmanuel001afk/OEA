package com.oea.launcher.miniapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The .ics half of the Calendar. These are the cases a real file from Google, Apple or
 * Outlook exercises: Zulu timestamps, `TZID` parameters, all-day events, folded lines
 * and escaped text. Before this pass the writer labelled local time as UTC, wrote
 * zero-length all-day events, and unescaped in an order that could eat its own output.
 */
class OeaCalendarIcsTest {

    private fun event(
        id: Long = 42L,
        year: Int = 2026,
        month: Int = 2,
        day: Int = 10,
        title: String = "Planning",
        time: String = "12:30",
        endTime: String = "13:30",
        location: String = "",
        desc: String = "",
        reminderMin: Int = 15,
        recurrence: String = "NONE",
        allDay: Boolean = false
    ) = OeaCalendarApp.Event(
        id = id, year = year, month = month, day = day, title = title,
        time = if (allDay) "" else time, endTime = if (allDay) "" else endTime,
        location = location, desc = desc, category = "OTHER",
        reminderMin = reminderMin, recurrence = recurrence, allDay = allDay
    )

    @Test fun theStampIsActuallyUtc() {
        assertEquals("19700101T000000Z", OeaCalendarIcs.stampUtc(0L))
        val stamp = OeaCalendarIcs.stampUtc()
        assertTrue(stamp.endsWith("Z"))
        val parsed = LocalDateTime.parse(stamp, DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))
        val seconds = kotlin.math.abs(Duration.between(parsed.toInstant(ZoneOffset.UTC), Instant.now()).seconds)
        // If the writer formatted local time and appended Z, this is off by the zone.
        assertTrue("stamp $stamp should be within a minute of now", seconds < 60)
    }

    @Test fun anAllDayEventEndsTheDayAfterItStarts() {
        val ics = OeaCalendarIcs.toIcs(listOf(event(day = 10, allDay = true)))
        assertTrue(ics.contains("DTSTART;VALUE=DATE:20260310"))
        assertTrue(ics.contains("DTEND;VALUE=DATE:20260311"))
    }

    @Test fun anAllDayEventOnTheLastOfTheMonthEndsOnTheFirstOfTheNext() {
        val ics = OeaCalendarIcs.toIcs(listOf(event(year = 2026, month = 0, day = 31, allDay = true)))
        assertTrue(ics.contains("DTSTART;VALUE=DATE:20260131"))
        assertTrue(ics.contains("DTEND;VALUE=DATE:20260201"))
    }

    @Test fun longLinesAreFoldedAndUnfoldBack() {
        val longTitle = "Quarterly planning session with the whole leadership team and the board of directors"
        val ics = OeaCalendarIcs.toIcs(listOf(event(title = longTitle)))
        ics.split('\n').filter { it.isNotEmpty() }.forEach { line ->
            assertTrue("line too long (${line.length}): $line", line.length <= 75)
        }
        assertEquals(longTitle, OeaCalendarIcs.fromIcs(ics).single().title)
    }

    @Test fun aRoundTripKeepsWhatTheAppCanShow() {
        val original = event(
            title = "Lunch, with Ana; and Bob",
            location = "Café Ünïcode",
            desc = "line one\nline two",
            recurrence = "WEEKLY",
            reminderMin = 30
        )
        val back = OeaCalendarIcs.fromIcs(OeaCalendarIcs.toIcs(listOf(original))).single()
        assertEquals(original.title, back.title)
        assertEquals(original.time, back.time)
        assertEquals(original.endTime, back.endTime)
        assertEquals(original.location, back.location)
        assertEquals(original.desc, back.desc)
        assertEquals(original.recurrence, back.recurrence)
        assertEquals(original.reminderMin, back.reminderMin)
        assertEquals(2026, back.year)
        assertEquals(2, back.month)
        assertEquals(10, back.day)
        // Categories are OEA's own, and are deliberately not written to a shared format.
        assertEquals("OTHER", back.category)
    }

    @Test fun escapingIsSymmetricEvenForAnEscapedNewline() {
        assertEquals("a\nb", OeaCalendarIcs.unescape(OeaCalendarIcs.escape("a\nb")))
        assertEquals("a\\nb", OeaCalendarIcs.unescape(OeaCalendarIcs.escape("a\\nb")))
        assertEquals("a\\,b", OeaCalendarIcs.unescape(OeaCalendarIcs.escape("a\\,b")))
        assertEquals("semi;colon,comma", OeaCalendarIcs.unescape(OeaCalendarIcs.escape("semi;colon,comma")))
    }

    @Test fun aZuluStartIsConvertedToThePhoneZone() {
        val ics = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            DTSTART:20260310T100000Z
            SUMMARY:Zulu
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val expected = LocalDateTime.ofInstant(
            LocalDateTime.of(2026, 3, 10, 10, 0).toInstant(ZoneOffset.UTC),
            ZoneId.systemDefault()
        )
        assertLocal(expected, OeaCalendarIcs.fromIcs(ics).single())
    }

    @Test fun aTzidStartIsReadOnItsOwnClock() {
        val ics = """
            BEGIN:VCALENDAR
            BEGIN:VTIMEZONE
            TZID:Europe/Berlin
            BEGIN:STANDARD
            DTSTART:19701025T030000
            END:STANDARD
            END:VTIMEZONE
            BEGIN:VEVENT
            DTSTART;TZID="Europe/Berlin":20260310T100000
            SUMMARY:Berlin
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val expected = LocalDateTime.ofInstant(
            LocalDateTime.of(2026, 3, 10, 10, 0).atZone(ZoneId.of("Europe/Berlin")).toInstant(),
            ZoneId.systemDefault()
        )
        assertLocal(expected, OeaCalendarIcs.fromIcs(ics).single())
    }

    @Test fun theTimeZoneBlockDoesNotBecomeAnEvent() {
        val ics = """
            BEGIN:VCALENDAR
            BEGIN:VTIMEZONE
            TZID:Europe/Berlin
            BEGIN:DAYLIGHT
            DTSTART:19700329T020000
            RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU
            END:DAYLIGHT
            END:VTIMEZONE
            BEGIN:VEVENT
            DTSTART;VALUE=DATE:20260401
            SUMMARY:All day
            RRULE:FREQ=YEARLY
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val events = OeaCalendarIcs.fromIcs(ics)
        assertEquals(1, events.size)
        assertTrue(events.single().allDay)
        assertEquals("YEARLY", events.single().recurrence)
        assertEquals("", events.single().time)
    }

    @Test fun aFoldedSummaryFromAnotherCalendarIsUnfolded() {
        // A fold drops the newline and the continuation's single space, so a space
        // that belongs to the text has to be written before the fold.
        val ics = "BEGIN:VCALENDAR\nBEGIN:VEVENT\nDTSTART:20260505T090000Z\n" +
            "SUMMARY:Review with the platform team and the \n" +
            " security reviewers about the next release\n" +
            "END:VEVENT\nEND:VCALENDAR"
        val back = OeaCalendarIcs.fromIcs(ics).single()
        assertEquals("Review with the platform team and the security reviewers about the next release", back.title)
    }

    @Test fun importedEventsGetPositiveIdsSoTheyStayEditable() {
        val ics = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            DTSTART:20260710T100000Z
            SUMMARY:Imported
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val imported = OeaCalendarIcs.fromIcs(ics).single()
        assertTrue("a negative id would mark this read-only", imported.id > 0L)
        val next = OeaCalendarApp.freshEventId()
        assertTrue(next > imported.id)
        assertTrue(OeaCalendarApp.freshEventId() > next)
    }

    @Test fun aValarmBecomesAReminder() {
        val ics = OeaCalendarIcs.toIcs(listOf(event(reminderMin = 60)))
        assertTrue(ics.contains("TRIGGER:-PT60M"))
        assertEquals(60, OeaCalendarIcs.fromIcs(ics).single().reminderMin)
    }

    @Test fun aNoteWithoutAnyDateIsNotAnEvent() {
        val ics = "BEGIN:VCALENDAR\nBEGIN:VEVENT\nSUMMARY:Nothing dated\nEND:VEVENT\nEND:VCALENDAR"
        assertTrue(OeaCalendarIcs.fromIcs(ics).isEmpty())
        assertFalse(OeaCalendarIcs.toIcs(emptyList()).contains("BEGIN:VEVENT"))
    }

    private fun assertLocal(expected: LocalDateTime, actual: OeaCalendarApp.Event) {
        assertEquals(expected.year, actual.year)
        assertEquals(expected.monthValue - 1, actual.month)
        assertEquals(expected.dayOfMonth, actual.day)
        assertEquals("%02d:%02d".format(expected.hour, expected.minute), actual.time)
    }
}
