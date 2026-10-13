package com.oea.launcher.engine

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.view.ContextThemeWrapper
import com.oea.launcher.R
import com.oea.launcher.icons.OeaIconController
import com.oea.launcher.model.OeaAppInfo
import com.oea.launcher.model.OeaAppModel
import com.oea.launcher.workspace.OeaWorkspace
import java.util.concurrent.Executors

/**
 * Owns the app model and the single OEA Home workspace.
 *
 * The engine is available application-wide, but the heavyweight Home view is
 * created only when Home actually requests it. App discovery runs off the UI
 * thread; the first frame can appear while Android's package manager responds.
 */
class OeaLauncherEngine(private val context: Context) {
    val model = OeaAppModel(context)
    private val workspaceDelegate = lazy {
        OeaWorkspace(ContextThemeWrapper(context, R.style.OeaHomeTheme))
    }
    val workspace: OeaWorkspace get() = workspaceDelegate.value

    private var lastBoundApps: List<OeaAppInfo> = emptyList()
    private var workspaceBound = false
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var dirty = true
    private var loading = false
    private var reloadPending = false

    private val packageCallback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = packagesChanged(arrayOf(packageName))
        override fun onPackageAdded(packageName: String, user: UserHandle) = packagesChanged(arrayOf(packageName))
        override fun onPackageChanged(packageName: String, user: UserHandle) = packagesChanged(arrayOf(packageName))
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = packagesChanged(packageNames)
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = packagesChanged(packageNames)
    }

    init {
        runCatching { context.getSystemService(LauncherApps::class.java)?.registerCallback(packageCallback, main) }
    }

    /** Called on every Home resume; never performs package discovery on the UI thread. */
    fun start() {
        // Package callbacks can populate the model before Home is first shown.
        // Bind that cached result once the Home workspace has actually been created.
        if (workspaceDelegate.isInitialized() && !workspaceBound && model.apps.isNotEmpty()) {
            bind(model.apps)
        }
        if (lastBoundApps.isEmpty() || dirty) reloadAsync()
    }

    fun reloadAsync() {
        if (loading) {
            reloadPending = true
            return
        }
        loading = true
        executor.execute {
            val apps = runCatching { model.query() }.getOrNull()
            main.post {
                loading = false
                // Never bind an empty result: that would wipe the saved layout.
                if (!apps.isNullOrEmpty()) {
                    model.setApps(apps)
                    if (apps != lastBoundApps || (workspaceDelegate.isInitialized() && !workspaceBound)) {
                        bind(apps)
                    }
                    dirty = false
                    preloadIcons(apps)
                }
                if (reloadPending) {
                    reloadPending = false
                    reloadAsync()
                }
            }
        }
    }

    private fun bind(apps: List<OeaAppInfo>) {
        if (workspaceDelegate.isInitialized()) {
            workspaceDelegate.value.bind(apps)
            workspaceBound = true
        }
        lastBoundApps = apps.toList()
    }

    private fun packagesChanged(packages: Array<out String>) {
        val icons = OeaIconController.shared(context)
        packages.forEach { icons.invalidatePackage(it) }
        dirty = true
        reloadAsync()
    }

    private fun preloadIcons(apps: List<OeaAppInfo>) {
        val targets = apps.map { it.packageName to it.className }
        executor.execute { OeaIconController.shared(context).preload(targets) }
    }

    fun launchApp(packageName: String, className: String) {
        context.startActivity(Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setClassName(packageName, className)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
