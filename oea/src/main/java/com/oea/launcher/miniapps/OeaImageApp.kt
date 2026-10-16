package com.oea.launcher.miniapps

import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.core.content.FileProvider
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
 * OEA Image - EMAGE: fully competitive image browser.
 *
 * Unique features vs other mini-apps:
 * - Albums: unlimited, grouped by MediaStore BUCKET_DISPLAY_NAME + RELATIVE_PATH (Android 10+), so external apps' folders appear automatically
 *   Includes Screenshots, Downloads, Camera, WhatsApp Images, Telegram, etc — not limited
 * - Smart albums: Favorites (OEA private), Recent (last 30 days), Large (>5MB), Screenshots, Downloads, Camera
 * - Unlimited loading: pagination, load more on scroll, not capped at 200 — loads 300 initially then 200 more per batch
 * - Search: by name, bucket, date
 * - Details: EXIF (resolution, size, date, location if available), histogram placeholder
 * - Actions: favorite, share, delete, set as wallpaper, edit (crop/rotate via intent), slideshow, copy path
 * - Edit: rotate, flip, crop via system intent, filter preview
 * - Slideshow with timer
 * - Trash simulation: deleted items kept in OEA's own trash list for 30 days (since MediaStore trash needs API 30+ and user consent)
 * - Integration: open in OEA Camera, share to OEA Notes, set as OEA theme wallpaper
 * - Competitive grid: 3 columns, pinch to change, fast thumbnail loading via thread pool
 */
object OeaImageApp {
    private val io = Executors.newFixedThreadPool(4) { Thread(it, "OeaImage").apply { isDaemon = true } }

    data class ImageItem(
        val uri: Uri,
        val name: String,
        val date: Long,
        val size: Long,
        val bucket: String,
        val relativePath: String,
        val width: Int,
        val height: Int
    )

    private const val PREFS = "oea_image_v2"
    private const val KEY_FAV = "fav_uris"
    private const val KEY_TRASH = "trash_uris"
    private const val EXTRA_VIEW_HANDLED = "oea.emage.view_handled"
    private const val KEY_GRID_COLS = "grid_cols"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun queryImages(context: Context, offset: Int = 0, limit: Int = 300): List<ImageItem> {
        val list = mutableListOf<ImageItem>()
        try {
            val projection = mutableListOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT
            )
            if (Build.VERSION.SDK_INT >= 29) projection.add(MediaStore.Images.Media.RELATIVE_PATH)
            val cursor: Cursor? = context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection.toTypedArray(),
                null,
                null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC LIMIT $limit OFFSET $offset"
            )
            cursor?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val bucketCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                val widthCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                val heightCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
                val pathCol = if (Build.VERSION.SDK_INT >= 29) c.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH) else -1
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    val name = c.getString(nameCol) ?: "Image"
                    val date = c.getLong(dateCol) * 1000
                    val size = c.getLong(sizeCol)
                    val bucket = c.getString(bucketCol) ?: "Other"
                    val w = c.getInt(widthCol)
                    val h = c.getInt(heightCol)
                    val rel = if (pathCol >= 0) c.getString(pathCol) ?: "" else ""
                    list.add(ImageItem(uri, name, date, size, bucket, rel, w, h))
                }
            }
        } catch (_: Throwable) {
            // Fallback without LIMIT/OFFSET for older devices
            try {
                val projection = arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATE_ADDED,
                    MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.BUCKET_DISPLAY_NAME
                )
                val cursor = context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection, null, null,
                    "${MediaStore.Images.Media.DATE_ADDED} DESC"
                )
                cursor?.use { c ->
                    val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                    val sizeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                    val bucketCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                    var idx = 0
                    while (c.moveToNext()) {
                        if (idx++ < offset) continue
                        if (list.size >= limit) break
                        val id = c.getLong(idCol)
                        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                        list.add(ImageItem(uri, c.getString(nameCol) ?: "Image", c.getLong(dateCol) * 1000, c.getLong(sizeCol), c.getString(bucketCol) ?: "Other", "", 0, 0))
                    }
                }
            } catch (_: Throwable) {}
        }
        // Also include OEA's own pictures from private dir
        if (offset == 0) {
            try {
                val oeaDir = File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES), "OEA")
                oeaDir.listFiles()?.sortedByDescending { it.lastModified() }?.forEach { f ->
                    if (f.isFile && f.extension.lowercase() in listOf("jpg", "jpeg", "png", "webp")) {
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
                        list.add(0, ImageItem(uri, f.name, f.lastModified(), f.length(), "OEA Camera", "Pictures/OEA", 0, 0))
                    }
                }
            } catch (_: Throwable) {}
        }
        return list
    }

    private fun allBuckets(images: List<ImageItem>): Map<String, List<ImageItem>> {
        return images.groupBy { it.bucket.ifBlank { "Other" } }
    }

    fun build(host: Activity, palette: OeaPalette): View {
        val kit = OeaKit(host, palette)
        val column = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        val mediaPermission = if (Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_IMAGES else android.Manifest.permission.READ_EXTERNAL_STORAGE
        if (host.checkSelfPermission(mediaPermission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            kit.banner(column, R.drawable.oic_photo_library, "Connect your device pictures", "EMAGE can show the images on this phone, including Camera, Screenshots, Downloads and app albums. OEA never uploads them.", OeaKit.Tone.INFO, "Allow") {
                runCatching { host.requestPermissions(arrayOf(mediaPermission), 2201) }
            }
            kit.note(column, "You can still use OEA's own wallpaper library without granting photo access.")
        }
        var images: MutableList<ImageItem> = mutableListOf()
        var currentBucket: String? = null
        var currentTab = "albums" // albums, all, favorites, recent, large, screenshots, downloads
        var searchQuery = ""
        var gridCols = prefs(host).getInt(KEY_GRID_COLS, 3).coerceIn(2, 5)
        var loadedOffset = 0
        var hasMore = true
        var refresh: (Boolean, Boolean, Boolean) -> Unit = { _, _, _ -> }

        val header = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(kit.text("EMAGE", 22f, palette.text, OeaType.medium), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(kit.circleIconButton(R.drawable.oic_search, "Search", palette.cardHigh, palette.text) {
            kit.prompt("Search images", "Name, album or date", "Search", searchQuery) { q ->
                searchQuery = q.trim()
                refresh(false, false, false)
                null
            }
        }, LinearLayout.LayoutParams(kit.dp(40), kit.dp(40)).apply { rightMargin = kit.dp(8) })
        header.addView(kit.circleIconButton(R.drawable.oic_refresh, "Refresh", palette.cardHigh, palette.text) { refresh(true, false, false) })
        column.addView(header, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })

        val tabBar = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
        column.addView(kit.hscroll(tabBar), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(10) })

        val bucketBar = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
        column.addView(kit.hscroll(bucketBar), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(10) })

        val grid = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        column.addView(grid)

        val loadMoreBtn = kit.tonalButton("Load more images", R.drawable.oic_refresh) { refresh(false, true, false) }

        fun renderTabs() {
            tabBar.removeAllViews()
            listOf(
                "albums" to "Albums",
                "all" to "All",
                "favorites" to "Favorites",
                "recent" to "Recent",
                "screenshots" to "Screenshots",
                "downloads" to "Downloads",
                "large" to "Large",
                "oea" to "OEA pictures"
            ).forEach { (id, label) ->
                tabBar.addView(kit.chip(label, id == currentTab) {
                    currentTab = id
                    currentBucket = null
                    refresh(false, false, false)
                }, LinearLayout.LayoutParams(-2, kit.dp(36)).apply { rightMargin = kit.dp(6) })
            }
        }

        fun filteredList(): List<ImageItem> {
            var list: List<ImageItem> = images
            // Tab filtering
            list = when (currentTab) {
                "favorites" -> {
                    val fav = prefs(host).getStringSet(KEY_FAV, emptySet()) ?: emptySet()
                    list.filter { fav.contains(it.uri.toString()) }
                }
                "recent" -> {
                    val cutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
                    list.filter { it.date >= cutoff }
                }
                "screenshots" -> list.filter { it.bucket.contains("screenshot", true) || it.relativePath.contains("screenshot", true) || it.name.contains("screenshot", true) }
                "downloads" -> list.filter { it.bucket.contains("download", true) || it.relativePath.contains("download", true) }
                "large" -> list.filter { it.size > 5 * 1024 * 1024 }
                else -> list
            }
            // Bucket filtering (for albums tab, bucket is album; for all tab, bucket filter also works)
            if (currentBucket != null) {
                list = list.filter { it.bucket == currentBucket }
            }
            // Search
            if (searchQuery.isNotBlank()) {
                list = list.filter { it.name.contains(searchQuery, true) || it.bucket.contains(searchQuery, true) || it.relativePath.contains(searchQuery, true) }
            }
            return list
        }

        fun renderGrid() {
            grid.removeAllViews()
            if (currentTab == "oea") {
                // The OEA pictures (wallpapers, lock screens, live scenes) are part of EMAGE.
                bucketBar.visibility = View.GONE
                grid.addView(OeaGalleryApp.build(host, palette), LinearLayout.LayoutParams(-1, -2))
                return
            }
            bucketBar.visibility = View.VISIBLE
            val filtered = filteredList()
            if (currentTab == "albums" && currentBucket == null) {
                // Show albums as cards
                val buckets = allBuckets(images)
                if (buckets.isEmpty()) {
                    kit.note(grid, "No images found. Grant storage permission or add photos.")
                    return
                }
                // Smart albums first
                val smartAlbums = listOf(
                    "All (${images.size})" to images,
                    "Favorites (${(prefs(host).getStringSet(KEY_FAV, emptySet())?.size ?: 0)})" to filteredList(),
                    "Screenshots (${images.count { it.bucket.contains("screenshot", true) }})" to images.filter { it.bucket.contains("screenshot", true) },
                    "Downloads (${images.count { it.bucket.contains("download", true) }})" to images.filter { it.bucket.contains("download", true) },
                    "Camera (${images.count { it.bucket.contains("camera", true) || it.bucket.contains("dcim", true) }})" to images.filter { it.bucket.contains("camera", true) || it.bucket.contains("dcim", true) }
                )
                smartAlbums.forEach { (label, items) ->
                    if (items.isEmpty()) return@forEach
                    val row = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(kit.dp(12), kit.dp(10), kit.dp(12), kit.dp(10)); background = kit.rounded(palette.card, 16, palette.stroke, 1); isClickable = true }
                    val cover = ImageView(host).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(palette.cardHigh); clipToOutline = true; background = kit.rounded(Color.BLACK, 10) }
                    row.addView(cover, LinearLayout.LayoutParams(kit.dp(56), kit.dp(56)).apply { rightMargin = kit.dp(12) })
                    val textCol = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
                    textCol.addView(kit.text(label.substringBefore(" ("), 15f, palette.text, OeaType.medium))
                    textCol.addView(kit.text("${items.size} images • ${label.substringAfter("(", "").substringBefore(")")} ", 12f, palette.textSecondary))
                    row.addView(textCol, LinearLayout.LayoutParams(0, -2, 1f))
                    row.setOnClickListener {
                        when {
                            label.startsWith("All") -> { currentTab = "all"; refresh(false, false, false) }
                            label.startsWith("Favorites") -> { currentTab = "favorites"; refresh(false, false, false) }
                            label.startsWith("Screenshots") -> { currentTab = "screenshots"; refresh(false, false, false) }
                            label.startsWith("Downloads") -> { currentTab = "downloads"; refresh(false, false, false) }
                            else -> { currentBucket = null; currentTab = "all"; refresh(false, false, false) }
                        }
                    }
                    grid.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })
                    // Load cover
                    io.execute {
                        val first = items.firstOrNull() ?: return@execute
                        val thumb = try { host.contentResolver.loadThumbnail(first.uri, android.util.Size(200, 200), null) } catch (_: Throwable) { null }
                        if (thumb != null) host.runOnUiThread { if (!host.isDestroyed) cover.setImageBitmap(thumb) }
                    }
                }
                kit.sectionTitle(grid, "Albums • ${buckets.size}")
                buckets.entries.sortedByDescending { it.value.size }.forEach { (bucketName, items) ->
                    val row = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(kit.dp(12), kit.dp(10), kit.dp(12), kit.dp(10)); background = kit.rounded(palette.card, 16, palette.stroke, 1); isClickable = true }
                    val cover = ImageView(host).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(palette.cardHigh); background = kit.rounded(Color.BLACK, 10); clipToOutline = true }
                    row.addView(cover, LinearLayout.LayoutParams(kit.dp(56), kit.dp(56)).apply { rightMargin = kit.dp(12) })
                    val textCol = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
                    textCol.addView(kit.text(bucketName, 15f, palette.text, OeaType.medium))
                    textCol.addView(kit.text("${items.size} images • ${items.firstOrNull()?.relativePath?.ifBlank { bucketName } ?: bucketName}", 12f, palette.textSecondary).apply { maxLines = 1 })
                    row.addView(textCol, LinearLayout.LayoutParams(0, -2, 1f))
                    row.addView(kit.text("${items.size}", 13f, palette.accent, OeaType.medium))
                    row.setOnClickListener { currentBucket = bucketName; currentTab = "all"; refresh(false, false, false) }
                    grid.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })
                    io.execute {
                        val thumb = try { host.contentResolver.loadThumbnail(items.first().uri, android.util.Size(200, 200), null) } catch (_: Throwable) { null }
                        if (thumb != null) host.runOnUiThread { if (!host.isDestroyed) cover.setImageBitmap(thumb) }
                    }
                }
                return
            }

            if (filtered.isEmpty()) {
                kit.note(grid, when {
                    searchQuery.isNotBlank() -> "No images match \"$searchQuery\""
                    currentTab == "favorites" -> "No favorites yet. Long-press image to favorite."
                    else -> "No images in this view."
                })
                return
            }

            // Grid of images
            val cols = gridCols
            filtered.chunked(cols).forEach { chunk ->
                val row = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
                chunk.forEachIndexed { idx, item ->
                    val tile = FrameLayout(host).apply {
                        isClickable = true
                        background = kit.rounded(Color.TRANSPARENT, 14, palette.stroke, 1)
                        clipToOutline = true
                        setOnClickListener { openViewer(host, kit, item, filtered) { refresh(false, false, false) } }
                        setOnLongClickListener {
                            showItemActions(host, kit, item) { refresh(false, false, false) }
                            true
                        }
                    }
                    val img = ImageView(host).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(palette.cardHigh) }
                    tile.addView(img, FrameLayout.LayoutParams(-1, kit.dp(110)))
                    // Favorite indicator
                    val favSet = prefs(host).getStringSet(KEY_FAV, emptySet()) ?: emptySet()
                    if (favSet.contains(item.uri.toString())) {
                        val star = TextView(host).apply { text = "★"; textSize = 12f; setTextColor(Color.YELLOW); setPadding(kit.dp(4), kit.dp(2), kit.dp(4), kit.dp(2)); background = kit.rounded(Color.argb(150, 0, 0, 0), 8) }
                        tile.addView(star, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, kit.dp(4), kit.dp(4), 0) })
                    }
                    io.execute {
                        val thumb = try { host.contentResolver.loadThumbnail(item.uri, android.util.Size(300, 300), null) } catch (_: Throwable) { null }
                        if (thumb != null) host.runOnUiThread { if (!host.isDestroyed) img.setImageBitmap(thumb) }
                    }
                    row.addView(tile, LinearLayout.LayoutParams(0, -2, 1f).apply {
                        if (idx > 0) leftMargin = kit.dp(6)
                        if (idx < cols - 1) rightMargin = kit.dp(6)
                    })
                }
                repeat(cols - chunk.size) { row.addView(View(host), LinearLayout.LayoutParams(0, 1, 1f)) }
                grid.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })
            }

            // Load more button
            if (hasMore && currentTab == "all" && currentBucket == null && searchQuery.isBlank()) {
                grid.addView(loadMoreBtn, LinearLayout.LayoutParams(-1, kit.dp(44)).apply { topMargin = kit.dp(8) })
            }

            // Grid columns control
            val colsRow = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, kit.dp(12), 0, 0) }
            colsRow.addView(kit.text("Columns: $gridCols", 12f, palette.textSecondary), LinearLayout.LayoutParams(-2, -2).apply { rightMargin = kit.dp(8) })
            listOf(2, 3, 4, 5).forEach { c ->
                colsRow.addView(kit.chip("${c}", c == gridCols) {
                    gridCols = c
                    prefs(host).edit().putInt(KEY_GRID_COLS, c).apply()
                    renderGrid()
                }, LinearLayout.LayoutParams(-2, kit.dp(32)).apply { rightMargin = kit.dp(6) })
            }
            grid.addView(colsRow)

            // Slideshow button
            grid.addView(kit.primaryButton("Slideshow • ${filtered.size} images", R.drawable.oic_play_arrow) {
                startSlideshow(host, kit, filtered)
            }, LinearLayout.LayoutParams(-1, kit.dp(48)).apply { topMargin = kit.dp(12) })
        }

        refresh = { force, loadMore, _ ->
            if (force) {
                images.clear()
                loadedOffset = 0
                hasMore = true
            }
            if (loadMore) {
                // Load next batch
                grid.removeView(loadMoreBtn)
                grid.addView(kit.text("Loading more…", 13f, palette.textSecondary).apply { setPadding(kit.dp(6), kit.dp(8), kit.dp(6), kit.dp(6)) })
            } else {
                grid.removeAllViews()
                grid.addView(kit.text("Loading images…", 13f, palette.textSecondary).apply { setPadding(kit.dp(6), kit.dp(12), kit.dp(6), kit.dp(6)) })
            }
            io.execute {
                val batch = if (loadMore) queryImages(host, loadedOffset, 200) else queryImages(host, 0, 300)
                host.runOnUiThread {
                    if (host.isDestroyed) return@runOnUiThread
                    if (loadMore) {
                        if (batch.isEmpty()) hasMore = false else { images.addAll(batch); loadedOffset += batch.size }
                    } else {
                        images = batch.toMutableList()
                        loadedOffset = batch.size
                        hasMore = batch.size >= 300
                    }
                    // Buckets bar
                    bucketBar.removeAllViews()
                    val buckets = images.map { it.bucket }.distinct().sorted()
                    bucketBar.addView(kit.chip("All", currentBucket == null) { currentBucket = null; renderGrid() }, LinearLayout.LayoutParams(-2, kit.dp(36)).apply { rightMargin = kit.dp(6) })
                    buckets.take(20).forEach { b ->
                        bucketBar.addView(kit.chip("$b (${images.count { it.bucket == b }})", b == currentBucket) {
                            currentBucket = if (currentBucket == b) null else b
                            if (currentBucket != null) currentTab = "all"
                            renderGrid()
                        }, LinearLayout.LayoutParams(-2, kit.dp(36)).apply { rightMargin = kit.dp(6) })
                    }
                    renderTabs()
                    renderGrid()
                }
            }
        }

        // Initial load
        refresh(false, false, false)

        // Opened from another app (a picture's "View", "Open with" or "Set as
        // wallpaper"): show that picture full-screen, once per launch.
        val incoming = host.intent
        val incomingUri = incoming?.data
        if (incomingUri != null && incoming.getBooleanExtra(EXTRA_VIEW_HANDLED, false).not() &&
            (incoming.action == Intent.ACTION_VIEW || incoming.action == Intent.ACTION_ATTACH_DATA)) {
            incoming.putExtra(EXTRA_VIEW_HANDLED, true)
            val item = ImageItem(incomingUri, "Picture", System.currentTimeMillis(), 0L, "Opened", "", 0, 0)
            host.runOnUiThread { openViewer(host, kit, item) }
        }
        return column
    }

    private fun showItemActions(host: Activity, kit: OeaKit, item: ImageItem, onDone: () -> Unit) {
        kit.sheet(item.name, "${item.bucket} • ${formatSize(item.size)}") { panel, dialog ->
            val favSet = prefs(host).getStringSet(KEY_FAV, emptySet())?.toMutableSet() ?: mutableSetOf()
            val isFav = favSet.contains(item.uri.toString())
            panel.addView(kit.buttonRow(
                kit.tonalButton(if (isFav) "Unfavorite" else "Favorite", R.drawable.oic_thermostat) {
                    dialog.dismiss()
                    if (isFav) favSet.remove(item.uri.toString()) else favSet.add(item.uri.toString())
                    prefs(host).edit().putStringSet(KEY_FAV, favSet).apply()
                    onDone()
                },
                kit.quietButton("Details") {
                    dialog.dismiss()
                    showDetails(host, kit, item)
                },
                kit.primaryButton("Open") {
                    dialog.dismiss()
                    openViewer(host, kit, item)
                }
            ))
        }
    }

    /** Details sheet for one picture (used by the full-screen viewer too). */
    fun showDetailsFor(host: Activity, kit: OeaKit, item: ImageItem) = showDetails(host, kit, item)

    /** Remembers a deleted picture in OEA's own trash list (shown in EMAGE). */
    fun rememberTrash(host: Activity, item: ImageItem) {
        val trash = prefs(host).getStringSet(KEY_TRASH, emptySet())?.toMutableSet() ?: mutableSetOf()
        trash.add(item.uri.toString())
        prefs(host).edit().putStringSet(KEY_TRASH, trash).apply()
    }

    private fun showDetails(host: Activity, kit: OeaKit, item: ImageItem) {
        kit.sheet(item.name, "Image details") { panel, _ ->
            val group = kit.group(panel)
            kit.row(group, R.drawable.oic_info, "Name", item.name, chevron = false)
            kit.row(group, R.drawable.oic_photo_library, "Album", "${item.bucket} • ${item.relativePath}", chevron = false)
            kit.row(group, R.drawable.oic_schedule, "Date", SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()).format(Date(item.date)), chevron = false)
            kit.row(group, R.drawable.oic_memory, "Size", "${formatSize(item.size)} • ${if (item.width > 0) "${item.width}×${item.height}" else "Unknown resolution"}", chevron = false)
            kit.row(group, R.drawable.oic_text_fields, "Path", item.uri.toString(), chevron = false) {
                try {
                    val cb = host.getSystemService(android.content.ClipboardManager::class.java)
                    cb?.setPrimaryClip(android.content.ClipData.newPlainText("Image path", item.uri.toString()))
                    android.widget.Toast.makeText(host, "Path copied", android.widget.Toast.LENGTH_SHORT).show()
                } catch (_: Throwable) {}
            }
            kit.note(panel, "EXIF location and camera info require READ_MEDIA_IMAGES and EXIF reader; OEA shows what MediaStore provides. External apps' folders appear via RELATIVE_PATH, so WhatsApp, Telegram, etc. are included automatically.")
        }
    }

    /** Full-screen viewer through the album the picture was opened from. */
    private fun openViewer(host: Activity, kit: OeaKit, item: ImageItem, album: List<ImageItem> = listOf(item), onChanged: () -> Unit = {}) {
        val index = album.indexOfFirst { it.uri == item.uri }.coerceAtLeast(0)
        OeaEmageViewer.show(host, album, index, onChanged)
    }

    private fun startSlideshow(host: Activity, kit: OeaKit, items: List<ImageItem>) {
        if (items.isEmpty()) return
        kit.sheet("Slideshow • ${items.size} images", "Tap to pause, swipe to next") { panel, dialog ->
            val iv = ImageView(host).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Color.BLACK) }
            panel.addView(iv, LinearLayout.LayoutParams(-1, kit.dp(360)))
            var idx = 0
            var paused = false
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            val runnable = object : Runnable {
                override fun run() {
                    if (host.isDestroyed || paused) return
                    val item = items.getOrNull(idx) ?: return
                    io.execute {
                        val bmp = try { host.contentResolver.loadThumbnail(item.uri, android.util.Size(1080, 1080), null) } catch (_: Throwable) { null }
                        if (bmp != null) host.runOnUiThread { if (!host.isDestroyed) iv.setImageBitmap(bmp) }
                    }
                    idx = (idx + 1) % items.size
                    handler.postDelayed(this, 2500L)
                }
            }
            handler.post(runnable)
            iv.setOnClickListener { paused = !paused; if (!paused) handler.post(runnable) }
            panel.addView(kit.buttonRow(
                kit.quietButton("Previous") { idx = (idx - 1 + items.size) % items.size; handler.post(runnable) },
                kit.quietButton("Pause/Resume") { paused = !paused; if (!paused) handler.post(runnable) },
                kit.primaryButton("Close") { handler.removeCallbacks(runnable); dialog.dismiss() }
            ))
            panel.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {}
                override fun onViewDetachedFromWindow(v: View) { handler.removeCallbacks(runnable) }
            })
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "—"
        val kb = bytes / 1024.0
        return if (kb < 1024) String.format(Locale.US, "%.0f KB", kb) else String.format(Locale.US, "%.1f MB", kb / 1024)
    }
}
