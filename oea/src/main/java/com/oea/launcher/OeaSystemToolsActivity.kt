package com.oea.launcher

import android.app.Activity
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.*
import com.oea.launcher.applock.OeaAppFreezer
import com.oea.launcher.applock.OeaDeviceAdminReceiver
import com.oea.launcher.callblocker.OeaCallBlockRules
import com.oea.launcher.data.OeaDataStore
import com.oea.launcher.gameboost.OeaGameBoostService
import com.oea.launcher.gameboost.OeaGameBoostStore
import com.oea.launcher.model.OeaAppInfo
import com.oea.launcher.model.OeaAppModel
import com.oea.launcher.split.OeaSplitLauncher

class OeaSystemToolsActivity : Activity() {
    private val dataStore by lazy { OeaDataStore.get(this) }
    private val apps: List<OeaAppInfo> by lazy { OeaAppModel(this).also { it.load() }.apps }
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
        val key = intent.component?.className.orEmpty()
        when {
            key.endsWith("OeaSettings") -> showSettings()
            key.endsWith("OeaAppFreezer") -> showFreezer()
            key.endsWith("OeaCallBlocker") -> showCallBlocker()
            key.endsWith("OeaGameBoost") -> showGameBoost()
            key.endsWith("OeaMultitask") -> showSplitScreen()
            else -> showSettings()
        }
    }

    private fun showSettings() {
        val box = base("OEA Settings", "Launcher controls and OEA system tools")
        row(box, "Default launcher", "Choose OEA as Android Home") { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
        row(box, "Wallpaper", "Change the Android home wallpaper") { startActivity(Intent(Intent.ACTION_SET_WALLPAPER)) }
        row(box, "Themes", "Choose System / Wallpaper, Dark, or Light") { themeDialog() }
        row(box, "Hidden apps", "Review and restore apps hidden from the drawer") { hiddenAppsDialog() }
        row(box, "App Freezer", "Freeze, review, and unfreeze suspended apps") { showFreezer() }
        row(box, "App Lock", "Configure OEA launcher app protection") {
            Toast.makeText(this, "Use an app's long-press menu on OEA Home to lock or unlock it.", Toast.LENGTH_LONG).show()
        }
        row(box, "Call Blocker", "Exact numbers, prefixes, suffixes, contacts, and history") { showCallBlocker() }
        row(box, "Game Boost", "Choose games and control boost monitoring") { showGameBoost() }
        row(box, "Split Screen", "Choose two apps and launch them side by side") { showSplitScreen() }
        row(box, "Widgets", "Use ⋮ > Add widget on OEA Home") {
            Toast.makeText(this, "The widget picker belongs to OEA Home so widgets return to OEA.", Toast.LENGTH_LONG).show()
        }
        row(box, "Notification access", "Allow OEA to show notification badges") { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        row(box, "Usage access", "Required by Game Boost") { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        setRoot(box)
    }

    private fun showFreezer() {
        val box = base("App Freezer", "Freeze/unfreeze apps. Android requires OEA device-owner authority.")
        val dpm = getSystemService(DevicePolicyManager::class.java)
        val owner = dpm?.isDeviceOwnerApp(packageName) == true
        row(box, if (owner) "Authority active" else "Enable freezer authority",
            if (owner) "OEA can suspend packages." else "Android device-owner setup is required.") {
            if (!owner) requestDeviceAdmin()
        }
        addDivider(box)
        val frozen = OeaAppFreezer.frozenPackages(this)
        val candidates = linkedMapOf<String, String>()
        apps.filterNot { it.packageName == packageName }.forEach { candidates[it.packageName] = it.label }
        frozen.forEach { pkg ->
            if (!candidates.containsKey(pkg)) {
                runCatching { candidates[pkg] = packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }
            }
        }
        candidates.entries.sortedBy { it.value.lowercase() }.forEach { (pkg, label) ->
            val isFrozen = frozen.contains(pkg)
            row(box, label, if (isFrozen) "FROZEN • tap to unfreeze" else "Tap to freeze") {
                if (!owner) requestDeviceAdmin()
                else {
                    val result = OeaAppFreezer.setFrozen(this, pkg, !isFrozen)
                    Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
                    showFreezer()
                }
            }
        }
        setRoot(box)
    }

    private fun showCallBlocker() {
        val box = base("Call Blocker", "Android call-screening rules")
        val role = if (android.os.Build.VERSION.SDK_INT >= 29) {
            getSystemService(android.app.role.RoleManager::class.java)?.isRoleHeld(android.app.role.RoleManager.ROLE_CALL_SCREENING) == true
        } else false
        row(box, if (role) "Call-screening role active" else "Enable call-screening role",
            if (role) "OEA can screen matching incoming calls." else "Android must grant OEA the call-screening role.") { requestCallRole() }
        row(box, if (OeaCallBlockRules.enabled(this)) "Blocking enabled" else "Blocking disabled", "Tap to toggle the blocker") {
            OeaCallBlockRules.setEnabled(this, !OeaCallBlockRules.enabled(this)); showCallBlocker()
        }
        row(box, "Contacts", if (OeaCallBlockRules.allowContacts(this)) "Allowed automatically" else "Not exempt") {
            OeaCallBlockRules.setAllowContacts(this, !OeaCallBlockRules.allowContacts(this)); showCallBlocker()
        }
        row(box, "Starred contacts", if (OeaCallBlockRules.allowStarred(this)) "Allowed automatically" else "Not exempt") {
            OeaCallBlockRules.setAllowStarred(this, !OeaCallBlockRules.allowStarred(this)); showCallBlocker()
        }
        addDivider(box)
        ruleSection(box, "Exact numbers", OeaCallBlockRules.getExact(this).toList(), "exact")
        ruleSection(box, "Prefixes", OeaCallBlockRules.getPrefix(this).toList(), "prefix")
        ruleSection(box, "Suffixes", OeaCallBlockRules.getSuffix(this).toList(), "suffix")
        setRoot(box)
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
                when (type) { "exact" -> exact.remove(value); "prefix" -> prefix.remove(value); "suffix" -> suffix.remove(value) }
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

    private fun showGameBoost() {
        val box = base("Game Boost", "Choose games, then enable monitoring")
        row(box, if (OeaGameBoostStore.enabled(this)) "Game Boost enabled" else "Game Boost disabled", "Tap to toggle monitoring") {
            val enabled = !OeaGameBoostStore.enabled(this)
            OeaGameBoostStore.setEnabled(this, enabled)
            if (enabled) startBoostService() else stopService(Intent(this, OeaGameBoostService::class.java))
            showGameBoost()
        }
        row(box, "Choose games", OeaGameBoostStore.games(this).size.toString() + " selected") { chooseGames() }
        row(box, "Usage access", "Required to detect the foreground game") { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        row(box, "Overlay permission", "Required for the optional boost overlay") {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + packageName))) }
        }
        setRoot(box)
    }

    private fun chooseGames() {
        val choices = apps.filter { it.packageName != packageName }
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

    private fun showSplitScreen() {
        val box = base("Split Screen", "Select two apps. Android controls the final divider and orientation.")
        row(box, "Choose two apps", "Use the Android app list, then launch both adjacent") { chooseSplitApps() }
        setRoot(box)
    }

    private fun chooseSplitApps() {
        val choices = apps.filter { it.packageName != packageName }
        val checked = BooleanArray(choices.size)
        AlertDialog.Builder(this).setTitle("Select two apps")
            .setMultiChoiceItems(choices.map { it.label }.toTypedArray(), checked) { dialog, which, value ->
                if (value && checked.count { it } >= 2) {
                    (dialog as AlertDialog).listView.setItemChecked(which, false)
                    Toast.makeText(this, "Select only two apps.", Toast.LENGTH_SHORT).show()
                } else checked[which] = value
            }
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Launch") { _, _ ->
                val picked = choices.mapIndexedNotNull { i, app -> app.packageName.takeIf { checked[i] } }
                if (picked.size == 2 && !OeaSplitLauncher.launchPair(this, picked[0], picked[1]))
                    Toast.makeText(this, "Android could not start the pair in split screen.", Toast.LENGTH_LONG).show()
            }.show()
    }

    private fun hiddenAppsDialog() {
        val hidden = dataStore.hiddenApps()
        val choices = apps
        val checked = BooleanArray(choices.size) { hidden.contains(choices[it].packageName + "/" + choices[it].className) }
        AlertDialog.Builder(this).setTitle("Hidden apps")
            .setMultiChoiceItems(choices.map { it.label }.toTypedArray(), checked) { _, which, value ->
                val app = choices[which]; dataStore.setHidden(app.packageName, app.className, value)
            }
            .setPositiveButton("Done", null).show()
    }

    private fun themeDialog() {
        val choices = arrayOf("System / Wallpaper", "Dark", "Light")
        val checked = when (dataStore.themeMode()) { "dark" -> 1; "light" -> 2; else -> 0 }
        AlertDialog.Builder(this).setTitle("OEA Themes")
            .setSingleChoiceItems(choices, checked) { dialog, which ->
                dataStore.setThemeMode(when (which) { 1 -> "dark"; 2 -> "light"; else -> "system" })
                dialog.dismiss()
            }.show()
    }

    private fun requestDeviceAdmin() {
        val command = "adb shell dpm set-device-owner com.oea.launcher/com.oea.launcher.applock.OeaDeviceAdminReceiver"
        val clip = getSystemService(android.content.ClipboardManager::class.java)
        clip?.setPrimaryClip(android.content.ClipData.newPlainText("OEA device-owner command", command))
        AlertDialog.Builder(this)
            .setTitle("Freezer authority")
            .setMessage("OEA is not the device owner. A normal Device Admin prompt cannot grant the package-suspension authority. For a test/provisioned device, run this from ADB during setup:\n\n$command\n\nThe command was copied to your clipboard.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun requestCallRole() {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val rm = getSystemService(android.app.role.RoleManager::class.java)
            if (rm?.isRoleAvailable(android.app.role.RoleManager.ROLE_CALL_SCREENING) == true &&
                !rm.isRoleHeld(android.app.role.RoleManager.ROLE_CALL_SCREENING))
                startActivity(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_CALL_SCREENING))
        }
    }

    private fun startBoostService() {
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(Intent(this, OeaGameBoostService::class.java))
            else startService(Intent(this, OeaGameBoostService::class.java))
        }
    }

    private fun base(title: String, subtitle: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(24, 28, 24, 32)
        setBackgroundColor(backgroundColor())
        addView(TextView(this@OeaSystemToolsActivity).apply {
            text = title; textSize = 29f; setTextColor(textColor()); setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 6 })
        addView(TextView(this@OeaSystemToolsActivity).apply {
            text = subtitle; textSize = 14f; setTextColor(mutedColor())
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
            addView(TextView(this@OeaSystemToolsActivity).apply { text = title; textSize = 16f; setTextColor(textColor()) })
            addView(TextView(this@OeaSystemToolsActivity).apply {
                text = subtitle; textSize = 12f; setTextColor(mutedColor()); maxLines = 2
            })
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 9 })
    }

    private fun addDivider(box: LinearLayout) {
        box.addView(Space(this), LinearLayout.LayoutParams(1, 8))
    }
}
