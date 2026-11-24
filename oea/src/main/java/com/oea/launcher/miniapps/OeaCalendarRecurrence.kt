package com.oea.launcher.miniapps

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/** One appearance of an event on one day, which is not the same thing as an event. */
internal data class OeaCalendarOccurrence(
    val event: OeaCalendarApp.Event,
    val date: LocalDate,
    /** True for the event's own date; false for every repeated appearance. */
    val anchor: Boolean
)

/**
 * Recurrence for OEA's own events, as pure date arithmetic.
 *
 * Before this, "Weekly" was a label: the store kept it, the editor offered it, the .ics
 * export wrote `RRULE:FREQ=WEEKLY`, and every view drew the event exactly once, on the
 * day it was created. The rules below are the ones a person expects, including the
 * two that calendars usually get wrong:
 *
 * * a monthly event on the 31st appears on the last day of shorter months (31 Jan →
 *   28/29 Feb → 31 Mar), never on the 3rd of a month it has been clamped into;
 * * a yearly event on 29 February appears on 28 February in common years and returns
 *   to the 29th in leap years.
 *
 * Repeated appearances are generated, never stored, so an endless series costs nothing
 * in the store and editing the series edits one event.
 */
internal object OeaCalendarRecurrence {

    /** "HH:mm" from an event, or [fallbackHour] when the field is blank or broken. */
    fun parseTime(raw: String, fallbackHour: Int = 9): LocalTime {
        val parts = raw.trim().split(":")
        val h = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: fallbackHour
        val m = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
        return LocalTime.of(h.coerceIn(0, 23), m.coerceIn(0, 59))
    }

    /** The day an event is anchored to, clamping a 31st into a 30-day month. */
    fun anchorDate(event: OeaCalendarApp.Event): LocalDate {
        val month = YearMonth.of(event.year, (event.month + 1).coerceIn(1, 12))
        return month.atDay(event.day.coerceIn(1, month.lengthOfMonth()))
    }

    /** Does [event] appear on [date]? */
    fun occursOn(event: OeaCalendarApp.Event, date: LocalDate): Boolean {
        val anchor = anchorDate(event)
        if (date.isBefore(anchor)) return false
        return when (event.recurrence.uppercase(Locale.US)) {
            "DAILY" -> true
            "WEEKLY" -> date.dayOfWeek == anchor.dayOfWeek
            "MONTHLY" -> date.dayOfMonth == clampedDay(anchor.dayOfMonth, date)
            "YEARLY" -> date.month == anchor.month && date.dayOfMonth == clampedDay(anchor.dayOfMonth, date)
            else -> date == anchor
        }
    }

    /**
     * Every appearance in `[from, toExclusive)`, ordered by day and then by time.
     *
     * Generation jumps to the next appearance rather than walking every day, so a year
     * view of two hundred events is two hundred short walks, not 73,000 date tests.
     */
    fun occurrences(
        events: List<OeaCalendarApp.Event>,
        from: LocalDate,
        toExclusive: LocalDate,
        limit: Int = 600
    ): List<OeaCalendarOccurrence> {
        if (!from.isBefore(toExclusive)) return emptyList()
        val out = ArrayList<OeaCalendarOccurrence>()
        events.forEach { event ->
            val anchor = anchorDate(event)
            when (event.recurrence.uppercase(Locale.US)) {
                "DAILY" -> {
                    var day = if (from.isAfter(anchor)) from else anchor
                    while (day.isBefore(toExclusive) && out.size < limit) {
                        out += OeaCalendarOccurrence(event, day, day == anchor)
                        day = day.plusDays(1)
                    }
                }
                "WEEKLY" -> {
                    var day = if (from.isAfter(anchor)) from else anchor
                    while (day.dayOfWeek != anchor.dayOfWeek) day = day.plusDays(1)
                    while (day.isBefore(toExclusive) && out.size < limit) {
                        out += OeaCalendarOccurrence(event, day, day == anchor)
                        day = day.plusDays(7)
                    }
                }
                "MONTHLY" -> {
                    var month = YearMonth.from(if (from.isAfter(anchor)) from else anchor)
                    val lastMonth = YearMonth.from(toExclusive)
                    while (!month.isAfter(lastMonth) && out.size < limit) {
                        val day = month.atDay(clampedDay(anchor.dayOfMonth, month.atDay(1)))
                        if (!day.isBefore(anchor) && !day.isBefore(from) && day.isBefore(toExclusive)) {
                            out += OeaCalendarOccurrence(event, day, day == anchor)
                        }
                        month = month.plusMonths(1)
                    }
                }
                "YEARLY" -> {
                    var year = maxOf(anchor.year, from.year)
                    while (year <= toExclusive.year && out.size < limit) {
                        val month = YearMonth.of(year, anchor.month)
                        val day = month.atDay(clampedDay(anchor.dayOfMonth, month.atDay(1)))
                        if (!day.isBefore(anchor) && !day.isBefore(from) && day.isBefore(toExclusive)) {
                            out += OeaCalendarOccurrence(event, day, day == anchor)
                        }
                        year++
                    }
                }
                else -> if (!anchor.isBefore(from) && anchor.isBefore(toExclusive)) {
                    out += OeaCalendarOccurrence(event, anchor, true)
                }
            }
        }
        return out.sortedWith(
            compareBy<OeaCalendarOccurrence>(
                { it.date },
                { if (it.event.allDay) "" else it.event.time },
                { it.event.title }
            )
        )
    }

    /**
     * When the event next starts at or after [from], or null when it never will again
     * (a one-off in the past). All-day events are given 09:00, the hour a reminder for
     * "something today" is useful at.
     *
     * Walking day by day for a bounded 400 days is what makes the clamped cases
     * (31st in February, 29 February in a common year) fall out for free instead of
     * needing five separate steppers that each have to agree with [occursOn].
     */
    fun nextStart(event: OeaCalendarApp.Event, from: ZonedDateTime): ZonedDateTime? {
        val time = if (event.allDay || event.time.isBlank()) LocalTime.of(9, 0) else parseTime(event.time)
        val anchor = anchorDate(event)
        var day = if (from.toLocalDate().isAfter(anchor)) from.toLocalDate() else anchor
        repeat(400) {
            if (occursOn(event, day)) {
                val candidate = ZonedDateTime.of(day, time, from.zone)
                if (!candidate.isBefore(from)) return candidate
            }
            day = day.plusDays(1)
        }
        return null
    }

    /** Day [day] as it exists in the month of [inMonth] (31 → 28/29/30). */
    fun clampedDay(day: Int, inMonth: LocalDate): Int =
        day.coerceIn(1, YearMonth.from(inMonth).lengthOfMonth())
}
