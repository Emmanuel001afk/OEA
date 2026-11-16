package com.oea.launcher.miniapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules behind "which address is the same person".
 *
 * Every bug in here shows up on a phone as a conversation that opens blank or
 * as two halves of one chat, so the cases are the real ones reported in
 * testing: a Nigerian contact stored as `+2347089007507` but texted as
 * `07089007507`, and a bank shortcode that arrives as both `312` and `"312 "`.
 */
class OeaSmsMatcherTest {

    // ------------------------------------------------------------- sameAddress

    @Test fun localAndInternationalFormsOfOneNumberAreTheSamePerson() {
        assertTrue(OeaSmsMatcher.sameAddress("+2347089007507", "07089007507"))
        assertTrue(OeaSmsMatcher.sameAddress("07089007507", "+2347089007507"))
        assertTrue(OeaSmsMatcher.sameAddress("2347089007507", "07089007507"))
        assertTrue(OeaSmsMatcher.sameAddress("+234 708 900 7507", "0708 900 7507"))
    }

    @Test fun paddedAndBareShortcodesAreTheSameSender() {
        assertTrue(OeaSmsMatcher.sameAddress("312", "312 "))
        assertTrue(OeaSmsMatcher.sameAddress(" 312", "312"))
        assertTrue(OeaSmsMatcher.sameAddress("55019", "55019"))
    }

    @Test fun differentShortcodesNeverCollide() {
        assertFalse(OeaSmsMatcher.sameAddress("312", "313"))
        assertFalse(OeaSmsMatcher.sameAddress("312", "55019"))
        // A shortcode is not the tail of a subscriber number.
        assertFalse(OeaSmsMatcher.sameAddress("312", "+2347089007507"))
    }

    @Test fun differentPeopleNeverCollide() {
        assertFalse(OeaSmsMatcher.sameAddress("+2347089007507", "+2348012345678"))
        assertFalse(OeaSmsMatcher.sameAddress("07089007507", "07089007508"))
    }

    @Test fun alphanumericSendersMatchIgnoringCaseAndSpaces() {
        assertTrue(OeaSmsMatcher.sameAddress("GTBank", "gtbank"))
        assertTrue(OeaSmsMatcher.sameAddress("MTN ", "MTN"))
        // ...but a name is never a number, however tempting the digits are.
        assertFalse(OeaSmsMatcher.sameAddress("GTBank", "312"))
        assertFalse(OeaSmsMatcher.sameAddress("MTN", "+2347089007507"))
    }

    @Test fun blanksNeverMatchAnything() {
        assertFalse(OeaSmsMatcher.sameAddress("", "312"))
        assertFalse(OeaSmsMatcher.sameAddress("312", ""))
        assertFalse(OeaSmsMatcher.sameAddress(null, null))
    }

    // --------------------------------------------------------------- keys

    @Test fun canonicalKeyUnifiesTheFormsOfOneNumber() {
        assertEquals(
            OeaSmsMatcher.canonicalAddressKey("+2347089007507"),
            OeaSmsMatcher.canonicalAddressKey("07089007507")
        )
        assertEquals(
            OeaSmsMatcher.canonicalAddressKey("+2347089007507"),
            OeaSmsMatcher.canonicalAddressKey("+234 708 900 7507")
        )
    }

    @Test fun canonicalKeyKeepsDistinctShortcodesDistinct() {
        val threeOneTwo = OeaSmsMatcher.canonicalAddressKey("312")
        val threeOneThree = OeaSmsMatcher.canonicalAddressKey("313")
        assertEquals(OeaSmsMatcher.canonicalAddressKey("312 "), threeOneTwo)
        assertFalse(threeOneTwo == threeOneThree)
    }

    @Test fun sqlSuffixPrefiltersWithoutClaimingEquality() {
        assertEquals("9007507", OeaSmsMatcher.sqlPhoneSuffix("+2347089007507"))
        assertEquals("9007507", OeaSmsMatcher.sqlPhoneSuffix("07089007507"))
        // Shortcodes have no suffix clause: an exact/`%clean%` match finds them.
        assertNull(OeaSmsMatcher.sqlPhoneSuffix("312"))
        assertNull(OeaSmsMatcher.sqlPhoneSuffix("GTBank"))
    }

    // ----------------------------------------------------------------- OTP

    @Test fun extractsAVerificationCodeFromARealBankSms() {
        assertEquals(
            "482913",
            OeaSmsMatcher.extractOtpCode("Your GTBank OTP is 482913. Do not share it with anyone.")
        )
        assertEquals("5512", OeaSmsMatcher.extractOtpCode("Verification code: 5512"))
    }

    @Test fun prefersTheLongCodeWhenAYearIsAlsoPresent() {
        assertEquals(
            "904417",
            OeaSmsMatcher.extractOtpCode("Login code 904417 valid until 2026")
        )
    }

    @Test fun ordinaryMessagesHaveNoCode() {
        assertNull(OeaSmsMatcher.extractOtpCode("Are we still on for 6pm?"))
        assertNull(OeaSmsMatcher.extractOtpCode(""))
        assertNull(OeaSmsMatcher.extractOtpCode(null))
        // Digits alone are not a code: something has to say the message is one.
        assertNull(OeaSmsMatcher.extractOtpCode("1234"))
    }

    // ------------------------------------------------------------- category

    @Test fun sortsThreadsIntoTheChipsTheInboxShows() {
        assertEquals("otp", OeaSmsMatcher.categoryOf("GTBank", "Your OTP is 482913", false))
        assertEquals(
            "transactions",
            OeaSmsMatcher.categoryOf("GTBank", "Acct 0123456789 credited with NGN 5,000", false)
        )
        assertEquals("offers", OeaSmsMatcher.categoryOf("MTN", "Dial *131# for a bonus", false))
        assertEquals("personal", OeaSmsMatcher.categoryOf("+2347089007507", "Are you home?", true))
        // A person is not a promotion just because the number is not in contacts.
        assertEquals("personal", OeaSmsMatcher.categoryOf("+2347089007507", "Are you home?", false))
    }
}
