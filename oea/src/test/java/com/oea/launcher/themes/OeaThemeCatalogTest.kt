package com.oea.launcher.themes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OeaThemeCatalogTest {
    private val themes = OeaThemeCatalog.builtIns

    @Test fun quoteCollectionUsesRealArtAndCoordinatedSharedImagery() {
        val quotes = themes.filter { it.id.startsWith("quote_") }
        assertEquals(11, quotes.size)
        assertEquals(quotes.size, quotes.map { it.home.key }.toSet().size)
        quotes.forEach { theme ->
            assertTrue(theme.sharedImagery)
            val art = theme.home as OeaImageRef.Bundled
            assertTrue("Quote art must decode a real resource, not a drawn placeholder", art.resId != 0)
            assertTrue(OeaThemeCatalog.bundledImages.contains(art))
            assertEquals("dark", theme.mode)
            assertEquals("glass", theme.dockStyle)
            assertTrue(theme.tagline.isNotBlank())
        }
    }

    @Test fun theRestUseSeparateHomeAndLockPictures() {
        themes.filterNot { it.sharedImagery }.forEach { theme ->
            assertNotEquals(theme.id, theme.home.key, theme.lock.key)
        }
        assertTrue(themes.any { !it.sharedImagery })
    }

    @Test fun idsAreUniqueAndImagesResolve() {
        assertEquals(themes.size, themes.map { it.id }.toSet().size)
        themes.flatMap { listOf(it.home, it.lock) }.forEach { ref ->
            when (ref) {
                is OeaImageRef.Bundled -> assertNotNull(OeaThemeCatalog.bundled(ref.name))
                is OeaImageRef.Generated -> assertTrue(com.oea.launcher.wallpaper.OeaArtwork.isKnown(ref.name))
                is OeaImageRef.Imported -> throw AssertionError("Built-in themes must not depend on user files")
            }
            assertEquals(ref, OeaImageRef.parse(ref.key))
        }
    }

    @Test fun stylesUseKnownValues() {
        val shapes = OeaThemeCatalog.ICON_SHAPES.map { it.first }
        val docks = OeaThemeCatalog.DOCK_STYLES.map { it.first }
        val glass = OeaThemeCatalog.GLASS_STYLES.map { it.first }
        val effects = OeaThemeCatalog.PAGE_EFFECTS.map { it.first }
        themes.forEach {
            assertTrue(it.iconShape in shapes)
            assertTrue(it.dockStyle in docks)
            assertTrue(it.glassStyle in glass)
            assertTrue(it.pageEffect in effects)
            assertTrue(it.mode in listOf("system", "dark", "light"))
        }
    }
}
