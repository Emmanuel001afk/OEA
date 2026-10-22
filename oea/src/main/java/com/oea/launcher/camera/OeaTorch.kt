package com.oea.launcher.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * One owner for the LED torch.
 *
 * The launcher needs a torch in five places — Home gestures, Quick controls,
 * voice ("turn the flashlight on"), the Phone's flash-on-ring alert and the
 * Camera app itself — and each one used to talk to `CameraManager` on its own
 * with `cameraIdList.firstOrNull()`. That first id is the *front* camera on some
 * phones, a camera with no LED on others, and on a device with no torch at all
 * `setTorchMode` throws. The result was a torch that worked from one screen and
 * silently did nothing from another, and a UI that could not say which state the
 * LED was really in.
 *
 * So the choice lives here, once: the first rear camera that actually has a
 * flash, then any camera with a flash. The state lives here too, kept honest by
 * Android's own [CameraManager.TorchCallback], so a gesture, a tile and the
 * Camera screen can never disagree about whether the light is on.
 */
object OeaTorch {
    private const val TAG = "OeaTorch"

    /** A camera, as far as the torch is concerned. Plain data so [choose] is testable. */
    data class Candidate(val id: String, val backFacing: Boolean, val hasFlash: Boolean)

    /**
     * The camera a torch should use: a rear camera with a flash, otherwise the
     * first camera that has one, otherwise nothing.
     */
    fun choose(candidates: List<Candidate>): Candidate? =
        candidates.firstOrNull { it.backFacing && it.hasFlash } ?: candidates.firstOrNull { it.hasFlash }

    private val lock = Any()
    @Volatile private var resolved = false
    @Volatile private var chosenId: String? = null
    @Volatile private var lit = false
    @Volatile private var watching = false

    /** Every camera this device exposes, described for [choose]. Safe to call from any thread. */
    fun candidates(context: Context): List<Candidate> {
        val manager = runCatching { context.getSystemService(CameraManager::class.java) }.getOrNull() ?: return emptyList()
        val ids = runCatching { manager.cameraIdList.toList() }.getOrDefault(emptyList())
        return ids.mapNotNull { id ->
            runCatching {
                val chars = manager.getCameraCharacteristics(id)
                Candidate(
                    id = id,
                    backFacing = chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK,
                    hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                )
            }.getOrNull()
        }
    }

    /** The camera whose LED is used as the torch; resolved once per process. */
    fun cameraId(context: Context): String? {
        if (resolved) return chosenId
        synchronized(lock) {
            if (resolved) return chosenId
            chosenId = choose(candidates(context))?.id
            resolved = true
            return chosenId
        }
    }

    fun available(context: Context): Boolean = cameraId(context) != null

    /** The last state Android confirmed, not what a caller hoped for. */
    fun isOn(): Boolean = lit

    /**
     * Switches the LED on or off. Returns whether Android accepted the change,
     * so a caller can tell "the light is off" apart from "there is no light".
     * Never throws and needs no permission.
     */
    fun set(context: Context, on: Boolean): Boolean {
        val manager = runCatching { context.getSystemService(CameraManager::class.java) }.getOrNull() ?: return false
        val id = cameraId(context) ?: return false
        val applied = runCatching { manager.setTorchMode(id, on) }.isSuccess
        if (applied) {
            lit = on
            watch(context, manager)
        }
        return applied
    }

    /** Toggles the LED and returns the state afterwards. */
    fun toggle(context: Context): Boolean {
        val wanted = !lit
        return if (set(context, wanted)) wanted else lit
    }

    /** Puts the light out if it is on. Called whenever the screen that owns it goes away. */
    fun off(context: Context) {
        if (!lit) return
        set(context, false)
    }

    /**
     * Android reports every torch change to every listener, including the one
     * Android's own Quick Settings tile causes. Listening keeps [isOn] true to
     * the hardware instead of to the last thing OEA asked for.
     */
    private fun watch(context: Context, manager: CameraManager) {
        if (watching) return
        synchronized(lock) {
            if (watching) return
            watching = true
            val callback = object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                    if (cameraId == chosenId) lit = enabled
                }

                override fun onTorchModeUnavailable(cameraId: String) {
                    if (cameraId == chosenId) lit = false
                }
            }
            val registered = runCatching {
                manager.registerTorchCallback(callback, Handler(Looper.getMainLooper()))
                true
            }.getOrDefault(false)
            if (!registered) {
                watching = false
                Log.w(TAG, "This phone would not report torch state changes")
            }
        }
    }
}
