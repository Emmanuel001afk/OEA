package com.oea.launcher.power

import org.junit.Assert.assertEquals
import org.junit.Test

class OeaWaveDetectorTest {
    private fun detector(sensitivity: Int = 1, twoWaves: Boolean = false, pocket: Boolean = true) =
        OeaWaveDetector(sensitivity, twoWaves, pocket).apply { reset(0L, coveredNow = false) }

    private val none = OeaWaveDetector.Result.NONE
    private val on = OeaWaveDetector.Result.ON

    @Test fun quickCoverAndUncoverWakes() {
        val d = detector()
        assertEquals(none, d.onReading(true, 1_000L))
        assertEquals(on, d.onReading(false, 1_300L))
    }

    @Test fun cooldownPreventsRepeatedWakes() {
        val d = detector()
        d.onReading(true, 1_000L)
        assertEquals(on, d.onReading(false, 1_300L))
        d.onReading(true, 1_500L)
        assertEquals(none, d.onReading(false, 1_700L))
        d.onReading(true, 5_000L)
        assertEquals(on, d.onReading(false, 5_200L))
    }

    @Test fun longCoverIsPocketNotWave() {
        val d = detector()
        d.onReading(true, 100L)
        assertEquals(none, d.onReading(false, 2_500L))
    }

    @Test fun sensorNoiseIsIgnored() {
        val d = detector()
        d.onReading(true, 3_000L)
        assertEquals(none, d.onReading(false, 3_010L))
    }

    @Test fun firstUncoverAfterStartingInPocketIsIgnored() {
        val d = OeaWaveDetector(2, requireTwoWaves = false, pocketGuard = true)
        d.reset(0L, coveredNow = true)
        assertEquals(none, d.onReading(false, 400L))
        d.onReading(true, 1_000L)
        assertEquals(on, d.onReading(false, 1_300L))
    }

    @Test fun twoWaveModeNeedsASecondWaveInTime() {
        val d = detector(twoWaves = true)
        d.onReading(true, 100L)
        assertEquals(none, d.onReading(false, 300L))
        d.onReading(true, 600L)
        assertEquals(on, d.onReading(false, 800L))

        val late = detector(twoWaves = true)
        late.onReading(true, 100L)
        assertEquals(none, late.onReading(false, 300L))
        late.onReading(true, 3_000L)
        assertEquals(none, late.onReading(false, 3_200L))
    }

    @Test fun lowSensitivityRejectsSlowWaves() {
        val d = detector(sensitivity = 0)
        d.onReading(true, 100L)
        assertEquals(none, d.onReading(false, 700L))
        d.onReading(true, 1_000L)
        assertEquals(on, d.onReading(false, 1_300L))
    }
}
