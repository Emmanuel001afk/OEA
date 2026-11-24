package com.oea.launcher.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic and the wording behind every battery figure OEA shows.
 *
 * These are the cases that used to be guesses on screen: a device that reports no
 * scale, a phone that cannot answer `BATTERY_PROPERTY_CAPACITY`, a truncated
 * percentage, and a full or charge-limited battery pretending to be "charging".
 */
class OeaBatteryMathTest {

    // ---------- the percentage ----------

    @Test
    fun levelAndScaleGiveTheWholePercentage() {
        assertEquals(85, OeaBatteryMath.percent(85, 100))
        assertEquals(100, OeaBatteryMath.percent(100, 100))
        assertEquals(0, OeaBatteryMath.percent(0, 100))
    }

    @Test
    fun roundsInsteadOfTruncating() {
        // 2/3 of a charge is 66.7%, not the 66% that integer division printed.
        assertEquals(67, OeaBatteryMath.percent(2, 3))
        // A sample device reports 995/1000; that is 100%, not 99%.
        assertEquals(100, OeaBatteryMath.percent(995, 1000))
        assertEquals(33, OeaBatteryMath.percent(1, 3))
    }

    @Test
    fun brokenScaleIsUnknownNotZero() {
        // The old code defaulted the scale to 100, so a missing scale turned any
        // level into a plausible-looking lie.
        assertNull(OeaBatteryMath.percent(50, 0))
        assertNull(OeaBatteryMath.percent(50, -1))
        assertNull(OeaBatteryMath.percent(-1, 100))
        assertNull(OeaBatteryMath.percent(Int.MIN_VALUE, Int.MIN_VALUE))
    }

    @Test
    fun levelAboveScaleIsClamped() {
        assertEquals(100, OeaBatteryMath.percent(150, 100))
    }

    @Test
    fun capacityPropertySentinelIsUnknown() {
        assertEquals(87, OeaBatteryMath.percentProperty(87))
        assertEquals(0, OeaBatteryMath.percentProperty(0))
        assertNull(OeaBatteryMath.percentProperty(Int.MIN_VALUE))
        assertNull(OeaBatteryMath.percentProperty(101))
        assertNull(OeaBatteryMath.percentProperty(-1))
    }

    // ---------- the charging state ----------

    @Test
    fun onlyRealStatusesSurviveTheProperty() {
        assertEquals(OeaBatteryMath.STATUS_CHARGING, OeaBatteryMath.statusProperty(2))
        assertEquals(OeaBatteryMath.STATUS_FULL, OeaBatteryMath.statusProperty(5))
        assertNull(OeaBatteryMath.statusProperty(Int.MIN_VALUE))
        assertNull(OeaBatteryMath.statusProperty(42))
    }

    @Test
    fun chargingIncludesFullButNotEveryKindOfPluggedIn() {
        assertTrue(OeaBatteryMath.isCharging(OeaBatteryMath.STATUS_CHARGING))
        assertTrue(OeaBatteryMath.isCharging(OeaBatteryMath.STATUS_FULL))
        assertFalse(OeaBatteryMath.isCharging(OeaBatteryMath.STATUS_DISCHARGING))
        assertFalse(OeaBatteryMath.isCharging(OeaBatteryMath.STATUS_NOT_CHARGING))
        assertFalse(OeaBatteryMath.isCharging(OeaBatteryMath.STATUS_UNKNOWN))
    }

    @Test
    fun aLimitedBatteryIsPluggedInButNotCharging() {
        // A phone sitting at its charge limit: the cable is in, the battery is not
        // moving. The old code called this "CHARGING" in the in-game strip.
        assertEquals("Plugged in, not charging", OeaBatteryMath.description(OeaBatteryMath.STATUS_NOT_CHARGING, true))
        assertEquals("Not charging", OeaBatteryMath.description(OeaBatteryMath.STATUS_NOT_CHARGING, false))
        assertEquals("Plugged in, discharging", OeaBatteryMath.description(OeaBatteryMath.STATUS_DISCHARGING, true))
        assertEquals("Discharging", OeaBatteryMath.description(OeaBatteryMath.STATUS_DISCHARGING, false))
    }

    @Test
    fun chargingAndFullAndUnknownReadCorrectly() {
        assertEquals("Charging", OeaBatteryMath.description(OeaBatteryMath.STATUS_CHARGING, true))
        assertEquals("Full", OeaBatteryMath.description(OeaBatteryMath.STATUS_FULL, true))
        assertEquals("Status unknown", OeaBatteryMath.description(OeaBatteryMath.STATUS_UNKNOWN, false))
        assertEquals("Plugged in, status unknown", OeaBatteryMath.description(OeaBatteryMath.STATUS_UNKNOWN, true))
    }

    // ---------- the words on screen ----------

    @Test
    fun unknownIsAnEmDashNeverZero() {
        assertEquals("—", OeaBatteryMath.label(null))
        assertEquals("85%", OeaBatteryMath.label(85))
        assertEquals("0%", OeaBatteryMath.label(0))
    }

    @Test
    fun shortLabelAddsTheNoteOnlyWhenThereIsOne() {
        assertEquals("85%", OeaBatteryMath.shortLabel(85, OeaBatteryMath.STATUS_DISCHARGING, false))
        assertEquals("85% · Charging", OeaBatteryMath.shortLabel(85, OeaBatteryMath.STATUS_CHARGING, true))
        assertEquals("100% · Full", OeaBatteryMath.shortLabel(100, OeaBatteryMath.STATUS_FULL, true))
        assertEquals(
            "80% · Plugged in, not charging",
            OeaBatteryMath.shortLabel(80, OeaBatteryMath.STATUS_NOT_CHARGING, true)
        )
        // An unknown level stays unknown on the cable instead of gaining a percent.
        assertEquals("—", OeaBatteryMath.shortLabel(null, OeaBatteryMath.STATUS_CHARGING, true))
    }

    @Test
    fun hudCaptionSaysPluggedWhenItIsNotCharging() {
        assertEquals("CHARGING", OeaBatteryMath.hudCaption(OeaBatteryMath.STATUS_CHARGING, true))
        assertEquals("CHARGING", OeaBatteryMath.hudCaption(OeaBatteryMath.STATUS_FULL, true))
        assertEquals("PLUGGED", OeaBatteryMath.hudCaption(OeaBatteryMath.STATUS_NOT_CHARGING, true))
        assertEquals("PLUGGED", OeaBatteryMath.hudCaption(OeaBatteryMath.STATUS_UNKNOWN, true))
        assertEquals("BATTERY", OeaBatteryMath.hudCaption(OeaBatteryMath.STATUS_DISCHARGING, false))
        // Plugged stays false when the phone says it is on battery — no invented cable.
        assertEquals("BATTERY", OeaBatteryMath.hudCaption(OeaBatteryMath.STATUS_UNKNOWN, false))
    }

    // ---------- the reading as a whole ----------

    @Test
    fun anUnknownReadingSaysSoAndClaimsNoCharge() {
        val unknown = OeaBatteryState.UNKNOWN
        assertNull(unknown.percent)
        assertTrue(unknown.isUnknown)
        assertEquals("—", unknown.label)
        assertFalse(unknown.isCharging)
        assertEquals("— · Status unknown", unknown.text)
    }

    @Test
    fun aReadingCarriesItsOwnChargingState() {
        val charging = OeaBatteryState(64, OeaBatteryMath.STATUS_CHARGING, true, 31.5f)
        assertTrue(charging.isCharging)
        assertEquals("64% · Charging", charging.shortLabel)
        assertEquals("64% · Charging", charging.text)
        assertEquals(31.5f, charging.temperatureCelsius!!, 0.001f)

        val full = OeaBatteryState(100, OeaBatteryMath.STATUS_FULL, true, null)
        assertTrue(full.isCharging)
        assertTrue(full.isFull)
        assertEquals("100% · Full", full.shortLabel)
    }

    @Test
    fun anUnreadableLevelOnTheCableIsStillHonest() {
        // The level is missing but the cable is not: say the cable, do not invent 0%.
        val noLevel = OeaBatteryState(null, OeaBatteryMath.STATUS_CHARGING, true, null)
        assertEquals("—", noLevel.label)
        assertEquals("Charging", noLevel.chargingNote)
        assertFalse(noLevel.isUnknown)
    }

    @Test
    fun oddScalesStillGiveAWholePercentage() {
        // Devices report in 255ths (older kernels) as well as 100ths.
        assertEquals(50, OeaBatteryMath.percent(128, 255))
        assertEquals(1, OeaBatteryMath.percent(1, 100))
        assertNull(OeaBatteryMath.percent(1, 0))
    }

    @Test
    fun noteIsSilentWhileDraining() {
        assertNull(OeaBatteryMath.note(OeaBatteryMath.STATUS_DISCHARGING, false))
        assertNull(OeaBatteryMath.note(OeaBatteryMath.STATUS_UNKNOWN, false))
        assertEquals(
            "Plugged in, not charging",
            OeaBatteryMath.note(OeaBatteryMath.STATUS_NOT_CHARGING, true)
        )
    }

    @Test
    fun aCableIsNeverInvented() {
        // Status "not charging" with no plug reported: say "Not charging", do not
        // claim a cable the phone says is not there.
        val state = OeaBatteryState(42, OeaBatteryMath.STATUS_NOT_CHARGING, false, null)
        assertFalse(state.isCharging)
        assertEquals("Not charging", state.chargingNote)
        assertEquals("42% · Not charging", state.shortLabel)
        assertEquals("BATTERY", OeaBatteryMath.hudCaption(state.status, state.plugged))
    }
}
