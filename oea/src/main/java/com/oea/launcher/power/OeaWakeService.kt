package com.oea.launcher.power

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.oea.launcher.OeaSystemToolsActivity
import com.oea.launcher.R
import com.oea.launcher.data.OeaDataStore

/**
 * Wakes the screen without the power key.
 *
 * Android delivers sensor events only to apps in the foreground or with a
 * foreground service, so this small service (with a quiet notification) runs
 * while wave or lift to wake is switched on. It registers its sensors only
 * while the screen is off and unregisters them the moment the screen turns
 * on; with the screen on it does nothing at all. Proximity and gesture
 * sensors report by interrupt, so there is no polling.
 */
class OeaWakeService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var sensorManager: SensorManager? = null
    private var proximity: Sensor? = null
    private var lift: Sensor? = null
    private var detector: OeaWaveDetector? = null
    private var listening = false
    private var receiverRegistered = false

    private val proximityListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val sensor = proximity ?: return
            val distance = event.values.firstOrNull() ?: return
            val covered = distance < minOf(sensor.maximumRange, 5f) * 0.6f + 0.01f
            lastCovered = covered
            val store = OeaDataStore.get(this@OeaWakeService)
            val bound = store.waveAction() != "none"
            val offBound = store.waveOffAction() != "none"
            if (inCall() || (!bound && !offBound)) return
            when (detector?.onReading(covered, SystemClock.elapsedRealtime(), screenOffBound = offBound)) {
                OeaWaveDetector.Result.ON -> if (bound) wake("wave")
                OeaWaveDetector.Result.OFF -> if (offBound) offGesture()
                else -> Unit
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val liftListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!inCall() && !pocketCovered()) wake("lift")
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val liftTrigger = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            if (!inCall() && !pocketCovered()) wake("lift")
            // One-shot sensors disarm after firing; re-arm while the screen stays off.
            main.postDelayed({ if (listening) armLift() }, 1_500L)
        }
    }

    private var lastCovered = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> startListening()
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> stopListening()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        running = true
        sensorManager = getSystemService(SensorManager::class.java)
        var promoted = false
        try {
            val notif = try { notification() } catch (e: Throwable) {
                Log.w(TAG, "Wake notification build failed, using fallback", e)
                fallbackNotification()
            }
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notif)
            }
            promoted = true
        } catch (e: Throwable) {
            Log.w(TAG, "Wake service could not enter the foreground", e)
            try {
                val fallback = fallbackNotification()
                if (Build.VERSION.SDK_INT >= 34) {
                    startForeground(NOTIFICATION_ID, fallback, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                } else {
                    startForeground(NOTIFICATION_ID, fallback)
                }
                promoted = true
            } catch (e2: Throwable) {
                Log.e(TAG, "Fallback foreground promotion also failed", e2)
            }
        }
        if (!promoted) {
            running = false
            instance = null
            stopSelf()
            return
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        receiverRegistered = runCatching {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else registerReceiver(screenReceiver, filter)
            true
        }.getOrDefault(false)
        reloadSettings()
    }

    private fun fallbackNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            runCatching {
                manager?.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL,
                        "Wake gestures",
                        NotificationManager.IMPORTANCE_MIN
                    ).apply {
                        description = "Keeps OEA's wake gestures available."
                        setShowBadge(false)
                    }
                )
            }
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setSmallIcon(R.drawable.ic_oea_launcher_monochrome)
            .setContentTitle("OEA wake gestures")
            .setContentText("Sensors active while screen is off")
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!OeaPower.wakeFeaturesOn(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopListening()
        if (receiverRegistered) runCatching { unregisterReceiver(screenReceiver) }
        receiverRegistered = false
        main.removeCallbacksAndMessages(null)
        if (instance === this) instance = null
        running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun reloadSettings() {
        if (!OeaPower.wakeFeaturesOn(this)) {
            stopSelf()
            return
        }
        val offGestureBound = OeaDataStore.get(this).waveOffAction() != "none"
        val needProximity = offGestureBound || OeaPower.waveToWake(this) || (OeaPower.liftToWake(this) && OeaPower.pocketGuard(this))
        proximity = if (needProximity) OeaPower.proximitySensor(this) else null
        lift = if (OeaPower.liftToWake(this)) OeaPower.liftSensor(this) else null
        detector = OeaWaveDetector(OeaPower.sensitivity(this), OeaPower.requireTwoWaves(this), OeaPower.pocketGuard(this))
        val screenOn = getSystemService(PowerManager::class.java)?.isInteractive != false
        stopListening()
        if (!screenOn) startListening()
    }

    private fun startListening() {
        if (listening) return
        val sm = sensorManager ?: return
        listening = true
        lastCovered = false
        detector?.reset(SystemClock.elapsedRealtime(), coveredNow = false)
        proximity?.let { sensor ->
            runCatching { sm.registerListener(proximityListener, sensor, SensorManager.SENSOR_DELAY_NORMAL) }
                .onFailure { Log.w(TAG, "Proximity sensor unavailable", it) }
        }
        armLift()
    }

    private fun armLift() {
        val sm = sensorManager ?: return
        val sensor = lift ?: return
        runCatching {
            if (sensor.reportingMode == Sensor.REPORTING_MODE_ONE_SHOT) sm.requestTriggerSensor(liftTrigger, sensor)
            else sm.registerListener(liftListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }.onFailure { Log.w(TAG, "Lift sensor could not be armed", it) }
    }

    private fun stopListening() {
        if (!listening) return
        listening = false
        val sm = sensorManager ?: return
        runCatching { sm.unregisterListener(proximityListener) }
        runCatching { sm.unregisterListener(liftListener) }
        lift?.let { sensor -> if (sensor.reportingMode == Sensor.REPORTING_MODE_ONE_SHOT) runCatching { sm.cancelTriggerSensor(liftTrigger, sensor) } }
    }

    private fun pocketCovered(): Boolean = OeaPower.pocketGuard(this) && lastCovered

    private fun inCall(): Boolean {
        val mode = runCatching { getSystemService(AudioManager::class.java)?.mode }.getOrNull() ?: return false
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_RINGTONE
    }

    private fun wake(reason: String) {
        val action = if (reason == "lift") "wake" else OeaDataStore.get(this).waveAction()
        Log.i(TAG, "Gesture recognised ($reason) -> $action")
        when (action) {
            "lock" -> com.oea.launcher.gesture.OeaGestures.run(this, "wave", "lock")
            "power_menu" -> com.oea.launcher.gesture.OeaGestures.run(this, "wave", "power_menu")
            "low" -> com.oea.launcher.gesture.OeaGestures.run(this, "wave", "low")
            "none" -> Unit
            else -> wakeScreen(this)
        }
    }

    private fun offGesture() {
        val store = OeaDataStore.get(this)
        val action = store.waveOffAction()
        Log.i(TAG, "Screen-off gesture recognised -> $action")
        when (action) {
            "lock" -> OeaPower.lockScreen(this)
            "none" -> Unit
            else -> com.oea.launcher.gesture.OeaGestures.run(this, "wave_off", action)
        }
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager?.createNotificationChannel(NotificationChannel(CHANNEL, "Wake gestures", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Keeps OEA's wave and lift to wake available while the screen is off."
                setShowBadge(false)
            })
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, OeaSystemToolsActivity::class.java).putExtra(OeaSystemToolsActivity.EXTRA_SCREEN, "power").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        val what = listOfNotNull(
            if (OeaPower.waveToWake(this)) "wave" else null,
            if (OeaPower.liftToWake(this) && OeaPower.liftSensor(this) != null) "lift" else null,
            if (OeaDataStore.get(this).waveOffAction() != "none") "hold-and-wave" else null,
        ).joinToString(" or ").ifEmpty { "nothing" }
        return builder
            .setSmallIcon(R.drawable.ic_oea_launcher_monochrome)
            .setContentTitle("Wake gestures are on")
            .setContentText("Screen off: $what to wake. Sensors sleep while the screen is on.")
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val TAG = "OeaWake"
        private const val CHANNEL = "oea_wake_gestures"
        private const val NOTIFICATION_ID = 0x0EA7
        @Volatile var running = false
            private set
        @Volatile var instance: OeaWakeService? = null
            private set

        fun wakeScreen(context: Context) {
            val app = context.applicationContext
            runCatching {
                @Suppress("DEPRECATION")
                val lock = app.getSystemService(PowerManager::class.java)?.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                    "OEA:wake",
                )
                lock?.acquire(3_000L)
            }.onFailure { Log.w(TAG, "Wake lock was refused", it) }
            runCatching {
                app.startActivity(Intent(app, OeaWakeActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
                ))
            }.onFailure { Log.w(TAG, "Wake activity could not start", it) }
        }
    }
}
