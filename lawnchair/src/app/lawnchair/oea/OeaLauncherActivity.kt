package app.lawnchair.oea

import android.app.Activity
import android.os.Bundle
import app.lawnchair.oea.runtime.OeaHomeController
import app.lawnchair.oea.runtime.OeaRuntime

/**
 * OEA-owned HOME entry point. Launcher3/Quickstep is not involved in startup,
 * model loading, or workspace creation.
 */
class OeaLauncherActivity : Activity() {
    private lateinit var home: OeaHomeController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OeaRuntime.initialize(application)
        home = OeaHomeController(this)
        setContentView(home.createHomeSurface())
    }

    override fun onResume() {
        super.onResume()
        if (::home.isInitialized) home.refresh()
    }

    @Deprecated("Launcher HOME should not be closed by an accidental Back press.")
    override fun onBackPressed() {
        if (::home.isInitialized) {
            home.handleBack()
        } else {
            super.onBackPressed()
        }
    }
}
