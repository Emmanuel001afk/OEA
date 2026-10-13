package com.oea.launcher.runtime

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.view.View
import com.oea.launcher.engine.OeaLauncherEngine
import com.oea.launcher.ui.OeaHomeSurfaceController

class OeaHomeController(private val context: Context) {
    private val engine: OeaLauncherEngine get() = OeaRuntime.engine(context)
    private val surfaceController = OeaHomeSurfaceController()

    fun createHomeSurface(): View {
        engine.start()
        engine.workspace.attachHost(context as? Activity)
        return surfaceController.attach(engine)
    }

    fun refresh() {
        engine.start()
        engine.workspace.attachHost(context as? Activity)
        engine.workspace.onHostResumed()
    }

    fun onPause() = engine.workspace.onHostPaused()
    fun onHomeIntent(alreadyOnHome: Boolean) = engine.workspace.onHomeIntent(alreadyOnHome)
    fun onConfigurationChanged(config: Configuration) = engine.workspace.onHostConfigurationChanged(config)
    fun handleActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?): Boolean =
        engine.workspace.handleActivityResult(requestCode, resultCode, data)
    fun handleBack(): Boolean = engine.workspace.handleBack()
    fun performAction(action: String) = engine.workspace.performHomeAction(action)
}
