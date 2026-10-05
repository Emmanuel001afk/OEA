package app.lawnchair.oea.engine

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class OeaAppCatalog(context: Context) {
    data class App(
        val component: ComponentName,
        val packageName: String,
        val label: String,
    )

    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager
    private val _apps = MutableStateFlow<List<App>>(emptyList())
    val apps: StateFlow<List<App>> = _apps.asStateFlow()

    fun refresh(): List<App> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val result = packageManager.queryIntentActivities(intent, 0)
            .mapNotNull(::toApp)
            .distinctBy { it.component }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
        _apps.value = result
        return result
    }

    private fun toApp(info: ResolveInfo): App? {
        val activity = info.activityInfo ?: return null
        val component = ComponentName(activity.packageName, activity.name)
        val label = info.loadLabel(packageManager)?.toString()?.trim()
            .takeUnless { it.isNullOrEmpty() } ?: activity.packageName
        return App(component, activity.packageName, label)
    }
}
