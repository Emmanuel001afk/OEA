package com.oea.launcher

import android.app.Activity
import android.os.Bundle
import android.os.Build
import com.oea.launcher.runtime.OeaHomeController
import com.oea.launcher.runtime.OeaRuntime

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
    override fun onResume() { super.onResume(); if (::home.isInitialized) home.refresh() }
    @Deprecated("Launcher HOME should not be closed by an accidental Back press.")
    override fun onBackPressed() { if (::home.isInitialized) home.handleBack() else super.onBackPressed() }
}
