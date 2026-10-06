package app.lawnchair.oea.runtime

import android.content.Context
import app.lawnchair.oea.engine.OeaLauncherEngine
import app.lawnchair.oea.ui.OeaHomeSurfaceController

class OeaHomeController(private val context: Context) {
    private val engine: OeaLauncherEngine
        get() = OeaRuntime.engine(context)
    private val surfaceController = OeaHomeSurfaceController(context)

    fun createHomeSurface(): android.view.View {
        engine.start()
        return surfaceController.attach(engine.model.apps)
    }

    fun refresh() {
        engine.start()
        surfaceController.attach(engine.model.apps)
    }
}