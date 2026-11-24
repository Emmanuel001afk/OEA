package com.oea.launcher.miniapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a reply really sends, what the counter says about it, what a bubble calls
 * a failure, and which notification a conversation owns.
 *
 * These are the quiet faults: none of them throws, all of them tell the user
 * something untrue about a message they may be waiting on.
 */
class OeaMessagingFactsTest {

    // ------------------------------------------------------------------ composing

    @Test
    fun aPlainMessageIsSentExactlyAsTyped() {
        assertEquals("See you at six", OeaMessagingFacts.composeBody("See you at six", null))
        assertEquals("See you at six", OeaMessagingFacts.composeBody("See you at six", ""))
        assertEquals("See you at six", OeaMessagingFacts.composeBody("See you at six", "   "))
    }

    @Test
    fun aReplyLeadsWithTheQuoteItIsAnswering() {
        assertEquals(
            "\u201CDinner at seven?\u201D\nOn my way",
            OeaMessagingFacts.composeBody("On my way", "Dinner at seven?")
        )
    }

    @Test
    fun aLongQuoteIsClippedAndSaysSo() {
        val prefix = OeaMessagingFacts.quotePrefix("x".repeat(200))
        // 120 characters of the original, an ellipsis, the two quotes and a newline.
        assertEquals(120 + 1 + 2 + 1, prefix.length)
        assertTrue(prefix.endsWith("\u2026\u201D\n"))
        assertTrue(prefix.startsWith("\u201C"))
    }

    @Test
    fun aQuoteThatEndsInSpacesDoesNotCarryThemIntoTheEllipsis() {
        val prefix = OeaMessagingFacts.quotePrefix("y".repeat(118) + "   " + "z".repeat(10))
        assertFalse(prefix.contains(" \u2026"))
        assertTrue(prefix.endsWith("y\u2026\u201D\n"))
    }

    @Test
    fun theQuoteCountsTowardsTheMessage() {
        // 60 typed characters are well inside one segment; the same 60 after a
        // 120-character quote are not. The old counter measured only the 60.
        assertTrue(OeaMessagingFacts.composeBody("t".repeat(60), null).length <= 160)
        val withQuote = OeaMessagingFacts.composeBody("t".repeat(60), "q".repeat(120))
        assertTrue(withQuote.length > 160)
        assertEquals(60 + 120 + 3, withQuote.length)
    }

    @Test
    fun theCounterHidesWhenThereIsNothingToSays() {
        // Over 25 characters left of a single segment, the counter stays out of the way.
        assertNull(OeaMessagingFacts.counterLabel(1, 160))
        assertNull(OeaMessagingFacts.counterLabel(1, 26))
        assertEquals("25", OeaMessagingFacts.counterLabel(1, 25))
        assertEquals("0", OeaMessagingFacts.counterLabel(1, 0))
        assertNull(OeaMessagingFacts.counterLabel(0, 0))
    }

    @Test
    fun theCounterNamesThePartsOnceThereIsMoreThanOne() {
        assertEquals("10 • 2 SMS", OeaMessagingFacts.counterLabel(2, 10))
        assertEquals("70 • 3 SMS", OeaMessagingFacts.counterLabel(3, 70))
        assertEquals("0 • 2 SMS", OeaMessagingFacts.counterLabel(2, 0))
    }

    // ------------------------------------------------------------------- statuses

    @Test
    fun aRejectedSendIsAFailureEvenThoughItWasFiledAsSent() {
        // OEA files the row optimistically as SENT; the network's refusal arrives
        // afterwards as STATUS_FAILED on that same row.
        assertTrue(OeaMessagingFacts.isFailed(OeaMessagingFacts.TYPE_SENT, OeaMessagingFacts.STATUS_FAILED))
        assertTrue(OeaMessagingFacts.isFailed(OeaMessagingFacts.TYPE_FAILED, OeaMessagingFacts.STATUS_NONE))
        assertFalse(OeaMessagingFacts.isFailed(OeaMessagingFacts.TYPE_SENT, OeaMessagingFacts.STATUS_COMPLETE))
        assertFalse(OeaMessagingFacts.isFailed(OeaMessagingFacts.TYPE_SENT, OeaMessagingFacts.STATUS_PENDING))
        assertFalse(OeaMessagingFacts.isFailed(OeaMessagingFacts.TYPE_SENT, OeaMessagingFacts.STATUS_NONE))
        // An incoming message never failed to send.
        assertFalse(OeaMessagingFacts.isFailed(OeaMessagingFacts.TYPE_INBOX, OeaMessagingFacts.STATUS_FAILED))
    }

    @Test
    fun theBubbleSaysWhatHappenedToTheMessage() {
        assertEquals(" • Draft", OeaMessagingFacts.statusNote(OeaMessagingFacts.TYPE_DRAFT, OeaMessagingFacts.STATUS_NONE))
        assertEquals(" • Sending…", OeaMessagingFacts.statusNote(OeaMessagingFacts.TYPE_OUTBOX, OeaMessagingFacts.STATUS_NONE))
        assertEquals(" • Sending…", OeaMessagingFacts.statusNote(OeaMessagingFacts.TYPE_QUEUED, OeaMessagingFacts.STATUS_NONE))
        assertEquals(
            " • Not sent — tap to retry",
            OeaMessagingFacts.statusNote(OeaMessagingFacts.TYPE_SENT, OeaMessagingFacts.STATUS_FAILED)
        )
        assertEquals(" • Delivered", OeaMessagingFacts.statusNote(OeaMessagingFacts.TYPE_SENT, OeaMessagingFacts.STATUS_COMPLETE))
        assertEquals(" • Sent", OeaMessagingFacts.statusNote(OeaMessagingFacts.TYPE_SENT, OeaMessagingFacts.STATUS_PENDING))
        assertEquals("", OeaMessagingFacts.statusNote(OeaMessagingFacts.TYPE_SENT, OeaMessagingFacts.STATUS_NONE))
        assertEquals("", OeaMessagingFacts.statusNote(OeaMessagingFacts.TYPE_INBOX, OeaMessagingFacts.STATUS_NONE))
    }

    @Test
    fun theMirroredPlatformValuesAreTheOnesTelephonyUses() {
        // android.provider.Telephony.Sms — restated here so this object needs no
        // framework. If these drift, every bubble above is wrong.
        assertEquals(1, OeaMessagingFacts.TYPE_INBOX)
        assertEquals(2, OeaMessagingFacts.TYPE_SENT)
        assertEquals(3, OeaMessagingFacts.TYPE_DRAFT)
        assertEquals(4, OeaMessagingFacts.TYPE_OUTBOX)
        assertEquals(5, OeaMessagingFacts.TYPE_FAILED)
        assertEquals(6, OeaMessagingFacts.TYPE_QUEUED)
        assertEquals(-1, OeaMessagingFacts.STATUS_NONE)
        assertEquals(0, OeaMessagingFacts.STATUS_COMPLETE)
        assertEquals(32, OeaMessagingFacts.STATUS_FAILED)
        assertEquals(64, OeaMessagingFacts.STATUS_PENDING)
    }

    // -------------------------------------------------------------- notifications

    @Test
    fun aConversationKeepsOneNotificationSlot() {
        val first = OeaMessagingFacts.notificationId("+2347089007507")
        assertEquals(first, OeaMessagingFacts.notificationId("+2347089007507"))
        assertEquals(first, OeaMessagingFacts.notificationId(" +2347089007507 "))
    }

    @Test
    fun everyFormOfTheSameSenderSharesThatSlot() {
        // The old id was System.currentTimeMillis(), so even two messages from one
        // number never shared anything.
        assertEquals(OeaMessagingFacts.notificationId("MTN Nigeria"), OeaMessagingFacts.notificationId("mtn nigeria"))
        assertEquals(OeaMessagingFacts.notificationId("312 "), OeaMessagingFacts.notificationId("312"))
    }

    @Test
    fun differentSendersDoNotShareIt() {
        assertNotEquals(
            OeaMessagingFacts.notificationId("+2347089007507"),
            OeaMessagingFacts.notificationId("+2348031234567")
        )
        assertNotEquals(
            OeaMessagingFacts.notificationId("MTN Nigeria"),
            OeaMessagingFacts.notificationId("312")
        )
        // Never zero: zero is a legal notification id but this one is used to mean
        // "no conversation", so the mapping must not produce it.
        assertNotEquals(0, OeaMessagingFacts.notificationId(""))
        assertNotEquals(0, OeaMessagingFacts.notificationId("   "))
    }

    @Test
    fun aNotificationLeadsWithTheNameItKnows() {
        assertEquals("Chidi", OeaMessagingFacts.notificationTitle("Chidi", "+2347089007507"))
        // No contact match — the lookup hands back the address itself.
        assertEquals("+2347089007507", OeaMessagingFacts.notificationTitle("+2347089007507", "+2347089007507"))
        assertEquals("+2347089007507", OeaMessagingFacts.notificationTitle(null, "+2347089007507"))
        assertEquals("+2347089007507", OeaMessagingFacts.notificationTitle("  ", "+2347089007507"))
        assertEquals("New message", OeaMessagingFacts.notificationTitle("", ""))
    }
}
