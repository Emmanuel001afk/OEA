package com.oea.launcher.miniapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The language catalogue, which is the part of VIP's subtitles that has to be
 * right on paper: a bad entry does not crash anything, it quietly puts the
 * German track under a film for a viewer who asked for Yoruba.
 *
 * What is pinned here:
 *  * every way a filename spells a language — `en`, `ENG`, `fre`, `en-GB`,
 *    `zh-CN`, `iw`, `Français`, `Yorùbá` — lands on one entry;
 *  * every entry is reachable by its own tag and by each alias it declares, so
 *    the catalogue cannot contain a language nothing can ever select;
 *  * no two entries claim the same tag, and a language's name is not printed
 *    twice when its native and English spellings agree;
 *  * right-to-left is a property of the language, not a guess;
 *  * the script reader names the writing system when it can, and refuses to
 *    name a Latin-script language from its letters alone.
 */
class OeaSubtitleLanguagesTest {

    @Test fun codesNamesAndRegionsAllLandOnOneLanguage() {
        assertEquals("en", OeaSubtitleLanguages.resolve("EN")?.tag)
        assertEquals("en", OeaSubtitleLanguages.resolve("eng")?.tag)
        assertEquals("en", OeaSubtitleLanguages.resolve("en-GB")?.tag)
        assertEquals("fr", OeaSubtitleLanguages.resolve("fre")?.tag)
        assertEquals("fr", OeaSubtitleLanguages.resolve("fra")?.tag)
        assertEquals("fr", OeaSubtitleLanguages.resolve("fr-CA")?.tag)
        assertEquals("pt", OeaSubtitleLanguages.resolve("pt-PT")?.tag)
        assertEquals("pt-BR", OeaSubtitleLanguages.resolve("pt-br")?.tag)
        // The codes Android still hands out for languages that were renamed.
        assertEquals("he", OeaSubtitleLanguages.resolve("iw")?.tag)
        assertEquals("id", OeaSubtitleLanguages.resolve("in")?.tag)
        assertEquals("yi", OeaSubtitleLanguages.resolve("ji")?.tag)
        assertEquals("nb", OeaSubtitleLanguages.resolve("no")?.tag)
    }

    @Test fun chineseRegionsBecomeScripts() {
        assertEquals("zh-Hans", OeaSubtitleLanguages.resolve("zh")?.tag)
        assertEquals("zh-Hans", OeaSubtitleLanguages.resolve("zh-CN")?.tag)
        assertEquals("zh-Hans", OeaSubtitleLanguages.resolve("zh-SG")?.tag)
        assertEquals("zh-Hant", OeaSubtitleLanguages.resolve("zh-TW")?.tag)
        assertEquals("zh-Hant", OeaSubtitleLanguages.resolve("zh-HK")?.tag)
    }

    @Test fun aNameMatchesInItsOwnSpellingOrInEnglish() {
        assertEquals("yo", OeaSubtitleLanguages.resolve("Yorùbá")?.tag)
        assertEquals("yo", OeaSubtitleLanguages.resolve("YORUBA")?.tag)
        assertEquals("fr", OeaSubtitleLanguages.resolve("Français")?.tag)
        assertEquals("de", OeaSubtitleLanguages.resolve("Deutsch")?.tag)
        assertEquals("tr", OeaSubtitleLanguages.resolve("Türkçe")?.tag)
        assertEquals("es", OeaSubtitleLanguages.resolve("spanish")?.tag)
        assertEquals("pt-BR", OeaSubtitleLanguages.resolve("Brazilian Portuguese")?.tag)
        assertEquals("zh-Hans", OeaSubtitleLanguages.resolve("Chinese (Simplified)")?.tag)
    }

    @Test fun aWordThatIsNotALanguageIsNotGuessed() {
        assertNull(OeaSubtitleLanguages.resolve("Klingon"))
        assertNull(OeaSubtitleLanguages.resolve("1080p"))
        assertNull(OeaSubtitleLanguages.resolve("x264"))
        assertNull(OeaSubtitleLanguages.resolve(""))
        assertNull(OeaSubtitleLanguages.resolve(null))
    }

    @Test fun everyEntryIsReachableByItsOwnTagAndItsAliases() {
        OeaSubtitleLanguages.all.forEach { language ->
            assertEquals("tag ${language.tag}", language.tag, OeaSubtitleLanguages.resolve(language.tag)?.tag)
            language.aliases.forEach { alias ->
                assertEquals("alias $alias of ${language.tag}", language.tag, OeaSubtitleLanguages.resolve(alias)?.tag)
            }
        }
    }

    @Test fun noTwoEntriesShareATagOrAName() {
        val tags = OeaSubtitleLanguages.all.map { it.tag }
        assertEquals(tags.size, tags.distinct().size)
        val english = OeaSubtitleLanguages.all.map { it.english }
        assertEquals(english.size, english.distinct().size)
        // Every alias is a code somebody might write in a filename; two languages
        // claiming the same one would make the picker depend on list order.
        val claimed = HashMap<String, String>()
        OeaSubtitleLanguages.all.forEach { language ->
            (language.aliases + language.tag).forEach { code ->
                val owner = claimed.put(code.lowercase(), language.tag)
                if (owner != null) assertEquals("code $code", owner, language.tag)
            }
        }
    }

    @Test fun rightToLeftIsAPropertyOfTheLanguage() {
        assertTrue(OeaSubtitleLanguages.byTag("ar")!!.rightToLeft)
        assertTrue(OeaSubtitleLanguages.byTag("he")!!.rightToLeft)
        assertTrue(OeaSubtitleLanguages.byTag("fa")!!.rightToLeft)
        assertTrue(OeaSubtitleLanguages.byTag("ur")!!.rightToLeft)
        assertTrue(OeaSubtitleLanguages.byTag("ps")!!.rightToLeft)
        assertFalse(OeaSubtitleLanguages.byTag("en")!!.rightToLeft)
        assertFalse(OeaSubtitleLanguages.byTag("yo")!!.rightToLeft)
        assertFalse(OeaSubtitleLanguages.byTag("hi")!!.rightToLeft)
    }

    @Test fun theBadgeIsShortAndTheLabelIsNotRepeated() {
        assertEquals("EN", OeaSubtitleLanguages.byTag("en-GB")!!.badge)
        assertEquals("PT", OeaSubtitleLanguages.byTag("pt-BR")!!.badge)
        assertEquals("ZH", OeaSubtitleLanguages.byTag("zh-Hant")!!.badge)
        assertEquals("English", OeaSubtitleLanguages.byTag("en")!!.label)
        assertEquals("Français · French", OeaSubtitleLanguages.byTag("fr")!!.label)
        assertEquals("Yorùbá · Yoruba", OeaSubtitleLanguages.byTag("yo")!!.label)
    }

    @Test fun cjkIsMarkedForALittleMoreSize() {
        assertTrue(OeaSubtitleLanguages.byTag("ja")!!.wideGlyphs)
        assertTrue(OeaSubtitleLanguages.byTag("ko")!!.wideGlyphs)
        assertTrue(OeaSubtitleLanguages.byTag("zh-Hans")!!.wideGlyphs)
        assertFalse(OeaSubtitleLanguages.byTag("en")!!.wideGlyphs)
        assertFalse(OeaSubtitleLanguages.byTag("ar")!!.wideGlyphs)
    }

    @Test fun theScriptIsNamedWhenTheLettersGiveItAway() {
        assertEquals("ar", OeaSubtitleLanguages.scriptOf("مرحبا بكم في البيت")?.tag)
        assertEquals("he", OeaSubtitleLanguages.scriptOf("שלום לכולם")?.tag)
        assertEquals("ru", OeaSubtitleLanguages.scriptOf("Привет, как дела")?.tag)
        assertEquals("el", OeaSubtitleLanguages.scriptOf("Καλημέρα σε όλους")?.tag)
        assertEquals("hi", OeaSubtitleLanguages.scriptOf("नमस्ते दुनिया")?.tag)
        assertEquals("th", OeaSubtitleLanguages.scriptOf("สวัสดีทุกคน")?.tag)
        assertEquals("ko", OeaSubtitleLanguages.scriptOf("안녕하세요 여러분")?.tag)
        assertEquals("ja", OeaSubtitleLanguages.scriptOf("こんにちは世界")?.tag)
        assertEquals("zh-Hans", OeaSubtitleLanguages.scriptOf("你好，世界")?.tag)
    }

    @Test fun latinLettersAreNotALanguage() {
        // English, Yoruba, French and eighty others share these letters. Naming
        // one of them would be a guess, and VIP does not guess.
        assertNull(OeaSubtitleLanguages.scriptOf("Hello, this is a line of dialogue"))
        assertNull(OeaSubtitleLanguages.scriptOf("Ẹ kú àárọ̀, ṣé àlàáfíà ni"))
        assertNull(OeaSubtitleLanguages.scriptOf(""))
        assertNull(OeaSubtitleLanguages.scriptOf("1234 5678"))
    }

    @Test fun aSingleStrayCharacterIsNotAWholeScript() {
        assertNull(OeaSubtitleLanguages.scriptOf("ok はい"))
    }
}
