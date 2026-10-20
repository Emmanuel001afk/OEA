package com.oea.launcher.miniapps

import android.content.ContentUris
import android.content.Context
import android.content.ContentValues
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The media index behind EMAGE and VIP.
 *
 * A gallery is only as good as its query, and the old one was the whole problem:
 * it asked MediaStore for "the newest 300 rows", derived its albums from those 300
 * rows and stopped. Real phones hold far more than 300 pictures, so the albums
 * were wrong, the counts were wrong, and anything older than a few weeks simply
 * did not exist — "doesn't capture all my images" was literally true.
 *
 * This reads the **whole** collection once, off the main thread, and hands both
 * apps one immutable [Snapshot]:
 *  * every picture, video and audio track the device exposes, including other
 *    apps' folders (WhatsApp, Telegram, Screenshots, Downloads, DCIM/Camera);
 *  * exact album counts, sizes and cover picks from a single pass over the rows;
 *  * day buckets, so a grid can carry real section headers;
 *  * OEA's own camera output, folded in as first-class items and indexed with
 *    [scanOwnDirectories] the moment it is written;
 *  * a live rescan through a [android.database.ContentObserver], so a picture you
 *    took in another app appears in the grid while you are looking at it.
 */
object OeaMediaLibrary {

    enum class Kind { IMAGE, VIDEO, AUDIO }

    /**
     * The access Android actually granted for one media collection. Android 14+
     * can report a non-empty library while exposing only the pictures selected
     * in the permission picker; treating that as FULL makes a gallery silently
     * look incomplete.
     */
    enum class Access { FULL, PARTIAL, DENIED }

    /** One MediaStore row (or one OEA-owned file), normalised for the UI. */
    data class Item(
        val id: Long,
        val uri: Uri,
        val name: String,
        val takenAt: Long,
        val size: Long,
        val width: Int,
        val height: Int,
        val bucketId: Long,
        val album: String,
        val relativePath: String,
        val durationMs: Long,
        val kind: Kind,
        val systemFavorite: Boolean,
        val local: Boolean = false,
        /** Real filesystem path for OEA-owned files; a FileProvider URI is not a path. */
        val localPath: String? = null
    ) {
        val key: String get() = uri.toString()
        val aspect: Float get() = if (width > 0 && height > 0) width.toFloat() / height.toFloat() else 4f / 3f
        val hasRealResolution: Boolean get() = width > 0 && height > 0
    }

    /** An album as the user should see it: a cover, a real count, a real size. */
    data class Album(
        val name: String,
        val path: String,
        val cover: Item?,
        val count: Int,
        val size: Long,
        val kind: Kind,
        val lastTakenAt: Long
    )

    data class Snapshot(
        val images: List<Item> = emptyList(),
        val videos: List<Item> = emptyList(),
        val audio: List<Item> = emptyList(),
        val imageAlbums: List<Album> = emptyList(),
        val videoAlbums: List<Album> = emptyList(),
        val audioAlbums: List<Album> = emptyList(),
        val takenAt: Long = 0L,
        val permissionMissing: Boolean = false,
        val imageAccess: Access = Access.DENIED,
        val videoAccess: Access = Access.DENIED,
        val audioAccess: Access = Access.DENIED,
        val error: String? = null,
        /** Collections represented by this snapshot; prevents a cached image scan from masking a VIP scan. */
        val scannedKinds: Set<Kind> = emptySet()
    ) {
        val totalSize: Long get() = (images + videos + audio).sumOf { it.size }
        val totalCount: Int get() = images.size + videos.size + audio.size
        val empty: Boolean get() = images.isEmpty() && videos.isEmpty() && audio.isEmpty()

        fun pool(kind: Kind): List<Item> = when (kind) {
            Kind.IMAGE -> images
            Kind.VIDEO -> videos
            Kind.AUDIO -> audio
        }

        fun albums(kind: Kind): List<Album> = when (kind) {
            Kind.IMAGE -> imageAlbums
            Kind.VIDEO -> videoAlbums
            Kind.AUDIO -> audioAlbums
        }

        fun inAlbum(album: String, kind: Kind): List<Item> =
            pool(kind).filter { it.album.equals(album, ignoreCase = true) }
    }

    enum class Sort { NEWEST, OLDEST, NAME, SIZE, DURATION }

    /** A deletion OEA remembers; recoverable when the bytes are OEA's own. */
    data class TrashRecord(
        val key: String,
        val name: String,
        val album: String,
        val deletedAt: Long,
        val path: String?,
        val kind: Kind = Kind.IMAGE
    ) {
        val recoverable: Boolean get() = !path.isNullOrBlank() && File(path).isFile
    }

    /** A day of pictures, for the header a grid scrolls under. */
    data class Day(val label: String, val dayStart: Long, val items: List<Item>)

    private const val PREFS = "oea_media_index"
    private const val KEY_FAV = "favourites"
    private const val KEY_HIDDEN = "hidden_albums"
    private const val KEY_TRASH = "trash_records"
    private const val COLUMN_FAVOURITE = "is_favorite"
    private const val TRASH_DIR = ".oea-trash"

    private fun sortKey(kind: Kind) = "sort_${kind.name.lowercase(Locale.US)}"

    private val io = Executors.newSingleThreadExecutor { task ->
        Thread(task, "OeaMediaIndex").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    private val scanning = AtomicBoolean(false)
    private val pendingRescan = Runnable { rescanNow() }

    private val subscribers = CopyOnWriteArrayList<Subscription>()
    private val observers = mutableMapOf<Uri, android.database.ContentObserver>()

    @Volatile
    private var snapshot: Snapshot? = null

    private var appContext: Context? = null
    @Volatile
    private var kindsWanted = setOf<Kind>()
    /** A VIP subscription can arrive while an image scan is still running. */
    private val rescanAgain = AtomicBoolean(false)

    private val favourites = LinkedHashSet<String>()
    private var favouritesLoaded = false
    private val favouritesLock = Any()
    private val hidden = LinkedHashSet<String>()
    private var hiddenLoaded = false
    private val hiddenLock = Any()
    private val trash = LinkedHashMap<String, TrashRecord>()
    private var trashLoaded = false
    private val trashLock = Any()

    /** A screen's link to the index. Dispose it when the screen leaves the window. */
    class Subscription(val onSnapshot: (Snapshot) -> Unit, val onProgress: ((Int, String) -> Unit)?) {
        fun dispose() {
            subscribers.remove(this)
            if (subscribers.isEmpty()) stopWatching()
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // -------------------------------------------------------------- subscriptions

    /**
     * Delivers the current snapshot at once when one exists, then every refresh.
     * Scans are shared: five screens asking for one cost a single scan.
     */
    fun observe(
        context: Context,
        kinds: Set<Kind>,
        onProgress: ((Int, String) -> Unit)? = null,
        onSnapshot: (Snapshot) -> Unit
    ): Subscription {
        val app = context.applicationContext
        appContext = app
        kindsWanted = kindsWanted + kinds
        val subscription = Subscription(onSnapshot, onProgress)
        subscribers.add(subscription)
        watch(app, kinds)
        val cached = snapshot
        if (cached != null) {
            main.post {
                if (subscribers.contains(subscription)) {
                    runCatching { onSnapshot(cached) }
                        .onFailure { android.util.Log.w("OeaMediaLibrary", "Media subscriber failed", it) }
                }
            }
            // A cached image-only snapshot is not a valid answer for VIP. Keep the
            // fast first paint, then fill the newly requested collections.
            if (!cached.scannedKinds.containsAll(kinds)) requestScan(0L)
        } else {
            requestScan(0L)
        }
        return subscription
    }

    fun cached(): Snapshot? = snapshot
    fun isScanning(): Boolean = scanning.get()

    /** Rescans after a permission grant, a capture, or an explicit refresh. */
    fun refresh(context: Context, kinds: Set<Kind>? = null) {
        appContext = context.applicationContext
        if (kinds != null) kindsWanted = kindsWanted + kinds
        requestScan(0L)
    }

    private fun requestScan(debounceMs: Long) {
        main.removeCallbacks(pendingRescan)
        if (debounceMs <= 0L) pendingRescan.run() else main.postDelayed(pendingRescan, debounceMs)
    }

    private fun rescanNow() {
        val context = appContext ?: return
        if (!scanning.compareAndSet(false, true)) {
            // Do not lose a request for a collection that was added while the
            // single worker was busy. The next completion will start one scan.
            rescanAgain.set(true)
            return
        }
        val kinds = kindsWanted.ifEmpty { setOf(Kind.IMAGE) }
        progress(8, "Reading your library…")
        io.execute {
            val result = runCatching { build(context, kinds) }
            scanning.set(false)
            val built = result.getOrElse { error ->
                android.util.Log.w("OeaMediaLibrary", "MediaStore scan failed", error)
                val message = error.message ?: error.javaClass.simpleName
                snapshot?.copy(takenAt = System.currentTimeMillis(), error = message) ?: Snapshot(
                    takenAt = System.currentTimeMillis(),
                    imageAccess = accessFor(context, Kind.IMAGE),
                    videoAccess = accessFor(context, Kind.VIDEO),
                    audioAccess = accessFor(context, Kind.AUDIO),
                    error = message
                )
            }
            snapshot = built
            main.post {
                subscribers.toList().forEach { subscriber ->
                    if (!subscribers.contains(subscriber)) return@forEach
                    runCatching { subscriber.onSnapshot(built) }
                        .onFailure { android.util.Log.w("OeaMediaLibrary", "Media subscriber failed", it) }
                }
                val rerun = rescanAgain.getAndSet(false)
                if (result.isSuccess && rerun) requestScan(0L)
            }
        }
    }

    private fun progress(percent: Int, label: String) {
        main.post {
            subscribers.toList().forEach { subscriber ->
                if (!subscribers.contains(subscriber)) return@forEach
                runCatching { subscriber.onProgress?.invoke(percent, label) }
                    .onFailure { android.util.Log.w("OeaMediaLibrary", "Media progress subscriber failed", it) }
            }
        }
    }

    private fun watch(context: Context, kinds: Set<Kind>) {
        val resolver = context.contentResolver
        kinds.map { collectionFor(it) }.forEach { target ->
            if (observers.containsKey(target)) return@forEach
            val observer = object : android.database.ContentObserver(main) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    // One camera save fires several changes; coalesce them.
                    requestScan(650L)
                }
            }
            runCatching {
                resolver.registerContentObserver(target, true, observer)
                observers[target] = observer
            }
        }
    }

    private fun stopWatching() {
        val context = appContext ?: return
        val resolver = context.contentResolver
        observers.values.toList().forEach { observer -> runCatching { resolver.unregisterContentObserver(observer) } }
        observers.clear()
    }

    private fun collectionFor(kind: Kind): Uri = when (kind) {
        Kind.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        Kind.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        Kind.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    }

    // -------------------------------------------------------------------- queries

    private fun build(context: Context, kinds: Set<Kind>): Snapshot {
        val imagesRaw = if (kinds.contains(Kind.IMAGE)) query(context, Kind.IMAGE) else emptyList()
        val videosRaw = if (kinds.contains(Kind.VIDEO)) query(context, Kind.VIDEO) else emptyList()
        val audioRaw = if (kinds.contains(Kind.AUDIO)) query(context, Kind.AUDIO) else emptyList()
        val own = ownFiles(context, kinds)
        val hiddenSet = hiddenAlbums(context)
        val gone = trashedKeys(context)
        val visible = { list: List<Item> ->
            list.filterNot { hiddenSet.contains(it.album.lowercase(Locale.getDefault())) || gone.contains(it.key) }
        }
        val ownImages = own.filter { it.kind == Kind.IMAGE }
        val ownVideos = own.filter { it.kind == Kind.VIDEO }
        val images = order(visible(mergeOwn(ownImages, imagesRaw)), sortFor(context, Kind.IMAGE))
        val videos = order(visible(mergeOwn(ownVideos, videosRaw)), sortFor(context, Kind.VIDEO))
        val audio = order(visible(audioRaw), sortFor(context, Kind.AUDIO))
        val imageAccess = accessFor(context, Kind.IMAGE)
        val videoAccess = accessFor(context, Kind.VIDEO)
        val audioAccess = accessFor(context, Kind.AUDIO)
        progress(70, "Grouping albums…")
        return Snapshot(
            images = images,
            videos = videos,
            audio = audio,
            imageAlbums = albums(images, Kind.IMAGE),
            videoAlbums = albums(videos, Kind.VIDEO),
            audioAlbums = albums(audio, Kind.AUDIO),
            takenAt = System.currentTimeMillis(),
            permissionMissing = missing(kinds, Kind.IMAGE, imageAccess, images) ||
                missing(kinds, Kind.VIDEO, videoAccess, videos) ||
                missing(kinds, Kind.AUDIO, audioAccess, audio),
            imageAccess = imageAccess,
            videoAccess = videoAccess,
            audioAccess = audioAccess,
            scannedKinds = kinds.toSet()
        )
    }

    /**
     * Android 14's selected-photos grant is deliberately checked separately
     * from READ_MEDIA_IMAGES/VIDEO. A gallery must not label a partial library
     * "empty" or imply that the user has granted full access.
     */
    fun accessFor(context: Context, kind: Kind): Access {
        fun granted(permission: String): Boolean =
            androidx.core.content.ContextCompat.checkSelfPermission(context, permission) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= 34) {
            val fullPermission = when (kind) {
                Kind.IMAGE -> android.Manifest.permission.READ_MEDIA_IMAGES
                Kind.VIDEO -> android.Manifest.permission.READ_MEDIA_VIDEO
                Kind.AUDIO -> android.Manifest.permission.READ_MEDIA_AUDIO
            }
            if (granted(fullPermission)) return Access.FULL
            // READ_MEDIA_VISUAL_USER_SELECTED applies to images and videos, not audio.
            if (kind != Kind.AUDIO && granted(android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)) {
                return Access.PARTIAL
            }
            return Access.DENIED
        }
        if (Build.VERSION.SDK_INT >= 33) {
            val permission = when (kind) {
                Kind.IMAGE -> android.Manifest.permission.READ_MEDIA_IMAGES
                Kind.VIDEO -> android.Manifest.permission.READ_MEDIA_VIDEO
                Kind.AUDIO -> android.Manifest.permission.READ_MEDIA_AUDIO
            }
            return if (granted(permission)) Access.FULL else Access.DENIED
        }
        return if (granted(android.Manifest.permission.READ_EXTERNAL_STORAGE)) Access.FULL else Access.DENIED
    }

    private fun missing(kinds: Set<Kind>, kind: Kind, access: Access, items: List<Item>): Boolean =
        kinds.contains(kind) && access == Access.DENIED && items.isEmpty()

    /**
     * The old boolean is kept in the snapshot for callers that only need the
     * blocking state. Partial access is not missing: it is usable, but the UI
     * should say that Android is showing only the selected media.
     */

    /**
     * Reads a whole collection: no LIMIT, no offset, no truncation. The grid that
     * consumes it is virtualised, so 40 000 rows cost one array of small records
     * rather than 40 000 views — and the album list can finally be exact.
     */
    private fun query(context: Context, kind: Kind): List<Item> {
        // A denied collection is expected on a first run (and for audio when
        // EMAGE is the only screen open). Do not turn that normal state into a
        // whole-library error by asking a provider we cannot read.
        if (accessFor(context, kind) == Access.DENIED) return emptyList()
        val collection = collectionFor(kind)
        var lastFailure: Throwable? = null
        projections(kind).forEach { projection ->
            try {
                return read(context, collection, projection, kind)
            } catch (error: Throwable) {
                lastFailure = error
            }
        }
        throw lastFailure ?: IllegalStateException("Media provider could not be queried")
    }

    /**
     * The widest projection this Android accepts, then the narrower ones. A gallery
     * showing nothing because a column arrived in API 29 is the worst possible
     * failure, so the query degrades — buckets, then size, then id and name.
     */
    private fun projections(kind: Kind): List<Array<String>> {
        val base = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.SIZE
        )
        if (kind != Kind.IMAGE) {
            base.add(if (kind == Kind.AUDIO) MediaStore.Audio.Media.DURATION else MediaStore.Video.Media.DURATION)
        }
        val withBucket = base.toMutableList().apply {
            if (Build.VERSION.SDK_INT >= 28) {
                add(MediaStore.MediaColumns.BUCKET_ID)
                add(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            }
            if (kind == Kind.AUDIO) add(MediaStore.Audio.Media.ALBUM)
            if (Build.VERSION.SDK_INT < 29) add(MediaStore.MediaColumns.DATA)
            if (Build.VERSION.SDK_INT >= 29) {
                add(MediaStore.MediaColumns.IS_PENDING)
                if (Build.VERSION.SDK_INT >= 30) add(MediaStore.MediaColumns.IS_TRASHED)
            }
        }
        val rich = withBucket.toMutableList().apply {
            if (Build.VERSION.SDK_INT >= 29) {
                add(MediaStore.MediaColumns.WIDTH)
                add(MediaStore.MediaColumns.HEIGHT)
                add(MediaStore.MediaColumns.RELATIVE_PATH)
                add(COLUMN_FAVOURITE)
            }
        }
        return listOf(rich.toTypedArray(), withBucket.toTypedArray(), base.toTypedArray())
    }

    private fun read(context: Context, collection: Uri, projection: Array<String>, kind: Kind): List<Item> {
        val rows = ArrayList<Item>(4096)
        val selection = when {
            Build.VERSION.SDK_INT >= 30 -> "${MediaStore.MediaColumns.IS_PENDING} = 0 AND ${MediaStore.MediaColumns.IS_TRASHED} = 0"
            Build.VERSION.SDK_INT >= 29 -> "${MediaStore.MediaColumns.IS_PENDING} = 0"
            else -> null
        }
        val resolver = context.contentResolver
        val cursor = runCatching {
            resolver.query(
                collection,
                projection,
                selection,
                null,
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
            ) ?: error("Media provider returned no cursor")
        }.getOrElse {
            // A few OEM providers expose the rows but reject IS_TRASHED or the
            // sort expression. Retry without optional filtering; malformed and
            // pending rows are still rejected when their columns are available.
            resolver.query(collection, projection, null, null, null)
                ?: throw IllegalStateException("Media provider returned no cursor", it)
        }
        cursor.use { c ->
            fun col(name: String) = c.getColumnIndex(name)
            val idCol = col(MediaStore.MediaColumns._ID)
            if (idCol < 0) error("Media provider omitted the media id")
            val nameCol = col(MediaStore.MediaColumns.DISPLAY_NAME)
            val addedCol = col(MediaStore.MediaColumns.DATE_ADDED)
            val modifiedCol = col(MediaStore.MediaColumns.DATE_MODIFIED)
            val sizeCol = col(MediaStore.MediaColumns.SIZE)
            val bucketCol = col(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            val bucketIdCol = col(MediaStore.MediaColumns.BUCKET_ID)
            val widthCol = col(MediaStore.MediaColumns.WIDTH)
            val heightCol = col(MediaStore.MediaColumns.HEIGHT)
            val pathCol = col(MediaStore.MediaColumns.RELATIVE_PATH)
            val pendingCol = col(MediaStore.MediaColumns.IS_PENDING)
            val trashedCol = col(MediaStore.MediaColumns.IS_TRASHED)
            val favCol = col(COLUMN_FAVOURITE)
            val dataCol = col(MediaStore.MediaColumns.DATA)
            val durationCol = if (kind == Kind.IMAGE) -1
            else col(if (kind == Kind.AUDIO) MediaStore.Audio.Media.DURATION else MediaStore.Video.Media.DURATION)
            val albumCol = if (kind == Kind.AUDIO) col(MediaStore.Audio.Media.ALBUM) else -1
            while (c.moveToNext()) {
                val pending = pendingCol >= 0 && runCatching { c.getInt(pendingCol) != 0 }.getOrDefault(false)
                val trashed = trashedCol >= 0 && runCatching { c.getInt(trashedCol) != 0 }.getOrDefault(false)
                if (pending || trashed) continue
                runCatching {
                    val id = c.getLong(idCol)
                    val added = if (addedCol >= 0) c.getLong(addedCol) else 0L
                    val modified = if (modifiedCol >= 0) c.getLong(modifiedCol) else 0L
                    val taken = if (modified > 0L) modified * 1000L else added * 1000L
                    val data = if (dataCol >= 0) c.getString(dataCol).orEmpty() else ""
                    val albumFromColumn = if (albumCol >= 0) c.getString(albumCol).orEmpty() else ""
                    val bucketFromColumn = if (bucketCol >= 0) c.getString(bucketCol).orEmpty() else ""
                    val album = albumFromColumn.ifBlank { bucketFromColumn }
                        .ifBlank { File(data).parentFile?.name.orEmpty() }
                    val relative = if (pathCol >= 0) c.getString(pathCol).orEmpty()
                    else if (data.isNotBlank()) File(data).parent.orEmpty() else ""
                    rows.add(
                        Item(
                            id = id,
                            uri = ContentUris.withAppendedId(collection, id),
                            name = if (nameCol >= 0) c.getString(nameCol).orEmpty().ifBlank { "Media" } else "Media",
                            takenAt = taken,
                            size = if (sizeCol >= 0) c.getLong(sizeCol) else 0L,
                            width = if (widthCol >= 0) c.getInt(widthCol) else 0,
                            height = if (heightCol >= 0) c.getInt(heightCol) else 0,
                            bucketId = if (bucketIdCol >= 0) c.getLong(bucketIdCol) else 0L,
                            album = album.ifBlank { if (kind == Kind.AUDIO) "Music" else "Other" },
                            relativePath = relative,
                            durationMs = if (durationCol >= 0) c.getLong(durationCol) else 0L,
                            kind = kind,
                            systemFavorite = favCol >= 0 && c.getInt(favCol) != 0
                        )
                    )
                }.onFailure {
                    // One malformed provider row must not erase every other
                    // picture from a device's library.
                    android.util.Log.d("OeaMediaLibrary", "Skipping malformed media row", it)
                }
            }
        }
        return rows
    }

    /**
     * OEA Camera writes one private copy (for editing/trash) and one MediaStore
     * copy (for other galleries). EMAGE should show that as one picture, with the
     * private item retained as the editable/trash-capable representative.
     */
    private fun mergeOwn(privateItems: List<Item>, indexed: List<Item>): List<Item> {
        if (privateItems.isEmpty()) return indexed
        val privateNames = privateItems.map { it.name.lowercase(Locale.US) }.toSet()
        return privateItems + indexed.filterNot { item ->
            item.name.lowercase(Locale.US) in privateNames && (
                item.relativePath.lowercase(Locale.US).contains("pictures/oea") ||
                    item.relativePath.lowercase(Locale.US).contains("movies/oea")
                )
        }
    }

    /** OEA's own camera roll, so a shot taken in OEA is never "missing". */
    private fun ownFiles(context: Context, kinds: Set<Kind>): List<Item> {
        val out = ArrayList<Item>()
        if (kinds.contains(Kind.IMAGE)) {
            out += collect(File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES), "OEA"), Kind.IMAGE, context, "Pictures/OEA", PICTURE_EXTENSIONS)
        }
        if (kinds.contains(Kind.VIDEO)) {
            out += collect(File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES), "OEA"), Kind.VIDEO, context, "Movies/OEA", VIDEO_EXTENSIONS)
        }
        return out
    }

    private val PICTURE_EXTENSIONS = listOf("jpg", "jpeg", "png", "webp", "heic", "gif", "dng")
    private val VIDEO_EXTENSIONS = listOf("mp4", "mkv", "webm", "mov", "3gp", "avi")

    private fun collect(dir: File, kind: Kind, context: Context, relative: String, extensions: List<String>): List<Item> {
        if (!dir.isDirectory) return emptyList()
        val files = runCatching { dir.listFiles() }.getOrNull()?.filter {
            it.isFile && it.extension.lowercase(Locale.getDefault()) in extensions
        } ?: return emptyList()
        return files.mapNotNull { file ->
            val uri = runCatching {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            }.getOrNull() ?: return@mapNotNull null
            Item(
                id = -(file.absolutePath.hashCode().toLong() and 0x7FFFFFFFL),
                uri = uri,
                name = file.name,
                takenAt = file.lastModified(),
                size = file.length(),
                width = 0,
                height = 0,
                bucketId = 0L,
                album = "OEA",
                relativePath = relative,
                durationMs = if (kind == Kind.VIDEO) probeDuration(file) else 0L,
                kind = kind,
                systemFavorite = false,
                local = true,
                localPath = file.absolutePath
            )
        }.sortedByDescending { it.takenAt }
    }

    private fun probeDuration(file: File): Long = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            runCatching { if (Build.VERSION.SDK_INT >= 29) retriever.close() else retriever.release() }
        }
    }.getOrDefault(0L)

    /** Tells Android about OEA's own output, so it reaches MediaStore at once. */
    fun scanOwnDirectories(context: Context) {
        val paths = ArrayList<String>()
        listOf(
            File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES), "OEA"),
            File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES), "OEA")
        ).forEach { dir ->
            runCatching { dir.listFiles() }.getOrNull()?.filter { it.isFile }?.forEach { paths.add(it.absolutePath) }
        }
        if (paths.isEmpty()) return
        runCatching {
            android.media.MediaScannerConnection.scanFile(context.applicationContext, paths.toTypedArray(), null, null)
        }
    }

    // --------------------------------------------------------------------- albums

    private fun albums(items: List<Item>, kind: Kind): List<Album> = items
        .groupBy { it.album.ifBlank { if (kind == Kind.AUDIO) "Music" else "Other" } }
        .map { (name, rows) ->
            Album(
                name = name,
                path = rows.firstOrNull { it.relativePath.isNotBlank() }?.relativePath.orEmpty(),
                cover = rows.maxByOrNull { it.takenAt } ?: rows.firstOrNull(),
                count = rows.size,
                size = rows.sumOf { it.size },
                kind = kind,
                lastTakenAt = rows.maxOfOrNull { it.takenAt } ?: 0L
            )
        }
        .sortedWith(compareByDescending<Album> { it.count }.thenByDescending { it.lastTakenAt })

    // -------------------------------------------------------------------- sorting

    fun sortFor(context: Context, kind: Kind): Sort = runCatching {
        Sort.valueOf(prefs(context).getString(sortKey(kind), Sort.NEWEST.name) ?: Sort.NEWEST.name)
    }.getOrDefault(Sort.NEWEST)

    fun setSort(context: Context, kind: Kind, sort: Sort) {
        prefs(context).edit().putString(sortKey(kind), sort.name).apply()
        val current = snapshot ?: return
        val rebuilt = current.copy(
            images = if (kind == Kind.IMAGE) order(current.images, sort) else current.images,
            videos = if (kind == Kind.VIDEO) order(current.videos, sort) else current.videos,
            audio = if (kind == Kind.AUDIO) order(current.audio, sort) else current.audio
        )
        snapshot = rebuilt
        main.post {
            subscribers.toList().forEach { subscriber ->
                if (!subscribers.contains(subscriber)) return@forEach
                runCatching { subscriber.onSnapshot(rebuilt) }
                    .onFailure { android.util.Log.w("OeaMediaLibrary", "Media sort subscriber failed", it) }
            }
        }
    }

    fun order(items: List<Item>, sort: Sort): List<Item> = when (sort) {
        Sort.NEWEST -> items.sortedWith(compareByDescending<Item> { it.takenAt }.thenByDescending { it.id })
        Sort.OLDEST -> items.sortedWith(compareBy<Item> { it.takenAt }.thenBy { it.id })
        Sort.NAME -> items.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        Sort.SIZE -> items.sortedByDescending { it.size }
        Sort.DURATION -> items.sortedByDescending { it.durationMs }
    }

    // ---------------------------------------------------------------- favourites

    private fun loadFavourites(context: Context) {
        synchronized(favouritesLock) {
            if (favouritesLoaded) return
            favouritesLoaded = true
            runCatching { (prefs(context).getStringSet(KEY_FAV, emptySet()) ?: emptySet()).forEach { favourites.add(it) } }
        }
    }

    fun favouriteKeys(context: Context): Set<String> {
        loadFavourites(context)
        return synchronized(favouritesLock) { favourites.toSet() }
    }

    fun isFavourite(context: Context, item: Item): Boolean {
        loadFavourites(context)
        return synchronized(favouritesLock) { favourites.contains(item.key) } || item.systemFavorite
    }

    /** @return the star's new state. */
    fun toggleFavourite(context: Context, item: Item): Boolean {
        loadFavourites(context)
        var on = false
        var values: Set<String> = emptySet()
        synchronized(favouritesLock) {
            on = !favourites.contains(item.key)
            if (on) favourites.add(item.key) else favourites.remove(item.key)
            values = favourites.toSet()
        }
        prefs(context).edit().putStringSet(KEY_FAV, values).apply()
        return on
    }

    fun favouriteItems(snapshot: Snapshot, context: Context, kind: Kind): List<Item> {
        val keys = favouriteKeys(context)
        return snapshot.pool(kind).filter { keys.contains(it.key) || it.systemFavorite }
    }

    // ------------------------------------------------------------ hidden albums

    private fun loadHidden(context: Context) {
        synchronized(hiddenLock) {
            if (hiddenLoaded) return
            hiddenLoaded = true
            runCatching { (prefs(context).getStringSet(KEY_HIDDEN, emptySet()) ?: emptySet()).forEach { hidden.add(it) } }
        }
    }

    fun hiddenAlbums(context: Context): Set<String> {
        loadHidden(context)
        return synchronized(hiddenLock) { hidden.toSet() }
    }

    fun setHiddenAlbums(context: Context, albums: Set<String>) {
        loadHidden(context)
        val values = albums.map { it.lowercase(Locale.getDefault()) }.toSet()
        synchronized(hiddenLock) {
            hidden.clear()
            hidden.addAll(values)
        }
        prefs(context).edit().putStringSet(KEY_HIDDEN, values).apply()
        requestScan(0L)
    }

    // ---------------------------------------------------------------------- trash

    private fun loadTrash(context: Context) {
        synchronized(trashLock) {
            if (trashLoaded) return
            trashLoaded = true
            val raw = runCatching { prefs(context).getStringSet(KEY_TRASH, emptySet()) ?: emptySet() }.getOrDefault(emptySet())
            raw.forEach { entry ->
                val parts = entry.split('\u001F')
                if (parts.size >= 4) {
                    trash[parts[0]] = TrashRecord(
                        key = parts[0],
                        name = parts[1],
                        album = parts[2],
                        deletedAt = parts[3].toLongOrNull() ?: 0L,
                        path = parts.getOrNull(4)?.takeIf { it.isNotBlank() && it != "~" },
                        kind = runCatching { Kind.valueOf(parts.getOrNull(5).orEmpty()) }.getOrDefault(Kind.IMAGE)
                    )
                }
            }
        }
    }

    private fun storeTrashLocked(context: Context) {
        prefs(context).edit().putStringSet(KEY_TRASH, trash.values.map { record ->
            listOf(record.key, record.name, record.album, record.deletedAt.toString(), record.path ?: "~", record.kind.name)
                .joinToString("\u001F")
        }.toSet()).apply()
    }

    private fun storeTrash(context: Context) {
        synchronized(trashLock) { storeTrashLocked(context) }
    }

    fun trashRecords(context: Context): List<TrashRecord> {
        loadTrash(context)
        return synchronized(trashLock) { trash.values.sortedByDescending { it.deletedAt } }
    }

    fun trashedKeys(context: Context): Set<String> {
        loadTrash(context)
        return synchronized(trashLock) { trash.keys.toSet() }
    }

    /**
     * Remembers a deletion. Files OEA owns are really moved into OEA's trash
     * folder, so they can come back; device media goes through Android's own
     * delete confirmation and is kept as history, which is all Android allows.
     */
    fun recordDeletion(context: Context, item: Item) {
        loadTrash(context)
        val moved = if (item.local) moveToOeaTrash(context, item) else null
        synchronized(trashLock) {
            trash[item.key] = TrashRecord(item.key, item.name, item.album, System.currentTimeMillis(), moved, item.kind)
            storeTrashLocked(context)
        }
        com.oea.launcher.ui.OeaThumbnails.invalidate(item.uri)
    }

    private fun ownRoot(context: Context, kind: Kind): File = File(
        context.getExternalFilesDir(if (kind == Kind.VIDEO) android.os.Environment.DIRECTORY_MOVIES else android.os.Environment.DIRECTORY_PICTURES),
        "OEA"
    )

    private fun moveToOeaTrash(context: Context, item: Item): String? {
        val source = item.localPath?.let(::File)?.takeIf { it.isFile } ?: return null
        val dir = File(ownRoot(context, item.kind), TRASH_DIR)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val target = uniqueFile(dir, source.name)
        return if (runCatching { source.renameTo(target) }.getOrDefault(false)) target.absolutePath else null
    }

    private fun uniqueFile(dir: File, name: String): File {
        val first = File(dir, name)
        if (!first.exists()) return first
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        var index = 2
        var candidate: File
        do {
            candidate = File(dir, "${stem}_$index$extension")
            index++
        } while (candidate.exists() && index < 10_000)
        return candidate
    }

    /** Puts an OEA-owned media file back into the appropriate private collection. */
    fun restore(context: Context, record: TrashRecord): Boolean {
        val source = record.path?.let { File(it) }?.takeIf { it.isFile } ?: return false
        val dir = ownRoot(context, record.kind)
        if (!dir.isDirectory) runCatching { dir.mkdirs() }
        val target = uniqueFile(dir, record.name)
        val moved = runCatching { source.renameTo(target) }.getOrDefault(false)
        if (moved) {
            removeTrashRecord(context, record.key)
            refresh(context)
        }
        return moved
    }

    fun removeTrashRecord(context: Context, key: String) {
        loadTrash(context)
        val path = synchronized(trashLock) { trash.remove(key)?.path }
        path?.let { runCatching { File(it).takeIf { file -> file.isFile }?.delete() } }
        synchronized(trashLock) { storeTrashLocked(context) }
    }

    fun emptyTrash(context: Context): Int {
        loadTrash(context)
        val records = synchronized(trashLock) { trash.values.toList() }
        var deleted = 0
        records.forEach { record ->
            val path = record.path
            if (path != null && runCatching { File(path).delete() }.getOrDefault(false)) deleted++
        }
        synchronized(trashLock) {
            records.forEach { trash.remove(it.key) }
            storeTrashLocked(context)
        }
        return deleted
    }

    // --------------------------------------------------------------- date groups

    /** Groups a list into Today, Yesterday, weekday, day-of-month or month buckets. */
    fun days(items: List<Item>): List<Day> {
        if (items.isEmpty()) return emptyList()
        val calendar = Calendar.getInstance()
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val dayStarts = LinkedHashMap<Long, MutableList<Item>>()
        items.forEach { item ->
            calendar.timeInMillis = item.takenAt
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            dayStarts.getOrPut(calendar.timeInMillis) { ArrayList() }.add(item)
        }
        val dayFormatter = SimpleDateFormat("d MMMM yyyy", Locale.getDefault())
        val monthFormatter = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
        val weekdayFormatter = SimpleDateFormat("EEEE", Locale.getDefault())
        return dayStarts.entries.sortedByDescending { it.key }.map { (start, rows) ->
            val age = today.timeInMillis - start
            val label = when {
                age == 0L -> "Today"
                age == 86_400_000L -> "Yesterday"
                age in 1L..5L * 86_400_000L -> weekdayFormatter.format(Date(rows.first().takenAt))
                age < 31L * 86_400_000L -> dayFormatter.format(Date(rows.first().takenAt))
                else -> monthFormatter.format(Date(rows.first().takenAt))
            }
            Day(label, start, rows)
        }
    }

    // ------------------------------------------------------------- human strings

    fun formatSize(bytes: Long): String {
        if (bytes <= 0L) return "—"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.0f KB".format(kb)
        val mb = kb / 1024
        if (mb < 1024) return "%.1f MB".format(mb)
        return "%.2f GB".format(mb / 1024)
    }

    fun formatDuration(ms: Long): String {
        if (ms <= 0L) return "—"
        val total = ms / 1000L
        val h = total / 3600L
        val m = (total % 3600L) / 60L
        val s = total % 60L
        return if (h > 0L) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    fun formatDate(millis: Long): String =
        if (millis <= 0L) "Date not recorded" else SimpleDateFormat("d MMM yyyy • HH:mm", Locale.getDefault()).format(Date(millis))

    /** "3 photos • 1.2 MB" style summary for a header line. */
    fun summary(count: Int, bytes: Long): String =
        "%s • %s".format(if (count == 1) "1 item" else "%d items".format(count), formatSize(bytes))

    // ----------------------------------------------------------------- operations

    fun mimeFor(item: Item): String = when (item.kind) {
        Kind.IMAGE -> "image/*"
        Kind.VIDEO -> "video/*"
        Kind.AUDIO -> "audio/*"
    }

    /**
     * Returns a URI that another package may read. OEA-owned rows normally
     * already contain a FileProvider URI, but re-creating it here also repairs
     * old indexes that were built before a provider was configured. A file URI
     * is never handed to another app on Android 7+.
     */
    fun externalUri(context: Context, item: Item): Uri? {
        val file = if (item.local) {
            item.localPath?.let(::File)?.takeIf { it.isFile }
        } else if (item.uri.scheme == "file") {
            item.uri.path?.let(::File)?.takeIf { it.isFile }
        } else {
            null
        }
        if (!item.local && item.uri.scheme != "file") return item.uri
        return file?.let {
            runCatching {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it)
            }.getOrNull()
        }
    }

    /** Shares through the system chooser, granting the receiver read access. */
    fun share(context: Context, items: List<Item>, label: String = "Share") {
        if (items.isEmpty()) return
        val uris = items.mapNotNull { externalUri(context, it) }
        if (uris.size != items.size) {
            toast(context, "That file is no longer available to share.")
            return
        }
        val commonType = items.firstOrNull()?.let { first ->
            if (items.all { it.kind == first.kind }) mimeFor(first) else "*/*"
        } ?: "*/*"
        val intent = if (uris.size == 1) {
            android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = commonType
                putExtra(android.content.Intent.EXTRA_STREAM, uris.first())
            }
        } else {
            android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).apply {
                type = commonType
                putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, ArrayList(uris))
            }
        }
        intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // EXTRA_STREAM is the historical contract; ClipData is the modern
        // grant carrier and is required by some Android 13+ share targets.
        intent.clipData = android.content.ClipData.newUri(context.contentResolver, items.first().name, uris.first()).apply {
            uris.drop(1).forEach { addItem(android.content.ClipData.Item(it)) }
        }
        runCatching { context.startActivity(android.content.Intent.createChooser(intent, label)) }
            .onFailure { toast(context, "Nothing on this phone can receive a share.") }
    }

    fun openExternally(context: Context, item: Item) {
        val uri = externalUri(context, item)
        if (uri == null) {
            toast(context, "That file is no longer available.")
            return
        }
        runCatching {
            context.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mimeFor(item))
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
        }.onFailure { toast(context, "No app can open ${item.name}.") }
    }

    fun toast(context: Context, message: String) {
        runCatching { android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show() }
    }

    /**
     * The delete flow Android actually allows.
     *
     * On 11+ an app may not quietly erase someone else's media: it must hand the
     * URIs to [MediaStore.createDeleteRequest] and let the system ask. That is why
     * EMAGE's delete looks like a system dialog. [pendingDelete] returns the
     * [android.content.IntentSender] for the caller to start, and the caller reports
     * back through [deleteFinished] so the record, the caches and the index all
     * agree on what actually went away.
     */
    fun requestDelete(context: Context, items: List<Item>): android.content.IntentSender? {
        val remote = items.filterNot { it.local }
        if (remote.isEmpty()) return null
        if (Build.VERSION.SDK_INT < 30) return null
        return runCatching {
            MediaStore.createDeleteRequest(context.contentResolver, remote.map { it.uri }).intentSender
        }.getOrNull()
    }

    /** Deletes what a permission-free path allows (OEA's own files) right away. */
    fun deleteOwnedNow(context: Context, items: List<Item>): Int {
        var deleted = 0
        items.filter { it.local }.forEach { item ->
            // Move first so OEA-owned pictures remain recoverable. The old order
            // deleted the FileProvider target and only then tried to move it to
            // trash, which made every local delete permanently destructive.
            recordDeletion(context, item)
            loadTrash(context)
            val record = synchronized(trashLock) { trash[item.key] }
            if (record?.recoverable == true) {
                deleted++
            } else {
                val removed = runCatching { context.contentResolver.delete(item.uri, null, null) > 0 }.getOrDefault(false) ||
                    runCatching { item.localPath?.let(::File)?.takeIf { it.isFile }?.delete() == true }.getOrDefault(false)
                if (removed) {
                    synchronized(trashLock) {
                        trash[item.key] = TrashRecord(item.key, item.name, item.album, System.currentTimeMillis(), null, item.kind)
                        storeTrashLocked(context)
                    }
                    deleted++
                } else {
                    // Do not show a fake history entry when both moving and
                    // deleting the private file were refused by the filesystem.
                    removeTrashRecord(context, item.key)
                }
            }
        }
        if (deleted > 0) refresh(context)
        return deleted
    }

    /** Called with the list a delete request was made for, once it returns. */
    fun deleteFinished(context: Context, requested: List<Item>, granted: Boolean) {
        if (!granted) return
        requested.forEach { item ->
            recordDeletion(context, item)
        }
        refresh(context)
    }

    /** The readable path of an item, for the details sheet. */
    fun pathOf(item: Item): String {
        if (item.local) return item.localPath ?: item.name
        val relative = item.relativePath.trimEnd('/')
        return if (relative.isBlank()) item.name else "$relative/${item.name}"
    }

    /** Copies a picture OEA owns into the device's Pictures collection. */
    fun publishToDeviceLibrary(context: Context, item: Item): Boolean {
        if (!item.local) return false
        val source = item.localPath?.let(::File)?.takeIf { it.isFile } ?: return false
        val mime = android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(source.extension.lowercase(Locale.US)) ?: "image/jpeg"
        val values = android.content.ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/OEA")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            put(MediaStore.MediaColumns.DATE_ADDED, System.currentTimeMillis() / 1000L)
        }
        var target: Uri? = null
        val copied = runCatching {
            target = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@runCatching false
            context.contentResolver.openOutputStream(target!!)?.use { out ->
                source.inputStream().use { it.copyTo(out) }
            } ?: return@runCatching false
            if (Build.VERSION.SDK_INT >= 29) {
                context.contentResolver.update(target!!, android.content.ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }, null, null)
            }
            true
        }.getOrDefault(false)
        if (!copied) target?.let { runCatching { context.contentResolver.delete(it, null, null) } }
        if (copied) refresh(context)
        return copied
    }
}
