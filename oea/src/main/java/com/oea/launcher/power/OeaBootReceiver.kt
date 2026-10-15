package com.oea.launcher.power

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.oea.launcher.fault.OeaFaultShield
import com.oea.launcher.perf.OeaLowMode

/**
 * Restores OEA state after reboot or after Ultra Power Saving Mode exit.
 *
 * Manufacturers' Ultra Power Saving Mode often kills background services and
 * may clear secondary SharedPreferences. This receiver:
 * - restores wave/lift settings from backup
 * - re-syncs wake service
 * - re-applies Low mode state
 * - re-syncs Dynamic Island overlay if permission still granted
 */
class OeaBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        OeaFaultShield.guard("boot:restore") {
            val action = intent.action
            Log.i(TAG, "Boot/restore received: $action")
            // Restore backup prefs if OEM wiped oea_power
            OeaPower.restoreIfNeeded(context)
            // Restore wake service based on current (possibly restored) settings
            OeaPower.syncWakeService(context)
            // Restore Low mode monitoring
            try { OeaLowMode.observe(context.applicationContext) } catch (_: Throwable) {}
            try { if (OeaLowMode.active(context)) OeaLowMode.apply(context) } catch (_: Throwable) {}
            // Restore island overlay if still allowed
            try { com.oea.launcher.workspace.OeaDynamicIslandOverlayService.ensureRunning(context) } catch (_: Throwable) {}
        }
    }

    companion object {
        private const val TAG = "OeaBoot"
    }
}
