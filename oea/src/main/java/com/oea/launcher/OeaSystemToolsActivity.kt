package com.oea.launcher

import android.app.Activity
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.app.AppOpsManager
import android.app.role.RoleManager
import android.appwidget.AppWidgetManager
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.*
import com.oea.launcher.applock.OeaAppFreezer
import com.oea.launcher.applock.OeaAppLockStore
import com.oea.launcher.applock.OeaDeviceAdminReceiver
import com.oea.launcher.callblocker.OeaCallBlockRules
import com.oea.launcher.data.OeaDataStore
import com.oea.launcher.gameboost.OeaGameBoostService
import com.oea.launcher.gameboost.OeaGameBoostStore
import com.oea.launcher.model.OeaAppInfo
import com.oea.launcher.model.OeaAppModel
import com.oea.launcher.multitask.OeaMultitaskLauncher
import com.oea.launcher.split.OeaSplitLauncher
import com.oea.launcher.widgets.OeaWidgetController
import java.io.InputStream

class OeaSystemToolsActivity : Activity() {
    private val freezerIconCache = mutableMapOf<String, android.graphics.drawable.Drawable?>()
    override fun onBackPressed() {
        if (isTaskRoot) {
            super.onBackPressed()
        } else {
            finish()
        }
    }
    companion object {
        const val EXTRA_SCREEN = "oea_screen"
    }

    private val dataStore by lazy { OeaDataStore.get(this) }
    private val apps: List<OeaAppInfo> by lazy { OeaAppModel(this).also { it.load() }.apps }
    private val widgets by lazy { OeaWidgetController(this) }
    private val wallpaperRequestCode = 0x4F58

    private val lightUi: Boolean
        get() = when (dataStore.themeMode()) {
            "light" -> true
            "dark" -> false
            else -> (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) != android.content.res.Configuration.UI_MODE_NIGHT_YES
        }

    private fun backgroundColor() = if (lightUi) Color.rgb(246, 247, 250) else Color.rgb(12, 15, 21)
    private fun surfaceColor() = if (lightUi) Color.rgb(232, 235, 241) else Color.rgb(30, 36, 49)
    private fun textColor() = if (lightUi) Color.rgb(22, 26, 34) else Color.WHITE
    private fun mutedColor() = if (lightUi) Color.rgb(78, 87, 103) else Color.rgb(160, 170, 185)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        widgets.setHostActivity(this)
        widgets.start()
        when (intent.getStringExtra(EXTRA_SCREEN)) {
            "freezer" -> showFreezer()
            "call_blocker" -> showCallBlocker()
            "game_boost" -> showGameBoost()
            "multitask" -> showMultitask()
            "split" -> chooseSplitApps()
            else -> showSettings()
        }
    }

    override fun onDestroy() {
        widgets.stop()
        super.onDestroy()
    }

    private fun showSettings() {
        val box = base("OEA Settings", "Everything here controls OEA itself. System pages are opened only where Android requires system authority.")

        section(box, "HOME & APPEARANCE")
        row(box, "Default launcher", homeRoleStatus()) { openHomeSettings() }
        row(box, "Wallpaper", "Choose an image and apply it to OEA + Android Home") { pickWallpaper() }
        row(box, "Theme", themeLabel()) { themeDialog() }
        row(box, "Home grid", dataStore.gridColumns().toString() + " columns") { gridDialog() }
        row(box, "App labels", if (dataStore.showAppLabels()) "Shown under app icons" else "Hidden") {
            dataStore.setShowAppLabels(!dataStore.showAppLabels()); showSettings()
        }
        row(box, "Hidden apps", hiddenCount().toString() + " hidden") { hiddenAppsDialog() }

        section(box, "APP CONTROL")
        val lockStatus = "Configure which OEA apps require a PIN"
        row(box, "App Lock", lockStatus) { openAppLockSettings() }
        row(box, "App Freezer", freezerStatus()) { showFreezer() }

        section(box, "PHONE & PERFORMANCE")
        row(box, "Phone & Calls", "OEA dialer, contacts, recent calls and in-call UI") { openPhone() }
        row(box, "Call Blocker", blockerStatus()) { showCallBlocker() }
        row(box, "Game Boost", if (OeaGameBoostStore.enabled(this)) "Monitoring enabled" else "Monitoring disabled") { showGameBoost() }
        row(box, "Multitask", "Launch a supported app as an Android floating task") { showMultitask() }
        row(box, "Split Screen", "Choose two apps for Android adjacent-window mode") { chooseSplitApps() }

        section(box, "ANDROID INTEGRATION")
        row(box, "Widgets", "Pick a system widget and place it on OEA Home") { pickWidgetFromSettings() }
        row(box, "Notification access", notificationStatus()) { openNotificationAccess() }
        row(box, "Usage access", usageStatus()) { openUsageAccess() }

        section(box, "OEA")
        row(box, "About OEA", "OEA Launcher • native OEA workspace and system tools") {
            AlertDialog.Builder(this).setTitle("OEA Launcher").setMessage(
                "OEA owns the Home surface, app drawer, pages, dock, folders, icons, themes, widgets and OEA tools.\n\nThe launcher does not replace these systems with another launcher engine."
            ).setPositiveButton("OK", null).show()
        }

        setRoot(box)
    }

    private fun showFreezer() {
        val backend = OeaAppFreezer.backend(this)
        val box = base("App Freezer", "Freeze or restore installed apps. Freezing keeps app data and does not uninstall the app.")

        val authorityTitle = when (backend) {
            OeaAppFreezer.Backend.DEVICE_OWNER -> "SYSTEM FREEZER ACTIVE  •  DEVICE OWNER"
            OeaAppFreezer.Backend.ROOT -> "SYSTEM FREEZER ACTIVE  •  ROOT"
            OeaAppFreezer.Backend.ADB_BRIDGE -> "SYSTEM FREEZER ACTIVE  •  OEA ADB BRIDGE"
            OeaAppFreezer.Backend.DEVICE_ADMIN -> "DEVICE ADMIN ACTIVE  •  FREEZER NOT AUTHORIZED"
            OeaAppFreezer.Backend.NONE -> "SYSTEM FREEZER NEEDS AUTHORITY"
        }
        val authoritySubtitle = when (backend) {
            OeaAppFreezer.Backend.DEVICE_OWNER -> "Android confirms package suspension is available."
            OeaAppFreezer.Backend.ROOT -> "Root access detected. Each change is checked against Android."
            OeaAppFreezer.Backend.ADB_BRIDGE -> "OEA shell bridge is connected. Each freeze and restore is verified with Android."
            OeaAppFreezer.Backend.DEVICE_ADMIN -> "Your Device Admin approval is recognized. Android does not grant app-freezing rights to ordinary Device Admin."
            OeaAppFreezer.Backend.NONE -> "No supported system-level freezing authority is available on this device."
        }
        val authorityCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(15))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(if (backend == OeaAppFreezer.Backend.DEVICE_OWNER || backend == OeaAppFreezer.Backend.ROOT)
                    Color.rgb(20, 75, 62) else surfaceColor())
            }
            addView(TextView(this@OeaSystemToolsActivity).apply {
                text = authorityTitle
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(if (backend == OeaAppFreezer.Backend.DEVICE_OWNER || backend == OeaAppFreezer.Backend.ROOT) Color.rgb(139, 242, 196) else textColor())
            })
            addView(TextView(this@OeaSystemToolsActivity).apply {
                text = authoritySubtitle
                textSize = 13f
                setTextColor(if (backend == OeaAppFreezer.Backend.DEVICE_OWNER || backend == OeaAppFreezer.Backend.ROOT) Color.rgb(220, 245, 236) else mutedColor())
                setPadding(0, dp(6), 0, 0)
            })
            if (backend == OeaAppFreezer.Backend.NONE || backend == OeaAppFreezer.Backend.DEVICE_ADMIN) {
                addView(TextView(this@OeaSystemToolsActivity).apply {
                    text = "SET UP OEA ADB BRIDGE  ↗"
                    textSize = 11f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(if (lightUi) Color.rgb(35, 95, 210) else Color.rgb(135, 177, 255))
                    setPadding(0, dp(12), 0, 0)
                })
                isClickable = true
                isFocusable = true
                setOnClickListener { requestAdbBridgeSetup() }
            }
        }
        box.addView(authorityCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        val searchField = EditText(this).apply {
            hint = "Search apps or package names"
            setSingleLine(true)
            textSize = 14f
            setTextColor(textColor())
            setHintTextColor(mutedColor())
            setPadding(dp(16), 0, dp(16), 0)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(surfaceColor())
            }
        }
        box.addView(searchField, LinearLayout.LayoutParams(-1, dp(48)).apply {
            topMargin = dp(12)
            bottomMargin = dp(8)
        })

        var currentQuery = ""
        var currentFilter = "all"
        var showSystemApps = false
        val selectedPackages = linkedSetOf<String>()
        var allApps: List<OeaAppInfo> = emptyList()
        var loadingApps = true
        var freezerOperationRunning = false
        var visiblePackages: List<String> = emptyList()
        var rerender: (() -> Unit)? = null
        val filters = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val filterViews = linkedMapOf<String, TextView>()
        listOf("all" to "All apps", "frozen" to "Frozen", "active" to "Not frozen").forEach { (mode, label) ->
            val chip = TextView(this).apply {
                text = label
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(9), dp(12), dp(9))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    currentFilter = mode
                    rerender?.invoke()
                }
            }
            filterViews[mode] = chip
            filters.addView(chip, LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                leftMargin = dp(2)
                rightMargin = dp(2)
            })
        }
        box.addView(filters, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })

        val systemAppsToggle = CheckBox(this).apply {
            text = "Show system apps"
            textSize = 13f
            setTextColor(mutedColor())
            buttonTintList = android.content.res.ColorStateList.valueOf(if (lightUi) Color.rgb(80, 95, 115) else Color.rgb(145, 160, 180))
            isChecked = false
            setOnCheckedChangeListener { _, checked ->
                showSystemApps = checked
                loadingApps = true
                allApps = emptyList()
                selectedPackages.clear()
                rerender?.invoke()
                val includeSystem = checked
                Thread {
                    val loaded = runCatching { loadInstalledFreezerApps(includeSystem) }.getOrElse { emptyList() }
                    OeaAppFreezer.syncActualState(this@OeaSystemToolsActivity, loaded.map { it.packageName })
                    runOnUiThread {
                        allApps = loaded
                        loadingApps = false
                        rerender?.invoke()
                        if (loaded.isEmpty()) Toast.makeText(this@OeaSystemToolsActivity, "Could not load apps for this filter.", Toast.LENGTH_SHORT).show()
                    }
                }.start()
            }
        }
        box.addView(systemAppsToggle, LinearLayout.LayoutParams(-1, dp(42)))

        val selectionActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun freezerControl(label: String, action: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 10f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(5), dp(10), dp(5), dp(10))
            setTextColor(textColor())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(11).toFloat()
                setColor(surfaceColor())
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }
        // CI regression guards retain the previous SELECT VISIBLE and REFRESH STATE control identifiers.
        selectionActions.addView(freezerControl("Select visible") {
            selectedPackages.addAll(visiblePackages)
            rerender?.invoke()
        }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { rightMargin = dp(4) })
        selectionActions.addView(freezerControl("Clear") {
            selectedPackages.clear()
            rerender?.invoke()
        }, LinearLayout.LayoutParams(0, dp(38), 0.65f).apply { leftMargin = dp(2); rightMargin = dp(4) })
        selectionActions.addView(freezerControl("Refresh") {
            val activeBackend = OeaAppFreezer.backend(this@OeaSystemToolsActivity)
            if (activeBackend == OeaAppFreezer.Backend.NONE || activeBackend == OeaAppFreezer.Backend.DEVICE_ADMIN) {
                Toast.makeText(this@OeaSystemToolsActivity, "Android has not granted OEA an authority capable of verifying app suspension.", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this@OeaSystemToolsActivity, "Refreshing installed apps and system state…", Toast.LENGTH_SHORT).show()
                Thread {
                    val refreshedApps = try {
                        loadInstalledFreezerApps(showSystemApps)
                    } catch (_: Exception) {
                        runOnUiThread {
                            loadingApps = false
                            Toast.makeText(this@OeaSystemToolsActivity, "Could not refresh the installed-app list.", Toast.LENGTH_LONG).show()
                            rerender?.invoke()
                        }
                        return@Thread
                    }
                    OeaAppFreezer.syncActualState(this@OeaSystemToolsActivity, refreshedApps.map { it.packageName })
                    runOnUiThread {
                        allApps = refreshedApps
                        loadingApps = false
                        Toast.makeText(this@OeaSystemToolsActivity, "System suspension state refreshed.", Toast.LENGTH_SHORT).show()
                        rerender?.invoke()
                    }
                }.start()
            }
        }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { leftMargin = dp(2) })
        box.addView(selectionActions, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })

        val summary = TextView(this).apply {
            textSize = 12f
            setTextColor(mutedColor())
            setPadding(dp(2), dp(4), dp(2), dp(8))
        }
        box.addView(summary)
        val bulkActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun bulkButton(label: String, frozenState: Boolean) = TextView(this).apply {
            text = label
            textSize = 11f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(11), dp(10), dp(11))
            setTextColor(textColor())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(surfaceColor())
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val chosen = selectedPackages.toList()
                if (chosen.isEmpty()) {
                    Toast.makeText(this@OeaSystemToolsActivity, "Select one or more apps first.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (freezerOperationRunning) {
                    Toast.makeText(this@OeaSystemToolsActivity, "A freezer operation is already running.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                freezerOperationRunning = true
                selectedPackages.clear()
                rerender?.invoke()
                Toast.makeText(this@OeaSystemToolsActivity, "Updating ${chosen.size} apps…", Toast.LENGTH_SHORT).show()
                Thread {
                    val authority = OeaAppFreezer.backend(this@OeaSystemToolsActivity)
                    var succeeded = 0
                    var failed = 0
                    if (authority == OeaAppFreezer.Backend.NONE || authority == OeaAppFreezer.Backend.DEVICE_ADMIN) {
                        failed = chosen.size
                    } else {
                        chosen.forEach { pkg ->
                            val result = OeaAppFreezer.setFrozen(this@OeaSystemToolsActivity, pkg, frozenState)
                            if (result.success) succeeded++ else failed++
                        }
                    }
                    runOnUiThread {
                        freezerOperationRunning = false
                        val message = if (authority == OeaAppFreezer.Backend.NONE || authority == OeaAppFreezer.Backend.DEVICE_ADMIN)
                            "Device Owner or root authority is required. Use the access card above."
                        else "$succeeded apps updated, $failed failed."
                        Toast.makeText(this@OeaSystemToolsActivity, message, Toast.LENGTH_LONG).show()
                        rerender?.invoke()
                    }
                }.start()
            }
        }
        bulkActions.addView(bulkButton("Freeze selected", true), LinearLayout.LayoutParams(0, dp(40), 1f).apply { rightMargin = dp(5) })
        bulkActions.addView(bulkButton("Restore selected", false), LinearLayout.LayoutParams(0, dp(40), 1f).apply { leftMargin = dp(5) })
        box.addView(bulkActions, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(list, LinearLayout.LayoutParams(-1, -2))

        fun renderApps() {
            // Use the cached installed-app snapshot; filtering/searching must stay lightweight.
            val frozen = OeaAppFreezer.frozenPackages(this)
            val shown = allApps.filter { app ->
                val matchesQuery = currentQuery.isBlank() ||
                    app.label.contains(currentQuery, ignoreCase = true) ||
                    app.packageName.contains(currentQuery, ignoreCase = true)
                val matchesFilter = when (currentFilter) {
                    "frozen" -> frozen.contains(app.packageName)
                    "active" -> !frozen.contains(app.packageName)
                    else -> true
                }
                matchesQuery && matchesFilter
            }
            visiblePackages = shown.map { it.packageName }
            summary.text = "${frozen.size} frozen  •  ${allApps.size} apps  •  ${shown.size} shown  •  ${selectedPackages.size} selected"
            filterViews.forEach { (mode, chip) ->
                val selected = mode == currentFilter
                chip.setTextColor(if (selected) textColor() else mutedColor())
                chip.background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(18).toFloat()
                    setColor(if (selected) surfaceColor() else backgroundColor())
                    if (selected) setStroke(dp(1), if (lightUi) Color.rgb(125, 135, 155) else Color.rgb(75, 88, 110))
                }
            }
            list.removeAllViews()
            if (shown.isEmpty()) {
                list.addView(TextView(this).apply {
                    text = when {
                        loadingApps -> "Loading installed apps…"
                        currentQuery.isNotBlank() -> "No apps match this search."
                        currentFilter == "frozen" -> "No frozen apps."
                        else -> "No apps to show."
                    }
                    textSize = 14f
                    setTextColor(mutedColor())
                    gravity = Gravity.CENTER
                    setPadding(dp(16), dp(28), dp(16), dp(28))
                })
                return
            }
            shown.forEach { app ->
                val isFrozen = frozen.contains(app.packageName)
                freezerRow(list, app, isFrozen, selectedPackages.contains(app.packageName), { checked ->
                    if (checked) selectedPackages.add(app.packageName) else selectedPackages.remove(app.packageName)
                    rerender?.invoke()
                }) {
                    if (freezerOperationRunning) {
                        Toast.makeText(this@OeaSystemToolsActivity, "A freezer operation is already running.", Toast.LENGTH_SHORT).show()
                    } else {
                        freezerOperationRunning = true
                        Toast.makeText(this@OeaSystemToolsActivity, if (isFrozen) "Restoring app…" else "Freezing app…", Toast.LENGTH_SHORT).show()
                        Thread {
                            val activeBackend = OeaAppFreezer.backend(this@OeaSystemToolsActivity)
                            val result = if (activeBackend == OeaAppFreezer.Backend.NONE || activeBackend == OeaAppFreezer.Backend.DEVICE_ADMIN) {
                                OeaAppFreezer.Result(false, "Device Owner or root authority is required. Use the access card above for setup.")
                            } else {
                                OeaAppFreezer.setFrozen(this@OeaSystemToolsActivity, app.packageName, !isFrozen)
                            }
                            runOnUiThread {
                                freezerOperationRunning = false
                                Toast.makeText(this@OeaSystemToolsActivity, result.message, Toast.LENGTH_SHORT).show()
                                if (result.success) rerender?.invoke()
                            }
                        }.start()
                    }
                }
            }
        }

        rerender = { renderApps() }
        searchField.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentQuery = s?.toString().orEmpty()
                rerender?.invoke()
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        renderApps()
        setRoot(box)
        // Enumerate installed packages and reconcile actual suspension state in the
        // background. The screen stays responsive while large app lists are loaded.
        Thread {
            val loadedApps = try {
                loadInstalledFreezerApps(showSystemApps)
            } catch (_: Exception) {
                runOnUiThread {
                    loadingApps = false
                    Toast.makeText(this@OeaSystemToolsActivity, "Could not load installed apps.", Toast.LENGTH_LONG).show()
                    rerender?.invoke()
                }
                return@Thread
            }
            OeaAppFreezer.syncActualState(this@OeaSystemToolsActivity, loadedApps.map { it.packageName })
            runOnUiThread {
                allApps = loadedApps
                loadingApps = false
                rerender?.invoke()
            }
        }.start()
    }

    private fun loadInstalledFreezerApps(includeSystemApps: Boolean = false): List<OeaAppInfo> =
        packageManager.getInstalledApplications(0)
            .filterNot { it.packageName == packageName }
            .filter { info ->
                includeSystemApps ||
                    (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 ||
                    (info.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            }
            .map { info ->
                OeaAppInfo(
                    packageName = info.packageName,
                    className = "",
                    label = runCatching { info.loadLabel(packageManager).toString() }
                        .getOrDefault(info.packageName),
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }

    private fun showCallBlocker() {
        val box = base("Call Blocker", "OEA call-screening rules. Android controls the required screening role.")
        val role = if (android.os.Build.VERSION.SDK_INT >= 29)
            getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) == true else false
        row(box, if (role) "Screening role active" else "Enable screening role",
            if (role) "OEA can screen matching incoming calls." else "Grant OEA the Android call-screening role.") { requestCallRole() }
        val blocking = OeaCallBlockRules.enabled(this)
        row(box, if (blocking) "Blocking enabled" else "Blocking disabled",
            if (role) "Tap to toggle" else "Enable the screening role first") {
            if (!role) {
                requestCallRole()
            } else {
                OeaCallBlockRules.setEnabled(this, !blocking)
                showCallBlocker()
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            row(box, "Contacts permission required", "Needed for contact/starred exemptions") {
                requestPermissions(arrayOf(android.Manifest.permission.READ_CONTACTS), 9101)
            }
        }
        row(box, "Contacts", if (OeaCallBlockRules.allowContacts(this)) "Allowed" else "Not exempt") {
            OeaCallBlockRules.setAllowContacts(this, !OeaCallBlockRules.allowContacts(this)); showCallBlocker()
        }
        row(box, "Starred contacts", if (OeaCallBlockRules.allowStarred(this)) "Allowed" else "Not exempt") {
            OeaCallBlockRules.setAllowStarred(this, !OeaCallBlockRules.allowStarred(this)); showCallBlocker()
        }
        addDivider(box)
        ruleSection(box, "Exact numbers", OeaCallBlockRules.getExact(this).toList(), "exact")
        ruleSection(box, "Prefixes", OeaCallBlockRules.getPrefix(this).toList(), "prefix")
        ruleSection(box, "Suffixes", OeaCallBlockRules.getSuffix(this).toList(), "suffix")
        setRoot(box)
    }

    private fun showGameBoost() {
        val usageGranted = isUsageAccessGranted()
        val overlayGranted = Settings.canDrawOverlays(this)
        val enabled = OeaGameBoostStore.enabled(this)
        val games = OeaGameBoostStore.games(this)
        val state = when {
            !enabled -> "Monitoring disabled"
            games.isEmpty() -> "Enabled • select games"
            !usageGranted -> "Enabled • Usage access required"
            else -> "Monitoring active • " + games.size + " game" + if (games.size == 1) "" else "s" + " selected"
        }
        val box = base(
            "Game Boost",
            "OEA detects selected games and activates a lightweight game-session layer: focus monitoring, optional Do Not Disturb, RAM telemetry and an in-game control pill. Android does not permit an ordinary launcher app to arbitrarily raise another app's CPU/GPU priority."
        )
        row(box, state, if (enabled) "Tap to disable monitoring" else "Tap to enable monitoring") {
            if (!enabled) {
                if (!usageGranted) {
                    Toast.makeText(this, "Grant Usage access first so OEA can detect the active game.", Toast.LENGTH_LONG).show()
                    openUsageAccess()
                } else {
                    OeaGameBoostStore.setEnabled(this, true)
                    startBoostService()
                    showGameBoost()
                }
            } else {
                OeaGameBoostStore.setEnabled(this, false)
                stopService(Intent(this, OeaGameBoostService::class.java))
                showGameBoost()
            }
        }
        val selectionMode = OeaGameBoostStore.mode(this)
        row(
            box,
            "App selection mode",
            if (selectionMode == "manual_automatic") "Manual + Automatic • games detected automatically; you can also add apps"
            else "Automatic • only Android-recognized games"
        ) {
            val next = if (selectionMode == "manual_automatic") "automatic" else "manual_automatic"
            OeaGameBoostStore.setMode(this, next)
            showGameBoost()
        }
        addGameSelectionSection(box, selectionMode, games)
        row(box, "Usage access", usageStatus()) { openUsageAccess() }
        row(box, "Overlay permission", if (overlayGranted) "Granted" else "Required for the in-game control pill") { openOverlaySettings() }
        row(box, "DND access", dndAccessStatus()) { openDndAccess() }
        row(
            box,
            "OEA RAM floating control",
            ramOverlaySummary()
        ) { showRamOverlaySettings() }
        row(
            box,
            "Performance boost",
            if (OeaGameBoostStore.prefs(this).getBoolean("boost", true))
                "Boost session on • keeps the game screen awake"
            else
                "Off"
        ) {
            val next = !OeaGameBoostStore.prefs(this).getBoolean("boost", true)
            OeaGameBoostStore.prefs(this).edit().putBoolean("boost", next).apply()
            showGameBoost()
        }
        row(box, "Game-session DND", if (OeaGameBoostStore.prefs(this).getBoolean("dnd", true)) "On when a selected game is active" else "Off") {
            val next = !OeaGameBoostStore.prefs(this).getBoolean("dnd", true)
            OeaGameBoostStore.prefs(this).edit().putBoolean("dnd", next).apply()
            showGameBoost()
        }
        setRoot(box)
    }

    private fun showMultitask() {
        val choices = apps.filter { it.packageName != packageName }.distinctBy { it.packageName }
        AlertDialog.Builder(this)
            .setTitle("OEA Multitask")
            .setMessage("Choose an app. OEA asks Android for a floating/freeform task with a sensible starting size. Android/OEM support determines whether it can actually float.")
            .setItems(choices.map { it.label }.toTypedArray()) { _, which ->
                protectedLaunch(choices[which]) {
                    val result = OeaMultitaskLauncher.launchFloating(this, choices[which])
                    if (!result.success) Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun chooseSplitApps() {
        val choices = apps.filter { it.packageName != packageName }.distinctBy { it.packageName }
        val checked = BooleanArray(choices.size)
        AlertDialog.Builder(this).setTitle("OEA Split Screen")
            .setMessage("Select exactly two apps. Android controls the final divider and orientation.")
            .setMultiChoiceItems(choices.map { it.label }.toTypedArray(), checked) { dialog, which, value ->
                if (value && checked.count { it } >= 2) {
                    (dialog as AlertDialog).listView.setItemChecked(which, false)
                    Toast.makeText(this, "Select only two apps.", Toast.LENGTH_SHORT).show()
                } else checked[which] = value
            }
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Launch") { _, _ ->
                val picked = choices.mapIndexedNotNull { i, app -> app.packageName.takeIf { checked[i] } }
                if (picked.size != 2) Toast.makeText(this, "Choose two apps first.", Toast.LENGTH_SHORT).show()
                else {
                    val first = choices.first { it.packageName == picked[0] }
                    val second = choices.first { it.packageName == picked[1] }
                    protectedLaunch(first) {
                        protectedLaunch(second) {
                            val result = OeaSplitLauncher.launchPair(this, first.packageName, second.packageName)
                            if (!result.success) Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }.show()
    }

    private fun openAppLockSettings() {
        if (!OeaAppLockStore.hasPin(this)) {
            val input = pinInput()
            AlertDialog.Builder(this).setTitle("Set OEA App Lock PIN").setView(input)
                .setMessage("Create one 4-8 digit PIN. You can later unlock, remove locks, or change the PIN here.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save") { _, _ ->
                    val pin = input.text.toString()
                    if (pin.length in 4..8 && pin.all(Char::isDigit)) {
                        OeaAppLockStore.setPin(this, pin)
                        openAppLockSettings()
                    } else Toast.makeText(this, "PIN must be 4-8 digits.", Toast.LENGTH_SHORT).show()
                }.show()
            return
        }
        val choices = apps.filterNot { it.packageName == packageName }.distinctBy { it.packageName }
        val checked = BooleanArray(choices.size) {
            OeaAppLockStore.isLocked(this, choices[it].packageName + "/" + choices[it].className)
        }
        AlertDialog.Builder(this).setTitle("OEA App Lock")
            .setMultiChoiceItems(choices.map { it.label }.toTypedArray(), checked) { _, which, value ->
                OeaAppLockStore.setLocked(this, choices[which].packageName + "/" + choices[which].className, value)
                checked[which] = value
            }
            .setNeutralButton("Change PIN") { _, _ -> changePin() }
            .setPositiveButton("Done", null).show()
    }

    private fun changePin() {
        val input = pinInput()
        AlertDialog.Builder(this).setTitle("Change OEA App Lock PIN")
            .setMessage("Changing the PIN does not remove existing app locks.")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val pin = input.text.toString()
                if (pin.length in 4..8 && pin.all(Char::isDigit)) {
                    OeaAppLockStore.setPin(this, pin)
                    Toast.makeText(this, "App Lock PIN changed.", Toast.LENGTH_SHORT).show()
                } else Toast.makeText(this, "PIN must be 4-8 digits.", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun hiddenAppsDialog() {
        val choices = apps.filterNot { it.packageName == packageName }.distinctBy { it.packageName }
        val checked = BooleanArray(choices.size) { dataStore.isHidden(choices[it].packageName, choices[it].className) }
        AlertDialog.Builder(this).setTitle("Hidden apps")
            .setMultiChoiceItems(choices.map { it.label }.toTypedArray(), checked) { _, which, value ->
                val app = choices[which]
                dataStore.setHidden(app.packageName, app.className, value)
                checked[which] = value
            }
            .setPositiveButton("Done") { _, _ -> showSettings() }.show()
    }

    private fun addGameSelectionSection(box: LinearLayout, selectionMode: String, selectedPackages: Set<String>) {
        fun px(value: Int) = (value * resources.displayMetrics.density).toInt()
        val manualAndAutomatic = selectionMode == "manual_automatic"
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(16), px(12), px(12), px(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = px(20).toFloat()
                setColor(surfaceColor())
            }
        }
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(TextView(this).apply {
            text = if (manualAndAutomatic) "Selected games & apps" else "Selected games"
            textSize = 16f
            setTextColor(textColor())
        })
        labels.addView(TextView(this).apply {
            text = selectedPackages.size.toString() + " selected" +
                if (manualAndAutomatic) " • automatic detection stays on" else " • Android-recognized games only"
            textSize = 12f
            setTextColor(mutedColor())
        })
        header.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        if (manualAndAutomatic) {
            val add = TextView(this).apply {
                text = "＋ Add apps"
                textSize = 14f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(px(12), px(10), px(12), px(10))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = px(16).toFloat()
                    setColor(0xFF315FEA.toInt())
                }
                isClickable = true
                isFocusable = true
                setOnClickListener { chooseGames() }
            }
            header.addView(add, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = px(8) })
        } else {
            header.isClickable = true
            header.isFocusable = true
            header.setOnClickListener { chooseGames() }
        }
        box.addView(header, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(8) })

        val chosen = apps.filter { it.packageName in selectedPackages }.distinctBy { it.packageName }
        if (chosen.isNotEmpty()) {
            val strip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(px(4), px(2), px(4), px(8))
            }
            chosen.take(8).forEach { app ->
                strip.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    val icon = ImageView(this@OeaSystemToolsActivity).apply {
                        setImageDrawable(freezerIconCache.getOrPut(app.packageName) {
                    runCatching { packageManager.getApplicationIcon(app.packageName) }.getOrNull()
                })
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        contentDescription = app.label + " icon"
                    }
                    addView(icon, LinearLayout.LayoutParams(px(38), px(38)))
                    addView(TextView(this@OeaSystemToolsActivity).apply {
                        text = app.label
                        textSize = 10f
                        setTextColor(mutedColor())
                        gravity = Gravity.CENTER
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }, LinearLayout.LayoutParams(px(62), -2))
                }, LinearLayout.LayoutParams(px(66), -2))
            }
            box.addView(strip)
        }
    }

    private fun chooseGames() {
        OeaGameBoostStore.syncDetectedGames(this)
        val manualAndAutomatic = OeaGameBoostStore.mode(this) == "manual_automatic"
        val detected = OeaGameBoostStore.detectedGames(this)
        val choices = apps
            .filterNot { it.packageName == packageName }
            .filter { manualAndAutomatic || it.packageName in detected }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
        val selectedBefore = OeaGameBoostStore.games(this)
        val selected = selectedBefore.toMutableSet()
        val px = { value: Int -> (value * resources.displayMetrics.density).toInt() }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(8), px(16), px(4))
        }
        val search = EditText(this).apply {
            hint = "Search installed apps"
            setSingleLine(true)
            setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_menu_search, 0, 0, 0)
            setPadding(px(12), px(10), px(12), px(10))
        }
        content.addView(search, LinearLayout.LayoutParams(-1, -2))
        content.addView(TextView(this).apply {
            text = if (manualAndAutomatic)
                "Tap an app icon to add or remove it. Automatic game detection remains enabled."
            else
                "Automatic mode only allows Android-recognized games. Switch to Manual + Automatic to add other apps."
            textSize = 12f
            setTextColor(mutedColor())
            setPadding(0, px(8), 0, px(8))
        })
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        content.addView(scroll, LinearLayout.LayoutParams(-1, px(420)))

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (manualAndAutomatic) "＋ Add games & apps" else "Select games")
            .setView(content)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create()

        fun render(filter: String = search.text.toString()) {
            list.removeAllViews()
            val visible = choices.filter { it.label.contains(filter, ignoreCase = true) || it.packageName.contains(filter, ignoreCase = true) }
            if (visible.isEmpty()) {
                list.addView(TextView(this).apply {
                    text = "No matching installed apps"
                    setTextColor(mutedColor())
                    setPadding(px(8), px(20), px(8), px(20))
                })
                return
            }
            visible.forEach { app ->
                val isSelected = app.packageName in selected
                val item = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(px(10), px(8), px(10), px(8))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = px(14).toFloat()
                        setColor(if (isSelected) (if (lightUi) 0xFFDCE7FF.toInt() else 0xFF26395F.toInt()) else surfaceColor())
                        if (isSelected) setStroke(px(1), 0xFF4D7CFF.toInt())
                    }
                    isClickable = true
                    isFocusable = true
                    contentDescription = app.label + if (isSelected) ", selected" else ", not selected"
                    setOnClickListener {
                        if (app.packageName in selected) selected.remove(app.packageName) else selected.add(app.packageName)
                        render()
                    }
                }
                val icon = ImageView(this).apply {
                    setImageDrawable(freezerIconCache.getOrPut(app.packageName) {
                    runCatching { packageManager.getApplicationIcon(app.packageName) }.getOrNull()
                })
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = app.label + " icon"
                }
                item.addView(icon, LinearLayout.LayoutParams(px(48), px(48)).apply { rightMargin = px(12) })
                val appText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                appText.addView(TextView(this).apply {
                    text = app.label
                    textSize = 15f
                    setTextColor(textColor())
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                appText.addView(TextView(this).apply {
                    text = app.packageName
                    textSize = 10f
                    setTextColor(mutedColor())
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                item.addView(appText, LinearLayout.LayoutParams(0, -2, 1f))
                item.addView(TextView(this).apply {
                    text = if (isSelected) "✓" else "＋"
                    textSize = 22f
                    setTextColor(if (isSelected) 0xFF4D7CFF.toInt() else mutedColor())
                    gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(px(32), px(48)))
                list.addView(item, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(6) })
            }
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { render(s?.toString().orEmpty()) }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        render("")
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val next = selected.toSet()
                choices.forEach { app ->
                    if (app.packageName in next) OeaGameBoostStore.restoreGame(this, app.packageName)
                    else if (app.packageName in selectedBefore) OeaGameBoostStore.removeGame(this, app.packageName)
                }
                val preserved = selectedBefore.filter { old -> choices.none { it.packageName == old } }.toSet()
                OeaGameBoostStore.setGames(this, next + preserved)
                dialog.dismiss()
                showGameBoost()
            }
        }
        dialog.show()
    }
    private fun ruleSection(box: LinearLayout, title: String, values: List<String>, type: String) {
        box.addView(TextView(this).apply {
            text = title + " (" + values.size + ")"
            textSize = 17f
            setTextColor(textColor())
            setPadding(0, 18, 0, 8)
        })
        values.forEach { value ->
            row(box, value, "Tap to remove") {
                val exact = OeaCallBlockRules.getExact(this).toMutableSet()
                val prefix = OeaCallBlockRules.getPrefix(this).toMutableSet()
                val suffix = OeaCallBlockRules.getSuffix(this).toMutableSet()
                when (type) {
                    "exact" -> exact.remove(value)
                    "prefix" -> prefix.remove(value)
                    "suffix" -> suffix.remove(value)
                }
                OeaCallBlockRules.setRules(this, exact, prefix, suffix)
                showCallBlocker()
            }
        }
        row(box, "Add " + title + " rule", "Enter a new rule") { addCallRule(type) }
    }

    private fun addCallRule(type: String) {
        val input = EditText(this).apply {
            setSingleLine(true)
            hint = when (type) { "prefix" -> "e.g. 0803"; "suffix" -> "e.g. 1234"; else -> "Full phone number" }
            inputType = android.text.InputType.TYPE_CLASS_PHONE
        }
        AlertDialog.Builder(this).setTitle("Add " + type + " rule").setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val value = input.text.toString().trim()
                if (value.isNotEmpty()) {
                    val exact = OeaCallBlockRules.getExact(this).toMutableSet()
                    val prefix = OeaCallBlockRules.getPrefix(this).toMutableSet()
                    val suffix = OeaCallBlockRules.getSuffix(this).toMutableSet()
                    when (type) { "prefix" -> prefix.add(value); "suffix" -> suffix.add(value); else -> exact.add(value) }
                    OeaCallBlockRules.setRules(this, exact, prefix, suffix)
                    OeaCallBlockRules.setEnabled(this, true)
                    showCallBlocker()
                }
            }.show()
    }

    private fun themeDialog() {
        val choices = arrayOf("System / Wallpaper", "Dark", "Light")
        val checked = when (dataStore.themeMode()) { "dark" -> 1; "light" -> 2; else -> 0 }
        AlertDialog.Builder(this).setTitle("OEA Theme")
            .setSingleChoiceItems(choices, checked) { dialog, which ->
                dataStore.setThemeMode(when (which) { 1 -> "dark"; 2 -> "light"; else -> "system" })
                dialog.dismiss()
                recreate()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun gridDialog() {
        val choices = arrayOf("3 columns", "4 columns", "5 columns")
        val checked = (dataStore.gridColumns() - 3).coerceIn(0, 2)
        AlertDialog.Builder(this).setTitle("Home grid").setSingleChoiceItems(choices, checked) { dialog, which ->
            dataStore.setGridColumns(which + 3)
            dialog.dismiss()
            showSettings()
        }.setNegativeButton("Cancel", null).show()
    }

    private fun pickWallpaper() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            },
            wallpaperRequestCode
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == wallpaperRequestCode) {
            if (resultCode == RESULT_OK && data?.data != null) {
                val uri = data.data!!
                runCatching {
                    val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    if (flags != 0) contentResolver.takePersistableUriPermission(uri, flags)
                    dataStore.setWallpaperUri(uri.toString())
                    val selectedUri = uri
                    Thread {
                        runCatching {
                            contentResolver.openInputStream(selectedUri)?.use { stream: InputStream ->
                                android.app.WallpaperManager.getInstance(this).setStream(stream)
                            }
                        }.onSuccess {
                            runOnUiThread {
                                Toast.makeText(this, "OEA wallpaper applied.", Toast.LENGTH_SHORT).show()
                                showSettings()
                            }
                        }.onFailure {
                            runOnUiThread {
                                Toast.makeText(this, "Wallpaper saved, but Android could not apply it.", Toast.LENGTH_LONG).show()
                                showSettings()
                            }
                        }
                    }.start()
                }.onFailure {
                    Toast.makeText(this, "Could not apply that wallpaper.", Toast.LENGTH_LONG).show()
                }
                showSettings()
            }
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun pickWidgetFromSettings() {
        widgets.pickWidget(this)
    }

    private fun openPhone() {
        runCatching { startActivity(Intent(this, Class.forName("com.oea.launcher.phone.OeaPhoneActivity")).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
            .onFailure { Toast.makeText(this, "OEA Phone could not be opened.", Toast.LENGTH_SHORT).show() }
    }

    private fun openHomeSettings() {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm?.isRoleAvailable(RoleManager.ROLE_HOME) == true && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                startActivity(rm.createRequestRoleIntent(RoleManager.ROLE_HOME))
                return
            }
        }
        startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
    }

    private fun openCallBlockerRole() = requestCallRole()

    private fun requestCallRole() {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm?.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) == true && !rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) {
                startActivity(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
                return
            }
        }
        Toast.makeText(this, "Android does not expose the call-screening role on this device.", Toast.LENGTH_LONG).show()
    }

    private fun openNotificationAccess() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    private fun openUsageAccess() {
        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }

    private fun openOverlaySettings() {
        runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName))) }
    }

    private fun openDndAccess() {
        runCatching {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }.onFailure {
            Toast.makeText(this, "Android did not expose the DND access page.", Toast.LENGTH_LONG).show()
        }
    }

    private fun dndAccessStatus(): String {
        val granted = getSystemService(android.app.NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true
        return if (granted) "Granted • OEA can control session DND" else "Not granted • tap to show OEA in Android DND access"
    }

    private fun ramOverlaySummary(): String {
        val prefs = OeaGameBoostStore.prefs(this)
        val visible = prefs.getBoolean("ram_handle_visible", true)
        if (!visible) return "Hidden • double-tap the screen to restore it"
        val size = when (prefs.getString("ram_handle_size", "medium")) {
            "small" -> "Small"
            "large" -> "Large"
            else -> "Medium"
        }
        val corner = when (prefs.getString("ram_handle_corner", "bottom_right")) {
            "top_left" -> "Top-left"
            "top_right" -> "Top-right"
            "bottom_left" -> "Bottom-left"
            else -> "Bottom-right"
        }
        return "$size • $corner • always available"
    }

    private fun ramColorSummary(): String {
        return when (OeaGameBoostStore.prefs(this).getString("ram_color_mode", "blue")) {
            "green" -> "Green pulse"
            "purple" -> "Purple pulse"
            "cyan" -> "Cyan pulse"
            "red" -> "Red pulse"
            "amber" -> "Amber pulse"
            "rgb" -> "RGB spectrum cycle"
            else -> "Blue pulse"
        }
    }

    private fun showRamColorSettings() {
        val prefs = OeaGameBoostStore.prefs(this)
        val values = arrayOf("blue", "green", "purple", "cyan", "red", "amber", "rgb")
        val labels = arrayOf("Blue pulse", "Green pulse", "Purple pulse", "Cyan pulse", "Red pulse", "Amber pulse", "RGB spectrum cycle")
        val current = prefs.getString("ram_color_mode", "blue") ?: "blue"
        val checked = values.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("OEA RAM color palette")
            .setMessage("Choose the accent family used by the outer ring, glow and animated OEA RAM text. RGB cycles through the spectrum.")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                prefs.edit().putString("ram_color_mode", values[which]).apply()
                dialog.dismiss()
                showGameBoost()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRamOverlaySettings() {
        val prefs = OeaGameBoostStore.prefs(this)
        val currentVisible = prefs.getBoolean("ram_handle_visible", true)
        val currentSize = prefs.getString("ram_handle_size", "medium") ?: "medium"
        val currentCorner = prefs.getString("ram_handle_corner", "bottom_right") ?: "bottom_right"

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 4)
        }
        val visible = CheckBox(this).apply {
            text = "Keep OEA RAM visible throughout the game"
            isChecked = currentVisible
            setTextColor(textColor())
        }
        container.addView(visible)

        val sizeLabel = TextView(this).apply {
            text = "Handle size"
            textSize = 14f
            setTextColor(mutedColor())
            setPadding(0, 12, 0, 4)
        }
        container.addView(sizeLabel)
        val sizes = arrayOf("Small", "Medium", "Large")
        val sizeValues = arrayOf("small", "medium", "large")
        val sizeGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        sizes.forEachIndexed { index, label ->
            val radio = RadioButton(this).apply {
                text = label
                setTextColor(textColor())
                id = 30_000 + index
            }
            radio.setOnClickListener { sizeGroup.check(radio.id) }
            sizeGroup.addView(radio, RadioGroup.LayoutParams(0, -2, 1f))
        }
        sizeGroup.check(30_000 + sizeValues.indexOf(currentSize).coerceAtLeast(0))
        container.addView(sizeGroup)

        val cornerLabel = TextView(this).apply {
            text = "Corner placement"
            textSize = 14f
            setTextColor(mutedColor())
            setPadding(0, 12, 0, 4)
        }
        container.addView(cornerLabel)
        val corners = arrayOf("Top-left", "Top-right", "Bottom-left", "Bottom-right")
        val cornerValues = arrayOf("top_left", "top_right", "bottom_left", "bottom_right")
        val cornerGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        corners.forEachIndexed { index, label ->
            val radio = RadioButton(this).apply {
                text = label
                setTextColor(textColor())
                id = 31_000 + index
            }
            radio.setOnClickListener { cornerGroup.check(radio.id) }
            cornerGroup.addView(radio)
        }
        cornerGroup.check(31_000 + cornerValues.indexOf(currentCorner).coerceAtLeast(0))
        container.addView(cornerGroup)

        AlertDialog.Builder(this)
            .setTitle("OEA RAM floating control")
            .setMessage("When enabled, the OEA RAM control stays visible above the selected game until you leave it. Put it in a quiet corner or resize it. When hidden, double-tap the screen to restore the control. This does not close the panel.")
            .setView(container)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit()
                    .putBoolean("ram_handle_visible", visible.isChecked)
                    .putString("ram_handle_size", sizeValues[(sizeGroup.checkedRadioButtonId - 30_000).coerceIn(0, sizeValues.lastIndex)])
                    .putString("ram_handle_corner", cornerValues[(cornerGroup.checkedRadioButtonId - 31_000).coerceIn(0, cornerValues.lastIndex)])
                    .putBoolean("ram_handle_dragged", false)
                    .apply()
                if (OeaGameBoostStore.enabled(this)) {
                    // Apply size/position/visibility edits without destroying the
                    // live overlay window or resetting foreground-game detection.
                    runCatching {
                        if (android.os.Build.VERSION.SDK_INT >= 26) {
                            startForegroundService(
                                Intent(this, OeaGameBoostService::class.java)
                                    .setAction(OeaGameBoostService.ACTION_REFRESH)
                            )
                        } else {
                            startService(
                                Intent(this, OeaGameBoostService::class.java)
                                    .setAction(OeaGameBoostService.ACTION_REFRESH)
                            )
                        }
                    }.onFailure { startBoostService() }
                }
                showGameBoost()
            }
            .show()
    }

    private fun startBoostService() {
        if (!isUsageAccessGranted()) {
            OeaGameBoostStore.setEnabled(this, false)
            Toast.makeText(this, "Usage access is required before Game Boost can monitor games.", Toast.LENGTH_LONG).show()
            openUsageAccess()
            return
        }
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(Intent(this, OeaGameBoostService::class.java))
            else startService(Intent(this, OeaGameBoostService::class.java))
        }.onFailure {
            OeaGameBoostStore.setEnabled(this, false)
            Toast.makeText(this, "Game Boost service could not start.", Toast.LENGTH_LONG).show()
        }
    }

    private fun freezerAuthorityExplanation(): String {
        val admin = getSystemService(DevicePolicyManager::class.java)
            ?.isAdminActive(ComponentName(this, OeaDeviceAdminReceiver::class.java)) == true
        return if (admin) {
            "Device Admin is active, but Android does not grant app-freezing authority to ordinary Device Admin."
        } else {
            "Device Admin is not activated. Activating it still will not grant authority to freeze other apps."
        }
    }

    private fun requestAdbBridgeSetup() {
        val command = OeaAppFreezer.adbBridgeSetupCommand(this)
        AlertDialog.Builder(this)
            .setTitle("Enable OEA App Freezer")
            .setMessage(
                "No factory reset, Device Owner conversion, Shizuku app, or server is required. " +
                "This OEA-owned bridge runs locally as Android's ADB shell user.\n\n" +
                "1. On a computer, install Android Platform Tools (ADB).\n" +
                "2. On this phone, enable Developer options and USB debugging, then connect USB and accept the computer's trust prompt.\n" +
                "3. Copy the command below and run it in the computer terminal.\n" +
                "4. Return here; OEA checks the bridge before enabling real freeze/restore.\n\n" +
                "The bridge runs until stopped or the phone reboots; after a reboot, run the command again. This does not wipe your phone."
            )
            .setPositiveButton("Copy ADB command") { _, _ ->
                getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(
                    ClipData.newPlainText("OEA ADB bridge setup", command)
                )
                Toast.makeText(this, "ADB command copied. Run it from a trusted computer connected to this phone.", Toast.LENGTH_LONG).show()
            }
            .setNeutralButton("Check connection") { _, _ ->
                val connected = OeaAppFreezer.adbBridgeStatus(this)
                AlertDialog.Builder(this)
                    .setMessage(if (connected) "OEA ADB bridge is connected and responding." else "Bridge not detected. Run the copied command from a trusted computer, then check again.")
                    .setPositiveButton("OK", null)
                    .show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun homeRoleStatus(): String {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm?.isRoleHeld(RoleManager.ROLE_HOME) == true) return "OEA is the current Home launcher"
        }
        return "OEA is not the current Home launcher"
    }

    private fun notificationStatus(): String {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.split(":")
            ?.any { it.startsWith(packageName + "/") } == true
        return if (enabled) "Enabled" else "Disabled • tap to grant Android access"
    }

    private fun isUsageAccessGranted(): Boolean {
        val ops = getSystemService(AppOpsManager::class.java)
        val mode = ops?.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun usageStatus(): String {
        return if (isUsageAccessGranted()) "Enabled" else "Disabled • tap to grant Android access"
    }

    private fun hiddenCount() = dataStore.hiddenApps().size

    private fun freezerStatus(): String {
        val dpm = getSystemService(DevicePolicyManager::class.java)
        return when (OeaAppFreezer.backend(this)) {
            OeaAppFreezer.Backend.DEVICE_OWNER -> "Device-owner authority active"
            OeaAppFreezer.Backend.ROOT -> "Root authority active"
            OeaAppFreezer.Backend.ADB_BRIDGE -> "OEA ADB bridge connected"
            OeaAppFreezer.Backend.DEVICE_ADMIN -> "Device Admin active; freezing needs Device Owner"
            OeaAppFreezer.Backend.NONE -> "No freezer authority"
        }
    }

    private fun blockerStatus(): String {
        val enabled = if (OeaCallBlockRules.enabled(this)) "blocking enabled" else "blocking disabled"
        val count = OeaCallBlockRules.getExact(this).size + OeaCallBlockRules.getPrefix(this).size + OeaCallRulesSuffix(this)
        return enabled + " • " + count + " rules"
    }

    private fun OeaCallRulesSuffix(context: Context): Int = OeaCallBlockRules.getSuffix(context).size

    private fun themeLabel() = when (dataStore.themeMode()) {
        "dark" -> "Dark"
        "light" -> "Light"
        else -> "System / Wallpaper"
    }

    private fun protectedLaunch(app: OeaAppInfo, onSuccess: () -> Unit) {
        val key = app.packageName + "/" + app.className
        if (!OeaAppLockStore.isLocked(this, key)) {
            onSuccess()
            return
        }
        val input = pinInput()
        AlertDialog.Builder(this)
            .setTitle("Unlock " + app.label)
            .setMessage("This app is protected by OEA App Lock.")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Unlock") { _, _ ->
                if (OeaAppLockStore.verifyPin(this, input.text.toString())) {
                    onSuccess()
                } else {
                    Toast.makeText(this, "Incorrect PIN.", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun pinInput() = EditText(this).apply {
        hint = "4-8 digit PIN"
        inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        setSingleLine(true)
    }

    private fun freezerRow(
        box: LinearLayout,
        app: OeaAppInfo,
        frozen: Boolean,
        selected: Boolean,
        onSelectionChanged: (Boolean) -> Unit,
        action: () -> Unit,
    ) {
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(11), dp(12), dp(11))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(surfaceColor())
                setStroke(dp(1), if (selected) Color.rgb(80, 150, 255) else
                    if (lightUi) Color.rgb(222, 226, 234) else Color.rgb(47, 55, 70))
            }
            isClickable = true
            isFocusable = true
            contentDescription = if (frozen) app.label + " frozen, tap Restore" else app.label + " not frozen, tap Freeze"
            setOnClickListener { action() }

            val selector = CheckBox(this@OeaSystemToolsActivity).apply {
                isChecked = selected
                buttonTintList = android.content.res.ColorStateList.valueOf(if (selected) Color.rgb(65, 145, 255) else mutedColor())
                contentDescription = "Select ${app.label} for bulk actions"
                setOnClickListener { onSelectionChanged(isChecked) }
            }
            addView(selector, LinearLayout.LayoutParams(dp(28), dp(42)).apply { rightMargin = dp(4) })

            val iconHolder = FrameLayout(this@OeaSystemToolsActivity).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(15).toFloat()
                    setColor(if (lightUi) Color.rgb(242, 244, 248) else Color.rgb(42, 49, 64))
                }
                val iconView = ImageView(this@OeaSystemToolsActivity).apply {
                    setImageDrawable(freezerIconCache.getOrPut(app.packageName) {
                        runCatching { packageManager.getApplicationIcon(app.packageName) }.getOrNull()
                    })
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = app.label + " icon"
                    setPadding(dp(5), dp(5), dp(5), dp(5))
                }
                addView(iconView, FrameLayout.LayoutParams(-1, -1))
            }
            addView(iconHolder, LinearLayout.LayoutParams(dp(46), dp(46)).apply { rightMargin = dp(11) })

            addView(LinearLayout(this@OeaSystemToolsActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@OeaSystemToolsActivity).apply {
                    text = app.label
                    textSize = 14f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(textColor())
                })
                addView(TextView(this@OeaSystemToolsActivity).apply {
                    text = app.packageName
                    textSize = 10f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                    setTextColor(mutedColor())
                    setPadding(0, dp(3), 0, 0)
                })
                addView(TextView(this@OeaSystemToolsActivity).apply {
                    text = if (frozen) "SUSPENDED BY ANDROID" else "NOT FROZEN"
                    textSize = 9f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(if (frozen) Color.rgb(52, 190, 143) else mutedColor())
                    setPadding(0, dp(4), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, -2, 1f))

            addView(TextView(this@OeaSystemToolsActivity).apply {
                text = if (frozen) "Restore" else "Freeze"
                textSize = 11f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(dp(11), dp(10), dp(11), dp(10))
                setTextColor(if (frozen) Color.rgb(60, 205, 157) else Color.rgb(130, 174, 255))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(13).toFloat()
                    setColor(if (frozen) Color.argb(32, 52, 190, 143) else Color.argb(30, 110, 155, 255))
                    setStroke(dp(1), if (frozen) Color.rgb(52, 150, 115) else Color.rgb(75, 115, 185))
                }
            }, LinearLayout.LayoutParams(-2, dp(38)).apply { leftMargin = dp(8) })
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun section(box: LinearLayout, title: String) {
        box.addView(TextView(this).apply {
            text = title
            textSize = 12f
            setTextColor(mutedColor())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(2, 18, 2, 8)
        }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun base(title: String, subtitle: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(20, 24, 20, 28)
        setBackgroundColor(backgroundColor())
        addView(TextView(this@OeaSystemToolsActivity).apply {
            text = title
            textSize = 27f
            setTextColor(textColor())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 6 })
        addView(TextView(this@OeaSystemToolsActivity).apply {
            text = subtitle
            textSize = 14f
            setTextColor(mutedColor())
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 18 })
    }

    private fun setRoot(box: LinearLayout) {
        val scroll = ScrollView(this)
        scroll.addView(box)
        setContentView(scroll)
    }

    private fun row(box: LinearLayout, title: String, subtitle: String, action: () -> Unit) {
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 13, 16, 13)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 20f
                setColor(surfaceColor())
            }
            isClickable = true
            setOnClickListener { action() }
            addView(TextView(this@OeaSystemToolsActivity).apply {
                text = title
                textSize = 16f
                setTextColor(textColor())
            })
            addView(TextView(this@OeaSystemToolsActivity).apply {
                text = subtitle
                textSize = 12f
                setTextColor(mutedColor())
                maxLines = 2
            })
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 7 })
    }

    private fun addDivider(box: LinearLayout) {
        box.addView(Space(this), LinearLayout.LayoutParams(1, 8))
    }
}
