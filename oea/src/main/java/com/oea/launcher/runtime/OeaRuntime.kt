package com.oea.launcher.runtime

import android.app.Application
import android.content.Context
import com.oea.launcher.engine.OeaLauncherEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-level service locator for OEA's launcher engine. Registering the
 * Application is cheap; the engine and Home view tree are created only when a
 * launcher feature actually requests them, so standalone OEA apps do not pay
 * Home's cold-start cost or inherit a failed Home initialization.
 */
object OeaRuntime {
    data class RuntimeState(val initialized: Boolean = false)

    private val lock = Any()
    @Volatile private var appContext: Context? = null
    @Volatile private var engineInstance: OeaLauncherEngine? = null
    private val _state = MutableStateFlow(RuntimeState())
    val state: StateFlow<RuntimeState> = _state.asStateFlow()

    fun initialize(app: Application) {
        if (appContext == null) appContext = app.applicationContext
    }

    fun engine(context: Context): OeaLauncherEngine {
        engineInstance?.let { return it }
        return synchronized(lock) {
            engineInstance ?: OeaLauncherEngine((appContext ?: context.applicationContext).applicationContext).also {
                engineInstance = it
                _state.value = RuntimeState(initialized = true)
            }
        }
    }
}
