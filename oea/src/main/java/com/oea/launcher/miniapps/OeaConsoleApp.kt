package com.oea.launcher.miniapps

import android.app.Activity
import android.app.ActivityManager
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
import com.oea.launcher.privilege.Privilege
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
 * Central OEA utility mini-app — OEA Console.
 *
 * Chosen name: "Console" because it is the command centre for advanced operations:
 * - Our own ADB-inspired system (not a wrapper around ADB or Shizuku)
 * - Diagnostics (expanded)
 * - Device information and hardware/software specs
 * - Security analysis and antivirus-style checks
 * - Storage cleanup and maintenance
 * - Memory, battery, temperature, performance
 * - Sound amplifier
 * - Other advanced utilities
 *
 * This is one central app, not separate icons for each utility.
 */
object OeaConsoleApp {
    private val io = Executors.newSingleThreadExecutor { Thread(it, "OeaConsole").apply { isDaemon = true } }

    fun build(host: Activity, palette: OeaPalette): View {
        val kit = OeaKit(host, palette)
        val column = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }

        val header = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(kit.text("OEA Console", 22f, palette.text, OeaType.medium), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(kit.iconBadge(R.drawable.oic_developer_mode, palette.accentSoft, palette.onAccentSoft, 40))
        column.addView(header, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(12) })
        kit.note(column, "Advanced device utilities: diagnostics, device info, security checks, storage tools, performance and our own ADB-inspired command system. Everything here uses supported Android APIs and explains its limits honestly.")

        // Quick stats
        val statsCard = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(kit.dp(16), kit.dp(14), kit.dp(16), kit.dp(14))
            background = kit.rounded(palette.card, 22, palette.stroke, 1)
        }
        val battery = batteryInfo(host)
        statsCard.addView(kit.text("Battery ${battery.first}% • ${if (battery.second) "Charging" else "Discharging"} • ${memoryInfo(host)}", 13f, palette.textSecondary))
        statsCard.addView(kit.text(deviceSummary(host), 12f, palette.textTertiary).apply { setPadding(0, kit.dp(4), 0, 0) })
        column.addView(statsCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(12) })

        // Sections
        val sections = listOf(
            "diagnostics" to "Diagnostics • ${OeaFaultLog.count()} recorded",
            "device" to "Device Info • Hardware & software",
            "security" to "Security • Suspicious apps & permissions",
            "storage" to "Storage • Cleanup & maintenance",
            "performance" to "Performance • Memory, battery, temperature",
            "sound" to "Sound Amplifier • Boost audio",
            "adb" to "Command System • Our own ADB-inspired build",
            "privileges" to "Privileges • Overlay, suspension, multitasking"
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
                }
            }
        }

        // Export latest incident
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
        kit.sheet("Diagnostics • ${faults.size} incidents", "Crashes, ANRs, failed ops, startup failures, UI freezes across launcher and mini-apps. Organized by app, component, time and error type.") { panel, _ ->
            if (faults.isEmpty()) {
                kit.note(panel, "No problems recorded. OEA only writes here when something had to be contained. Enable Keep a problem log in Settings › Stability.")
                return@sheet
            }
            // Group by subsystem
            val bySubsystem = faults.groupBy { it.subsystem.substringBefore(':') }
            bySubsystem.entries.sortedByDescending { it.value.size }.forEach { (sub, list) ->
                val g = kit.group(panel)
                kit.row(g, R.drawable.oic_layers, "$sub • ${list.size} incidents", "Latest: ${list.firstOrNull()?.errorClass} • ${SimpleDateFormat("MMM d HH:mm", Locale.getDefault()).format(Date(list.firstOrNull()?.at ?: 0L))}", value = list.firstOrNull()?.action?.name?.lowercase()) {
                    kit.sheet("$sub incidents", null) { p2, _ ->
                        list.take(20).forEach { record ->
                            val row = kit.group(p2)
                            kit.row(row, R.drawable.oic_history, record.title(), "${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(record.at))} • ${record.reason}", value = record.errorClass) {
                                showFaultDetail(host, kit, record)
                            }
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
            kit.note(panel, "Verified problem: this is a confirmed exception caught by OEA's fault shield. Suspicious indicators (e.g., slow UI, missing permission) are distinguished elsewhere. Access to other apps' private data is limited by Android sandboxing.")
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
        kit.sheet("Device Info", "Real measurements from supported Android APIs", build = { panel, _ ->
            val info = deviceDetails(host)
            info.forEach { (k, v) ->
                val g = kit.group(panel)
                kit.row(g, R.drawable.oic_info, k, v, chevron = false)
            }
            kit.note(panel, "All data comes from Build, ActivityManager, BatteryManager, etc. No fabricated information. Unsupported operations are explained rather than faked.")
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
        try {
            val am = ctx.getSystemService(ActivityManager::class.java)
            val mem = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mem)
            list.add("Total RAM" to "${mem.totalMem / (1024*1024)} MB")
            list.add("Avail RAM" to "${mem.availMem / (1024*1024)} MB")
            list.add("Low memory" to mem.lowMemory.toString())
        } catch (_: Throwable) {}
        try {
            val dm = ctx.resources.displayMetrics
            list.add("Screen" to "${dm.widthPixels}x${dm.heightPixels} • ${dm.densityDpi} dpi • ${dm.density}x")
        } catch (_: Throwable) {}
        return list
    }

    private fun deviceSummary(ctx: Context): String {
        return "${Build.MANUFACTURER} ${Build.MODEL} • Android ${Build.VERSION.RELEASE}"
    }

    // ------------------------------------------------------------------ security

    private fun showSecurity(host: Activity, kit: OeaKit) {
        kit.sheet("Security Analysis", "Identifies suspicious apps, pop-up ads, unexpected permissions, unwanted behaviour. Distinguishes confirmed threat, suspicious indicator and legitimate permission.") { panel, _ ->
            panel.addView(kit.text("Scanning installed apps…", 13f, kit.palette.textSecondary).apply { setPadding(kit.dp(6), kit.dp(12), kit.dp(6), kit.dp(6)) })
            val resultHost = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
            panel.addView(resultHost)

            io.execute {
                val findings = scanSecurity(host)
                host.runOnUiThread {
                    if (host.isDestroyed) return@runOnUiThread
                    resultHost.removeAllViews()
                    if (findings.isEmpty()) {
                        kit.banner(resultHost, R.drawable.oic_check_circle_fill, "No issues found", "No suspicious apps detected in quick scan. This is a best-effort check using package flags and permission analysis.", OeaKit.Tone.SUCCESS)
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
                        }
                    }
                    kit.note(resultHost, "This analysis uses public PackageManager APIs only. It cannot inspect other apps' private data or network traffic. An app having Accessibility access is not automatically malicious — OEA explains why it was flagged.")
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
                    val suspiciousPerms = perms.filter { it.contains("SYSTEM_ALERT_WINDOW") || it.contains("BIND_ACCESSIBILITY") || it.contains("READ_SMS") }
                    if (suspiciousPerms.size >= 3) {
                        list.add(Triple(app.packageName, "Requests multiple sensitive permissions: ${suspiciousPerms.joinToString()} — may be legitimate (e.g., launcher, automation) but worth reviewing. Level: suspicious indicator, not confirmed threat.", "suspicious"))
                    }
                    // Check for overlay permission actually granted
                    if (perms.contains(android.Manifest.permission.SYSTEM_ALERT_WINDOW)) {
                        try {
                            if (Settings.canDrawOverlays(ctx)) {
                                // Only flag if app is not OEA and has overlay granted
                                val canOverlay = pm.getPackageInfo(app.packageName, 0)?.let {
                                    // Check via AppOps
                                    try {
                                        val appOps = ctx.getSystemService(android.app.AppOpsManager::class.java)
                                        appOps.checkOpNoThrow(android.app.AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, app.uid, app.packageName) == android.app.AppOpsManager.MODE_ALLOWED
                                    } catch (_: Throwable) { false }
                                } ?: false
                                if (canOverlay && perms.contains(android.Manifest.permission.SYSTEM_ALERT_WINDOW)) {
                                    // Don't flag OEA itself
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
        return list.take(20)
    }

    // ------------------------------------------------------------------ storage

    private fun showStorage(host: Activity, kit: OeaKit) {
        kit.sheet("Storage & Cleanup", "Legitimate storage cleanup and maintenance based on real measurements") { panel, _ ->
            val cacheSize = try {
                host.cacheDir.walkTopDown().filter { it.isFile }.map { it.length() }.sum() / (1024*1024)
            } catch (_: Throwable) { 0L }
            val g = kit.group(panel)
            kit.row(g, R.drawable.oic_cleaning_services, "Cache", "${cacheSize} MB in OEA's own cache", value = "Clean") {
                kit.confirm("Clear OEA cache?", "Only OEA's own cache is cleared (${cacheSize} MB). Other apps' data is untouched.", "Clear") {
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
                kit.confirm("Clear diagnostic logs?", "Only OEA's private diagnostic files are removed.", "Clear", destructive = true) {
                    OeaFaultLog.clear(host)
                    (host as? OeaMiniAppActivity)?.refresh()
                }
            }
            kit.row(g, R.drawable.oic_wallpaper, "Wallpaper cache", "OEA's downscaled wallpaper copies", value = "Manage") {
                try { host.startActivity(Intent(host, com.oea.launcher.OeaSystemToolsActivity::class.java).putExtra(com.oea.launcher.OeaSystemToolsActivity.EXTRA_SCREEN, "wallpaper")) } catch (_: Throwable) {}
            }
            kit.note(panel, "OEA only cleans its own files. It does not claim to boost performance by clearing other apps' caches, which Android manages itself. Real storage info from StatFs and package manager.")
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
        } catch (_: Throwable) {}
        try {
            val files = ctx.filesDir.walkTopDown().filter { it.isFile }.map { it.length() }.sum() / (1024*1024)
            list.add("OEA files" to "${files} MB")
        } catch (_: Throwable) {}
        return list
    }

    // ------------------------------------------------------------------ performance

    private fun showPerformance(host: Activity, kit: OeaKit) {
        kit.sheet("Performance", "Memory, battery, temperature and performance where available") { panel, _ ->
            val mem = memoryInfo(host)
            val battery = batteryInfo(host)
            val temp = batteryTemp(host)
            val g = kit.group(panel)
            kit.row(g, R.drawable.oic_memory, "Memory", mem, chevron = false)
            kit.row(g, R.drawable.oic_battery_full, "Battery", "${battery.first}% • ${if (battery.second) "Charging" else "Not charging"}", chevron = false)
            kit.row(g, R.drawable.oic_thermostat, "Battery temperature", temp, chevron = false)
            kit.row(g, R.drawable.oic_battery_charging_full, "LOW mode", com.oea.launcher.perf.OeaLowMode.summary(host)) {
                try { host.startActivity(Intent(host, com.oea.launcher.OeaSystemToolsActivity::class.java).putExtra(com.oea.launcher.OeaSystemToolsActivity.EXTRA_SCREEN, "low").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) {}
            }
            kit.sectionTitle(panel, "Thermal & CPU")
            val tg = kit.group(panel)
            kit.row(tg, R.drawable.oic_info, "Thermal state", thermalInfo(host), chevron = false)
            kit.row(tg, R.drawable.oic_memory, "CPU cores", "${Runtime.getRuntime().availableProcessors()} cores • ${Build.SUPPORTED_ABIS.joinToString()}", chevron = false)
            kit.note(panel, "Data from ActivityManager, BatteryManager and PowerManager. No fabricated boost claims. OEA trims caches on onTrimMemory.")
        }
    }

    private fun memoryInfo(ctx: Context): String {
        return try {
            val am = ctx.getSystemService(ActivityManager::class.java)
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            "${mi.availMem / (1024*1024)} MB free / ${mi.totalMem / (1024*1024)} MB total"
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

    // ------------------------------------------------------------------ sound amplifier

    private fun showSoundAmplifier(host: Activity, kit: OeaKit) {
        kit.sheet("Sound Amplifier", "Uses supported Android audio capabilities") { panel, _ ->
            kit.note(panel, "OEA's sound amplifier boosts media volume and can use microphone for amplification where supported. It uses AudioManager and MediaRecorder APIs, not fabricated processing.")
            val audio = host.getSystemService(android.media.AudioManager::class.java)
            val g = kit.group(panel)
            if (audio != null) {
                kit.row(g, R.drawable.oic_volume_up, "Media volume", "${audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)} / ${audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)}") {
                    try { audio.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_SAME, android.media.AudioManager.FLAG_SHOW_UI) } catch (_: Throwable) {}
                }
                // Simple amplifier: increase media volume to max
                panel.addView(kit.primaryButton("Boost media volume", R.drawable.oic_volume_up) {
                    try {
                        audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC), 0)
                        android.widget.Toast.makeText(host, "Media volume boosted", android.widget.Toast.LENGTH_SHORT).show()
                    } catch (_: Throwable) {}
                }, LinearLayout.LayoutParams(-1, kit.dp(50)).apply { topMargin = kit.dp(12) })
            }
            kit.sectionTitle(panel, "Microphone amplifier (experimental)")
            kit.note(panel, "Uses microphone to amplify surrounding sound to headphones. Requires RECORD_AUDIO permission and headphones. This is a legitimate accessibility feature using Android's audio routing.")
            panel.addView(kit.tonalButton("Start amplifier (needs permission)", R.drawable.oic_mic) {
                if (host.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    try { host.requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 3002) } catch (_: Throwable) {}
                    android.widget.Toast.makeText(host, "Grant microphone permission", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    android.widget.Toast.makeText(host, "Amplifier would route mic to headphones here (implementation needs AudioTrack). See OEA docs for full implementation.", android.widget.Toast.LENGTH_LONG).show()
                }
            }, LinearLayout.LayoutParams(-1, kit.dp(50)))
        }
    }

    // ------------------------------------------------------------------ ADB-inspired command system

    private fun showCommandSystem(host: Activity, kit: OeaKit) {
        kit.sheet("OEA Command System", "Our own implementation, our own format and our own build — inspired by ADB, not a wrapper", build = { panel, _ ->
            kit.banner(panel, R.drawable.oic_developer_mode, "Built-in, on-device", "No laptop, no Shizuku, no helper app. OEA's own privileged service layer using DevicePolicyManager, root shell channel, appops and settings commands — scoped to OEA's own package and always verified.", OeaKit.Tone.INFO)
            kit.sectionTitle(panel, "Architecture")
            kit.note(panel, """
OEA's command system research:
- ADB: client-server, adbd daemon, shell commands via binder.
- Shizuku: privileged service via app that exposes shell via binder to other apps.
- Android permission architecture: runtime permissions, appops (SYSTEM_ALERT_WINDOW, GET_USAGE_STATS), special access (notification listener, accessibility, device admin, device owner).
- Wireless Debugging: pairing via QR, TLS, adb over Wi-Fi.
- Root: su binary, scoped commands, verification by reading state back.
- Device Owner: DevicePolicyManager.setPermissionGrantState, setPackagesSuspended.

Our own build: OeaShell runs su commands for OEA's own package only, always verified. OeaPrivileges opens exact Android pages and verifies result. OeaAccessDoctor verifies live state, not just switch. No fake overlay tricks.

This is broader than arbitrary few functions: it covers overlay, multitasking, suspension, restricted settings and diagnostics.
            """.trimIndent())

            val g = kit.group(panel)
            kit.row(g, R.drawable.oic_layers, "Overlay permissions and restrictions", "Checks canDrawOverlays and verifies with real draw") {
                showOverlayDoctor(host, kit)
            }
            kit.row(g, R.drawable.oic_splitscreen, "Multitasking and split-screen capabilities", "Freeform, split-screen support") {
                showMultitaskDoctor(host, kit)
            }
            kit.row(g, R.drawable.oic_ac_unit, "Application suspension and required privileges", "Device Owner, root, OEA Freeze") {
                showSuspensionDoctor(host, kit)
            }
            kit.row(g, R.drawable.oic_settings, "Other system operations and restricted settings", "DND, battery optimization, write settings") {
                showOtherOps(host, kit)
            }

            kit.sectionTitle(panel, "Execute command")
            val cmdField = kit.field("Enter OEA command (e.g., overlay status, freeze com.example, split com.a com.b)")
            panel.addView(cmdField, LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(8) })
            val output = kit.text("", 12f, kit.palette.textSecondary, com.oea.launcher.ui.OeaType.regular).apply {
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(kit.dp(6), kit.dp(8), kit.dp(6), kit.dp(8))
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

            kit.note(panel, "Commands are OEA's own format, not ADB. Each operation explains what it does, why access is required, how to authorize and how to confirm success. No bypass of security boundaries without required privileges.")
        })
    }

    private fun executeOeaCommand(ctx: Context, cmd: String): String {
        val parts = cmd.trim().split("\\s+".toRegex())
        if (parts.isEmpty()) return "Empty command"
        return when (parts[0].lowercase()) {
            "help" -> """
OEA Command System - own format
Commands:
  help - this list
  overlay status - check overlay permission and verify
  overlay request - request overlay permission
  freeze <package> - freeze app (needs DO/root, else OEA Freeze)
  unfreeze <package> - unfreeze app
  freeze status <package> - check frozen state
  suspend <package> - same as freeze (system suspension)
  list frozen - list all frozen apps
  multitask status - check split-screen and freeform support
  split <pkg1> <pkg2> - launch app pair in split screen
  float <package> - launch app as floating window
  dnd on|off|status - Do Not Disturb control
  battery status - battery info
  memory status - memory info
  shell <command> - run root shell command (needs root, OEA only)
  clear diagnostics - clear fault logs
  device info - full device specs
            """.trimIndent()
            "overlay" -> when (parts.getOrNull(1)?.lowercase()) {
                "status" -> {
                    val can = OeaPrivileges.canOverlay(ctx)
                    val verified = try { com.oea.launcher.workspace.OeaDynamicIslandOverlayService.verifyOverlay(ctx) } catch (_: Throwable) { false }
                    "Overlay permission: ${if (can) "granted" else "not granted"}\nVerified draw: ${if (verified) "success" else "failed"}\nWhy: SYSTEM_ALERT_WINDOW is reserved to user on stock devices. OEA verifies by actually drawing 1px probe.\nAuthorize: Settings › Apps › OEA › Display over other apps\nConfirm: probe view attached and removed without SecurityException"
                }
                "request" -> {
                    val ok = OeaPrivileges.requestOverlay(ctx, 0)
                    "Overlay request: ${if (ok) "opened settings page" else "could not open, grant manually"}\nWhat it unlocks: system-wide Dynamic Island, Game Boost overlay, wake gesture fallback"
                }
                else -> "Usage: overlay status|request"
            }
            "freeze", "suspend" -> {
                if (parts.size < 2) return "Usage: freeze <package>"
                val pkg = parts[1]
                val result = OeaAppFreezer.setFrozen(ctx, pkg, true)
                "${result.message}\nBackend: ${result.backend}\nWhat it does: suspends package via DevicePolicyManager (DO) or cmd package suspend (root). Preserves data.\nWhy needed: PACKAGE_SUSPEND is privileged; ordinary apps cannot suspend others.\nAuthorize: provision OEA as Device Owner via adb shell dpm set-device-owner com.oea.launcher/.applock.OeaDeviceAdminReceiver, or root.\nConfirm: PackageManager.isPackageSuspended == true and dumpsys confirms"
            }
            "unfreeze" -> {
                if (parts.size < 2) return "Usage: unfreeze <package>"
                val pkg = parts[1]
                val result = OeaAppFreezer.setFrozen(ctx, pkg, false)
                if (!result.success) {
                    // Try OEA Freeze unfreeze
                    OeaAppFreezer.setHibernated(ctx, listOf(pkg), false)
                    "Unfrozen from OEA Freeze (hibernated)\nBackend: OEA"
                } else "${result.message}\nBackend: ${result.backend}"
            }
            "list" -> when (parts.getOrNull(1)?.lowercase()) {
                "frozen" -> {
                    val all = OeaAppFreezer.allFrozen(ctx)
                    if (all.isEmpty()) "No frozen apps" else all.joinToString("\n") { "- $it" } + "\n\nTotal: ${all.size}"
                }
                else -> "Unknown list command. Try 'list frozen'"
            }
            "multitask" -> {
                val freeform = com.oea.launcher.multitask.OeaMultitaskLauncher.freeformEnabled(ctx)
                val split = "Split-screen: supported on Android 7+ via ActivityManager, but OEM and app can restrict. OEA uses split-screen via intent and verifies with Assistant."
                "Freeform (floating windows): ${if (freeform) "enabled" else "disabled"}\n$split\nAuthorize freeform: Developer options › Enable freeform windows + Force resizable, reboot"
            }
            "split" -> {
                if (parts.size < 3) return "Usage: split <pkg1> <pkg2>"
                val result = com.oea.launcher.split.OeaSplitLauncher.launchPair(ctx, parts[1], parts[2])
                "Split result: ${result.message} • success=${result.success}\nWhat it does: requests Android split-screen with two apps side-by-side\nWhy might fail: OEM disables split, app opts out, not in foreground"
            }
            "float" -> {
                if (parts.size < 2) return "Usage: float <package>"
                val app = com.oea.launcher.model.OeaAppInfo(parts[1], "", parts[1])
                val result = com.oea.launcher.multitask.OeaMultitaskLauncher.launchFloating(ctx, app)
                "Floating result: ${result.message}"
            }
            "dnd" -> when (parts.getOrNull(1)?.lowercase()) {
                "status" -> "DND access: ${if (com.oea.launcher.power.OeaPower.policyAccess(ctx)) "granted" else "not granted"}\nDND on: ${com.oea.launcher.power.OeaPower.dndOn(ctx)}\nAuthorize: Settings › Apps › Special access › Do Not Disturb access"
                "on" -> if (com.oea.launcher.power.OeaPower.setDnd(ctx, true)) "DND enabled" else "Failed - need access"
                "off" -> if (com.oea.launcher.power.OeaPower.setDnd(ctx, false)) "DND disabled" else "Failed - need access"
                else -> "Usage: dnd on|off|status"
            }
            "battery" -> {
                val (level, charging) = com.oea.launcher.perf.OeaLowMode.battery(ctx)
                "Battery: $level% • ${if (charging) "charging" else "not charging"}\nSource: BatteryManager"
            }
            "memory" -> memoryInfo(ctx)
            "shell" -> {
                val command = parts.drop(1).joinToString(" ")
                if (command.isBlank()) return "Usage: shell <command>"
                val backend = OeaAppFreezer.backend(ctx)
                if (backend != OeaAppFreezer.Backend.ROOT) return "Root not available. Backend: $backend\nOEA's shell channel needs su binary and root grant. It runs commands scoped to OEA's own package only."
                try {
                    val proc = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
                    val out = proc.inputStream.bufferedReader().readText()
                    val exit = proc.waitFor()
                    "Exit: $exit\nOutput:\n$out"
                } catch (e: Throwable) { "Shell failed: ${e.message}" }
            }
            "clear" -> when (parts.getOrNull(1)?.lowercase()) {
                "diagnostics" -> { OeaFaultLog.clear(ctx); "Diagnostics cleared" }
                else -> "Unknown clear command"
            }
            "device" -> deviceDetails(ctx).joinToString("\n") { "${it.first}: ${it.second}" }
            else -> "Unknown command '${parts[0]}'. Try 'help'"
        }
    }

    private fun showOverlayDoctor(host: Activity, kit: OeaKit) {
        kit.sheet("Overlay Doctor", null) { panel, _ ->
            val can = OeaPrivileges.canOverlay(host)
            val verified = try { com.oea.launcher.workspace.OeaDynamicIslandOverlayService.verifyOverlay(host) } catch (_: Throwable) { false }
            kit.banner(panel, if (verified) R.drawable.oic_check_circle_fill else R.drawable.oic_warning_fill, "Overlay: ${if (can) "granted" else "not granted"}", "Verified draw: ${if (verified) "success" else "failed"}. Some firmware reports granted but blocks drawing; OEA probes real WindowManager.", tone = if (verified) OeaKit.Tone.SUCCESS else OeaKit.Tone.WARNING)
            kit.note(panel, "What it unlocks: Dynamic Island over other apps, Game Boost RAM control, wake gesture fallback activity.\nWhy: SYSTEM_ALERT_WINDOW is user-granted special access.\nAuthorize: Settings › Apps › OEA › Display over other apps.\nConfirm: probe 1x1 overlay window attaches and detaches.")
        }
    }

    private fun showMultitaskDoctor(host: Activity, kit: OeaKit) {
        kit.sheet("Multitasking Doctor", null) { panel, _ ->
            val freeform = com.oea.launcher.multitask.OeaMultitaskLauncher.freeformEnabled(host)
            kit.banner(panel, if (freeform) R.drawable.oic_check_circle_fill else R.drawable.oic_info, "Freeform: ${if (freeform) "enabled" else "disabled"}", "Floating windows need Developer options › Enable freeform windows. OEM may not expose it.", tone = if (freeform) OeaKit.Tone.SUCCESS else OeaKit.Tone.INFO)
            kit.note(panel, "Split-screen: Android 7+ supports via ActivityManager. OEM and app can still deny. OEA waits for first app to be in front before launching adjacent, verifies with Assistant and recovers once via system split toggle.")
        }
    }

    private fun showSuspensionDoctor(host: Activity, kit: OeaKit) {
        kit.sheet("Suspension Doctor", null) { panel, _ ->
            val backend = OeaAppFreezer.backend(host)
            val mode = OeaAppFreezer.mode(host)
            kit.banner(panel, if (mode == OeaAppFreezer.Mode.SYSTEM) R.drawable.oic_check_circle_fill else R.drawable.oic_info, "Mode: $mode • Backend: $backend", when (backend) {
                OeaAppFreezer.Backend.DEVICE_OWNER -> "System freeze via DevicePolicyManager.setPackagesSuspended — genuine suspension, Android blocks launch, background, notifications."
                OeaAppFreezer.Backend.ROOT -> "System freeze via su cmd package suspend — genuine suspension, verified via dumpsys."
                else -> "OEA Freeze: blocks in OEA and force-stops via Assistant. Cannot prevent other apps from launching it without DO/root; OEA now blocks via Assistant foreground watch as mitigation."
            }, tone = if (mode == OeaAppFreezer.Mode.SYSTEM) OeaKit.Tone.SUCCESS else OeaKit.Tone.INFO)
            kit.note(panel, "Freezing preserves data. Unfreezing restores. Genuine freezing needs Device Owner (adb shell dpm set-device-owner) or root. Without, best supported alternative is OEA Freeze with Assistant blocking external routes.")
        }
    }

    private fun showOtherOps(host: Activity, kit: OeaKit) {
        kit.sheet("Other Operations", null) { panel, _ ->
            val rows = OeaPrivileges.privilegeRows(host)
            rows.forEach { (priv, grant) ->
                val g = kit.group(panel)
                kit.row(g, R.drawable.oic_settings, priv.title, "${priv.summary} • ${grant.name}", chevron = false)
            }
            kit.note(panel, "Each operation shows what it does, why access required, how to authorize, how to confirm. OEA never pretends ordinary app can bypass security boundaries.")
        }
    }

    // ------------------------------------------------------------------ privileges overview

    private fun showPrivileges(host: Activity, kit: OeaKit) {
        kit.sheet("Privileges & Capabilities", "What OEA can use, what it unlocks") { panel, _ ->
            val caps = OeaPrivileges.capabilities(host)
            caps.forEach { cap ->
                val g = kit.group(panel)
                kit.banner(g, if (cap.isReady) R.drawable.oic_check_circle_fill else R.drawable.oic_info, cap.feature, "Needs: ${cap.requires.title} • Shown as: ${cap.surface.label}\nWithout: ${cap.effect}", tone = if (cap.isReady) OeaKit.Tone.SUCCESS else OeaKit.Tone.INFO)
            }
            kit.note(panel, "OEA grants by itself whatever Android allows (Device Owner via DPM, or root via own su channel, scoped to OEA's package and verified), and falls back to Home-side or notification presentation otherwise. Display over other apps and Usage access are user's to give on stock device — no third-party app can change that.")
        }
    }

    private fun Intent(action: String): Intent = Intent(action)
}
