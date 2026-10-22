package com.oea.launcher.power

/**
 * Deliberate screen-on proximity cover gesture. A clear reading must be observed
 * first, so a phone that starts listening while already in a pocket cannot lock.
 * The service owns the delay/timer; this class keeps the gesture state testable.
 */
class OeaCoverLockDetector(private val holdMs: Long = HOLD_MS) {
    private var hasClearBaseline = false
    private var coveredAt = -1L
    private var triggered = false

    /** True only on the first covered reading of a new, eligible cover. */
    fun onReading(covered: Boolean, nowMs: Long): Boolean {
        if (!hasClearBaseline) {
            if (!covered) hasClearBaseline = true
            return false
        }
        if (covered) {
            if (coveredAt < 0L) {
                coveredAt = nowMs
                triggered = false
                return true
            }
        } else {
            coveredAt = -1L
            triggered = false
        }
        return false
    }

    /** Called by the service after the hold timer; succeeds once per cover. */
    fun shouldLock(nowMs: Long): Boolean {
        if (coveredAt < 0L || triggered || nowMs - coveredAt < holdMs) return false
        triggered = true
        return true
    }

    val isArmed: Boolean get() = coveredAt >= 0L && !triggered

    fun reset() {
        hasClearBaseline = false
        coveredAt = -1L
        triggered = false
    }

    companion object {
        const val HOLD_MS = 1_200L
    }
}
