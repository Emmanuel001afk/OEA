package com.oea.launcher.miniapps

import android.content.Context
import android.net.Uri
import java.io.BufferedReader
import java.io.File
import java.util.Locale
import java.util.regex.Pattern

/**
 * Subtitles VIP can actually render.
 *
 * The player used to say "detect sidecar .srt … show placeholder". A placeholder
 * in a video player is the worst kind of promise, so this parses the two formats
 * people really have next to their files — SubRip (`.srt`) and WebVTT (`.vtt`) —
 * into cues the player draws, with the inline tags stripped and a timing offset
 * the user can dial in while watching.
 */
object OeaSubtitles {

    data class Cue(val startMs: Long, val endMs: Long, val text: String)

    private val TIME = Pattern.compile("""(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})""")
    private val SHORT_TIME = Pattern.compile("""(\d{1,3}):(\d{2})[,.](\d{1,3})""")
    private val TAGS = Pattern.compile("""(?s)<[^>]+>|\{[^}]*\}""")

    val SUBTITLE_EXTENSIONS = listOf("srt", "vtt", "ass", "ssa")

    /** A cue list is a plain list on purpose: the player binary-searches it. */
    class Track(val name: String, val cues: List<Cue>) {
        val isEmpty: Boolean get() = cues.isEmpty()
        val count: Int get() = cues.size

        /** The cue to show at [timeMs], or null. Fast enough for 60 fps. */
        fun at(timeMs: Long): String? {
            if (cues.isEmpty()) return null
            var low = 0
            var high = cues.size - 1
            var found: Cue? = null
            while (low <= high) {
                val mid = (low + high) ushr 1
                val cue = cues[mid]
                when {
                    cue.startMs > timeMs -> high = mid - 1
                    cue.endMs < timeMs -> low = mid + 1
                    else -> {
                        found = cue
                        break
                    }
                }
            }
            return found?.text ?: runCatching {
                // Overlaps and gaps: fall back to the nearest preceding cue.
                val index = cues.indexOfLast { it.startMs <= timeMs }
                cues.getOrNull(index)?.takeIf { timeMs - it.startMs < 1500L }?.text
            }.getOrNull()
        }
    }

    /** Reads a subtitle file the user picked (or one OEA found beside the video). */
    fun load(context: Context, uri: Uri, name: String): Track? = runCatching {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return@runCatching null
        parse(name, text)
    }.getOrNull()

    fun loadFile(file: File): Track? = try {
        // A plain try/catch rather than runCatching: the result of parsing is already
        // nullable, and Result<Track?>.getOrNull() widens that to Any?.
        parse(file.name, file.readText())
    } catch (error: Throwable) {
        null
    }

    /** Parses SRT/VTT (and only strips the tags SSA/ASS would need, refusing the rest). */
    fun parse(name: String, raw: String): Track? {
        val normalised = raw.replace("\r\n", "\n").replace("\r", "\n")
        return if (name.lowercase(Locale.getDefault()).endsWith(".vtt") || normalised.startsWith("WEBVTT")) {
            parseVtt(normalised)
        } else if (name.lowercase(Locale.getDefault()).endsWith(".ass") || name.lowercase(Locale.getDefault()).endsWith(".ssa")) {
            null // VIP says plainly that styled ASS is not supported, rather than guessing.
        } else {
            parseSrt(normalised)
        }?.let { Track(name.substringAfterLast('/').substringBeforeLast('.'), it) }
    }

    private fun parseSrt(text: String): List<Cue> {
        val cues = ArrayList<Cue>(256)
        val blocks = text.split(Regex("\n{2,}"))
        var index = 0
        while (index < blocks.size) {
            val block = blocks[index].trim()
            index++
            if (block.isEmpty()) continue
            val lines = block.split('\n')
            val timeLine = lines.firstOrNull { TIME.matcher(it).find() || SHORT_TIME.matcher(it).find() } ?: continue
            val span = parseSpan(timeLine) ?: continue
            val body = lines.filter { it != timeLine && it.trim().toIntOrNull() == null && !CUE_ID.matcher(it.trim()).matches() }
                .joinToString("\n") { clean(it) }
                .trim()
            if (body.isEmpty()) continue
            cues.add(Cue(span.first, span.second, body))
        }
        return cues.sortedBy { it.startMs }
    }

    private val CUE_ID = Pattern.compile("""\d{1,7}""")

    private fun parseVtt(text: String): List<Cue> {
        val cues = ArrayList<Cue>(256)
        val body = text.substringAfter("WEBVTT", text)
        body.split(Regex("\n{2,}")).forEach { block ->
            val trimmed = block.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("NOTE") || trimmed.startsWith("STYLE") || trimmed.startsWith("REGION")) return@forEach
            val timeLine = trimmed.lineSequence().firstOrNull { it.contains("-->") } ?: return@forEach
            val span = parseSpan(timeLine) ?: return@forEach
            val caption = trimmed.lines().filter { it != timeLine }.joinToString("\n") { clean(it) }.trim()
            if (caption.isEmpty()) return@forEach
            cues.add(Cue(span.first, span.second, caption))
        }
        return cues.sortedBy { it.startMs }
    }

    private fun parseSpan(line: String): Pair<Long, Long>? {
        val times = ArrayList<Long>(2)
        val full = TIME.matcher(line)
        while (full.find() && times.size < 2) {
            times.add(
                full.group(1)!!.toLong() * 3_600_000L + full.group(2)!!.toLong() * 60_000L +
                    full.group(3)!!.toLong() * 1_000L + full.group(4)!!.padEnd(3, '0').toLong()
            )
        }
        if (times.size < 2) {
            times.clear()
            val short = SHORT_TIME.matcher(line)
            while (short.find() && times.size < 2) {
                times.add(
                    short.group(1)!!.toLong() * 60_000L + short.group(2)!!.toLong() * 1_000L +
                        short.group(3)!!.padEnd(3, '0').toLong()
                )
            }
        }
        if (times.size < 2) return null
        val start = times[0]
        val end = if (times[1] > start) times[1] else start + 4_000L
        return start to end
    }

    /** Drops `<i>`/`{\an8}` style markup and the `{\pos(...)}` leftovers. */
    private fun clean(line: String): String =
        TAGS.matcher(line).replaceAll("").replace("&nbsp;", " ").replace("&amp;", "&").trim()

    /** Rewrites every cue by [offsetMs] — the "subtitles are late" fix. */
    fun shifted(track: Track, offsetMs: Long): Track {
        if (offsetMs == 0L) return track
        return Track(track.name, track.cues.map { Cue((it.startMs + offsetMs).coerceAtLeast(0L), (it.endMs + offsetMs).coerceAtLeast(1L), it.text) })
    }
}
