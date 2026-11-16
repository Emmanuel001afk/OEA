package com.oea.launcher.miniapps

import java.util.Calendar

/** Next occurrence in local time; days use Calendar's Sunday=0 convention. */
internal fun nextClockAlarm(now: Calendar, hour: Int, minute: Int, days: Set<Int>): Long {
    val next = now.clone() as Calendar
    next.set(Calendar.HOUR_OF_DAY, hour)
    next.set(Calendar.MINUTE, minute)
    next.set(Calendar.SECOND, 0)
    next.set(Calendar.MILLISECOND, 0)
    if (next.timeInMillis <= now.timeInMillis) next.add(Calendar.DAY_OF_YEAR, 1)
    if (days.isNotEmpty()) {
        repeat(7) {
            if (next.get(Calendar.DAY_OF_WEEK) - 1 in days) return next.timeInMillis
            next.add(Calendar.DAY_OF_YEAR, 1)
        }
    }
    return next.timeInMillis
}
