package com.oea.launcher.miniapps

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.Locale
import java.util.regex.Pattern

/**
 * Subtitles VIP can actually render, in every language it is handed.
 *
 * The player used to say "detect sidecar .srt … show placeholder". A placeholder
 * in a video player is the worst kind of promise, so this parses the two formats
 * people really have next to their files — SubRip (`.srt`) and WebVTT (`.vtt`) —
 * into cues the player draws, with the inline tags stripped and a timing offset
 * the user can dial in while watching.
 *
 * It is also where a track learns what language it is: from its file name when
 * that says so ([OeaSubtitleFiles.languageIn]), from the words themselves when it
 * does not (an Arabic cue is not Swedish), and otherwise null, which VIP shows
 * as "language not named" rather than inventing one.
 *
 * A [Track] is plain data — language, flags and where it came from included — so
 * the player can hold several of them at once, draw two at a time, and put the
 * one the viewer picked back under the same clip next time.
 */
object OeaSubtitles {

    data class Cue(val startMs: Long, val endMs: Long, val text: String)

    /**
     * One subtitle track.
     *
     * [origin] is the path or URI the cues were read from, kept so a choice can
     * be remembered for the clip it was made on; [forced] and [sdh] are the
     * filename flags that say this file is only part of a film's dialogue.
     */
    data class Track(
        val name: String,
        val cues: List<Cue>,
        val language: OeaSubtitleLanguages.Language? = null,
        val forced: Boolean = false,
        val sdh: Boolean = false,
        val origin: String? = null
    ) {
        val isEmpty: Boolean get() = cues.isEmpty()
        val count: Int get() = cues.size

        /** "French · Français", or an honest "language not named". */
        val languageLabel: String get() = language?.label ?: "Language not named"

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

    private val TIME = Pattern.compile("""(\d{1,2}):(\d{2}):(\d{2})[.,](\d{1,3})""")
    private val SHORT_TIME = Pattern.compile("""(\d{1,3}):(\d{2})[.,](\d{1,3})""")
    private val TAGS = Pattern.compile("""(?s)<[^>]+>|\{[^}]*\}""")

    val SUBTITLE_EXTENSIONS = listOf("srt", "vtt", "ass", "ssa")

    /**
     * Reads a subtitle file the user picked (or one OEA found beside the video).
     *
     * A `file:` URI is opened as a file and anything else through the content
     * resolver, because the track VIP remembers for a clip is stored as a URI and
     * a file found beside a film has no content provider behind it.
     */
    fun load(context: Context, uri: Uri, name: String): Track? = runCatching {
        val file = if (uri.scheme == "file") uri.path?.let(::File)?.takeIf { it.isFile } else null
        val stream = if (file != null) file.inputStream() else context.contentResolver.openInputStream(uri)
        val text = stream?.bufferedReader()?.use { it.readText() } ?: return@runCatching null
        parse(name, text)?.copy(origin = uri.toString())
    }.getOrNull()

    fun loadFile(file: File): Track? = try {
        // A file that cannot be read is simply no subtitles, the same quiet answer
        // [load] gives, because VIP asks about this one in passing and a dialog in the
        // middle of a film would be worse than none at all.
        parse(file.name, file.readText())?.copy(origin = Uri.fromFile(file).toString())
    } catch (error: Throwable) {
        null
    }

    /**
     * Parses SRT/VTT (and only strips the tags SSA/ASS would need, refusing those
     * two formats outright), naming the language from [language] when a caller
     * already knows it, from the file name otherwise, and from the script of the
     * text as a last resort.
     */
    fun parse(
        name: String,
        raw: String,
        language: OeaSubtitleLanguages.Language? = null
    ): Track? {
        // A byte-order mark is not text: left in place it glued the first cue's index
        // ("1") onto the first line of dialogue, because that line no longer looked
        // like a number. Subtitle files written on Windows carry one routinely.
        val normalised = raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace("\r", "\n")
        // The cue list and the track have to be built in one place: the old shape put
        // `?.let` on the last branch only, so a WebVTT file returned a bare cue list
        // and the caller never saw a track at all.
        val cues = if (name.lowercase(Locale.getDefault()).endsWith(".vtt") || normalised.startsWith("WEBVTT")) {
            parseVtt(normalised)
        } else if (name.lowercase(Locale.getDefault()).endsWith(".ass") || name.lowercase(Locale.getDefault()).endsWith(".ssa")) {
            // VIP says plainly that styled ASS is not supported, rather than guessing.
            null
        } else {
            parseSrt(normalised)
        } ?: return null
        if (cues.isEmpty()) return null
        val named = language
            ?: OeaSubtitleFiles.languageIn(name)
            ?: OeaSubtitleLanguages.scriptOf(cues.take(120).joinToString(" ") { it.text })
        return Track(
            name = name.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.'),
            cues = cues,
            language = named,
            forced = OeaSubtitleFiles.forcedIn(name),
            sdh = OeaSubtitleFiles.sdhIn(name)
        )
    }

    private fun parseSrt(text: String): List<Cue> {
        val cues = ArrayList<Cue>(256)
        val blocks = text.split(Regex("\\n{2,}"))
        for (raw in blocks) {
            val lines = raw.trim().split('\n')
            if (lines.isEmpty()) continue
            val timeAt = lines.indexOfFirst { TIME.matcher(it).find() || SHORT_TIME.matcher(it).find() }
            if (timeAt < 0) continue
            val span = parseSpan(lines[timeAt]) ?: continue
            // Everything before the timing line is a cue number (or an id); everything
            // after it is dialogue. The old filter also dropped dialogue that happened
            // to be a number — "1984", "42" — because it removed every numeric line.
            val body = lines.drop(timeAt + 1)
                .joinToString("\n") { clean(it) }
                .trim()
            if (body.isEmpty()) continue
            cues.add(Cue(span.first, span.second, body))
        }
        return cues.sortedBy { it.startMs }
    }

    private fun parseVtt(text: String): List<Cue> {
        val cues = ArrayList<Cue>(256)
        val body = text.substringAfter("WEBVTT", text)
        body.split(Regex("\\n{2,}")).forEach { block ->
            val trimmed = block.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("NOTE") || trimmed.startsWith("STYLE") || trimmed.startsWith("REGION")) return@forEach
            val lines = trimmed.lines()
            val timeAt = lines.indexOfFirst { it.contains("-->") }
            if (timeAt < 0) return@forEach
            val span = parseSpan(lines[timeAt]) ?: return@forEach
            // WebVTT lets a cue carry an identifier line above its timing. Everything
            // before the timing is a label for the file, not words on the screen.
            val caption = lines.drop(timeAt + 1).joinToString("\n") { clean(it) }.trim()
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
        return track.copy(
            cues = track.cues.map {
                Cue(
                    (it.startMs + offsetMs).coerceAtLeast(0L),
                    (it.endMs + offsetMs).coerceAtLeast(1L),
                    it.text
                )
            }
        )
    }

    // ------------------------------------------------------------------- drawing

    /**
     * True when the first strong character in [text] reads right to left.
     *
     * Decided from the words rather than from the track's label on purpose: a
     * file called `Movie.ar.srt` and a file called `subs.srt` that happens to be
     * Arabic both have to put their full stop on the left.
     */
    fun isRightToLeft(text: String): Boolean {
        for (ch in text) {
            if (ch.isWhitespace() || ch.isDigit()) continue
            when (Character.getDirectionality(ch)) {
                Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return false
                Character.DIRECTIONALITY_RIGHT_TO_LEFT,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return true
            }
        }
        return false
    }

    /**
     * The cue as it should be drawn: a right-to-left line is wrapped in an
     * isolate so trailing punctuation and any Latin name inside it land where a
     * reader of that language expects, not at the "end" of a left-to-right run.
     */
    fun display(text: String): String {
        if (text.isEmpty() || text.startsWith('\u202B')) return text
        return if (isRightToLeft(text)) "\u202B$text\u202C" else text
    }

    /**
     * Two languages at once — the picture's own dialogue and the one you read it
     * in. Returns null when neither has anything to say at [timeMs], which is the
     * answer that hides the bar instead of leaving an empty bubble on screen.
     */
    fun together(first: Track?, second: Track?, timeMs: Long): String? {
        val primary = first?.takeIf { !it.isEmpty }?.at(timeMs)
        val other = second?.takeIf { !it.isEmpty && it !== first }?.at(timeMs)
        if (primary == null && other == null) return null
        return listOfNotNull(primary, other).joinToString("\n") { display(it) }
    }

    // ------------------------------------------------------------------ writing

    /**
     * Writes a track back out as SubRip — the format every other player reads.
     * Used when a language picked in VIP has to leave VIP (a download with one
     * track, a phone that needs the file in another app).
     */
    fun toSrt(track: Track): String = buildString {
        track.cues.forEachIndexed { index, cue ->
            append(index + 1).append('\n')
            append(stamp(cue.startMs)).append(" --> ").append(stamp(cue.endMs)).append('\n')
            append(cue.text.trim()).append('\n')
            if (index != track.cues.lastIndex) append('\n')
        }
    }

    private fun stamp(milliseconds: Long): String {
        val at = milliseconds.coerceAtLeast(0L)
        return String.format(
            Locale.ROOT,
            "%02d:%02d:%02d,%03d",
            at / 3_600_000L,
            (at / 60_000L) % 60L,
            (at / 1_000L) % 60L,
            at % 1_000L
        )
    }
}
