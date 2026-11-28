package com.oea.launcher.miniapps

import java.text.Normalizer
import java.util.Locale

/**
 * The languages VIP can put under a picture, and how it recognises them.
 *
 * A subtitle file almost never says what language it is: SubRip has no header for
 * it and WebVTT's is optional. What files *do* carry is their name — every
 * subtitle site and every downloader writes `Movie.en.srt`, `Movie.English.srt`,
 * `Movie.pt-BR.srt` or `Movie.eng.forced.srt` — so the name is where the answer
 * has to come from, with two fallbacks for the files that carry nothing: the
 * text's own script (an Arabic cue cannot be Swedish) and, last, "unknown",
 * which is a label VIP is happy to show rather than a guess it cannot support.
 *
 * Two things here are deliberately *not* claims of language detection:
 *  * [scriptOf] answers which writing system a run of text uses. Cyrillic is
 *    Russian, Ukrainian and Bulgarian; Latin is English, Yoruba, French and
 *    eighty others. It is used only to tell right-to-left from left-to-right
 *    and to name an obvious script, never to say "this is French".
 *  * [resolve] handles the codes and names people write in filenames — ISO
 *    639-1, the two 639-2 codes, BCP-47 regions, and the English/native names.
 */
object OeaSubtitleLanguages {

    /**
     * One language. [tag] is the BCP-47 code VIP stores, normalised to lower
     * case; [english] and [native] are what the picker shows.
     */
    class Language internal constructor(
        val tag: String,
        val english: String,
        val native: String,
        val aliases: List<String> = emptyList(),
        val rightToLeft: Boolean = false
    ) {
        /** The two-letter part — what a `EN` badge and a filename suffix use. */
        val primary: String get() = tag.substringBefore('-')

        /** The badge the subtitle pill and the track rows carry. */
        val badge: String get() = primary.uppercase(Locale.ROOT)

        /**
         * "Français · French"; a language whose two names are the same word is
         * printed once. Accents count as a difference: "Yorùbá · Yoruba" is how a
         * reader of it should see it.
         */
        val label: String
            get() = if (native.equals(english, ignoreCase = true)) english else "$native · $english"

        /**
         * Chinese, Japanese and Korean are drawn wider per character than Latin
         * text at the same point size, so the player gives them a little more.
         */
        val wideGlyphs: Boolean
            get() = tag.startsWith("zh") || tag == "ja" || tag == "ko"

        override fun toString(): String = tag

        override fun equals(other: Any?): Boolean = other is Language && other.tag == tag

        override fun hashCode(): Int = tag.hashCode()
    }

    // ------------------------------------------------------------- the catalogue

    /**
     * Languages offered in the picker. It is not the full ISO register: it is the
     * set of languages subtitle files actually ship in, plus every language OEA's
     * own copy is written in, so a Yoruba or Hausa track is a first-class choice
     * and not an "other".
     */
    private val CATALOGUE: List<Language> = listOf(
        Language("af", "Afrikaans", "Afrikaans", listOf("afr")),
        Language("am", "Amharic", "አማርኛ", listOf("amh")),
        Language("ar", "Arabic", "العربية", listOf("ara"), rightToLeft = true),
        Language("az", "Azerbaijani", "Azərbaycanca", listOf("aze")),
        Language("ba", "Bashkir", "Башҡортса", listOf("bak")),
        Language("be", "Belarusian", "Беларуская", listOf("bel")),
        Language("bg", "Bulgarian", "Български", listOf("bul")),
        Language("bn", "Bengali", "বাংলা", listOf("ben")),
        Language("bo", "Tibetan", "བོད་སྐད་", listOf("tib", "bod")),
        Language("bs", "Bosnian", "Bosanski", listOf("bos")),
        Language("ca", "Catalan", "Català", listOf("cat")),
        Language("ce", "Chechen", "Нохчийн", listOf("che")),
        Language("ckb", "Kurdish (Sorani)", "کوردی", listOf("sorani"), rightToLeft = true),
        Language("cs", "Czech", "Čeština", listOf("cze", "ces")),
        Language("cv", "Chuvash", "Чӑвашла", listOf("chv")),
        Language("cy", "Welsh", "Cymraeg", listOf("wel", "cym")),
        Language("da", "Danish", "Dansk", listOf("dan")),
        Language("de", "German", "Deutsch", listOf("ger", "deu")),
        Language("dv", "Dhivehi", "ދިވެހި", listOf("div"), rightToLeft = true),
        Language("el", "Greek", "Ελληνικά", listOf("gre", "ell")),
        Language("en", "English", "English", listOf("eng")),
        Language("eo", "Esperanto", "Esperanto", listOf("epo")),
        Language("es", "Spanish", "Español", listOf("spa")),
        Language("et", "Estonian", "Eesti", listOf("est")),
        Language("eu", "Basque", "Euskara", listOf("baq", "eus")),
        Language("fa", "Persian", "فارسی", listOf("per", "fas"), rightToLeft = true),
        Language("ff", "Fula", "Fulfulde", listOf("ful")),
        Language("fi", "Finnish", "Suomi", listOf("fin")),
        Language("fil", "Filipino", "Filipino", listOf("tl", "tgl")),
        Language("fo", "Faroese", "Føroyskt", listOf("fao")),
        Language("fr", "French", "Français", listOf("fre", "fra")),
        Language("fy", "Western Frisian", "Frysk", listOf("fry")),
        Language("ga", "Irish", "Gaeilge", listOf("gle")),
        Language("gd", "Scottish Gaelic", "Gàidhlig", listOf("gla")),
        Language("gl", "Galician", "Galego", listOf("glg")),
        Language("gn", "Guarani", "Avañe'ẽ", listOf("grn")),
        Language("gu", "Gujarati", "ગુજરાતી", listOf("guj")),
        Language("ha", "Hausa", "Hausa", listOf("hau")),
        Language("he", "Hebrew", "עברית", listOf("heb", "iw"), rightToLeft = true),
        Language("hi", "Hindi", "हिन्दी", listOf("hin")),
        Language("hr", "Croatian", "Hrvatski", listOf("hrv")),
        Language("ht", "Haitian Creole", "Kreyòl ayisyen", listOf("hat")),
        Language("hu", "Hungarian", "Magyar", listOf("hun")),
        Language("hy", "Armenian", "Հայերեն", listOf("arm", "hye")),
        Language("id", "Indonesian", "Bahasa Indonesia", listOf("ind", "in")),
        Language("ig", "Igbo", "Igbo", listOf("ibo")),
        Language("is", "Icelandic", "Íslenska", listOf("ice", "isl")),
        Language("it", "Italian", "Italiano", listOf("ita")),
        Language("ja", "Japanese", "日本語", listOf("jpn")),
        Language("jv", "Javanese", "Basa Jawa", listOf("jav", "jw")),
        Language("ka", "Georgian", "ქართული", listOf("geo", "kat")),
        Language("kk", "Kazakh", "Қазақша", listOf("kaz")),
        Language("km", "Khmer", "ខ្មែរ", listOf("khm")),
        Language("kn", "Kannada", "ಕನ್ನಡ", listOf("kan")),
        Language("ko", "Korean", "한국어", listOf("kor")),
        Language("ku", "Kurdish", "Kurdî", listOf("kur")),
        Language("ky", "Kyrgyz", "Кыргызча", listOf("kir")),
        Language("lb", "Luxembourgish", "Lëtzebuergesch", listOf("ltz")),
        Language("ln", "Lingala", "Lingála", listOf("lin")),
        Language("lo", "Lao", "ລາວ", listOf("lao")),
        Language("lt", "Lithuanian", "Lietuvių", listOf("lit")),
        Language("lv", "Latvian", "Latviešu", listOf("lav")),
        Language("mg", "Malagasy", "Malagasy", listOf("mlg")),
        Language("mk", "Macedonian", "Македонски", listOf("mac", "mkd")),
        Language("ml", "Malayalam", "മലയാളം", listOf("mal")),
        Language("mn", "Mongolian", "Монгол", listOf("mon")),
        Language("mr", "Marathi", "मराठी", listOf("mar")),
        Language("ms", "Malay", "Bahasa Melayu", listOf("may", "msa")),
        Language("mt", "Maltese", "Malti", listOf("mlt")),
        Language("my", "Burmese", "မြန်မာ", listOf("bur", "mya")),
        Language("nb", "Norwegian", "Norsk", listOf("nor", "no", "nn")),
        Language("ne", "Nepali", "नेपाली", listOf("nep")),
        Language("nl", "Dutch", "Nederlands", listOf("dut", "nld")),
        Language("om", "Oromo", "Afaan Oromoo", listOf("orm")),
        Language("pa", "Punjabi", "ਪੰਜਾਬੀ", listOf("pan")),
        Language("pl", "Polish", "Polski", listOf("pol")),
        Language("ps", "Pashto", "پښتو", listOf("pus"), rightToLeft = true),
        Language("pt", "Portuguese", "Português", listOf("por")),
        Language("pt-BR", "Brazilian Portuguese", "Português (Brasil)", listOf("pt-br", "ptbr")),
        Language("qu", "Quechua", "Runasimi", listOf("que")),
        Language("ro", "Romanian", "Română", listOf("rum", "ron", "mo")),
        Language("ru", "Russian", "Русский", listOf("rus")),
        Language("rw", "Kinyarwanda", "Ikinyarwanda", listOf("kin")),
        Language("sd", "Sindhi", "سنڌي", listOf("snd"), rightToLeft = true),
        Language("si", "Sinhala", "සිංහල", listOf("sin")),
        Language("sk", "Slovak", "Slovenčina", listOf("slo", "slk")),
        Language("sl", "Slovenian", "Slovenščina", listOf("slv")),
        Language("sn", "Shona", "chiShona", listOf("sna")),
        Language("so", "Somali", "Soomaali", listOf("som")),
        Language("sq", "Albanian", "Shqip", listOf("alb", "sqi")),
        Language("sr", "Serbian", "Српски", listOf("srp")),
        Language("st", "Sesotho", "Sesotho", listOf("sot")),
        Language("sv", "Swedish", "Svenska", listOf("swe")),
        Language("sw", "Swahili", "Kiswahili", listOf("swa")),
        Language("ta", "Tamil", "தமிழ்", listOf("tam")),
        Language("te", "Telugu", "తెలుగు", listOf("tel")),
        Language("tg", "Tajik", "Тоҷикӣ", listOf("tgk")),
        Language("th", "Thai", "ไทย", listOf("tha")),
        Language("ti", "Tigrinya", "ትግርኛ", listOf("tir")),
        Language("tn", "Tswana", "Setswana", listOf("tsn")),
        Language("tr", "Turkish", "Türkçe", listOf("tur")),
        Language("tt", "Tatar", "Татарча", listOf("tat")),
        Language("ug", "Uyghur", "ئۇيغۇرچە", listOf("uig"), rightToLeft = true),
        Language("uk", "Ukrainian", "Українська", listOf("ukr")),
        Language("ur", "Urdu", "اردو", listOf("urd"), rightToLeft = true),
        Language("uz", "Uzbek", "Oʻzbekcha", listOf("uzb")),
        Language("vi", "Vietnamese", "Tiếng Việt", listOf("vie")),
        Language("wo", "Wolof", "Wolof", listOf("wol")),
        Language("xh", "Xhosa", "isiXhosa", listOf("xho")),
        Language("yi", "Yiddish", "ייִדיש", listOf("yid", "ji"), rightToLeft = true),
        Language("yo", "Yoruba", "Yorùbá", listOf("yor")),
        Language("zh-Hans", "Chinese (Simplified)", "简体中文", listOf("zh", "zh-cn", "zh-sg", "chi", "zho")),
        Language("zh-Hant", "Chinese (Traditional)", "繁體中文", listOf("zh-tw", "zh-hk", "zh-mo")),
        Language("zu", "Zulu", "isiZulu", listOf("zul"))
    )

    /** Every language in the picker, alphabetical by its English name. */
    val all: List<Language> = CATALOGUE.sortedWith(compareBy<Language>({ it.english }, { it.tag }))

    private val tagIndex: Map<String, Language> = LinkedHashMap<String, Language>().apply {
        CATALOGUE.forEach { language ->
            putIfAbsent(language.tag, language)
            language.aliases.forEach { putIfAbsent(it.lowercase(Locale.ROOT), language) }
        }
    }

    private val nameIndex: Map<String, Language> = LinkedHashMap<String, Language>().apply {
        CATALOGUE.forEach { language ->
            putIfAbsent(nameKey(language.english), language)
            putIfAbsent(nameKey(language.native), language)
        }
    }

    /**
     * Legacy and region spellings that have to land on one entry: the old Java
     * codes Android still hands out (`iw`, `in`, `ji`), Chinese regions before
     * they mean a script, and `no`/`nn` which both mean the Norwegian VIP ships.
     */
    private val SPELLINGS: Map<String, String> = mapOf(
        "iw" to "he",
        "in" to "id",
        "ji" to "yi",
        "jw" to "jv",
        "mo" to "ro",
        "no" to "nb",
        "nn" to "nb",
        "zh-cn" to "zh-hans",
        "zh-sg" to "zh-hans",
        "zh-my" to "zh-hans",
        "zh-hans" to "zh-hans",
        "zh-tw" to "zh-hant",
        "zh-hk" to "zh-hant",
        "zh-mo" to "zh-hant",
        "zh-hant" to "zh-hant",
        "pt-pt" to "pt",
        "pt-br" to "pt-br",
        "sr-latn" to "sr",
        "sr-cyrl" to "sr",
        "nb-no" to "nb"
    )

    // ------------------------------------------------------------------ lookups

    /**
     * Turns a code, a region or a name into a language, or null.
     *
     * Accepts `en`, `EN`, `eng`, `en-GB`, `fr-CA`, `fre`, `fra`, `zh-CN`,
     * `iw`, `English`, `Français`, `Yorùbá`, `yoruba`, `brazilian portuguese`.
     */
    fun resolve(raw: String?): Language? {
        val cleaned = clean(raw) ?: return null
        val spelled = SPELLINGS[cleaned]
        if (spelled != null) tagIndex[spelled]?.let { return it }
        tagIndex[cleaned]?.let { return it }
        nameIndex[nameKey(cleaned)]?.let { return it }
        // Region and script subtags: `fr-CA` is French, `en-US` is English. Only
        // the primary subtag is used, which is the only thing BCP-47 promises.
        val primary = cleaned.substringBefore('-')
        if (primary != cleaned) {
            val base = SPELLINGS[primary]
            if (base != null) tagIndex[base]?.let { return it }
            tagIndex[primary]?.let { return it }
        }
        return null
    }

    /** The language VIP uses when the setting says "the phone's own". */
    fun deviceDefault(): Language? {
        val locale = Locale.getDefault()
        return resolve(locale.language) ?: resolve("${locale.language}-${locale.country}")
    }

    /**
     * A catalogue entry by a tag VIP stored. The same lookup as [resolve], so a
     * stored `en-GB` still finds English and an old stored code still works after
     * the catalogue is renamed.
     */
    fun byTag(tag: String?): Language? = resolve(tag)

    // ------------------------------------------------------------------- script

    /**
     * Which writing system [text] is mostly written in, expressed as the
     * language that script immediately implies. Null for Latin text, where the
     * answer would be a coin toss between English, Yoruba, French and dozens
     * more — VIP says "unknown language" there instead of picking one.
     */
    fun scriptOf(text: String): Language? {
        if (text.isBlank()) return null
        val counts = HashMap<String, Int>()
        var letters = 0
        for (ch in text) {
            val script = scriptOfChar(ch) ?: continue
            letters++
            counts[script] = (counts[script] ?: 0) + 1
        }
        if (letters < 4) return null
        // Japanese is Han plus kana: kana wins, because Han alone cannot say which.
        counts["ja"]?.let { kana ->
            val han = counts["zh-Hans"] ?: 0
            if (kana * 4 >= han) return byTag("ja")
        }
        val best = counts.entries.maxByOrNull { it.value } ?: return null
        if (best.value * 2 < letters) return null
        return resolve(best.key)
    }

    private fun scriptOfChar(ch: Char): String? {
        val code = ch.code
        return when {
            code in 0x0590..0x05FF || code in 0xFB1D..0xFB4F -> "he"
            code in 0x0600..0x06FF || code in 0x0750..0x077F || code in 0x08A0..0x08FF -> "ar"
            code in 0x0700..0x074F -> "sd"
            code in 0x0780..0x07BF -> "dv"
            code in 0x1200..0x137F -> "am"
            code in 0x0400..0x052F -> "ru"
            code in 0x0370..0x03FF -> "el"
            code in 0x0530..0x058F -> "hy"
            code in 0x10A0..0x10FF -> "ka"
            code in 0x0900..0x097F -> "hi"
            code in 0x0980..0x09FF -> "bn"
            code in 0x0A00..0x0A7F -> "pa"
            code in 0x0A80..0x0AFF -> "gu"
            code in 0x0B80..0x0BFF -> "ta"
            code in 0x0C00..0x0C7F -> "te"
            code in 0x0C80..0x0CFF -> "kn"
            code in 0x0D00..0x0D7F -> "ml"
            code in 0x0D80..0x0DFF -> "si"
            code in 0x0E00..0x0E7F -> "th"
            code in 0x0E80..0x0EFF -> "lo"
            code in 0x0F00..0x0FFF -> "bo"
            code in 0x1000..0x109F -> "my"
            code in 0x1780..0x17FF -> "km"
            code in 0x1100..0x11FF || code in 0x3130..0x318F || code in 0xAC00..0xD7AF -> "ko"
            code in 0x3040..0x30FF -> "ja"
            code in 0x3400..0x4DBF || code in 0x4E00..0x9FFF -> "zh-Hans"
            else -> null
        }
    }

    // -------------------------------------------------------------------- names

    /**
     * Case- and accent-insensitive key: "Yorùbá" and "YORUBA" are one key, so a
     * file called `Movie.yoruba.srt` matches the catalogue entry written in the
     * language's own spelling.
     */
    fun nameKey(value: String): String {
        val folded = Normalizer.normalize(value, Normalizer.Form.NFD)
        val builder = StringBuilder(folded.length)
        for (ch in folded) {
            when {
                ch.code in 0x0300..0x036F -> Unit             // combining accents
                ch.isLetterOrDigit() -> builder.append(ch.lowercaseChar())
            }
        }
        return builder.toString()
    }

    /** Strips quoting and separators, lower-cases, and keeps `-` inside tags. */
    private fun clean(raw: String?): String? {
        val text = raw?.trim() ?: return null
        val value = text.lowercase(Locale.ROOT).replace('_', '-')
        if (value.length > 40) return null
        return value.trim('-', '.', ' ', '[', ']', '(', ')').takeIf { it.isNotBlank() }
    }
}
