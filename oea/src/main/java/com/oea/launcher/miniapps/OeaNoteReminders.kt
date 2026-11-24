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

/**
 * Reminders that actually ring.
 *
 * A note could carry a reminder time since the notes pass: the picker stored it,
 * the list showed it, the Reminders filter listed it — and nothing ever set an
 * alarm. A reminder that is a line of text is worse than no reminder, because the
 * phone is trusted to ring and does not.
 *
 * Each note with a future reminder gets one alarm, armed through `AlarmManager` so
 * it fires with the app closed and the phone asleep; inexact when Android has not
 * granted exact alarms, which can run a few minutes late — a note is not an alarm
 * clock, and OEA does not ask for that permission on its behalf. Which reminders
 * are armed, rung or dropped is decided by [OeaNoteReminderPlan], which is pure
 * and tested; this object only talks to Android.
 */
internal object OeaNoteReminders {

    private const val CHANNEL_ID = "oea_note_reminders"
    private const val PREFS = "oea_note_reminders"
    private const val KEY_CODES = "codes"
    private const val KEY_RUNG = "rung"
    private const val ACTION = "com.oea.launcher.action.NOTE_REMINDER"
    const val EXTRA_ID = "note_id"

    fun codeFor(noteId: Long): Int = ((noteId xor (noteId ushr 32)).toInt() and 0x7FFFFFFF)

    fun notificationId(noteId: Long): Int = 6000 + (codeFor(noteId) % 3000)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The `id|at` keys already rung, so two syncs never ring one note twice. */
    private fun rung(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_RUNG, emptySet()).orEmpty()

    /**
     * Puts every reminder in the state the clock says it should be in.
     *
     * Cheap enough to call on open, on save, on delete and after a reboot: it reads
     * the notes store's cached lists and touches `AlarmManager` only. Same shape as
     * the calendar's sync, so both behave identically when a reminder is set,
     * cleared, or the clock moves.
     */
    fun sync(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val now = System.currentTimeMillis()
        val notes = OeaNotesApp.reminderNotes(context)
        val byId = notes.associateBy { it.id }
        val plan = OeaNoteReminderPlan.plan(
            notes.map { OeaNoteReminderPlan.Entry(it.id, it.reminder) },
            now,
            rung(context)
        )

        // Alarms for cleared, trashed or ancient reminders are stale: cancel any code
        // that is no longer one of the armed ones.
        val wanted = plan.arm.associateBy { codeFor(it.id) }
        prefs(context).getStringSet(KEY_CODES, emptySet()).orEmpty().forEach { stored ->
            val code = stored.toIntOrNull() ?: return@forEach
            if (code !in wanted.keys) runCatching { alarms.cancel(pending(context, code, 0L)) }
        }

        wanted.forEach { (code, entry) ->
            runCatching {
                val pi = pending(context, code, entry.id)
                val exact = Build.VERSION.SDK_INT < 31 ||
                    runCatching { alarms.canScheduleExactAlarms() }.getOrDefault(false)
                if (exact) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, entry.at, pi)
                else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, entry.at, pi)
            }
        }

        // A reminder that came due while nothing was listening rings now, once.
        val rungNow = rung(context).toMutableSet()
        plan.ringNow.forEach { id ->
            val note = byId[id] ?: return@forEach
            notify(context, note)
            rungNow += OeaNoteReminderPlan.Entry(note.id, note.reminder).key()
        }

        // Keep the rung log to reminders that still exist, so it cannot grow for ever.
        val alive = notes.map { OeaNoteReminderPlan.Entry(it.id, it.reminder).key() }.toSet()
        prefs(context).edit()
            .putStringSet(KEY_CODES, wanted.keys.map { it.toString() }.toSet())
            .putStringSet(KEY_RUNG, rungNow.filter { it in alive }.toSet())
            .apply()
    }

    /**
     * The alarm for one note. [noteId] only travels in the extras — a PendingIntent
     * matches on action and component, so cancelling with a stale id still cancels
     * the right alarm.
     */
    private fun pending(context: Context, code: Int, noteId: Long): PendingIntent {
        val intent = Intent(context, OeaNoteReminderReceiver::class.java).apply {
            action = ACTION
            putExtra(EXTRA_ID, noteId)
        }
        return PendingIntent.getBroadcast(
            context, code, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Posts the reminder for [note]. */
    fun notify(context: Context, note: OeaNotesApp.Note) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Note reminders", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val title = note.title.ifBlank { note.text.lineSequence().firstOrNull().orEmpty() }
            .ifBlank { "Note reminder" }
        val body = note.text.lineSequence().drop(1).firstOrNull { it.isNotBlank() }?.take(120)
            ?: "Reminder from Notes"
        val tap = PendingIntent.getActivity(
            context, notificationId(note.id),
            OeaMiniApps.launchIntent(context, "notes"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        val notification = builder
            .setSmallIcon(R.drawable.ic_oea_notes)
            .setContentTitle(title)
            .setContentText(body)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        runCatching { manager.notify(notificationId(note.id), notification) }
    }
}

/** One alarm firing: post the reminder, then put the rest of the list back in order. */
class OeaNoteReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        com.oea.launcher.fault.OeaFaultShield.guard("notes:reminder") {
            val id = intent.getLongExtra(OeaNoteReminders.EXTRA_ID, 0L)
            if (id != 0L) {
                OeaNotesApp.reminderNotes(context).firstOrNull { it.id == id }
                    ?.let { OeaNoteReminders.notify(context, it) }
            }
            OeaNoteReminders.sync(context)
            true
        }
    }
}

/** Rebooting or moving the clock loses every pending alarm; re-arm them. */
class OeaNoteRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runCatching { OeaNoteReminders.sync(context) }
    }
}
