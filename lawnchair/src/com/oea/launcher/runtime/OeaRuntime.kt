package com.oea.launcher.runtime

import android.app.Application
import android.content.Context
import com.oea.launcher.engine.OeaLauncherEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object OeaRuntime {
    data class RuntimeState(val initialized: Boolean = false)

    private var engine: OeaLauncherEngine? = null
    private val _state = MutableStateFlow(RuntimeState())
    val state: StateFlow<RuntimeState> = _state.asStateFlow()

    @Synchronized
    fun initialize(app: Application) {
        if (engine != null) return
        engine = OeaLauncherEngine(app.applicationContext)
        _state.value = RuntimeState(initialized = true)
    }

    fun engine(context: Context): OeaLauncherEngine =
        engine ?: synchronized(this) {
            engine ?: OeaLauncherEngine(context.applicationContext).also { engine = it; _state.value = RuntimeState(initialized = true) }
        }
}