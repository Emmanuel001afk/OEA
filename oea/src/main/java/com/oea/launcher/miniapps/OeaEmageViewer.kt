package com.oea.launcher.miniapps

import android.app.Activity
import android.app.Dialog
import android.app.WallpaperManager
import com.oea.launcher.wallpaper.OeaWallpaperStore
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.oea.launcher.R
import com.oea.launcher.ui.OeaAppKit
import com.oea.launcher.ui.OeaInsets
import com.oea.launcher.ui.OeaKit
import com.oea.launcher.ui.OeaPalette
import com.oea.launcher.ui.OeaThumbnails
import com.oea.launcher.ui.OeaType
import com.oea.launcher.ui.OeaZoomImageView
import androidx.recyclerview.widget.PagerSnapHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * EMAGE's full-screen viewer, its details sheet and its slideshow.
 *
 * The old viewer was a `Dialog` with one `ImageView`: no zoom, no page memory, a
 * 1440×2560 bitmap loaded only for the current page, and a "Details" sheet that
 * admitted it had no EXIF reader. This is a virtualised horizontal pager — every
 * page is a pinch-zoomable view that fetches a screen-sized bitmap for itself and
 * a tile-sized one for its neighbours — plus chrome that gets out of the way, real
 * camera data, and the actions a picture actually needs.
 */
internal object OeaEmageViewer {

    private val WALLPAPER_WORKER = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "OeaWallpaperPick").apply { isDaemon = true }
    }

    /** Opens [items] at [index]. [onChanged] runs after a favourite or a delete. */
    fun open(host: Activity, items: List<OeaMediaLibrary.Item>, index: Int, onChanged: () -> Unit = {}) {
        if (items.isEmpty()) return
        Viewer(host, items, index, onChanged).show()
    }

    fun slideshow(host: Activity, items: List<OeaMediaLibrary.Item>) {
        if (items.isEmpty()) return
        Slideshow(host, items).show()
    }

    /** The details sheet EMAGE's selection bar and viewer both open. */
    fun details(host: Activity, palette: OeaPalette, item: OeaMediaLibrary.Item) {
        val kit = OeaKit(host, palette)
        val exif = runCatching { OeaMediaProbe.photo(host, item.uri) }.getOrNull()
        kit.sheet(item.name, "${item.album} • ${OeaMediaLibrary.formatSize(item.size)}") { panel, _ ->
            val group = kit.group(panel)
            kit.row(group, R.drawable.oic_photo_library, "Taken", OeaMediaLibrary.formatDate(item.takenAt), chevron = false)
            kit.row(group, R.drawable.oic_layers, "Resolution",
                if (item.hasRealResolution) "${item.width}×${item.height}  •  ${megapixels(item.width, item.height)}"
                else probeResolution(host, item) ?: "Not recorded", chevron = false)
            kit.row(group, R.drawable.oic_memory, "File", OeaMediaLibrary.formatSize(item.size), chevron = false)
            kit.row(
                group, R.drawable.oic_folder, "Album",
                buildString {
                    append(item.album.ifBlank { "Unfiled" })
                    if (item.relativePath.isNotBlank()) append("  •  ${item.relativePath.trimEnd('/')}")
                },
                chevron = false
            )
            kit.row(group, R.drawable.oic_text_fields, "Address", OeaMediaLibrary.pathOf(item), chevron = false) {
                copy(host, OeaMediaLibrary.pathOf(item), "Path copied")
            }
            if (exif != null && exif.hasCamera) {
                kit.sectionTitle(panel, "Camera")
                val camera = kit.group(panel)
                kit.row(camera, R.drawable.oic_photo_library, "Camera",
                    listOf(exif.make, exif.model).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Not recorded" }, chevron = false)
                if (exif.lens.isNotBlank()) {
                    kit.row(camera, R.drawable.oic_visibility_off, "Lens", exif.lens, chevron = false)
                }
                kit.row(camera, R.drawable.oic_schedule, "Exposure", "${exif.exposureSeconds}  •  ${exif.aperture}  •  ISO ${exif.iso}", chevron = false)
                kit.row(camera, R.drawable.oic_tune, "Focal length", "${exif.focalLength}  •  flash ${if (exif.flash) "fired" else "off"}  •  ${exif.metering}", chevron = false)
                if (exif.takenAt > 0L) {
                    kit.row(camera, R.drawable.oic_info, "EXIF time", exif.takenLabel, chevron = false)
                }
                if (exif.software.isNotBlank()) {
                    kit.row(camera, R.drawable.oic_developer_mode, "Processed by", exif.software, chevron = false)
                }
            }
            kit.sectionTitle(panel, "Where")
            val place = kit.group(panel)
            if (exif?.hasLocation == true) {
                kit.row(place, R.drawable.oic_location_on, "Coordinates", exif.locationLabel,
                    value = exif.altitudeMeters?.let { "%.0f m".format(it) }, chevron = false) {
                    copy(host, exif.locationLabel, "Coordinates copied")
                }
                kit.row(place, R.drawable.oic_map, "Open in a map", "Hands the spot to your maps app") {
                    openMap(host, exif.latitude!!, exif.longitude!!)
                }
            } else {
                kit.note(place, "This picture carries no GPS tag. OEA does not add one — and never reads your location for a photo.")
            }
            kit.note(panel, "Everything here is read from the file itself with Android's EXIF reader. No upload, no lookup, no guess.")
        }
    }

    fun sendToThemes(host: Activity, item: OeaMediaLibrary.Item) {
        runCatching {
            host.startActivity(
                Intent(host, com.oea.launcher.themes.OeaThemesActivity::class.java).apply {
                    action = Intent.ACTION_ATTACH_DATA
                    setDataAndType(item.uri, "image/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
        }.onFailure { applyWallpaper(host, item, com.oea.launcher.wallpaper.OeaWallpaperStore.Target.BOTH) }
    }

    /**
     * Applies a picture through OEA's own wallpaper engine, so Home picks the new
     * colours up, the dim choice is honoured and a device that refuses the write
     * says so instead of quietly doing nothing.
     */
    fun applyWallpaper(host: Activity, item: OeaMediaLibrary.Item, target: com.oea.launcher.wallpaper.OeaWallpaperStore.Target) {
        OeaMediaLibrary.toast(host, "Preparing ${target.label.lowercase(Locale.getDefault())}…")
        WALLPAPER_WORKER.execute {
            val metrics = host.resources.displayMetrics
            val edge = maxOf(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(1080)
            val bitmap = runCatching {
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                host.contentResolver.openInputStream(item.uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= edge && bounds.outHeight / (sample * 2) >= edge) sample *= 2
                val options = android.graphics.BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                }
                host.contentResolver.openInputStream(item.uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, options) }
            }.getOrNull()
            host.runOnUiThread {
                if (host.isDestroyed) return@runOnUiThread
                if (bitmap == null) {
                    OeaMediaLibrary.toast(host, "That picture could not be read")
                    return@runOnUiThread
                }
                com.oea.launcher.wallpaper.OeaWallpaperStore.applyBitmap(host, bitmap, target) { result ->
                    OeaMediaLibrary.toast(host, if (result is OeaWallpaperStore.Result.Failed) result.why else result.message)
                }
            }
        }
    }

    private fun megapixels(width: Int, height: Int): String {
        if (width <= 0 || height <= 0) return ""
        val mega = width.toLong() * height.toLong() / 1_000_000.0
        return "%.1f MP".format(mega)
    }

    private fun probeResolution(host: Activity, item: OeaMediaLibrary.Item): String? = runCatching {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        host.contentResolver.openInputStream(item.uri)?.use { stream ->
            android.graphics.BitmapFactory.decodeStream(stream, null, bounds)
        }
        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            "${bounds.outWidth}×${bounds.outHeight}  •  ${megapixels(bounds.outWidth, bounds.outHeight)}"
        } else null
    }.getOrNull()

    private fun copy(context: Context, value: String, said: String) {
        runCatching {
            context.getSystemService(android.content.ClipboardManager::class.java)
                ?.setPrimaryClip(android.content.ClipData.newPlainText("EMAGE", value))
            OeaMediaLibrary.toast(context, said)
        }
    }

    private fun openMap(context: Context, lat: Double, lon: Double) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon(EMAGE picture)"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { OeaMediaLibrary.toast(context, "No maps app is installed") }
    }

    // ------------------------------------------------------------------- viewer

    /**
     * A horizontal pager of zoomable pictures. `PagerSnapHelper` on a
     * `RecyclerView` is the same machinery a system gallery uses: one page
     * settles under your thumb, neighbours are preloaded, and the picture you
     * swipe to is already decoded at tile size before it reaches the screen.
     */
    private class Viewer(
        private val host: Activity,
        private val items: List<OeaMediaLibrary.Item>,
        startIndex: Int,
        private val onChanged: () -> Unit
    ) {
        private val palette: OeaPalette = com.oea.launcher.ui.OeaTheme.resolve(host)
        private val kit = OeaAppKit(host, palette)
        private val dialog = Dialog(host, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        private val handler = Handler(Looper.getMainLooper())
        private var position = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        private var chromeVisible = true
        private var liveItems = items.toMutableList()

        private val pager = RecyclerView(host).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
        }
        private val counter = TextView(host).apply {
            setTextColor(Color.WHITE)
            textSize = 13.5f
            typeface = OeaType.medium
            letterSpacing = 0.02f
        }
        private val albumLabel = TextView(host).apply {
            setTextColor(0xB3FFFFFF.toInt())
            textSize = 11.5f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        private val topBar = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0x99000000.toInt())
        }
        private val bottomBar = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0x99000000.toInt())
        }
        private val root = FrameLayout(host).apply {
            setBackgroundColor(Color.BLACK)
            addView(pager, FrameLayout.LayoutParams(-1, -1))
            addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
            addView(bottomBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        }

        private val hideChrome = Runnable { if (chromeVisible) setChrome(false) }

        fun show() {
            pager.adapter = Pages()
            PagerSnapHelper().attachToRecyclerView(pager)
            pager.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(host, RecyclerView.HORIZONTAL, false)
            pager.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(view: RecyclerView, newState: Int) {
                    if (newState != RecyclerView.SCROLL_STATE_IDLE) return
                    val manager = view.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager ?: return
                    val first = manager.findFirstCompletelyVisibleItemPosition()
                    if (first != RecyclerView.NO_POSITION && first != position) {
                        position = first
                        paintLabels()
                    }
                }
            })
            topBar.setPadding(kit.dp(8), kit.dp(8), kit.dp(8), kit.dp(8))
            val titles = LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
            }
            titles.addView(counter, LinearLayout.LayoutParams(-1, -2))
            titles.addView(albumLabel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(1) })
            topBar.addView(kit.circleIconButton(R.drawable.oic_close, "Close", 0x33FFFFFF, Color.WHITE) { dialog.dismiss() },
                LinearLayout.LayoutParams(kit.dp(40), kit.dp(40)).apply { marginEnd = kit.dp(8) })
            topBar.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
            topBar.addView(kit.circleIconButton(R.drawable.oic_more_vert, "More", 0x33FFFFFF, Color.WHITE) { more() },
                LinearLayout.LayoutParams(kit.dp(40), kit.dp(40)).apply { marginStart = kit.dp(6) })
            buildActions()
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            dialog.setContentView(root)
            dialog.window?.apply {
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.BLACK))
                addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
                OeaInsets.edgeToEdge(this, lightBars = false)
            }
            root.setOnApplyWindowInsetsListener { _, insets ->
                val bars = OeaInsets.bars(insets)
                topBar.setPadding(kit.dp(8) + bars[0], bars[1] + kit.dp(4), kit.dp(8) + bars[2], kit.dp(8))
                bottomBar.setPadding(kit.dp(10) + bars[0], kit.dp(8), kit.dp(10) + bars[2], bars[3] + kit.dp(10))
                insets
            }
            paintLabels()
            dialog.show()
            pager.scrollToPosition(position)
            autoHide()
        }

        private fun buildActions() {
            bottomBar.removeAllViews()
            action(R.drawable.oic_star_fill, "Favourite") { toggleFavourite() }
            action(R.drawable.oic_share_filled, "Share") {
                OeaMediaLibrary.share(host, listOf(current()))
            }
            action(R.drawable.oic_edit, "Edit") { editCurrent() }
            action(R.drawable.oic_wallpaper, "Wallpaper") { wallpaperMenu() }
            action(R.drawable.oic_info, "Details") {
                setChrome(true)
                OeaEmageViewer.details(host, palette, current())
            }
            action(R.drawable.oic_delete, "Delete") { deleteCurrent() }
        }

        private fun action(icon: Int, label: String, onClick: () -> Unit) {
            val cell = LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                isClickable = true
                isFocusable = true
                setPadding(kit.dp(6), kit.dp(7), kit.dp(6), kit.dp(6))
                background = kit.ripple(null, 12)
                contentDescription = label
                setOnClickListener { onClick() }
            }
            cell.addView(kit.iconView(icon, Color.WHITE, 21))
            cell.addView(
                TextView(host).apply {
                    text = label
                    setTextColor(0xE6FFFFFF.toInt())
                    textSize = 10.5f
                    gravity = Gravity.CENTER
                    maxLines = 1
                    setPadding(0, kit.dp(2), 0, 0)
                }
            )
            bottomBar.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
        }

        private fun current(): OeaMediaLibrary.Item = liveItems[position.coerceIn(0, (liveItems.size - 1).coerceAtLeast(0))]

        private fun paintLabels() {
            val item = current()
            counter.text = "${position + 1} of ${liveItems.size}  •  ${item.name}"
            albumLabel.text = buildString {
                append(item.album.ifBlank { "Unfiled" })
                if (item.local) append("  •  OEA")
                if (OeaMediaLibrary.isFavourite(host, item)) append("  •  ★")
                append("  •  ${SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(item.takenAt))}")
            }
            if (position + 2 < liveItems.size) {
                OeaThumbnails.prefetch(host, listOf(liveItems[position + 1].uri, liveItems[position + 2].uri), kit.dp(900))
            }
        }

        private fun setChrome(visible: Boolean) {
            chromeVisible = visible
            topBar.animate().alpha(if (visible) 1f else 0f).translationY(if (visible) 0f else -kit.dpf(18f)).setDuration(170L).start()
            bottomBar.animate().alpha(if (visible) 1f else 0f).translationY(if (visible) 0f else kit.dpf(18f)).setDuration(170L).start()
            handler.removeCallbacks(hideChrome)
            if (visible) autoHide()
        }

        private fun autoHide() {
            handler.removeCallbacks(hideChrome)
            handler.postDelayed(hideChrome, 4200L)
        }

        private fun more() {
            kit.actions("This picture", current().name, listOf(
                OeaAppKit.Action(R.drawable.oic_open_in_new, "Open with another app", "Sends the file to a system editor or viewer") {
                    OeaMediaLibrary.openExternally(host, current())
                },
                OeaAppKit.Action(R.drawable.oic_play_arrow, "Slideshow from here", "Plays the rest of this album full-screen") {
                    OeaEmageViewer.slideshow(host, liveItems.drop(position))
                },
                OeaAppKit.Action(R.drawable.oic_layers, "Copy to Pictures", "Publishes OEA's own shot into the device library",
                    disabled = !current().local) {
                    if (OeaMediaLibrary.publishToDeviceLibrary(host, current())) {
                        OeaMediaLibrary.toast(host, "Copied into Pictures")
                    } else {
                        OeaMediaLibrary.toast(host, "Android did not accept the copy")
                    }
                },
                OeaAppKit.Action(R.drawable.oic_memory, "Copy the path", "The address of this file, for another app") {
                    copy(host, OeaMediaLibrary.pathOf(current()), "Path copied")
                }
            ))
        }

        private fun wallpaperMenu() {
            val item = current()
            kit.sheet("Use as wallpaper", item.name) { panel, dialog ->
                val group = kit.group(panel)
                kit.row(group, R.drawable.oic_home, "Home screen", "Behind your icons, on this launcher or any other") {
                    dialog.dismiss()
                    applyWallpaper(host, item, OeaWallpaperStore.Target.HOME)
                }
                kit.row(group, R.drawable.oic_lock, "Lock screen", "Shown before you unlock") {
                    dialog.dismiss()
                    applyWallpaper(host, item, OeaWallpaperStore.Target.LOCK)
                }
                kit.row(group, R.drawable.oic_wallpaper, "Both", "Home and lock at once") {
                    dialog.dismiss()
                    applyWallpaper(host, item, OeaWallpaperStore.Target.BOTH)
                }
                kit.row(group, R.drawable.oic_palette, "Choose in Themes", "OEA's theme app previews it on Home and lock first") {
                    dialog.dismiss()
                    sendToThemes(host, item)
                }
            }
        }

        private fun editCurrent() {
            val item = current()
            kit.sheet("Edit", item.name) { panel, dialog ->
                val group = kit.group(panel)
                kit.row(group, R.drawable.oic_refresh, "Rotate right", "Saved into the picture itself") {
                    dialog.dismiss()
                    rotateAndSave(item)
                }
                kit.row(group, R.drawable.oic_open_in_new, "Open in an editor", "The phone's own editor, with crop and filters") {
                    dialog.dismiss()
                    val started = runCatching {
                        host.startActivity(
                            Intent(Intent.ACTION_EDIT).apply {
                                setDataAndType(item.uri, "image/*")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                            }
                        )
                    }.isSuccess
                    if (!started) OeaMediaLibrary.toast(host, "This phone has no editor for pictures")
                }
                kit.row(group, R.drawable.oic_open_in_full, "Set as wallpaper", "Android crops that to fit by itself") {
                    dialog.dismiss()
                    wallpaperMenu()
                }
                kit.note(panel, "EMAGE saves a rotation straight into the file, so every app sees it turned. For crop and filters, the phone's own editor does it best — EMAGE sends the picture there unchanged.")
            }
        }

        /**
         * Rotates the picture 90° and writes it back into the file
         * itself. Where Android will not let EMAGE overwrite the
         * original (a read-only grant from another app), a rotated
         * copy is published into Pictures instead — a real result,
         * never a silent failure.
         */
        private fun rotateAndSave(item: OeaMediaLibrary.Item) {
            OeaMediaLibrary.toast(host, "Rotating…")
            WALLPAPER_WORKER.execute {
                val outcome = runCatching {
                    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    host.contentResolver.openInputStream(item.uri)?.use {
                        android.graphics.BitmapFactory.decodeStream(it, null, bounds)
                    }
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 4096) sample *= 2
                    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                    val src = host.contentResolver.openInputStream(item.uri)?.use {
                        android.graphics.BitmapFactory.decodeStream(it, null, opts)
                    } ?: return@runCatching false
                    val matrix = android.graphics.Matrix().apply { postRotate(90f) }
                    val rotated = android.graphics.Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
                    if (rotated !== src) src.recycle()
                    val isPng = item.name.substringAfterLast('.', "").lowercase() == "png"
                    val format = if (isPng) android.graphics.Bitmap.CompressFormat.PNG else android.graphics.Bitmap.CompressFormat.JPEG
                    val overwritten = runCatching {
                        host.contentResolver.openOutputStream(item.uri)?.use { out ->
                            rotated.compress(format, 95, out)
                        } ?: false
                    }.getOrDefault(false)
                    if (!overwritten) {
                        val ext = item.name.substringAfterLast('.', "jpg")
                        val values = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                                item.name.substringBeforeLast('.') + "_rotated." + ext)
                            put(android.provider.MediaStore.Images.Media.MIME_TYPE,
                                if (isPng) "image/png" else "image/jpeg")
                            if (android.os.Build.VERSION.SDK_INT >= 29) {
                                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                                    android.os.Environment.DIRECTORY_PICTURES)
                            }
                        }
                        val copy = host.contentResolver.insert(
                            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        val saved = copy?.let { uri ->
                            host.contentResolver.openOutputStream(uri)?.use { out ->
                                rotated.compress(format, 95, out)
                            } ?: false
                        } ?: false
                        if (!saved) return@runCatching false
                    }
                    rotated.recycle()
                    OeaThumbnails.invalidate(item.uri)
                    true
                }
                val ok = outcome.getOrDefault(false)
                host.runOnUiThread {
                    if (ok) {
                        // The file itself now carries the turn, so
                        // the on-screen one goes back to straight.
                        currentZoomView()?.rotation = 0f
                        OeaMediaLibrary.toast(host, "Rotated and saved")
                    } else {
                        // No write access: still turn the view, and
                        // say plainly that the file is untouched.
                        rotateInPlace(item)
                        OeaMediaLibrary.toast(host, "Rotated the view only — Android would not let EMAGE save over that file")
                    }
                    onChanged()
                }
            }
        }

        /** Rotates what is on screen even with no editor installed — a real action, not a toast. */
        private fun rotateInPlace(item: OeaMediaLibrary.Item) {
            val view = currentZoomView() ?: return
            view.rotation = (view.rotation + 90f) % 360f
            OeaMediaLibrary.toast(host, "Rotated the view — an editor is needed to save a rotated file")
        }

        private fun currentZoomView(): OeaZoomImageView? {
            val manager = pager.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager ?: return null
            val holder = manager.findViewByPosition(position)?.let { pager.getChildViewHolder(it) }
            return (holder as? PageHolder)?.zoom
        }

        private fun toggleFavourite() {
            val on = OeaMediaLibrary.toggleFavourite(host, current())
            OeaMediaLibrary.toast(host, if (on) "Favourited" else "Removed from favourites")
            paintLabels()
            onChanged()
        }

        private fun deleteCurrent() {
            val item = current()
            kit.confirm("Delete this picture?", "Android will ask you to confirm it. OEA keeps a record in Recently deleted.", "Delete", destructive = true) {
                val sender = OeaMediaLibrary.requestDelete(host, listOf(item))
                if (sender != null) {
                    kit.sheet("Android confirms deletes", item.name) { panel, _ ->
                        kit.note(panel, "Tap Delete again to let the system dialog finish this — OEA cannot remove a file another app owns without it.")
                        val group = kit.group(panel)
                        kit.row(group, R.drawable.oic_delete, "Delete with Android's confirm", "Opens the system dialog") {
                            runCatching {
                                @Suppress("DEPRECATION")
                                host.startIntentSenderForResult(sender, 4311, null, 0, 0, 0)
                            }
                            dialog.dismiss()
                        }
                    }
                    return@confirm
                }
                val deleted = runCatching { host.contentResolver.delete(item.uri, null, null) > 0 }.getOrDefault(false) ||
                    runCatching { Uri.parse(item.uri.toString()).path?.let { java.io.File(it).delete() } == true }.getOrDefault(false)
                if (!deleted) {
                    OeaMediaLibrary.toast(host, "Android would not let this one be deleted")
                    return@confirm
                }
                OeaMediaLibrary.recordDeletion(host, item)
                OeaThumbnails.invalidate(item.uri)
                val index = liveItems.indexOfFirst { it.key == item.key }
                if (index >= 0) {
                    liveItems.removeAt(index)
                    pager.adapter?.notifyItemRemoved(index)
                    if (liveItems.isEmpty()) dialog.dismiss() else {
                        position = index.coerceAtMost(liveItems.lastIndex)
                        pager.scrollToPosition(position)
                        paintLabels()
                    }
                }
                onChanged()
            }
        }

        private inner class PageHolder(val frame: FrameLayout, val zoom: OeaZoomImageView, val loader: View) :
            RecyclerView.ViewHolder(frame)

        private inner class Pages : RecyclerView.Adapter<PageHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
                val zoom = OeaZoomImageView(host).apply {
                    layoutParams = RecyclerView.LayoutParams(-1, -1)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setBackgroundColor(Color.BLACK)
                    onTap = { setChrome(!chromeVisible) }
                }
                val frame = FrameLayout(host).apply {
                    addView(zoom, FrameLayout.LayoutParams(-1, -1))
                }
                val loader = View(host).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.TRANSPARENT)
                        setStroke(kit.dp(3), 0x66FFFFFF)
                    }
                    visibility = View.GONE
                }
                frame.addView(loader, FrameLayout.LayoutParams(kit.dp(28), kit.dp(28), Gravity.CENTER))
                return PageHolder(frame, zoom, loader)
            }

            override fun onBindViewHolder(holder: PageHolder, position: Int) {
                val item = liveItems.getOrNull(position) ?: return
                holder.zoom.rotation = 0f
                holder.zoom.reset()
                holder.loader.visibility = View.VISIBLE
                OeaThumbnails.loadFull(holder.zoom, item.uri, bigEdge(host), false) { ok ->
                    holder.loader.visibility = View.GONE
                    if (!ok) holder.zoom.setImageResource(R.drawable.oic_photo_library)
                }
            }

            override fun getItemCount(): Int = liveItems.size

            override fun onViewRecycled(holder: PageHolder) {
                OeaThumbnails.cancel(holder.zoom)
                holder.zoom.setImageDrawable(null)
            }

            private fun bigEdge(context: Context): Int {
                val metrics = context.resources.displayMetrics
                return maxOf(metrics.widthPixels, metrics.heightPixels).coerceAtMost(2200)
            }
        }
    }

    // ----------------------------------------------------------------- slideshow

    /**
     * A full-screen slideshow with a real timer: interval, pause, loop, and the
     * picture names it is showing. The old one lived in a bottom sheet with a
     * 360 dp box, which is not a slideshow, and it never stopped running when the
     * sheet closed.
     */
    private class Slideshow(private val host: Activity, private val items: List<OeaMediaLibrary.Item>) {
        private val dialog = Dialog(host, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        private val handler = Handler(Looper.getMainLooper())
        private val palette = com.oea.launcher.ui.OeaTheme.resolve(host)
        private val kit = OeaAppKit(host, palette)
        private var index = 0
        private var playing = true
        private var intervalMs = 3200L
        private var image: OeaZoomImageView? = null
        private var label: TextView? = null
        private var state: TextView? = null

        fun show() {
            val root = FrameLayout(host).apply { setBackgroundColor(Color.BLACK) }
            val view = OeaZoomImageView(host).apply {
                setBackgroundColor(Color.BLACK)
                onTap = { toggle() }
            }
            image = view
            root.addView(view, FrameLayout.LayoutParams(-1, -1))
            val bar = LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(0x88000000.toInt())
                setPadding(kit.dp(10), kit.dp(10), kit.dp(10), kit.dp(10))
            }
            val title = TextView(host).apply {
                setTextColor(Color.WHITE)
                textSize = 13f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            label = title
            bar.addView(title, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = kit.dp(10) })
            bar.addView(kit.quietButton("Prev") { step(-1) }.apply { setTextColor(Color.WHITE) })
            bar.addView(kit.quietButton("Pause") { toggle() }.apply {
                setTextColor(Color.WHITE)
                setPadding(kit.dp(14), kit.dp(9), kit.dp(14), kit.dp(9))
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = kit.dp(6) })
            bar.addView(kit.quietButton("Next") { step(1) }.apply {
                setTextColor(Color.WHITE)
                setPadding(kit.dp(14), kit.dp(9), kit.dp(14), kit.dp(9))
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = kit.dp(6) })
            bar.addView(kit.quietButton("Speed") { speed() }.apply {
                setTextColor(Color.WHITE)
                setPadding(kit.dp(14), kit.dp(9), kit.dp(14), kit.dp(9))
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = kit.dp(6) })
            bar.addView(kit.circleIconButton(R.drawable.oic_close, "Stop", 0x33FFFFFF, Color.WHITE) { close() },
                LinearLayout.LayoutParams(kit.dp(38), kit.dp(38)).apply { marginStart = kit.dp(8) })
            root.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            val badge = TextView(host).apply {
                setTextColor(Color.WHITE)
                textSize = 11.5f
                setPadding(kit.dp(10), kit.dp(5), kit.dp(10), kit.dp(5))
                background = GradientDrawable().apply {
                    cornerRadius = kit.dpf(12f)
                    setColor(0x88000000.toInt())
                }
            }
            state = badge
            root.addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                setMargins(kit.dp(10), kit.dp(34), kit.dp(10), 0)
            })
            root.setOnApplyWindowInsetsListener { _, insets ->
                val bars = OeaInsets.bars(insets)
                bar.setPadding(kit.dp(10) + bars[0], kit.dp(10), kit.dp(10) + bars[2], bars[3] + kit.dp(10))
                insets
            }
            dialog.setContentView(root)
            dialog.setOnDismissListener { close() }
            dialog.show()
            render()
            schedule()
        }

        private fun render() {
            val item = items.getOrNull(index) ?: return
            image?.rotation = 0f
            image?.reset()
            image?.let { OeaThumbnails.loadFull(it, item.uri, maxOf(host.resources.displayMetrics.widthPixels, host.resources.displayMetrics.heightPixels)) }
            label?.text = "${index + 1} / ${items.size}  •  ${item.name}"
        }

        private fun schedule() {
            handler.removeCallbacksAndMessages(null)
            if (!playing) {
                state?.text = "Paused"
                return
            }
            state?.text = "%.1f s".format(intervalMs / 1000.0)
            handler.postDelayed({
                index = (index + 1) % items.size
                render()
                schedule()
            }, intervalMs)
        }

        private fun step(delta: Int) {
            index = ((index + delta) % items.size + items.size) % items.size
            render()
            schedule()
        }

        private fun toggle() {
            playing = !playing
            schedule()
        }

        private fun speed() {
            val names = listOf("1.5 s", "3.2 s", "6 s", "10 s", "20 s")
            val values = listOf(1500L, 3200L, 6000L, 10000L, 20000L)
            val chosen = values.indexOf(intervalMs).coerceAtLeast(0)
            kit.choose("Slideshow speed", null, names, chosen) { pick ->
                intervalMs = values[pick]
                schedule()
            }
        }

        private fun close() {
            handler.removeCallbacksAndMessages(null)
            if (dialog.isShowing) runCatching { dialog.dismiss() }
        }
    }
}
