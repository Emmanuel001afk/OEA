package app.lawnchair.oea.engine

import app.lawnchair.oea.data.OeaDataStore
import com.android.launcher3.model.BgDataModel
import com.android.launcher3.model.data.AppInfo
import com.android.launcher3.model.data.WorkspaceData

/**
 * Thin adapter from the Launcher3 model callback stream into OEA state.
 *
 * OEA observes the launcher model; it does not replace or mutate Launcher3's model.
 */
class OeaModelBridge(private val store: OeaDataStore) : BgDataModel.Callbacks {
    override fun bindCompleteModel(itemIdMap: WorkspaceData, isBindingSync: Boolean) {
        store.markModelBound()
    }

    override fun bindAllApplications(
        apps: Array<AppInfo>,
        flags: Int,
        packageUserKeytoUidMap: Map<com.android.launcher3.util.PackageUserKey, Int>,
    ) {
        store.setApplicationCount(apps.size)
    }
}
