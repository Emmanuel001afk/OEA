package com.oea.launcher.miniapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of translation that must not depend on a phone having Play services:
 * which languages can be offered, what code each becomes, and — the one that
 * matters most — that translating a track never moves a cue.
 */
class OeaTranslateTest {

    private fun track(vararg lines: String): OeaSubtitles.Track = OeaSubtitles.Track(
        name = "Movie.en",
        cues = lines.mapIndexed { index, line ->
            OeaSubtitles.Cue(index * 2000L, index * 2000L + 1800L, line)
        },
        language = OeaSubtitleLanguages.byTag("en")
    )

    @Test fun everyLanguageVIPOffersCanBeNamed() {
        val languages = OeaTranslate.languages()
        assertTrue("${languages.size}", languages.size >= 50)
        languages.forEach { language ->
            assertTrue(language.label, language.english.isNotBlank())
            assertTrue(language.tag, OeaTranslate.supports(language.tag))
        }
    }

    @Test fun aChineseTagBecomesTheOneChineseModel() {
        assertEquals("zh", OeaTranslate.code("zh-Hans"))
        assertEquals("zh", OeaTranslate.code("zh-Hant"))
        assertEquals("zh", OeaTranslate.code("zh-TW"))
    }

    @Test fun legacyCodesBecomeTheOnesTheModelsKnow() {
        assertEquals("he", OeaTranslate.code("iw"))
        assertEquals("id", OeaTranslate.code("in"))
        assertEquals("no", OeaTranslate.code("nb"))
        assertEquals("no", OeaTranslate.code("nn"))
        assertEquals("tl", OeaTranslate.code("fil"))
    }

    @Test fun aLanguageWithNoModelIsNotOffered() {
        assertNull(OeaTranslate.code("yo"))
        assertFalse(OeaTranslate.supports("yo"))
        assertFalse(OeaTranslate.supports(null))
        assertFalse(OeaTranslate.supports(""))
    }

    @Test fun theTargetsNeverIncludeTheLanguageItIsAlreadyIn() {
        val english = OeaSubtitleLanguages.byTag("en")
        val targets = OeaTranslate.targets(english)
        assertTrue(targets.none { OeaTranslate.code(it.tag) == "en" })
        assertTrue(targets.any { OeaTranslate.code(it.tag) == "fr" })
    }

    @Test fun aTranslationKeepsEveryCueWhereItWas() {
        val original = track("Hello there.", "Nobody tells me anything.", "Where were you?")
        val french = OeaSubtitleLanguages.byTag("fr")!!
        val translated = OeaTranslate.translated(
            original,
            french,
            listOf("Bonjour.", "Personne ne me dit rien.", "Où étiez-vous ?")
        )
        assertEquals(original.cues.size, translated.cues.size)
        original.cues.zip(translated.cues).forEach { (before, after) ->
            assertEquals(before.startMs, after.startMs)
            assertEquals(before.endMs, after.endMs)
        }
        assertEquals("Bonjour.", translated.cues[0].text)
        assertEquals(french, translated.language)
    }

    @Test fun aCueTheModelReturnedNothingForKeepsItsWords() {
        val original = track("Hello there.", "Second line.")
        val german = OeaSubtitleLanguages.byTag("de")!!
        val translated = OeaTranslate.translated(original, german, listOf("Hallo.", ""))
        assertEquals("Hallo.", translated.cues[0].text)
        assertEquals("Second line.", translated.cues[1].text)
    }

    @Test fun aTranslatedTrackDoesNotPretendToBeAFile() {
        val translated = OeaTranslate.translated(
            track("Hello."), OeaSubtitleLanguages.byTag("es")!!, listOf("Hola.")
        )
        assertNull(translated.origin)
        assertTrue(translated.name, translated.name.contains("ES"))
    }

    @Test fun translatingIntoTheSameLanguageIsRefusedRatherThanDuplicated() {
        val english = OeaSubtitleLanguages.byTag("en")!!
        assertNull(OeaTranslate.translate(track("Hello."), english))
    }
}
