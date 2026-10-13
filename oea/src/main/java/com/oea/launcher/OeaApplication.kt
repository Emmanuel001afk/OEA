package com.oea.launcher

import android.app.Application
import com.oea.engine.OeaEngine
import com.oea.games.OeaGames
import com.oea.launcher.games.OeaLauncherIslandHost
import com.oea.launcher.runtime.OeaRuntime

/** OEA application owner. */
class OeaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        OeaRuntime.initialize(this)
        // OEA Game Engine: register the built-in games and let games publish
        // live activities to the launcher's Dynamic Island (card and/or camera style).
        // Cheap: no GPU, network or game objects are created here.
        OeaEngine.init(this)
        OeaEngine.islandHost = OeaLauncherIslandHost(this)
        OeaGames.registerBuiltIns(this)
    }
}
