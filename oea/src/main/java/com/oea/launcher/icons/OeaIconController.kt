package com.oea.launcher.icons

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.drawable.Drawable
import com.oea.launcher.data.OeaDataStore
import org.xmlpull.v1.XmlPullParser

/**
 * Resolves icons from installed ADW/Nova-style icon packs. Apps not covered by a
 * selected pack always fall back to their real launcher icon.
 */
class OeaIconController(private val context: Context) {
    data class IconPack(val packageName: String, val label: String)

    private val packageManager = context.packageManager
    private val store = OeaDataStore.get(context)
    private val cache = mutableMapOf<String, Drawable.ConstantState?>()
    private var activePack: String? = null
    private var componentMapPack: String? = null
    private var componentMap: Map<String, String> = emptyMap()

    fun installedPacks(): List<IconPack> {
        val candidates = linkedMapOf<String, String>()
        val actions = listOf(
            "org.adw.launcher.THEMES",
            "com.novalauncher.THEME",
            "com.anddoes.launcher.THEME",
            "com.teslacoilsw.launcher.THEME",
            "com.dlto.atom.launcher.THEME",
        )
        actions.forEach { action ->
            runCatching {
                packageManager.queryIntentActivities(Intent(action), PackageManager.MATCH_ALL)
                    .forEach { info ->
                        val app = info.activityInfo.applicationInfo
                        if (app.packageName != context.packageName) {
                            candidates[app.packageName] = app.loadLabel(packageManager).toString()
                        }
                    }
            }
        }
        // Some packs expose appfilter.xml without advertising a theme-picker activity.
        runCatching {
            packageManager.getInstalledApplications(PackageManager.GET_META_DATA).forEach { app ->
                if (app.packageName == context.packageName || candidates.containsKey(app.packageName)) return@forEach
                val hasFilter = runCatching {
                    val res = packageManager.getResourcesForApplication(app.packageName)
                    res.getIdentifier("appfilter", "xml", app.packageName) != 0 ||
                        res.getIdentifier("app_filter", "xml", app.packageName) != 0
                }.getOrDefault(false)
                if (hasFilter) candidates[app.packageName] = app.loadLabel(packageManager).toString()
            }
        }
        return candidates.map { IconPack(it.key, it.value) }.sortedBy { it.label.lowercase() }
    }

    fun icon(packageName: String, className: String? = null): Drawable? {
        val selectedPack = store.iconPackPackage()
        if (selectedPack != activePack) {
            activePack = selectedPack
            cache.clear()
            componentMapPack = null
            componentMap = emptyMap()
        }
        val key = "$selectedPack|$packageName/$className"
        if (!cache.containsKey(key)) {
            cache[key] = loadPackIcon(selectedPack, packageName, className)
                ?: runCatching {
                    if (!className.isNullOrBlank()) packageManager.getActivityIcon(ComponentName(packageName, className)).constantState
                    else packageManager.getApplicationIcon(packageName).constantState
                }.getOrNull()
                ?: runCatching { packageManager.getApplicationIcon(packageName).constantState }.getOrNull()
        }
        return cache[key]?.newDrawable()?.mutate()
    }

    private fun loadPackIcon(packPackage: String?, packageName: String, className: String?): Drawable.ConstantState? {
        if (packPackage.isNullOrBlank()) return null
        val resources = runCatching { packageManager.getResourcesForApplication(packPackage) }.getOrNull() ?: return null
        val mapping = mappings(packPackage, resources)
        val component = className?.let { ComponentName(packageName, it).flattenToString() }
        val drawableName = (component?.let(mapping::get))
            ?: mapping.entries.firstOrNull { it.key.startsWith("$packageName/") }?.value
            ?: return null
        val id = resources.getIdentifier(drawableName, "drawable", packPackage)
            .takeIf { it != 0 }
            ?: resources.getIdentifier(drawableName, "mipmap", packPackage)
        if (id == 0) return null
        return runCatching { resources.getDrawable(id, null).constantState }.getOrNull()
    }

    private fun mappings(packPackage: String, resources: Resources): Map<String, String> {
        if (componentMapPack == packPackage) return componentMap
        val xmlId = resources.getIdentifier("appfilter", "xml", packPackage)
            .takeIf { it != 0 }
            ?: resources.getIdentifier("app_filter", "xml", packPackage)
        if (xmlId == 0) {
            componentMapPack = packPackage
            componentMap = emptyMap()
            return componentMap
        }
        val result = linkedMapOf<String, String>()
        runCatching {
            resources.getXml(xmlId).use { parser ->
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG && parser.name == "item") {
                        val rawComponent = parser.getAttributeValue(null, "component")
                        val drawable = parser.getAttributeValue(null, "drawable")
                        if (!rawComponent.isNullOrBlank() && !drawable.isNullOrBlank()) {
                            val flattened = rawComponent.removePrefix("ComponentInfo{").removeSuffix("}")
                            result[flattened] = drawable
                        }
                    }
                    event = parser.next()
                }
            }
        }
        componentMapPack = packPackage
        componentMap = result
        return result
    }
}
