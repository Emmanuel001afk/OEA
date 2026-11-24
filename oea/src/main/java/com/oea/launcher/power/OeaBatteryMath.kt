package com.oea.launcher.power

import kotlin.math.roundToInt

/**
 * The arithmetic behind every battery percentage OEA draws — pure, so it is tested
 * rather than eyeballed.
 *
 * Battery level arrives two different ways and neither is trustworthy on its own:
 *
 *  * the sticky `ACTION_BATTERY_CHANGED` intent, which carries `level` and `scale`
 *    that **both** have to be valid before they mean anything (`scale` is 0 on some
 *    builds, and `level` can exceed it);
 *  * `BatteryManager.getIntProperty(BATTERY_PROPERTY_CAPACITY)`, which answers
 *    `Integer.MIN_VALUE` — not a number — on phones that do not implement it.
 *
 * Worse than either: a percentage that is right when the screen opened and wrong
 * a minute later, and a "charging" claim made from the level alone. So the rules
 * below clamp, round, and treat *unknown* as unknown instead of as 0% or 100%.
 */
internal object OeaBatteryMath {

    // Mirrors of android.os.BatteryManager.BATTERY_STATUS_*, so the tests need no
    // Android framework. If these drift, every reading in the app is wrong.
    const val STATUS_UNKNOWN = 1
    const val STATUS_CHARGING = 2
    const val STATUS_DISCHARGING = 3
    const val STATUS_NOT_CHARGING = 4
    const val STATUS_FULL = 5

    /** What `getIntProperty` answers when the phone cannot report the value. */
    const val PROPERTY_UNKNOWN = Int.MIN_VALUE

    /** What the caller uses when there is no percentage at all. */
    const val NO_PERCENT = -1

    /**
     * The charge as a whole percentage, or null when the pair does not describe one.
     *
     * Rounded rather than truncated: `level * 100 / scale` turns 2/3 into 66 and
     * 995/1000 into 99, both of which are percentages the phone never showed anyone.
     * A level above the scale is clamped instead of printing "101%".
     */
    fun percent(level: Int, scale: Int): Int? {
        if (level < 0 || scale <= 0) return null
        val value = (level.toDouble() * 100.0 / scale).roundToInt()
        return value.coerceIn(0, 100)
    }

    /** A `BATTERY_PROPERTY_CAPACITY` answer, or null when it is the "unsupported" sentinel. */
    fun percentProperty(value: Int): Int? = if (value in 0..100) value else null

    /** A `BATTERY_PROPERTY_STATUS` answer, or null when it is the sentinel. */
    fun statusProperty(value: Int): Int? = if (value in STATUS_UNKNOWN..STATUS_FULL) value else null

    /** Charging means taking charge — a full battery on the cable still counts. */
    fun isCharging(status: Int): Boolean = status == STATUS_CHARGING || status == STATUS_FULL

    fun isFull(status: Int): Boolean = status == STATUS_FULL

    /**
     * The long form, used where there is room. "Plugged in" is kept distinct from
     * "charging": a phone holding at 80% on a charge limiter is plugged in and not
     * charging, and saying otherwise is the kind of guess this file exists to stop.
     */
    fun description(status: Int, plugged: Boolean): String = when (status) {
        STATUS_CHARGING -> "Charging"
        STATUS_FULL -> "Full"
        STATUS_DISCHARGING -> if (plugged) "Plugged in, discharging" else "Discharging"
        STATUS_NOT_CHARGING -> if (plugged) "Plugged in, not charging" else "Not charging"
        else -> if (plugged) "Plugged in, status unknown" else "Status unknown"
    }

    /** The short form for a caption, or null when there is nothing worth adding. */
    fun note(status: Int, plugged: Boolean): String? = when (status) {
        STATUS_CHARGING -> "Charging"
        STATUS_FULL -> "Full"
        STATUS_NOT_CHARGING -> if (plugged) "Plugged in, not charging" else "Not charging"
        STATUS_DISCHARGING -> if (plugged) "Plugged in, discharging" else null
        else -> if (plugged) "Plugged in, status unknown" else null
    }

    /** "85%" — or an em dash when the phone cannot say. */
    fun label(percent: Int?): String = percent?.let { "$it%" } ?: "—"

    /**
     * "85%" while running on the battery, "85% · Charging" otherwise — a phone on
     * the cable should never look like one draining in a pocket.
     */
    fun shortLabel(percent: Int?, status: Int, plugged: Boolean): String {
        val base = label(percent)
        if (percent == null) return base
        val note = note(status, plugged) ?: return base
        return "$base · $note"
    }

    /** The in-game strip's caption: it screams only what is true. */
    fun hudCaption(status: Int, plugged: Boolean): String = when {
        isCharging(status) -> "CHARGING"
        plugged -> "PLUGGED"
        else -> "BATTERY"
    }

    /** The icon that belongs with the reading. */
    fun isChargingIcon(status: Int): Boolean = isCharging(status)
}
