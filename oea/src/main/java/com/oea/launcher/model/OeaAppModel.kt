package com.oea.launcher.model

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.os.Build
import com.oea.launcher.OeaLauncherActivity
import java.text.Collator

/**
 * One launchable activity. [category] is Android's declared app category
 * (ApplicationInfo.CATEGORY_*, -1 when undefined) and powers the drawer's
 * smart categories; [system] marks preinstalled, never-updated apps.
 */
data class OeaAppInfo(
    val packageName: String,
    val className: String,
    val label: String,
    val category: Int = -1,
    val system: Boolean = false,
)

class OeaAppModel(private val context: Context) {
    var apps: List<OeaAppInfo> = emptyList()
        private set

    fun load() {
        apps = query()
    }

    fun setApps(value: List<OeaAppInfo>) {
        apps = value
    }

    /** Queries every launchable activity. Safe to call from a background thread. */
    fun query(): List<OeaAppInfo> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
        return pm.queryIntentActivities(intent, 0)
            .map { info: ResolveInfo ->
                val appInfo = info.activityInfo.applicationInfo
                OeaAppInfo(
                    packageName = info.activityInfo.packageName,
                    className = info.activityInfo.name,
                    label = runCatching { info.loadLabel(pm).toString() }.getOrDefault(info.activityInfo.packageName).trim(),
                    category = if (Build.VERSION.SDK_INT >= 26) appInfo.category else -1,
                    system = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                        (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0,
                )
            }
            .distinctBy { ComponentName(it.packageName, it.className) }
            .filterNot { it.packageName == context.packageName && it.className == OeaLauncherActivity::class.java.name }
            .sortedWith { a, b -> collator.compare(a.label, b.label) }
    }
}
