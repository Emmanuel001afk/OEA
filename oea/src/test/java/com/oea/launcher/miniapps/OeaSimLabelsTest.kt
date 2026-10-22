package com.oea.launcher.miniapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OeaSimLabelsTest {

    @Test fun shortLabelLeadsWithTheSlotAndTheSimsOwnName() {
        assertEquals("SIM 1 • MTN Nigeria", OeaSimLabels.short(1, "MTN Nigeria", "MTN"))
        assertEquals("SIM 2 • Airtel", OeaSimLabels.short(2, "", "Airtel"))
        assertEquals("SIM 1 • Work line", OeaSimLabels.short(1, "Work line", "Glo"))
    }

    @Test fun shortLabelDegradesHonestlyWhenNothingIsKnown() {
        assertEquals("SIM", OeaSimLabels.short(-1, "", ""))
        assertEquals("SIM 2", OeaSimLabels.short(2, " ", " "))
    }

    @Test fun networkLineOnlyAppearsWhenItAddsInformation() {
        assertEquals("MTN Nigeria", OeaSimLabels.network("Work line", "MTN Nigeria"))
        assertNull(OeaSimLabels.network("MTN", "MTN"))
        assertNull(OeaSimLabels.network("mtn nigeria", "MTN Nigeria"))
        assertNull(OeaSimLabels.network("Work line", ""))
    }
}
