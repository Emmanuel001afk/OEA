package com.oea.launcher.power

/**
 * Turns proximity near/far readings into deliberate "wave" gestures.
 *
 * A wave is the sensor being covered briefly and uncovered again. Guards
 * against accidental wakes:
 *  - covers longer than [pocketMs] (a pocket, a bag, the phone face down)
 *    are not waves, and while the sensor stays covered nothing triggers;
 *  - a cover shorter than [minCoverMs] is sensor noise;
 *  - optionally two waves within [doubleWindowMs] are required;
 *  - after a wake, readings are ignored for [cooldownMs].
 *
 * Pure logic with explicit timestamps, so it is the same code in the wake
 * service and in the live test in OEA Settings.
 */
class OeaWaveDetector(
    sensitivity: Int,
    private val requireTwoWaves: Boolean,
    private val pocketGuard: Boolean,
) {
    /** Longest cover that still counts as a wave: gentle (low), normal, generous (high). */
    private val maxCoverMs: Long = when (sensitivity) {
        0 -> 450L
        2 -> 1_100L
        else -> 750L
    }
    private val minCoverMs = 40L
    private val pocketMs = 1_800L
    private val doubleWindowMs = 1_600L
    private val cooldownMs = 2_500L

    private var coveredAt = -1L
    private var lastWaveAt = -1L
    private var lastTriggerAt = -1L
    /** True while the sensor was already covered when listening began (e.g. in a pocket). */
    private var startedCovered = false

    /** Call when listening starts (screen turned off) with the current state, if known. */
    fun reset(nowMs: Long, coveredNow: Boolean) {
        coveredAt = if (coveredNow) nowMs else -1L
        startedCovered = coveredNow && pocketGuard
        lastWaveAt = -1L
    }

    /** Feeds one reading; returns true when the gesture is complete and the screen should wake. */
    fun onReading(covered: Boolean, nowMs: Long): Boolean {
        if (lastTriggerAt >= 0 && nowMs - lastTriggerAt < cooldownMs) {
            if (!covered) coveredAt = -1L else if (coveredAt < 0) coveredAt = nowMs
            return false
        }
        if (covered) {
            if (coveredAt < 0) coveredAt = nowMs
            return false
        }
        // Uncovered: was that a wave?
        val start = coveredAt
        coveredAt = -1L
        if (start < 0) return false
        val held = nowMs - start
        if (startedCovered) {
            // Coming out of a pocket or bag: the first uncover is not a gesture.
            startedCovered = false
            return false
        }
        if (pocketGuard && held >= pocketMs) return false
        if (held < minCoverMs || held > maxCoverMs) return false
        if (requireTwoWaves) {
            val previous = lastWaveAt
            lastWaveAt = nowMs
            if (previous < 0 || nowMs - previous > doubleWindowMs) return false
        }
        lastWaveAt = -1L
        lastTriggerAt = nowMs
        return true
    }
}
