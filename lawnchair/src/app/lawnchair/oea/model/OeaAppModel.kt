package app.lawnchair.oea.model

import android.content.Context
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
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        }
        apps = context.packageManager.queryIntentActivities(intent, 0)
            .map { info: ResolveInfo ->
                OeaAppInfo(
                    info.activityInfo.packageName,
                    info.activityInfo.name,
                    info.loadLabel(context.packageManager).toString()
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }
}
