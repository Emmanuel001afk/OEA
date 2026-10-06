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
    fun gridColumns() = prefs.getInt(KEY_GRID_COLUMNS, 4).coerceIn(3, 5)
    fun setGridColumns(v: Int) { prefs.edit().putInt(KEY_GRID_COLUMNS, v.coerceIn(3, 5)).apply() }
    fun sortMode() = runCatching {
        SortMode.valueOf(prefs.getString(KEY_SORT_MODE, SortMode.NAME.name) ?: SortMode.NAME.name)
    }.getOrDefault(SortMode.NAME)
    fun setSortMode(v: SortMode) { prefs.edit().putString(KEY_SORT_MODE, v.name).apply() }

    fun recordLaunch(p: String, c: String) {
        val k = componentKey(p, c)
        val r = recentLaunches().toMutableList().apply { remove(k); add(0, k) }.take(MAX_RECENTS)
        prefs.edit().putString(KEY_RECENTS, r.joinToString(SEP)).apply()
    }
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
        private const val KEY_SORT_MODE = "sort_mode"
        private const val KEY_RECENTS = "recent_launches"
        private const val SEP = "|"
        private const val MAX_RECENTS = 12
        @Volatile private var instance: OeaDataStore? = null
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: OeaDataStore(context).also { instance = it }
        }
    }
}
