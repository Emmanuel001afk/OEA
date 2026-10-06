package app.lawnchair.oea.drawer

import app.lawnchair.oea.model.OeaAppInfo
import java.util.Locale

class OeaAppDrawerController {
    fun filter(apps: List<OeaAppInfo>, query: String): List<OeaAppInfo> {
        val q = query.trim().lowercase(Locale.ROOT)
        return apps.asSequence()
            .filter { q.isEmpty() || it.label.lowercase(Locale.ROOT).contains(q) || it.packageName.lowercase(Locale.ROOT).contains(q) }
            .sortedBy { it.label.lowercase(Locale.ROOT) }
            .toList()
    }
}