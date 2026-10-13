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

/**
 * Package-manager-backed app catalogue. A single malformed ResolveInfo must
 * not take down Home; valid launcher activities are still returned and sorted.
 */
class OeaAppModel(private val context: Context) {
    @Volatile
    var apps: List<OeaAppInfo> = emptyList()
        private set

    fun load() {
        apps = query()
    }

    fun setApps(value: List<OeaAppInfo>) {
        apps = value.toList()
    }

    /** Queries every launchable activity. Call from a worker for initial loads. */
    fun query(): List<OeaAppInfo> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
        return pm.queryIntentActivities(intent, 0)
            .mapNotNull { info: ResolveInfo -> toAppInfo(info, pm) }
            .distinctBy { ComponentName(it.packageName, it.className) }
            .filterNot { it.packageName == context.packageName && it.className == OeaLauncherActivity::class.java.name }
            .sortedWith { a, b ->
                val labels = collator.compare(a.label, b.label)
                if (labels != 0) labels else ComponentName(a.packageName, a.className)
                    .flattenToString().compareTo(ComponentName(b.packageName, b.className).flattenToString())
            }
    }

    private fun toAppInfo(resolveInfo: ResolveInfo, pm: android.content.pm.PackageManager): OeaAppInfo? =
        runCatching {
            val activity = resolveInfo.activityInfo ?: return null
            val appInfo: ApplicationInfo = activity.applicationInfo ?: return null
            val packageName = activity.packageName?.takeIf(String::isNotBlank) ?: return null
            val className = activity.name?.takeIf(String::isNotBlank) ?: return null
            val label = runCatching { resolveInfo.loadLabel(pm)?.toString()?.trim() }
                .getOrNull()
                .orEmpty()
                .ifBlank { packageName }
            OeaAppInfo(
                packageName = packageName,
                className = className,
                label = label,
                category = if (Build.VERSION.SDK_INT >= 26) appInfo.category else -1,
                system = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                    (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0,
            )
        }.getOrNull()
}
