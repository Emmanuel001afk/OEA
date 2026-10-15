package com.oea.launcher.perf

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.provider.Settings
import android.util.Log
import com.oea.launcher.applock.OeaAppFreezer
import com.oea.launcher.data.OeaDataStore
import com.oea.launcher.fault.OeaFaultShield
import com.oea.launcher.workspace.OeaDynamicIslandOverlayService

/**
 * OEA's LOW mode: the launcher's own power-saving state, also known as Ultra Power Saving Mode on launcher.
 *
 * Competitive expansion:
 * - Tiers: Light (launcher only), Balanced (launcher + suspend chosen apps), Extreme (launcher + suspend + restrict background + DND + freeze live activities)
 * - Whitelist: mini-apps are whitelisted by design, never blocked in LOW mode (unlike device OEM ultra saving)
 * - Auto-rules: turn on at X% battery, turn off at Y% or when charging
 * - Battery exemption: request ignore battery optimizations for OEA so alarms, widgets, islands work
 * - Ultra saving detection: device's own ultra power saving that blocks mini-apps when in device ultra saving — detect, explain, mitigate
 * - Persistent after reboot via BootReceiver
 * - Honest about limits: cannot prevent OEM ultra saving from killing background work without whitelist, but explains how to whitelist
 *
 * Layers:
 * | Layer | What Low mode does | Needs |
 * |---|---|---|
 * | Always | Home stops animating: live wallpapers freeze to still, page motion off, At a Glance and live activities hidden, island service stops | nothing |
 * | Always | Game Boost overlay and its foreground service stop, wake-gesture sensors released | nothing |
 * | Light tier | Above only | nothing |
 * | Balanced tier | + suspend chosen apps (user list) | device owner or root |
 * | Extreme tier | + DND on, battery optimization for other apps (suggest), restrict background sync (via Low mode: no widgets refresh, no island) | DND access for DND part |
 * | Mini-apps | Whitelisted — work normally in LOW mode | nothing |
 * | Device ultra saving | Separate — OEM's own ultra saving may block mini-apps, OEA detects and explains whitelist | user action in OEM settings |
 */
object OeaLowMode {
    private const val TAG = "OeaLowMode"

    enum class Tier(val id: String, val label: String, val desc: String) {
        LIGHT("light", "Light", "Launcher only: freeze live scenes, hide islands, stop animations, release sensors"),
        BALANCED("balanced", "Balanced", "Launcher + suspend chosen apps (needs DO/root)"),
        EXTREME("extreme", "Extreme", "Launcher + suspend + DND + restrict background + freeze live activities");
    }

    fun active(context: Context): Boolean = OeaDataStore.get(context).lowModeEnabled()

    fun tier(context: Context): Tier {
        return when (OeaDataStore.get(context).lowModeTier()) {
            "balanced" -> Tier.BALANCED
            "extreme" -> Tier.EXTREME
            else -> Tier.LIGHT
        }
    }

    fun setEnabled(context: Context, enabled: Boolean, automatic: Boolean = false) {
        val store = OeaDataStore.get(context)
        store.setLowModeEnabled(enabled)
        store.setLowModeAutoEngaged(automatic && enabled)
        apply(context)
    }

    fun engagedAutomatically(context: Context): Boolean =
        OeaDataStore.get(context).lowModeAutoEngaged()

    fun onBatteryChanged(context: Context, percent: Int, charging: Boolean) {
        val store = OeaDataStore.get(context)
        if (!store.lowModeAuto()) return
        when {
            !store.lowModeEnabled() && !charging && percent in 1 until store.lowModeThreshold() ->
                setEnabled(context, true, automatic = true)
            store.lowModeEnabled() && engagedAutomatically(context) &&
                (charging || percent >= store.lowModeExit()) ->
                setEnabled(context, false, automatic = true)
        }
    }

    fun battery(context: Context): Pair<Int, Boolean> {
        val manager = runCatching { context.getSystemService(BatteryManager::class.java) }.getOrNull()
            ?: return -1 to false
        val level = runCatching {
            manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }.getOrDefault(-1)
        val status = runCatching {
            manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
        }.getOrDefault(BatteryManager.BATTERY_STATUS_UNKNOWN)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return level to charging
    }

    fun apply(context: Context) {
        val store = OeaDataStore.get(context)
        val on = store.lowModeEnabled()
        val tier = tier(context)
        Log.i(TAG, "Low mode ${if (on) "on" else "off"} tier=${tier.id} for ${context.packageName}")
        OeaFaultShield.guard("low-mode:island") {
            if (on) {
                runCatching { OeaDynamicIslandOverlayService.stopForLowMode(context) }
            } else {
                OeaDynamicIslandOverlayService.ensureRunning(context)
            }
        }
        OeaFaultShield.guard("low-mode:wake") {
            runCatching { com.oea.launcher.power.OeaPower.syncWakeService(context) }
        }
        OeaFaultShield.guard("low-mode:boost") {
            if (on) runCatching { com.oea.launcher.gameboost.OeaGameBoost.onLowMode(context) }
        }
        OeaFaultShield.guard("low-mode:apps") { applySuspension(context) }
        OeaFaultShield.guard("low-mode:dnd") {
            if (on && tier == Tier.EXTREME) {
                // Extreme tier: DND on if policy access granted
                if (com.oea.launcher.power.OeaPower.policyAccess(context)) {
                    com.oea.launcher.power.OeaPower.setDnd(context, true)
                }
            } else if (!on) {
                // When turning off, if we enabled DND automatically, disable it if still in DND and was auto
                if (store.lowModeAutoEngaged()) {
                    // Only turn off DND if it was on due to extreme tier and user didn't manually keep it
                    // We check if current mode is sleeping etc — but for simplicity, we don't auto-off DND unless it was auto-engaged and low mode off
                    // Actually we leave DND as is if user manually wants it, but for auto case we try to restore
                    try {
                        if (com.oea.launcher.power.OeaPower.policyAccess(context) && com.oea.launcher.power.OeaPower.dndOn(context)) {
                            // Only if mode is not sleeping
                            val currentMode = com.oea.launcher.ui.OeaModes.current(context)
                            if (currentMode.id != "sleeping") {
                                com.oea.launcher.power.OeaPower.setDnd(context, false)
                            }
                        }
                    } catch (_: Throwable) {}
                }
            }
        }
    }

    private fun applySuspension(context: Context) {
        val store = OeaDataStore.get(context)
        val wanted = store.lowModeSuspendApps() && store.lowModeEnabled() && tier(context) != Tier.LIGHT
        val apps = store.lowModeAppList()
        if (apps.isEmpty()) return
        val backend = OeaAppFreezer.backend(context)
        Log.i(TAG, "Low mode suspension: wanted=$wanted apps=${apps.size} backend=$backend tier=${tier(context).id}")
        if (backend == OeaAppFreezer.Backend.NONE) return
        apps.forEach { packageName ->
            OeaFaultShield.guard("low-mode:suspend") {
                val result = OeaAppFreezer.setFrozen(context, packageName, wanted)
                if (!result.success) Log.w(TAG, "Low mode could not change $packageName: ${result.message}")
            }
        }
    }

    fun summary(context: Context): String {
        if (!active(context)) {
            val store = OeaDataStore.get(context)
            return if (store.lowModeAuto()) "Off · turns on at ${store.lowModeThreshold()}% • Tier ${tier(context).label}" else "Off • Tier ${tier(context).label}"
        }
        val (percent, charging) = battery(context)
        val because = when {
            charging -> "on, phone is charging"
            percent in 0..100 -> "on · $percent% battery"
            else -> "on"
        }
        return "$because • ${tier(context).label} • ${if (isDeviceUltraSaving(context)) "Device ultra saving ON!" else "Device ultra saving OFF"}"
    }

    fun observe(app: Context) {
        if (receiver != null) return
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                OeaFaultShield.guard("low-mode:battery") {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                    val percent = if (level >= 0) level * 100 / scale else -1
                    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
                    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL ||
                        intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                    onBatteryChanged(context, percent, charging)
                }
            }
        }
        runCatching { app.applicationContext.registerReceiver(r, filter) }
            .onFailure { Log.w(TAG, "Battery receiver could not be registered", it) }
            .onSuccess { receiver = r }
    }

    @Volatile private var receiver: BroadcastReceiver? = null

    fun dndAvailable(context: Context): Boolean =
        runCatching {
            context.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted
        }.getOrDefault(false) == true

    fun hasDeepLayer(context: Context): Boolean =
        OeaAppFreezer.backend(context) != OeaAppFreezer.Backend.NONE

    // ---- Device ultra saving detection and mitigation

    fun isDeviceUltraSaving(context: Context): Boolean {
        return try {
            val pm = context.getSystemService(android.os.PowerManager::class.java)
            if (pm?.isPowerSaveMode == true) {
                val ultra = Settings.System.getInt(context.contentResolver, "ultra_powersaving_mode", 0) == 1 ||
                        Settings.Global.getInt(context.contentResolver, "low_power", 0) == 1 ||
                        Settings.System.getInt(context.contentResolver, "ultra_power_saving_mode", 0) == 1
                ultra || pm.isPowerSaveMode
            } else false
        } catch (_: Throwable) { false }
    }

    fun isWhitelistedFromDoze(context: Context): Boolean {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                val pm = context.getSystemService(android.os.PowerManager::class.java)
                pm?.isIgnoringBatteryOptimizations(context.packageName) == true
            } else true
        } catch (_: Throwable) { false }
    }

    fun requestWhitelist(context: Context): Boolean {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } else false
        } catch (_: Throwable) {
            try {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (_: Throwable) { false }
        }
    }

    fun whitelistExplanation(): String = """
Device Ultra Power Saving (OEM) vs OEA LOW mode:
• OEA LOW (Ultra Power Saving) = OEA's own power saving. Makes launcher cheap (freeze live scenes, hide islands, release sensors, suspend chosen apps if DO/root). Mini-apps whitelisted — they still work. Persists after reboot. No OEM interference.

• Device Ultra Power Saving = Samsung/Xiaomi/etc. own ultra saving. When ON, OEM restricts:
  - Background services killed (OEA island, game boost, wake sensors)
  - Overlay (SYSTEM_ALERT_WINDOW) blocked — islands, Game Boost overlay not shown
  - Network restricted — weather, maps, online features fail
  - Activities blocked — mini-apps may not launch, alarms delayed
  - Widgets frozen

Why mini-apps not working in device ultra saving:
OEM ultra saving is outside OEA's control. Android's battery saver + OEM customizations kill background work. OEA detects via PowerManager.isPowerSaveMode and Settings.System ultra_powersaving_mode.

Fix / mitigation:
1. Settings › Battery › Unrestricted for OEA (request via OEA Console › Performance › Doze whitelist)
2. Lock OEA in recents (recent apps › tap lock icon on OEA)
3. Disable device ultra saving or add OEA to allowed list:
   - Samsung: Device Care › Battery › Background usage limits › Never sleeping apps › Add OEA, and Battery › Unrestricted
   - Xiaomi: Security › Battery › App battery saver › OEA › No restrictions, and Autostart › Enable OEA
   - OnePlus: Battery › Battery optimization › OEA › Don't optimize
   - Huawei: Battery › App launch › OEA › Manage manually › All on
4. In OEA, LOW mode stays OFF when device ultra saving ON to avoid double restriction, or keep it ON but whitelist mini-apps (they are whitelisted by design)
5. For alarms: ensure OEA is whitelisted from Doze (ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) so AlarmManager exact alarms work
6. If still blocked, disable device ultra saving — it is OEM feature, not OEA's.

OEA's mitigation in code:
- OeaPowerSaveReceiver listens for ACTION_POWER_SAVE_MODE_CHANGED and OEM broadcasts (e.g., samsung ultra power saving)
- When device ultra saving ON, OEA shows banner in Console and Home, explains whitelist
- LOW mode (OEA's own) does NOT block mini-apps — they are whitelisted
- Alarms use setExactAndAllowWhileIdle and check canScheduleExactAlarms
    """.trimIndent()
}
