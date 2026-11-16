package com.oea.launcher.miniapps

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class OeaClockScheduleTest {
    private fun date(day: Int, hour: Int, minute: Int, second: Int = 0): Calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear(); set(2026, Calendar.NOVEMBER, day, hour, minute, second)
    }
    @Test fun nextAlarmRespectsTimeAndRepeatDays() {
        // 16 November 2026 is Monday; Sunday=0, Monday=1.
        assertEquals(date(16, 7, 30).timeInMillis, nextClockAlarm(date(16, 7, 29, 30), 7, 30, emptySet()))
        assertEquals(date(17, 7, 30).timeInMillis, nextClockAlarm(date(16, 7, 30), 7, 30, emptySet()))
        assertEquals(date(22, 7, 30).timeInMillis, nextClockAlarm(date(16, 8, 0), 7, 30, setOf(0)))
        assertEquals(date(23, 7, 30).timeInMillis, nextClockAlarm(date(16, 8, 0), 7, 30, setOf(1)))
    }
    @Test fun handlesHalfHourZones() {
        val now = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata")).apply { clear(); set(2026, 10, 16, 6, 0) }
        val expected = (now.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, 7); set(Calendar.MINUTE, 15) }
        assertEquals(expected.timeInMillis, nextClockAlarm(now, 7, 15, emptySet()))
    }
}
