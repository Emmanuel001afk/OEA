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
    val builtIn: Boolean = true
) {
    /** Home and lock screen share one picture (or a coordinated pair). */
    val sharedImagery: Boolean get() = home.key == lock.key
}

object OeaThemeCatalog {
    /**
     * A picture in the library that OEA draws itself.
     *
     * These used to point at bundled bitmap files, which meant the app shipped a
     * few megabytes of scenery and every wallpaper had one resolution. OEA's artwork
     * is a recipe instead (see OeaArtwork), so a "photograph" here is drawn at exactly
     * the device's pixel size, crisp on a 1080p phone and on a 1440p tablet, and the
     * APK carries no image data at all. `resId == 0` is what tells the decoders to
     * paint rather than open a stream.
     */
    private fun drawn(name: String, title: String) = OeaImageRef.Bundled(name, 0, title)

    val AURORA = drawn("aurora_night", "Aurora lake")
    val LIQUID_GLASS = drawn("glass_deep", "Liquid glass")
    val MOUNTAIN = drawn("ridge_night", "Mountain dawn")
    val FOREST = drawn("forest_mist", "Forest river")
    val DESERT = drawn("dune_dusk", "Desert coast")
    val NEON_HOME = drawn("neon_pulse", "Neon street")
    val NEON_LOCK = drawn("lock_neon", "Neon rain")
    val OCEAN_HOME = drawn("teal_lagoon", "Turquoise shore")
    val OCEAN_LOCK = drawn("lock_ocean", "Sunlit deep")
    val SAKURA_HOME = drawn("orchid_bloom", "Sakura branches")
    val SAKURA_LOCK = drawn("bloom_night", "Garden path")
    val NEBULA_HOME = drawn("nebula_core", "Violet nebula")
    val NEBULA_LOCK = drawn("orbit_dawn", "Ringed planet")
    val GRAPHITE_HOME = drawn("obsidian_mirror", "Graphite waves")
    val GRAPHITE_LOCK = drawn("lock_noir", "Graphite orbits")

    // Real quote artwork, bundled as density-independent WebP.
    val BORROWED_LIGHT = OeaImageRef.Bundled("quote_borrowed_light", R.drawable.oea_quote_borrowed_light, "Borrowed Light")
    val QUIET_GROWTH = OeaImageRef.Bundled("quote_quiet_growth", R.drawable.oea_quote_quiet_growth, "Quiet Growth")
    val TIDE_RELEASE = OeaImageRef.Bundled("quote_tide_and_release", R.drawable.oea_quote_tide_and_release, "Tide & Release")

    val QUOTE_AFTER_THE_STORM = OeaImageRef.Bundled("quote_after_the_storm", R.drawable.oea_quote_after_the_storm, "After the Storm")
    val QUOTE_ROOTS_AND_WINGS = OeaImageRef.Bundled("quote_roots_and_wings", R.drawable.oea_quote_roots_and_wings, "Roots & Wings")
    val QUOTE_UNFINISHED_STORY = OeaImageRef.Bundled("quote_unfinished_story", R.drawable.oea_quote_unfinished_story, "Unfinished Story")
    val QUOTE_SLOW_BECOMING = OeaImageRef.Bundled("quote_slow_becoming", R.drawable.oea_quote_slow_becoming, "Slow Becoming")
    val QUOTE_PRECIOUS_ORDINARY = OeaImageRef.Bundled("quote_precious_ordinary", R.drawable.oea_quote_precious_ordinary, "Precious Ordinary")
    val QUOTE_CHOOSE_PEACE = OeaImageRef.Bundled("quote_choose_peace", R.drawable.oea_quote_choose_peace, "Choose Peace")
    val QUOTE_LIGHT_YOU_CARRY = OeaImageRef.Bundled("quote_light_you_carry", R.drawable.oea_quote_light_you_carry, "The Light You Carry")
    val QUOTE_OPEN_HORIZON = OeaImageRef.Bundled("quote_open_horizon", R.drawable.oea_quote_open_horizon, "Open Horizon")

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
    // New lock wallpapers
    val NEON_LOCK_GEN = OeaImageRef.Generated("lock_neon", "Neon lock")
    val ARCTIC_LOCK_GEN = OeaImageRef.Generated("lock_arctic", "Arctic lock")
    val VOLCANIC_LOCK_GEN = OeaImageRef.Generated("lock_volcanic", "Volcanic lock")
    val ZEN_LOCK_GEN = OeaImageRef.Generated("lock_zen", "Zen lock")
    val OBSIDIAN_LOCK_GEN = OeaImageRef.Generated("lock_obsidian", "Obsidian lock")
    val PRISM_LOCK_GEN = OeaImageRef.Generated("lock_prism", "Prism lock")
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
    // Mass expansion 16 new homes for peak beauty
    val NEON_PULSE_HOME = OeaImageRef.Generated("neon_pulse", "Neon pulse")
    val ARCTIC_AURORA_HOME = OeaImageRef.Generated("arctic_aurora", "Arctic aurora")
    val VOLCANIC_GLOW_HOME = OeaImageRef.Generated("volcanic_glow", "Volcanic glow")
    val CYBER_GRID_HOME = OeaImageRef.Generated("cyber_grid", "Cyber grid")
    val LUSH_JUNGLE_HOME = OeaImageRef.Generated("lush_jungle", "Lush jungle")
    val STARLIGHT_DESERT_HOME = OeaImageRef.Generated("starlight_desert", "Starlight desert")
    val CORAL_REEF_HOME = OeaImageRef.Generated("coral_reef", "Coral reef")
    val MONSOON_CLOUD_HOME = OeaImageRef.Generated("monsoon_cloud", "Monsoon cloud")
    val GOLDEN_HOUR_HOME = OeaImageRef.Generated("golden_hour", "Golden hour")
    val FROSTED_LAKE_HOME = OeaImageRef.Generated("frosted_lake", "Frosted lake")
    val OBSIDIAN_MIRROR_HOME = OeaImageRef.Generated("obsidian_mirror", "Obsidian mirror")
    val PRISM_SHARD_HOME = OeaImageRef.Generated("prism_shard", "Prism shard")
    val TUNDRA_DAWN_HOME = OeaImageRef.Generated("tundra_dawn", "Tundra dawn")
    val MAGMA_FLOW_HOME = OeaImageRef.Generated("magma_flow", "Magma flow")
    val ZEN_GARDEN_HOME = OeaImageRef.Generated("zen_garden", "Zen garden")
    val NEBULA_CORE_HOME = OeaImageRef.Generated("nebula_core", "Nebula core")

    /** Every bundled picture, offered in the wallpaper library too. */
    val bundledImages: List<OeaImageRef.Bundled> = listOf(
        BORROWED_LIGHT, QUIET_GROWTH, TIDE_RELEASE,
        QUOTE_AFTER_THE_STORM, QUOTE_ROOTS_AND_WINGS, QUOTE_UNFINISHED_STORY, QUOTE_SLOW_BECOMING, QUOTE_PRECIOUS_ORDINARY, QUOTE_CHOOSE_PEACE, QUOTE_LIGHT_YOU_CARRY, QUOTE_OPEN_HORIZON,
        AURORA, LIQUID_GLASS, MOUNTAIN, FOREST, DESERT,
        NEON_HOME, NEON_LOCK, OCEAN_HOME, OCEAN_LOCK, SAKURA_HOME, SAKURA_LOCK,
        NEBULA_HOME, NEBULA_LOCK, GRAPHITE_HOME, GRAPHITE_LOCK
)

    fun bundled(name: String): OeaImageRef.Bundled? = bundledImages.firstOrNull { it.name == name }

    /**
     * Thirty polished themes: original 10 + 10 + 10 new distinct coordinated looks.
     * Themes provide coordinated appearance: same image for home & lock or different,
     * with colours matched. Wallpaper has its own distinct collection (now 66 wallpapers).
     * Proper previews with larger distinct collections, fixed transparent/white look.
     */
    val builtIns: List<OeaThemeSpec> = listOf(
        OeaThemeSpec(
            "quote_after_the_storm", "After the Storm", "You have survived days you thought would break you. Keep going.", QUOTE_AFTER_THE_STORM, QUOTE_AFTER_THE_STORM,
            accentDark = Color.rgb(241, 204, 147), accentLight = Color.rgb(127, 83, 34), surfaceTint = Color.rgb(31, 42, 48),
            mode = "dark", glassStyle = "classic", iconShape = "rounded", dockStyle = "glass", pageEffect = "fade"
        ),
        OeaThemeSpec(
            "quote_roots_and_wings", "Roots & Wings", "Remember your roots. Do not forget you can still grow.", QUOTE_ROOTS_AND_WINGS, QUOTE_ROOTS_AND_WINGS,
            accentDark = Color.rgb(224, 205, 143), accentLight = Color.rgb(111, 93, 34), surfaceTint = Color.rgb(35, 47, 32),
            mode = "dark", glassStyle = "classic", iconShape = "circle", dockStyle = "glass", pageEffect = "fade"
        ),
        OeaThemeSpec(
            "quote_unfinished_story", "Unfinished Story", "A hard chapter is not the end of your story.", QUOTE_UNFINISHED_STORY, QUOTE_UNFINISHED_STORY,
            accentDark = Color.rgb(195, 181, 248), accentLight = Color.rgb(92, 68, 151), surfaceTint = Color.rgb(32, 29, 65),
            mode = "dark", glassStyle = "liquid", iconShape = "squircle", dockStyle = "glass", pageEffect = "fade"
        ),
        OeaThemeSpec(
            "quote_slow_becoming", "Slow Becoming", "You are not late. You are still becoming.", QUOTE_SLOW_BECOMING, QUOTE_SLOW_BECOMING,
            accentDark = Color.rgb(231, 189, 190), accentLight = Color.rgb(139, 76, 93), surfaceTint = Color.rgb(31, 54, 58),
            mode = "dark", glassStyle = "liquid", iconShape = "rounded", dockStyle = "glass", pageEffect = "fade"
        ),
        OeaThemeSpec(
            "quote_precious_ordinary", "Precious Ordinary", "One day, the ordinary moments will be the ones you miss most.", QUOTE_PRECIOUS_ORDINARY, QUOTE_PRECIOUS_ORDINARY,
            accentDark = Color.rgb(239, 187, 123), accentLight = Color.rgb(137, 80, 31), surfaceTint = Color.rgb(59, 37, 25),
            mode = "dark", glassStyle = "classic", iconShape = "rounded", dockStyle = "glass", pageEffect = "fade"
        ),
        OeaThemeSpec(
            "quote_choose_peace", "Choose Peace", "Not every battle deserves your soul. Choose peace.", QUOTE_CHOOSE_PEACE, QUOTE_CHOOSE_PEACE,
            accentDark = Color.rgb(159, 218, 185), accentLight = Color.rgb(37, 103, 80), surfaceTint = Color.rgb(20, 52, 43),
            mode = "dark", glassStyle = "liquid", iconShape = "circle", dockStyle = "glass", pageEffect = "fade"
        ),
        OeaThemeSpec(
            "quote_light_you_carry", "The Light You Carry", "When the road grows dark, be gentle with the light you carry.", QUOTE_LIGHT_YOU_CARRY, QUOTE_LIGHT_YOU_CARRY,
            accentDark = Color.rgb(238, 200, 136), accentLight = Color.rgb(132, 87, 34), surfaceTint = Color.rgb(25, 35, 61),
            mode = "dark", glassStyle = "classic", iconShape = "squircle", dockStyle = "glass", pageEffect = "fade"
        ),
        OeaThemeSpec(
            "quote_open_horizon", "Open Horizon", "You can honour where you have been without staying there.", QUOTE_OPEN_HORIZON, QUOTE_OPEN_HORIZON,
            accentDark = Color.rgb(156, 216, 231), accentLight = Color.rgb(34, 111, 139), surfaceTint = Color.rgb(22, 44, 65),
            mode = "dark", glassStyle = "liquid", iconShape = "rounded", dockStyle = "glass", pageEffect = "fade"
        ),
        OeaThemeSpec(
            "quote_borrowed_light", "Borrowed Light", "Time is borrowed. Love is what we leave behind.", BORROWED_LIGHT, BORROWED_LIGHT,
            accentDark = Color.rgb(244, 201, 130), accentLight = Color.rgb(137, 85, 29), surfaceTint = Color.rgb(57, 39, 28),
            mode = "dark", glassStyle = "classic", iconShape = "rounded", dockStyle = "glass", pageEffect = "fade"
        ),
        OeaThemeSpec(
            "quote_quiet_growth", "Quiet Growth", "Some growth is too quiet for the world to hear. Keep growing.", QUIET_GROWTH, QUIET_GROWTH,
            accentDark = Color.rgb(167, 221, 166), accentLight = Color.rgb(40, 109, 68), surfaceTint = Color.rgb(22, 51, 39),
            mode = "dark", glassStyle = "liquid", iconShape = "circle", dockStyle = "glass", pageEffect = "fade"
        ),
        OeaThemeSpec(
            "quote_tide_release", "Tide & Release", "Let go of what has ended. Make room for what can begin.", TIDE_RELEASE, TIDE_RELEASE,
            accentDark = Color.rgb(161, 214, 229), accentLight = Color.rgb(39, 100, 134), surfaceTint = Color.rgb(24, 37, 59),
            mode = "dark", glassStyle = "liquid", iconShape = "squircle", dockStyle = "glass", pageEffect = "fade"
        ),
        OeaThemeSpec(
            "aurora", "Aurora", "Northern lights over a still lake", AURORA, AURORA_LOCK,
            accentDark = Color.rgb(95, 227, 192), accentLight = Color.rgb(0, 121, 107), surfaceTint = Color.rgb(24, 70, 82),
            mode = "dark", glassStyle = "liquid", iconShape = "squircle", dockStyle = "glass", pageEffect = "liquid"
),
        OeaThemeSpec(
            "liquid_glass", "Liquid Glass", "Flowing glass, frosted surfaces and liquid motion", LIQUID_GLASS, GLASS_LOCK,
            accentDark = Color.rgb(124, 196, 255), accentLight = Color.rgb(21, 101, 192), surfaceTint = Color.rgb(27, 42, 85),
            mode = "dark", glassStyle = "liquid", iconShape = "squircle", dockStyle = "glass", pageEffect = "liquid"
),
        OeaThemeSpec(
            "mountain_dawn", "Mountain Dawn", "First light on misty peaks", MOUNTAIN, RIDGE_LOCK,
            accentDark = Color.rgb(255, 180, 138), accentLight = Color.rgb(180, 83, 42), surfaceTint = Color.rgb(107, 74, 58),
            mode = "system", glassStyle = "classic", iconShape = "rounded", dockStyle = "glass", pageEffect = "classic"
),
        OeaThemeSpec(
            "forest_river", "Forest River", "Calm water through deep green woods", FOREST, FOREST_LOCK,
            accentDark = Color.rgb(139, 214, 160), accentLight = Color.rgb(46, 125, 79), surfaceTint = Color.rgb(47, 74, 52),
            mode = "dark", glassStyle = "classic", iconShape = "circle", dockStyle = "glass", pageEffect = "stack"
),
        OeaThemeSpec(
            "desert_coast", "Desert Coast", "Warm dunes meeting the sea", DESERT, DUNE_LOCK,
            accentDark = Color.rgb(242, 194, 123), accentLight = Color.rgb(140, 90, 0), surfaceTint = Color.rgb(107, 82, 50),
            mode = "system", glassStyle = "classic", iconShape = "rounded", dockStyle = "solid", pageEffect = "zoom"
),
        OeaThemeSpec(
            "neon_city", "Neon City", "Rainy streets at night, neon reflections on lock", NEON_HOME, NEON_LOCK,
            accentDark = Color.rgb(255, 92, 200), accentLight = Color.rgb(176, 32, 127), surfaceTint = Color.rgb(58, 31, 79),
            mode = "dark", glassStyle = "liquid", iconShape = "squircle", dockStyle = "glass", pageEffect = "cube"
),
        OeaThemeSpec(
            "ocean", "Ocean", "Turquoise shore on Home, sunlit depths on lock", OCEAN_HOME, OCEAN_LOCK,
            accentDark = Color.rgb(79, 216, 232), accentLight = Color.rgb(0, 120, 138), surfaceTint = Color.rgb(15, 59, 74),
            mode = "system", glassStyle = "liquid", iconShape = "circle", dockStyle = "glass", pageEffect = "liquid"
),
        OeaThemeSpec(
            "sakura", "Sakura", "Blossoms on Home, a lantern-lit garden on lock", SAKURA_HOME, SAKURA_LOCK,
            accentDark = Color.rgb(255, 158, 196), accentLight = Color.rgb(176, 48, 106), surfaceTint = Color.rgb(90, 52, 70),
            mode = "light", glassStyle = "liquid", iconShape = "rounded", dockStyle = "glass", pageEffect = "fade"
),
        OeaThemeSpec(
            "nebula", "Nebula", "A violet nebula on Home, a ringed planet on lock", NEBULA_HOME, NEBULA_LOCK,
            accentDark = Color.rgb(183, 156, 255), accentLight = Color.rgb(91, 63, 196), surfaceTint = Color.rgb(42, 31, 79),
            mode = "dark", glassStyle = "liquid", iconShape = "circle", dockStyle = "glass", pageEffect = "zoom"
),
        OeaThemeSpec(
            "graphite", "Graphite", "Quiet monochrome waves and orbits", GRAPHITE_HOME, GRAPHITE_LOCK,
            accentDark = Color.rgb(215, 220, 228), accentLight = Color.rgb(60, 67, 80), surfaceTint = Color.rgb(35, 38, 44),
            mode = "dark", glassStyle = "classic", iconShape = "square", dockStyle = "solid", pageEffect = "classic"
),
        // New 10 distinct themes using generated artwork - each has purpose
        OeaThemeSpec(
            "crimson_tide", "Crimson Tide", "Bold red waves with soft glow", CRIMSON_TIDE_HOME, CRIMSON_LOCK,
            accentDark = Color.rgb(255, 120, 140), accentLight = Color.rgb(180, 40, 70), surfaceTint = Color.rgb(64, 20, 32),
            mode = "dark", glassStyle = "liquid", iconShape = "squircle", dockStyle = "glass", pageEffect = "liquid"
),
        OeaThemeSpec(
            "emerald_forest", "Emerald Forest", "Deep emerald with misty layers", EMERALD_HOME, PINE_LOCK,
            accentDark = Color.rgb(120, 220, 160), accentLight = Color.rgb(40, 120, 80), surfaceTint = Color.rgb(20, 56, 40),
            mode = "dark", glassStyle = "classic", iconShape = "circle", dockStyle = "glass", pageEffect = "stack"
),
        OeaThemeSpec(
            "sapphire_drift", "Sapphire Drift", "Cool sapphire drift, calm and focused", SAPPHIRE_HOME, STORM_LOCK,
            accentDark = Color.rgb(140, 180, 255), accentLight = Color.rgb(40, 80, 180), surfaceTint = Color.rgb(20, 32, 64),
            mode = "dark", glassStyle = "liquid", iconShape = "rounded", dockStyle = "glass", pageEffect = "fade"
),
        OeaThemeSpec(
            "amber_haze", "Amber Haze", "Warm amber haze for reading", AMBER_HAZE_HOME, AMBER_LOCK,
            accentDark = Color.rgb(255, 200, 100), accentLight = Color.rgb(160, 100, 20), surfaceTint = Color.rgb(80, 50, 20),
            mode = "light", glassStyle = "classic", iconShape = "rounded", dockStyle = "solid", pageEffect = "zoom"
),
        OeaThemeSpec(
            "violet_storm", "Violet Storm", "Electric violet storm", VIOLET_STORM_HOME, VIOLET_LOCK,
            accentDark = Color.rgb(190, 140, 255), accentLight = Color.rgb(100, 50, 180), surfaceTint = Color.rgb(40, 20, 64),
            mode = "dark", glassStyle = "liquid", iconShape = "squircle", dockStyle = "glass", pageEffect = "cube"
),
        OeaThemeSpec(
            "teal_lagoon", "Teal Lagoon", "Calm teal lagoon, minimal distraction", TEAL_LAGOON_HOME, TEAL_LOCK,
            accentDark = Color.rgb(100, 230, 220), accentLight = Color.rgb(0, 120, 110), surfaceTint = Color.rgb(12, 56, 56),
            mode = "system", glassStyle = "liquid", iconShape = "circle", dockStyle = "glass", pageEffect = "liquid"
),
        OeaThemeSpec(
            "sunset_ridge", "Sunset Ridge", "Sunset over ridges, warm and vibrant", SUNSET_RIDGE_HOME, OCEAN_NIGHT_LOCK,
            accentDark = Color.rgb(255, 180, 100), accentLight = Color.rgb(180, 80, 30), surfaceTint = Color.rgb(80, 40, 24),
            mode = "dark", glassStyle = "classic", iconShape = "rounded", dockStyle = "glass", pageEffect = "depth"
),
        OeaThemeSpec(
            "midnight_bloom", "Midnight Bloom", "Midnight bloom with violet petals", MIDNIGHT_BLOOM_HOME, BLOOM_LOCK,
            accentDark = Color.rgb(220, 160, 255), accentLight = Color.rgb(120, 60, 180), surfaceTint = Color.rgb(48, 24, 64),
            mode = "dark", glassStyle = "liquid", iconShape = "squircle", dockStyle = "none", pageEffect = "paper"
),
        OeaThemeSpec(
            "ocean_abyss", "Ocean Abyss", "Deep ocean abyss, OLED friendly", OCEAN_ABYSS_HOME, ABYSS_LOCK,
            accentDark = Color.rgb(80, 180, 255), accentLight = Color.rgb(0, 100, 180), surfaceTint = Color.rgb(6, 20, 40),
            mode = "dark", glassStyle = "classic", iconShape = "square", dockStyle = "glass", pageEffect = "depth"
),
        OeaThemeSpec(
            "paper_light", "Paper Light", "Light paper, calm and readable", PAPER_LIGHT_HOME, SAND_LIGHT_LOCK,
            accentDark = Color.rgb(200, 190, 160), accentLight = Color.rgb(100, 90, 70), surfaceTint = Color.rgb(245, 240, 225),
            mode = "light", glassStyle = "classic", iconShape = "circle", dockStyle = "none", pageEffect = "paper"
),
        // Mass expansion 10 more distinct themes for peak — total 30
        OeaThemeSpec(
            "neon_pulse", "Neon Pulse", "Pulsing neon lines, vibrant energetic", NEON_PULSE_HOME, NEON_LOCK_GEN,
            accentDark = Color.rgb(255, 100, 220), accentLight = Color.rgb(180, 30, 140), surfaceTint = Color.rgb(48, 12, 48),
            mode = "dark", glassStyle = "liquid", iconShape = "squircle", dockStyle = "glass", pageEffect = "zoom"
),
        OeaThemeSpec(
            "arctic_aurora", "Arctic Aurora", "Arctic aurora with ice glow", ARCTIC_AURORA_HOME, ARCTIC_LOCK_GEN,
            accentDark = Color.rgb(120, 240, 255), accentLight = Color.rgb(0, 130, 160), surfaceTint = Color.rgb(12, 40, 56),
            mode = "dark", glassStyle = "liquid", iconShape = "rounded", dockStyle = "glass", pageEffect = "liquid"
),
        OeaThemeSpec(
            "volcanic_glow", "Volcanic Glow", "Volcanic glow with ember and ash", VOLCANIC_GLOW_HOME, VOLCANIC_LOCK_GEN,
            accentDark = Color.rgb(255, 130, 60), accentLight = Color.rgb(180, 50, 20), surfaceTint = Color.rgb(64, 20, 12),
            mode = "dark", glassStyle = "classic", iconShape = "rounded", dockStyle = "solid", pageEffect = "cube"
),
        OeaThemeSpec(
            "cyber_grid", "Cyber Grid", "Cyberpunk grid with neon perspective", CYBER_GRID_HOME, OBSIDIAN_LOCK_GEN,
            accentDark = Color.rgb(100, 255, 180), accentLight = Color.rgb(0, 160, 90), surfaceTint = Color.rgb(8, 32, 24),
            mode = "dark", glassStyle = "liquid", iconShape = "square", dockStyle = "glass", pageEffect = "stack"
),
        OeaThemeSpec(
            "lush_jungle", "Lush Jungle", "Jungle canopy with light shafts", LUSH_JUNGLE_HOME, PINE_LOCK,
            accentDark = Color.rgb(100, 220, 140), accentLight = Color.rgb(20, 120, 60), surfaceTint = Color.rgb(16, 48, 24),
            mode = "dark", glassStyle = "classic", iconShape = "circle", dockStyle = "glass", pageEffect = "fade"
),
        OeaThemeSpec(
            "coral_reef", "Coral Reef", "Underwater coral reef with sun rays", CORAL_REEF_HOME, OCEAN_NIGHT_LOCK,
            accentDark = Color.rgb(255, 150, 190), accentLight = Color.rgb(180, 50, 90), surfaceTint = Color.rgb(64, 24, 32),
            mode = "system", glassStyle = "liquid", iconShape = "circle", dockStyle = "glass", pageEffect = "liquid"
),
        OeaThemeSpec(
            "golden_hour", "Golden Hour", "Golden hour over fields, warm bright", GOLDEN_HOUR_HOME, ZEN_LOCK_GEN,
            accentDark = Color.rgb(255, 215, 100), accentLight = Color.rgb(160, 100, 20), surfaceTint = Color.rgb(80, 50, 20),
            mode = "light", glassStyle = "classic", iconShape = "rounded", dockStyle = "solid", pageEffect = "zoom"
),
        OeaThemeSpec(
            "obsidian_mirror", "Obsidian Mirror", "Black obsidian mirror, OLED pure", OBSIDIAN_MIRROR_HOME, OBSIDIAN_LOCK_GEN,
            accentDark = Color.rgb(210, 210, 220), accentLight = Color.rgb(80, 80, 90), surfaceTint = Color.rgb(10, 10, 14),
            mode = "dark", glassStyle = "classic", iconShape = "square", dockStyle = "none", pageEffect = "none"
),
        OeaThemeSpec(
            "prism_shard", "Prism Shard", "Prism shards with rainbow refractions", PRISM_SHARD_HOME, PRISM_LOCK_GEN,
            accentDark = Color.rgb(190, 150, 255), accentLight = Color.rgb(90, 60, 180), surfaceTint = Color.rgb(32, 20, 56),
            mode = "dark", glassStyle = "liquid", iconShape = "squircle", dockStyle = "glass", pageEffect = "depth"
),
        OeaThemeSpec(
            "nebula_core", "Nebula Core", "Nebula core star formation, cosmic", NEBULA_CORE_HOME, ABYSS_LOCK,
            accentDark = Color.rgb(210, 130, 255), accentLight = Color.rgb(100, 40, 180), surfaceTint = Color.rgb(32, 12, 48),
            mode = "dark", glassStyle = "liquid", iconShape = "circle", dockStyle = "glass", pageEffect = "zoom"
)
)

    val ICON_SHAPES = listOf("system" to "System", "circle" to "Circle", "squircle" to "Squircle", "rounded" to "Rounded", "teardrop" to "Teardrop", "square" to "Square")
    val DOCK_STYLES = listOf("glass" to "Glass", "solid" to "Solid", "none" to "None")
    val GLASS_STYLES = listOf("classic" to "Classic", "liquid" to "Liquid glass")
    val PAGE_EFFECTS = listOf("classic" to "Classic", "liquid" to "Liquid", "cube" to "Cube", "stack" to "Stack", "zoom" to "Zoom", "fade" to "Fade", "none" to "None", "depth" to "Depth", "paper" to "Paper")
    val MODES = listOf("system" to "Keep current", "dark" to "Dark", "light" to "Light")

    fun label(options: List<Pair<String, String>>, value: String) = options.firstOrNull { it.first == value }?.second ?: value
}
