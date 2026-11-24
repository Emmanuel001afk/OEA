package com.oea.launcher.voice

/**
 * What "open calculator" and "go to Ikeja" mean — pure, so it is tested instead of
 * hoped for.
 *
 * The grammar used to live inside the recogniser's callback, where it could only be
 * checked by speaking into a phone. Two things were wrong with the order it ran in:
 *
 *  * "go to" was listed as an app opener *and* as a navigation phrase, and the app
 *    opener ran first, so "go to Ikeja" opened nothing and never reached maps;
 *  * "alarm" was matched with `contains`, so "cancel my alarm" set a new one.
 *
 * Both are decided here, without a Context: the caller supplies the installed app
 * names, and the answer says which command it is and what the query is. Time and
 * duration parsing live here too, because "set a timer for ten minutes" has to be
 * understood before any Android API is called.
 */
internal object OeaVoiceGrammar {

    enum class Kind {
        OPEN_APP, SEARCH_WEB, SEARCH_APP, CALL_CONTACT, MESSAGE_CONTACT,
        SET_ALARM, SET_TIMER, PLAY_MUSIC, NAVIGATE, TOGGLE_FLASHLIGHT,
        TOGGLE_DND, TOGGLE_LOWMODE, CHANGE_MODE, CHANGE_WALLPAPER,
        CHANGE_THEME, TAKE_PHOTO, TAKE_VIDEO, OPEN_SETTINGS, UNKNOWN
    }

    data class Match(val kind: Kind, val query: String, val confidence: Float)

    private val POLITE = listOf("please ", "hey oea ", "ok oea ", "oea ")

    /** Words that mean "do it now"; without one, an alarm/timer sentence is not a command. */
    private val IMPERATIVE = listOf("set ", "create ", "start ", "add ", "make ", "wake me", "remind me")

    /** Words that mean the opposite: never turn these into a new alarm. */
    private val NEGATIVE = listOf("cancel", "disable", "delete", "remove", "turn off", "stop", "clear")

    private val MODES = listOf(
        "classic", "liquid", "tide", "depth", "ink", "aurora", "glass", "mesh", "paper",
        "slate", "bloom", "study", "music", "sleeping", "focus", "gaming", "reading",
        "work", "driving", "low"
    )

    private val NUMBER_WORDS = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
        "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14,
        "fifteen" to 15, "twenty" to 20, "thirty" to 30, "forty" to 40,
        "forty five" to 45, "fifty" to 50, "sixty" to 60, "ninety" to 90
    )

    /**
     * Trims the politeness and punctuation a recogniser adds, keeping the words' case:
     * "open Tunde" must still be Tunde when it reaches the contact lookup.
     */
    fun normalizeKeepCase(raw: String): String {
        var text = raw.trim().replace(Regex("\\s+"), " ")
        text = text.trimEnd('.', ',', '!', '?')
        val lower = text.lowercase()
        for (p in POLITE) {
            if (lower == p.trim() || lower.startsWith(p)) { text = text.substring(p.length); break }
        }
        return text.trim()
    }

    /** The same, folded to lowercase, for matching only. */
    fun normalize(raw: String): String = normalizeKeepCase(raw).lowercase()

    /** Parses one spoken instruction. [appNames] are the installed apps, lowercased by the caller. */
    fun parse(raw: String, appNames: List<String> = emptyList()): Match? {
        val cased = normalizeKeepCase(raw)
        val text = cased.lowercase()
        if (text.isBlank()) return null

        // The remainder after a prefix, still in the case it was spoken.
        fun rest(prefix: String): String = cased.substring(prefix.length.coerceAtMost(cased.length)).trim()

        // "go to X": an app if one is called X, otherwise a place to navigate to.
        for (prefix in listOf("go to ", "take me to ")) {
            if (text.startsWith(prefix)) {
                val query = rest(prefix)
                if (query.isEmpty()) return null
                val app = matchingApp(query, appNames)
                return if (app != null) Match(Kind.OPEN_APP, app, 0.9f)
                else Match(Kind.NAVIGATE, query, 0.8f)
            }
        }

        for (prefix in listOf("navigate to ", "directions to ")) {
            if (text.startsWith(prefix)) {
                val query = rest(prefix)
                if (query.isNotEmpty()) return Match(Kind.NAVIGATE, query, 0.85f)
            }
        }

        for (prefix in listOf("open ", "launch ", "start ")) {
            if (text.startsWith(prefix)) {
                val query = rest(prefix)
                if (query.isEmpty()) return null
                return Match(Kind.OPEN_APP, matchingApp(query, appNames) ?: query, 0.9f)
            }
        }

        for (prefix in listOf("search for ", "search ", "look up ", "find ", "google ")) {
            if (text.startsWith(prefix)) {
                val query = rest(prefix)
                if (query.isNotEmpty()) return Match(Kind.SEARCH_WEB, query, 0.85f)
            }
        }

        for (prefix in listOf("call ", "phone ", "dial ")) {
            if (text.startsWith(prefix)) {
                val query = rest(prefix)
                if (query.isNotEmpty()) return Match(Kind.CALL_CONTACT, query, 0.85f)
            }
        }

        for (prefix in listOf("send a message to ", "send text to ", "message ", "text ", "sms ")) {
            if (text.startsWith(prefix)) {
                val query = rest(prefix)
                if (query.isNotEmpty()) return Match(Kind.MESSAGE_CONTACT, query, 0.8f)
            }
        }

        // Alarms and timers need an instruction. "Cancel my alarm" is not one — and it
        // is not a web search either, so it is reported as a command OEA cannot run
        // rather than turned into a query.
        val imperative = IMPERATIVE.any { text.startsWith(it) || text.contains(" $it".trim()) }
        val negative = NEGATIVE.any { text.contains(it) }
        val aboutAlarms = text.contains("alarm") || text.contains("timer") ||
            text.contains("do not disturb") || text.contains("dnd")
        if (negative && aboutAlarms) return Match(Kind.UNKNOWN, text, 0.3f)
        if (imperative) {
            if (text.contains("timer") || text.contains("countdown")) {
                return Match(Kind.SET_TIMER, text, 0.8f)
            }
            if (text.contains("alarm") || text.contains("wake me") || text.contains("remind me")) {
                return Match(Kind.SET_ALARM, text, 0.8f)
            }
        }

        if (text.startsWith("play ")) {
            val query = rest("play ")
            return Match(Kind.PLAY_MUSIC, query, 0.8f)
        }

        if (text.contains("flashlight") || text.contains("torch")) return Match(Kind.TOGGLE_FLASHLIGHT, text, 0.85f)
        if (text.contains("do not disturb") || text.contains("dnd") || text == "silence" || text.contains("silent mode")) {
            return Match(Kind.TOGGLE_DND, text, 0.85f)
        }
        if (text.contains("low mode") || text.contains("ultra power saving") || text.contains("battery saver") || text.contains("power saving")) {
            return Match(Kind.TOGGLE_LOWMODE, text, 0.85f)
        }

        for (mode in MODES) {
            if (text.contains("$mode mode") || text == mode || text == "set $mode" || text.contains("change to $mode")) {
                return Match(Kind.CHANGE_MODE, mode, 0.9f)
            }
        }

        if (text.contains("wallpaper") || text.contains("background")) return Match(Kind.CHANGE_WALLPAPER, text, 0.7f)
        if (text.contains("theme")) return Match(Kind.CHANGE_THEME, text, 0.7f)
        if (text.contains("take video") || text.contains("record video")) return Match(Kind.TAKE_VIDEO, text, 0.8f)
        if (text.contains("take a photo") || text.contains("take photo") || text.contains("take picture") || text.contains("selfie")) {
            return Match(Kind.TAKE_PHOTO, text, 0.8f)
        }
        if (text.contains("settings")) return Match(Kind.OPEN_SETTINGS, text, 0.7f)

        // Anything else is a web search — the honest fallback, announced as a search.
        return Match(Kind.SEARCH_WEB, cased, 0.5f)
    }

    /**
     * The installed app a spoken name refers to, or null.
     *
     * Exact name first, then a name that starts with what was said, then containment —
     * so "open camera" prefers "Camera" over "Camera Tools Pro".
     */
    fun matchingApp(query: String, appNames: List<String>): String? {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return null
        return appNames.firstOrNull { it.equals(q, ignoreCase = true) }
            ?: appNames.firstOrNull { it.lowercase().startsWith(q) }
            ?: appNames.firstOrNull { it.lowercase().contains(q) }
    }

    /** Which way a flashlight sentence points: true = on, false = off, null = toggle. */
    fun flashlightWants(raw: String): Boolean? {
        val words = normalize(raw).split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
        if ("off" in words) return false
        if ("on" in words) return true
        return null
    }

    /**
     * The hour and minute spoken in an alarm sentence, 24-hour, or null.
     *
     * Understands "7", "7:30", "7 30", "7am", "7 pm", "half past seven", "quarter past
     * six" and words up to ninety. Returns pair (hour, minute).
     */
    fun clockTime(text: String): Pair<Int, Int>? {
        val t = normalize(text).replace("a.m", "am").replace("p.m", "pm")
        val half = Regex("\\bhalf past (\\d{1,2}|[a-z ]+?)\\b").find(t)
        val quarter = Regex("\\bquarter past (\\d{1,2}|[a-z ]+?)\\b").find(t)
        if (half != null) {
            val h = number(half.groupValues[1]) ?: return null
            return toHour(h, 30, pm = t.contains("pm") || t.contains("evening") || t.contains("night"))
        }
        if (quarter != null) {
            val h = number(quarter.groupValues[1]) ?: return null
            return toHour(h, 15, pm = t.contains("pm") || t.contains("evening") || t.contains("night"))
        }
        val colon = Regex("\\b(\\d{1,2})[:.](\\d{2})").find(t)
        if (colon != null) {
            val h = colon.groupValues[1].toIntOrNull() ?: return null
            val m = colon.groupValues[2].toIntOrNull() ?: return null
            if (h !in 0..23 || m !in 0..59) return null
            return toHour(h, m, pm = t.contains("pm") || t.contains("evening") || t.contains("night"))
        }
        val flat = Regex("\\b(\\d{1,2}) ?(am|pm)\\b").find(t)
        if (flat != null) {
            val h = flat.groupValues[1].toIntOrNull() ?: return null
            return toHour(h, 0, pm = flat.groupValues[2] == "pm")
        }
        // "wake me at seven", "alarm for 6"
        val worded = Regex("\\b(?:at|for) ([a-z ]+?)(?: o.?clock)?\\b").find(t)
        if (worded != null) {
            val h = number(worded.groupValues[1]) ?: return null
            return toHour(h, 0, pm = t.contains("pm") || t.contains("evening") || t.contains("night"))
        }
        val bare = Regex("\\b(\\d{1,2})\\b").find(t)
        if (bare != null) {
            val h = bare.groupValues[1].toIntOrNull() ?: return null
            return toHour(h, 0, pm = t.contains("pm") || t.contains("evening") || t.contains("night"))
        }
        return null
    }

    /**
     * The length spoken in a timer sentence, in seconds, or null.
     *
     * "10 minutes", "10 min", "ten minutes", "1 hour 30 minutes", "90 seconds".
     */
    fun durationSeconds(text: String): Long? {
        val t = normalize(text)
        var total = 0L
        var found = false
        val part = Regex("(\\d{1,3}|[a-z]+)\\s*(hours?|hrs?|minutes?|mins?|m|seconds?|secs?|s)\\b")
        for (match in part.findAll(t)) {
            val value = number(match.groupValues[1]) ?: continue
            val unit = match.groupValues[2]
            val seconds = when {
                unit.startsWith("h") -> value * 3600L
                unit.startsWith("min") || unit == "m" -> value * 60L
                else -> value.toLong()
            }
            total += seconds
            found = true
        }
        if (!found) {
            // "a timer for ten" — a bare number is minutes, the way phone timers behave.
            val bare = Regex("\\b(?:for|timer) (\\d{1,3}|[a-z]+)\\b").find(t)
            val value = bare?.let { number(it.groupValues[1]) } ?: return null
            total = value * 60L
            found = true
        }
        return if (found && total in 1..24 * 3600L) total else null
    }

    private fun toHour(hour: Int, minute: Int, pm: Boolean): Pair<Int, Int>? {
        if (hour !in 0..23 || minute !in 0..59) return null
        if (pm && hour in 1..11) return (hour + 12) to minute
        if (!pm && hour == 12 && minute == 0) return 0 to 0
        return hour to minute
    }

    private fun number(word: String): Int? {
        val t = word.trim()
        t.toIntOrNull()?.let { return it }
        if (t in NUMBER_WORDS) return NUMBER_WORDS[t]
        // "twenty five", "thirty two"
        val parts = t.split(' ')
        if (parts.size == 2) {
            val tens = NUMBER_WORDS[parts[0]] ?: return null
            val ones = NUMBER_WORDS[parts[1]] ?: return null
            if (tens >= 20 && ones in 0..9) return tens + ones
        }
        return null
    }
}
