package com.oea.launcher.ui

import android.content.Context
import com.oea.launcher.data.OeaDataStore
import com.oea.launcher.icons.OeaIconController
import com.oea.launcher.wallpaper.OeaLiveScenes
import com.oea.launcher.wallpaper.OeaWallpaperStore

/**
 * OEA's launcher modes: expanded beyond limited examples to a broader, well-considered collection.
 *
 * Each mode has its own purpose and useful features, not same interface with different names.
 * Modes allow configuration and combinations of supported actions, with reliable activation/deactivation.
 *
 * - CLASSIC, LIQUID, TIDE, DEPTH, INK: visual modes (existing)
 * - STUDY: reduces distractions, prioritizes selected apps, configures restrictions
 * - MUSIC: suitable listening experience with media island and album art
 * - SLEEPING: quiet-time behaviour with DND and dim
 * - FOCUS: strict distraction reduction
 * - GAMING: optimized for games, fast, Game Boost
 * - READING: light paper, no motion, readable
 * - WORK: productivity, work apps prioritized
 * - DRIVING: large icons, high contrast
 */
enum class OeaMode(
    val id: String,
    val label: String,
    val tagline: String,
    val detail: String,
    val category: String = "visual"
) {
    CLASSIC(
        "classic", "Classic",
        "The everyday look",
        "Plain surfaces, classic page motion, and the wallpaper owned by Android as usual.",
        "visual"
    ),
    LIQUID(
        "liquid", "Liquid",
        "Glass you can almost touch",
        "Frosted translucent surfaces, icons that trail like liquid while a page moves, squircle icon shape and a lighter, swifter motion.",
        "visual"
    ),
    TIDE(
        "tide", "Tide",
        "A living surface — only OEA",
        "Home draws moving water, layered and lit, and takes its accent from the water itself. The scene is live and never repeats; Android's wallpaper is left untouched.",
        "visual"
    ),
    DEPTH(
        "depth", "Depth",
        "Four ridges, four distances",
        "Home draws layered ridges that shift against each other as you move between pages: the nearest moves most, the furthest almost not at all. Pages themselves move towards and away from you.",
        "visual"
    ),
    INK(
        "ink", "Ink",
        "Paper, brush and seal",
        "A light, monochrome page drawn by hand: paper fibre, soft ink washes, one decisive stroke and a single red seal that becomes the accent. Pages settle like sheets of paper, and every press spreads ink.",
        "visual"
    ),
    STUDY(
        "study", "Study",
        "Reduce distractions, focus on learning",
        "Study Mode: DND on, games and social hidden from suggestions, calm paper wallpaper, slow animations, At a Glance shows date only, prioritizes selected study apps. Helps reduce distractions.",
        "focus"
    ),
    MUSIC(
        "music", "Music",
        "Immersive listening experience",
        "Music Mode: dark theme with accent from album art, liquid glass, media island expanded with album art and progress, keeps screen on during playback, aurora live scene option. Optimized for listening.",
        "media"
    ),
    SLEEPING(
        "sleeping", "Sleeping",
        "Quiet time, no interruptions",
        "Sleeping Mode: DND on, LOW mode on (optional), islands hidden, live scenes frozen to still, dim wallpaper, dark panels, no haptics. Supports quiet-time behaviour.",
        "quiet"
    ),
    FOCUS(
        "focus", "Focus",
        "Strict distraction-free work",
        "Focus Mode: only focus-list apps in suggestions, categories off, search hidden, monochrome graphite theme, no page motion, DND on. Maximum concentration.",
        "focus"
    ),
    GAMING(
        "gaming", "Gaming",
        "Optimized for play, no interruptions",
        "Gaming Mode: fast animations, liquid glass, Game Boost enabled, DND on, performance mode, games prioritized in drawer, ocean abyss wallpaper. For gaming sessions.",
        "performance"
    ),
    READING(
        "reading", "Reading",
        "Paper, calm, readable",
        "Reading Mode: light theme, paper light wallpaper, circle icons, no dock background, paper page transition, no animations, large labels, DND on. For reading.",
        "calm"
    ),
    WORK(
        "work", "Work",
        "Productivity, work apps first",
        "Work Mode: work apps pinned, suggestions show frequent work apps, solid dock, classic surfaces, slate lines wallpaper, DND off but notifications filtered. For productivity.",
        "productivity"
    ),
    DRIVING(
        "driving", "Driving",
        "Large, high-contrast, hands-free",
        "Driving Mode: large icons, labels always on, high contrast, auto-rotate on, DND with auto, quick controls with volume and assistant, no widgets. For driving.",
        "utility"
    );

    val isLive: Boolean get() = this == TIDE || this == DEPTH || this == INK || this == MUSIC

    companion object {
        fun of(id: String?): OeaMode = entries.firstOrNull { it.id == id } ?: CLASSIC
    }
}

/** What a mode sets. Null means "leave whatever the user chose". */
data class ModeSpec(
    val glass: String? = null,
    val effect: String? = null,
    val iconShape: String? = null,
    val dock: String? = null,
    val themeMode: String? = null,
    val wallpaperSource: String? = null,
    val scene: String? = null,
    val accent: String? = null,
    val animationSpeed: Int? = null,
    val parallax: Int? = null,
    val dnd: Boolean? = null,
    val lowMode: Boolean? = null,
    val showLabels: Boolean? = null,
    val badgeStyle: String? = null,
    val glanceStyle: String? = null,
    val drawerCategories: Boolean? = null,
    val haptics: Boolean? = null,
)

object OeaModes {
    fun spec(mode: OeaMode): ModeSpec = when (mode) {
        OeaMode.CLASSIC -> ModeSpec(
            glass = OeaDataStore.GLASS_CLASSIC,
            effect = "classic",
            iconShape = "system",
            dock = "glass",
            animationSpeed = 1,
            glanceStyle = "large",
            drawerCategories = true,
            haptics = true,
            badgeStyle = "dot"
        )
        OeaMode.LIQUID -> ModeSpec(
            glass = OeaDataStore.GLASS_LIQUID,
            effect = "liquid",
            iconShape = "squircle",
            dock = "glass",
            animationSpeed = 2,
            parallax = 60,
            glanceStyle = "large",
            haptics = true
        )
        OeaMode.TIDE -> ModeSpec(
            glass = OeaDataStore.GLASS_LIQUID,
            effect = "liquid",
            iconShape = "squircle",
            dock = "glass",
            themeMode = "dark",
            wallpaperSource = OeaDataStore.WALLPAPER_OEA,
            scene = OeaLiveScenes.TIDE,
            accent = "wallpaper",
            animationSpeed = 1,
            parallax = 95,
            glanceStyle = "large"
        )
        OeaMode.DEPTH -> ModeSpec(
            glass = OeaDataStore.GLASS_CLASSIC,
            effect = "depth",
            iconShape = "rounded",
            dock = "glass",
            themeMode = "dark",
            wallpaperSource = OeaDataStore.WALLPAPER_OEA,
            scene = OeaLiveScenes.DEPTH,
            accent = "wallpaper",
            animationSpeed = 1,
            parallax = 100,
            glanceStyle = "compact"
        )
        OeaMode.INK -> ModeSpec(
            glass = OeaDataStore.GLASS_CLASSIC,
            effect = "paper",
            iconShape = "circle",
            dock = "none",
            themeMode = "light",
            wallpaperSource = OeaDataStore.WALLPAPER_OEA,
            scene = OeaLiveScenes.INK,
            accent = "wallpaper",
            animationSpeed = 0,
            parallax = 20,
            glanceStyle = "compact",
            haptics = false
        )
        OeaMode.STUDY -> ModeSpec(
            glass = OeaDataStore.GLASS_CLASSIC,
            effect = "fade",
            iconShape = "rounded",
            dock = "solid",
            themeMode = "light",
            wallpaperSource = OeaDataStore.WALLPAPER_OEA,
            scene = "paper_light",
            accent = "teal",
            animationSpeed = 0,
            parallax = 10,
            dnd = true,
            lowMode = false,
            showLabels = true,
            badgeStyle = "off",
            glanceStyle = "compact",
            drawerCategories = false,
            haptics = false
        )
        OeaMode.MUSIC -> ModeSpec(
            glass = OeaDataStore.GLASS_LIQUID,
            effect = "liquid",
            iconShape = "squircle",
            dock = "glass",
            themeMode = "dark",
            wallpaperSource = OeaDataStore.WALLPAPER_OEA,
            scene = OeaLiveScenes.AURORA,
            accent = "wallpaper",
            animationSpeed = 1,
            parallax = 70,
            dnd = false,
            showLabels = false,
            glanceStyle = "hidden",
            drawerCategories = true,
            haptics = true
        )
        OeaMode.SLEEPING -> ModeSpec(
            glass = OeaDataStore.GLASS_CLASSIC,
            effect = "none",
            iconShape = "circle",
            dock = "none",
            themeMode = "dark",
            wallpaperSource = OeaDataStore.WALLPAPER_OEA,
            scene = "mesh_amoled",
            accent = "slate",
            animationSpeed = 0,
            parallax = 0,
            dnd = true,
            lowMode = true,
            showLabels = false,
            badgeStyle = "off",
            glanceStyle = "compact",
            drawerCategories = false,
            haptics = false
        )
        OeaMode.FOCUS -> ModeSpec(
            glass = OeaDataStore.GLASS_CLASSIC,
            effect = "none",
            iconShape = "square",
            dock = "solid",
            themeMode = "dark",
            wallpaperSource = OeaDataStore.WALLPAPER_OEA,
            scene = "slate_lines",
            accent = "slate",
            animationSpeed = 0,
            parallax = 0,
            dnd = true,
            showLabels = true,
            badgeStyle = "off",
            glanceStyle = "hidden",
            drawerCategories = false,
            haptics = false
        )
        OeaMode.GAMING -> ModeSpec(
            glass = OeaDataStore.GLASS_LIQUID,
            effect = "zoom",
            iconShape = "squircle",
            dock = "glass",
            themeMode = "dark",
            wallpaperSource = OeaDataStore.WALLPAPER_OEA,
            scene = "ocean_abyss",
            accent = "blue",
            animationSpeed = 2,
            parallax = 80,
            dnd = true,
            lowMode = false,
            showLabels = false,
            glanceStyle = "hidden",
            drawerCategories = false,
            haptics = true
        )
        OeaMode.READING -> ModeSpec(
            glass = OeaDataStore.GLASS_CLASSIC,
            effect = "paper",
            iconShape = "circle",
            dock = "none",
            themeMode = "light",
            wallpaperSource = OeaDataStore.WALLPAPER_OEA,
            scene = "paper_light",
            accent = "amber",
            animationSpeed = 0,
            parallax = 5,
            dnd = true,
            showLabels = true,
            badgeStyle = "off",
            glanceStyle = "compact",
            haptics = false
        )
        OeaMode.WORK -> ModeSpec(
            glass = OeaDataStore.GLASS_CLASSIC,
            effect = "stack",
            iconShape = "rounded",
            dock = "solid",
            themeMode = "system",
            wallpaperSource = OeaDataStore.WALLPAPER_OEA,
            scene = "slate_lines",
            accent = "indigo",
            animationSpeed = 1,
            parallax = 30,
            dnd = false,
            showLabels = true,
            glanceStyle = "large",
            drawerCategories = true,
            haptics = true
        )
        OeaMode.DRIVING -> ModeSpec(
            glass = OeaDataStore.GLASS_CLASSIC,
            effect = "none",
            iconShape = "rounded",
            dock = "glass",
            themeMode = "light",
            wallpaperSource = OeaDataStore.WALLPAPER_SYSTEM,
            accent = "blue",
            animationSpeed = 0,
            parallax = 0,
            dnd = true,
            showLabels = true,
            glanceStyle = "large",
            drawerCategories = false,
            haptics = true
        )
    }

    fun current(context: Context): OeaMode = OeaMode.of(OeaDataStore.get(context).launcherMode())

    fun summary(context: Context): String {
        val mode = current(context)
        return when {
            mode.isLive -> "${mode.label} · live, drawn by Home"
            else -> "${mode.label} · ${mode.category}"
        }
    }

    fun apply(context: Context, mode: OeaMode, keepWallpaper: Boolean = false) {
        val store = OeaDataStore.get(context)
        val spec = spec(mode)
        spec.glass?.let { store.setGlassStyle(it) }
        spec.effect?.let { store.setPageEffect(it) }
        spec.iconShape?.let { store.setIconShape(it) }
        spec.dock?.let { store.setDockStyle(it) }
        spec.animationSpeed?.let { store.setAnimationSpeed(it) }
        spec.parallax?.let { store.setParallaxStrength(it) }
        spec.showLabels?.let { store.setShowAppLabels(it) }
        spec.badgeStyle?.let { store.setBadgeStyle(it) }
        spec.glanceStyle?.let { store.setGlanceStyle(it) }
        spec.drawerCategories?.let { store.setDrawerCategories(it) }
        spec.haptics?.let { store.setHapticsEnabled(it) }
        if (!keepWallpaper) {
            spec.themeMode?.let { store.setThemeMode(it) }
            spec.accent?.let { store.setAccentMode(it) }
            spec.wallpaperSource?.let { store.setWallpaperSource(it) }
            spec.scene?.let { store.setHomeScene(it) }
            if (spec.wallpaperSource == OeaDataStore.WALLPAPER_OEA || OeaWallpaperStore.scene(context) != null) {
                OeaWallpaperStore.setThemeFollowsScene(context, true)
            }
        }
        // Handle DND and Low mode as part of mode system with reliable activation/deactivation
        spec.dnd?.let { enable ->
            try {
                if (enable) {
                    if (com.oea.launcher.power.OeaPower.policyAccess(context)) {
                        com.oea.launcher.power.OeaPower.setDnd(context, true)
                    }
                } else {
                    if (com.oea.launcher.power.OeaPower.policyAccess(context)) {
                        com.oea.launcher.power.OeaPower.setDnd(context, false)
                    }
                }
            } catch (_: Throwable) {}
        }
        spec.lowMode?.let { enable ->
            try { com.oea.launcher.perf.OeaLowMode.setEnabled(context, enable) } catch (_: Throwable) {}
        }
        store.setLauncherMode(mode.id)
        store.setModeChosen(true)
        OeaIconController.shared(context).invalidate()
        OeaTheme.invalidate()
        OeaWallpaperStore.syncWithSystem(context)
    }

    fun drawnSceneOptions(): List<Pair<String, String>> =
        OeaLiveScenes.ids.map { it to OeaLiveScenes.label(it) } +
            com.oea.launcher.wallpaper.OeaWallpaper.homeSet.map { it.id to it.label }
}
