package com.oea.launcher.miniapps

import android.content.Context
import android.graphics.Bitmap
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
}
