package app.lawnchair.oea.engine

import android.content.Context
import android.content.Intent
import app.lawnchair.oea.model.OeaAppModel
import app.lawnchair.oea.workspace.OeaWorkspace

/**
 * OEA-owned launcher runtime.
 *
 * This is deliberately independent of Launcher3's Launcher/Quickstep runtime.
 * Launcher3 can remain in the repository as source reference during migration,
 * but OEA owns startup, model loading and workspace creation here.
 */
class OeaLauncherEngine(private val context: Context) {
    val model = OeaAppModel(context)
    val workspace = OeaWorkspace(context)

    fun start() {
        model.load()
        workspace.bind(model.apps)
    }

    fun launchApp(packageName: String, className: String) {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setClassName(packageName, className)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
