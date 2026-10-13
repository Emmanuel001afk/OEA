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

class OeaLauncherActivity : Activity() {
    companion object {
        /** Lets OEA Settings ask Home to open one of its own panels (e.g. "widgets"). */
        const val EXTRA_HOME_ACTION = "oea_home_action"
    }

    private lateinit var home: OeaHomeController
    private var resumedAndFocused = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge over the real system wallpaper; the workspace pads
        // itself with the system-bar insets and picks the bar icon colour.
        OeaInsets.edgeToEdge(window, lightBars = false)
        OeaRuntime.initialize(application)
        home = OeaHomeController(this)
        setContentView(home.createHomeSurface())
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            ) { home.handleBack() }
        }
        consumeHomeAction(intent)
    }

    private fun consumeHomeAction(source: Intent?) {
        val action = source?.getStringExtra(EXTRA_HOME_ACTION) ?: return
        source.removeExtra(EXTRA_HOME_ACTION)
        window.decorView.post { home.performAction(action) }
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
            home.onHomeIntent(alreadyOnHome = resumedAndFocused)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (home.handleActivityResult(requestCode, resultCode, data)) return
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onResume() {
        super.onResume()
        if (::home.isInitialized) home.refresh()
        resumeGameBoostMonitoring()
    }

    override fun onPause() {
        resumedAndFocused = false
        if (::home.isInitialized) home.onPause()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        resumedAndFocused = hasFocus
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::home.isInitialized) home.onConfigurationChanged(newConfig)
    }

    /**
     * If Android/OEM process management stopped the monitor while its setting
     * remained enabled, resuming OEA Home starts it again without requiring the
     * user to toggle Game Boost off and on manually.
     */
    private fun resumeGameBoostMonitoring() {
        if (!OeaGameBoostStore.enabled(this) || !hasUsageAccess()) return
        val intent = Intent(this, OeaGameBoostService::class.java)
            .setAction(OeaGameBoostService.ACTION_REFRESH)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
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
