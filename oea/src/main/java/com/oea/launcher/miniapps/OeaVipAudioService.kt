package com.oea.launcher.miniapps

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
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import com.oea.launcher.R

/**
 * The notification VIP keeps alive while audio plays with the player hidden.
 *
 * The player itself stays in [OeaVipPlayerActivity] — moving a MediaPlayer into a
 * service would mean two owners of one object. This service only does the two jobs
 * that genuinely need a service: it keeps the process interesting to Android while
 * sound is coming out, and it puts transport buttons on the notification shade and
 * the headset button.
 */
class OeaVipAudioService : Service() {

    /** Everything the notification needs to know, answered by the player. */
    interface Control {
        fun status(): Status
        fun togglePlayPause()
        fun next()
        fun previous()
        fun skipBy(deltaMs: Long)
        fun dismiss()
    }

    data class Status(
        val title: String,
        val subtitle: String,
        val playing: Boolean,
        val positionMs: Long,
        val durationMs: Long,
        val art: Bitmap? = null
    )

    private val buttonReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_MEDIA_BUTTON) return
            val event = runCatching { intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) }.getOrNull() ?: return
            if (event.action != KeyEvent.ACTION_DOWN) return
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> control?.togglePlayPause()
                KeyEvent.KEYCODE_MEDIA_PLAY -> if (control?.status()?.playing != true) control?.togglePlayPause()
                KeyEvent.KEYCODE_MEDIA_PAUSE -> if (control?.status()?.playing == true) control?.togglePlayPause()
                KeyEvent.KEYCODE_MEDIA_NEXT -> control?.next()
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> control?.previous()
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> control?.skipBy(10_000L)
                KeyEvent.KEYCODE_MEDIA_REWIND -> control?.skipBy(-10_000L)
                KeyEvent.KEYCODE_MEDIA_STOP -> control?.dismiss()
                else -> return
            }
            runCatching { abortBroadcast() }
        }
    }

    private var control: Control? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        channel()
        runCatching {
            val filter = IntentFilter(Intent.ACTION_MEDIA_BUTTON)
            filter.priority = IntentFilter.SYSTEM_HIGH_PRIORITY
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(buttonReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(buttonReceiver, filter)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        control = pendingControl ?: control
        when (intent?.action) {
            ACTION_STOP -> {
                runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_PLAY_PAUSE -> control?.togglePlayPause()
            ACTION_NEXT -> control?.next()
            ACTION_PREVIOUS -> control?.previous()
            ACTION_FORWARD -> control?.skipBy(10_000L)
            ACTION_REWIND -> control?.skipBy(-10_000L)
        }
        publish(runCatching { control?.status() }.getOrNull() ?: lastStatus)
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(buttonReceiver) }
        control = null
        if (instance === this) instance = null
        running = false
        super.onDestroy()
    }

    /**
     * The player hands its callbacks over once, then calls [publish] whenever the
     * title, position or play state changes. The service never talks to the
     * player directly, so a player that dies takes the notification with it.
     */
    private var lastStatus: Status? = null

    fun publish(status: Status?) {
        val shown = status ?: run {
            lastStatus = null
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
            return
        }
        lastStatus = shown
        val note = build(shown)
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIF, note)
            }
        }
    }

    private fun build(status: Status): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, OeaVipPlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(if (status.playing) R.drawable.ic_oea_video else R.drawable.ic_oea_video)
            .setContentTitle(status.title.ifBlank { "VIP" })
            .setContentText(if (status.subtitle.isNullOrBlank()) "Playing" else status.subtitle)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setShowWhen(false)
            .setColor(0xFF8AB4F8.toInt())
            .addAction(
                NotificationCompat.Action(
                    R.drawable.oic_skip_previous, "Previous",
                    command(ACTION_PREVIOUS, 11)
                )
            )
            .addAction(
                NotificationCompat.Action(
                    if (status.playing) R.drawable.oic_pause else R.drawable.oic_play_arrow,
                    if (status.playing) "Pause" else "Play",
                    command(ACTION_PLAY_PAUSE, 12)
                )
            )
            .addAction(
                NotificationCompat.Action(
                    R.drawable.oic_skip_next, "Next",
                    command(ACTION_NEXT, 13)
                )
            )
            .addAction(
                NotificationCompat.Action(
                    R.drawable.oic_close, "Stop",
                    command(ACTION_STOP, 14)
                )
            )
        if (status.durationMs > 0L) {
            val seconds = (status.positionMs / 1000L).coerceAtLeast(0L)
            val total = (status.durationMs / 1000L).coerceAtLeast(1L)
            builder.setProgress(100, ((seconds * 100L) / total).toInt().coerceIn(0, 100), false)
            builder.setSubText("${OeaMediaLibrary.formatDuration(status.positionMs)} / ${OeaMediaLibrary.formatDuration(status.durationMs)}")
        }
        status.art?.let { builder.setLargeIcon(it) }
        builder.setStyle(NotificationCompat.InboxStyle().addLine(status.title))
        return builder.build()
    }

    private fun command(action: String, code: Int): PendingIntent = PendingIntent.getService(
        this, code, Intent(this, OeaVipAudioService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun channel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "VIP playback", NotificationManager.IMPORTANCE_LOW).apply {
                description = "The controls VIP shows while music or a recording plays in the background"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }
        )
    }

    companion object {
        private const val CHANNEL = "oea_vip_audio"
        private const val NOTIF = 4311
        const val ACTION_PLAY_PAUSE = "com.oea.launcher.vip.PLAY_PAUSE"
        const val ACTION_NEXT = "com.oea.launcher.vip.NEXT"
        const val ACTION_PREVIOUS = "com.oea.launcher.vip.PREVIOUS"
        const val ACTION_FORWARD = "com.oea.launcher.vip.FORWARD"
        const val ACTION_REWIND = "com.oea.launcher.vip.REWIND"
        const val ACTION_STOP = "com.oea.launcher.vip.STOP"

        /**
         * The service cannot be handed an object through an Intent, so the player
         * publishes itself here just before it starts the service.
         */
        @Volatile
        internal var pendingControl: Control? = null

        @Volatile
        private var instance: OeaVipAudioService? = null

        /** True while the shade notification is up, so the player stops nudging. */
        @Volatile
        var running: Boolean = false
            private set

        fun start(context: Context, control: Control, status: Status) {
            pendingControl = control
            running = true
            launch(context, null, status)
        }

        /** Called at most once a second by the player; the service coalesces it. */
        fun update(context: Context, status: Status?) {
            val alive = instance
            if (alive != null) {
                runCatching { alive.publish(status) }
                return
            }
            if (status != null) launch(context, null, status)
        }

        fun command(context: Context, action: String) {
            launch(context, action, null)
        }

        private fun launch(context: Context, action: String?, status: Status?) {
            val intent = Intent(context, OeaVipAudioService::class.java).apply {
                if (action != null) setAction(action)
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
            }
        }

        fun stop(context: Context) {
            pendingControl = null
            running = false
            runCatching { context.stopService(Intent(context, OeaVipAudioService::class.java)) }
        }
    }
}
