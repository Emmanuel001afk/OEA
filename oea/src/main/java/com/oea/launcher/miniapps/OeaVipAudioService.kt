package com.oea.launcher.miniapps

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import com.oea.launcher.R

/**
 * The notification VIP keeps alive while audio plays with the player hidden.
 *
 * The player itself stays in [OeaVipPlayerActivity] — moving a MediaPlayer into a
 * service would mean two owners of one object. This service does the three jobs that
 * genuinely need a service: it keeps the process interesting to Android while sound
 * is coming out, it publishes a [MediaSession] so the system knows what is playing
 * and where it is, and it puts a media notification on the shade with transport
 * buttons and the cover art.
 *
 * The session is the part that matters beyond the shade. A notification-only player
 * is invisible to the lock screen, to a car's Bluetooth controls, to the media
 * resumption carousel, and to OEA's own Dynamic Island and glance card — all of them
 * read a `Notification.EXTRA_MEDIA_SESSION` token and ask the session for metadata
 * and playback state. The old version of this file published a notification with no
 * session at all, so none of those surfaces could see VIP, and a headset button only
 * worked while a hand-registered broadcast receiver happened to be alive.
 */
class OeaVipAudioService : Service() {

    /** Everything the notification needs to know, answered by the player. */
    interface Control {
        fun status(): Status
        fun togglePlayPause()
        fun next()
        fun previous()
        fun seekTo(positionMs: Long)
        fun skipBy(deltaMs: Long)
        fun dismiss()
    }

    data class Status(
        val title: String,
        val subtitle: String,
        val playing: Boolean,
        val positionMs: Long,
        val durationMs: Long,
        /** The rate the player is actually running at, so the system can extrapolate. */
        val speed: Float = 1f,
        val art: Bitmap? = null
    )

    private var control: Control? = null
    private var session: MediaSession? = null

    /**
     * The player hands its callbacks over once, then calls [publish] whenever the
     * title, position or play state changes. The service never talks to the player
     * directly, so a player that dies takes the notification with it.
     */
    private var lastStatus: Status? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        channel()
        session = runCatching {
            MediaSession(this, SESSION_TAG).apply {
                setCallback(callbacks)
                setSessionActivity(openIntent())
                // An active session receives media buttons and transport commands on
                // its own; MediaSession.setFlags() has been deprecated since API 26
                // because exactly this is the default.
                isActive = true
            }
        }.getOrNull()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        control = pendingControl ?: control
        ensureForeground()
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
        runCatching {
            session?.isActive = false
            session?.release()
        }
        session = null
        control = null
        if (instance === this) instance = null
        running = false
        super.onDestroy()
    }

    /**
     * A service started with `startForegroundService` owes Android a notification
     * within five seconds — even when its first job turns out to be stopping.
     *
     * `stop` and a status-less `update` both went straight to `stopSelf()`, which
     * Android 8 answers with `RemoteServiceException: Context.startForegroundService()
     * did not then call Service.startForeground()`. Nothing in VIP reaches that path
     * today, so this is a hazard removed rather than a bug observed; the placeholder
     * is replaced by the real notification on the same tick.
     */
    private fun ensureForeground() {
        if (Build.VERSION.SDK_INT < 26) return
        if (lastStatus != null) return
        runCatching {
            val note = build(Status(title = "VIP", subtitle = "", playing = false, positionMs = 0L, durationMs = 0L))
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIF, note)
            }
        }
    }

    fun publish(status: Status?) {
        val shown = status ?: run {
            lastStatus = null
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
            return
        }
        lastStatus = shown
        updateSession(shown)
        val note = build(shown)
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIF, note)
            }
        }
    }

    /**
     * What the lock screen, the car and the media carousel read. The position is the
     * position *now*: the framework extrapolates it from [Status.speed] between
     * updates, which is why the player no longer has to nudge this service every few
     * seconds to keep a progress bar honest.
     */
    private fun updateSession(status: Status) {
        val live = session ?: return
        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, status.title.ifBlank { "VIP" })
            .putString(MediaMetadata.METADATA_KEY_ARTIST, status.subtitle)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, status.durationMs.coerceAtLeast(0L))
            .apply { status.art?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) } }
            .build()
        val state = if (status.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        val rate = if (status.playing) status.speed.coerceIn(0.1f, 4f) else 0f
        val playback = PlaybackState.Builder()
            .setActions(ACTIONS)
            .setState(state, status.positionMs.coerceAtLeast(0L), rate)
            .build()
        runCatching { live.setMetadata(metadata) }
        runCatching { live.setPlaybackState(playback) }
        runCatching { live.isActive = running && control != null }
    }

    /** Transport commands from the lock screen, a car, a headset or OEA's own island. */
    private val callbacks = object : MediaSession.Callback() {
        override fun onPlay() = setPlaying(true)
        override fun onPause() = setPlaying(false)
        override fun onSkipToNext() { control?.next() }
        override fun onSkipToPrevious() { control?.previous() }
        override fun onFastForward() { control?.skipBy(10_000L) }
        override fun onRewind() { control?.skipBy(-10_000L) }
        override fun onSeekTo(pos: Long) { control?.seekTo(pos) }
        override fun onStop() { control?.dismiss() }

        /**
         * The system sends play and pause as separate commands, and a stale status
         * (a race with the player's own pause) must not turn one into the other.
         */
        private fun setPlaying(play: Boolean) {
            val player = control ?: return
            val playing = runCatching { player.status().playing }.getOrDefault(false)
            if (playing == play) return
            player.togglePlayPause()
        }
    }

    private fun openIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, OeaVipPlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun build(status: Status): Notification {
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(if (status.playing) R.drawable.oic_play_arrow else R.drawable.oic_pause)
            .setContentTitle(status.title.ifBlank { "VIP" })
            .setContentText(status.subtitle.ifBlank { "Playing" })
            .setContentIntent(openIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setShowWhen(false)
            .setColor(0xFF8AB4F8.toInt())
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .addAction(action(R.drawable.oic_skip_previous, "Previous", command(ACTION_PREVIOUS, 11)))
            .addAction(
                action(
                    if (status.playing) R.drawable.oic_pause else R.drawable.oic_play_arrow,
                    if (status.playing) "Pause" else "Play",
                    command(ACTION_PLAY_PAUSE, 12)
                )
            )
            .addAction(action(R.drawable.oic_skip_next, "Next", command(ACTION_NEXT, 13)))
            .addAction(action(R.drawable.oic_close, "Stop", command(ACTION_STOP, 14)))
        if (status.durationMs > 0L) {
            val seconds = (status.positionMs / 1000L).coerceAtLeast(0L)
            val total = (status.durationMs / 1000L).coerceAtLeast(1L)
            builder.setProgress(100, ((seconds * 100L) / total).toInt().coerceIn(0, 100), false)
            builder.setSubText("${OeaMediaLibrary.formatDuration(status.positionMs)} / ${OeaMediaLibrary.formatDuration(status.durationMs)}")
        }
        status.art?.let { builder.setLargeIcon(it) }
        return builder.build()
    }

    private fun action(icon: Int, title: String, intent: PendingIntent): Notification.Action =
        Notification.Action.Builder(Icon.createWithResource(this, icon), title, intent).build()

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
        private const val SESSION_TAG = "oea-vip"

        const val ACTION_PLAY_PAUSE = "com.oea.launcher.vip.PLAY_PAUSE"
        const val ACTION_NEXT = "com.oea.launcher.vip.NEXT"
        const val ACTION_PREVIOUS = "com.oea.launcher.vip.PREVIOUS"
        const val ACTION_FORWARD = "com.oea.launcher.vip.FORWARD"
        const val ACTION_REWIND = "com.oea.launcher.vip.REWIND"
        const val ACTION_STOP = "com.oea.launcher.vip.STOP"

        /** Everything the shade, the lock screen and a car may ask VIP to do. */
        private val ACTIONS: Long = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP or
            PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_FAST_FORWARD or
            PlaybackState.ACTION_REWIND

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

        /** Called by the player whenever what is playing, or where it is, changes. */
        fun update(context: Context, status: Status?) {
            val alive = instance
            if (alive != null) {
                runCatching { alive.publish(status) }
                return
            }
            if (status != null) launch(context, null, status)
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
