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

    /**
     * A picture OEA draws itself (see OeaArtwork). Generated artwork exists so a
     * theme can have its *own* lock-screen picture — different from the picture
     * behind Home, and never one of the wallpapers in the library.
     */
    data class Generated(val name: String, val title: String) : OeaImageRef() {
        override val key: String get() = "gen:$name"
    }

    companion object {
        fun parse(key: String?): OeaImageRef? {
            if (key.isNullOrBlank()) return null
            return when {
                key.startsWith("res:") -> OeaThemeCatalog.bundled(key.removePrefix("res:"))
                key.startsWith("file:") -> Imported(key.removePrefix("file:")).takeIf { java.io.File(it.path).isFile }
                key.startsWith("gen:") -> key.removePrefix("gen:")
                    .takeIf { com.oea.launcher.wallpaper.OeaArtwork.isKnown(it) }
                    ?.let { Generated(it, com.oea.launcher.wallpaper.OeaArtwork.label(it)) }
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

    // ------------------------------------------------------------- drawn artwork
    // OEA draws these itself (OeaArtwork): a lock-screen picture made for each of
    // the five themes that would otherwise repeat their Home picture on the lock
    // screen. They are deliberately *not* in the wallpaper library — themes and
    // wallpapers never share a picture.

    val AURORA_LOCK = OeaImageRef.Generated("aurora_night", "Northern night")
    val GLASS_LOCK = OeaImageRef.Generated("glass_deep", "Deep glass")
    val RIDGE_LOCK = OeaImageRef.Generated("ridge_night", "Ridge night")
    val FOREST_LOCK = OeaImageRef.Generated("forest_mist", "Forest mist")
    val DUNE_LOCK = OeaImageRef.Generated("dune_dusk", "Dune dusk")
    // Expanded generated locks
    val CRIMSON_LOCK = OeaImageRef.Generated("crimson_night", "Crimson night")
    val TEAL_LOCK = OeaImageRef.Generated("teal_night", "Teal night")
    val AMBER_LOCK = OeaImageRef.Generated("amber_night", "Amber night")
    val VIOLET_LOCK = OeaImageRef.Generated("violet_night", "Violet night")
    val OCEAN_NIGHT_LOCK = OeaImageRef.Generated("ocean_night", "Ocean night")
    val SAND_LIGHT_LOCK = OeaImageRef.Generated("sand_light", "Sand light")
    val PINE_LOCK = OeaImageRef.Generated("pine_night", "Pine night")
    val STORM_LOCK = OeaImageRef.Generated("storm_night", "Storm night")
    val BLOOM_LOCK = OeaImageRef.Generated("bloom_night", "Bloom night")
    val ABYSS_LOCK = OeaImageRef.Generated("abyss_night", "Abyss night")
    // Wallpaper-art based for themes (distinct from wallpaper library usage)
    val CRIMSON_TIDE_HOME = OeaImageRef.Generated("crimson_tide", "Crimson tide")
    val EMERALD_HOME = OeaImageRef.Generated("emerald_depth", "Emerald depth")
    val SAPPHIRE_HOME = OeaImageRef.Generated("sapphire_drift", "Sapphire drift")
    val AMBER_HAZE_HOME = OeaImageRef.Generated("amber_haze", "Amber haze")
    val VIOLET_STORM_HOME = OeaImageRef.Generated("violet_storm", "Violet storm")
    val TEAL_LAGOON_HOME = OeaImageRef.Generated("teal_lagoon", "Teal lagoon")
    val SUNSET_RIDGE_HOME = OeaImageRef.Generated("sunset_ridge", "Sunset ridge")
    val FROSTED_PINE_HOME = OeaImageRef.Generated("frosted_pine", "Frosted pine")
    val DESERT_MIRAGE_HOME = OeaImageRef.Generated("desert_mirage", "Desert mirage")
    val MIDNIGHT_BLOOM_HOME = OeaImageRef.Generated("midnight_bloom", "Midnight bloom")
    val OCEAN_ABYSS_HOME = OeaImageRef.Generated("ocean_abyss", "Ocean abyss")
    val PAPER_LIGHT_HOME = OeaImageRef.Generated("paper_light", "Paper light")

    /** Every bundled picture, offered in the wallpaper library too. */
    val bundledImages: List<OeaImageRef.Bundled> = listOf(
        AURORA, LIQUID_GLASS, MOUNTAIN, FOREST, DESERT,
        NEON_HOME, NEON_LOCK, OCEAN_HOME, OCEAN_LOCK, SAKURA_HOME, SAKURA_LOCK,
        NEBULA_HOME, NEBULA_LOCK, GRAPHITE_HOME, GRAPHITE_LOCK,
    )

    fun bundled(name: String): OeaImageRef.Bundled? = bundledImages.firstOrNull { it.name == name }

    /**
     * Twenty polished themes: original 10 + 10 new distinct coordinated looks.
     * Themes provide coordinated appearance: same image for home & lock or different,
     * with colours matched. Wallpaper has its own distinct collection.
     */
    val builtIns: List<OeaThemeSpec> = listOf(
        OeaThemeSpec(
            "aurora", "Aurora", "Northern lights over a still lake", AURORA, AURORA_LOCK,
            accentDark = Color.rgb(95, 227, 192), accentLight = Color.rgb(0, 121, 107), surfaceTint = Color.rgb(24, 70, 82),
            mode = "dark", glassStyle = "liquid", iconShape = "squircle", dockStyle = "glass", pageEffect = "liquid",
        ),
        OeaThemeSpec(
            "liquid_glass", "Liquid Glass", "Flowing glass, frosted surfaces and liquid motion", LIQUID_GLASS, GLASS_LOCK,
            accentDark = Color.rgb(124, 196, 255), accentLight = Color.rgb(21, 101, 192), surfaceTint = Color.rgb(27, 42, 85),
            mode = "dark", glassStyle = "liquid", iconShape = "squircle", dockStyle = "glass", pageEffect = "liquid",
        ),
        OeaThemeSpec(
            "mountain_dawn", "Mountain Dawn", "First light on misty peaks", MOUNTAIN, RIDGE_LOCK,
            accentDark = Color.rgb(255, 180, 138), accentLight = Color.rgb(180, 83, 42), surfaceTint = Color.rgb(107, 74, 58),
            mode = "system", glassStyle = "classic", iconShape = "rounded", dockStyle = "glass", pageEffect = "classic",
        ),
        OeaThemeSpec(
            "forest_river", "Forest River", "Calm water through deep green woods", FOREST, FOREST_LOCK,
            accentDark = Color.rgb(139, 214, 160), accentLight = Color.rgb(46, 125, 79), surfaceTint = Color.rgb(47, 74, 52),
            mode = "dark", glassStyle = "classic", iconShape = "circle", dockStyle = "glass", pageEffect = "stack",
        ),
        OeaThemeSpec(
            "desert_coast", "Desert Coast", "Warm dunes meeting the sea", DESERT, DUNE_LOCK,
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
        // New 10 distinct themes using generated artwork - each has purpose
        OeaThemeSpec(
            "crimson_tide", "Crimson Tide", "Bold red waves with soft glow", CRIMSON_TIDE_HOME, CRIMSON_LOCK,
            accentDark = Color.rgb(255, 120, 140), accentLight = Color.rgb(180, 40, 70), surfaceTint = Color.rgb(64, 20, 32),
            mode = "dark", glassStyle = "liquid", iconShape = "squircle", dockStyle = "glass", pageEffect = "liquid",
        ),
        OeaThemeSpec(
            "emerald_forest", "Emerald Forest", "Deep emerald with misty layers", EMERALD_HOME, PINE_LOCK,
            accentDark = Color.rgb(120, 220, 160), accentLight = Color.rgb(40, 120, 80), surfaceTint = Color.rgb(20, 56, 40),
            mode = "dark", glassStyle = "classic", iconShape = "circle", dockStyle = "glass", pageEffect = "stack",
        ),
        OeaThemeSpec(
            "sapphire_drift", "Sapphire Drift", "Cool sapphire drift, calm and focused", SAPPHIRE_HOME, STORM_LOCK,
            accentDark = Color.rgb(140, 180, 255), accentLight = Color.rgb(40, 80, 180), surfaceTint = Color.rgb(20, 32, 64),
            mode = "dark", glassStyle = "liquid", iconShape = "rounded", dockStyle = "glass", pageEffect = "fade",
        ),
        OeaThemeSpec(
            "amber_haze", "Amber Haze", "Warm amber haze for reading", AMBER_HAZE_HOME, AMBER_LOCK,
            accentDark = Color.rgb(255, 200, 100), accentLight = Color.rgb(160, 100, 20), surfaceTint = Color.rgb(80, 50, 20),
            mode = "light", glassStyle = "classic", iconShape = "rounded", dockStyle = "solid", pageEffect = "zoom",
        ),
        OeaThemeSpec(
            "violet_storm", "Violet Storm", "Electric violet storm", VIOLET_STORM_HOME, VIOLET_LOCK,
            accentDark = Color.rgb(190, 140, 255), accentLight = Color.rgb(100, 50, 180), surfaceTint = Color.rgb(40, 20, 64),
            mode = "dark", glassStyle = "liquid", iconShape = "squircle", dockStyle = "glass", pageEffect = "cube",
        ),
        OeaThemeSpec(
            "teal_lagoon", "Teal Lagoon", "Calm teal lagoon, minimal distraction", TEAL_LAGOON_HOME, TEAL_LOCK,
            accentDark = Color.rgb(100, 230, 220), accentLight = Color.rgb(0, 120, 110), surfaceTint = Color.rgb(12, 56, 56),
            mode = "system", glassStyle = "liquid", iconShape = "circle", dockStyle = "glass", pageEffect = "liquid",
        ),
        OeaThemeSpec(
            "sunset_ridge", "Sunset Ridge", "Sunset over ridges, warm and vibrant", SUNSET_RIDGE_HOME, OCEAN_NIGHT_LOCK,
            accentDark = Color.rgb(255, 180, 100), accentLight = Color.rgb(180, 80, 30), surfaceTint = Color.rgb(80, 40, 24),
            mode = "dark", glassStyle = "classic", iconShape = "rounded", dockStyle = "glass", pageEffect = "depth",
        ),
        OeaThemeSpec(
            "midnight_bloom", "Midnight Bloom", "Midnight bloom with violet petals", MIDNIGHT_BLOOM_HOME, BLOOM_LOCK,
            accentDark = Color.rgb(220, 160, 255), accentLight = Color.rgb(120, 60, 180), surfaceTint = Color.rgb(48, 24, 64),
            mode = "dark", glassStyle = "liquid", iconShape = "squircle", dockStyle = "none", pageEffect = "paper",
        ),
        OeaThemeSpec(
            "ocean_abyss", "Ocean Abyss", "Deep ocean abyss, OLED friendly", OCEAN_ABYSS_HOME, ABYSS_LOCK,
            accentDark = Color.rgb(80, 180, 255), accentLight = Color.rgb(0, 100, 180), surfaceTint = Color.rgb(6, 20, 40),
            mode = "dark", glassStyle = "classic", iconShape = "square", dockStyle = "glass", pageEffect = "depth",
        ),
        OeaThemeSpec(
            "paper_light", "Paper Light", "Light paper, calm and readable", PAPER_LIGHT_HOME, SAND_LIGHT_LOCK,
            accentDark = Color.rgb(200, 190, 160), accentLight = Color.rgb(100, 90, 70), surfaceTint = Color.rgb(245, 240, 225),
            mode = "light", glassStyle = "classic", iconShape = "circle", dockStyle = "none", pageEffect = "paper",
        ),
    )

    val ICON_SHAPES = listOf("system" to "System", "circle" to "Circle", "squircle" to "Squircle", "rounded" to "Rounded", "teardrop" to "Teardrop", "square" to "Square")
    val DOCK_STYLES = listOf("glass" to "Glass", "solid" to "Solid", "none" to "None")
    val GLASS_STYLES = listOf("classic" to "Classic", "liquid" to "Liquid glass")
    val PAGE_EFFECTS = listOf("classic" to "Classic", "liquid" to "Liquid", "cube" to "Cube", "stack" to "Stack", "zoom" to "Zoom", "fade" to "Fade", "none" to "None", "depth" to "Depth", "paper" to "Paper")
    val MODES = listOf("system" to "Keep current", "dark" to "Dark", "light" to "Light")

    fun label(options: List<Pair<String, String>>, value: String) = options.firstOrNull { it.first == value }?.second ?: value
}
