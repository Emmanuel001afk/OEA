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

        /**
         * How long a freshly launched app may take to put one real picture on
         * the private display before the window stops claiming it is live.
         */
        private const val FIRST_FRAME_MS = 6_000L

        /** Polling period while waiting for that first frame. */
        private const val FIRST_FRAME_POLL_MS = 100L
    }

    // ---------------------------------------------------------------- state

    @Volatile var state: OeaProjectionState = OeaProjectionState.STARTING
        private set
    @Volatile var minimised: Boolean = false
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("oea-projection-$packageName").apply { start() }
    private val handler = Handler(thread.looper)

    /**
     * Frames get their own thread.
     *
     * The projection thread blocks for the whole launch ladder — occupancies,
     * trampoline routes, task moves — which is up to a minute of waiting. Any
     * frame work posted there (binding the presenter, draining the reader)
     * simply did not run until the launch had finished, so the window sat
     * empty for the entire start and the display's buffer queue went
     * undrained. Nothing about frames may depend on the launch being over.
     */
    private val frameThread = HandlerThread("oea-projection-frames-$packageName").apply { start() }
    private val frameHandler = Handler(frameThread.looper)

    /** Guards the presenter swap: the main thread and the frame thread meet here. */
    private val frameLock = Any()

    private var host: OeaVirtualDisplayHost? = null
    private var overlay: OeaLiveWindowOverlay? = null
    private var controller = OeaProjectionController { OeaLiveWindowManager.accessibilityService() }

    @Volatile private var released = false
    @Volatile private var frameMode = OeaFrameMode.NONE
    @Volatile private var lastEventUptime = 0L
    @Volatile private var lastActionReport = "Starting…"
    @Volatile private var readerRunning = false
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

        // 2) Floating window AND the pixel pipeline, both live before the
        //    launch starts. The ladder below blocks this thread for as long
        //    as it takes, so nothing that draws may wait for it.
        runOnMain {
            if (released) return@runOnMain
            val icon = appIconSafe()
            val o = OeaLiveWindowOverlay(
                service, appLabel, icon,
                displayHost.width, displayHost.height,
                this@OeaLiveWindowSession
            )
            // Bind the presenter on the calling thread: posting it to the
            // projection thread used to queue it behind the whole launch.
            o.onSurfaceTextureReady = { st -> bindTexture(st) }
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
        // The display already has somewhere to render (the reader it was
        // created with). Start draining it now, so frames flow from the first
        // second and the display's buffer queue never stalls.
        startReaderDrain()

        // 3) Launch ladder on this (background) thread.
        val result = OeaProjectionLaunch.launch(
            service, displayHost, packageName, activityName, appLabel
        )
        launchEvidence = result.evidence
        if (!result.success) {
            fail(result.headline, result.evidence)
            return
        }

        // 4) A picture, not just a promise.
        //
        // The ladder reporting success means the target's window was seen on
        // the private display. It does not mean any pixel of it reached this
        // window, and a black rectangle wearing a green LIVE badge is the
        // single worst thing this feature can do. Wait for frames that
        // arrived *after* the target landed, so the check is about the app
        // and not about the empty display behind it.
        val framesAtLaunchEnd = displayHost.framesReceived
        val t0 = SystemClock.uptimeMillis()
        val framed = awaitNewFrameSince(framesAtLaunchEnd, FIRST_FRAME_MS)
        if (!framed) {
            fail(
                "$appLabel is on the private display, but no picture of it reached this " +
                    "window within ${FIRST_FRAME_MS / 1000}s. The window is not being shown " +
                    "as live, because it is not.",
                listOf(
                    OeaEvidenceLine(
                        "New frames after the app landed",
                        "none — ${displayHost.framesReceived} frames total, none since the launch finished",
                        SystemClock.uptimeMillis() - t0
                    )
                )
            )
            return
        }
        launchEvidence = launchEvidence + OeaEvidenceLine(
            "New frames after the app landed",
            "yes — ${displayHost.framesReceived - framesAtLaunchEnd} new frames; the app is being drawn here",
            SystemClock.uptimeMillis() - t0
        )

        // 5) Capability probe: is there anything to interact with?
        val probe = controller.probe(displayHost.displayId, packageName)
        val viewOnly = !probe.nodeControlAvailable || !controller.displayAware()

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

    /** True once a frame newer than [framesAtStart] has reached the presenter. */
    private fun awaitNewFrameSince(framesAtStart: Long, budgetMs: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + budgetMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (released) return false
            if ((host?.framesReceived ?: 0L) > framesAtStart) return true
            try {
                Thread.sleep(FIRST_FRAME_POLL_MS)
            } catch (_: Throwable) {
                return (host?.framesReceived ?: 0L) > framesAtStart
            }
        }
        return (host?.framesReceived ?: 0L) > framesAtStart
    }

    /**
     * Points the virtual display at the TextureView's SurfaceTexture for
     * zero-copy frames.
     *
     * Runs on the calling thread — the main thread, straight out of the
     * SurfaceTexture callback — and returns immediately. It used to be posted
     * to the projection thread, which is blocked inside the launch ladder for
     * the whole start, so the display stayed pointed at the reader and the
     * window stayed empty for as long as the launch took.
     */
    private fun bindTexture(st: SurfaceTexture) {
        val displayHost = host ?: return
        synchronized(frameLock) {
            if (released) return
            st.setDefaultBufferSize(displayHost.width, displayHost.height)
            val surface = Surface(st)
            if (displayHost.swapSurface(surface)) {
                textureSurface?.release()
                textureSurface = surface
                frameMode = OeaFrameMode.TEXTURE
            } else {
                surface.release()
                switchToReaderLocked()
            }
        }
        val mode = frameMode
        runOnMain {
            overlay?.setPresenterMode(mode)
            if (mode == OeaFrameMode.NONE) {
                overlay?.setStatusLine("The private display works, but its frames cannot be shown in this window.")
            }
        }
        // If the TextureView never renders (some OEMs refuse hardware
        // -accelerated overlay windows), go back to the reader instead of
        // showing nothing at all.
        frameHandler.postDelayed({
            if (!released && frameMode == OeaFrameMode.TEXTURE && displayHost.lastFrameUptimeMs == 0L) {
                switchToReader()
            }
        }, TEXTURE_FIRST_FRAME_MS)
    }

    /**
     * Starts pumping frames out of the reader.
     *
     * The reader is the surface the display was created with, so it is
     * always available and never needs a swap. That makes it both the
     * starting presenter and the fallback — and it means the display has a
     * consumer from the first second, rather than filling its three buffer
     * slots and stalling while OEA waits for a launch.
     */
    private fun startReaderDrain() {
        synchronized(frameLock) {
            if (released) return
            val displayHost = host ?: return
            if (displayHost.swapSurface(displayHost.surface())) frameMode = OeaFrameMode.READER
        }
        runOnMain { overlay?.setPresenterMode(frameMode) }
        postReaderPoll()
    }

    private fun switchToReader() {
        synchronized(frameLock) { switchToReaderLocked() }
        val mode = frameMode
        runOnMain {
            overlay?.setPresenterMode(mode)
            if (mode == OeaFrameMode.NONE) {
                overlay?.setStatusLine("The private display works, but its frames cannot be shown in this window.")
            }
        }
        postReaderPoll()
    }

    /** Call with [frameLock] held. */
    private fun switchToReaderLocked() {
        val displayHost = host ?: return
        textureSurface?.release()
        textureSurface = null
        frameMode = if (displayHost.swapSurface(displayHost.surface())) OeaFrameMode.READER else OeaFrameMode.NONE
    }

    private fun postReaderPoll() {
        synchronized(frameLock) {
            if (released || readerRunning || frameMode != OeaFrameMode.READER) return
            readerRunning = true
        }
        frameHandler.post(readerPoll)
    }

    private val readerPoll = object : Runnable {
        override fun run() {
            if (released) {
                synchronized(frameLock) { readerRunning = false }
                return
            }
            synchronized(frameLock) {
                val displayHost = host
                // The TextureView took over: one consumer owns the buffers.
                if (frameMode != OeaFrameMode.READER || displayHost == null) {
                    readerRunning = false
                    return
                }
                val bitmap = displayHost.acquireLatestBitmap()
                if (bitmap != null) {
                    displayHost.noteFrame()
                    runOnMain { overlay?.showFrame(bitmap) }
                }
            }
            frameHandler.postDelayed(this, READER_POLL_MS)
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
        if (!released && frameMode == OeaFrameMode.TEXTURE) switchToReader()
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
        frameThread.quitSafely()
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
