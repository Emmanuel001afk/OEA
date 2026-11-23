package com.oea.launcher.search

import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The app drawer's search keys, prepared once per app instead of once per keystroke.
 *
 * The drawer used to normalize every label inside the filter itself: for each key
 * the user typed it ran an ICU decomposition ([Normalizer.normalize] with NFD), a
 * mark-stripping regex replace and a lowercase over every app in the catalogue,
 * then split each label into words and built the initials, then sorted. None of
 * that work depends on the query — only the comparisons at the end do — so it was
 * repeated for nothing on every keystroke, and typing is exactly where a launcher
 * must not spend the frame.
 *
 * This index keeps the normalized label, its words, its initials and its package
 * name per app. A keystroke now costs one normalization of the *query* plus
 * comparisons.
 *
 * The ranking is unchanged, and deliberately so — the order the user has learned
 * is part of the feature:
 *
 * | score | rule |
 * | --- | --- |
 * | 0 | the label starts with the query |
 * | 1 | a word inside the label starts with the query |
 * | 2 | the initials of the words start with the query (two characters or more) |
 * | 3 | the label contains the query |
 * | 4 | the package name contains the query |
 *
 * No match returns null. Ties are broken by launch count and then by label in the
 * caller, because only the drawer knows those.
 *
 * Entries are keyed by app *and* label, so a renamed app can never be served its
 * old keys; the map is bounded so a long session of installs and uninstalls
 * cannot grow it without limit.
 */
class OeaSearchIndex(private val limit: Int = MAX_ENTRIES) {

    /** Everything the filter needs about one app label, computed once. */
    class Entry(
        val normalized: String,
        val words: List<String>,
        val initials: String,
        val packageLower: String,
        val initialChar: Char?
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    /** Compiled once: the previous code built this Regex on every call. */
    private val combiningMarks = Regex("\\p{Mn}+")

    /** Folds a label or a query to a plain lowercase form with the marks removed. */
    fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(combiningMarks, "")
            .lowercase(Locale.getDefault())

    /** The keys for [label], computed on first sight and reused after that. */
    fun entry(key: String, label: String, packageName: String): Entry {
        val cacheKey = key + SEPARATOR + label
        entries[cacheKey]?.let { return it }
        val built = build(label, packageName)
        if (entries.size >= limit) entries.clear()
        entries[cacheKey] = built
        return built
    }

    /**
     * Rank of [entry] against an already-normalized [query], or null when the app
     * does not match at all. See the table in the class comment.
     */
    fun score(entry: Entry, query: String): Int? = when {
        entry.normalized.startsWith(query) -> 0
        entry.words.any { it.startsWith(query) } -> 1
        query.length >= 2 && entry.initials.startsWith(query) -> 2
        entry.normalized.contains(query) -> 3
        entry.packageLower.contains(query) -> 4
        else -> null
    }

    /** The alphabet letter an app sorts under, or null when it has no letter at all. */
    fun initial(entry: Entry): Char? = entry.initialChar

    fun clear() = entries.clear()

    fun size(): Int = entries.size

    private fun build(label: String, packageName: String): Entry {
        val normalized = normalize(label)
        val words = normalized.split(' ', '-', '_', '.').filter { it.isNotEmpty() }
        val first = normalize(label.trim()).firstOrNull()?.uppercaseChar()
        return Entry(
            normalized = normalized,
            words = words,
            initials = words.joinToString("") { it.take(1) },
            packageLower = packageName.lowercase(Locale.ROOT),
            initialChar = when {
                first == null -> null
                first in 'A'..'Z' -> first
                else -> '#'
            }
        )
    }

    private companion object {
        /** Generous for a phone; only reachable after heavy install churn. */
        const val MAX_ENTRIES = 4096
        const val SEPARATOR = '\u0000'
    }
}
