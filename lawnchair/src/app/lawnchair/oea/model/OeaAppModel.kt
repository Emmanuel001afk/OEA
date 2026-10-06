package app.lawnchair.oea.model

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import app.lawnchair.oea.OeaLauncherActivity
import android.content.pm.ResolveInfo

data class OeaAppInfo(
    val packageName: String,
    val className: String,
    val label: String
)

class OeaAppModel(private val context: Context) {
    var apps: List<OeaAppInfo> = emptyList()
        private set

    fun load() {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        apps = context.packageManager.queryIntentActivities(intent, 0)
            .filter { info: ResolveInfo -> info.activityInfo.packageName != context.packageName }
            .map { info: ResolveInfo ->
                OeaAppInfo(
                    info.activityInfo.packageName,
                    info.activityInfo.name,
                    info.loadLabel(context.packageManager).toString()
                )
            }
            .distinctBy { ComponentName(it.packageName, it.className) }
            .filterNot {
                it.packageName == context.packageName &&
                    it.className == OeaLauncherActivity::class.java.name
            }
            .sortedBy { it.label.lowercase() }
    }
}
