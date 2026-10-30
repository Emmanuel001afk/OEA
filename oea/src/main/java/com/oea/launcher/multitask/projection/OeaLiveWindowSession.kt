package com.oea.launcher.multitask.projection

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import android.view.accessibility.AccessibilityEvent

/**
 * One projected application: virtual display + launch + floating window +
 * input translation + staleness supervision.
 *
 * The session is deliberately pessimistic: it never *claims* to be live.
 * It becomes LIVE only after the target package's window was seen on the
 * virtual display, and it demotes itself to STALE/FAILED when frames stop
 * flowing or the target's window disappears. Every state is visible in the
 * window's title bar so a frozen projection can never masquerade as a
 * working one.
 *
 * Threading model:
 *  - main thread: window attach/detach, badges, status line;
 *  - one dedicated handler thread ("oea-projection-<pkg>"): node actions
 *    (binder calls), launch ladder, frame polling, watchdog. Node objects
 *    are only touched there.
 */
class OeaLiveWindowSession(
    private val service: AccessibilityService,
    val packageName: String,
    val activityName: String,
    val appLabel: String,
    private val startX: Int,
    private val startY: Int
) : OeaLiveWindowOverlay.Callbacks {

    companion object {
        /** A projection is stale when no frame arrived for this long. */
        private const val STALE_AFTER_MS = 3_500L

        /** Watchdog period. */
        private const val WATCHDOG_MS = 2_000L

        /** How long to wait for texture frames before falling back to the
         *  ImageReader presenter. */
        private const val TEXTURE_FIRST_FRAME_MS = 2_500L

        /** Reader-mode poll period. */
        private const val READER_POLL_MS = 120L
    }

    // ---------------------------------------------------------------- state

    @Volatile var state: OeaProjectionState = OeaProjectionState.STARTING
        private set
    @Volatile var minimised: Boolean = false
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("oea-projection-$packageName").apply { start() }
    private val handler = Handler(thread.looper)

    private var host: OeaVirtualDisplayHost? = null
    private var overlay: OeaLiveWindowOverlay? = null
    private var controller = OeaProjectionController { OeaLiveWindowManager.accessibilityService() }

    @Volatile private var released = false
    @Volatile private var frameMode = OeaFrameMode.NONE
    @Volatile private var lastEventUptime = 0L
    @Volatile private var lastActionReport = "Starting…"
    private var textureSurface: Surface? = null
    private var launchEvidence: List<OeaEvidenceLine> = emptyList()
    private var startedAt = SystemClock.uptimeMillis()

    // ---------------------------------------------------------------- start

    /** Async start: creates everything and reports through the overlay. */
    fun start() {
        handler.post { startInternal() }
    }

    private fun startInternal() {
        // 1) Virtual display first: the window's touch mapping and aspect
        //    depend on the real display geometry the OEM granted.
        val displayHost = when (val r = OeaVirtualDisplayHost.create(service, "OEA Live Window")) {
            is OeaVirtualDisplayHost.CreateResult.Created -> r.host
            is OeaVirtualDisplayHost.CreateResult.Failed -> {
                fail(
                    "This phone refused to create the private display OEA needs. ${r.reason}",
                    r.attempts.mapIndexed { i, a -> OeaEvidenceLine("Virtual display attempt ${i + 1}", a) }
                )
                return
            }
        }
        host = displayHost

        // 2) Floating window: the user sees progress immediately.
        runOnMain {
            if (released) return@runOnMain
            val icon = appIconSafe()
            val o = OeaLiveWindowOverlay(
                service, appLabel, icon,
                displayHost.width, displayHost.height,
                this@OeaLiveWindowSession
            )
            o.onSurfaceTextureReady = { st -> handler.post { bindTexture(st) } }
            o.onFramePulse = { onFrameRendered() }
            try {
                o.attach(startX, startY)
                overlay = o
                o.setStatusBadge("STARTING", 0xFF9CCEFF.toInt())
                o.setStatusLine("Starting $appLabel on a private display…")
            } catch (t: Throwable) {
                fail("The floating window could not be opened: ${t.message ?: t.javaClass.simpleName}")
            }
        }

        // 3) Launch ladder on this (background) thread.
        val result = OeaProjectionLaunch.launch(
            service, displayHost, packageName, activityName, appLabel
        )
        launchEvidence = result.evidence
        if (!result.success) {
            fail(result.headline, result.evidence)
            return
        }

        // 4) Capability probe: is there anything to interact with?
        val probe = controller.probe(displayHost.displayId, packageName)
        val viewOnly = !probe.nodeControlAvailable || !controller.displayAware()

        // 5) Frame pipeline: prefer the zero-copy TextureView; the watchdog
        //    falls back to ImageReader polling if no frame arrives.
        runOnMain {
            overlay?.setStatusBadge(
                if (viewOnly) "VIEW-ONLY" else "LIVE",
                if (viewOnly) 0xFFFFB86B.toInt() else 0xFF7AE7C7.toInt()
            )
            overlay?.setStatusLine(
                if (viewOnly)
                    "The app is displayed, but it exposes no interactive controls. Taps will not work here."
                else
                    "Tap to interact. The phone screen stays as it is."
            )
        }
        state = if (viewOnly) OeaProjectionState.VIEW_ONLY else OeaProjectionState.LIVE
        OeaLiveWindowManager.notifyStateChanged()

        startWatchdog()
    }

    private fun bindTexture(st: SurfaceTexture) {
        if (released) return
        val displayHost = host ?: return
        st.setDefaultBufferSize(displayHost.width, displayHost.height)
        val surface = Surface(st)
        val swapped = displayHost.swapSurface(surface)
        if (swapped) {
            textureSurface = surface
            frameMode = OeaFrameMode.TEXTURE
            runOnMain { overlay?.setPresenterMode(OeaFrameMode.TEXTURE) }
            // If the TextureView path yields no frame quickly (some OEMs
            // refuse hardware-accelerated overlay windows), fall back.
            handler.postDelayed({
                if (!released && frameMode == OeaFrameMode.TEXTURE && displayHost.lastFrameUptimeMs == 0L) {
                    switchToReader()
                }
            }, TEXTURE_FIRST_FRAME_MS)
        } else {
            surface.release()
            switchToReader()
        }
    }

    private fun switchToReader() {
        val displayHost = host ?: return
        textureSurface?.release()
        textureSurface = null
        val ok = displayHost.swapSurface(displayHost.surface())
        frameMode = if (ok) OeaFrameMode.READER else OeaFrameMode.NONE
        runOnMain {
            overlay?.setPresenterMode(frameMode)
            if (frameMode == OeaFrameMode.NONE) {
                overlay?.setStatusLine("The private display works, but its frames cannot be shown in this window.")
            }
        }
        if (ok) {
            handler.post(object : Runnable {
                override fun run() {
                    if (released || frameMode != OeaFrameMode.READER) return
                    val bitmap = displayHost.acquireLatestBitmap()
                    if (bitmap != null) {
                        displayHost.noteFrame()
                        runOnMain { overlay?.showFrame(bitmap) }
                    }
                    handler.postDelayed(this, READER_POLL_MS)
                }
            })
        }
    }

    // ---------------------------------------------------------------- frames/staleness

    /** Called by the manager when the TextureView renders a new frame. */
    fun onFrameRendered() {
        host?.noteFrame()
    }

    /** Called by the manager for every accessibility event. */
    fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) lastEventUptime = SystemClock.uptimeMillis()
    }

    private fun startWatchdog() {
        handler.post(object : Runnable {
            override fun run() {
                if (released || state == OeaProjectionState.STOPPED || state == OeaProjectionState.FAILED) return
                val displayHost = host
                if (displayHost == null) {
                    handler.postDelayed(this, WATCHDOG_MS)
                    return
                }
                val stillThere = controller.packageOnDisplay(displayHost.displayId, packageName)
                val now = SystemClock.uptimeMillis()
                val lastFrame = displayHost.lastFrameUptimeMs
                val fresh = lastFrame > 0 && now - lastFrame < STALE_AFTER_MS ||
                        (lastFrame == 0L && now - startedAt < STALE_AFTER_MS)
                runOnMain {
                    if (!stillThere) {
                        demote(
                            "$appLabel is no longer on the private display (it was closed or moved on its own). " +
                                "This window is no longer live."
                        )
                    } else {
                        when {
                            state == OeaProjectionState.VIEW_ONLY -> Unit
                            !fresh -> overlay?.setStatusBadge("STALE", 0xFFFFB86B.toInt())
                            else -> overlay?.setStatusBadge("LIVE", 0xFF7AE7C7.toInt())
                        }
                    }
                }
                handler.postDelayed(this, WATCHDOG_MS)
            }
        })
    }

    private fun demote(message: String) {
        if (state == OeaProjectionState.STOPPED || state == OeaProjectionState.FAILED) return
        state = OeaProjectionState.FAILED
        overlay?.setStatusBadge("ENDED", 0xFFFF7A7A.toInt())
        overlay?.setStatusLine(message)
        OeaLiveWindowManager.notifyStateChanged()
    }

    // ---------------------------------------------------------------- input

    // All node actions hop to the single projection thread.

    override fun onTap(x: Int, y: Int) {
        runNodeAction("Tap") { controller.clickAt(displayIdSafe(), x, y) }
    }

    override fun onLongPress(x: Int, y: Int) {
        runNodeAction("Long press") { controller.longPressAt(displayIdSafe(), x, y) }
    }

    override fun onScroll(x: Int, y: Int, forward: Boolean) {
        runNodeAction("Scroll") { controller.scroll(displayIdSafe(), x, y, forward) }
    }

    override fun onSetText(text: String) {
        runNodeAction("Type") { controller.setText(displayIdSafe(), text, packageName) }
    }

    override fun onPaste() {
        runNodeAction("Paste") { controller.pasteClipboard(displayIdSafe(), packageName) }
    }

    override fun onBack() {
        runNodeAction("Back") { controller.backInApp(displayIdSafe(), packageName) }
    }

    override fun onTextStripFocus(want: Boolean) {
        if (!want) runOnMain { overlay?.openTextStrip(false) }
    }

    override fun onSurfaceLost() {
        // Presenter surface gone: fall back to the ImageReader so the
        // projection keeps rendering and staleness stays measurable.
        handler.post { if (!released && frameMode == OeaFrameMode.TEXTURE) switchToReader() }
    }

    override fun onClose() {
        // Through the manager so the session list, the MAX_WINDOWS count and
        // the workspace rows all stay truthful.
        OeaLiveWindowManager.stop(this@OeaLiveWindowSession)
    }

    override fun onMinimise() {
        minimise()
    }

    /** Hides the window but keeps the projection running. */
    fun minimise() {
        minimised = true
        runOnMain { overlay?.setVisible(false) }
        OeaLiveWindowManager.notifyStateChanged()
    }

    /** Re-shows a minimised window. */
    fun restore() {
        minimised = false
        runOnMain { overlay?.setVisible(true) }
        OeaLiveWindowManager.notifyStateChanged()
    }

    private fun runNodeAction(label: String, action: () -> OeaNodeOutcome) {
        handler.post {
            if (released) return@post
            if (state == OeaProjectionState.FAILED) {
                reportAction("This window is no longer live.")
                return@post
            }
            val outcome = try {
                action()
            } catch (t: Throwable) {
                OeaNodeOutcome.Failed(t.message ?: t.javaClass.simpleName)
            }
            when (outcome) {
                is OeaNodeOutcome.Ok -> reportAction("$label: ${outcome.detail}")
                is OeaNodeOutcome.Rejected -> reportAction("$label not possible: ${outcome.reason}")
                is OeaNodeOutcome.Failed -> reportAction("$label failed: ${outcome.reason}")
            }
        }
    }

    private fun reportAction(text: String) {
        lastActionReport = text
        runOnMain { overlay?.setStatusLine(text) }
    }

    private fun displayIdSafe(): Int = host?.displayId ?: android.view.Display.DEFAULT_DISPLAY

    // ---------------------------------------------------------------- close

    fun close() {
        if (released) return
        released = true
        state = OeaProjectionState.STOPPED
        thread.quitSafely()
        // Release on main: surface teardown must not race view drawing.
        runOnMain {
            val displayHost = host
            host = null
            try {
                overlay?.detach()
            } catch (_: Throwable) {
            }
            overlay = null
            OeaProjectionTrampolineActivity.finishLiving()
            displayHost?.release()
            OeaLiveWindowManager.notifyStateChanged()
        }
    }

    // ---------------------------------------------------------------- misc

    private fun fail(message: String, extraEvidence: List<OeaEvidenceLine> = emptyList()) {
        if (released) return
        state = OeaProjectionState.FAILED
        launchEvidence = launchEvidence + extraEvidence +
                OeaEvidenceLine("Result", message)
        runOnMain {
            overlay?.setStatusBadge("FAILED", 0xFFFF7A7A.toInt())
            overlay?.setStatusLine(message)
        }
        OeaLiveWindowManager.notifyStateChanged()
    }

    private fun appIconSafe(): android.graphics.drawable.Drawable? = try {
        service.packageManager.getApplicationIcon(packageName)
    } catch (_: Throwable) {
        null
    }

    /** Evidence log for diagnostics. */
    fun evidence(): List<OeaEvidenceLine> = launchEvidence

    /** Snapshot for settings/status surfaces. */
    fun describe(): String = when (state) {
        OeaProjectionState.STARTING -> "$appLabel: starting…"
        OeaProjectionState.LIVE -> "$appLabel: live${if (minimised) " (hidden)" else ""}"
        OeaProjectionState.VIEW_ONLY -> "$appLabel: view-only${if (minimised) " (hidden)" else ""}"
        OeaProjectionState.FAILED -> "$appLabel: failed — ${launchEvidence.lastOrNull()?.outcome ?: "unknown"}"
        OeaProjectionState.STOPPED -> "$appLabel: stopped"
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }
}
