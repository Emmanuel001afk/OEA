package com.oea.launcher

import android.app.Activity
import android.os.Bundle
import com.oea.launcher.runtime.OeaHomeController
import com.oea.launcher.runtime.OeaRuntime

class OeaLauncherActivity : Activity() {
    private lateinit var home: OeaHomeController
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OeaRuntime.initialize(application)
        home = OeaHomeController(this)
        setContentView(home.createHomeSurface())
    }
    override fun onResume() { super.onResume(); if (::home.isInitialized) home.refresh() }
    @Deprecated("Launcher HOME should not be closed by an accidental Back press.")
    override fun onBackPressed() { if (::home.isInitialized) home.handleBack() else super.onBackPressed() }
}
