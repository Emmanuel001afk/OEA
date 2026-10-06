package app.lawnchair.oea.shortcuts

import android.content.Context
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager

class OeaShortcutController(private val context: Context) {
    fun shortcuts(packageName: String): List<ShortcutInfo> =
        runCatching {
            context.getSystemService(ShortcutManager::class.java)
                .getShortcuts(ShortcutManager.FLAG_MATCH_ALL_KINDS)
                .filter { it.getPackage() == packageName }
        }.getOrDefault(emptyList())
}