package com.oea.launcher.power

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OeaCoverLockDetectorTest {
    @Test fun coverLockIsDisabledInLandscapeAndDuringCalls() {
        assertFalse(OeaCoverLockPolicy.isAllowed(screenOn = true, landscape = true, enabled = true, inCall = false))
        assertFalse(OeaCoverLockPolicy.isAllowed(screenOn = true, landscape = false, enabled = true, inCall = true))
        assertFalse(OeaCoverLockPolicy.isAllowed(screenOn = false, landscape = false, enabled = true, inCall = false))
        assertFalse(OeaCoverLockPolicy.isAllowed(screenOn = true, landscape = false, enabled = false, inCall = false))
        assertTrue(OeaCoverLockPolicy.isAllowed(screenOn = true, landscape = false, enabled = true, inCall = false))
    }

    @Test fun alreadyCoveredAtStartupIsIgnoredUntilClearThenNewCover() {
        val detector = OeaCoverLockDetector(holdMs = 1_000L)
        assertFalse(detector.onReading(covered = true, nowMs = 100L))
        assertFalse(detector.isArmed)
        assertFalse(detector.onReading(covered = false, nowMs = 400L))
        assertTrue(detector.onReading(covered = true, nowMs = 700L))
        assertTrue(detector.shouldLock(1_700L))
    }

    @Test fun lockRequiresFullHoldAndTriggersOnlyOncePerCover() {
        val detector = OeaCoverLockDetector(holdMs = 1_000L)
        detector.onReading(covered = false, nowMs = 0L)
        detector.onReading(covered = true, nowMs = 100L)

        assertFalse(detector.shouldLock(1_099L))
        assertTrue(detector.shouldLock(1_100L))
        assertFalse(detector.shouldLock(2_000L))
    }

    @Test fun uncoverBeforeTimeoutCancelsTheGestureAndAllowsAnother() {
        val detector = OeaCoverLockDetector(holdMs = 1_000L)
        detector.onReading(covered = false, nowMs = 0L)
        detector.onReading(covered = true, nowMs = 100L)
        detector.onReading(covered = false, nowMs = 500L)
        assertFalse(detector.shouldLock(1_500L))

        assertTrue(detector.onReading(covered = true, nowMs = 2_000L))
        assertTrue(detector.shouldLock(3_000L))
    }
}
