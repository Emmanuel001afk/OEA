package com.oea.launcher.gesture

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
 * The point of this file is that gestures cannot collide. Each gesture has its
 * own identity, its own input source and its own settings key:
 *
 * | Gesture | Source | How it is kept apart from the others |
 * |---|---|---|
 * | double tap | touch, 1 finger | fires only after [MULTI_TAP_WINDOW], when a third tap can no longer come |
 * | triple tap | touch, 1 finger | fires only when three taps land inside that same window |
 * | long press | touch, 1 finger | held past the system's touch-slop timeout, never on an icon |
 * | two-finger swipe | touch, 2 pointers | pointer count, so a one-finger swipe can never trigger it |
 * | swipe down | touch, 1 finger | axis check: vertical movement must beat horizontal by 1.1× |
 * | wave | proximity sensor | a defined near→far pattern with a pause, not a single reading |
 * | lift | significant-motion sensor | its own sensor, and never while the pocket guard sees a cover |
 * | volume keys | hardware keys | only when a key event is delivered while OEA is in front |
 *
 * A gesture bound to "none" is not listened for at all: the wave and lift
 * sensors are released, the detector ignores the pattern, so an unwanted gesture
 * costs nothing.
 */
object OeaGestures {
    /**
     * How long Home waits after a tap before deciding it was a double tap.
     *
     * This one number is what keeps double and triple tap apart: a double tap can
     * only fire once a third tap is no longer possible, so the same three taps can
     * never run both actions. It sits inside Android's own double-tap window, so
     * the gestures still feel immediate.
     */
    const val MULTI_TAP_WINDOW = 260L

    /** Gesture id → the label Settings shows. */
    val slots = linkedMapOf(
        "double_tap" to "Double-tap Home",
        "triple_tap" to "Triple-tap Home",
        "two_finger" to "Two-finger swipe on Home",
        "swipe_down" to "Swipe down on Home",
        "long_press" to "Long-press empty Home",
        "wave" to "Wave in front of the phone",
    )

    /** Gesture id → the actions it may be bound to (each gesture has its own list). */
    val allowed: Map<String, List<String>> = mapOf(
        "double_tap" to listOf("none", "lock", "power_menu", "quick_controls", "drawer", "search", "low", "volume", "silence"),
        "triple_tap" to listOf("none", "lock", "power_menu", "quick_controls", "drawer", "search", "low", "volume", "silence"),
        "two_finger" to listOf("none", "search", "drawer", "notifications", "quick_settings", "quick_controls", "silence", "low"),
        "swipe_down" to listOf("none", "notifications", "quick_settings", "quick_controls", "search", "silence", "volume"),
        "long_press" to listOf("none", "wallpaper", "widgets", "settings", "low"),
        "wave" to listOf("none", "wake", "lock", "power_menu", "low", "volume", "silence"),
    )

    /** Every action, with a plain description, for the Settings sheet. */
    val actions: List<Triple<String, String, String>> = listOf(
        Triple("none", "Nothing", "The gesture is not listened for at all."),
        Triple("wake", "Wake the screen", "Wave gestures: turn the screen on."),
        Triple("lock", "Lock the phone", "Locks the screen, like the power button."),
        Triple("power_menu", "Power menu", "Android's own power menu (needs OEA Assistant)."),
        Triple("drawer", "Open the app drawer", "The same as swiping up."),
        Triple("search", "Search apps", "Opens OEA's search with the keyboard ready."),
        Triple("notifications", "Notifications", "Pulls down the notification shade."),
        Triple("quick_settings", "Quick Settings", "Pulls down Android's Quick Settings."),
        Triple("quick_controls", "OEA Quick controls", "OEA's own panel: brightness, volume, ringer."),
        Triple("volume", "Volume panel", "Shows Android's volume panel."),
        Triple("silence", "Silence / restore", "Switches Do Not Disturb on, or restores your ringer."),
        Triple("low", "LOW mode", "Turns OEA's power-saving mode on or off."),
        Triple("wallpaper", "Wallpaper & themes", "Opens OEA's wallpaper and theme screen."),
        Triple("widgets", "Add a widget", "Opens OEA's widget picker."),
        Triple("settings", "OEA Settings", "Opens OEA's own settings."),
    )

    fun actionLabel(id: String): String = actions.firstOrNull { it.first == id }?.second ?: id

    fun actionDetail(id: String): String = actions.firstOrNull { it.first == id }?.third ?: ""

    /** The action a gesture is currently bound to. */
    fun action(context: Context, gesture: String): String {
        val store = OeaDataStore.get(context)
        return when (gesture) {
            "double_tap" -> store.doubleTapAction()
            "triple_tap" -> store.tripleTapAction()
            "two_finger" -> store.twoFingerSwipeAction()
            "swipe_down" -> store.swipeDownAction()
            "long_press" -> store.emptyLongPressAction()
            "wave" -> store.waveAction()
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
        }
        // The wake service keeps its sensors only while a sensor gesture is bound,
        // so binding "none" releases the hardware immediately.
        if (gesture == "wave") OeaPower.syncWakeService(context)
    }

    /**
     * Actions that two gestures may legitimately share, because they are the same
     * kind of thing (doing nothing, or a wake that only the wave can mean).
     */
    private val shareable = setOf("none", "wake")

    /**
     * Other gestures already bound to [action]. Settings shows this before saving,
     * so the user can see the overlap instead of discovering it later, and the
     * tests use it to prove no two touch gestures can end up identical.
     */
    fun conflicts(context: Context, action: String, ignore: String? = null): List<String> {
        if (action in shareable) return emptyList()
        return slots.keys.filter { it != ignore && this.action(context, it) == action }
    }

    /** A gesture repeated faster than this is treated as one. */
    private const val COOLDOWN_MS = 400L
    private val lastRun = ConcurrentHashMap<String, Long>()

    /**
     * Runs the action bound to [gesture]; true when something was done.
     *
     * [homeAction] lets Home perform an action in place (opening its own drawer,
     * for instance) instead of being asked to do it through an intent; the wake
     * service, which runs while Home is not in front, passes nothing and the
     * action is delivered to Home as a home-action intent.
     */
    fun run(
        context: Context,
        gesture: String,
        actionOverride: String? = null,
        homeAction: ((String) -> Boolean)? = null,
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
                else -> {
                    val handled = homeAction?.invoke(action) == true
                    handled || deliverToHome(app, action)
                }
            }
        }
    }

    /** Home is not in front: hand the action to Home as an intent. */
    private fun deliverToHome(context: Context, action: String): Boolean = runCatching {
        context.startActivity(
            Intent(context, OeaLauncherActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .putExtra(OeaLauncherActivity.EXTRA_HOME_ACTION, action)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
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

    /** Shade actions, shared by the swipe binder and the settings rows. */
    fun expandNotifications(context: Context): Boolean = OeaSystemActions.expandNotifications(context)

    fun expandQuickSettings(context: Context): Boolean = OeaSystemActions.expandQuickSettings(context)

    /** True for the two hardware keys OEA may be handed while it is in front. */
    fun isVolumeKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
}

/**
 * Turns a stream of taps into single, double or triple taps without ever letting
 * two of them fire for the same sequence.
 *
 * Android's own [android.view.GestureDetector] reports a double tap as soon as
 * the second tap lands, which makes a triple tap impossible to add without
 * running the double-tap action first. Here every tap only **counts**, and the
 * decision is taken once, [OeaGestures.MULTI_TAP_WINDOW] milliseconds after the
 * last tap: three taps in the window run the triple-tap action, two run the
 * double-tap action, one runs the single-tap action (Home leaves that unbound,
 * because a tap on Home should do nothing).
 */
class OeaTapRouter(private val window: Long = OeaGestures.MULTI_TAP_WINDOW) {
    private var taps = 0
    private var lastTapAt = 0L
    private val pending = Runnable { decide() }

    var onDoubleTap: (() -> Unit)? = null
    var onTripleTap: (() -> Unit)? = null
    var onSingleTap: (() -> Unit)? = null

    /** Call from the touch handler on every confirmed tap. */
    fun tap(now: Long = SystemClock.uptimeMillis()) {
        taps = if (now - lastTapAt > window) 1 else taps + 1
        lastTapAt = now
        // Actually schedule the decision on the caller's handler.
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
