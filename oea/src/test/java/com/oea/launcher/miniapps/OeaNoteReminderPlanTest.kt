package com.oea.launcher.miniapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules behind a note reminder: when to arm an alarm, when to ring at once, and
 * when to stay quiet.
 */
class OeaNoteReminderPlanTest {

    private val hour = 60L * 60L * 1000L
    private val now = 1_700_000_000_000L

    private fun entry(id: Long, at: Long) = OeaNoteReminderPlan.Entry(id, at)

    @Test
    fun aFutureReminderIsArmed() {
        val plan = OeaNoteReminderPlan.plan(listOf(entry(1, now + hour)), now)
        assertEquals(listOf(entry(1, now + hour)), plan.arm)
        assertTrue(plan.ringNow.isEmpty())
        assertTrue(plan.drop.isEmpty())
    }

    @Test
    fun aReminderMissedTodayRingsOnce() {
        // The phone was off at the time; the user still wants to know.
        val plan = OeaNoteReminderPlan.plan(listOf(entry(7, now - hour)), now)
        assertEquals(listOf(7L), plan.ringNow)
        assertTrue(plan.arm.isEmpty())
    }

    @Test
    fun aWeekOldReminderIsDroppedNotRung() {
        val plan = OeaNoteReminderPlan.plan(listOf(entry(9, now - 7 * 24 * hour)), now)
        assertEquals(listOf(9L), plan.drop)
        assertTrue(plan.ringNow.isEmpty())
    }

    @Test
    fun theGracePeriodEndsExactlyAtADay() {
        assertEquals(OeaNoteReminderPlan.Action.RING_NOW, OeaNoteReminderPlan.action(now - hour * 24, now))
        assertEquals(OeaNoteReminderPlan.Action.DROP, OeaNoteReminderPlan.action(now - hour * 24 - 1, now))
    }

    @Test
    fun noReminderMeansNothingToDo() {
        assertEquals(OeaNoteReminderPlan.Action.DROP, OeaNoteReminderPlan.action(0L, now))
        assertEquals(OeaNoteReminderPlan.Action.DROP, OeaNoteReminderPlan.action(-5L, now))
    }

    @Test
    fun anAlreadyRungReminderDoesNotRingAgain() {
        val key = entry(3, now - hour).key()
        val plan = OeaNoteReminderPlan.plan(listOf(entry(3, now - hour)), now, setOf(key))
        assertTrue(plan.ringNow.isEmpty())
        // Not armed either — it is already due, so there is nothing ahead to wait for.
        assertTrue(plan.arm.isEmpty())
    }

    @Test
    fun theSameNoteAppearsOnce() {
        val plan = OeaNoteReminderPlan.plan(
            listOf(entry(4, now + hour), entry(4, now + 2 * hour)),
            now
        )
        assertEquals(1, plan.arm.size)
    }

    @Test
    fun armedAlarmsAreCappedButNothingIsLostSilently() {
        val many = (1L..120L).map { entry(it, now + it * 60_000L) }
        val plan = OeaNoteReminderPlan.plan(many, now)
        assertEquals(OeaNoteReminderPlan.MAX_ALARMS, plan.arm.size)
        // The rest are neither rung nor dropped: a later sync picks them up.
        assertTrue(plan.ringNow.isEmpty())
        assertTrue(plan.drop.isEmpty())
    }

    @Test
    fun aMixedListIsSortedIntoAllThreeAnswers() {
        val plan = OeaNoteReminderPlan.plan(
            listOf(
                entry(1, now + hour),
                entry(2, now - hour),
                entry(3, now - 30 * 24 * hour),
                entry(4, 0L),
                entry(5, now + 5 * hour)
            ),
            now
        )
        assertEquals(listOf(1L, 5L), plan.arm.map { it.id })
        assertEquals(listOf(2L), plan.ringNow)
        assertEquals(listOf(3L, 4L), plan.drop)
    }

    @Test
    fun theRungKeyIsStableForTheSameTimeAndDiffersOtherwise() {
        val a = OeaNoteReminderPlan.Entry(11, now)
        val b = OeaNoteReminderPlan.Entry(11, now)
        val c = OeaNoteReminderPlan.Entry(11, now + 1)
        assertEquals(a.key(), b.key())
        assertFalse(a.key() == c.key())
    }
}
