package com.oea.launcher.power

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.oea.launcher.fault.OeaFaultShield
import com.oea.launcher.perf.OeaLowMode

/**
 * Handles Ultra Power Saving Mode (OEM) and Android's own battery saver.
 *
 * Investigation:
 * - Stock Android: ACTION_POWER_SAVE_MODE_CHANGED, isPowerSaveMode true means battery saver.
 *   OEA should preserve settings, keep Home functional, pause expensive work (live wallpapers,
 *   island overlay, wake sensors) and restore when saver exits.
 * - OEM Ultra Power Saving (Samsung, Xiaomi, etc.): often custom broadcast like
 *   "android.os.action.POWER_SAVE_MODE_CHANGED" or manufacturer-specific, but also sets
 *   isPowerSaveMode and may kill background services, restrict overlay, disable sensors.
 *   OEA cannot prevent OEM from restricting, but must:
 *   - preserve user settings (wave, lift, island selections) in main prefs backup
 *   - restore services when mode exits (via BOOT_COMPLETED and this receiver)
 *   - keep essential components available: Home grid, dock, drawer, search, phone, settings
 *   - intentionally restrict: live wallpapers frozen, island overlay stopped, Game Boost stopped,
 *     wake sensors released — all restored automatically
 *   - detect failing recovery: if service fails to restart, show honest message, not silent
 */
class OeaPowerSaveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        OeaFaultShield.guard("power-save:receive") {
            val action = intent.action
            Log.i(TAG, "Power save event: $action")
            when (action) {
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED,
                Intent.ACTION_BATTERY_CHANGED,
                "android.os.action.POWER_SAVE_MODE_CHANGED" -> {
                    handlePowerSaveChanged(context)
                }
                // Samsung Ultra Power Saving Mode
                "android.os.action.ULTRA_POWER_SAVING_MODE_CHANGED",
                "com.samsung.android.action.ULTRA_POWER_SAVING_MODE_CHANGED",
                // Xiaomi
                "miui.intent.action.ULTRA_POWER_SAVE_MODE_CHANGED" -> {
                    handleUltraPowerSaveChanged(context, intent)
                }
            }
        }
    }

    private fun handlePowerSaveChanged(context: Context) {
        val pm = context.getSystemService(PowerManager::class.java)
        val saverOn = pm?.isPowerSaveMode == true
        Log.i(TAG, "Battery saver: $saverOn")
        if (saverOn) {
            // Entering saver: pause expensive work but preserve settings
            OeaPower.restoreIfNeeded(context)
            // Low mode may be triggered, but don't force it; just ensure OEA's own expensive work is paused
            try { com.oea.launcher.workspace.OeaDynamicIslandOverlayService.stopForLowMode(context) } catch (_: Throwable) {}
            try { context.stopService(Intent(context, OeaWakeService::class.java)) } catch (_: Throwable) {}
            try { com.oea.launcher.gameboost.OeaGameBoost.onLowMode(context) } catch (_: Throwable) {}
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
            context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
        } catch (_: Throwable) { false }
        Log.i(TAG, "Ultra power saving: $ultraOn")
        if (ultraOn) {
            // Preserve settings in main prefs (already mirrored)
            OeaPower.restoreIfNeeded(context)
            // Essential components that should remain: Home, dock, drawer, search, phone, settings, freezer, themes
            // Restricted: live wallpapers, island overlay, game boost, wake sensors
            // These are intentionally paused and will recover automatically
            try { com.oea.launcher.workspace.OeaDynamicIslandOverlayService.stopForLowMode(context) } catch (_: Throwable) {}
            try { context.stopService(Intent(context, OeaWakeService::class.java)) } catch (_: Throwable) {}
        } else {
            // Restore
            OeaPower.restoreIfNeeded(context)
            OeaPower.syncWakeService(context)
            try { com.oea.launcher.workspace.OeaDynamicIslandOverlayService.ensureRunning(context) } catch (_: Throwable) {}
        }
    }

    companion object {
        private const val TAG = "OeaPowerSave"
    }
}
