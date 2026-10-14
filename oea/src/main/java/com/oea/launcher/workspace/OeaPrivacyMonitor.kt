package com.oea.launcher.workspace

import android.content.Context
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Camera and microphone use, for the island's privacy dots.
 *
 * Uses only public, permission-free callbacks: CameraManager reports when any
 * app opens or closes a camera, and AudioManager reports active recordings
 * (anonymised, so OEA never learns which app records). Both are push based
 * and registered only while an island surface is on screen, so nothing runs
 * in the background.
 */
object OeaPrivacyMonitor {
    private const val TAG = "OeaPrivacy"
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val busyCameras = HashSet<String>()
    private var appContext: Context? = null
    private var registered = false

    @Volatile var cameraInUse = false
        private set
    @Volatile var micInUse = false
        private set

    private val cameraCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(cameraId: String) {
            if (busyCameras.remove(cameraId)) update()
        }

        override fun onCameraUnavailable(cameraId: String) {
            if (busyCameras.add(cameraId)) update()
        }
    }

    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
            val active = !configs.isNullOrEmpty()
            if (active != micInUse) {
                micInUse = active
                notifyListeners()
            }
        }
    }

    fun addListener(context: Context, listener: () -> Unit) {
        listeners += listener
        if (!registered) start(context.applicationContext)
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
        if (listeners.isEmpty()) stop()
    }

    private fun start(context: Context) {
        appContext = context
        registered = true
        runCatching { context.getSystemService(CameraManager::class.java)?.registerAvailabilityCallback(cameraCallback, main) }
            .onFailure { Log.w(TAG, "Camera availability is not reported on this device", it) }
        runCatching {
            val audio = context.getSystemService(AudioManager::class.java)
            audio?.registerAudioRecordingCallback(recordingCallback, main)
            micInUse = audio?.activeRecordingConfigurations?.isNotEmpty() == true
        }.onFailure { Log.w(TAG, "Recording activity is not reported on this device", it) }
    }

    private fun stop() {
        val context = appContext ?: return
        registered = false
        runCatching { context.getSystemService(CameraManager::class.java)?.unregisterAvailabilityCallback(cameraCallback) }
        runCatching { context.getSystemService(AudioManager::class.java)?.unregisterAudioRecordingCallback(recordingCallback) }
        busyCameras.clear()
        cameraInUse = false
        micInUse = false
    }

    private fun update() {
        val inUse = busyCameras.isNotEmpty()
        if (inUse != cameraInUse) {
            cameraInUse = inUse
            notifyListeners()
        }
    }

    private fun notifyListeners() {
        main.post { listeners.forEach { runCatching { it() } } }
    }
}
