package com.oea.launcher.engine

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.util.Log
import android.view.ContextThemeWrapper
import com.oea.launcher.R
import com.oea.launcher.icons.OeaIconController
import com.oea.launcher.model.OeaAppInfo
import com.oea.launcher.model.OeaAppModel
import com.oea.launcher.workspace.OeaWorkspace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

/**
 * OEA's process-scoped launcher engine. It owns catalogue refresh, package
 * invalidation and the persistent workspace, while activities remain thin
 * hosts. The view tree is created lazily, and package enumeration never blocks
 * Home's main thread.
 */
class OeaLauncherEngine(context: Context) {
    data class EngineState(
        val loadingApps: Boolean = false,
        val appCount: Int = 0,
        val lastSuccessfulLoadMillis: Long? = null,
        val lastError: String? = null,
    )

    private val appContext = context.applicationContext
    val model = OeaAppModel(appContext)

    /** Do not allocate the Home view tree when Game Boost, Phone, or Settings opens. */
    val workspace: OeaWorkspace by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        OeaWorkspace(ContextThemeWrapper(appContext, R.style.OeaHomeTheme))
    }

    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "OeaLauncherEngine").apply { isDaemon = true }
    }
    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    @Volatile private var dirty = true
    @Volatile private var started = false
    @Volatile private var lastBoundApps: List<OeaAppInfo> = emptyList()
    private var loading = false
    private var reloadPending = false
    private var hasSuccessfulLoad = false

    private val packageCallback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = packagesChanged(arrayOf(packageName))
        override fun onPackageAdded(packageName: String, user: UserHandle) = packagesChanged(arrayOf(packageName))
        override fun onPackageChanged(packageName: String, user: UserHandle) = packagesChanged(arrayOf(packageName))
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = packagesChanged(packageNames)
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = packagesChanged(packageNames)
    }

    init {
        runCatching { appContext.getSystemService(LauncherApps::class.java)?.registerCallback(packageCallback, main) }
            .onFailure { Log.w(TAG, "Could not register package-change callback", it) }
    }

    /** Called on Home creation/resume; normally a no-op after the first load. */
    fun start() {
        started = true
        if (!loading && (!hasSuccessfulLoad || dirty)) reloadAsync()
    }

    /** Safe from any thread; results and workspace updates are marshalled to main. */
    fun reloadAsync() {
        if (Looper.myLooper() != main.looper) {
            main.post { reloadAsync() }
            return
        }
        if (loading) {
            reloadPending = true
            return
        }
        loading = true
        if (lastBoundApps.isEmpty()) workspace.setAppLoadStatus("Loading apps…")
        _state.value = _state.value.copy(loadingApps = true, lastError = null)

        try {
            executor.execute {
                val result = runCatching { model.query() }
                main.post { finishReload(result) }
            }
        } catch (error: RuntimeException) {
            finishReload(Result.failure(error))
        }
    }

    /**
     * A recoverable refresh problem must never look like a broken launcher.
     *
     * Home only shows its blocking retry affordance when there is genuinely
     * nothing to show — i.e. no app catalogue has ever been bound successfully.
     * When a previous good workspace is on screen, a transient PackageManager or
     * bind failure keeps that workspace visible and is logged instead. This is
     * what made the old build claim Home had failed while the user could see a
     * fully working launcher behind the message.
     */
    private val hasUsableWorkspace: Boolean get() = lastBoundApps.isNotEmpty()

    private fun finishReload(result: Result<List<OeaAppInfo>>) {
        loading = false
        result.fold(
            onSuccess = { apps ->
                hasSuccessfulLoad = true
                dirty = false
                if (apps.isNotEmpty()) {
                    model.setApps(apps)
                    val bound = apps == lastBoundApps || bind(apps)
                    if (!bound) {
                        dirty = true
                        val error = IllegalStateException("Workspace rejected the refreshed app catalogue")
                        Log.e(TAG, "Could not bind app catalogue; keeping the last good workspace", error)
                        _state.value = _state.value.copy(loadingApps = false, lastError = error.message)
                        if (hasUsableWorkspace) {
                            workspace.setAppLoadStatus(null)
                        } else {
                            workspace.setAppLoadStatus("Home could not refresh · tap to retry") { reloadAsync() }
                        }
                        return@fold
                    }
                    _state.value = EngineState(
                        loadingApps = false,
                        appCount = lastBoundApps.size,
                        lastSuccessfulLoadMillis = System.currentTimeMillis(),
                    )
                    workspace.setAppLoadStatus(null)
                    preloadIcons(apps)
                } else {
                    _state.value = EngineState(
                        loadingApps = false,
                        appCount = lastBoundApps.size,
                        lastSuccessfulLoadMillis = System.currentTimeMillis(),
                    )
                    if (hasUsableWorkspace) {
                        // An empty reply is transient on many devices (profile
                        // switch, package install, direct-boot window). Keep the
                        // workspace the user can already see and retry quietly.
                        dirty = true
                        Log.w(TAG, "App catalogue came back empty; keeping the last good workspace")
                        workspace.setAppLoadStatus(null)
                    } else {
                        workspace.setAppLoadStatus("No launchable apps found · tap to retry") { reloadAsync() }
                    }
                }
            },
            onFailure = { error ->
                dirty = true
                val message = error.message ?: error.javaClass.simpleName
                Log.e(TAG, "App catalogue refresh failed; keeping the last good workspace", error)
                _state.value = EngineState(
                    loadingApps = false,
                    appCount = lastBoundApps.size,
                    lastSuccessfulLoadMillis = _state.value.lastSuccessfulLoadMillis,
                    lastError = message,
                )
                if (hasUsableWorkspace) workspace.setAppLoadStatus(null)
                else workspace.setAppLoadStatus("Apps could not be loaded · tap to retry") { reloadAsync() }
            },
        )

        if (reloadPending) {
            reloadPending = false
            reloadAsync()
        }
    }

    private fun bind(apps: List<OeaAppInfo>): Boolean = runCatching {
        workspace.bind(apps)
        lastBoundApps = apps.toList()
        true
    }.onFailure { Log.e(TAG, "Workspace bind failed for ${apps.size} apps", it) }
        .getOrDefault(false)

    private fun packagesChanged(packages: Array<out String>) {
        runCatching {
            val icons = OeaIconController.shared(appContext)
            packages.forEach { icons.invalidatePackage(it) }
        }.onFailure { Log.w(TAG, "Could not invalidate changed app icons", it) }
        dirty = true
        if (started) reloadAsync()
    }

    private fun preloadIcons(apps: List<OeaAppInfo>) {
        val targets = apps.map { it.packageName to it.className }
        runCatching { executor.execute { OeaIconController.shared(appContext).preload(targets) } }
            .onFailure { Log.w(TAG, "Could not queue launcher icon preload", it) }
    }

    fun launchApp(packageName: String, className: String) {
        runCatching {
            appContext.startActivity(Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setClassName(packageName, className)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }.onFailure { Log.w(TAG, "Could not launch $packageName/$className", it) }
    }

    private companion object {
        const val TAG = "OeaLauncherEngine"
    }
}
