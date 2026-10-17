package com.oea.launcher.miniapps

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.oea.launcher.R
import com.oea.launcher.miniapps.OeaMediaLibrary.Item
import com.oea.launcher.miniapps.OeaMediaLibrary.Kind
import com.oea.launcher.ui.OeaAppKit
import com.oea.launcher.ui.OeaInsets
import com.oea.launcher.ui.OeaKit
import com.oea.launcher.ui.OeaPalette
import com.oea.launcher.ui.OeaSpanLookup
import com.oea.launcher.ui.OeaThumbnails
import com.oea.launcher.ui.OeaType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * EMAGE — OEA's picture app.
 *
 * What "every picture, not a page of them" costs, and what this pays:
 *
 *  * **The whole library.** [OeaMediaLibrary] reads every row MediaStore has, so
 *    the grid, the day groups and the album counts agree with reality instead of
 *    with the newest 300 files.
 *  * **A real grid.** One virtualised [RecyclerView], square-to-tall tiles at the
 *    device's own pixel density, prefetched one screen ahead, with tiles that keep
 *    their picture when they are recycled. The old one built 300 views by hand
 *    inside a settings ScrollView, which is why it was shallow, slow, and why a
 *    long flick left grey squares behind.
 *  * **Live updates.** A content observer means a picture taken in another app
 *    shows up without reopening anything, and OEA's own camera output is indexed
 *    the moment it is written.
 *  * **Albums, favourites, days, search, selection, slideshow, viewer with pinch
 *    zoom, real EXIF, wallpaper hand-off to Themes, and a trash that can bring
 *    OEA's own files back.**
 */
object OeaImageApp {

    private var active: EmageScreen? = null

    fun build(host: Activity, palette: OeaPalette): View {
        val screen = EmageScreen(host, palette)
        active = screen
        screen.attach()
        return screen.root
    }

    fun onTop(): Boolean = active != null
}

/**
 * The screen itself. A class rather than one long builder function, because a
 * gallery has real state — the album you are inside, the selection, the search,
 * the column count — and a screen that keeps that state in locals gets rebuilt by
 * its host, which is how "the app forgets where I was" bugs are born.
 */
internal class EmageScreen(val host: Activity, palette: OeaPalette) {

    private val kit = OeaAppKit(host, palette)
    val root: FrameLayout = kit.overlay()
    private val column = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
    private val content = FrameLayout(host).apply { layoutParams = LinearLayout.LayoutParams(-1, 0, 1f) }
    private val bar = kit.appBar("EMAGE", "Reading your library…", onBack = { runCatching { host.finish() } })
    private val searchBox = kit.searchField("Pictures, albums, dates", onQuery = { query ->
        this.query = query.trim()
        rebuild()
    })
    private val searchRow = FrameLayout(host).apply {
        setPadding(kit.dp(14), 0, kit.dp(14), kit.dp(8))
        addView(searchBox.root, FrameLayout.LayoutParams(-1, -2))
        visibility = View.GONE
    }
    private val tabs = kit.chipBar()
    private val status = LinearLayout(host).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(kit.dp(14), kit.dp(2), kit.dp(14), 0)
        visibility = View.GONE
    }
    private val scope = LinearLayout(host).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(kit.dp(16), kit.dp(6), kit.dp(12), kit.dp(6))
        background = kit.rounded(palette.accentSoft, 16)
        visibility = View.GONE
    }
    private val scopeTitle = kit.text("", 13.5f, palette.onAccentSoft, OeaType.medium)
    private val scopeBadge = kit.badge("0", palette.withAlpha(palette.accent, 0x33), palette.accent)

    private val photoGrid = kit.grid(SPAN_DEFAULT)
    private val albumGrid = kit.grid(2, kit.dp(8), kit.dp(10))
    private val trashList = kit.list(kit.dp(8))
    private val oeaHost = ScrollView(host).apply {
        isVerticalScrollBarEnabled = false
        clipToPadding = false
        setPadding(kit.dp(4), 0, kit.dp(4), kit.dp(90))
        visibility = View.GONE
    }
    private var stateCard: View? = null
    private val snack = kit.snack(root)

    // ---------------------------------------------------------------- state

    private enum class Surface { PHOTOS, ALBUMS, TRASH, OEA }

    private var surface = Surface.PHOTOS
    private var tab = "all"
    private var album: String? = null
    private var query = ""
    private var sort = OeaMediaLibrary.Sort.NEWEST
    private var columns = SPAN_DEFAULT
    private var tileShape = 1f
    private var selecting = false
    private val selected = LinkedHashSet<String>()
    private var snapshot: OeaMediaLibrary.Snapshot? = null
    private var subscription: OeaMediaLibrary.Subscription? = null
    private var progressRow: View? = null
    private var pendingDelete: List<Item> = emptyList()

    private val rows = ArrayList<Row>()
    private val headerAt = HashMap<Int, Int>()
    private var itemsForViewer = ArrayList<Item>()

    /** One thing in the picture grid: a day header or a tile. */
    private sealed class Row {
        data class Header(val label: String, val count: Int, val bytes: Long) : Row()
        data class Tile(val item: Item) : Row()
    }

    private val tiles = TilesAdapter()
    private val albums = AlbumAdapter()
    private val trashAdapter = TrashAdapter()

    init {
        root.addView(column, FrameLayout.LayoutParams(-1, -1))
        column.addView(bar.root, LinearLayout.LayoutParams(-1, -2))
        column.addView(searchRow, LinearLayout.LayoutParams(-1, -2))
        column.addView(
            kit.hscroll(tabs.root),
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(2) }
        )
        scope.addView(scopeTitle, LinearLayout.LayoutParams(0, -2, 1f))
        scope.addView(scopeBadge)
        scope.addView(
            kit.circleIconButton(R.drawable.oic_close, "Leave this album", Color.TRANSPARENT, palette.onAccentSoft) {
                album = null
                rebuild()
            },
            LinearLayout.LayoutParams(kit.dp(30), kit.dp(30)).apply { marginStart = kit.dp(6) }
        )
        column.addView(scope, LinearLayout.LayoutParams(-1, -2).apply {
            leftMargin = kit.dp(14)
            rightMargin = kit.dp(14)
            bottomMargin = kit.dp(6)
        })
        column.addView(status, LinearLayout.LayoutParams(-1, -2))
        column.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(photoGrid, FrameLayout.LayoutParams(-1, -1))
        content.addView(albumGrid, FrameLayout.LayoutParams(-1, -1))
        content.addView(trashList, FrameLayout.LayoutParams(-1, -1))
        content.addView(oeaHost, FrameLayout.LayoutParams(-1, -1))
        photoGrid.adapter = tiles
        albumGrid.adapter = albums
        trashList.adapter = trashAdapter
        (photoGrid.layoutManager as? androidx.recyclerview.widget.GridLayoutManager)?.let { manager ->
            val lookup = OeaSpanLookup(manager.spanCount).apply {
                headerType = TYPE_HEADER
                typeAt = { position -> tiles.viewTypeAt(position) }
                headerAt = { position -> this@EmageScreen.headerAt[position] }
            }
            manager.spanSizeLookup = lookup
        }
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = OeaInsets.bars(insets)
            column.setPadding(bars[0], 0, bars[2], bars[3])
            insets
        }
        with(kit) {
            bar.addAction(R.drawable.oic_search, "Search pictures") { toggleSearch() }
            bar.addAction(R.drawable.oic_refresh, "Scan again") { rescan() }
            bar.addAction(R.drawable.oic_more_vert, "More EMAGE options") { showMore() }
        }
        tabs.onPick = { id -> pickTab(id) }
        tabs.setOptions(TABS, tab)
        kit.onIdle(photoGrid) { prefetchVisible() }
        photoGrid.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (photoGrid.width > 0) {
                tileEdge = ((photoGrid.width - kit.dp(8)) / columns).coerceAtLeast(kit.dp(88))
            }
        }
        // The picture must be sized before the first bind, or the first screen of
        // tiles decodes at a guess and then again at the real size.
        tileEdge = (host.resources.displayMetrics.widthPixels / columns).coerceAtLeast(kit.dp(88))
        showSurface()
    }

    private var tileEdge: Int = (host.resources.displayMetrics.widthPixels / SPAN_DEFAULT).coerceAtLeast(kit.dp(88))

    // ------------------------------------------------------------- lifecycle

    fun attach() {
        subscription = OeaMediaLibrary.observe(
            host,
            setOf(Kind.IMAGE),
            onProgress = { _, label -> showProgress(label) }
        ) { built ->
            snapshot = built
            sort = OeaMediaLibrary.sortFor(host, Kind.IMAGE)
            showProgress(null)
            rebuild()
        }
        host.miniResultHook = { requestCode, resultCode, data -> handleResult(requestCode, resultCode, data) }
        // A screen that is no longer in the window must not keep listening: the
        // host can rebuild this view tree at any moment (theme change, a granted
        // permission), and a stale listener would paint into a dead grid.
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit

            override fun onViewDetachedFromWindow(v: View) {
                subscription?.dispose()
                subscription = null
                host.miniResultHook = null
            }
        })
        openIncoming()
    }

    private fun handleResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQUEST_DELETE) return false
        val items = pendingDelete
        pendingDelete = emptyList()
        OeaMediaLibrary.deleteFinished(host, items, resultCode == Activity.RESULT_OK)
        if (resultCode == Activity.RESULT_OK) {
            snack.show(if (items.size == 1) "Picture deleted" else "${items.size} pictures deleted", OeaKit.Tone.SUCCESS)
            OeaThumbnails.invalidateAll()
        }
        exitSelection()
        return true
    }

    /** A picture opened from Files, Messages or the camera goes straight to the viewer. */
    private fun openIncoming() {
        val intent = host.intent ?: return
        val uri = intent.data ?: return
        if (intent.action != Intent.ACTION_VIEW && intent.action != Intent.ACTION_ATTACH_DATA) return
        if (intent.getBooleanExtra(EXTRA_HANDLED, false)) return
        intent.putExtra(EXTRA_HANDLED, true)
        val existing = snapshot?.images?.firstOrNull { it.uri == uri }
        val item = existing ?: Item(
            id = 0L,
            uri = uri,
            name = uri.lastPathSegment?.substringAfterLast('/') ?: "Picture",
            takenAt = System.currentTimeMillis(),
            size = 0L,
            width = 0,
            height = 0,
            bucketId = 0L,
            album = "Opened",
            relativePath = "",
            durationMs = 0L,
            kind = Kind.IMAGE,
            systemFavorite = false
        )
        val albumItems = if (existing != null && album == null) currentList() else listOf(item)
        OeaEmageViewer.open(host, albumItems, albumItems.indexOfFirst { it.uri == uri }.coerceAtLeast(0)) { rebuild() }
    }

    // ------------------------------------------------------------------ views

    private fun toggleSearch() {
        if (searchRow.visibility == View.VISIBLE) {
            searchRow.visibility = View.GONE
            searchBox.close()
            if (query.isNotEmpty()) {
                query = ""
                searchBox.setText("")
                rebuild()
            }
            return
        }
        searchRow.visibility = View.VISIBLE
        searchBox.focus()
    }

    private fun pickTab(id: String) {
        tab = id
        album = null
        when (id) {
            "albums" -> surface = Surface.ALBUMS
            "trash" -> surface = Surface.TRASH
            "oea" -> surface = Surface.OEA
            else -> surface = Surface.PHOTOS
        }
        exitSelection()
        showSurface()
        rebuild()
    }

    private fun showSurface() {
        photoGrid.visibility = if (surface == Surface.PHOTOS) View.VISIBLE else View.GONE
        albumGrid.visibility = if (surface == Surface.ALBUMS) View.VISIBLE else View.GONE
        trashList.visibility = if (surface == Surface.TRASH) View.VISIBLE else View.GONE
        oeaHost.visibility = if (surface == Surface.OEA) View.VISIBLE else View.GONE
        if (surface == Surface.OEA && oeaHost.childCount == 0) {
            oeaHost.addView(
                OeaGalleryApp.build(host, palette),
                ScrollView.LayoutParams(-1, -2)
            )
        }
        tabs.root.visibility = if (surface == Surface.OEA) View.GONE else View.VISIBLE
    }

    private fun showProgress(label: String?) {
        if (label == null) {
            progressRow?.let { status.removeView(it) }
            progressRow = null
            return
        }
        if (progressRow == null) {
            progressRow = kit.progressRow(label)
            status.addView(progressRow)
        }
        status.visibility = View.VISIBLE
    }

    private fun rebuild() {
        val built = snapshot
        if (built == null) {
            bar.setSubtitle("Reading your library…")
            rows.clear()
            tiles.notifyDataSetChanged()
            return
        }
        if (built.permissionMissing) {
            showState(
                R.drawable.oic_photo_library,
                "EMAGE cannot see your pictures yet",
                "Allow photo access and OEA will list every album on this phone — Camera, Screenshots, Downloads and the folders other apps write to. Nothing is uploaded, and nothing leaves the device.",
                "Allow access"
            ) { askPermission() }
            return
        }
        hideState()
        scope.visibility = if (album != null) View.VISIBLE else View.GONE
        album?.let { name -> scopeTitle.text = "Album  •  $name" }
        when (surface) {
            Surface.PHOTOS -> {
                val list = currentList()
                itemsForViewer = ArrayList(list)
                rows.clear()
                headerAt.clear()
                OeaMediaLibrary.days(list).forEach { day ->
                    val headerPosition = rows.size
                    rows.add(Row.Header(day.label, day.items.size, day.items.sumOf { it.size }))
                    day.items.forEachIndexed { index, item ->
                        rows.add(Row.Tile(item))
                        headerAt[headerPosition + index + 1] = headerPosition
                    }
                }
                tiles.notifyDataSetChanged()
                bar.title.text = "EMAGE"
                bar.setSubtitle(
                    when {
                        query.isNotBlank() -> "${list.size} matching ${if (list.size == 1) "picture" else "pictures"}"
                        album != null -> "$album • ${list.size} • ${OeaMediaLibrary.formatSize(list.sumOf { it.size })}"
                        else -> "${list.size} pictures • ${built.imageAlbums.size} albums • ${OeaMediaLibrary.formatSize(built.totalSize)}"
                    }
                )
            }

            Surface.ALBUMS -> {
                albums.notifyDataSetChanged()
                val total = built.images.size
                bar.title.text = "Albums"
                bar.setSubtitle("${built.imageAlbums.size} albums • $total pictures")
                if (built.imageAlbums.isEmpty()) {
                    showState(
                        R.drawable.oic_folder,
                        "No albums yet",
                        "An album is a folder with pictures in it. Take a photo, or allow photo access, and they appear here by themselves.",
                        "Allow access"
                    ) { askPermission() }
                }
            }

            Surface.TRASH -> {
                trashAdapter.notifyDataSetChanged()
                bar.title.text = "Recently deleted"
                bar.setSubtitle("${OeaMediaLibrary.trashRecords(host).size} remembered")
            }

            Surface.OEA -> {
                bar.title.text = "OEA pictures"
                bar.setSubtitle("Wallpapers, live scenes and what OEA's camera saved")
            }
        }
        if (selecting) updateSelectionBar()
    }

    /** The list the current tab and album scope describe, with search applied. */
    private fun currentList(): List<Item> {
        val built = snapshot ?: return emptyList()
        var list = built.images
        if (album != null) list = built.inAlbum(album!!, Kind.IMAGE)
        list = when (tab) {
            "favourites" -> OeaMediaLibrary.favouriteItems(built, host, Kind.IMAGE)
            "recent" -> {
                val cutoff = System.currentTimeMillis() - 7L * 86_400_000L
                list.filter { it.takenAt >= cutoff }
            }

            "screenshots" -> list.filter { it.isScreenshot() }
            "downloads" -> list.filter { it.isDownload() }
            "large" -> list.filter { it.size >= 4L shl 20 }
            "oea" -> list.filter { it.local || it.album.equals("OEA", ignoreCase = true) }
            else -> list
        }
        if (query.isNotBlank()) list = list.filter { it.matches(query) }
        return OeaMediaLibrary.order(list, sort)
    }

    private fun Item.matches(term: String): Boolean {
        val lower = term.lowercase(Locale.getDefault())
        if (name.lowercase(Locale.getDefault()).contains(lower)) return true
        if (album.lowercase(Locale.getDefault()).contains(lower)) return true
        if (relativePath.lowercase(Locale.getDefault()).contains(lower)) return true
        return SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(Date(takenAt)).lowercase(Locale.getDefault())
            .contains(lower) ||
            SimpleDateFormat("yyyy", Locale.getDefault()).format(Date(takenAt)).contains(lower)
    }

    private fun Item.isScreenshot(): Boolean =
        name.contains("screenshot", true) || album.contains("screenshot", true) || relativePath.contains("screenshot", true) ||
            album.contains("screenshots", true) || relativePath.contains("Screenshots", true)

    private fun Item.isDownload(): Boolean =
        album.contains("download", true) || relativePath.contains("Download", true)

    private fun askPermission() {
        val permission = if (Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_IMAGES
        else android.Manifest.permission.READ_EXTERNAL_STORAGE
        runCatching { host.requestPermissions(arrayOf(permission, android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 2201) }
            .onFailure { snack.show("Android would not open the permission dialog", OeaKit.Tone.WARNING) }
    }

    private fun rescan() {
        snack.show("Looking for new pictures…")
        OeaMediaLibrary.refresh(host, setOf(Kind.IMAGE))
    }

    private fun showState(icon: Int, title: String, body: String, action: String? = null, onAction: (() -> Unit)? = null) {
        hideState()
        val card = kit.emptyState(icon, title, body, action, onAction)
        val wrap = FrameLayout(host).apply {
            setPadding(kit.dp(16), kit.dp(24), kit.dp(16), 0)
            addView(card, FrameLayout.LayoutParams(-1, -2))
        }
        stateCard = wrap
        content.addView(wrap, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        photoGrid.visibility = View.GONE
        albumGrid.visibility = View.GONE
        trashList.visibility = View.GONE
        oeaHost.visibility = View.GONE
    }

    private fun hideState() {
        stateCard?.let { content.removeView(it) }
        stateCard = null
        showSurface()
    }

    private fun prefetchVisible() {
        if (surface != Surface.PHOTOS || rows.isEmpty()) return
        val (first, last) = kit.visibleRange(photoGrid)
        if (last <= first) return
        val uris = (first..last).mapNotNull { rows.getOrNull(it) as? Row.Tile }.map { it.item.uri }
        OeaThumbnails.prefetch(host, uris, tileEdge, radius = kit.dp(10))
    }

    // -------------------------------------------------------------- interactions

    private fun openTile(position: Int) {
        val row = rows.getOrNull(position) as? Row.Tile ?: return
        val items = itemsForViewer
        val index = items.indexOfFirst { it.uri == row.item.uri }.coerceAtLeast(0)
        OeaEmageViewer.open(host, items, index) { rebuild() }
    }

    private fun beginSelection(position: Int): Boolean {
        val row = rows.getOrNull(position) as? Row.Tile ?: return false
        selecting = true
        selected.clear()
        selected.add(row.item.key)
        tiles.notifyDataSetChanged()
        updateSelectionBar()
        return true
    }

    private fun toggleSelected(item: Item) {
        if (!selected.remove(item.key)) selected.add(item.key)
        tiles.notifyDataSetChanged()
        updateSelectionBar()
    }

    private fun updateSelectionBar() {
        selection.setCount(selected.size, itemsForViewer.size)
        val single = selected.size == 1
        selection.setAction(0, R.drawable.oic_share_filled, "Share") { shareSelected() }
        selection.setAction(1, R.drawable.oic_star_fill, if (single) "Favourite" else "Favourite") { favouriteSelected() }
        selection.setAction(2, R.drawable.oic_wallpaper, "Wallpaper") { wallpaperSelected() }
        selection.setAction(3, R.drawable.oic_delete, "Delete") { deleteSelected() }
        selection.setAction(4, R.drawable.oic_info, "Details") { detailsSelected() }
        selection.selectAll {
            selected.clear()
            itemsForViewer.forEach { selected.add(it.key) }
            tiles.notifyDataSetChanged()
            updateSelectionBar()
        }
        if (!selecting) selected.clear()
    }

    private fun exitSelection() {
        selecting = false
        selected.clear()
        selection.setCount(0, 0)
        tiles.notifyDataSetChanged()
    }

    private val selection: OeaAppKit.SelectionBar by lazy {
        kit.selectionBar(root, listOf(
            0 to "Share",
            0 to "Favourite",
            0 to "Wallpaper",
            0 to "Delete",
            0 to "Info"
        )) { exitSelection() }
    }

    private fun selectedItems(): List<Item> = itemsForViewer.filter { selected.contains(it.key) }

    private fun shareSelected() {
        val items = selectedItems()
        if (items.isEmpty()) return
        OeaMediaLibrary.share(host, items)
        exitSelection()
    }

    private fun favouriteSelected() {
        val items = selectedItems()
        if (items.isEmpty()) return
        var turnedOn = 0
        items.forEach { if (OeaMediaLibrary.toggleFavourite(host, it)) turnedOn++ }
        snack.show(if (items.size == 1) (if (turnedOn > 0) "Added to favourites" else "Removed from favourites")
        else "$turnedOn of ${items.size} added to favourites", OeaKit.Tone.SUCCESS)
        exitSelection()
        rebuild()
    }

    private fun wallpaperSelected() {
        val items = selectedItems()
        if (items.isEmpty()) return
        if (items.size > 1) {
            snack.show("Pick one picture for a wallpaper")
            return
        }
        OeaEmageViewer.sendToThemes(host, items.first())
        exitSelection()
    }

    private fun detailsSelected() {
        val items = selectedItems()
        if (items.isEmpty()) return
        OeaEmageViewer.details(host, palette, items.first())
        exitSelection()
    }

    private fun deleteSelected() {
        val items = selectedItems()
        if (items.isEmpty()) return
        kit.confirm(
            if (items.size == 1) "Delete this picture?" else "Delete ${items.size} pictures?",
            "Android asks you to confirm, because only it may remove files this app did not create. Pictures OEA saved itself go to EMAGE's trash, where you can bring them back.",
            "Delete",
            destructive = true
        ) { performDelete(items) }
    }

    private fun performDelete(items: List<Item>) {
        val sender = OeaMediaLibrary.requestDelete(host, items)
        if (sender != null) {
            pendingDelete = items
            val started = runCatching {
                @Suppress("DEPRECATION")
                host.startIntentSenderForResult(sender, REQUEST_DELETE, null, 0, 0, 0)
            }.isSuccess
            if (started) return
            pendingDelete = emptyList()
            snack.show("Android would not show its confirm dialog", OeaKit.Tone.WARNING)
            return
        }
        // Android 10 and older: delete directly, and honour the resolution dialog
        // the system throws at us when the file belongs to another app.
        val resolution = deleteWithRecoverable(items)
        if (resolution != null) {
            pendingDelete = items
            runCatching {
                @Suppress("DEPRECATION")
                host.startIntentSenderForResult(resolution, REQUEST_DELETE, null, 0, 0, 0)
            }.onFailure { snack.show("Android refused the delete", OeaKit.Tone.DANGER) }
            return
        }
        OeaMediaLibrary.refresh(host)
        snack.show(
            if (items.size == 1) "Picture removed from EMAGE" else "${items.size} pictures removed from EMAGE",
            OeaKit.Tone.SUCCESS
        )
        exitSelection()
        rebuild()
    }

    private fun deleteWithRecoverable(items: List<Item>): android.content.IntentSender? {
        var sender: android.content.IntentSender? = null
        items.forEach { item ->
            val outcome = runCatching { host.contentResolver.delete(item.uri, null, null) }
            when {
                outcome.getOrDefault(0) > 0 -> OeaMediaLibrary.recordDeletion(host, item)
                else -> {
                    // Android 10 refuses a silent delete of another app's file and
                    // hands back the dialog that grants it. Take it seriously
                    // instead of telling the user it failed.
                    val cause = (outcome.exceptionOrNull() as? SecurityException)?.cause
                    if (cause is android.content.RecoverableSecurityException) {
                        sender = cause.userAction.actionIntent.intentSender
                    }
                }
            }
        }
        return sender
    }

    private fun showMore() {
        val built = snapshot
        kit.sheet("EMAGE", "How this gallery looks and what it hides") { panel, _ ->
            val grid = kit.group(panel)
            kit.row(grid, R.drawable.oic_grid_view, "Columns", "How many tiles fit across the screen", value = "$columns", chevron = false)
            val columnsRow = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            listOf(2, 3, 4, 5).forEach { count ->
                columnsRow.addView(
                    kit.chip("$count", count == columns) {
                        columns = count
                        kit.setSpan(photoGrid, count)
                        rebuild()
                    },
                    LinearLayout.LayoutParams(0, kit.dp(38), 1f).apply { marginEnd = kit.dp(6) }
                )
            }
            grid.addView(columnsRow, LinearLayout.LayoutParams(-1, -2).apply { setPadding(kit.dp(14), 0, kit.dp(14), kit.dp(12)) })
            kit.row(grid, R.drawable.oic_layers, "Tile shape", if (tileShape == 1f) "Square" else if (tileShape > 1f) "Tall (3:4)" else "Wide (4:3)", chevron = false)
            val shapes = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
            listOf(1f to "Square", 3f / 4f to "Tall", 4f / 3f to "Wide").forEach { (ratio, label) ->
                shapes.addView(
                    kit.chip(label, tileShape == ratio) {
                        tileShape = ratio
                        rebuild()
                    },
                    LinearLayout.LayoutParams(0, kit.dp(38), 1f).apply { marginEnd = kit.dp(6) }
                )
            }
            grid.addView(shapes, LinearLayout.LayoutParams(-1, -2).apply { setPadding(kit.dp(14), 0, kit.dp(14), kit.dp(12)) })
            val sortNames = OeaMediaLibrary.Sort.values().map { it.label() }
            kit.row(grid, R.drawable.oic_sort_by_alpha, "Order", sort.label(), chevron = true) {
                kit.choose("Order the library by", null, sortNames, OeaMediaLibrary.Sort.values().indexOf(sort)) { index ->
                    sort = OeaMediaLibrary.Sort.values()[index]
                    OeaMediaLibrary.setSort(host, Kind.IMAGE, sort)
                    rebuild()
                }
            }
            kit.row(grid, R.drawable.oic_visibility_off, "Hidden albums", hiddenSummary(built), chevron = true) {
                manageHiddenAlbums(built)
            }
            kit.row(grid, R.drawable.oic_delete, "Recently deleted", "${OeaMediaLibrary.trashRecords(host).size} remembered", chevron = true) {
                pickTab("trash")
                tabs.select("trash")
            }
            kit.row(grid, R.drawable.oic_photo_library, "OEA pictures", "Wallpapers, live scenes and OEA camera shots", chevron = true) {
                pickTab("oea")
                tabs.select("oea")
            }
            kit.row(grid, R.drawable.oic_refresh, "Rescan now", "Asks Android to index OEA's camera output again") {
                rescan()
            }
            kit.note(panel, "EMAGE reads the whole MediaStore collection every time it opens, groups it into days and albums, and never uploads any of it.")
        }
    }

    private fun hiddenSummary(built: OeaMediaLibrary.Snapshot?): String {
        val hidden = OeaMediaLibrary.hiddenAlbums(host)
        return when {
            hidden.isEmpty() -> "Nothing hidden — every album MediaStore reports is shown"
            hidden.size == 1 -> "1 album hidden (${hidden.first()})"
            else -> "${hidden.size} albums hidden"
        }
    }

    private fun manageHiddenAlbums(built: OeaMediaLibrary.Snapshot?) {
        val names = built?.imageAlbums?.map { it.name } ?: emptyList()
        if (names.isEmpty()) {
            snack.show("No albums to hide yet")
            return
        }
        val chosen = LinkedHashSet(OeaMediaLibrary.hiddenAlbums(host))
        kit.sheet("Albums EMAGE should not list", "Hidden albums stay on the phone; only this gallery stops showing them.") { panel, dialog ->
            val list = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
            names.forEach { name ->
                val on = chosen.contains(name.lowercase(Locale.getDefault()))
                val row = LinearLayout(host).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(kit.dp(14), kit.dp(10), kit.dp(12), kit.dp(10))
                    background = kit.ripple(kit.rounded(if (on) palette.accentSoft else palette.card, 16), 16)
                }
                row.addView(
                    kit.text(name, 15f, if (on) palette.onAccentSoft else palette.text, OeaType.medium).apply { maxLines = 1 },
                    LinearLayout.LayoutParams(0, -2, 1f)
                )
                val count = built?.imageAlbums?.firstOrNull { it.name == name }?.count ?: 0
                row.addView(kit.text("$count", 12.5f, palette.textSecondary).apply { setPadding(kit.dp(8), 0, kit.dp(10), 0) })
                row.addView(
                    kit.toggle(on) { value ->
                        if (value) chosen.add(name.lowercase(Locale.getDefault()))
                        else chosen.remove(name.lowercase(Locale.getDefault()))
                        row.background = kit.ripple(kit.rounded(if (value) palette.accentSoft else palette.card, 16), 16)
                    }
                )
                list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(5) })
            }
            panel.addView(kit.boundedScroll(list, 0.55f))
            panel.addView(
                kit.buttonRow(
                    kit.quietButton("Show all") {
                        chosen.clear()
                        OeaMediaLibrary.setHiddenAlbums(host, emptySet())
                        dialog.dismiss()
                        rebuild()
                    },
                    kit.primaryButton("Done") {
                        OeaMediaLibrary.setHiddenAlbums(host, chosen.toSet())
                        dialog.dismiss()
                        rebuild()
                    }
                )
            )
        }
    }

    // ----------------------------------------------------------------- adapters

    private inner class TilesAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        fun viewTypeAt(position: Int): Int = when (rows.getOrNull(position)) {
            is Row.Header -> TYPE_HEADER
            else -> TYPE_TILE
        }

        override fun getItemViewType(position: Int): Int = viewTypeAt(position)

        override fun getItemCount(): Int = rows.size

        override fun getItemId(position: Int): Long = when (val row = rows[position]) {
            is Row.Tile -> row.item.id
            else -> position.toLong()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == TYPE_HEADER) return HeaderHolder(kit.listHeader("", null))
            val cover = kit.coverTile(-1, kit.dp(10))
            val shape = ShapeFrame(parent.context).apply {
                addView(cover.root, LayoutParams(-1, -1))
            }
            return TileHolder(cover, shape)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows.getOrNull(position)) {
                is Row.Header -> {
                    val header = (holder as HeaderHolder).view
                    (header.getChildAt(0) as? TextView)?.text =
                        "${row.label}  •  ${row.count} ${if (row.count == 1) "picture" else "pictures"}  •  ${OeaMediaLibrary.formatSize(row.bytes)}"
                }

                is Row.Tile -> {
                    val tile = holder as TileHolder
                    val item = row.item
                    tile.shape.ratio = tileShape
                    tile.cover.root.setOnClickListener {
                        if (selecting) {
                            if (beginSelectionFromClick(position)) return@setOnClickListener
                        } else {
                            openTile(position)
                        }
                    }
                    tile.cover.root.setOnLongClickListener {
                        if (!selecting) selecting = true
                        toggleSelected(item)
                        true
                    }
                    tile.cover.setSelected(selecting && selected.contains(item.key))
                    tile.cover.showStar(OeaMediaLibrary.isFavourite(host, item))
                    tile.cover.showBadge(if (item.local) "OEA" else null)
                    tile.cover.image.scaleType = if (tileShape >= 1f) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
                    OeaThumbnails.load(
                        tile.cover.image,
                        item.uri,
                        tileEdge,
                        video = false,
                        cornerRadiusPx = kit.dp(10),
                        tag = position
                    )
                }

                null -> Unit
            }
        }

        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            if (holder is TileHolder) OeaThumbnails.cancel(holder.cover.image)
        }

        private fun beginSelectionFromClick(position: Int): Boolean {
            val row = rows.getOrNull(position) as? Row.Tile ?: return false
            toggleSelected(row.item)
            return true
        }
    }

    /** Forces a tile to the gallery's aspect without a measure pass per row. */
    private inner class ShapeFrame(context: Context) : FrameLayout(context) {
        var ratio: Float = 1f
            set(value) {
                if (field != value) {
                    field = value
                    requestLayout()
                }
            }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            val width = measuredWidth
            if (width > 0) {
                super.onMeasure(
                    widthMeasureSpec,
                    MeasureSpec.makeMeasureSpec((width / ratio.coerceAtLeast(0.2f)).toInt().coerceAtLeast(kit.dp(56)), MeasureSpec.EXACTLY)
                )
            }
        }
    }

    private inner class TileHolder(val cover: OeaAppKit.CoverTile, val shape: ShapeFrame) : RecyclerView.ViewHolder(shape)
    private inner class HeaderHolder(val view: LinearLayout) : RecyclerView.ViewHolder(view)

    private inner class AlbumAdapter : RecyclerView.Adapter<AlbumAdapter.Holder>() {

        inner class Holder(val card: OeaAppKit.AlbumCard) : RecyclerView.ViewHolder(card.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(kit.albumCard(kit.dp(112)))

        override fun getItemCount(): Int {
            val built = snapshot ?: return 0
            return built.imageAlbums.size
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val built = snapshot ?: return
            val albumData = built.imageAlbums.getOrNull(position) ?: return
            val card = holder.card
            card.name.text = albumData.name
            card.meta.text = "${albumData.count} • ${OeaMediaLibrary.formatSize(albumData.size)}"
            card.badge.text = SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(albumData.lastTakenAt))
            albumData.cover?.let { cover ->
                OeaThumbnails.load(card.cover, cover.uri, kit.dp(220), false, 0, position)
            }
            card.root.setOnClickListener {
                album = albumData.name
                surface = Surface.PHOTOS
                tabs.select("albums")
                showSurface()
                rebuild()
            }
            card.root.setOnLongClickListener {
                albumActions(albumData)
                true
            }
        }
    }

    private fun albumActions(data: OeaMediaLibrary.Album) {
        kit.actions(
            data.name,
            "${data.count} pictures • ${OeaMediaLibrary.formatSize(data.size)}${if (data.path.isNotBlank()) " • ${data.path}" else ""}",
            listOf(
                OeaAppKit.Action(R.drawable.oic_photo_library, "Open", "Show these pictures in the grid") {
                    album = data.name
                    surface = Surface.PHOTOS
                    showSurface()
                    rebuild()
                },
                OeaAppKit.Action(R.drawable.oic_play_arrow, "Slideshow", "Play this album full-screen") {
                    val list = snapshot?.inAlbum(data.name, Kind.IMAGE) ?: emptyList()
                    if (list.isNotEmpty()) OeaEmageViewer.slideshow(host, list)
                },
                OeaAppKit.Action(R.drawable.oic_visibility_off, "Hide this album", "EMAGE stops listing it (nothing is deleted)") {
                    val hidden = OeaMediaLibrary.hiddenAlbums(host).toMutableSet()
                    hidden.add(data.name.lowercase(Locale.getDefault()))
                    OeaMediaLibrary.setHiddenAlbums(host, hidden)
                },
                OeaAppKit.Action(
                    R.drawable.oic_folder, "Share the newest", "Sends the ten most recent pictures of this album",
                    disabled = data.count == 0
                ) {
                    val list = (snapshot?.inAlbum(data.name, Kind.IMAGE) ?: emptyList()).sortedByDescending { it.takenAt }.take(10)
                    OeaMediaLibrary.share(host, list, "Share ${data.name}")
                }
            )
        )
    }

    private inner class TrashAdapter : RecyclerView.Adapter<TrashAdapter.Holder>() {

        inner class Holder(val row: LinearLayout, val name: TextView, val meta: TextView, val restore: TextView) :
            RecyclerView.ViewHolder(row)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val row = LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(kit.dp(14), kit.dp(12), kit.dp(12), kit.dp(12))
                background = kit.rounded(palette.card, 20, palette.stroke, 1)
            }
            val texts = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
            val name = kit.text("", 15f, palette.text, OeaType.medium).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
            val meta = kit.text("", 12f, palette.textSecondary).apply { maxLines = 2 }
            texts.addView(name)
            texts.addView(meta, LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(2) })
            row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            val restore = kit.tonalButton("Restore") { }
            row.addView(restore, LinearLayout.LayoutParams(-2, kit.dp(38)).apply { marginStart = kit.dp(10) })
            return Holder(row, name, meta, restore)
        }

        override fun getItemCount(): Int = OeaMediaLibrary.trashRecords(host).size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val record = OeaMediaLibrary.trashRecords(host).getOrNull(position) ?: return
            holder.name.text = record.name
            holder.meta.text = "${record.album} • ${OeaMediaLibrary.formatDate(record.deletedAt)}" +
                if (record.recoverable) " • still on this phone" else " • removed by Android"
            holder.restore.isEnabled = record.recoverable
            holder.restore.alpha = if (record.recoverable) 1f else 0.4f
            holder.restore.setOnClickListener {
                if (OeaMediaLibrary.restore(host, record)) {
                    snack.show("Back in your library", OeaKit.Tone.SUCCESS)
                    trashAdapter.notifyDataSetChanged()
                } else {
                    snack.show("The file is not there any more", OeaKit.Tone.WARNING)
                }
            }
            holder.row.setOnLongClickListener {
                kit.confirm("Forget this record?", "EMAGE stops remembering this deletion. The picture itself is already gone.", "Forget") {
                    OeaMediaLibrary.removeTrashRecord(host, record.key)
                    trashAdapter.notifyDataSetChanged()
                }
                true
            }
        }
    }

    private fun OeaMediaLibrary.Sort.label(): String = when (this) {
        OeaMediaLibrary.Sort.NEWEST -> "Newest first"
        OeaMediaLibrary.Sort.OLDEST -> "Oldest first"
        OeaMediaLibrary.Sort.NAME -> "Name (A–Z)"
        OeaMediaLibrary.Sort.SIZE -> "Largest file"
        OeaMediaLibrary.Sort.DURATION -> "Longest"
    }

    companion object {
        private const val SPAN_DEFAULT = 3
        private const val TYPE_TILE = 0
        private const val TYPE_HEADER = 1
        private const val REQUEST_DELETE = 4310
        const val EXTRA_HANDLED = "oea.emage.view_handled"
        private val TABS = listOf(
            "all" to "All",
            "albums" to "Albums",
            "favourites" to "Favourites",
            "recent" to "Last 7 days",
            "screenshots" to "Screenshots",
            "downloads" to "Downloads",
            "large" to "Large",
            "oea" to "OEA",
            "trash" to "Recently deleted"
        )
    }
}
