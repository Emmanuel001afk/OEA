package com.oea.launcher.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.oea.launcher.camera.OeaCameraOptics
import java.io.File
import java.io.InputStream

/**
 * EXIF orientation, in one place.
 *
 * OEA's camera now saves the camera's own JPEG untouched and records the rotation
 * in the EXIF tag, so a portrait still is neither re-encoded nor sampled down to a
 * working size (see `docs/camera-review-2026-11-23.md`). `BitmapFactory` and every
 * decoder that reads a stream directly ignore that tag, which would draw those
 * pictures on their side in OEA's own gallery, full-screen viewer, editor, home
 * widget and wallpaper — so every one of those paths has to turn the tag back into
 * pixels here, once, instead of each guessing.
 *
 * Pictures whose pixels were already turned (an older OEA photo, a filtered shot,
 * anything from another camera) carry tag 1 and pass through untouched.
 */
object OeaPhotoDecode {

    /** ExifInterface.ORIENTATION_NORMAL, named so callers do not import the class for it. */
    const val NORMAL: Int = ExifInterface.ORIENTATION_NORMAL

    /** Tag 2, 4, 5 and 7 mirror as well as rotate; 5 and 7 are the transposed pair. */
    fun needsTurn(tag: Int): Boolean =
        OeaCameraOptics.rotationForOrientationTag(tag) != 0 || OeaCameraOptics.orientationTagMirrors(tag)

    /** The EXIF orientation tag behind [uri], or "normal" when it has none. */
    fun tagOf(context: Context, uri: Uri): Int = runCatching {
        context.contentResolver.openInputStream(uri)?.use { tagOf(it) } ?: ExifInterface.ORIENTATION_NORMAL
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    /** The EXIF orientation tag written in [file], or "normal". */
    fun tagOf(file: File): Int = runCatching {
        ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    /** The EXIF orientation tag at the head of [stream], or "normal". */
    fun tagOf(stream: InputStream): Int =
        ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)

    /** The EXIF orientation tag of the bytes [source] hands out, or [NORMAL]. */
    fun tagOfSource(source: () -> InputStream?): Int = runCatching {
        source()?.use { tagOf(it) } ?: NORMAL
    }.getOrDefault(NORMAL)

    /** [bitmap], turned to match the tag on [uri]. */
    fun upright(context: Context, uri: Uri, bitmap: Bitmap): Bitmap = upright(bitmap, tagOf(context, uri))

    /**
     * Returns [bitmap] upright.
     *
     * The same instance comes back when the tag is normal. Otherwise a new bitmap is
     * returned and [bitmap] is recycled: the caller is mid-decode and cannot hold a
     * 12-megapixel frame twice. Mirrored tags are mirrored horizontally, which is
     * the only mirror the camera itself writes and the one a selfie expects;
     * vertical-mirror tags (4) are treated as their rotation, not as a flip.
     */
    fun upright(bitmap: Bitmap, tag: Int): Bitmap {
        if (bitmap.isRecycled || !needsTurn(tag)) return bitmap
        return runCatching {
            val rotation = OeaCameraOptics.rotationForOrientationTag(tag)
            val mirror = OeaCameraOptics.orientationTagMirrors(tag)
            val matrix = Matrix().apply {
                if (rotation != 0) postRotate(rotation.toFloat())
                // Tag 4 is the vertical mirror; turning it round is the common case
                // and a horizontal flip would be the wrong one, so it rotates only.
                if (mirror && tag != ExifInterface.ORIENTATION_FLIP_VERTICAL) postScale(-1f, 1f)
            }
            val turned = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (turned !== bitmap && !bitmap.isRecycled) bitmap.recycle()
            turned
        }.getOrDefault(bitmap)
    }
}
