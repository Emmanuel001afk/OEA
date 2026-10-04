package app.lawnchair.oea.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * OEA-owned lightweight persistent state.
 *
 * Launcher3/Lawnchair keeps launcher/workspace state in its own model and database.
 * This store is intentionally separate so OEA feature state never becomes coupled to
 * Launcher3's schema.
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

    fun recordAction(command: String, result: String) {
        prefs.edit()
            .putString(KEY_LAST_COMMAND, command)
            .putString(KEY_LAST_RESULT, result)
            .apply()
    }

    fun lastCommand(): String? = prefs.getString(KEY_LAST_COMMAND, null)
    fun lastResult(): String? = prefs.getString(KEY_LAST_RESULT, null)

    companion object {
        private const val PREFS = "oea_data"
        private const val KEY_HOME_SURFACE = "home_surface_enabled"
        private const val KEY_LAST_COMMAND = "last_command"
        private const val KEY_LAST_RESULT = "last_result"

        @Volatile private var instance: OeaDataStore? = null

        fun get(context: Context): OeaDataStore =
            instance ?: synchronized(this) {
                instance ?: OeaDataStore(context).also { instance = it }
            }
    }
}
