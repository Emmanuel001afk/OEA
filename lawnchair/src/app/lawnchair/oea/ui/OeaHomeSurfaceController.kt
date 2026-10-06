package app.lawnchair.oea.ui

import android.content.Context
import android.view.View
import app.lawnchair.oea.engine.OeaLauncherEngine

/**
 * OEA-owned home surface. The engine-owned workspace is the single displayed
 * workspace instance, so refreshes update the visible HOME rather than an
 * off-screen replacement view.
 */
class OeaHomeSurfaceController(private val context: Context) {
    fun attach(engine: OeaLauncherEngine): View {
        engine.workspace.bind(engine.model.apps)
        return engine.workspace
    }

    fun refresh(engine: OeaLauncherEngine) {
        engine.workspace.bind(engine.model.apps)
    }
}