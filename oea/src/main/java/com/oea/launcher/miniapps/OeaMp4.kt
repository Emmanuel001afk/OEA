package com.oea.launcher.miniapps

import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

/**
 * The subtitle tracks muxed inside an MP4 or MOV.
 *
 * Matroska hands subtitle text over as a stream of blocks, so [OeaMatroska] can
 * walk clusters and read it as it goes. MP4 does not: every sample's position and
 * duration live in tables at the front of the file (`stts`, `stsz`, `stsc`,
 * `stco`), and the picture data is a featureless run of bytes. Reading the text
 * therefore means reading those tables first and then jumping — which is what
 * this does, for the three timed-text formats MP4 actually carries in practice
 * (`tx3g`, `text` and `sbtl` handlers).
 *
 * Bitmap subtitles (`c608`/`c708` caption channels, `mp4s`) are skipped for the
 * same reason PGS is: a picture of a word is not a word. The language comes from
 * the packed ISO-639-2 code in `mdhd`, which is a real field rather than a
 * guess.
 */
object OeaMp4 {

    /** One sample of timed text: when it shows, for how long, and what it says. */
    private class Sample(val startMs: Long, val endMs: Long, val text: String)

    private val TEXT_HANDLERS = setOf("sbtl", "subt", "text", "clcp", "subp", "caption")

    private const val MAX_MOOV_BYTES = 64 * 1024 * 1024
    private const val MAX_SAMPLE_BYTES = 4 * 1024 * 1024

    /** True when the file starts with an `ftyp` box, the MP4 family's signature. */
    fun looksMp4(file: File): Boolean = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 12L) {
                false
            } else {
                val head = ByteArray(12)
                raf.readFully(head)
                String(head, 4, 4, Charsets.US_ASCII) == "ftyp"
            }
        }
    }.getOrDefault(false)

    /** Every timed-text track in [file], named, timed and ready to draw. */
    fun read(file: File): List<OeaSubtitles.Track> = runCatching {
        if (!file.isFile || !looksMp4(file)) return@runCatching emptyList()
        val moov = moovOf(file) ?: return@runCatching emptyList()
        RandomAccessFile(file, "r").use { raf ->
            build(file, moov, raf)
        }
    }.getOrDefault(emptyList())

    // ------------------------------------------------------------------- the box

    /** Reads `moov` into memory — it is a table of contents, not a payload. */
    private fun moovOf(file: File): ByteArray? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            var at = 0L
            while (at + 8 <= length) {
                raf.seek(at)
                val header = ByteArray(8)
                raf.readFully(header)
                var size = u32(header, 0)
                val type = String(header, 4, 4, Charsets.US_ASCII)
                if (size == 1L) {
                    // 64-bit size in the following eight bytes.
                    val wide = ByteArray(8)
                    raf.readFully(wide)
                    size = u64(wide, 0)
                    if (size < 16L) return@use null
                } else if (size == 0L) {
                    size = length - at
                }
                if (size < 8L) return@use null
                if (type == "moov") {
                    val payload = (size - 8L).coerceAtMost(MAX_MOOV_BYTES.toLong()).toInt()
                    val bytes = ByteArray(payload)
                    raf.readFully(bytes)
                    return@use bytes
                }
                at += size
            }
            null
        }
    }.getOrNull()

    // ---------------------------------------------------------------- the tables

    private class Chunk(val offset: Long, val samples: Int)

    /**
     * What one `trak` says about its samples, filled in as the boxes are walked
     * and turned into chunks only once both tables have been seen — `stsc` and
     * `stco` are siblings and their order is not guaranteed.
     */
    private class Table {
        var timescale = 0L
        var language: String? = null
        var handler: String? = null
        var durations = LongArray(0)
        var sizes = IntArray(0)
        var offsets = LongArray(0)
        var runs = LongArray(0)

        fun chunks(): List<Chunk> {
            if (offsets.isEmpty()) return emptyList()
            val out = ArrayList<Chunk>(offsets.size)
            var run = 0
            offsets.forEachIndexed { index, offset ->
                val number = index + 1L
                while (run + 3 < runs.size && number >= runs[run + 3]) {
                    run += 3
                }
                val perChunk = if (runs.size > run + 1) runs[run + 1].toInt() else 1
                out.add(Chunk(offset, perChunk.coerceAtLeast(1)))
            }
            return out
        }
    }

    private fun build(file: File, moov: ByteArray, raf: RandomAccessFile): List<OeaSubtitles.Track> {
        val tracks = ArrayList<OeaSubtitles.Track>(2)
        var ordinal = 0
        boxes(moov, 0, moov.size) { type, from, to ->
            if (type == "trak") {
                val table = Table()
                trak(moov, from, to, table)
                val track = trackOf(file, raf, table, ordinal)
                if (track != null) {
                    tracks.add(track)
                    ordinal++
                }
            }
        }
        return tracks.sortedWith(compareBy { it.language?.english ?: "" })
    }

    private fun trak(bytes: ByteArray, from: Int, to: Int, table: Table) {
        boxes(bytes, from, to) { type, start, end ->
            if (type == "mdia") mdia(bytes, start, end, table)
        }
    }

    private fun mdia(bytes: ByteArray, from: Int, to: Int, table: Table) {
        boxes(bytes, from, to) { type, start, end ->
            when (type) {
                "mdhd" -> mdhd(bytes, start, end, table)
                "hdlr" -> if (start + 12 <= end) table.handler = String(bytes, start + 8, 4, Charsets.US_ASCII)
                "minf" -> minf(bytes, start, end, table)
                else -> Unit
            }
        }
    }

    /** Timescale and the packed ISO-639-2 language code. */
    private fun mdhd(bytes: ByteArray, from: Int, to: Int, table: Table) {
        if (from + 24 > to) return
        val version = bytes[from].toInt() and 0xFF
        val timescaleAt = if (version == 1) from + 20 else from + 12
        val languageAt = if (version == 1) from + 32 else from + 20
        if (timescaleAt + 4 <= to) table.timescale = u32(bytes, timescaleAt)
        if (languageAt + 2 <= to) table.language = unpackLanguage(u16(bytes, languageAt))
    }

    private fun minf(bytes: ByteArray, from: Int, to: Int, table: Table) {
        boxes(bytes, from, to) { type, start, end ->
            if (type == "stbl") stbl(bytes, start, end, table)
        }
    }

    private fun stbl(bytes: ByteArray, from: Int, to: Int, table: Table) {
        boxes(bytes, from, to) { type, start, end ->
            when (type) {
                "stts" -> table.durations = stts(bytes, start, end)
                "stsz" -> table.sizes = stsz(bytes, start, end)
                "stsc" -> table.runs = stsc(bytes, start, end)
                "stco" -> table.offsets = stco(bytes, start, end, wide = false)
                "co64" -> table.offsets = stco(bytes, start, end, wide = true)
                else -> Unit
            }
        }
    }

    private fun stts(bytes: ByteArray, from: Int, to: Int): LongArray {
        val count = u32(bytes, from + 4).toInt()
        val out = ArrayList<Long>(256)
        var at = from + 8
        for (index in 0 until count) {
            if (at + 8 > to) break
            val repeat = u32(bytes, at).toInt().coerceAtLeast(0)
            val delta = u32(bytes, at + 4)
            repeat(repeat.coerceAtMost(1_000_000)) { out.add(delta) }
            at += 8
        }
        return out.toLongArray()
    }

    private fun stsz(bytes: ByteArray, from: Int, to: Int): IntArray {
        val fixed = u32(bytes, from + 4)
        val count = u32(bytes, from + 8).toInt()
        if (count <= 0) return IntArray(0)
        if (fixed > 0L) return IntArray(count) { fixed.toInt() }
        if (from + 12 + count * 4 > to) return IntArray(0)
        return IntArray(count) { u32(bytes, from + 12 + it * 4).toInt() }
    }

    /** Triples: first chunk number, samples in that chunk, description index. */
    private fun stsc(bytes: ByteArray, from: Int, to: Int): LongArray {
        val count = u32(bytes, from + 4).toInt()
        val out = ArrayList<Long>(count * 3)
        var at = from + 8
        for (index in 0 until count) {
            if (at + 12 > to) break
            out.add(u32(bytes, at))
            out.add(u32(bytes, at + 4))
            at += 12
        }
        return out.toLongArray()
    }

    private fun stco(bytes: ByteArray, from: Int, to: Int, wide: Boolean): LongArray {
        val count = u32(bytes, from + 4).toInt()
        if (count <= 0) return LongArray(0)
        val step = if (wide) 8 else 4
        if (from + 8 + count * step > to) return LongArray(0)
        return LongArray(count) { u32(bytes, from + 8 + it * step) }
    }

    // ----------------------------------------------------------------- the track

    private fun trackOf(
        file: File,
        raf: RandomAccessFile,
        table: Table,
        ordinal: Int
    ): OeaSubtitles.Track? {
        if (table.handler?.lowercase(Locale.ROOT) !in TEXT_HANDLERS) return null
        if (table.timescale <= 0L || table.sizes.isEmpty()) return null
        val samples = samplesOf(raf, table) ?: return null
        if (samples.isEmpty()) return null
        val language = table.language?.let { OeaSubtitleLanguages.resolve(it) }
        return OeaSubtitles.Track(
            name = "${file.name}#${ordinal + 1}",
            cues = samples.map { OeaSubtitles.Cue(it.startMs, it.endMs, it.text) },
            language = language,
            origin = "${OeaMatroska.ORIGIN_PREFIX}${file.absolutePath}#${ordinal + 1}"
        )
    }

    /**
     * The text of every sample, timed by the `stts` deltas.
     *
     * A `tx3g` sample is a two-byte length followed by UTF-8; anything after the
     * text is style boxes. `text` samples carry a longer header, which is why the
     * length prefix is only trusted when it fits inside the sample.
     */
    private fun samplesOf(raf: RandomAccessFile, table: Table): List<Sample>? {
        val out = ArrayList<Sample>(64)
        var index = 0
        var clock = 0L
        for (chunk in table.chunks()) {
            var at = chunk.offset
            for (within in 0 until chunk.samples) {
                if (index >= table.sizes.size) return out
                val size = table.sizes[index]
                val delta = table.durations.getOrNull(index) ?: 0L
                index++
                if (size > 0 && size <= MAX_SAMPLE_BYTES) {
                    val bytes = ByteArray(size)
                    raf.seek(at)
                    if (runCatching { raf.readFully(bytes) }.isSuccess) {
                        val text = textOf(bytes)
                        if (text.isNotEmpty()) {
                            val start = clock * 1000L / table.timescale
                            var end = (clock + delta.coerceAtLeast(1L)) * 1000L / table.timescale
                            if (end - start < 600L) end = start + 600L
                            out.add(Sample(start, end, text))
                        }
                    }
                }
                at += size
                clock += delta
            }
        }
        return out
    }

    private fun textOf(bytes: ByteArray): String {
        if (bytes.size < 2) return ""
        val declared = u16(bytes, 0)
        val from = 2
        val length = if (declared in 1..(bytes.size - from)) declared else bytes.size - from
        val raw = String(bytes, from, length, Charsets.UTF_8)
        return raw
            .replace(Regex("""<[^>]*>|\{[^}]*\}"""), "")
            .replace("\u0000", "")
            .trim()
    }

    // ------------------------------------------------------------------ plumbing

    /** Walks the boxes in `[from, to)`, handing each type and payload range on. */
    private inline fun boxes(bytes: ByteArray, from: Int, to: Int, onBox: (String, Int, Int) -> Unit) {
        var at = from
        var guard = 0
        while (at + 8 <= to && guard++ < 100_000) {
            var size = u32(bytes, at)
            val type = String(bytes, at + 4, 4, Charsets.US_ASCII)
            var header = 8
            if (size == 1L) {
                if (at + 16 > to) return
                size = u64(bytes, at + 8)
                header = 16
            } else if (size == 0L) {
                size = (to - at).toLong()
            }
            if (size < header.toLong()) return
            val end = (at + size).coerceAtMost(to.toLong()).toInt()
            onBox(type, at + header, end)
            at = end
        }
    }

    private fun u32(bytes: ByteArray, at: Int): Long =
        ((bytes[at].toLong() and 0xFF) shl 24) or
            ((bytes[at + 1].toLong() and 0xFF) shl 16) or
            ((bytes[at + 2].toLong() and 0xFF) shl 8) or
            (bytes[at + 3].toLong() and 0xFF)

    private fun u16(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)

    private fun u64(bytes: ByteArray, at: Int): Long {
        var value = 0L
        for (index in 0 until 8) value = (value shl 8) or (bytes[at + index].toLong() and 0xFF)
        return value
    }

    /**
     * The packed fifteen-bit language of `mdhd`: three five-bit letters offset
     * from 'a'. `und` means the file does not say.
     */
    private fun unpackLanguage(packed: Int): String? {
        if (packed == 0) return null
        val letters = CharArray(3) { index ->
            val shift = 10 - index * 5
            (((packed shr shift) and 0x1F) + 0x60).toChar()
        }
        val code = String(letters)
        return if (code == "und" || code.isBlank()) null else code
    }
}
