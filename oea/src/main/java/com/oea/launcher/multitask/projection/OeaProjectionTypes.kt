package com.oea.launcher.multitask.projection

/**
 * Shared vocabulary for OEA's live app projection (experimental).
 *
 * Terminology used across the projection package:
 *  - "virtual display" (VD): an app-owned secondary display created with
 *    [android.hardware.display.DisplayManager]. The real target application
 *    runs on it while the user's foreground app keeps the physical screen.
 *  - "trampoline": a transparent OEA activity that is placed on the VD first
 *    so the target application can inherit its display (AOSP multi-display
 *    launch policy). It never draws content.
 *  - "live window": OEA's floating overlay window that presents the VD's
 *    frames and translates touches into accessibility node actions on the
 *    target application.
 *
 * Everything in this file is plain data: no Android types that need a
 * Context, so it can be reasoned about (and reviewed) in isolation.
 */

/** Lifecycle of one projected application. */
enum class OeaProjectionState {
    /** Creating the display and launching the target application. */
    STARTING,

    /** Target is on the virtual display, frames are flowing, node control works. */
    LIVE,

    /** Target is on the virtual display and renders, but no interactive nodes
     *  were found (or node actions were rejected). The window is view-only. */
    VIEW_ONLY,

    /** The pipeline broke: launch failed, the app never reached the display,
     *  or the display died. [reason] must explain what happened. */
    FAILED,

    /** Closed by the user or the service. Terminal. */
    STOPPED
}

/** Structured outcome of a single accessibility node action. */
sealed class OeaNodeOutcome {
    /** Action accepted by the target application's view. */
    data class Ok(val detail: String) : OeaNodeOutcome()

    /** The action could not be performed at all (service gone, no window). */
    data class Failed(val reason: String) : OeaNodeOutcome()

    /**
     * The action was performed on a node, but the view reported it could not
     * carry it out (common when an app's custom view does not implement the
     * accessibility action). Still "view-only" territory for that control.
     */
    data class Rejected(val reason: String) : OeaNodeOutcome()
}

/** One measured step of an experiment or launch attempt. */
data class OeaEvidenceLine(
    val step: String,
    val outcome: String,
    val ms: Long = -1L
) {
    override fun toString(): String {
        val time = if (ms >= 0) " (${ms}ms)" else ""
        return "• $step: $outcome$time"
    }
}

/** Result of the whole launch ladder for one target application. */
data class OeaProjectionLaunchResult(
    val success: Boolean,
    val displayId: Int,
    val state: OeaProjectionState,
    val headline: String,
    val evidence: List<OeaEvidenceLine>
) {
    fun evidenceText(): String = evidence.joinToString("\n")
}

/**
 * How the virtual display's pixels reach the floating window.
 * The device decides which one actually works; the diagnostics report it.
 */
enum class OeaFrameMode {
    /** TextureView + SurfaceTexture: zero-copy, per-frame timestamps. */
    TEXTURE,

    /** ImageReader polled on a background thread and drawn into an ImageView. */
    READER,

    /** No frames reached the window. */
    NONE
}
