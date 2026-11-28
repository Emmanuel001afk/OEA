package com.oea.launcher.miniapps

import java.util.Locale

/**
 * Which subtitle files belong to a clip, and in which language.
 *
 * One sidecar per film is the exception, not the rule: a downloaded series comes
 * with `Episode.en.srt`, `Episode.fr.srt` and `Episode.yor.srt` in the same
 * folder, a media server writes them into `Subs/`, and a disc rip writes
 * `Episode.English.forced.srt` for the alien dialogue only. VIP used to find the
 * first file it happened to look at — so a phone set to English could put the
 * German track under the picture and there was no way to say otherwise.
 *
 * This is the part that decides. It is pure: names in, candidates out. The
 * filesystem walk belongs to [OeaMediaProbe], and reading the cues to
 * [OeaSubtitles], so every rule below can be pinned by a unit test.
 *
 * The rule is deliberately strict: a file must share the clip's name and every
 * word after it must be a language, a flag VIP understands, or noise
 * (`subs`, `cc`, `default`). `Movie.Trailer.srt` is not `Movie.mkv`'s dialogue,
 * and painting it over the film would be worse than finding nothing.
 */
object OeaSubtitleFiles {

    /** The formats VIP renders itself. ASS/SSA stay out: their dialogue is styled. */
    val EXTENSIONS = listOf("srt", "vtt")

    /**
     * One file that belongs to a clip.
     *
     * [language] is null when the name carries no language — the words inside
     * will be asked instead, which can tell a script apart but cannot name a
     * Latin-script language.
     */
    class Candidate(
        val name: String,
        val language: OeaSubtitleLanguages.Language?,
        val forced: Boolean = false,
        val sdh: Boolean = false
    ) {
        val label: String get() = language?.label ?: "Language not named"

        val badge: String? get() = language?.badge

        val note: String
            get() = when {
                forced && sdh -> "forced • SDH"
                forced -> "forced"
                sdh -> "SDH"
                else -> ""
            }

        override fun toString(): String = name

        override fun equals(other: Any?): Boolean =
            other is Candidate && other.name == name && other.language == language &&
                other.forced == forced && other.sdh == sdh

        override fun hashCode(): Int = name.hashCode()
    }

    private val SEPARATORS = Regex("""[.,()\[\]{}\s]+""")

    /**
     * Words that say something about a subtitle file and nothing about its
     * language. `und` is BCP-47's own word for "undetermined".
     */
    private val NOISE = setOf(
        "sub", "subs", "subtitle", "subtitles", "caption", "captions", "closedcaption",
        "default", "full", "dialog", "dialogue", "dialogs", "sdh", "cc", "hoh",
        "forced", "foreign", "und", "unknown", "multi", "multisub", "multilang"
    )

    /** True for a file VIP could read: `.srt` or `.vtt`, in any case. */
    fun isSubtitle(name: String): Boolean = EXTENSIONS.any { name.endsWith(".$it", ignoreCase = true) }

    /** The name without its extension — `Movie.en.srt` becomes `Movie.en`. */
    fun stem(name: String): String = name.substringBeforeLast('.', name)

    /**
     * The files in [names] that belong to [videoName], in the order they were
     * given minus anything that is not this clip's dialogue.
     */
    fun candidates(videoName: String, names: Collection<String>): List<Candidate> {
        val file = videoName.substringAfterLast('/').substringAfterLast('\\')
        val videoStem = stem(file)
        if (videoStem.length < 2) return emptyList()
        val key = videoStem.lowercase(Locale.ROOT)
        val found = LinkedHashMap<String, Candidate>()
        names.forEach { raw ->
            val name = raw.substringAfterLast('/').substringAfterLast('\\')
            if (!isSubtitle(name)) return@forEach
            val base = stem(name)
            val baseKey = base.lowercase(Locale.ROOT)
            // The clip's own name, then a separator, then the language part.
            val tail = when {
                baseKey == key -> ""
                baseKey.startsWith("$key.") || baseKey.startsWith("$key ") ||
                    baseKey.startsWith("${key}_") || baseKey.startsWith("$key-") -> base.substring(videoStem.length + 1)
                else -> return@forEach
            }
            val parsed = parseTail(tail) ?: return@forEach
            // Two files whose names differ only in case are one file on Android.
            found[name.lowercase(Locale.ROOT)] = Candidate(name, parsed.language, parsed.forced, parsed.sdh)
        }
        return found.values.toList()
    }

    /** True when the name marks this file as only the dialogue that needs it. */
    fun forcedIn(fileName: String): Boolean = wordsIn(fileName).any { it == "forced" || it == "foreign" }

    /** True for a hearing-impaired track: `Movie.en.sdh.srt`, `Movie.en.cc.srt`. */
    fun sdhIn(fileName: String): Boolean = wordsIn(fileName).any { it == "sdh" || it == "cc" || it == "hoh" }

    private fun wordsIn(fileName: String): List<String> =
        stem(fileName.substringAfterLast('/').substringAfterLast('\\'))
            .split(SEPARATORS).filter { it.isNotBlank() }.map { it.trim().lowercase(Locale.ROOT) }

    /** What is left of a file name after the clip's own, or null if it is not ours. */
    private fun parseTail(tail: String): Tail? {
        if (tail.isBlank()) return Tail(null, false, false)
        val words = tail.split(SEPARATORS).filter { it.isNotBlank() }
        if (words.isEmpty()) return Tail(null, false, false)
        // `Movie.directors.cut.en.srt` is too far from the clip's name to be sure.
        if (words.size > 4) return null
        var language: OeaSubtitleLanguages.Language? = null
        var forced = false
        var sdh = false
        for (word in words) {
            val token = word.trim().lowercase(Locale.ROOT)
            when {
                token == "forced" || token == "foreign" -> forced = true
                token == "sdh" || token == "cc" || token == "hoh" -> sdh = true
                token in NOISE -> Unit
                else -> {
                    val hit = OeaSubtitleLanguages.resolve(token)
                        ?: OeaSubtitleLanguages.resolve(token.substringBefore('-'))
                    // A word nobody recognises means this is probably another
                    // file with the same opening (`Movie.Trailer.srt`).
                    if (hit == null) return null
                    if (language == null) language = hit
                }
            }
        }
        return Tail(language, forced, sdh)
    }

    private class Tail(
        val language: OeaSubtitleLanguages.Language?,
        val forced: Boolean,
        val sdh: Boolean
    )

    /**
     * The language a file name announces, for files VIP did not find by itself
     * (one the user picked through the system picker, where the clip's name is
     * not part of the name).
     *
     * Only the last word that is not a flag or noise counts: `Movie.en.srt` and
     * `Some Film 2019 1080p fr.srt` name their language, `Movie.English.SDH.srt`
     * names it before its flag, and `The English Patient.srt` names nothing —
     * which is the honest answer, and the words inside will still be drawn.
     */
    fun languageIn(fileName: String): OeaSubtitleLanguages.Language? {
        val base = stem(fileName.substringAfterLast('/').substringAfterLast('\\'))
        val words = base.split(SEPARATORS).filter { it.isNotBlank() }
        var skipped = 0
        for (word in words.asReversed()) {
            val token = word.trim().lowercase(Locale.ROOT)
            if (token.all { it.isDigit() } || token in NOISE || token == "forced" || token == "foreign") {
                if (++skipped > 2) return null
                continue
            }
            return OeaSubtitleLanguages.resolve(token)
                ?: OeaSubtitleLanguages.resolve(token.substringBefore('-'))
        }
        return null
    }

    /**
     * Orders candidates the way a viewer would: the language they chose first,
     * then complete tracks before forced ones, then named languages before
     * unnamed, then by name so the list does not reshuffle.
     */
    fun sorted(
        candidates: List<Candidate>,
        preferred: OeaSubtitleLanguages.Language?
    ): List<Candidate> = candidates.sortedWith(
        compareBy<Candidate> { if (preferred != null && it.language?.tag == preferred.tag) 0 else 1 }
            .thenBy { if (it.forced) 1 else 0 }
            .thenBy { if (it.language == null) 1 else 0 }
            .thenBy { it.language?.english ?: "" }
            .thenBy { it.name.lowercase(Locale.ROOT) }
    )

    /**
     * The first candidate the viewer is most likely to want: their preferred
     * language, a full track, a named language, else plain whatever came first.
     */
    fun best(
        candidates: List<Candidate>,
        preferred: OeaSubtitleLanguages.Language?
    ): Candidate? = sorted(candidates, preferred).firstOrNull()
}
