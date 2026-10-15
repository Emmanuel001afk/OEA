package com.oea.launcher.miniapps

import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.oea.launcher.R
import com.oea.launcher.ui.OeaKit
import com.oea.launcher.ui.OeaPalette
import com.oea.launcher.ui.OeaType
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * OEA Image - EMAGE: image browsing, albums, downloads, previews, organization.
 */
object OeaImageApp {
    private val io = Executors.newFixedThreadPool(2) { Thread(it, "OeaImage").apply { isDaemon = true } }

    data class ImageItem(val uri: Uri, val name: String, val date: Long, val size: Long, val bucket: String)

    private fun queryImages(context: Context): List<ImageItem> {
        val list = mutableListOf<ImageItem>()
        try {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME
            )
            val cursor: Cursor? = context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )
            cursor?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val bucketCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    val name = c.getString(nameCol) ?: "Image"
                    val date = c.getLong(dateCol) * 1000
                    val size = c.getLong(sizeCol)
                    val bucket = c.getString(bucketCol) ?: "Other"
                    list.add(ImageItem(uri, name, date, size, bucket))
                    if (list.size >= 200) break
                }
            }
        } catch (_: Throwable) {}
        return list
    }

    fun build(host: Activity, palette: OeaPalette): View {
        val kit = OeaKit(host, palette)
        val column = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        var images: List<ImageItem> = emptyList()
        var currentBucket: String? = null
        var refresh: (String?) -> Unit = {}

        val header = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val title = kit.text("EMAGE", 22f, palette.text, OeaType.medium)
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(kit.circleIconButton(R.drawable.oic_refresh, "Refresh", palette.cardHigh, palette.text) {
            refresh(currentBucket)
        })
        column.addView(header, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(12) })

        val bucketBar = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
        column.addView(kit.hscroll(bucketBar), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(10) })

        val grid = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        column.addView(grid)

        refresh = { bucket ->
            currentBucket = bucket
            grid.removeAllViews()
            grid.addView(kit.text("Loading images…", 13f, palette.textSecondary).apply { setPadding(kit.dp(6), kit.dp(12), kit.dp(6), kit.dp(6)) })
            io.execute {
                val all = queryImages(host)
                images = all
                host.runOnUiThread {
                    if (host.isDestroyed) return@runOnUiThread
                    // Buckets
                    bucketBar.removeAllViews()
                    val buckets = all.map { it.bucket }.distinct().sorted()
                    bucketBar.addView(kit.chip("All", bucket == null) { refresh(null) }, LinearLayout.LayoutParams(-2, kit.dp(36)).apply { rightMargin = kit.dp(6) })
                    buckets.forEach { b ->
                        bucketBar.addView(kit.chip("$b (${all.count { it.bucket == b }})", b == bucket) { refresh(b) }, LinearLayout.LayoutParams(-2, kit.dp(36)).apply { rightMargin = kit.dp(6) })
                    }

                    grid.removeAllViews()
                    val filtered = if (bucket == null) all else all.filter { it.bucket == bucket }
                    if (filtered.isEmpty()) {
                        kit.note(grid, "No images found. Grant storage permission or add photos to device.")
                        return@runOnUiThread
                    }
                    filtered.chunked(3).forEach { chunk ->
                        val row = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
                        chunk.forEachIndexed { idx, item ->
                            val tile = FrameLayout(host).apply {
                                isClickable = true
                                background = kit.rounded(Color.TRANSPARENT, 14, palette.stroke, 1)
                                clipToOutline = true
                                setOnClickListener { openViewer(host, kit, item) }
                            }
                            val img = ImageView(host).apply {
                                scaleType = ImageView.ScaleType.CENTER_CROP
                                setBackgroundColor(palette.cardHigh)
                            }
                            tile.addView(img, FrameLayout.LayoutParams(-1, kit.dp(110)))
                            // Load thumbnail async
                            io.execute {
                                val thumb = try {
                                    host.contentResolver.loadThumbnail(item.uri, android.util.Size(300, 300), null)
                                } catch (_: Throwable) { null }
                                if (thumb != null) host.runOnUiThread { if (!host.isDestroyed) img.setImageBitmap(thumb) }
                            }
                            row.addView(tile, LinearLayout.LayoutParams(0, -2, 1f).apply {
                                if (idx > 0) leftMargin = kit.dp(6)
                                if (idx < 2) rightMargin = kit.dp(6)
                            })
                        }
                        repeat(3 - chunk.size) { row.addView(View(host), LinearLayout.LayoutParams(0, 1, 1f)) }
                        grid.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })
                    }
                }
            }
        }
        refresh(null)
        return column
    }

    private fun openViewer(host: Activity, kit: OeaKit, item: ImageItem) {
        kit.sheet(item.name, "${item.bucket} • ${formatSize(item.size)} • ${SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(item.date))}") { panel, dialog ->
            val img = ImageView(host).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(kit.palette.cardHigh)
            }
            panel.addView(img, LinearLayout.LayoutParams(-1, kit.dp(320)))
            io.execute {
                val bmp = try { host.contentResolver.loadThumbnail(item.uri, android.util.Size(1080, 1080), null) } catch (_: Throwable) { null }
                if (bmp != null) host.runOnUiThread { if (!host.isDestroyed) img.setImageBitmap(bmp) }
            }
            panel.addView(kit.buttonRow(
                kit.quietButton("Share") {
                    dialog.dismiss()
                    try {
                        host.startActivity(Intent.createChooser(Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "image/*"
                            putExtra(Intent.EXTRA_STREAM, item.uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }, "Share image"))
                    } catch (_: Throwable) {}
                },
                kit.quietButton("Delete") {
                    dialog.dismiss()
                    kit.confirm("Delete image?", "This removes the image from device.", "Delete", destructive = true) {
                        try { host.contentResolver.delete(item.uri, null, null) } catch (_: Throwable) {}
                        (host as? OeaMiniAppActivity)?.refresh()
                    }
                },
                kit.primaryButton("Close") { dialog.dismiss() }
            ))
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "—"
        val kb = bytes / 1024.0
        return if (kb < 1024) String.format(Locale.US, "%.0f KB", kb) else String.format(Locale.US, "%.1f MB", kb / 1024)
    }

    private fun Intent(action: String): android.content.Intent = android.content.Intent(action)
}
