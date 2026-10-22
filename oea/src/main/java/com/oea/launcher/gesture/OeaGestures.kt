package com.oea.launcher.gesture

import com.oea.launcher.camera.OeaTorch

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent
import com.oea.launcher.OeaLauncherActivity
import com.oea.launcher.assistant.OeaSystemActions
import com.oea.launcher.data.OeaDataStore
import com.oea.launcher.fault.OeaFaultShield
import com.oea.launcher.perf.OeaLowMode
import com.oea.launcher.power.OeaPower
import com.oea.launcher.power.OeaWakeService
import java.util.concurrent.ConcurrentHashMap

/**
 * Every gesture OEA recognises, and the action each one runs.
 *
 * Expanded to support separate configurable actions including waking device,
 * locking screen, DND, flashlight, etc. — not just one switch.
 *
 * Persistence fix: gesture settings are stored in OeaDataStore which survives
 * reboots and Ultra Power Saving Mode. OeaPower also mirrors sensitivity to
 * DataStore for redundancy. Boot receiver and LowMode exit both call syncWakeService.
 */
object OeaGestures {
    const val MULTI_TAP_WINDOW = 260L

    val slots = linkedMapOf(
        "double_tap" to "Double-tap Home",
        "triple_tap" to "Triple-tap Home",
        "two_finger" to "Two-finger swipe on Home",
        "swipe_down" to "Swipe down on Home",
        "long_press" to "Long-press empty Home",
        "wave" to "Wave in front of the phone",
        "wave_off" to "Hold-and-wave (screen off)",
        "shake" to "Shake device",
        "flip" to "Flip device"
    )

    val allowed: Map<String, List<String>> = mapOf(
        "double_tap" to listOf("none", "lock", "power_menu", "quick_controls", "drawer", "search", "low", "volume", "silence", "dnd_toggle", "dnd_on", "dnd_off", "flashlight", "wake", "screenshot", "camera"),
        "triple_tap" to listOf("none", "lock", "power_menu", "quick_controls", "drawer", "search", "low", "volume", "silence", "dnd_toggle", "dnd_on", "dnd_off", "flashlight", "wake", "screenshot"),
        "two_finger" to listOf("none", "search", "drawer", "notifications", "quick_settings", "quick_controls", "silence", "low", "dnd_toggle", "flashlight", "camera"),
        "swipe_down" to listOf("none", "notifications", "quick_settings", "quick_controls", "search", "silence", "volume", "dnd_toggle", "flashlight"),
        "long_press" to listOf("none", "wallpaper", "widgets", "settings", "low", "quick_controls", "dnd_toggle"),
        "wave" to listOf("none", "wake", "lock", "power_menu", "low", "volume", "silence", "dnd_toggle", "dnd_on", "dnd_off", "flashlight"),
        "wave_off" to listOf("none", "lock", "dnd_toggle", "dnd_on", "dnd_off", "flashlight", "low"),
        "shake" to listOf("none", "lock", "flashlight", "camera", "screenshot", "dnd_toggle", "low"),
        "flip" to listOf("none", "dnd_on", "dnd_off", "dnd_toggle", "silence", "low")
    )

    val actions: List<Triple<String, String, String>> = listOf(
        Triple("none", "Nothing", "The gesture is not listened for at all."),
        Triple("wake", "Wake the screen", "Wave/lift gestures: turn the screen on. Needs Turn screen on permission on Android 14+."),
        Triple("lock", "Lock the phone", "Locks the screen, like power button. Needs OEA Assistant or Device Admin."),
        Triple("power_menu", "Power menu", "Android's own power menu (needs OEA Assistant)."),
        Triple("drawer", "Open app drawer", "Same as swipe up."),
        Triple("search", "Search apps", "Opens OEA search with keyboard."),
        Triple("notifications", "Notifications", "Pulls down notification shade (needs Assistant)."),
        Triple("quick_settings", "Quick Settings", "Pulls down Quick Settings (needs Assistant)."),
        Triple("quick_controls", "OEA Quick controls", "OEA's own panel: brightness, volume, ringer, notifications centre."),
        Triple("volume", "Volume panel", "Shows Android volume panel."),
        Triple("silence", "Silence / restore", "Mutes ring and enables DND, or restores."),
        Triple("dnd_toggle", "Toggle Do Not Disturb", "Switches DND on/off. Needs DND access."),
        Triple("dnd_on", "Enable Do Not Disturb", "Enables DND. Needs DND access."),
        Triple("dnd_off", "Disable Do Not Disturb", "Disables DND. Needs DND access."),
        Triple("low", "LOW mode", "Turns OEA power-saving on/off."),
        Triple("wallpaper", "Wallpaper & themes", "Opens wallpaper and theme screen."),
        Triple("widgets", "Add a widget", "Opens widget picker."),
        Triple("settings", "OEA Settings", "Opens OEA settings."),
        Triple("flashlight", "Flashlight", "Toggles the OEA flashlight."),
        Triple("camera", "Open camera", "Opens OEA Camera or system camera."),
        Triple("screenshot", "Screenshot", "Takes screenshot via Game Boost capture where available.")
)

    fun actionLabel(id: String): String = actions.firstOrNull { it.first == id }?.second ?: id
    fun actionDetail(id: String): String = actions.firstOrNull { it.first == id }?.third ?: ""

    fun action(context: Context, gesture: String): String {
        val store = OeaDataStore.get(context)
        return when (gesture) {
            "double_tap" -> store.doubleTapAction()
            "triple_tap" -> store.tripleTapAction()
            "two_finger" -> store.twoFingerSwipeAction()
            "swipe_down" -> store.swipeDownAction()
            "long_press" -> store.emptyLongPressAction()
            "wave" -> store.waveAction()
            "wave_off" -> store.waveOffAction()
            "shake" -> store.shakeAction()
            "flip" -> store.flipAction()
            else -> "none"
        }
    }

    fun set(context: Context, gesture: String, action: String) {
        val permitted = allowed[gesture].orEmpty()
        val value = if (action in permitted) action else "none"
        val store = OeaDataStore.get(context)
        when (gesture) {
            "double_tap" -> store.setDoubleTapAction(value)
            "triple_tap" -> store.setTripleTapAction(value)
            "two_finger" -> store.setTwoFingerSwipeAction(value)
            "swipe_down" -> store.setSwipeDownAction(value)
            "long_press" -> store.setEmptyLongPressAction(value)
            "wave" -> store.setWaveAction(value)
            "wave_off" -> store.setWaveOffAction(value)
            "shake" -> store.setShakeAction(value)
            "flip" -> store.setFlipAction(value)
        }
        if (gesture == "wave" || gesture == "wave_off") OeaPower.syncWakeService(context)
    }

    private val shareable = setOf("none", "wake")

    fun conflicts(context: Context, action: String, ignore: String? = null): List<String> {
        if (action in shareable) return emptyList()
        return slots.keys.filter { it != ignore && this.action(context, it) == action }
    }

    private const val COOLDOWN_MS = 400L
    private val lastRun = ConcurrentHashMap<String, Long>()

    fun run(
        context: Context,
        gesture: String,
        actionOverride: String? = null,
        homeAction: ((String) -> Boolean)? = null
): Boolean {
        val action = actionOverride ?: action(context, gesture)
        if (action == "none") return false
        val now = SystemClock.elapsedRealtime()
        val previous = lastRun[gesture]
        if (previous != null && now - previous < COOLDOWN_MS) return false
        lastRun[gesture] = now
        val app = context.applicationContext
        return OeaFaultShield.value("gesture:$gesture:$action", false) {
            when (action) {
                "wake" -> { OeaWakeService.wakeScreen(app); true }
                "lock" -> OeaPower.lockScreen(app) == OeaPower.LockResult.LOCKED
                "power_menu" -> OeaPower.openPowerMenu()
                "low" -> {
                    OeaLowMode.setEnabled(app, !OeaLowMode.active(app))
                    true
                }
                "volume" -> { OeaPower.showVolumePanel(app); true }
                "silence" -> silence(app)
                "dnd_toggle" -> {
                    val on = !OeaPower.dndOn(app)
                    OeaPower.setDnd(app, on)
                }
                "dnd_on" -> OeaPower.setDnd(app, true)
                "dnd_off" -> OeaPower.setDnd(app, false)
                "flashlight" -> toggleFlashlight(app)
                "camera" -> {
                    try {
                        app.startActivity(com.oea.launcher.miniapps.OeaMiniApps.launchIntent(app, "camera").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        true
                    } catch (_: Throwable) { false }
                }
                "screenshot" -> {
                    // Use Game Boost capture if available, else system screenshot via Assistant
                    try {
                        val intent = Intent(app, com.oea.launcher.gameboost.OeaGameCaptureActivity::class.java).putExtra(com.oea.launcher.gameboost.OeaGameCaptureActivity.EXTRA_MODE, com.oea.launcher.gameboost.OeaGameCaptureActivity.MODE_SCREENSHOT).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        app.startActivity(intent)
                        true
                    } catch (_: Throwable) { false }
                }
                else -> {
                    val handled = homeAction?.invoke(action) == true
                    handled || deliverToHome(app, action)
                }
            }
        }
    }

    private fun deliverToHome(context: Context, action: String): Boolean = runCatching {
        context.startActivity(
            Intent(context, OeaLauncherActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .putExtra(OeaLauncherActivity.EXTRA_HOME_ACTION, action)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
)
        true
    }.getOrDefault(false)

    private fun silence(context: Context): Boolean {
        val manager = runCatching { context.getSystemService(AudioManager::class.java) }.getOrNull() ?: return false
        val dndOn = runCatching {
            context.getSystemService(android.app.NotificationManager::class.java)?.currentInterruptionFilter !=
                android.app.NotificationManager.INTERRUPTION_FILTER_ALL
        }.getOrDefault(false)
        return if (dndOn) {
            val unmuted = runCatching { manager.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_UNMUTE, 0) }.isSuccess
            OeaPower.setDnd(context, false) || unmuted
        } else {
            val muted = runCatching { manager.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_MUTE, 0) }.isSuccess
            OeaPower.setDnd(context, true) || muted
        }
    }

    /**
     * The same torch the Quick controls tile and the Camera screen use, so a
     * gesture cannot leave the LED in a state another screen disagrees about.
     * The old version remembered its own state in preferences and picked
     * `cameraIdList.first()`, which on a phone whose first camera is the front one
     * meant the flashlight "worked" only in OEA's own imagination.
     */
    private fun toggleFlashlight(context: Context): Boolean = OeaTorch.toggle(context)

    fun expandNotifications(context: Context): Boolean = OeaSystemActions.expandNotifications(context)
    fun expandQuickSettings(context: Context): Boolean = OeaSystemActions.expandQuickSettings(context)
    fun isVolumeKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
}

class OeaTapRouter(private val window: Long = OeaGestures.MULTI_TAP_WINDOW) {
    private var taps = 0
    private var lastTapAt = 0L
    private val pending = Runnable { decide() }

    var onDoubleTap: (() -> Unit)? = null
    var onTripleTap: (() -> Unit)? = null
    var onSingleTap: (() -> Unit)? = null

    fun tap(now: Long = android.os.SystemClock.uptimeMillis()) {
        taps = if (now - lastTapAt > window) 1 else taps + 1
        lastTapAt = now
        schedule()
    }

    private fun schedule() {
        handler?.removeCallbacks(pending)
        handler?.postDelayed(pending, window + 10L)
    }

    private var handler: android.os.Handler? = null

    fun attach(handler: android.os.Handler) {
        this.handler = handler
    }

    fun cancel() {
        taps = 0
        handler?.removeCallbacks(pending)
    }

    private fun decide() {
        val count = taps
        taps = 0
        when {
            count >= 3 -> onTripleTap?.invoke()
            count == 2 -> onDoubleTap?.invoke()
            count == 1 -> onSingleTap?.invoke()
        }
    }
}
