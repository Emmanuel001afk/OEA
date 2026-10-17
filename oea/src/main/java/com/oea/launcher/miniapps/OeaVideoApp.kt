package com.oea.launcher.miniapps

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.oea.launcher.R
import com.oea.launcher.miniapps.OeaMediaLibrary.Item
import com.oea.launcher.miniapps.OeaMediaLibrary.Kind
import com.oea.launcher.ui.OeaAppKit
import com.oea.launcher.ui.OeaInsets
import com.oea.launcher.ui.OeaKit
import com.oea.launcher.ui.OeaPalette
import com.oea.launcher.ui.OeaThumbnails
import com.oea.launcher.ui.OeaType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * VIP — the library half of "Video Instant Player".
 *
 * It reads the whole video and audio collection through [OeaMediaLibrary] (the
 * same index EMAGE uses, so one scan serves both apps), and it hands the player a
 * **queue**: tapping any clip starts that clip with the list around it, so next
 * and previous, auto-advance and repeat-all are real instead of decorative.
 *
 * What the old screen did not have and this does:
 *  * every folder Android reports, including other apps' (WhatsApp Video, Screen
 *    recordings, Downloads), with exact counts and covers;
 *  * a Continue watching row that resumes where you stopped, per clip;
 *  * playlists you can create, fill, reorder, rename and delete;
 *  * an audio tab that lists music and recordings with their album, duration and
 *    size, played by the same player;
 *  * selection with share, favourite, delete and "add to playlist";
 *  * long-press on a tile, real details from the file's own track table, and a
 *    subtitle loader that reads sidecar `.srt`/`.vtt` files.
 */
object OeaVideoApp {

    private var active: VipScreen? = null

    fun build(host: Activity, palette: OeaPalette): View {
        val screen = VipScreen(host, palette)
        active = screen
        screen.attach()
        return screen.root
    }
}

/** VIP's library screen. */
internal class VipScreen(val host: Activity, val palette: OeaPalette) {

    private val kit = OeaAppKit(host, palette)
    val root: FrameLayout = kit.overlay()
    private val column = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
    private val content = FrameLayout(host).apply { layoutParams = LinearLayout.LayoutParams(-1, 0, 1f) }
    private val bar = kit.appBar("VIP", "Reading your videos…", onBack = { runCatching { host.finish() } })
    private val searchBox = kit.searchField("Videos, audio, albums", onQuery = { value ->
        query = value.trim()
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
    private val videoGrid = kit.grid(2)
    private val albumGrid = kit.grid(2, kit.dp(8), kit.dp(10))
    private val playlistList = kit.list(kit.dp(8))
    private val audioList = kit.list(kit.dp(6))
    private val continueList = kit.rail(kit.dp(10))
    private var stateCard: View? = null
    private var progressRow: View? = null
    private val snack = kit.snack(root)

    private enum class Surface { VIDEOS, ALBUMS, PLAYLISTS, AUDIO, CONTINUE }

    private var surface = Surface.VIDEOS
    private var tab = "all"
    private var album: String? = null
    private var playlist: String? = null
    private var query = ""
    private var sort = OeaMediaLibrary.Sort.NEWEST
    private var selecting = false
    private val selected = LinkedHashSet<String>()
    private var snapshot: OeaMediaLibrary.Snapshot? = null
    private var subscription: OeaMediaLibrary.Subscription? = null
    private val rows = ArrayList<Item>()
    private var pendingDelete: List<Item> = emptyList()
    private val videos = VideoAdapter()
    private val albums = AlbumAdapter()
    private val playlists = PlaylistAdapter()
    private val audio = AudioAdapter()
    private val continuing = ContinueAdapter()

    init {
        root.addView(column, FrameLayout.LayoutParams(-1, -1))
        column.addView(bar.root, LinearLayout.LayoutParams(-1, -2))
        column.addView(searchRow, LinearLayout.LayoutParams(-1, -2))
        column.addView(kit.hscroll(tabs.root), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(2) })
        column.addView(status, LinearLayout.LayoutParams(-1, -2))
        column.addView(
            kit.listHeader("Album", null).apply {
                visibility = View.GONE
                id = View.generateViewId()
            },
            LinearLayout.LayoutParams(-1, -2)
        )
        column.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(videoGrid, FrameLayout.LayoutParams(-1, -1))
        content.addView(albumGrid, FrameLayout.LayoutParams(-1, -1))
        content.addView(playlistList, FrameLayout.LayoutParams(-1, -1))
        content.addView(audioList, FrameLayout.LayoutParams(-1, -1))
        content.addView(continueList, FrameLayout.LayoutParams(-1, -1))
        videoGrid.adapter = videos
        albumGrid.adapter = albums
        playlistList.adapter = playlists
        audioList.adapter = audio
        continueList.adapter = continuing
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = OeaInsets.bars(insets)
            column.setPadding(bars[0], 0, bars[2], bars[3])
            insets
        }
        with(kit) {
            bar.addAction(R.drawable.oic_search, "Search") { toggleSearch() }
            bar.addAction(R.drawable.oic_play_arrow, "Play this list in order") { playAll() }
            bar.addAction(R.drawable.oic_refresh, "Scan again") { OeaMediaLibrary.refresh(host, setOf(Kind.VIDEO, Kind.AUDIO)) }
            bar.addAction(R.drawable.oic_more_vert, "More VIP options") { showMore() }
        }
        tabs.onPick = { id -> pickTab(id) }
        tabs.setOptions(TABS, tab)
        showSurface()
    }

    fun attach() {
        subscription = OeaMediaLibrary.observe(
            host,
            setOf(Kind.VIDEO, Kind.AUDIO),
            onProgress = { _, label -> showProgress(label) }
        ) { built ->
            snapshot = built
            sort = OeaMediaLibrary.sortFor(host, Kind.VIDEO)
            showProgress(null)
            rebuild()
        }
        host.miniResultHook = { requestCode, resultCode, data -> handleResult(requestCode, resultCode, data) }
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
            snack.show(if (items.size == 1) "Clip deleted" else "${items.size} clips deleted", OeaKit.Tone.SUCCESS)
            OeaThumbnails.invalidateAll()
        }
        exitSelection()
        return true
    }

    /** "Open with → VIP" from Files or Messages starts the player, not the library. */
    private fun openIncoming() {
        val intent = host.intent ?: return
        val uri = intent.data ?: return
        if (intent.action != Intent.ACTION_VIEW && intent.action != Intent.ACTION_GET_CONTENT) return
        if (intent.getBooleanExtra(EXTRA_HANDLED, false)) return
        intent.putExtra(EXTRA_HANDLED, true)
        val audioFile = (intent.type ?: "").startsWith("audio/")
        val known = (if (audioFile) snapshot?.audio else snapshot?.videos)?.firstOrNull { it.uri == uri }
        val item = known ?: Item(
            id = 0L,
            uri = uri,
            name = uri.lastPathSegment?.substringAfterLast('/') ?: "Media",
            takenAt = System.currentTimeMillis(),
            size = 0L,
            width = 0,
            height = 0,
            bucketId = 0L,
            album = "Opened",
            relativePath = "",
            durationMs = 0L,
            kind = if (audioFile) Kind.AUDIO else Kind.VIDEO,
            systemFavorite = false
        )
        play(listOf(item), 0)
    }

    // -------------------------------------------------------------------- views

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
        playlist = null
        surface = when (id) {
            "albums" -> Surface.ALBUMS
            "playlists" -> Surface.PLAYLISTS
            "audio" -> Surface.AUDIO
            "continue" -> Surface.CONTINUE
            else -> Surface.VIDEOS
        }
        exitSelection()
        showSurface()
        rebuild()
    }

    private fun showSurface() {
        videoGrid.visibility = if (surface == Surface.VIDEOS) View.VISIBLE else View.GONE
        albumGrid.visibility = if (surface == Surface.ALBUMS) View.VISIBLE else View.GONE
        playlistList.visibility = if (surface == Surface.PLAYLISTS) View.VISIBLE else View.GONE
        audioList.visibility = if (surface == Surface.AUDIO) View.VISIBLE else View.GONE
        continueList.visibility = if (surface == Surface.CONTINUE) View.VISIBLE else View.GONE
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
            videos.notifyDataSetChanged()
            return
        }
        hideState()
        when (surface) {
            Surface.VIDEOS -> {
                val list = currentList()
                rows.clear()
                rows.addAll(list)
                videos.notifyDataSetChanged()
                bar.title.text = if (album != null) album!! else "Videos"
                bar.setSubtitle(
                    when {
                        query.isNotBlank() -> "${list.size} matching"
                        album != null -> "$album • ${list.size} • ${OeaMediaLibrary.formatSize(list.sumOf { it.size })}"
                        else -> "${list.size} clips • ${built.videoAlbums.size} albums • ${OeaMediaLibrary.formatSize(built.videos.sumOf { it.size })}"
                    }
                )
                if (list.isEmpty()) emptyVideos(built)
            }

            Surface.ALBUMS -> {
                albums.notifyDataSetChanged()
                bar.title.text = "Albums"
                bar.setSubtitle("${built.videoAlbums.size} folders • ${built.videos.size} clips")
                if (built.videoAlbums.isEmpty()) {
                    showState(R.drawable.oic_folder, "No video folders yet", "A screen recording, a download or a clip from another app appears here on its own. Nothing is uploaded to make that happen.", "Scan again") {
                        OeaMediaLibrary.refresh(host, setOf(Kind.VIDEO))
                    }
                }
            }

            Surface.PLAYLISTS -> {
                playlists.notifyDataSetChanged()
                bar.title.text = "Playlists"
                bar.setSubtitle("${OeaVipStore.playlists(host).size} built by you")
                if (OeaVipStore.playlists(host).isEmpty()) {
                    showState(R.drawable.oic_layers, "No playlists yet", "Long-press a clip and choose Add to playlist, or make one here and fill it while you watch.", "New playlist") { createPlaylist() }
                }
            }

            Surface.AUDIO -> {
                audio.notifyDataSetChanged()
                bar.title.text = "Audio"
                bar.setSubtitle("${built.audio.size} tracks • ${OeaMediaLibrary.formatSize(built.audio.sumOf { it.size })}")
                if (built.audio.isEmpty()) {
                    showState(R.drawable.oic_music_note, "No music or recordings", "VIP plays audio with the same controls: seek, speed, background play, sleep timer.", "Scan again") {
                        OeaMediaLibrary.refresh(host, setOf(Kind.AUDIO))
                    }
                }
            }

            Surface.CONTINUE -> {
                continuing.notifyDataSetChanged()
                bar.title.text = "Continue watching"
                val list = OeaVipStore.continueWatching(host, built.videos)
                bar.setSubtitle("${list.size} clips part-way through")
                if (list.isEmpty()) {
                    showState(R.drawable.oic_history, "Nothing part-way through", "Stop a video anywhere past the start and it waits for you here, at the second you left it.")
                }
            }
        }
        if (selecting) updateSelectionBar()
    }

    private fun emptyVideos(built: OeaMediaLibrary.Snapshot) {
        if (built.permissionMissing) {
            showState(R.drawable.ic_oea_video, "VIP cannot see your videos yet", "Allow video access and every folder on this phone shows up — Camera, Screen recordings, Downloads and the folders other apps write into.", "Allow access") {
                askPermission()
            }
        } else {
            showState(R.drawable.ic_oea_video, "No video in this view", "Nothing matches the current filter. Try another tab, or clear the search.", "Show all videos") {
                tab = "all"
                album = null
                query = ""
                searchBox.setText("")
                tabs.select("all")
                rebuild()
            }
        }
    }

    private fun currentList(): List<Item> {
        val built = snapshot ?: return emptyList()
        if (playlist != null) return OeaVipStore.itemsOf(host, playlist!!, built.videos)
        var list = built.videos
        if (album != null) list = built.inAlbum(album!!, Kind.VIDEO)
        list = when (tab) {
            "favourites" -> OeaMediaLibrary.favouriteItems(built, host, Kind.VIDEO)
            "continue" -> {
                val keys = OeaVipStore.continueWatching(host, built.videos).map { it.first.key }.toSet()
                list.filter { keys.contains(it.key) }
            }

            "downloads" -> list.filter { it.album.contains("download", true) || it.relativePath.contains("Download", true) }
            "recordings" -> list.filter {
                it.album.contains("screen", true) || it.name.contains("screen", true) ||
                    it.album.contains("recording", true) || it.relativePath.contains("Movies", true) && it.name.contains("record", true)
            }

            "large" -> list.filter { it.size >= 100L shl 20 }
            else -> list
        }
        if (query.isNotBlank()) {
            val lower = query.lowercase(Locale.getDefault())
            list = list.filter {
                it.name.lowercase(Locale.getDefault()).contains(lower) ||
                    it.album.lowercase(Locale.getDefault()).contains(lower) ||
                    it.relativePath.lowercase(Locale.getDefault()).contains(lower)
            }
        }
        return OeaMediaLibrary.order(list, sort)
    }

    private fun askPermission() {
        val video = if (Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_VIDEO else android.Manifest.permission.READ_EXTERNAL_STORAGE
        val audio = if (Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO else android.Manifest.permission.READ_EXTERNAL_STORAGE
        runCatching { host.requestPermissions(arrayOf(video, audio, android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 2202) }
            .onFailure { snack.show("Android would not open the permission dialog", OeaKit.Tone.WARNING) }
    }

    private fun showState(icon: Int, title: String, body: String, action: String? = null, onAction: (() -> Unit)? = null) {
        stateCard?.let { content.removeView(it) }
        val card = kit.emptyState(icon, title, body, action, onAction)
        val wrap = FrameLayout(host).apply {
            setPadding(kit.dp(16), kit.dp(24), kit.dp(16), 0)
            addView(card, FrameLayout.LayoutParams(-1, -2))
        }
        stateCard = wrap
        content.addView(wrap, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        showSurface()
        videoGrid.visibility = View.GONE
        albumGrid.visibility = View.GONE
        playlistList.visibility = View.GONE
        audioList.visibility = View.GONE
        continueList.visibility = View.GONE
    }

    private fun hideState() {
        stateCard?.let { content.removeView(it) }
        stateCard = null
    }

    // ------------------------------------------------------------ playback & ops

    /** Starts the player with [list] as the queue, at [index]. */
    private fun play(list: List<Item>, index: Int) {
        if (list.isEmpty()) return
        val at = index.coerceIn(0, list.lastIndex)
        val item = list[at]
        val audioOnly = item.kind == Kind.AUDIO
        OeaVipStore.rememberPosition(host, item.uri, OeaVipStore.position(host, item.uri), item.durationMs)
        runCatching {
            host.startActivity(
                Intent(host, OeaVipPlayerActivity::class.java).apply {
                    data = item.uri
                    putExtra(OeaVipPlayerActivity.EXTRA_URI, item.uri.toString())
                    putExtra(OeaVipPlayerActivity.EXTRA_TITLE, item.name)
                    putExtra(OeaVipPlayerActivity.EXTRA_SUBTITLE, item.album)
                    putExtra(OeaVipPlayerActivity.EXTRA_AUDIO, audioOnly)
                    putExtra(OeaVipPlayerActivity.EXTRA_START_AT, OeaVipStore.position(host, item.uri))
                    putExtra(OeaVipPlayerActivity.EXTRA_DURATION, item.durationMs)
                    putExtra(OeaVipPlayerActivity.EXTRA_QUEUE, ArrayList(list.map { it.uri.toString() }))
                    putExtra(OeaVipPlayerActivity.EXTRA_QUEUE_TITLES, ArrayList(list.map { it.name }))
                    putExtra(OeaVipPlayerActivity.EXTRA_INDEX, at)
                    OeaMediaProbe.siblingFile(item, OeaSubtitles.SUBTITLE_EXTENSIONS)?.let {
                        putExtra(OeaVipPlayerActivity.EXTRA_SIDECAR, it.path)
                    }
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
        }.onFailure { snack.show("The player could not start on this file", OeaKit.Tone.DANGER) }
        exitSelection()
    }

    private fun playAll() {
        val list = currentList()
        if (list.isEmpty()) {
            snack.show("Nothing in this list to play")
            return
        }
        play(list, 0)
    }

    private fun itemActions(item: Item) {
        val built = snapshot ?: return
        val isFavourite = OeaMediaLibrary.isFavourite(host, item)
        val resume = OeaVipStore.position(host, item.uri)
        kit.actions(
            item.name,
            "${item.album} • ${OeaMediaLibrary.formatDuration(item.durationMs)} • ${OeaMediaLibrary.formatSize(item.size)}",
            listOf(
                OeaAppKit.Action(R.drawable.oic_play_arrow, if (resume > 0L) "Resume at ${OeaMediaLibrary.formatDuration(resume)}" else "Play",
                    "Opens full-screen with this list as the queue") {
                    play(currentList().ifEmpty { listOf(item) }, currentList().indexOfFirst { it.key == item.key }.coerceAtLeast(0))
                },
                OeaAppKit.Action(R.drawable.oic_star_fill, if (isFavourite) "Remove from favourites" else "Add to favourites") {
                    OeaMediaLibrary.toggleFavourite(host, item)
                    rebuild()
                },
                OeaAppKit.Action(R.drawable.oic_playlist, "Add to a playlist",
                    if (OeaVipStore.playlists(host).isEmpty()) "Make your first playlist" else "${OeaVipStore.playlists(host).size} to choose from") {
                    addToPlaylist(item)
                },
                OeaAppKit.Action(R.drawable.oic_info, "Codec and file details", "Read from the file, not guessed") {
                    OeaVipDetails.sheet(host, palette, item)
                },
                OeaAppKit.Action(R.drawable.oic_share_filled, "Share") {
                    OeaMediaLibrary.share(host, listOf(item))
                },
                OeaAppKit.Action(R.drawable.oic_open_in_new, "Open with another app") {
                    OeaMediaLibrary.openExternally(host, item)
                },
                OeaAppKit.Action(R.drawable.oic_delete, "Delete", "Android asks you to confirm it", destructive = true) {
                    kit.confirm("Delete this file?", "${item.name} • ${OeaMediaLibrary.formatSize(item.size)}. Android confirms, because another app may own it.", "Delete", destructive = true) {
                        performDelete(listOf(item))
                    }
                }
            )
        )
    }

    private fun addToPlaylist(item: Item) {
        val existing = OeaVipStore.playlists(host)
        if (existing.isEmpty()) {
            createPlaylist(item)
            return
        }
        kit.sheet("Add ${item.name.substringAfterLast('/')} to a playlist", null) { panel, dialog ->
            val group = kit.group(panel)
            existing.forEach { playlistData ->
                kit.row(group, R.drawable.oic_layers, playlistData.name, "${playlistData.size} clips",
                    value = if (playlistData.uris.contains(item.key)) "already in" else null, chevron = false) {
                    dialog.dismiss()
                    if (OeaVipStore.addToPlaylist(host, playlistData.name, item.uri)) {
                        snack.show("Added to ${playlistData.name}", OeaKit.Tone.SUCCESS)
                    } else {
                        snack.show("It is already in ${playlistData.name}")
                    }
                    rebuild()
                }
            }
            panel.addView(
                kit.primaryButton("New playlist", R.drawable.oic_add) {
                    dialog.dismiss()
                    createPlaylist(item)
                },
                LinearLayout.LayoutParams(-1, kit.dp(46)).apply { topMargin = kit.dp(10) }
            )
        }
    }

    private fun createPlaylist(forItem: Item? = null) {
        kit.prompt("New playlist", "A name you will recognise", "Playlist name") { name ->
            if (name.isBlank()) return@prompt "Give it a name"
            if (!OeaVipStore.createPlaylist(host, name)) return@prompt "That name is taken"
            if (forItem != null) OeaVipStore.addToPlaylist(host, name.trim(), forItem.uri)
            snack.show("Playlist created", OeaKit.Tone.SUCCESS)
            surface = Surface.PLAYLISTS
            tab = "playlists"
            tabs.select("playlists")
            showSurface()
            rebuild()
            null
        }
    }

    private fun playlistActions(data: OeaVipStore.Playlist) {
        kit.actions(data.name, "${data.size} clips", listOf(
            OeaAppKit.Action(R.drawable.oic_play_arrow, "Play the playlist") {
                val built = snapshot ?: return@Action
                val list = OeaVipStore.itemsOf(host, data.name, built.videos)
                if (list.isEmpty()) snack.show("The clips in it are gone") else play(list, 0)
            },
            OeaAppKit.Action(R.drawable.oic_view_list, "Open in VIP", "Shows these clips in the grid") {
                playlist = data.name
                surface = Surface.VIDEOS
                showSurface()
                rebuild()
            },
            OeaAppKit.Action(R.drawable.oic_edit, "Rename") {
                kit.prompt("Rename the playlist", data.name, "Name") { name ->
                    if (name.isBlank()) return@prompt "Give it a name"
                    OeaVipStore.renamePlaylist(host, data.name, name)
                    rebuild()
                    null
                }
            },
            OeaAppKit.Action(R.drawable.oic_delete, "Delete the playlist", "The clips themselves stay on the phone", destructive = true) {
                kit.confirm("Delete ${data.name}?", "The playlist goes away; your files do not.", "Delete", destructive = true) {
                    OeaVipStore.deletePlaylist(host, data.name)
                    rebuild()
                }
            }
        ))
    }

    private fun performDelete(items: List<Item>) {
        val sender = OeaMediaLibrary.requestDelete(host, items)
        if (sender != null) {
            pendingDelete = items
            val started = runCatching {
                @Suppress("DEPRECATION")
                host.startIntentSenderForResult(sender, REQUEST_DELETE, null, 0, 0, 0)
            }.isSuccess
            if (!started) {
                pendingDelete = emptyList()
                snack.show("Android would not show its confirm dialog", OeaKit.Tone.WARNING)
            }
            return
        }
        val removed = OeaMediaLibrary.deleteOwnedNow(host, items)
        snack.show(if (removed > 0) "$removed clip(s) removed" else "Android would not let VIP delete these",
            if (removed > 0) OeaKit.Tone.SUCCESS else OeaKit.Tone.DANGER)
        exitSelection()
        OeaMediaLibrary.refresh(host)
    }

    // -------------------------------------------------------------- interactions

    private fun beginSelection(item: Item) {
        selecting = true
        selected.add(item.key)
        videos.notifyDataSetChanged()
        updateSelectionBar()
    }

    private fun toggleSelected(item: Item) {
        if (!selected.remove(item.key)) selected.add(item.key)
        videos.notifyDataSetChanged()
        updateSelectionBar()
    }

    private fun exitSelection() {
        selecting = false
        selected.clear()
        selection.setCount(0, 0)
        videos.notifyDataSetChanged()
    }

    private val selection: OeaAppKit.SelectionBar by lazy {
        kit.selectionBar(root, listOf(0 to "Play next", 0 to "Favourite", 0 to "Playlist", 0 to "Share", 0 to "Delete")) { exitSelection() }
    }

    private fun selectedItems(): List<Item> = rows.filter { selected.contains(it.key) }

    private fun updateSelectionBar() {
        selection.setCount(selected.size, rows.size)
        selection.setAction(0, R.drawable.oic_play_arrow, "Queue") {
            val items = selectedItems()
            if (items.isNotEmpty()) play(items, 0)
        }
        selection.setAction(1, R.drawable.oic_star_fill, "Star") {
            selectedItems().forEach { OeaMediaLibrary.toggleFavourite(host, it) }
            snack.show("Favourites updated", OeaKit.Tone.SUCCESS)
            exitSelection()
            rebuild()
        }
        selection.setAction(2, R.drawable.oic_layers, "Playlist") {
            val items = selectedItems()
            if (items.isEmpty()) return@setAction
            if (items.size > 1) {
                snack.show("Pick one clip to add to a playlist")
            } else {
                addToPlaylist(items.first())
            }
            exitSelection()
        }
        selection.setAction(3, R.drawable.oic_share_filled, "Share") {
            OeaMediaLibrary.share(host, selectedItems(), "Share clips")
            exitSelection()
        }
        selection.setAction(4, R.drawable.oic_delete, "Delete") {
            val items = selectedItems()
            if (items.isEmpty()) return@setAction
            kit.confirm(if (items.size == 1) "Delete this clip?" else "Delete ${items.size} clips?",
                "Android confirms every delete, because the file may belong to another app.", "Delete", destructive = true) {
                performDelete(items)
            }
        }
        selection.selectAll {
            selected.clear()
            rows.forEach { selected.add(it.key) }
            videos.notifyDataSetChanged()
            selection.setCount(selected.size, rows.size)
        }
    }

    private fun showMore() {
        val built = snapshot
        kit.sheet("VIP", "How the player behaves") { panel, _ ->
            val group = kit.group(panel)
            val sortNames = OeaMediaLibrary.Sort.values().map { it.label() }
            kit.row(group, R.drawable.oic_sort_by_alpha, "Order", sort.label(), chevron = true) {
                kit.choose("Order the library by", null, sortNames, OeaMediaLibrary.Sort.values().indexOf(sort)) { index ->
                    sort = OeaMediaLibrary.Sort.values()[index]
                    OeaMediaLibrary.setSort(host, Kind.VIDEO, sort)
                    rebuild()
                }
            }
            kit.row(group, R.drawable.oic_visibility_off, "Clips in this view", "${rows.size} shown of ${built?.videos?.size ?: 0}", chevron = false)
            kit.switchRow(group, R.drawable.oic_play_arrow, "Play the next clip", "When one finishes, VIP starts the next in the queue",
                OeaVipStore.autoNext(host)) { value ->
                OeaVipStore.setAutoNext(host, value)
            }
            kit.switchRow(group, R.drawable.oic_music_note, "Audio keeps playing", "Music and recordings continue when you leave the player",
                OeaVipStore.backgroundAudio(host)) { value ->
                OeaVipStore.setBackgroundAudio(host, value)
            }
            kit.row(group, R.drawable.oic_timer, "Sleep timer", "Set it inside the player, in its more sheet", chevron = false)
            kit.row(group, R.drawable.oic_refresh, "Scan again", "Asks Android to index OEA's own recordings too") {
                OeaMediaLibrary.refresh(host, setOf(Kind.VIDEO, Kind.AUDIO))
            }
            kit.note(panel, "VIP plays with Android's own media stack, so what a phone can decode is what VIP can play; the details sheet reads the file's track table instead of guessing.")
        }
    }

    private fun OeaMediaLibrary.Sort.label(): String = when (this) {
        OeaMediaLibrary.Sort.NEWEST -> "Newest first"
        OeaMediaLibrary.Sort.OLDEST -> "Oldest first"
        OeaMediaLibrary.Sort.NAME -> "Name (A–Z)"
        OeaMediaLibrary.Sort.SIZE -> "Largest file"
        OeaMediaLibrary.Sort.DURATION -> "Longest clip"
    }

    // ----------------------------------------------------------------- adapters

    private inner class VideoAdapter : RecyclerView.Adapter<VideoAdapter.Holder>() {
        inner class Holder(val cover: OeaAppKit.CoverTile) : RecyclerView.ViewHolder(cover.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(kit.coverTile(kit.dp(102), kit.dp(12)))

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = rows.getOrNull(position) ?: return
            val cover = holder.cover
            OeaThumbnails.load(cover.image, item.uri, kit.dp(260), item.kind == Kind.VIDEO, kit.dp(12), position)
            cover.showBadge(if (item.durationMs > 0L) OeaMediaLibrary.formatDuration(item.durationMs) else if (item.kind == Kind.AUDIO) "audio" else "—")
            cover.showStar(OeaMediaLibrary.isFavourite(host, item))
            val at = OeaVipStore.position(host, item.uri)
            cover.showProgress(if (item.durationMs > 0L && at > 0L) at.toFloat() / item.durationMs else 0f)
            cover.root.setOnClickListener {
                if (selecting) toggleSelected(item) else play(rows, position)
            }
            cover.root.setOnLongClickListener {
                if (!selecting) beginSelection(item) else toggleSelected(item)
                true
            }
            cover.markSelected(selecting && selected.contains(item.key))
        }

        override fun onViewRecycled(holder: Holder) {
            OeaThumbnails.cancel(holder.cover.image)
        }
    }

    private inner class AlbumAdapter : RecyclerView.Adapter<AlbumAdapter.Holder>() {
        inner class Holder(val card: OeaAppKit.AlbumCard) : RecyclerView.ViewHolder(card.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(kit.albumCard(kit.dp(108)))

        override fun getItemCount(): Int = snapshot?.videoAlbums?.size ?: 0

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val data = snapshot?.videoAlbums?.getOrNull(position) ?: return
            holder.card.name.text = data.name
            holder.card.meta.text = "${data.count} clips • ${OeaMediaLibrary.formatSize(data.size)}"
            holder.card.badge.text = SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(data.lastTakenAt))
            data.cover?.let { OeaThumbnails.load(holder.card.cover, it.uri, kit.dp(220), true, 0, position) }
            holder.card.root.setOnClickListener {
                album = data.name
                surface = Surface.VIDEOS
                showSurface()
                rebuild()
            }
            holder.card.root.setOnLongClickListener {
                kit.actions(data.name, "${data.count} clips in ${data.path.ifBlank { "this folder" }}", listOf(
                    OeaAppKit.Action(R.drawable.oic_play_arrow, "Play the album") {
                        val list = snapshot?.inAlbum(data.name, Kind.VIDEO) ?: return@Action
                        if (list.isNotEmpty()) play(list, 0)
                    },
                    OeaAppKit.Action(R.drawable.oic_view_list, "Open in VIP") {
                        album = data.name
                        surface = Surface.VIDEOS
                        showSurface()
                        rebuild()
                    }
                ))
                true
            }
        }
    }

    private inner class PlaylistAdapter : RecyclerView.Adapter<PlaylistAdapter.Holder>() {
        inner class Holder(val row: LinearLayout, val title: TextView, val meta: TextView, val play: View) :
            RecyclerView.ViewHolder(row)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val row = LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(kit.dp(14), kit.dp(12), kit.dp(12), kit.dp(12))
                background = kit.rounded(palette.card, 20, palette.stroke, 1)
                isClickable = true
                isLongClickable = true
            }
            val badge = kit.iconBadge(R.drawable.oic_layers, palette.accentSoft, palette.onAccentSoft, 42)
            row.addView(badge, LinearLayout.LayoutParams(kit.dp(42), kit.dp(42)).apply { marginEnd = kit.dp(13) })
            val texts = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
            val title = kit.text("", 15.5f, palette.text, OeaType.medium).apply { maxLines = 1 }
            val meta = kit.text("", 12f, palette.textSecondary).apply { maxLines = 1; setPadding(0, kit.dp(2), 0, 0) }
            texts.addView(title)
            texts.addView(meta)
            row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            val play = kit.circleIconButton(R.drawable.oic_play_arrow, "Play the playlist", palette.accentSoft, palette.onAccentSoft) {}
            row.addView(play, LinearLayout.LayoutParams(kit.dp(40), kit.dp(40)))
            return Holder(row, title, meta, play)
        }

        override fun getItemCount(): Int = OeaVipStore.playlists(host).size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val data = OeaVipStore.playlists(host).getOrNull(position) ?: return
            val built = snapshot
            val items = OeaVipStore.itemsOf(host, data.name, built?.videos ?: emptyList())
            holder.title.text = data.name
            holder.meta.text = if (items.isEmpty()) "${data.size} clips • none still on the phone"
            else "${items.size} clips • ${OeaMediaLibrary.formatSize(items.sumOf { it.size })}"
            holder.row.setOnClickListener { playlistActions(data) }
            holder.play.setOnClickListener {
                if (items.isEmpty()) snack.show("The clips in it are gone") else play(items, 0)
            }
        }
    }

    private inner class AudioAdapter : RecyclerView.Adapter<AudioAdapter.Holder>() {
        inner class Holder(val cover: ImageView, val title: TextView, val meta: TextView, val duration: TextView, val row: LinearLayout) :
            RecyclerView.ViewHolder(row)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val row = LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(kit.dp(12), kit.dp(9), kit.dp(12), kit.dp(9))
                background = kit.rounded(palette.card, 18, palette.stroke, 1)
                isClickable = true
            }
            val cover = ImageView(host).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(palette.cardHigh)
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, kit.dpf(10f))
                    }
                }
            }
            row.addView(cover, LinearLayout.LayoutParams(kit.dp(50), kit.dp(50)).apply { marginEnd = kit.dp(12) })
            val texts = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
            val title = kit.text("", 15f, palette.text, OeaType.medium).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
            val meta = kit.text("", 12f, palette.textSecondary).apply { maxLines = 1; setPadding(0, kit.dp(2), 0, 0) }
            texts.addView(title)
            texts.addView(meta)
            row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            val duration = kit.text("", 12f, palette.accent, OeaType.medium).apply { setPadding(kit.dp(8), 0, 0, 0) }
            row.addView(duration)
            return Holder(cover, title, meta, duration, row)
        }

        override fun getItemCount(): Int = snapshot?.audio?.size ?: 0

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val built = snapshot ?: return
            val list = if (query.isBlank()) built.audio else {
                val lower = query.lowercase(Locale.getDefault())
                built.audio.filter { it.name.lowercase(Locale.getDefault()).contains(lower) || it.album.lowercase(Locale.getDefault()).contains(lower) }
            }
            val item = list.getOrNull(position) ?: return
            holder.title.text = item.name.substringBeforeLast('.')
            holder.meta.text = "${item.album} • ${OeaMediaLibrary.formatSize(item.size)}"
            holder.duration.text = OeaMediaLibrary.formatDuration(item.durationMs)
            OeaThumbnails.load(holder.cover, item.uri, kit.dp(120), true, 0, position)
            holder.row.setOnClickListener { play(list, position) }
            holder.row.setOnLongClickListener {
                itemActions(item)
                true
            }
        }
    }

    private inner class ContinueAdapter : RecyclerView.Adapter<ContinueAdapter.Holder>() {
        inner class Holder(val card: OeaAppKit.AlbumCard) : RecyclerView.ViewHolder(card.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(kit.albumCard(kit.dp(84)))

        override fun getItemCount(): Int = snapshot?.let { OeaVipStore.continueWatching(host, it.videos).size } ?: 0

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val built = snapshot ?: return
            val (item, at) = OeaVipStore.continueWatching(host, built.videos).getOrNull(position) ?: return
            holder.card.name.text = item.name.substringBeforeLast('.')
            holder.card.meta.text = OeaVipStore.watchedLabel(at, item.durationMs)
            holder.card.badge.text = OeaMediaLibrary.formatDuration(item.durationMs - at)
            OeaThumbnails.load(holder.card.cover, item.uri, kit.dp(220), true, 0, position)
            holder.card.root.setOnClickListener {
                play(OeaVipStore.continueWatching(host, built.videos).map { it.first }, position)
            }
            holder.card.root.setOnLongClickListener {
                itemActions(item)
                true
            }
        }
    }

    companion object {
        private const val REQUEST_DELETE = 4320
        const val EXTRA_HANDLED = "oea.vip.view_handled"
        private val TABS = listOf(
            "all" to "All",
            "continue" to "Continue",
            "albums" to "Albums",
            "playlists" to "Playlists",
            "favourites" to "Favourites",
            "recordings" to "Recordings",
            "downloads" to "Downloads",
            "large" to "Large",
            "audio" to "Audio"
        )
    }
}
