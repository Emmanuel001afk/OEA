package com.oea.launcher.power

import android.os.BatteryManager
import org.junit.Assert.assertEquals
import org.junit.Test

class OeaBatteryStateTest {
    @Test fun labelsChargingFullAndUncertainStatesHonestly() {
        assertEquals("75% · Charging", OeaBatteryState(75, BatteryManager.BATTERY_STATUS_CHARGING, true).text)
        assertEquals("100% · Full", OeaBatteryState(100, BatteryManager.BATTERY_STATUS_FULL, true).text)
        assertEquals("75% · Plugged in · discharging", OeaBatteryState(75, BatteryManager.BATTERY_STATUS_DISCHARGING, true).text)
        assertEquals("75% · Discharging", OeaBatteryState(75, BatteryManager.BATTERY_STATUS_DISCHARGING, false).text)
        assertEquals("— · Status unknown", OeaBatteryState(null, BatteryManager.BATTERY_STATUS_UNKNOWN, false).text)
        assertEquals("75% · Plugged in · not charging", OeaBatteryState(75, BatteryManager.BATTERY_STATUS_NOT_CHARGING, true).text)
    }
}
