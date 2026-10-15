package com.oea.launcher.phone

import android.Manifest
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import androidx.core.app.NotificationCompat
import com.oea.launcher.R
import com.oea.launcher.callblocker.OeaCallBlockRules
import java.lang.ref.WeakReference

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
            if (state == Call.STATE_DISCONNECTED) cancelCallNotification() else showCallNotification(call)
        }

        override fun onDetailsChanged(call: Call, details: Call.Details) {
            OeaCallState.notifyChanged()
        }
    }

    override fun onCreate() {
        com.oea.launcher.fault.OeaFaultShield.guard("phone:incall-create") {
            super.onCreate()
            OeaCallState.attachService(this)
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
        showCallNotification(call)
        // The chosen presentation (full screen, popup or floating card). When
        // Android blocks a background start, the full-screen intent of the
        // call notification shows the call screen instead.
        runCatching { startActivity(OeaCallUi.intent(this)) }
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
            cancelCallNotification()
        }
        if (OeaCallState.current == call) OeaCallState.current = null
        super.onCallRemoved(call)
    }

    @Deprecated("Deprecated in Android 14, still the route source on older releases")
    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        @Suppress("DEPRECATION")
        super.onCallAudioStateChanged(audioState)
        OeaCallState.audioState = audioState
        OeaCallState.notifyChanged()
    }

    // ============================================================ notifications

    /**
     * Incoming calls get a heads-up/full-screen notification with Answer and
     * Decline (so the call can always be picked up, even when Android blocks
     * opening the call screen from the background); active calls keep an
     * ongoing notification with Hang up that reopens the call screen.
     * Telecom plays the ringtone, so these channels are silent.
     */
    private fun showCallNotification(call: Call) {
        val state = runCatching { call.state }.getOrNull() ?: return
        if (state == Call.STATE_DISCONNECTED) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        ensureChannels(manager)
        val ringing = state == Call.STATE_RINGING
        val caller = callerName(call)
        val open = PendingIntent.getActivity(
            this, 1, OeaCallUi.intent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
)
        val visibleNow = OeaCallUi.screenVisible
        val builder = NotificationCompat.Builder(this, if (ringing && !visibleNow) CHANNEL_INCOMING else CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.oic_call)
            .setContentTitle(caller)
            .setContentText(
                when (state) {
                    Call.STATE_RINGING -> "Incoming call"
                    Call.STATE_DIALING, Call.STATE_CONNECTING -> "Calling…"
                    Call.STATE_HOLDING -> "On hold"
                    else -> "Ongoing call"
                }
)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setOnlyAlertOnce(!ringing)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .setSilent(true)
        if (ringing) {
            builder.setPriority(NotificationCompat.PRIORITY_MAX)
            builder.setFullScreenIntent(open, true)
            builder.addAction(R.drawable.oic_call_end, "Decline", actionIntent(OeaCallActionReceiver.ACTION_DECLINE, 2))
            // Answer opens the call screen directly (an activity intent): Android 12+
            // does not let a broadcast from a notification action open an activity.
            val answer = PendingIntent.getActivity(
                this, 3, OeaCallUi.intent(this).putExtra(OeaCallUi.EXTRA_ANSWER, true),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
)
            builder.addAction(R.drawable.oic_call, "Answer", answer)
        } else {
            builder.setPriority(NotificationCompat.PRIORITY_LOW)
            if (state == Call.STATE_ACTIVE) {
                val connect = runCatching { call.details?.connectTimeMillis }.getOrNull()?.takeIf { it > 0L }
                if (connect != null) builder.setWhen(connect).setUsesChronometer(true).setShowWhen(true)
            }
            builder.addAction(R.drawable.oic_call_end, "Hang up", actionIntent(OeaCallActionReceiver.ACTION_HANG_UP, 4))
            builder.addAction(R.drawable.oic_volume_up, if (OeaCallState.route() == CallAudioState.ROUTE_SPEAKER) "Earpiece" else "Speaker",
                actionIntent(OeaCallActionReceiver.ACTION_SPEAKER, 5))
        }
        runCatching { manager.notify(NOTIFICATION_ID, builder.build()) }
    }

    private fun actionIntent(action: String, requestCode: Int): PendingIntent = PendingIntent.getBroadcast(
        this, requestCode, Intent(this, OeaCallActionReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
)

    private fun cancelCallNotification() {
        runCatching { getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID) }
    }

    /** Re-post after the call screen appears or closes, so the heads-up only shows when needed. */
    internal fun refreshNotification() {
        val call = observed ?: return
        showCallNotification(call)
    }

    private fun ensureChannels(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < 26) return
        if (manager.getNotificationChannel(CHANNEL_INCOMING) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_INCOMING, "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Shows incoming calls with Answer and Decline"
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            })
        }
        if (manager.getNotificationChannel(CHANNEL_ONGOING) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ONGOING, "Ongoing calls", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Keeps the current call one tap away"
                setSound(null, null)
                enableVibration(false)
            })
        }
    }

    private fun callerName(call: Call): String {
        val number = OeaCallUi.displayNumber(call)
        return OeaCallUi.contactName(this, number) ?: number
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
        cancelCallNotification()
        observed?.unregisterCallback(callback)
        observed = null
        OeaCallState.detachService(this)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_INCOMING = "oea_calls_incoming"
        private const val CHANNEL_ONGOING = "oea_calls_ongoing"
        private const val NOTIFICATION_ID = 4210
    }
}

/** Answer / Decline / Hang up / Speaker from the call notification. */
class OeaCallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val call = OeaCallState.current ?: return
        when (intent.action) {
            ACTION_ANSWER -> runCatching { call.answer(VideoProfile.STATE_AUDIO_ONLY) }
            ACTION_DECLINE -> runCatching { call.reject(false, null) }
            ACTION_HANG_UP -> runCatching { call.disconnect() }
            ACTION_SPEAKER -> OeaCallState.toggleSpeaker()
        }
    }

    companion object {
        const val ACTION_ANSWER = "com.oea.launcher.phone.ANSWER"
        const val ACTION_DECLINE = "com.oea.launcher.phone.DECLINE"
        const val ACTION_HANG_UP = "com.oea.launcher.phone.HANG_UP"
        const val ACTION_SPEAKER = "com.oea.launcher.phone.SPEAKER"
    }
}

/** Observable state from OEA's default-dialer connection for the Home capsule and call screens. */
object OeaCallState {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()
    private var service: WeakReference<OeaInCallService>? = null

    @Volatile
    var current: Call? = null
        set(value) {
            field = value
            notifyChanged()
        }

    /** Latest audio route/mute state reported by Telecom. */
    @Volatile
    var audioState: CallAudioState? = null

    internal fun attachService(value: OeaInCallService) {
        service = WeakReference(value)
    }

    internal fun detachService(value: OeaInCallService) {
        if (service?.get() === value) service = null
        audioState = null
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

    /** Telecom's audio state: the last callback value, else asked from the service directly. */
    @Suppress("DEPRECATION")
    private fun currentAudio(): CallAudioState? =
        audioState ?: service?.get()?.let { svc -> runCatching { svc.callAudioState }.getOrNull() }

    fun isMuted(): Boolean = currentAudio()?.isMuted == true

    fun route(): Int = currentAudio()?.route ?: CallAudioState.ROUTE_EARPIECE

    fun supportedRoutes(): Int = currentAudio()?.supportedRouteMask ?: (CallAudioState.ROUTE_EARPIECE or CallAudioState.ROUTE_SPEAKER)

    fun hasRoute(route: Int): Boolean = (supportedRoutes() and route) != 0

    /** Mute through Telecom; AudioManager's microphone mute is overridden during calls. */
    fun setMuted(muted: Boolean): Boolean {
        val svc = service?.get() ?: return false
        return runCatching { svc.setMuted(muted); true }.getOrDefault(false)
    }

    /** Route audio through Telecom (earpiece, speaker, Bluetooth or wired headset). */
    @Suppress("DEPRECATION")
    fun setRoute(route: Int): Boolean {
        val svc = service?.get() ?: return false
        return runCatching { svc.setAudioRoute(route); true }.getOrDefault(false)
    }

    /** Speaker on/off; "off" returns to Bluetooth or a headset when one is connected. */
    fun toggleSpeaker(): Boolean {
        val target = if (route() == CallAudioState.ROUTE_SPEAKER) {
            when {
                hasRoute(CallAudioState.ROUTE_BLUETOOTH) -> CallAudioState.ROUTE_BLUETOOTH
                hasRoute(CallAudioState.ROUTE_WIRED_HEADSET) -> CallAudioState.ROUTE_WIRED_HEADSET
                else -> CallAudioState.ROUTE_EARPIECE
            }
        } else CallAudioState.ROUTE_SPEAKER
        return setRoute(target)
    }

    internal fun refreshNotification() {
        main.post { service?.get()?.refreshNotification() }
    }
}

/**
 * Picks the call screen for the user's choice in Phone › Call display:
 * full screen (over the wallpaper), a popup over the current app, or a compact
 * floating card at the top. Locked phones always get the full-screen screen.
 */
object OeaCallUi {
    const val FULLSCREEN = "fullscreen"
    const val POPUP = "popup"
    const val COMPACT = "compact"
    const val EXTRA_PRESENTATION = "oea_call_presentation"
    /** Set by the notification's Answer action: the call screen answers when it opens. */
    const val EXTRA_ANSWER = "oea_call_answer"

    /** True while a call screen is in front; incoming notifications then skip the heads-up. */
    @Volatile
    var screenVisible = false
        internal set

    fun mode(context: Context): String =
        context.getSharedPreferences("oea_phone_prefs", Context.MODE_PRIVATE).getString("call_ui_mode", FULLSCREEN)
            ?.takeIf { it == FULLSCREEN || it == POPUP || it == COMPACT } ?: FULLSCREEN

    fun intent(context: Context, presentation: String = mode(context)): Intent {
        val locked = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        val effective = if (locked) FULLSCREEN else presentation
        val target = if (effective == FULLSCREEN) OeaInCallActivity::class.java else OeaInCallPopupActivity::class.java
        return Intent(context, target)
            .putExtra(EXTRA_PRESENTATION, effective)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
    }

    fun displayNumber(call: Call): String {
        val handle = runCatching { call.details?.handle }.getOrNull() ?: return "Unknown caller"
        val ssp = handle.schemeSpecificPart.orEmpty()
        val frag = handle.fragment
        val full = if (frag != null) "$ssp#$frag" else ssp
        return full.ifBlank { "Unknown caller" }
    }

    fun contactName(context: Context, number: String): String? {
        if (number.isBlank() || number == "Unknown caller" || number.startsWith("*") || number.startsWith("#")) return null
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return null
        val uri = android.net.Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, android.net.Uri.encode(number))
        return runCatching {
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
            }
        }.getOrNull()
    }

    /** Silences the ringtone (default dialers may do this) without declining the call. */
    fun silenceRinger(context: Context) {
        runCatching { context.getSystemService(TelecomManager::class.java)?.silenceRinger() }
    }
}
