package com.oea.launcher.power

import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.oea.launcher.applock.OeaDeviceAdminReceiver
import com.oea.launcher.assistant.OeaAssistant

/**
 * Alternatives for a faulty power (or volume) button, using only what
 * Android lets an app do:
 *  - wake the screen with a proximity wave or, where the phone has one, its
 *    low-power pick-up sensor (OEA's wake service listens only while the
 *    screen is off);
 *  - lock / sleep the screen and open the power menu through OEA Assistant
 *    (with Device admin as a lock fallback);
 *  - volume, ringer and Do Not Disturb controls.
 * No app can switch on a powered-off phone or restart it without the
 * system's own power menu; OEA never pretends otherwise.
 */
object OeaPower {
    private const val TAG = "OeaPower"
    private const val PREFS = "oea_power"
    const val KEY_WAVE = "wave_to_wake"
    const val KEY_LIFT = "lift_to_wake"
    const val KEY_SENSITIVITY = "wave_sensitivity"
    const val KEY_TWO_WAVES = "wave_twice"
    const val KEY_POCKET = "pocket_guard"

    fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun waveToWake(context: Context) = prefs(context).getBoolean(KEY_WAVE, false)
    fun liftToWake(context: Context) = prefs(context).getBoolean(KEY_LIFT, false)
    fun sensitivity(context: Context) = prefs(context).getInt(KEY_SENSITIVITY, 1).coerceIn(0, 2)
    fun requireTwoWaves(context: Context) = prefs(context).getBoolean(KEY_TWO_WAVES, true)
    fun pocketGuard(context: Context) = prefs(context).getBoolean(KEY_POCKET, true)

    fun set(context: Context, key: String, value: Boolean) {
        prefs(context).edit().putBoolean(key, value).apply()
        syncWakeService(context)
    }

    fun setSensitivity(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_SENSITIVITY, value.coerceIn(0, 2)).apply()
        syncWakeService(context)
    }

    fun wakeFeaturesOn(context: Context) = waveToWake(context) || (liftToWake(context) && liftSensor(context) != null)

    // ------------------------------------------------------------- sensors

    /** The proximity sensor, preferring the variant that can wake the processor. */
    fun proximitySensor(context: Context): Sensor? {
        val sm = context.getSystemService(SensorManager::class.java) ?: return null
        return sm.getDefaultSensor(Sensor.TYPE_PROXIMITY, true) ?: sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    }

    /**
     * A low-power gesture sensor some phones expose (pick-up, wake or glance
     * gesture, tilt detector). Unlike polling the accelerometer it costs
     * almost nothing while the screen is off; phones without one simply
     * don't offer lift to wake.
     */
    fun liftSensor(context: Context): Sensor? {
        val sm = context.getSystemService(SensorManager::class.java) ?: return null
        val wanted = listOf("android.sensor.pick_up_gesture", "android.sensor.wake_gesture", "android.sensor.glance_gesture", "android.sensor.tilt_detector")
        return runCatching {
            sm.getSensorList(Sensor.TYPE_ALL).filter { it.isWakeUpSensor && it.stringType in wanted }
                .minByOrNull { wanted.indexOf(it.stringType) }
        }.getOrNull()
    }

    // ------------------------------------------------------------ actions

    enum class LockResult { LOCKED, NEEDS_SETUP }

    /**
     * Locks and turns off the screen. OEA Assistant's lock action behaves like
     * the power key (fingerprint and face unlock keep working). Device admin
     * is the fallback; Android may then ask for the PIN on the next unlock.
     */
    fun lockScreen(context: Context): LockResult {
        if (OeaAssistant.lockScreen()) return LockResult.LOCKED
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(context, OeaDeviceAdminReceiver::class.java)
        if (dpm != null && deviceAdminCanLock(context)) {
            val locked = runCatching {
                dpm.lockNow()
                true
            }.onFailure { Log.w(TAG, "Device admin lock is not granted (re-activation needed)", it) }.getOrDefault(false)
            if (locked) return LockResult.LOCKED
        }
        return LockResult.NEEDS_SETUP
    }

    fun deviceAdminActive(context: Context): Boolean = runCatching {
        context.getSystemService(DevicePolicyManager::class.java)?.isAdminActive(ComponentName(context, OeaDeviceAdminReceiver::class.java)) == true
    }.getOrDefault(false)

    /**
     * Device admin can lock only if its activation included the "force lock"
     * policy; an admin activated by an older OEA (freezer only) must be
     * re-activated to grant it.
     */
    fun deviceAdminCanLock(context: Context): Boolean = runCatching {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return false
        val admin = ComponentName(context, OeaDeviceAdminReceiver::class.java)
        dpm.isAdminActive(admin) && dpm.hasGrantedPolicy(admin, android.app.admin.DeviceAdminInfo.USES_POLICY_FORCE_LOCK)
    }.getOrDefault(false)

    fun deviceAdminIntent(context: Context): Intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
        .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(context, OeaDeviceAdminReceiver::class.java))
        .putExtra(
            DevicePolicyManager.EXTRA_ADD_EXPLANATION,
            "Lets OEA lock the screen when your power button doesn't work and OEA Assistant is off. Android may ask for your PIN instead of your fingerprint after OEA locks the phone.",
        )

    /** Android's power menu (power off, restart, emergency). Needs OEA Assistant. */
    fun openPowerMenu(): Boolean = OeaAssistant.openPowerMenu()

    /** Shows Android's own volume panel (works without any permission). */
    fun showVolumePanel(context: Context) {
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        runCatching { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI) }
    }

    fun policyAccess(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true

    /** Sound / vibrate / silent. Silent and DND changes need Do Not Disturb access on Android 7+. */
    fun setRinger(context: Context, mode: Int): Boolean {
        val audio = context.getSystemService(AudioManager::class.java) ?: return false
        if (mode == AudioManager.RINGER_MODE_SILENT && !policyAccess(context)) return false
        return runCatching {
            audio.ringerMode = mode
            true
        }.getOrDefault(false)
    }

    fun dndOn(context: Context): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        return nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL &&
            nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }

    fun setDnd(context: Context, on: Boolean): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        if (!nm.isNotificationPolicyAccessGranted) return false
        return runCatching {
            nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
            true
        }.getOrDefault(false)
    }

    fun dndSettingsIntent(): Intent = Intent("android.settings.ZEN_MODE_SETTINGS")
    fun policyAccessIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
    fun notificationSettingsIntent(): Intent = Intent("android.settings.NOTIFICATION_SETTINGS")
    fun soundSettingsIntent(): Intent = Intent(Settings.ACTION_SOUND_SETTINGS)

    /** Starts or stops OEA's wake service to match the settings (call from the foreground). */
    fun syncWakeService(context: Context) {
        val app = context.applicationContext
        val intent = Intent(app, OeaWakeService::class.java)
        // Low mode releases the motion sensors; the user's wave/lift settings are
        // kept and the service simply comes back when Low mode ends.
        if (com.oea.launcher.perf.OeaLowMode.active(app)) {
            app.stopService(intent)
            return
        }
        if (!wakeFeaturesOn(app)) {
            app.stopService(intent)
            return
        }
        if (OeaWakeService.running) {
            OeaWakeService.instance?.reloadSettings()
            return
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(intent) else app.startService(intent)
        }.onFailure { Log.w(TAG, "Android did not allow starting the wake service now", it) }
    }
}
