package com.oea.launcher.multitask.projection

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager

/**
 * Owns one virtual display and its pixel pipeline.
 *
 * Why a virtual display at all: Android does not let an ordinary app render
 * or capture a *background* application. The only no-root, no-ADB mechanism
 * that keeps the user's foreground app untouched while another real app stays
 * visible and interactive is to put the target application on an app-created
 * secondary display. Whatever renders on that display arrives here, frame by
 * frame, through a Surface we own.
 *
 * Creation is a flag ladder because two flag bits that help secondary-display
 * behaviour (SUPPORTS_TOUCH, OWN_FOCUS/OWN_DISPLAY_GROUP) are hidden or
 * permission-gated; each attempt is recorded so the diagnostics can say
 * exactly which flags this OEM accepted.
 *
 * This class never throws at callers: every failure becomes a [CreateResult]
 * with a human-readable reason.
 */
class OeaVirtualDisplayHost private constructor(
    val displayId: Int,
    val width: Int,
    val height: Int,
    val densityDpi: Int,
    private val virtualDisplay: VirtualDisplay,
    private val reader: ImageReader,
    val flagsUsed: Int,
    val flagsLabel: String
) {

    /** Notifications about the pipeline, delivered on [listenerHandler]. */
    interface Callback {
        fun onFrame() {}
        fun onDisplayStopped() {}
    }

    sealed class CreateResult {
        data class Created(val host: OeaVirtualDisplayHost) : CreateResult()
        data class Failed(val reason: String, val attempts: List<String>) : CreateResult()
    }

    /** Monotonic counters used for staleness and diagnostics. */
    @Volatile var framesReceived: Long = 0
        private set
    @Volatile var lastFrameUptimeMs: Long = 0
        private set
    @Volatile private var released = false

    /**
     * Records that one frame reached the presenter. Called by the session:
     * either from the TextureView's render callback (texture mode) or after
     * a successful ImageReader poll (reader mode). A single consumer owns
     * the buffers, so the queue never stalls.
     */
    fun noteFrame() {
        framesReceived++
        lastFrameUptimeMs = SystemClock.uptimeMillis()
    }

    /** The reader's surface: the virtual display can always render into it. */
    fun surface(): Surface? = if (released) null else reader.surface

    /**
     * Points the virtual display at a different surface without tearing it
     * down (for example a TextureView's SurfaceTexture for zero-copy mode).
     * Returns false if the swap failed; the reader stays usable afterwards.
     */
    fun swapSurface(target: Surface?): Boolean {
        if (released) return false
        return try {
            virtualDisplay.surface = target
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Grabs the newest frame as a bitmap (a software copy of the hardware
     * buffer). Returns null when no frame is pending. Used by the reader-mode
     * presenter and by the diagnostics to prove frames really flow.
     */
    fun acquireLatestBitmap(): android.graphics.Bitmap? {
        if (released) return null
        val image = try {
            reader.acquireLatestImage()
        } catch (_: Throwable) {
            null
        } ?: return null
        return try {
            val buffer = image.planes.firstOrNull()?.buffer ?: return image.close().let { null }
            val pixelStride = image.planes.first().pixelStride
            val rowStride = image.planes.first().rowStride
            val rowPadding = rowStride - pixelStride * width
            val bitmapWidth = width + rowPadding / pixelStride
            val bitmap = android.graphics.Bitmap.createBitmap(
                bitmapWidth, height, android.graphics.Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            if (bitmapWidth != width) {
                // Crop the row-stride padding away.
                android.graphics.Bitmap.createBitmap(bitmap, 0, 0, width, height)
            } else {
                bitmap
            }
        } catch (t: Throwable) {
            null
        } finally {
            try {
                image.close()
            } catch (_: Throwable) {
            }
        }
    }

    // ---------------------------------------------------------------- resize

    fun resize(newWidth: Int, newHeight: Int): Boolean {
        if (released) return false
        return try {
            virtualDisplay.resize(newWidth, newHeight, densityDpi)
            true
        } catch (_: Throwable) {
            false
        }
    }

    // ---------------------------------------------------------------- release

    /**
     * Releases the display and its buffers. Because the display is created
     * with DESTROY_CONTENT_ON_REMOVAL where the OEM allows it, activities
     * that live on it are destroyed instead of leaking onto the phone.
     */
    fun release() {
        if (released) return
        released = true
        try {
            reader.setOnImageAvailableListener(null, null)
        } catch (_: Throwable) {
        }
        try {
            virtualDisplay.release()
        } catch (_: Throwable) {
        }
        try {
            reader.close()
        } catch (_: Throwable) {
        }
    }

    override fun toString(): String =
        "OeaVirtualDisplayHost(id=$displayId, ${width}x${height}@$densityDpi, flags=$flagsLabel)"

    companion object {
        private const val TAG = "OeaProjectionVD"

        /**
         * Virtual display flag bits (values verified against
         * android.hardware.display.DisplayManager, Android 13).
         * SUPPORTS_TOUCH (1<<6) is @hide but not permission-gated; the
         * permission-gated bits (TRUSTED 1<<10, OWN_DISPLAY_GROUP 1<<11,
         * ALWAYS_UNLOCKED 1<<12) are never requested — the platform rejects
         * them for ordinary apps and the ladder must stay honest.
         */
        private const val FLAG_PUBLIC = 1 shl 0
        private const val FLAG_PRESENTATION = 1 shl 1
        private const val FLAG_OWN_CONTENT_ONLY = 1 shl 3
        private const val FLAG_SUPPORTS_TOUCH = 1 shl 6
        private const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8

        /**
         * Suggested display geometry for the target device: portrait, no
         * wider than 720px so a Go device is not forced to push oversized
         * buffers, and never smaller than 320px so apps do not fall back to
         * tablet compatibility layouts.
         */
        fun suggestSpec(context: Context): Triple<Int, Int, Int> {
            val metrics: DisplayMetrics = if (android.os.Build.VERSION.SDK_INT >= 30) {
                val wm = context.getSystemService(WindowManager::class.java)
                wm?.currentWindowMetrics?.let { m ->
                    DisplayMetrics().apply {
                        widthPixels = m.bounds.width()
                        heightPixels = m.bounds.height()
                        densityDpi = context.resources.displayMetrics.densityDpi
                    }
                } ?: context.resources.displayMetrics
            } else {
                context.resources.displayMetrics
            }
            var width = metrics.widthPixels.coerceIn(320, 720)
            var height = (width * (metrics.heightPixels.toFloat() / metrics.widthPixels.toFloat())).toInt()
            if (height <= width) height = (width * 16f / 9f).toInt()
            height = height.coerceIn(480, 1600)
            val dpi = metrics.densityDpi.coerceIn(120, 480)
            return Triple(width, height, dpi)
        }

        /**
         * Creates a virtual display backed by an ImageReader. Every flag set
         * that was tried is recorded, whether it failed or succeeded, so the
         * Stage-B diagnostics can show exactly what this OEM allows.
         */
        fun create(context: Context, name: String): CreateResult {
            val (width, height, dpi) = suggestSpec(context)
            if (width <= 0 || height <= 0) {
                return CreateResult.Failed("Invalid display size ${width}x${height}.", emptyList())
            }
            val reader = try {
                ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
            } catch (t: Throwable) {
                return CreateResult.Failed(
                    "Could not allocate a ${width}x${height} surface: ${t.message ?: t.javaClass.simpleName}",
                    emptyList()
                )
            }
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
                ?: run {
                    reader.close()
                    return CreateResult.Failed("DisplayManager is unavailable.", emptyList())
                }

            // Richest flag set first; each failure narrows the request.
            val ladder = listOf(
                "public+presentation+own_content+touch+destroy" to
                    (FLAG_PUBLIC or FLAG_PRESENTATION or FLAG_OWN_CONTENT_ONLY or
                        FLAG_SUPPORTS_TOUCH or FLAG_DESTROY_CONTENT_ON_REMOVAL),
                "public+presentation+own_content+touch" to
                    (FLAG_PUBLIC or FLAG_PRESENTATION or FLAG_OWN_CONTENT_ONLY or FLAG_SUPPORTS_TOUCH),
                "public+presentation+own_content" to
                    (FLAG_PUBLIC or FLAG_PRESENTATION or FLAG_OWN_CONTENT_ONLY),
                "public+own_content" to (FLAG_PUBLIC or FLAG_OWN_CONTENT_ONLY),
                "private" to 0
            )
            val attempts = mutableListOf<String>()
            for ((label, flags) in ladder) {
                try {
                    val vd = dm.createVirtualDisplay(name, width, height, dpi, reader.surface, flags)
                    if (vd != null) {
                        attempts += "$label: ok (display ${vd.display.displayId})"
                        return CreateResult.Created(
                            OeaVirtualDisplayHost(
                                vd.display.displayId, width, height, dpi, vd, reader, flags, label
                            )
                        )
                    }
                    attempts += "$label: returned null"
                } catch (se: SecurityException) {
                    attempts += "$label: SecurityException ${se.message}"
                } catch (t: Throwable) {
                    attempts += "$label: ${t.javaClass.simpleName} ${t.message}"
                }
            }
            reader.close()
            return CreateResult.Failed(
                "This device refused to create a virtual display (some manufacturers cap or block them for ordinary apps).",
                attempts
            )
        }
    }
}
