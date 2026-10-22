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
import android.content.res.Configuration
import android.view.Surface
import android.view.WindowManager
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
 * while any proximity/lift gesture is enabled. Wake sensors are registered only
 * while the screen is off; the proximity sensor is also registered while the
 * screen is on only when the user explicitly enables cover-to-lock. Unneeded
 * listeners are removed on each screen transition. Sensors report by interrupt,
 * so there is no polling.
 */
class OeaWakeService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var sensorManager: SensorManager? = null
    private var proximity: Sensor? = null
    private var lift: Sensor? = null
    private var accelerometer: Sensor? = null
    @Volatile private var landscapeOrientation = false
    private var detector: OeaWaveDetector? = null
    private val coverLockDetector = OeaCoverLockDetector()
    private var listening = false
    private var listeningScreenOff: Boolean? = null
    private var receiverRegistered = false

    private val coverLockTimeout = Runnable {
        if (!coverToLockAllowed() || listeningScreenOff != false || !lastCovered) {
            coverLockDetector.reset()
            return@Runnable
        }
        if (coverLockDetector.shouldLock(SystemClock.elapsedRealtime())) {
            when (OeaPower.lockScreen(this)) {
                OeaPower.LockResult.LOCKED -> Log.i(TAG, "Screen-on proximity cover locked the phone")
                OeaPower.LockResult.NEEDS_SETUP -> Log.e(TAG, "Cover-to-lock recognized, but locking needs OEA Assistant or Device Admin FORCE_LOCK")
            }
        }
    }

    private val proximityListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val sensor = proximity ?: return
            val distance = event.values.firstOrNull() ?: return
            val covered = distance < minOf(sensor.maximumRange, 5f) * 0.6f + 0.01f
            lastCovered = covered
            val store = OeaDataStore.get(this@OeaWakeService)
            if (inCall()) {
                main.removeCallbacks(coverLockTimeout)
                coverLockDetector.reset()
                if (!covered && isScreenInteractive()) {
                    coverLockDetector.onReading(covered = false, nowMs = SystemClock.elapsedRealtime())
                }
                return
            }

            val screenOff = listeningScreenOff ?: !isScreenInteractive()
            if (!screenOff) {
                if (!coverToLockAllowed()) {
                    main.removeCallbacks(coverLockTimeout)
                    coverLockDetector.reset()
                    return
                }
                val started = coverLockDetector.onReading(covered, SystemClock.elapsedRealtime())
                if (started) main.postDelayed(coverLockTimeout, OeaCoverLockDetector.HOLD_MS)
                if (!coverLockDetector.isArmed) main.removeCallbacks(coverLockTimeout)
                return
            }

            val bound = OeaPower.waveToWake(this@OeaWakeService) && store.waveAction() != "none"
            val offBound = store.waveOffAction() != "none"
            if (!bound && !offBound) return
            // Screen-off hold gestures remain separate from the screen-on cover-to-lock action.
            val result = detector?.onReading(covered, SystemClock.elapsedRealtime(), screenOffBound = offBound)
            when (if (landscapeOrientation && result == OeaWaveDetector.Result.OFF) OeaWaveDetector.Result.NONE else result) {
                OeaWaveDetector.Result.ON -> if (bound) wake("wave")
                OeaWaveDetector.Result.OFF -> if (offBound) offGesture()
                else -> Unit
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    // Read device orientation while screen-off gestures are armed. This avoids
    // relying on Activity orientation callbacks, which do not run in this service.
    private val orientationListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.values.size < 2) return
            val x = event.values[0]
            val y = event.values[1]
            // Ignore near-flat readings; retain the last stable orientation.
            if (kotlin.math.abs(x) + kotlin.math.abs(y) < 3.0f) return
            landscapeOrientation = kotlin.math.abs(x) > kotlin.math.abs(y)
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val liftListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (listeningScreenOff == true && !isScreenInteractive() && !inCall() && !pocketCovered()) wake("lift")
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val liftTrigger = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            if (listeningScreenOff == true && !isScreenInteractive() && !inCall() && !pocketCovered()) wake("lift")
            // One-shot sensors disarm after firing; re-arm only while still in screen-off mode.
            main.postDelayed({ if (listening && listeningScreenOff == true && !isScreenInteractive()) armLift() }, 1_500L)
        }
    }

    private var lastCovered = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> refreshListening(screenOff = true)
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> refreshListening(screenOff = false)
                Intent.ACTION_CONFIGURATION_CHANGED -> refreshListening()
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
            addAction(Intent.ACTION_CONFIGURATION_CHANGED)
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
                        "Proximity gestures",
                        NotificationManager.IMPORTANCE_MIN
                    ).apply {
                        description = "Keeps OEA's configured screen gestures available."
                        setShowBadge(false)
                    }
                )
            }
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setSmallIcon(R.drawable.ic_oea_launcher_monochrome)
            .setContentTitle("OEA proximity gestures")
            .setContentText(
                if (OeaDataStore.get(this).coverToLockEnabled())
                    "Cover sensor to lock while on; wake gestures while off"
                else
                    "Wake sensors active while screen is off"
            )
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
            stopListening()
            stopSelf()
            return
        }
        val store = OeaDataStore.get(this)
        val offGestureBound = store.waveOffAction() != "none"
        val coverToLock = store.coverToLockEnabled()
        val needProximity = offGestureBound || coverToLock || OeaPower.waveToWake(this) ||
            (OeaPower.liftToWake(this) && OeaPower.pocketGuard(this))
        proximity = if (needProximity) OeaPower.proximitySensor(this) else null
        lift = if (OeaPower.liftToWake(this)) OeaPower.liftSensor(this) else null
        accelerometer = if (offGestureBound) sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) else null
        detector = OeaWaveDetector(OeaPower.sensitivity(this), OeaPower.requireTwoWaves(this), OeaPower.pocketGuard(this))
        stopListening()
        startListening()
        runCatching { getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification()) }
            .onFailure { Log.w(TAG, "Could not refresh wake-gesture notification", it) }
    }

    private fun isScreenInteractive(): Boolean =
        getSystemService(PowerManager::class.java)?.isInteractive != false

    /** Use the current display configuration; fall back to display rotation only if undefined. */
    private fun isLandscapeMode(): Boolean = when (resources.configuration.orientation) {
        Configuration.ORIENTATION_LANDSCAPE -> true
        Configuration.ORIENTATION_PORTRAIT -> false
        else -> {
            @Suppress("DEPRECATION")
            val rotation = (getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay?.rotation
            rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
        }
    }

    private fun coverToLockAllowed(): Boolean = OeaCoverLockPolicy.isAllowed(
        screenOn = isScreenInteractive(),
        landscape = isLandscapeMode(),
        enabled = OeaDataStore.get(this).coverToLockEnabled(),
        inCall = inCall()
    )

    private fun refreshListening(screenOff: Boolean? = null) {
        stopListening()
        startListening(screenOff)
    }

    private fun startListening(screenOffOverride: Boolean? = null) {
        if (!OeaPower.wakeFeaturesOn(this)) {
            stopListening()
            return
        }
        val screenOff = screenOffOverride ?: !isScreenInteractive()
        val store = OeaDataStore.get(this)
        val offGestureBound = store.waveOffAction() != "none"
        val listenForCoverToLock = !screenOff && !isLandscapeMode() && store.coverToLockEnabled()
        val listenForProximity = if (screenOff) {
            OeaPower.waveToWake(this) || offGestureBound
        } else {
            listenForCoverToLock
        }
        val listenForLift = screenOff && OeaPower.liftToWake(this) && lift != null
        val listenForOrientation = screenOff && offGestureBound && proximity != null && accelerometer != null
        if (!listenForProximity && !listenForLift && !listenForOrientation) {
            stopListening()
            return
        }
        if (listening && listeningScreenOff == screenOff) return
        if (listening) stopListening()
        val sm = sensorManager ?: return
        listening = true
        listeningScreenOff = screenOff
        lastCovered = false
        detector?.reset(SystemClock.elapsedRealtime(), coveredNow = false)
        coverLockDetector.reset()
        if (listenForProximity) {
            proximity?.let { sensor ->
                runCatching { sm.registerListener(proximityListener, sensor, SensorManager.SENSOR_DELAY_NORMAL) }
                    .onFailure { Log.w(TAG, "Proximity sensor unavailable", it) }
            }
        }
        if (listenForOrientation) {
            accelerometer?.let { sensor ->
                runCatching { sm.registerListener(orientationListener, sensor, SensorManager.SENSOR_DELAY_NORMAL) }
                    .onFailure { Log.w(TAG, "Orientation sensor unavailable; landscape guard may be limited", it) }
            }
        }
        if (listenForLift) armLift()
    }

    private fun armLift() {
        if (listeningScreenOff != true || isScreenInteractive()) return
        val sm = sensorManager ?: return
        val sensor = lift ?: return
        runCatching {
            if (sensor.reportingMode == Sensor.REPORTING_MODE_ONE_SHOT) sm.requestTriggerSensor(liftTrigger, sensor)
            else sm.registerListener(liftListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }.onFailure { Log.w(TAG, "Lift sensor could not be armed", it) }
    }

    private fun stopListening() {
        main.removeCallbacks(coverLockTimeout)
        coverLockDetector.reset()
        val wasListening = listening
        listening = false
        listeningScreenOff = null
        lastCovered = false
        if (!wasListening) return
        val sm = sensorManager ?: return
        runCatching { sm.unregisterListener(proximityListener) }
        runCatching { sm.unregisterListener(orientationListener) }
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
        Log.i(TAG, "Screen-off single-hold gesture recognised -> $action")
        when (action) {
            "lock" -> {
                val result = OeaPower.lockScreen(this)
                if (result == OeaPower.LockResult.LOCKED) {
                    Log.i(TAG, "Screen-off gesture lock request dispatched successfully")
                } else {
                    Log.e(TAG, "Screen-off gesture detected, but locking needs setup: enable OEA Assistant or approve Device Admin FORCE_LOCK")
                }
            }
            "none" -> Unit
            else -> com.oea.launcher.gesture.OeaGestures.run(this, "wave_off", action)
        }
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager?.createNotificationChannel(NotificationChannel(CHANNEL, "Proximity gestures", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Keeps OEA's configured screen gestures available."
                setShowBadge(false)
            })
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, OeaSystemToolsActivity::class.java).putExtra(OeaSystemToolsActivity.EXTRA_SCREEN, "power").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        val store = OeaDataStore.get(this)
        val activeGestures = listOfNotNull(
            if (store.coverToLockEnabled()) "cover sensor to lock while screen is on" else null,
            if (OeaPower.waveToWake(this)) "wave to wake while screen is off" else null,
            if (OeaPower.liftToWake(this) && OeaPower.liftSensor(this) != null) "lift to wake" else null,
            if (store.waveOffAction() != "none") "screen-off hold gesture" else null
        ).joinToString(" • ").ifEmpty { "Sensor service active" }
        return builder
            .setSmallIcon(R.drawable.ic_oea_launcher_monochrome)
            .setContentTitle("OEA proximity gestures are on")
            .setContentText(activeGestures)
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
                    "OEA:wake"
)
                lock?.acquire(3_000L)
            }.onFailure { Log.w(TAG, "Wake lock was refused", it) }
            runCatching {
                app.startActivity(Intent(app, OeaWakeActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
))
            }.onFailure { Log.w(TAG, "Wake activity could not start", it) }
        }
    }
}
