package com.oea.launcher.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The drawer's search keys.
 *
 * Two things matter here and both used to be wrong for speed rather than for
 * output: the ranking has to stay exactly what the user learned, and the labels
 * have to be normalized once instead of on every keystroke.
 */
class OeaSearchIndexTest {

    private val index = OeaSearchIndex()

    private fun score(label: String, query: String, packageName: String = "com.example.app"): Int? {
        val entry = index.entry("key:$label", label, packageName)
        return index.score(entry, index.normalize(query))
    }

    @Test fun prefixBeatsWordStartBeatsInitialsBeatsContainsBeatsPackage() {
        // "cal" starts "Calendar".
        assertEquals(0, score("Calendar", "cal"))
        // It is a later word that starts "Photo Cal" — still a strong match.
        assertEquals(1, score("Photo Camera Roll", "cam"))
        // Initials: "Photo Camera Roll" -> "pcr".
        assertEquals(2, score("Photo Camera Roll", "pcr"))
        // Only inside the label.
        assertEquals(3, score("MyCalendar", "cal"))
        // Only inside the package name.
        assertEquals(4, score("Reminders", "cal", packageName = "com.calendar.reminders"))
        // Nowhere.
        assertNull(score("Maps", "cal"))
    }

    @Test fun initialsNeedTwoCharacters() {
        // A single letter can only match a prefix or a word start, never initials:
        // otherwise every app whose initials contain the letter would jump in.
        assertEquals(0, score("Zeta Alpha", "z"))
        assertEquals(2, score("Zeta Alpha", "za"))
    }

    @Test fun diacriticsAndCaseAreFolded() {
        assertEquals("cafe", index.normalize("Café"))
        assertEquals("uber", index.normalize("Über"))
        assertEquals(0, score("Café", "cafe"))
        assertEquals(0, score("CAFÉ", "caf"))
        assertEquals(0, score("Ångström", "ang"))
    }

    @Test fun wordSeparatorsSplitTheSameWayTheOldFilterDid() {
        assertEquals(1, score("photo-camera", "camera"))
        assertEquals(1, score("photo_camera", "camera"))
        assertEquals(1, score("photo.camera", "camera"))
        assertEquals(2, score("photo-camera", "pc"))
    }

    @Test fun anEntryIsBuiltOncePerLabel() {
        val first = index.entry("k", "Calendar", "com.example.calendar")
        val second = index.entry("k", "Calendar", "com.example.calendar")
        assertSame("a repeated lookup must not re-normalize the label", first, second)
    }

    @Test fun aRenamedAppIsNotServedItsOldKeys() {
        // The package deliberately contains no "cal": this test is about the label.
        val before = index.entry("k", "Calendar", "com.example.app")
        val after = index.entry("k", "Reminders", "com.example.app")
        assertEquals("calendar", before.normalized)
        assertEquals("reminders", after.normalized)
        assertNull(index.score(after, index.normalize("cal")))
    }

    @Test fun theMapStaysBounded() {
        val small = OeaSearchIndex(limit = 2)
        repeat(10) { i -> small.entry("k$i", "App $i", "com.example.app$i") }
        assertTrue("cache grew past its limit: ${small.size()}", small.size() <= 2)
    }

    @Test fun alphabetLettersSkipSymbols() {
        assertEquals('C', index.initial(index.entry("k", "  Calendar", "p")))
        assertEquals('#', index.initial(index.entry("k", "1Password", "p")))
        // Diacritics fold before the letter is taken, exactly as the old code did:
        // "Émile" is an E, not a symbol.
        assertEquals('E', index.initial(index.entry("k", "Émile", "p")))
        assertEquals('E', index.initial(index.entry("k", "École", "p")))
        assertNull(index.initial(index.entry("k", "   ", "p")))
    }

    @Test fun sortingByScoreThenLaunchCountThenLabelIsStable() {
        val apps = listOf(
            Triple("Calendar", 0, "com.a.calendar"),
            Triple("My Calendar", 3, "com.a.mycal"),
            Triple("Cal", 12, "com.a.cal"),
            Triple("Photo Camera", 1, "com.a.cam")
)
        val ranked = apps.mapNotNull { (label, launches, pkg) ->
            val entry = index.entry(pkg, label, pkg)
            val q = index.normalize("cal")
            index.score(entry, q)?.let { Triple(label, it, launches) }
        }.sortedWith(compareBy<Triple<String, Int, Int>>({ it.second }, { -it.third }, { it.first }))
            .map { it.first }
        assertEquals(listOf("Cal", "Calendar", "My Calendar"), ranked)
        assertNotNull(ranked)
    }
}
