package app.lawnchair.oea.runtime

import com.android.launcher3.LauncherState
import kotlinx.coroutines.flow.StateFlow

/**
 * OEA home/controller facade.
 *
 * Keeps OEA feature code out of the Launcher3 model itself while exposing the current
 * launcher state to OEA-owned UI and services.
 */
class OeaHomeController {
    val state: StateFlow<OeaRuntime.RuntimeState>
        get() = OeaRuntime.state

    fun onLauncherAttached(initialState: LauncherState) {
        OeaRuntime.attachLauncher(initialState)
    }

    fun onLauncherStateChanged(state: LauncherState) {
        OeaRuntime.updateLauncherState(state)
    }

    fun onLauncherDetached() {
        OeaRuntime.detachLauncher()
    }
}
