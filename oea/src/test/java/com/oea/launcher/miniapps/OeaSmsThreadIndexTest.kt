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
    }

    @Test fun emptyAddressesAreIgnoredAndReadThreadsStayRead() {
        val index = OeaSmsThreadIndex()
        index.add("   ", "orphan", 1L, false)
        index.add("123", "hello", 2L, true)
        assertEquals(1, index.threads().size)
        assertTrue(index.threads().single().read)
    }
}
