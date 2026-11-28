package com.oea.launcher.miniapps

import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

/**
 * The subtitle tracks muxed inside an MKV.
 *
 * Android's player will select a text track for you and then hand it over in
 * one format only, and never at all for the tracks most films actually ship. So
 * a downloaded season with `English [SDH]` written into the file was a dead end
 * in VIP: the film had subtitles, and the player said "no subtitle files found".
 *
 * This reads them out of the container itself. Matroska is a tree of
 * length-prefixed elements, so this walks the tree — TrackEntry for what each
 * track *is*, then every Cluster's blocks for what it *says* — and stops at the
 * first thing it does not understand rather than guessing. Text is kept for the
 * formats that are plain text: SubRip/UTF8, WebVTT and the dialogue field of
 * ASS/SSA (its styling is dropped, its words are not). Bitmap formats —
 * PGS, VobSub — are skipped, because turning a picture of a word into a word is
 * a different job entirely.
 *
 * The result is an ordinary [OeaSubtitles.Track], so an embedded language is
 * selectable, pairable and exportable exactly like a file beside the film.
 */
object OeaMatroska {

    /** One subtitle track found in the container, before it becomes a Track. */
    private class Entry(
        val number: Long,
        val codec: String,
        val language: String?,
        val name: String?,
        val forced: Boolean
    ) {
        val ass: Boolean get() = codec.contains("ASS", ignoreCase = true) || codec.contains("SSA", ignoreCase = true)

        fun text(payload: ByteArray): String {
            val raw = String(payload, Charsets.UTF_8)
            val body = if (ass) {
                // ReadOrder,Layer,Style,Name,MarginL,MarginR,MarginV,Effect,Text
                raw.split(",", limit = 9).getOrNull(8) ?: return ""
            } else {
                raw
            }
            return body
                .replace("\\N", "\n")
                .replace("\\n", "\n")
                .replace(TAGS, "")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&nbsp;", " ")
                .trim()
        }
    }

    private class Raw(val ticks: Long, val durationTicks: Long?, val text: String)

    private val TAGS = Regex("""<[^>]*>|\{[^}]*\}""")

    private const val ID_SEGMENT = 0x18538067L
    private const val ID_TRACKS = 0x1654AE6BL
    private const val ID_TRACK_ENTRY = 0xAEL
    private const val ID_TRACK_NUMBER = 0xD7L
    private const val ID_TRACK_TYPE = 0x83L
    private const val ID_CODEC_ID = 0x86L
    private const val ID_LANGUAGE = 0x22B59CL
    private const val ID_LANGUAGE_IETF = 0x22B59DL
    private const val ID_TRACK_NAME = 0x536EL
    private const val ID_FLAG_FORCED = 0x55AAL
    private const val ID_CLUSTER = 0x1F43B675L
    private const val ID_TIMECODE = 0xE7L
    private const val ID_SIMPLE_BLOCK = 0xA3L
    private const val ID_BLOCK_GROUP = 0xA0L
    private const val ID_BLOCK = 0xA1L
    private const val ID_BLOCK_DURATION = 0x9BL
    private const val ID_TIMESTAMP_SCALE = 0x2AD7B1L

    private const val TRACK_TYPE_SUBTITLE = 0x11L
    private const val MAX_ELEMENTS = 4_000_000
    private const val MAX_BLOCK_BYTES = 2_000_000
    private const val MAX_TEXT_FIELD = 512

    /** True when [file] starts with the Matroska/WebM signature. */
    fun looksMatroska(file: File): Boolean = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 4L) {
                false
            } else {
                val head = ByteArray(4)
                raf.readFully(head)
                head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() &&
                    head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte()
            }
        }
    }.getOrDefault(false)

    /** Every subtitle track in [file], named, timed and ready to draw. */
    fun read(file: File): List<OeaSubtitles.Track> = runCatching {
        if (!file.isFile || !looksMatroska(file)) return@runCatching emptyList()
        RandomAccessFile(file, "r").use { raf ->
            val entries = LinkedHashMap<Long, Entry>()
            val cues = LinkedHashMap<Long, MutableList<Raw>>()
            var scale = 1_000_000L
            var guard = 0
            while (raf.filePointer < raf.length() && guard++ < MAX_ELEMENTS) {
                val id = ident(raf) ?: break
                val size = length(raf) ?: break
                val end = endOf(raf, size, raf.length())
                when (id) {
                    ID_SEGMENT -> scale = segment(raf, end, entries, cues, scale)
                    else -> Unit
                }
                raf.seek(end)
            }
            build(file, entries, cues, scale)
        }
    }.getOrDefault(emptyList())

    // ------------------------------------------------------------------- the tree

    private fun segment(
        raf: RandomAccessFile,
        end: Long,
        entries: MutableMap<Long, Entry>,
        cues: MutableMap<Long, MutableList<Raw>>,
        scale: Long
    ): Long {
        var current = scale
        var guard = 0
        while (raf.filePointer < end && guard++ < MAX_ELEMENTS) {
            val id = ident(raf) ?: break
            val size = length(raf) ?: break
            val dataEnd = endOf(raf, size, end)
            when (id) {
                ID_TIMESTAMP_SCALE -> current = unsigned(raf, size).coerceAtLeast(1L)
                ID_TRACKS -> tracks(raf, dataEnd, entries)
                ID_CLUSTER -> cluster(raf, dataEnd, entries, cues)
                else -> Unit
            }
            raf.seek(dataEnd)
        }
        return current
    }

    private fun tracks(raf: RandomAccessFile, end: Long, out: MutableMap<Long, Entry>) {
        while (raf.filePointer < end) {
            val id = ident(raf) ?: return
            val size = length(raf) ?: return
            val dataEnd = endOf(raf, size, end)
            if (id == ID_TRACK_ENTRY) trackEntry(raf, dataEnd, out)
            raf.seek(dataEnd)
        }
    }

    private fun trackEntry(raf: RandomAccessFile, end: Long, out: MutableMap<Long, Entry>) {
        var number = -1L
        var type = -1L
        var codec = ""
        var language: String? = null
        var ietf: String? = null
        var name: String? = null
        var forced = false
        while (raf.filePointer < end) {
            val id = ident(raf) ?: break
            val size = length(raf) ?: break
            val dataEnd = endOf(raf, size, end)
            when (id) {
                ID_TRACK_NUMBER -> number = unsigned(raf, size)
                ID_TRACK_TYPE -> type = unsigned(raf, size)
                ID_CODEC_ID -> codec = text(raf, size)
                ID_LANGUAGE -> language = text(raf, size)
                ID_LANGUAGE_IETF -> ietf = text(raf, size)
                ID_TRACK_NAME -> name = text(raf, size)
                ID_FLAG_FORCED -> forced = unsigned(raf, size) != 0L
            }
            raf.seek(dataEnd)
        }
        if (number <= 0L || type != TRACK_TYPE_SUBTITLE) return
        if (!supported(codec)) return
        out[number] = Entry(number, codec, ietf ?: language, name, forced)
    }

    private fun cluster(
        raf: RandomAccessFile,
        end: Long,
        entries: Map<Long, Entry>,
        cues: MutableMap<Long, MutableList<Raw>>
    ) {
        var timecode = 0L
        var guard = 0
        while (raf.filePointer < end && guard++ < MAX_ELEMENTS) {
            val id = ident(raf) ?: return
            val size = length(raf) ?: return
            val dataEnd = endOf(raf, size, end)
            when (id) {
                ID_TIMECODE -> timecode = unsigned(raf, size)
                ID_SIMPLE_BLOCK -> block(raf, dataEnd, timecode, entries, cues, null)
                ID_BLOCK_GROUP -> group(raf, dataEnd, timecode, entries, cues)
                else -> Unit
            }
            raf.seek(dataEnd)
        }
    }

    private fun group(
        raf: RandomAccessFile,
        end: Long,
        timecode: Long,
        entries: Map<Long, Entry>,
        cues: MutableMap<Long, MutableList<Raw>>
    ) {
        var duration: Long? = null
        var blockStart = -1L
        var blockEnd = -1L
        while (raf.filePointer < end) {
            val id = ident(raf) ?: return
            val size = length(raf) ?: return
            val dataEnd = endOf(raf, size, end)
            when (id) {
                ID_BLOCK_DURATION -> duration = unsigned(raf, size)
                ID_BLOCK -> {
                    blockStart = raf.filePointer
                    blockEnd = dataEnd
                }
                else -> Unit
            }
            raf.seek(dataEnd)
        }
        if (blockStart < 0L) return
        raf.seek(blockStart)
        block(raf, blockEnd, timecode, entries, cues, duration)
    }

    private fun block(
        raf: RandomAccessFile,
        end: Long,
        timecode: Long,
        entries: Map<Long, Entry>,
        cues: MutableMap<Long, MutableList<Raw>>,
        durationTicks: Long?
    ) {
        // A block starts with the track number as a VINT, then a signed
        // 16-bit offset from the cluster's timecode, then flags.
        val trackNumber = length(raf) ?: return
        if (raf.filePointer + 3 > end) return
        val high = raf.read()
        val low = raf.read()
        val relative = (((high shl 8) or low).toShort()).toLong()
        val flags = raf.read()
        // Laced blocks pack several frames together. Subtitle frames are almost
        // never laced, and splitting one wrongly would shift every later cue, so
        // a laced block is skipped rather than guessed at.
        if (flags and 0x06 != 0) return
        val entry = entries[trackNumber] ?: return
        val remaining = end - raf.filePointer
        if (remaining <= 0L || remaining > MAX_BLOCK_BYTES) return
        val payload = ByteArray(remaining.toInt())
        raf.readFully(payload)
        val body = entry.text(payload)
        if (body.isBlank()) return
        cues.getOrPut(trackNumber) { ArrayList() }.add(Raw(timecode + relative, durationTicks, body))
    }

    // ------------------------------------------------------------------ assembling

    private fun build(
        file: File,
        entries: Map<Long, Entry>,
        cues: Map<Long, List<Raw>>,
        scale: Long
    ): List<OeaSubtitles.Track> {
        val perMs = scale / 1_000_000.0
        val tracks = ArrayList<OeaSubtitles.Track>(entries.size)
        entries.forEach { (number, entry) ->
            val raw = cues[number]?.sortedBy { it.ticks } ?: return@forEach
            if (raw.isEmpty()) return@forEach
            val list = ArrayList<OeaSubtitles.Cue>(raw.size)
            raw.forEachIndexed { index, item ->
                val start = (item.ticks * perMs).toLong().coerceAtLeast(0L)
                val next = raw.getOrNull(index + 1)?.let { (it.ticks * perMs).toLong() }
                val declared = item.durationTicks?.let { (it * perMs).toLong() }
                // A cue's length is whatever the file declares. When it declares
                // nothing — which plenty of muxers do not — the next cue is the
                // natural end, but never so far away that one line sits on the
                // screen through a scene change, and never shorter than a breath.
                val natural = declared?.takeIf { it > 0L } ?: (next?.minus(start) ?: 2_000L)
                var end = start + natural.coerceIn(600L, 7_000L)
                if (next != null && end > next) end = next
                list.add(OeaSubtitles.Cue(start, end, item.text))
            }
            val language = OeaSubtitleLanguages.resolve(entry.language)
            val sdh = entry.name?.let { SDH.containsMatchIn(it.lowercase(Locale.ROOT)) } ?: false
            tracks.add(
                OeaSubtitles.Track(
                    name = "${file.name}#$number",
                    cues = list,
                    language = language,
                    forced = entry.forced,
                    sdh = sdh,
                    origin = "${ORIGIN_PREFIX}${file.absolutePath}#$number"
                )
            )
        }
        return tracks.sortedWith(
            compareBy<OeaSubtitles.Track> { if (it.forced) 1 else 0 }
                .thenBy { it.language?.english ?: "" }
        )
    }

    // -------------------------------------------------------------- the container

    /** The origin prefix VIP stores so an embedded track can be found again. */
    const val ORIGIN_PREFIX = "embedded:"

    private val SDH = Regex("""(sdh|hearing|deaf|hoh)""")

    private fun supported(codec: String): Boolean {
        val upper = codec.uppercase(Locale.ROOT)
        return upper.startsWith("S_TEXT/") || upper.startsWith("D_WEBVTT/")
    }

    /** Reads an element ID, marker bits included, which is what makes it an ID. */
    private fun ident(raf: RandomAccessFile): Long? {
        if (raf.filePointer >= raf.length()) return null
        val first = raf.read()
        if (first <= 0) return null
        val extra = (Integer.numberOfLeadingZeros(first) - 24).coerceIn(0, 3)
        var value = first.toLong()
        repeat(extra) {
            val next = raf.read()
            if (next < 0) return null
            value = (value shl 8) or next.toLong()
        }
        return value
    }

    /** Reads an element size, marker bit stripped. Negative means "unknown". */
    private fun length(raf: RandomAccessFile): Long? {
        if (raf.filePointer >= raf.length()) return null
        val first = raf.read()
        if (first <= 0) return null
        val extra = Integer.numberOfLeadingZeros(first) - 24
        if (extra > 7) return null
        var value = (first.toLong() and (0xFFL ushr (extra + 1)))
        repeat(extra) {
            val next = raf.read()
            if (next < 0) return null
            value = (value shl 8) or (next.toLong() and 0xFF)
        }
        val unknown = (1L shl (7 * (extra + 1))) - 1L
        return if (value == unknown) -1L else value
    }

    private fun endOf(raf: RandomAccessFile, size: Long, limit: Long): Long =
        if (size < 0L) limit else minOf(raf.filePointer + size, limit)

    private fun unsigned(raf: RandomAccessFile, size: Long): Long {
        if (size <= 0L) return 0L
        val count = minOf(size, 8L).toInt()
        var value = 0L
        for (index in 0 until count) {
            val next = raf.read()
            if (next < 0) return value
            value = (value shl 8) or next.toLong()
        }
        // Step over the bytes beyond the eight a Long can hold.
        if (size > count) raf.seek(raf.filePointer + (size - count))
        return value
    }

    private fun text(raf: RandomAccessFile, size: Long): String {
        if (size <= 0L) return ""
        val count = minOf(size, MAX_TEXT_FIELD.toLong()).toInt()
        val bytes = ByteArray(count)
        val read = runCatching { raf.read(bytes) }.getOrDefault(-1)
        if (read <= 0) return ""
        if (size > count) raf.seek(raf.filePointer + (size - count))
        return String(bytes, 0, read, Charsets.UTF_8).trimEnd('\u0000').trim()
    }
}
