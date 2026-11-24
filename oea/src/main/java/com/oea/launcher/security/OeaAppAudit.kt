package com.oea.launcher.security

import android.app.admin.DevicePolicyManager
import android.app.usage.StorageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.provider.Settings
import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Locale

/**
 * Reads what [OeaThreatEngine] needs, and nothing else.
 *
 * Every fact here comes from the phone: the package manager, the package's own
 * signing certificate, the special-access tables Android keeps, the installer
 * record. No network, no external service, no upload — the console can run this
 * on a phone that has never been online.
 */
object OeaAppAudit {

    private const val PREFS = "oea_threat_audit"
    private const val KEY_ALLOWED = "allowed_packages"
    private const val KEY_LAST_SCAN = "last_scan"
    private const val KEY_LAST_FLAGGED = "last_flagged"
    private const val KEY_LAST_HIGH = "last_high"

    /** An APK sitting in storage that nothing has installed yet. */
    data class PendingApk(val name: String, val uri: Uri, val bytes: Long, val added: Long)

    /** One whole-phone scan: what the engine said, plus loose installers on disk. */
    data class ScanResult(
        val report: OeaThreatEngine.Report,
        val apks: List<PendingApk>,
        val error: String? = null
    ) {
        val flagged: Int get() = report.flagged
        val verdict: OeaThreatEngine.Verdict get() = report.verdict
    }

    // ------------------------------------------------------------------ scanning

    /** Scans every installed app. Call off the main thread. */
    fun scan(ctx: Context): ScanResult {
        val apps = collect(ctx) ?: return ScanResult(
            OeaThreatEngine.Report(0, 0, 0, emptyList(), 0, 0, 0),
            emptyList(),
            "The app list could not be read on this device"
        )
        val report = OeaThreatEngine.assessInstalled(apps, allowedPackages(ctx), ctx.packageName)
        val apks = runCatching { pendingApks(ctx) }.getOrDefault(emptyList())
        rememberScan(ctx, report)
        return ScanResult(report, apks)
    }

    /**
     * Gathers the facts for every installed app, or null when the package list is
     * unreadable. A single unreadable app is skipped, never counted as clean.
     */
    fun collect(ctx: Context): List<OeaThreatEngine.AppFacts>? {
        val pm = ctx.packageManager
        val apps = runCatching { pm.getInstalledApplications(0) }.getOrNull() ?: return null
        val accessibility = enabledComponents(ctx, "enabled_accessibility_services")
        val listeners = enabledComponents(ctx, "enabled_notification_listeners")
        val admins = activeAdmins(ctx)
        val out = ArrayList<OeaThreatEngine.AppFacts>(apps.size)
        apps.forEach { app ->
            runCatching { factsFor(ctx, pm, app, accessibility, listeners, admins) }.getOrNull()?.let(out::add)
        }
        return out
    }

    private fun factsFor(
        ctx: Context,
        pm: PackageManager,
        app: ApplicationInfo,
        accessibility: Set<String>,
        listeners: Set<String>,
        admins: Set<String>
    ): OeaThreatEngine.AppFacts {
        val pkg = app.packageName
        @Suppress("DEPRECATION")
        val info = pm.getPackageInfo(
            pkg,
            PackageManager.GET_PERMISSIONS or
                PackageManager.GET_SERVICES or
                PackageManager.GET_RECEIVERS or
                (if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else 0)
        )
        val permissions = (info.requestedPermissions ?: emptyArray()).filterNotNull().toSet()
        val granted = permissions.filterTo(HashSet()) { permission ->
            runCatching { pm.checkPermission(permission, pkg) == PackageManager.PERMISSION_GRANTED }
                .getOrDefault(false)
        }
        val services = info.services.orEmpty()
        val declaresAccessibility = services.any {
            it.permission == "android.permission.BIND_ACCESSIBILITY_SERVICE"
        }
        val declaresListener = services.any {
            it.permission == "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"
        }
        val overlay = appOpAllowed(ctx, "android:system_alert_window", app.uid, pkg)
        val label = runCatching { pm.getApplicationLabel(app).toString() }.getOrDefault(pkg)
        val installer = runCatching {
            if (Build.VERSION.SDK_INT >= 30) {
                pm.getInstallSourceInfo(pkg).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                pm.getInstallerPackageName(pkg)
            }
        }.getOrNull()
        val targetSdk = runCatching { app.targetSdkVersion }.getOrDefault(0)
        val bytes = (listOfNotNull(app.sourceDir) + app.splitSourceDirs.orEmpty().toList())
            .sumOf { runCatching { File(it).length() }.getOrDefault(0L) }
        return OeaThreatEngine.AppFacts(
            packageName = pkg,
            label = label,
            isSystem = app.flags and ApplicationInfo.FLAG_SYSTEM != 0,
            isUpdatedSystem = app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0,
            targetSdk = targetSdk,
            installer = installer,
            permissions = permissions,
            grantedPermissions = granted,
            declaresAccessibility = declaresAccessibility,
            enabledAccessibility = enabledIn(accessibility, pkg),
            declaresNotificationListener = declaresListener,
            enabledNotificationListener = enabledIn(listeners, pkg),
            deviceAdmin = pkg in admins,
            drawsOverOtherApps = overlay,
            hasLauncherIcon = runCatching { pm.getLaunchIntentForPackage(pkg) != null }.getOrDefault(true),
            signingSubject = signerSubject(info),
            apkBytes = bytes
        )
    }

    /** `Settings.Secure` holds these as `pkg/component` entries separated by colons. */
    /** The facts for one package — what the install watcher needs after an install. */
    fun factsFor(ctx: Context, packageName: String): OeaThreatEngine.AppFacts? {
        val pm = ctx.packageManager
        val app = runCatching { pm.getApplicationInfo(packageName, 0) }.getOrNull() ?: return null
        val accessibility = enabledComponents(ctx, "enabled_accessibility_services")
        val listeners = enabledComponents(ctx, "enabled_notification_listeners")
        val admins = activeAdmins(ctx)
        return runCatching { factsFor(ctx, pm, app, accessibility, listeners, admins) }.getOrNull()
    }

    private fun enabledComponents(ctx: Context, key: String): Set<String> =
        runCatching { Settings.Secure.getString(ctx.contentResolver, key) }
            .getOrNull()
            .orEmpty()
            .split(':')
            .map { it.trim().substringBefore('/') }
            .filter { it.isNotEmpty() }
            .toSet()

    private fun enabledIn(enabled: Set<String>, pkg: String): Boolean = pkg in enabled

    private fun activeAdmins(ctx: Context): Set<String> {
        val dpm = runCatching { ctx.getSystemService(DevicePolicyManager::class.java) }.getOrNull()
            ?: return emptySet()
        return runCatching {
            ctx.packageManager.queryBroadcastReceivers(Intent(DevicePolicyManager.ACTION_DEVICE_ADMIN_ENABLED), 0)
                .mapNotNull { it.activityInfo }
                .filter { dpm.isAdminActive(ComponentName(it.packageName, it.name)) }
                .map { it.packageName }
                .toSet()
        }.getOrDefault(emptySet())
    }

    /** The signing certificate's subject — the readable part of "who signed this". */
    private fun signerSubject(info: android.content.pm.PackageInfo): String? = runCatching {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        } ?: return null
        val factory = CertificateFactory.getInstance("X.509")
        signatures.firstOrNull()?.let { signature ->
            val certificate = signature.toByteArray().inputStream().use { stream ->
                factory.generateCertificate(stream) as X509Certificate
            }
            certificate.subjectX500Principal.name
        }
    }.getOrNull()

    // -------------------------------------------------------------- pending APKs

    /**
     * APK files visible in storage that nobody has installed. On Android 13+ a
     * plain query only sees media, so this can legitimately come back empty; the
     * UI says "none visible" rather than "none exist".
     */
    fun pendingApks(ctx: Context, limit: Int = 20): List<PendingApk> {
        val collection = MediaStore.Files.getContentUri("external")
        val columns = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_ADDED
        )
        val out = ArrayList<PendingApk>()
        ctx.contentResolver.query(
            collection,
            columns,
            "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?",
            arrayOf("%.apk"),
            "${MediaStore.Files.FileColumns.SIZE} DESC"
        )?.use { c ->
            while (c.moveToNext() && out.size < limit) {
                val id = c.getLong(0)
                val name = c.getString(1) ?: continue
                out.add(
                    PendingApk(
                        name = name,
                        uri = android.content.ContentUris.withAppendedId(collection, id),
                        bytes = c.getLong(2),
                        added = c.getLong(3) * 1000L
                    )
                )
            }
        }
        return out
    }

    // ------------------------------------------------------------- allow & memory

    fun allowedPackages(ctx: Context): Set<String> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_ALLOWED, emptySet())?.toSet()
            ?: emptySet()

    fun allow(ctx: Context, packageName: String, on: Boolean) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = (prefs.getStringSet(KEY_ALLOWED, emptySet()) ?: emptySet()).toMutableSet()
        if (on) current.add(packageName) else current.remove(packageName)
        prefs.edit().putStringSet(KEY_ALLOWED, current).apply()
    }

    private fun rememberScan(ctx: Context, report: OeaThreatEngine.Report) {
        runCatching {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_LAST_SCAN, System.currentTimeMillis())
                .putInt(KEY_LAST_FLAGGED, report.flagged)
                .putInt(KEY_LAST_HIGH, report.high)
                .apply()
        }
    }

    /** The last scan, for the console card's subtitle: (millis, flagged, high). */
    fun lastScan(ctx: Context): Triple<Long, Int, Int> {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Triple(prefs.getLong(KEY_LAST_SCAN, 0L), prefs.getInt(KEY_LAST_FLAGGED, 0), prefs.getInt(KEY_LAST_HIGH, 0))
    }

    fun allowListSize(ctx: Context): Int = allowedPackages(ctx).size

    // ------------------------------------------------------------------- caches

    /**
     * Per-app cache and data sizes, when this build is allowed to see them.
     *
     * `StorageStatsManager` needs the Usage-access app-op; without it Android
     * throws, and the caller is told "unknown" rather than shown a zero.
     */
    fun appCaches(ctx: Context, limit: Int = 8): List<AppCache>? {
        if (!usageAccessGranted(ctx)) return null
        if (Build.VERSION.SDK_INT < 26) return null
        val stats = runCatching { ctx.getSystemService(StorageStatsManager::class.java) }.getOrNull() ?: return null
        val storage = runCatching { ctx.getSystemService(StorageManager::class.java) }.getOrNull() ?: return null
        val uuid = runCatching { storage.getUuidForPath(File(ctx.filesDir.parentFile?.absolutePath ?: "/")) }
            .getOrNull() ?: return null
        val pm = ctx.packageManager
        val out = ArrayList<AppCache>()
        runCatching { pm.getInstalledApplications(0) }.getOrNull().orEmpty().forEach { app ->
            if (app.uid < android.os.Process.FIRST_APPLICATION_UID) return@forEach
            runCatching {
                val info = stats.queryStatsForUid(uuid, app.uid)
                val label = runCatching { pm.getApplicationLabel(app).toString() }.getOrDefault(app.packageName)
                AppCache(app.packageName, label, info.cacheBytes, info.dataBytes)
            }.getOrNull()?.let(out::add)
        }
        return out.sortedByDescending { it.cacheBytes }.take(limit)
    }

    data class AppCache(val packageName: String, val label: String, val cacheBytes: Long, val dataBytes: Long)

    fun usageAccessGranted(ctx: Context): Boolean =
        appOpAllowed(ctx, android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), ctx.packageName)

    /**
     * One app-op read, across the API split: `unsafeCheckOpNoThrow` arrived in
     * Android 10, and on 8 and 9 the only call is the deprecated `checkOpNoThrow`.
     * Both are reads — neither changes the op.
     */
    private fun appOpAllowed(ctx: Context, op: String, uid: Int, packageName: String): Boolean = runCatching {
        val ops = ctx.getSystemService(android.app.AppOpsManager::class.java) ?: return false
        if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(op, uid, packageName) == android.app.AppOpsManager.MODE_ALLOWED
        } else {
            @Suppress("DEPRECATION")
            val mode = ops.checkOpNoThrow(op, uid, packageName)
            mode == android.app.AppOpsManager.MODE_ALLOWED
        }
    }.getOrDefault(false)

    // --------------------------------------------------------------- big files

    data class BigFile(val name: String, val uri: Uri, val bytes: Long, val mime: String)

    /** The largest files the app is allowed to see, newest-free and size-sorted. */
    fun bigFiles(ctx: Context, minBytes: Long = 50L * 1024 * 1024, limit: Int = 15): List<BigFile> {
        val collection = MediaStore.Files.getContentUri("external")
        val columns = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.MIME_TYPE
        )
        val out = ArrayList<BigFile>()
        runCatching {
            ctx.contentResolver.query(
                collection,
                columns,
                "${MediaStore.Files.FileColumns.SIZE} >= ?",
                arrayOf(minBytes.toString()),
                "${MediaStore.Files.FileColumns.SIZE} DESC"
            )?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    val name = c.getString(1) ?: continue
                    out.add(
                        BigFile(
                            name = name,
                            uri = android.content.ContentUris.withAppendedId(collection, c.getLong(0)),
                            bytes = c.getLong(2),
                            mime = c.getString(3).orEmpty()
                        )
                    )
                }
            }
        }
        return out
    }

    /** A ready-made, user-confirmed delete for [uris], or null when unsupported. */
    fun deleteRequest(ctx: Context, uris: List<Uri>): android.content.IntentSender? {
        if (Build.VERSION.SDK_INT < 30 || uris.isEmpty()) return null
        return runCatching { MediaStore.createDeleteRequest(ctx.contentResolver, uris).intentSender }
            .getOrNull()
    }

    /** A human size, using the one formatter the rest of the app uses. */
    fun sizeText(bytes: Long): String = com.oea.launcher.miniapps.OeaEmageFacts.size(bytes)

    fun cacheSummary(caches: List<AppCache>?): String {
        if (caches == null) return "Usage access not granted"
        val total = caches.sumOf { it.cacheBytes }
        return "${sizeText(total)} of cache in the ${caches.size} biggest apps"
    }

    fun apkSummary(apks: List<PendingApk>): String = when {
        apks.isEmpty() -> "No installers visible in storage"
        apks.size == 1 -> "1 APK waiting to be installed"
        else -> "${apks.size} APKs waiting to be installed"
    }

    fun packageLabel(facts: OeaThreatEngine.AppFacts): String =
        facts.label.ifBlank { facts.packageName }.also { it.lowercase(Locale.getDefault()) }
}
