package com.oea.launcher.miniapps

/**
 * Which note reminders deserve an alarm, which one to ring now, and which are
 * history — the part of [OeaNoteReminders] that can be tested without Android.
 *
 * The three answers exist because a note reminder is not a calendar event:
 *
 *  * a time still ahead is **armed** with `AlarmManager`, so it rings with the app
 *    closed and the phone asleep;
 *  * a time that passed while the phone was off is **rung once**, on the next
 *    sync, but only if it is still recent — waking up to eleven alarms from last
 *    week is not a reminder, it is an ambush;
 *  * anything older than the grace period is **dropped**: the note keeps its time
 *    in its own page, but the phone does not ring about it.
 */
internal object OeaNoteReminderPlan {

    /** How late a reminder may ring: one day. Past that it is dropped silently. */
    const val GRACE_MS: Long = 24L * 60L * 60L * 1000L

    /** Android allows 500 alarms an app; a notes app does not need more than this. */
    const val MAX_ALARMS: Int = 50

    data class Entry(val id: Long, val at: Long) {
        /** Identity of one reminder moment, for the "already rung" log. */
        fun key(): String = "$id|$at"
    }

    data class Plan(
        /** Reminders to arm at [Entry.at]. */
        val arm: List<Entry> = emptyList(),
        /** Reminders that came due while nothing was listening, and should ring now. */
        val ringNow: List<Long> = emptyList(),
        /** Reminders too old to ring about; their alarms (if any) must be cancelled. */
        val drop: List<Long> = emptyList()
    )

    /** What to do with one reminder at [at] when the clock says [now]. */
    fun action(at: Long, now: Long): Action = when {
        at <= 0L -> Action.DROP
        at > now -> Action.ARM
        now - at <= GRACE_MS -> Action.RING_NOW
        else -> Action.DROP
    }

    enum class Action { ARM, RING_NOW, DROP }

    /**
     * Sorts a whole list of reminders into the three answers.
     *
     * [alreadyRung] holds the `id|at` keys that have already been rung, so a sync
     * that runs twice (opening Notes, then a boot broadcast) does not ring the same
     * note twice. The cap applies to armed alarms only: a note beyond it keeps its
     * time and is picked up by a later sync as the earlier ones fire.
     */
    fun plan(entries: List<Entry>, now: Long, alreadyRung: Set<String> = emptySet()): Plan {
        val arm = ArrayList<Entry>()
        val ringNow = ArrayList<Long>()
        val drop = ArrayList<Long>()
        val seen = HashSet<Long>()
        for (entry in entries) {
            if (!seen.add(entry.id)) continue
            when (action(entry.at, now)) {
                Action.ARM -> if (arm.size < MAX_ALARMS) arm.add(entry)
                Action.RING_NOW -> if (entry.key() !in alreadyRung) ringNow.add(entry.id)
                Action.DROP -> drop.add(entry.id)
            }
        }
        return Plan(arm = arm, ringNow = ringNow, drop = drop)
    }
}
