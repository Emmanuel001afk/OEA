package com.oea.launcher.runtime

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.view.View
import com.oea.launcher.engine.OeaLauncherEngine
import com.oea.launcher.ui.OeaHomeSurfaceController

/**
 * The thin boundary between Android's Home window and OEA's engine.
 *
 * It owns nothing: the engine and the workspace are process-scoped, so the
 * controller can be created and thrown away with each Activity while Home keeps
 * its layout, its app catalogue and its running services. Every method here is
 * expected to be called inside a fault guard by the activity.
 */
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

    /** Rebuilds the running surface after a fault; true when Home came back cleanly. */
    fun recoverSurface(): Boolean {
        engine.start()
        engine.workspace.attachHost(context as? Activity)
        return engine.workspace.recoverSurface()
    }

    fun onTrimMemory(level: Int) = engine.workspace.onHostTrimMemory(level)

    /**
     * Home's window is gone. The workspace stays alive (it is reused by the next
     * Home instance) but must not keep this Activity or its widget host.
     */
    fun onDestroy() {
        engine.workspace.detachHost()
    }
}
