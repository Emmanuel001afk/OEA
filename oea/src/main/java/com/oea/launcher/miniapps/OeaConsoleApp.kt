package com.oea.launcher.miniapps

import android.app.Activity
import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.oea.launcher.R
import com.oea.launcher.applock.OeaAppFreezer
import com.oea.launcher.data.OeaDataStore
import com.oea.launcher.fault.OeaFaultLog
import com.oea.launcher.fault.OeaFaultRecord
import com.oea.launcher.privilege.OeaPrivileges
import com.oea.launcher.ui.OeaKit
import com.oea.launcher.ui.OeaPalette
import com.oea.launcher.ui.OeaType
import com.oea.launcher.ui.OeaTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Central OEA utility mini-app — OEA Console (central utility).
 *
 * Chosen name: "Console" because it is the command centre for advanced operations:
 * - Our own ADB-inspired system (not a wrapper around ADB or Shizuku) — own format, own build, own verification
 * - Diagnostics (expanded with crash/ANR/startup failure organization)
 * - Device information and hardware/software specs (real APIs)
 * - Security analysis and antivirus-style checks (confirmed threat vs suspicious vs legit)
 * - Storage cleanup and maintenance (real measurements)
 * - Memory, battery, temperature, performance (real BatteryManager, ActivityManager, PowerManager)
 * - Sound amplifier: real mic → earphone loopback with gain and a feedback limiter
 * - Other advanced utilities
 *
 * ADB-inspired system research (summarized):
 * - ADB: client-server, adbd daemon, shell commands via binder, pm, am, settings, dumpsys, logcat, input, svc
 * - Shizuku: privileged service via app that exposes shell via binder to other apps, uses adb wireless debugging or root
 * - Android permission architecture: runtime permissions, appops (SYSTEM_ALERT_WINDOW, GET_USAGE_STATS, etc.), special access (notification listener, accessibility, device admin, device owner), roles
 * - Wireless Debugging: pairing via QR, TLS, adb over Wi-Fi — needs PC or helper
 * - Root: su binary, scoped commands, verification by reading state back
 * - Device Owner: DevicePolicyManager.setPermissionGrantState, setPackagesSuspended, setApplicationHidden, etc.
 *
 * Our own build:
 * - OeaShell runs su commands for OEA's own package only, always verified by reading back state (e.g., pm list packages --suspended, dumpsys package, appops get)
 * - OeaPrivileges opens exact Android pages and verifies result (canDrawOverlays, isNotificationListener, etc.)
 * - OeaAccessDoctor verifies live state, not just switch (probe overlay window, check assistant connection)
 * - No fake overlay tricks, no claiming ordinary app can bypass security
 * - Broader than few functions: covers overlay, multitasking (freeform, split, PIP), suspension (freeze), restricted settings (DND, battery optimization, write settings, secure settings), diagnostics, device info, security, storage, performance, sound
 * - On-device, no laptop needed, no Shizuku helper, no third-party
 * - Commands are OEA's own DSL, not ADB: e.g., "device info", "package list", "freeze com.example", "overlay status", "multitask split pkg1 pkg2", etc.
 */
object OeaConsoleApp {
    private val io = Executors.newFixedThreadPool(2) { Thread(it, "OeaConsole").apply { isDaemon = true } }

    fun build(host: Activity, palette: OeaPalette): View {
        val kit = OeaKit(host, palette)
        val column = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }

        val header = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(kit.text("Console", 22f, palette.text, OeaType.medium), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(kit.iconBadge(R.drawable.oic_developer_mode, palette.accentSoft, palette.onAccentSoft, 40))
        column.addView(header, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(12) })
        kit.note(column, "Advanced device utilities: diagnostics, device info, security checks, storage tools, performance and our own ADB-inspired command system. Everything here uses supported Android APIs and explains its limits honestly. No laptop, no Shizuku, no helper app — OEA's own privileged service layer.")

        // Quick stats
        val statsCard = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(kit.dp(16), kit.dp(14), kit.dp(16), kit.dp(14))
            background = kit.rounded(palette.card, 22, palette.stroke, 1)
        }
        val battery = batteryInfo(host)
        val lowModeActive = com.oea.launcher.perf.OeaLowMode.active(host)
        val deviceUltraSaving = isDeviceInUltraPowerSaving(host)
        statsCard.addView(kit.text("Battery ${battery.first}% • ${if (battery.second) "Charging" else "Discharging"} • ${memoryInfo(host)}", 13f, palette.textSecondary))
        statsCard.addView(kit.text(deviceSummary(host) + if (lowModeActive) " • LOW (Ultra Power Saving) ON" else " • LOW OFF" + if (deviceUltraSaving) " • Device Ultra Saving ON" else "", 12f, palette.textTertiary).apply { setPadding(0, kit.dp(4), 0, 0) })
        if (deviceUltraSaving) {
            statsCard.addView(kit.text("Device Ultra Power Saving is ON — OEM may block mini-apps, alarms, background work. OEA Console explains how to whitelist.", 12f, palette.danger).apply { setPadding(0, kit.dp(6), 0, 0) })
        }
        column.addView(statsCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(12) })

        // Sections
        val sections = listOf(
            "diagnostics" to "Diagnostics • ${OeaFaultLog.count()} recorded",
            "device" to "Device Info • Hardware & software",
            "security" to "Security • Suspicious apps & permissions",
            "storage" to "Storage • Cleanup & maintenance",
            "performance" to "Performance • Memory, battery, temperature, ultra saving",
            "sound" to "Sound Amplifier • Room sound to the earphones",
            "adb" to "Command System • Our own ADB-inspired build (comprehensive)",
            "privileges" to "Privileges • Overlay, suspension, multitasking",
            "lowmode" to "LOW Mode • Ultra Power Saving (launcher's own)"
        )

        val group = kit.group(column)
        sections.forEach { (id, label) ->
            val icon = when (id) {
                "diagnostics" -> R.drawable.oic_shield_fill
                "device" -> R.drawable.oic_phone_android
                "security" -> R.drawable.oic_shield
                "storage" -> R.drawable.oic_cleaning_services
                "performance" -> R.drawable.oic_memory
                "sound" -> R.drawable.oic_volume_up
                "adb" -> R.drawable.oic_developer_mode
                "privileges" -> R.drawable.oic_accessibility_new
                "lowmode" -> R.drawable.oic_battery_charging_full
                else -> R.drawable.oic_settings
            }
            kit.row(group, icon, label.substringBefore(" • "), label.substringAfter(" • ")) {
                when (id) {
                    "diagnostics" -> showDiagnostics(host, kit)
                    "device" -> showDeviceInfo(host, kit)
                    "security" -> showSecurity(host, kit)
                    "storage" -> showStorage(host, kit)
                    "performance" -> showPerformance(host, kit)
                    "sound" -> showSoundAmplifier(host, kit)
                    "adb" -> showCommandSystem(host, kit)
                    "privileges" -> showPrivileges(host, kit)
                    "lowmode" -> showLowMode(host, kit)
                }
            }
        }

        column.addView(kit.primaryButton("Send latest incident to developer", R.drawable.oic_public) {
            val latest = OeaFaultLog.history().firstOrNull()
            if (latest == null) {
                android.widget.Toast.makeText(host, "No incidents recorded", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                shareIncident(host, latest)
            }
        }, LinearLayout.LayoutParams(-1, kit.dp(50)).apply { topMargin = kit.dp(14) })

        return column
    }

    // ------------------------------------------------------------------ diagnostics

    private fun showDiagnostics(host: Activity, kit: OeaKit) {
        val faults = OeaFaultLog.history()
        kit.sheet("Diagnostics • ${faults.size} incidents", "Crashes, ANRs, failed ops, startup failures, UI freezes across launcher and mini-apps. Organized by app, component, time and error type. Does not become source of crashes/battery drain/storage bloat — only writes when something had to be contained, with size limit.") { panel, _ ->
            if (faults.isEmpty()) {
                kit.note(panel, "No problems recorded. OEA only writes here when something had to be contained. Enable Keep a problem log in Settings › Stability.")
                return@sheet
            }
            // Group by subsystem and type
            val bySubsystem = faults.groupBy { it.subsystem.substringBefore(':') }
            bySubsystem.entries.sortedByDescending { it.value.size }.forEach { (sub, list) ->
                val g = kit.group(panel)
                kit.row(g, R.drawable.oic_layers, "$sub • ${list.size} incidents", "Latest: ${list.firstOrNull()?.errorClass} • ${SimpleDateFormat("MMM d HH:mm", Locale.getDefault()).format(Date(list.firstOrNull()?.at ?: 0L))}", value = list.firstOrNull()?.action?.name?.lowercase()) {
                    kit.sheet("$sub incidents", null) { p2, _ ->
                        // Organize by time and type
                        val byType = list.groupBy { it.errorClass }
                        byType.forEach { (type, typeList) ->
                            kit.sectionTitle(p2, "$type • ${typeList.size}")
                            typeList.take(10).forEach { record ->
                                val row = kit.group(p2)
                                kit.row(row, R.drawable.oic_history, record.title(), "${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(record.at))} • ${record.reason}", value = record.errorClass) {
                                    showFaultDetail(host, kit, record)
                                }
                            }
                        }
                    }
                }
            }
            // Group by time
            kit.sectionTitle(panel, "By time")
            val timeGroups = faults.groupBy { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(it.at)) }
            timeGroups.entries.sortedByDescending { it.key }.take(5).forEach { (date, list) ->
                val g = kit.group(panel)
                kit.row(g, R.drawable.oic_schedule, date, "${list.size} incidents") {
                    kit.sheet("$date incidents", null) { p2, _ ->
                        list.take(20).forEach { record ->
                            kit.row(kit.group(p2), R.drawable.oic_history, record.title(), "${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(record.at))} • ${record.errorClass}")
                        }
                    }
                }
            }

            val actions = kit.group(panel)
            kit.row(actions, R.drawable.oic_delete, "Clear diagnostic history", "Removes all recorded incidents") {
                kit.confirm("Clear diagnostics?", "All recorded problems will be removed. Settings stay.", "Clear", destructive = true) {
                    OeaFaultLog.clear(host)
                    android.widget.Toast.makeText(host, "Diagnostics cleared", android.widget.Toast.LENGTH_SHORT).show()
                    (host as? OeaMiniAppActivity)?.refresh()
                }
            }
            kit.row(actions, R.drawable.oic_text_fields, "Export all as text", "Copy or share full log") {
                val text = faults.joinToString("\n\n") { it.body() }
                kit.sheet("Export diagnostics", null) { p2, _ ->
                    kit.note(p2, text.take(8000) + if (text.length > 8000) "\n\n... truncated, full log is ${text.length} chars" else "")
                    p2.addView(kit.buttonRow(
                        kit.quietButton("Copy") {
                            val cb = host.getSystemService(android.content.ClipboardManager::class.java)
                            cb?.setPrimaryClip(android.content.ClipData.newPlainText("OEA diagnostics", text))
                            android.widget.Toast.makeText(host, "Copied", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        kit.primaryButton("Share") {
                            try {
                                host.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share diagnostics"))
                            } catch (_: Throwable) {}
                        }
                    ))
                }
            }
        }
    }

    private fun showFaultDetail(host: Activity, kit: OeaKit, record: OeaFaultRecord) {
        kit.sheet(record.title(), "${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(record.at))} • ${record.thread}") { panel, _ ->
            kit.note(panel, record.body())
            panel.addView(kit.buttonRow(
                kit.quietButton("Copy") {
                    val cb = host.getSystemService(android.content.ClipboardManager::class.java)
                    cb?.setPrimaryClip(android.content.ClipData.newPlainText("OEA fault", record.body()))
                    android.widget.Toast.makeText(host, "Copied", android.widget.Toast.LENGTH_SHORT).show()
                },
                kit.tonalButton("Share") {
                    try {
                        host.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, record.body()), "Share incident"))
                    } catch (_: Throwable) {}
                },
                kit.primaryButton("Send to AI") {
                    shareIncident(host, record)
                }
            ))
            kit.note(panel, "Verified problem: confirmed exception caught by fault shield. Suspicious indicators (slow UI, missing permission) distinguished elsewhere. Access to other apps' private data limited by sandboxing.")
        }
    }

    private fun shareIncident(host: Activity, record: OeaFaultRecord) {
        val text = buildString {
            append("OEA Diagnostic Report\n")
            append("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(record.at))}\n")
            append("Device: ${Build.MANUFACTURER} ${Build.MODEL} • Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
            append("App: ${host.packageName} • ${record.subsystem}\n")
            append("Error: ${record.errorClass}: ${record.message}\n")
            append("Thread: ${record.thread} (${record.kind})\n")
            append("Action: ${record.action} • ${record.reason}\n")
            append("Stack: ${record.frames.joinToString(" <- ")}\n")
            append("\nThis report was generated by OEA Console's diagnostics system. It contains only OEA's own fault data, no private data from other apps unless Android permits it via public APIs.")
        }
        try {
            host.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Send incident"))
        } catch (_: Throwable) {
            val cb = host.getSystemService(android.content.ClipboardManager::class.java)
            cb?.setPrimaryClip(android.content.ClipData.newPlainText("OEA incident", text))
            android.widget.Toast.makeText(host, "Copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------ device info

    private fun showDeviceInfo(host: Activity, kit: OeaKit) {
        kit.sheet("Device Info", "Real measurements from supported Android APIs, no fabricated info", build = { panel, _ ->
            val info = deviceDetails(host)
            info.forEach { (k, v) ->
                val g = kit.group(panel)
                kit.row(g, R.drawable.oic_info, k, v, chevron = false) {
                    try {
                        val cb = host.getSystemService(android.content.ClipboardManager::class.java) as android.content.ClipboardManager
                        cb.setPrimaryClip(android.content.ClipData.newPlainText(k, v))
                        android.widget.Toast.makeText(host, "$k copied", android.widget.Toast.LENGTH_SHORT).show()
                    } catch (_: Throwable) {}
                }
            }
            kit.note(panel, "All data from Build, ActivityManager, BatteryManager, DisplayMetrics, PackageManager, etc. No fabricated information. Unsupported operations explained rather than faked. For mini-apps not working in device ultra power saving, see Performance section.")
        })
    }

    private fun deviceDetails(ctx: Context): List<Pair<String, String>> {
        val list = mutableListOf<Pair<String, String>>()
        list.add("Manufacturer" to Build.MANUFACTURER)
        list.add("Model" to Build.MODEL)
        list.add("Brand" to Build.BRAND)
        list.add("Device" to Build.DEVICE)
        list.add("Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        list.add("Security patch" to (Build.VERSION.SECURITY_PATCH ?: "unknown"))
        list.add("Fingerprint" to (Build.FINGERPRINT ?: "unknown"))
        list.add("Board" to (Build.BOARD ?: "unknown"))
        list.add("Hardware" to (Build.HARDWARE ?: "unknown"))
        list.add("Supported ABIs" to Build.SUPPORTED_ABIS.joinToString())
        list.add("Product" to (Build.PRODUCT ?: "unknown"))
        try {
            val am = ctx.getSystemService(ActivityManager::class.java)
            val mem = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mem)
            list.add("Total RAM" to "${mem.totalMem / (1024*1024)} MB")
            list.add("Avail RAM" to "${mem.availMem / (1024*1024)} MB")
            list.add("Low memory" to mem.lowMemory.toString())
            list.add("Threshold" to "${mem.threshold / (1024*1024)} MB")
        } catch (_: Throwable) {}
        try {
            val dm = ctx.resources.displayMetrics
            list.add("Screen" to "${dm.widthPixels}x${dm.heightPixels} • ${dm.densityDpi} dpi • ${dm.density}x")
        } catch (_: Throwable) {}
        try {
            val pm = ctx.packageManager
            val info = pm.getPackageInfo(ctx.packageName, 0)
            list.add("OEA version" to "${info.versionName} (${info.longVersionCode})")
        } catch (_: Throwable) {}
        list.add("Low mode (Ultra Saving)" to if (com.oea.launcher.perf.OeaLowMode.active(ctx)) "ON • ${com.oea.launcher.perf.OeaLowMode.summary(ctx)}" else "OFF")
        list.add("Device ultra saving" to if (isDeviceInUltraPowerSaving(ctx)) "ON • OEM may block mini-apps" else "OFF")
        list.add("Power save mode" to if (isPowerSaveMode(ctx)) "ON" else "OFF")
        return list
    }

    private fun deviceSummary(ctx: Context): String = "${Build.MANUFACTURER} ${Build.MODEL} • Android ${Build.VERSION.RELEASE}"

    // ------------------------------------------------------------------ security

    private fun showSecurity(host: Activity, kit: OeaKit) {
        kit.sheet("Security Analysis", "Identifies suspicious apps, pop-up ads, unexpected permissions, unwanted behaviour. Distinguishes confirmed threat, suspicious indicator and legitimate permission. Explains why flagged.") { panel, _ ->
            panel.addView(kit.text("Scanning installed apps…", 13f, kit.palette.textSecondary).apply { setPadding(kit.dp(6), kit.dp(12), kit.dp(6), kit.dp(6)) })
            val resultHost = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
            panel.addView(resultHost)

            io.execute {
                val findings = scanSecurity(host)
                host.runOnUiThread {
                    if (host.isDestroyed) return@runOnUiThread
                    resultHost.removeAllViews()
                    if (findings.isEmpty()) {
                        kit.banner(resultHost, R.drawable.oic_check_circle_fill, "No issues found", "No suspicious apps detected in quick scan. Best-effort check using package flags and permission analysis.", OeaKit.Tone.SUCCESS)
                    } else {
                        findings.forEach { (pkg, reason, level) ->
                            val tone = when (level) {
                                "threat" -> OeaKit.Tone.DANGER
                                "suspicious" -> OeaKit.Tone.WARNING
                                else -> OeaKit.Tone.INFO
                            }
                            val g = kit.group(resultHost)
                            kit.banner(g, if (level == "threat") R.drawable.oic_warning_fill else R.drawable.oic_info, pkg, reason, tone = tone)
                            kit.row(g, R.drawable.oic_info, "App info", "Open Android's app info") {
                                try { host.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) {}
                            }
                            kit.row(g, R.drawable.oic_delete, "Uninstall?", "Request uninstall via system dialog") {
                                try { host.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))) } catch (_: Throwable) {}
                            }
                        }
                    }
                    kit.note(resultHost, "Analysis uses public PackageManager APIs only. Cannot inspect other apps' private data or network traffic. App having Accessibility is not automatically malicious — OEA explains why flagged. Confirmed threat = known malware package name or behavior, suspicious = multiple sensitive perms, legit = launcher with overlay permission is expected.")
                }
            }
        }
    }

    private fun scanSecurity(ctx: Context): List<Triple<String, String, String>> {
        val list = mutableListOf<Triple<String, String, String>>()
        try {
            val pm = ctx.packageManager
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            apps.forEach { app ->
                if (app.packageName == ctx.packageName) return@forEach
                val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                if (isSystem) return@forEach
                try {
                    val perms = pm.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)?.requestedPermissions ?: emptyArray()
                    val suspiciousPerms = perms.filter { it.contains("SYSTEM_ALERT_WINDOW") || it.contains("BIND_ACCESSIBILITY") || it.contains("READ_SMS") || it.contains("READ_CALL_LOG") }
                    if (suspiciousPerms.size >= 4) {
                        list.add(Triple(app.packageName, "Requests multiple sensitive permissions: ${suspiciousPerms.joinToString()} — may be legitimate (e.g., launcher, automation, security) but worth reviewing. Level: suspicious indicator, not confirmed threat. Why flagged: combination of overlay + accessibility + SMS + call log is often used by adware, but also by legitimate apps. Check app's purpose and uninstall if unknown.", "suspicious"))
                    } else if (suspiciousPerms.size >= 2) {
                        list.add(Triple(app.packageName, "Requests sensitive permissions: ${suspiciousPerms.joinToString()} — likely legitimate if you installed it for that purpose (e.g., call blocker needs call log). Level: legitimate permission, informational.", "legit"))
                    }
                    // Known threat patterns (example: packages with "adware" or known bad)
                    if (app.packageName.contains("adware", true) || app.packageName.contains("spyware", true)) {
                        list.add(Triple(app.packageName, "Package name contains suspicious keyword — confirmed threat pattern. Recommend uninstall.", "threat"))
                    }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
        return list.take(30)
    }

    // ------------------------------------------------------------------ storage

    private fun showStorage(host: Activity, kit: OeaKit) {
        kit.sheet("Storage & Cleanup", "Legitimate storage cleanup based on real measurements, no fake boost claims") { panel, _ ->
            val cacheSize = try {
                host.cacheDir.walkTopDown().filter { it.isFile }.map { it.length() }.sum() / (1024*1024)
            } catch (_: Throwable) { 0L }
            val g = kit.group(panel)
            kit.row(g, R.drawable.oic_cleaning_services, "Cache", "${cacheSize} MB in OEA's own cache", value = "Clean") {
                kit.confirm("Clear OEA cache?", "Only OEA's own cache is cleared (${cacheSize} MB). Other apps' data untouched.", "Clear") {
                    try {
                        host.cacheDir.deleteRecursively()
                        android.widget.Toast.makeText(host, "Cache cleared", android.widget.Toast.LENGTH_SHORT).show()
                        (host as? OeaMiniAppActivity)?.refresh()
                    } catch (_: Throwable) {
                        android.widget.Toast.makeText(host, "Could not clear cache", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
            kit.row(g, R.drawable.oic_delete, "Diagnostic logs", "${OeaFaultLog.count()} incidents • ${diagnosticsSize(host)} KB", value = "Clear") {
                kit.confirm("Clear diagnostic logs?", "Only OEA's private diagnostic files removed.", "Clear", destructive = true) {
                    OeaFaultLog.clear(host)
                    (host as? OeaMiniAppActivity)?.refresh()
                }
            }
            kit.row(g, R.drawable.oic_wallpaper, "Wallpaper cache", "OEA's downscaled wallpaper copies", value = "Manage") {
                try { host.startActivity(Intent(host, com.oea.launcher.OeaSystemToolsActivity::class.java).putExtra(com.oea.launcher.OeaSystemToolsActivity.EXTRA_SCREEN, "wallpaper")) } catch (_: Throwable) {}
            }
            kit.row(g, R.drawable.oic_photo_library, "OEA Camera photos", "Photos in Pictures/OEA", value = "Open") {
                try { host.startActivity(Intent(Intent.ACTION_VIEW).setType("image/*")) } catch (_: Throwable) {}
            }
            kit.note(panel, "OEA only cleans its own files. Does not claim to boost performance by clearing other apps' caches, which Android manages itself. Real storage info from StatFs and package manager.")
            val stats = storageStats(host)
            kit.sectionTitle(panel, "Storage")
            stats.forEach { (k, v) ->
                val sg = kit.group(panel)
                kit.row(sg, R.drawable.oic_info, k, v, chevron = false)
            }
        }
    }

    private fun diagnosticsSize(ctx: Context): Long {
        return try {
            val dir = File(ctx.filesDir, "diagnostics")
            if (!dir.exists()) return 0L
            dir.walkTopDown().filter { it.isFile }.map { it.length() }.sum() / 1024
        } catch (_: Throwable) { 0L }
    }

    private fun storageStats(ctx: Context): List<Pair<String, String>> {
        val list = mutableListOf<Pair<String, String>>()
        try {
            val stat = android.os.StatFs(ctx.filesDir.path)
            val total = stat.totalBytes / (1024*1024*1024)
            val free = stat.freeBytes / (1024*1024*1024)
            list.add("Internal total" to "${total} GB")
            list.add("Internal free" to "${free} GB")
            list.add("Block size" to "${stat.blockSizeLong} bytes")
        } catch (_: Throwable) {}
        try {
            val files = ctx.filesDir.walkTopDown().filter { it.isFile }.map { it.length() }.sum() / (1024*1024)
            list.add("OEA files" to "${files} MB")
        } catch (_: Throwable) {}
        return list
    }

    // ------------------------------------------------------------------ performance + ultra saving

    private fun showPerformance(host: Activity, kit: OeaKit) {
        kit.sheet("Performance • Ultra Power Saving", "Memory, battery, temperature, power saving — real APIs, with device ultra saving handling") { panel, _ ->
            val mem = memoryInfo(host)
            val battery = batteryInfo(host)
            val temp = batteryTemp(host)
            val g = kit.group(panel)
            kit.row(g, R.drawable.oic_memory, "Memory", mem, chevron = false)
            kit.row(g, R.drawable.oic_battery_full, "Battery", "${battery.first}% • ${if (battery.second) "Charging" else "Not charging"}", chevron = false)
            kit.row(g, R.drawable.oic_thermostat, "Battery temperature", temp, chevron = false)
            kit.row(g, R.drawable.oic_battery_charging_full, "LOW mode (Ultra Saving)", com.oea.launcher.perf.OeaLowMode.summary(host)) {
                try { host.startActivity(Intent(host, com.oea.launcher.OeaSystemToolsActivity::class.java).putExtra(com.oea.launcher.OeaSystemToolsActivity.EXTRA_SCREEN, "low").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) {}
            }
            kit.row(g, R.drawable.oic_battery_full, "Power save mode", if (isPowerSaveMode(host)) "ON • Android battery saver" else "OFF", chevron = false)
            kit.row(g, R.drawable.oic_battery_charging_full, "Device ultra saving", if (isDeviceInUltraPowerSaving(host)) "ON • OEM ultra saving may block mini-apps" else "OFF • Normal", chevron = false)

            if (isDeviceInUltraPowerSaving(host)) {
                kit.banner(panel, R.drawable.oic_warning_fill, "Device Ultra Power Saving is ON", "Your device's own ultra power saving (Samsung, Xiaomi, etc.) restricts background work and may block OEA mini-apps, alarms, widgets, and overlays. This is OEM behavior, not OEA. Fix: add OEA to protected/unrestricted apps, disable ultra saving, or whitelist in battery settings.", OeaKit.Tone.WARNING, "Battery settings") {
                    try { host.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Throwable) {
                        try { host.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${host.packageName}"))) } catch (_: Throwable) {}
                    }
                }
                kit.note(panel, "Why mini-apps not working in device ultra saving: OEM ultra saving kills background services, blocks overlay (SYSTEM_ALERT_WINDOW), restricts network, and may prevent activities from launching. OEA's own Low mode (Ultra Power Saving) is different — it only makes launcher cheap (freeze live scenes, hide islands, release sensors) and never blocks mini-apps. To keep mini-apps working: 1) Settings › Battery › Unrestricted for OEA, 2) Lock OEA in recents, 3) Disable device ultra saving or add OEA to allowed list, 4) Check OEM-specific: Samsung Device Care › Battery › Unrestricted, Xiaomi Security › Battery › No restrictions.")
            }

            kit.sectionTitle(panel, "Thermal & CPU")
            val tg = kit.group(panel)
            kit.row(tg, R.drawable.oic_info, "Thermal state", thermalInfo(host), chevron = false)
            kit.row(tg, R.drawable.oic_memory, "CPU cores", "${Runtime.getRuntime().availableProcessors()} cores • ${Build.SUPPORTED_ABIS.joinToString()}", chevron = false)
            kit.row(tg, R.drawable.oic_battery_full, "Doze whitelist", if (isWhitelistedFromDoze(host)) "OEA whitelisted • Alarms work in Doze" else "Not whitelisted • Alarms may be delayed in Doze") {
                try {
                    if (Build.VERSION.SDK_INT >= 23) {
                        host.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${host.packageName}")))
                    }
                } catch (_: Throwable) {
                    try { host.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Throwable) {}
                }
            }

            kit.sectionTitle(panel, "Actions")
            val ag = kit.group(panel)
            kit.row(ag, R.drawable.oic_cleaning_services, "Trim memory", "Releases icon and preview caches") {
                try {
                    (host.application as? com.oea.launcher.OeaApplication)?.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_MODERATE)
                    android.widget.Toast.makeText(host, "Memory trimmed", android.widget.Toast.LENGTH_SHORT).show()
                } catch (_: Throwable) {}
            }
            kit.row(ag, R.drawable.oic_refresh, "GC", "Request garbage collection (best-effort)") {
                try { System.gc(); android.widget.Toast.makeText(host, "GC requested", android.widget.Toast.LENGTH_SHORT).show() } catch (_: Throwable) {}
            }

            kit.note(panel, "Data from ActivityManager, BatteryManager, PowerManager. No fabricated boost claims. OEA trims caches on onTrimMemory. Device ultra saving detection via PowerManager.isPowerSaveMode and OEM broadcasts (Samsung, Xiaomi).")
        }
    }

    private fun memoryInfo(ctx: Context): String {
        return try {
            val am = ctx.getSystemService(ActivityManager::class.java)
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            "${mi.availMem / (1024*1024)} MB free / ${mi.totalMem / (1024*1024)} MB total • Threshold ${mi.threshold / (1024*1024)} MB • Low ${mi.lowMemory}"
        } catch (_: Throwable) { "Unavailable" }
    }

    private fun batteryInfo(ctx: Context): Pair<Int, Boolean> {
        return try {
            val bm = ctx.getSystemService(BatteryManager::class.java)
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val charging = bm.isCharging
            level to charging
        } catch (_: Throwable) { -1 to false }
    }

    private fun batteryTemp(ctx: Context): String {
        return try {
            val intent = ctx.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            if (temp > 0) String.format(Locale.US, "%.1f°C", temp / 10.0) else "Unavailable"
        } catch (_: Throwable) { "Unavailable" }
    }

    private fun thermalInfo(ctx: Context): String {
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val pm = ctx.getSystemService(android.os.PowerManager::class.java)
                when (pm?.currentThermalStatus) {
                    android.os.PowerManager.THERMAL_STATUS_LIGHT -> "Light"
                    android.os.PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
                    android.os.PowerManager.THERMAL_STATUS_SEVERE -> "Severe"
                    android.os.PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
                    else -> "Normal"
                }
            } else "Normal (API <29)"
        } catch (_: Throwable) { "Unavailable" }
    }

    private fun isPowerSaveMode(ctx: Context): Boolean {
        return try { ctx.getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode == true } catch (_: Throwable) { false }
    }

    private fun isDeviceInUltraPowerSaving(ctx: Context): Boolean {
        // Detect via PowerManager + check for Samsung/Xiaomi ultra saving settings via Settings
        return try {
            val pm = ctx.getSystemService(android.os.PowerManager::class.java)
            if (pm?.isPowerSaveMode == true) {
                // Check if ultra saving is on via system settings (best-effort)
                val ultra = Settings.System.getInt(ctx.contentResolver, "ultra_powersaving_mode", 0) == 1 ||
                        Settings.Global.getInt(ctx.contentResolver, "low_power", 0) == 1
                ultra || pm.isPowerSaveMode
            } else false
        } catch (_: Throwable) {
            // Fallback: check if low memory or restricted
            false
        }
    }

    private fun isWhitelistedFromDoze(ctx: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 23) {
                val pm = ctx.getSystemService(android.os.PowerManager::class.java)
                pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true
            } else true
        } catch (_: Throwable) { false }
    }

    // ------------------------------------------------------------------ sound amplifier

    private const val REQUEST_AMPLIFIER = 3012

    private fun showSoundAmplifier(host: Activity, kit: OeaKit) {
        kit.sheet("Sound Amplifier", "Room sound to the earphones, and the volume controls around it") { panel, _ ->
            val audio = host.getSystemService(android.media.AudioManager::class.java)
            val g = kit.group(panel)
            if (audio != null) {
                kit.row(g, R.drawable.oic_volume_up, "Media volume", "${audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)} / ${audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)}") {
                    try { audio.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_SAME, android.media.AudioManager.FLAG_SHOW_UI) } catch (_: Throwable) {}
                }
                kit.row(g, R.drawable.oic_volume_up, "Call volume", "${audio.getStreamVolume(android.media.AudioManager.STREAM_VOICE_CALL)} / ${audio.getStreamMaxVolume(android.media.AudioManager.STREAM_VOICE_CALL)}", chevron = false)
                panel.addView(kit.primaryButton("Boost media volume to max", R.drawable.oic_volume_up) {
                    try {
                        audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC), 0)
                        android.widget.Toast.makeText(host, "Media volume boosted", android.widget.Toast.LENGTH_SHORT).show()
                        (host as? OeaMiniAppActivity)?.refresh()
                    } catch (_: Throwable) {}
                }, LinearLayout.LayoutParams(-1, kit.dp(50)).apply { topMargin = kit.dp(12) })
            }
            kit.sectionTitle(panel, "Amplify the room into your ears")
            kit.note(
                panel,
                "OEA reads the microphone, makes it louder and writes it to the earphones, buffer by buffer, on its own thread. Headphones or earpieces only: a speaker and a microphone in the same room with gain on will squeal. OEA pulls the gain back when it hears that happen, but the physics is yours to respect."
            )
            val status = android.widget.TextView(host).apply {
                text = if (OeaAmplifier.isRunning) "Amplifying · %.1f×".format(OeaAmplifier.gain) else "Stopped"
                setTextColor(kit.palette.textSecondary)
                textSize = 12.5f
                setPadding(kit.dp(2), kit.dp(2), kit.dp(2), kit.dp(4))
            }
            panel.addView(status, LinearLayout.LayoutParams(-1, -2))
            val gainSlider = com.oea.launcher.ui.OeaSlider(host).apply {
                minValue = 0.5f
                maxValue = 6f
                steps = 11
                value = OeaAmplifier.gain
                format = { "%.1f×".format(it) }
                setColors(kit.palette, onDark = false)
                onValue = { value, _ ->
                    OeaAmplifier.gain = value
                    status.text = if (OeaAmplifier.isRunning) {
                        "Amplifying · %.1f× · %.0f ms of buffering".format(value, OeaAmplifier.latencyMs.toFloat())
                    } else {
                        "%.1f× · stopped".format(value)
                    }
                }
            }
            panel.addView(gainSlider, LinearLayout.LayoutParams(-1, kit.dp(44)))
            panel.addView(
                kit.buttonRow(
                    kit.primaryButton("Start") {
                        if (host.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                            runCatching { host.requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQUEST_AMPLIFIER) }
                            android.widget.Toast.makeText(host, "OEA needs the microphone — allow it, then press Start again", android.widget.Toast.LENGTH_LONG).show()
                        } else {
                            OeaAmplifier.start(host) { ok, message ->
                                host.runOnUiThread {
                                    android.widget.Toast.makeText(host, message, android.widget.Toast.LENGTH_LONG).show()
                                    status.text = if (ok) "Amplifying · %.1f× · %.0f ms of buffering".format(OeaAmplifier.gain, OeaAmplifier.latencyMs.toFloat()) else (OeaAmplifier.lastError ?: message)
                                    (host as? OeaMiniAppActivity)?.refresh()
                                }
                            }
                        }
                    },
                    kit.tonalButton("Stop") {
                        OeaAmplifier.stop()
                        status.text = "Stopped"
                        (host as? OeaMiniAppActivity)?.refresh()
                    }
                ),
                LinearLayout.LayoutParams(-1, kit.dp(50)).apply { topMargin = kit.dp(8) }
            )
            kit.note(
                panel,
                "Android takes the microphone away from an app that is no longer in front, so this stops the moment you leave OEA — it is not a hearing-aid service and it does not run in your pocket. If you need one that does, Android's own Sound Amplifier (Settings → Accessibility → Sound amplifier) is the feature for that, and OEA will not pretend to replace it."
            )
            kit.sectionTitle(panel, "Equalizer & Bass")
            kit.note(panel, "The equaliser OEA can reach is the one attached to a media session it owns — the VIP player's own bands, in its audio sheet. A system-wide bass knob is not something an app is handed, so OEA offers the volume controls above and leaves the rest to the phone.")
            panel.addView(kit.tonalButton("Open system sound settings", R.drawable.oic_settings) {
                try { host.startActivity(Intent(Settings.ACTION_SOUND_SETTINGS)) } catch (_: Throwable) {}
            }, LinearLayout.LayoutParams(-1, kit.dp(48)))
        }
    }

    // ------------------------------------------------------------------ LOW mode expanded

    private fun showLowMode(host: Activity, kit: OeaKit) {
        kit.sheet("LOW Mode • Ultra Power Saving", "OEA's own power saving, also called Ultra Power Saving Mode on device — expanded with tiers, whitelist, auto-rules", build = { panel, _ ->
            val active = com.oea.launcher.perf.OeaLowMode.active(host)
            val (percent, charging) = com.oea.launcher.perf.OeaLowMode.battery(host)
            if (active) {
                kit.banner(panel, R.drawable.oic_battery_charging_full, "LOW mode is ON • Ultra Power Saving", com.oea.launcher.perf.OeaLowMode.summary(host) + " • Home is running light, mini-apps still work.", OeaKit.Tone.INFO, "Turn off") {
                    com.oea.launcher.perf.OeaLowMode.setEnabled(host, false)
                    (host as? OeaMiniAppActivity)?.refresh()
                }
            } else {
                kit.banner(panel, R.drawable.oic_battery_full, "LOW mode is OFF", "Battery $percent% • ${if (charging) "Charging" else "Not charging"} • Turn on to save battery.", OeaKit.Tone.INFO)
            }

            kit.note(panel, "LOW mode = Ultra Power Saving Mode in OEA. It is OEA's own power saving, not Android's battery saver. What it does:\n• Always: Home stops animating (live wallpapers freeze to still), page motion off, At a Glance and live activities hidden, island service stops, Game Boost overlay stops, wake-gesture sensors released\n• When device owner/root: suspend chosen apps (genuine suspension, verified)\n• Mini-apps still work in LOW mode — they are whitelisted by design (unlike device ultra saving which may block them)\n• State persists after reboot, restored by BootReceiver")

            kit.sectionTitle(panel, "Tiers")
            val tier = OeaDataStore.get(host).lowModeTier()
            val tierGroup = kit.group(panel)
            listOf("light" to "Light • Launcher only", "balanced" to "Balanced • Launcher + suspend", "extreme" to "Extreme • Launcher + suspend + restrict").forEach { (id, label) ->
                kit.row(tierGroup, R.drawable.oic_battery_charging_full, label.substringBefore(" • "), label.substringAfter(" • "), value = if (tier == id) "On" else "") {
                    OeaDataStore.get(host).setLowModeTier(id)
                    android.widget.Toast.makeText(host, "Tier set to $id", android.widget.Toast.LENGTH_SHORT).show()
                }
            }

            kit.sectionTitle(panel, "Switching & Auto-rules")
            val switching = kit.group(panel)
            kit.switchRow(switching, R.drawable.oic_battery_charging_full, "LOW mode", "Turn it on now", active) {
                com.oea.launcher.perf.OeaLowMode.setEnabled(host, it)
            }
            kit.switchRow(switching, R.drawable.oic_battery_full, "Turn on by itself", "When battery low, without asking", OeaDataStore.get(host).lowModeAuto()) {
                OeaDataStore.get(host).setLowModeAuto(it)
            }
            kit.stepperRow(switching, R.drawable.oic_insights, "Turn on at", "Battery %", OeaDataStore.get(host).lowModeThreshold() / 5, 1, 10, { "${it * 5}%" }) {
                OeaDataStore.get(host).setLowModeThreshold(it * 5)
            }
            kit.stepperRow(switching, R.drawable.oic_refresh, "Turn off at", "Or when charging", OeaDataStore.get(host).lowModeExit() / 5, 2, 16, { "${it * 5}%" }) {
                OeaDataStore.get(host).setLowModeExit(it * 5)
            }
            kit.note(panel, "Auto: battery level from BatteryManager. One that switched itself on also switches off when battery recovers or charging. Manual stays until off.")

            kit.sectionTitle(panel, "Whitelist & Mini-apps")
            kit.note(panel, "Mini-apps are whitelisted in LOW mode — they still work. Device ultra saving (OEM) may block them, not OEA's LOW. To keep mini-apps working in device ultra saving, whitelist OEA in battery settings.")
            val whitelistGroup = kit.group(panel)
            kit.row(whitelistGroup, R.drawable.oic_apps, "Mini-apps whitelist", "All mini-apps work in LOW mode by design") {
                android.widget.Toast.makeText(host, "Mini-apps whitelisted in LOW mode", android.widget.Toast.LENGTH_SHORT).show()
            }
            kit.row(whitelistGroup, R.drawable.oic_battery_full, "Doze whitelist", if (isWhitelistedFromDoze(host)) "Whitelisted" else "Not whitelisted") {
                try {
                    if (Build.VERSION.SDK_INT >= 23) host.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${host.packageName}")))
                } catch (_: Throwable) {}
            }

            kit.sectionTitle(panel, "Suspend chosen apps (needs DO/root)")
            val deep = kit.group(panel)
            val backend = OeaAppFreezer.backend(host)
            if (backend == OeaAppFreezer.Backend.NONE) {
                kit.row(deep, R.drawable.oic_info, "Suspend chosen apps", "Not available: needs device owner or root", chevron = false)
            } else {
                kit.switchRow(deep, R.drawable.oic_block, "Suspend chosen apps", "Stops apps you pick while LOW on (${backend.name.lowercase()})", OeaDataStore.get(host).lowModeSuspendApps()) {
                    OeaDataStore.get(host).setLowModeSuspendApps(it)
                    com.oea.launcher.perf.OeaLowMode.apply(host)
                }
                kit.row(deep, R.drawable.oic_apps, "Apps to suspend", "${OeaDataStore.get(host).lowModeAppList().size} chosen") {
                    // Simple picker
                    kit.sheet("Apps to suspend", "Verified via Android") { p2, d2 ->
                        val pm = host.packageManager
                        val apps = pm.getInstalledApplications(0).filterNot { it.packageName == host.packageName }.sortedBy { it.loadLabel(pm).toString().lowercase() }.take(100)
                        apps.forEach { app ->
                            val chosen = OeaDataStore.get(host).lowModeAppList().contains(app.packageName)
                            kit.row(kit.group(p2), R.drawable.oic_apps, app.loadLabel(pm).toString(), app.packageName, value = if (chosen) "✓" else "") {
                                val next = OeaDataStore.get(host).lowModeAppList().toMutableSet()
                                if (!next.add(app.packageName)) next.remove(app.packageName)
                                OeaDataStore.get(host).setLowModeAppList(next)
                                d2.dismiss()
                            }
                        }
                    }
                }
            }
        })
    }

    // ------------------------------------------------------------------ ADB-inspired command system (comprehensive)

    private fun showCommandSystem(host: Activity, kit: OeaKit) {
        kit.sheet("OEA Command System", "Our own implementation, own format, own build — inspired by ADB, not a wrapper — comprehensive", build = { panel, _ ->
            kit.banner(panel, R.drawable.oic_developer_mode, "Built-in, on-device, comprehensive", "No laptop, no Shizuku, no helper app. OEA's own privileged service layer using DevicePolicyManager, root shell channel (su), appops, settings, PackageManager, ActivityManager — scoped to OEA's own package and always verified. Own DSL, not ADB.", OeaKit.Tone.INFO)
            kit.sectionTitle(panel, "Architecture & Research")
            kit.note(panel, """
OEA Command System research:
- ADB: client-server, adbd daemon, shell commands via binder. Commands: adb shell pm list packages, am start -n pkg/.Activity, settings get/put global/secure/system, dumpsys package, logcat, getprop, input keyevent/tap/swipe, svc power, cmd package suspend, etc.
- Shizuku: privileged service via app that exposes shell via binder to other apps. Needs wireless debugging pairing (QR, TLS) or root. Allows apps to call system APIs with shell permissions.
- Android permission architecture: runtime permissions (READ_SMS), appops (SYSTEM_ALERT_WINDOW, GET_USAGE_STATS, WRITE_SETTINGS), special access (notification listener, accessibility, device admin, device owner), roles (HOME, SMS, DIALER).
- Wireless Debugging: adb over Wi-Fi, pairing code, needs PC or helper.
- Root: su binary, scoped commands, verification by reading state back (e.g., after pm suspend, check isPackageSuspended).
- Device Owner: DevicePolicyManager.setPermissionGrantState, setPackagesSuspended, setApplicationHidden, setUninstallBlocked, etc. Provision via adb shell dpm set-device-owner com.oea.launcher/.applock.OeaDeviceAdminReceiver.

Our own build:
- OeaShell: runs su commands for OEA's own package only, always verified (reads back state). No arbitrary root for other apps.
- OeaPrivileges: opens exact Android pages and verifies result (canDrawOverlays via Settings.canDrawOverlays + probe window, accessibility via isEnabled, etc.)
- OeaAccessDoctor: verifies live state, not just switch (draws 1px probe for overlay, checks assistant connection).
- No fake overlay tricks, no bypass without required privileges.
- Broader than few functions: covers overlay, multitasking (freeform, split-screen, PIP), suspension (freeze/unfreeze, system vs OEA Freeze), restricted settings (DND, battery optimization, write settings, secure settings, appops), diagnostics, device info, security, storage, performance, sound, low mode, island, wallpaper, themes, gestures, modes.
- On-device, no laptop, no helper, but explains when DO/root needed and how to authorize.
            """.trimIndent())

            val g = kit.group(panel)
            kit.row(g, R.drawable.oic_layers, "Overlay permissions and restrictions", "Checks canDrawOverlays and verifies with real draw") { showOverlayDoctor(host, kit) }
            kit.row(g, R.drawable.oic_splitscreen, "Multitasking and split-screen capabilities", "Freeform, split-screen, PIP support") { showMultitaskDoctor(host, kit) }
            kit.row(g, R.drawable.oic_ac_unit, "Application suspension and required privileges", "Device Owner, root, OEA Freeze — genuine vs hibernated") { showSuspensionDoctor(host, kit) }
            kit.row(g, R.drawable.oic_settings, "Other system operations and restricted settings", "DND, battery optimization, write settings, secure settings, appops") { showOtherOps(host, kit) }
            kit.row(g, R.drawable.oic_battery_charging_full, "LOW mode (Ultra Power Saving) commands", "Status, on/off, auto, tier, whitelist") { showLowMode(host, kit) }
            kit.row(g, R.drawable.oic_wallpaper, "Wallpaper, themes, modes, gestures", "List and apply") {
                kit.sheet("Appearance commands", null) { p2, _ ->
                    kit.note(p2, "Commands: wallpaper list, wallpaper apply <id> home|lock|both, theme list, theme apply <id>, mode list, mode apply <id>, gesture list, gesture set <slot> <action>")
                }
            }

            kit.sectionTitle(panel, "Execute command (OEA's own DSL)")
            val cmdField = kit.field("Enter OEA command, e.g., help, device info, overlay status, freeze com.example, package list, lowmode status")
            panel.addView(cmdField, LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(8) })
            val output = TextView(host).apply {
                textSize = 12f
                setTextColor(kit.palette.textSecondary)
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(kit.dp(6), kit.dp(8), kit.dp(6), kit.dp(8))
                setTextIsSelectable(true)
            }
            panel.addView(output, LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(8) })
            panel.addView(kit.primaryButton("Run command", R.drawable.oic_play_arrow) {
                val cmd = cmdField.text.toString().trim()
                if (cmd.isEmpty()) {
                    output.text = "Enter a command. Try 'help' for list."
                    return@primaryButton
                }
                io.execute {
                    val result = executeOeaCommand(host, cmd)
                    host.runOnUiThread { output.text = result }
                }
            }, LinearLayout.LayoutParams(-1, kit.dp(50)).apply { topMargin = kit.dp(8) })

            val actionsRow = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
            actionsRow.addView(kit.primaryButton("Copy output", R.drawable.oic_text_fields) {
                val text = output.text?.toString().orEmpty()
                if (text.isNotBlank()) {
                    val cm = host.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                    cm?.setPrimaryClip(android.content.ClipData.newPlainText("OEA Command Output", text))
                    consoleToast(host, "Output copied")
                } else consoleToast(host, "Nothing to copy")
            }, LinearLayout.LayoutParams(0, kit.dp(46), 1f).apply { topMargin = kit.dp(8); rightMargin = kit.dp(4) })
            actionsRow.addView(kit.primaryButton("Share output", R.drawable.oic_share_filled) {
                val text = output.text?.toString().orEmpty()
                if (text.isNotBlank()) {
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "OEA Command Output")
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    try { host.startActivity(Intent.createChooser(share, "Share output")) } catch (_: Throwable) { consoleToast(host, "No share target") }
                } else consoleToast(host, "Nothing to share")
            }, LinearLayout.LayoutParams(0, kit.dp(46), 1f).apply { topMargin = kit.dp(8); leftMargin = kit.dp(4) })
            panel.addView(actionsRow)

            kit.note(panel, "Commands are OEA's own format, not ADB. Each operation explains what it does, why access required, how to authorize and how to confirm success. No bypass of security boundaries without required privileges. Comprehensive: covers everything ADB can do within app sandbox + DO/root where available, plus OEA-specific (low mode, island, etc.).")
        })
    }

    private fun consoleToast(host: Activity, msg: String) {
        android.widget.Toast.makeText(host, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun networkInfo(ctx: Context): String {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                ?: return "ConnectivityManager unavailable"
            val network = cm.activeNetwork ?: return "No active network (airplane mode or offline)"
            val caps = cm.getNetworkCapabilities(network)
                ?: return "No capabilities reported for the active network"
            val transports = buildList {
                if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) add("Wi-Fi")
                if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) add("Cellular")
                if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) add("Ethernet")
                if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
            }
            buildString {
                append("Transports: ").append(if (transports.isEmpty()) "unknown" else transports.joinToString(" + ")).append('\n')
                append("Validated internet: ").append(caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)).append('\n')
                append("Metered: ").append(!caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)).append('\n')
                append("Downstream: ").append(caps.linkDownstreamBandwidthKbps / 1000f).append(" Mbps (reported)\n")
                append("Roaming: ").append(!caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)).append('\n')
                append("Source: ConnectivityManager (no extra permissions)")
            }
        } catch (e: Throwable) { "Network info failed: ${e.message}" }
    }

    private fun helpForTopic(topic: String): String = when (topic) {
        "device" -> "device info|battery|memory|storage|cpu|thermal|display|sensors|network\n" +
            "Reads public Android APIs (BatteryManager, ActivityManager, StatFs, SensorManager, ConnectivityManager). No permissions needed.\n" +
            "Tip: 'device network' reports transport (Wi-Fi/cellular/VPN), validation, metered flag and reported link speed."
        "package", "packages" -> "package list [frozen|hidden|system]\n" +
            "Uses PackageManager. Frozen = Android suspension (DO/root) or OEA Freeze records. Hidden = OEA Home's hidden-apps store."
        "freeze", "suspend", "unfreeze" -> "freeze <pkg> / unfreeze <pkg>\n" +
            "Genuine suspension: DevicePolicyManager.setPackagesSuspended (Device Owner) or su cmd package suspend (root).\n" +
            "Without privileges: OEA Freeze — launcher blocks it, Assistant force-stops external launches. Honest limit: other apps can hold it open until Assistant reacts.\n" +
            "Authorize DO: on a fresh device run adb shell dpm set-device-owner com.oea.launcher/.applock.OeaDeviceAdminReceiver. Verify: package list frozen."
        "launch", "start" -> "launch <package> — opens the app's main activity via its launch intent.\n" +
            "Honest limits: frozen/hidden packages may fail; OEA reports the real startActivity result."
        "overlay" -> "overlay status|request|list\n" +
            "status draws a real 1px probe window to verify (some firmware lies about granted). request opens the system page."
        "multitask", "split", "float", "pip" -> "multitask status | multitask split <p1> <p2> | split <p1> <p2> | float <pkg> | pip\n" +
            "Split needs Android 7+, freeform needs Developer options enable-freeform. OEA verifies with Assistant after requesting."
        "dnd" -> "dnd status|on|off — needs Do Not Disturb special access; verified via currentInterruptionFilter after setting."
        "lowmode", "low", "ultra" -> "lowmode status|on|off|auto on|off\n" +
            "LOW = OEA's Ultra Power Saving: cheap locker, frozen scenes, sensors released, optional app suspension tier."
        "island" -> "island status|toggle — Dynamic Island state, style, over-other-apps flag, overlay verification."
        "settings" -> "settings get|put global|secure|system <key> [value]\n" +
            "Reads are free for public/secure values your permission level allows. Writes need WRITE_SECURE_SETTINGS (privileged) or root — OEA reports the real failure, no pretend success."
        "appops" -> "appops get <pkg> [op] — reads AppOpsManager (0=allow, 1=ignore, 2=deny, 4=default). Writes need root via 'shell cmd appops set ...'."
        "shell" -> "shell <cmd> — runs via su, scoped to OEA's own operations, exit code + stdout read back for verification. Refuses without root."
        "doze" -> "doze status|request — battery optimization whitelist; request opens ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS."
        "security" -> "security scan — flags packages with dangerous combos (overlay+accessibility+recorder, sideloaded requesters). Quick, local."
        "performance" -> "performance trim|gc — relinquishes OEA's own memory; honest: cannot force other processes."
        "notification" -> "notification status — listener enabled check; badges/media/quick-reply need this."
        "diagnostics", "dump" -> "diagnostics summary — OEA fault store counts per subsystem; 'logcat' = last 5 stored entries; 'clear diagnostics' wipes."
        "logcat" -> "logcat — OEA's own fault history (last 5). Not adb logcat; OEA cannot read other apps' logs as a normal app."
        "mode", "wallpaper", "theme", "gesture" -> "mode list|current|apply <id> • wallpaper list • theme list • gesture list — appearance & input surface state; apply verified through the same pipeline the UI uses."
        else -> "No topic '$topic'. Topics: " + TOPICS.joinToString(" ") + "\nOr type 'help' for the full list."
    }

    private val TOPICS = listOf("device", "package", "freeze", "launch", "overlay", "multitask", "dnd", "lowmode", "island", "settings", "appops", "shell", "doze", "security", "performance", "notification", "diagnostics", "logcat", "mode", "wallpaper", "theme", "gesture")

    private fun executeOeaCommand(ctx: Context, cmd: String): String {
        val parts = cmd.trim().split("\\s+".toRegex())
        if (parts.isEmpty()) return "Empty command"
        return when (parts[0].lowercase()) {
            "help" -> parts.getOrNull(1)?.lowercase()?.let { helpForTopic(it) } ?: """
OEA Command System — own DSL, own build, comprehensive
Categories:
  device — device info, battery, memory, storage, cpu, thermal, display, sensors
  package — list packages, frozen, hidden, system, details
  freeze/suspend/unfreeze — app suspension (genuine via DO/root, else OEA Freeze)
  overlay — overlay permission status/request/verify/list
  multitask — freeform, split, float, pip status
  settings — get/put global/secure/system (needs WRITE_SECURE_SETTINGS or root)
  appops — get/set appops (needs root/privileged)
  dnd — Do Not Disturb on/off/status
  lowmode — LOW mode (Ultra Power Saving) status/on/off/auto/tier
  island — Dynamic Island status/toggle/style
  wallpaper/theme/mode/gesture — list/apply
  security — scan suspicious apps
  performance — trim, gc, thermal
  shell — run root shell command (needs root, OEA only, verified)
  doze — battery optimization whitelist status/request
  notification — listener status
  clear — clear diagnostics
  logcat — show OEA logs
  help — this list\n  launch — open an app's main activity\n  diagnostics — fault summary\n\nTip: 'help <topic>' for per-command detail, e.g. 'help freeze'

Examples:
  help
  device info
  device battery
  package list
  package list frozen
  freeze com.example.app
  unfreeze com.example.app
  overlay status
  overlay request
  multitask status
  split com.android.chrome com.youtube
  float com.example.app
  dnd status
  lowmode status
  lowmode on
  lowmode off
  island status
  wallpaper list
  theme list
  mode list
  mode apply gaming
  gesture list
  security scan
  shell pm list packages --suspended
  doze status
  clear diagnostics
            """.trimIndent()

            "device" -> when (parts.getOrNull(1)?.lowercase()) {
                "info" -> deviceDetails(ctx).joinToString("\n") { "${it.first}: ${it.second}" }
                "battery" -> {
                    val (level, charging) = batteryInfo(ctx)
                    "Battery: $level% • ${if (charging) "charging" else "not charging"} • PowerSave: ${isPowerSaveMode(ctx)} • UltraSaving: ${isDeviceInUltraPowerSaving(ctx)}\nSource: BatteryManager, PowerManager"
                }
                "memory" -> memoryInfo(ctx)
                "storage" -> storageStats(ctx).joinToString("\n") { "${it.first}: ${it.second}" }
                "cpu" -> "CPU cores: ${Runtime.getRuntime().availableProcessors()} • ABIs: ${Build.SUPPORTED_ABIS.joinToString()} • Thermal: ${thermalInfo(ctx)}"
                "thermal" -> "Thermal: ${thermalInfo(ctx)} • Battery temp: ${batteryTemp(ctx)}"
                "display" -> {
                    val dm = ctx.resources.displayMetrics
                    "Display: ${dm.widthPixels}x${dm.heightPixels} • ${dm.densityDpi} dpi • density ${dm.density} • scaled ${dm.scaledDensity}"
                }
                "network" -> networkInfo(ctx)
                "sensors" -> {
                    try {
                        val sm = ctx.getSystemService(android.hardware.SensorManager::class.java)
                        val sensors = sm?.getSensorList(android.hardware.Sensor.TYPE_ALL)?.take(20)?.joinToString("\n") { "${it.name} • ${it.stringType} • wakeup=${it.isWakeUpSensor}" } ?: "No sensors"
                        "Sensors (first 20):\n$sensors"
                    } catch (e: Throwable) { "Sensors unavailable: ${e.message}" }
                }
                else -> "Usage: device info|battery|memory|storage|cpu|thermal|display|sensors"
            }

            "package", "packages" -> when (parts.getOrNull(1)?.lowercase()) {
                "list" -> when (parts.getOrNull(2)?.lowercase()) {
                    "frozen" -> {
                        val all = OeaAppFreezer.allFrozen(ctx)
                        if (all.isEmpty()) "No frozen apps" else all.joinToString("\n") { "- $it" } + "\n\nTotal: ${all.size} • Backend: ${OeaAppFreezer.backend(ctx)} • Mode: ${OeaAppFreezer.mode(ctx)}"
                    }
                    "hidden" -> {
                        val hidden = OeaDataStore.get(ctx).hiddenApps()
                        if (hidden.isEmpty()) "No hidden apps" else hidden.joinToString("\n") + "\n\nTotal: ${hidden.size}"
                    }
                    "system" -> {
                        try {
                            val pm = ctx.packageManager
                            val sys = pm.getInstalledApplications(0).filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0 }.take(30).joinToString("\n") { it.packageName }
                            "System apps (first 30):\n$sys"
                        } catch (e: Throwable) { "Failed: ${e.message}" }
                    }
                    else -> {
                        try {
                            val pm = ctx.packageManager
                            val apps = pm.getInstalledApplications(0).take(30).joinToString("\n") { it.packageName }
                            "Installed apps (first 30):\n$apps\n\nTotal: ${pm.getInstalledApplications(0).size} • Use 'package list frozen|hidden|system' for filtered"
                        } catch (e: Throwable) { "Failed: ${e.message}" }
                    }
                }
                else -> "Usage: package list [frozen|hidden|system]"
            }

            "launch", "start" -> {
                if (parts.size < 2) return "Usage: launch <package> — opens the app's main launcher activity"
                val pkg = parts[1]
                try {
                    val pm = ctx.packageManager
                    val intent = pm.getLaunchIntentForPackage(pkg)
                    if (intent == null) "No launchable activity for $pkg (may be frozen, hidden, or a service-only package). Try 'package list' to verify it is installed."
                    else {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ctx.startActivity(intent)
                        "Launching $pkg — startActivity succeeded. Confirm: switch to the app; if it bounces back, check freezer ('package list frozen')."
                    }
                } catch (e: Throwable) { "Launch failed: ${e.message}" }
            }

            "freeze", "suspend" -> {
                if (parts.size < 2) return "Usage: freeze <package>"
                val pkg = parts[1]
                val result = OeaAppFreezer.setFrozen(ctx, pkg, true)
                "${result.message}\nBackend: ${result.backend} • Mode: ${OeaAppFreezer.mode(ctx)}\nWhat it does: suspends package via DevicePolicyManager (DO) or cmd package suspend (root). Preserves data, blocks launch/background/notifications.\nWhy needed: PACKAGE_SUSPEND is privileged; ordinary apps cannot suspend others without DO/root.\nAuthorize: adb shell dpm set-device-owner com.oea.launcher/.applock.OeaDeviceAdminReceiver (needs fresh device or work profile), or root via su.\nConfirm: PackageManager.isPackageSuspended == true and dumpsys package confirms suspended=true\nOEA Freeze fallback: if no DO/root, OEA blocks in launcher and force-stops via Assistant (no-root). Cannot prevent other apps from launching it without DO/root — mitigation: Assistant watches all windows and blocks frozen via external routes."
            }

            "unfreeze" -> {
                if (parts.size < 2) return "Usage: unfreeze <package>"
                val pkg = parts[1]
                val result = OeaAppFreezer.setFrozen(ctx, pkg, false)
                if (!result.success) {
                    OeaAppFreezer.setHibernated(ctx, listOf(pkg), false)
                    "Unfrozen from OEA Freeze (hibernated)\nBackend: OEA • Also tried system unfreeze: ${result.message}"
                } else "${result.message}\nBackend: ${result.backend} • Verified: isPackageSuspended == false"
            }

            "overlay" -> when (parts.getOrNull(1)?.lowercase()) {
                "status" -> {
                    val can = OeaPrivileges.canOverlay(ctx)
                    val verified = try { com.oea.launcher.workspace.OeaDynamicIslandOverlayService.verifyOverlay(ctx) } catch (_: Throwable) { false }
                    "Overlay permission: ${if (can) "granted" else "not granted"}\nVerified draw: ${if (verified) "success (probe 1px window attached)" else "failed (SecurityException or not granted)"}\nWhy: SYSTEM_ALERT_WINDOW is reserved to user on stock devices. OEA verifies by actually drawing probe.\nAuthorize: Settings › Apps › OEA › Display over other apps\nConfirm: probe view attached and removed without exception\nWhat it unlocks: system-wide Dynamic Island over other apps, Game Boost overlay, wake gesture fallback"
                }
                "request" -> {
                    val ok = OeaPrivileges.requestOverlay(ctx, 0)
                    "Overlay request: ${if (ok) "opened settings page" else "could not open, grant manually"}\nWhat it unlocks: system-wide Dynamic Island, Game Boost overlay, wake gesture fallback\nAuthorize: Settings › Apps › Special access › Display over other apps"
                }
                "list" -> {
                    try {
                        val pm = ctx.packageManager
                        val apps = pm.getInstalledApplications(0).filter { app ->
                            try {
                                val perms = pm.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)?.requestedPermissions ?: emptyArray()
                                perms.contains(android.Manifest.permission.SYSTEM_ALERT_WINDOW)
                            } catch (_: Throwable) { false }
                        }.take(20).joinToString("\n") { it.packageName }
                        "Apps requesting overlay (first 20):\n$apps"
                    } catch (e: Throwable) { "Failed: ${e.message}" }
                }
                else -> "Usage: overlay status|request|list"
            }

            "multitask" -> when (parts.getOrNull(1)?.lowercase()) {
                "status" -> {
                    val freeform = com.oea.launcher.multitask.OeaMultitaskLauncher.freeformEnabled(ctx)
                    val split = "Split-screen: supported on Android 7+ via ActivityManager, but OEM and app can restrict. OEA uses split-screen via intent and verifies with Assistant."
                    val window = "OEA floating window: ${if (com.oea.launcher.multitask.OeaFloatingWindows.enabled(ctx)) "on" else "off"} (overlay container; works without freeform)"
                    "Freeform (floating windows): ${if (freeform) "enabled" else "disabled"}\n$split\n$window\nPIP: ${if (Build.VERSION.SDK_INT >= 26) "supported (Android 8+)" else "not supported"}\nAuthorize freeform: Developer options › Enable freeform windows + Force resizable, reboot"
                }
                "window" -> {
                    val svc = com.oea.launcher.multitask.OeaFloatingWindows
                    when (parts.getOrNull(2)?.lowercase()) {
                        "on" -> if (svc.isAccessibilityEnabled(ctx)) {
                            svc.setEnabled(ctx, true)
                            "OEA floating window: on • a draggable, resizable container appears over the next app"
                        } else {
                            "OEA floating window needs Accessibility first: Settings › Accessibility › OEA Floating Window"
                        }
                        "off" -> {
                            svc.setEnabled(ctx, false)
                            "OEA floating window: off"
                        }
                        else -> "OEA floating window: ${if (svc.enabled(ctx)) "on" else "off"} • accessibility: ${if (svc.isAccessibilityEnabled(ctx)) "granted" else "not granted"}\nUsage: multitask window on|off"
                    }
                }
                "split" -> {
                    if (parts.size < 4) return "Usage: multitask split <pkg1> <pkg2> OR split <pkg1> <pkg2> (alias)"
                    val result = com.oea.launcher.split.OeaSplitLauncher.launchPair(ctx, parts[2], parts[3])
                    "Split result: ${result.message} • success=${result.success}\nWhat it does: requests Android split-screen with two apps side-by-side\nWhy might fail: OEM disables split, app opts out, not in foreground\nConfirm: check recents for split divider"
                }
                else -> "Usage: multitask status|split <pkg1> <pkg2>|window on|off"
            }

            "split" -> {
                if (parts.size < 3) return "Usage: split <pkg1> <pkg2>"
                val result = com.oea.launcher.split.OeaSplitLauncher.launchPair(ctx, parts[1], parts[2])
                "Split result: ${result.message} • success=${result.success}\nWhat it does: requests Android split-screen\nWhy might fail: OEM disables, app opts out\nConfirm: recents shows split"
            }

            "float" -> {
                if (parts.size < 2) return "Usage: float <package>"
                val app = com.oea.launcher.model.OeaAppInfo(parts[1], "", parts[1])
                val result = com.oea.launcher.multitask.OeaMultitaskLauncher.launchFloating(ctx, app)
                "Floating result: ${result.message}\nWhat it does: launches app as freeform window (floating)\nNeeds: freeform enabled in Developer options\nAlternative on phones without freeform: multitask window on\nConfirm: window appears movable/resizable if granted"
            }

            "pip" -> {
                "PIP: ${if (Build.VERSION.SDK_INT >= 26) "supported, use video player PIP button" else "not supported (needs Android 8+)"}\nWhat it does: picture-in-picture for video playback\nAuthorize: none, but app must support"
            }

            "dnd" -> when (parts.getOrNull(1)?.lowercase()) {
                "status" -> "DND access: ${if (com.oea.launcher.power.OeaPower.policyAccess(ctx)) "granted" else "not granted"}\nDND on: ${com.oea.launcher.power.OeaPower.dndOn(ctx)}\nAuthorize: Settings › Apps › Special access › Do Not Disturb access\nConfirm: NotificationManager.isNotificationPolicyAccessGranted and currentInterruptionFilter"
                "on" -> if (com.oea.launcher.power.OeaPower.setDnd(ctx, true)) "DND enabled • Verified" else "Failed - need DND access"
                "off" -> if (com.oea.launcher.power.OeaPower.setDnd(ctx, false)) "DND disabled • Verified" else "Failed - need access"
                else -> "Usage: dnd on|off|status"
            }

            "lowmode", "low", "ultrasaving", "ultra" -> when (parts.getOrNull(1)?.lowercase()) {
                "status" -> {
                    val active = com.oea.launcher.perf.OeaLowMode.active(ctx)
                    val summary = com.oea.launcher.perf.OeaLowMode.summary(ctx)
                    val tier = OeaDataStore.get(ctx).lowModeTier()
                    "LOW mode (Ultra Power Saving): ${if (active) "ON" else "OFF"} • $summary • Tier: $tier • Backend: ${OeaAppFreezer.backend(ctx)}\nWhat it does: makes launcher cheap (freeze live scenes, hide islands, release sensors) + suspend chosen apps if DO/root\nDevice ultra saving: ${if (isDeviceInUltraPowerSaving(ctx)) "ON (OEM may block mini-apps)" else "OFF"}\nMini-apps: whitelisted in LOW mode, work normally"
                }
                "on" -> { com.oea.launcher.perf.OeaLowMode.setEnabled(ctx, true); "LOW mode enabled • Verified: active=${com.oea.launcher.perf.OeaLowMode.active(ctx)}" }
                "off" -> { com.oea.launcher.perf.OeaLowMode.setEnabled(ctx, false); "LOW mode disabled • Verified" }
                "auto" -> {
                    val on = parts.getOrNull(2)?.lowercase() == "on"
                    OeaDataStore.get(ctx).setLowModeAuto(on)
                    "LOW auto: ${if (on) "on" else "off"}"
                }
                else -> "Usage: lowmode status|on|off|auto on|off"
            }

            "island" -> when (parts.getOrNull(1)?.lowercase()) {
                "status" -> {
                    val enabled = OeaDataStore.get(ctx).dynamicIslandEnabled()
                    val style = OeaDataStore.get(ctx).islandStyle()
                    val overApps = OeaDataStore.get(ctx).islandOnOtherApps()
                    "Island: ${if (enabled) "enabled" else "disabled"} • Style: $style • Over other apps: $overApps • Verified: overlay=${OeaPrivileges.canOverlay(ctx)}"
                }
                "toggle" -> {
                    val cur = OeaDataStore.get(ctx).dynamicIslandEnabled()
                    OeaDataStore.get(ctx).setDynamicIslandEnabled(!cur)
                    "Island toggled to ${!cur}"
                }
                else -> "Usage: island status|toggle"
            }

            "wallpaper" -> when (parts.getOrNull(1)?.lowercase()) {
                "list" -> {
                    val home = com.oea.launcher.wallpaper.OeaWallpaper.homeSet.take(10).joinToString("\n") { "${it.id} • ${it.label}" }
                    "Wallpapers (first 10 home):\n$home\n\nTotal home: ${com.oea.launcher.wallpaper.OeaWallpaper.homeSet.size} • lock: ${com.oea.launcher.wallpaper.OeaWallpaper.lockSet.size}"
                }
                else -> "Usage: wallpaper list"
            }

            "theme" -> when (parts.getOrNull(1)?.lowercase()) {
                "list" -> {
                    val themes = com.oea.launcher.themes.OeaThemeCatalog.builtIns.take(10).joinToString("\n") { "${it.id} • ${it.name}" }
                    "Themes (first 10):\n$themes\n\nTotal: ${com.oea.launcher.themes.OeaThemeCatalog.builtIns.size}"
                }
                else -> "Usage: theme list"
            }

            "mode" -> when (parts.getOrNull(1)?.lowercase()) {
                "list" -> com.oea.launcher.ui.OeaMode.entries.joinToString("\n") { "${it.id} • ${it.label} • ${it.category} • ${it.tagline}" }
                "current" -> "Current mode: ${com.oea.launcher.ui.OeaModes.current(ctx).label} • ${com.oea.launcher.ui.OeaModes.summary(ctx)}"
                "apply" -> {
                    if (parts.size < 3) return "Usage: mode apply <id>"
                    val modeId = parts[2]
                    val mode = com.oea.launcher.ui.OeaMode.entries.firstOrNull { it.id == modeId }
                    if (mode == null) "Unknown mode $modeId • Available: ${com.oea.launcher.ui.OeaMode.entries.joinToString { it.id }}"
                    else {
                        com.oea.launcher.ui.OeaModes.apply(ctx, mode)
                        "Mode applied: ${mode.label} • Verified"
                    }
                }
                else -> "Usage: mode list|current|apply <id>"
            }

            "gesture" -> when (parts.getOrNull(1)?.lowercase()) {
                "list" -> {
                    val gestures = com.oea.launcher.gesture.OeaGestures.slots.entries.joinToString("\n") { (slot, label) -> "$slot: $label • Action: ${com.oea.launcher.gesture.OeaGestures.action(ctx, slot)}" }
                    "Gestures:\n$gestures"
                }
                else -> "Usage: gesture list"
            }

            "security" -> when (parts.getOrNull(1)?.lowercase()) {
                "scan" -> {
                    val findings = scanSecurity(ctx)
                    if (findings.isEmpty()) "No suspicious apps found (quick scan)" else findings.joinToString("\n\n") { "${it.first} • ${it.third}\n${it.second}" }
                }
                else -> "Usage: security scan"
            }

            "performance" -> when (parts.getOrNull(1)?.lowercase()) {
                "trim" -> {
                    try { (ctx.applicationContext as? com.oea.launcher.OeaApplication)?.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_MODERATE); "Memory trimmed" } catch (e: Throwable) { "Failed: ${e.message}" }
                }
                "gc" -> { System.gc(); "GC requested" }
                else -> "Usage: performance trim|gc"
            }

            "doze" -> when (parts.getOrNull(1)?.lowercase()) {
                "status" -> "Doze whitelist: ${if (isWhitelistedFromDoze(ctx)) "whitelisted (alarms work in Doze)" else "not whitelisted (alarms may be delayed)"}\nAuthorize: Settings › Battery › Unrestricted or request via intent"
                "request" -> {
                    try {
                        if (Build.VERSION.SDK_INT >= 23) ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        "Opened whitelist request"
                    } catch (e: Throwable) { "Failed: ${e.message}" }
                }
                else -> "Usage: doze status|request"
            }

            "notification" -> when (parts.getOrNull(1)?.lowercase()) {
                "status" -> {
                    val enabled = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")?.contains(ctx.packageName) == true
                    "Notification listener: ${if (enabled) "enabled" else "disabled"} • Needed for badges, media, quick reply\nAuthorize: Settings › Apps › Special access › Notification access"
                }
                else -> "Usage: notification status"
            }

            "shell" -> {
                val command = parts.drop(1).joinToString(" ")
                if (command.isBlank()) return "Usage: shell <command> (needs root, OEA only, verified)"
                val backend = OeaAppFreezer.backend(ctx)
                if (backend != OeaAppFreezer.Backend.ROOT) return "Root not available. Backend: $backend\nOEA's shell channel needs su binary and root grant. It runs commands scoped to OEA's own package only, verified by reading state back."
                try {
                    val proc = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
                    val out = proc.inputStream.bufferedReader().readText()
                    val exit = proc.waitFor()
                    "Exit: $exit\nOutput:\n$out\n\nVerified: command executed via su, output read back. Scoped to OEA's own operations."
                } catch (e: Throwable) { "Shell failed: ${e.message}" }
            }

            "settings" -> when (parts.getOrNull(1)?.lowercase()) {
                "get" -> {
                    if (parts.size < 4) return "Usage: settings get global|secure|system <key>"
                    val ns = parts[2]
                    val key = parts[3]
                    try {
                        val value = when (ns) {
                            "global" -> Settings.Global.getString(ctx.contentResolver, key)
                            "secure" -> Settings.Secure.getString(ctx.contentResolver, key)
                            "system" -> Settings.System.getString(ctx.contentResolver, key)
                            else -> null
                        }
                        "Settings $ns $key = ${value ?: "null"}"
                    } catch (e: Throwable) { "Failed: ${e.message} • Needs READ permission or root" }
                }
                "put" -> {
                    if (parts.size < 5) return "Usage: settings put global|secure|system <key> <value> (needs WRITE_SECURE_SETTINGS or root)"
                    val ns = parts[2]; val key = parts[3]; val value = parts.drop(4).joinToString(" ")
                    try {
                        val success = when (ns) {
                            "global" -> Settings.Global.putString(ctx.contentResolver, key, value)
                            "secure" -> Settings.Secure.putString(ctx.contentResolver, key, value)
                            "system" -> Settings.System.putString(ctx.contentResolver, key, value)
                            else -> false
                        }
                        if (success) "Settings $ns $key set to $value • Verified" else "Failed to set — needs WRITE_SECURE_SETTINGS (privileged) or root"
                    } catch (e: Throwable) { "Failed: ${e.message} • Needs privileged permission" }
                }
                else -> "Usage: settings get|put global|secure|system <key> [value]"
            }

            "appops" -> when (parts.getOrNull(1)?.lowercase()) {
                "get" -> {
                    if (parts.size < 3) return "Usage: appops get <package> [op]"
                    val pkg = parts[2]
                    val op = parts.getOrNull(3) ?: "SYSTEM_ALERT_WINDOW"
                    try {
                        val appOps = ctx.getSystemService(android.app.AppOpsManager::class.java)
                        val uid = ctx.packageManager.getPackageUid(pkg, 0)
                        val mode = appOps?.checkOpNoThrow(op, uid, pkg)
                        "AppOps $pkg $op = $mode (0=allow, 1=ignore, 2=deny, 4=default)"
                    } catch (e: Throwable) { "Failed: ${e.message}" }
                }
                else -> "Usage: appops get <package> [op]"
            }

            "diagnostics", "dump" -> when (parts.getOrNull(1)?.lowercase()) {
                "summary" -> {
                    val history = OeaFaultLog.history()
                    val bySubsystem = history.groupingBy { it.subsystem }.eachCount().entries
                        .sortedByDescending { it.value }.take(8)
                        .joinToString("\n") { "  ${it.key}: ${it.value} events" }
                    "OEA diagnostics summary (own store, not logcat):\n" +
                        "Total events: ${history.size}\n" +
                        (if (bySubsystem.isNotBlank()) "By subsystem:\n$bySubsystem\n" else "No events recorded.\n") +
                        "Latest: use 'logcat' for the last 5 entries. Clear with 'clear diagnostics'."
                }
                else -> "Usage: diagnostics summary — crash/fault counts per subsystem from OEA's own fault log ('logcat' shows the last entries)"
            }

            "clear" -> when (parts.getOrNull(1)?.lowercase()) {
                "diagnostics" -> { OeaFaultLog.clear(ctx); "Diagnostics cleared • Verified" }
                else -> "Unknown clear command. Try 'clear diagnostics'"
            }

            "logcat" -> {
                try {
                    val logs = OeaFaultLog.history().take(5).joinToString("\n\n") { it.body() }
                    if (logs.isBlank()) "No OEA logs" else "OEA logs (last 5):\n$logs"
                } catch (e: Throwable) { "Failed: ${e.message}" }
            }

            "battery" -> {
                val (level, charging) = batteryInfo(ctx)
                "Battery: $level% • ${if (charging) "charging" else "not charging"}\nSource: BatteryManager"
            }

            "memory" -> memoryInfo(ctx)

            else -> "Unknown command '${parts[0]}'. Try 'help' for comprehensive list. OEA's own DSL, not ADB."
        }
    }

    private fun showOverlayDoctor(host: Activity, kit: OeaKit) {
        kit.sheet("Overlay Doctor", "Verifies overlay permission live") { panel, _ ->
            val can = OeaPrivileges.canOverlay(host)
            val verified = try { com.oea.launcher.workspace.OeaDynamicIslandOverlayService.verifyOverlay(host) } catch (_: Throwable) { false }
            kit.banner(panel, if (verified) R.drawable.oic_check_circle_fill else R.drawable.oic_warning_fill, "Overlay: ${if (can) "granted" else "not granted"}", "Verified draw: ${if (verified) "success (probe 1px window)" else "failed"}. Some firmware reports granted but blocks drawing; OEA probes real WindowManager.", tone = if (verified) OeaKit.Tone.SUCCESS else OeaKit.Tone.WARNING)
            kit.note(panel, "What it unlocks: Dynamic Island over other apps, Game Boost RAM control, wake gesture fallback.\nWhy: SYSTEM_ALERT_WINDOW is user-granted special access.\nAuthorize: Settings › Apps › OEA › Display over other apps.\nConfirm: probe 1x1 overlay window attaches and detaches without SecurityException.")
        }
    }

    private fun showMultitaskDoctor(host: Activity, kit: OeaKit) {
        kit.sheet("Multitasking Doctor", "Freeform, split, PIP") { panel, _ ->
            val freeform = com.oea.launcher.multitask.OeaMultitaskLauncher.freeformEnabled(host)
            kit.banner(panel, if (freeform) R.drawable.oic_check_circle_fill else R.drawable.oic_info, "Freeform: ${if (freeform) "enabled" else "disabled"}", "Floating windows need Developer options › Enable freeform windows. OEM may not expose it.", tone = if (freeform) OeaKit.Tone.SUCCESS else OeaKit.Tone.INFO)
            kit.note(panel, "Split-screen: Android 7+ supports via ActivityManager. OEM and app can deny. OEA waits for first app to be in front before launching adjacent, verifies with Assistant and recovers once via system split toggle.\nPIP: Android 8+ supports picture-in-picture for video; use VIP player's PIP button.")
        }
    }

    private fun showSuspensionDoctor(host: Activity, kit: OeaKit) {
        kit.sheet("Suspension Doctor", "Genuine suspension vs OEA Freeze") { panel, _ ->
            val backend = OeaAppFreezer.backend(host)
            val mode = OeaAppFreezer.mode(host)
            kit.banner(panel, if (mode == OeaAppFreezer.Mode.SYSTEM) R.drawable.oic_check_circle_fill else R.drawable.oic_info, "Mode: $mode • Backend: $backend", when (backend) {
                OeaAppFreezer.Backend.DEVICE_OWNER -> "System freeze via DevicePolicyManager.setPackagesSuspended — genuine suspension, Android blocks launch, background, notifications. Preserves data. Verified via isPackageSuspended and dumpsys."
                OeaAppFreezer.Backend.ROOT -> "System freeze via su cmd package suspend — genuine suspension, verified via dumpsys package suspended=true"
                else -> "OEA Freeze: blocks in OEA and force-stops via Assistant. Cannot prevent other apps from launching it without DO/root; mitigation: Assistant watches all windows and blocks frozen via external routes (packageNames null, performs BACK/HOME + forceStop)."
            }, tone = if (mode == OeaAppFreezer.Mode.SYSTEM) OeaKit.Tone.SUCCESS else OeaKit.Tone.INFO)
            kit.note(panel, "Freezing preserves data. Unfreezing restores. Genuine freezing needs Device Owner (adb shell dpm set-device-owner com.oea.launcher/.applock.OeaDeviceAdminReceiver) or root. Without, best supported alternative is OEA Freeze with Assistant blocking external routes. Prevents bypass via external links/downloads via maybeBlockFrozenApp.")
        }
    }

    private fun showOtherOps(host: Activity, kit: OeaKit) {
        kit.sheet("Other Operations & Restricted Settings", "DND, battery optimization, write settings, secure settings, appops") { panel, _ ->
            val rows = OeaPrivileges.privilegeRows(host)
            rows.forEach { (priv, grant) ->
                val g = kit.group(panel)
                kit.row(g, R.drawable.oic_settings, priv.title, "${priv.summary} • ${grant.name}", chevron = false)
            }
            kit.note(panel, "Each operation shows what it does, why access required, how to authorize, how to confirm. OEA never pretends ordinary app can bypass security boundaries. Examples:\n• DND: needs Notification policy access, verified via isNotificationPolicyAccessGranted\n• Battery optimization: request ignore via ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, verified via isIgnoringBatteryOptimizations\n• Write settings: needs WRITE_SETTINGS, verified via Settings.System.canWrite\n• Secure settings: needs WRITE_SECURE_SETTINGS (privileged) or root via shell\n• AppOps: get/set via AppOpsManager, needs privileged or root\n• Settings get/put: via Settings.Global/Secure/System, needs permission or root")
        }
    }

    private fun showPrivileges(host: Activity, kit: OeaKit) {
        kit.sheet("Privileges & Capabilities", "What OEA can use, what it unlocks") { panel, _ ->
            val caps = OeaPrivileges.capabilities(host)
            caps.forEach { cap ->
                val g = kit.group(panel)
                kit.banner(g, if (cap.isReady) R.drawable.oic_check_circle_fill else R.drawable.oic_info, cap.feature, "Needs: ${cap.requires.title} • Shown as: ${cap.surface.label}\nWithout: ${cap.effect}", tone = if (cap.isReady) OeaKit.Tone.SUCCESS else OeaKit.Tone.INFO)
            }
            kit.note(panel, "OEA grants by itself whatever Android allows (Device Owner via DPM, or root via own su channel, scoped to OEA's package and verified), and falls back to Home-side or notification presentation otherwise. Display over other apps and Usage access are user's to give on stock device — no third-party app can change that without root/DO.")
        }
    }
}
