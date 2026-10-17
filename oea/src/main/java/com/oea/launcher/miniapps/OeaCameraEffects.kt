package com.oea.launcher.miniapps

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.PointF
import android.graphics.BitmapFactory
import android.media.FaceDetector
import java.io.File
import java.io.FileOutputStream
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The two Camera modes that change pixels instead of only changing the label.
 *
 * OEA has no native code and no machine-learning model, so it does not pretend
 * otherwise: "Night" is a real shadow-lifting tone curve with a light denoise in
 * the dark areas only, and "Portrait" is a distance-weighted blur that softens
 * everything away from the band you focused on — the same trick a tilt-shift lens
 * pulls, and it is honest about not knowing depth. "Blur faces" runs Android's own
 * [FaceDetector] over the picture and smudges what it finds, which is what you
 * want before sending a photo of a street to anyone.
 *
 * Everything runs on a copy capped at [WORK_MAX] pixels on the long edge, so a
 * 108-megapixel file cannot turn the shutter into a two-second wait.
 */
internal object OeaCameraEffects {

    private const val WORK_MAX = 1600

    /** True when this picture needs any work at all, so the shutter stays instant. */
    fun needed(mode: OeaCameraApp.CaptureMode, blurFaces: Boolean): Boolean =
        blurFaces || mode == OeaCameraApp.CaptureMode.NIGHT || mode == OeaCameraApp.CaptureMode.PORTRAIT

    /**
     * Returns a new bitmap, or the same instance when nothing applied. The caller
     * owns both, and never shows a half-processed frame: this is a pure function on
     * pixels.
     */
    fun process(
        source: Bitmap,
        mode: OeaCameraApp.CaptureMode,
        blurFaces: Boolean,
        focusY: Float = 0.5f
    ): Bitmap {
        if (!needed(mode, blurFaces)) return source
        val longest = maxOf(source.width, source.height).coerceAtLeast(1)
        val scale = minOf(1f, WORK_MAX.toFloat() / longest)
        // A real downscale, not a crop: the whole frame has to survive the pass.
        val work = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                source,
                (source.width * scale).toInt().coerceAtLeast(8),
                (source.height * scale).toInt().coerceAtLeast(8),
                true
            )
        } else source
        val mutable = work.copy(Bitmap.Config.ARGB_8888, true) ?: work
        if (mutable !== work && work !== source) work.recycle()
        when (mode) {
            OeaCameraApp.CaptureMode.NIGHT -> night(mutable)
            OeaCameraApp.CaptureMode.PORTRAIT -> portrait(mutable, focusY)
            else -> Unit
        }
        if (blurFaces) blurFaces(mutable)
        return mutable
    }

    /** Lifts shadows, keeps highlights, and denoises only where it is dark. */
    private fun night(bitmap: Bitmap) {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        for (i in pixels.indices) {
            val color = pixels[i]
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            val luma = (r * 299 + g * 587 + b * 114) / 1000
            // Shadows gain the most, mid-tones a little, highlights are left alone.
            val gain = when {
                luma < 24 -> 2.45f
                luma < 60 -> 1.72f
                luma < 120 -> 1.24f
                else -> 1.0f
            }
            var nr = (r * gain).toInt()
            var ng = (g * gain).toInt()
            var nb = (b * gain).toInt()
            // A touch of contrast on the way out, so the lift does not go milky.
            nr = applyCurve(nr)
            ng = applyCurve(ng)
            nb = applyCurve(nb)
            pixels[i] = Color.rgb(nr.coerceIn(0, 255), ng.coerceIn(0, 255), nb.coerceIn(0, 255))
        }
        // Cheap chroma/luma smoothing over the dark regions only: a 3×3 box blur
        // blended by how dark the pixel was. Colour noise lives in the shadows.
        val blurred = IntArray(pixels.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var r = 0
                var g = 0
                var b = 0
                var n = 0
                var dy = -1
                while (dy <= 1) {
                    var dx = -1
                    while (dx <= 1) {
                        val sx = (x + dx).coerceIn(0, width - 1)
                        val sy = (y + dy).coerceIn(0, height - 1)
                        val c = pixels[sy * width + sx]
                        r += (c shr 16) and 0xFF
                        g += (c shr 8) and 0xFF
                        b += c and 0xFF
                        n++
                        dx++
                    }
                    dy++
                }
                blurred[y * width + x] = Color.rgb(r / n, g / n, b / n)
            }
        }
        for (i in pixels.indices) {
            val original = pixels[i]
            val luma = (((original shr 16) and 0xFF) * 299 + (((original shr 8) and 0xFF) * 587) + ((original and 0xFF) * 114)) / 1000
            val strength = if (luma < 70) 0.45f else 0f
            if (strength <= 0f) continue
            val soft = blurred[i]
            val mixed = Color.rgb(
                mix((original shr 16) and 0xFF, (soft shr 16) and 0xFF, strength),
                mix((original shr 8) and 0xFF, (soft shr 8) and 0xFF, strength),
                mix(original and 0xFF, soft and 0xFF, strength)
            )
            pixels[i] = mixed
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    }

    private fun applyCurve(value: Int): Int {
        val v = value / 255f
        val curved = if (v < 0.5f) 2f * v * v else 1f - pow((2f - 2f * v).toDouble(), 2.0).toFloat() / 2f
        return (curved * 255f).toInt()
    }

    private fun mix(from: Int, to: Int, amount: Float): Int = (from + (to - from) * amount).toInt().coerceIn(0, 255)

    /**
     * Everything outside the middle band of the frame is blurred harder, which is
     * what a shallow depth of field looks like. The band follows the focus tap:
     * [focusY] is where in the frame you told the camera to look, as a fraction of
     * the height. There is no depth sensor in this, and OEA says so — it is the
     * tilt-shift trick, not a measurement of distance.
     */
    private fun portrait(bitmap: Bitmap, focusY: Float = 0.5f) {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val soft = IntArray(pixels.size)
        // Two passes of a widening box blur give a smooth falloff without a
        // separate blur radius per row.
        val bandTop = (height * (focusY - 0.18f)).toInt().coerceAtLeast(0)
        val bandBottom = (height * (focusY + 0.18f)).toInt().coerceAtMost(height - 1)
        for (y in 0 until height) {
            val distance = when {
                y in bandTop..bandBottom -> 0
                y < bandTop -> bandTop - y
                else -> y - bandBottom
            }.toFloat()
            val radius = (distance / height * 6f).toInt().coerceIn(0, 6)
            if (radius == 0) {
                for (x in 0 until width) soft[y * width + x] = pixels[y * width + x]
                continue
            }
            for (x in 0 until width) {
                var r = 0
                var g = 0
                var b = 0
                var n = 0
                var dy = -radius
                while (dy <= radius) {
                    var dx = -radius
                    while (dx <= radius) {
                        val sx = (x + dx).coerceIn(0, width - 1)
                        val sy = (y + dy).coerceIn(0, height - 1)
                        val c = pixels[sy * width + sx]
                        r += (c shr 16) and 0xFF
                        g += (c shr 8) and 0xFF
                        b += c and 0xFF
                        n++
                        dx++
                    }
                    dy++
                }
                soft[y * width + x] = Color.rgb(r / n, g / n, b / n)
            }
        }
        for (i in pixels.indices) {
            val y = i / width
            val inBand = y in bandTop..bandBottom
            val original = pixels[i]
            if (inBand) {
                // Keep the subject crisp but push its colour slightly forward.
                val r = (original shr 16) and 0xFF
                val g = (original shr 8) and 0xFF
                val b = original and 0xFF
                val luma = (r * 299 + g * 587 + b * 114) / 1000
                pixels[i] = Color.rgb(
                    mix(r, luma, -0.10f).coerceIn(0, 255),
                    mix(g, luma, -0.06f).coerceIn(0, 255),
                    mix(b, luma, -0.04f).coerceIn(0, 255)
                )
            } else {
                val blurred = soft[i]
                pixels[i] = Color.rgb(
                    mix((original shr 16) and 0xFF, (blurred shr 16) and 0xFF, 0.82f),
                    mix((original shr 8) and 0xFF, (blurred shr 8) and 0xFF, 0.82f),
                    mix(original and 0xFF, blurred and 0xFF, 0.82f)
                )
            }
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        vignette(bitmap)
    }

    private fun vignette(bitmap: Bitmap) {
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = android.graphics.RadialGradient(
            bitmap.width / 2f, bitmap.height / 2f, maxOf(bitmap.width, bitmap.height) * 0.72f,
            intArrayOf(Color.TRANSPARENT, Color.argb(0x50, 0, 0, 0)),
            floatArrayOf(0.58f, 1f),
            android.graphics.Shader.TileMode.CLAMP
        )
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_OVER)
        canvas.drawRect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(), paint)
    }

    /**
     * Blurs what Android's own face detector finds. The detector wants RGB_565 and
     * works on a small copy, so the boxes are scaled back up to the working bitmap.
     */
    private fun blurFaces(bitmap: Bitmap) {
        val scale = minOf(1f, 640f / bitmap.width.toFloat())
        val probeWidth = (bitmap.width * scale).toInt().coerceAtLeast(16)
        val probeHeight = (bitmap.height * scale).toInt().coerceAtLeast(16)
        val probe = Bitmap.createBitmap(probeWidth, probeHeight, Bitmap.Config.RGB_565)
        Canvas(probe).drawBitmap(bitmap, Matrix().apply { postScale(scale, scale) }, null)
        val found = arrayOfNulls<FaceDetector.Face>(MAX_FACES)
        val count = runCatching { FaceDetector(probeWidth, probeHeight, MAX_FACES).findFaces(probe, found) }
            .getOrDefault(0)
        if (count <= 0) {
            probe.recycle()
            return
        }
        val canvas = Canvas(bitmap)
        for (position in 0 until count) {
            val face = found[position] ?: continue
            val centre = PointF()
            face.getMidPoint(centre)
            val eyes = face.eyesDistance().coerceAtLeast(4f) / scale
            val size = (eyes * 4.4f).toInt().coerceAtLeast(16)
            val left = (centre.x / scale - size / 2f).roundToInt().coerceIn(0, bitmap.width - 2)
            val top = (centre.y / scale - size * 0.58f).roundToInt().coerceIn(0, bitmap.height - 2)
            val width = minOf(size, bitmap.width - left).coerceAtLeast(2)
            val height = minOf((size * 1.3f).toInt(), bitmap.height - top).coerceAtLeast(2)
            // createBitmap hands back the source itself when the rectangle is the
            // whole image, so never recycle what is still being drawn into.
            val crop = Bitmap.createBitmap(bitmap, left, top, width, height)
            val blurred = boxBlur(crop, (minOf(width, height) / 6).coerceIn(3, 18))
            canvas.drawBitmap(blurred, left.toFloat(), top.toFloat(), null)
            if (crop !== bitmap) crop.recycle()
            if (blurred !== crop) blurred.recycle()
        }
        probe.recycle()
    }

    private fun boxBlur(source: Bitmap, radius: Int): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val temp = IntArray(pixels.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var r = 0
                var g = 0
                var b = 0
                var n = 0
                var dx = -radius
                while (dx <= radius) {
                    val sx = (x + dx).coerceIn(0, width - 1)
                    val c = pixels[y * width + sx]
                    r += (c shr 16) and 0xFF
                    g += (c shr 8) and 0xFF
                    b += c and 0xFF
                    n++
                    dx++
                }
                temp[y * width + x] = Color.rgb(r / n, g / n, b / n)
            }
        }
        for (y in 0 until height) {
            for (x in 0 until width) {
                var r = 0
                var g = 0
                var b = 0
                var n = 0
                var dy = -radius
                while (dy <= radius) {
                    val sy = (y + dy).coerceIn(0, height - 1)
                    val c = temp[sy * width + x]
                    r += (c shr 16) and 0xFF
                    g += (c shr 8) and 0xFF
                    b += c and 0xFF
                    n++
                    dy++
                }
                pixels[y * width + x] = Color.rgb(r / n, g / n, b / n)
            }
        }
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, width, 0, 0, width, height)
        return out
    }

    private const val MAX_FACES = 8

    /**
     * Re-runs an effect on a picture already on disk, overwriting it. The shutter
     * sheet offers this so a photo taken in "Photo" can still get its faces blurred
     * before it goes anywhere.
     */
    fun applyToFile(file: File, mode: OeaCameraApp.CaptureMode, blurFaces: Boolean): Boolean {
        if (!file.isFile) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
        var sample = 1
        while (longest / (sample * 2) > SAVE_MAX) sample *= 2
        val decoded = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return false
        val working = decoded.copy(Bitmap.Config.ARGB_8888, true) ?: decoded
        if (working !== decoded) decoded.recycle()
        when (mode) {
            OeaCameraApp.CaptureMode.NIGHT -> night(working)
            OeaCameraApp.CaptureMode.PORTRAIT -> portrait(working)
            else -> Unit
        }
        if (blurFaces) blurFaces(working)
        val written = runCatching {
            FileOutputStream(file).use { out -> working.compress(Bitmap.CompressFormat.JPEG, 92, out) }
            true
        }.getOrDefault(false)
        working.recycle()
        return written
    }

    private const val SAVE_MAX = 2400

    /** A soft, flattering pass — used by the shutter sheet as a one-tap option. */
    fun soft(bitmap: Bitmap) {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val luma = (r * 299 + g * 587 + b * 114) / 1000
            pixels[i] = Color.rgb(
                mix(r, luma, -0.08f).coerceIn(0, 255),
                mix(g, luma, -0.04f).coerceIn(0, 255),
                mix(b, luma, 0.04f).coerceIn(0, 255)
            )
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    }
}
