package com.oea.launcher.shortcuts

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Process

/**
 * App shortcuts (long-press menus) through LauncherApps. Android only grants
 * this to the default Home app, so every call degrades to "no shortcuts".
 */
class OeaShortcutController(private val context: Context) {
    private val launcherApps: LauncherApps? = context.getSystemService(LauncherApps::class.java)

    fun hasPermission(): Boolean = runCatching { launcherApps?.hasShortcutHostPermission() == true }.getOrDefault(false)

    /** Up to [limit] shortcuts for an app: static ones first, then dynamic, by rank. */
    fun shortcuts(packageName: String, className: String? = null, limit: Int = 4): List<ShortcutInfo> {
        val apps = launcherApps ?: return emptyList()
        if (!hasPermission()) return emptyList()
        val query = LauncherApps.ShortcutQuery()
            .setPackage(packageName)
            .setQueryFlags(
                LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
)
        if (!className.isNullOrBlank()) query.setActivity(ComponentName(packageName, className))
        val all = runCatching { apps.getShortcuts(query, Process.myUserHandle()) }.getOrNull().orEmpty()
        val forActivity = all.filter { it.isEnabled && (it.isDynamic || it.isDeclaredInManifest) }
            .sortedWith(compareBy<ShortcutInfo>({ if (it.isDeclaredInManifest) 0 else 1 }, { it.rank }))
        val result = if (forActivity.isEmpty() && !className.isNullOrBlank()) {
            // Some apps attach shortcuts to a different activity than the launcher entry.
            shortcutsForPackageOnly(packageName)
        } else forActivity
        return result.take(limit)
    }

    private fun shortcutsForPackageOnly(packageName: String): List<ShortcutInfo> {
        val apps = launcherApps ?: return emptyList()
        val query = LauncherApps.ShortcutQuery().setPackage(packageName).setQueryFlags(
            LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST
)
        return runCatching { apps.getShortcuts(query, Process.myUserHandle()) }.getOrNull().orEmpty()
            .filter { it.isEnabled }
            .sortedWith(compareBy<ShortcutInfo>({ if (it.isDeclaredInManifest) 0 else 1 }, { it.rank }))
    }

    /** Finds one shortcut (pinned shortcuts on Home use this after a restart). */
    fun find(packageName: String, shortcutId: String): ShortcutInfo? {
        val apps = launcherApps ?: return null
        if (!hasPermission()) return null
        val query = LauncherApps.ShortcutQuery()
            .setPackage(packageName)
            .setShortcutIds(listOf(shortcutId))
            .setQueryFlags(
                LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
)
        return runCatching { apps.getShortcuts(query, Process.myUserHandle()) }.getOrNull()?.firstOrNull()
    }

    fun start(packageName: String, shortcutId: String, bounds: Rect? = null, options: Bundle? = null): Boolean {
        val apps = launcherApps ?: return false
        return runCatching {
            apps.startShortcut(packageName, shortcutId, bounds, options, Process.myUserHandle())
            true
        }.getOrDefault(false)
    }

    fun icon(info: ShortcutInfo): Drawable? = runCatching {
        launcherApps?.getShortcutIconDrawable(info, context.resources.displayMetrics.densityDpi)
    }.getOrNull()

    /** Keeps shortcuts placed on Home pinned, so apps cannot silently remove them. */
    fun pin(packageName: String, shortcutIds: List<String>) {
        val apps = launcherApps ?: return
        if (!hasPermission()) return
        runCatching { apps.pinShortcuts(packageName, shortcutIds, Process.myUserHandle()) }
    }

    fun label(info: ShortcutInfo): String =
        (info.shortLabel ?: info.longLabel)?.toString()?.takeIf { it.isNotBlank() } ?: info.id
}
