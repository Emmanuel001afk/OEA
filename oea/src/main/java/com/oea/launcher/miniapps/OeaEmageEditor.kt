package com.oea.launcher.miniapps

import android.app.Activity
import android.app.Dialog
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.oea.launcher.ui.OeaInsets
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * A small on-device photo editor for EMAGE. It never overwrites the source:
 * crop/rotate/filter edits are rendered into a new Pictures/EMAGE file.
 */
internal object OeaEmageEditor {
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "EMAGE-Editor").apply { isDaemon = true }
    }

    fun open(host: Activity, item: OeaMediaLibrary.Item, onSaved: () -> Unit = {}) {
        Editor(host, item, onSaved).show()
    }

    private class Editor(
        private val host: Activity,
        private val item: OeaMediaLibrary.Item,
        private val onSaved: () -> Unit
    ) {
        private val dialog = Dialog(host, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        private val root = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(12, 14, 18))
        }
        private val stage = FrameLayout(host).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }
        private val editorView = CropCanvas(host)
        private val message = TextView(host).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(6))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            text = "Crop by dragging over the picture. Edits save as a new copy."
        }
        private var source: Bitmap? = null
        private var filterIndex = 0
        private var brightness = 0f
        private var contrast = 1f
        private var saving = false
        private lateinit var brightnessSeek: SeekBar
        private lateinit var contrastSeek: SeekBar
        private lateinit var brightnessValue: TextView
        private lateinit var contrastValue: TextView
        private lateinit var filterRow: LinearLayout
        private lateinit var saveButton: TextView
        private val filters = listOf("Original", "Mono", "Warm", "Cool", "Vintage", "Bright", "Vivid", "Soft")
        private val ink = Color.rgb(245, 247, 252)
        private val muted = Color.rgb(168, 176, 194)
        private val surfaceColor = Color.rgb(20, 23, 30)
        private val buttonColor = Color.rgb(34, 39, 50)
        private val strokeColor = Color.rgb(58, 66, 84)
        private val accentColor = Color.rgb(92, 168, 255)
        private val onAccentColor = Color.rgb(12, 18, 28)

        fun show() {
            stage.addView(editorView, FrameLayout.LayoutParams(-1, -1))
            root.addView(topBar(), LinearLayout.LayoutParams(-1, -2))
            root.addView(stage)
            // On a short or landscape display the controls can be taller than
            // the space below the preview. Scroll them instead of crushing the
            // Save action or pushing it off-screen.
            val controls = LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(surfaceColor)
                setPadding(0, dp(6), 0, dp(8))
            }
            controls.addView(
                View(host).apply { setBackgroundColor(strokeColor) },
                LinearLayout.LayoutParams(-1, max(1, dp(1)))
            )
            controls.addView(message, LinearLayout.LayoutParams(-1, -2))
            filterRow = LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(2), dp(12), dp(6))
            }
            paintFilters()
            controls.addView(
                android.widget.HorizontalScrollView(host).apply {
                    isHorizontalScrollBarEnabled = false
                    overScrollMode = View.OVER_SCROLL_NEVER
                    addView(filterRow)
                },
                LinearLayout.LayoutParams(-1, -2)
            )
            controls.addView(adjustmentRow("Brightness", -50, 50, 0) { brightness = it.toFloat(); refreshPreview() })
            controls.addView(adjustmentRow("Contrast", 50, 150, 100) { contrast = it / 100f; refreshPreview() })
            controls.addView(toolbar(listOf(
                "Rotate left" to { rotate(-90f) },
                "Rotate right" to { rotate(90f) },
                "Mirror" to { mirror() },
                "Reset crop" to { resetCrop() },
                "Reset tone" to { resetAdjustments() }
            )))
            val controlScroll = object : android.widget.ScrollView(host) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    val limit = (resources.displayMetrics.heightPixels * 0.45f).toInt()
                    val mode = View.MeasureSpec.getMode(heightMeasureSpec)
                    val maxHeight = if (mode == View.MeasureSpec.UNSPECIFIED) limit
                        else minOf(limit, View.MeasureSpec.getSize(heightMeasureSpec))
                    super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST))
                }
            }.apply { isVerticalScrollBarEnabled = false; addView(controls) }
            root.addView(controlScroll, LinearLayout.LayoutParams(-1, -2))
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            dialog.setContentView(root)
            dialog.window?.apply {
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.BLACK))
                OeaInsets.edgeToEdge(this, lightBars = false)
            }
            root.setOnApplyWindowInsetsListener { _, insets ->
                val bars = OeaInsets.bars(insets)
                root.setPadding(bars[0], bars[1], bars[2], bars[3])
                insets
            }
            dialog.setOnDismissListener { source?.takeIf { !it.isRecycled }?.recycle() }
            dialog.show()
            status("Loading editable picture…", announce = true)
            worker.execute {
                val loaded = runCatching {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    host.contentResolver.openInputStream(item.uri)?.use {
                        BitmapFactory.decodeStream(it, null, bounds)
                    }
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) error("Picture could not be read")
                    // The old rule divided before comparing, so its own 4096 ceiling
                    // really allowed 8192: an 8000×6000 photo became a 192 MB bitmap and
                    // the editor answered "Could not load picture" on the phones that
                    // take such photos.
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = OeaEmageFacts.sampleSize(
                            bounds.outWidth,
                            bounds.outHeight,
                            OeaEmageFacts.EDIT_EDGE
                        )
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    val decoded = host.contentResolver.openInputStream(item.uri)?.use {
                        BitmapFactory.decodeStream(it, null, options)
                    } ?: error("Picture could not be decoded")
                    // The editor works on upright pixels and saves a new file with no
                    // orientation tag, so a tag-turned source has to be turned first.
                    com.oea.launcher.ui.OeaPhotoDecode.upright(host, item.uri, decoded)
                }
                host.runOnUiThread {
                    if (!dialog.isShowing) {
                        loaded.getOrNull()?.recycle()
                        return@runOnUiThread
                    }
                    loaded.fold(
                        { bitmap ->
                            source = bitmap
                            editorView.setBitmap(bitmap)
                            // Say what resolution the edit is being made at: a 48 MP photo
                            // is edited as a reduced copy, and that should be visible
                            // rather than discovered in the saved file.
                            status(
                                "Crop area ready at ${bitmap.width}×${bitmap.height}. Drag a rectangle, rotate, mirror, or choose a filter.",
                                announce = true
                            )
                        },
                        { err -> status("Could not load picture: ${err.message ?: "unknown error"}", announce = true) }
                    )
                }
            }
        }

        private fun topBar(): LinearLayout = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(surfaceColor)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            addView(pillButton("Cancel", active = false) {
                if (!saving) dialog.dismiss() else status("Saving copy, please wait…", announce = true)
            }, LinearLayout.LayoutParams(-2, dp(42)))
            addView(TextView(host).apply {
                text = "Edit photo"
                setTextColor(ink)
                textSize = 16f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(0, -2, 1f))
            saveButton = pillButton("Save copy", active = true) { save() }
            addView(saveButton, LinearLayout.LayoutParams(-2, dp(42)))
        }

        private fun pillButton(label: String, active: Boolean, onClick: () -> Unit): TextView {
            val isFilter = filters.contains(label)
            return TextView(host).apply {
                text = label
                contentDescription = if (active && isFilter) "$label filter, selected" else label
                setTextColor(if (active) onAccentColor else ink)
                textSize = 13f
                typeface = Typeface.create("sans-serif-medium", if (active) Typeface.BOLD else Typeface.NORMAL)
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                minimumHeight = dp(40)
                minWidth = dp(48)
                setPadding(dp(15), dp(8), dp(15), dp(8))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(if (active) accentColor else buttonColor)
                    cornerRadius = dp(20).toFloat()
                    if (!active) setStroke(max(1, dp(1)), strokeColor)
                }
                setOnClickListener { onClick() }
            }
        }

        private fun paintFilters() {
            filterRow.removeAllViews()
            filters.forEachIndexed { idx, name ->
                filterRow.addView(
                    pillButton(name, idx == filterIndex) {
                        if (saving) return@pillButton
                        filterIndex = idx
                        paintFilters()
                        refreshPreview()
                    },
                    LinearLayout.LayoutParams(-2, dp(40)).apply {
                        if (idx > 0) marginStart = dp(6)
                    }
                )
            }
        }

        /** A horizontally scrollable action rail: labels stay legible on small phones. */
        private fun toolbar(actions: List<Pair<String, () -> Unit>>): View =
            android.widget.HorizontalScrollView(host).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                clipToPadding = false
                setPadding(dp(12), dp(4), dp(12), dp(2))
                addView(LinearLayout(host).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    actions.forEachIndexed { idx, (label, callback) ->
                        addView(pillButton(label, active = false, onClick = callback), LinearLayout.LayoutParams(-2, dp(42)).apply {
                            if (idx > 0) marginStart = dp(6)
                        })
                    }
                })
            }

        private fun rotate(degrees: Float) {
            if (saving) return
            val old = source ?: return
            val rotated = runCatching {
                val matrix = android.graphics.Matrix().apply { postRotate(degrees) }
                Bitmap.createBitmap(old, 0, 0, old.width, old.height, matrix, true)
            }.getOrNull() ?: return
            if (rotated !== old) old.recycle()
            source = rotated
            editorView.setBitmap(rotated)
            refreshPreview()
            status("Rotation preview. Save copy to keep the edit.", announce = true)
        }

        private fun mirror() {
            if (saving) return
            val old = source ?: return
            val mirrored = runCatching {
                val matrix = android.graphics.Matrix().apply { preScale(-1f, 1f) }
                Bitmap.createBitmap(old, 0, 0, old.width, old.height, matrix, true)
            }.getOrNull() ?: return
            if (mirrored !== old) old.recycle()
            source = mirrored
            editorView.setBitmap(mirrored)
            refreshPreview()
            status("Mirrored preview. Save copy to keep the edit.", announce = true)
        }

        private fun adjustmentRow(label: String, min: Int, max: Int, initial: Int, changed: (Int) -> Unit): LinearLayout =
            LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), 0, dp(14), 0)
                val readout = TextView(host).apply {
                    text = initialValueLabel(label, initial)
                    setTextColor(ink)
                    textSize = 12f
                    typeface = Typeface.create("monospace", Typeface.NORMAL)
                    gravity = Gravity.CENTER_VERTICAL or Gravity.END
                }
                val seek = SeekBar(host).apply {
                    id = View.generateViewId()
                    this.max = max - min
                    progress = initial - min
                    minHeight = dp(44)
                    progressTintList = android.content.res.ColorStateList.valueOf(accentColor)
                    thumbTintList = android.content.res.ColorStateList.valueOf(accentColor)
                    progressBackgroundTintList = android.content.res.ColorStateList.valueOf(strokeColor)
                    contentDescription = "$label, ${initialValueLabel(label, initial)}"
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                            val value = progress + min
                            readout.text = initialValueLabel(label, value)
                            contentDescription = "$label, ${initialValueLabel(label, value)}"
                            if (!saving) changed(value)
                        }
                        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                    })
                }
                addView(TextView(host).apply {
                    text = label
                    setTextColor(muted)
                    textSize = 12.5f
                    gravity = Gravity.CENTER_VERTICAL
                    labelFor = seek.id
                }, LinearLayout.LayoutParams(dp(78), dp(44)))
                if (label == "Brightness") {
                    brightnessSeek = seek
                    brightnessValue = readout
                } else if (label == "Contrast") {
                    contrastSeek = seek
                    contrastValue = readout
                }
                addView(seek, LinearLayout.LayoutParams(0, dp(44), 1f))
                addView(readout, LinearLayout.LayoutParams(dp(44), dp(44)))
            }

        private fun initialValueLabel(label: String, value: Int): String = when (label) {
            "Brightness" -> if (value > 0) "+$value" else value.toString()
            "Contrast" -> "$value%"
            else -> value.toString()
        }

        private fun dp(value: Int): Int = (value * host.resources.displayMetrics.density).toInt()

        private fun status(text: String, announce: Boolean = false) {
            message.text = text
            if (announce && message.isShown) message.announceForAccessibility(text)
        }

        private fun refreshPreview() {
            if (saving) return
            editorView.setFilter(filterIndex, brightness, contrast)
            status("Filter: ${filters[filterIndex]} • Brightness: ${brightness.toInt()} • Contrast: ${(contrast * 100).toInt()}")
        }

        private fun resetAdjustments() {
            if (saving) return
            filterIndex = 0
            brightness = 0f
            contrast = 1f
            brightnessSeek.progress = 50
            contrastSeek.progress = 50
            brightnessValue.text = "0"
            contrastValue.text = "100%"
            paintFilters()
            editorView.setFilter(filterIndex, brightness, contrast)
            status("Tone and filter reset. Crop is preserved.", announce = true)
        }

        private fun resetCrop() {
            if (saving) return
            editorView.resetCrop()
            status("Crop reset to the whole picture.", announce = true)
        }

        private fun save() {
            if (saving) return
            val bitmap = source ?: run {
                status("Wait until the picture has loaded.", announce = true)
                return
            }
            val crop = editorView.sourceCrop(bitmap.width, bitmap.height)
            if (crop.width() < 2f || crop.height() < 2f) {
                status("The crop is too small. Drag a larger area.", announce = true)
                return
            }
            // Work from a stable snapshot. The preview may otherwise be rotated or
            // recycled while the background writer is still reading it.
            val working = runCatching { bitmap.copy(Bitmap.Config.ARGB_8888, false) }.getOrElse {
                status("There is not enough memory to save this edit.", announce = true)
                return
            }
            saving = true
            dialog.setCancelable(false)
            status("Saving edited copy…", announce = true)
            worker.execute {
                val result = runCatching {
                    val left = crop.left.toInt().coerceIn(0, working.width - 1)
                    val top = crop.top.toInt().coerceIn(0, working.height - 1)
                    val right = crop.right.toInt().coerceIn(left + 1, working.width)
                    val bottom = crop.bottom.toInt().coerceIn(top + 1, working.height)
                    val cropped = Bitmap.createBitmap(working, left, top, right - left, bottom - top)
                    val filter = filterFor(filterIndex, brightness, contrast)
                    val edited = if (filter == null) cropped else Bitmap.createBitmap(cropped.width, cropped.height, Bitmap.Config.ARGB_8888).also { out ->
                        Canvas(out).drawBitmap(cropped, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG).apply { colorFilter = filter })
                        if (out !== cropped) cropped.recycle()
                    }
                    val isPng = item.name.substringAfterLast('.', "").lowercase(Locale.ROOT) == "png"
                    val mime = if (isPng) "image/png" else "image/jpeg"
                    val extension = if (isPng) "png" else "jpg"
                    val displayName = "${item.name.substringBeforeLast('.', item.name)}_edited.$extension"
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                        put(MediaStore.MediaColumns.MIME_TYPE, mime)
                        if (Build.VERSION.SDK_INT >= 29) {
                            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/EMAGE")
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        }
                    }
                    val uri = host.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        ?: error("Android refused to create the edited picture")
                    try {
                        val written = host.contentResolver.openOutputStream(uri)?.use { out ->
                            edited.compress(if (isPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 94, out)
                        } ?: false
                        if (!written) error("Could not write the edited picture")
                        if (Build.VERSION.SDK_INT >= 29) {
                            host.contentResolver.update(uri, ContentValues().apply {
                                put(MediaStore.MediaColumns.IS_PENDING, 0)
                            }, null, null)
                        }
                        uri
                    } catch (error: Throwable) {
                        runCatching { host.contentResolver.delete(uri, null, null) }
                        throw error
                    } finally {
                        if (!edited.isRecycled) edited.recycle()
                        if (cropped !== edited && !cropped.isRecycled) cropped.recycle()
                    }
                }
                if (!working.isRecycled) working.recycle()
                host.runOnUiThread {
                    saving = false
                    if (host.isFinishing || host.isDestroyed || !dialog.isShowing) return@runOnUiThread
                    dialog.setCancelable(true)
                    result.fold(
                        {
                            status("Saved to Pictures/EMAGE", announce = true)
                            OeaMediaLibrary.refresh(host)
                            onSaved()
                            dialog.dismiss()
                            OeaMediaLibrary.toast(host, "Edited copy saved to Pictures/EMAGE")
                        },
                        { error -> status("Save failed: ${error.message ?: "Android refused the file"}", announce = true) }
                    )
                }
            }
        }

        private fun filterFor(index: Int, brightness: Float = 0f, contrast: Float = 1f): ColorMatrixColorFilter? {
            val matrix = when (index) {
                1 -> ColorMatrix().apply { setSaturation(0f) }
                2 -> ColorMatrix(floatArrayOf(
                    1.08f, 0f, 0f, 0f, 12f,
                    0f, 1.0f, 0f, 0f, 3f,
                    0f, 0f, 0.88f, 0f, -4f,
                    0f, 0f, 0f, 1f, 0f
                ))
                3 -> ColorMatrix(floatArrayOf(
                    0.9f, 0f, 0f, 0f, -3f,
                    0f, 1.0f, 0f, 0f, 2f,
                    0f, 0f, 1.12f, 0f, 12f,
                    0f, 0f, 0f, 1f, 0f
                ))
                4 -> ColorMatrix().apply {
                    setSaturation(0.72f)
                    postConcat(ColorMatrix(floatArrayOf(
                        1.06f, 0f, 0f, 0f, 8f,
                        0f, 0.96f, 0f, 0f, 3f,
                        0f, 0f, 0.82f, 0f, -2f,
                        0f, 0f, 0f, 1f, 0f
                    )))
                }
                5 -> ColorMatrix(floatArrayOf(
                    1.0f, 0f, 0f, 0f, 24f,
                    0f, 1.0f, 0f, 0f, 24f,
                    0f, 0f, 1.0f, 0f, 24f,
                    0f, 0f, 0f, 1f, 0f
                ))
                6 -> ColorMatrix().apply { setSaturation(1.35f) }
                7 -> ColorMatrix().apply {
                    setSaturation(0.82f)
                    postConcat(ColorMatrix(floatArrayOf(
                        1.02f, 0f, 0f, 0f, 5f,
                        0f, 1.02f, 0f, 0f, 5f,
                        0f, 0f, 1.02f, 0f, 5f,
                        0f, 0f, 0f, 1f, 0f
                    )))
                }
                else -> ColorMatrix()
            }
            if (contrast != 1f || brightness != 0f) {
                matrix.postConcat(ColorMatrix(floatArrayOf(
                    contrast, 0f, 0f, 0f, brightness,
                    0f, contrast, 0f, 0f, brightness,
                    0f, 0f, contrast, 0f, brightness,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            if (index == 0 && contrast == 1f && brightness == 0f) return null
            return ColorMatrixColorFilter(matrix)
        }

        private inner class CropCanvas(context: Activity) : View(context) {
            init {
                isFocusable = true
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                contentDescription = "Photo crop area. Drag across the picture to choose the crop."
                setOnLongClickListener {
                    if (!saving) {
                        resetCrop()
                        status("Crop reset to the whole picture.", announce = true)
                    }
                    true
                }
            }

            private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt() }
            private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 2.5f
            }
            private val bitmapRect = RectF()
            private val crop = RectF()
            private var bitmap: Bitmap? = null
            private var dragging = false
            private var downX = 0f
            private var downY = 0f
            // Existing crop edges can be grabbed and refined instead of forcing
            // the user to redraw the whole selection every time.
            private var adjustLeft = false
            private var adjustTop = false
            private var adjustRight = false
            private var adjustBottom = false
            private var filter: ColorMatrixColorFilter? = null

            fun setBitmap(value: Bitmap) {
                bitmap = value
                crop.setEmpty()
                contentDescription = "Photo crop area. Drag across the picture to choose a crop. Current picture is ${value.width} by ${value.height} pixels."
                invalidate()
            }

            fun setFilter(index: Int, brightness: Float = 0f, contrast: Float = 1f) {
                filter = filterFor(index, brightness, contrast)
                bitmapPaint.colorFilter = filter
                invalidate()
            }

            fun resetCrop() {
                crop.setEmpty()
                invalidate()
            }

            private fun updateBitmapRect(image: Bitmap) {
                if (width <= 0 || height <= 0) {
                    bitmapRect.setEmpty()
                    return
                }
                val scale = min(width.toFloat() / image.width, height.toFloat() / image.height)
                val w = image.width * scale
                val h = image.height * scale
                bitmapRect.set((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val image = bitmap ?: return
                updateBitmapRect(image)
                canvas.drawColor(Color.BLACK)
                canvas.drawBitmap(image, null, bitmapRect, bitmapPaint)
                // Start with the entire image selected: simply applying a filter
                // or rotating must not silently crop away the edges.
                if (crop.isEmpty) crop.set(bitmapRect)
                val outside = Path().apply {
                    fillType = Path.FillType.EVEN_ODD
                    addRect(bitmapRect, Path.Direction.CW)
                    addRect(crop, Path.Direction.CW)
                }
                canvas.drawPath(outside, shadePaint)
                canvas.drawRect(crop, borderPaint)
                val thirdW = crop.width() / 3f
                val thirdH = crop.height() / 3f
                val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0x99FFFFFF.toInt()
                    strokeWidth = 1f
                }
                for (i in 1..2) {
                    canvas.drawLine(crop.left + thirdW * i, crop.top, crop.left + thirdW * i, crop.bottom, gridPaint)
                    canvas.drawLine(crop.left, crop.top + thirdH * i, crop.right, crop.top + thirdH * i, gridPaint)
                }
            }

            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (saving || bitmap == null || bitmapRect.isEmpty) return false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.x.coerceIn(bitmapRect.left, bitmapRect.right)
                        downY = event.y.coerceIn(bitmapRect.top, bitmapRect.bottom)
                        val handle = dp(28).toFloat()
                        val hasCrop = !crop.isEmpty && crop.width() >= 12f && crop.height() >= 12f
                        adjustLeft = hasCrop && kotlin.math.abs(downX - crop.left) <= handle
                        adjustRight = hasCrop && kotlin.math.abs(downX - crop.right) <= handle
                        adjustTop = hasCrop && kotlin.math.abs(downY - crop.top) <= handle
                        adjustBottom = hasCrop && kotlin.math.abs(downY - crop.bottom) <= handle
                        val nearCrop = hasCrop && downX >= crop.left - handle && downX <= crop.right + handle &&
                            downY >= crop.top - handle && downY <= crop.bottom + handle
                        if (!nearCrop) {
                            crop.set(downX, downY, downX, downY)
                            adjustLeft = true; adjustTop = true; adjustRight = false; adjustBottom = false
                        } else {
                            // If the finger lands inside the selection rather than on an edge,
                            // start a fresh selection there; edge grabs resize the current crop.
                            if (!adjustLeft && !adjustRight && !adjustTop && !adjustBottom) {
                                crop.set(downX, downY, downX, downY)
                                adjustLeft = true; adjustTop = true; adjustRight = false; adjustBottom = false
                            }
                        }
                        dragging = true
                        invalidate()
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> if (dragging) {
                        val x = event.x.coerceIn(bitmapRect.left, bitmapRect.right)
                        val y = event.y.coerceIn(bitmapRect.top, bitmapRect.bottom)
                        if (adjustLeft) crop.left = min(x, crop.right - 2f)
                        if (adjustRight) crop.right = max(x, crop.left + 2f)
                        if (adjustTop) crop.top = min(y, crop.bottom - 2f)
                        if (adjustBottom) crop.bottom = max(y, crop.top + 2f)
                        crop.left = crop.left.coerceAtLeast(bitmapRect.left)
                        crop.top = crop.top.coerceAtLeast(bitmapRect.top)
                        crop.right = crop.right.coerceAtMost(bitmapRect.right)
                        crop.bottom = crop.bottom.coerceAtMost(bitmapRect.bottom)
                        invalidate()
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        dragging = false
                        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                        invalidate()
                        return true
                    }
                }
                return true
            }

            override fun performClick(): Boolean {
                super.performClick()
                return true
            }

            fun sourceCrop(sourceWidth: Int, sourceHeight: Int): RectF {
                bitmap?.let { updateBitmapRect(it) }
                if (bitmapRect.isEmpty) return RectF(0f, 0f, sourceWidth.toFloat(), sourceHeight.toFloat())
                val effective = if (crop.width() < 12f || crop.height() < 12f) bitmapRect else crop
                val sx = sourceWidth / bitmapRect.width()
                val sy = sourceHeight / bitmapRect.height()
                return RectF(
                    ((effective.left - bitmapRect.left) * sx).coerceIn(0f, sourceWidth.toFloat()),
                    ((effective.top - bitmapRect.top) * sy).coerceIn(0f, sourceHeight.toFloat()),
                    ((effective.right - bitmapRect.left) * sx).coerceIn(0f, sourceWidth.toFloat()),
                    ((effective.bottom - bitmapRect.top) * sy).coerceIn(0f, sourceHeight.toFloat())
                )
            }
        }
    }
}
