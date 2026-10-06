package com.oea.launcher

import android.app.Activity
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.*
import com.oea.launcher.applock.OeaDeviceAdminReceiver
import com.oea.launcher.applock.OeaAppFreezer
import com.oea.launcher.callblocker.OeaCallBlockRules
import com.oea.launcher.gameboost.OeaGameBoostService
import com.oea.launcher.gameboost.OeaGameBoostStore
import com.oea.launcher.split.OeaSplitLauncher

class OeaSystemToolsActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val key = intent.component?.className.orEmpty()
        val title = when {
            key.endsWith("OeaSettings") -> "OEA Settings"
            key.endsWith("OeaAppFreezer") -> "App Freezer"
            key.endsWith("OeaCallBlocker") -> "Call Blocker"
            key.endsWith("OeaGameBoost") -> "Game Boost"
            key.endsWith("OeaMultitask") -> "Multitask & Split Screen"
            else -> "OEA Systems"
        }
        if (key.endsWith("OeaSettings")) showSettings(title) else showTool(title, key)
    }

    private fun showSettings(title: String) {
        val box = base(title)
        add(box, "Launcher settings") { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
        add(box, "Wallpaper") { startActivity(Intent(Intent.ACTION_SET_WALLPAPER)) }
        add(box, "Notification access") { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        add(box, "Usage access") { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        add(box, "OEA Systems") { showTool("OEA Systems", "") }
        setContentView(box)
    }

    private fun showTool(title: String, key: String) {
        val box = base(title)
        when {
            key.endsWith("OeaAppFreezer") -> {
                add(box, "Freeze / unfreeze apps") {
                    AlertDialog.Builder(this)
                        .setTitle("App Freezer")
                        .setMessage("OEA needs device-owner authority for real package suspension.")
                        .setPositiveButton("Authority") { _, _ ->
                            startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).putExtra(
                                DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                                ComponentName(this, OeaDeviceAdminReceiver::class.java)
                            ))
                        }.setNegativeButton("Close", null).show()
                }
            }
            key.endsWith("OeaCallBlocker") -> {
                add(box, "Enable / disable call blocking") {
                    OeaCallBlockRules.setEnabled(this, !OeaCallBlockRules.enabled(this))
                    Toast.makeText(this, if (OeaCallBlockRules.enabled(this)) "Call blocking enabled" else "Call blocking disabled", Toast.LENGTH_SHORT).show()
                }
                add(box, "Call screening role") {
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        getSystemService(android.app.role.RoleManager::class.java)?.let { rm ->
                            if (rm.isRoleAvailable(android.app.role.RoleManager.ROLE_CALL_SCREENING) &&
                                !rm.isRoleHeld(android.app.role.RoleManager.ROLE_CALL_SCREENING)) {
                                startActivity(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_CALL_SCREENING))
                            }
                        }
                    }
                }
            }
            key.endsWith("OeaGameBoost") -> {
                add(box, "Enable / disable Game Boost") {
                    val enabled = !OeaGameBoostStore.enabled(this)
                    OeaGameBoostStore.setEnabled(this, enabled)
                    if (enabled) {
                        runCatching { startForegroundService(Intent(this, OeaGameBoostService::class.java)) }
                    } else stopService(Intent(this, OeaGameBoostService::class.java))
                    Toast.makeText(this, if (enabled) "Game Boost enabled" else "Game Boost disabled", Toast.LENGTH_SHORT).show()
                }
                add(box, "Usage / overlay permissions") {
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }
            }
            key.endsWith("OeaMultitask") -> {
                add(box, "Launch split pair") { splitDialog() }
            }
            else -> {
                add(box, "App Freezer") { startAlias("OeaAppFreezer") }
                add(box, "Call Blocker") { startAlias("OeaCallBlocker") }
                add(box, "Game Boost") { startAlias("OeaGameBoost") }
                add(box, "Multitask & Split Screen") { startAlias("OeaMultitask") }
            }
        }
        setContentView(box)
    }

    private fun splitDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 0, 16, 0) }
        val first = EditText(this).apply { hint = "First package name" }
        val second = EditText(this).apply { hint = "Second package name" }
        box.addView(first); box.addView(second)
        AlertDialog.Builder(this).setTitle("Split pair").setView(box)
            .setPositiveButton("Launch") { _, _ ->
                if (!OeaSplitLauncher.launchPair(this, first.text.toString().trim(), second.text.toString().trim()))
                    Toast.makeText(this, "Could not launch both apps", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun startAlias(name: String) {
        runCatching {
            startActivity(Intent().setComponent(ComponentName(this, "com.oea.launcher.$name")))
        }
    }

    private fun base(title: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(28, 36, 28, 28)
        setBackgroundColor(android.graphics.Color.rgb(12, 15, 21))
        addView(TextView(this@OeaSystemToolsActivity).apply {
            text = title; textSize = 28f; setTextColor(android.graphics.Color.WHITE)
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 24 })
    }

    private fun add(box: LinearLayout, label: String, action: () -> Unit) {
        box.addView(Button(this).apply {
            text = label; setOnClickListener { action() }
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, 54).apply { bottomMargin = 12 })
    }
}
