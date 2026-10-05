package app.lawnchair.oea.engine

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.os.UserHandle
import android.os.UserManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * OEA-owned application inventory.
 *
 * LauncherApps is the Android launcher API and therefore the engine's inventory authority.
 * This avoids coupling app discovery to Launcher3's LauncherModel.
 */
class OeaAppCatalog(context: Context) {
    data class App(
        val component: ComponentName,
        val packageName: String,
        val label: String,
        val user: UserHandle,
    )

    private val appContext = context.applicationContext
    private val launcherApps = appContext.getSystemService(LauncherApps::class.java)
    private val userManager = appContext.getSystemService(UserManager::class.java)
    private val _apps = MutableStateFlow<List<App>>(emptyList())
    val apps: StateFlow<List<App>> = _apps.asStateFlow()

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageAdded(packageName: String, user: UserHandle) {
            refresh()
        }

        override fun onPackageRemoved(packageName: String, user: UserHandle) {
            refresh()
        }

        override fun onPackageChanged(packageName: String, user: UserHandle) {
            refresh()
        }

        override fun onPackagesAvailable(
            packageNames: Array<out String>,
            user: UserHandle,
            replacing: Boolean,
        ) {
            refresh()
        }

        override fun onPackagesUnavailable(
            packageNames: Array<out String>,
            user: UserHandle,
            replacing: Boolean,
        ) {
            refresh()
        }
    }

    @Synchronized
    fun start(): OeaAppCatalog {
        launcherApps.registerCallback(callback)
        refresh()
        return this
    }

    @Synchronized
    fun stop() {
        launcherApps.unregisterCallback(callback)
    }

    fun refresh(): List<App> {
        val result = buildList {
            val profiles = userManager.userProfiles.ifEmpty {
                listOf(android.os.Process.myUserHandle())
            }
            for (user in profiles) {
                val activities = runCatching {
                    launcherApps.getActivityList(null, user)
                }.getOrDefault(emptyList())
                activities.forEach { activity ->
                    toApp(activity, user)?.let(::add)
                }
            }
        }
            .distinctBy { it.component to it.user }
            .sortedWith(
                compareBy<App, String>(String.CASE_INSENSITIVE_ORDER) { it.label }
                    .thenBy { it.component.flattenToShortString() },
            )
        _apps.value = result
        return result
    }

    private fun toApp(info: LauncherActivityInfo, user: UserHandle): App {
        val component = info.componentName
        val label = info.label?.toString()?.trim()
            .takeUnless { it.isNullOrEmpty() } ?: component.packageName
        return App(component, component.packageName, label, user)
    }
}
