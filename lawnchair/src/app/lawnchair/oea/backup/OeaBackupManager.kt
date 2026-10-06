package app.lawnchair.oea.backup

import android.content.Context
import app.lawnchair.oea.workspace.OeaWorkspaceStore

class OeaBackupManager(context: Context) {
    private val store = OeaWorkspaceStore.get(context)
    fun snapshot(): Map<String, Any> = mapOf(
        "pages" to store.pages(),
        "currentPage" to store.getCurrentPage(),
        "dock" to store.dock(),
        "items" to store.items(),
        "folders" to store.folders(),
    )
}