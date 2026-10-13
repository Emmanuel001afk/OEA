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
