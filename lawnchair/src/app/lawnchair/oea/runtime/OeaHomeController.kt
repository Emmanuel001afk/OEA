package app.lawnchair.oea.runtime

import app.lawnchair.oea.data.OeaDataStore
import app.lawnchair.oea.engine.OeaModelBridge
import app.lawnchair.oea.ui.OeaHomeSurfaceController
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherState
import kotlinx.coroutines.flow.StateFlow

/**
 * OEA home/controller facade.
 *
 * Launcher3/Lawnchair remains the owner of the real home model and workspace. OEA attaches
 * after the launcher has created its first view hierarchy, then requests an initial callback
 * bind so OEA observes the already-loaded model without replacing Launcher3's callbacks.
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

        launcher.rootView.post {
            if (attachedLauncher !== launcher) return@post

            surfaceController = OeaHomeSurfaceController(launcher).also { it.attach() }

            val bridge = OeaModelBridge(OeaDataStore.get(launcher))
            modelBridge = bridge

            // addCallbacks() alone never delivers the current model. addCallbacksAndLoad()
            // binds OEA to the current Launcher3 model while preserving Launcher3 ownership
            // of the workspace and all existing callbacks.
            LauncherAppState.getInstance(launcher).model.addCallbacksAndLoad(bridge)
        }
    }

    fun onLauncherStateChanged(state: LauncherState) {
        OeaRuntime.updateLauncherState(state)
    }

    fun onLauncherDetached() {
        surfaceController?.detach()
        surfaceController = null
        modelBridge?.let { bridge ->
            attachedLauncher?.let {
                LauncherAppState.getInstance(it).model.removeCallbacks(bridge)
            }
        }
        modelBridge = null
        attachedLauncher = null
        OeaRuntime.detachLauncher()
    }
}
