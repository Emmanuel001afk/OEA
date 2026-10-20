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
            setPadding(12, 6, 12, 6)
            text = "Crop by dragging over the picture. Edits save as a new copy."
        }
        private var source: Bitmap? = null
        private var filterIndex = 0
        private val filters = listOf("Original", "Mono", "Warm", "Cool", "Vintage", "Bright")
        private val ink = Color.WHITE
        private val buttonColor = Color.rgb(37, 45, 58)

        fun show() {
            stage.addView(editorView, FrameLayout.LayoutParams(-1, -1))
            root.addView(toolbar(listOf("Cancel" to { dialog.dismiss() }, "Rotate" to { rotate() }, "Crop reset" to { editorView.resetCrop() })))
            root.addView(stage)
            root.addView(message, LinearLayout.LayoutParams(-1, -2))
            root.addView(toolbar(listOf("Filter" to { nextFilter() }, "Save copy" to { save() })))
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            dialog.setContentView(root)
            dialog.window?.apply {
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.BLACK))
                addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
                OeaInsets.edgeToEdge(this, lightBars = false)
            }
            root.setOnApplyWindowInsetsListener { _, insets ->
                val bars = OeaInsets.bars(insets)
                root.setPadding(bars[0], bars[1], bars[2], bars[3])
                insets
            }
            dialog.setOnDismissListener { source?.takeIf { !it.isRecycled }?.recycle() }
            dialog.show()
            message.text = "Loading full-resolution picture…"
            worker.execute {
                val loaded = runCatching {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    host.contentResolver.openInputStream(item.uri)?.use {
                        BitmapFactory.decodeStream(it, null, bounds)
                    }
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) error("Picture could not be read")
                    var sample = 1
                    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 4096) sample *= 2
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    host.contentResolver.openInputStream(item.uri)?.use {
                        BitmapFactory.decodeStream(it, null, options)
                    } ?: error("Picture could not be decoded")
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
                            message.text = "Crop: drag a rectangle • Rotate • Filter: ${filters[filterIndex]}"
                        },
                        { err -> message.text = "Could not load picture: ${err.message ?: "unknown error"}" }
                    )
                }
            }
        }

        private fun toolbar(actions: List<Pair<String, () -> Unit>>): LinearLayout =
            LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(8, 6, 8, 6)
                actions.forEach { (label, callback) ->
                    val button = TextView(host).apply {
                        text = label
                        setTextColor(ink)
                        textSize = 13f
                        typeface = Typeface.DEFAULT_BOLD
                        gravity = Gravity.CENTER
                        isClickable = true
                        isFocusable = true
                        setPadding(10, 13, 10, 13)
                        background = android.graphics.drawable.GradientDrawable().apply {
                            setColor(buttonColor)
                            cornerRadius = 18f
                        }
                        setOnClickListener { callback() }
                    }
                    addView(button, LinearLayout.LayoutParams(0, -2, 1f).apply {
                        marginStart = 3
                        marginEnd = 3
                    })
                }
            }

        private fun rotate() {
            val old = source ?: return
            val rotated = runCatching {
                val matrix = android.graphics.Matrix().apply { postRotate(90f) }
                Bitmap.createBitmap(old, 0, 0, old.width, old.height, matrix, true)
            }.getOrNull() ?: return
            if (rotated !== old) old.recycle()
            source = rotated
            editorView.setBitmap(rotated)
            message.text = "Rotated preview • Save copy to keep the edit"
        }

        private fun nextFilter() {
            filterIndex = (filterIndex + 1) % filters.size
            editorView.setFilter(filterIndex)
            message.text = "Filter: ${filters[filterIndex]} • Save copy to keep the edit"
        }

        private fun save() {
            val bitmap = source ?: run {
                message.text = "Wait until the picture has loaded."
                return
            }
            val crop = editorView.sourceCrop(bitmap.width, bitmap.height)
            if (crop.width() < 2f || crop.height() < 2f) {
                message.text = "The crop is too small. Drag a larger area."
                return
            }
            message.text = "Saving edited copy…"
            worker.execute {
                val result = runCatching {
                    val left = crop.left.toInt().coerceIn(0, bitmap.width - 1)
                    val top = crop.top.toInt().coerceIn(0, bitmap.height - 1)
                    val right = crop.right.toInt().coerceIn(left + 1, bitmap.width)
                    val bottom = crop.bottom.toInt().coerceIn(top + 1, bitmap.height)
                    val cropped = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
                    val filter = filterFor(filterIndex)
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
                        if (edited !== bitmap && !edited.isRecycled) edited.recycle()
                    }
                }
                host.runOnUiThread {
                    result.fold(
                        {
                            message.text = "Saved to Pictures/EMAGE"
                            OeaMediaLibrary.refresh(host)
                            onSaved()
                            dialog.dismiss()
                            OeaMediaLibrary.toast(host, "Edited copy saved to Pictures/EMAGE")
                        },
                        { error -> message.text = "Save failed: ${error.message ?: "Android refused the file"}" }
                    )
                }
            }
        }

        private fun filterFor(index: Int): ColorMatrixColorFilter? {
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
                else -> return null
            }
            return ColorMatrixColorFilter(matrix)
        }

        private inner class CropCanvas(context: Activity) : View(context) {
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
            private var filter: ColorMatrixColorFilter? = null

            fun setBitmap(value: Bitmap) {
                bitmap = value
                crop.setEmpty()
                invalidate()
            }

            fun setFilter(index: Int) {
                filter = filterFor(index)
                bitmapPaint.colorFilter = filter
                invalidate()
            }

            fun resetCrop() {
                crop.setEmpty()
                invalidate()
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val image = bitmap ?: return
                val scale = min(width.toFloat() / image.width, height.toFloat() / image.height)
                val w = image.width * scale
                val h = image.height * scale
                bitmapRect.set((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
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
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.x.coerceIn(bitmapRect.left, bitmapRect.right)
                        downY = event.y.coerceIn(bitmapRect.top, bitmapRect.bottom)
                        crop.set(downX, downY, downX, downY)
                        dragging = true
                        invalidate()
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> if (dragging) {
                        crop.set(
                            min(downX, event.x.coerceIn(bitmapRect.left, bitmapRect.right)),
                            min(downY, event.y.coerceIn(bitmapRect.top, bitmapRect.bottom)),
                            max(downX, event.x.coerceIn(bitmapRect.left, bitmapRect.right)),
                            max(downY, event.y.coerceIn(bitmapRect.top, bitmapRect.bottom))
                        )
                        invalidate()
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        dragging = false
                        invalidate()
                        return true
                    }
                }
                return true
            }

            fun sourceCrop(sourceWidth: Int, sourceHeight: Int): RectF {
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
