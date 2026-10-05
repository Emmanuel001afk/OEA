package app.lawnchair.oea.runtime

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * OEA-owned runtime boundary between the Lawnchair/Launcher3 foundation and OEA features.
 *
 * This deliberately contains no Aria-specific types. Launcher3 remains responsible for
 * workspace/model/app enumeration; OEA owns the lifecycle and feature coordination above it.
 */
object OeaRuntime {
    data class RuntimeState(
        val initialized: Boolean = false,
        val launcherAttached: Boolean = false,
    )

    private var application: Application? = null
    private var scope: CoroutineScope? = null
    private val _state = MutableStateFlow(RuntimeState())
    val state: StateFlow<RuntimeState> = _state.asStateFlow()

    @Synchronized
    fun initialize(app: Application) {
        if (application === app && scope != null) {
            _state.update { it.copy(initialized = true) }
            return
        }

        scope?.cancel()
        application = app
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        _state.update { it.copy(initialized = true) }
    }

    fun attachLauncher() {
        if (!stateInitialized()) return
        _state.update { it.copy(launcherAttached = true) }
    }

    fun detachLauncher() {
        if (!stateInitialized()) return
        _state.update { it.copy(launcherAttached = false, launcherState = null) }
    }

    private fun stateInitialized(): Boolean = _state.value.initialized
}
