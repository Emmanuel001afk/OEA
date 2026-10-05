package app.lawnchair.oea.runtime

import com.android.launcher3.LauncherState
import com.android.launcher3.Launcher
import app.lawnchair.oea.ui.OeaHomeSurfaceController
import app.lawnchair.oea.data.OeaDataStore
import app.lawnchair.oea.engine.OeaModelBridge
import com.android.launcher3.LauncherAppState
import kotlinx.coroutines.flow.StateFlow

/**
 * OEA home/controller facade.
 *
 * Keeps OEA feature code out of the Launcher3 model itself while exposing the current
 * launcher state to OEA-owned UI and services.
 */
class OeaHomeController {
    private var surfaceController: OeaHomeSurfaceController? = null
    private var modelBridge: OeaModelBridge? = null
    private var attachedLauncher: Launcher? = null
    val state: StateFlow<OeaRuntime.RuntimeState>
        get() = OeaRuntime.state

    fun onLauncherAttached(launcher: Launcher, initialState: LauncherState) {
        attachedLauncher = launcher
        OeaRuntime.attachLauncher(initialState)
        surfaceController = OeaHomeSurfaceController(launcher).also { it.attach() }
        modelBridge = OeaModelBridge(OeaDataStore.get(launcher)).also { bridge ->
            val model = LauncherAppState.getInstance(launcher).model
            // Join an existing Launcher3 load instead of interrupting it. If no load is active,
            // use the normal loader path so OEA receives an initial model snapshot too.
            if (model.isActive()) {
                model.addCallbacks(bridge)
            } else {
                model.addCallbacksAndLoad(bridge)
            }
        }
    }

    fun onLauncherStateChanged(state: LauncherState) {
        OeaRuntime.updateLauncherState(state)
    }

    fun onLauncherDetached() {
        surfaceController?.detach()
        surfaceController = null
        modelBridge?.let { bridge ->
            attachedLauncher?.let { LauncherAppState.getInstance(it).model.removeCallbacks(bridge) }
        }
        modelBridge = null
        attachedLauncher = null
        OeaRuntime.detachLauncher()
    }
}
