package com.oea.launcher.camera

/**
 * The arithmetic a camera needs, kept away from the Android framework so it can
 * be tested on the JVM.
 *
 * Every value here answers a question the camera screen used to get wrong by
 * guessing: which way is up (the sensor is not mounted the way the screen is),
 * how much of the sensor is on screen (a preview buffer is not the shape of the
 * view), where the finger pointed (a tap is in view space, focus is in sensor
 * space) and how far the lens should move for a 0–100 slider.
 */
object OeaCameraOptics {

    /**
     * Degrees the camera buffer must be turned clockwise to appear upright in
     * the window, given the sensor's mounting [sensorOrientation] and the
     * display's current rotation in degrees.
     *
     * Rear cameras are mounted opposite to the screen; front cameras are mounted
     * the same way round, which is why they use a sum instead of a difference.
     * This is the same pair of formulas Android's own camera samples use.
     */
    fun rotationDegrees(sensorOrientation: Int, deviceRotationDegrees: Int, frontFacing: Boolean): Int {
        val sensor = normalize(sensorOrientation)
        val device = normalize(deviceRotationDegrees)
        val rotation = if (frontFacing) (sensor + device) % 360 else (sensor - device + 360) % 360
        return normalize(rotation)
    }

    /**
     * The EXIF orientation tag (1, 3, 6 or 8) for a still whose pixels are stored
     * exactly as the sensor delivered them. 6 means "rotate 90° clockwise to
     * display", which is the common portrait phone case.
     */
    fun exifOrientation(sensorOrientation: Int, deviceRotationDegrees: Int, frontFacing: Boolean): Int =
        when (rotationDegrees(sensorOrientation, deviceRotationDegrees, frontFacing)) {
            90 -> 6
            180 -> 3
            270 -> 8
            else -> 1
        }

    /** Turns a display rotation constant's degrees into whole quarter turns. */
    fun quarterTurns(degrees: Int): Int = (normalize(degrees) / 90) % 4

    private fun normalize(degrees: Int): Int = ((degrees % 360) + 360) % 360

    /**
     * Focus distance in dioptres for a 0–100 slider, where 0 means "let the
     * camera focus itself" and 100 means "as close as this lens can focus".
     *
     * The old mapping was inverted: 100 became 0 dioptres, i.e. infinity, so
     * dragging the slider towards "close" pushed the lens to the horizon.
     */
    fun focusDioptres(nearDioptres: Float, percent: Int): Float {
        if (percent <= 0 || nearDioptres <= 0f) return 0f
        return nearDioptres * percent.coerceIn(0, 100) / 100f
    }

    fun aspectOf(width: Int, height: Int): Float =
        if (width <= 0 || height <= 0) 1f else width.toFloat() / height.toFloat()

    fun matchesAspect(width: Int, height: Int, aspect: Float, tolerance: Float = 0.02f): Boolean {
        if (aspect <= 0f) return true
        return kotlin.math.abs(aspectOf(width, height) - aspect) <= tolerance * aspect
    }

    /**
     * The biggest offered size that matches [aspect] inside a pixel budget.
     *
     * When nothing matches exactly, the closest shape wins — a phone that only
     * offers 4:3 still gives a picture for a 16:9 request instead of returning
     * nothing — and among equally close shapes the biggest wins, because a crop
     * from a larger frame is still a better picture.
     */
    fun bestAspectSize(sizes: List<Pair<Int, Int>>, aspect: Float, maxPixels: Long = Long.MAX_VALUE): Pair<Int, Int>? {
        val usable = sizes.filter { it.first > 0 && it.second > 0 && it.first.toLong() * it.second <= maxPixels }
        if (usable.isEmpty()) return null
        if (aspect <= 0f) return usable.maxByOrNull { it.first.toLong() * it.second }
        val exact = usable.filter { matchesAspect(it.first, it.second, aspect) }
        if (exact.isNotEmpty()) return exact.maxByOrNull { it.first.toLong() * it.second }
        val closest = usable.minOfOrNull { kotlin.math.abs(aspectOf(it.first, it.second) - aspect) } ?: return null
        return usable
            .filter { kotlin.math.abs(aspectOf(it.first, it.second) - aspect) <= closest + 0.0001f }
            .maxByOrNull { it.first.toLong() * it.second }
    }

    /**
     * Scale that makes a buffer cover a view after being rotated by
     * [rotationDegrees] — a centre crop, never a stretch, so the preview keeps
     * the shape the lens sees.
     */
    fun coverScale(viewWidth: Float, viewHeight: Float, bufferWidth: Float, bufferHeight: Float, rotationDegrees: Int): Float {
        if (viewWidth <= 0f || viewHeight <= 0f || bufferWidth <= 0f || bufferHeight <= 0f) return 1f
        val quarterTurn = rotationDegrees == 90 || rotationDegrees == 270
        val rotatedWidth = if (quarterTurn) bufferHeight else bufferWidth
        val rotatedHeight = if (quarterTurn) bufferWidth else bufferHeight
        return maxOf(viewWidth / rotatedWidth, viewHeight / rotatedHeight)
    }

    /**
     * Which part of a still survives an aspect crop: `[left, top, right, bottom]`.
     * A picture that already has the wanted shape is returned untouched.
     */
    fun cropRect(width: Int, height: Int, aspect: Float): IntArray {
        if (aspect <= 0f || width <= 0 || height <= 0) return intArrayOf(0, 0, width, height)
        val current = aspectOf(width, height)
        if (kotlin.math.abs(current - aspect) < 0.01f) return intArrayOf(0, 0, width, height)
        return if (current > aspect) {
            val croppedWidth = (height * aspect).toInt().coerceIn(1, width)
            val left = (width - croppedWidth) / 2
            intArrayOf(left, 0, left + croppedWidth, height)
        } else {
            val croppedHeight = (width / aspect).toInt().coerceIn(1, height)
            val top = (height - croppedHeight) / 2
            intArrayOf(0, top, width, top + croppedHeight)
        }
    }
}
