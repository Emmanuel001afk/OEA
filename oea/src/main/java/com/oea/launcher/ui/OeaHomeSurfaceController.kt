package com.oea.launcher.ui

import android.view.View
import android.view.ViewGroup
import com.oea.launcher.engine.OeaLauncherEngine

/**
 * Hands the single OEA workspace to the current Home activity. The workspace
 * outlives activity recreation, so it is detached from any previous window
 * first; re-adding a view that still has a parent would crash Home.
 */
class OeaHomeSurfaceController {
    fun attach(engine: OeaLauncherEngine): View {
        val surface = engine.workspace
        (surface.parent as? ViewGroup)?.removeView(surface)
        return surface
    }

    fun refresh(engine: OeaLauncherEngine) {
        engine.start()
    }
}
