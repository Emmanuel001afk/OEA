package com.oea.launcher.miniapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Grouping real provider rows — the fix for "half my messages are missing" and
 * "this conversation opens blank".
 *
 * Android files the same person under several address strings (`+2347089007507`,
 * `07089007507`, `0708 900 7507`), gives one correspondent several `thread_id`s
 * when a dual-SIM phone is involved, and pads shortcodes (`312`, `"312 "`). The
 * index has to merge all of that into one conversation while keeping genuinely
 * different shortcodes apart, because merging "312" with "313" would be worse
 * than the bug it fixes.
 */
class OeaSmsThreadMergeTest {

    @Test fun mergesInternationalAndLocalFormsOfOneNumber() {
        val index = OeaSmsThreadIndex()
        index.add("+2347089007507", "Are you home?", 30L, true, threadId = 7)
        index.add("07089007507", "Yes", 40L, false, threadId = 9)

        val thread = index.threads().single()
        assertEquals(2, thread.count)
        assertEquals("Yes", thread.snippet)
        assertEquals(1, thread.unreadCount)
        // Both id forms are kept, so opening the conversation can find every row.
        assertTrue(thread.rawAddresses.contains("+2347089007507"))
        assertTrue(thread.rawAddresses.contains("07089007507"))
        assertTrue(thread.threadIds.contains(7L))
        assertTrue(thread.threadIds.contains(9L))
        assertEquals(9L, thread.threadId)
    }

    @Test fun mergesFormattedAndSpacedFormsOfOneNumber() {
        val index = OeaSmsThreadIndex()
        index.add("+234 708 900 7507", "one", 10L, true, threadId = 4)
        index.add("0708 900 7507", "two", 20L, true, threadId = 4)
        assertEquals(1, index.threads().size)
        assertEquals(2, index.threads().single().count)
    }

    @Test fun prefersTheInternationalFormAsTheDisplayName() {
        val index = OeaSmsThreadIndex()
        index.add("07089007507", "first", 10L, true, threadId = 4)
        index.add("+2347089007507", "second", 20L, true, threadId = 4)
        assertEquals("+2347089007507", index.threads().single().address)
    }

    @Test fun mergesPaddedAndBareShortcodes() {
        val index = OeaSmsThreadIndex()
        index.add("312", "alert one", 10L, true, threadId = 3)
        index.add("312 ", "alert two", 20L, false, threadId = 4)

        val thread = index.threads().single()
        assertEquals(2, thread.count)
        assertEquals(1, thread.unreadCount)
        assertEquals("alert two", thread.snippet)
    }

    @Test fun differentShortcodesStaySeparateConversations() {
        val index = OeaSmsThreadIndex()
        index.add("312", "bank alert", 10L, true, threadId = 3)
        index.add("313", "another sender", 20L, true, threadId = 4)
        index.add("55019", "third sender", 30L, true, threadId = 5)
        assertEquals(3, index.threads().size)
    }

    @Test fun rowsSharingAProviderThreadIdAreOneConversation() {
        // Group MMS and dual-SIM splits: the address differs, the thread does not.
        val index = OeaSmsThreadIndex()
        index.add("55019", "first", 10L, true, threadId = 5)
        index.add("55020", "second", 20L, true, threadId = 5)

        val thread = index.threads().single()
        assertEquals(2, thread.count)
        assertEquals(setOf(5L), thread.threadIds)
    }

    @Test fun alphanumericSendersMergeIgnoringCaseAndSpaces() {
        val index = OeaSmsThreadIndex()
        index.add("GTBank", "debit alert", 10L, true, threadId = 6)
        index.add("gtbank ", "another alert", 20L, true, threadId = 6)
        assertEquals(1, index.threads().size)
        assertEquals(2, index.threads().single().count)
    }

    @Test fun withoutAThreadIdGroupingStaysStrictlyByAddress() {
        // The ad-hoc grouping path (and the older tests) must not change: no
        // thread_id means no evidence that two address strings are one person.
        val index = OeaSmsThreadIndex()
        index.add("+2347089007507", "one", 10L, true)
        index.add("07089007507", "two", 20L, true)
        assertEquals(2, index.threads().size)
    }

    @Test fun lookupAddressesExportsEveryFormTrimmed() {
        val index = OeaSmsThreadIndex()
        index.add("+2347089007507", "one", 10L, true, threadId = 7)
        index.add(" 07089007507 ", "two", 20L, true, threadId = 7)
        val addresses = index.threads().single().lookupAddresses()
        assertEquals(setOf("+2347089007507", "07089007507"), addresses)
    }

    @Test fun conversationsSortNewestFirst() {
        val index = OeaSmsThreadIndex()
        index.add("+2347089007507", "older", 10L, true, threadId = 7)
        index.add("+2348012345678", "newest", 90L, true, threadId = 8)
        index.add("+2348099999999", "middle", 50L, true, threadId = 9)
        assertEquals(
            listOf("+2348012345678", "+2348099999999", "+2347089007507"),
            index.threads().map { it.address }
        )
    }
}
