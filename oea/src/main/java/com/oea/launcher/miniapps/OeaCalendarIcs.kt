package com.oea.launcher.miniapps

import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The iCalendar half of the Calendar, as text in and text out.
 *
 * It was correct for the easy case and quietly wrong for the ones people actually
 * import: `DTSTAMP` said "Zulu" while holding local time, an all-day event was written
 * with `DTEND` equal to `DTSTART` (a zero-length day some clients drop), a
 * `DTSTART;TZID=Europe/Berlin` was read as if Berlin were the phone's own zone, long
 * descriptions went out on one line past the 75-octet limit, and a title containing
 * `\,` unescaped wrongly because the replacements ran in an order where one could eat
 * another's output.
 */
internal object OeaCalendarIcs {

    private const val MAX_OCTETS = 73

    // ------------------------------------------------------------------ writing

    fun toIcs(events: List<OeaCalendarApp.Event>): String {
        val stamp = stampUtc()
        val out = StringBuilder()
        out.line("BEGIN:VCALENDAR")
        out.line("VERSION:2.0")
        out.line("PRODID:-//OEA//Calendar//EN")
        out.line("CALSCALE:GREGORIAN")
        out.line("METHOD:PUBLISH")
        events.forEach { event ->
            out.line("BEGIN:VEVENT")
            out.line("UID:oea-${event.id}@oea.local")
            out.line("DTSTAMP:$stamp")
            val anchor = anchorOf(event)
            val date = "%04d%02d%02d".format(anchor.year, anchor.monthValue, anchor.dayOfMonth)
            if (event.allDay || event.time.isBlank()) {
                out.line("DTSTART;VALUE=DATE:$date")
                val next = anchor.plusDays(1)
                // DTEND is exclusive: the day after is what makes this a one-day event.
                out.line("DTEND;VALUE=DATE:" + "%04d%02d%02d".format(next.year, next.monthValue, next.dayOfMonth))
            } else {
                val start = event.time.replace(":", "").padEnd(4, '0').take(4)
                val end = if (event.endTime.isBlank()) start else event.endTime.replace(":", "").padEnd(4, '0').take(4)
                out.line("DTSTART:${date}T${start}00")
                out.line("DTEND:${date}T${end}00")
            }
            out.line("SUMMARY:${escape(event.title)}")
            if (event.location.isNotBlank()) out.line("LOCATION:${escape(event.location)}")
            if (event.desc.isNotBlank()) out.line("DESCRIPTION:${escape(event.desc)}")
            when (event.recurrence.uppercase(Locale.US)) {
                "DAILY" -> out.line("RRULE:FREQ=DAILY")
                "WEEKLY" -> out.line("RRULE:FREQ=WEEKLY")
                "MONTHLY" -> out.line("RRULE:FREQ=MONTHLY")
                "YEARLY" -> out.line("RRULE:FREQ=YEARLY")
                else -> Unit
            }
            if (event.reminderMin > 0) {
                out.line("BEGIN:VALARM")
                out.line("ACTION:DISPLAY")
                out.line("DESCRIPTION:${escape(event.title)}")
                out.line("TRIGGER:-PT${event.reminderMin}M")
                out.line("END:VALARM")
            }
            out.line("END:VEVENT")
        }
        out.line("END:VCALENDAR")
        return out.toString()
    }

    /**
     * `DTSTAMP` is Zulu time by definition. Formatting local time and appending `Z`
     * told every other calendar that an event created in Lagos was made two hours ago
     * in London.
     */
    fun stampUtc(now: Long = System.currentTimeMillis()): String {
        val format = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date(now))
    }

    /**
     * RFC 5545 folds content lines at 75 **octets**, not characters: a title in
     * Yoruba or an emoji can take two to four bytes each. 73 leaves room for the
     * space the next line starts with, and full code points are never split.
     */
    fun fold(line: String): String {
        if (line.toByteArray(Charsets.UTF_8).size <= MAX_OCTETS) return line
        val out = StringBuilder(line.length + 8)
        var segment = StringBuilder()
        var octets = 0
        var index = 0
        while (index < line.length) {
            val codePoint = line.codePointAt(index)
            val chars = Character.charCount(codePoint)
            val width = when {
                codePoint < 0x80 -> 1
                codePoint < 0x800 -> 2
                codePoint < 0x10000 -> 3
                else -> 4
            }
            if (octets + width > MAX_OCTETS && segment.isNotEmpty()) {
                if (out.isNotEmpty()) out.append('\n').append(' ')
                out.append(segment)
                segment = StringBuilder()
                octets = 0
            }
            segment.appendRange(line, index, index + chars)
            octets += width
            index += chars
        }
        if (segment.isNotEmpty()) {
            if (out.isNotEmpty()) out.append('\n').append(' ')
            out.append(segment)
        }
        return out.toString()
    }

    fun escape(value: String): String = buildString {
        value.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                ';' -> append("\\;")
                ',' -> append("\\,")
                '\r' -> Unit
                '\n' -> append("\\n")
                else -> append(c)
            }
        }
    }

    fun unescape(value: String): String {
        if ('\\' !in value) return value
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                when (val next = value[i + 1]) {
                    'n', 'N' -> out.append('\n')
                    '\\', ';', ',' -> out.append(next)
                    else -> { out.append('\\'); out.append(next) }
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    // ------------------------------------------------------------------ reading

    /** Reads an iCalendar file. Only the properties OEA can actually show are taken. */
    fun fromIcs(text: String): List<OeaCalendarApp.Event> {
        val out = ArrayList<OeaCalendarApp.Event>()
        // One reusable block, a flag for whether a VEVENT is open, and a depth for the
        // VTIMEZONE blocks that carry properties with the same names as an event's.
        val block = LinkedHashMap<String, String>()
        var inEvent = false
        var timeZoneDepth = 0
        unfold(text).lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.startsWith("BEGIN:VTIMEZONE", ignoreCase = true) -> timeZoneDepth++
                line.startsWith("END:VTIMEZONE", ignoreCase = true) -> timeZoneDepth = (timeZoneDepth - 1).coerceAtLeast(0)
                timeZoneDepth > 0 -> Unit
                line.equals("BEGIN:VEVENT", ignoreCase = true) -> { block.clear(); inEvent = true }
                line.equals("END:VEVENT", ignoreCase = true) -> {
                    // A VEVENT with no DTSTART has no day to live on.
                    if (inEvent && block.keys.any { it.startsWith("DTSTART") }) out += toEvent(block)
                    block.clear()
                    inEvent = false
                }
                inEvent && line.contains(':') -> {
                    // The property name is case-insensitive; its parameters are not
                    // ("TZID=Europe/Berlin" is a zone id, not a slogan).
                    val left = line.substringBefore(':').trim()
                    val property = left.substringBefore(';').uppercase(Locale.US)
                    val params = left.substringAfter(';', "")
                    val key = if (params.isBlank()) property else "$property;$params"
                    val value = line.substringAfter(':')
                    // A repeated property (two ATTENDEEs, a second VALARM) keeps its first.
                    if (!block.contains(key)) block[key] = value
                }
            }
        }
        return out
    }

    private fun value(parts: Map<String, String>, name: String): String? =
        parts.entries.firstOrNull { it.key == name || it.key.startsWith("$name;") }?.value

    private fun leftSide(parts: Map<String, String>, name: String): String? =
        parts.keys.firstOrNull { it == name || it.startsWith("$name;") }

    /**
     * The event's start as a local date-time, plus whether it is all-day.
     *
     * A wall-clock time with a `TZID` (or a trailing `Z`) means the file's author is
     * telling us which clock to read it on; without one it is floating and is taken
     * as local, which is what every calendar does with a floating time.
     */
    private fun startOf(parts: Map<String, String>): Pair<LocalDateTime, Boolean>? {
        val left = leftSide(parts, "DTSTART") ?: return null
        val raw = parts[left]?.trim() ?: return null
        val allDay = "VALUE=DATE" in left.uppercase(Locale.US) || !raw.contains('T')
        val dateDigits = raw.substringBefore('T').filter { it.isDigit() }
        if (dateDigits.length < 8) return null
        val year = dateDigits.take(4).toIntOrNull() ?: return null
        val month = dateDigits.drop(4).take(2).toIntOrNull() ?: return null
        val day = dateDigits.drop(6).take(2).toIntOrNull() ?: return null
        if (month !in 1..12 || day !in 1..31) return null
        if (allDay) {
            val date = runCatching { LocalDate.of(year, month, day) }.getOrNull() ?: return null
            return date.atStartOfDay() to true
        }
        val timeDigits = raw.substringAfter('T', "").filter { it.isDigit() }.take(6)
        val hour = timeDigits.take(2).toIntOrNull() ?: 0
        val minute = timeDigits.drop(2).take(2).toIntOrNull() ?: 0
        val wall = runCatching {
            LocalDateTime.of(year, month, day, hour.coerceIn(0, 23), minute.coerceIn(0, 59))
        }.getOrNull() ?: return null
        val zone = when {
            raw.endsWith("Z", ignoreCase = true) -> ZoneOffset.UTC
            else -> Regex("TZID=([^;:]+)", RegexOption.IGNORE_CASE)
                .find(left)?.groupValues?.getOrNull(1)?.trim()?.replace("\"", "")
                ?.let { id -> runCatching { ZoneId.of(id) }.getOrNull() }
        } ?: return wall to false
        val instant = runCatching { wall.atZone(zone).toInstant() }.getOrNull() ?: return wall to false
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault()) to false
    }

    private fun toEvent(parts: Map<String, String>): OeaCalendarApp.Event {
        val start = startOf(parts)
        val local = start?.first ?: LocalDateTime.of(1970, 1, 1, 0, 0)
        val allDay = start?.second ?: false
        val time = if (allDay) "" else "%02d:%02d".format(local.hour, local.minute)
        val endTime = if (allDay) "" else {
            val endRaw = value(parts, "DTEND").orEmpty()
            val digits = endRaw.substringAfter('T', "").filter { it.isDigit() }.take(4)
            if (digits.length == 4) "${digits.take(2)}:${digits.drop(2)}" else ""
        }
        val rrule = value(parts, "RRULE").orEmpty().uppercase(Locale.US)
        val recurrence = when {
            "FREQ=DAILY" in rrule -> "DAILY"
            "FREQ=WEEKLY" in rrule -> "WEEKLY"
            "FREQ=MONTHLY" in rrule -> "MONTHLY"
            "FREQ=YEARLY" in rrule -> "YEARLY"
            else -> "NONE"
        }
        val trigger = value(parts, "TRIGGER").orEmpty()
        val minutes = Regex("-?PT(\\d+)M").find(trigger)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val uid = value(parts, "UID").orEmpty().filter { it.isDigit() }.toLongOrNull()
        return OeaCalendarApp.Event(
            id = OeaCalendarApp.freshEventId(seed = uid),
            year = local.year, month = local.monthValue - 1, day = local.dayOfMonth,
            title = unescape(value(parts, "SUMMARY").orEmpty()).ifBlank { "Event" },
            time = time, endTime = endTime,
            location = unescape(value(parts, "LOCATION").orEmpty()),
            desc = unescape(value(parts, "DESCRIPTION").orEmpty()),
            category = "OTHER", reminderMin = minutes.coerceAtLeast(0),
            recurrence = recurrence, allDay = allDay
        )
    }

    private fun unfold(text: String): String =
        text.replace("\r\n", "\n").replace("\r", "\n").replace(Regex("\\n[ \\t]"), "")

    private fun StringBuilder.line(raw: String) = append(fold(raw)).append('\n')

    private fun anchorOf(event: OeaCalendarApp.Event): LocalDate {
        val month = (event.month + 1).coerceIn(1, 12)
        val first = LocalDate.of(event.year, month, 1)
        return first.withDayOfMonth(event.day.coerceIn(1, first.lengthOfMonth()))
    }
}
