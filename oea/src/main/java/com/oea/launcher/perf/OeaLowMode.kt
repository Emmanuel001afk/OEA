package com.oea.launcher.perf

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import com.oea.launcher.applock.OeaAppFreezer
import com.oea.launcher.data.OeaDataStore
import com.oea.launcher.fault.OeaFaultShield
import com.oea.launcher.workspace.OeaDynamicIslandOverlayService

/**
 * OEA's LOW mode: the launcher's own power-saving state.
 *
 * It is deliberately *not* a claim to be Android's battery saver. Android
 * decides what other apps may do; what OEA can honestly do is make **itself**
 * cheap, and use the roles it has been given:
 *
 * | Layer | What Low mode does | Needs |
 * |---|---|---|
 * | Always | Home stops animating: live wallpapers freeze to a still frame, page motion is off, At a Glance and the live activities are hidden, the island service stops | nothing |
 * | Always | Game Boost's overlay and its foreground service stop, and the wake-gesture sensors are released | nothing |
 * | When device owner | The apps the user listed are suspended, so they really cannot run in the background | device owner |
 * | When root | The same suspension, through OEA's own shell channel | root |
 *
 * It can be switched on by hand or by itself when the battery reaches the
 * user's threshold, and it lifts when the battery is back above the exit
 * threshold or the phone is charging. The state is written to preferences, so
 * Home comes back in Low mode after a restart instead of silently forgetting.
 */
object OeaLowMode {
    private const val TAG = "OeaLowMode"

    /** True when Low mode is on and Home should behave accordingly. */
    fun active(context: Context): Boolean = OeaDataStore.get(context).lowModeEnabled()

    /** Switches Low mode on or off, remembering whether the user asked for it. */
    fun setEnabled(context: Context, enabled: Boolean, automatic: Boolean = false) {
        val store = OeaDataStore.get(context)
        store.setLowModeEnabled(enabled)
        store.setLowModeAutoEngaged(automatic && enabled)
        apply(context)
    }

    /** True when Low mode switched itself on (so it may also switch itself off). */
    fun engagedAutomatically(context: Context): Boolean =
        OeaDataStore.get(context).lowModeAutoEngaged()

    /**
     * Called by the battery receiver in [com.oea.launcher.OeaApplication]. Only an
     * automatic engagement is ever lifted automatically: if the user turned Low
     * mode on by hand, it stays on until they say otherwise.
     */
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

    /** Battery percentage and charging state right now, or unknown. */
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

    /**
     * Makes the rest of the launcher match the mode. Every step is guarded and
     * independent: a step that fails (a service that is not running, a role the
     * phone does not grant) leaves the others working.
     */
    fun apply(context: Context) {
        val store = OeaDataStore.get(context)
        val on = store.lowModeEnabled()
        Log.i(TAG, "Low mode ${if (on) "on" else "off"} for ${context.packageName}")
        OeaFaultShield.guard("low-mode:island") {
            if (on) {
                // The system-wide island keeps a foreground service and a window
                // alive over every app; that is exactly what Low mode is for.
                runCatching { OeaDynamicIslandOverlayService.stopForLowMode(context) }
            } else {
                OeaDynamicIslandOverlayService.ensureRunning(context)
            }
        }
        OeaFaultShield.guard("low-mode:wake") {
            // Motion sensors are the most expensive thing OEA keeps listening to.
            runCatching { com.oea.launcher.power.OeaPower.syncWakeService(context) }
        }
        OeaFaultShield.guard("low-mode:boost") {
            if (on) runCatching { com.oea.launcher.gameboost.OeaGameBoost.onLowMode(context) }
        }
        OeaFaultShield.guard("low-mode:apps") { applySuspension(context) }
    }

    /** Suspends or restores the user's app list, only through a role OEA really holds. */
    private fun applySuspension(context: Context) {
        val store = OeaDataStore.get(context)
        val wanted = store.lowModeSuspendApps() && store.lowModeEnabled()
        val apps = store.lowModeAppList()
        if (apps.isEmpty()) return
        val backend = OeaAppFreezer.backend(context)
        Log.i(TAG, "Low mode suspension: wanted=$wanted apps=${apps.size} backend=$backend")
        if (backend == OeaAppFreezer.Backend.NONE) return
        // The same verified path the Freezer uses: Android must confirm the
        // state change or nothing is recorded as done.
        apps.forEach { packageName ->
            OeaFaultShield.guard("low-mode:suspend") {
                val result = OeaAppFreezer.setFrozen(context, packageName, wanted)
                if (!result.success) Log.w(TAG, "Low mode could not change $packageName: ${result.message}")
            }
        }
    }

    /** A one-line description for Settings and the Home banner. */
    fun summary(context: Context): String {
        if (!active(context)) {
            val store = OeaDataStore.get(context)
            return if (store.lowModeAuto()) "Off · turns on at ${store.lowModeThreshold()}% battery" else "Off"
        }
        val (percent, charging) = battery(context)
        val because = when {
            charging -> "on, phone is charging"
            percent in 0..100 -> "on · $percent% battery"
            else -> "on"
        }
        return because
    }

    /** Registers the battery receiver; safe to call more than once. */
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

    /** Android's own Do Not Disturb, offered from Low mode's screen. */
    fun dndAvailable(context: Context): Boolean =
        runCatching {
            context.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted
        }.getOrDefault(false) == true

    /** True when the phone lets Low mode go beyond the launcher itself. */
    fun hasDeepLayer(context: Context): Boolean =
        OeaAppFreezer.backend(context) != OeaAppFreezer.Backend.NONE
}
