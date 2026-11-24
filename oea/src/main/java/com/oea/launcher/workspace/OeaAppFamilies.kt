package com.oea.launcher.workspace

/**
 * Works out which installed apps belong together — the "smart" in smart folders.
 *
 * The folder pass used to read a catalogue written by hand: a fixed list of
 * namespaces and package names. That list can only describe the apps someone
 * remembered to write down, so an app family nobody typed in never got a folder,
 * while a package that merely *looked* like one did. The phone already knows the
 * answer, so this asks it instead:
 *
 *  * **Shared UID** — two apps installed under one Android user id are, by the
 *    system's own rules, one developer family (they share storage and can read each
 *    other's data). This cannot be claimed by an app that does not hold that id.
 *  * **Shared signing certificate** — apps signed with the same key belong to the
 *    same publisher, whatever they are called and whatever namespace they use.
 *  * **Shared namespace** — `com.transsion.*`, `com.microsoft.*`: three segments of
 *    package name, which is how publishers lay out their own apps.
 *
 * All three come from the phone. Nothing here reads a label to decide membership,
 * because a label can be localised or faked; labels are only used to *name* a
 * family that the identifiers already agreed on.
 *
 * Pure Kotlin: no Android types, so the rules are tested rather than presumed.
 */
internal object OeaAppFamilies {

    /** One installed app, reduced to the facts these rules use. */
    data class Fact(
        /** The workspace key (`package/class`) this app is stored under. */
        val key: String,
        val packageName: String,
        val label: String,
        /** Android uid; 0 when unknown. Apps sharing a uid are one publisher. */
        val uid: Int = 0,
        /** Signing certificate digest; blank when unknown. */
        val signature: String = ""
    )

    enum class Signal { SHARED_UID, SHARED_SIGNATURE, NAMESPACE }

    data class Family(
        val id: String,
        val title: String,
        val members: List<String>,
        val signal: Signal
    )

    /** Words that make a poor folder title on their own. */
    private val GENERIC = setOf(
        "app", "apps", "the", "my", "mobile", "official", "free", "lite", "pro",
        "plus", "android", "com", "www", "inc", "ltd", "online", "beta"
    )

    /** Namespace segments that describe the platform, not the publisher. */
    private val PLATFORM = setOf("android", "google", "app", "apps", "mobile", "com", "www")

    /**
     * Groups [facts] into families.
     *
     * [signatureOf] is consulted only for apps the first two signals did not group,
     * because reading a signing certificate is a real PackageManager call; on a phone
     * where shared UIDs already answer the question it is never asked.
     */
    fun discover(
        facts: List<Fact>,
        signatureOf: ((String) -> String?)? = null,
        minSize: Int = 2
    ): List<Family> {
        val usable = facts.filter { it.key.isNotBlank() && it.packageName.isNotBlank() }
        if (usable.size < minSize) return emptyList()

        val taken = LinkedHashSet<String>()
        val out = ArrayList<Family>()

        fun add(signal: Signal, id: String, group: List<Fact>) {
            // One family per package: an app with two launchable activities must not
            // appear twice, and a package must not straddle two families.
            val fresh = group.filterNot { it.packageName in taken }
            val packages = fresh.map { it.packageName }.distinct()
            if (packages.size < minSize) return
            taken += packages
            out += Family(
                id = sanitize(id),
                title = titleFor(fresh, null),
                members = fresh.map { it.key },
                signal = signal
            )
        }

        // 1. Shared uid.
        usable.filter { it.uid > 0 }
            .groupBy { it.uid }
            .forEach { (uid, group) -> add(Signal.SHARED_UID, "uid$uid", group) }

        // 2. Shared namespace, for what the uid pass did not cover.
        usable.filterNot { it.packageName in taken }
            .mapNotNull { fact -> namespaceOf(fact.packageName)?.let { it to fact } }
            .groupBy({ it.first }, { it.second })
            .forEach { (namespace, group) ->
                add(Signal.NAMESPACE, "ns_$namespace", group)
            }

        // 3. Shared signing certificate, last: it costs a PackageManager call.
        if (signatureOf != null) {
            val leftovers = usable.filterNot { it.packageName in taken }
            if (leftovers.size >= minSize) {
                leftovers.map { fact ->
                    val sig = fact.signature.ifBlank { runCatching { signatureOf(fact.packageName) }.getOrNull().orEmpty() }
                    sig to fact
                }.filter { it.first.isNotBlank() }
                    .groupBy({ it.first }, { it.second })
                    .forEach { (sig, group) ->
                        add(Signal.SHARED_SIGNATURE, "sig_${sig.take(12)}", group)
                    }
            }
        }

        return out.sortedWith(compareBy({ it.title.lowercase() }, { it.id }))
    }

    /**
     * `com.transsion.hilauncher` → `com.transsion`; null when the package has no
     * publisher namespace to speak of (`com.example` alone, or a two-segment name).
     */
    fun namespaceOf(packageName: String): String? {
        val parts = packageName.split('.').filter { it.isNotBlank() }
        if (parts.size < 3) return null
        // Never group on the platform's own namespace: com.android.* is not a family.
        if (parts[0] == "com" && parts[1] == "android") return null
        return parts.take(3).joinToString(".")
    }

    /** A folder title for a group, derived from the apps themselves. */
    fun titleFor(group: List<Fact>, namespace: String?): String {
        commonLabelPrefix(group.map { it.label })?.let { return it }
        val ns = namespace ?: group.mapNotNull { namespaceOf(it.packageName) }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        ns?.let { name ->
            val segment = name.split('.')
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
