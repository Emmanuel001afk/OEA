package app.lawnchair.oea.engine

import android.content.ComponentName
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class OeaWorkspaceStore(context: Context) {
    data class Item(
        val component: ComponentName,
        val screen: Int,
        val cellX: Int,
        val cellY: Int,
        val spanX: Int = 1,
        val spanY: Int = 1,
    )

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): List<Item> {
        val raw = prefs.getString(KEY_ITEMS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList(array.length()) {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    add(
                        Item(
                            ComponentName(item.getString("package"), item.getString("class")),
                            item.optInt("screen", 0),
                            item.optInt("cellX", 0),
                            item.optInt("cellY", 0),
                            item.optInt("spanX", 1),
                            item.optInt("spanY", 1),
                        ),
                    )
                }
            }
        }.getOrElse { emptyList() }
    }

    fun save(items: List<Item>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("package", item.component.packageName)
                    .put("class", item.component.className)
                    .put("screen", item.screen)
                    .put("cellX", item.cellX)
                    .put("cellY", item.cellY)
                    .put("spanX", item.spanX)
                    .put("spanY", item.spanY),
            )
        }
        prefs.edit().putString(KEY_ITEMS, array.toString()).apply()
    }

    companion object {
        private const val PREFS = "oea_engine_workspace"
        private const val KEY_ITEMS = "items"
    }
}
