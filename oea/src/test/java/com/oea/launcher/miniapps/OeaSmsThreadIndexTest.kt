package com.oea.launcher.miniapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OeaSmsThreadIndexTest {
    @Test fun groupsEveryThreadWithoutTheOldHundredThreadCutoff() {
        val index = OeaSmsThreadIndex()
        repeat(145) { i -> index.add("+155500${i.toString().padStart(4, '0')}", "message $i", i.toLong(), true) }
        assertEquals(145, index.threads().size)
    }

    @Test fun groupsMessagesAndKeepsNewestSnippetAndUnreadState() {
        val index = OeaSmsThreadIndex()
        index.add("  +15551234 ", "newest", 30L, true)
        index.add("+15551234", "older", 10L, false)
        index.add("+15551234", "middle", 20L, true)

        val thread = index.threads().single()
        assertEquals("+15551234", thread.address)
        assertEquals("newest", thread.snippet)
        assertEquals(3, thread.count)
        assertFalse(thread.read)
        assertEquals(1, thread.unreadCount)
    }

    @Test fun emptyAddressesAreIgnoredAndReadThreadsStayRead() {
        val index = OeaSmsThreadIndex()
        index.add("   ", "orphan", 1L, false)
        index.add("123", "hello", 2L, true)
        assertEquals(1, index.threads().size)
        assertTrue(index.threads().single().read)
    }

    @Test fun unreadCountCountsEveryUnreadMessage() {
        val index = OeaSmsThreadIndex()
        index.add("0803", "one", 10L, false)
        index.add("0803", "two", 20L, false)
        index.add("0803", "three", 30L, true)
        val thread = index.threads().single()
        assertEquals(2, thread.unreadCount)
        assertFalse(thread.read)
    }

    @Test fun latestSimAndTypeComeFromTheNewestMessage() {
        // The row's "You:"/"Not sent" prefix and the reply-SIM default both
        // read these from whichever message is newest, never from an older one.
        val index = OeaSmsThreadIndex()
        index.add("0803", "newest", 30L, true, subscriptionId = 7, type = 2)
        index.add("0803", "older", 10L, true, subscriptionId = 3, type = 1)
        val thread = index.threads().single()
        assertEquals(7, thread.latestSubscriptionId)
        assertEquals(2, thread.latestType)
    }
}
