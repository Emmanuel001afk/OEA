package app.lawnchair.oea.ui

import android.content.Context
import android.view.View
import app.lawnchair.oea.model.OeaAppInfo
import app.lawnchair.oea.workspace.OeaWorkspace

/**
 * OEA-owned home surface. No Launcher3 Launcher/DragLayer/Workspace dependency.
 */
class OeaHomeSurfaceController(private val context: Context) {
    private var workspace: OeaWorkspace? = null

    fun attach(apps: List<OeaAppInfo>): View {
        workspace = OeaWorkspace(context).also { it.bind(apps) }
        return workspace!!
    }

    fun detach() {
        workspace = null
    }
}