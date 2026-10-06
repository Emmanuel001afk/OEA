package com.oea.launcher.focus

import android.content.Context

class OeaFocusStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("oea_focus", Context.MODE_PRIVATE)

    fun apps(): List<String> = prefs.getStringSet(KEY_APPS, emptySet()).orEmpty().toList()
    fun isFocused(key: String) = apps().contains(key)

    fun toggle(key: String): Boolean {
        val set = apps().toMutableSet()
        if (set.contains(key)) set.remove(key) else if (set.size < MAX_APPS) set.add(key)
        prefs.edit().putStringSet(KEY_APPS, set).apply()
        return set.contains(key)
    }

    fun setApps(keys: Collection<String>) {
        prefs.edit().putStringSet(KEY_APPS, keys.take(MAX_APPS).toSet()).apply()
    }

    companion object {
        const val MAX_APPS = 7
        private const val KEY_APPS = "focus_apps"
        @Volatile private var instance: OeaFocusStore? = null
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: OeaFocusStore(context).also { instance = it }
        }
    }
}
