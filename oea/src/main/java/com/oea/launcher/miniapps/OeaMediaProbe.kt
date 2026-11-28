package com.oea.launcher.miniapps

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The real metadata behind EMAGE's "About this picture" and VIP's codec sheet.
 *
 * Both used to print "codec placeholder" and "EXIF requires a reader" — a detail
 * sheet that apologises for not having data is not a feature. Everything here is
 * read from the file itself: [MediaExtractor] for container, tracks, bitrate and
 * frame rate, [ExifInterface] for camera settings and GPS coordinates.
 */
object OeaMediaProbe {

    data class Track(
        val index: Int,
        val kind: String,
        val mime: String,
        val label: String,
        val width: Int = 0,
        val height: Int = 0,
        val frameRate: Float = 0f,
        val channels: Int = 0,
        val sampleRate: Int = 0,
        val language: String = "",
        val bitrate: Long = 0L
    )

    data class Clip(
        val container: String,
        val durationMs: Long,
        val rotationDeg: Int,
        val bitrate: Long,
        val sizeBytes: Long,
        val tracks: List<Track>,
        val width: Int,
        val height: Int
    ) {
        val videoTracks: List<Track> get() = tracks.filter { it.kind == "video" }
        val audioTracks: List<Track> get() = tracks.filter { it.kind == "audio" }
        val textTracks: List<Track> get() = tracks.filter { it.kind == "text" }
        val resolution: String get() = if (width > 0 && height > 0) "${width}×${height}" else "—"
        fun frameRateLabel(): String = videoTracks.firstOrNull { it.frameRate > 0f }?.frameRate?.let { "%.2f fps".format(it) } ?: "—"
        fun audioLabel(): String = audioTracks.firstOrNull()?.let {
            "${it.channels}ch • ${it.sampleRate / 1000} kHz • ${it.mime.substringAfterLast('/')}"
        } ?: "—"
    }

    /** Everything a picture's EXIF block actually carries. */
    data class Photo(
        val make: String,
        val model: String,
        val lens: String,
        val software: String,
        val takenAt: Long,
        val exposureSeconds: String,
        val aperture: String,
        val iso: String,
        val focalLength: String,
        val flash: Boolean,
        val metering: String,
        val whiteBalance: String,
        val orientation: Int,
        val width: Int,
        val height: Int,
        val latitude: Double?,
        val longitude: Double?,
        val altitudeMeters: Double?
    ) {
        val hasCamera: Boolean get() = make.isNotBlank() || model.isNotBlank()
        val hasLocation: Boolean get() = latitude != null && longitude != null
        /** Copyable coordinates: always a dot, never the device locale's comma. */
        val locationLabel: String get() = if (latitude != null && longitude != null) {
            OeaEmageFacts.coordinates(latitude, longitude)
        } else {
            "Not tagged"
        }
        /** The one line the details sheet shows for camera settings. */
        val exposureLabel: String get() = OeaEmageFacts.exposureLine(exposureSeconds, aperture, iso)
        val takenLabel: String get() = if (takenAt > 0L)
            SimpleDateFormat("d MMMM yyyy • HH:mm:ss", Locale.getDefault()).format(Date(takenAt)) else "Not recorded"
    }

    /**
     * Reads container and track information. [MediaExtractor] is the only API that
     * reports the real codec of a local file, and it costs a few milliseconds
     * because it never decodes anything.
     */
    fun probe(context: Context, uri: Uri): Clip? = runCatching {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val tracks = ArrayList<Track>()
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                val kind = when {
                    mime.startsWith("video/") -> "video"
                    mime.startsWith("audio/") -> "audio"
                    mime.startsWith("text/") || mime.startsWith("application/x-subrip") -> "text"
                    else -> "data"
                }
                val width = if (format.containsKey(MediaFormat.KEY_WIDTH)) format.getInteger(MediaFormat.KEY_WIDTH) else 0
                val height = if (format.containsKey(MediaFormat.KEY_HEIGHT)) format.getInteger(MediaFormat.KEY_HEIGHT) else 0
                val frameRate = if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() else 0f
                val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 0
                val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 0
                val language = if (Build.VERSION.SDK_INT >= 21 && format.containsKey(MediaFormat.KEY_LANGUAGE)) {
                    format.getString(MediaFormat.KEY_LANGUAGE).orEmpty()
                } else ""
                val bitrate = if (format.containsKey(MediaFormat.KEY_BIT_RATE)) format.getInteger(MediaFormat.KEY_BIT_RATE).toLong() else 0L
                val label = when (kind) {
                    "video" -> "%s %s".format(mime.substringAfterLast('/').uppercase(Locale.getDefault()), if (width > 0 && height > 0) "$width×$height" else "")
                    "audio" -> "%s %s".format(
                        mime.substringAfterLast('/').uppercase(Locale.getDefault()),
                        listOfNotNull(
                            if (channels > 0) "${channels}ch" else null,
                            if (sampleRate > 0) "%d kHz".format(sampleRate / 1000) else null
                        ).joinToString(" ")
                    )
                    else -> mime.substringAfterLast('/').uppercase(Locale.getDefault())
                }
                tracks.add(
                    Track(
                        index = index,
                        kind = kind,
                        mime = mime,
                        label = label.trim(),
                        width = width,
                        height = height,
                        frameRate = frameRate,
                        channels = channels,
                        sampleRate = sampleRate,
                        language = language,
                        bitrate = bitrate
                    )
                )
            }
            // MediaExtractor has no duration of its own — the container stores it per
            // track, and the longest one is the clip.
            val durationUs = (0 until runCatching { extractor.trackCount }.getOrDefault(0)).mapNotNull { index ->
                runCatching {
                    val format = extractor.getTrackFormat(index)
                    if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else null
                }.getOrNull()
            }.maxOrNull() ?: 0L
            val duration = if (durationUs > 0L) durationUs / 1000L else 0L
            val video = tracks.firstOrNull { it.kind == "video" }
            val total = tracks.sumOf { it.bitrate }
            val retriever = MediaMetadataRetriever()
            var rotation = 0
            try {
                retriever.setDataSource(context, uri)
                rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            } catch (_: Throwable) {
            } finally {
                runCatching { if (Build.VERSION.SDK_INT >= 29) retriever.close() else retriever.release() }
            }
            Clip(
                container = tracks.firstOrNull()?.mime?.substringAfterLast('/')?.uppercase(Locale.getDefault()) ?: "MEDIA",
                durationMs = duration,
                rotationDeg = rotation,
                bitrate = total,
                sizeBytes = sizeOf(context, uri),
                tracks = tracks,
                width = video?.width ?: 0,
                height = video?.height ?: 0
            )
        } finally {
            runCatching { extractor.release() }
        }
    }.getOrNull()

    /** Camera settings and coordinates from a picture's EXIF block. */
    fun photo(context: Context, uri: Uri): Photo? = runCatching {
        val stream = context.contentResolver.openInputStream(uri) ?: return@runCatching null
        val exif = stream.use { ExifInterface(it) }
        val latitude = exif.coordinate(ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF)
        val longitude = exif.coordinate(ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF)
        val altitudeMeters = exif.coordinate(ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF)
        val exposure = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME).orEmpty()
        val fNumber = exif.getAttribute(ExifInterface.TAG_F_NUMBER).orEmpty()
        Photo(
            make = exif.getAttribute(ExifInterface.TAG_MAKE).orEmpty().trim(),
            model = exif.getAttribute(ExifInterface.TAG_MODEL).orEmpty().trim(),
            lens = exif.getAttribute(ExifInterface.TAG_LENS_MODEL).orEmpty().trim(),
            software = exif.getAttribute(ExifInterface.TAG_SOFTWARE).orEmpty().trim(),
            takenAt = runCatching {
                exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.let { raw ->
                    SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(raw)?.time
                }
            }.getOrNull() ?: 0L,
            exposureSeconds = OeaEmageFacts.shutter(exposure),
            aperture = OeaEmageFacts.aperture(fNumber),
            // Many phones write only the modern sensitivity tag, so the old sheet said
            // "—" for the ISO of a picture taken on the phone showing it.
            iso = OeaEmageFacts.iso(
                exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS).orEmpty()
                    // Read by its EXIF name: the constant's spelling differs between the
                    // framework reader and the AndroidX copy, the tag name does not.
                    .ifBlank { exif.getAttribute("PhotographicSensitivity").orEmpty() }
            ),
            focalLength = OeaEmageFacts.focalLength(
                exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM).orEmpty(),
                exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH).orEmpty()
            ),
            flash = (exif.getAttributeInt(ExifInterface.TAG_FLASH, 0) and 1) == 1,
            metering = OeaEmageFacts.metering(exif.getAttributeInt(ExifInterface.TAG_METERING_MODE, -1)),
            whiteBalance = OeaEmageFacts.whiteBalance(exif.getAttributeInt(ExifInterface.TAG_WHITE_BALANCE, -1)),
            orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL),
            width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0),
            height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0),
            latitude = latitude,
            longitude = longitude,
            altitudeMeters = altitudeMeters
        )
    }.getOrNull()

    /**
     * An Exif GPS value is a handful of rationals plus a reference letter, and the
     * convenience getters differ between the framework and the AndroidX copy of
     * ExifInterface, so the details sheet reads the pair it is given.
     */
    private fun ExifInterface.coordinate(valueTag: String, refTag: String): Double? {
        val raw = runCatching { getAttribute(valueTag) }.getOrNull() ?: return null
        val parts = raw.split(',').mapNotNull { entry ->
            val ratio = entry.trim().split('/')
            runCatching {
                if (ratio.size == 2) ratio[0].trim().toDouble() / ratio[1].trim().toDouble()
                else ratio[0].trim().toDouble()
            }.getOrNull()
        }
        if (parts.isEmpty()) return null
        var degrees = parts.getOrElse(0) { 0.0 } +
            parts.getOrElse(1) { 0.0 } / 60.0 +
            parts.getOrElse(2) { 0.0 } / 3600.0
        when (runCatching { getAttribute(refTag) }.getOrNull()?.trim()?.uppercase(Locale.getDefault())) {
            "S", "W", "1" -> degrees = -degrees
        }
        return degrees
    }

    private fun sizeOf(context: Context, uri: Uri): Long = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { stat -> if (stat.statSize > 0) stat.statSize else 0L } ?: 0L
    }.getOrDefault(0L)

    /**
     * The sidecar file beside a clip (the usual way subtitles travel with a
     * download). Only OEA-owned files can be listed this way: scoped storage does
     * not let an app read arbitrary neighbours of a MediaStore row, which is why
     * VIP also offers "Load subtitles…" through the system document picker.
     */
    fun siblingFile(item: OeaMediaLibrary.Item, extensions: List<String>): File? {
        if (!item.local) return null
        val path = item.localPath ?: return null
        val parent = File(path).parentFile ?: return null
        val base = item.name.substringBeforeLast('.')
        return extensions
            .flatMap { candidate -> listOfNotNull(File(parent, "$base.$candidate").takeIf { it.isFile }) }
            .firstOrNull()
    }

    /** Where subtitle files travel besides the clip's own folder. */
    private val SUBTITLE_FOLDERS = listOf("Subs", "Subtitles", "Captions", "subs", "subtitles", "captions")

    /** A ceiling on one folder walk: a card with thousands of files is not a library. */
    private const val SUBTITLE_SCAN_LIMIT = 500

    /**
     * Every subtitle file that belongs to [item], in every language beside it.
     *
     * [siblingFile] answers "is there one"; this answers "which ones, and in what
     * language" — the difference between a film with the German track under it
     * and a film with English, French and Yoruba to choose from. Same folder and
     * the usual one-folder-down (`Subs/`, `Subtitles/`, `Captions/`), matched by
     * name with [OeaSubtitleFiles] so a neighbouring film's subtitles cannot
     * attach themselves to this one.
     *
     * Only OEA-owned files can be listed this way: scoped storage does not let an
     * app read arbitrary neighbours of a MediaStore row, which is why VIP also
     * offers "Pick a subtitle file" through the system document picker.
     */
    fun subtitleFiles(item: OeaMediaLibrary.Item, preferred: OeaSubtitleLanguages.Language? = null): List<File> =
        if (!item.local) emptyList() else subtitleFilesFor(item.localPath?.let(::File), preferred)

    /** The same list as [subtitleFiles], with the language each name announces. */
    fun subtitleCandidates(
        item: OeaMediaLibrary.Item,
        preferred: OeaSubtitleLanguages.Language? = null
    ): List<OeaSubtitleFiles.Candidate> =
        if (!item.local) emptyList() else subtitleCandidatesFor(item.localPath?.let(::File), preferred)

    /**
     * The same walk from a plain [clip] — the player's form, because the activity
     * is handed absolute paths rather than library rows, and because walking a
     * folder belongs on the thread that reads the cues, not on the tap that
     * started playback.
     */
    fun subtitleFilesFor(clip: File?, preferred: OeaSubtitleLanguages.Language? = null): List<File> {
        if (clip == null) return emptyList()
        val folder = subtitleFolder(clip)
        if (folder.isEmpty()) return emptyList()
        return OeaSubtitleFiles.sorted(
            OeaSubtitleFiles.candidates(clip.name, folder.values.map { it.name }),
            preferred
        ).mapNotNull { candidate -> folder[candidate.name.lowercase(Locale.ROOT)] }
    }

    fun subtitleCandidatesFor(
        clip: File?,
        preferred: OeaSubtitleLanguages.Language? = null
    ): List<OeaSubtitleFiles.Candidate> {
        if (clip == null) return emptyList()
        val folder = subtitleFolder(clip)
        if (folder.isEmpty()) return emptyList()
        return OeaSubtitleFiles.sorted(
            OeaSubtitleFiles.candidates(clip.name, folder.values.map { it.name }),
            preferred
        )
    }

    /**
     * The subtitle files around a clip, keyed by lower-case name. The clip's own
     * folder wins over `Subs/` when both hold a file with the same name.
     */
    private fun subtitleFolder(clip: File): Map<String, File> {
        val parent = clip.parentFile ?: return emptyMap()
        val found = LinkedHashMap<String, File>()
        fun take(directory: File) {
            val files = runCatching { directory.listFiles() }.getOrNull() ?: return
            files.forEach { file ->
                if (found.size >= SUBTITLE_SCAN_LIMIT) return
                if (!file.isFile || !OeaSubtitleFiles.isSubtitle(file.name)) return@forEach
                found.putIfAbsent(file.name.lowercase(Locale.ROOT), file)
            }
        }
        take(parent)
        SUBTITLE_FOLDERS.forEach { name ->
            File(parent, name).takeIf { it.isDirectory }?.let { take(it) }
        }
        return found
    }
}
