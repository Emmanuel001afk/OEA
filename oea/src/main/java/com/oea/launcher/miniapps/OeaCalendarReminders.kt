package com.oea.launcher.miniapps

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.oea.launcher.R
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * Reminders that actually ring.
 *
 * The editor has offered 5m/15m/30m/1h/1d since the first version, the store kept the
 * choice, the .ics export wrote a `VALARM` — and nothing in the app ever looked at it.
 * A reminder that is a label is worse than no reminder: it is a promise the calendar
 * breaks silently.
 *
 * Each event with a reminder gets one alarm, armed for `start − reminder`, through
 * `AlarmManager` so it fires with the app closed and the phone asleep. The alarm is
 * inexact when Android has not granted exact alarms (from Android 12 that is a
 * separate permission), which can run a few minutes late; the app never asks for the
 * exact-alarm permission on the calendar's behalf, because a calendar reminder is not
 * an alarm clock.
 */
internal object OeaCalendarReminders {

    private const val CHANNEL_ID = "oea_calendar_reminders"
    private const val PREFS = "oea_calendar_reminders"
    private const val KEY_CODES = "codes"
    private const val ACTION = "com.oea.launcher.action.CALENDAR_REMINDER"
    private const val EXTRA_CODE = "code"

    /** How far ahead alarms are armed; anything later is picked up by the next sync. */
    private const val HORIZON_MS = 45L * 24L * 60L * 60L * 1000L

    /** Android allows 500 alarms per app; a calendar does not need more than this. */
    private const val MAX_ALARMS = 50

    /** The stable alarm id for an event's reminders. */
    fun codeFor(eventId: Long): Int = ((eventId xor (eventId ushr 32)).toInt() and 0x7FFFFFFF)

    fun notificationId(eventId: Long): Int = 4000 + (codeFor(eventId) % 4000)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Re-arms every reminder from the store. Cheap enough to call on open, on save and
     * on resume — it reads the cached event list and touches `AlarmManager` only.
     */
    fun sync(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val now = ZonedDateTime.now()
        val nowMs = now.toInstant().toEpochMilli()
        val wanted = LinkedHashMap<Int, Long>()
        OeaCalendarApp.events(context).forEach { event ->
            if (event.reminderMin <= 0 || wanted.size >= MAX_ALARMS) return@forEach
            val start = OeaCalendarRecurrence.nextStart(event, now) ?: return@forEach
            val trigger = start.toInstant().toEpochMilli() - event.reminderMin * 60_000L
            if (trigger <= nowMs) return@forEach
            if (trigger - nowMs > HORIZON_MS) return@forEach
            wanted[codeFor(event.id)] = trigger
        }

        // Alarms for deleted events, or for events edited past the horizon, are stale.
        val previous = prefs(context).getStringSet(KEY_CODES, emptySet()).orEmpty()
        previous.forEach { stored ->
            val code = stored.toIntOrNull() ?: return@forEach
            if (code !in wanted.keys) runCatching { alarms.cancel(pending(context, code)) }
        }

        wanted.forEach { (code, at) ->
            runCatching {
                val pi = pending(context, code)
                val exact = Build.VERSION.SDK_INT < 31 ||
                    runCatching { alarms.canScheduleExactAlarms() }.getOrDefault(false)
                if (exact) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        }
        prefs(context).edit().putStringSet(KEY_CODES, wanted.keys.map { it.toString() }.toSet()).apply()
    }

    private fun pending(context: Context, code: Int): PendingIntent {
        val intent = Intent(context, OeaCalendarReminderReceiver::class.java).apply {
            action = ACTION
            putExtra(EXTRA_CODE, code)
        }
        return PendingIntent.getBroadcast(
            context, code, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Posts the reminder for [event]. The alarm itself carries no text, only the id. */
    fun notify(context: Context, event: OeaCalendarApp.Event) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Event reminders", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val whenText = if (event.allDay || event.time.isBlank()) "All day" else "Starts at ${event.time}"
        val body = listOf(whenText, event.location.takeIf { it.isNotBlank() })
            .filterNotNull().joinToString(" · ")
        val tap = PendingIntent.getActivity(
            context, notificationId(event.id),
            OeaMiniApps.launchIntent(context, "calendar"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        val notification = builder
            .setSmallIcon(R.drawable.ic_oea_calendar)
            .setContentTitle(event.title)
            .setContentText(body)
            .setCategory(Notification.CATEGORY_EVENT)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        runCatching { manager.notify(notificationId(event.id), notification) }
    }

    /** The text a reminder would show today, for the Calendar's own preview rows. */
    fun describe(event: OeaCalendarApp.Event, date: LocalDate? = null): String {
        val whenText = if (event.allDay || event.time.isBlank()) "All day" else "Starts at ${event.time}"
        val prefix = date?.let { "$it · " }.orEmpty()
        return prefix + whenText + if (event.reminderMin > 0) " · reminder ${label(event.reminderMin)}" else ""
    }

    fun label(minutes: Int): String = when {
        minutes <= 0 -> "None"
        minutes < 60 -> "${minutes}m"
        minutes % 60 == 0 -> "${minutes / 60}h"
        else -> "${minutes / 60}h ${minutes % 60}m"
    }
}

/** One alarm firing: post the reminder, then re-arm whatever comes next. */
class OeaCalendarReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = intent.getIntExtra("code", 0)
        val event = OeaCalendarApp.events(context)
            .firstOrNull { OeaCalendarReminders.codeFor(it.id) == code }
        if (event != null) OeaCalendarReminders.notify(context, event)
        // A repeating event has another appearance to arm; a one-off now has none.
        runCatching { OeaCalendarReminders.sync(context) }
    }
}

/** Rebooting or changing the clock loses every pending alarm; re-arm them. */
class OeaCalendarRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runCatching { OeaCalendarReminders.sync(context) }
    }
}
