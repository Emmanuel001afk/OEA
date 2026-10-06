package app.lawnchair.oea.runtime

import android.app.Application
import android.content.Context
import app.lawnchair.oea.engine.OeaLauncherEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object OeaRuntime {
    data class RuntimeState(val initialized:Boolean=false)

    private var application: Application? = null
    private var engine: OeaLauncherEngine? = null
    private val _state = MutableStateFlow(RuntimeState())
    val state: StateFlow<RuntimeState> = _state.asStateFlow()

    @Synchronized
    fun initialize(app: Application) {
        if (application === app && engine != null) return
        application = app
        engine = OeaLauncherEngine(app)
        _state.value = RuntimeState(initialized = true)
    }

    fun engine(context: Context): OeaLauncherEngine =
        engine ?: synchronized(this) {
            engine ?: OeaLauncherEngine(context.applicationContext).also { engine = it }
        }
}