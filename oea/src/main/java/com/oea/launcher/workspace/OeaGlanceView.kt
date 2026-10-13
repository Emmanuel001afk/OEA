package com.oea.launcher.workspace

import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.Notification
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.oea.launcher.R
import com.oea.launcher.data.OeaDataStore
import com.oea.launcher.notifications.OeaNotificationState
import com.oea.launcher.ui.OeaPalette
import com.oea.launcher.ui.OeaTheme
import com.oea.launcher.ui.OeaType
import java.util.Calendar
import java.util.Date
import java.util.concurrent.Executors

/**
 * OEA At a Glance: a large clock and date over the wallpaper, followed by live
 * chips for the next alarm, battery, today's screen time and what is playing.
 * Every chip is tappable and opens the matching app.
 */
class OeaGlanceView(context: Context) : LinearLayout(context) {
    private val store = OeaDataStore.get(context)
    private val density = resources.displayMetrics.density
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val clock = TextView(context)
    private val date = TextView(context)
    private val chipsScroll = HorizontalScrollView(context)
    private val chips = LinearLayout(context)
    private var palette: OeaPalette = OeaTheme.resolve(context)
    private var registered = false
    private var batteryLevel = -1
    private var charging = false
    private var screenTimeMillis = -1L
    private var lastScreenTimeQuery = 0L

    var onOpenOverview: (() -> Unit)? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> readBattery(intent)
            }
            refresh()
        }
    }
    private val mediaListener: () -> Unit = { refreshChips() }

    init {
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false
        clock.apply {
            includeFontPadding = false
            letterSpacing = -0.02f
            isClickable = true
            background = null
            setOnClickListener { openClock() }
        }
        date.apply {
            isClickable = true
            setOnClickListener { openCalendar() }
        }
        chips.orientation = HORIZONTAL
        chips.gravity = Gravity.CENTER_VERTICAL
        chipsScroll.apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            addView(chips)
        }
        addView(clock, LayoutParams(-2, -2))
        addView(date, LayoutParams(-2, -2).apply { topMargin = (2 * density).toInt() })
        addView(chipsScroll, LayoutParams(-1, -2).apply { topMargin = (10 * density).toInt() })
        applyStyle()
    }

    fun setPalette(value: OeaPalette) {
        palette = value
        applyStyle()
        refresh()
    }

    fun applyStyle() {
        val style = store.glanceStyle()
        visibility = if (style == "hidden") GONE else VISIBLE
        val large = style == "large"
        clock.visibility = if (large) VISIBLE else GONE
        clock.textSize = 64f
        clock.typeface = OeaType.light
        date.textSize = if (large) 16f else 22f
        date.typeface = if (large) OeaType.medium else OeaType.regular
        listOf(clock, date).forEach {
            it.setTextColor(palette.homeText)
            it.setShadowLayer(6f * density, 0f, 1.5f * density, palette.homeShadow)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    fun start() {
        if (registered) return
        registered = true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)
        }
        val sticky = runCatching {
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else context.registerReceiver(receiver, filter)
        }.getOrNull()
        sticky?.let { if (it.action == Intent.ACTION_BATTERY_CHANGED) readBattery(it) }
        OeaNotificationState.addListener(mediaListener)
        refresh()
    }

    fun stop() {
        if (!registered) return
        registered = false
        runCatching { context.unregisterReceiver(receiver) }
        OeaNotificationState.removeListener(mediaListener)
    }

    /** Called on every Home resume. */
    fun refresh() {
        if (visibility == GONE) return
        val now = Date()
        clock.text = DateFormat.format(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm", now)
        val large = store.glanceStyle() == "large"
        date.text = DateFormat.format(if (large) "EEEE, MMMM d" else "EEE, MMM d", now)
        refreshChips()
        maybeQueryScreenTime()
    }

    private fun readBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        batteryLevel = if (level >= 0) level * 100 / scale else -1
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun refreshChips() {
        chips.removeAllViews()
        nextAlarm()?.let { (label, intent) -> addChip(R.drawable.oic_alarm, label) { start(intent) } }
        if (batteryLevel >= 0 && (charging || batteryLevel <= 20)) {
            val label = if (charging) "$batteryLevel% • Charging" else "$batteryLevel% battery"
            addChip(if (charging) R.drawable.oic_battery_charging_full else R.drawable.oic_battery_full, label) {
                start(Intent(Intent.ACTION_POWER_USAGE_SUMMARY))
            }
        }
        mediaChip()
        if (screenTimeMillis >= 60_000L) {
            addChip(R.drawable.oic_timer, "Screen time ${formatDuration(screenTimeMillis)}") { onOpenOverview?.invoke() }
        }
        chipsScroll.visibility = if (chips.childCount == 0) GONE else VISIBLE
    }

    private fun nextAlarm(): Pair<String, Intent>? {
        val alarm = runCatching { context.getSystemService(AlarmManager::class.java)?.nextAlarmClock }.getOrNull() ?: return null
        val inDay = alarm.triggerTime - System.currentTimeMillis()
        if (inDay <= 0 || inDay > 24L * 60 * 60 * 1000) return null
        val time = DateFormat.format(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a", alarm.triggerTime).toString()
        return time to Intent(AlarmClock.ACTION_SHOW_ALARMS)
    }

    private fun mediaChip() {
        val sbn = OeaNotificationState.media() ?: return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val artist = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank()) return
        val token = runCatching {
            if (Build.VERSION.SDK_INT >= 33) extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
            else @Suppress("DEPRECATION") (extras.getParcelable(Notification.EXTRA_MEDIA_SESSION) as? MediaSession.Token)
        }.getOrNull()
        val controller = token?.let { runCatching { MediaController(context, it) }.getOrNull() }
        val playing = controller?.playbackState?.state == PlaybackState.STATE_PLAYING
        val label = if (artist.isBlank()) title else "$title • $artist"
        val chip = addChip(R.drawable.oic_music_note, label) {
            sbn.notification.contentIntent?.let { OeaPendingIntents.send(context, it) }
        }
        if (controller != null) {
            val toggle = ImageView(context).apply {
                setImageDrawable(context.getDrawable(if (playing) R.drawable.oic_pause else R.drawable.oic_play_arrow)?.mutate()?.apply { setTint(palette.homeText) })
                contentDescription = if (playing) "Pause" else "Play"
                setPadding(dp(4), dp(4), dp(4), dp(4))
                setOnClickListener {
                    if (playing) controller.transportControls.pause() else controller.transportControls.play()
                    main.postDelayed({ refreshChips() }, 350L)
                }
            }
            chip.addView(toggle, LayoutParams(dp(30), dp(30)).apply { leftMargin = dp(4) })
            val next = ImageView(context).apply {
                setImageDrawable(context.getDrawable(R.drawable.oic_skip_next)?.mutate()?.apply { setTint(palette.homeText) })
                contentDescription = "Next track"
                setPadding(dp(4), dp(4), dp(4), dp(4))
                setOnClickListener { controller.transportControls.skipToNext() }
            }
            chip.addView(next, LayoutParams(dp(30), dp(30)))
        }
    }

    private fun addChip(icon: Int, label: String, onClick: () -> Unit): LinearLayout {
        val chip = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(10), dp(6))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(palette.glass)
                setStroke(maxOf(1, dp(1) / 2), palette.glassStroke)
            }
            isClickable = true
            setOnClickListener { onClick() }
        }
        chip.addView(ImageView(context).apply {
            setImageDrawable(context.getDrawable(icon)?.mutate()?.apply { setTint(palette.homeText) })
        }, LayoutParams(dp(16), dp(16)).apply { rightMargin = dp(7) })
        chip.addView(TextView(context).apply {
            text = label
            textSize = 13f
            typeface = OeaType.medium
            maxLines = 1
            maxWidth = dp(220)
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(palette.homeText)
        })
        chips.addView(chip, LayoutParams(-2, dp(34)).apply { rightMargin = dp(8) })
        return chip
    }

    private fun maybeQueryScreenTime() {
        val now = System.currentTimeMillis()
        if (now - lastScreenTimeQuery < 60_000L) return
        lastScreenTimeQuery = now
        if (!hasUsageAccess()) {
            screenTimeMillis = -1L
            return
        }
        val launcher = context.packageName
        io.execute {
            val start = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val total = runCatching {
                val usm = context.getSystemService(UsageStatsManager::class.java)
                usm.queryAndAggregateUsageStats(start, System.currentTimeMillis()).values
                    .filter { it.packageName != launcher && !it.packageName.startsWith("com.android.systemui") }
                    .sumOf { it.totalTimeInForeground }
            }.getOrDefault(-1L)
            main.post {
                screenTimeMillis = total
                refreshChips()
            }
        }
    }

    private fun hasUsageAccess(): Boolean = runCatching {
        context.getSystemService(AppOpsManager::class.java).checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName,
        ) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    private fun formatDuration(ms: Long): String {
        val minutes = ms / 60_000L
        val h = minutes / 60
        val m = minutes % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    private fun openClock() {
        start(Intent(AlarmClock.ACTION_SHOW_ALARMS))
    }

    private fun openCalendar() {
        val uri = CalendarContract.CONTENT_URI.buildUpon().appendPath("time").appendPath(System.currentTimeMillis().toString()).build()
        if (!start(Intent(Intent.ACTION_VIEW).setData(uri))) {
            start(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR))
        }
    }

    private fun start(intent: Intent): Boolean = runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    private fun dp(v: Int) = (v * density + 0.5f).toInt()
}

/** Sends a PendingIntent from OEA Home with OEA's foreground launch privilege (Android 14+). */
object OeaPendingIntents {
    fun send(context: Context, intent: android.app.PendingIntent): Boolean = runCatching {
        val options = if (Build.VERSION.SDK_INT >= 34) {
            @Suppress("DEPRECATION")
            android.app.ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                .toBundle()
        } else null
        intent.send(context, 0, null, null, null, null, options)
        true
    }.getOrDefault(false)
}
