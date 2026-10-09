package com.oea.launcher

import android.app.Activity
import android.app.AppOpsManager
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.os.Process
import com.oea.launcher.runtime.OeaHomeController
import com.oea.launcher.runtime.OeaRuntime
import com.oea.launcher.gameboost.OeaGameBoostService
import com.oea.launcher.gameboost.OeaGameBoostStore

class OeaLauncherActivity : Activity() {
    private lateinit var home: OeaHomeController
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OeaRuntime.initialize(application)
        home = OeaHomeController(this)
        setContentView(home.createHomeSurface())
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            ) { home.handleBack() }
        }
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        if (home.handleActivityResult(requestCode, resultCode, data)) return
        super.onActivityResult(requestCode, resultCode, data)
    }
    override fun onResume() {
        super.onResume()
        if (::home.isInitialized) home.refresh()
        resumeGameBoostMonitoring()
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
