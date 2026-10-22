package com.oea.launcher.miniapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.Calendar
import java.util.Locale

class OeaMediaDateGroupsTest {

    private fun at(year: Int, month: Int, day: Int, hour: Int = 12): Long {
        return Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, hour, 0, 0)
        }.timeInMillis
    }

    private val now = at(2026, 10, 22, 15)

    @Test fun aWholePastMonthIsOneBucketWithOneLabel() {
        // The reported confusion: many sections all called "October". Two
        // different October days of a past year must now share one bucket.
        val early = OeaMediaDateGroups.bucketFor(at(2025, 10, 3), now, Locale.US)
        val late = OeaMediaDateGroups.bucketFor(at(2025, 10, 28), now, Locale.US)
        assertEquals(early.start, late.start)
        assertEquals(at(2025, 10, 1, 0), early.start)
        assertEquals("October 2025", early.label)
        assertEquals("October 2025", late.label)
    }

    @Test fun lastMonthIsAlsoOneBucketEvenWhenRecent() {
        val start = OeaMediaDateGroups.bucketFor(at(2026, 9, 2), now, Locale.US)
        val end = OeaMediaDateGroups.bucketFor(at(2026, 9, 30), now, Locale.US)
        assertEquals(start.start, end.start)
        assertEquals("September 2026", start.label)
    }

    @Test fun currentMonthKeepsDatedDayBuckets() {
        val fifth = OeaMediaDateGroups.bucketFor(at(2026, 10, 5), now, Locale.US)
        val sixth = OeaMediaDateGroups.bucketFor(at(2026, 10, 6), now, Locale.US)
        assertNotEquals(fifth.start, sixth.start)
        assertEquals("5 October", fifth.label)
        assertEquals("6 October", sixth.label)
    }

    @Test fun todayYesterdayAndRecentWeekdaysCarryTheirDate() {
        assertEquals("Today", OeaMediaDateGroups.bucketFor(at(2026, 10, 22, 9), now, Locale.US).label)
        assertEquals("Yesterday", OeaMediaDateGroups.bucketFor(at(2026, 10, 21), now, Locale.US).label)
        // 2026-10-19 is a Monday, three days before "now".
        assertEquals("Monday, 19 October", OeaMediaDateGroups.bucketFor(at(2026, 10, 19), now, Locale.US).label)
    }

    @Test fun yesterdayWinsEvenAcrossAMonthBoundary() {
        val firstOfMonth = at(2026, 11, 1, 9)
        val bucket = OeaMediaDateGroups.bucketFor(at(2026, 10, 31), firstOfMonth, Locale.US)
        assertEquals("Yesterday", bucket.label)
    }
}
