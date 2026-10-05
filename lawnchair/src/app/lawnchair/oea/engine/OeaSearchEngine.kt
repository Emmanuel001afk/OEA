package app.lawnchair.oea.engine

import android.content.ComponentName
import android.os.UserHandle

class OeaSearchEngine(private val catalog: OeaAppCatalog) {
    data class Result(
        val component: ComponentName,
        val label: String,
        val packageName: String,
        val user: UserHandle,
        val score: Int,
    )

    fun search(query: String, limit: Int = 20): List<Result> {
        val normalized = query.trim().lowercase()
        if (normalized.isEmpty()) return emptyList()

        return catalog.apps.value
            .mapNotNull { app ->
                score(app.label, app.packageName, normalized)?.let {
                    Result(app.component, app.label, app.packageName, app.user, it)
                }
            }
            .sortedWith(
                compareByDescending<Result> { it.score }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label }
                    .thenBy { it.component.flattenToShortString() },
            )
            .take(limit.coerceIn(1, 100))
    }

    private fun score(label: String, packageName: String, query: String): Int? {
        val name = label.lowercase()
        val pkg = packageName.lowercase()
        if (name == query) return 1000
        if (name.startsWith(query)) return 900 - (name.length - query.length).coerceAtMost(100)
        if (name.split(Regex("[^a-z0-9]+")).any { it.startsWith(query) }) return 800
        if (name.contains(query)) return 700
        if (pkg.contains(query)) return 500

        var cursor = 0
        var matched = 0
        for (character in name) {
            if (cursor < query.length && character == query[cursor]) {
                cursor++
                matched++
            }
        }
        return if (matched == query.length) {
            300 - (name.length - query.length).coerceAtMost(100)
        } else {
            null
        }
    }
}
