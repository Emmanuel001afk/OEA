package com.oea.launcher.miniapps

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * The frames drawn under the scrubber, and the one thread allowed to touch them.
 *
 * The retriever this replaces was opened on the UI thread — a file open plus a
 * container parse, right on the frame playback starts, which is the one moment the
 * player cannot afford a hitch — and then read from a preview thread while the next
 * clip, or `onDestroy`, released it from the main one. A `MediaMetadataRetriever`
 * is not safe for that: a release that lands mid-grab loses the preview in the best
 * case and takes the native decoder down in the worst.
 *
 * Here every call — open, grab, release — runs on one owned thread, so the object
 * has a single owner for its whole life and no caller ever waits on a file.
 */
internal class OeaVipFrames {

    private val thread = Executors.newSingleThreadExecutor { task ->
        Thread(task, "OeaVipFrames").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var closed = false
    private var retriever: MediaMetadataRetriever? = null

    /** Opens [uri] for grabs. Returns at once; the file is read on the frame thread. */
    fun open(context: Context, uri: Uri) {
        if (closed) return
        val app = context.applicationContext
        runCatching {
            thread.execute {
                releaseOnThread()
                if (closed) return@execute
                retriever = runCatching {
                    MediaMetadataRetriever().apply { setDataSource(app, uri) }
                }.getOrNull()
            }
        }
    }

    /**
     * The synchronised frame at [atMs], handed back on the main thread.
     *
     * [callback] is not called at all once the window is closed, so a grab that
     * finishes after the player is gone cannot resurrect a dead view — the caller's
     * own sequence token still decides whether the bitmap is wanted.
     */
    fun frame(atMs: Long, width: Int, height: Int, callback: (Bitmap?) -> Unit) {
        if (closed) return
        val wanted = width.coerceAtLeast(1) to height.coerceAtLeast(1)
        runCatching {
            thread.execute {
                val grabber = retriever
                if (closed || grabber == null) return@execute
                val bitmap = runCatching {
                    if (Build.VERSION.SDK_INT >= 27) {
                        grabber.getScaledFrameAtTime(
                            atMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, wanted.first, wanted.second
                        )
                    } else {
                        grabber.getFrameAtTime(atMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    }
                }.getOrNull()
                main.post { if (!closed) callback(bitmap) }
            }
        }
    }

    /**
     * A square cover for the notification and the lock screen: the file's own album
     * art when it carries one, otherwise a frame from [atMs] into the clip.
     *
     * Decoding happens on the frame thread with a sample size, so a 4000-pixel cover
     * inside an audio file never allocates the full bitmap on the way to a 512-pixel
     * icon — an OOM in the middle of playback is a worse outcome than no artwork.
     */
    fun artwork(size: Int, atMs: Long, callback: (Bitmap?) -> Unit) {
        if (closed) return
        val target = size.coerceAtLeast(64)
        runCatching {
            thread.execute {
                val grabber = retriever
                if (closed || grabber == null) return@execute
                val bitmap = runCatching { decode(grabber.embeddedPicture, target) }.getOrNull()
                    ?: runCatching { grabFrame(grabber, atMs, target) }.getOrNull()
                main.post { if (!closed) callback(bitmap) }
            }
        }
    }

    private fun grabFrame(grabber: MediaMetadataRetriever, atMs: Long, target: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= 27) {
            return grabber.getScaledFrameAtTime(
                atMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, target, target
            )
        }
        val raw = grabber.getFrameAtTime(atMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
        return shrink(raw, target)
    }

    private fun decode(bytes: ByteArray?, target: Int): Bitmap? {
        if (bytes == null || bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, target)
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        return shrink(decoded, target)
    }

    private fun shrink(bitmap: Bitmap, target: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= target) return bitmap
        val scale = target.toFloat() / longest
        return runCatching {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true
            )
        }.getOrDefault(bitmap)
    }

    /** Releases the retriever on its own thread and refuses all later work. */
    fun close() {
        if (closed) return
        closed = true
        runCatching { thread.execute { releaseOnThread() } }
        runCatching { thread.shutdown() }
    }

    private fun releaseOnThread() {
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) retriever?.close() else retriever?.release()
        }
        retriever = null
    }

    companion object {
        /**
         * The power-of-two sample factor that brings an image's long side down to
         * [target] before it is decoded. Pure arithmetic, so it is unit-tested
         * without a device (`OeaVipFramesTest`).
         */
        internal fun sampleSize(width: Int, height: Int, target: Int): Int {
            if (width <= 0 || height <= 0 || target <= 0) return 1
            var sample = 1
            val longest = maxOf(width, height)
            while (longest / sample > target) sample *= 2
            return sample
        }
    }
}
