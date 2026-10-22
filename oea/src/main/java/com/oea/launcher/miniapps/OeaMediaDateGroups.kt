package com.oea.launcher.miniapps

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * How EMAGE turns a picture's date into the section it sits under.
 *
 * The rule that replaces the old one: **this month is shown day by day, and
 * every earlier month is one section.** The previous grouping bucketed by
 * day at every age but only *labelled* old buckets with the month, so a busy
 * October produced twenty sections all called "October 2026" with no date on
 * any of them. Now October is one section, it says October once, and the days
 * of the current month still get their own dated headings ("Today",
 * "Yesterday", "Monday, 12 October", "3 October").
 *
 * Pure `java.util` so the bucketing is covered by JVM tests.
 */
internal object OeaMediaDateGroups {

    /** One section: the sort key (the start of its day or month) and its heading. */
    data class Bucket(val start: Long, val label: String)

    fun bucketFor(takenAt: Long, now: Long, locale: Locale = Locale.getDefault()): Bucket {
        val taken = Calendar.getInstance().apply { timeInMillis = takenAt }
        val today = Calendar.getInstance().apply { timeInMillis = now }
        val dayStart = taken.dayStart()
        val ageDays = ageInDays(dayStart, today.dayStart())
        if (ageDays <= 0L) return Bucket(dayStart, "Today")
        if (ageDays == 1L) return Bucket(dayStart, "Yesterday")
        val sameMonth = taken.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
            taken.get(Calendar.MONTH) == today.get(Calendar.MONTH)
        if (sameMonth) {
            val label = if (ageDays in 2L..6L) {
                SimpleDateFormat("EEEE, d MMMM", locale).format(Date(takenAt))
            } else {
                SimpleDateFormat("d MMMM", locale).format(Date(takenAt))
            }
            return Bucket(dayStart, label)
        }
        val monthStart = Calendar.getInstance().apply {
            timeInMillis = takenAt
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return Bucket(monthStart, SimpleDateFormat("MMMM yyyy", locale).format(Date(takenAt)))
    }

    private fun Calendar.dayStart(): Long {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        return timeInMillis
    }

    /** Rounded, because a DST day is 23 or 25 hours and yesterday must stay 1. */
    private fun ageInDays(dayStart: Long, todayStart: Long): Long =
        (todayStart - dayStart + 43_200_000L) / 86_400_000L
}
