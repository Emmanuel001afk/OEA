package com.oea.launcher.gameboost

import org.junit.Assert.assertEquals
import org.junit.Test

class OeaTrafficRatesTest {
    @Test fun ratesUseTheSelectedUidDeltasAndReset() {
        val r = OeaTrafficRates()
        r.sample(1000, 5000, 4000)
        assertEquals("—", r.down)
        r.sample(3000, 25000, 8000)
        assertEquals("10 KB/s", r.down)
        assertEquals("2 KB/s", r.up)
        r.sample(3500, 30000, 10000) // retain the last full interval
        assertEquals("10 KB/s", r.down)
        r.sample(4000, -1, -1) // unsupported: don't use device-wide totals
        assertEquals("—", r.down)
        assertEquals("—", r.up)
        r.sample(5000, 100, 200)
        assertEquals("—", r.down)
        r.sample(6000, 2000100, 200)
        assertEquals("2.0 MB/s", r.down)
        assertEquals("0 KB/s", r.up)
        r.sample(7000, 0, 0) // counter reset
        assertEquals("—", r.down)
        r.reset() // switching games
        r.sample(8000, 5000, 5000)
        assertEquals("—", r.down)
    }
}
