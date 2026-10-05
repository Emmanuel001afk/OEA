package app.lawnchair.oea.runtime

import android.app.Activity
import android.view.ViewGroup
import app.lawnchair.oea.data.OeaDataStore
import app.lawnchair.oea.engine.OeaEngine
import app.lawnchair.oea.ui.OeaHomeSurfaceController
import kotlinx.coroutines.flow.StateFlow

/**
 * OEA home/controller facade. The HOME activity and surface are OEA-owned.
 */
class OeaHomeController {
    private var surfaceController: OeaHomeSurfaceController? = null
    private var attachedActivity: Activity? = null
    private var engine: OeaEngine? = null

    val state: StateFlow<OeaRuntime.RuntimeState>
        get() = OeaRuntime.state

    fun onHomeAttached(activity: Activity, root: ViewGroup) {
        attachedActivity = activity
        OeaRuntime.attachLauncher()
        engine = OeaEngine.get(activity).start()
        OeaDataStore.get(activity).setApplicationCount(engine?.apps?.value?.size ?: 0)
        surfaceController = OeaHomeSurfaceController(activity, root).also { it.attach() }
    }

    fun onHomeDetached() {
        surfaceController?.detach()
        surfaceController = null
        attachedActivity = null
        engine = null
        OeaRuntime.detachLauncher()
    }
}
