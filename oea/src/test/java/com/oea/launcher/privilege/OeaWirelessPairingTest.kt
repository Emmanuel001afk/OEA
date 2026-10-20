package com.oea.launcher.privilege

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class OeaWirelessPairingTest {
    @Test fun pairingDialogGivesCodeAndPairingAddress() {
        assertTrue(OeaWirelessPairing.read(listOf("Pair device with pairing code", "Wi-Fi pairing code", "123 456", "IP address & Port", "192.168.1.20:37125"), 1_000L))
        val p = OeaWirelessPairing.pairing
        assertNotNull(p); assertEquals("123456", p!!.code); assertEquals("192.168.1.20", p.host); assertEquals(37125, p.port)
    }
    @Test fun mainScreenGivesConnectionPortNotPairingCode() {
        OeaWirelessPairing.read(listOf("Wireless debugging", "Pair device with pairing code", "IP address & Port", "192.168.1.20:41235"), 2_000L)
        val c = OeaWirelessPairing.connection
        assertNotNull(c); assertEquals(41235, c!!.port); assertEquals("192.168.1.20", c.host)
    }
    @Test fun unrelatedTextChangesNothing() { assertFalse(OeaWirelessPairing.read(listOf("Bluetooth", "Storage", "42"), 3_000L)) }
    @Test fun codeMustBeSixDigits() {
        assertNull(Regex("""^\s*(\d{3})\s?(\d{3})\s*$""").matchEntire("12345")?.value)
        assertNotNull(Regex("""^\s*(\d{3})\s?(\d{3})\s*$""").matchEntire("123 456")?.value)
    }
    @Test fun sameWindowDialogSplitsPairingAndConnectionPorts() {
        val entries = listOf(
            OeaWirelessPairing.Entry("Wireless debugging", 0, 60),
            OeaWirelessPairing.Entry("IP address and port", 300, 340),
            OeaWirelessPairing.Entry("10.213.122.13:34531", 340, 380),
            OeaWirelessPairing.Entry("Pair with device", 900, 940),
            OeaWirelessPairing.Entry("Wi-Fi pairing code", 940, 970),
            OeaWirelessPairing.Entry("955 806", 970, 1030),
            OeaWirelessPairing.Entry("IP address and port", 1030, 1060),
            OeaWirelessPairing.Entry("10.213.122.13:39345", 1060, 1100))
        assertTrue(OeaWirelessPairing.readEntries(entries, 4_000L))
        val p = OeaWirelessPairing.pairing
        assertNotNull(p); assertEquals("955806", p!!.code); assertEquals(39345, p.port); assertEquals("10.213.122.13", p.host)
        val c = OeaWirelessPairing.connection
        assertNotNull(c); assertEquals(34531, c!!.port)
    }
    @Test fun ownWindowDialogReadsOnlyPairingAddress() {
        val entries = listOf(
            OeaWirelessPairing.Entry("Pair with device", 0, 40),
            OeaWirelessPairing.Entry("Wi-Fi pairing code", 40, 70),
            OeaWirelessPairing.Entry("066 520", 70, 130),
            OeaWirelessPairing.Entry("IP address and port", 130, 160),
            OeaWirelessPairing.Entry("192.168.43.215:44963", 160, 200))
        assertTrue(OeaWirelessPairing.readEntries(entries, 5_000L))
        val p = OeaWirelessPairing.pairing
        assertNotNull(p); assertEquals("066520", p!!.code); assertEquals("192.168.43.215", p.host); assertEquals(44963, p.port)
    }

    @Test fun mismatchedHostsKeepAutoReadArmedForRecovery() {
        val pair = OeaWirelessPairing.Pairing("123456", "192.168.1.20", 37125, 1_000L)
        val connection = OeaWirelessPairing.Connection("192.168.1.21", 41235, 1_000L)
        assertFalse(OeaWirelessPairing.shouldStopWatching(pair, connection))
    }

    @Test fun matchingHostsAllowAutoReadToFinish() {
        val pair = OeaWirelessPairing.Pairing("123456", "192.168.1.20", 37125, 1_000L)
        val connection = OeaWirelessPairing.Connection("192.168.1.20", 41235, 1_000L)
        assertTrue(OeaWirelessPairing.shouldStopWatching(pair, connection))
    }
}
