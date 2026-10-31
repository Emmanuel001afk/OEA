package com.oea.launcher.workspace

import android.content.Context
import com.oea.launcher.data.OeaDataStore
import com.oea.launcher.model.OeaAppInfo

/**
 * Automatic app-family folders ("smart folders").
 *
 * Families are recognised from reliable package identifiers — a curated
 * catalogue of package-name prefixes and exact package names — never from
 * display names, labels or icons, which any app can fake or localise.
 *
 * Rules the reconcile follows:
 *  - a family folder is created only when **two or more** of its apps are
 *    installed and launchable, so unrelated phones never gain stray folders;
 *  - apps the user moves out of a family folder are remembered and never
 *    re-added automatically;
 *  - deleting a family folder turns that family off; it can be re-enabled in
 *    Settings › Home screen › Smart folders;
 *  - existing folders are only ever touched by member list — a user's title,
 *    position, and any unrelated apps they dragged in are preserved;
 *  - ordinary apps that belong to no family always stay normal icons.
 *
 * All state lives in its own preferences file, separate from the workspace
 * layout and from every other OEA feature.
 */
object OeaSmartFolders {

    /** A recognised app family. */
    data class Family(
        val id: String,
        val title: String,
        val prefixes: List<String> = emptyList(),
        val packages: Set<String> = emptySet(),
        /** Packages that match a prefix but are not consumer apps of this family. */
        val excludePackages: Set<String> = emptySet(),
    )

    /**
     * The catalogue. Prefixes are only used where the prefix is a protected
     * developer namespace (com.google., com.microsoft., …); app families with
     * scattered package names (Meta) are listed package by package.
     */
    val CATALOGUE = listOf(
        Family(
            "google", "Google",
            prefixes = listOf("com.google."),
            excludePackages = setOf(
                // Support components that sometimes expose launcher entries
                // but are not part of the app family a user expects.
                "com.google.android.webview",
                "com.google.android.trichromelibrary",
                "com.google.android.tts",
                "com.google.android.gms",
                "com.google.android.gsf",
                "com.google.android.partnersetup"
            )
        ),
        Family("microsoft", "Microsoft", prefixes = listOf("com.microsoft.")),
        Family(
            "meta", "Meta",
            packages = setOf(
                "com.facebook.katana", "com.facebook.lite",
                "com.facebook.orca", "com.facebook.orca.lite",
                "com.instagram.android", "com.instagram.lite",
                "com.whatsapp", "com.whatsapp.w4b"
            )
        ),
        Family("adobe", "Adobe", prefixes = listOf("com.adobe.")),
        Family(
            "amazon", "Amazon",
            prefixes = listOf("com.amazon."),
            packages = setOf("com.audible.application", "com.amazon.kindle.fcreader")
        ),
        Family("transsion", "Transsion", prefixes = listOf("com.transsion.")),
    )

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
    private const val FOLDER_PREFIX = "smart_"

    fun folderId(familyId: String) = "$FOLDER_PREFIX$familyId"
    fun familyIdOf(folderId: String): String? =
        folderId.takeIf { it.startsWith(FOLDER_PREFIX) }?.removePrefix(FOLDER_PREFIX)
            ?.takeIf { id -> CATALOGUE.any { it.id == id } }

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

    /** Settings listing: family catalogue with live enabled state. */
    fun families(context: Context): List<Pair<Family, Boolean>> =
        CATALOGUE.map { it to isEnabled(context, it.id) }

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
     * Brings family folders in line with the installed apps. Cheap when
     * nothing changed: the family membership signature is compared first and
     * the pass is skipped entirely when it is unchanged — every manual edit
     * path (rename, move, add, remove) already keeps folders consistent.
     */
    fun reconcile(
        context: Context,
        apps: List<OeaAppInfo>,
        foldersIn: List<OeaWorkspaceStore.Folder>,
        homeGrid: Pair<Int, Int>
    ): Result {
        val store = OeaDataStore.get(context)
        val membersByFamily = LinkedHashMap<String, MutableList<String>>()
        apps.forEach { app ->
            val family = familyOf(app.packageName) ?: return@forEach
            if (store.isHidden(app.packageName, app.className)) return@forEach
            membersByFamily.getOrPut(family.id) { mutableListOf() }
                .add(OeaWorkspaceStore.key(app.packageName, app.className))
        }
        val signature = membersByFamily.entries.joinToString("|") { (id, keys) ->
            "$id=${keys.sorted().joinToString(",")}"
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

        CATALOGUE.forEach { family ->
            val fid = folderId(family.id)
            val index = folders.indexOfFirst { it.id == fid }
            val enabled = p.getBoolean(KEY_ENABLED + family.id, true)
            val desired = membersByFamily[family.id].orEmpty()
            val excluded = p.getStringSet(KEY_EXCLUDED + family.id, emptySet()).orEmpty()

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

            val desiredKeys = desired.filterNot { it in excluded }
            if (index < 0) {
                if (desiredKeys.size >= 2) {
                    // Bottom of the first page; normalization moves the folder
                    // to a nearby free cell when this one is taken.
                    folders += OeaWorkspaceStore.Folder(fid, family.title, 0, (rows - 1) * cols, desiredKeys)
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
            val merged = (kept + desiredKeys.filterNot { it in keptSet }).distinct()
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
        }

        cachedSignature = signature
        return Result(folders, extraItems, changed)
    }

    /** Forces the next reconcile to run (after a settings change). */
    fun invalidate() {
        cachedSignature = null
    }

    @Volatile private var cachedSignature: String? = null

    /** The family a package belongs to, by identifier only; null when none. */
    fun familyOf(packageName: String): Family? = CATALOGUE.firstOrNull { family ->
        packageName in family.packages ||
            (family.prefixes.any { packageName.startsWith(it) } && packageName !in family.excludePackages)
    }
}
