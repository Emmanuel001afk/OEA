package com.oea.launcher.runtime

import android.content.Context
import android.view.View
import com.oea.launcher.engine.OeaLauncherEngine
import com.oea.launcher.ui.OeaHomeSurfaceController

class OeaHomeController(private val context: Context) {
    private val engine: OeaLauncherEngine get() = OeaRuntime.engine(context)
    private val surfaceController = OeaHomeSurfaceController()
    fun createHomeSurface(): View { engine.start(); return surfaceController.attach(engine) }
    fun refresh() { engine.start(); surfaceController.refresh(engine) }
    fun handleBack(): Boolean = engine.workspace.handleBack()
}
