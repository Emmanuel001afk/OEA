package com.oea.launcher.miniapps

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.media.MediaActionSound
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.oea.engine.net.qr.QrCodes
import com.oea.engine.net.qr.QrScannerView
import com.oea.launcher.R
import com.oea.launcher.camera.OeaCameraOptics
import com.oea.launcher.camera.OeaTorch
import com.oea.launcher.ui.OeaKit
import com.oea.launcher.ui.OeaPalette
import com.oea.launcher.ui.OeaTheme
import com.oea.launcher.ui.OeaThumbnails
import com.oea.launcher.ui.OeaType
import com.oea.launcher.widgets.OeaWidgetStudio
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * OEA Camera — a real Camera2 app, not a wrapper around somebody else's.
 *
 * What changed from the first version (all of it was found by comparing this
 * screen against what `CameraCharacteristics` and the rest of OEA already do):
 *
 * - **The preview is upright.** The texture was drawn raw, so on a portrait
 *   phone the picture was sideways and stretched to the window's shape. It is
 *   now rotated by the sensor/display difference and centre-cropped to cover,
 *   using the same arithmetic Android's own samples use (and the engine's QR
 *   scanner already used), with the front camera mirrored.
 * - **A still is a photo, not a screenshot.** The shutter used to save whatever
 *   the preview texture held — 1280×720, already distorted. It now captures
 *   through a JPEG [ImageReader] at the sensor's own resolution, carries the
 *   correct EXIF orientation, and only falls back to the texture if the camera
 *   refuses the request.
 * - **The settings do what they say.** Location tag writes GPS into the EXIF,
 *   the shutter sound is playable, flash and zoom reach the running request,
 *   the aspect chip really changes the buffer and crops the file, and the Pro
 *   focus bar no longer pushes the lens to infinity when you ask for close.
 * - **Tap-to-focus lands where you tapped** (view space → sensor space, crop
 *   region and rotation included) and returns to continuous focus instead of
 *   latching.
 * - **Video is rotated and audited.** `setOrientationHint` is written from the
 *   same maths as the stills, a bit rate is chosen for the size, and the
 *   microphone is requested instead of silently leaving every clip mute.
 * - **QR works offline.** It used to ask for a third-party zxing *client* that
 *   almost no phone has; it now uses OEA's own engine scanner (Camera2 + ZXing,
 *   already shipped for game invite codes) and can also read a code out of a
 *   picture. Nothing is uploaded anywhere.
 * - **Document mode is a scan.** It used to hand over to the phone's camera; it
 *   now captures and runs a real paper-scan pass (white and black levels found
 *   from the frame's own histogram).
 * - **The camera is released when you leave**, errors are shown with a retry
 *   instead of a black rectangle, and the torch is owned by [OeaTorch] like
 *   every other torch in OEA.
 *
 * Modes: Photo, Video, Portrait (focus-band blur), Night (shadow lift), Pro,
 * QR, Document, Time-lapse. Flash Auto/On/Off/Torch, timer 0–10 s, a real burst,
 * grid, aspect, filters, pinch and slider zoom, a captured-photo sheet with
 * Share / Edit in EMAGE / Night pass / face blur / background softening, and a
 * settings sheet where every switch is wired to something.
 */
object OeaCameraApp {

    private const val PREFS = "oea_camera_v2"
    private const val KEY_FLASH = "flash"
    private const val KEY_TIMER = "timer"
    private const val KEY_GRID = "grid"
    private const val KEY_ASPECT = "aspect"
    private const val KEY_FILTER = "filter"
    private const val KEY_FACING = "facing_front"
    private const val KEY_ZOOM = "zoom"
    private const val KEY_HDR = "hdr"
    private const val KEY_LOCATION = "location"
    private const val KEY_SHUTTER_SOUND = "shutter_sound"
    private const val KEY_PRIVACY_BLUR = "privacy_blur"
    private const val KEY_BURST = "burst_frames"
    private const val KEY_PENDING_SYSTEM_CAPTURE = "pending_system_capture"

    private const val REQUEST_SYSTEM_PHOTO = 3001
    private const val REQUEST_SYSTEM_VIDEO = 3004
    private const val REQUEST_QR_PICTURE = 3002
    private const val REQUEST_CAMERA_PERMISSION = 2001
    private const val REQUEST_LOCATION_PERMISSION = 2002
    private const val REQUEST_MIC_PERMISSION = 2003

    private const val BURST_STEP_MS = 340L
    private const val TIME_LAPSE_RATE = 6f
    private const val MAX_JPEG_PIXELS = 12_000_000L
    /** A camera that accepts a capture and then says nothing must not hang the shutter. */
    private const val STILL_TIMEOUT_MS = 5000L

    /** The white-balance stops the WB bar walks through, in that order. */
    private val WB_NAMES = listOf("auto", "tungsten", "fluorescent", "daylight", "cloudy")

    /** Developing a frame (filters, night pass, face blur, encode) never runs on the UI thread. */
    private val CAPTURE = Executors.newSingleThreadExecutor { task ->
        Thread(task, "OeaCameraDevelop").apply { isDaemon = true }
    }

    private var focusBandY = 0.5f

    // ------------------------------------------------------------------ settings

    fun burstCount(host: Activity): Int = prefs(host).getInt(KEY_BURST, 1).coerceIn(1, 10)

    fun setBurstCount(host: Activity, frames: Int) {
        prefs(host).edit().putInt(KEY_BURST, frames.coerceIn(1, 10)).apply()
    }

    /** OEA's own shutter effect: blur every face Android can find. */
    fun privacyBlur(host: Activity): Boolean = prefs(host).getBoolean(KEY_PRIVACY_BLUR, false)

    fun setPrivacyBlur(host: Activity, value: Boolean) {
        prefs(host).edit().putBoolean(KEY_PRIVACY_BLUR, value).apply()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun <T : Enum<T>> stored(context: Context, key: String, values: Array<T>, fallback: T): T {
        val saved = prefs(context).getString(key, null) ?: return fallback
        return values.firstOrNull { it.name == saved } ?: fallback
    }

    // -------------------------------------------------------------------- enums

    enum class FlashMode { AUTO, ON, OFF, TORCH }

    enum class TimerMode(val seconds: Int, val label: String) { OFF(0, "Off"), S3(3, "3s"), S5(5, "5s"), S10(10, "10s") }

    enum class GridMode(val label: String) { OFF("Off"), GRID_3X3("3×3"), GRID_4X4("4×4"), GOLDEN("Golden"), DIAGONAL("Diagonal") }

    enum class AspectMode(val label: String, val ratio: Float) {
        R_4_3("4:3", 4f / 3f),
        R_16_9("16:9", 16f / 9f),
        R_1_1("1:1", 1f),
        FULL("Full", 0f)
    }

    enum class FilterMode(val label: String) { NONE("None"), MONO("Mono"), SEPIA("Sepia"), VIVID("Vivid"), COOL("Cool"), WARM("Warm"), NOIR("Noir") }

    enum class CaptureMode(val label: String, val desc: String) {
        PHOTO("Photo", "Standard capture · the contrast push is in settings"),
        VIDEO("Video", "Record with stabilization and mic control"),
        PORTRAIT("Portrait", "Softens everything outside the band you focused on"),
        NIGHT("Night", "Lifts shadows and calms colour noise in the dark"),
        PRO("Pro", "ISO, exposure, white balance, focus — as far as this lens obeys"),
        QR("QR", "Scan a QR code with OEA's own camera, or from a picture"),
        DOC("Document", "Captures and flattens the page into a clean scan"),
        TIMELAPSE("Time-lapse", "Records at 6 fps, plays at 30 — five times faster")
    }

    // ------------------------------------------------------- outside-caller support

    @Suppress("DEPRECATION")
    private fun outputUri(intent: Intent?): Uri? {
        if (intent == null) return null
        return if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT, Uri::class.java)
        else intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT)
    }

    private fun isExternalCapture(host: Activity): Boolean =
        host.intent?.action == MediaStore.ACTION_IMAGE_CAPTURE || host.intent?.action == MediaStore.ACTION_VIDEO_CAPTURE

    /** Copies a private file into a caller-owned output URI without ever exposing file://. */
    private fun copyToUri(context: Context, file: File, target: Uri): Boolean = try {
        context.contentResolver.openOutputStream(target)?.use { out -> file.inputStream().use { it.copyTo(out) } } != null
    } catch (_: Throwable) {
        false
    }

    private fun providerUri(context: Context, file: File): Uri? =
        runCatching { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file) }.getOrNull()

    private fun finishExternalCapture(host: Activity, target: Uri?, file: File, mime: String): Boolean {
        val resultUri = if (target != null) {
            if (!copyToUri(host, file, target)) return false
            target
        } else {
            providerUri(host, file) ?: return false
        }
        host.setResult(Activity.RESULT_OK, Intent().setDataAndType(resultUri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        host.finish()
        return true
    }

    /**
     * Results from the two things the camera hands to another app: the system
     * camera fallback and the picture a QR code is read out of. Keeping this in
     * the camera app means an incoming ACTION_IMAGE_CAPTURE flow and an in-app
     * fallback use the same provider-safe result path.
     */
    fun handleActivityResult(host: Activity, requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode == REQUEST_QR_PICTURE) {
            val uri = data?.data
            if (resultCode != Activity.RESULT_OK || uri == null) {
                toast(host, "No picture selected")
                return true
            }
            decodeQrPicture(host, uri)
            return true
        }
        if (requestCode != REQUEST_SYSTEM_PHOTO && requestCode != REQUEST_SYSTEM_VIDEO) return false

        val p = prefs(host)
        val pendingPath = p.getString(KEY_PENDING_SYSTEM_CAPTURE, null)
        p.edit().remove(KEY_PENDING_SYSTEM_CAPTURE).apply()
        val pendingFile = pendingPath?.let { File(it) }?.takeIf { it.isFile && it.length() > 0L }
        val target = outputUri(host.intent)
        if (resultCode != Activity.RESULT_OK) {
            pendingFile?.delete()
            if (isExternalCapture(host)) {
                host.setResult(Activity.RESULT_CANCELED)
                host.finish()
            }
            return true
        }
        val video = host.intent?.action == MediaStore.ACTION_VIDEO_CAPTURE && requestCode == REQUEST_SYSTEM_VIDEO
        if (isExternalCapture(host)) {
            val resultMime = if (video) "video/mp4" else "image/jpeg"
            // If the caller supplied EXTRA_OUTPUT, the system camera already
            // wrote to it. Return that content URI, never a raw path.
            if (target != null) {
                host.setResult(Activity.RESULT_OK, Intent().setDataAndType(target, resultMime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                host.finish()
                return true
            }
            if (pendingFile != null) {
                val uri = providerUri(host, pendingFile)
                if (uri != null) {
                    host.setResult(Activity.RESULT_OK, Intent().setDataAndType(uri, resultMime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    host.finish()
                    return true
                }
            }
            // No output URI and no provider-backed fallback: pass through a
            // camera thumbnail only when one was actually returned. Video
            // callers must never receive a successful empty result.
            if (data != null) host.setResult(Activity.RESULT_OK, data) else host.setResult(Activity.RESULT_CANCELED)
            host.finish()
            return true
        }
        if (pendingFile != null) {
            MediaScannerConnection.scanFile(host, arrayOf(pendingFile.absolutePath), arrayOf("image/jpeg"), null)
            showCapturedSheet(host, OeaKit(host, OeaTheme.resolve(host)), pendingFile)
            return true
        }
        val thumb = data?.extras?.get("data") as? Bitmap
        if (thumb != null) {
            val saved = saveProcessed(
                host = host,
                source = thumb,
                mode = CaptureMode.PHOTO,
                filter = FilterMode.NONE,
                privacy = false,
                aspect = AspectMode.FULL,
                hdr = false,
                orientationTag = 1,
                withLocation = false
            )
            thumb.recycle()
            if (saved != null) showCapturedSheet(host, OeaKit(host, OeaTheme.resolve(host)), saved)
        } else {
            toast(host, "Capture failed")
        }
        return true
    }

    /** Puts the torch out when the camera screen goes to the background. */
    fun onHostPaused(ctx: Context) {
        OeaTorch.off(ctx)
    }

    /** Keeps the torch off while the camera's own viewfinder owns the LED. */
    fun onViewfinderOpen(ctx: Context) {
        OeaTorch.off(ctx)
    }

    // ------------------------------------------------------------------- the app

    /**
     * The Camera, full-screen: the viewfinder fills the window and the controls
     * sit over it in three clear bands — a top bar (aspect, flash, timer, grid,
     * filter, settings), a bottom band (pro controls, zoom, modes, shutter row).
     */
    fun build(host: Activity, palette: OeaPalette): View {
        val kit = OeaKit(host, palette)
        val p = prefs(host)
        var flash = stored(host, KEY_FLASH, FlashMode.values(), FlashMode.AUTO)
        var timer = stored(host, KEY_TIMER, TimerMode.values(), TimerMode.OFF)
        var grid = stored(host, KEY_GRID, GridMode.values(), GridMode.GRID_3X3)
        var filter = stored(host, KEY_FILTER, FilterMode.values(), FilterMode.NONE)
        var aspect = stored(host, KEY_ASPECT, AspectMode.values(), AspectMode.R_4_3)
        var front = p.getBoolean(KEY_FACING, false)
        var zoom = p.getFloat(KEY_ZOOM, 1f).coerceIn(1f, 8f)
        val captureTarget = outputUri(host.intent)
        val externalCapture = isExternalCapture(host)
        var captureMode = if (host.intent?.action == MediaStore.ACTION_VIDEO_CAPTURE) CaptureMode.VIDEO else CaptureMode.PHOTO
        var isRecording = false
        val density = host.resources.displayMetrics.density
        fun dp(v: Int) = (v * density + 0.5f).toInt()
        // Chips are styled when created; this repaints one when its selection changes.
        fun styleChip(view: TextView, selected: Boolean) {
            view.setTextColor(if (selected) palette.onAccent else palette.text)
            view.background = kit.ripple(kit.rounded(if (selected) palette.accent else palette.cardHigh, 18), 18)
        }

        val root = FrameLayout(host).apply { setBackgroundColor(Color.BLACK) }

        val cameraGranted = host.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!cameraGranted) {
            val column = LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(24), dp(24), dp(24), dp(24))
            }
            column.addView(kit.text("Camera needs permission", 20f, Color.WHITE, OeaType.medium).apply { gravity = Gravity.CENTER })
            column.addView(
                kit.text(
                    "Allow the camera to use OEA's own viewfinder, flash, zoom and pro controls. Without it, the system camera still takes photos.",
                    14f,
                    Color.LTGRAY
                ).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(20)) }
            )
            column.addView(kit.primaryButton("Allow camera", R.drawable.oic_info) {
                try {
                    host.requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
                } catch (_: Throwable) {
                }
            }, LinearLayout.LayoutParams(-1, dp(50)))
            column.addView(
                kit.tonalButton("Open system camera", R.drawable.oic_photo_library) {
                    openSystemCamera(host, captureTarget, if (externalCapture && host.intent?.action == MediaStore.ACTION_VIDEO_CAPTURE) REQUEST_SYSTEM_VIDEO else REQUEST_SYSTEM_PHOTO)
                },
                LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(10) }
            )
            root.addView(column, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
            return root
        }

        // ---- viewfinder: full-bleed, grid / filter / status layered on top
        val textureView = TextureView(host)
        root.addView(textureView, FrameLayout.LayoutParams(-1, -1))
        val gridOverlay = GridOverlayView(host, grid)
        root.addView(gridOverlay, FrameLayout.LayoutParams(-1, -1))
        val filterOverlay = View(host).apply { alpha = 0f }
        root.addView(filterOverlay, FrameLayout.LayoutParams(-1, -1))
        val focusView = View(host).apply {
            background = kit.oval(Color.TRANSPARENT, Color.WHITE, dp(2))
            visibility = View.GONE
        }
        root.addView(focusView, FrameLayout.LayoutParams(dp(72), dp(72)))

        // A camera that cannot start says so, and one tap tries again — instead of
        // leaving the user looking at a black rectangle with working buttons.
        // The status card is created before the camera object, but its tap action
        // belongs to the camera object, so it is filled in a moment later.
        var retryAction: (() -> Unit)? = null
        val statusView = kit.text("", 13f, Color.WHITE).apply {
            setBackgroundColor(0xCC000000.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(12), dp(18), dp(12))
            visibility = View.GONE
            isClickable = true
        }
        root.addView(statusView, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        statusView.setOnClickListener {
            statusView.visibility = View.GONE
            retryAction?.invoke()
        }

        fun applyFilterOverlay() {
            filterOverlay.alpha = if (filter == FilterMode.NONE) 0f else 0.18f
            filterOverlay.setBackgroundColor(
                when (filter) {
                    FilterMode.MONO -> Color.GRAY
                    FilterMode.SEPIA -> Color.rgb(112, 66, 20)
                    FilterMode.VIVID -> Color.rgb(255, 100, 100)
                    FilterMode.COOL -> Color.rgb(100, 150, 255)
                    FilterMode.WARM -> Color.rgb(255, 180, 80)
                    FilterMode.NOIR -> Color.BLACK
                    else -> Color.TRANSPARENT
                }
            )
        }
        applyFilterOverlay()

        val cameraHelper = AdvancedCameraHelper(
            activity = host,
            textureView = textureView,
            initialFront = front,
            initialZoom = zoom,
            initialFlash = flash,
            initialAspect = aspect,
            onStatus = { message ->
                if (message == null) {
                    statusView.visibility = View.GONE
                } else {
                    statusView.text = "$message · tap to try again"
                    statusView.visibility = View.VISIBLE
                }
            },
            onReady = { onViewfinderOpen(host) }
        )

        retryAction = { cameraHelper.retry() }

        var qrDialog: Dialog? = null
        // The host's own pause/resume are the reliable points to release the
        // camera: a permission dialog, a share sheet or another app must not leave
        // the sensor open behind them.
        val miniApp = host as? OeaMiniAppActivity
        miniApp?.pauseHook = {
            runCatching { qrDialog?.dismiss() }
            cameraHelper.pauseNow()
        }
        miniApp?.resumeHook = { cameraHelper.resumeNow() }

        cameraHelper.start()
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                cameraHelper.start()
            }

            override fun onViewDetachedFromWindow(v: View) {
                cameraHelper.stop()
            }
        })

        // ---- touch: pinch to zoom, tap to focus
        val scaleDetector = ScaleGestureDetector(host, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val next = (zoom * detector.scaleFactor).coerceIn(1f, 8f)
                zoom = next
                p.edit().putFloat(KEY_ZOOM, zoom).apply()
                cameraHelper.setZoom(zoom)
                return true
            }
        })
        var pinching = false
        textureView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> pinching = false
                MotionEvent.ACTION_POINTER_DOWN -> pinching = true
                MotionEvent.ACTION_UP -> if (!pinching) {
                    focusView.x = event.x - dp(36)
                    focusView.y = event.y - dp(36)
                    focusView.visibility = View.VISIBLE
                    focusView.alpha = 1f
                    focusView.animate().alpha(0f).setStartDelay(800).setDuration(300)
                        .withEndAction { focusView.visibility = View.GONE }.start()
                    cameraHelper.focusAt(event.x, event.y, textureView.width, textureView.height)
                    // The portrait pass blurs hardest away from this line, so the
                    // band you focused on is the band the picture keeps sharp.
                    if (textureView.height > 0) {
                        focusBandY = (event.y / textureView.height).coerceIn(0.12f, 0.88f)
                    }
                    if (captureMode == CaptureMode.PORTRAIT) {
                        toast(host, "Portrait keeps the ${(focusBandY * 100).toInt()}% line sharp")
                    }
                }
                else -> Unit
            }
            // Keep receiving the moves of a pinch that started on this view.
            true
        }

        // ---- top bar
        val topBar = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0x66000000)
            setPadding(dp(10), dp(30), dp(10), dp(8))
        }
        val aspectChip = kit.chip("Aspect ${aspect.label}", aspect != AspectMode.R_4_3) {}
        val flashChip = kit.chip("Flash ${flash.name.lowercase().replaceFirstChar { it.uppercase() }}", flash != FlashMode.OFF) {}
        val timerChip = kit.chip("Timer ${timer.label}", timer != TimerMode.OFF) {}
        val gridChip = kit.chip("Grid ${grid.label}", grid != GridMode.OFF) {}
        val filterChip = kit.chip("Filter ${filter.label}", filter != FilterMode.NONE) {}

        fun refreshChips() {
            aspectChip.text = "Aspect ${aspect.label}"
            styleChip(aspectChip, aspect != AspectMode.R_4_3)
            flashChip.text = "Flash ${flash.name.lowercase().replaceFirstChar { it.uppercase() }}"
            styleChip(flashChip, flash != FlashMode.OFF)
            timerChip.text = "Timer ${timer.label}"
            styleChip(timerChip, timer != TimerMode.OFF)
            gridChip.text = "Grid ${grid.label}"
            styleChip(gridChip, grid != GridMode.OFF)
            filterChip.text = "Filter ${filter.label}"
            styleChip(filterChip, filter != FilterMode.NONE)
        }
        refreshChips()

        aspectChip.setOnClickListener {
            aspect = AspectMode.values()[(aspect.ordinal + 1) % AspectMode.values().size]
            p.edit().putString(KEY_ASPECT, aspect.name).apply()
            cameraHelper.setAspect(aspect)
            refreshChips()
        }
        flashChip.setOnClickListener {
            flash = FlashMode.values()[(flash.ordinal + 1) % FlashMode.values().size]
            p.edit().putString(KEY_FLASH, flash.name).apply()
            cameraHelper.setFlash(flash)
            if (flash == FlashMode.TORCH) {
                // With the viewfinder open the request owns the LED; without it (a
                // camera that refused to start) OEA's torch still works.
                if (cameraHelper.isOpen()) {
                    OeaTorch.off(host)
                } else if (!OeaTorch.set(host, true)) {
                    toast(host, "This phone has no torch OEA can switch on")
                }
            } else {
                OeaTorch.off(host)
            }
            refreshChips()
        }
        timerChip.setOnClickListener {
            timer = TimerMode.values()[(timer.ordinal + 1) % TimerMode.values().size]
            p.edit().putString(KEY_TIMER, timer.name).apply()
            refreshChips()
        }
        gridChip.setOnClickListener {
            grid = GridMode.values()[(grid.ordinal + 1) % GridMode.values().size]
            p.edit().putString(KEY_GRID, grid.name).apply()
            gridOverlay.setMode(grid)
            refreshChips()
        }
        filterChip.setOnClickListener {
            filter = FilterMode.values()[(filter.ordinal + 1) % FilterMode.values().size]
            p.edit().putString(KEY_FILTER, filter.name).apply()
            applyFilterOverlay()
            refreshChips()
        }
        val chipLp = { LinearLayout.LayoutParams(-2, dp(32)).apply { rightMargin = dp(6) } }
        val chips = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
        chips.addView(aspectChip, chipLp())
        chips.addView(flashChip, chipLp())
        chips.addView(timerChip, chipLp())
        chips.addView(gridChip, chipLp())
        chips.addView(filterChip, chipLp())
        topBar.addView(kit.hscroll(chips), LinearLayout.LayoutParams(0, -2, 1f))
        topBar.addView(
            kit.circleIconButton(R.drawable.oic_settings, "Camera settings", 0x33FFFFFF, Color.WHITE) {
                showSettings(host, kit, palette, aspect) { chosen ->
                    aspect = chosen
                    p.edit().putString(KEY_ASPECT, chosen.name).apply()
                    cameraHelper.setAspect(chosen)
                    refreshChips()
                }
            },
            LinearLayout.LayoutParams(dp(40), dp(40)).apply { leftMargin = dp(6) }
        )
        root.addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        // ---- bottom band: pro controls, zoom, modes, shutter row
        val bottom = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0x66000000)
            setPadding(dp(12), dp(10), dp(12), dp(22))
        }
        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        // Pro controls: only in Pro mode.
        val proBox = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val isoBar = SeekBar(host).apply { max = 100 }
        val expBar = SeekBar(host).apply { max = 200; progress = 100 }
        val wbBar = SeekBar(host).apply { max = WB_NAMES.size - 1 }
        val focusBar = SeekBar(host).apply { max = 100 }
        val proCaption = kit.text("Pro", 10.5f, 0xB3FFFFFF.toInt())

        // The bars are not scenery: each one writes into the live repeating request,
        // and OEA says plainly which of them this particular lens obeys.
        fun pushPro() {
            cameraHelper.applyPro(
                isoPercent = isoBar.progress,
                evPercent = expBar.progress,
                awbPick = wbBar.progress,
                focusPercent = focusBar.progress
            )
            val ev = expBar.progress - 100
            proCaption.text = "ISO " + (if (isoBar.progress == 0) "auto" else isoBar.progress.toString() + "%") +
                " · EV " + (if (ev == 0) "auto" else ev.toString()) +
                " · WB " + WB_NAMES[wbBar.progress.coerceIn(0, WB_NAMES.size - 1)] +
                " · focus " + (if (focusBar.progress == 0) "auto" else focusBar.progress.toString() + "%")
        }

        fun addProRow(label: String, bar: SeekBar) {
            val row = LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(kit.text(label, 12f, Color.WHITE), LinearLayout.LayoutParams(dp(40), -2))
            row.addView(bar, LinearLayout.LayoutParams(0, -2, 1f))
            proBox.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
        }
        addProRow("ISO", isoBar)
        addProRow("EV", expBar)
        addProRow("WB", wbBar)
        addProRow("Focus", focusBar)
        proBox.addView(proCaption, LinearLayout.LayoutParams(-1, -2).apply { leftMargin = dp(44); topMargin = dp(2) })
        val proListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) pushPro()
            }

            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        }
        listOf(isoBar, expBar, wbBar, focusBar).forEach { it.setOnSeekBarChangeListener(proListener) }
        bottom.addView(proBox, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        // Zoom presets and a fine slider share one row; pinching moves both.
        val zoomRow = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val zoomLabels = mutableListOf<Pair<Float, TextView>>()
        val zoomBar = SeekBar(host).apply { max = 700; progress = (((zoom - 1f) * 100).toInt()).coerceIn(0, 700) }
        fun refreshZoom() {
            zoomLabels.forEach { (level, view) -> styleChip(view, kotlin.math.abs(level - zoom) < 0.05f) }
            val wanted = (((zoom - 1f) * 100).toInt()).coerceIn(0, 700)
            if (zoomBar.progress != wanted) zoomBar.progress = wanted
        }
        listOf(1f, 2f, 4f).forEach { level ->
            val chip = kit.chip("${level.toInt()}×", false) {
                zoom = level
                p.edit().putFloat(KEY_ZOOM, zoom).apply()
                cameraHelper.setZoom(zoom)
                refreshZoom()
            }
            zoomLabels += level to chip
            zoomRow.addView(chip, LinearLayout.LayoutParams(dp(56), dp(34)).apply { rightMargin = dp(6) })
        }
        zoomBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    zoom = 1f + progress / 100f
                    p.edit().putFloat(KEY_ZOOM, zoom).apply()
                    cameraHelper.setZoom(zoom)
                    refreshZoom()
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        })
        zoomRow.addView(zoomBar, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(4) })
        bottom.addView(zoomRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        refreshZoom()

        // Modes: one row of chips, selection updated in place.
        val modeRow = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
        val modeChips = mutableListOf<Pair<CaptureMode, TextView>>()
        fun selectMode(mode: CaptureMode) {
            if (isRecording && mode != captureMode) {
                toast(host, "Stop the recording before changing mode")
                return
            }
            captureMode = mode
            proBox.visibility = if (mode == CaptureMode.PRO) View.VISIBLE else View.GONE
            if (mode == CaptureMode.PRO) {
                proCaption.text = cameraHelper.proStatus()
                pushPro()
            } else if (captureMode != mode) {
                // Manual ISO switches auto-exposure off. Leaving Pro without
                // handing the controls back would leave every later photo dark.
                isoBar.progress = 0
                expBar.progress = 100
                wbBar.progress = 0
                focusBar.progress = 0
                cameraHelper.applyPro(isoPercent = 0, evPercent = 100, awbPick = 0, focusPercent = 0)
            }
            modeChips.forEach { (m, view) -> styleChip(view, m == mode) }
            when (mode) {
                CaptureMode.QR -> openQrScanner(host, kit, palette) { dialog -> qrDialog = dialog }
                CaptureMode.VIDEO, CaptureMode.TIMELAPSE -> {
                    if (host.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        runCatching { host.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC_PERMISSION) }
                    }
                }
                CaptureMode.DOC -> toast(host, "Document: tap the shutter, OEA flattens the page")
                else -> Unit
            }
        }
        CaptureMode.values().forEach { mode ->
            val chip = kit.chip(mode.label, mode == captureMode) { selectMode(mode) }
            modeChips += mode to chip
            modeRow.addView(chip, LinearLayout.LayoutParams(-2, dp(34)).apply { rightMargin = dp(6) })
        }
        bottom.addView(kit.hscroll(modeRow), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        proBox.visibility = if (captureMode == CaptureMode.PRO) View.VISIBLE else View.GONE

        // Shutter row: the gallery thumb on one side, the camera switch on the other.
        val shutterRow = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val captureBtn = FrameLayout(host).apply {
            background = kit.oval(Color.WHITE, palette.accent, dp(4))
            isClickable = true
            contentDescription = "Shutter"
        }
        val innerCapture = View(host).apply { background = kit.oval(Color.WHITE) }
        captureBtn.addView(innerCapture, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER))
        val switchBtn = kit.circleIconButton(R.drawable.oic_refresh, "Switch camera", 0x33FFFFFF, Color.WHITE) {
            if (isRecording) {
                toast(host, "Stop the recording before switching camera")
                return@circleIconButton
            }
            front = !front
            p.edit().putBoolean(KEY_FACING, front).apply()
            cameraHelper.switchCamera(front)
        }
        cameraHelper.onRecordingAbandoned = { file ->
            isRecording = false
            innerCapture.background = kit.oval(Color.WHITE)
            if (!externalCapture) {
                MediaScannerConnection.scanFile(host, arrayOf(file.absolutePath), arrayOf("video/mp4"), null)
                toast(host, "Recording stopped and saved: ${file.name}")
            }
        }
        val galleryBtn = photoLibraryButton(host, kit)
        shutterRow.addView(galleryBtn, LinearLayout.LayoutParams(dp(52), dp(52)))
        shutterRow.addView(View(host), LinearLayout.LayoutParams(0, 1, 1f))
        shutterRow.addView(captureBtn, LinearLayout.LayoutParams(dp(80), dp(80)))
        shutterRow.addView(View(host), LinearLayout.LayoutParams(0, 1, 1f))
        shutterRow.addView(switchBtn, LinearLayout.LayoutParams(dp(52), dp(52)))
        bottom.addView(shutterRow, LinearLayout.LayoutParams(-1, -2))

        // ---- capture
        val mainHandler = Handler(Looper.getMainLooper())

        fun playShutterIfWanted() {
            if (!p.getBoolean(KEY_SHUTTER_SOUND, true)) return
            runCatching {
                val sound = MediaActionSound()
                sound.load(MediaActionSound.SHUTTER_CLICK)
                sound.play(MediaActionSound.SHUTTER_CLICK)
                mainHandler.postDelayed({ runCatching { sound.release() } }, 1200L)
            }
        }

        fun deliverStill(bytes: ByteArray?, ceremony: Boolean) {
            if (host.isDestroyed) return
            val snapshot = if (bytes == null) cameraHelper.snapshot() else null
            if (bytes == null && snapshot == null) {
                if (externalCapture) {
                    host.setResult(Activity.RESULT_CANCELED)
                    host.finish()
                } else {
                    toast(host, "Capture failed — the camera is busy")
                }
                return
            }
            playShutterIfWanted()
            val orientationTag = if (bytes != null) cameraHelper.jpegOrientation() else 1
            val mode = captureMode
            val tone = filter
            val smoothFaces = privacyBlur(host)
            val hdr = p.getBoolean(KEY_HDR, false)
            val withLocation = p.getBoolean(KEY_LOCATION, false)
            val cropAspect = aspect.ratio
            CAPTURE.execute {
                val saved = saveFrame(
                    host = host,
                    jpeg = bytes,
                    upright = snapshot,
                    mode = mode,
                    filter = tone,
                    privacy = smoothFaces,
                    aspect = cropAspect,
                    hdr = hdr,
                    orientationTag = orientationTag,
                    withLocation = withLocation
                )
                host.runOnUiThread {
                    if (host.isDestroyed) return@runOnUiThread
                    if (externalCapture) {
                        if (saved != null && finishExternalCapture(host, captureTarget, saved, "image/jpeg")) return@runOnUiThread
                        saved?.delete()
                        host.setResult(Activity.RESULT_CANCELED)
                        host.finish()
                        return@runOnUiThread
                    }
                    if (saved == null) {
                        toast(host, "Capture failed")
                        return@runOnUiThread
                    }
                    if (!ceremony) {
                        toast(host, "Frame saved")
                        return@runOnUiThread
                    }
                    val extra = if (mode == CaptureMode.PHOTO) "" else " · ${mode.label.lowercase(Locale.getDefault())} pass"
                    toast(host, "Saved ${saved.name}$extra")
                    showCapturedSheet(host, kit, saved)
                }
            }
        }

        fun takeOne(ceremony: Boolean) {
            cameraHelper.captureStill { bytes -> deliverStill(bytes, ceremony) }
        }

        fun capturePhotos() {
            val shots = if (externalCapture) 1 else burstCount(host)
            if (timer.seconds > 0) toast(host, "Capturing in ${timer.seconds}s")
            if (shots > 1) toast(host, "$shots frames, $BURST_STEP_MS ms apart")
            repeat(shots) { index ->
                val at = timer.seconds * 1000L + index * BURST_STEP_MS
                val last = index == shots - 1
                if (at <= 0L) takeOne(last) else mainHandler.postDelayed({ takeOne(last) }, at)
            }
        }

        fun stopRecording() {
            isRecording = false
            innerCapture.background = kit.oval(Color.WHITE)
            cameraHelper.stopVideoRecording { file ->
                if (host.isDestroyed) return@stopVideoRecording
                if (file == null) {
                    toast(host, "Video failed")
                    if (externalCapture) {
                        host.setResult(Activity.RESULT_CANCELED)
                        host.finish()
                    }
                    return@stopVideoRecording
                }
                if (externalCapture) {
                    if (!finishExternalCapture(host, captureTarget, file, "video/mp4")) {
                        file.delete()
                        host.setResult(Activity.RESULT_CANCELED)
                        host.finish()
                    }
                    return@stopVideoRecording
                }
                MediaScannerConnection.scanFile(host, arrayOf(file.absolutePath), arrayOf("video/mp4"), null)
                toast(host, "Video saved: ${file.name}")
            }
        }

        fun startRecording() {
            val withAudio = host.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (!withAudio) {
                runCatching { host.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC_PERMISSION) }
                toast(host, "Recording without sound — allow the microphone for audio")
            }
            isRecording = true
            innerCapture.background = kit.oval(Color.RED)
            cameraHelper.startVideoRecording(
                withAudio = withAudio,
                captureRateHz = if (captureMode == CaptureMode.TIMELAPSE) TIME_LAPSE_RATE else 0f,
                onError = { message ->
                    isRecording = false
                    innerCapture.background = kit.oval(Color.WHITE)
                    toast(host, message)
                }
            )
        }

        captureBtn.setOnClickListener {
            when (captureMode) {
                CaptureMode.VIDEO, CaptureMode.TIMELAPSE -> if (isRecording) stopRecording() else startRecording()
                CaptureMode.QR -> openQrScanner(host, kit, palette) { dialog -> qrDialog = dialog }
                else -> capturePhotos()
            }
        }

        // ---- a fallback for phones whose camera refuses Camera2 entirely
        if (!cameraHelper.hasCamera()) {
            statusView.text = "OEA could not find a camera to open · tap to use the system camera"
            statusView.visibility = View.VISIBLE
            statusView.setOnClickListener {
                openSystemCamera(
                    host,
                    captureTarget,
                    if (externalCapture && host.intent?.action == MediaStore.ACTION_VIDEO_CAPTURE) REQUEST_SYSTEM_VIDEO else REQUEST_SYSTEM_PHOTO
                )
            }
        }

        return root
    }

    // --------------------------------------------------------------- the view bits

    /** The last picture, loaded through OEA's thumbnail pipeline rather than decoded whole. */
    private fun photoLibraryButton(host: Activity, kit: OeaKit): View {
        val latest = latestPhoto(host)
        if (latest == null) {
            return kit.circleIconButton(R.drawable.oic_photo_library, "EMAGE", 0x33FFFFFF, Color.WHITE) { openGallery(host) }
        }
        val uri = providerUri(host, latest)
        if (uri == null) {
            return kit.circleIconButton(R.drawable.oic_photo_library, "EMAGE", 0x33FFFFFF, Color.WHITE) { openGallery(host) }
        }
        val frame = FrameLayout(host).apply {
            background = kit.rounded(Color.BLACK, 12)
            clipToOutline = true
            isClickable = true
            contentDescription = "Open EMAGE"
            setOnClickListener { openGallery(host) }
        }
        val image = ImageView(host).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "Last photo"
        }
        frame.addView(image, FrameLayout.LayoutParams(dpOf(host, 48), dpOf(host, 48)))
        OeaThumbnails.load(image, uri, dpOf(host, 48), tag = "camera-last")
        return frame
    }

    private fun latestPhoto(host: Activity): File? = runCatching {
        File(host.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES), "OEA")
            .listFiles()
            ?.filter { it.isFile && it.length() > 0L }
            ?.maxByOrNull { it.lastModified() }
    }.getOrNull()

    private fun dpOf(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    private fun openGallery(host: Activity) {
        OeaMiniApps.open(host, "emage")
    }

    private fun openSystemCamera(host: Activity, requestedOutput: Uri?, requestCode: Int) {
        openSystemCamera(host, requestedOutput, requestCode, video = requestCode == REQUEST_SYSTEM_VIDEO)
    }

    private fun openSystemCamera(host: Activity, requestedOutput: Uri?, requestCode: Int, video: Boolean) {
        try {
            val intent = Intent(if (video) MediaStore.ACTION_VIDEO_CAPTURE else MediaStore.ACTION_IMAGE_CAPTURE)
            val output = requestedOutput ?: run {
                val dir = File(
                    host.getExternalFilesDir(if (video) android.os.Environment.DIRECTORY_MOVIES else android.os.Environment.DIRECTORY_PICTURES),
                    "OEA"
                )
                dir.mkdirs()
                val file = File(dir, "OEA_FALLBACK_${System.currentTimeMillis()}.${if (video) "mp4" else "jpg"}")
                prefs(host).edit().putString(KEY_PENDING_SYSTEM_CAPTURE, file.absolutePath).apply()
                providerUri(host, file)
            }
            if (output != null) {
                intent.putExtra(MediaStore.EXTRA_OUTPUT, output)
                intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                intent.clipData = ClipData.newRawUri("OEA capture", output)
            }
            host.startActivityForResult(intent, requestCode)
        } catch (_: Throwable) {
            prefs(host).edit().remove(KEY_PENDING_SYSTEM_CAPTURE).apply()
            if (isExternalCapture(host)) {
                host.setResult(Activity.RESULT_CANCELED)
                host.finish()
            } else {
                toast(host, "No camera app is available to fall back to")
            }
        }
    }

    // --------------------------------------------------------------- QR scanning

    /**
     * OEA reads QR codes itself: Camera2 frames into ZXing, entirely on the
     * phone. The previous version asked for the standalone zxing *client* app,
     * which is not installed on essentially any phone, so QR mode did nothing.
     */
    private fun openQrScanner(host: Activity, kit: OeaKit, palette: OeaPalette, onCreated: (Dialog) -> Unit) {
        if (host.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            toast(host, "Camera permission is needed to scan a code")
            return
        }
        val dialog = Dialog(host, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = FrameLayout(host)
        val scanner = QrScannerView(host)
        root.addView(scanner, FrameLayout.LayoutParams(-1, -1))
        val top = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(kit.dp(16), kit.dp(36), kit.dp(16), kit.dp(12))
        }
        top.addView(
            kit.circleIconButton(R.drawable.oic_close, "Close scanner", 0x66000000, Color.WHITE) { dialog.dismiss() },
            LinearLayout.LayoutParams(kit.dp(44), kit.dp(44))
        )
        top.addView(kit.text("Scan a code", 18f, Color.WHITE, OeaType.medium).apply { setPadding(kit.dp(14), 0, 0, 0) })
        root.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        val bottom = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(kit.dp(20), kit.dp(12), kit.dp(20), kit.dp(40))
        }
        bottom.addView(
            kit.quietButton("Torch") { scanner.setTorch(!scanner.torch) },
            LinearLayout.LayoutParams(0, kit.dp(48), 1f).apply { rightMargin = kit.dp(10) }
        )
        bottom.addView(
            kit.quietButton("From a picture") {
                dialog.dismiss()
                runCatching {
                    host.startActivityForResult(
                        Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE),
                        REQUEST_QR_PICTURE
                    )
                }.onFailure { toast(host, "No picture picker is available") }
            },
            LinearLayout.LayoutParams(0, kit.dp(48), 1f)
        )
        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        scanner.onResult = { text ->
            dialog.dismiss()
            showScanResult(host, kit, palette, text)
        }
        scanner.onError = { message -> toast(host, message) }
        dialog.setContentView(root)
        dialog.setOnShowListener { scanner.start() }
        dialog.setOnDismissListener { scanner.stop() }
        dialog.show()
        onCreated(dialog)
    }

    private fun decodeQrPicture(host: Activity, uri: Uri) {
        CAPTURE.execute {
            val text = runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                host.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 3000) sample *= 2
                val bitmap = host.contentResolver.openInputStream(uri)
                    ?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
                bitmap?.let { QrCodes.decode(it) }.also { bitmap?.recycle() }
            }.getOrNull()
            host.runOnUiThread {
                if (host.isDestroyed) return@runOnUiThread
                if (text.isNullOrBlank()) {
                    toast(host, "No QR code found in that picture")
                } else {
                    showScanResult(host, OeaKit(host, OeaTheme.resolve(host)), OeaTheme.resolve(host), text)
                }
            }
        }
    }

    private fun showScanResult(host: Activity, kit: OeaKit, palette: OeaPalette, text: String) {
        val clipboard = runCatching { host.getSystemService(ClipboardManager::class.java) }.getOrNull()
        runCatching { clipboard?.setPrimaryClip(ClipData.newPlainText("QR result", text)) }
        val link = runCatching { Uri.parse(text) }.getOrNull()?.takeIf { it.scheme == "http" || it.scheme == "https" }
        kit.sheet("Code read", if (link != null) "A link — copied to the clipboard" else "Copied to the clipboard") { panel, dialog ->
            panel.addView(
                kit.text(text, 15f, palette.text).apply {
                    setPadding(0, kit.dp(4), 0, kit.dp(12))
                    maxLines = 8
                }
            )
            panel.addView(
                kit.buttonRow(
                    kit.quietButton("Copy") {
                        runCatching { clipboard?.setPrimaryClip(ClipData.newPlainText("QR result", text)) }
                        toast(host, "Copied")
                        dialog.dismiss()
                    },
                    if (link != null) kit.tonalButton("Open link") {
                        dialog.dismiss()
                        runCatching {
                            host.startActivity(Intent(Intent.ACTION_VIEW, link).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }.onFailure { toast(host, "Nothing can open that link") }
                    } else kit.tonalButton("Search") {
                        dialog.dismiss()
                        runCatching {
                            host.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode(text)}"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }.onFailure { toast(host, "No browser is available") }
                    },
                    kit.primaryButton("Done") { dialog.dismiss() }
                )
            )
        }
    }

    // ------------------------------------------------------------------ developing

    /**
     * The whole post-capture pipeline, on the develop thread: optional pixel
     * work, optional aspect crop, EXIF (orientation, maker, time, GPS), the file
     * itself, MediaStore and the scanner.
     *
     * [jpeg] is the camera's own still (full resolution, orientation in its
     * EXIF). [upright] is the fallback: the preview texture already rotated and
     * mirrored for display, so it needs orientation 1.
     */
    private fun saveFrame(
        host: Activity,
        jpeg: ByteArray?,
        upright: Bitmap?,
        mode: CaptureMode,
        filter: FilterMode,
        privacy: Boolean,
        aspect: Float,
        hdr: Boolean,
        orientationTag: Int,
        withLocation: Boolean
    ): File? {
        val needsPixels = filter != FilterMode.NONE || hdr || OeaCameraEffects.needed(mode, privacy) || aspect > 0f
        if (jpeg != null && !needsPixels) {
            // Nothing to develop: the camera's own JPEG is the best possible file.
            val file = newPhotoFile(host, filter)
            val written = runCatching { file.outputStream().use { it.write(jpeg) }; true }.getOrDefault(false)
            if (!written) {
                file.delete()
                return null
            }
            writeExif(host, file, orientationTag, withLocation)
            publish(host, file)
            return file
        }
        val decoded = jpeg?.let { decodeJpeg(it, 3000) }
        val source = decoded ?: upright ?: return null
        val result = saveProcessed(
            host = host,
            source = source,
            mode = mode,
            filter = filter,
            privacy = privacy,
            aspect = aspect,
            hdr = hdr,
            orientationTag = if (decoded != null) orientationTag else 1,
            withLocation = withLocation
        )
        decoded?.recycle()
        upright?.recycle()
        return result
    }

    /** Effects + filter + aspect crop + JPEG encode, on whatever thread calls it. */
    private fun saveProcessed(
        host: Context,
        source: Bitmap,
        mode: CaptureMode,
        filter: FilterMode,
        privacy: Boolean,
        aspect: Float,
        hdr: Boolean,
        orientationTag: Int,
        withLocation: Boolean
    ): File? {
        var working = source
        if (OeaCameraEffects.needed(mode, privacy)) {
            val processed = runCatching { OeaCameraEffects.process(working, mode, privacy, focusBandY) }.getOrDefault(working)
            if (processed !== working && working !== source) working.recycle()
            working = processed
        }
        if (filter != FilterMode.NONE) {
            val filtered = applyFilter(working, filter)
            if (filtered !== working && working !== source) working.recycle()
            working = filtered
        }
        if (hdr) {
            val pushed = applyContrast(working)
            if (pushed !== working && working !== source) working.recycle()
            working = pushed
        }
        // The requested ratio is the sensor buffer's ratio, which is the same thing
        // the preview size and the still size were chosen with; a rotated photo is
        // rotated by its EXIF tag afterwards, so cropping in buffer space is right.
        if (aspect > 0f) {
            val rect = OeaCameraOptics.cropRect(working.width, working.height, aspect)
            val croppedWidth = rect[2] - rect[0]
            val croppedHeight = rect[3] - rect[1]
            if (croppedWidth > 0 && croppedHeight > 0 && (croppedWidth != working.width || croppedHeight != working.height)) {
                val cropped = runCatching { Bitmap.createBitmap(working, rect[0], rect[1], croppedWidth, croppedHeight) }.getOrNull()
                if (cropped != null && cropped !== working) {
                    if (working !== source) working.recycle()
                    working = cropped
                }
            }
        }
        val file = newPhotoFile(host, filter)
        val written = runCatching {
            file.outputStream().use { out -> working.compress(Bitmap.CompressFormat.JPEG, 92, out) }
            true
        }.getOrDefault(false)
        if (working !== source) working.recycle()
        if (!written) {
            file.delete()
            return null
        }
        writeExif(host, file, orientationTag, withLocation)
        publish(host, file)
        return file
    }

    private fun newPhotoFile(context: Context, filter: FilterMode): File {
        val dir = File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES), "OEA")
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        return File(dir, "OEA_${stamp}_${filter.name.lowercase(Locale.US)}.jpg")
    }

    private fun decodeJpeg(bytes: ByteArray, maxEdge: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxEdge) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    private fun applyFilter(src: Bitmap, filter: FilterMode): Bitmap = runCatching {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint()
        val matrix = android.graphics.ColorMatrix()
        when (filter) {
            FilterMode.MONO -> matrix.setSaturation(0f)
            FilterMode.SEPIA -> matrix.set(
                floatArrayOf(
                    0.393f, 0.769f, 0.189f, 0f, 0f,
                    0.349f, 0.686f, 0.168f, 0f, 0f,
                    0.272f, 0.534f, 0.131f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            FilterMode.VIVID -> matrix.setScale(1.2f, 1.2f, 1.2f, 1f)
            FilterMode.COOL -> matrix.set(
                floatArrayOf(
                    1f, 0f, 0f, 0f, 0f,
                    0f, 1f, 0f, 0f, 0f,
                    0f, 0f, 1.2f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            FilterMode.WARM -> matrix.set(
                floatArrayOf(
                    1.2f, 0f, 0f, 0f, 0f,
                    0f, 1f, 0f, 0f, 0f,
                    0f, 0f, 0.8f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            FilterMode.NOIR -> {
                matrix.setSaturation(0f)
                matrix.postConcat(android.graphics.ColorMatrix().apply { setScale(1.1f, 1.1f, 1.1f, 1f) })
            }
            else -> return src
        }
        paint.colorFilter = android.graphics.ColorMatrixColorFilter(matrix)
        canvas.drawBitmap(src, 0f, 0f, paint)
        out
    }.getOrDefault(src)

    private fun applyContrast(src: Bitmap): Bitmap = runCatching {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply {
            colorFilter = android.graphics.ColorMatrixColorFilter(
                android.graphics.ColorMatrix().apply { setScale(1.1f, 1.1f, 1.1f, 1f) }
            )
        }
        Canvas(out).drawBitmap(src, 0f, 0f, paint)
        out
    }.getOrDefault(src)

    private fun writeExif(context: Context, file: File, orientationTag: Int, withLocation: Boolean) {
        runCatching {
            val exif = ExifInterface(file.absolutePath)
            if (orientationTag != 1) {
                exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientationTag.toString())
            }
            exif.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER ?: "OEA")
            exif.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL ?: "OEA Camera")
            exif.setAttribute(
                ExifInterface.TAG_DATETIME,
                SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date())
            )
            if (withLocation) {
                lastKnownLocation(context)?.let { (latitude, longitude) -> exif.setLatLong(latitude, longitude) }
            }
            exif.saveAttributes()
        }
    }

    private fun lastKnownLocation(context: Context): Pair<Double, Double>? {
        val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return null
        return runCatching {
            val manager = context.getSystemService(android.location.LocationManager::class.java) ?: return null
            val location = manager.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                ?: manager.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                ?: return null
            location.latitude to location.longitude
        }.getOrNull()
    }

    /** Makes the file visible to EMAGE, the system gallery and other apps, once. */
    private fun publish(context: Context, file: File) {
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/OEA")
                    put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)?.let { uri ->
                    resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                    resolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                        null,
                        null
                    )
                }
            }
        }
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/jpeg"), null)
        runCatching { OeaThumbnails.invalidateAll() }
        // The home-screen camera widget shows the newest photo, so it has to be
        // told that there is a new one.
        runCatching { OeaWidgetStudio.refreshWidgets(context, "camera") }
    }

    private fun toast(host: Activity, message: String) {
        runCatching { android.widget.Toast.makeText(host, message, android.widget.Toast.LENGTH_SHORT).show() }
    }

    // ------------------------------------------------------------- captured sheet

    private fun showCapturedSheet(host: Activity, kit: OeaKit, file: File) {
        val fileUri = providerUri(host, file) ?: return
        kit.sheet("Photo saved", file.name) { panel, dialog ->
            val preview = ImageView(host).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = kit.rounded(Color.BLACK, 16)
                clipToOutline = true
            }
            panel.addView(preview, LinearLayout.LayoutParams(-1, kit.dp(240)).apply { bottomMargin = kit.dp(10) })
            OeaThumbnails.load(preview, fileUri, kit.dp(320), tag = "camera-sheet")
            panel.addView(
                kit.buttonRow(
                    kit.quietButton("Share") {
                        dialog.dismiss()
                        runCatching {
                            host.startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "image/*"
                                        putExtra(Intent.EXTRA_STREAM, fileUri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    },
                                    "Share photo"
                                )
                            )
                        }.onFailure { toast(host, "No app can share that picture") }
                    },
                    kit.tonalButton("Edit in EMAGE") {
                        dialog.dismiss()
                        runCatching {
                            host.startActivity(
                                Intent(host, OeaImageActivity::class.java).apply {
                                    action = Intent.ACTION_EDIT
                                    setDataAndType(fileUri, "image/*")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                            )
                        }.onFailure { toast(host, "EMAGE could not open that picture") }
                    },
                    kit.primaryButton("Close") { dialog.dismiss() }
                )
            )
            panel.addView(
                kit.tonalButton("Delete") {
                    dialog.dismiss()
                    kit.confirm("Delete photo?", file.name, "Delete", destructive = true) {
                        file.delete()
                        runCatching { OeaThumbnails.invalidateAll() }
                    }
                },
                LinearLayout.LayoutParams(-1, kit.dp(46)).apply { topMargin = kit.dp(8) }
            )
            // The modes that change pixels are also available after the fact, so a
            // picture taken in "Photo" is not stuck being a plain picture.
            val touchUps = kit.group(panel)
            kit.row(touchUps, R.drawable.oic_ac_unit, "Brighten the shadows", "Night pass, on this file") {
                applyEffectToFile(host, file, CaptureMode.NIGHT, false, preview)
            }
            kit.row(touchUps, R.drawable.oic_visibility_off, "Blur every face", "Android's own face detector, then a smudge") {
                applyEffectToFile(host, file, CaptureMode.PHOTO, true, preview)
            }
            kit.row(touchUps, R.drawable.oic_layers, "Soften the background", "Portrait pass, on this file") {
                applyEffectToFile(host, file, CaptureMode.PORTRAIT, false, preview)
            }
            kit.row(touchUps, R.drawable.oic_text_fields, "Flatten into a scan", "Document pass: white paper, dark ink") {
                applyEffectToFile(host, file, CaptureMode.DOC, false, preview)
            }
        }
    }

    private fun applyEffectToFile(host: Activity, file: File, mode: CaptureMode, blurFaces: Boolean, preview: ImageView) {
        CAPTURE.execute {
            val done = runCatching { OeaCameraEffects.applyToFile(file, mode, blurFaces) }.getOrDefault(false)
            host.runOnUiThread {
                if (host.isDestroyed) return@runOnUiThread
                if (!done) {
                    toast(host, "OEA could not rewrite that file")
                    return@runOnUiThread
                }
                val uri = providerUri(host, file)
                if (uri != null) {
                    runCatching { OeaThumbnails.invalidateAll() }
                    OeaThumbnails.load(preview, uri, dpOf(host, 320), tag = "camera-sheet-${System.currentTimeMillis()}")
                }
                toast(host, "${mode.label} pass applied")
            }
        }
    }

    // ------------------------------------------------------------------- settings

    private fun showSettings(host: Activity, kit: OeaKit, palette: OeaPalette, aspect: AspectMode, onAspect: (AspectMode) -> Unit) {
        kit.sheet("Camera settings", "OEA's own camera preferences") { panel, _ ->
            val p = prefs(host)
            val group = kit.group(panel)
            kit.switchRow(
                group,
                R.drawable.oic_visibility_off,
                "Blur faces on every shot",
                "OEA smudges each face it can find before saving",
                privacyBlur(host)
            ) { setPrivacyBlur(host, it) }
            kit.switchRow(
                group,
                R.drawable.oic_volume_up,
                "Shutter sound",
                "Play the system shutter click when a frame is taken",
                p.getBoolean(KEY_SHUTTER_SOUND, true)
            ) { p.edit().putBoolean(KEY_SHUTTER_SOUND, it).apply() }
            kit.switchRow(
                group,
                R.drawable.oic_location_on,
                "Location tag",
                "Write GPS into the photo's EXIF (needs location permission)",
                p.getBoolean(KEY_LOCATION, false)
            ) { enabled ->
                if (enabled && host.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    runCatching { host.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQUEST_LOCATION_PERMISSION) }
                }
                p.edit().putBoolean(KEY_LOCATION, enabled).apply()
            }
            kit.switchRow(
                group,
                R.drawable.oic_contrast,
                "Contrast push (HDR-style)",
                "One frame, lifted contrast — no second exposure is taken, because a phone camera hands OEA one image at a time",
                p.getBoolean(KEY_HDR, false)
            ) { p.edit().putBoolean(KEY_HDR, it).apply() }
            kit.stepperRow(
                group,
                R.drawable.oic_layers,
                "Burst",
                "Frames per shutter tap, each saved on its own",
                burstCount(host),
                1,
                10,
                { frames -> if (frames == 1) "1 frame" else "$frames frames" }
            ) { setBurstCount(host, it) }

            kit.sectionTitle(panel, "Aspect")
            val aspectRow = LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(kit.dp(6), 0, kit.dp(6), kit.dp(10))
            }
            AspectMode.values().forEach { mode ->
                val chip = kit.chip(mode.label, mode == aspect) { onAspect(mode) }
                aspectRow.addView(chip, LinearLayout.LayoutParams(-2, kit.dp(38)).apply { rightMargin = kit.dp(6) })
            }
            panel.addView(kit.hscroll(aspectRow))
            kit.note(
                panel,
                "The aspect chip sets the preview buffer and crops the saved file from the sensor's own frame — it is a crop, never a stretch. Camera2 all the way down: the still is the camera's JPEG, the torch is OEA's, tap-to-focus uses the focus regions this lens declares, and Pro's ISO/EV/white-balance/focus only reach what this hardware exposes (OEA hides what it refuses). Filters are OEA's own ColorMatrix. Night, Portrait and Document rewrite the pixels themselves: there is no depth sensor in this app, so nothing pretends to measure distance. Face blur is Android's FaceDetector over a small copy of the frame."
            )
        }
    }

    // ----------------------------------------------------------------- grid overlay

    private class GridOverlayView(ctx: Context, private var mode: GridMode) : View(ctx) {
        private val paint = Paint().apply {
            color = Color.WHITE
            alpha = 80
            strokeWidth = 1f
            style = Paint.Style.STROKE
        }

        fun setMode(m: GridMode) {
            mode = m
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (mode == GridMode.OFF) return
            val w = width.toFloat()
            val h = height.toFloat()
            when (mode) {
                GridMode.GRID_3X3 -> {
                    for (i in 1..2) {
                        canvas.drawLine(w * i / 3f, 0f, w * i / 3f, h, paint)
                        canvas.drawLine(0f, h * i / 3f, w, h * i / 3f, paint)
                    }
                }
                GridMode.GRID_4X4 -> {
                    for (i in 1..3) {
                        canvas.drawLine(w * i / 4f, 0f, w * i / 4f, h, paint)
                        canvas.drawLine(0f, h * i / 4f, w, h * i / 4f, paint)
                    }
                }
                GridMode.GOLDEN -> {
                    val phi = 0.618f
                    canvas.drawLine(w * phi, 0f, w * phi, h, paint)
                    canvas.drawLine(w * (1 - phi), 0f, w * (1 - phi), h, paint)
                    canvas.drawLine(0f, h * phi, w, h * phi, paint)
                    canvas.drawLine(0f, h * (1 - phi), w, h * (1 - phi), paint)
                }
                GridMode.DIAGONAL -> {
                    canvas.drawLine(0f, 0f, w, h, paint)
                    canvas.drawLine(w, 0f, 0f, h, paint)
                    canvas.drawLine(w / 2f, 0f, w / 2f, h, paint)
                    canvas.drawLine(0f, h / 2f, w, h / 2f, paint)
                }
                else -> Unit
            }
        }
    }

    // ------------------------------------------------------------------- the camera

    /**
     * The Camera2 session: preview transform, still capture, focus, zoom, flash,
     * pro controls and video.
     *
     * Threading: every camera call is made from one dedicated thread, and every
     * callback that touches the UI is posted to the main thread. The old version
     * used the main thread for all of it and opened the camera without waiting
     * for the surface, which is how it ended up with a black preview on slower
     * devices.
     */
    private class AdvancedCameraHelper(
        private val activity: Activity,
        private val textureView: TextureView,
        initialFront: Boolean,
        initialZoom: Float,
        initialFlash: FlashMode,
        initialAspect: AspectMode,
        private val onStatus: (String?) -> Unit,
        private val onReady: () -> Unit
    ) {
        private val main = Handler(Looper.getMainLooper())
        private var thread: HandlerThread? = null
        private var handler: Handler? = null

        private var manager: CameraManager? = null
        private var device: CameraDevice? = null
        private var session: CameraCaptureSession? = null
        private var previewRequest: CaptureRequest.Builder? = null
        private var previewSurface: Surface? = null
        private var jpegReader: ImageReader? = null
        private var previewSize: Size? = null
        private var sensorOrientation = 90
        private var cameraId: String? = null
        private var opening = false
        private var running = false
        private var previewMatrix: Matrix? = null

        private class PendingCapture(val queuedAt: Long, val callback: (ByteArray?) -> Unit)

        private val captures = ArrayDeque<PendingCapture>()
        private var watchdog: Runnable? = null

        private var front: Boolean = initialFront
        private var zoomValue: Float = initialZoom.coerceIn(1f, 8f)
        private var flashMode: FlashMode = initialFlash
        private var aspectMode: AspectMode = initialAspect

        private var recorder: MediaRecorder? = null
        private var recordingFile: File? = null
        private var recordPreviewSurface: Surface? = null

        private val surfaceListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                openIfReady()
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                applyPreviewTransform()
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                closeCamera()
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }

        /**
         * Camera objects belong to the camera thread. Every entry point that
         * changes a request hops onto it, so a slider drag and the session
         * callback can never rewrite the same builder at the same moment.
         */
        private fun onCamera(block: () -> Unit) {
            val h = handler
            if (h == null || Looper.myLooper() == h.looper) block() else h.post(block)
        }

        fun hasCamera(): Boolean = runCatching {
            val cm = activity.getSystemService(CameraManager::class.java)
            cm != null && cm.cameraIdList.isNotEmpty()
        }.getOrDefault(false)

        fun isOpen(): Boolean = device != null

        fun start() {
            if (running) return
            running = true
            manager = runCatching { activity.getSystemService(CameraManager::class.java) }.getOrNull()
            if (thread == null) {
                val created = HandlerThread("OeaCamera2")
                created.start()
                thread = created
                handler = Handler(created.looper)
            }
            textureView.surfaceTextureListener = surfaceListener
            if (textureView.isAvailable) openIfReady()
        }

        /** Called when something ends a recording the user did not stop themselves. */
        var onRecordingAbandoned: ((File) -> Unit)? = null

        fun stop() {
            running = false
            val abandoned = endRecording(keepFile = true)
            if (abandoned != null) main.post { onRecordingAbandoned?.invoke(abandoned) }
            closeCamera()
            deliverPending(null)
            val finished = thread
            thread = null
            handler = null
            runCatching { finished?.quitSafely() }
            main.post { onStatus(null) }
        }

        /** Called when the screen goes to the background: release the sensor, keep the UI. */
        fun pauseNow() {
            stop()
        }

        fun resumeNow() {
            start()
        }

        fun retry() {
            main.post { onStatus(null) }
            onCamera {
                closeCamera()
                openIfReady()
            }
        }

        /**
         * Front/rear swap. The old camera is closed first — a phone will refuse to
         * open the second lens while the first still holds it — and the zoom is
         * re-applied to whichever lens answers, because the new sensor has its own
         * crop limits.
         */
        fun switchCamera(wantFront: Boolean) {
            if (wantFront == front) return
            front = wantFront
            main.post { onStatus(null) }
            deliverPending(null)
            onCamera {
                closeCamera()
                openIfReady()
            }
        }

        fun setZoom(value: Float) {
            zoomValue = value.coerceIn(1f, 8f)
            onCamera {
                val builder = previewRequest ?: return@onCamera
                applyZoom(builder)
                updateRepeating()
            }
        }

        fun setFlash(mode: FlashMode) {
            flashMode = mode
            onCamera {
                val builder = previewRequest ?: return@onCamera
                set(builder, CaptureRequest.FLASH_MODE, if (mode == FlashMode.TORCH) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF)
                set(builder, CaptureRequest.CONTROL_AE_MODE, aeMode(mode))
                updateRepeating()
            }
        }

        fun setAspect(mode: AspectMode) {
            if (mode == aspectMode) return
            aspectMode = mode
            // The preview buffer and the still size follow the aspect, so the
            // session has to be rebuilt; the camera stays open.
            onCamera { recreateSession() }
        }

        fun jpegOrientation(): Int =
            OeaCameraOptics.exifOrientation(sensorOrientation, deviceRotationDegrees(), front)

        /** The preview as it looks on screen, for the fallback path. */
        fun snapshot(): Bitmap? {
            val raw = runCatching { textureView.bitmap }.getOrNull() ?: return null
            val matrix = previewMatrix ?: return raw
            return runCatching { Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true) }.getOrDefault(raw)
        }

        // ------------------------------------------------------------- opening

        private fun openIfReady() {
            if (!running) return
            if (device != null || opening) return
            if (textureView.surfaceTexture == null) return
            if (activity.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                report("Camera permission is needed")
                return
            }
            val cm = manager ?: activity.getSystemService(CameraManager::class.java) ?: run {
                report("This phone has no camera service")
                return
            }
            manager = cm
            val id = pickCameraId(cm, front) ?: run {
                report(if (front) "This phone has no front camera" else "This phone has no rear camera")
                return
            }
            cameraId = id
            sensorOrientation = runCatching { cm.getCameraCharacteristics(id).get(CameraCharacteristics.SENSOR_ORIENTATION) }.getOrNull() ?: 90
            opening = true
            try {
                cm.openCamera(id, deviceCallback, handler)
            } catch (error: Throwable) {
                opening = false
                report("The camera could not be opened: ${error.message ?: "unknown error"}")
                offerSystemCamera()
            }
        }

        private fun pickCameraId(manager: CameraManager, wantFront: Boolean): String? {
            val wanted = if (wantFront) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            val ids = runCatching { manager.cameraIdList.toList() }.getOrDefault(emptyList())
            val matching = ids.filter { id ->
                runCatching { manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == wanted }.getOrDefault(false)
            }
            // Several rear cameras can exist (ultra-wide, telephoto, a logical
            // multi-camera): the first one is the primary lens.
            return matching.firstOrNull()
        }

        private val deviceCallback = object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                opening = false
                device = camera
                createSession()
            }

            override fun onClosed(camera: CameraDevice) {
                opening = false
                if (device === camera) device = null
            }

            override fun onDisconnected(camera: CameraDevice) {
                opening = false
                if (device === camera) device = null
                runCatching { camera.close() }
                report("Another app took the camera")
            }

            override fun onError(camera: CameraDevice, error: Int) {
                opening = false
                if (device === camera) device = null
                runCatching { camera.close() }
                report(
                    when (error) {
                        ERROR_CAMERA_IN_USE -> "Another app is using the camera"
                        ERROR_MAX_CAMERAS_IN_USE -> "This phone cannot open another camera right now"
                        ERROR_CAMERA_DISABLED -> "The camera is disabled on this phone"
                        ERROR_CAMERA_DEVICE -> "The camera reported a hardware fault"
                        ERROR_CAMERA_SERVICE -> "The camera service stopped responding"
                        else -> "The camera could not start (error $error)"
                    }
                )
            }
        }

        private fun closeCamera() {
            runCatching { session?.close() }
            session = null
            previewRequest = null
            runCatching { device?.close() }
            device = null
            closeReader()
            runCatching { previewSurface?.release() }
            previewSurface = null
            opening = false
        }

        private fun closeReader() {
            runCatching { jpegReader?.close() }
            jpegReader = null
        }

        private fun characteristics(): CameraCharacteristics? {
            val id = cameraId ?: return null
            return runCatching { manager?.getCameraCharacteristics(id) }.getOrNull()
        }

        private fun streamConfig(): android.hardware.camera2.params.StreamConfigurationMap? =
            runCatching { characteristics()?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) }.getOrNull()

        private fun choosePreviewSize(): Size {
            val sizes = runCatching { streamConfig()?.getOutputSizes(SurfaceTexture::class.java)?.map { it.width to it.height } }
                .getOrNull()
                .orEmpty()
            val picked = OeaCameraOptics.bestAspectSize(sizes, aspectMode.ratio, 1920L * 1080L) ?: (1280 to 720)
            return Size(picked.first, picked.second)
        }

        private fun chooseJpegSize(): Size {
            val sizes = runCatching { streamConfig()?.getOutputSizes(ImageFormat.JPEG)?.map { it.width to it.height } }
                .getOrNull()
                .orEmpty()
            val picked = OeaCameraOptics.bestAspectSize(sizes, aspectMode.ratio, MAX_JPEG_PIXELS) ?: (1920 to 1080)
            return Size(picked.first, picked.second)
        }

        private fun chooseVideoSize(): Size {
            val sizes = runCatching { streamConfig()?.getOutputSizes(MediaRecorder::class.java)?.map { it.width to it.height } }
                .getOrNull()
                .orEmpty()
            val picked = OeaCameraOptics.bestAspectSize(sizes, aspectMode.ratio, 1920L * 1080L) ?: (1280 to 720)
            return Size(picked.first, picked.second)
        }

        // ------------------------------------------------------------- session

        private fun recreateSession() {
            val camera = device ?: return
            if (textureView.surfaceTexture == null) return
            runCatching { session?.close() }
            session = null
            createSession(camera)
        }

        private fun createSession() {
            val camera = device ?: return
            createSession(camera)
        }

        @Suppress("DEPRECATION")
        private fun createSession(camera: CameraDevice) {
            val texture = textureView.surfaceTexture ?: return
            val preview = choosePreviewSize()
            previewSize = preview
            texture.setDefaultBufferSize(preview.width, preview.height)
            val surface = Surface(texture)
            runCatching { previewSurface?.release() }
            previewSurface = surface
            closeReader()
            val jpegSize = chooseJpegSize()
            val reader = runCatching { ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2) }.getOrNull()
            jpegReader = reader
            if (reader != null) {
                reader.setOnImageAvailableListener({ r -> onJpegAvailable(r) }, handler)
            }
            var builder = runCatching { camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW) }.getOrNull()
            if (builder == null) {
                report("The camera refused a preview request")
                return
            }
            builder.addTarget(surface)
            if (reader != null) builder.addTarget(reader.surface)
            val previewBuilder = builder
            set(previewBuilder, CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            applyFocusDefaults(previewBuilder)
            set(previewBuilder, CaptureRequest.CONTROL_AE_MODE, aeMode(flashMode))
            set(previewBuilder, CaptureRequest.FLASH_MODE, if (flashMode == FlashMode.TORCH) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF)
            applyZoom(previewBuilder)
            previewRequest = previewBuilder
            val targets = ArrayList<Surface>()
            targets.add(surface)
            if (reader != null) targets.add(reader.surface)
            try {
                camera.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(configured: CameraCaptureSession) {
                        if (!running) {
                            runCatching { configured.close() }
                            return
                        }
                        session = configured
                        val started = runCatching { configured.setRepeatingRequest(previewBuilder.build(), null, handler) }
                        if (started.isFailure) {
                            report("The preview could not start: ${started.exceptionOrNull()?.message ?: "camera busy"}")
                            return
                        }
                        main.post {
                            onStatus(null)
                            applyPreviewTransform()
                            onReady()
                        }
                    }

                    override fun onConfigureFailed(failed: CameraCaptureSession) {
                        report("The camera refused this preview size")
                    }
                }, handler)
            } catch (error: Throwable) {
                report("The preview could not be created: ${error.message ?: "camera busy"}")
            }
        }

        private fun updateRepeating() {
            val builder = previewRequest ?: return
            val active = session ?: return
            runCatching { active.setRepeatingRequest(builder.build(), null, handler) }
        }

        private fun deviceRotationDegrees(): Int {
            val rotation = runCatching {
                (textureView.display ?: activity.windowManager.defaultDisplay).rotation
            }.getOrDefault(Surface.ROTATION_0)
            return when (rotation) {
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
        }

        /**
         * The preview transform. Without this the buffer is stretched into the
         * window's shape and, on a portrait phone, drawn sideways — which is
         * exactly what the first version did.
         */
        private fun applyPreviewTransform() {
            val size = previewSize ?: return
            val viewWidth = textureView.width.toFloat()
            val viewHeight = textureView.height.toFloat()
            if (viewWidth <= 0f || viewHeight <= 0f) return
            val rotation = OeaCameraOptics.rotationDegrees(sensorOrientation, deviceRotationDegrees(), front)
            val bufferWidth = size.width.toFloat()
            val bufferHeight = size.height.toFloat()
            val scale = OeaCameraOptics.coverScale(viewWidth, viewHeight, bufferWidth, bufferHeight, rotation)
            val matrix = Matrix()
            // Buffer centre to the view centre, rotate, scale to cover, mirror a
            // front camera — in that order, which is the order `post` composes.
            matrix.postTranslate(-bufferWidth / 2f, -bufferHeight / 2f)
            matrix.postRotate(rotation.toFloat())
            matrix.postScale(scale, scale)
            matrix.postTranslate(viewWidth / 2f, viewHeight / 2f)
            if (front) matrix.postScale(-1f, 1f, viewWidth / 2f, viewHeight / 2f)
            previewMatrix = matrix
            runCatching { textureView.setTransform(matrix) }
            // The focus square and the portrait band are placed in view space, so
            // they stay correct; the still is cropped from the buffer itself.
        }

        // ---------------------------------------------------------- still capture

        fun captureStill(callback: (ByteArray?) -> Unit) = onCamera {
            val camera = device
            val active = session
            val reader = jpegReader
            if (camera == null || active == null || reader == null) {
                main.post { callback(null) }
                return@onCamera
            }
            val builder = runCatching { camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE) }.getOrNull()
            if (builder == null) {
                main.post { callback(null) }
                return@onCamera
            }
            builder.addTarget(reader.surface)
            set(builder, CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            set(
                builder,
                CaptureRequest.CONTROL_AE_MODE,
                when (flashMode) {
                    FlashMode.ON -> CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                    FlashMode.AUTO -> CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH
                    else -> CameraMetadata.CONTROL_AE_MODE_ON
                }
            )
            applyFocusDefaults(builder)
            set(builder, CaptureRequest.FLASH_MODE, if (flashMode == FlashMode.TORCH) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF)
            set(builder, CaptureRequest.JPEG_ORIENTATION, jpegOrientation())
            set(builder, CaptureRequest.JPEG_QUALITY, 94.toByte())
            applyZoom(builder)
            synchronized(captures) { captures.addLast(PendingCapture(SystemClock.elapsedRealtime(), callback)) }
            armWatchdog()
            val request = builder.build()
            try {
                active.capture(request, object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                        deliverImage(null)
                    }
                }, handler)
            } catch (error: Throwable) {
                deliverImage(null)
            }
        }

        /**
         * Gives up on a capture that produced no image. It is a queue rather than
         * one timer per shot, because a burst queues several and a single late
         * timer would fail the wrong frame.
         */
        private fun armWatchdog() {
            if (watchdog != null) return
            val tick = object : Runnable {
                override fun run() {
                    val now = SystemClock.elapsedRealtime()
                    val expired = ArrayList<(ByteArray?) -> Unit>()
                    synchronized(captures) {
                        while (true) {
                            val head = captures.firstOrNull() ?: break
                            if (now - head.queuedAt < STILL_TIMEOUT_MS) break
                            expired += captures.removeFirst().callback
                        }
                    }
                    expired.forEach { late -> main.post { late(null) } }
                    if (synchronized(captures) { captures.isNotEmpty() }) {
                        handler?.postDelayed(this, 250L)
                    } else {
                        watchdog = null
                    }
                }
            }
            watchdog = tick
            handler?.postDelayed(tick, 250L)
        }

        private fun onJpegAvailable(reader: ImageReader) {
            val image = runCatching { reader.acquireNextImage() }.getOrNull()
            if (image == null) {
                deliverImage(null)
                return
            }
            val bytes = try {
                val buffer = image.planes[0].buffer
                ByteArray(buffer.remaining()).also { buffer.get(it) }
            } catch (_: Throwable) {
                null
            } finally {
                runCatching { image.close() }
            }
            deliverImage(bytes)
        }

        private fun deliverImage(bytes: ByteArray?) {
            val pending = synchronized(captures) { captures.removeFirstOrNull() } ?: return
            main.post { pending.callback(bytes) }
        }

        private fun deliverPending(bytes: ByteArray?) {
            while (true) {
                val pending = synchronized(captures) { captures.removeFirstOrNull() } ?: return
                main.post { pending.callback(bytes) }
            }
        }

        // ------------------------------------------------------------- focus / pro

        private fun applyFocusDefaults(builder: CaptureRequest.Builder) {
            val modes = runCatching { characteristics()?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) }.getOrNull()
            val wanted = when {
                modes == null -> CameraMetadata.CONTROL_AF_MODE_AUTO
                modes.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE) -> CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                modes.contains(CameraMetadata.CONTROL_AF_MODE_AUTO) -> CameraMetadata.CONTROL_AF_MODE_AUTO
                else -> modes.firstOrNull() ?: CameraMetadata.CONTROL_AF_MODE_OFF
            }
            set(builder, CaptureRequest.CONTROL_AF_MODE, wanted)
        }

        /**
         * Focus on the point under the finger. The tap is in view space; the
         * metering rectangle is in sensor space, inside the current zoom crop and
         * turned by the same rotation the preview uses.
         */
        fun focusAt(viewX: Float, viewY: Float, viewWidth: Int, viewHeight: Int) = onCamera {
            if (viewWidth <= 0 || viewHeight <= 0) return@onCamera
            val builder = previewRequest ?: return@onCamera
            val active = session ?: return@onCamera
            val chars = characteristics() ?: return@onCamera
            val sensor = runCatching { chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) }.getOrNull() ?: return@onCamera
            val crop = runCatching { builder.get(CaptureRequest.SCALER_CROP_REGION) }.getOrNull() ?: sensor
            val rotation = OeaCameraOptics.rotationDegrees(sensorOrientation, deviceRotationDegrees(), front)
            val quarterTurn = rotation == 90 || rotation == 270
            val rotatedWidth = if (quarterTurn) (previewSize?.height ?: sensor.height()).toFloat() else (previewSize?.width ?: sensor.width()).toFloat()
            val rotatedHeight = if (quarterTurn) (previewSize?.width ?: sensor.width()).toFloat() else (previewSize?.height ?: sensor.height()).toFloat()
            val scale = OeaCameraOptics.coverScale(
                viewWidth.toFloat(),
                viewHeight.toFloat(),
                (previewSize?.width ?: sensor.width()).toFloat(),
                (previewSize?.height ?: sensor.height()).toFloat(),
                rotation
            )
            // How much of the (rotated, scaled) buffer is actually on screen.
            val visibleFractionX = (viewWidth / (scale * rotatedWidth)).coerceIn(0.05f, 1f)
            val visibleFractionY = (viewHeight / (scale * rotatedHeight)).coerceIn(0.05f, 1f)
            var nx = (viewX / viewWidth - 0.5f) * visibleFractionX
            var ny = (viewY / viewHeight - 0.5f) * visibleFractionY
            // Undo the preview rotation to get back into buffer space.
            val bufferX: Float
            val bufferY: Float
            when (rotation) {
                90 -> {
                    bufferX = -ny
                    bufferY = nx
                }
                180 -> {
                    bufferX = -nx
                    bufferY = -ny
                }
                270 -> {
                    bufferX = ny
                    bufferY = -nx
                }
                else -> {
                    bufferX = nx
                    bufferY = ny
                }
            }
            val mirrored = if (front) -bufferX else bufferX
            val half = minOf(crop.width(), crop.height()) * 0.07f
            val centreX = crop.left + (mirrored + 0.5f) * crop.width()
            val centreY = crop.top + (bufferY + 0.5f) * crop.height()
            val left = (centreX - half).toInt().coerceIn(crop.left, crop.right - 1)
            val top = (centreY - half).toInt().coerceIn(crop.top, crop.bottom - 1)
            val right = (centreX + half).toInt().coerceIn(crop.left + 1, crop.right)
            val bottom = (centreY + half).toInt().coerceIn(crop.top + 1, crop.bottom)
            val metering = android.hardware.camera2.params.MeteringRectangle(Rect(left, top, right, bottom), 800)
            set(builder, CaptureRequest.CONTROL_AF_REGIONS, arrayOf(metering))
            set(builder, CaptureRequest.CONTROL_AE_REGIONS, arrayOf(metering))
            set(builder, CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
            set(builder, CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            runCatching { active.capture(builder.build(), null, handler) }
            // Clear the trigger and go back to continuous focus, or the lens stays
            // locked where it last focused for the rest of the session.
            handler?.postDelayed({
                set(builder, CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
                applyFocusDefaults(builder)
                updateRepeating()
            }, 1200L)
        }

        /**
         * What this lens lets OEA touch. The answer comes from the camera's own
         * characteristics, so the label under the Pro bars is a fact about this
         * device rather than a promise about cameras in general.
         */
        fun proStatus(): String {
            val chars = characteristics() ?: return "Camera is still opening"
            val ae = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            val iso = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val awb = chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
            val near = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            val parts = ArrayList<String>()
            parts += if (iso == null) "ISO locked by the camera" else "ISO ${iso.lower}–${iso.upper}"
            parts += if (ae == null) "no exposure compensation" else "EV ${ae.lower} to ${ae.upper}"
            parts += if ((awb?.size ?: 0) <= 2) "white balance fixed" else "white balance: ${awb?.size ?: 0} modes"
            parts += if (near <= 0f) "no manual focus" else "focus to ${"%.0f".format(100f / near)} cm"
            return parts.joinToString(" · ")
        }

        /** Writes the Pro values into the running request; unsupported values are skipped, not faked. */
        fun applyPro(isoPercent: Int, evPercent: Int, awbPick: Int, focusPercent: Int) = onCamera {
            val builder = previewRequest ?: return@onCamera
            val chars = characteristics() ?: return@onCamera
            val isoRange = runCatching { chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) }.getOrNull()
            val aeRange = runCatching { chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) }.getOrNull()
            val timeRange = runCatching { chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) }.getOrNull()
            val near = runCatching { chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) }.getOrNull() ?: 0f
            if (isoPercent > 0 && isoRange != null) {
                val span = isoRange.upper - isoRange.lower
                set(builder, CaptureRequest.SENSOR_SENSITIVITY, isoRange.lower + span * isoPercent / 100)
                if (timeRange != null) {
                    val fraction = 0.03 + 0.97 * (evPercent.coerceIn(0, 200) / 200f)
                    set(
                        builder,
                        CaptureRequest.SENSOR_EXPOSURE_TIME,
                        timeRange.lower + ((timeRange.upper - timeRange.lower) * fraction).toLong()
                    )
                }
                // Manual sensitivity means the auto-exposure loop has to be off, or
                // it simply overwrites what the slider asked for.
                set(builder, CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            } else {
                set(builder, CaptureRequest.CONTROL_AE_MODE, if (flashMode == FlashMode.AUTO) CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH else CameraMetadata.CONTROL_AE_MODE_ON)
                if (aeRange != null) {
                    val steps = aeRange.upper - aeRange.lower
                    set(builder, CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, aeRange.lower + steps * evPercent / 200)
                }
            }
            val wantedAwb = when (awbPick) {
                1 -> CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT
                2 -> CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT
                3 -> CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT
                4 -> CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
                else -> CameraMetadata.CONTROL_AWB_MODE_AUTO
            }
            val awbModes = runCatching { chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) }.getOrNull()
            val awb = if (awbPick == 0 || awbModes == null || awbModes.contains(wantedAwb)) wantedAwb else CameraMetadata.CONTROL_AWB_MODE_AUTO
            set(builder, CaptureRequest.CONTROL_AWB_MODE, awb)
            if (focusPercent > 0 && near > 0f) {
                set(builder, CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                set(builder, CaptureRequest.LENS_FOCUS_DISTANCE, OeaCameraOptics.focusDioptres(near, focusPercent))
            } else {
                applyFocusDefaults(builder)
            }
            updateRepeating()
        }

        private fun applyZoom(builder: CaptureRequest.Builder) {
            val chars = characteristics() ?: return
            val sensor = runCatching { chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) }.getOrNull() ?: return
            val maxZoom = runCatching { chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) }.getOrNull() ?: 4f
            val applied = zoomValue.coerceIn(1f, if (maxZoom > 1f) maxZoom else 1f)
            if (applied <= 1.001f) {
                set(builder, CaptureRequest.SCALER_CROP_REGION, sensor)
                return
            }
            val width = (sensor.width() / applied).toInt().coerceAtLeast(1)
            val height = (sensor.height() / applied).toInt().coerceAtLeast(1)
            val cx = sensor.centerX()
            val cy = sensor.centerY()
            val crop = Rect(cx - width / 2, cy - height / 2, cx + width / 2, cy + height / 2)
            set(builder, CaptureRequest.SCALER_CROP_REGION, crop)
        }

        private fun aeMode(mode: FlashMode): Int = when (mode) {
            FlashMode.AUTO -> CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH
            FlashMode.ON -> CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH
            else -> CameraMetadata.CONTROL_AE_MODE_ON
        }

        /** A request key this phone does not support is skipped, not fatal. */
        private fun <T> set(builder: CaptureRequest.Builder, key: CaptureRequest.Key<T>, value: T) {
            runCatching { builder.set(key, value) }
        }

        // ------------------------------------------------------------------ video

        fun startVideoRecording(withAudio: Boolean, captureRateHz: Float, onError: (String) -> Unit) = onCamera {
            val camera = device ?: run {
                onError("The camera is not ready")
                return@onCamera
            }
            val texture = textureView.surfaceTexture ?: run {
                onError("The preview is not ready")
                return@onCamera
            }
            try {
                val dir = File(activity.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES), "OEA")
                dir.mkdirs()
                val file = File(dir, "OEA_VID_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp4")
                recordingFile = file
                val size = chooseVideoSize()
                @Suppress("DEPRECATION")
                val created = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(activity) else MediaRecorder()
                created.apply {
                    setVideoSource(MediaRecorder.VideoSource.SURFACE)
                    if (withAudio) setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                    if (withAudio) {
                        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                        setAudioEncodingBitRate(128_000)
                        setAudioSamplingRate(44_100)
                    }
                    setVideoSize(size.width, size.height)
                    setVideoFrameRate(30)
                    setVideoEncodingBitRate(if (size.width >= 1920) 16_000_000 else 9_000_000)
                    // Without this a portrait recording plays on its side forever.
                    setOrientationHint(OeaCameraOptics.rotationDegrees(sensorOrientation, deviceRotationDegrees(), front))
                    if (captureRateHz > 0f) {
                        // The documented time-lapse pair: the encoder is told frames
                        // arrive at captureRateHz while the track is stamped at the
                        // normal frame rate, so the file plays back accelerated.
                        runCatching { setCaptureRate(captureRateHz.toDouble()) }
                    }
                    setOutputFile(file.absolutePath)
                    prepare()
                }
                recorder = created
                val recorderSurface = created.surface
                texture.setDefaultBufferSize(size.width, size.height)
                runCatching { recordPreviewSurface?.release() }
                val previewSurfaceForRecording = Surface(texture)
                recordPreviewSurface = previewSurfaceForRecording
                runCatching { session?.close() }
                session = null
                val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                builder.addTarget(previewSurfaceForRecording)
                builder.addTarget(recorderSurface)
                set(builder, CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                applyFocusDefaults(builder)
                if (captureRateHz > 0f) {
                    set(
                        builder,
                        CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                        android.util.Range(captureRateHz.toInt().coerceAtLeast(1), 30)
                    )
                }
                applyZoom(builder)
                val request = builder.build()
                camera.createCaptureSession(listOf(previewSurfaceForRecording, recorderSurface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(configured: CameraCaptureSession) {
                        session = configured
                        val started = runCatching {
                            configured.setRepeatingRequest(request, null, handler)
                            created.start()
                        }
                        if (started.isFailure) {
                            endRecording(keepFile = false)
                            onError("Recording could not start")
                        }
                    }

                    override fun onConfigureFailed(failed: CameraCaptureSession) {
                        endRecording(keepFile = false)
                        onError("The camera refused to record at this size")
                    }
                }, handler)
            } catch (error: Throwable) {
                endRecording(keepFile = false)
                onError("Recording could not start: ${error.message ?: "unknown error"}")
            }
        }

        /**
         * Closes the recorder on the camera thread and rebuilds the still session,
         * then hands the finished file back on the main thread. The UI never waits
         * for it, so a slow encoder cannot freeze the shutter.
         */
        fun stopVideoRecording(onDone: (File?) -> Unit) {
            onCamera {
                val finished = endRecording(keepFile = true)
                if (device != null) createSession()
                main.post { onDone(finished) }
            }
        }

        /** Returns the finished file when [keepFile] is true and something was recording. */
        private fun endRecording(keepFile: Boolean): File? {
            val active = recorder
            recorder = null
            if (active != null) {
                // stop() throws when no frames were ever written; the file is then
                // empty and is dropped by the caller's length check.
                runCatching { active.stop() }
                runCatching { active.reset() }
                runCatching { active.release() }
            }
            runCatching { session?.close() }
            session = null
            runCatching { recordPreviewSurface?.release() }
            recordPreviewSurface = null
            val file = recordingFile
            recordingFile = null
            if (!keepFile) {
                file?.delete()
                return null
            }
            if (active == null) return null
            return file?.takeIf { it.isFile && it.length() > 0L } ?: run {
                file?.delete()
                null
            }
        }

        // ----------------------------------------------------------------- status

        private fun report(message: String) {
            main.post { onStatus(message) }
        }

        private fun offerSystemCamera() {
            // Nothing to post: the screen shows "tap to use the system camera".
        }
    }
}
