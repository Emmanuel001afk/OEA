package com.oea.launcher.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class OeaDataStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _modelBound = MutableStateFlow(false)
    val modelBound: StateFlow<Boolean> = _modelBound.asStateFlow()
    private val _applicationCount = MutableStateFlow(0)
    val applicationCount: StateFlow<Int> = _applicationCount.asStateFlow()
    private val _homeSurfaceEnabled = MutableStateFlow(prefs.getBoolean(KEY_HOME_SURFACE, false))
    val homeSurfaceEnabled: StateFlow<Boolean> = _homeSurfaceEnabled.asStateFlow()

    /** Home listens so settings changed anywhere in OEA apply immediately. */
    fun registerListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(listener)
    fun unregisterListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(listener)

    fun markModelBound() { _modelBound.value = true }
    fun setApplicationCount(count: Int) { _applicationCount.value = count }
    fun setHomeSurfaceEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HOME_SURFACE, enabled).apply()
        _homeSurfaceEnabled.value = enabled
    }

    fun favorites(): Set<String> = prefs.getStringSet(KEY_FAVORITES, emptySet())?.toSet() ?: emptySet()
    fun isFavorite(p: String, c: String) = favorites().contains(componentKey(p, c))
    fun setFavorite(p: String, c: String, f: Boolean) {
        val u = favorites().toMutableSet()
        val k = componentKey(p, c)
        if (f) u.add(k) else u.remove(k)
        prefs.edit().putStringSet(KEY_FAVORITES, u).apply()
    }

    fun hiddenApps(): Set<String> = prefs.getStringSet(KEY_HIDDEN_APPS, emptySet())?.toSet() ?: emptySet()
    fun isHidden(p: String, c: String) = hiddenApps().contains(componentKey(p, c))
    fun setHidden(p: String, c: String, h: Boolean) {
        val u = hiddenApps().toMutableSet()
        val k = componentKey(p, c)
        if (h) u.add(k) else u.remove(k)
        prefs.edit().putStringSet(KEY_HIDDEN_APPS, u).apply()
    }

    /** Hidden apps space asks for the App Lock PIN before opening. */
    fun hiddenSpaceLocked() = prefs.getBoolean(KEY_HIDDEN_SPACE_LOCKED, false)
    fun setHiddenSpaceLocked(v: Boolean) { prefs.edit().putBoolean(KEY_HIDDEN_SPACE_LOCKED, v).apply() }

    fun showAppLabels() = prefs.getBoolean(KEY_SHOW_LABELS, true)
    fun setShowAppLabels(v: Boolean) { prefs.edit().putBoolean(KEY_SHOW_LABELS, v).apply() }
    fun gridColumns() = prefs.getInt(KEY_GRID_COLUMNS, 4).coerceIn(3, 6)
    fun setGridColumns(v: Int) { prefs.edit().putInt(KEY_GRID_COLUMNS, v.coerceIn(3, 6)).apply() }
    fun gridRows() = prefs.getInt(KEY_GRID_ROWS, 5).coerceIn(3, 7)
    fun setGridRows(v: Int) { prefs.edit().putInt(KEY_GRID_ROWS, v.coerceIn(3, 7)).apply() }
    fun iconSizeDp() = prefs.getInt(KEY_ICON_SIZE, 54).coerceIn(44, 66)
    fun setIconSizeDp(v: Int) { prefs.edit().putInt(KEY_ICON_SIZE, v.coerceIn(44, 66)).apply() }

    /** Dock surface: "glass" (frosted card), "solid" or "none". */
    fun dockStyle() = prefs.getString(KEY_DOCK_STYLE, "glass") ?: "glass"
    fun setDockStyle(v: String) { prefs.edit().putString(KEY_DOCK_STYLE, v).apply() }

    /** Home search pill: "bottom" (below the dock), "top" (under At a Glance) or "hidden". */
    fun searchBarPosition() = prefs.getString(KEY_SEARCH_POSITION, "bottom") ?: "bottom"
    fun setSearchBarPosition(v: String) { prefs.edit().putString(KEY_SEARCH_POSITION, v).apply() }

    /** At a Glance: "large", "compact" or "hidden". */
    fun glanceStyle() = prefs.getString(KEY_GLANCE_STYLE, "large") ?: "large"
    fun setGlanceStyle(v: String) { prefs.edit().putString(KEY_GLANCE_STYLE, v).apply() }

    /** Notification badges: "dot", "count" or "off". */
    fun badgeStyle() = prefs.getString(KEY_BADGE_STYLE, "dot") ?: "dot"
    fun setBadgeStyle(v: String) { prefs.edit().putString(KEY_BADGE_STYLE, v).apply() }

    /** In-workspace live activity capsule. Notification content needs listener access; charging does not. */
    fun dynamicIslandEnabled() = prefs.getBoolean(KEY_DYNAMIC_ISLAND_ENABLED, true)
    fun setDynamicIslandEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DYNAMIC_ISLAND_ENABLED, enabled).apply()
    }

    /**
     * Live activity style: "card" (the capsule card under the status bar),
     * "camera" (an expandable island around the front camera) or "both".
     */
    fun islandStyle() = prefs.getString(KEY_ISLAND_STYLE, ISLAND_CARD)?.takeIf { it in setOf(ISLAND_CARD, ISLAND_CAMERA, ISLAND_BOTH) } ?: ISLAND_CARD
    fun setIslandStyle(v: String) { prefs.edit().putString(KEY_ISLAND_STYLE, v).apply() }

    /** With both styles: apps whose live activity shows in the camera island. */
    fun islandCameraApps(): Set<String> = prefs.getStringSet(KEY_ISLAND_CAMERA_APPS, emptySet()).orEmpty()
    /** With both styles: apps whose live activity shows in the card. */
    fun islandCardApps(): Set<String> = prefs.getStringSet(KEY_ISLAND_CARD_APPS, emptySet()).orEmpty()

    /** An app belongs to one style at a time, so assigning it to one removes it from the other. */
    fun setIslandCameraApps(packages: Set<String>) {
        prefs.edit()
            .putStringSet(KEY_ISLAND_CAMERA_APPS, packages.toSet())
            .putStringSet(KEY_ISLAND_CARD_APPS, islandCardApps() - packages)
            .apply()
    }

    fun setIslandCardApps(packages: Set<String>) {
        prefs.edit()
            .putStringSet(KEY_ISLAND_CARD_APPS, packages.toSet())
            .putStringSet(KEY_ISLAND_CAMERA_APPS, islandCameraApps() - packages)
            .apply()
    }

    /** With both styles: where unassigned apps, calls and charging appear ("card" or "camera"). */
    fun islandDefaultSurface() = prefs.getString(KEY_ISLAND_DEFAULT_SURFACE, ISLAND_CARD)?.takeIf { it == ISLAND_CARD || it == ISLAND_CAMERA } ?: ISLAND_CARD
    fun setIslandDefaultSurface(v: String) { prefs.edit().putString(KEY_ISLAND_DEFAULT_SURFACE, v).apply() }

    /** By default, compatible notification activities from any app may appear. */
    fun islandAllApps() = prefs.getBoolean(KEY_ISLAND_ALL_APPS, true)
    fun setIslandAllApps(enabled: Boolean) { prefs.edit().putBoolean(KEY_ISLAND_ALL_APPS, enabled).apply() }
    fun islandSelectedApps(): Set<String> = prefs.getStringSet(KEY_ISLAND_SELECTED_APPS, emptySet()).orEmpty()
    fun setIslandSelectedApps(packages: Set<String>) {
        prefs.edit().putStringSet(KEY_ISLAND_SELECTED_APPS, packages.toSet()).apply()
    }

    /** Camera-island position offsets, in dp from the front-camera default position. */
    fun islandCameraOffsetX() = prefs.getInt(KEY_ISLAND_CAMERA_OFFSET_X, 0).coerceIn(-4096, 4096)
    fun islandCameraOffsetY() = prefs.getInt(KEY_ISLAND_CAMERA_OFFSET_Y, 0).coerceIn(-4096, 4096)
    fun setIslandCameraPosition(xDp: Int, yDp: Int) {
        prefs.edit()
            .putInt(KEY_ISLAND_CAMERA_OFFSET_X, xDp.coerceIn(-4096, 4096))
            .putInt(KEY_ISLAND_CAMERA_OFFSET_Y, yDp.coerceIn(-4096, 4096))
            .apply()
    }

    /** Use the current system wallpaper behind the All Apps surface instead of an opaque panel. */
    fun drawerWallpaperBackground() = prefs.getBoolean(KEY_DRAWER_WALLPAPER_BACKGROUND, false)
    fun setDrawerWallpaperBackground(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DRAWER_WALLPAPER_BACKGROUND, enabled).apply()
    }

    /** Preferred initial size for Android-owned freeform windows. */
    fun floatingWindowSize() = prefs.getString(KEY_FLOATING_WINDOW_SIZE, "medium")?.takeIf { it in FLOATING_WINDOW_SIZES } ?: "medium"
    fun setFloatingWindowSize(size: String) {
        prefs.edit().putString(KEY_FLOATING_WINDOW_SIZE, size.takeIf { it in FLOATING_WINDOW_SIZES } ?: "medium").apply()
    }

    /** Optional system-wide island; requires the user's explicit overlay permission. */
    fun islandOnOtherApps() = prefs.getBoolean(KEY_ISLAND_ON_OTHER_APPS, false)
    fun setIslandOnOtherApps(enabled: Boolean) { prefs.edit().putBoolean(KEY_ISLAND_ON_OTHER_APPS, enabled).apply() }

    // ------------------------------------------------------------- OEA themes

    /** The applied OEA theme ("" when the user never applied one). */
    fun activeThemeId() = prefs.getString(KEY_ACTIVE_THEME, "") ?: ""
    fun themeAccentDark() = prefs.getInt(KEY_THEME_ACCENT_DARK, 0)
    fun themeAccentLight() = prefs.getInt(KEY_THEME_ACCENT_LIGHT, 0)
    fun themeSurfaceTint() = prefs.getInt(KEY_THEME_SURFACE, 0)

    /**
     * Stores the colours of an applied theme in one write. [accentMode] "theme"
     * makes OEA's palette use them; personal settings are untouched.
     */
    fun applyThemeColors(themeId: String, accentDark: Int, accentLight: Int, surfaceTint: Int) {
        prefs.edit()
            .putString(KEY_ACTIVE_THEME, themeId)
            .putInt(KEY_THEME_ACCENT_DARK, accentDark)
            .putInt(KEY_THEME_ACCENT_LIGHT, accentLight)
            .putInt(KEY_THEME_SURFACE, surfaceTint)
            .putString(KEY_ACCENT_MODE, "theme")
            .apply()
    }

    /** Surface style for docks, search bars, folders and cards: "classic" or "liquid" glass. */
    fun glassStyle() = prefs.getString(KEY_GLASS_STYLE, GLASS_CLASSIC)?.takeIf { it == GLASS_CLASSIC || it == GLASS_LIQUID } ?: GLASS_CLASSIC
    fun setGlassStyle(v: String) { prefs.edit().putString(KEY_GLASS_STYLE, if (v == GLASS_LIQUID) GLASS_LIQUID else GLASS_CLASSIC).apply() }

    /** Home page transition: classic, liquid, cube, stack, zoom, fade or none. */
    fun pageEffect() = prefs.getString(KEY_PAGE_EFFECT, "classic")?.takeIf { it in PAGE_EFFECTS } ?: "classic"
    fun setPageEffect(v: String) { prefs.edit().putString(KEY_PAGE_EFFECT, if (v in PAGE_EFFECTS) v else "classic").apply() }

    /**
     * Show the wallpaper (behind a contrast scrim) under OEA search: the search
     * screen opened from Home and the All Apps search area. Off keeps the
     * current opaque search surface.
     */
    fun searchWallpaperVisible() = prefs.getBoolean(KEY_SEARCH_WALLPAPER, false)
    fun setSearchWallpaperVisible(v: Boolean) { prefs.edit().putBoolean(KEY_SEARCH_WALLPAPER, v).apply() }

    /** Frost the wallpaper behind All Apps and search where Android supports window blur. */
    fun wallpaperBlur() = prefs.getBoolean(KEY_WALLPAPER_BLUR, true)
    fun setWallpaperBlur(v: Boolean) { prefs.edit().putBoolean(KEY_WALLPAPER_BLUR, v).apply() }

    // ------------------------------------------------------- island behaviour

    /** Briefly expand the island for calls, new messages, charging and track changes. */
    fun islandPeek() = prefs.getBoolean(KEY_ISLAND_PEEK, true)
    fun setIslandPeek(v: Boolean) { prefs.edit().putBoolean(KEY_ISLAND_PEEK, v).apply() }

    /** Camera and microphone in-use dots inside the island. */
    fun islandPrivacyDots() = prefs.getBoolean(KEY_ISLAND_PRIVACY, true)
    fun setIslandPrivacyDots(v: Boolean) { prefs.edit().putBoolean(KEY_ISLAND_PRIVACY, v).apply() }

    fun themedIcons() = prefs.getBoolean(KEY_THEMED_ICONS, false)
    fun setThemedIcons(v: Boolean) { prefs.edit().putBoolean(KEY_THEMED_ICONS, v).apply() }

    /** Accent: "wallpaper" (Material You / wallpaper colour) or a fixed OEA accent. */
    fun accentMode() = prefs.getString(KEY_ACCENT_MODE, "wallpaper") ?: "wallpaper"
    fun setAccentMode(v: String) { prefs.edit().putString(KEY_ACCENT_MODE, v).apply() }

    /** Home swipe-down: "notifications", "quick_settings", "search" or "none". */
    fun swipeDownAction() = prefs.getString(KEY_SWIPE_DOWN, "notifications") ?: "notifications"
    fun setSwipeDownAction(v: String) { prefs.edit().putString(KEY_SWIPE_DOWN, v).apply() }

    /** Home double-tap: "lock", "drawer" or "none". */
    fun doubleTapAction() = prefs.getString(KEY_DOUBLE_TAP, "lock") ?: "lock"
    fun setDoubleTapAction(v: String) { prefs.edit().putString(KEY_DOUBLE_TAP, v).apply() }

    /** Recents button: "system" (Android Recents via OEA Assistant) or "oea" (OEA Overview). */
    fun recentsMode() = prefs.getString(KEY_RECENTS_MODE, "system") ?: "system"
    fun setRecentsMode(v: String) { prefs.edit().putString(KEY_RECENTS_MODE, v).apply() }

    fun drawerCategories() = prefs.getBoolean(KEY_DRAWER_CATEGORIES, true)
    fun setDrawerCategories(v: Boolean) { prefs.edit().putBoolean(KEY_DRAWER_CATEGORIES, v).apply() }
    fun drawerKeyboard() = prefs.getBoolean(KEY_DRAWER_KEYBOARD, false)
    fun setDrawerKeyboard(v: Boolean) { prefs.edit().putBoolean(KEY_DRAWER_KEYBOARD, v).apply() }
    fun homeLayoutLocked() = prefs.getBoolean(KEY_LAYOUT_LOCKED, false)
    fun setHomeLayoutLocked(v: Boolean) { prefs.edit().putBoolean(KEY_LAYOUT_LOCKED, v).apply() }
    fun hapticsEnabled() = prefs.getBoolean(KEY_HAPTICS, true)
    fun setHapticsEnabled(v: Boolean) { prefs.edit().putBoolean(KEY_HAPTICS, v).apply() }
    /**
     * Home stability: when set, Home skips the optional subsystems (widget host,
     * At a Glance, live activities, boost resume) for the session. Turned on
     * automatically after repeated faults and switchable by the user.
     */
    fun homeSafeMode() = prefs.getBoolean(KEY_HOME_SAFE_MODE, false)
    fun setHomeSafeMode(enabled: Boolean) { prefs.edit().putBoolean(KEY_HOME_SAFE_MODE, enabled).apply() }

    /** The games that keep stopping, so the hub can offer a safer path for them. */
    fun unstableGames(): Set<String> = prefs.getStringSet(KEY_UNSTABLE_GAMES, emptySet()).orEmpty()
    fun markUnstable(gameId: String, unstable: Boolean) {
        val set = unstableGames().toMutableSet()
        if (unstable) set += gameId else set -= gameId
        prefs.edit().putStringSet(KEY_UNSTABLE_GAMES, set).apply()
    }

    /** Optional fault log for the Stability screen; turning it off stops all file writes. */
    fun faultLogEnabled() = prefs.getBoolean(KEY_FAULT_LOG, true)
    fun setFaultLogEnabled(enabled: Boolean) { prefs.edit().putBoolean(KEY_FAULT_LOG, enabled).apply() }

    fun defaultHomeBannerDismissed() = prefs.getBoolean(KEY_DEFAULT_BANNER, false)
    fun setDefaultHomeBannerDismissed(v: Boolean) { prefs.edit().putBoolean(KEY_DEFAULT_BANNER, v).apply() }
    fun onboardingDone() = prefs.getBoolean(KEY_ONBOARDING_DONE, false)
    fun setOnboardingDone(v: Boolean) { prefs.edit().putBoolean(KEY_ONBOARDING_DONE, v).apply() }
    enum class DrawerMode { GRID, VERTICAL, HORIZONTAL }
    fun drawerMode() = runCatching {
        DrawerMode.valueOf(prefs.getString(KEY_DRAWER_MODE, DrawerMode.GRID.name) ?: DrawerMode.GRID.name)
    }.getOrDefault(DrawerMode.GRID)
    fun setDrawerMode(v: DrawerMode) { prefs.edit().putString(KEY_DRAWER_MODE, v.name).apply() }
    fun sortMode() = runCatching {
        SortMode.valueOf(prefs.getString(KEY_SORT_MODE, SortMode.NAME.name) ?: SortMode.NAME.name)
    }.getOrDefault(SortMode.NAME)
    fun setSortMode(v: SortMode) { prefs.edit().putString(KEY_SORT_MODE, v.name).apply() }
    fun themeMode() = prefs.getString(KEY_THEME_MODE, "system") ?: "system"
    fun setThemeMode(v: String) { prefs.edit().putString(KEY_THEME_MODE, v).apply() }
    fun showMostUsed() = prefs.getBoolean(KEY_SHOW_MOST_USED, true)
    fun setShowMostUsed(v: Boolean) { prefs.edit().putBoolean(KEY_SHOW_MOST_USED, v).apply() }
    /** "system" keeps the device icon mask; circle/squircle/rounded/square/teardrop reshape adaptive icons. */
    fun iconShape() = prefs.getString(KEY_ICON_SHAPE, "system") ?: "system"
    fun setIconShape(v: String) { prefs.edit().putString(KEY_ICON_SHAPE, v).apply() }
    fun animationSpeed() = prefs.getInt(KEY_ANIMATION_SPEED, 1).coerceIn(0, 2)
    fun setAnimationSpeed(value: Int) { prefs.edit().putInt(KEY_ANIMATION_SPEED, value.coerceIn(0, 2)).apply() }
    fun animationDuration(baseMillis: Long): Long = (baseMillis * when (animationSpeed()) { 0 -> 1.25; 2 -> 0.75; else -> 1.0 }).toLong().coerceAtLeast(60L)
    fun iconPackPackage() = prefs.getString(KEY_ICON_PACK_PACKAGE, null)
    fun setIconPackPackage(packageName: String?) {
        prefs.edit().apply {
            if (packageName.isNullOrBlank()) remove(KEY_ICON_PACK_PACKAGE)
            else putString(KEY_ICON_PACK_PACKAGE, packageName)
        }.apply()
    }
    fun wallpaperUri() = prefs.getString(KEY_WALLPAPER_URI, null)
    fun setWallpaperUri(v: String?) { prefs.edit().apply { if (v == null) remove(KEY_WALLPAPER_URI) else putString(KEY_WALLPAPER_URI, v) }.apply() }

    fun recordLaunch(p: String, c: String) {
        val k = componentKey(p, c)
        val r = recentLaunches().toMutableList().apply { remove(k); add(0, k) }.take(MAX_RECENTS)
        val count = launchCount(k) + 1
        prefs.edit()
            .putString(KEY_RECENTS, r.joinToString(SEP))
            .putInt(KEY_LAUNCH_COUNT_PREFIX + k, count)
            .apply()
    }
    fun launchCount(key: String): Int = prefs.getInt(KEY_LAUNCH_COUNT_PREFIX + key, 0)
    fun mostUsed(keys: Collection<String>, limit: Int = 8): List<String> =
        keys.sortedWith(compareByDescending<String> { launchCount(it) }.thenBy { it }).take(limit)
    fun recentLaunches() = prefs.getString(KEY_RECENTS, null)?.split(SEP)?.filter { it.isNotBlank() } ?: emptyList()
    fun recordAction(c: String, r: String) {
        prefs.edit().putString(KEY_LAST_COMMAND, c).putString(KEY_LAST_RESULT, r).apply()
    }
    fun lastCommand() = prefs.getString(KEY_LAST_COMMAND, null)
    fun lastResult() = prefs.getString(KEY_LAST_RESULT, null)

    private fun componentKey(p: String, c: String) = "$p/$c"
    enum class SortMode { NAME, RECENT }

    companion object {
        private const val PREFS = "oea_data"
        private const val KEY_HOME_SURFACE = "home_surface_enabled"
        private const val KEY_LAST_COMMAND = "last_command"
        private const val KEY_LAST_RESULT = "last_result"
        private const val KEY_FAVORITES = "favorite_apps"
        private const val KEY_HIDDEN_APPS = "hidden_apps"
        private const val KEY_HIDDEN_SPACE_LOCKED = "hidden_space_locked"
        private const val KEY_SHOW_LABELS = "show_app_labels"
        private const val KEY_GRID_COLUMNS = "grid_columns"
        private const val KEY_DRAWER_MODE = "drawer_mode"
        private const val KEY_SORT_MODE = "sort_mode"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_SHOW_MOST_USED = "show_most_used"
        private const val KEY_ICON_SHAPE = "icon_shape"
        private const val KEY_ICON_PACK_PACKAGE = "icon_pack_package"
        private const val KEY_ANIMATION_SPEED = "animation_speed"
        private const val KEY_WALLPAPER_URI = "wallpaper_uri"
        private const val KEY_GRID_ROWS = "grid_rows"
        private const val KEY_ICON_SIZE = "icon_size_dp"
        private const val KEY_DOCK_STYLE = "dock_style"
        private const val KEY_SEARCH_POSITION = "search_bar_position"
        private const val KEY_GLANCE_STYLE = "glance_style"
        private const val KEY_BADGE_STYLE = "badge_style"
        private const val KEY_DYNAMIC_ISLAND_ENABLED = "dynamic_island_enabled"
        private const val KEY_ISLAND_STYLE = "island_style"
        private const val KEY_ISLAND_CAMERA_APPS = "island_camera_apps"
        private const val KEY_ISLAND_CARD_APPS = "island_card_apps"
        private const val KEY_ISLAND_DEFAULT_SURFACE = "island_default_surface"
        private const val KEY_ISLAND_ALL_APPS = "island_all_apps"
        private const val KEY_ISLAND_SELECTED_APPS = "island_selected_apps"
        private const val KEY_ISLAND_CAMERA_OFFSET_X = "island_camera_offset_x_dp"
        private const val KEY_ISLAND_CAMERA_OFFSET_Y = "island_camera_offset_y_dp"
        private const val KEY_DRAWER_WALLPAPER_BACKGROUND = "drawer_wallpaper_background"
        private const val KEY_ISLAND_ON_OTHER_APPS = "island_on_other_apps"
        private const val KEY_FLOATING_WINDOW_SIZE = "floating_window_size"
        private val FLOATING_WINDOW_SIZES = setOf("small", "medium", "large")
        const val ISLAND_CARD = "card"
        const val ISLAND_CAMERA = "camera"
        const val ISLAND_BOTH = "both"
        private const val KEY_ACTIVE_THEME = "theme_active_id"
        private const val KEY_THEME_ACCENT_DARK = "theme_accent_dark"
        private const val KEY_THEME_ACCENT_LIGHT = "theme_accent_light"
        private const val KEY_THEME_SURFACE = "theme_surface_tint"
        private const val KEY_GLASS_STYLE = "glass_style"
        private const val KEY_PAGE_EFFECT = "page_effect"
        private const val KEY_SEARCH_WALLPAPER = "search_wallpaper_visible"
        private const val KEY_WALLPAPER_BLUR = "wallpaper_blur"
        private const val KEY_ISLAND_PEEK = "island_peek"
        private const val KEY_ISLAND_PRIVACY = "island_privacy_dots"
        const val GLASS_CLASSIC = "classic"
        const val GLASS_LIQUID = "liquid"
        val PAGE_EFFECTS = listOf("classic", "liquid", "cube", "stack", "zoom", "fade", "none")
        private const val KEY_THEMED_ICONS = "themed_icons"
        private const val KEY_ACCENT_MODE = "accent_mode"
        private const val KEY_SWIPE_DOWN = "gesture_swipe_down"
        private const val KEY_DOUBLE_TAP = "gesture_double_tap"
        private const val KEY_RECENTS_MODE = "recents_mode"
        private const val KEY_DRAWER_CATEGORIES = "drawer_categories"
        private const val KEY_DRAWER_KEYBOARD = "drawer_keyboard"
        private const val KEY_LAYOUT_LOCKED = "home_layout_locked"
        private const val KEY_HAPTICS = "haptics"
        private const val KEY_ONBOARDING_DONE = "onboarding_done"
        private const val KEY_HOME_SAFE_MODE = "home_safe_mode"
        private const val KEY_UNSTABLE_GAMES = "unstable_games"
        private const val KEY_FAULT_LOG = "fault_log_enabled"
        private const val KEY_DEFAULT_BANNER = "default_home_banner_dismissed"
        private const val KEY_RECENTS = "recent_launches"
        private const val KEY_LAUNCH_COUNT_PREFIX = "launch_count:"
        private const val SEP = "|"
        private const val MAX_RECENTS = 12
        @Volatile private var instance: OeaDataStore? = null
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: OeaDataStore(context).also { instance = it }
        }
    }
}
