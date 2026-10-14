package com.oea.launcher.themes

import android.graphics.Color
import com.oea.launcher.R

/**
 * An image a theme or the wallpaper library can use: a picture bundled with
 * OEA ([Bundled], referenced by a stable name rather than a resource id) or a
 * private copy of a picture the user imported ([Imported]).
 */
sealed class OeaImageRef {
    abstract val key: String

    data class Bundled(val name: String, val resId: Int, val title: String) : OeaImageRef() {
        override val key: String get() = "res:$name"
    }

    data class Imported(val path: String) : OeaImageRef() {
        override val key: String get() = "file:$path"
    }

    companion object {
        fun parse(key: String?): OeaImageRef? {
            if (key.isNullOrBlank()) return null
            return when {
                key.startsWith("res:") -> OeaThemeCatalog.bundled(key.removePrefix("res:"))
                key.startsWith("file:") -> Imported(key.removePrefix("file:")).takeIf { java.io.File(it.path).isFile }
                else -> null
            }
        }
    }
}

/**
 * A complete OEA look: Home and lock wallpapers plus coordinated colours,
 * icon shape, dock and glass style and page motion. Applying a theme only
 * changes these appearance settings; apps, folders, widgets, the grid and
 * every other personal setting are never touched.
 */
data class OeaThemeSpec(
    val id: String,
    val name: String,
    val tagline: String,
    val home: OeaImageRef,
    val lock: OeaImageRef,
    /** Accent used with dark panels (a light tone) and with light panels (a deep tone). */
    val accentDark: Int,
    val accentLight: Int,
    /** Tints OEA's panels (drawer, Settings, sheets) so they match the artwork. */
    val surfaceTint: Int,
    /** "dark", "light" or "system" (keep the user's choice). */
    val mode: String,
    val glassStyle: String,
    val iconShape: String,
    val dockStyle: String,
    val pageEffect: String,
    val builtIn: Boolean = true,
) {
    /** Home and lock screen share one picture (or a coordinated pair). */
    val sharedImagery: Boolean get() = home.key == lock.key
}

object OeaThemeCatalog {
    private fun res(name: String, id: Int, title: String) = OeaImageRef.Bundled(name, id, title)

    val AURORA = res("oea_theme_aurora", R.drawable.oea_theme_aurora, "Aurora lake")
    val LIQUID_GLASS = res("oea_theme_liquid_glass", R.drawable.oea_theme_liquid_glass, "Liquid glass")
    val MOUNTAIN = res("oea_wallpaper_mountain_dawn", R.drawable.oea_wallpaper_mountain_dawn, "Mountain dawn")
    val FOREST = res("oea_wallpaper_forest_river", R.drawable.oea_wallpaper_forest_river, "Forest river")
    val DESERT = res("oea_wallpaper_desert_coast", R.drawable.oea_wallpaper_desert_coast, "Desert coast")
    val NEON_HOME = res("oea_theme_neon_home", R.drawable.oea_theme_neon_home, "Neon street")
    val NEON_LOCK = res("oea_theme_neon_lock", R.drawable.oea_theme_neon_lock, "Neon rain")
    val OCEAN_HOME = res("oea_theme_ocean_home", R.drawable.oea_theme_ocean_home, "Turquoise shore")
    val OCEAN_LOCK = res("oea_theme_ocean_lock", R.drawable.oea_theme_ocean_lock, "Sunlit deep")
    val SAKURA_HOME = res("oea_theme_sakura_home", R.drawable.oea_theme_sakura_home, "Sakura branches")
    val SAKURA_LOCK = res("oea_theme_sakura_lock", R.drawable.oea_theme_sakura_lock, "Garden path")
    val NEBULA_HOME = res("oea_theme_nebula_home", R.drawable.oea_theme_nebula_home, "Violet nebula")
    val NEBULA_LOCK = res("oea_theme_nebula_lock", R.drawable.oea_theme_nebula_lock, "Ringed planet")
    val GRAPHITE_HOME = res("oea_theme_graphite_home", R.drawable.oea_theme_graphite_home, "Graphite waves")
    val GRAPHITE_LOCK = res("oea_theme_graphite_lock", R.drawable.oea_theme_graphite_lock, "Graphite orbits")

    /** Every bundled picture, offered in the wallpaper library too. */
    val bundledImages: List<OeaImageRef.Bundled> = listOf(
        AURORA, LIQUID_GLASS, MOUNTAIN, FOREST, DESERT,
        NEON_HOME, NEON_LOCK, OCEAN_HOME, OCEAN_LOCK, SAKURA_HOME, SAKURA_LOCK,
        NEBULA_HOME, NEBULA_LOCK, GRAPHITE_HOME, GRAPHITE_LOCK,
    )

    fun bundled(name: String): OeaImageRef.Bundled? = bundledImages.firstOrNull { it.name == name }

    /**
     * Ten original themes. The first five use the same picture on Home and
     * the lock screen; the other five pair two different pictures.
     */
    val builtIns: List<OeaThemeSpec> = listOf(
        OeaThemeSpec(
            "aurora", "Aurora", "Northern lights over a still lake", AURORA, AURORA,
            accentDark = Color.rgb(95, 227, 192), accentLight = Color.rgb(0, 121, 107), surfaceTint = Color.rgb(24, 70, 82),
            mode = "dark", glassStyle = "liquid", iconShape = "squircle", dockStyle = "glass", pageEffect = "liquid",
        ),
        OeaThemeSpec(
            "liquid_glass", "Liquid Glass", "Flowing glass, frosted surfaces and liquid motion", LIQUID_GLASS, LIQUID_GLASS,
            accentDark = Color.rgb(124, 196, 255), accentLight = Color.rgb(21, 101, 192), surfaceTint = Color.rgb(27, 42, 85),
            mode = "dark", glassStyle = "liquid", iconShape = "squircle", dockStyle = "glass", pageEffect = "liquid",
        ),
        OeaThemeSpec(
            "mountain_dawn", "Mountain Dawn", "First light on misty peaks", MOUNTAIN, MOUNTAIN,
            accentDark = Color.rgb(255, 180, 138), accentLight = Color.rgb(180, 83, 42), surfaceTint = Color.rgb(107, 74, 58),
            mode = "system", glassStyle = "classic", iconShape = "rounded", dockStyle = "glass", pageEffect = "classic",
        ),
        OeaThemeSpec(
            "forest_river", "Forest River", "Calm water through deep green woods", FOREST, FOREST,
            accentDark = Color.rgb(139, 214, 160), accentLight = Color.rgb(46, 125, 79), surfaceTint = Color.rgb(47, 74, 52),
            mode = "dark", glassStyle = "classic", iconShape = "circle", dockStyle = "glass", pageEffect = "stack",
        ),
        OeaThemeSpec(
            "desert_coast", "Desert Coast", "Warm dunes meeting the sea", DESERT, DESERT,
            accentDark = Color.rgb(242, 194, 123), accentLight = Color.rgb(140, 90, 0), surfaceTint = Color.rgb(107, 82, 50),
            mode = "system", glassStyle = "classic", iconShape = "rounded", dockStyle = "solid", pageEffect = "zoom",
        ),
        OeaThemeSpec(
            "neon_city", "Neon City", "Rainy streets at night, neon reflections on lock", NEON_HOME, NEON_LOCK,
            accentDark = Color.rgb(255, 92, 200), accentLight = Color.rgb(176, 32, 127), surfaceTint = Color.rgb(58, 31, 79),
            mode = "dark", glassStyle = "liquid", iconShape = "squircle", dockStyle = "glass", pageEffect = "cube",
        ),
        OeaThemeSpec(
            "ocean", "Ocean", "Turquoise shore on Home, sunlit depths on lock", OCEAN_HOME, OCEAN_LOCK,
            accentDark = Color.rgb(79, 216, 232), accentLight = Color.rgb(0, 120, 138), surfaceTint = Color.rgb(15, 59, 74),
            mode = "system", glassStyle = "liquid", iconShape = "circle", dockStyle = "glass", pageEffect = "liquid",
        ),
        OeaThemeSpec(
            "sakura", "Sakura", "Blossoms on Home, a lantern-lit garden on lock", SAKURA_HOME, SAKURA_LOCK,
            accentDark = Color.rgb(255, 158, 196), accentLight = Color.rgb(176, 48, 106), surfaceTint = Color.rgb(90, 52, 70),
            mode = "light", glassStyle = "liquid", iconShape = "rounded", dockStyle = "glass", pageEffect = "fade",
        ),
        OeaThemeSpec(
            "nebula", "Nebula", "A violet nebula on Home, a ringed planet on lock", NEBULA_HOME, NEBULA_LOCK,
            accentDark = Color.rgb(183, 156, 255), accentLight = Color.rgb(91, 63, 196), surfaceTint = Color.rgb(42, 31, 79),
            mode = "dark", glassStyle = "liquid", iconShape = "circle", dockStyle = "glass", pageEffect = "zoom",
        ),
        OeaThemeSpec(
            "graphite", "Graphite", "Quiet monochrome waves and orbits", GRAPHITE_HOME, GRAPHITE_LOCK,
            accentDark = Color.rgb(215, 220, 228), accentLight = Color.rgb(60, 67, 80), surfaceTint = Color.rgb(35, 38, 44),
            mode = "dark", glassStyle = "classic", iconShape = "square", dockStyle = "solid", pageEffect = "classic",
        ),
    )

    val ICON_SHAPES = listOf("system" to "System", "circle" to "Circle", "squircle" to "Squircle", "rounded" to "Rounded", "teardrop" to "Teardrop", "square" to "Square")
    val DOCK_STYLES = listOf("glass" to "Glass", "solid" to "Solid", "none" to "None")
    val GLASS_STYLES = listOf("classic" to "Classic", "liquid" to "Liquid glass")
    val PAGE_EFFECTS = listOf("classic" to "Classic", "liquid" to "Liquid", "cube" to "Cube", "stack" to "Stack", "zoom" to "Zoom", "fade" to "Fade", "none" to "None")
    val MODES = listOf("system" to "Keep current", "dark" to "Dark", "light" to "Light")

    fun label(options: List<Pair<String, String>>, value: String) = options.firstOrNull { it.first == value }?.second ?: value
}
