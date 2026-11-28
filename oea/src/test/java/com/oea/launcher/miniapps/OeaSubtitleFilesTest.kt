package com.oea.launcher.miniapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which subtitle files belong to a clip, and in which language.
 *
 * This is the decision that made multi-language subtitles possible, so it is
 * pinned the way a parser is: names in, candidates out, no filesystem. The cases
 * below are the conventions real subtitle files arrive in — code, name, region,
 * forced, SDH, one folder down, dots in the release name — and the cases where
 * the honest answer is "not this film's dialogue".
 */
class OeaSubtitleFilesTest {

    private fun found(video: String, vararg names: String) =
        OeaSubtitleFiles.candidates(video, names.toList())

    private fun only(video: String, name: String) = found(video, name).firstOrNull()

    @Test fun everyNamingConventionInTheWildIsRead() {
        val all = found(
            "Movie.mkv",
            "Movie.en.srt", "Movie.fr.srt", "Movie.eng.vtt", "Movie.English.srt",
            "Movie.en-US.srt", "Movie.srt", "Movie.yoruba.srt", "Movie.English SDH.srt"
        )
        assertEquals(8, all.size)
        assertEquals("en", all.first { it.name == "Movie.en.srt" }.language?.tag)
        assertEquals("fr", all.first { it.name == "Movie.fr.srt" }.language?.tag)
        assertEquals("en", all.first { it.name == "Movie.eng.vtt" }.language?.tag)
        assertEquals("en", all.first { it.name == "Movie.English.srt" }.language?.tag)
        assertEquals("en", all.first { it.name == "Movie.en-US.srt" }.language?.tag)
        assertEquals("yo", all.first { it.name == "Movie.yoruba.srt" }.language?.tag)
        assertNull(all.first { it.name == "Movie.srt" }.language)
        assertTrue(all.first { it.name == "Movie.English SDH.srt" }.sdh)
    }

    @Test fun aReleaseNameWithDotsStillMatches() {
        assertEquals(
            1,
            found("Movie.2019.1080p.x264-GROUP.mkv", "Movie.2019.1080p.x264-GROUP.en.srt").size
        )
        // The same film at a different quality is a different name, and its
        // subtitles are not silently accepted for this one.
        assertTrue(found("Movie.2019.1080p.x264-GROUP.mkv", "Movie.2019.720p.en.srt").isEmpty())
    }

    @Test fun theCaseOfTheNameDoesNotMatter() {
        assertEquals("fr", only("MOVIE.MKV", "movie.FR.SRT")?.language?.tag)
        assertTrue(OeaSubtitleFiles.isSubtitle("Film.Srt"))
        assertFalse(OeaSubtitleFiles.isSubtitle("Film.srt.bak"))
    }

    @Test fun anotherFilesSubtitlesAreNotThisOnes() {
        assertTrue(
            found(
                "Movie.mkv",
                "Other.en.srt", "Movie.Trailer.srt", "Movie.txt", "Moviee.en.srt", "Movie.release.nfo"
            ).isEmpty()
        )
    }

    @Test fun forcedAndHearingImpairedAreNotTheSameThing() {
        val forced = only("Movie.mkv", "Movie.en.forced.srt")!!
        assertEquals("en", forced.language?.tag)
        assertTrue(forced.forced)
        assertFalse(forced.sdh)

        val sdh = only("Movie.mkv", "Movie.en.sdh.srt")!!
        assertTrue(sdh.sdh)
        assertFalse(sdh.forced)

        val cc = only("Movie.mkv", "Movie.en.cc.srt")!!
        assertTrue(cc.sdh)

        // "hi" is Hindi. The hearing-impaired flag people write is SDH.
        assertEquals("hi", only("Movie.mkv", "Movie.hi.srt")?.language?.tag)
    }

    @Test fun subtitlesOneFolderDownAreFound() {
        assertEquals("de", only("Movie.mkv", "Subs/Movie.de.srt")?.language?.tag)
        assertEquals("ja", only("Movie.mkv", "Subtitles/Movie.ja.vtt")?.language?.tag)
    }

    @Test fun aPickedFileNameSaysItsLanguageFromTheEnd() {
        assertEquals("fr", OeaSubtitleFiles.languageIn("Some Film 2019 1080p fr.srt")?.tag)
        assertEquals("yo", OeaSubtitleFiles.languageIn("Movie.yor.srt")?.tag)
        assertEquals("en", OeaSubtitleFiles.languageIn("Movie.English.SDH.srt")?.tag)
        assertEquals("pt-BR", OeaSubtitleFiles.languageIn("Film.pt-BR.forced.srt")?.tag)
        // The last word is the language; the first word of a title is not.
        assertNull(OeaSubtitleFiles.languageIn("The English Patient.srt"))
        assertNull(OeaSubtitleFiles.languageIn("subtitles.srt"))
        assertNull(OeaSubtitleFiles.languageIn("Episode 4.srt"))
    }

    @Test fun theChosenLanguageIsWhatOpens() {
        val all = found("Movie.mkv", "Movie.en.srt", "Movie.fr.srt", "Movie.yor.srt")
        assertEquals("Movie.yor.srt", OeaSubtitleFiles.best(all, OeaSubtitleLanguages.byTag("yo"))?.name)
        assertEquals("Movie.fr.srt", OeaSubtitleFiles.best(all, OeaSubtitleLanguages.byTag("fr"))?.name)
        // A language this clip does not have falls back to a real track, not to none.
        assertEquals("Movie.en.srt", OeaSubtitleFiles.best(all, OeaSubtitleLanguages.byTag("de"))?.name)
        // With nothing chosen, the first complete named track opens.
        assertEquals("Movie.en.srt", OeaSubtitleFiles.best(all, null)?.name)
    }

    @Test fun aCompleteTrackComesBeforeAForcedOneAndAnUnnamedOne() {
        val all = found("Movie.mkv", "Movie.en.forced.srt", "Movie.de.srt", "Movie.srt")
        val ordered = OeaSubtitleFiles.sorted(all, null)
        assertEquals(
            listOf("Movie.de.srt", "Movie.en.forced.srt", "Movie.srt"),
            ordered.map { it.name }
        )
    }

    @Test fun aCandidateSaysWhatItIsWithoutInventingALanguage() {
        val named = only("Movie.mkv", "Movie.fr.forced.srt")!!
        assertEquals("Français · French", named.label)
        assertEquals("FR", named.badge)
        assertEquals("forced", named.note)

        val unnamed = only("Movie.mkv", "Movie.srt")!!
        assertEquals("Language not named", unnamed.label)
        assertNull(unnamed.badge)
    }
}
