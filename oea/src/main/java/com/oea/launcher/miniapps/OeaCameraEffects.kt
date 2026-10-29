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
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/**
 * The two Camera modes that change pixels instead of only changing the label.
 *
 * OEA has no native code and no machine-learning model, so it does not pretend
 * otherwise: "Night" is a real shadow-lifting tone curve with a light denoise in
 * the dark areas only, and "Portrait" gently softens outside detected face-derived head/torso envelopes.
 * It is an approximation, not a depth or person segmentation model. "Blur faces" runs Android's own
 * [FaceDetector] over the picture and smudges what it finds, which is what you
 * want before sending a photo of a street to anyone.
 *
 * "Document" is the fourth pass and it is also the one that earns its name: the
 * frame is flattened to luminance, its black and white points are taken from its
 * own histogram (2nd and 98th percentile, so one bright window or one dark chair
 * cannot wash out the page), and a mild gamma pushes the paper towards white while
 * keeping the ink. That is a real scan, not a filter on top of a photo.
 *
 * Everything runs on a copy capped at [WORK_MAX] pixels on the long edge, so a
 * 108-megapixel file cannot turn the shutter into a two-second wait.
 */
internal object OeaCameraEffects {

    private const val WORK_MAX = 1600

    /** True when this picture needs any work at all, so the shutter stays instant. */
    fun needed(mode: OeaCameraApp.CaptureMode, blurFaces: Boolean): Boolean =
        blurFaces ||
            mode == OeaCameraApp.CaptureMode.NIGHT ||
            mode == OeaCameraApp.CaptureMode.PORTRAIT ||
            mode == OeaCameraApp.CaptureMode.DOC

    /**
     * Returns a new bitmap, or the same instance when nothing applied. The caller
     * owns both, and never shows a half-processed frame: this is a pure function on
     * pixels.
     */
    fun process(
        source: Bitmap,
        mode: OeaCameraApp.CaptureMode,
        blurFaces: Boolean
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
            OeaCameraApp.CaptureMode.PORTRAIT -> portrait(mutable)
            OeaCameraApp.CaptureMode.DOC -> document(mutable)
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
        val curved = if (v < 0.5f) 2f * v * v else (1f - (2f - 2f * v) * (2f - 2f * v) / 2f)
        return (curved * 255f).toInt()
    }

    private fun mix(from: Int, to: Int, amount: Float): Int = (from + (to - from) * amount).toInt().coerceIn(0, 255)

    /** Gentle face-guided approximation, not a person/depth segmentation mask.
     * Keeps detected head/torso envelopes sharp; leaves undetected scenes alone.
     */
    private fun portrait(bitmap: Bitmap) {
        // A face-derived head/torso envelope is intentionally conservative. It
        // cannot segment hair or limbs; if detection fails, keep the original.
        val probeWidth = minOf(640, bitmap.width).let { it - it % 2 }.coerceAtLeast(2)
        val scale = probeWidth.toFloat() / bitmap.width
        val probeHeight = (bitmap.height * scale).toInt().coerceAtLeast(2)
        val probe = Bitmap.createBitmap(probeWidth, probeHeight, Bitmap.Config.RGB_565)
        Canvas(probe).drawBitmap(bitmap, null, android.graphics.Rect(0, 0, probeWidth, probeHeight), null)
        val faces = arrayOfNulls<FaceDetector.Face>(MAX_FACES)
        val count = try {
            FaceDetector(probeWidth, probeHeight, MAX_FACES).findFaces(probe, faces)
        } finally { probe.recycle() }
        val envelopes = (0 until count).mapNotNull { index ->
            faces[index]?.takeIf { it.confidence() >= 0.4f }?.let { face ->
                val center = PointF()
                face.getMidPoint(center)
                floatArrayOf(center.x / scale, center.y / scale, face.eyesDistance() / scale)
            }
        }
        if (envelopes.isEmpty()) return
        val blurred = boxBlur(bitmap, 10)
        val pixels = IntArray(bitmap.width * bitmap.height)
        val soft = IntArray(pixels.size)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        blurred.getPixels(soft, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        blurred.recycle()
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            var keep = 0f
            for (face in envelopes) {
                val eyes = face[2].coerceAtLeast(4f)
                // Protect the head AND torso, rather than a horizontal sharp band.
                val head = kotlin.math.hypot((x-face[0])/(eyes*2.2f), (y-face[1])/(eyes*3f))
                val torso = kotlin.math.hypot((x-face[0])/(eyes*3.8f), (y-face[1]-eyes*6f)/(eyes*6f))
                val distance = minOf(head, torso)
                val t = ((distance - 0.85f) / 0.45f).coerceIn(0f, 1f)
                keep = maxOf(keep, 1f - t*t*(3f-2f*t))
            }
            val i = y * bitmap.width + x
            val amount = (1f-keep)*0.65f
            val original = pixels[i]
            val blurredPixel = soft[i]
            pixels[i] = Color.rgb(
                mix((original shr 16) and 255, (blurredPixel shr 16) and 255, amount),
                mix((original shr 8) and 255, (blurredPixel shr 8) and 255, amount),
                mix(original and 255, blurredPixel and 255, amount))
        }
        bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    /**
     * Flattens the frame into something that looks like paper.
     *
     * The black and white points come from the picture's own luminance histogram
     * rather than from fixed numbers: the 2nd percentile becomes black and the
     * 98th becomes white, so a page shot under a desk lamp and one shot in daylight
     * both come out with a white background and readable ink. A concave curve
     * (gamma 0.85) is applied after the stretch so paper lifts to pure white and
     * dark ink keeps its edges. Colour
     * is thrown away on purpose — a scan of a page is a page, and keeping the
     * yellow of indoor lighting would only make it look like a photo again.
     */
    private fun document(bitmap: Bitmap) {
        val width = bitmap.width
        val height = bitmap.height
        val count = width * height
        val pixels = IntArray(count)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val histogram = IntArray(256)
        val luma = IntArray(count)
        for (i in 0 until count) {
            val c = pixels[i]
            val y = (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
            luma[i] = y
            histogram[y]++
        }
        val lowCut = (count * 2) / 100
        val highCut = count - lowCut
        var running = 0
        var black = 0
        var white = 255
        for (value in 0..255) {
            running += histogram[value]
            if (running >= lowCut && black == 0) black = value
            if (running >= highCut) {
                white = value
                break
            }
        }
        // A frame with almost no contrast (a blank wall) must not be blown up into
        // noise: a minimum span keeps the stretch harmless.
        if (white - black < 24) {
            black = (black - 12).coerceAtLeast(0)
            white = (white + 12).coerceAtMost(255)
        }
        val span = (white - black).coerceAtLeast(1)
        val curve = IntArray(256)
        for (value in 0..255) {
            val stretched = ((value - black) * 255 / span).coerceIn(0, 255) / 255f
            // Concave curve: near-white becomes paper-white, ink keeps its edge.
            curve[value] = if (stretched > 0.96f) 255
            else (Math.pow(stretched.toDouble(), 0.85) * 255).roundToInt().coerceIn(0, 255)
        }
        for (i in 0 until count) {
            val v = curve[luma[i]]
            pixels[i] = Color.rgb(v, v, v)
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
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
        val probeWidth = (bitmap.width * scale).toInt().let { it - it % 2 }.coerceAtLeast(16)
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
        // The file's pixels are stored the way the sensor wrote them; the EXIF tag
        // is what turns them. A pass that ignores the tag works sideways — the
        // Portrait band lands across the picture instead of down it — so the pixels
        // are turned upright first and the tag is reset afterwards.
        val tag = runCatching {
            ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val rotation = com.oea.launcher.camera.OeaCameraOptics.rotationForOrientationTag(tag)
        val mirrored = com.oea.launcher.camera.OeaCameraOptics.orientationTagMirrors(tag)
        val upright = if (rotation != 0 || mirrored) {
            runCatching {
                val matrix = Matrix().apply {
                    if (rotation != 0) postRotate(rotation.toFloat())
                    if (mirrored) postScale(-1f, 1f)
                }
                Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also {
                    if (it !== decoded) decoded.recycle()
                }
            }.getOrDefault(decoded)
        } else {
            decoded
        }
        val working = upright.copy(Bitmap.Config.ARGB_8888, true) ?: upright
        if (working !== upright) upright.recycle()
        when (mode) {
            OeaCameraApp.CaptureMode.NIGHT -> night(working)
            OeaCameraApp.CaptureMode.PORTRAIT -> portrait(working)
            OeaCameraApp.CaptureMode.DOC -> document(working)
            else -> Unit
        }
        if (blurFaces) blurFaces(working)
        val written = runCatching {
            FileOutputStream(file).use { out -> working.compress(Bitmap.CompressFormat.JPEG, 92, out) }
            true
        }.getOrDefault(false)
        working.recycle()
        if (written && (rotation != 0 || mirrored)) {
            // The pixels now carry the turn, so the tag must not apply it twice.
            runCatching {
                ExifInterface(file.absolutePath).apply {
                    setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                    saveAttributes()
                }
            }
        }
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
