package com.oea.launcher.power

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OeaWaveDetectorTest {
    private fun detector(sensitivity: Int = 1, twoWaves: Boolean = false, pocket: Boolean = true) =
        OeaWaveDetector(sensitivity, twoWaves, pocket).apply { reset(0L, coveredNow = false) }

    @Test fun quickCoverAndUncoverWakes() {
        val d = detector()
        assertFalse(d.onReading(true, 1_000L))
        assertTrue(d.onReading(false, 1_300L))
    }

    @Test fun cooldownPreventsRepeatedWakes() {
        val d = detector()
        d.onReading(true, 1_000L)
        assertTrue(d.onReading(false, 1_300L))
        d.onReading(true, 1_500L)
        assertFalse(d.onReading(false, 1_700L))
        d.onReading(true, 5_000L)
        assertTrue(d.onReading(false, 5_200L))
    }

    @Test fun longCoverIsPocketNotWave() {
        val d = detector()
        d.onReading(true, 100L)
        assertFalse(d.onReading(false, 2_500L))
    }

    @Test fun sensorNoiseIsIgnored() {
        val d = detector()
        d.onReading(true, 3_000L)
        assertFalse(d.onReading(false, 3_010L))
    }

    @Test fun firstUncoverAfterStartingInPocketIsIgnored() {
        val d = OeaWaveDetector(2, requireTwoWaves = false, pocketGuard = true)
        d.reset(0L, coveredNow = true)
        assertFalse(d.onReading(false, 400L))
        d.onReading(true, 1_000L)
        assertTrue(d.onReading(false, 1_300L))
    }

    @Test fun twoWaveModeNeedsASecondWaveInTime() {
        val d = detector(twoWaves = true)
        d.onReading(true, 100L)
        assertFalse(d.onReading(false, 300L))
        d.onReading(true, 600L)
        assertTrue(d.onReading(false, 800L))

        val late = detector(twoWaves = true)
        late.onReading(true, 100L)
        late.onReading(false, 300L)
        late.onReading(true, 3_000L)
        assertFalse(late.onReading(false, 3_200L))
    }

    @Test fun lowSensitivityRejectsSlowWaves() {
        val d = detector(sensitivity = 0)
        d.onReading(true, 100L)
        assertFalse(d.onReading(false, 700L))
        d.onReading(true, 1_000L)
        assertTrue(d.onReading(false, 1_300L))
    }
}
