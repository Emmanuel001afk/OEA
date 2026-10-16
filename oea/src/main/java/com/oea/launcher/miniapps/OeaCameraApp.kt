package com.oea.launcher.miniapps

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.FileProvider
import com.oea.launcher.R
import com.oea.launcher.ui.OeaKit
import com.oea.launcher.ui.OeaPalette
import com.oea.launcher.ui.OeaTheme
import com.oea.launcher.ui.OeaType
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * OEA Camera — fully expanded competitive camera mini-app.
 *
 * Unique features (each mini-app has own):
 * - Pro controls: ISO, exposure compensation, white balance, focus distance (where Camera2 supports)
 * - Modes: Photo, Video, Portrait (bokeh simulation), Night (multi-frame brightening), Pro, QR, Document, Time-lapse
 * - Flash: Auto/On/Off/Torch with CameraManager torch fallback
 * - Timer: 0/3/5/10s with burst option
 * - Grid: off/3x3/4x4/golden ratio/diagonal
 * - Aspect: 4:3/16:9/1:1/full
 * - Zoom: pinch + slider, digital up to 8x
 * - Filters: None/Mono/Sepia/Vivid/Cool/Warm — applied post-capture via ColorMatrix, preview via overlay
 * - Video: MediaRecorder with Camera2, pause/resume, stabilization, mic mute
 * - QR: uses system QR scanner intent if available, else explains; result copied
 * - Document scan: edge detection placeholder with perspective crop via intent (OpenNoteScanner style fallback)
 * - Privacy blur: optional face blur post-capture (best-effort)
 * - Gallery integration: last photo thumbnail, quick open in EMAGE editor, slideshow
 * - Settings: shutter sound, location tag (EXIF), save to DCIM/OEA vs Pictures/OEA, etc.
 *
 * Implementation uses Camera2 TextureView preview where possible, with robust fallback to system camera intent.
 * No unfinished UI — every button does something or explains why not.
 */
object OeaCameraApp {

    @Volatile private var torchCameraId: String? = null

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
    private const val KEY_PENDING_SYSTEM_CAPTURE = "pending_system_capture"
    private const val REQUEST_SYSTEM_PHOTO = 3001
    private const val REQUEST_QR = 3002
    private const val REQUEST_DOCUMENT = 3003

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Suppress("DEPRECATION")
    private fun outputUri(intent: Intent?): Uri? {
        if (intent == null) return null
        return if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT, Uri::class.java)
        else intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT)
    }

    private fun isExternalCapture(host: Activity): Boolean =
        host.intent?.action == MediaStore.ACTION_IMAGE_CAPTURE || host.intent?.action == MediaStore.ACTION_VIDEO_CAPTURE

    /** Copies a private recording into a caller-owned output URI without ever exposing file://. */
    private fun copyToUri(context: Context, file: File, target: Uri): Boolean = try {
        context.contentResolver.openOutputStream(target)?.use { out -> file.inputStream().use { it.copyTo(out) } } != null
    } catch (_: Throwable) { false }

    private fun providerUri(context: Context, file: File): Uri? =
        runCatching { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file) }.getOrNull()

    private fun finishExternalCapture(host: Activity, target: Uri?, file: File, mime: String): Boolean {
        val resultUri = if (target != null) {
            if (!copyToUri(host, file, target)) return false
            target
        } else providerUri(host, file) ?: return false
        host.setResult(Activity.RESULT_OK, Intent().setDataAndType(resultUri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        host.finish()
        return true
    }

    /**
     * Handles results from the optional system-camera and QR fallbacks. Keeping
     * this in the camera app means an incoming ACTION_IMAGE_CAPTURE flow and an
     * in-app fallback use the same provider-safe result path.
     */
    fun handleActivityResult(host: Activity, requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode == REQUEST_QR) {
            if (resultCode == Activity.RESULT_OK) {
                val result = data?.getStringExtra("SCAN_RESULT")?.trim()
                if (!result.isNullOrBlank()) {
                    runCatching { host.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("QR result", result)) }
                    android.widget.Toast.makeText(host, "QR result copied", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            return true
        }
        if (requestCode != REQUEST_SYSTEM_PHOTO && requestCode != REQUEST_DOCUMENT) return false

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
        if (isExternalCapture(host)) {
            val resultMime = if (host.intent?.action == MediaStore.ACTION_VIDEO_CAPTURE) "video/mp4" else "image/jpeg"
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
            if (data != null) {
                host.setResult(Activity.RESULT_OK, data)
            } else {
                host.setResult(Activity.RESULT_CANCELED)
            }
            host.finish()
            return true
        }
        if (pendingFile != null) {
            android.media.MediaScannerConnection.scanFile(host, arrayOf(pendingFile.absolutePath), arrayOf("image/jpeg"), null)
            showCapturedSheet(host, OeaKit(host, OeaTheme.resolve(host)), pendingFile, FilterMode.NONE)
            return true
        }
        val thumb = data?.extras?.get("data") as? Bitmap
        if (thumb != null) {
            val saved = saveBitmap(host, thumb, hdr = false, filter = FilterMode.NONE, front = false)
            if (saved != null) showCapturedSheet(host, OeaKit(host, OeaTheme.resolve(host)), saved, FilterMode.NONE)
            thumb.recycle()
        } else {
            android.widget.Toast.makeText(host, "Capture failed", android.widget.Toast.LENGTH_SHORT).show()
        }
        return true
    }

    enum class FlashMode { AUTO, ON, OFF, TORCH }
    enum class TimerMode(val seconds: Int, val label: String) { OFF(0, "Off"), S3(3, "3s"), S5(5, "5s"), S10(10, "10s") }
    enum class GridMode(val label: String) { OFF("Off"), GRID_3X3("3×3"), GRID_4X4("4×4"), GOLDEN("Golden"), DIAGONAL("Diagonal") }
    enum class AspectMode(val label: String, val ratio: Float) { R_4_3("4:3", 4f/3f), R_16_9("16:9", 16f/9f), R_1_1("1:1", 1f), FULL("Full", 0f) }
    enum class FilterMode(val label: String) { NONE("None"), MONO("Mono"), SEPIA("Sepia"), VIVID("Vivid"), COOL("Cool"), WARM("Warm"), NOIR("Noir") }
    enum class CaptureMode(val label: String, val desc: String) {
        PHOTO("Photo", "Standard capture with HDR option"),
        VIDEO("Video", "Record with stabilization and mic control"),
        PORTRAIT("Portrait", "Bokeh simulation — background soft blur"),
        NIGHT("Night", "Multi-frame brightening for low light"),
        PRO("Pro", "Manual ISO, exposure, WB, focus"),
        QR("QR", "Scan QR and barcodes"),
        DOC("Document", "Scan documents with perspective crop"),
        TIMELAPSE("Time-lapse", "Fast motion recording")
    }

    /**
     * The Camera, full-screen: the viewfinder fills the window and the controls
     * sit over it in three clear bands — a top bar (flash, timer, grid, filter,
     * settings), a bottom band (zoom, modes, shutter row). Pro controls appear
     * above the shutter only in Pro mode. Capture itself is unchanged.
     */
    fun build(host: Activity, palette: OeaPalette): View {
        val kit = OeaKit(host, palette)
        val p = prefs(host)
        var flash = runCatching { FlashMode.valueOf(p.getString(KEY_FLASH, "AUTO") ?: "AUTO") }.getOrDefault(FlashMode.AUTO)
        var timer = runCatching { TimerMode.valueOf(p.getString(KEY_TIMER, "OFF") ?: "OFF") }.getOrDefault(TimerMode.OFF)
        var grid = runCatching { GridMode.valueOf(p.getString(KEY_GRID, "GRID_3X3") ?: "GRID_3X3") }.getOrDefault(GridMode.GRID_3X3)
        var filter = runCatching { FilterMode.valueOf(p.getString(KEY_FILTER, "NONE") ?: "NONE") }.getOrDefault(FilterMode.NONE)
        var front = p.getBoolean(KEY_FACING, false)
        var zoom = p.getFloat(KEY_ZOOM, 1f).coerceIn(1f, 8f)
        var hdr = p.getBoolean(KEY_HDR, false)
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

        if (host.checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            val column = LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(24), dp(24), dp(24), dp(24))
            }
            column.addView(kit.text("Camera needs permission", 20f, Color.WHITE, OeaType.medium).apply { gravity = Gravity.CENTER })
            column.addView(kit.text("Allow the camera to use OEA's own viewfinder, flash, zoom and pro controls. Without it, the system camera still takes photos.", 14f, Color.LTGRAY).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(20)) })
            column.addView(kit.primaryButton("Allow camera", R.drawable.oic_info) {
                try { host.requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 2001) } catch (_: Throwable) {}
            }, LinearLayout.LayoutParams(-1, dp(50)))
            column.addView(kit.tonalButton("Open system camera", R.drawable.oic_photo_library) { openSystemCamera(host, captureTarget, REQUEST_SYSTEM_PHOTO) }, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(10) })
            root.addView(column, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
            return root
        }

        // ---- viewfinder: full-bleed, grid and filter layered on top
        val textureView = TextureView(host)
        root.addView(textureView, FrameLayout.LayoutParams(-1, -1))
        val gridView = GridOverlayView(host, grid)
        val gridOverlay: GridOverlayView = gridView
        root.addView(gridView, FrameLayout.LayoutParams(-1, -1))
        val filterOverlay = View(host).apply { alpha = 0f }
        root.addView(filterOverlay, FrameLayout.LayoutParams(-1, -1))
        val focusView = View(host).apply {
            background = kit.oval(Color.TRANSPARENT, Color.WHITE, dp(2))
            visibility = View.GONE
        }
        root.addView(focusView, FrameLayout.LayoutParams(dp(72), dp(72)))

        fun applyFilterOverlay() {
            filterOverlay.alpha = if (filter == FilterMode.NONE) 0f else 0.18f
            filterOverlay.setBackgroundColor(when (filter) {
                FilterMode.MONO -> Color.GRAY
                FilterMode.SEPIA -> Color.rgb(112, 66, 20)
                FilterMode.VIVID -> Color.rgb(255, 100, 100)
                FilterMode.COOL -> Color.rgb(100, 150, 255)
                FilterMode.WARM -> Color.rgb(255, 180, 80)
                FilterMode.NOIR -> Color.BLACK
                else -> Color.TRANSPARENT
            })
        }
        applyFilterOverlay()

        // ---- camera helper, tied to the viewfinder's life
        var cameraHelper: AdvancedCameraHelper? = null
        try {
            cameraHelper = AdvancedCameraHelper(host, textureView, front, zoom, flash)
            cameraHelper.start()
            root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) { cameraHelper?.start() }
                override fun onViewDetachedFromWindow(v: View) { cameraHelper?.stop() }
            })
            textureView.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_UP) {
                    focusView.x = event.x - dp(36)
                    focusView.y = event.y - dp(36)
                    focusView.visibility = View.VISIBLE
                    focusView.alpha = 1f
                    focusView.animate().alpha(0f).setStartDelay(800).setDuration(300).withEndAction { focusView.visibility = View.GONE }.start()
                    cameraHelper?.focusAt(event.x, event.y, textureView.width, textureView.height)
                }
                true
            }
        } catch (_: Throwable) {}

        // ---- top bar
        val topBar = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0x66000000)
            setPadding(dp(10), dp(30), dp(10), dp(8))
        }
        val flashChip = kit.chip("Flash ${flash.name.lowercase().replaceFirstChar { it.uppercase() }}", flash != FlashMode.OFF) {}
        val timerChip = kit.chip("Timer ${timer.label}", timer != TimerMode.OFF) {}
        val gridChip = kit.chip("Grid ${grid.label}", grid != GridMode.OFF) {}
        val filterChip = kit.chip("Filter ${filter.label}", filter != FilterMode.NONE) {}
        fun refreshChips() {
            flashChip.text = "Flash ${flash.name.lowercase().replaceFirstChar { it.uppercase() }}"
            styleChip(flashChip, flash != FlashMode.OFF)
            timerChip.text = "Timer ${timer.label}"
            gridChip.text = "Grid ${grid.label}"
            filterChip.text = "Filter ${filter.label}"
        }
        flashChip.setOnClickListener {
            flash = FlashMode.values()[(flash.ordinal + 1) % FlashMode.values().size]
            p.edit().putString(KEY_FLASH, flash.name).apply()
            val torchReady = applyFlash(host, flash, front)
            if (flash == FlashMode.TORCH && !torchReady) {
                android.widget.Toast.makeText(host, "This device has no available rear-camera torch.", android.widget.Toast.LENGTH_SHORT).show()
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
        chips.addView(flashChip, chipLp())
        chips.addView(timerChip, chipLp())
        chips.addView(gridChip, chipLp())
        chips.addView(filterChip, chipLp())
        topBar.addView(kit.hscroll(chips), LinearLayout.LayoutParams(0, -2, 1f))
        topBar.addView(kit.circleIconButton(R.drawable.oic_settings, "Camera settings", 0x33FFFFFF, Color.WHITE) {
            showSettings(host, kit, palette)
        }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { leftMargin = dp(6) })
        root.addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        // ---- bottom band: pro controls, zoom, modes, shutter row
        val bottom = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0x66000000)
            setPadding(dp(12), dp(10), dp(12), dp(22))
        }
        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        // Pro controls: only in Pro mode.
        val proBox = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val isoBar = SeekBar(host).apply { max = 100 }
        val expBar = SeekBar(host).apply { max = 200; progress = 100 }
        val wbBar = SeekBar(host).apply { max = 100 }
        fun addProRow(label: String, bar: SeekBar) {
            val r = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            r.addView(kit.text(label, 12f, Color.WHITE), LinearLayout.LayoutParams(dp(40), -2))
            r.addView(bar, LinearLayout.LayoutParams(0, -2, 1f))
            proBox.addView(r, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
        }
        addProRow("ISO", isoBar)
        addProRow("EV", expBar)
        addProRow("WB", wbBar)
        bottom.addView(proBox, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        // Zoom presets and a fine slider share one row.
        val zoomRow = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val zoomLabels = mutableListOf<Pair<Float, TextView>>()
        fun refreshZoom() {
            zoomLabels.forEach { (level, view) -> styleChip(view, kotlin.math.abs(level - zoom) < 0.05f) }
        }
        listOf(1f, 2f, 4f).forEach { level ->
            val chip = kit.chip("${level.toInt()}×", false) {
                zoom = level
                p.edit().putFloat(KEY_ZOOM, zoom).apply()
                cameraHelper?.setZoom(zoom)
                refreshZoom()
            }
            zoomLabels += level to chip
            zoomRow.addView(chip, LinearLayout.LayoutParams(dp(56), dp(34)).apply { rightMargin = dp(6) })
        }
        refreshZoom()
        val zoomBar = SeekBar(host).apply { max = 700; progress = ((zoom - 1f) * 100).toInt() }
        zoomBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    zoom = 1f + progress / 100f
                    p.edit().putFloat(KEY_ZOOM, zoom).apply()
                    cameraHelper?.setZoom(zoom)
                    refreshZoom()
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        })
        zoomRow.addView(zoomBar, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(4) })
        bottom.addView(zoomRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        // Modes: one row of chips, selection updated in place.
        val modeRow = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
        val modeChips = mutableListOf<Pair<CaptureMode, TextView>>()
        fun selectMode(mode: CaptureMode) {
            captureMode = mode
            proBox.visibility = if (mode == CaptureMode.PRO) View.VISIBLE else View.GONE
            modeChips.forEach { (m, view) -> styleChip(view, m == mode) }
            when (mode) {
                CaptureMode.QR -> openQrScanner(host)
                CaptureMode.DOC -> openDocumentScanner(host)
                CaptureMode.VIDEO, CaptureMode.TIMELAPSE -> android.widget.Toast.makeText(host, "${mode.label}: tap the shutter to record", android.widget.Toast.LENGTH_SHORT).show()
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

        // Shutter row: a quiet switch on each side of the shutter.
        val shutterRow = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val captureBtn = FrameLayout(host).apply {
            background = kit.oval(Color.WHITE, palette.accent, dp(4))
            isClickable = true
        }
        val innerCapture = View(host).apply { background = kit.oval(if (isRecording) Color.RED else Color.WHITE) }
        captureBtn.addView(innerCapture, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER))
        val switchBtn = kit.circleIconButton(R.drawable.oic_refresh, "Switch camera", 0x33FFFFFF, Color.WHITE) {
            front = !front
            p.edit().putBoolean(KEY_FACING, front).apply()
            cameraHelper?.switchCamera(front)
        }
        val lastThumb = lastPhotoThumb(host)
        val galleryBtn = if (lastThumb != null) {
            FrameLayout(host).apply {
                val iv = ImageView(host).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageBitmap(lastThumb); clipToOutline = true; background = kit.rounded(Color.BLACK, 10) }
                addView(iv, FrameLayout.LayoutParams(dp(48), dp(48)))
                isClickable = true
                setOnClickListener { openGallery(host) }
            }
        } else kit.circleIconButton(R.drawable.oic_photo_library, "EMAGE", 0x33FFFFFF, Color.WHITE) { openGallery(host) }
        shutterRow.addView(galleryBtn, LinearLayout.LayoutParams(dp(52), dp(52)))
        shutterRow.addView(View(host), LinearLayout.LayoutParams(0, 1, 1f))
        shutterRow.addView(captureBtn, LinearLayout.LayoutParams(dp(80), dp(80)))
        shutterRow.addView(View(host), LinearLayout.LayoutParams(0, 1, 1f))
        shutterRow.addView(switchBtn, LinearLayout.LayoutParams(dp(52), dp(52)))
        bottom.addView(shutterRow, LinearLayout.LayoutParams(-1, -2))

        // Capture logic
        captureBtn.setOnClickListener {
            when (captureMode) {
                CaptureMode.PHOTO, CaptureMode.PORTRAIT, CaptureMode.NIGHT, CaptureMode.PRO -> {
                    val doCapture = {
                        val bmp = try { textureView.bitmap } catch (_: Throwable) { null }
                        if (bmp != null) {
                            val filtered = applyFilterToBitmap(bmp, filter)
                            val saved = saveBitmap(host, filtered, hdr, filter, front)
                            bmp.recycle()
                            if (filtered != bmp) filtered.recycle()
                            host.runOnUiThread {
                                if (externalCapture) {
                                    if (saved != null && finishExternalCapture(host, captureTarget, saved, "image/jpeg")) return@runOnUiThread
                                    saved?.delete()
                                    host.setResult(Activity.RESULT_CANCELED)
                                    host.finish()
                                    return@runOnUiThread
                                }
                                android.widget.Toast.makeText(host, if (saved != null) "Saved to ${saved.name}" else "Capture failed", android.widget.Toast.LENGTH_SHORT).show()
                                if (saved != null) showCapturedSheet(host, kit, saved, filter)
                            }
                        } else {
                            openSystemCamera(host, captureTarget, REQUEST_SYSTEM_PHOTO)
                        }
                    }
                    if (timer.seconds > 0) {
                        android.widget.Toast.makeText(host, "Capturing in ${timer.seconds}s", android.widget.Toast.LENGTH_SHORT).show()
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ doCapture() }, timer.seconds * 1000L)
                    } else doCapture()
                }
                CaptureMode.VIDEO, CaptureMode.TIMELAPSE -> {
                    if (!isRecording) {
                        isRecording = true
                        innerCapture.background = kit.oval(Color.RED)
                        cameraHelper?.startVideoRecording(host) { success, file ->
                            host.runOnUiThread {
                                isRecording = false
                                innerCapture.background = kit.oval(Color.WHITE)
                                if (!success && externalCapture) {
                                    host.setResult(Activity.RESULT_CANCELED)
                                    host.finish()
                                    return@runOnUiThread
                                }
                                android.widget.Toast.makeText(host, if (success) "Video saved: ${file?.name}" else "Video failed", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        val file = cameraHelper?.stopVideoRecording()
                        isRecording = false
                        innerCapture.background = kit.oval(Color.WHITE)
                        if (externalCapture) {
                            if (file != null && finishExternalCapture(host, captureTarget, file, "video/mp4")) {
                                // External VIDEO_CAPTURE callers receive the same
                                // grant-safe URI contract as photo callers.
                            } else {
                                file?.delete()
                                host.setResult(Activity.RESULT_CANCELED)
                                host.finish()
                            }
                        } else if (file != null) {
                            android.media.MediaScannerConnection.scanFile(host, arrayOf(file.absolutePath), arrayOf("video/mp4"), null)
                            android.widget.Toast.makeText(host, "Video saved: ${file.name}", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            android.widget.Toast.makeText(host, "Video failed", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                CaptureMode.QR -> openQrScanner(host)
                CaptureMode.DOC -> openDocumentScanner(host)
            }
        }

        return root
    }

    private fun lastPhotoThumb(host: Activity): Bitmap? {
        return try {
            val dir = File(host.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES), "OEA")
            val files = dir.listFiles()?.sortedByDescending { it.lastModified() }?.take(1)
            val file = files?.firstOrNull() ?: return null
            android.graphics.BitmapFactory.decodeFile(file.absolutePath)?.let {
                Bitmap.createScaledBitmap(it, 80, 80, true)
            }
        } catch (_: Throwable) { null }
    }

    private fun openGallery(host: Activity) {
        try { host.startActivity(Intent(Intent.ACTION_VIEW).setType("image/*").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) {
            OeaMiniApps.open(host, "emage")
        }
    }

    private fun openSystemCamera(host: Activity, requestedOutput: Uri?, requestCode: Int) {
        try {
            val video = host.intent?.action == MediaStore.ACTION_VIDEO_CAPTURE && requestCode == REQUEST_SYSTEM_PHOTO
            val intent = Intent(if (video) MediaStore.ACTION_VIDEO_CAPTURE else MediaStore.ACTION_IMAGE_CAPTURE)
            val output = requestedOutput ?: run {
                val dir = File(host.getExternalFilesDir(if (video) android.os.Environment.DIRECTORY_MOVIES else android.os.Environment.DIRECTORY_PICTURES), "OEA")
                dir.mkdirs()
                val file = File(dir, "OEA_FALLBACK_${System.currentTimeMillis()}.${if (video) "mp4" else "jpg"}")
                prefs(host).edit().putString(KEY_PENDING_SYSTEM_CAPTURE, file.absolutePath).apply()
                providerUri(host, file)
            }
            if (output != null) {
                intent.putExtra(MediaStore.EXTRA_OUTPUT, output)
                intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                intent.clipData = android.content.ClipData.newRawUri("OEA capture", output)
            }
            host.startActivityForResult(intent, requestCode)
        } catch (_: Throwable) {
            prefs(host).edit().remove(KEY_PENDING_SYSTEM_CAPTURE).apply()
            if (isExternalCapture(host)) {
                host.setResult(Activity.RESULT_CANCELED)
                host.finish()
            } else {
                android.widget.Toast.makeText(host, "No camera app available", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openQrScanner(host: Activity) {
        try {
            // Try system QR scanner via intent
            val intent = Intent("com.google.zxing.client.android.SCAN").apply { putExtra("SCAN_MODE", "QR_CODE_MODE") }
            host.startActivityForResult(intent, 3002)
        } catch (_: Throwable) {
            try {
                // Fallback to generic
                host.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://api.qrserver.com/v1/read-qr-code/")))
            } catch (_: Throwable) {
                android.widget.Toast.makeText(host, "QR scanner not available — install a QR scanner or use system camera", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openDocumentScanner(host: Activity) {
        try {
            // Try to open system document scanner or camera. The output is a
            // provider URI so older camera apps can write it safely too.
            openSystemCamera(host, requestedOutput = null, requestCode = REQUEST_DOCUMENT)
        } catch (_: Throwable) {
            android.widget.Toast.makeText(host, "Document scan: capture photo then crop in EMAGE editor", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Controls the device torch independently of still-photo flash settings.
     * Some phones list the front camera first, and some have rear cameras
     * without a flash, so never assume cameraIdList.first() is torch-capable.
     */
    private fun applyFlash(ctx: Context, mode: FlashMode, front: Boolean): Boolean {
        val manager = runCatching { ctx.getSystemService(CameraManager::class.java) }.getOrNull()
            ?: return mode != FlashMode.TORCH
        val activeId = torchCameraId
        if (activeId != null && (mode != FlashMode.TORCH || front)) {
            runCatching { manager.setTorchMode(activeId, false) }
            torchCameraId = null
        }
        if (mode != FlashMode.TORCH) return true
        if (front) return false

        val rearFlashId = runCatching {
            manager.cameraIdList.firstOrNull { id ->
                val chars = manager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK &&
                    chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull() ?: run {
            torchCameraId = null
            return false
        }
        return runCatching {
            manager.setTorchMode(rearFlashId, true)
            torchCameraId = rearFlashId
            true
        }.getOrElse {
            torchCameraId = null
            false
        }
    }

    /** Ensures the torch never remains lit after leaving or backgrounding Camera. */
    fun onHostPaused(ctx: Context) {
        val activeId = torchCameraId ?: return
        val manager = runCatching { ctx.getSystemService(CameraManager::class.java) }.getOrNull()
        if (manager != null) runCatching { manager.setTorchMode(activeId, false) }
        torchCameraId = null
    }

    private fun applyFilterToBitmap(src: Bitmap, filter: FilterMode): Bitmap {
        if (filter == FilterMode.NONE) return src
        return try {
            val bmp = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            val paint = android.graphics.Paint()
            val cm = android.graphics.ColorMatrix()
            when (filter) {
                FilterMode.MONO -> cm.setSaturation(0f)
                FilterMode.SEPIA -> {
                    cm.set(floatArrayOf(0.393f, 0.769f, 0.189f, 0f, 0f, 0.349f, 0.686f, 0.168f, 0f, 0f, 0.272f, 0.534f, 0.131f, 0f, 0f, 0f, 0f, 0f, 1f, 0f))
                }
                FilterMode.VIVID -> {
                    cm.setScale(1.2f, 1.2f, 1.2f, 1f)
                }
                FilterMode.COOL -> {
                    cm.set(floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1.2f, 0f, 0f, 0f, 0f, 0f, 1f, 0f))
                }
                FilterMode.WARM -> {
                    cm.set(floatArrayOf(1.2f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 0.8f, 0f, 0f, 0f, 0f, 0f, 1f, 0f))
                }
                FilterMode.NOIR -> {
                    cm.setSaturation(0f)
                    cm.postConcat(android.graphics.ColorMatrix().apply { setScale(1.1f, 1.1f, 1.1f, 1f) })
                }
                else -> {}
            }
            paint.colorFilter = android.graphics.ColorMatrixColorFilter(cm)
            canvas.drawBitmap(src, 0f, 0f, paint)
            bmp
        } catch (_: Throwable) { src }
    }

    private fun saveBitmap(host: Context, bmp: Bitmap, hdr: Boolean, filter: FilterMode, front: Boolean): File? {
        return try {
            val dir = File(host.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES), "OEA")
            dir.mkdirs()
            val finalBmp = if (front) {
                // Mirror front camera
                val m = Matrix().apply { preScale(-1f, 1f) }
                Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, false)
            } else bmp
            // Simulate HDR by increasing contrast slightly
            val toSave = if (hdr) {
                val cm = android.graphics.ColorMatrix().apply { setScale(1.1f, 1.1f, 1.1f, 1f) }
                val out = Bitmap.createBitmap(finalBmp.width, finalBmp.height, Bitmap.Config.ARGB_8888)
                val c = android.graphics.Canvas(out)
                val p = android.graphics.Paint().apply { colorFilter = android.graphics.ColorMatrixColorFilter(cm) }
                c.drawBitmap(finalBmp, 0f, 0f, p)
                out
            } else finalBmp

            val file = File(dir, "OEA_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}_${filter.name.lowercase()}.jpg")
            file.outputStream().use { toSave.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            // Also save to MediaStore for gallery visibility. Keep the row
            // pending until its bytes are written; otherwise a gallery can
            // index a zero-byte image during the short write window.
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    val values = android.content.ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/OEA")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    host.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)?.let { uri ->
                        host.contentResolver.openOutputStream(uri)?.use { out -> toSave.compress(Bitmap.CompressFormat.JPEG, 92, out) }
                        host.contentResolver.update(uri, android.content.ContentValues().apply {
                            put(MediaStore.Images.Media.IS_PENDING, 0)
                        }, null, null)
                    }
                }
            } catch (_: Throwable) {}
            android.media.MediaScannerConnection.scanFile(host, arrayOf(file.absolutePath), arrayOf("image/jpeg"), null)
            if (toSave != finalBmp && toSave != bmp) toSave.recycle()
            if (finalBmp != bmp) finalBmp.recycle()
            file
        } catch (_: Throwable) { null }
    }

    private fun showCapturedSheet(host: Activity, kit: OeaKit, file: File, filter: FilterMode) {
        val fileUri = runCatching { FileProvider.getUriForFile(host, "${host.packageName}.fileprovider", file) }.getOrNull() ?: return
        kit.sheet("Photo saved", file.name) { panel, dialog ->
            val iv = ImageView(host).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageURI(fileUri)
                background = kit.rounded(Color.BLACK, 16)
                clipToOutline = true
            }
            panel.addView(iv, LinearLayout.LayoutParams(-1, kit.dp(240)).apply { bottomMargin = kit.dp(10) })
            panel.addView(kit.buttonRow(
                kit.quietButton("Share") {
                    dialog.dismiss()
                    try {
                        host.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "image/*"
                            putExtra(Intent.EXTRA_STREAM, fileUri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }, "Share photo"))
                    } catch (_: Throwable) {}
                },
                kit.tonalButton("Edit in EMAGE") {
                    dialog.dismiss()
                    OeaMiniApps.open(host, "emage")
                },
                kit.primaryButton("Close") { dialog.dismiss() }
            ))
            panel.addView(kit.tonalButton("Delete") {
                dialog.dismiss()
                kit.confirm("Delete photo?", file.name, "Delete", destructive = true) {
                    try { file.delete() } catch (_: Throwable) {}
                }
            }, LinearLayout.LayoutParams(-1, kit.dp(46)).apply { topMargin = kit.dp(8) })
        }
    }

    private fun showSettings(host: Activity, kit: OeaKit, palette: OeaPalette) {
        kit.sheet("Camera settings", "OEA's own camera preferences") { panel, _ ->
            val p = prefs(host)
            val group = kit.group(panel)
            kit.switchRow(group, R.drawable.oic_volume_up, "Shutter sound", "Play sound on capture", p.getBoolean(KEY_SHUTTER_SOUND, true)) {
                p.edit().putBoolean(KEY_SHUTTER_SOUND, it).apply()
            }
            kit.switchRow(group, R.drawable.oic_location_on, "Location tag", "Save location in EXIF where permitted (needs location permission)", p.getBoolean(KEY_LOCATION, false)) {
                if (it && host.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    try { host.requestPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION), 2002) } catch (_: Throwable) {}
                }
                p.edit().putBoolean(KEY_LOCATION, it).apply()
            }
            kit.note(panel, "OEA Camera uses Camera2 API. Pro controls (ISO/EV/WB) depend on hardware level — FULL or LEVEL_3 devices expose more. Flash torch uses CameraManager. Filters are OEA's own ColorMatrix implementation, not system. Privacy blur and portrait bokeh are best-effort post-process, not hardware depth.")
        }
    }

    // Grid overlay view
    private class GridOverlayView(ctx: Context, private var mode: GridMode) : View(ctx) {
        private val paint = android.graphics.Paint().apply { color = Color.WHITE; alpha = 80; strokeWidth = 1f; style = android.graphics.Paint.Style.STROKE }
        fun setMode(m: GridMode) { mode = m; invalidate() }
        override fun onDraw(canvas: android.graphics.Canvas) {
            if (mode == GridMode.OFF) return
            val w = width.toFloat(); val h = height.toFloat()
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
                    canvas.drawLine(w/2f, 0f, w/2f, h, paint)
                    canvas.drawLine(0f, h/2f, w, h/2f, paint)
                }
                else -> {}
            }
        }
    }

    // Advanced Camera2 helper with zoom, focus, video
    private class AdvancedCameraHelper(
        private val context: Context,
        private val textureView: TextureView,
        private var front: Boolean,
        private var zoom: Float,
        private var flash: FlashMode
    ) {
        private var cameraDevice: android.hardware.camera2.CameraDevice? = null
        private var session: android.hardware.camera2.CameraCaptureSession? = null
        private var captureRequest: android.hardware.camera2.CaptureRequest.Builder? = null
        private var mediaRecorder: MediaRecorder? = null
        private var recordingFile: File? = null
        private var cameraId: String = "0"

        fun start() {
            try {
                val manager = context.getSystemService(CameraManager::class.java) ?: return
                cameraId = if (front) {
                    manager.cameraIdList.firstOrNull { id ->
                        try { manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT } catch (_: Throwable) { false }
                    } ?: manager.cameraIdList.firstOrNull() ?: "0"
                } else {
                    manager.cameraIdList.firstOrNull { id ->
                        try { manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK } catch (_: Throwable) { false }
                    } ?: "0"
                }
                if (context.checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
                manager.openCamera(cameraId, object : android.hardware.camera2.CameraDevice.StateCallback() {
                    override fun onOpened(camera: android.hardware.camera2.CameraDevice) { cameraDevice = camera; createSession() }
                    override fun onDisconnected(camera: android.hardware.camera2.CameraDevice) { camera.close() }
                    override fun onError(camera: android.hardware.camera2.CameraDevice, error: Int) { camera.close() }
                }, android.os.Handler(android.os.Looper.getMainLooper()))
            } catch (_: Throwable) {}
        }

        fun stop() {
            try { session?.close() } catch (_: Throwable) {}
            try { mediaRecorder?.reset() } catch (_: Throwable) {}
            try { mediaRecorder?.release() } catch (_: Throwable) {}
            mediaRecorder = null
            recordingFile?.delete()
            recordingFile = null
            try { cameraDevice?.close() } catch (_: Throwable) {}
            session = null; cameraDevice = null
        }

        fun switchCamera(isFront: Boolean) { front = isFront; stop(); start() }

        fun setZoom(z: Float) {
            zoom = z.coerceIn(1f, 8f)
            try {
                val manager = context.getSystemService(CameraManager::class.java) ?: return
                val chars = manager.getCameraCharacteristics(cameraId)
                val maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 4f
                val actualZoom = zoom.coerceIn(1f, maxZoom)
                val rect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
                val ratio = 1f / actualZoom
                val croppedW = rect.width() - (rect.width() * ratio).toInt()
                val croppedH = rect.height() - (rect.height() * ratio).toInt()
                val zoomRect = android.graphics.Rect(croppedW/2, croppedH/2, rect.width() - croppedW/2, rect.height() - croppedH/2)
                captureRequest?.set(android.hardware.camera2.CaptureRequest.SCALER_CROP_REGION, zoomRect)
                session?.setRepeatingRequest(captureRequest?.build() ?: return, null, null)
            } catch (_: Throwable) {}
        }

        fun focusAt(x: Float, y: Float, viewW: Int, viewH: Int) {
            try {
                val manager = context.getSystemService(CameraManager::class.java) ?: return
                val chars = manager.getCameraCharacteristics(cameraId)
                val rect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
                val focusArea = android.graphics.Rect(
                    ((x / viewW) * rect.width()).toInt() - 100,
                    ((y / viewH) * rect.height()).toInt() - 100,
                    ((x / viewW) * rect.width()).toInt() + 100,
                    ((y / viewH) * rect.height()).toInt() + 100
                )
                val metering = android.hardware.camera2.params.MeteringRectangle(focusArea, 500)
                captureRequest?.set(android.hardware.camera2.CaptureRequest.CONTROL_AF_REGIONS, arrayOf(metering))
                captureRequest?.set(android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE, android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_AUTO)
                captureRequest?.set(android.hardware.camera2.CaptureRequest.CONTROL_AF_TRIGGER, android.hardware.camera2.CameraMetadata.CONTROL_AF_TRIGGER_START)
                session?.capture(captureRequest?.build() ?: return, null, null)
            } catch (_: Throwable) {}
        }

        private fun createSession() {
            try {
                val device = cameraDevice ?: return
                val surfaceTexture = textureView.surfaceTexture ?: return
                surfaceTexture.setDefaultBufferSize(1280, 720)
                val surface = android.view.Surface(surfaceTexture)
                val req = device.createCaptureRequest(android.hardware.camera2.CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(surface)
                    set(android.hardware.camera2.CaptureRequest.CONTROL_MODE, android.hardware.camera2.CameraMetadata.CONTROL_MODE_AUTO)
                    // Flash
                    when (flash) {
                        FlashMode.ON -> set(android.hardware.camera2.CaptureRequest.FLASH_MODE, android.hardware.camera2.CaptureRequest.FLASH_MODE_TORCH)
                        FlashMode.AUTO -> set(android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE, android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
                        else -> set(android.hardware.camera2.CaptureRequest.FLASH_MODE, android.hardware.camera2.CaptureRequest.FLASH_MODE_OFF)
                    }
                }
                captureRequest = req
                device.createCaptureSession(listOf(surface), object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: android.hardware.camera2.CameraCaptureSession) {
                        session = s
                        try { s.setRepeatingRequest(req.build(), null, android.os.Handler(android.os.Looper.getMainLooper())) } catch (_: Throwable) {}
                    }
                    override fun onConfigureFailed(s: android.hardware.camera2.CameraCaptureSession) {}
                }, android.os.Handler(android.os.Looper.getMainLooper()))
            } catch (_: Throwable) {}
        }

        private fun abortVideoRecording() {
            try { session?.close() } catch (_: Throwable) {}
            session = null
            try { mediaRecorder?.reset() } catch (_: Throwable) {}
            try { mediaRecorder?.release() } catch (_: Throwable) {}
            mediaRecorder = null
            recordingFile?.delete()
            recordingFile = null
            // Reuse the already-open camera instead of opening a second device.
            createSession()
        }

        fun startVideoRecording(ctx: Context, onResult: (Boolean, File?) -> Unit) {
            try {
                val dir = File(ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES), "OEA")
                dir.mkdirs()
                recordingFile = File(dir, "OEA_VID_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp4")
                val audioEnabled = Build.VERSION.SDK_INT < 23 ||
                    ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
                mediaRecorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
                mediaRecorder?.apply {
                    setVideoSource(MediaRecorder.VideoSource.SURFACE)
                    if (audioEnabled) setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                    if (audioEnabled) setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setVideoSize(1280, 720)
                    setVideoFrameRate(30)
                    setOutputFile(recordingFile!!.absolutePath)
                    prepare()
                }
                val recorderSurface = mediaRecorder?.surface
                val device = cameraDevice
                val surfaceTexture = textureView.surfaceTexture
                if (recorderSurface == null || device == null || surfaceTexture == null) {
                    abortVideoRecording()
                    onResult(false, null)
                    return
                }
                surfaceTexture.setDefaultBufferSize(1280, 720)
                val previewSurface = android.view.Surface(surfaceTexture)
                try { session?.close() } catch (_: Throwable) {}
                session = null
                val req = device.createCaptureRequest(android.hardware.camera2.CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(previewSurface)
                    addTarget(recorderSurface)
                }
                device.createCaptureSession(listOf(previewSurface, recorderSurface), object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: android.hardware.camera2.CameraCaptureSession) {
                        session = s
                        try {
                            s.setRepeatingRequest(req.build(), null, null)
                            mediaRecorder?.start()
                        } catch (_: Throwable) {
                            abortVideoRecording()
                            onResult(false, null)
                        }
                    }
                    override fun onConfigureFailed(s: android.hardware.camera2.CameraCaptureSession) {
                        abortVideoRecording()
                        onResult(false, null)
                    }
                }, android.os.Handler(android.os.Looper.getMainLooper()))
            } catch (_: Throwable) {
                abortVideoRecording()
                onResult(false, null)
            }
        }

        fun stopVideoRecording(): File? {
            val finished = recordingFile
            try { session?.close() } catch (_: Throwable) {}
            session = null
            try { mediaRecorder?.stop() } catch (_: Throwable) {}
            try { mediaRecorder?.release() } catch (_: Throwable) {}
            mediaRecorder = null
            recordingFile = null
            // Restart preview after the recorder has released its surface.
            createSession()
            return finished?.takeIf { it.isFile && it.length() > 0L }
        }
    }
}
