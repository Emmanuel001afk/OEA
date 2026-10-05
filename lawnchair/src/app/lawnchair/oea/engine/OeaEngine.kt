package app.lawnchair.oea.engine

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.UserHandle
import android.provider.Settings
import app.lawnchair.oea.data.OeaDataStore
import kotlinx.coroutines.flow.StateFlow

/**
 * Independent OEA launcher engine foundation.
 *
 * The engine owns application discovery, local search, workspace persistence and launching.
 * Launcher3 is still the compatibility UI while this engine is proven; the engine itself does
 * not depend on Launcher3's model or database.
 */
class OeaEngine private constructor(context: Context) {
    sealed interface Result {
        data class Success(val message: String) : Result
        data class Failure(val message: String, val cause: Throwable? = null) : Result
    }

    private val appContext = context.applicationContext
    private val dataStore = OeaDataStore.get(appContext)
    private val launcherApps = appContext.getSystemService(LauncherApps::class.java)

    val catalog = OeaAppCatalog(appContext)
    val search = OeaSearchEngine(catalog)
    val workspace = OeaWorkspaceStore(appContext)
    val apps: StateFlow<List<OeaAppCatalog.App>> = catalog.apps

    @Synchronized
    fun start(): OeaEngine {
        catalog.start()
        return this
    }

    @Synchronized
    fun stop() {
        catalog.stop()
    }

    fun launch(component: ComponentName, user: UserHandle? = null): Result {
        val app = apps.value.firstOrNull {
            it.component == component && (user == null || it.user == user)
        } ?: return Result.Failure("App is no longer available: " + component.flattenToShortString())

        val result = runCatching {
            launcherApps.startMainActivity(app.component, app.user, null, null)
            Result.Success("Opened " + component.packageName)
        }.getOrElse {
            Result.Failure("Unable to open " + component.packageName, it)
        }

        dataStore.recordAction(
            "launch:" + component.flattenToShortString(),
            result.message(),
        )
        return result
    }

    fun execute(action: Action): Result {
        val result = when (action) {
            is Action.LaunchPackage -> {
                val app = apps.value.firstOrNull { it.packageName == action.packageName }
                if (app == null) {
                    Result.Failure("No launchable app for " + action.packageName)
                } else {
                    launch(app.component, app.user)
                }
            }

            Action.OpenHomeSettings -> runCatching {
                appContext.startActivity(
                    Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                Result.Success("Opened Home settings")
            }.getOrElse {
                Result.Failure("Unable to open Home settings", it)
            }
        }

        dataStore.recordAction(action.describe(), result.message())
        return result
    }

    private fun Result.message(): String = when (this) {
        is Result.Success -> message
        is Result.Failure -> message
    }

    sealed interface Action {
        fun describe(): String

        data class LaunchPackage(val packageName: String) : Action {
            override fun describe(): String = "launch:" + packageName
        }

        data object OpenHomeSettings : Action {
            override fun describe(): String = "open-home-settings"
        }
    }

    companion object {
        @Volatile private var instance: OeaEngine? = null

        fun get(context: Context): OeaEngine =
            instance ?: synchronized(this) {
                instance ?: OeaEngine(context).also { instance = it }
            }
    }
}
