package app.lawnchair.oea.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * OEA-owned launcher preferences/state.
 *
 * This is deliberately separate from Launcher3's launcher database so OEA can
 * evolve its workspace model without inheriting legacy HOME ownership.
 */
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

    fun isFavorite(packageName: String, className: String): Boolean =
        favorites().contains(componentKey(packageName, className))

    fun setFavorite(packageName: String, className: String, favorite: Boolean) {
        val updated = favorites().toMutableSet()
        val key = componentKey(packageName, className)
        if (favorite) updated.add(key) else updated.remove(key)
        prefs.edit().putStringSet(KEY_FAVORITES, updated).apply()
    }

    fun gridColumns(): Int = prefs.getInt(KEY_GRID_COLUMNS, 4).coerceIn(3, 5)

    fun setGridColumns(columns: Int) {
        prefs.edit().putInt(KEY_GRID_COLUMNS, columns.coerceIn(3, 5)).apply()
    }

    fun sortMode(): SortMode =
        runCatching { SortMode.valueOf(prefs.getString(KEY_SORT_MODE, SortMode.NAME.name) ?: SortMode.NAME.name) }
            .getOrDefault(SortMode.NAME)

    fun setSortMode(mode: SortMode) {
        prefs.edit().putString(KEY_SORT_MODE, mode.name).apply()
    }

    fun recordLaunch(packageName: String, className: String) {
        val key = componentKey(packageName, className)
        val recent = recentLaunches().toMutableList().apply {
            remove(key)
            add(0, key)
        }.take(MAX_RECENTS)
        prefs.edit().putString(KEY_RECENTS, recent.joinToString(SEP)).apply()
    }

    fun recentLaunches(): List<String> =
        prefs.getString(KEY_RECENTS, null)
            ?.split(SEP)
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    fun recordAction(command: String, result: String) {
        prefs.edit()
            .putString(KEY_LAST_COMMAND, command)
            .putString(KEY_LAST_RESULT, result)
            .apply()
    }

    fun lastCommand(): String? = prefs.getString(KEY_LAST_COMMAND, null)
    fun lastResult(): String? = prefs.getString(KEY_LAST_RESULT, null)

    private fun componentKey(packageName: String, className: String): String =
        "$packageName/$className"

    enum class SortMode { NAME, RECENT }

    companion object {
        private const val PREFS = "oea_data"
        private const val KEY_HOME_SURFACE = "home_surface_enabled"
        private const val KEY_LAST_COMMAND = "last_command"
        private const val KEY_LAST_RESULT = "last_result"
        private const val KEY_FAVORITES = "favorite_apps"
        private const val KEY_GRID_COLUMNS = "grid_columns"
        private const val KEY_SORT_MODE = "sort_mode"
        private const val KEY_RECENTS = "recent_launches"
        private const val SEP = "|"
        private const val MAX_RECENTS = 12

        @Volatile private var instance: OeaDataStore? = null

        fun get(context: Context): OeaDataStore =
            instance ?: synchronized(this) {
                instance ?: OeaDataStore(context).also { instance = it }
            }
    }
}
