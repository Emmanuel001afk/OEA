package app.lawnchair.oea.engine

import app.lawnchair.oea.data.OeaDataStore
import app.lawnchair.oea.model.OeaAppInfo

/**
 * OEA-owned model adapter. No Launcher3 model callbacks are used.
 */
class OeaModelBridge(private val store: OeaDataStore) {
    fun bind(apps: List<OeaAppInfo>) {
        store.setApplicationCount(apps.size)
        store.markModelBound()
    }
}