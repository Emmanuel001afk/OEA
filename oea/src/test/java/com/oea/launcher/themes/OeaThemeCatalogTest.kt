package com.oea.launcher.themes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OeaThemeCatalogTest {
    private val themes = OeaThemeCatalog.builtIns

    @Test fun atLeastHalfShareHomeAndLockImagery() {
        val shared = themes.count { it.sharedImagery }
        assertTrue("shared=$shared of ${themes.size}", shared * 2 >= themes.size)
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
            val bundled = ref as OeaImageRef.Bundled
            assertNotNull(OeaThemeCatalog.bundled(bundled.name))
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
