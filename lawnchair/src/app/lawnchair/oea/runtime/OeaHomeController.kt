package app.lawnchair.oea.runtime

import app.lawnchair.oea.data.OeaDataStore
import app.lawnchair.oea.engine.OeaEngine
import app.lawnchair.oea.ui.OeaHomeSurfaceController
import com.android.launcher3.Launcher
import kotlinx.coroutines.flow.StateFlow

/**
 * OEA home/controller facade.
 *
 * OEA owns application state and the visible HOME surface. The Launcher3/Lawnchair base activity
 * is only a compatibility shell; this controller never binds OEA state to Launcher3's model.
 */
class OeaHomeController {
    private var surfaceController: OeaHomeSurfaceController? = null
    private var attachedLauncher: Launcher? = null
    private var engine: OeaEngine? = null

    val state: StateFlow<OeaRuntime.RuntimeState>
        get() = OeaRuntime.state

    fun onLauncherAttached(launcher: Launcher) {
        attachedLauncher = launcher
        OeaRuntime.attachLauncher()

        engine = OeaEngine.get(launcher).start()
        OeaDataStore.get(launcher).setApplicationCount(engine?.apps?.value?.size ?: 0)

        surfaceController = OeaHomeSurfaceController(launcher).also { it.attach() }
    }

    fun onLauncherDetached() {
        surfaceController?.detach()
        surfaceController = null
        attachedLauncher = null
        engine = null
        OeaRuntime.detachLauncher()
    }
}
