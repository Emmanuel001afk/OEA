package com.oea.launcher.miniapps

import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The arithmetic and the wording behind EMAGE's detail sheet and its decodes.
 *
 * Two kinds of number meet in a photo app and they must not be formatted the same way:
 *
 *  * **Data**, which someone may copy out and paste somewhere that only understands
 *    a dot — coordinates, shutter speeds, apertures, focal lengths. These always use
 *    `.` as the decimal mark whatever the phone's language is, because `6,52400` is
 *    not a coordinate any map will accept and `1/59 s` is not the shutter speed the
 *    camera recorded.
 *  * **Prose**, which is read, not parsed — a file size, a megapixel count, an
 *    altitude. These follow the device locale, so a German phone says "1,4 MB".
 *
 * The other half is [sampleSize]: the one power-of-two rule the app decodes at. The
 * editor used to allow a 8192-pixel decode of a 48-megapixel photo, which is a 192 MB
 * bitmap — an out-of-memory the user met as "Could not load picture".
 */
internal object OeaEmageFacts {

    // ------------------------------------------------------------------ decoding

    /** The longest edge the editor decodes at: ~12 MB at 4:3, which any phone can hold twice. */
    const val EDIT_EDGE = 3000

    /**
     * The `inSampleSize` to decode [width]×[height] at.
     *
     * `BitmapFactory` only honours powers of two, so the question is which one. In
     * **fit** mode (the editor, a full-screen view) the long edge must end up at or
     * below [target]: a decode larger than the target is memory spent on pixels that
     * are thrown away. In **cover** mode (a square tile, a wallpaper) the *short* edge
     * must still reach [target] or the tile is upscaled and soft, so the sample stops
     * one step earlier.
     */
    fun sampleSize(width: Int, height: Int, target: Int, cover: Boolean = false): Int {
        if (width <= 0 || height <= 0 || target <= 0) return 1
        var sample = 1
        if (cover) {
            val short = minOf(width, height)
            while (short / (sample * 2) >= target) sample *= 2
        } else {
            val long = maxOf(width, height)
            while (long / sample > target) sample *= 2
        }
        return sample
    }

    // -------------------------------------------------------------------- prose

    private val SIZE_UNITS = listOf("B", "KB", "MB", "GB", "TB")

    /**
     * A file size a person reads: `512 B`, `12 KB`, `1.4 MB`, `2.34 GB`.
     *
     * The framing follows the *rounded* value, so 1 048 500 bytes is "1.0 MB" and never
     * "1024 KB", and 1023 bytes stays "1023 B" instead of becoming "1 KB".
     */
    fun size(bytes: Long): String {
        if (bytes <= 0L) return "—"
        if (bytes < 1024L) return "$bytes B"
        var value = bytes / 1024.0
        var unit = 1
        while (unit < SIZE_UNITS.lastIndex) {
            val decimals = decimalsFor(unit)
            val scale = 10.0.pow(decimals)
            val carried = (value * scale).roundToLong() / scale
            if (carried < 1024.0) break
            value /= 1024.0
            unit++
        }
        return prose("%.${decimalsFor(unit)}f", value) + " " + SIZE_UNITS[unit]
    }

    /** KB shows whole numbers, MB one decimal, GB and beyond two. */
    private fun decimalsFor(unit: Int): Int = when {
        unit <= 1 -> 0
        unit == 2 -> 1
        else -> 2
    }

    /** `12.2 MP`, or empty when the resolution is not known. */
    fun megapixels(width: Int, height: Int): String {
        if (width <= 0 || height <= 0) return ""
        return prose("%.1f", width.toLong() * height.toLong() / 1_000_000.0) + " MP"
    }

    /** `12 m`, `-4 m`; null when the file has no altitude. */
    fun altitude(metres: Double?): String? =
        if (printable(metres)) prose("%.0f", metres!!) + " m" else null

    // --------------------------------------------------------------------- data

    /**
     * A GPS pair with a dot, five decimals (about a metre), and no grouping: the text
     * this returns is meant to be pasted into a maps app or a message, both of which
     * read `6.52400, 3.37900` and neither of which reads `6,52400, 3,37900`.
     */
    fun coordinates(latitude: Double, longitude: Double): String =
        String.format(Locale.US, "%.5f, %.5f", latitude, longitude)

    /**
     * A shutter speed as a camera states it: `1/250 s`, `1/2 s`, `2.5 s`.
     *
     * Two faults are pinned here. The old label truncated the reciprocal instead of
     * rounding it, so a 1/60 s exposure printed as `1/59 s`; and a file that stores the
     * speed as the string `1/250` (most do) took a different branch and lost its unit,
     * so the details sheet said `Exposure 1/250` beside `Aperture f/1.8`.
     */
    fun shutter(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty() || text == "0" || text == "—") return "—"
        if (text.contains('/')) {
            // Keep the camera's own fraction: "1/250 s" is what a photographer reads,
            // and "0.004 s" is not.
            val bottom = text.split('/').getOrNull(1)?.trim()?.toDoubleOrNull()
            return if (bottom != null && bottom != 0.0) text + " s" else "—"
        }
        // Anything unreadable is left out rather than printed as a bare number: the
        // whole fault being fixed here is a number without its unit.
        val seconds = text.removeSuffix("s").trim().toDoubleOrNull() ?: return "—"
        if (seconds <= 0.0) return "—"
        if (seconds >= 0.5) return OeaCalcEngine.format(seconds) + " s"
        // One sixtieth of a second is 59.9999… when divided back out; rounding is the
        // difference between the number the camera wrote and a number nobody has seen.
        return "1/${(1.0 / seconds).roundToInt()} s"
    }

    /** `f/1.8`, from either `1.8` or the rational form `18/10`. */
    fun aperture(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty() || text == "—") return "—"
        if (text.startsWith("f/", ignoreCase = true)) return text
        val value = rational(text) ?: return "f/$text"
        return "f/" + OeaCalcEngine.format(value)
    }

    /** `ISO 100`, or `ISO —` never: the row leaves the field out when it is unknown. */
    fun iso(raw: String): String {
        val text = raw.trim()
        return if (text.isEmpty() || text == "—") "—" else "ISO $text"
    }

    /**
     * A focal length in millimetres: `50 mm`, `3.5 mm`. Files store the rational
     * `50/1`, which the old sheet printed as `50/1 mm`.
     */
    fun focalLength(equivalent35mm: String, raw: String): String {
        val value = rational(equivalent35mm.trim().removeSuffix("mm").trim())
            ?: rational(raw.trim().removeSuffix("mm").trim())
            ?: return "—"
        return OeaCalcEngine.format(value) + " mm"
    }

    /**
     * The exposure line under a photo: `1/250 s • f/1.8 • ISO 100`.
     *
     * Fields the file does not carry are dropped rather than printed as a dash, and a
     * file with no camera data at all says so instead of showing three em dashes.
     */
    fun exposureLine(shutter: String, aperture: String, iso: String): String {
        val parts = listOf(shutter, aperture, iso)
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "—" }
        return if (parts.isEmpty()) "No camera settings recorded" else parts.joinToString(" • ")
    }

    /** EXIF metering mode, complete: the sheet used to show "—" for multi-spot. */
    fun metering(code: Int): String = when (code) {
        0 -> "—"
        1 -> "Average"
        2 -> "Centre weighted"
        3 -> "Spot"
        4 -> "Multi spot"
        5 -> "Pattern"
        6 -> "Partial"
        255 -> "Other"
        else -> "—"
    }

    /**
     * EXIF white balance: 0 is automatic and 1 is manual.
     *
     * The old table read the *light source* codes here — it said "Auto" for a manual
     * white balance and offered "Fluorescent" (9), which is not a value this tag has.
     */
    fun whiteBalance(code: Int): String = when (code) {
        0 -> "Auto"
        1 -> "Manual"
        else -> "—"
    }

    // ------------------------------------------------------------------ internals

    /** A number written for reading, in the device's own language. */
    private fun prose(pattern: String, value: Double): String = String.format(Locale.getDefault(), pattern, value)

    /** Parses `5`, `3.5`, `50/1` or `35/10`. */
    private fun rational(raw: String): Double? {
        if (raw.isBlank()) return null
        val parts = raw.split('/')
        if (parts.size == 2) {
            val top = parts[0].trim().toDoubleOrNull() ?: return null
            val bottom = parts[1].trim().toDoubleOrNull() ?: return null
            if (bottom == 0.0) return null
            return top / bottom
        }
        return raw.trim().toDoubleOrNull()
    }

    /** True when [value] is a number the sheet can print at all. */
    fun printable(value: Double?): Boolean = value != null && !value.isNaN() && !value.isInfinite() && abs(value) < 1e12
}
