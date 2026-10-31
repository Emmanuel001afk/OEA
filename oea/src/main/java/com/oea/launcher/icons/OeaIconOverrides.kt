package com.oea.launcher.icons

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Per-app icon customisation: one optional override per launcher component.
 *
 * An override is purely cosmetic — the launcher keeps launching the exact
 * same component; only the drawable OEA renders changes. Three kinds:
 *
 *  - `system`            always use the app's own real icon (ignores any
 *                         selected icon pack for this app);
 *  - `pack:<drawable>`   use one specific drawable from the selected icon
 *                         pack (picked by hand in the icon picker);
 *  - `image:<file>`      use a picture the user chose, stored privately in
 *                         OEA's filesDir and masked like a normal icon.
 *
 * Stored in its own preferences file so the launcher's main settings and the
 * workspace layout are never touched by icon play, mirroring how the call
 * blocker keeps rules and history apart.
 */
object OeaIconOverrides {

    private const val PREFS = "oea_icon_overrides"
    private const val DIR = "oea_icon_overrides"

    /** Marker override value: use the app's real icon even when a pack is selected. */
    const val SYSTEM = "system"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(packageName: String, className: String?) =
        "$packageName/${className.orEmpty()}"

    /** The override spec for one launcher component, or null for "default look". */
    fun spec(context: Context, packageName: String, className: String?): String? =
        prefs(context).getString(key(packageName, className), null)

    /** Records (or with null clears) the override for one component. */
    fun setSpec(context: Context, packageName: String, className: String?, value: String?) {
        val k = key(packageName, className)
        val old = prefs(context).getString(k, null)
        val editor = prefs(context).edit()
        if (value.isNullOrBlank()) editor.remove(k) else editor.putString(k, value)
        editor.apply()
        // A replaced or cleared picture override leaves its file behind otherwise.
        if (old != null && old.startsWith(PREFIX_IMAGE) && old != value) deleteImage(context, old)
        changes++
    }

    /**
     * Bumped on every change, so Home can detect "an icon was changed while a
     * picker screen was in front" on resume and re-render its tiles.
     */
    fun version(): Int = changes

    @Volatile private var changes = 0

    /**
     * Copies a user-chosen picture into OEA's private storage and returns the
     * `image:<file>` spec for it, or null when the picture could not be read.
     * Every pick writes a NEW file: an applied override keeps pointing at the
     * exact picture it was saved with until a new spec replaces it, so backing
     * out of the picker can never silently change an applied icon.
     */
    fun saveImage(context: Context, packageName: String, className: String?, uri: Uri): String? {
        val digest = runCatching {
            MessageDigest.getInstance("SHA-1").digest(key(packageName, className).toByteArray())
                .joinToString("") { "%02x".format(it) }.take(20)
        }.getOrNull() ?: return null
        val name = "img_${digest}_${System.currentTimeMillis()}.png"
        val dir = File(context.applicationContext.filesDir, DIR)
        if (!runCatching { if (!dir.isDirectory) dir.mkdirs(); true }.getOrDefault(false)) return null
        val target = File(dir, name)
        val ok = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                // Decode bounds first: a 48-megapixel gallery photo must not be
                // kept at full size for something rendered at ~60dp.
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(input, null, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching false
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= 256 && bounds.outHeight / (sample * 2) >= 256) sample *= 2
                val bitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply { inSampleSize = sample })
                } ?: return@runCatching false
                val square = squareCrop(bitmap)
                FileOutputStream(target).use { out ->
                    square.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                if (square !== bitmap) bitmap.recycle()
                true
            } ?: false
        }.getOrDefault(false)
        return if (ok) "$PREFIX_IMAGE$name" else null
    }

    /** Center-crops a bitmap to a square so the shape mask never stretch it. */
    private fun squareCrop(source: Bitmap): Bitmap {
        val side = minOf(source.width, source.height)
        if (source.width == source.height) return source
        return Bitmap.createBitmap(
            source,
            (source.width - side) / 2,
            (source.height - side) / 2,
            side,
            side
        )
    }

    /** The stored picture file behind an `image:<file>` spec, or null if gone. */
    fun imageFile(context: Context, spec: String): File? {
        val name = spec.removePrefix(PREFIX_IMAGE)
        // Never trust stored strings as paths.
        if (spec.length <= PREFIX_IMAGE.length || name.isEmpty() || !name.matches(Regex("[A-Za-z0-9_.-]+"))) return null
        val file = File(File(context.applicationContext.filesDir, DIR), name)
        return if (file.isFile) file else null
    }

    /** Last-modified stamp of a picture, so caches key on the real bytes. */
    fun imageStamp(context: Context, spec: String): Long = imageFile(context, spec)?.lastModified() ?: 0L

    private fun deleteImage(context: Context, spec: String) {
        if (!spec.startsWith(PREFIX_IMAGE)) return
        runCatching { imageFile(context, spec)?.delete() }
    }

    /**
     * Removes a stored picture that was never applied to a component (the
     * picker was backed out of). Specs that ARE applied are never touched by
     * this — only the picker calls it with its un-applied candidate.
     */
    fun deleteUnapplied(context: Context, spec: String) {
        // Only ever delete files this object created, for the calling component,
        // and only when no component references the spec anymore.
        val referenced = prefs(context).all.values.any { it == spec }
        if (!referenced) deleteImage(context, spec)
    }

    private const val PREFIX_IMAGE = "image:"
    private const val PREFIX_PACK = "pack:"
}
