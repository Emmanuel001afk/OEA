package com.oea.launcher.ui

import android.content.Context
import com.oea.launcher.data.OeaDataStore
import com.oea.launcher.icons.OeaIconController
import com.oea.launcher.wallpaper.OeaLiveScenes
import com.oea.launcher.wallpaper.OeaWallpaper
import com.oea.launcher.wallpaper.OeaWallpaperStore

/**
 * OEA's launcher modes: one choice that sets the whole look coherently, instead
 * of the user having to line up glass, motion, icons and wallpaper themselves.
 *
 * A mode only ever writes settings the user can still change one by one, and
 * changing a single setting afterwards never silently flips the mode.
 *
 * - [CLASSIC]  the everyday look: plain surfaces, classic page motion.
 * - [LIQUID]   Apple-style liquid glass: frosted translucent surfaces, a page
 *              that swells and trails while it moves, squircle icons.
 * - [TIDE]     OEA's own: a living water surface drawn by Home, in real time,
 *              with the interface's accent taken from the water as it moves.
 * - [DEPTH]    four ridges at four distances that parallax with your pages.
 * - [INK]      paper, brush washes and a red seal — light, monochrome, calm.
 *
 * TIDE, DEPTH and INK are drawn by Home itself, so they never touch Android's
 * wallpaper: the lock screen, other apps and the stock picker keep whatever the
 * user set there.
 */
enum class OeaMode(
    val id: String,
    val label: String,
    val tagline: String,
    val detail: String,
) {
    CLASSIC(
        "classic", "Classic",
        "The everyday look",
        "Plain surfaces, classic page motion, and the wallpaper owned by Android as usual.",
    ),
    LIQUID(
        "liquid", "Liquid",
        "Glass you can almost touch",
        "Frosted translucent surfaces, icons that trail like liquid while a page moves, squircle icon shape and a lighter, swifter motion.",
    ),
    TIDE(
        "tide", "Tide",
        "A living surface — only OEA",
        "Home draws moving water, layered and lit, and takes its accent from the water itself. The scene is live and never repeats; Android's wallpaper is left untouched.",
    ),
    DEPTH(
        "depth", "Depth",
        "Four ridges, four distances",
        "Home draws layered ridges that shift against each other as you move between pages: the nearest moves most, the furthest almost not at all.",
    ),
    INK(
        "ink", "Ink",
        "Paper, brush and seal",
        "A light, monochrome page drawn by hand: paper fibre, soft ink washes, one decisive stroke and a single red seal that becomes the accent.",
    );

    val isLive: Boolean get() = this == TIDE || this == DEPTH || this == INK

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
)

object OeaModes {
    fun spec(mode: OeaMode): ModeSpec = when (mode) {
        OeaMode.CLASSIC -> ModeSpec(
            glass = OeaDataStore.GLASS_CLASSIC,
            effect = "classic",
            iconShape = "system",
            dock = "glass",
            animationSpeed = 1,
        )
        OeaMode.LIQUID -> ModeSpec(
            glass = OeaDataStore.GLASS_LIQUID,
            effect = "liquid",
            iconShape = "squircle",
            dock = "glass",
            animationSpeed = 2,
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
        )
        OeaMode.DEPTH -> ModeSpec(
            glass = OeaDataStore.GLASS_CLASSIC,
            effect = "classic",
            iconShape = "rounded",
            dock = "glass",
            themeMode = "dark",
            wallpaperSource = OeaDataStore.WALLPAPER_OEA,
            scene = OeaLiveScenes.DEPTH,
            accent = "wallpaper",
            animationSpeed = 1,
        )
        OeaMode.INK -> ModeSpec(
            glass = OeaDataStore.GLASS_CLASSIC,
            effect = "fade",
            iconShape = "circle",
            dock = "none",
            themeMode = "light",
            wallpaperSource = OeaDataStore.WALLPAPER_OEA,
            scene = OeaLiveScenes.INK,
            accent = "wallpaper",
            animationSpeed = 0,
        )
    }

    fun current(context: Context): OeaMode = OeaMode.of(OeaDataStore.get(context).launcherMode())

    /** One-line description for Settings rows. */
    fun summary(context: Context): String {
        val mode = current(context)
        return when {
            mode.isLive -> "${mode.label} · live, drawn by Home"
            else -> mode.label
        }
    }

    /**
     * Applies [mode]. [keepWallpaper] leaves the wallpaper source and scene alone,
     * which is what the Settings switch does when the user only wants the
     * surfaces and motion of a mode.
     */
    fun apply(context: Context, mode: OeaMode, keepWallpaper: Boolean = false) {
        val store = OeaDataStore.get(context)
        val spec = spec(mode)
        spec.glass?.let { store.setGlassStyle(it) }
        spec.effect?.let { store.setPageEffect(it) }
        spec.iconShape?.let { store.setIconShape(it) }
        spec.dock?.let { store.setDockStyle(it) }
        spec.animationSpeed?.let { store.setAnimationSpeed(it) }
        if (!keepWallpaper) {
            spec.themeMode?.let { store.setThemeMode(it) }
            spec.accent?.let { store.setAccentMode(it) }
            spec.wallpaperSource?.let { store.setWallpaperSource(it) }
            spec.scene?.let { store.setHomeScene(it) }
            if (spec.wallpaperSource == OeaDataStore.WALLPAPER_OEA || OeaWallpaperStore.scene(context) != null) {
                // A drawn scene is useless if the interface keeps an accent from
                // somewhere else, so the mode expects the theme to follow it.
                OeaWallpaperStore.setThemeFollowsScene(context, true)
            }
        }
        store.setLauncherMode(mode.id)
        store.setModeChosen(true)
        OeaIconController.shared(context).invalidate()
        OeaTheme.invalidate()
        OeaWallpaperStore.syncWithSystem(context)
    }

    /** What the wallpaper area of Settings offers for the OEA-drawn source. */
    fun drawnSceneOptions(): List<Pair<String, String>> =
        OeaLiveScenes.ids.map { it to OeaLiveScenes.label(it) } +
            OeaWallpaper.homeSet.map { it.id to it.label }
}
