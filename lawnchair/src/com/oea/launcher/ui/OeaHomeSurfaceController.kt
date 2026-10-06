package com.oea.launcher.ui

import android.view.View
import com.oea.launcher.engine.OeaLauncherEngine

/**
 * OEA-owned home surface. The engine-owned workspace is the single displayed
 * workspace instance, so refreshes update the visible HOME rather than an
 * off-screen replacement view.
 */
class OeaHomeSurfaceController {
    fun attach(engine: OeaLauncherEngine): View = engine.workspace

    fun refresh(engine: OeaLauncherEngine) {
        engine.start()
    }
}
