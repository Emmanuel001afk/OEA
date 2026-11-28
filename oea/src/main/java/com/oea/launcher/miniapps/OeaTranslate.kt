package com.oea.launcher.miniapps

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Turning a track VIP already has into another language, on the phone.
 *
 * This is the honest version of "translate my subtitles". The translation runs
 * on the device through ML Kit, so the film never leaves it; the model for the
 * language you asked for — about thirty megabytes — is downloaded from Play
 * services the first time and kept. What comes back is a new track with exactly
 * the timings of the original: the words change, the clock does not, which is
 * the whole reason translating a subtitle file is a sane operation and
 * transcribing a film is a hard one.
 *
 * ML Kit translates through English, so anything that is not English is
 * translated to English and out again, and the result reads like it. That is
 * said in the sheet rather than discovered afterwards. Its models cover the
 * languages below and not every language in [OeaSubtitleLanguages] — Yoruba,
 * Igbo, Hausa, Amharic and Zulu are not in the list, and VIP does not pretend
 * otherwise: those rows are simply not offered.
 */
object OeaTranslate {

    /**
     * The languages ML Kit's on-device translator has models for.
     *
     * Kept as codes rather than names so the mapping is checkable at a glance:
     * every one of these is a tag [OeaSubtitleLanguages] can resolve.
     */
    val TAGS = listOf(
        "af", "ar", "be", "bg", "bn", "ca", "cs", "cy", "da", "de",
        "el", "en", "eo", "es", "et", "fa", "fi", "fr", "ga", "gl",
        "gu", "he", "hi", "hr", "ht", "hu", "id", "is", "it", "ja",
        "kn", "ko", "lt", "lv", "mk", "mr", "ms", "mt", "nl", "no",
        "pl", "pt", "ro", "ru", "sk", "sl", "sq", "sv", "sw", "ta",
        "te", "th", "tl", "tr", "uk", "ur", "vi", "zh"
    )

    /** The languages VIP can offer, named and in alphabetical order. */
    fun languages(): List<OeaSubtitleLanguages.Language> =
        TAGS.mapNotNull { OeaSubtitleLanguages.byTag(it) }.distinctBy { it.tag }.sortedBy { it.english }

    /**
     * The code to hand ML Kit for a VIP tag.
     *
     * Three things happen here. A Chinese tag loses its script because ML Kit
     * has one model for both; Norwegian is "no" whichever written form the
     * track used; and the legacy codes VIP still reads in file names (`iw`,
     * `in`, `ji`, `fil`) become the modern ones the models answer to.
     */
    fun code(tag: String?): String? {
        val primary = tag?.trim()?.lowercase(Locale.ROOT)?.substringBefore('-')?.substringBefore('_')
        if (primary.isNullOrEmpty()) return null
        val modern = when (primary) {
            "iw" -> "he"
            "in" -> "id"
            "ji" -> "yi"
            "fil", "tgl" -> "tl"
            "nb", "nn" -> "no"
            "zh" -> "zh"
            "cmn" -> "zh"
            else -> primary
        }
        return if (modern in TAGS) modern else null
    }

    /** True when VIP can translate [tag] on this phone. */
    fun supports(tag: String?): Boolean = code(tag) != null

    /** The language rows to show for a track in [source]: everything it is not. */
    fun targets(source: OeaSubtitleLanguages.Language?): List<OeaSubtitleLanguages.Language> {
        val from = code(source?.tag)
        return languages().filter { code(it.tag) != from }
    }

    /**
     * A translated track: same cues, same clock, new words.
     *
     * Pure, so the one thing that must never be wrong about a translation — the
     * timing — is pinned by a test rather than by a device. A cue the translator
     * returned nothing for keeps its original words instead of becoming a blank
     * rectangle over somebody's face.
     */
    fun translated(
        track: OeaSubtitles.Track,
        target: OeaSubtitleLanguages.Language,
        texts: List<String>
    ): OeaSubtitles.Track {
        val cues = track.cues.mapIndexed { index, cue ->
            val translated = texts.getOrNull(index)?.trim()?.replace(Regex("\\s+"), " ").orEmpty()
            if (translated.isEmpty()) cue else cue.copy(text = OeaCaptions.wrap(translated))
        }
        return track.copy(
            name = "${track.name} · ${target.badge}",
            cues = cues,
            language = target,
            forced = false,
            sdh = false,
            origin = null
        )
    }

    /**
     * Translates [track] into [target], blocking the calling thread.
     *
     * Never call this on the UI thread: the first call for a language downloads
     * a model over the network. Returns null when the phone cannot do it, which
     * the caller should say out loud rather than show as an empty track.
     */
    fun translate(track: OeaSubtitles.Track, target: OeaSubtitleLanguages.Language): OeaSubtitles.Track? {
        val source = code(track.language?.tag) ?: return null
        val destination = code(target.tag) ?: return null
        if (source == destination) return null
        val options = runCatching {
            TranslatorOptions.Builder()
                .setSourceLanguage(source)
                .setTargetLanguage(destination)
                .build()
        }.getOrNull() ?: return null
        val translator = runCatching { Translation.getClient(options) }.getOrNull() ?: return null
        return try {
            val ready = runCatching {
                Tasks.await(translator.downloadModelIfNeeded(), MODEL_SECONDS, TimeUnit.SECONDS)
            }.isSuccess
            if (!ready) return null
            val texts = track.cues.map { cue ->
                // One cue at a time: joining them into a paragraph lets the model
                // reflow the sentences, and then the words no longer belong to
                // the moments they were timed for.
                runCatching {
                    Tasks.await(translator.translate(cue.text.replace('\n', ' ')), CUE_SECONDS, TimeUnit.SECONDS)
                }.getOrDefault("")
            }
            translated(track, target, texts)
        } catch (error: Throwable) {
            null
        } finally {
            runCatching { translator.close() }
        }
    }

    /** What the sheet says before the first model for a language is fetched. */
    const val DOWNLOAD_NOTE = "The first translation into a language downloads a model of about 30 MB from Play services. Everything after that runs on the phone."

    private const val MODEL_SECONDS = 180L
    private const val CUE_SECONDS = 45L
}
