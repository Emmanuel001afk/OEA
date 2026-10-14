package com.oea.launcher.model

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.os.Build
import android.util.Log
import com.oea.launcher.OeaLauncherActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Collator

/**
 * One launchable activity. [category] is Android's declared app category
 * (ApplicationInfo.CATEGORY_*, -1 when undefined) and powers the drawer's
 * smart categories; [system] marks preinstalled, never-updated apps.
 * [stamp] changes whenever the app's APK is replaced (install/update), so
 * cached icons and labels are never reused across app versions.
 */
data class OeaAppInfo(
    val packageName: String,
    val className: String,
    val label: String,
    val category: Int = -1,
    val system: Boolean = false,
    val stamp: Long = 0L,
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

    private val cacheFile: File get() = File(context.noBackupFilesDir, CACHE_FILE)

    /**
     * The catalogue from the last successful refresh. Home binds it straight
     * away on a cold start, so a process restart shows the user's Home at once
     * instead of an empty wallpaper with "Loading apps…" while every app label
     * is re-read from PackageManager. The live refresh still runs right after.
     */
    fun loadCached(): List<OeaAppInfo> = runCatching {
        val file = cacheFile
        if (!file.isFile || file.length() <= 2L || file.length() > MAX_CACHE_BYTES) return emptyList()
        val root = JSONObject(file.readText())
        if (root.optInt("v") != CACHE_VERSION) return emptyList()
        val array = root.optJSONArray("apps") ?: return emptyList()
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val pkg = o.optString("p").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val cls = o.optString("c").takeIf(String::isNotBlank) ?: return@mapNotNull null
            OeaAppInfo(pkg, cls, o.optString("l").ifBlank { pkg }, o.optInt("g", -1), o.optBoolean("s"), o.optLong("t"))
        }
    }.onFailure { Log.w(TAG, "Ignoring unreadable app catalogue cache", it) }.getOrDefault(emptyList())

    /** Persists [value] atomically; call from a worker thread. */
    fun saveCache(value: List<OeaAppInfo>) {
        if (value.isEmpty()) return
        runCatching {
            val array = JSONArray()
            value.forEach { app ->
                array.put(JSONObject().apply {
                    put("p", app.packageName)
                    put("c", app.className)
                    put("l", app.label)
                    if (app.category != -1) put("g", app.category)
                    if (app.system) put("s", true)
                    if (app.stamp != 0L) put("t", app.stamp)
                })
            }
            val file = cacheFile
            val temp = File(file.parentFile, "$CACHE_FILE.tmp")
            temp.writeText(JSONObject().put("v", CACHE_VERSION).put("apps", array).toString())
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        }.onFailure { Log.w(TAG, "Could not persist the app catalogue cache", it) }
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
                stamp = apkStamp(appInfo),
            )
        }.getOrNull()

    /** The APK path changes on every install/update; its mtime covers in-place system updates. */
    private fun apkStamp(info: ApplicationInfo): Long = runCatching {
        val dir = info.sourceDir ?: return 0L
        dir.hashCode().toLong() * 31L + File(dir).lastModified()
    }.getOrDefault(0L)

    private companion object {
        const val TAG = "OeaAppModel"
        const val CACHE_FILE = "oea_app_catalogue.json"
        const val CACHE_VERSION = 1
        const val MAX_CACHE_BYTES = 4L * 1024L * 1024L
    }
}
