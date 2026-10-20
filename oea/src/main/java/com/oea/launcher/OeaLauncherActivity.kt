package com.oea.launcher

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import com.oea.launcher.fault.OeaFaultShield
import com.oea.launcher.gameboost.OeaGameBoost
import com.oea.launcher.privilege.OeaPrivileges
import com.oea.launcher.runtime.OeaHomeController
import com.oea.launcher.runtime.OeaRuntime
import com.oea.launcher.ui.OeaInsets
import com.oea.launcher.ui.OeaScreenFailure
import com.oea.launcher.workspace.OeaDynamicIslandOverlayService

/**
 * OEA Home's window. Its only real jobs are to hand the workspace to Android,
 * to keep every platform callback from hurting the launcher, and to recover in
 * place when something inside Home fails.
 *
 * Stability contract for this activity:
 * - every lifecycle callback runs inside [OeaFaultShield.guard], so a thrown
 *   fault is recorded and Home is rebuilt or kept — never a force-close;
 * - [OeaScreenFailure] is only allowed while Home has never rendered;
 * - Home installs a [OeaFaultShield.Recovery] so a fault anywhere in the process
 *   (game, widget provider, island, service) can rebuild or protect the running
 *   surface instead of ending the launcher.
 */
class OeaLauncherActivity : Activity() {
    companion object {
        /** Lets OEA Settings ask Home to open one of its own panels (e.g. "widgets"). */
        const val EXTRA_HOME_ACTION = "oea_home_action"
    }

    private var home: OeaHomeController? = null
    private var resumedAndFocused = false

    /**
     * True once OEA Home's view tree was created and attached successfully.
     *
     * The failure screen is a *startup* boundary: it replaces the activity's
     * content view, so it may only be used when there is no working Home
     * surface to keep. Once Home has rendered, a later transient error is
     * logged and the running workspace is left alone instead of blanking a
     * launcher the user can still see and use.
     */
    private var surfaceReady = false

    /** Re-entrant guard: a fault during recovery must not start another recovery. */
    private var recovering = false

    private val recovery = object : OeaFaultShield.Recovery {
        override fun rebuildHome(): Boolean = recover("rebuild")

        override fun closeFrontScreen(): Boolean {
            // The fault belonged to another OEA screen; Home stays exactly as it is.
            return false
        }

        override fun enterSafeMode(): Boolean = recover("safe-mode")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OeaFaultShield.guard("home:create") { buildHome() }
    }

    private fun buildHome() {
        try {
            // Edge-to-edge over the real system wallpaper; the workspace pads
            // itself with the system-bar insets and picks the bar icon colour.
            OeaInsets.edgeToEdge(window, lightBars = false)
            OeaRuntime.initialize(application)
            val controller = OeaHomeController(this)
            home = controller
            setContentView(controller.createHomeSurface())
            surfaceReady = true
            OeaFaultShield.noteHomeRendered()
            if (Build.VERSION.SDK_INT >= 33) {
                onBackInvokedDispatcher.registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT
) { home?.handleBack() }
            }
            consumeHomeAction(intent)
        } catch (error: Throwable) {
            reportFailure("OEA Home", error)
        }
    }

    /**
     * Shows the last-resort error screen only when Home never came up.
     *
     * A resume, rotation or resize failure on an already-rendered Home must not
     * tear down a working launcher. Those recoverable errors are logged so they
     * stay diagnosable, and the existing workspace keeps the screen.
     */
    private fun reportFailure(screen: String, error: Throwable) {
        android.util.Log.e("OeaLauncherActivity", "$screen failed; attempting launcher recovery", error)

        // A previously attached surface is not proof that it is still usable:
        // a drawer/workspace can be left empty after a failed refresh. Try the
        // saved workspace first, then retry with optional subsystems disabled.
        if (surfaceReady) {
            val rebuilt = runCatching { recover("failure:$screen") }.getOrDefault(false)
            if (rebuilt) return
            OeaFaultShield.setSafeMode(true, application)
            val safe = runCatching { recover("failure-safe-mode:$screen") }.getOrDefault(false)
            if (safe) return
        }

        // Do not leave the user on a dead transparent/black Home surface. This
        // screen offers a retry path while preserving the saved layout/settings.
        OeaScreenFailure.show(
            this,
            screen,
            error,
            null,
            "Retry Home",
            {
                OeaFaultShield.setSafeMode(true, application)
                home = null
                surfaceReady = false
                buildHome()
            }
        )
    }

    /** Rebuilds Home from the saved workspace; true when the surface is usable again. */
    private fun recover(cause: String): Boolean {
        if (recovering) return true
        recovering = true
        val ok = try {
            val controller = home ?: return false
            OeaFaultShield.value("home:recover:$cause", false) {
                controller.recoverSurface()
            }
        } finally {
            recovering = false
        }
        if (ok) OeaFaultShield.noteHomeRendered()
        else OeaFaultShield.noteHomeUnhealthy()
        return ok
    }

    private fun consumeHomeAction(source: Intent?) {
        val action = source?.getStringExtra(EXTRA_HOME_ACTION) ?: return
        source.removeExtra(EXTRA_HOME_ACTION)
        window.decorView.post {
            val controller = home ?: return@post
            OeaFaultShield.guard("home:action") { controller.performAction(action) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val controller = home ?: return
        // Home pressed: close the drawer/folders; when Home was already in
        // front, also glide back to the first page like native launchers.
        if (intent.hasExtra(EXTRA_HOME_ACTION)) {
            OeaFaultShield.guard("home:action") { consumeHomeAction(intent) }
            return
        }
        if (Intent.ACTION_MAIN == intent.action) {
            OeaFaultShield.guard("home:home-intent") { controller.onHomeIntent(alreadyOnHome = resumedAndFocused) }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val handled = home != null && OeaFaultShield.value(
            "home:activity-result",
            false
) { home?.handleActivityResult(requestCode, resultCode, data) == true }
        if (handled) return
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onResume() {
        super.onResume()
        OeaFaultShield.setRecovery(recovery)
        OeaPrivileges.startObserving(this)
        val controller = home
        if (controller != null) {
            OeaFaultShield.guard("home:resume") { controller.refresh() }
        } else {
            buildHome()
        }
        OeaFaultShield.guard("home:boost-resume") { OeaGameBoost.onHomeResumed() }
        OeaFaultShield.guard("home:island-resume") { OeaDynamicIslandOverlayService.ensureRunning(this) }
        OeaFaultShield.guard("home:wake-resume") {
            com.oea.launcher.power.OeaPower.restoreIfNeeded(this)
            com.oea.launcher.power.OeaPower.syncWakeService(this)
        }
    }

    override fun onStop() {
        super.onStop()
        OeaFaultShield.guard("home:boost-stop") { OeaGameBoost.onHomeStopped(this) }
    }

    override fun onPause() {
        resumedAndFocused = false
        OeaFaultShield.guard("home:pause") { home?.onPause() }
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        resumedAndFocused = hasFocus
        if (hasFocus && !surfaceReady) buildHome()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val controller = home ?: return
        OeaFaultShield.guard("home:configuration") { controller.onConfigurationChanged(newConfig) }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Memory pressure is the one signal that a launcher-wide problem is
        // coming; dropping caches early avoids the OutOfMemoryError instead of
        // reacting to it.
        val controller = home ?: return
        OeaFaultShield.guard("home:trim") { controller.onTrimMemory(level) }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        OeaFaultShield.guard("home:low-memory") { home?.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE) }
    }

    override fun onDestroy() {
        OeaPrivileges.stopObserving(this)
        OeaFaultShield.setRecovery(null)
        OeaFaultShield.guard("home:destroy") { home?.onDestroy() }
        home = null
        super.onDestroy()
    }

    @Deprecated("Launcher HOME should not be closed by an accidental Back press.")
    override fun onBackPressed() {
        val controller = home ?: run {
            @Suppress("DEPRECATION")
            super.onBackPressed()
            return
        }
        OeaFaultShield.guard("home:back") { controller.handleBack() }
    }
}
