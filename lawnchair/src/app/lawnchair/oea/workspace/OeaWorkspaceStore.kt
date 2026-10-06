package app.lawnchair.oea.workspace

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent OEA-owned home model.
 *
 * The UI is only a renderer. Pages, dock positions and folders live here so they survive
 * activity recreation and can later be backed up/restored without depending on Launcher3.
 */
class OeaWorkspaceStore private constructor(context: Context) {
    data class Item(
        val id: String,
        val packageName: String,
        val className: String,
        val page: Int,
        val cell: Int,
        val folderId: String? = null,
    )
    data class Folder(
        val id: String,
        val title: String,
        val page: Int,
        val cell: Int,
        val members: List<String>,
    )

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun pages(): Int = prefs.getInt(KEY_PAGES, 1).coerceIn(1, MAX_PAGES)

    fun setPages(count: Int) {
        prefs.edit().putInt(KEY_PAGES, count.coerceIn(1, MAX_PAGES)).apply()
    }

    fun items(): List<Item> = readItems()

    fun dock(): List<String> =
        prefs.getStringSet(KEY_DOCK, emptySet())?.toList()?.sortedBy {
            it.substringBefore("|").toIntOrNull() ?: Int.MAX_VALUE
        }?.map { it.substringAfter("|") } ?: emptyList()

    fun setDock(values: List<String>) {
        prefs.edit().putStringSet(KEY_DOCK, values.take(DOCK_SLOTS).mapIndexed { index, value -> "$index|$value" }.toSet()).apply()
    }

    fun folders(): List<Folder> {
        val raw = prefs.getString(KEY_FOLDERS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                val members = o.optJSONArray("members")
                Folder(
                    o.getString("id"),
                    o.optString("title", "Folder"),
                    o.optInt("page", 0),
                    o.optInt("cell", 0),
                    if (members == null) emptyList() else (0 until members.length()).map(members::getString),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun replaceItems(values: List<Item>) {
        val array = JSONArray()
        values.forEach { item ->
            array.put(JSONObject().apply {
                put("id", item.id)
                put("package", item.packageName)
                put("class", item.className)
                put("page", item.page)
                put("cell", item.cell)
                item.folderId?.let { put("folder", it) }
            })
        }
        prefs.edit().putString(KEY_ITEMS, array.toString()).apply()
    }

    fun replaceFolders(values: List<Folder>) {
        val array = JSONArray()
        values.forEach { folder ->
            array.put(JSONObject().apply {
                put("id", folder.id)
                put("title", folder.title)
                put("page", folder.page)
                put("cell", folder.cell)
                put("members", JSONArray(folder.members))
            })
        }
        prefs.edit().putString(KEY_FOLDERS, array.toString()).apply()
    }

    fun ensureSeeded(apps: List<Triple<String, String, String>>) {
        if (prefs.contains(KEY_SEEDED)) return
        val dockKeys = apps.take(DOCK_SLOTS).map { key(it.first, it.second) }\n        val homeApps = apps.drop(DOCK_SLOTS).take(20)\n        val items = homeApps.mapIndexed { index, app ->\n            Item(\n                id = key(app.first, app.second),\n                packageName = app.first,\n                className = app.second,\n                page = index / 20,\n                cell = index,\n            )\n        }\n        replaceItems(items)\n        setDock(dockKeys)\n        setPages(if (apps.isEmpty()) 1 else 1)
        prefs.edit().putBoolean(KEY_SEEDED, true).apply()
    }

    fun clearMissing(validKeys: Set<String>) {
        replaceItems(items().filter { it.id in validKeys })
        setDock(dock().filter(validKeys::contains))
        replaceFolders(folders().map { it.copy(members = it.members.filter(validKeys::contains)) }.filter { it.members.isNotEmpty() })
    }

    companion object {
        const val DOCK_SLOTS = 5
        const val MAX_PAGES = 12
        private const val PREFS = "oea_workspace"
        private const val KEY_ITEMS = "items"
        private const val KEY_FOLDERS = "folders"
        private const val KEY_DOCK = "dock"
        private const val KEY_PAGES = "pages"
        private const val KEY_SEEDED = "seeded"
        @Volatile private var instance: OeaWorkspaceStore? = null

        fun get(context: Context): OeaWorkspaceStore =
            instance ?: synchronized(this) {
                instance ?: OeaWorkspaceStore(context).also { instance = it }
            }

        fun key(packageName: String, className: String) = "$packageName/$className"
    }

    private fun readItems(): List<Item> {
        val raw = prefs.getString(KEY_ITEMS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                Item(
                    o.getString("id"),
                    o.getString("package"),
                    o.getString("class"),
                    o.optInt("page", 0),
                    o.optInt("cell", 0),
                    o.optString("folder", "").takeIf(String::isNotBlank),
                )
            }
        }.getOrDefault(emptyList())
    }
}
