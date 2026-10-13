package com.oea.launcher.phone

import android.Manifest
import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.InCallService
import com.oea.launcher.callblocker.OeaCallBlockRules

class OeaInCallService : InCallService() {
    private val handler = Handler(Looper.getMainLooper())
    private var flashOn = false
    private var flashCameraId: String? = null
    private var observed: Call? = null
    private val flashPulse = object : Runnable {
        override fun run() {
            val call = observed
            val enabled = getSharedPreferences("oea_phone_prefs", Context.MODE_PRIVATE)
                .getBoolean("flash_alert", false)
            if (!enabled || call?.state != Call.STATE_RINGING ||
                checkSelfPermission(Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                stopFlash()
                return
            }
            runCatching {
                val manager = getSystemService(CameraManager::class.java)
                val id = flashCameraId ?: manager.cameraIdList.firstOrNull { camera ->
                    manager.getCameraCharacteristics(camera).get(
                        android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE
                    ) == true
                }?.also { flashCameraId = it }
                if (id != null) {
                    flashOn = !flashOn
                    manager.setTorchMode(id, flashOn)
                }
            }
            handler.postDelayed(this, 450L)
        }
    }

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            OeaCallState.notifyChanged()
            if (state == Call.STATE_RINGING) {
                if (interceptIfBlocked(call)) return
                startFlash()
            } else {
                stopFlash()
            }
        }

        override fun onDetailsChanged(call: Call, details: Call.Details) {
            OeaCallState.notifyChanged()
        }
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        if (call.state == Call.STATE_RINGING && interceptIfBlocked(call)) {
            return
        }
        observed?.unregisterCallback(callback)
        observed = call
        OeaCallState.current = call
        runCatching { call.registerCallback(callback) }
        if (call.state == Call.STATE_RINGING) startFlash() else stopFlash()
        runCatching {
            startActivity(
                android.content.Intent(this, OeaInCallActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
    }

    private fun interceptIfBlocked(call: Call): Boolean {
        val details = call.details ?: return false
        val rawNumber = details.handle?.schemeSpecificPart.orEmpty()
        if (!OeaCallBlockRules.shouldBlockIncomingCall(this, rawNumber, details.handlePresentation)) {
            return false
        }
        stopFlash()
        val label = OeaCallBlockRules.normalizeNumber(rawNumber).ifEmpty { "Unknown/private" }
        OeaCallBlockRules.recordBlocked(this, label)
        runCatching { call.reject(false, null) }.onFailure {
            runCatching { call.disconnect() }
        }
        return true
    }

    override fun onCallRemoved(call: Call) {
        runCatching { call.unregisterCallback(callback) }
        if (observed == call) {
            observed = null
            stopFlash()
        }
        if (OeaCallState.current == call) OeaCallState.current = null
        super.onCallRemoved(call)
    }

    private fun startFlash() {
        handler.removeCallbacks(flashPulse)
        handler.post(flashPulse)
    }

    private fun stopFlash() {
        handler.removeCallbacks(flashPulse)
        if (flashOn) {
            runCatching {
                flashCameraId?.let { getSystemService(CameraManager::class.java).setTorchMode(it, false) }
            }
        }
        flashOn = false
    }

    override fun onDestroy() {
        stopFlash()
        observed?.unregisterCallback(callback)
        observed = null
        super.onDestroy()
    }
}

/** Observable state from OEA's default-dialer connection for the Home capsule. */
object OeaCallState {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()

    @Volatile
    var current: Call? = null
        set(value) {
            field = value
            notifyChanged()
        }

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    fun notifyChanged() {
        main.post { listeners.forEach { listener -> runCatching { listener() } } }
    }
}
