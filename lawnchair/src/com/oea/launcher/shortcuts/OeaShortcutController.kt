package com.oea.launcher.shortcuts

import android.content.Context
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager

class OeaShortcutController(private val context: Context) {
    fun shortcuts(packageName: String): List<ShortcutInfo> =
        runCatching {
            context.getSystemService(ShortcutManager::class.java)
                .getShortcuts(ShortcutManager.FLAG_MATCH_DYNAMIC or ShortcutManager.FLAG_MATCH_MANIFEST or ShortcutManager.FLAG_MATCH_PINNED)
                .filter { it.getPackage() == packageName }
        }.getOrDefault(emptyList())
}