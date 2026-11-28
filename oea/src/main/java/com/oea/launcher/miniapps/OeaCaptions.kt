package com.oea.launcher.miniapps

import java.util.Locale

/**
 * The words a recognizer produced, turned into cues worth drawing.
 *
 * Everything here is pure, because everything here is what makes generated
 * subtitles look generated: a line that starts in the middle of a word, a
 * capital that never arrives, a "[MUSIC]" left on screen for two seconds, a
 * cue that runs past the end of the film. The timing is not decided here — it
 * comes from [OeaSpeechRegions], measured on the audio — so this only has to
 * make the text presentable and honest.
 */
object OeaCaptions {

    /** Two lines is a subtitle; three is a paragraph over somebody's face. */
    const val LINES = 2

    /** Roughly 42 characters fits across a phone in landscape at a readable size. */
    const val LINE_CHARS = 42

    private val NOISE = Regex("""^\s*[\[(（].*?[\])）]\s*$""")
    private val SOUND_MARKS = Regex("""^[\s♪♫\-–—.…]+$""")

    /**
     * The recognizer's line, as a subtitle line.
     *
     * Capitalised, with the spaces a recognizer likes to leave around the edges
     * gone, and with the sounds it reports rather than transcribes — "[MUSIC]",
     * "(applause)", "♪" — dropped instead of shown. Those are notes for a
     * human, and a generated track that displays them is worse than one that
     * stays quiet through the music.
     */
    fun tidy(raw: String?): String {
        val text = raw?.replace('\u00A0', ' ')?.trim().orEmpty()
        if (text.isEmpty()) return ""
        if (NOISE.matches(text) || SOUND_MARKS.matches(text)) return ""
        val collapsed = text.replace(Regex("""\s+"""), " ").trim()
        if (collapsed.isEmpty()) return ""
        val first = collapsed.first()
        val spaced = if (first.isLowerCase()) {
            collapsed.replaceFirstChar { it.titlecase(Locale.getDefault()) }
        } else {
            collapsed
        }
        return if (spaced.last().isLetterOrDigit()) "$spaced." else spaced
    }

    /**
     * Cues from measured regions and the words found in each.
     *
     * A region the recognizer heard nothing in is skipped rather than shown as
     * an empty cue, and the neighbours of a skipped region are not stretched
     * over it: silence on screen during silence in the film is correct.
     */
    fun cues(
        regions: List<OeaSpeechRegions.Region>,
        told: List<String?>,
        durationMs: Long,
        limitMs: Long = Long.MAX_VALUE
    ): List<OeaSubtitles.Cue> {
        val out = ArrayList<OeaSubtitles.Cue>(regions.size)
        var previous = ""
        regions.forEachIndexed { index, region ->
            val text = tidy(told.getOrNull(index))
            if (text.isEmpty() || text == previous) return@forEachIndexed
            val start = region.startMs.coerceAtLeast(0L)
            // The film's end is a ceiling, not a suggestion: a cue that starts
            // 500 ms before the credits must stop at the credits.
            val ceiling = if (durationMs > 0L) minOf(durationMs, limitMs) else limitMs
            val end = minOf(region.endMs, ceiling)
                .coerceAtLeast(minOf(start + 600L, ceiling))
            if (end - start < 300L) return@forEachIndexed
            out.add(OeaSubtitles.Cue(start, end, wrap(text, LINE_CHARS)))
            previous = text
        }
        return out
    }

    /** Breaks one line into at most [LINES] balanced lines of about [maxChars]. */
    fun wrap(text: String, maxChars: Int = LINE_CHARS): String {
        val words = text.split(' ').filter { it.isNotEmpty() }
        if (words.size < 2 || text.length <= maxChars) return text
        val lines = ArrayList<String>(LINES)
        var current = StringBuilder()
        for (word in words) {
            if (current.isNotEmpty() && current.length + 1 + word.length > maxChars && lines.size < LINES - 1) {
                lines.add(current.toString())
                current = StringBuilder()
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(word)
        }
        if (current.isNotEmpty()) lines.add(current.toString())
        return lines.joinToString("\n")
    }

    /** What a generated track says about itself, for the sheet. */
    fun provenance(onDevice: Boolean): String = if (onDevice) {
        "Written from the sound by the phone's own recognizer — the audio never leaves the phone. Timings measured from the audio."
    } else {
        "Written from the sound by an online recognizer — the clip's audio left the phone for it. Timings measured from the audio."
    }
}
