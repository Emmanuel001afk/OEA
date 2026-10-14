package com.oea.engine.net.qr

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import com.oea.engine.core.OeaGuard
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max

/** QR encoding and decoding (ZXing), for invite/reply codes and LAN join codes. */
object QrCodes {
    /**
     * Renders [text] as a crisp QR bitmap about [sizePx] wide (integer module
     * scaling, so modules never blur). Medium error correction tolerates
     * glare and screenshots.
     */
    fun encode(text: String, sizePx: Int, dark: Int = Color.BLACK, light: Int = Color.WHITE, quietModules: Int = 2): Bitmap {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to quietModules,
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        val n = matrix.width
        val scale = max(1, sizePx / n)
        val size = n * scale
        val pixels = IntArray(size * size)
        for (y in 0 until n) for (x in 0 until n) {
            val c = if (matrix.get(x, y)) dark else light
            for (dy in 0 until scale) {
                val row = (y * scale + dy) * size + x * scale
                for (dx in 0 until scale) pixels[row + dx] = c
            }
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    private fun reader() = MultiFormatReader().apply {
        setHints(mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true,
        ))
    }

    /** Decodes a QR from a picture (screenshot, photo). Tries a few scales and inverted colours. */
    fun decode(bitmap: Bitmap): String? {
        val attempts = ArrayList<Bitmap>()
        attempts += bitmap
        val longest = max(bitmap.width, bitmap.height)
        if (longest > 1600) attempts += Bitmap.createScaledBitmap(bitmap, bitmap.width * 1600 / longest, bitmap.height * 1600 / longest, true)
        if (longest > 900) attempts += Bitmap.createScaledBitmap(bitmap, bitmap.width * 900 / longest, bitmap.height * 900 / longest, true)
        for (b in attempts) {
            val px = IntArray(b.width * b.height)
            b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
            val src = RGBLuminanceSource(b.width, b.height, px)
            decode(src)?.let { return it }
            decode(src.invert())?.let { return it }
        }
        return null
    }

    internal fun decode(source: LuminanceSource): String? = try {
        reader().decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
    } catch (_: Exception) {
        null
    }
}

/**
 * Camera QR scanner (Camera2 preview + ZXing on a worker). Needs the CAMERA
 * permission before [start]. [onResult] fires once on the main thread.
 */
class QrScannerView(context: Context) : FrameLayout(context) {
    var onResult: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    /** Optional filter: return false to keep scanning (e.g. ignore QR codes that aren't OEA codes). */
    var accept: (String) -> Boolean = { true }

    private val texture = TextureView(context)
    private val finder = Viewfinder(context)
    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var request: CaptureRequest.Builder? = null
    private var previewSize: Size? = null
    private var sensorOrientation = 90
    private val decoder = OeaGuard.pool("qr", 1, "oea-qr-decode")
    private val busy = AtomicBoolean(false)
    private val done = AtomicBoolean(false)
    private var running = false
    var torch = false
        private set

    init {
        addView(texture, LayoutParams(-1, -1))
        addView(finder, LayoutParams(-1, -1))
        setBackgroundColor(Color.BLACK)
    }

    fun start() {
        if (running) return
        running = true
        done.set(false)
        thread = HandlerThread("oea-qr-camera").apply {
            setUncaughtExceptionHandler { t, e -> OeaGuard.report("qr-camera", e, fatal = true) }
        }.also { it.start() }
        handler = Handler(thread!!.looper)
        if (texture.isAvailable) open() else texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) { if (running) open() }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = applyTransform()
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
        finder.animateLine(true)
    }

    fun stop() {
        running = false
        finder.animateLine(false)
        runCatching { session?.close() }
        runCatching { camera?.close() }
        runCatching { reader?.close() }
        session = null; camera = null; reader = null
        thread?.quitSafely()
        thread = null; handler = null
    }

    fun setTorch(on: Boolean) {
        torch = on
        val r = request ?: return
        runCatching {
            r.set(CaptureRequest.FLASH_MODE, if (on) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
            session?.setRepeatingRequest(r.build(), null, handler)
        }
    }

    override fun onDetachedFromWindow() {
        stop()
        decoder.shutdownNow()
        super.onDetachedFromWindow()
    }

    @SuppressLint("MissingPermission")
    private fun open() {
        val cm = context.getSystemService(CameraManager::class.java) ?: return error("No camera available.")
        try {
            val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
                ?: cm.cameraIdList.firstOrNull() ?: return error("No camera available.")
            val chars = cm.getCameraCharacteristics(id)
            sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return error("The camera can't be used.")
            val preview = choose(map.getOutputSizes(SurfaceTexture::class.java), 1920, 1080)
            val analysis = choose(map.getOutputSizes(ImageFormat.YUV_420_888), 1280, 720)
            previewSize = preview
            main.post { applyTransform() }
            val r = ImageReader.newInstance(analysis.width, analysis.height, ImageFormat.YUV_420_888, 2)
            r.setOnImageAvailableListener({ ir -> onFrame(ir) }, handler)
            reader = r
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (!running) { device.close(); return }
                    camera = device
                    startSession(device, preview, r)
                }
                override fun onDisconnected(device: CameraDevice) { device.close(); camera = null }
                override fun onError(device: CameraDevice, error: Int) { device.close(); camera = null; error("The camera is busy or unavailable (error $error).") }
            }, handler)
        } catch (e: SecurityException) {
            error("Camera permission is needed to scan codes.")
        } catch (e: Exception) {
            Log.w("OeaQr", "camera open failed", e)
            error("Couldn't open the camera.")
        }
    }

    @Suppress("DEPRECATION")
    private fun startSession(device: CameraDevice, preview: Size, r: ImageReader) {
        val st = texture.surfaceTexture ?: return
        st.setDefaultBufferSize(preview.width, preview.height)
        val previewSurface = Surface(st)
        try {
            device.createCaptureSession(listOf(previewSurface, r.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (!running) { s.close(); return }
                    session = s
                    val b = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                    b.addTarget(previewSurface)
                    b.addTarget(r.surface)
                    b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    if (torch) b.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                    request = b
                    runCatching { s.setRepeatingRequest(b.build(), null, handler) }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) { error("The camera couldn't start.") }
            }, handler)
        } catch (e: Exception) {
            error("The camera couldn't start.")
        }
    }

    private fun onFrame(ir: ImageReader) {
        val image = runCatching { ir.acquireLatestImage() }.getOrNull() ?: return
        if (done.get() || !busy.compareAndSet(false, true)) { image.close(); return }
        val w = image.width; val h = image.height
        val plane = image.planes[0]
        val stride = plane.rowStride
        val buf = plane.buffer
        val y = ByteArray(w * h)
        if (stride == w) {
            buf.get(y, 0, minOf(y.size, buf.remaining()))
        } else {
            for (row in 0 until h) {
                buf.position(row * stride)
                buf.get(y, row * w, minOf(w, buf.remaining()))
            }
        }
        image.close()
        decoder.execute {
            try {
                // Scan the central region first (where the viewfinder points), then the whole frame.
                val side = minOf(w, h) * 3 / 4
                val text = QrCodes.decode(PlanarYUVLuminanceSource(y, w, h, (w - side) / 2, (h - side) / 2, side, side, false))
                    ?: QrCodes.decode(PlanarYUVLuminanceSource(y, w, h, 0, 0, w, h, false))
                if (text != null && accept(text) && done.compareAndSet(false, true)) {
                    main.post { onResult?.invoke(text) }
                }
            } finally {
                busy.set(false)
            }
        }
    }

    private fun applyTransform() {
        val p = previewSize ?: return
        val vw = width.toFloat(); val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) return
        val rotation = display?.rotation ?: Surface.ROTATION_0
        val m = Matrix()
        val viewRect = RectF(0f, 0f, vw, vh)
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            val bufferRect = RectF(0f, 0f, p.height.toFloat(), p.width.toFloat())
            bufferRect.offset(viewRect.centerX() - bufferRect.centerX(), viewRect.centerY() - bufferRect.centerY())
            m.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = max(vh / p.height, vw / p.width)
            m.postScale(scale, scale, viewRect.centerX(), viewRect.centerY())
            m.postRotate(90f * (rotation - 2), viewRect.centerX(), viewRect.centerY())
        } else {
            // Portrait: the camera buffer is landscape; restore its aspect and centre-crop.
            val bw = p.height.toFloat(); val bh = p.width.toFloat()
            val scale = max(vw / bw, vh / bh)
            m.setScale(scale * bw / vw, scale * bh / vh, vw / 2f, vh / 2f)
            if (rotation == Surface.ROTATION_180) m.postRotate(180f, vw / 2f, vh / 2f)
        }
        texture.setTransform(m)
    }

    private fun choose(sizes: Array<Size>?, w: Int, h: Int): Size {
        val list = sizes?.toList().orEmpty()
        if (list.isEmpty()) return Size(w, h)
        return list.filter { it.width <= w && it.height <= h }.maxByOrNull { it.width * it.height - abs(it.width * 9 - it.height * 16) }
            ?: list.minByOrNull { abs(it.width - w) + abs(it.height - h) }!!
    }

    private fun error(message: String) { main.post { onError?.invoke(message) } }

    /** Darkened surroundings, corner brackets and a moving scan line. */
    private class Viewfinder(context: Context) : View(context) {
        private val dim = Paint().apply { color = 0x99000000.toInt() }
        private val corner = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF57C8FF.toInt() }
        private val path = Path()
        private var phase = 0f
        private var animating = false
        private val tick = object : Runnable {
            override fun run() {
                phase = (phase + 0.012f) % 1f
                invalidate()
                if (animating) postDelayed(this, 16)
            }
        }

        fun animateLine(on: Boolean) {
            animating = on
            removeCallbacks(tick)
            if (on) post(tick)
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val side = minOf(w, h) * 0.68f
            val l = (w - side) / 2f; val t = (h - side) / 2f
            val r = RectF(l, t, l + side, t + side)
            val d = resources.displayMetrics.density
            path.reset()
            path.fillType = Path.FillType.EVEN_ODD
            path.addRect(0f, 0f, w, h, Path.Direction.CW)
            path.addRoundRect(r, 24f * d, 24f * d, Path.Direction.CW)
            canvas.drawPath(path, dim)
            corner.strokeWidth = 4f * d
            val c = side * 0.16f
            canvas.drawLine(r.left, r.top + c, r.left, r.top + 12f * d, corner); canvas.drawLine(r.left + 12f * d, r.top, r.left + c, r.top, corner)
            canvas.drawLine(r.right - c, r.top, r.right - 12f * d, r.top, corner); canvas.drawLine(r.right, r.top + 12f * d, r.right, r.top + c, corner)
            canvas.drawLine(r.left, r.bottom - c, r.left, r.bottom - 12f * d, corner); canvas.drawLine(r.left + 12f * d, r.bottom, r.left + c, r.bottom, corner)
            canvas.drawLine(r.right - c, r.bottom, r.right - 12f * d, r.bottom, corner); canvas.drawLine(r.right, r.bottom - 12f * d, r.right, r.bottom - c, corner)
            val y = r.top + side * (0.08f + 0.84f * (0.5f - 0.5f * kotlin.math.cos(phase * Math.PI.toFloat() * 2f)))
            line.alpha = 200
            canvas.drawRect(r.left + 18f * d, y - d, r.right - 18f * d, y + d, line)
        }
    }
}
