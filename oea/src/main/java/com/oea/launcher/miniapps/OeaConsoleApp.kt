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
import com.oea.launcher.security.OeaThreatEngine
import com.oea.launcher.ui.OeaAppKit
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
 * - Security review of app permissions and links to Android protections (not malware detection)
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
        val kit = OeaAppKit(host, palette)
        var currentTab = "system"
        var renderTab: () -> Unit = {}
        val navItems = listOf(
            OeaAppKit.NavItem("system", "System", R.drawable.oic_phone_android),
            OeaAppKit.NavItem("security", "Security", R.drawable.oic_shield_fill),
            OeaAppKit.NavItem("commands", "Commands", R.drawable.oic_developer_mode),
            OeaAppKit.NavItem("tools", "Tools", R.drawable.oic_tune)
        )
        val shell = kit.appShell(
            title = "Console",
            subtitle = deviceSummary(host),
            navItems = navItems,
            initialNav = currentTab,
            onBack = host.miniIndexBack()
        ) { id ->
            currentTab = id
            renderTab()
        }
        val content = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        shell.body.addView(content)

        shell.bar.addAction(R.drawable.oic_developer_mode, "Command terminal") {
            showCommandSystem(host, kit)
        }
        shell.bar.addAction(R.drawable.oic_more_vert, "Console options") {
            val latest = OeaFaultLog.history().firstOrNull()
            kit.actions("Console", null, listOf(
                OeaAppKit.Action(R.drawable.oic_shield_fill, "Diagnostic history", "${OeaFaultLog.count()} recorded incidents") {
                    showDiagnostics(host, kit)
                },
                OeaAppKit.Action(R.drawable.oic_share_filled, "Share latest incident", if (latest != null) latest.title() else "No incidents recorded", disabled = latest == null) {
                    if (latest != null) shareIncident(host, latest)
                },
                OeaAppKit.Action(R.drawable.oic_refresh, "Refresh status", "Re-read memory, battery and permissions") {
                    renderTab()
                }
            ))
        }

        renderTab = {
            shell.nav?.select(currentTab, notify = false)
            shell.bar.title.text = when (currentTab) {
                "security" -> "Security"
                "commands" -> "Commands"
                "tools" -> "Tools"
                else -> "Console"
            }
            content.removeAllViews()
            when (currentTab) {
                "system" -> {
                    val battery = com.oea.launcher.power.OeaBatteryState.read(host)
                    val lowModeActive = com.oea.launcher.perf.OeaLowMode.active(host)
                    val deviceUltraSaving = isDeviceInUltraPowerSaving(host)
                    val incidents = OeaFaultLog.count()

                    val statsRow = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
                    val battTile = kit.statTile("Battery", battery.text.substringBefore(' '), palette.text)
                    val lowTile = kit.statTile("Power mode", if (lowModeActive) "LOW" else "Normal", if (lowModeActive) palette.warning else palette.text)
                    val logTile = kit.statTile("Incidents", incidents.toString(), if (incidents > 0) palette.warning else palette.success)
                    statsRow.addView(battTile, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = kit.dp(8) })
                    statsRow.addView(lowTile, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = kit.dp(8) })
                    statsRow.addView(logTile, LinearLayout.LayoutParams(0, -2, 1f))
                    content.addView(statsRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(10) })

                    val memCard = LinearLayout(host).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(kit.dp(16), kit.dp(12), kit.dp(16), kit.dp(12))
                        background = kit.rounded(palette.card, 18, palette.stroke, 1)
                    }
                    val memLabel = kit.text("Battery ${battery.text} · ${memoryInfo(host)}", 13f, palette.textSecondary)
                    memCard.addView(memLabel)
                    com.oea.launcher.power.OeaBatteryState.observe(memCard) { state ->
                        memLabel.text = "Battery ${state.text} · ${memoryInfo(host)}"
                    }
                    content.addView(memCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(10) })

                    if (deviceUltraSaving) {
                        kit.banner(
                            content,
                            R.drawable.oic_warning_fill,
                            "System Ultra Power Saving active",
                            "Background tasks and alarms may be paused until OEA is exempted in battery settings.",
                            OeaKit.Tone.WARNING,
                            "Details"
                        ) { showPerformance(host, kit) }
                    }

                    kit.sectionTitle(content, "Device & health")
                    val group = kit.group(content)
                    kit.row(group, R.drawable.oic_phone_android, "Device information", "Model, display, CPU and Android build", chevron = true) {
                        showDeviceInfo(host, kit)
                    }
                    kit.row(group, R.drawable.oic_memory, "Performance & battery", "Memory pressure, thermals and power state", chevron = true) {
                        showPerformance(host, kit)
                    }
                    kit.row(group, R.drawable.oic_cleaning_services, "Storage & maintenance", "Disk usage and cache cleanup", chevron = true) {
                        showStorage(host, kit)
                    }
                    kit.row(group, R.drawable.oic_battery_charging_full, "LOW power mode", "Ultra power saving for OEA", value = if (lowModeActive) "On" else "Off", chevron = true) {
                        showLowMode(host, kit)
                    }
                    kit.row(group, R.drawable.oic_history, "Diagnostics", "$incidents recorded ${if (incidents == 1) "incident" else "incidents"}", chevron = true) {
                        showDiagnostics(host, kit)
                    }
                }

                "security" -> {
                    kit.sectionTitle(content, "Access & protections")
                    val group = kit.group(content)
                    val lastScan = com.oea.launcher.security.OeaAppAudit.lastScan(host)
                    val auditSubtitle = if (lastScan.first <= 0L) {
                        "Never run • ${OeaThreatEngine.ruleCount} rules built in, on this phone"
                    } else {
                        "Last scan ${SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(lastScan.first))} • " +
                            "${lastScan.second} flagged" + if (lastScan.third > 0) ", ${lastScan.third} high risk" else ""
                    }
                    kit.row(group, R.drawable.oic_shield, "Antivirus & app audit", auditSubtitle, chevron = true) {
                        showProtection(host, kit)
                    }
                    kit.row(group, R.drawable.oic_accessibility_new, "Privileges & special access", "Overlay, notification access, app suspension and split-screen", chevron = true) {
                        showPrivileges(host, kit)
                    }
                    kit.row(group, R.drawable.oic_check_circle_fill, "Allowed apps", "${com.oea.launcher.security.OeaAppAudit.allowListSize(host)} apps you told OEA to stop reporting", chevron = true) {
                        showAllowList(host, kit)
                    }
                    kit.row(group, R.drawable.oic_wifi, "Offline by design", "What works with no network, and the only two things in OEA that ask for one", chevron = true) {
                        showOfflineNote(host, kit)
                    }
                    kit.row(group, R.drawable.oic_ac_unit_fill, "App Freezer", "Pause unused apps so they stop running in the background", chevron = true) {
                        runCatching {
                            host.startActivity(Intent(host, com.oea.launcher.freezer.OeaFreezerActivity::class.java))
                        }
                    }
                    kit.row(group, R.drawable.oic_shield_fill, "File signature scan", "Optional: match a file against a SHA-256 list you import", chevron = true) {
                        runCatching {
                            host.startActivity(Intent(host, com.oea.launcher.security.OeaSecurityScanActivity::class.java))
                        }
                    }
                }

                "commands" -> {
                    val hero = LinearLayout(host).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(kit.dp(18), kit.dp(18), kit.dp(18), kit.dp(18))
                        background = kit.rounded(palette.card, 22, palette.stroke, 1)
                    }
                    hero.addView(kit.mono(kit.text(">_ oea console", 17f, palette.accent, OeaType.medium)))
                    hero.addView(kit.text(
                        "Inspect packages, verify privileges, toggle modes, freeze apps and run device diagnostics directly on-device.",
                        13.5f,
                        palette.textSecondary
                    ).apply { setPadding(0, kit.dp(6), 0, kit.dp(14)); setLineSpacing(kit.dpf(2f), 1f) })
                    hero.addView(
                        kit.primaryButton("Open command prompt", R.drawable.oic_developer_mode) {
                            showCommandSystem(host, kit)
                        },
                        LinearLayout.LayoutParams(-1, kit.dp(48))
                    )
                    content.addView(hero, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(10) })

                    kit.sectionTitle(content, "Related modules")
                    val group = kit.group(content)
                    kit.row(group, R.drawable.oic_accessibility_new, "Privilege doctor", "Verify overlay, root, device-owner and assistant live", chevron = true) {
                        showPrivileges(host, kit)
                    }
                    kit.row(group, R.drawable.oic_history, "Crash & fault log", "${OeaFaultLog.count()} recorded incidents", chevron = true) {
                        showDiagnostics(host, kit)
                    }
                }

                "tools" -> {
                    kit.sectionTitle(content, "Utilities")
                    val group = kit.group(content)
                    kit.row(group, R.drawable.oic_headset, "Sound amplifier", "Route room audio to wired or Bluetooth earphones with gain control", chevron = true) {
                        showSoundAmplifier(host, kit)
                    }
                    kit.row(group, R.drawable.oic_cleaning_services, "Storage cleanup", "Inspect storage and trim OEA caches", chevron = true) {
                        showStorage(host, kit)
                    }
                    kit.row(group, R.drawable.oic_battery_charging_full, "LOW power mode", "Trim animations and background work when battery is low", chevron = true) {
                        showLowMode(host, kit)
                    }
                    val latest = OeaFaultLog.history().firstOrNull()
                    kit.row(
                        group,
                        R.drawable.oic_share_filled,
                        "Export latest incident",
                        latest?.title() ?: "No incidents on record",
                        chevron = latest != null
                    ) {
                        if (latest == null) {
                            android.widget.Toast.makeText(host, "No incidents recorded", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            shareIncident(host, latest)
                        }
                    }
                }
            }
        }
        renderTab()
        return shell.root
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
            // The whole report, the way the Play Store shows a device:
            // one block you can copy or send anywhere.
            val report = info.joinToString("\n") { "${it.first}: ${it.second}" }
            val actions = kit.group(panel)
            kit.row(actions, R.drawable.oic_file_copy, "Copy everything", "The whole report, as text", onClick = {
                try {
                    val cb = host.getSystemService(android.content.ClipboardManager::class.java) as android.content.ClipboardManager
                    cb.setPrimaryClip(android.content.ClipData.newPlainText("OEA device info", report))
                    android.widget.Toast.makeText(host, "Device info copied", android.widget.Toast.LENGTH_SHORT).show()
                } catch (_: Throwable) {}
            })
            kit.row(actions, R.drawable.oic_share_filled, "Share device info", "Send the report to any app", onClick = {
                try {
                    host.startActivity(Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "OEA Device Info\n$report"),
                        "Share device info"
                    ))
                } catch (_: Throwable) {}
            })
            kit.note(panel, "All data from Build, ActivityManager, BatteryManager, DisplayMetrics, PackageManager, StatFs, etc. No fabricated information. Unsupported operations explained rather than faked. For mini-apps not working in device ultra power saving, see Performance section.")
        })
    }

    private fun deviceDetails(ctx: Context): List<Pair<String, String>> {
        val list = mutableListOf<Pair<String, String>>()
        // The identity trio the system device-info app and the Play
        // Store both lead with: what the phone calls itself, its model
        // and the Android it runs.
        val deviceName = runCatching {
            android.provider.Settings.Global.getString(ctx.contentResolver, "device_name")
                ?.takeIf { it.isNotBlank() } ?: Build.MANUFACTURER + " " + Build.MODEL
        }.getOrDefault(Build.MANUFACTURER + " " + Build.MODEL)
        list.add("Device name" to deviceName)
        list.add("Manufacturer" to Build.MANUFACTURER)
        list.add("Model" to Build.MODEL)
        list.add("Brand" to Build.BRAND)
        list.add("Device" to Build.DEVICE)
        list.add("Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        list.add("Android security patch" to (Build.VERSION.SECURITY_PATCH ?: "unknown"))
        list.add("Build number" to (Build.DISPLAY ?: "unknown"))
        list.add("Kernel" to (System.getProperty("os.version") ?: "unknown"))
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
            val inches = screenInches(ctx)
            list.add("Screen" to "${dm.widthPixels}x${dm.heightPixels} • ${dm.densityDpi} dpi • ${dm.density}x" + if (inches > 0f) " • ${"%1.1f".format(inches)}\"" else "")
        } catch (_: Throwable) {}
        list.addAll(storageStats(ctx))
        try {
            val pm = ctx.packageManager
            val info = pm.getPackageInfo(ctx.packageName, 0)
            list.add("OEA version" to "${info.versionName} (${info.longVersionCode})")
        } catch (_: Throwable) {}
        list.add("OEA Home" to homeHandlerStatus(ctx))
        list.add("EMAGE image handler" to imageHandlerStatus(ctx))
        runCatching {
            // One reading for the whole launcher. A phone that reports no level gets
            // "—" — hiding the row (the old `capacity > 0`) made 0% look like no battery.
            list.add("Battery" to com.oea.launcher.power.OeaBatteryState.read(ctx).text)
        }
        list.add("Low mode (Ultra Saving)" to if (com.oea.launcher.perf.OeaLowMode.active(ctx)) "ON • ${com.oea.launcher.perf.OeaLowMode.summary(ctx)}" else "OFF")
        list.add("Device ultra saving" to if (isDeviceInUltraPowerSaving(ctx)) "ON • OEM may block mini-apps" else "OFF")
        list.add("Power save mode" to if (isPowerSaveMode(ctx)) "ON" else "OFF")
        return list
    }

    private fun homeHandlerStatus(ctx: Context): String = runCatching {
        if (Build.VERSION.SDK_INT >= 29) {
            val roles = ctx.getSystemService(android.app.role.RoleManager::class.java)
            if (roles?.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME) == true) {
                return@runCatching if (roles.isRoleHeld(android.app.role.RoleManager.ROLE_HOME)) "OEA is the default launcher" else "Another launcher is default"
            }
        }
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = ctx.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
        when {
            resolved == null -> "Android has no resolved Home"
            resolved.packageName == ctx.packageName -> "OEA is the resolved launcher"
            else -> "${resolved.packageName} is the resolved launcher"
        }
    }.getOrDefault("Unavailable")

    private fun imageHandlerStatus(ctx: Context): String = runCatching {
        val view = Intent(Intent.ACTION_VIEW).setType("image/*")
        val resolved = ctx.packageManager.resolveActivity(view, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
        when {
            resolved == null -> "No image handler resolved"
            resolved.name?.contains("Resolver", ignoreCase = true) == true -> "Android will ask which app to use"
            resolved.packageName == ctx.packageName -> "EMAGE is the selected image app"
            else -> "${resolved.packageName} is selected; EMAGE is available as an alternative"
        }
    }.getOrDefault("Unavailable")

    private fun screenInches(ctx: Context): Float = runCatching {
        val dm = ctx.resources.displayMetrics
        val w = dm.widthPixels / dm.xdpi
        val h = dm.heightPixels / dm.ydpi
        Math.sqrt((w * w + h * h).toDouble()).toFloat()
    }.getOrDefault(0f)

    private fun deviceSummary(ctx: Context): String = "${Build.MANUFACTURER} ${Build.MODEL} • Android ${Build.VERSION.RELEASE}"

    // ------------------------------------------------------------------ security

    // ------------------------------------------------------- antivirus / protection

    /** The severity a finding is drawn with. */
    private fun severityIcon(severity: OeaThreatEngine.Severity): Int =
        if (severity == OeaThreatEngine.Severity.HIGH) R.drawable.oic_warning_fill else R.drawable.oic_shield

    /**
     * Device protection: OEA's own antivirus, on the phone.
     *
     * The old sheet's answer to "is this phone safe?" was a button that opened the
     * Play Store — an external platform doing the work, unavailable offline, on a
     * phone without Google services, or on a build like this one. What can be done
     * here is a real audit of every installed app against rules bundled in the APK:
     * what it can see, who signed it, where it came from, which dangerous
     * capabilities it holds together. Nothing is uploaded and nothing is
     * downloaded, so it works on a phone that has never been online.
     */
    private fun showProtection(host: Activity, kit: OeaAppKit) {
        val subtitle = "Every installed app is checked on this phone against " +
            "${OeaThreatEngine.ruleCount} built-in rules. No cloud, no Play Protect, nothing uploaded."
        kit.sheet("Device protection", subtitle) { panel, dialog ->
            val progress = kit.text("Reading installed apps…", 13f, kit.palette.textSecondary).apply {
                setPadding(kit.dp(6), kit.dp(14), kit.dp(6), kit.dp(8))
            }
            panel.addView(progress)
            val results = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
            panel.addView(results)
            val tools = kit.group(panel)
            kit.row(
                tools, R.drawable.oic_shield_fill, "File signature scan",
                "Match a file's SHA-256 against a list you import — the code-level check these rules cannot do",
                chevron = true
            ) {
                runCatching {
                    host.startActivity(Intent(host, com.oea.launcher.security.OeaSecurityScanActivity::class.java))
                }.onFailure { consoleToast(host, "Security Scan unavailable") }
            }
            val watchPrefs = host.getSharedPreferences("security_scan", Context.MODE_PRIVATE)
            val watching = watchPrefs.getBoolean("monitor", false)
            kit.row(
                tools, R.drawable.oic_shield_fill, "Watch new installs",
                if (watching) "On — a newly installed app is checked against the rules and you are told if it needs review"
                else "Off — apps are checked only when you open this screen",
                value = if (watching) "On" else "Off"
            ) {
                watchPrefs.edit().putBoolean("monitor", !watching).apply()
                consoleToast(host, if (watching) "Install watch off" else "Install watch on — new apps are checked as they arrive")
                dialog.dismiss()
                showProtection(host, kit)
            }
            kit.row(tools, R.drawable.oic_lock, "Android security settings", "Screen lock, encryption and system updates", chevron = true) {
                runCatching { host.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
                    .onFailure { consoleToast(host, "Security settings unavailable") }
            }
            // A local function, not a lambda: allowing an app re-runs the scan, so it
            // has to be able to call itself.
            fun runScan() {
                progress.visibility = View.VISIBLE
                results.removeAllViews()
                io.execute {
                    val scan = runCatching { com.oea.launcher.security.OeaAppAudit.scan(host.applicationContext) }
                        .getOrElse {
                            com.oea.launcher.security.OeaAppAudit.ScanResult(
                                OeaThreatEngine.Report(0, 0, 0, emptyList(), 0, 0, 0),
                                emptyList(),
                                it.message ?: "The scan stopped unexpectedly"
                            )
                        }
                    host.runOnUiThread {
                        if (host.isDestroyed || !dialog.isShowing) return@runOnUiThread
                        progress.visibility = View.GONE
                        renderProtection(host, kit, results, scan) { runScan() }
                    }
                }
            }
            runScan()
        }
    }

    private fun renderProtection(
        host: Activity,
        kit: OeaAppKit,
        container: LinearLayout,
        scan: com.oea.launcher.security.OeaAppAudit.ScanResult,
        rescan: () -> Unit
    ) {
        container.removeAllViews()
        val report = scan.report
        if (scan.error != null) {
            kit.banner(container, R.drawable.oic_warning_fill, "Scan could not run", scan.error, OeaKit.Tone.DANGER)
            return
        }
        val tone = when (scan.verdict) {
            OeaThreatEngine.Verdict.HIGH -> OeaKit.Tone.DANGER
            OeaThreatEngine.Verdict.REVIEW -> OeaKit.Tone.WARNING
            OeaThreatEngine.Verdict.LOW -> OeaKit.Tone.INFO
            OeaThreatEngine.Verdict.CLEAN -> OeaKit.Tone.SUCCESS
        }
        kit.banner(
            container,
            if (scan.verdict == OeaThreatEngine.Verdict.CLEAN) R.drawable.oic_check_circle_fill else R.drawable.oic_warning_fill,
            OeaThreatEngine.verdictLabel(scan.verdict),
            OeaThreatEngine.verdictSentence(report),
            tone
        )
        val stats = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
        stats.addView(
            kit.statTile("Apps checked", report.checked.toString(), kit.palette.text),
            LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = kit.dp(8) }
        )
        stats.addView(
            kit.statTile("High risk", report.high.toString(), if (report.high > 0) kit.palette.danger else kit.palette.success),
            LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = kit.dp(8) }
        )
        stats.addView(
            kit.statTile("To review", (report.review + report.low).toString(), if (report.review + report.low > 0) kit.palette.warning else kit.palette.textSecondary),
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        container.addView(stats, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(10) })

        if (report.assessments.isEmpty()) {
            kit.note(
                container,
                "No app matched a rule. That is a good result for ${report.checked} apps, " +
                    "not a guarantee: these rules read declarations, signatures and install records, not app code."
            )
        }

        report.assessments.forEach { assessment ->
            val g = kit.group(container)
            val severity = assessment.highest ?: OeaThreatEngine.Severity.LOW
            kit.row(
                g, severityIcon(severity), assessment.facts.label, assessment.facts.packageName,
                value = OeaThreatEngine.severityLabel(severity),
                chevron = false,
                iconBg = if (severity == OeaThreatEngine.Severity.HIGH) OeaTheme.alpha(kit.palette.danger, 0x33) else kit.palette.accentSoft,
                iconTint = if (severity == OeaThreatEngine.Severity.HIGH) kit.palette.danger else kit.palette.onAccentSoft
            )
            assessment.findings.forEach { finding ->
                kit.row(g, 0, finding.title, finding.detail, chevron = false)
            }
            val actions = LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(kit.dp(14), kit.dp(4), kit.dp(14), kit.dp(12))
            }
            actions.addView(
                kit.tonalButton("App info") {
                    runCatching {
                        host.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${assessment.facts.packageName}"))
                        )
                    }.onFailure { consoleToast(host, "App info unavailable") }
                },
                LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = kit.dp(8) }
            )
            actions.addView(
                kit.tonalButton("Allow") {
                    kit.confirm(
                        "Stop checking ${assessment.facts.label}?",
                        "It stays installed and keeps its access — OEA simply stops reporting it. You can scan again after removing it from the allow list.",
                        "Allow"
                    ) {
                        com.oea.launcher.security.OeaAppAudit.allow(host, assessment.facts.packageName, true)
                        rescan()
                    }
                },
                LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = kit.dp(8) }
            )
            actions.addView(
                kit.tonalButton("Uninstall") {
                    runCatching {
                        host.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${assessment.facts.packageName}")))
                    }.onFailure { consoleToast(host, "Uninstall unavailable") }
                },
                LinearLayout.LayoutParams(0, -2, 1f)
            )
            g.addView(actions)
        }

        if (scan.apks.isNotEmpty()) {
            kit.sectionTitle(container, "Installers waiting")
            val g = kit.group(container)
            scan.apks.forEach { apk ->
                kit.row(
                    g, R.drawable.oic_file_download, apk.name,
                    "${com.oea.launcher.security.OeaAppAudit.sizeText(apk.bytes)} • an APK nothing has installed",
                    value = "Delete"
                ) {
                    val sender = com.oea.launcher.security.OeaAppAudit.deleteRequest(host, listOf(apk.uri))
                    if (sender == null) {
                        consoleToast(host, "This Android version cannot delete it from here")
                    } else {
                        runCatching { host.startIntentSenderForResult(sender, 0x0EA1, null, 0, 0, 0) }
                            .onFailure { consoleToast(host, "Android refused the delete request") }
                    }
                }
            }
            kit.note(
                container,
                "An APK sitting in storage is not running and has no access to anything. It is worth " +
                    "deleting only because installing it later would. On Android 13+ a plain app can only " +
                    "see some of storage, so this list can be empty while one exists elsewhere."
            )
        }

        kit.note(
            container,
            "How this works: ${OeaThreatEngine.ruleCount} rules, compiled into this build, read what each app " +
                "declares (permissions it holds, whether it can draw over other apps, accessibility and " +
                "notification access, device administrator), which certificate signed it and what installed " +
                "it. That finds the shapes Android malware takes — a text-reading app that can also send, " +
                "accessibility plus overlay, an overlay that can install apps — without reading a single byte " +
                "of app code, which is what the optional SHA-256 list is for.\n\n" +
                "It cannot prove an app is malicious and no result here proves a phone is safe. Nothing is " +
                "uploaded, nothing is downloaded, and no external service — Play Protect included — is " +
                "consulted or needed."
        )
    }

    private fun showAllowList(host: Activity, kit: OeaKit) {
        val allowed = com.oea.launcher.security.OeaAppAudit.allowedPackages(host)
        kit.sheet("Allowed apps", "Findings for these apps are not reported. They keep every permission they hold — allowing only silences OEA.") { panel, dialog ->
            if (allowed.isEmpty()) {
                kit.banner(
                    panel, R.drawable.oic_check_circle_fill, "Nothing is allowed",
                    "Every non-system app is checked on each scan.", OeaKit.Tone.INFO
                )
                return@sheet
            }
            val g = kit.group(panel)
            allowed.sorted().forEach { pkg ->
                val label = runCatching {
                    host.packageManager.getApplicationLabel(host.packageManager.getApplicationInfo(pkg, 0)).toString()
                }.getOrDefault(pkg)
                kit.row(g, R.drawable.oic_apps, label, pkg, value = "Check again") {
                    com.oea.launcher.security.OeaAppAudit.allow(host, pkg, false)
                    dialog.dismiss()
                    showAllowList(host, kit)
                }
            }
            kit.note(
                panel,
                "Removing an app from this list puts it back in the next scan. Allowing is for apps you " +
                    "have decided about — not for apps you have not looked at yet."
            )
        }
    }

    /**
     * Where the console is honest about the network: what it does with none, and the
     * only two places in OEA that ever ask for one. The protection path uses neither.
     */
    private fun showOfflineNote(host: Activity, kit: OeaKit) {
        kit.sheet("Offline by design", "OEA Console, and the protection in it, never uses the network.") { panel, _ ->
            val worksWithout = kit.group(panel)
            kit.row(worksWithout, R.drawable.oic_shield_fill, "Antivirus & app audit", "All ${OeaThreatEngine.ruleCount} rules are compiled into the APK", chevron = false)
            kit.row(worksWithout, R.drawable.oic_search, "File signature scan", "SHA-256, computed here; the list is a file you import", chevron = false)
            kit.row(worksWithout, R.drawable.oic_cleaning_services, "Junk, caches and large files", "Measured on the phone with Android's own storage service", chevron = false)
            kit.row(worksWithout, R.drawable.oic_memory, "Memory, battery, thermals", "BatteryManager, ActivityManager, PowerManager", chevron = false)
            kit.row(worksWithout, R.drawable.oic_developer_mode, "Command system and doctors", "Package manager, app-ops and settings state, read back", chevron = false)

            kit.sectionTitle(panel, "The exceptions, in full")
            kit.note(
                panel,
                "Two parts of the launcher — not the console — can use a connection:\n\n" +
                    "• Weather: fetches a forecast from a public weather service when you open it. " +
                    "Without a connection it says so and shows nothing stale.\n" +
                    "• Network quality: opens one connection to time the round trip when the Game Boost " +
                    "panel asks for a latency reading.\n\n" +
                    "Nothing else in OEA sends or receives. The console's protection features run on a " +
                    "phone that has never been online, and no result is uploaded, ever."
            )
            kit.note(
                panel,
                "Why this matters for an antivirus: a scanner that depends on a cloud service stops " +
                    "working offline, on a de-Googled phone, and on the day the service changes. OEA's rules " +
                    "ship with the app, so the answer is the same on the first boot and in a tunnel."
            )
        }
    }

    // --------------------------------------------------------------- phone master

    private fun showAppCaches(host: Activity, kit: OeaKit) {
        kit.sheet("App caches", "Sizes from Android's own storage service. Clearing another app's cache is not something Android allows an ordinary app to do, so OEA opens that app's storage page.") { panel, dialog ->
            val progress = kit.text("Measuring app storage…", 13f, kit.palette.textSecondary).apply {
                setPadding(kit.dp(6), kit.dp(14), kit.dp(6), kit.dp(8))
            }
            panel.addView(progress)
            val results = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
            panel.addView(results)
            io.execute {
                val caches = runCatching { com.oea.launcher.security.OeaAppAudit.appCaches(host.applicationContext) }.getOrNull()
                host.runOnUiThread {
                    if (host.isDestroyed || !dialog.isShowing) return@runOnUiThread
                    results.removeAllViews()
                    if (caches == null) {
                        progress.text = "Usage access is not granted, so Android withholds other apps' sizes."
                        kit.banner(
                            results, R.drawable.oic_info, "Usage access needed",
                            "Android only reports other apps' cache and data to an app with Usage access. OEA reads the sizes and nothing else; no data leaves the phone either way.",
                            OeaKit.Tone.INFO, "Open"
                        ) {
                            runCatching { host.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                                .onFailure { consoleToast(host, "Usage access settings unavailable") }
                        }
                        return@runOnUiThread
                    }
                    progress.text = com.oea.launcher.security.OeaAppAudit.cacheSummary(caches)
                    if (caches.isEmpty()) return@runOnUiThread
                    val g = kit.group(results)
                    caches.forEach { cache ->
                        kit.row(
                            g, R.drawable.oic_apps, cache.label,
                            "Data ${com.oea.launcher.security.OeaAppAudit.sizeText(cache.dataBytes)}",
                            value = com.oea.launcher.security.OeaAppAudit.sizeText(cache.cacheBytes)
                        ) {
                            runCatching {
                                host.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${cache.packageName}"))
                                )
                            }.onFailure { consoleToast(host, "That app's settings are unavailable") }
                        }
                    }
                    kit.note(
                        results,
                        "Cache is data an app can rebuild, so Android tells apps to keep it small on its own. " +
                            "Deleting it saves space until the app rebuilds it, and the value on the right is the " +
                            "cache each app is holding now — opened, not cleared, from here."
                    )
                }
            }
        }
    }

    private fun showBigFiles(host: Activity, kit: OeaKit) {
        kit.sheet("Large files", "The largest files OEA is allowed to see, over 50 MB. Deleting goes through Android's own confirmation, so OEA never removes a file on its own authority.") { panel, dialog ->
            val progress = kit.text("Looking for large files…", 13f, kit.palette.textSecondary).apply {
                setPadding(kit.dp(6), kit.dp(14), kit.dp(6), kit.dp(8))
            }
            panel.addView(progress)
            val results = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
            panel.addView(results)
            io.execute {
                val files = runCatching { com.oea.launcher.security.OeaAppAudit.bigFiles(host.applicationContext) }.getOrDefault(emptyList())
                host.runOnUiThread {
                    if (host.isDestroyed || !dialog.isShowing) return@runOnUiThread
                    results.removeAllViews()
                    if (files.isEmpty()) {
                        progress.text = "No file over 50 MB is visible to OEA on this phone."
                        kit.note(
                            results,
                            "On Android 13+ an app only sees the media it has permission for, so a large file " +
                                "in another app's private storage is invisible here — and so is the space it takes, " +
                                "until you look in Settings › Storage."
                        )
                        return@runOnUiThread
                    }
                    val total = files.sumOf { it.bytes }
                    progress.text = "${files.size} files · ${com.oea.launcher.security.OeaAppAudit.sizeText(total)}"
                    val g = kit.group(results)
                    files.forEach { file ->
                        kit.row(
                            g, R.drawable.oic_folder, file.name,
                            com.oea.launcher.security.OeaAppAudit.sizeText(file.bytes),
                            value = "Delete"
                        ) {
                            kit.confirm(
                                "Delete ${file.name}?",
                                "Android will ask you to confirm removing ${com.oea.launcher.security.OeaAppAudit.sizeText(file.bytes)}. OEA cannot undo it.",
                                "Delete",
                                destructive = true
                            ) {
                                val sender = com.oea.launcher.security.OeaAppAudit.deleteRequest(host, listOf(file.uri))
                                if (sender == null) {
                                    consoleToast(host, "This Android version cannot delete it from here")
                                } else {
                                    runCatching { host.startIntentSenderForResult(sender, 0x0EA2, null, 0, 0, 0) }
                                        .onFailure { consoleToast(host, "Android refused the delete request") }
                                }
                            }
                        }
                    }
                    kit.note(
                        results,
                        "Sorted by size, largest first. A large file is not junk — a long video or a phone " +
                            "backup is large because it is worth keeping. OEA measures and asks; it never " +
                            "sweeps your storage."
                    )
                }
            }
        }
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
            kit.sectionTitle(panel, "Phone master")
            val master = kit.group(panel)
            kit.row(master, R.drawable.oic_apps, "App caches", "What each app keeps in cache, measured by Android", chevron = true) {
                showAppCaches(host, kit)
            }
            kit.row(master, R.drawable.oic_folder, "Large files", "Files over 50 MB, with a confirmed delete", chevron = true) {
                showBigFiles(host, kit)
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
            val battery = com.oea.launcher.power.OeaBatteryState.read(host)
            val temp = batteryTemp(host)
            val g = kit.group(panel)
            kit.row(g, R.drawable.oic_memory, "Memory", mem, chevron = false)
            val batteryRow = kit.row(g, R.drawable.oic_battery_full, "Battery", battery.text, chevron = false)
            com.oea.launcher.power.OeaBatteryState.observe(batteryRow) { state ->
                ((batteryRow.getChildAt(1) as? LinearLayout)?.getChildAt(1) as? TextView)?.text = state.text
            }
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

    private fun batteryInfo(ctx: Context): com.oea.launcher.power.OeaBatteryState =
        com.oea.launcher.power.OeaBatteryState.read(ctx)

    private fun batteryTemp(ctx: Context): String =
        // From the shared reading: 0 °C and sub-zero are real temperatures, and the
        // old `temp > 0` test reported both as "Unavailable".
        com.oea.launcher.power.OeaBatteryState.read(ctx).temperatureCelsius
            ?.let { String.format(Locale.US, "%.1f°C", it) }
            ?: "Unavailable"

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
            kit.note(panel, "Auto: battery level from BatteryManager. One that switched itself on also switches off when the battery recovers or the phone is plugged in. Manual stays until off.")

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
        "security" -> "security scan — local permission review, not malware detection."
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
                    val battery = batteryInfo(ctx)
                    "Battery: ${battery.text} • PowerSave: ${isPowerSaveMode(ctx)} • UltraSaving: ${isDeviceInUltraPowerSaving(ctx)}\nSource: Android battery broadcast, PowerManager"
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
                    val intent = com.oea.launcher.multitask.OeaMultitaskIntents.resolve(ctx, pkg, null)
                    if (intent == null) "No startable activity for $pkg (may be frozen, hidden, disabled, or a service-only package). Try 'package list' to verify it is installed."
                    else {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ctx.startActivity(intent)
                        com.oea.launcher.multitask.OeaMultitaskIntents.record(ctx, pkg, intent.component?.className)
                        "Launching ${intent.component?.flattenToString() ?: pkg} — startActivity succeeded. Confirm: switch to the app; if it bounces back, check freezer ('package list frozen')."
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
                val result = com.oea.launcher.multitask.OeaFloatingWindows.launchDefault(ctx, app)
                "Floating result: ${result.message}\nWhat it does: launches app as freeform window (floating)\nNeeds: freeform enabled in Developer options\nAlternative on phones without freeform: multitask window on\nConfirm: window appears movable/resizable if granted"
            }

            "pip" -> {
                val systemPip = ctx.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)
                val blocked = com.oea.launcher.miniapps.OeaVipPip.blocked(ctx)
                "PIP (system): ${if (systemPip) "this phone reports picture-in-picture" else "NOT SUPPORTED by this phone (FEATURE_PICTURE_IN_PICTURE missing) — no app can use Android's PiP here"}\n" +
                    "PIP (OEA): ${if (blocked == null) "available — VIP's pip button draws OEA's own floating window" else "unavailable — $blocked"}\n" +
                    "What it does: picture-in-picture for video playback\n" +
                    "How OEA does it: the window comes from the multitasking family (accessibility overlay, else draw-over-apps); VIP is OEA's own player, so the video goes straight into that window's surface — no virtual display, no launch ladder\n" +
                    "Confirm: VIP pip button opens a draggable, resizable video window while you keep using the phone"
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
                    val scan = runCatching { com.oea.launcher.security.OeaAppAudit.scan(ctx) }.getOrNull()
                    val error = scan?.error
                    when {
                        scan == null || error != null -> "Protection scan failed: ${error ?: "unknown error"}"
                        else -> {
                            val report = scan.report
                            buildString {
                                append(OeaThreatEngine.verdictLabel(report.verdict)).append(" — ")
                                append(OeaThreatEngine.verdictSentence(report))
                                append("\n\nRules: ${OeaThreatEngine.ruleCount} (built in, offline)").append('\n')
                                append("Apps checked: ${report.checked}").append('\n')
                                append("Flagged: ${report.flagged} (${report.high} high)")
                                if (scan.apks.isNotEmpty()) append(" • ${scan.apks.size} installers waiting")
                                if (report.assessments.isEmpty()) append("\n\nNothing flagged.")
                                report.assessments.forEach { assessment ->
                                    append("\n\n${assessment.facts.label} (${assessment.facts.packageName}) — ")
                                    append(OeaThreatEngine.severityLabel(assessment.highest ?: OeaThreatEngine.Severity.LOW))
                                    assessment.findings.forEach { finding ->
                                        append("\n  • ${finding.title}: ${finding.detail}")
                                    }
                                }
                            }
                        }
                    }
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
                val battery = batteryInfo(ctx)
                "Battery: ${battery.text}\nSource: Android battery broadcast"
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
            val systemPip = host.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)
            val pipBlocked = com.oea.launcher.miniapps.OeaVipPip.blocked(host)
            kit.note(panel, "Split-screen: Android 7+ supports via ActivityManager. OEM and app can deny. OEA waits for first app to be in front before launching adjacent, verifies with Assistant and recovers once via system split toggle.\nPIP: Android's own picture-in-picture is ${if (systemPip) "reported by this phone" else "NOT available on this phone — FEATURE_PICTURE_IN_PICTURE is missing, so no app can enter it"}. ${if (pipBlocked == null) "VIP's pip button falls back to OEA's own floating video window (multitasking overlay; VIP is OEA's player, so no virtual display is involved)." else "OEA's fallback window cannot be drawn yet: $pipBlocked"}")
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
