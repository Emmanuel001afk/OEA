package com.oea.launcher

import android.app.Activity
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.app.AppOpsManager
import android.app.role.RoleManager
import android.appwidget.AppWidgetManager
import android.content.ClipData
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
    override fun onBackPressed() {
        if (isTaskRoot) {
            super.onBackPressed()
        } else {
            finish()
        }
    }
    companion object { const val EXTRA_SCREEN = "oea_screen" }
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
        val box = base("App Freezer", "OEA uses Android package suspension when device-owner authority is available, with root as a fallback.")
        OeaAppFreezer.syncActualState(this)
        val backend = OeaAppFreezer.backend(this)
        val authorityTitle = when (backend) {
            OeaAppFreezer.Backend.DEVICE_OWNER -> "Device-owner authority active"
            OeaAppFreezer.Backend.ROOT -> "Root authority active"
            OeaAppFreezer.Backend.NONE -> "Freezer authority required"
        }
        val authoritySubtitle = when (backend) {
            OeaAppFreezer.Backend.DEVICE_OWNER -> "Android package suspension is available. Tap any app below to freeze or restore it."
            OeaAppFreezer.Backend.ROOT -> "OEA can use root package suspension. Tap any app below to freeze or restore it."
            OeaAppFreezer.Backend.NONE -> "Not approved. OEA cannot freeze apps until Android grants device-owner/root authority."
        }
        row(box, authorityTitle, authoritySubtitle) {
            if (backend == OeaAppFreezer.Backend.NONE) {
                requestDeviceOwner()
            }
        }
        addDivider(box)
        val frozen = OeaAppFreezer.frozenPackages(this)
        apps.filterNot { it.packageName == packageName }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
            .forEach { app ->
                val isFrozen = frozen.contains(app.packageName)
                freezerRow(box, app, isFrozen) {
                    if (backend == OeaAppFreezer.Backend.NONE) {
                        Toast.makeText(this, "No freezer authority. Use the Authority row above to provision device-owner/root access.", Toast.LENGTH_LONG).show()
                    } else {
                        val result = OeaAppFreezer.setFrozen(this, app.packageName, !isFrozen)
                        Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
                        showFreezer()
                    }
                }
            }
        setRoot(box)
    }

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
        row(box, "Selected games", games.size.toString() + " selected") { chooseGames() }
        row(box, "Usage access", usageStatus()) { openUsageAccess() }
        row(box, "Overlay permission", if (overlayGranted) "Granted" else "Required for the in-game control pill") { openOverlaySettings() }
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

    private fun chooseGames() {
        OeaGameBoostStore.syncDetectedGames(this)
        val choices = apps.filterNot { it.packageName == packageName }.distinctBy { it.packageName }
        val selected = OeaGameBoostStore.games(this)
        val checked = BooleanArray(choices.size) { selected.contains(choices[it].packageName) }
        AlertDialog.Builder(this).setTitle("Game Boost games")
            .setMessage("OEA automatically detects Android-declared games. You can also add or remove any installed app.")
            .setMultiChoiceItems(choices.map { it.label }.toTypedArray(), checked) { _, which, value -> checked[which] = value }
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val next = choices.mapIndexedNotNull { i, app -> app.packageName.takeIf { checked[i] } }.toSet()
                choices.forEach { app ->
                    if (app.packageName in next) OeaGameBoostStore.restoreGame(this, app.packageName)
                    else if (app.packageName in selected) OeaGameBoostStore.removeGame(this, app.packageName)
                }
                OeaGameBoostStore.setGames(this, next)
                showGameBoost()
            }.show()
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

    private fun requestDeviceOwner() {
        AlertDialog.Builder(this).setTitle("Freezer authority not approved")
            .setMessage("Android has not approved device-owner or root authority for OEA. True package freezing is unavailable until that authority is provisioned. OEA will not repeatedly request an unavailable permission.")
            .setPositiveButton("OK", null).show()
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

    private fun freezerRow(box: LinearLayout, app: OeaAppInfo, frozen: Boolean, action: () -> Unit) {
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(14, 10, 14, 10)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 20f
                setColor(surfaceColor())
            }
            isClickable = true
            isFocusable = true
            contentDescription = if (frozen) app.label + " frozen, tap to restore" else app.label + " not frozen, tap to freeze"
            setOnClickListener { action() }

            val iconView = ImageView(this@OeaSystemToolsActivity).apply {
                setImageDrawable(runCatching { packageManager.getApplicationIcon(app.packageName) }.getOrNull())
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                contentDescription = app.label + " icon"
            }
            addView(iconView, LinearLayout.LayoutParams(dp(46), dp(46)).apply { rightMargin = dp(12) })

            addView(LinearLayout(this@OeaSystemToolsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@OeaSystemToolsActivity).apply {
                    text = app.label
                    textSize = 16f
                    setTextColor(textColor())
                })
                addView(TextView(this@OeaSystemToolsActivity).apply {
                    text = if (frozen) "FROZEN • tap to restore" else "Tap to freeze"
                    textSize = 12f
                    setTextColor(if (frozen) textColor() else mutedColor())
                })
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
    }

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
