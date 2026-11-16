package com.oea.launcher.miniapps

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import com.oea.launcher.R

/** Keeps an alarm audible until the user snoozes or stops it. */
class OeaClockRingService : Service() {
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var activeId = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        val id = intent.getLongExtra("id", 0L)
        if (ringingId != null && ringingId != id) getSystemService(NotificationManager::class.java)?.cancel(notificationId(activeId))
        stopPlayback()
        activeId = id
        ringingId = id
        ringingIntent = Intent(intent)
        try {
            val notification = notification(this, intent, fallback = false)
            startForeground(notificationId(id), notification)
            val requested = OeaClockApp.ringtoneUri(intent.getStringExtra("sound") ?: "Default")
                ?.let { android.net.Uri.parse(it) }
            val fallback = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            fun play(uri: android.net.Uri): MediaPlayer {
                val p = MediaPlayer()
                try {
                    p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    p.setWakeMode(this@OeaClockRingService, android.os.PowerManager.PARTIAL_WAKE_LOCK)
                    p.setDataSource(this@OeaClockRingService, uri)
                    p.isLooping = true; p.prepare(); p.start()
                    return p
                } catch (error: Exception) { p.release(); throw error }
            }
            player = runCatching { play(requested ?: fallback) }.getOrElse {
                android.util.Log.w("OeaClock", "Chosen alarm sound unavailable; using default", it)
                play(fallback)
            }
            if (intent.getBooleanExtra("vibrate", true)) {
                @Suppress("DEPRECATION")
                vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 500), 0))
            }
        } catch (error: Exception) {
            android.util.Log.e("OeaClock", "Could not play alarm", error)
            @Suppress("DEPRECATION")
            runCatching { stopForeground(true) }
            runCatching { showFallback(this, intent) }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun stopPlayback() {
        runCatching { player?.stop() }; runCatching { player?.release() }; player = null
        runCatching { vibrator?.cancel() }; vibrator = null
    }

    override fun onDestroy() {
        stopPlayback()
        if (ringingId == activeId) { ringingId = null; ringingIntent = null }
        super.onDestroy()
    }

    companion object {
        @Volatile private var ringingId: Long? = null
        @Volatile private var ringingIntent: Intent? = null
        fun notificationId(id: Long): Int = (id.hashCode() and Int.MAX_VALUE).coerceAtLeast(1)
        fun current(): Intent? = ringingIntent?.let { Intent(it) }
        fun respond(context: Context, intent: Intent, dismiss: Boolean) {
            stop(context, intent.getLongExtra("id", 0L))
            context.sendBroadcast(Intent(context, OeaClockSnoozeReceiver::class.java).putExtras(intent).putExtra("dismissOnly", dismiss))
        }
        fun stop(context: Context, id: Long) {
            if (ringingId == id) { ringingId = null; ringingIntent = null; context.stopService(Intent(context, OeaClockRingService::class.java)) }
        }
        fun showFallback(context: Context, intent: Intent) {
            context.getSystemService(NotificationManager::class.java)?.notify(notificationId(intent.getLongExtra("id", 0L)), notification(context, intent, true))
        }
        private fun notification(context: Context, intent: Intent, fallback: Boolean): Notification {
            val id = intent.getLongExtra("id", 0L)
            val sound = intent.getStringExtra("sound") ?: "Default"
            val uri = OeaClockApp.ringtoneUri(sound)?.let { android.net.Uri.parse(it) }
                ?: RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            val channelId = if (fallback) "oea_alarm_fallback_${uri.hashCode()}" else "oea_alarm_ringing_v1"
            val manager = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                manager?.createNotificationChannel(NotificationChannel(channelId, "Clock alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = if (fallback) "Alarm fallback sound" else "Ringing alarms and controls"
                    setSound(if (fallback) uri else null, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
                    enableVibration(fallback && intent.getBooleanExtra("vibrate", true))
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                })
            }
            val immutable = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            fun action(dismiss: Boolean): PendingIntent {
                val target = Intent(context, OeaClockSnoozeReceiver::class.java).putExtras(intent).apply {
                    data = android.net.Uri.parse("oea-clock://action/$id/${if (dismiss) "stop" else "snooze"}")
                    putExtra("dismissOnly", dismiss)
                }
                return PendingIntent.getBroadcast(context, 0, target, immutable)
            }
            val open = PendingIntent.getActivity(context, 0, Intent(context, OeaClockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), immutable)
            val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, channelId) else @Suppress("DEPRECATION") Notification.Builder(context)
            builder.setSmallIcon(R.drawable.ic_oea_launcher_monochrome)
                .setContentTitle(intent.getStringExtra("label")?.ifBlank { "Alarm" } ?: "Alarm")
                .setContentText("Alarm ringing · ${OeaClockApp.soundLabel(context, sound)}")
                .setCategory(Notification.CATEGORY_ALARM).setPriority(Notification.PRIORITY_MAX)
                .setVisibility(Notification.VISIBILITY_PUBLIC).setOngoing(true).setContentIntent(open)
                .addAction(Notification.Action.Builder(null, "Snooze ${intent.getIntExtra("snooze", 10)} min", action(false)).build())
                .addAction(Notification.Action.Builder(null, "Stop", action(true)).build())
            if (Build.VERSION.SDK_INT >= 29) builder.setFullScreenIntent(open, true)
            return builder.build()
        }
    }
}
