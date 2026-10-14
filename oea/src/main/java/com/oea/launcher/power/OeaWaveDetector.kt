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
    /** Cover long enough to mean "hand over the screen", not "wave past it". */
    private val longCoverMs = 1_250L
    /** How long after such a cover the deciding wave may come. */
    private val armWindowMs = 1_400L
    private var armedAt = -1L

    private var coveredAt = -1L
    private var lastWaveAt = -1L
    private var lastTriggerAt = -1L
    private var offTriggerAt = -1L
    /** True while the sensor was already covered when listening began (e.g. in a pocket). */
    private var startedCovered = false

    /**
     * What a reading should do next. The screen-on and screen-off gestures are two
     * different patterns, decided here and nowhere else:
     *
     * - [ON] — a wave (one, or two inside the double window) with the screen off.
     * - [OFF] — a *long* cover followed by an uncover and then a second wave right
     *   after it. The long cover is what keeps this pattern out of the way of the
     *   wake gesture: a deliberate hand-over-the-screen is not something that
     *   happens by accident, and a normal wave is too short to qualify.
     * - [NONE] — anything else, including the first wave of a pair.
     */
    enum class Result { NONE, ON, OFF }

    /** Call when listening starts (screen turned off) with the current state, if known. */
    fun reset(nowMs: Long, coveredNow: Boolean) {
        coveredAt = if (coveredNow) nowMs else -1L
        startedCovered = coveredNow && pocketGuard
        lastWaveAt = -1L
    }

    /**
     * Feeds one reading and reports which gesture, if any, it completed.
     *
     * [screenOffBound] tells the detector whether the screen-off pattern has an
     * action at all: when it does not, a long cover is simply ignored (exactly as
     * before), so the wake gesture behaves identically for users who never touch
     * the screen-off setting.
     */
    fun onReading(covered: Boolean, nowMs: Long, screenOffBound: Boolean = false): Result {
        val cooling = lastTriggerAt >= 0 && nowMs - lastTriggerAt < cooldownMs
        val offCooling = offTriggerAt >= 0 && nowMs - offTriggerAt < cooldownMs
        if (covered) {
            if (coveredAt < 0) coveredAt = nowMs
            return Result.NONE
        }
        // Uncovered: which pattern was that?
        val start = coveredAt
        coveredAt = -1L
        if (start < 0) return Result.NONE
        val held = nowMs - start
        if (startedCovered) {
            // Coming out of a pocket or bag: the first uncover is never a gesture.
            startedCovered = false
            return Result.NONE
        }
        if (screenOffBound && held >= longCoverMs) {
            // A long cover arms the screen-off gesture. The wave that follows it
            // decides; on its own a long cover does nothing.
            if (offCooling) return Result.NONE
            armedAt = nowMs
            return Result.NONE
        }
        if (pocketGuard && held >= pocketMs) return Result.NONE
        if (held < minCoverMs || held > maxCoverMs) return Result.NONE
        if (armedAt >= 0 && screenOffBound && nowMs - armedAt <= armWindowMs) {
            // The second wave of the off pattern. It is consumed here and never
            // reaches the wake logic, so one pattern cannot run both actions.
            armedAt = -1L
            offTriggerAt = nowMs
            return Result.OFF
        }
        if (cooling) return Result.NONE
        if (requireTwoWaves) {
            val previous = lastWaveAt
            lastWaveAt = nowMs
            if (previous < 0 || nowMs - previous > doubleWindowMs) return Result.NONE
        }
        lastWaveAt = -1L
        lastTriggerAt = nowMs
        return Result.ON
    }

    /** True while a long cover has been seen and the deciding wave is still awaited. */
    val armedForScreenOff: Boolean get() = armedAt >= 0
}
