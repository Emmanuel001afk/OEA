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
            LauncherAppState.getInstance(launcher).model.addCallbacks(bridge)

        // Do not force a model rebind here. Launcher3 owns the initial workspace/app binding;
        // OEA observes subsequent model updates without taking over the startup bind.
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
