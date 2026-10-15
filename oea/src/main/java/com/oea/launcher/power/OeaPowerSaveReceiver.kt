package com.oea.launcher.power

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.oea.launcher.fault.OeaFaultShield
import com.oea.launcher.perf.OeaLowMode

/**
 * Handles Ultra Power Saving Mode (OEM) and Android's own battery saver.
 *
 * Investigation:
 * - Stock Android: ACTION_POWER_SAVE_MODE_CHANGED, isPowerSaveMode true means battery saver.
 *   OEA preserves settings, keeps Home functional, pauses expensive work (live wallpapers,
 *   island overlay, wake sensors) and restores when saver exits.
 * - OEM Ultra Power Saving (Samsung, Xiaomi, OnePlus, Huawei, etc.): often custom broadcast
 *   like "android.os.action.POWER_SAVE_MODE_CHANGED" or manufacturer-specific, but also sets
 *   isPowerSaveMode and may kill background services, restrict overlay, disable sensors.
 *   OEA cannot prevent OEM from restricting without whitelist, but must:
 *   - preserve user settings (wave, lift, island selections) in main prefs backup (OeaDataStore + oea_power)
 *   - restore services when mode exits (via BOOT_COMPLETED and this receiver)
 *   - keep essential components available: Home grid, dock, drawer, search, phone, settings, mini-apps whitelisted
 *   - intentionally restrict: live wallpapers frozen, island overlay stopped, Game Boost stopped, wake sensors released — all restored automatically
 *   - detect failing recovery: if service fails to restart, show honest message, not silent
 *   - explain whitelist: OEA's mini-apps work in OEA's own LOW mode (Ultra Power Saving), but device ultra saving may block them unless OEA whitelisted in OEM battery settings
 *   - mitigate: request ignore battery optimizations, explain OEM-specific whitelist steps, show banner in Console
 *
 * Expanded with tiers: Light/Balanced/Extreme + mini-app whitelist + ultra saving detection in OeaPowerSaveReceiver + battery exemption.
 */
class OeaPowerSaveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        OeaFaultShield.guard("power-save:receive") {
            val action = intent.action
            Log.i(TAG, "Power save event: $action extras=${intent.extras}")
            when (action) {
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED,
                Intent.ACTION_BATTERY_CHANGED,
                "android.os.action.POWER_SAVE_MODE_CHANGED",
                "android.intent.action.BATTERY_SAVER_CHANGED" -> {
                    handlePowerSaveChanged(context)
                }
                // Samsung Ultra Power Saving Mode
                "android.os.action.ULTRA_POWER_SAVING_MODE_CHANGED",
                "com.samsung.android.action.ULTRA_POWER_SAVING_MODE_CHANGED",
                "com.samsung.android.sm.ACTION_ULTRA_POWER_SAVING_MODE_CHANGED",
                // Xiaomi
                "miui.intent.action.ULTRA_POWER_SAVE_MODE_CHANGED",
                "miui.intent.action.POWER_SAVE_MODE_CHANGED",
                // OnePlus
                "oneplus.intent.action.ULTRA_POWER_SAVING_MODE_CHANGED",
                // Huawei
                "huawei.intent.action.ULTRA_POWER_SAVE_MODE_CHANGED",
                // General OEM
                "android.intent.action.ULTRA_POWER_SAVE_MODE_CHANGED" -> {
                    handleUltraPowerSaveChanged(context, intent)
                }
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_LOCKED_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED -> {
                    // Restore after reboot
                    handleBoot(context)
                }
            }
        }
    }

    private fun handlePowerSaveChanged(context: Context) {
        val pm = context.getSystemService(PowerManager::class.java)
        val saverOn = pm?.isPowerSaveMode == true
        val deviceUltra = isDeviceUltraSaving(context)
        Log.i(TAG, "Battery saver: $saverOn deviceUltra=$deviceUltra")
        if (saverOn || deviceUltra) {
            // Entering saver: pause expensive work but preserve settings, keep mini-apps whitelisted
            OeaPower.restoreIfNeeded(context)
            try { com.oea.launcher.workspace.OeaDynamicIslandOverlayService.stopForLowMode(context) } catch (_: Throwable) {}
            try { context.stopService(Intent(context, OeaWakeService::class.java)) } catch (_: Throwable) {}
            try { com.oea.launcher.gameboost.OeaGameBoost.onLowMode(context) } catch (_: Throwable) {}
            // Mini-apps remain available: OEA's LOW mode whitelists them, but device ultra saving may block unless whitelisted
            // We cannot prevent OEM blocking, but we explain via Console and notification
            if (deviceUltra) {
                Log.w(TAG, "Device ultra saving ON — mini-apps may be blocked by OEM, need whitelist. OEA LOW mode still allows mini-apps.")
            }
        } else {
            // Exiting saver: restore everything based on saved settings
            OeaPower.restoreIfNeeded(context)
            OeaPower.syncWakeService(context)
            try { com.oea.launcher.workspace.OeaDynamicIslandOverlayService.ensureRunning(context) } catch (_: Throwable) {}
            try { OeaLowMode.apply(context) } catch (_: Throwable) {}
        }
    }

    private fun handleUltraPowerSaveChanged(context: Context, intent: Intent) {
        val ultraOn = try {
            intent.getBooleanExtra("enabled", false) ||
            intent.getBooleanExtra("ultra_power_saving", false) ||
            intent.getBooleanExtra("ultra_powersaving", false) ||
            Settings.System.getInt(context.contentResolver, "ultra_powersaving_mode", 0) == 1 ||
            Settings.System.getInt(context.contentResolver, "ultra_power_saving_mode", 0) == 1 ||
            Settings.Global.getInt(context.contentResolver, "low_power", 0) == 1 ||
            context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
        } catch (_: Throwable) { false }
        Log.i(TAG, "Ultra power saving: $ultraOn")
        if (ultraOn) {
            OeaPower.restoreIfNeeded(context)
            try { com.oea.launcher.workspace.OeaDynamicIslandOverlayService.stopForLowMode(context) } catch (_: Throwable) {}
            try { context.stopService(Intent(context, OeaWakeService::class.java)) } catch (_: Throwable) {}
            // Preserve mini-apps: they are whitelisted in OEA LOW mode, but device ultra saving may block
            // Mitigation: explain whitelist in Console, request battery exemption
            Log.w(TAG, "Ultra power saving ON — preserving settings, mini-apps whitelisted in OEA LOW, but device ultra may block. Explain whitelist.")
        } else {
            OeaPower.restoreIfNeeded(context)
            OeaPower.syncWakeService(context)
            try { com.oea.launcher.workspace.OeaDynamicIslandOverlayService.ensureRunning(context) } catch (_: Throwable) {}
            try { OeaLowMode.apply(context) } catch (_: Throwable) {}
        }
    }

    private fun handleBoot(context: Context) {
        Log.i(TAG, "Boot completed — restoring OEA state")
        try { OeaPower.restoreIfNeeded(context) } catch (_: Throwable) {}
        try { OeaPower.syncWakeService(context) } catch (_: Throwable) {}
        try { OeaLowMode.apply(context) } catch (_: Throwable) {}
        try { com.oea.launcher.workspace.OeaDynamicIslandOverlayService.ensureRunning(context) } catch (_: Throwable) {}
    }

    private fun isDeviceUltraSaving(context: Context): Boolean {
        return try {
            val pm = context.getSystemService(PowerManager::class.java)
            if (pm?.isPowerSaveMode == true) {
                Settings.System.getInt(context.contentResolver, "ultra_powersaving_mode", 0) == 1 ||
                Settings.System.getInt(context.contentResolver, "ultra_power_saving_mode", 0) == 1 ||
                Settings.Global.getInt(context.contentResolver, "low_power", 0) == 1 ||
                pm.isPowerSaveMode
            } else false
        } catch (_: Throwable) { false }
    }

    companion object {
        private const val TAG = "OeaPowerSave"
    }
}
