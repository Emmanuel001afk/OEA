package com.oea.launcher.ui

import android.content.Context
import com.oea.launcher.data.OeaDataStore
import com.oea.launcher.icons.OeaIconController
import com.oea.launcher.wallpaper.OeaLiveScenes
import com.oea.launcher.wallpaper.OeaWallpaperStore

/**
 * OEA's launcher modes: split into two distinct categories — Visual vs Behavior.
 *
 * Visual modes: screen diffuse effect, how Home looks, live scenes, glass, effects, parallax, wallpaper, accent, animation.
 *   - CLASSIC: everyday look, plain surfaces, system wallpaper
 *   - LIQUID: frosted translucent, liquid trail, squircle
 *   - TIDE: living water surface, live, never repeats
 *   - DEPTH: four ridges, four distances, parallax
 *   - INK: paper, brush, seal, monochrome
 *   - AURORA: aurora borealis, flowing lights, dark
 *   - GLASS: pure glassmorphism, blurred, translucent
 *   - MESH: gradient mesh, colorful, vibrant
 *   - PAPER: paper light, calm, readable
 *   - SLATE: slate lines, minimal, graphite
 *   - BLOOM: floral bloom, soft, pastel
 *   - etc.
 *
 * Behavior modes: launcher system limiting apps, DND, LOW mode, suggestions, categories, search, haptics, etc.
 *   - STUDY: reduces distractions, prioritizes study apps, DND on, games/social hidden, calm paper, slow animations, date only glance
 *   - MUSIC: immersive listening, dark, album art accent, media island expanded, keep screen on during playback
 *   - SLEEPING: quiet-time, DND on, LOW on (optional), islands hidden, live scenes frozen, dim, no haptics
 *   - FOCUS: strict distraction-free, only focus-list apps, categories off, search hidden, monochrome, no motion, DND on
 *   - GAMING: optimized for games, fast, Game Boost, DND on, performance, games prioritized
 *   - READING: paper, no motion, large labels, readable, light theme, DND on
 *   - WORK: productivity, work apps first, solid dock, slate lines, DND off but filtered
 *   - DRIVING: large, high-contrast, hands-free, auto-rotate, volume/assistant quick controls, no widgets
 *   - etc.
 *
 * Each mode has distinct purpose/features, reliable activation/restoration.
 * Visual is screen diffuse effect; behavior is launcher system limiting apps.
 * LOW Mode = Ultra Power Saving Mode reference (see OeaLowMode).
 *
 * Combinations allowed: user can pick one visual + one behavior, or single mode that sets both.
 */
enum class OeaMode(
    val id: String,
    val label: String,
    val tagline: String,
    val detail: String,
    val category: String = "visual",
    val kind: Kind = Kind.VISUAL
) {
    // Visual — screen diffuse effect
    CLASSIC(
        "classic", "Classic",
        "The everyday look",
        "Plain surfaces, classic page motion, and the wallpaper owned by Android as usual. Visual diffuse: none, system wallpaper.",
        "visual", Kind.VISUAL
    ),
    LIQUID(
        "liquid", "Liquid",
        "Glass you can almost touch",
        "Visual diffuse: frosted translucent surfaces, icons that trail like liquid while a page moves, squircle icon shape and lighter, swifter motion. Screen diffuse effect: liquid glass blur, 12dp, translucency 0.7, trail.",
        "visual", Kind.VISUAL
    ),
    TIDE(
        "tide", "Tide",
        "A living surface — only OEA",
        "Visual diffuse: Home draws moving water, layered and lit, and takes its accent from the water itself. The scene is live and never repeats; Android's wallpaper is left untouched. Diffuse: water caustics, parallax 95.",
        "visual", Kind.VISUAL
    ),
    DEPTH(
        "depth", "Depth",
        "Four ridges, four distances",
        "Visual diffuse: Home draws layered ridges that shift against each other as you move between pages: nearest moves most, furthest almost not at all. Pages move towards and away. Diffuse: depth parallax 100, layered.",
        "visual", Kind.VISUAL
    ),
    INK(
        "ink", "Ink",
        "Paper, brush and seal",
        "Visual diffuse: light monochrome page drawn by hand: paper fibre, soft ink washes, one decisive stroke and single red seal that becomes accent. Pages settle like sheets. Diffuse: paper texture, ink spread.",
        "visual", Kind.VISUAL
    ),
    AURORA(
        "aurora", "Aurora",
        "Northern lights, flowing",
        "Visual diffuse: aurora borealis flowing lights, dark background, color shift, slow motion. Diffuse: aurora gradient mesh, blur 20dp, color cycle.",
        "visual", Kind.VISUAL
    ),
    GLASS(
        "glass", "Glass",
        "Pure glassmorphism",
        "Visual diffuse: pure glassmorphism, blurred, translucent, frosted. Diffuse: glass blur 24dp, translucency 0.6, highlight.",
        "visual", Kind.VISUAL
    ),
    MESH(
        "mesh", "Mesh",
        "Gradient mesh, vibrant",
        "Visual diffuse: gradient mesh, colorful, vibrant, mesh gradient, blur 16dp.",
        "visual", Kind.VISUAL
    ),
    PAPER(
        "paper", "Paper",
        "Calm paper, readable",
        "Visual diffuse: paper light, calm, readable, no blur, paper texture, warm.",
        "visual", Kind.VISUAL
    ),
    SLATE(
        "slate", "Slate",
        "Slate lines, minimal",
        "Visual diffuse: slate lines, minimal, graphite, lines, dark, monochrome.",
        "visual", Kind.VISUAL
    ),
    BLOOM(
        "bloom", "Bloom",
        "Floral bloom, soft",
        "Visual diffuse: floral bloom, soft, pastel, bloom blur, light.",
        "visual", Kind.VISUAL
    ),

    // Behavior — launcher system limiting apps
    STUDY(
        "study", "Study",
        "Reduce distractions, focus on learning",
        "Behavior: Study Mode reduces distractions, prioritizes selected study apps, DND on, games and social hidden from suggestions, calm paper wallpaper, slow animations, At a Glance shows date only. System limiting: hides games/social, filters suggestions, DND, slow.",
        "focus", Kind.BEHAVIOR
    ),
    MUSIC(
        "music", "Music",
        "Immersive listening experience",
        "Behavior: Music Mode dark theme with accent from album art, liquid glass, media island expanded with album art and progress, keeps screen on during playback, aurora live scene option. System limiting: none, but optimizes for listening, keeps screen on, media controls prominent.",
        "media", Kind.BEHAVIOR
    ),
    SLEEPING(
        "sleeping", "Sleeping",
        "Quiet time, no interruptions",
        "Behavior: Sleeping Mode quiet-time behaviour with DND on, LOW mode on (optional), islands hidden, live scenes frozen to still, dim wallpaper, dark panels, no haptics. System limiting: DND, LOW, hides islands, freezes scenes, no haptics, dim.",
        "quiet", Kind.BEHAVIOR
    ),
    FOCUS(
        "focus", "Focus",
        "Strict distraction-free work",
        "Behavior: Focus Mode only focus-list apps in suggestions, categories off, search hidden, monochrome graphite theme, no page motion, DND on. System limiting: only focus apps, categories off, search hidden, DND, no motion.",
        "focus", Kind.BEHAVIOR
    ),
    GAMING(
        "gaming", "Gaming",
        "Optimized for play, no interruptions",
        "Behavior: Gaming Mode fast animations, liquid glass, Game Boost enabled, DND on, performance mode, games prioritized in drawer, ocean abyss wallpaper. System limiting: DND, games prioritized, Game Boost overlay, performance.",
        "performance", Kind.BEHAVIOR
    ),
    READING(
        "reading", "Reading",
        "Paper, calm, readable",
        "Behavior: Reading Mode light theme, paper light wallpaper, circle icons, no dock background, paper page transition, no animations, large labels, DND on. System limiting: no animations, large labels, DND, paper.",
        "calm", Kind.BEHAVIOR
    ),
    WORK(
        "work", "Work",
        "Productivity, work apps first",
        "Behavior: Work Mode work apps pinned, suggestions show frequent work apps, solid dock, classic surfaces, slate lines wallpaper, DND off but notifications filtered. System limiting: work apps pinned, frequent work apps, filtered notifications.",
        "productivity", Kind.BEHAVIOR
    ),
    DRIVING(
        "driving", "Driving",
        "Large, high-contrast, hands-free",
        "Behavior: Driving Mode large icons, labels always on, high contrast, auto-rotate on, DND with auto, quick controls with volume and assistant, no widgets. System limiting: large icons, high contrast, auto-rotate, no widgets, DND with auto-reply.",
        "utility", Kind.BEHAVIOR
    ),
    LOW(
        "low", "Low",
        "Ultra Power Saving — OEA's own",
        "Behavior: Low Mode = Ultra Power Saving Mode reference. Launcher's own power saving: freeze live scenes, hide islands, release sensors, suspend chosen apps if DO/root. Mini-apps whitelisted. System limiting: stops animations, hides glance/live, stops island/game boost/wake sensors, suspends apps. See OeaLowMode.",
        "power", Kind.BEHAVIOR
    );

    enum class Kind { VISUAL, BEHAVIOR }

    val isLive: Boolean get() = this == TIDE || this == DEPTH || this == INK || this == AURORA || this == MUSIC
    val isVisual: Boolean get() = kind == Kind.VISUAL
    val isBehavior: Boolean get() = kind == Kind.BEHAVIOR

    companion object {
        fun of(id: String?): OeaMode = entries.firstOrNull { it.id == id } ?: CLASSIC
        fun visualModes(): List<OeaMode> = entries.filter { it.isVisual }
        fun behaviorModes(): List<OeaMode> = entries.filter { it.isBehavior }
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
        OeaMode.AURORA -> ModeSpec(
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
            glanceStyle = "large",
            haptics = true
        )
        OeaMode.GLASS -> ModeSpec(
            glass = OeaDataStore.GLASS_LIQUID,
            effect = "liquid",
            iconShape = "squircle",
            dock = "glass",
            animationSpeed = 2,
            parallax = 50,
            glanceStyle = "large"
        )
        OeaMode.MESH -> ModeSpec(
            glass = OeaDataStore.GLASS_LIQUID,
            effect = "liquid",
            iconShape = "rounded",
            dock = "glass",
            themeMode = "dark",
            wallpaperSource = OeaDataStore.WALLPAPER_OEA,
            scene = "mesh_amoled",
            accent = "wallpaper",
            animationSpeed = 1,
            parallax = 60
        )
        OeaMode.PAPER -> ModeSpec(
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
            glanceStyle = "compact",
            haptics = false
        )
        OeaMode.SLATE -> ModeSpec(
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
            glanceStyle = "hidden"
        )
        OeaMode.BLOOM -> ModeSpec(
            glass = OeaDataStore.GLASS_LIQUID,
            effect = "paper",
            iconShape = "circle",
            dock = "glass",
            themeMode = "light",
            wallpaperSource = OeaDataStore.WALLPAPER_OEA,
            scene = "bloom_pastel",
            accent = "pink",
            animationSpeed = 1,
            parallax = 30
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
        OeaMode.LOW -> ModeSpec(
            glass = OeaDataStore.GLASS_CLASSIC,
            effect = "none",
            iconShape = "rounded",
            dock = "none",
            themeMode = "dark",
            wallpaperSource = OeaDataStore.WALLPAPER_OEA,
            scene = "mesh_amoled",
            accent = "slate",
            animationSpeed = 0,
            parallax = 0,
            dnd = true,
            lowMode = true,
            showLabels = true,
            badgeStyle = "off",
            glanceStyle = "hidden",
            drawerCategories = false,
            haptics = false
        )
    }

    fun current(context: Context): OeaMode = OeaMode.of(OeaDataStore.get(context).launcherMode())

    fun currentVisual(context: Context): OeaMode {
        val id = OeaDataStore.get(context).visualMode()
        return OeaMode.visualModes().firstOrNull { it.id == id } ?: OeaMode.CLASSIC
    }

    fun currentBehavior(context: Context): OeaMode? {
        val id = OeaDataStore.get(context).behaviorMode()
        return if (id.isNullOrBlank()) null else OeaMode.behaviorModes().firstOrNull { it.id == id }
    }

    fun summary(context: Context): String {
        val mode = current(context)
        val visual = currentVisual(context)
        val behavior = currentBehavior(context)
        return when {
            behavior != null -> "${visual.label} + ${behavior.label} · ${visual.category} + ${behavior.category}"
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
        // Also persist as visual or behavior
        if (mode.isVisual) store.setVisualMode(mode.id) else store.setBehaviorMode(mode.id)
        store.setModeChosen(true)
        OeaIconController.shared(context).invalidate()
        OeaTheme.invalidate()
        OeaWallpaperStore.syncWithSystem(context)
    }

    fun applyVisual(context: Context, mode: OeaMode) {
        if (!mode.isVisual) return
        apply(context, mode, keepWallpaper = false)
        OeaDataStore.get(context).setVisualMode(mode.id)
    }

    fun applyBehavior(context: Context, mode: OeaMode?) {
        if (mode == null) {
            OeaDataStore.get(context).setBehaviorMode("")
            // Restore visual only
            return
        }
        if (!mode.isBehavior) return
        // Apply behavior spec without overriding visual wallpaper if user wants combination
        val store = OeaDataStore.get(context)
        val spec = spec(mode)
        // Behavior modes can set DND, LOW, glance, drawer, haptics, etc., but not necessarily visual
        spec.dnd?.let { enable ->
            try {
                if (com.oea.launcher.power.OeaPower.policyAccess(context)) {
                    com.oea.launcher.power.OeaPower.setDnd(context, enable)
                }
            } catch (_: Throwable) {}
        }
        spec.lowMode?.let { enable ->
            try { com.oea.launcher.perf.OeaLowMode.setEnabled(context, enable) } catch (_: Throwable) {}
        }
        spec.showLabels?.let { store.setShowAppLabels(it) }
        spec.badgeStyle?.let { store.setBadgeStyle(it) }
        spec.glanceStyle?.let { store.setGlanceStyle(it) }
        spec.drawerCategories?.let { store.setDrawerCategories(it) }
        spec.haptics?.let { store.setHapticsEnabled(it) }
        spec.animationSpeed?.let { store.setAnimationSpeed(it) }
        spec.parallax?.let { store.setParallaxStrength(it) }
        store.setBehaviorMode(mode.id)
        store.setLauncherMode(mode.id)
    }

    fun drawnSceneOptions(): List<Pair<String, String>> =
        OeaLiveScenes.ids.map { it to OeaLiveScenes.label(it) } +
            com.oea.launcher.wallpaper.OeaWallpaper.homeSet.map { it.id to it.label }
}
