package com.oea.launcher.gameboost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules behind "the game gets the panel's settings, and the phone gets
 * itself back".
 *
 * These are the cases that left a phone silent after the game was closed: a
 * baseline nobody wrote down, a session that died holding Do Not Disturb, and
 * a restore that treated "we do not know what it was" as "leave it muted".
 */
class OeaGameBoostSessionStateTest {

    private val all = 1 // NotificationManager.INTERRUPTION_FILTER_ALL
    private val priority = 3 // NotificationManager.INTERRUPTION_FILTER_PRIORITY
    private val none = 2 // NotificationManager.INTERRUPTION_FILTER_NONE
    private val alarms = 4 // NotificationManager.INTERRUPTION_FILTER_ALARMS

    // ------------------------------------------------------------- baseline

    @Test fun theRealFilterIsRecordedWhenThePhoneGrantsAccess() {
        assertEquals(all, OeaGameBoostSessionState.baselineFor(all, hasAccess = true))
        assertEquals(priority, OeaGameBoostSessionState.baselineFor(priority, hasAccess = true))
    }

    @Test fun anUnreadableFilterIsRecordedAsUnknownNeverAsAGuess() {
        // Without notification policy access the filter cannot be read. Writing
        // down a guess would later "restore" the phone to a mode the user never
        // chose, which is worse than admitting nothing is known.
        assertEquals(
            OeaGameBoostSessionState.FILTER_UNKNOWN,
            OeaGameBoostSessionState.baselineFor(all, hasAccess = false)
        )
    }

    // -------------------------------------------------------------- applying

    @Test fun gamingWantsPriorityWhenTheSwitchIsOnAndEverythingWhenItIsOff() {
        assertEquals(priority, OeaGameBoostSessionState.wantedFilter(dndEnabled = true))
        assertEquals(all, OeaGameBoostSessionState.wantedFilter(dndEnabled = false))
    }

    @Test fun switchingDndOffMidGameIsEnforcedNotSkipped() {
        // The phone is silenced and the panel switch goes off: the panel must
        // unsilence it, or the switch is lying.
        assertTrue(OeaGameBoostSessionState.shouldApply(priority, OeaGameBoostSessionState.wantedFilter(false)))
    }

    @Test fun aFilterAlreadyWhereThePanelWantsItIsLeftAlone() {
        assertFalse(OeaGameBoostSessionState.shouldApply(priority, priority))
        assertFalse(OeaGameBoostSessionState.shouldApply(all, all))
    }

    // ------------------------------------------------------------- restoring

    @Test fun restoresTheExactModeThePhoneWasInBeforeTheGame() {
        assertEquals(alarms, OeaGameBoostSessionState.restoreTarget(alarms))
        assertEquals(none, OeaGameBoostSessionState.restoreTarget(none))
        assertEquals(all, OeaGameBoostSessionState.restoreTarget(all))
    }

    @Test fun aLostBaselineRestoresEverythingNeverSilence() {
        // The whole point of the fix: a session that muted the phone but lost
        // the record of what it was must not leave the phone muted.
        assertEquals(all, OeaGameBoostSessionState.restoreTarget(OeaGameBoostSessionState.FILTER_UNKNOWN))
    }

    @Test fun aSessionThatChangedNothingOwesNothing() {
        // The user silenced the phone by hand before the game; the panel asked
        // for priority, which is where it already was. Closing the game must
        // leave that manual silence exactly as it is.
        assertFalse(OeaGameBoostSessionState.owesRestore(applied = false, baseline = priority, current = priority))
    }

    @Test fun aSessionThatMutedThePhoneOwesItBack() {
        assertTrue(OeaGameBoostSessionState.owesRestore(applied = true, baseline = all, current = priority))
    }

    @Test fun restoringToAModeThePhoneIsAlreadyInIsNotADebt() {
        // The user put the phone back themselves during the game: nothing to do.
        assertFalse(OeaGameBoostSessionState.owesRestore(applied = true, baseline = all, current = all))
    }

    @Test fun anUnknownBaselineIsStillOwedWhenThePhoneIsSilenced() {
        assertTrue(OeaGameBoostSessionState.owesRestore(applied = true, baseline = OeaGameBoostSessionState.FILTER_UNKNOWN, current = priority))
    }
}
