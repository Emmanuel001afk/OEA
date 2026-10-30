package com.oea.launcher.multitask.projection

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityEvent

/**
 * Owns every live app projection (up to [MAX_WINDOWS] at once) and connects
 * them to the OEA Floating Window accessibility service.
 *
 * The manager deliberately does not know about the service class it lives
 * in: the service pushes events and lifecycle calls in, and reads state out.
 * That keeps the projection package free of launcher dependencies, which
 * keeps the whole feature reviewable and removable.
 *
 * While any session is running, the manager widens the accessibility
 * service's event mask (window-state, windows-changed, and the target
 * packages' content-changed events). This is the same runtime-widening
 * pattern OEA's App Freezer already uses, and it is narrowed again as soon
 * as the last session closes, so idle OEA behaviour is unchanged.
 */
object OeaLiveWindowManager {

    /** The intended end state: three simultaneous floating app windows. */
    const val MAX_WINDOWS = 3

    @Volatile private var serviceRef: AccessibilityService? = null
    private val sessions = ArrayList<OeaLiveWindowSession>()
    private var lastLaunchFailedAt = 0L

    /** Notified whenever a session is added, changes state, or is removed. */
    @Volatile var onStateChanged: (() -> Unit)? = null

    // ---------------------------------------------------------------- service

    /** Called by OeaFloatingWindowService.onServiceConnected. */
    fun attach(service: AccessibilityService) {
        serviceRef = service
    }

    /** Called by onUnbind/onDestroy: stops everything cleanly. */
    fun detach() {
        serviceRef = null
        synchronized(sessions) {
            sessions.toList().forEach { it.close() }
            sessions.clear()
        }
        updateEventMask()
        notifyStateChanged()
    }

    /** The bound service, or null. */
    fun accessibilityService(): AccessibilityService? = serviceRef

    // ---------------------------------------------------------------- sessions

    /** True when a live window can be started right now. */
    fun canStart(): Boolean = serviceRef != null && count() < MAX_WINDOWS

    /** Number of sessions that still occupy a window slot. */
    fun count(): Int {
        prune()
        return synchronized(sessions) { sessions.size }
    }

    /** Live/failed sessions; closed ones are pruned automatically. */
    fun list(): List<OeaLiveWindowSession> {
        prune()
        return synchronized(sessions) { sessions.toList() }
    }

    fun findByPackage(pkg: String): OeaLiveWindowSession? {
        prune()
        return synchronized(sessions) { sessions.firstOrNull { it.packageName == pkg } }
    }

    /**
     * Starts a live window for the launchable component of [packageName].
     * Returns a user-facing result line; never throws.
     */
    fun start(context: android.content.Context, packageName: String, appLabel: String): String {
        val service = serviceRef
            ?: return "OEA Floating Window accessibility is off, so live app windows cannot run. Enable it in OEA Settings › Multitasking."
        if (findByPackage(packageName) != null) {
            val existing = findByPackage(packageName)
            existing?.restore()
            return "$appLabel already has a live window."
        }
        if (!canStart()) {
            return "Up to $MAX_WINDOWS live windows can run at once. Close one first."
        }
        // One launch at a time: the trampoline handshake is global.
        if (synchronized(sessions) { sessions.any { it.state == OeaProjectionState.STARTING } }) {
            return "Another app is still starting in a live window. Wait a moment."
        }
        prune()
        val launchIntent = try {
            service.packageManager.getLaunchIntentForPackage(packageName)
        } catch (_: Throwable) {
            null
        } ?: return "$appLabel has no launchable activity."
        val activityName = launchIntent.component?.className
            ?: return "$appLabel has no launchable activity."

        val metrics = service.resources.displayMetrics
        val index = count()
        val startX = ((metrics.widthPixels * 0.06f) + index * dp(service, 18)).toInt()
        val startY = ((metrics.heightPixels * 0.08f) + index * dp(service, 24)).toInt()

        val session = OeaLiveWindowSession(service, packageName, activityName, appLabel, startX, startY)
        synchronized(sessions) { sessions.add(session) }
        updateEventMask()
        notifyStateChanged()
        session.start()
        return "Starting a live window for $appLabel. Real app, private display, phone screen untouched."
    }

    /** Stops one session. */
    fun stop(session: OeaLiveWindowSession) {
        synchronized(sessions) { sessions.remove(session) }
        session.close()
        updateEventMask()
        notifyStateChanged()
    }

    /** Stops everything (kill switch). */
    fun stopAll() {
        synchronized(sessions) {
            sessions.toList().forEach { it.close() }
            sessions.clear()
        }
        updateEventMask()
        notifyStateChanged()
    }

    /** Removes closed sessions from the list so they stop occupying slots. */
    fun prune() {
        val changed = synchronized(sessions) {
            val before = sessions.size
            sessions.removeAll { it.state == OeaProjectionState.STOPPED }
            before != sessions.size
        }
        if (changed) updateEventMask()
    }

    fun notifyStateChanged() {
        onStateChanged?.let { cb ->
            android.os.Handler(android.os.Looper.getMainLooper()).post { cb() }
        }
    }

    // ---------------------------------------------------------------- events

    /**
     * Routes accessibility events to sessions. Called from
     * OeaFloatingWindowService.onAccessibilityEvent; must stay cheap.
     */
    fun onAccessibilityEvent(event: AccessibilityEvent) {
        val live = synchronized(sessions) { sessions.toList() }
        if (live.isEmpty()) return
        for (session in live) {
            session.onAccessibilityEvent(event)
        }
        // TextureView render callbacks arrive through the overlay; a11y
        // events keep the "last seen" clock warm per package.
    }

    /**
     * Widens/narrows the service's event mask depending on live sessions.
     * Window-state events are always wanted (default config); content-changed
     * and windows-changed are only requested while projections run.
     */
    private fun updateEventMask() {
        val service = serviceRef ?: return
        val active = synchronized(sessions) {
            sessions.any { it.state != OeaProjectionState.STOPPED && it.state != OeaProjectionState.FAILED }
        }
        try {
            service.serviceInfo?.let { info ->
                val wanted = if (active) {
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                        AccessibilityEvent.TYPE_WINDOWS_CHANGED or
                        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                } else {
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                }
                if (info.eventTypes != wanted) {
                    info.eventTypes = wanted
                    service.serviceInfo = info
                }
            }
        } catch (_: Throwable) {
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun dp(context: android.content.Context, v: Int): Int =
        (v * context.resources.displayMetrics.density + 0.5f).toInt()
}
