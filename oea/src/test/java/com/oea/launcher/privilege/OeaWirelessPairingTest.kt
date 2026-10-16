package com.oea.launcher.privilege

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class OeaWirelessPairingTest {

    @Test fun pairingDialogGivesCodeAndPairingAddress() {
        // The dialog is its own window: no "Wireless debugging" title in it.
        val changed = OeaWirelessPairing.read(
            listOf("Pair device with pairing code", "Wi-Fi pairing code", "123 456", "IP address & Port", "192.168.1.20:37125"),
            now = 1_000L
        )
        assertTrue(changed)
        val p = OeaWirelessPairing.pairing
        assertNotNull(p)
        assertEquals("123456", p!!.code)
        assertEquals("192.168.1.20", p.host)
        assertEquals(37125, p.port)
    }

    @Test fun mainScreenGivesConnectionPortNotPairingCode() {
        // The main Wireless debugging screen has the "Pair device with pairing code" row,
        // which must not be mistaken for an open dialog.
        OeaWirelessPairing.read(
            listOf("Wireless debugging", "Pair device with pairing code", "IP address & Port", "192.168.1.20:41235"),
            now = 2_000L
        )
        val c = OeaWirelessPairing.connection
        assertNotNull(c)
        assertEquals(41235, c!!.port)
        assertEquals("192.168.1.20", c.host)
    }

    @Test fun unrelatedTextChangesNothing() {
        assertFalse(OeaWirelessPairing.read(listOf("Bluetooth", "Storage", "42"), now = 3_000L))
    }

    @Test fun codeMustBeSixDigits() {
        assertNull(Regex("""^\s*(\d{3})\s?(\d{3})\s*$""").matchEntire("12345")?.value)
        assertNotNull(Regex("""^\s*(\d{3})\s?(\d{3})\s*$""").matchEntire("123 456")?.value)
    }
}
