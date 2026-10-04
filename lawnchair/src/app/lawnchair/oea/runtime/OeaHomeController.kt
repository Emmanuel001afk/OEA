package app.lawnchair.oea.runtime

import com.android.launcher3.LauncherState
import com.android.launcher3.Launcher
import app.lawnchair.oea.ui.OeaHomeSurfaceController
import kotlinx.coroutines.flow.StateFlow

/**
 * OEA home/controller facade.
 *
 * Keeps OEA feature code out of the Launcher3 model itself while exposing the current
 * launcher state to OEA-owned UI and services.
 */
class OeaHomeController {
    private var surfaceController: OeaHomeSurfaceController? = null
    val state: StateFlow<OeaRuntime.RuntimeState>
        get() = OeaRuntime.state

    fun onLauncherAttached(launcher: Launcher, initialState: LauncherState) {
        OeaRuntime.attachLauncher(initialState)
        surfaceController = OeaHomeSurfaceController(launcher).also { it.attach() }
    }

    fun onLauncherStateChanged(state: LauncherState) {
        OeaRuntime.updateLauncherState(state)
    }

    fun onLauncherDetached() {
        surfaceController?.detach()
        surfaceController = null
        OeaRuntime.detachLauncher()
    }
}
