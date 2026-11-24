package com.oea.launcher.workspace

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.oea.launcher.data.OeaDataStore
import com.oea.launcher.model.OeaAppInfo

/**
 * Automatic app-family folders ("smart folders").
 *
 * Families are worked out from the phone, not from a list: apps that share an
 * Android uid, a signing certificate or a publisher namespace belong together (see
 * [OeaAppFamilies]). Nothing is read from a display name to decide membership, and
 * nothing has to be typed in — not the family, not the title. A folder names itself
 * from its apps ("Google Maps" + "Google Photos" → **Google**, `com.transsion.*` →
 * **Transsion**) and keeps that name up to date as members come and go, unless you
 * renamed it yourself, in which case your name wins for ever.
 *
 * Rules the reconcile follows:
 *  - a family folder is created only when **two or more** of its apps are
 *    installed and launchable, so unrelated phones never gain stray folders;
 *  - a title OEA wrote is replaced when the family changes; a title you wrote is
 *    never touched;
 *  - apps the user moves out of a family folder are remembered and never
 *    re-added automatically;
 *  - deleting a family folder turns that family off; it can be re-enabled in
 *    Settings › Home screen › Smart folders;
 *  - existing folders are only ever touched by member list and by OEA's own title —
 *    position, and any unrelated apps dragged in, are preserved;
 *  - ordinary apps that belong to no family always stay normal icons.
 *
 * All state lives in its own preferences file, separate from the workspace layout
 * and from every other OEA feature.
 */
object OeaSmartFolders {

    /** A family folder that exists in the layout. */
    data class Family(val id: String, val title: String)

    /** Result of a reconcile pass: the folder list to keep, plus icons to materialise. */
    data class Result(
        val folders: List<OeaWorkspaceStore.Folder>,
        val items: List<OeaWorkspaceStore.Item>,
        /** True when something actually changed and must be persisted. */
        val changed: Boolean
    )

    private const val PREFS = "oea_smart_folders"
    private const val KEY_ENABLED = "enabled_"
    private const val KEY_EXCLUDED = "excluded_"
    private const val KEY_WRITTEN_TITLE = "written_title_"
    private const val KEY_DISCOVERED = "discovered"
    private const val FOLDER_PREFIX = "smart_"

    fun folderId(familyId: String) = "$FOLDER_PREFIX$familyId"

    /** The family id behind a smart folder id, or null for any other folder. */
    fun familyIdOf(folderId: String): String? =
        folderId.takeIf { it.startsWith(FOLDER_PREFIX) }?.removePrefix(FOLDER_PREFIX)
            ?.takeIf { it.isNotBlank() }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context, familyId: String): Boolean =
        prefs(context).getBoolean(KEY_ENABLED + familyId, true)

    fun setEnabled(context: Context, familyId: String, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED + familyId, enabled).apply()
        invalidate()
    }

    /** Forgets every "user moved this app out" choice of one family. */
    fun reset(context: Context, familyId: String) {
        prefs(context).edit().remove(KEY_EXCLUDED + familyId).apply()
        invalidate()
    }

    /**
     * The families this phone was found to have on the last reconcile, with their
     * live enabled state — the Settings list, built from what exists here rather than
     * from a catalogue of family names.
     */
    fun families(context: Context): List<Pair<Family, Boolean>> =
        prefs(context).getStringSet(KEY_DISCOVERED, emptySet()).orEmpty()
            .mapNotNull { row ->
                val id = row.substringBefore('\t')
                val title = row.substringAfter('\t', "")
                if (id.isBlank()) null else Family(id, title.ifBlank { id }) to isEnabled(context, id)
            }
            .sortedBy { it.first.title.lowercase() }

    /** Called when the user removes one app from a family folder by hand. */
    fun onMemberRemoved(context: Context, folderId: String, memberKey: String) {
        val familyId = familyIdOf(folderId) ?: return
        val current = prefs(context).getStringSet(KEY_EXCLUDED + familyId, emptySet()).orEmpty()
        prefs(context).edit().putStringSet(KEY_EXCLUDED + familyId, current + memberKey).apply()
    }

    /**
     * Called when the user deletes a family folder itself: the family is turned
     * off so it is never recreated behind the user's back (it can be re-enabled
     * in Settings, which also clears nothing — deliberate exclusions survive).
     */
    fun onFolderDeleted(context: Context, folderId: String) {
        val familyId = familyIdOf(folderId) ?: return
        setEnabled(context, familyId, false)
    }

    /**
     * Brings family folders in line with the installed apps. Cheap when nothing
     * changed: the membership signature is compared first and the pass is skipped
     * entirely when it is unchanged — every manual edit path (rename, move, add,
     * remove) already keeps folders consistent.
     */
    fun reconcile(
        context: Context,
        apps: List<OeaAppInfo>,
        foldersIn: List<OeaWorkspaceStore.Folder>,
        homeGrid: Pair<Int, Int>
    ): Result {
        val store = OeaDataStore.get(context)
        val visible = apps.filterNot { store.isHidden(it.packageName, it.className) }
        if (visible.size < 2) return Result(foldersIn, emptyList(), changed = false)

        val discovered = OeaAppFamilies.discover(
            facts = visible.map { app ->
                OeaAppFamilies.Fact(
                    key = OeaWorkspaceStore.key(app.packageName, app.className),
                    packageName = app.packageName,
                    label = app.label,
                    uid = app.uid
                )
            },
            signatureOf = { packageName -> signatureOf(context, packageName) }
        )

        val signature = discovered.joinToString("|") { family ->
            "${family.id}:${family.title}=${family.members.sorted().joinToString(",")}"
        }
        if (signature == cachedSignature) {
            // Signature unchanged: folder edits since the last pass were made
            // by the user (or by this pass already) and must be preserved.
            return Result(foldersIn, emptyList(), changed = false)
        }

        val p = prefs(context)
        val appByKey = apps.associateBy { OeaWorkspaceStore.key(it.packageName, it.className) }
        val folders = foldersIn.toMutableList()
        val extraItems = mutableListOf<OeaWorkspaceStore.Item>()
        var changed = false
        val (cols, rows) = homeGrid

        // The Settings list is this pass's own result: the families found here.
        val summary = discovered.map { "${it.id}\t${it.title}" }.toSet()
        if (p.getStringSet(KEY_DISCOVERED, emptySet()).orEmpty() != summary) {
            p.edit().putStringSet(KEY_DISCOVERED, summary).apply()
        }

        discovered.forEach { family ->
            val fid = folderId(family.id)
            val index = folders.indexOfFirst { it.id == fid }
            val enabled = p.getBoolean(KEY_ENABLED + family.id, true)
            val excluded = p.getStringSet(KEY_EXCLUDED + family.id, emptySet()).orEmpty()
            val desired = family.members.filterNot { it in excluded }

            if (!enabled) {
                if (index >= 0) {
                    // Family was switched off (or its folder deleted on purpose):
                    // dissolve into the first member's icon, like removing the
                    // second-to-last member of any folder does.
                    val folder = folders[index]
                    folders.removeAt(index)
                    folder.members.firstOrNull()?.let { key ->
                        appByKey[key]?.let { app ->
                            extraItems += OeaWorkspaceStore.Item(key, app.packageName, app.className, folder.page, folder.cell)
                        }
                    }
                    changed = true
                }
                return@forEach
            }

            if (index < 0) {
                if (desired.size >= 2) {
                    // Bottom of the first page; normalization moves the folder
                    // to a nearby free cell when this one is taken.
                    folders += OeaWorkspaceStore.Folder(fid, family.title, 0, (rows - 1) * cols, desired)
                    prefs(context).edit().putString(KEY_WRITTEN_TITLE + fid, family.title).apply()
                    changed = true
                }
                return@forEach
            }

            val existing = folders[index]
            val kept = existing.members.filter { memberKey ->
                appByKey.containsKey(memberKey) &&
                    memberKey !in excluded &&
                    // Hidden apps are excluded the same way as in the desired
                    // list, so reconcile can never re-add what pruneHiddenApps
                    // just removed from this folder.
                    !store.isHidden(memberKey.substringBefore('/'), memberKey.substringAfter('/', ""))
            }
            val keptSet = kept.toSet()
            val merged = (kept + desired.filterNot { it in keptSet }).distinct()
            when {
                merged.size < 2 -> {
                    folders.removeAt(index)
                    merged.firstOrNull()?.let { key ->
                        appByKey[key]?.let { app ->
                            extraItems += OeaWorkspaceStore.Item(key, app.packageName, app.className, existing.page, existing.cell)
                        }
                    }
                    changed = true
                }
                merged != existing.members -> {
                    folders[index] = existing.copy(members = merged)
                    changed = true
                }
            }

            // The title maintains itself: OEA replaces the name it wrote, never one
            // you wrote. Renaming a family folder keeps your name for good.
            val current = folders.firstOrNull { it.id == fid } ?: return@forEach
            val written = p.getString(KEY_WRITTEN_TITLE + fid, null)
            if (current.title != family.title && (written == null || current.title == written)) {
                folders[folders.indexOfFirst { it.id == fid }] = current.copy(title = family.title)
                p.edit().putString(KEY_WRITTEN_TITLE + fid, family.title).apply()
                changed = true
            }
        }

        // Families that are gone (apps uninstalled, or recognised differently now)
        // leave no folder behind — but their members must stay on Home, so they are
        // materialised as icons first and the layout normalizer finds them cells.
        folders.filter { it.id.startsWith(FOLDER_PREFIX) }
            .toList()
            .forEach { folder ->
                val familyId = familyIdOf(folder.id) ?: return@forEach
                if (discovered.any { it.id == familyId }) return@forEach
                val index = folders.indexOfFirst { it.id == folder.id }
                if (index < 0) return@forEach
                folders.removeAt(index)
                folder.members.forEach { key ->
                    appByKey[key]?.let { app ->
                        extraItems += OeaWorkspaceStore.Item(key, app.packageName, app.className, folder.page, folder.cell)
                    }
                }
                prefs(context).edit().remove(KEY_WRITTEN_TITLE + folder.id).apply()
                changed = true
            }

        cachedSignature = signature
        return Result(folders, extraItems, changed)
    }

    /** Forces the next reconcile to run (after a settings change). */
    fun invalidate() {
        cachedSignature = null
    }

    @Volatile private var cachedSignature: String? = null

    private val signatures = HashMap<String, String>()

    /**
     * An app's signing certificate, shortened to a stable digest.
     *
     * Read once per process per package and only for apps the uid and namespace
     * passes could not place, so a phone whose families share a uid never pays for it.
     */
    @Suppress("DEPRECATION")
    private fun signatureOf(context: Context, packageName: String): String? {
        val cached = synchronized(signatures) { signatures[packageName] }
        if (cached != null) return if (cached.isBlank()) null else cached
        val digest = runCatching {
            val pm = context.packageManager
            val bytes = when {
                Build.VERSION.SDK_INT >= 28 ->
                    pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                        .signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
                else ->
                    pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
                        .signatures?.firstOrNull()?.toByteArray()
            }
            if (bytes == null) {
                ""
            } else {
                java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it) }
            }
        }.getOrDefault("")
        synchronized(signatures) { signatures[packageName] = digest }
        return if (digest.isBlank()) null else digest
    }
}
