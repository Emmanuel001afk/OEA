package com.oea.launcher.miniapps

import java.util.Locale

/**
 * The two clock faces as text.
 *
 * They lived as private helpers in the middle of a 1,200-line view, which meant the
 * only way to check them was to start a stopwatch by hand and read the screen. Both
 * have a rule that is easy to break and easy to state:
 *
 *  * a countdown **rounds up**, so a five-minute timer does not read 00:00 for its
 *    last second and a paused timer at 4:59.4 still says 05:00;
 *  * a stopwatch **truncates** and shows hundredths, because that is what a
 *    stopwatch is for — a hundredth that rounds up would show 00:01.00 at 999 ms;
 *  * neither ever shows a negative time.
 *
 * Both are pure functions of a Long, so `OeaClockFormatTest` pins the rules on a JVM.
 */
internal object OeaClockFormat {

    /** `MM:SS.hh` (or `H:MM:SS.hh`), truncating, for a running stopwatch. */
    fun stopwatch(ms: Long): String {
        val safe = ms.coerceAtLeast(0L)
        val totalSec = safe / 1000L
        val h = totalSec / 3600L
        val m = (totalSec % 3600L) / 60L
        val s = totalSec % 60L
        val hundredths = (safe % 1000L) / 10L
        return if (h > 0L) String.format(Locale.US, "%d:%02d:%02d.%02d", h, m, s, hundredths)
        else String.format(Locale.US, "%02d:%02d.%02d", m, s, hundredths)
    }

    /** `MM:SS` (or `H:MM:SS`), rounding up, for a countdown. */
    fun countdown(ms: Long): String {
        val totalSec = (ms.coerceAtLeast(0L) + 999L) / 1000L
        val h = totalSec / 3600L
        val m = (totalSec % 3600L) / 60L
        val s = totalSec % 60L
        return if (h > 0L) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }
}
