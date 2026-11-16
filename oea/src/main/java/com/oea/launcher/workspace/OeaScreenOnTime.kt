package com.oea.launcher.workspace

/** A screen-state transition, from Android UsageEvents. */
internal data class ScreenTransition(val at: Long, val interactive: Boolean)

/** Screen-on time since [start], or null if Android has not supplied a baseline state. */
internal fun screenOnTime(start: Long, end: Long, transitions: List<ScreenTransition>): Long? {
    if (end <= start) return null
    val events = transitions.filter { it.at <= end }.sortedBy { it.at }
    var state: Boolean? = null
    var cursor = start
    var total = 0L
    for (event in events) {
        if (event.at <= start) { state = event.interactive; continue }
        if (state == null) return null
        if (state) total += event.at - cursor
        cursor = event.at
        state = event.interactive
    }
    if (state == null) return null
    if (state) total += end - cursor
    return total.coerceIn(0L, end - start)
}
