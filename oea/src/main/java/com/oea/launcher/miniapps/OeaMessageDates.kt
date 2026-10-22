package com.oea.launcher.miniapps

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The dates Messaging prints: the small label on a conversation row, the pill
 * between days inside a chat, and the full stamp in a message's details.
 *
 * Pure `java.util` on purpose so the rules are unit-tested, not eyeballed:
 * a row says a time for today, a weekday inside this week, and a real date —
 * with the year whenever it is not this one — for everything older. The old
 * list printed "Oct 12 14:05" for every year and the chat printed "Oct 12"
 * for every October, which is how two different years ended up wearing the
 * same label.
 */
internal object OeaMessageDates {

    /** Local midnight at the start of [millis]' day. */
    fun dayStart(millis: Long): Long {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = millis
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    /**
     * Whole days between the day containing [millis] and the day containing
     * [now]. Rounded, because a DST change makes a local day 23 or 25 hours
     * and an exact-millisecond comparison calls yesterday "23 hours ago".
     */
    fun ageInDays(millis: Long, now: Long): Long {
        val diff = dayStart(now) - dayStart(millis)
        return (diff + 43_200_000L) / 86_400_000L
    }

    private fun sameYear(millis: Long, now: Long): Boolean {
        val a = Calendar.getInstance().apply { timeInMillis = millis }
        val b = Calendar.getInstance().apply { timeInMillis = now }
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
    }

    /** "14:05" — the style every other OEA clock readout uses. */
    fun timeLabel(millis: Long, locale: Locale = Locale.getDefault()): String =
        SimpleDateFormat("HH:mm", locale).format(Date(millis))

    /**
     * The label at the right edge of a conversation row: the time today,
     * "Yesterday", the weekday inside this week, then a date.
     */
    fun threadLabel(millis: Long, now: Long, locale: Locale = Locale.getDefault()): String {
        if (millis <= 0L) return ""
        val date = Date(millis)
        return when (val age = ageInDays(millis, now)) {
            0L -> timeLabel(millis, locale)
            1L -> "Yesterday"
            in 2L..6L -> SimpleDateFormat("EEE", locale).format(date)
            else -> {
                if (age < 0L || !sameYear(millis, now)) SimpleDateFormat("d MMM yy", locale).format(date)
                else SimpleDateFormat("d MMM", locale).format(date)
            }
        }
    }

    /**
     * The pill between days in a conversation. Always unambiguous: recent
     * days get their weekday *and* their date, older days get the full date.
     */
    fun separatorLabel(millis: Long, now: Long, locale: Locale = Locale.getDefault()): String {
        if (millis <= 0L) return ""
        val date = Date(millis)
        return when (ageInDays(millis, now)) {
            0L -> "Today"
            1L -> "Yesterday"
            in 2L..6L -> SimpleDateFormat("EEEE, d MMMM", locale).format(date) +
                if (sameYear(millis, now)) "" else SimpleDateFormat(" yyyy", locale).format(date)
            else -> SimpleDateFormat("d MMMM yyyy", locale).format(date)
        }
    }

    /** "Mon, 12 Oct 2026 • 14:05" — the details-sheet stamp. */
    fun fullLabel(millis: Long, locale: Locale = Locale.getDefault()): String =
        if (millis <= 0L) "Not recorded"
        else SimpleDateFormat("EEE, d MMM yyyy • HH:mm", locale).format(Date(millis))
}
