package com.oea.launcher.gameboost

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OeaGameCaptureService : Service() {
    companion object {
        const val ACTION_START = "com.oea.launcher.gameboost.START_CAPTURE"
        const val ACTION_STOP = "com.oea.launcher.gameboost.STOP_CAPTURE"
        const val EXTRA_MODE = "mode"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL = "oea_game_capture"
        private const val NOTIFICATION_ID = 4108
    }

    private var projection: MediaProjection? = null
    private var display: android.hardware.display.VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var outputUri: android.net.Uri? = null
    private var outputDescriptor: android.os.ParcelFileDescriptor? = null
    private var projectionCallback: MediaProjection.Callback? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "OEA Game Capture", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) return START_NOT_STICKY
        val mode = intent.getStringExtra(EXTRA_MODE) ?: OeaGameCaptureActivity.MODE_SCREENSHOT
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
        val data: Intent = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java) ?: return START_NOT_STICKY
        } else {
            @Suppress("DEPRECATION")
            (intent.getParcelableExtra(EXTRA_RESULT_DATA) ?: return START_NOT_STICKY)
        }
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification(if (mode == OeaGameCaptureActivity.MODE_RECORD) "Recording game screen" else "Saving screenshot"), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else startForeground(NOTIFICATION_ID, notification("OEA Game Capture"))
        val manager = getSystemService(MediaProjectionManager::class.java)
        projection = runCatching { manager.getMediaProjection(resultCode, data) }.getOrNull()
        if (projection == null) {
            // Never leave the Game Boost UI stuck in a capture/recording state
            // when Android rejects or ends the projection before a session starts.
            getSharedPreferences("oea_game_boost", MODE_PRIVATE).edit()
                .putBoolean("recording", false)
                .putBoolean("capture_active", false)
                .apply()
            stopSelf()
            return START_NOT_STICKY
        }

        projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                stopCapture(stopProjection = false)
                stopSelf()
            }
        }
        projection?.registerCallback(projectionCallback!!, Handler(Looper.getMainLooper()))

        runCatching {
            if (mode == OeaGameCaptureActivity.MODE_RECORD) startRecording() else captureScreenshot()
        }.onFailure {
            stopCapture()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun size(): Pair<Int, Int> {
        val wm = getSystemService(WindowManager::class.java)
        val bounds = if (Build.VERSION.SDK_INT >= 30) wm.maximumWindowMetrics.bounds else {
            DisplayMetrics().also { @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(it) }
                .let { android.graphics.Rect(0, 0, it.widthPixels, it.heightPixels) }
        }
        return bounds.width() to bounds.height()
    }

    private fun density(): Int = resources.displayMetrics.densityDpi

    private fun recordingSize(): Pair<Int, Int> {
        val (sourceWidth, sourceHeight) = size()
        val scale = minOf(1f, 1920f / sourceWidth.toFloat(), 1080f / sourceHeight.toFloat())
        val width = ((sourceWidth * scale).toInt() and 1.inv()).coerceAtLeast(2)
        val height = ((sourceHeight * scale).toInt() and 1.inv()).coerceAtLeast(2)
        return width to height
    }

    private fun recordingSize(): Pair<Int, Int> {
        val (sourceWidth, sourceHeight) = size()
        val scale = minOf(1f, 1920f / sourceWidth.toFloat(), 1080f / sourceHeight.toFloat())
        val width = ((sourceWidth * scale).toInt() and 1.inv()).coerceAtLeast(2)
        val height = ((sourceHeight * scale).toInt() and 1.inv()).coerceAtLeast(2)
        return width to height
    }

    private fun startRecording() {
        // MediaRecorder and VirtualDisplay must use identical dimensions.
        // The previous implementation configured the encoder at <=1080p but
        // fed it a full-resolution VirtualDisplay, which can yield black video.
        val (width, height) = recordingSize()
        val dir = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "OEA")
        dir.mkdirs()
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "OEA_" + stamp() + ".mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/OEA")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            outputUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            if (outputUri == null) { stopCapture(); stopSelf(); return }
        } else {
            outputFile = File(dir, "OEA_" + stamp() + ".mp4")
        }

        recorder = MediaRecorder(this).apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoEncodingBitRate(8_000_000)
            setVideoFrameRate(30)
            setVideoSize(width, height)
            if (Build.VERSION.SDK_INT >= 29) {
                outputDescriptor = contentResolver.openFileDescriptor(outputUri!!, "w")
                setOutputFile(outputDescriptor!!.fileDescriptor)
            } else {
                setOutputFile(outputFile!!.absolutePath)
            }
            setOnErrorListener { _, _, _ ->
                getSharedPreferences("oea_game_boost", MODE_PRIVATE).edit().putBoolean("recording", false).apply()
                stopCapture()
                stopSelf()
            }
            prepare()
        }

        // Attach the recorder surface before starting the encoder so frames
        // have a live consumer from the first frame onward.
        display = projection!!.createVirtualDisplay(
            "OEA Game Record",
            width,
            height,
            density(),
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            recorder!!.surface,
            null,
            null
        )
        runCatching { recorder!!.start() }.onFailure {
            stopCapture()
            stopSelf()
            throw it
        }
    }

    private fun captureScreenshot() {
        val (width, height) = size()
        reader = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 3)
        display = projection!!.createVirtualDisplay("OEA Screenshot", width, height, density(), DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, null)
        val deadline = android.os.SystemClock.uptimeMillis() + 2500L
        fun tryRead() {
            val image = reader?.acquireLatestImage()
            if (image == null) {
                if (android.os.SystemClock.uptimeMillis() < deadline) {
                    Handler(Looper.getMainLooper()).postDelayed({ tryRead() }, 120L)
                } else {
                    Toast.makeText(this, "OEA could not capture the screen.", Toast.LENGTH_SHORT).show()
                    stopCapture()
                    stopSelf()
                }
                return
            }
            runCatching {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val rowStride = plane.rowStride
                val pixelStride = plane.pixelStride
                val rowPadding = rowStride - pixelStride * width
                val paddedWidth = width + rowPadding / pixelStride
                val padded = android.graphics.Bitmap.createBitmap(paddedWidth, height, android.graphics.Bitmap.Config.ARGB_8888)
                padded.copyPixelsFromBuffer(buffer)
                val cropped = android.graphics.Bitmap.createBitmap(padded, 0, 0, width, height)
                padded.recycle()
                image.close()
                val saved = saveScreenshot(cropped)
                cropped.recycle()
                if (!saved) throw IllegalStateException("Screenshot save failed")
                Toast.makeText(this, "Screenshot saved to Pictures/OEA", Toast.LENGTH_SHORT).show()
            }.onFailure {
                runCatching { image.close() }
                Toast.makeText(this, "OEA could not save the screenshot.", Toast.LENGTH_SHORT).show()
            }
            stopCapture()
            stopSelf()
        }
        Handler(Looper.getMainLooper()).postDelayed({ tryRead() }, 180L)
    }

    private fun saveScreenshot(bitmap: android.graphics.Bitmap): Boolean {
        val name = "OEA_" + stamp() + ".png"
        val resolver = contentResolver
        return runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/OEA")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return@runCatching false
                val wrote = resolver.openOutputStream(uri)?.use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                } == true
                if (!wrote) {
                    resolver.delete(uri, null, null)
                    return@runCatching false
                }
                val published = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                resolver.update(uri, published, null, null) > 0
            } else {
                val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), name)
                FileOutputStream(file).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                android.media.MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), arrayOf("image/png"), null)
                true
            }
        }.getOrDefault(false)
    }

    private fun stopCapture(stopProjection: Boolean = true) {
        val wasRecording = recorder != null
        val stopped = runCatching { recorder?.stop(); true }.getOrDefault(false)
        runCatching { recorder?.reset() }
        recorder = null
        display?.release(); display = null
        reader?.close(); reader = null
        val currentProjection = projection
        projection = null
        projectionCallback?.let { callback ->
            runCatching { currentProjection?.unregisterCallback(callback) }
        }
        projectionCallback = null
        if (stopProjection) runCatching { currentProjection?.stop() }
        if (Build.VERSION.SDK_INT >= 29 && outputUri != null) {
            if (wasRecording && stopped) {
                val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                runCatching { contentResolver.update(outputUri!!, values, null, null) }
            } else {
                runCatching { contentResolver.delete(outputUri!!, null, null) }
            }
        }
        runCatching { outputDescriptor?.close() }
        outputDescriptor = null
        outputUri = null
        getSharedPreferences("oea_game_boost", MODE_PRIVATE).edit().putBoolean("recording", false).putBoolean("capture_active", false).apply()
        getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    private fun notification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("OEA RAM")
            .setContentText(text)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", android.app.PendingIntent.getService(this, 1, Intent(this, OeaGameCaptureService::class.java).setAction(ACTION_STOP), android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT))
            .build()

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    override fun onDestroy() { stopCapture(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
