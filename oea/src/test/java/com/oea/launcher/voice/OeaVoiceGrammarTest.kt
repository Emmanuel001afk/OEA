package com.oea.launcher.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The voice grammar, without a microphone: what a sentence means, and the times and
 * durations that have to be understood before any Android API is called.
 */
class OeaVoiceGrammarTest {

    private val apps = listOf("Camera", "Calculator", "Notes", "Settings", "Chrome", "Maps")

    private fun kind(text: String) = OeaVoiceGrammar.parse(text, apps)?.kind
    private fun query(text: String) = OeaVoiceGrammar.parse(text, apps)?.query

    @Test
    fun openingAnAppByName() {
        assertEquals(OeaVoiceGrammar.Kind.OPEN_APP, kind("open calculator"))
        assertEquals("Calculator", query("open calculator"))
        assertEquals(OeaVoiceGrammar.Kind.OPEN_APP, kind("please launch notes"))
    }

    @Test
    fun goToAnAppOpensIt() {
        assertEquals(OeaVoiceGrammar.Kind.OPEN_APP, kind("go to notes"))
        assertEquals("Notes", query("go to notes"))
    }

    @Test
    fun goToAPlaceNavigates() {
        // This is the sentence that used to be swallowed by the app opener.
        assertEquals(OeaVoiceGrammar.Kind.NAVIGATE, kind("go to Ikeja"))
        assertEquals("Ikeja", query("go to Ikeja"))
        assertEquals(OeaVoiceGrammar.Kind.NAVIGATE, kind("take me to the airport"))
        assertEquals(OeaVoiceGrammar.Kind.NAVIGATE, kind("navigate to Lekki"))
    }

    @Test
    fun searchingKeepsTheQuery() {
        assertEquals(OeaVoiceGrammar.Kind.SEARCH_WEB, kind("search for cats"))
        assertEquals("cats", query("search for cats"))
        assertEquals("cats", query("google cats"))
    }

    @Test
    fun callingAndMessaging() {
        assertEquals(OeaVoiceGrammar.Kind.CALL_CONTACT, kind("call mum"))
        assertEquals("mum", query("call mum"))
        assertEquals(OeaVoiceGrammar.Kind.MESSAGE_CONTACT, kind("send a message to Tunde"))
        assertEquals("Tunde", query("send a message to Tunde"))
        assertEquals(OeaVoiceGrammar.Kind.MESSAGE_CONTACT, kind("text 08031234567"))
    }

    @Test
    fun settingAnAlarmNeedsAnInstruction() {
        assertEquals(OeaVoiceGrammar.Kind.SET_ALARM, kind("set an alarm for 6:30"))
        assertEquals(OeaVoiceGrammar.Kind.SET_ALARM, kind("wake me at 7"))
    }

    @Test
    fun cancellingAnAlarmIsNotSettingOne() {
        // The old grammar matched the word "alarm" anywhere in the sentence, so
        // "cancel my alarm" armed a new one. It is now a command OEA admits it cannot
        // run, not a silent web search.
        assertEquals(OeaVoiceGrammar.Kind.UNKNOWN, kind("cancel my alarm"))
        assertEquals(OeaVoiceGrammar.Kind.UNKNOWN, kind("disable the alarm"))
        assertEquals(OeaVoiceGrammar.Kind.UNKNOWN, kind("turn off the alarm"))
        assertEquals(OeaVoiceGrammar.Kind.UNKNOWN, kind("stop the timer"))
    }

    @Test
    fun timersAndDurations() {
        assertEquals(OeaVoiceGrammar.Kind.SET_TIMER, kind("set a timer for 10 minutes"))
        assertEquals(600L, OeaVoiceGrammar.durationSeconds("set a timer for 10 minutes"))
        assertEquals(5400L, OeaVoiceGrammar.durationSeconds("start a timer for 1 hour 30 minutes"))
        assertEquals(45L, OeaVoiceGrammar.durationSeconds("timer for 45 seconds"))
        assertEquals(600L, OeaVoiceGrammar.durationSeconds("ten minutes timer"))
        assertEquals(180L, OeaVoiceGrammar.durationSeconds("timer for three minutes"))
        assertNull(OeaVoiceGrammar.durationSeconds("set a timer"))
        // A timer longer than a day is not a timer.
        assertNull(OeaVoiceGrammar.durationSeconds("timer for 99 hours"))
    }

    @Test
    fun clockTimesAreUnderstood() {
        assertEquals(6 to 30, OeaVoiceGrammar.clockTime("set an alarm for 6:30"))
        assertEquals(18 to 30, OeaVoiceGrammar.clockTime("wake me at 6:30 pm"))
        assertEquals(7 to 0, OeaVoiceGrammar.clockTime("set an alarm for 7"))
        assertEquals(7 to 0, OeaVoiceGrammar.clockTime("set an alarm for seven"))
        assertEquals(7 to 30, OeaVoiceGrammar.clockTime("set an alarm for half past seven"))
        assertEquals(7 to 15, OeaVoiceGrammar.clockTime("alarm for quarter past seven"))
        assertEquals(0 to 0, OeaVoiceGrammar.clockTime("alarm for 12 am"))
        assertNull(OeaVoiceGrammar.clockTime("set an alarm"))
    }

    @Test
    fun flashlightDirection() {
        assertEquals(false, OeaVoiceGrammar.flashlightWants("turn off the flashlight"))
        assertEquals(true, OeaVoiceGrammar.flashlightWants("turn on the flashlight"))
        assertNull(OeaVoiceGrammar.flashlightWants("flashlight"))
        // "on" inside another word must not count as "on".
        assertNull(OeaVoiceGrammar.flashlightWants("flashlight, please"))
    }

    @Test
    fun togglesAndModes() {
        assertEquals(OeaVoiceGrammar.Kind.TOGGLE_DND, kind("do not disturb on"))
        assertEquals(OeaVoiceGrammar.Kind.TOGGLE_LOWMODE, kind("turn on low mode"))
        assertEquals(OeaVoiceGrammar.Kind.CHANGE_MODE, kind("study mode"))
        assertEquals("study", query("study mode"))
        assertEquals(OeaVoiceGrammar.Kind.CHANGE_THEME, kind("change theme"))
        assertEquals(OeaVoiceGrammar.Kind.TAKE_PHOTO, kind("take a photo"))
        assertEquals(OeaVoiceGrammar.Kind.TAKE_VIDEO, kind("record video"))
    }

    @Test
    fun anythingElseIsAnnouncedAsASearch() {
        assertEquals(OeaVoiceGrammar.Kind.SEARCH_WEB, kind("how tall is Everest"))
        assertEquals(0.5f, OeaVoiceGrammar.parse("how tall is Everest", apps)!!.confidence, 0.001f)
    }

    @Test
    fun blankSpeechIsNotACommand() {
        assertNull(OeaVoiceGrammar.parse("", apps))
        assertNull(OeaVoiceGrammar.parse("   ", apps))
    }

    @Test
    fun appMatchingPrefersTheExactName() {
        assertEquals("Camera", OeaVoiceGrammar.matchingApp("camera", listOf("Camera Tools Pro", "Camera")))
        assertEquals("Chrome", OeaVoiceGrammar.matchingApp("chr", apps))
        assertNull(OeaVoiceGrammar.matchingApp("nothing here", apps))
    }
}
