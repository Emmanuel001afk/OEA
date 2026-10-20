package com.oea.launcher.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/**
 * OEA's picture pipeline — the part that decides whether a gallery feels instant
 * or feels like a queue of grey squares.
 *
 * Three rules make the difference, and an OEA app that shows your media follows
 * all three:
 *
 *  * **One decode per picture.** A memory cache sized from the heap plus a disk
 *    cache of finished tiles, so scrolling back never asks MediaStore again and a
 *    cold start after a reboot still fills the grid in one frame per tile.
 *  * **Nothing is applied to a row that scrolled away.** Every request is tagged
 *    on the view; when the tag no longer matches the result is dropped, so a fast
 *    flick cannot leave the wrong picture on the wrong tile.
 *  * **Real bitmaps at the right size.** Android 10+ uses
 *    `ContentResolver.loadThumbnail`, which returns an already-rotated, correctly
 *    scaled bitmap; API 26–29 fall back to a bounded decode for pictures and
 *    [MediaMetadataRetriever] for video frames instead of showing nothing.
 */
object OeaThumbnails {

    private val handler = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(
        Runtime.getRuntime().availableProcessors().coerceIn(3, 5),
        object : ThreadFactory {
            override fun newThread(r: Runnable): Thread =
                Thread(r, "OeaThumb").apply { priority = Thread.NORM_PRIORITY - 1 }
        }
    )

    private val memory: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(
        ((Runtime.getRuntime().maxMemory() / 8L).coerceAtMost(28L shl 20)).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private fun keyFor(uri: Uri, edge: Int, radius: Int): String = "${uri}#$edge#$radius"

    private fun diskFile(context: Context, key: String): File {
        val dir = File(context.cacheDir, "oea_thumbs").apply { if (!isDirectory) runCatching { mkdirs() } }
        diskRoot = dir
        val digest = runCatching {
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(key.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
        }.getOrElse { "${key.hashCode()}_${key.length}" }
        return File(dir, "$digest.webp")
    }

    /** The directory used by the disk cache in this process. */
    @Volatile
    private var diskRoot: File? = null

    /** Drops every cached picture — after a bulk edit, a delete or a rescan. */
    fun invalidateAll() {
        memory.evictAll()
        diskOf.values.toSet().forEach { file -> runCatching { file.delete() } }
        diskOf.clear()
        diskRoot?.listFiles()?.forEach { file -> runCatching { file.delete() } }
    }

    /** The disk file behind each cached key, so an edit can evict it for good. */
    private val diskOf = java.util.concurrent.ConcurrentHashMap<String, File>()

    /** Forgets one picture — after it was edited, favourited or deleted. */
    fun invalidate(uri: Uri) {
        val prefix = uri.toString() + "#"
        val doomed = memory.snapshot().keys.filter { it.startsWith(prefix) }
        doomed.forEach { key ->
            memory.remove(key)
            diskOf.remove(key)?.let { file -> runCatching { file.delete() } }
        }
        diskOf.entries.filterTo(mutableListOf()) { it.key.startsWith(prefix) }
            .forEach { entry ->
                runCatching { entry.value.delete() }
                diskOf.remove(entry.key)
            }
    }

    /** True when [view] still belongs to the row that asked for this tag. */
    fun stillWants(view: ImageView, tag: Any?): Boolean = view.tag == tag

    /** Clears the pending request of a recycled row. */
    fun cancel(view: ImageView) {
        view.tag = null
    }

    /**
     * Loads [uri] into [view], about [edge] pixels on the long edge.
     *
     * [video] grabs a frame instead of the image itself; [cornerRadiusPx] bakes
     * the rounding into the cached bitmap so a list never pays for an outline pass
     * per frame; [tag] identifies the row that owns the request.
     */
    fun load(
        view: ImageView,
        uri: Uri,
        edge: Int,
        video: Boolean = false,
        cornerRadiusPx: Int = 0,
        tag: Any? = null
    ) {
        val context = view.context.applicationContext
        val px = edge.coerceIn(48, 2048)
        val key = keyFor(uri, px, cornerRadiusPx)
        val want = tag ?: key
        view.tag = want
        memory.get(key)?.let { hit ->
            paintInto(view, context, hit)
            return
        }
        view.setImageDrawable(null)
        pool.execute {
            if (view.tag != want) return@execute
            val file = diskFile(context, key)
            var bitmap: Bitmap? = readDisk(file)
            if (bitmap == null) {
                val raw = decode(context, uri, px, video) ?: return@execute
                bitmap = if (cornerRadiusPx > 0) roundCorners(raw, cornerRadiusPx) else raw
                if (bitmap !== raw && !raw.isRecycled) raw.recycle()
                writeDisk(context, file, bitmap)
                diskOf[key] = file
                memory.put(key, bitmap)
            } else {
                diskOf[key] = file
                memory.put(key, bitmap)
            }
            val finished = bitmap
            handler.post {
                if (view.tag != want) return@post
                paintInto(view, context, finished)
            }
        }
    }

    /**
     * The big decode for a full-screen viewer. It bypasses the tile cache (a
     * 300 px tile blown up to screen size is the "too shallow" look itself) and
     * fades the real picture in over whatever is already showing.
     */
    fun loadFull(view: ImageView, uri: Uri, edge: Int, video: Boolean = false, onDone: ((Boolean) -> Unit)? = null) {
        val context = view.context.applicationContext
        val px = edge.coerceIn(600, 4096)
        val key = keyFor(uri, px, 0)
        val want = "full:$key"
        view.tag = want
        memory.get(key)?.let { hit ->
            view.setImageDrawable(BitmapDrawable(context.resources, hit))
            onDone?.invoke(true)
            return
        }
        pool.execute {
            val bitmap = decode(context, uri, px, video)
            if (bitmap != null) memory.put(key, bitmap)
            handler.post {
                if (view.tag != want) return@post
                if (bitmap != null) {
                    view.setImageDrawable(BitmapDrawable(context.resources, bitmap))
                    view.alpha = 0.35f
                    view.animate().alpha(1f).setDuration(180L)
                        .setInterpolator(android.view.animation.DecelerateInterpolator(1.5f)).start()
                }
                onDone?.invoke(bitmap != null)
            }
        }
    }

    /** Warms the cache for the tiles about to come on screen. */
    fun prefetch(context: Context, uris: List<Uri>, edge: Int, video: Boolean = false, radius: Int = 0) {
        if (uris.isEmpty()) return
        val app = context.applicationContext
        val px = edge.coerceIn(48, 2048)
        pool.execute {
            uris.take(16).forEach { uri ->
                val key = keyFor(uri, px, radius)
                if (memory.get(key) != null) return@forEach
                val file = diskFile(app, key)
                val disk = readDisk(file)
                if (disk == null) {
                    val raw = decode(app, uri, px, video) ?: return@forEach
                    val bitmap = if (radius > 0) roundCorners(raw, radius) else raw
                    if (bitmap !== raw && !raw.isRecycled) raw.recycle()
                    writeDisk(app, file, bitmap)
                    diskOf[key] = file
                    memory.put(key, bitmap)
                } else {
                    diskOf[key] = file
                    memory.put(key, disk)
                }
            }
        }
    }

    /** The cached bitmap is already the final shape, so this only paints it. */
    private fun paintInto(view: ImageView, context: Context, bitmap: Bitmap) {
        view.setImageDrawable(BitmapDrawable(context.resources, bitmap))
        if (view.alpha < 1f || view.drawable == null) {
            view.alpha = 0f
            view.animate().alpha(1f).setDuration(140L)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).start()
        }
    }

    private fun readDisk(file: File): Bitmap? {
        if (!file.isFile) return null
        val bitmap = runCatching {
            if (Build.VERSION.SDK_INT >= 28) {
                val source = android.graphics.ImageDecoder.createSource(file)
                try {
                    android.graphics.ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                        decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                        decoder.isMutableRequired = false
                    }
                } finally {
                    runCatching { (source as? java.io.Closeable)?.close() }
                }
            } else {
                android.graphics.BitmapFactory.decodeFile(file.absolutePath)
            }
        }.getOrNull()
        if (bitmap != null) return bitmap
        runCatching { file.delete() }
        return null
    }

    private fun writeDisk(context: Context, file: File, bitmap: Bitmap) {
        if (file.isFile) return
        runCatching {
            FileOutputStream(file).use { out ->
                val format = if (Build.VERSION.SDK_INT >= 30)
                    Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.JPEG
                bitmap.compress(format, 80, out)
            }
            trimDisk(context)
        }.onFailure { runCatching { file.delete() } }
    }

    /** Keeps the tile cache under 90 MB, oldest files first. */
    private fun trimDisk(context: Context) {
        runCatching {
            val files = File(context.cacheDir, "oea_thumbs").listFiles()?.sortedBy { it.lastModified() } ?: return@runCatching
            var total = files.sumOf { it.length() }
            var index = 0
            while (total > 90L shl 20 && index < files.size) {
                val file = files[index++]
                total -= file.length()
                runCatching { file.delete() }
            }
        }
    }

    private fun decode(context: Context, uri: Uri, px: Int, video: Boolean): Bitmap? {
        if (Build.VERSION.SDK_INT >= 29) {
            val thumbnail = runCatching {
                context.contentResolver.loadThumbnail(uri, android.util.Size(px, px), null)
            }.getOrNull()
            if (thumbnail != null) return thumbnail
        }
        return if (video) decodeFrame(context, uri, px) else decodeImage(context, uri, px)
    }

    private fun decodeImage(context: Context, uri: Uri, px: Int): Bitmap? = runCatching {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            android.graphics.BitmapFactory.decodeStream(stream, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= px && bounds.outHeight / (sample * 2) >= px) sample *= 2
        val options = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            android.graphics.BitmapFactory.decodeStream(stream, null, options)
        }
    }.getOrNull()

    private fun decodeFrame(context: Context, uri: Uri, px: Int): Bitmap? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: px
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: px
            val rotate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val scale = (px.toFloat() / maxOf(width, height)).coerceIn(0.05f, 1f)
            val frame = retriever.getFrameAtTime(1_500_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return@runCatching null
            val sized = if (scale >= 1f) frame
            else Bitmap.createScaledBitmap(
                frame,
                (frame.width * scale).toInt().coerceAtLeast(1),
                (frame.height * scale).toInt().coerceAtLeast(1),
                true
            ).also { if (it !== frame && !frame.isRecycled) frame.recycle() }
            val rotated = if (rotate % 360 == 0) sized else runCatching {
                val matrix = android.graphics.Matrix()
                matrix.postRotate(rotate.toFloat())
                Bitmap.createBitmap(sized, 0, 0, sized.width, sized.height, matrix, true)
            }.getOrDefault(sized)
            if (rotated !== sized && !sized.isRecycled) sized.recycle()
            rotated
        } finally {
            runCatching { if (Build.VERSION.SDK_INT >= 29) retriever.close() else retriever.release() }
        }
    }.getOrNull()

    /** Rounds a bitmap once so a list never pays for an outline pass per frame. */
    fun roundCorners(source: Bitmap, radiusPx: Int): Bitmap {
        if (radiusPx <= 0) return source
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = RectF(0f, 0f, source.width.toFloat(), source.height.toFloat())
        paint.color = Color.WHITE
        canvas.drawRoundRect(rect, radiusPx.toFloat(), radiusPx.toFloat(), paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(source, null, rect, paint)
        return result
    }
}
