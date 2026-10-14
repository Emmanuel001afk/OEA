package com.oea.launcher.wallpaper

import android.content.Context
import com.oea.launcher.data.OeaDataStore

/**
 * Answers one question for the rest of the launcher: **what wallpaper is Home
 * actually showing right now, and what does it mean for the interface?**
 *
 * There are two possible owners of Home's wallpaper ([OeaDataStore.wallpaperSource]):
 *
 * - Android, the default. OEA's window is transparent over the system wallpaper,
 *   so the theme reads the scene OEA applied through [OeaWallpaperStore].
 * - OEA itself ("Home draws it"). The scene never touches Android's wallpaper —
 *   Home paints it under its own icons, which is what makes a *live* scene (Tide,
 *   Aurora, Depth, Ink) possible and what leaves the lock screen, widgets and the
 *   stock picker exactly as the user had them.
 *
 * In both cases the interface should follow the artwork: its accent, and whether
 * Home's text must be light or dark. That decision lives here alone, so a new
 * source only ever has to be taught to this file.
 */
object OeaHomeScene {
    /** Home paints its own backdrop (the OEA wallpaper source). */
    fun drawnByHome(context: Context): Boolean =
        OeaDataStore.get(context).wallpaperSource() == OeaDataStore.WALLPAPER_OEA

    /** The live scene Home is drawing, if any: "tide", "depth", "ink" or "aurora". */
    fun live(context: Context): String? {
        if (!drawnByHome(context)) return null
        val id = OeaDataStore.get(context).homeScene()
        return if (OeaLiveScenes.isLive(id)) id else null
    }

    /** The still scene Home is showing or has painted, if any. */
    fun still(context: Context): OeaWallpaper? {
        if (drawnByHome(context)) {
            val id = OeaDataStore.get(context).homeScene()
            if (OeaLiveScenes.isLive(id)) return null
            return OeaWallpaper.byId(id)
        }
        // Android owns the wallpaper: only a scene OEA put there describes Home.
        return OeaWallpaperStore.scene(context)?.takeIf { OeaWallpaperStore.coversHome(context) }
    }

    /**
     * Home starts drawing [id] (a live id or a still scene id) itself. Android's
     * wallpaper is never touched, and the source is switched to "drawn by Home" in
     * the same call so the two can never disagree.
     */
    fun setLive(context: Context, id: String) {
        val store = OeaDataStore.get(context)
        store.setHomeScene(id)
        store.setWallpaperSource(OeaDataStore.WALLPAPER_OEA)
        com.oea.launcher.ui.OeaTheme.invalidate()
    }

    /** Home goes back to the still scenes / Android's wallpaper. */
    fun clearLive(context: Context) {
        OeaDataStore.get(context).setHomeScene(null)
        com.oea.launcher.ui.OeaTheme.invalidate()
    }

    /** True when a scene (live or still) is what the user sees behind Home's icons. */
    fun coversHome(context: Context): Boolean = drawnByHome(context) || still(context) != null

    /**
     * The accent the interface should use, or null when OEA should fall back to
     * Material You / a fixed accent. The user's "theme follows the wallpaper"
     * switch is respected in both sources.
     */
    fun accent(context: Context, dark: Boolean): Int? {
        if (!OeaWallpaperStore.themeFollowsScene(context)) return null
        live(context)?.let { return OeaLiveScenes.accent(it, dark) }
        return still(context)?.accent
    }

    /**
     * True when Home's background is bright enough that text must be dark, or
     * null when nothing here knows (the caller then asks Android's wallpaper
     * colours, exactly as before).
     */
    fun light(context: Context): Boolean? {
        live(context)?.let { return OeaLiveScenes.isLight(it) }
        return still(context)?.light
    }

    /** A human description for Settings and the Themes app. */
    fun describe(context: Context): String {
        live(context)?.let { return "${OeaLiveScenes.label(it)} · live, drawn by Home" }
        still(context)?.let { scene -> return if (drawnByHome(context)) "${scene.label} · drawn by Home" else scene.label }
        return if (drawnByHome(context)) "Android's wallpaper (Home is transparent over it)" else "Android's wallpaper"
    }
}
