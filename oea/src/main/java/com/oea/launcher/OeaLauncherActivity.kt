package com.oea.launcher

import android.app.Activity
import android.app.AppOpsManager
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Process
import com.oea.launcher.gameboost.OeaGameBoostService
import com.oea.launcher.gameboost.OeaGameBoostStore
import com.oea.launcher.runtime.OeaHomeController
import com.oea.launcher.runtime.OeaRuntime
import com.oea.launcher.ui.OeaInsets
import com.oea.launcher.ui.OeaScreenFailure

class OeaLauncherActivity : Activity() {
    companion object {
        /** Lets OEA Settings ask Home to open one of its own panels (e.g. "widgets"). */
        const val EXTRA_HOME_ACTION = "oea_home_action"
    }

    private lateinit var home: OeaHomeController
    private var resumedAndFocused = false

    /**
     * True once OEA Home's view tree was created and attached successfully.
     *
     * The failure screen is a *startup* boundary: it replaces the activity's
     * content view, so it may only be used when there is no working Home
     * surface to keep. Once Home has rendered, a later transient error is
     * logged and the running workspace is left alone instead of blanking a
     * launcher the user can still see and use.
     */
    private var surfaceReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            // Edge-to-edge over the real system wallpaper; the workspace pads
            // itself with the system-bar insets and picks the bar icon colour.
            OeaInsets.edgeToEdge(window, lightBars = false)
            OeaRuntime.initialize(application)
            home = OeaHomeController(this)
            setContentView(home.createHomeSurface())
            surfaceReady = true
            if (Build.VERSION.SDK_INT >= 33) {
                onBackInvokedDispatcher.registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                ) { home.handleBack() }
            }
            consumeHomeAction(intent)
        } catch (error: Exception) {
            reportFailure("OEA Home", error)
        }
    }

    /**
     * Shows the last-resort error screen only when Home never came up.
     *
     * A resume, rotation or resize failure on an already-rendered Home must not
     * tear down a working launcher. Those recoverable errors are logged so they
     * stay diagnosable, and the existing workspace keeps the screen.
     */
    private fun reportFailure(screen: String, error: Throwable) {
        if (surfaceReady) {
            android.util.Log.w("OeaLauncherActivity", "$screen hit a recoverable error; keeping the running Home surface", error)
            return
        }
        OeaScreenFailure.show(this, screen, error)
    }

    private fun consumeHomeAction(source: Intent?) {
        val action = source?.getStringExtra(EXTRA_HOME_ACTION) ?: return
        source.removeExtra(EXTRA_HOME_ACTION)
        window.decorView.post {
            if (!::home.isInitialized) return@post
            runCatching { home.performAction(action) }
                .onFailure { reportFailure("OEA Home", it) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Home pressed: close the drawer/folders; when Home was already in
        // front, also glide back to the first page like native launchers.
        if (::home.isInitialized && intent.hasExtra(EXTRA_HOME_ACTION)) {
            consumeHomeAction(intent)
            return
        }
        if (::home.isInitialized && Intent.ACTION_MAIN == intent.action) {
            runCatching { home.onHomeIntent(alreadyOnHome = resumedAndFocused) }
                .onFailure { reportFailure("OEA Home", it) }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val handled = ::home.isInitialized && runCatching { home.handleActivityResult(requestCode, resultCode, data) }
            .onFailure { reportFailure("OEA Home", it) }
            .getOrDefault(false)
        if (handled) return
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onResume() {
        super.onResume()
        if (::home.isInitialized) {
            runCatching { home.refresh() }
                .onFailure { reportFailure("OEA Home", it) }
        }
        resumeGameBoostMonitoring()
    }

    override fun onPause() {
        resumedAndFocused = false
        if (::home.isInitialized) runCatching { home.onPause() }
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        resumedAndFocused = hasFocus
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::home.isInitialized) {
            runCatching { home.onConfigurationChanged(newConfig) }
                .onFailure { reportFailure("OEA Home", it) }
        }
    }

    /**
     * If Android/OEM process management stopped the monitor while its setting
     * remained enabled, resuming OEA Home starts it again without requiring the
     * user to toggle Game Boost off and on manually.
     */
    private fun resumeGameBoostMonitoring() {
        val shouldRun = runCatching { OeaGameBoostStore.enabled(this) && hasUsageAccess() }
            .onFailure { android.util.Log.w("OeaLauncherActivity", "Could not inspect Game Boost state", it) }
            .getOrDefault(false)
        if (!shouldRun) return

        val intent = Intent(this, OeaGameBoostService::class.java)
            .setAction(OeaGameBoostService.ACTION_REFRESH)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
        }.onFailure { error ->
            android.util.Log.w("OeaLauncherActivity", "Could not resume Game Boost monitoring", error)
        }
    }

    private fun hasUsageAccess(): Boolean = runCatching {
        val appOps = getSystemService(AppOpsManager::class.java)
        appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            packageName,
        ) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    @Deprecated("Launcher HOME should not be closed by an accidental Back press.")
    override fun onBackPressed() { if (::home.isInitialized) home.handleBack() else super.onBackPressed() }
}
