package app.lawnchair.oea.runtime

import app.lawnchair.oea.data.OeaDataStore
import app.lawnchair.oea.engine.OeaEngine
import app.lawnchair.oea.ui.OeaHomeSurfaceController
import com.android.launcher3.Launcher
import kotlinx.coroutines.flow.StateFlow

/**
 * OEA home/controller facade.
 *
 * The independent OEA engine is the source of OEA application state. Launcher3 is only the
 * current compatibility surface; this controller deliberately does not register an OEA callback
 * with Launcher3's model, preventing model lifecycle problems from becoming engine dependencies.
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
