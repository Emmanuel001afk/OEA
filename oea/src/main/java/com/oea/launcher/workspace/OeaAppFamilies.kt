package com.oea.launcher.workspace

/**
 * Works out which installed apps belong together — the "smart" in smart folders.
 *
 * Two versions of this came before, and both were wrong in the same direction: they
 * guessed too much.
 *
 *  * A **written catalogue** of namespaces and package names could only describe the
 *    apps someone remembered to type in.
 *  * **Shared uid and shared signing certificate** sounded stronger, but they are
 *    not: every preinstalled app on a phone shares the platform's signing key, and
 *    many share one of Android's reserved uids (`android.uid.system` and friends).
 *    Grouping on those turned a phone's Camera, SIM Toolkit, Settings and update
 *    service into one "family" — a folder that mixed things that have nothing to do
 *    with each other.
 *
 * What is left is the one signal that means what a folder means: **the publisher
 * namespace**. `com.google.android.*` is Google's, `com.microsoft.*` is Microsoft's,
 * `com.facebook.*` is Meta's. A publisher can only own the namespace its package
 * names sit in, the platform's own namespaces are excluded, and two apps are only a
 * family if at least two of them really are installed. Nothing is written down, and
 * nothing that merely shares a key with the operating system becomes a folder.
 *
 * Labels are used only to *name* a family the namespaces already agreed on — never
 * to decide membership, because a label can be localised or faked.
 *
 * Pure Kotlin: no Android types, so the rules are tested rather than presumed.
 */
internal object OeaAppFamilies {

    /** One installed app, reduced to the facts these rules use. */
    data class Fact(
        /** The workspace key (`package/class`) this app is stored under. */
        val key: String,
        val packageName: String,
        val label: String
    )

    data class Family(
        val id: String,
        val title: String,
        val members: List<String>,
        /** The publisher namespace the family was found by. */
        val namespace: String
    )

    /** Words that make a poor folder title on their own. */
    private val GENERIC = setOf(
        "app", "apps", "the", "my", "mobile", "official", "free", "lite", "pro",
        "plus", "android", "com", "www", "inc", "ltd", "online", "beta"
    )

    /** Namespace segments that describe the platform, not the publisher. */
    private val PLATFORM = setOf("android", "google", "app", "apps", "mobile", "com", "www")

    /**
     * Groups [facts] into publisher families: two or more launchable apps under one
     * `com.<publisher>.<line>` namespace.
     */
    fun discover(facts: List<Fact>, minSize: Int = 2): List<Family> {
        val usable = facts.filter { it.key.isNotBlank() && it.packageName.isNotBlank() }
        if (usable.size < minSize) return emptyList()

        val byNamespace = LinkedHashMap<String, MutableList<Fact>>()
        usable.forEach { fact ->
            val namespace = namespaceOf(fact.packageName) ?: return@forEach
            val group = byNamespace.getOrPut(namespace) { mutableListOf() }
            // One entry per package: two launchable activities of one app are still
            // one app, and a package belongs to exactly one family.
            if (group.none { it.packageName == fact.packageName }) group += fact
        }

        return byNamespace
            .filterValues { group -> group.map { it.packageName }.distinct().size >= minSize }
            .map { (namespace, group) ->
                Family(
                    id = sanitize("ns_$namespace"),
                    title = titleFor(group, namespace),
                    members = group.map { it.key }.distinct(),
                    namespace = namespace
                )
            }
            .sortedWith(compareBy({ it.title.lowercase() }, { it.id }))
    }

    /**
     * `com.transsion.hilauncher` → `com.transsion`; null when the package has no
     * publisher namespace to speak of (two segments only) or sits in the platform's
     * own `com.android.*`, which is the operating system rather than a publisher.
     */
    fun namespaceOf(packageName: String): String? {
        val parts = packageName.split('.').filter { it.isNotBlank() }
        if (parts.size < 3) return null
        if (parts[0] == "com" && parts[1] == "android") return null
        return parts.take(3).joinToString(".")
    }

    /** A folder title for a group, derived from the apps and the namespace. */
    fun titleFor(group: List<Fact>, namespace: String?): String {
        commonLabelPrefix(group.map { it.label })?.let { return it }
        val ns = namespace ?: group.mapNotNull { namespaceOf(it.packageName) }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        if (ns != null) {
            val segment = ns.split('.')
                .filter { part -> part.isNotBlank() && part.lowercase() !in PLATFORM }
                .lastOrNull()
            if (segment != null) {
                val title = titleCase(segment)
                if (title.isNotBlank() && title.lowercase() !in GENERIC) return title
            }
        }
        sharedFirstWord(group.map { it.label })?.let { return it }
        return "Apps"
    }

    /** The words every label starts with: "Google Maps"/"Google Photos" → "Google". */
    fun commonLabelPrefix(labels: List<String>): String? {
        val words = labels.map { label ->
            label.split(' ', '-', '_', ':', ',', '(', ')')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }.filter { it.isNotEmpty() }
        if (words.size < 2) return null
        val first = words.first()
        val shared = ArrayList<String>()
        for (i in first.indices) {
            val word = first[i]
            if (words.all { it.size > i && it[i].equals(word, ignoreCase = true) }) shared += word else break
        }
        if (shared.isEmpty()) return null
        val candidate = shared.joinToString(" ")
        // A single generic word ("Mobile") is not a family name.
        if (shared.size == 1 && candidate.lowercase() in GENERIC) return null
        if (candidate.length < 3) return null
        return candidate
    }

    /** The word most labels begin with, when at least two agree on it. */
    fun sharedFirstWord(labels: List<String>): String? {
        val firstWords = labels.mapNotNull { label ->
            label.split(' ', '-', '_').firstOrNull { it.isNotBlank() }
        }.filter { it.length >= 3 && it.lowercase() !in GENERIC }
        val best = firstWords.groupingBy { it.lowercase() }.eachCount()
            .filterValues { it >= 2 }
            .maxByOrNull { it.value }
            ?: return null
        return titleCase(firstWords.first { it.equals(best.key, ignoreCase = true) })
    }

    fun titleCase(value: String): String = value.split('.', '-', '_', ' ')
        .filter { it.isNotBlank() }
        .joinToString(" ") { word ->
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }

    /** Ids end up in preference keys and folder ids: lowercase, no punctuation. */
    fun sanitize(id: String): String = id.lowercase()
        .map { if (it.isLetterOrDigit() || it == '_') it else '_' }
        .joinToString("")
        .trim('_')
        .ifBlank { "family" }
}
