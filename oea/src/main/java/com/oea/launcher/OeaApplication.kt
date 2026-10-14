package com.oea.launcher

import android.app.Application
import com.oea.engine.OeaEngine
import com.oea.games.OeaGames
import com.oea.launcher.data.OeaDataStore
import com.oea.launcher.fault.OeaFaultLog
import com.oea.launcher.fault.OeaFaultShield
import com.oea.launcher.games.OeaLauncherIslandHost
import com.oea.launcher.privilege.OeaPrivileges
import com.oea.launcher.runtime.OeaRuntime

/**
 * OEA application owner.
 *
 * Startup order is a stability decision: the fault shield goes first, so every
 * step below it (and every other thread in the process) is contained even when
 * its own initialization fails. Each step is then guarded individually, so a
 * broken game registration, an unreadable preference or a missing icon can
 * never stop Home from starting.
 */
class OeaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val store = runCatching { OeaDataStore.get(this) }.getOrNull()
        OeaFaultShield.install(this)
        OeaFaultLog.persistence = store?.faultLogEnabled() ?: true
        // Registering the Application is cheap; Home's engine and view tree are
        // built lazily by OeaRuntime, so the standalone OEA apps (Games, Phone,
        // Game Boost, Call Blocker, Freezer, Settings) never pay for Home and
        // never inherit a Home initialization problem.
        OeaFaultShield.guard("app:runtime") { OeaRuntime.initialize(this) }
        // Capabilities are read from Android, not cached blindly: a service the
        // user switched on while OEA was in the background must be noticed.
        OeaFaultShield.guard("app:capabilities") { OeaPrivileges.snapshot(this) }
        // OEA Game Engine: register the built-in games and let games publish live
        // activities to the launcher's Dynamic Island. Cheap: no GPU, no network
        // and no game objects are created here.
        OeaFaultShield.guard("app:engine") {
            OeaEngine.init(this)
            OeaEngine.islandHost = OeaLauncherIslandHost(this)
            OeaGames.registerBuiltIns(this)
        }
    }
}
