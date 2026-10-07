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
            OeaAppFreezer.Backend.DEVICE_OWNER -> "Android package suspension is available."
            OeaAppFreezer.Backend.ROOT -> "OEA can use root package suspension."
            OeaAppFreezer.Backend.NONE -> "No real suspension authority is available on this device."
        }
        row(box, authorityTitle, authoritySubtitle) {
            if (backend == OeaAppFreezer.Backend.NONE) requestDeviceOwner()
        }
        addDivider(box)
        val frozen = OeaAppFreezer.frozenPackages(this)
        apps.filterNot { it.packageName == packageName }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
            .forEach { app ->
                val isFrozen = frozen.contains(app.packageName)
                row(box, app.label, if (isFrozen) "FROZEN • tap to restore" else "Tap to freeze") {
                    if (!owner) requestDeviceOwner()
                    else {
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
        row(box, if (OeaCallBlockRules.enabled(this)) "Blocking enabled" else "Blocking disabled", "Tap to toggle") {
            OeaCallBlockRules.setEnabled(this, !OeaCallBlockRules.enabled(this)); showCallBlocker()
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
        val box = base("Game Boost", "OEA monitors selected games and provides a small in-game control pill.")
        row(box, if (OeaGameBoostStore.enabled(this)) "Game Boost enabled" else "Game Boost disabled", "Tap to toggle monitoring") {
            val enabled = !OeaGameBoostStore.enabled(this)
            OeaGameBoostStore.setEnabled(this, enabled)
            if (enabled) startBoostService() else stopService(Intent(this, OeaGameBoostService::class.java))
            showGameBoost()
        }
        row(box, "Selected games", OeaGameBoostStore.games(this).size.toString() + " selected") { chooseGames() }
        row(box, "Usage access", usageStatus()) { openUsageAccess() }
        row(box, "Overlay permission", if (Settings.canDrawOverlays(this)) "Granted" else "Required for the overlay") { openOverlaySettings() }
        setRoot(box)
    }

    private fun showMultitask() {
        val choices = apps.filter { it.packageName != packageName }.distinctBy { it.packageName }
        AlertDialog.Builder(this)
            .setTitle("OEA Multitask")
            .setMessage("Choose an app. OEA asks Android for a floating/freeform task with a sensible starting size. Android/OEM support determines whether it can actually float.")
            .setItems(choices.map { it.label }.toTypedArray()) { _, which ->
                if (!OeaMultitaskLauncher.launchFloating(this, choices[which]))
                    Toast.makeText(this, "Android could not open that app as a floating task on this device.", Toast.LENGTH_LONG).show()
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
                    val result = OeaSplitLauncher.launchPair(this, picked[0], picked[1])
                    if (!result.success) Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                }
            }.show()
    }

    private fun openAppLockSettings() {
        if (!OeaAppLockStore.hasPin(this)) {
            val input = pinInput()
            AlertDialog.Builder(this).setTitle("Set OEA App Lock PIN").setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save") { _, _ ->
                    val pin = input.text.toString()
                    if (pin.length in 4..8) {
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
        AlertDialog.Builder(this).setTitle("Change OEA App Lock PIN").setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val pin = input.text.toString()
                if (pin.length in 4..8) OeaAppLockStore.setPin(this, pin)
                else Toast.makeText(this, "PIN must be 4-8 digits.", Toast.LENGTH_SHORT).show()
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
        val choices = apps.filterNot { it.packageName == packageName }.distinctBy { it.packageName }
        val selected = OeaGameBoostStore.games(this)
        val checked = BooleanArray(choices.size) { selected.contains(choices[it].packageName) }
        AlertDialog.Builder(this).setTitle("Select games")
            .setMultiChoiceItems(choices.map { it.label }.toTypedArray(), checked) { _, which, value -> checked[which] = value }
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                OeaGameBoostStore.setGames(this, choices.mapIndexedNotNull { i, app -> app.packageName.takeIf { checked[i] } }.toSet())
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
                    contentResolver.openInputStream(uri)?.use { stream: InputStream ->
                        android.app.WallpaperManager.getInstance(this).setStream(stream)
                    }
                    Toast.makeText(this, "OEA wallpaper applied.", Toast.LENGTH_SHORT).show()
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
        runCatching { startActivity(Intent(this, Class.forName("com.oea.launcher.phone.OeaPhoneActivity"))) }
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
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(Intent(this, OeaGameBoostService::class.java))
            else startService(Intent(this, OeaGameBoostService::class.java))
        }
    }

    private fun requestDeviceOwner() {
        val command = "adb shell dpm set-device-owner com.oea.launcher/com.oea.launcher.applock.OeaDeviceAdminReceiver"
        getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(
            ClipData.newPlainText("OEA device-owner command", command)
        )
        AlertDialog.Builder(this).setTitle("Freezer authority")
            .setMessage("OEA is not the device owner. Android does not grant true package suspension through the normal Device Admin screen. Provision a test device during setup, then run:\n\n$command\n\nThe command was copied to the clipboard.")
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

    private fun usageStatus(): String {
        val ops = getSystemService(AppOpsManager::class.java)
        val mode = ops?.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
        return if (mode == AppOpsManager.MODE_ALLOWED) "Enabled" else "Disabled • tap to grant Android access"
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

    private fun pinInput() = EditText(this).apply {
        hint = "4-8 digit PIN"
        inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        setSingleLine(true)
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
        setPadding(24, 28, 24, 32)
        setBackgroundColor(backgroundColor())
        addView(TextView(this@OeaSystemToolsActivity).apply {
            text = title
            textSize = 29f
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
            setPadding(18, 14, 18, 14)
            setBackgroundColor(surfaceColor())
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
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 9 })
    }

    private fun addDivider(box: LinearLayout) {
        box.addView(Space(this), LinearLayout.LayoutParams(1, 8))
    }
}
