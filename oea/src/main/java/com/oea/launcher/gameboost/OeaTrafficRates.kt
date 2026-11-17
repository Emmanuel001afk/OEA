package com.oea.launcher.gameboost

import java.util.Locale

/** Per-UID byte counter deltas; no device-wide fallback when counters are inaccessible. */
internal class OeaTrafficRates {
    private var at = 0L
    private var rx = -1L
    private var tx = -1L
    var down = "—"; private set
    var up = "—"; private set

    fun reset() { at = 0L; rx = -1L; tx = -1L; down = "—"; up = "—" }

    /**
     * Sets a rate that was already measured, for a source that can only be
     * read as a window (Android's per-UID stats) rather than as a counter
     * that keeps climbing.
     */
    fun report(downBytes: Double, upBytes: Double) {
        // Stop the counter path from mixing its own delta into a rate measured
        // another way the next time it runs.
        at = 0L; rx = -1L; tx = -1L
        down = format(downBytes)
        up = format(upBytes)
    }

    fun sample(now: Long, nextRx: Long, nextTx: Long) {
        if (nextRx < 0 || nextTx < 0) { reset(); return }
        if (at != 0L && now - at < 1000L && now >= at) return
        if (at > 0L && now > at && nextRx >= rx && nextTx >= tx) {
            val seconds = (now - at) / 1000.0
            down = format((nextRx - rx) / seconds)
            up = format((nextTx - tx) / seconds)
        } else { down = "—"; up = "—" }
        rx = nextRx; tx = nextTx; at = now
    }

    private fun format(bytes: Double): String =
        if (bytes >= 1_000_000.0) String.format(Locale.US, "%.1f MB/s", bytes / 1_000_000.0)
        else String.format(Locale.US, "%.0f KB/s", bytes / 1_000.0)
}
