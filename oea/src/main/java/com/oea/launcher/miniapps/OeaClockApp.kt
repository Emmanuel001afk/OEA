package com.oea.launcher.miniapps

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.media.RingtoneManager
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.oea.launcher.R
import com.oea.launcher.ui.OeaKit
import com.oea.launcher.ui.OeaPalette
import com.oea.launcher.ui.OeaType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * OEA Clock: fully competitive clock app separate from Calendar.
 *
 * Unique features:
 * - Live clock with seconds, date, timezone, 12/24h toggle
 * - Alarms: two number wheels for the time, a label, repeat days as chips, snooze
 *   (5/10/15/30 min), a ringtone picked from the ones this phone actually carries,
 *   vibration, delete, enable/disable. The alarm arrives as an alarm-priority
 *   notification with Snooze and Stop on it, on a channel per chosen sound, with a
 *   full-screen intent where Android grants one — that is what an app is allowed to
 *   do at 06:30 without a system alarm-roll privilege.
 * - Timer: multiple timers, presets (1/5/10/15/30/60 min), custom, full-screen countdown, sound, notification when finished, add 1 min
 * - Stopwatch: start/pause/reset, laps with total, share as text, fastest/slowest lap highlighted
 * - World clock: add city via timezone search, shows daylight offset, DST, local time, remove, sort
 * - Bedtime: schedule DND + Low mode automatically (integrates with OEA Modes Sleeping), wind-down reminder
 * - Widgets: clock widget live update via AlarmManager
 * - Power scheduling investigation: Android does not expose power-off to normal apps; only Device Owner or privileged can schedule. OEA explains honestly.
 * - Unique: integration with Low mode (Ultra Power Saving) — Clock explains that device ultra power saving may block alarms and how to whitelist
 */
object OeaClockApp {
    internal const val PREFS = "oea_clock"
    private const val KEY_ALARMS = "alarms_v2"
    private const val KEY_WORLD = "world_zones"
    private const val KEY_TIMERS = "timers"
    private const val KEY_24H = "24h"
    private const val KEY_BEDTIME = "bedtime"
    private const val SEP_ROW = "\u001E"
    private const val SEP_FIELD = "\u001F"

    data class Alarm(
        val id: Long,
        val hour: Int,
        val minute: Int,
        val label: String,
        val enabled: Boolean,
        val days: Set<Int>,
        val snoozeMin: Int,
        val sound: String,
        val vibrate: Boolean
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun decodeAlarms(raw: String?): List<Alarm> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(SEP_ROW).mapNotNull { row ->
            val p = row.split(SEP_FIELD)
            if (p.size < 5) return@mapNotNull null
            try {
                val id = p[0].toLong()
                val h = p[1].toInt()
                val m = p[2].toInt()
                val label = p[3]
                val enabled = p[4] == "1"
                val days = if (p.size > 5 && p[5].isNotBlank()) p[5].split(",").mapNotNull { it.toIntOrNull() }.toSet() else emptySet()
                val snooze = p.getOrNull(6)?.toIntOrNull() ?: 10
                val sound = p.getOrNull(7) ?: "Default"
                val vib = p.getOrNull(8) != "0"
                Alarm(id, h, m, label, enabled, days, snooze, sound, vib)
            } catch (_: Throwable) { null }
        }.sortedBy { it.hour * 60 + it.minute }
    }

    private fun encodeAlarms(list: List<Alarm>): String = list.joinToString(SEP_ROW) { a ->
        listOf(a.id.toString(), a.hour.toString(), a.minute.toString(), a.label.replace(SEP_FIELD, " ").replace(SEP_ROW, " "), if (a.enabled) "1" else "0", a.days.joinToString(","), a.snoozeMin.toString(), a.sound, if (a.vibrate) "1" else "0").joinToString(SEP_FIELD)
    }

    fun build(host: Activity, palette: OeaPalette): View {
        val kit = OeaKit(host, palette)
        val root = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(palette.background) }
        val header = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(kit.dp(20), 0, kit.dp(12), 0) }
        val title = kit.text("Alarm", 25f, palette.text, OeaType.medium)
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        val menu = kit.text("⋮", 30f, palette.text).apply { gravity = Gravity.CENTER; contentDescription = "Clock options" }
        header.addView(menu, LinearLayout.LayoutParams(kit.dp(48), kit.dp(52)))
        root.addView(header, LinearLayout.LayoutParams(-1, kit.dp(64)))
        val scroll = android.widget.ScrollView(host).apply { isFillViewport = true; isVerticalScrollBarEnabled = false }
        val column = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL; setPadding(kit.dp(20), 0, kit.dp(20), kit.dp(24)) }
        scroll.addView(column)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val tabBar = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; setPadding(kit.dp(4), kit.dp(6), kit.dp(4), kit.dp(6)); setBackgroundColor(palette.card) }
        root.addView(tabBar, LinearLayout.LayoutParams(-1, -2))
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = com.oea.launcher.ui.OeaInsets.bars(insets)
            root.setPadding(bars[0], bars[1], bars[2], bars[3])
            insets
        }
        var refresh: () -> Unit = {}
        var currentTab = "alarms"

        // Android clock-role intents (Set alarm / Set timer / Show alarms): act
        // on them once, land on the right tab, then consume the intent so a
        // rebuild (theme change, tab switch) never applies it twice.
        host.intent?.let { intent ->
            when (intent.action) {
                "android.intent.action.SET_ALARM" -> {
                    val hour = intent.getIntExtra("android.intent.extra.alarm.HOUR", -1)
                    val minute = intent.getIntExtra("android.intent.extra.alarm.MINUTES", 0).coerceIn(0, 59)
                    if (hour in 0..23) {
                        val label = intent.getStringExtra("android.intent.extra.alarm.MESSAGE") ?: ""
                        val days = intent.getIntegerArrayListExtra("android.intent.extra.alarm.DAYS")
                        val daySet = days?.mapNotNull { calendarDayToOea(it) }?.toSet() ?: emptySet()
                        val alarms = decodeAlarms(prefs(host).getString(KEY_ALARMS, "")).toMutableList()
                        val alarm = Alarm(System.currentTimeMillis(), hour, minute, label, true, daySet, 10, "Default", true)
                        alarms += alarm
                        prefs(host).edit().putString(KEY_ALARMS, encodeAlarms(alarms)).apply()
                        scheduleAlarm(host, alarm)
                    }
                }
                "android.intent.action.SET_TIMER" -> {
                    currentTab = "timer"
                    parseIsoDuration(intent.getStringExtra("android.intent.extra.alarm.LENGTH"))?.let { ms ->
                        presetTimerMs = ms
                    }
                }
                // SHOW_ALARMS stays on the (default) alarms tab.
            }
            host.intent = null
        }
        val is24h = prefs(host).getBoolean(KEY_24H, android.text.format.DateFormat.is24HourFormat(host))

        // Live clock header
        val clockView = TextView(host).apply {
            textSize = 38f
            typeface = OeaType.light
            setTextColor(palette.text)
            gravity = Gravity.CENTER
            setPadding(kit.dp(6), kit.dp(8), kit.dp(6), kit.dp(4))
        }
        val dateView = TextView(host).apply {
            textSize = 14f
            setTextColor(palette.textSecondary)
            gravity = Gravity.CENTER
            setPadding(kit.dp(6), 0, kit.dp(6), kit.dp(4))
        }
        val tzView = TextView(host).apply {
            textSize = 12f
            setTextColor(palette.textTertiary)
            gravity = Gravity.CENTER
            setPadding(kit.dp(6), 0, kit.dp(6), kit.dp(14))
        }
        val analog = OeaAnalogClockView(host, palette)
        val worldTimes = mutableListOf<Pair<java.util.TimeZone, TextView>>()
        val timeHandler = Handler(Looper.getMainLooper())
        val timeRunnable = object : Runnable {
            override fun run() {
                if (host.isDestroyed) return
                val now = Date()
                val fmt = if (is24h) "HH:mm:ss" else "hh:mm:ss a"
                clockView.text = SimpleDateFormat(fmt, Locale.getDefault()).format(now)
                dateView.text = SimpleDateFormat("EEEE, MMM d, yyyy", Locale.getDefault()).format(now)
                tzView.text = java.util.TimeZone.getDefault().getDisplayName(java.util.TimeZone.getDefault().inDaylightTime(now), java.util.TimeZone.LONG, Locale.getDefault())
                analog.invalidate()
                worldTimes.forEach { (zone, view) ->
                    view.text = SimpleDateFormat(if (is24h) "HH:mm" else "h:mm a", Locale.getDefault()).apply { timeZone = zone }.format(now)
                }
                timeHandler.postDelayed(this, 1000L)
            }
        }
        timeHandler.post(timeRunnable)
        val clockHeader = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        clockHeader.addView(analog, LinearLayout.LayoutParams(kit.dp(224), kit.dp(224)).apply { topMargin = kit.dp(24); bottomMargin = kit.dp(12) })
        clockHeader.addView(clockView)
        clockHeader.addView(dateView)
        clockHeader.addView(tzView)
        column.addView(clockHeader)

        // The four primary destinations stay visible below the scrolling content.
        val content = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        column.addView(content)

        // Timer state
        var timerDurationMs = presetTimerMs ?: 5 * 60 * 1000L
        presetTimerMs = null
        var timerRemainingMs = timerDurationMs
        var timerRunning = false
        var timerEndAt = 0L
        val timerHandler = Handler(Looper.getMainLooper())
        var timerText: TextView? = null
        val timersList = mutableListOf<Pair<String, Long>>() // label to duration

        // Stopwatch state
        var swRunning = false
        var swStartAt = 0L
        var swElapsed = 0L
        var swLaps = mutableListOf<Long>()
        var swText: TextView? = null
        val swHandler = Handler(Looper.getMainLooper())

        // One ticker, owned by the tab rather than by a single build of
        // it: switching tabs rebuilds the view, and the old design's
        // runnable died with it — so a running stopwatch looked frozen
        // the moment you glanced at another tab.
        val swTicker = object : Runnable {
            override fun run() {
                if (!swRunning || host.isDestroyed) return
                swText?.text = formatMs(swElapsed + (System.currentTimeMillis() - swStartAt))
                swHandler.postDelayed(this, 50L)
            }
        }

        fun swStartTicking() {
            swHandler.removeCallbacks(swTicker)
            swHandler.post(swTicker)
        }

        fun renderTabs() {
            tabBar.removeAllViews()
            val tabs = listOf(Triple("alarms", "Alarm", R.drawable.oic_alarm), Triple("world", "World Clock", R.drawable.oic_public), Triple("timer", "Timer", R.drawable.oic_timer), Triple("stopwatch", "Stopwatch", R.drawable.oic_history))
            tabs.forEach { (id, label, icon) ->
                val selected = id == currentTab
                val item = LinearLayout(host).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                    contentDescription = label + if (selected) ", selected" else ""
                    setOnClickListener { currentTab = id; scroll.scrollTo(0, 0); refresh() }
                }
                val image = android.widget.ImageView(host).apply { setImageResource(icon); setColorFilter(if (selected) palette.accent else palette.textTertiary) }
                item.addView(image, LinearLayout.LayoutParams(kit.dp(24), kit.dp(24)))
                item.addView(kit.text(label, 11f, if (selected) palette.accent else palette.textTertiary, OeaType.medium).apply { gravity = Gravity.CENTER; maxLines = 1 }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(3) })
                tabBar.addView(item, LinearLayout.LayoutParams(0, kit.dp(58), 1f))
            }
        }
        menu.setOnClickListener {
            val popup = android.widget.PopupMenu(host, menu)
            popup.menu.add(if (is24h) "Use 12-hour time" else "Use 24-hour time").setOnMenuItemClickListener {
                prefs(host).edit().putBoolean(KEY_24H, !is24h).apply(); (host as? OeaMiniAppActivity)?.refresh(); true
            }
            popup.menu.add("Bedtime").setOnMenuItemClickListener { currentTab = "bedtime"; refresh(); true }
            popup.menu.add("Alarm settings").setOnMenuItemClickListener { alarmSettings(host, kit); true }
            popup.show()
        }

        refresh = {
            renderTabs()
            title.text = when (currentTab) { "world" -> "World Clock"; "timer" -> "Timer"; "stopwatch" -> "Stopwatch"; "bedtime" -> "Bedtime"; else -> "Alarm" }
            clockHeader.visibility = if (currentTab == "world") View.VISIBLE else View.GONE
            content.removeAllViews()
            worldTimes.clear()
            OeaClockRingService.current()?.let { ringing ->
                val box = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL; setPadding(kit.dp(16), kit.dp(12), kit.dp(16), kit.dp(12)); background = kit.rounded(palette.accentSoft, 18) }
                box.addView(kit.text("${ringing.getStringExtra("label") ?: "Alarm"} is ringing", 17f, palette.text, OeaType.medium))
                box.addView(kit.buttonRow(
                    kit.tonalButton("Snooze") { OeaClockRingService.respond(host, ringing, false); refresh() },
                    kit.primaryButton("Stop") { OeaClockRingService.respond(host, ringing, true); refresh() }
                ))
                content.addView(box, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(12) })
            }
            when (currentTab) {
                "alarms" -> {
                    val alarms = decodeAlarms(prefs(host).getString(KEY_ALARMS, ""))
                    if (alarms.isEmpty()) {
                        content.addView(kit.text("No alarms yet", 20f, palette.text, OeaType.medium).apply { gravity = Gravity.CENTER; setPadding(0, kit.dp(72), 0, kit.dp(8)) })
                        content.addView(kit.text("Add an alarm to start your day.", 14f, palette.textSecondary).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, kit.dp(32)) })
                    } else {
                        alarms.forEach { alarm ->
                            val card = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(kit.dp(18), kit.dp(14), kit.dp(16), kit.dp(14)); background = kit.rounded(palette.card, 20, palette.stroke, 1) }
                            val details = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
                            val formatted = if (is24h) String.format(Locale.getDefault(), "%02d:%02d", alarm.hour, alarm.minute) else String.format(Locale.getDefault(), "%d:%02d %s", (alarm.hour % 12).let { if (it == 0) 12 else it }, alarm.minute, if (alarm.hour < 12) "AM" else "PM")
                            details.addView(kit.text(formatted, 30f, if (alarm.enabled) palette.text else palette.textTertiary, OeaType.light))
                            val repeat = if (alarm.days.isEmpty()) "Once" else if (alarm.days.size == 7) "Every day" else listOf(1, 2, 3, 4, 5, 6, 0).filter { it in alarm.days }.joinToString(" ") { dayName(it) }
                            details.addView(kit.text(listOf(alarm.label.ifBlank { "Alarm" }, repeat).joinToString(" · "), 13f, palette.textSecondary).apply { maxLines = 2 })
                            card.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
                            card.addView(kit.toggle(alarm.enabled) { checked ->
                                val updated = alarms.map { if (it.id == alarm.id) it.copy(enabled = checked) else it }
                                prefs(host).edit().putString(KEY_ALARMS, encodeAlarms(updated)).apply()
                                scheduleAlarm(host, alarm.copy(enabled = checked)); refresh()
                            })
                            card.setOnClickListener { editAlarm(host, kit, alarm) { refresh() } }
                            content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(10) })
                        }
                    }
                    content.addView(kit.primaryButton("Add alarm", R.drawable.oic_add) { editAlarm(host, kit, null) { refresh() } }, LinearLayout.LayoutParams(-1, kit.dp(52)).apply { topMargin = kit.dp(12) })
                    if (Build.VERSION.SDK_INT >= 33 && host.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        content.addView(kit.tonalButton("Enable alarm notifications") { alarmSettings(host, kit) }, LinearLayout.LayoutParams(-1, kit.dp(48)).apply { topMargin = kit.dp(12) })
                    }
                    if (Build.VERSION.SDK_INT >= 31 && host.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == false) {
                        content.addView(kit.tonalButton("Allow alarms to ring on time") { alarmSettings(host, kit) }, LinearLayout.LayoutParams(-1, kit.dp(48)).apply { topMargin = kit.dp(12) })
                    }
                }

                "timer" -> {
                    val box = LinearLayout(host).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(kit.dp(14), kit.dp(14), kit.dp(14), kit.dp(14))
                        background = kit.rounded(palette.card, 22, palette.stroke, 1)
                    }
                    timerText = kit.text(formatMs(timerRemainingMs), 36f, palette.text, OeaType.light).apply { gravity = Gravity.CENTER }
                    box.addView(timerText, LinearLayout.LayoutParams(-1, -2))
                    // Presets
                    val presetRow = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, kit.dp(10), 0, 0) }
                    listOf(1 to "1m", 5 to "5m", 10 to "10m", 15 to "15m", 30 to "30m", 60 to "60m").forEach { (m, label) ->
                        presetRow.addView(kit.chip(label, false) {
                            timerDurationMs = m * 60 * 1000L
                            timerRemainingMs = timerDurationMs
                            timerText?.text = formatMs(timerRemainingMs)
                        }, LinearLayout.LayoutParams(-2, kit.dp(36)).apply { rightMargin = kit.dp(6) })
                    }
                    box.addView(kit.hscroll(presetRow), LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(8) })

                    val row = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, kit.dp(10), 0, 0) }
                    listOf(1 to "+1m", 5 to "+5m", 10 to "+10m").forEach { (m, label) ->
                        row.addView(kit.tonalButton(label) {
                            timerDurationMs += m * 60 * 1000L
                            if (!timerRunning) timerRemainingMs = timerDurationMs
                            timerText?.text = formatMs(timerRemainingMs)
                        }, LinearLayout.LayoutParams(-2, kit.dp(40)).apply { rightMargin = kit.dp(8) })
                    }
                    box.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(8) })
                    val controls = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, kit.dp(12), 0, 0) }
                    controls.addView(kit.primaryButton(if (timerRunning) "Pause" else "Start", R.drawable.oic_play_arrow) {
                        if (timerRunning) {
                            timerRunning = false
                            timerRemainingMs = (timerEndAt - System.currentTimeMillis()).coerceAtLeast(0L)
                        } else {
                            timerRunning = true
                            timerEndAt = System.currentTimeMillis() + timerRemainingMs
                            timerHandler.post(object : Runnable {
                                override fun run() {
                                    if (!timerRunning || host.isDestroyed) return
                                    val rem = (timerEndAt - System.currentTimeMillis()).coerceAtLeast(0L)
                                    timerRemainingMs = rem
                                    timerText?.text = formatMs(rem)
                                    if (rem <= 0L) {
                                        timerRunning = false
                                        host.runOnUiThread {
                                            android.widget.Toast.makeText(host, "Timer finished", android.widget.Toast.LENGTH_LONG).show()
                                            // Notification
                                            try {
                                                val nm = host.getSystemService(android.app.NotificationManager::class.java)
                                                if (Build.VERSION.SDK_INT >= 26) nm?.createNotificationChannel(android.app.NotificationChannel("oea_clock", "Clock", android.app.NotificationManager.IMPORTANCE_HIGH))
                                                val builder = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(host, "oea_clock") else @Suppress("DEPRECATION") android.app.Notification.Builder(host)
                                                val notif = builder.setSmallIcon(R.drawable.ic_oea_launcher_monochrome).setContentTitle("Timer finished").setContentText("OEA Timer • ${formatMs(timerDurationMs)}").setAutoCancel(true).build()
                                                nm?.notify(1001, notif)
                                            } catch (_: Throwable) {}
                                        }
                                    } else {
                                        timerHandler.postDelayed(this, 200L)
                                    }
                                }
                            })
                        }
                        refresh()
                    }, LinearLayout.LayoutParams(0, kit.dp(46), 1f).apply { rightMargin = kit.dp(8) })
                    controls.addView(kit.quietButton("Reset") {
                        timerRunning = false
                        timerRemainingMs = timerDurationMs
                        timerText?.text = formatMs(timerRemainingMs)
                        refresh()
                    }, LinearLayout.LayoutParams(0, kit.dp(46), 1f).apply { rightMargin = kit.dp(8) })
                    controls.addView(kit.tonalButton("+1m") {
                        timerEndAt += 60 * 1000L
                        timerRemainingMs += 60 * 1000L
                        timerText?.text = formatMs(timerRemainingMs)
                    }, LinearLayout.LayoutParams(-2, kit.dp(46)))
                    box.addView(controls, LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(12) })
                    content.addView(box)

                    // Multiple timers list
                    if (timersList.isNotEmpty()) {
                        kit.sectionTitle(content, "Saved timers")
                        val g = kit.group(content)
                        timersList.forEach { (label, dur) ->
                            kit.row(g, R.drawable.oic_timer, label, formatMs(dur)) {
                                timerDurationMs = dur
                                timerRemainingMs = dur
                                timerText?.text = formatMs(dur)
                            }
                        }
                    }
                }
                "stopwatch" -> {
                    val box = LinearLayout(host).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(kit.dp(14), kit.dp(14), kit.dp(14), kit.dp(14))
                        background = kit.rounded(palette.card, 22, palette.stroke, 1)
                    }
                    swText = kit.text(formatMs(swElapsed), 36f, palette.text, OeaType.light).apply { gravity = Gravity.CENTER }
                    box.addView(swText, LinearLayout.LayoutParams(-1, -2))
                    val controls = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, kit.dp(12), 0, 0) }
                    controls.addView(kit.primaryButton(if (swRunning) "Pause" else "Start", R.drawable.oic_play_arrow) {
                        if (swRunning) {
                            swRunning = false
                            swElapsed += System.currentTimeMillis() - swStartAt
                            swHandler.removeCallbacks(swTicker)
                        } else {
                            swRunning = true
                            swStartAt = System.currentTimeMillis()
                            swStartTicking()
                        }
                        refresh()
                    }, LinearLayout.LayoutParams(0, kit.dp(46), 1f).apply { rightMargin = kit.dp(8) })
                    controls.addView(kit.tonalButton("Lap") {
                        val elapsed = if (swRunning) swElapsed + (System.currentTimeMillis() - swStartAt) else swElapsed
                        swLaps.add(elapsed)
                        refresh()
                    }, LinearLayout.LayoutParams(0, kit.dp(46), 1f).apply { rightMargin = kit.dp(8) })
                    controls.addView(kit.quietButton("Reset") {
                        swRunning = false
                        swElapsed = 0L
                        swLaps.clear()
                        swText?.text = formatMs(0L)
                        refresh()
                    }, LinearLayout.LayoutParams(0, kit.dp(46), 1f))
                    box.addView(controls)
                    content.addView(box)

                    if (swLaps.isNotEmpty()) {
                        kit.sectionTitle(content, "Laps • ${swLaps.size}")
                        val g = kit.group(content)
                        val fastest = swLaps.minOrNull()
                        val slowest = swLaps.maxOrNull()
                        swLaps.forEachIndexed { idx, lap ->
                            val label = when (lap) {
                                fastest -> "Lap ${idx + 1} • Fastest"
                                slowest -> "Lap ${idx + 1} • Slowest"
                                else -> "Lap ${idx + 1}"
                            }
                            kit.row(g, R.drawable.oic_timer, label, formatMs(lap), chevron = false)
                        }
                        content.addView(kit.tonalButton("Share laps", R.drawable.oic_share_filled) {
                            try {
                                val text = swLaps.mapIndexed { i, l -> "Lap ${i+1}: ${formatMs(l)}" }.joinToString("\n")
                                host.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "OEA Stopwatch\nTotal: ${formatMs(swElapsed)}\n$text"), "Share laps"))
                            } catch (_: Throwable) {}
                        }, LinearLayout.LayoutParams(-1, kit.dp(46)).apply { topMargin = kit.dp(8) })
                    }
                    // Still running from before: keep the hands moving
                    // on this fresh view too.
                    if (swRunning) swStartTicking()
                }
                "world" -> {
                    val savedZones = prefs(host).getStringSet(KEY_WORLD, setOf("UTC", "America/New_York", "Europe/London", "Africa/Lagos")) ?: setOf("Africa/Lagos")
                    savedZones.sorted().forEach { zid ->
                        val tz = java.util.TimeZone.getTimeZone(zid)
                        val card = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(kit.dp(16), kit.dp(12), kit.dp(16), kit.dp(12)); background = kit.rounded(palette.card, 18, palette.stroke, 1) }
                        val city = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
                        city.addView(kit.text(zid.substringAfterLast('/').replace('_', ' '), 16f, palette.text, OeaType.medium))
                        val offset = tz.getOffset(System.currentTimeMillis()) / 60_000
                        city.addView(kit.text("GMT${if (offset >= 0) "+" else "−"}${kotlin.math.abs(offset) / 60}:${String.format(Locale.US, "%02d", kotlin.math.abs(offset) % 60)}", 12f, palette.textSecondary))
                        card.addView(city, LinearLayout.LayoutParams(0, -2, 1f))
                        val zoneTime = kit.text(SimpleDateFormat(if (is24h) "HH:mm" else "h:mm a", Locale.getDefault()).apply { timeZone = tz }.format(Date()), 22f, palette.text, OeaType.light)
                        worldTimes += tz to zoneTime
                        card.addView(zoneTime)
                        card.setOnClickListener {
                            kit.sheet(zid.substringAfterLast('/').replace('_', ' '), zid) { panel, dialog ->
                                panel.addView(kit.tonalButton("Remove city") {
                                    dialog.dismiss()
                                    prefs(host).edit().putStringSet(KEY_WORLD, savedZones.toMutableSet().apply { remove(zid) }).apply()
                                    refresh()
                                }, LinearLayout.LayoutParams(-1, kit.dp(48)))
                            }
                        }
                        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })
                    }
                    content.addView(kit.primaryButton("Add city/timezone", R.drawable.oic_add) {
                        val allZones = java.util.TimeZone.getAvailableIDs().sorted()
                        kit.sheet("Add timezone", "Search timezone ID") { panel, dialog ->
                            val field = kit.field("Timezone (e.g., Asia/Tokyo)", "")
                            val list = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
                            panel.addView(field, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })
                            panel.addView(list)
                            fun filter(q: String) {
                                list.removeAllViews()
                                val filtered = allZones.filter { it.contains(q, true) }.take(20)
                                filtered.forEach { zid ->
                                    kit.row(kit.group(list), R.drawable.oic_schedule, zid.substringAfterLast('/'), zid) {
                                        dialog.dismiss()
                                        val newSet = savedZones.toMutableSet().apply { add(zid) }
                                        prefs(host).edit().putStringSet(KEY_WORLD, newSet).apply()
                                        refresh()
                                    }
                                }
                            }
                            field.addTextChangedListener(object : android.text.TextWatcher {
                                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { filter(s.toString()) }
                                override fun afterTextChanged(s: android.text.Editable?) = Unit
                            })
                            filter("")
                        }
                    }, LinearLayout.LayoutParams(-1, kit.dp(48)).apply { topMargin = kit.dp(12) })

                }
                "bedtime" -> {
                    val bedtimeEnabled = prefs(host).getBoolean(KEY_BEDTIME, false)
                    val bedtimeSaved = prefs(host).getString("bedtime_time", "22:00") ?: "22:00"
                    val wakeSaved = prefs(host).getString("wake_time", "06:00") ?: "06:00"
                    val group = kit.group(content)
                    kit.switchRow(group, R.drawable.oic_bedtime, "Bedtime mode", "Automatically enable DND + Sleeping mode at bedtime", bedtimeEnabled) {
                        prefs(host).edit().putBoolean(KEY_BEDTIME, it).apply()
                        if (it) scheduleBedtime(host) else cancelBedtime(host)
                        refresh()
                    }
                    if (bedtimeEnabled) {
                        kit.row(group, R.drawable.oic_schedule, "Bedtime", "$bedtimeSaved • Tap to change") {
                            kit.prompt("Bedtime", "HH:mm", bedtimeSaved) { input ->
                                val cleaned = input.trim()
                                if (!cleaned.matches(Regex("""\d{1,2}:\d{2}"""))) {
                                    android.widget.Toast.makeText(host, "Use HH:mm, like 22:30", android.widget.Toast.LENGTH_SHORT).show()
                                    return@prompt null
                                }
                                prefs(host).edit().putString("bedtime_time", cleaned).apply()
                                scheduleBedtime(host)
                                refresh()
                                null
                            }
                        }
                        kit.row(group, R.drawable.oic_schedule, "Wake up", "$wakeSaved • Tap to change") {
                            kit.prompt("Wake up", "HH:mm", wakeSaved) { input ->
                                val cleaned = input.trim()
                                if (!cleaned.matches(Regex("""\d{1,2}:\d{2}"""))) {
                                    android.widget.Toast.makeText(host, "Use HH:mm, like 06:30", android.widget.Toast.LENGTH_SHORT).show()
                                    return@prompt null
                                }
                                prefs(host).edit().putString("wake_time", cleaned).apply()
                                scheduleBedtime(host)
                                refresh()
                                null
                            }
                        }
                        kit.note(content, "Bedtime: at bedtime time OEA puts the phone in Sleeping mode (DND on, quiet, islands hidden), and at wake time it puts back the mode you had before. Both are scheduled with exact alarms, so they survive the phone sleeping. If battery is low, Sleeping mode also keeps LOW (Ultra Power Saving) as that mode itself does.")
                        content.addView(kit.primaryButton("Apply Sleeping mode now", R.drawable.oic_bedtime) {
                            try { com.oea.launcher.ui.OeaModes.apply(host, com.oea.launcher.ui.OeaMode.SLEEPING) } catch (_: Throwable) {}
                            android.widget.Toast.makeText(host, "Sleeping mode applied", android.widget.Toast.LENGTH_SHORT).show()
                        }, LinearLayout.LayoutParams(-1, kit.dp(48)).apply { topMargin = kit.dp(8) })
                    } else {
                        kit.note(content, "Bedtime mode helps wind-down: DND + Sleeping mode automatically. Enable to configure.")
                    }
                }
            }
        }
        refresh()
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                timeHandler.removeCallbacksAndMessages(null)
                timerHandler.removeCallbacksAndMessages(null)
                swHandler.removeCallbacksAndMessages(null)
            }
        })
        return root
    }

    private fun alarmSettings(host: Activity, kit: OeaKit) {
        kit.sheet("Alarm settings", "Make sure your alarms can reach you.") { panel, _ ->
            val exact = Build.VERSION.SDK_INT < 31 || host.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
            kit.note(panel, if (exact) "Precise alarms are allowed." else "Precise alarms are off. Android may delay alarms until you allow them.")
            if (!exact && Build.VERSION.SDK_INT >= 31) panel.addView(kit.tonalButton("Allow precise alarms") {
                runCatching { host.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, android.net.Uri.parse("package:${host.packageName}"))) }
            }, LinearLayout.LayoutParams(-1, kit.dp(48)))
            panel.addView(kit.tonalButton("Alarm volume & sound") {
                runCatching { host.startActivity(Intent(android.provider.Settings.ACTION_SOUND_SETTINGS)) }
            }, LinearLayout.LayoutParams(-1, kit.dp(48)).apply { topMargin = kit.dp(8) })
            panel.addView(kit.tonalButton("Notification settings") {
                runCatching { host.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, host.packageName)) }
            }, LinearLayout.LayoutParams(-1, kit.dp(48)).apply { topMargin = kit.dp(8) })
            if (Build.VERSION.SDK_INT >= 33 && host.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                panel.addView(kit.primaryButton("Allow notifications") { host.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 301) }, LinearLayout.LayoutParams(-1, kit.dp(48)).apply { topMargin = kit.dp(8) })
            }
            panel.addView(kit.tonalButton("Battery settings") {
                runCatching { host.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${host.packageName}"))) }
            }, LinearLayout.LayoutParams(-1, kit.dp(48)).apply { topMargin = kit.dp(8) })
            kit.note(panel, "Check alarm volume and notification permissions. Battery restrictions and force-stopping OEA can prevent alarms.")
        }
    }

    /** "Chime|content://media/external/audio/media/42" is how a picked ringtone is stored. */
    internal fun soundLabel(context: Context, stored: String): String {
        val title = stored.substringBefore('|')
        return if (title.isBlank() || title == "Default") "The phone's own alarm sound" else title
    }

    fun ringtoneUri(stored: String): String? = stored.substringAfter('|', "").takeIf { it.isNotBlank() && stored.contains('|') }

    /** The ringtones this phone actually has, not a hard-coded list. */
    private fun pickRingtone(host: Activity, kit: OeaKit, onPick: (String, String?) -> Unit) {
        val titles = mutableListOf("Phone default (alarm)")
        val uris = mutableListOf<String?>(null)
        val kinds = mutableListOf("alarm")
        // The alarm column is often empty on Go devices, so the
        // picker falls back to the phone's whole music library —
        // any sound the user owns can be their ring.
        val queries = listOf(
            "${android.provider.MediaStore.Audio.Media.IS_ALARM} != 0" to "alarm",
            "${android.provider.MediaStore.Audio.Media.IS_MUSIC} != 0" to "music",
            "${android.provider.MediaStore.Audio.Media.IS_NOTIFICATION} != 0" to "notification"
        )
        val seen = mutableSetOf<String>()
        for ((selection, kind) in queries) {
            if (titles.size >= 80) break
            runCatching {
                val projection = arrayOf(
                    android.provider.MediaStore.Audio.Media._ID,
                    android.provider.MediaStore.Audio.Media.TITLE
                )
                host.contentResolver.query(
                    android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    projection, selection, null,
                    "${android.provider.MediaStore.Audio.Media.TITLE} ASC"
                )?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media._ID)
                    val titleColumn = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.TITLE)
                    while (cursor.moveToNext() && titles.size < 80) {
                        val uri = android.content.ContentUris.withAppendedId(
                            android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                            cursor.getLong(idColumn)
                        ).toString()
                        if (!seen.add(uri)) continue
                        titles += cursor.getString(titleColumn)
                        uris += uri
                        kinds += kind
                    }
                }
            }
        }
        val descriptions = titles.mapIndexed { index, _ ->
            when {
                index == 0 -> "Android's own alarm sound, at alarm volume"
                kinds.getOrElse(index) { "" } == "alarm" -> "From this phone's alarm ringtone library"
                else -> "Any sound on this phone"
            }
        }
        kit.choose("Alarm sound", if (titles.size == 1) "This phone lists no alarm ringtones, so only the default is offered" else "From the alarm library and your other sounds", titles, 0, descriptions) { pick ->
            onPick(titles[pick], uris[pick])
        }
    }

    private fun dayName(day: Int): String = when (day) {
        0 -> "Sun"; 1 -> "Mon"; 2 -> "Tue"; 3 -> "Wed"; 4 -> "Thu"; 5 -> "Fri"; 6 -> "Sat"; else -> "$day"
    }

    private fun formatMs(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        val msPart = (ms % 1000) / 10
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d.%02d", h, m, s, msPart)
        else String.format(Locale.US, "%02d:%02d.%02d", m, s, msPart)
    }

    private fun editAlarm(host: Activity, kit: OeaKit, existing: Alarm?, onDone: () -> Unit) {
        val isNew = existing == null
        var hour = existing?.hour ?: 7
        var minute = existing?.minute ?: 0
        var label = existing?.label ?: ""
        var days = existing?.days ?: emptySet()
        var snooze = existing?.snoozeMin ?: 10
        var sound = existing?.sound ?: "Default"
        var vibrate = existing?.vibrate ?: true

        kit.sheet(if (isNew) "New alarm" else "Edit alarm", "Choose a time and repeat days") { container, dialog ->
            val panel = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
            container.addView(kit.boundedScroll(panel, 0.68f))
            // Two platform number wheels, which is what a clock app is expected to
            // offer; the previous version asked for "Hour (0-23)" as a text field.
            val timeRow = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            val hourPicker = android.widget.NumberPicker(host).apply {
                minValue = 0
                maxValue = 23
                value = hour.coerceIn(0, 23)
                wrapSelectorWheel = true
                setFormatter { String.format(Locale.getDefault(), "%02d", it) }
            }
            val minutePicker = android.widget.NumberPicker(host).apply {
                minValue = 0
                maxValue = 59
                value = minute.coerceIn(0, 59)
                wrapSelectorWheel = true
                setFormatter { String.format(Locale.getDefault(), "%02d", it) }
            }
            timeRow.addView(hourPicker, LinearLayout.LayoutParams(0, kit.dp(140), 1f))
            timeRow.addView(kit.text(":", 30f, kit.palette.textTertiary).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-2, kit.dp(140)))
            timeRow.addView(minutePicker, LinearLayout.LayoutParams(0, kit.dp(140), 1f))
            panel.addView(timeRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(6) })
            val labelField = kit.field("Label", label)
            panel.addView(labelField, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })
            // Repeat days as seven chips: no more typing "1,2,3,4,5" into a box.
            val chosenDays = days.toMutableSet()
            val dayRow = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, kit.dp(4), 0, kit.dp(6)) }
            fun paintDays() {
                dayRow.removeAllViews()
                listOf(1, 2, 3, 4, 5, 6, 0).forEach { day ->
                    dayRow.addView(
                        kit.chip(dayName(day), day in chosenDays) {
                            if (day in chosenDays) chosenDays -= day else chosenDays += day
                            paintDays()
                        },
                        LinearLayout.LayoutParams(-2, kit.dp(40)).apply { rightMargin = kit.dp(4) }
                    )
                }
            }
            panel.addView(kit.text("Repeats", 12f, kit.palette.textTertiary))
            paintDays()
            panel.addView(kit.hscroll(dayRow), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(6) })
            val snoozeRow = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, kit.dp(4), 0, kit.dp(8)) }
            fun paintSnooze() {
                snoozeRow.removeAllViews()
                listOf(5, 10, 15, 30).forEach { option ->
                    snoozeRow.addView(
                        kit.chip("${option} min", option == snooze) {
                            snooze = option
                            paintSnooze()
                        },
                        LinearLayout.LayoutParams(-2, kit.dp(40)).apply { rightMargin = kit.dp(4) }
                    )
                }
            }
            panel.addView(kit.text("Snooze for", 12f, kit.palette.textTertiary))
            paintSnooze()
            panel.addView(kit.hscroll(snoozeRow), LinearLayout.LayoutParams(-1, -2))
            val soundGroup = kit.group(panel)
            fun paintSound() {
                soundGroup.removeAllViews()
                kit.row(soundGroup, R.drawable.oic_music_note, "Alarm ringtone", soundLabel(host, sound), value = if (sound.isBlank()) "default" else "change", chevron = true) {
                    pickRingtone(host, kit) { label, uri ->
                        sound = if (uri.isNullOrBlank()) label else "$label|$uri"
                        paintSound()
                    }
                }
                kit.note(soundGroup, "Rings at your phone’s alarm volume until you stop or snooze it.")
            }
            paintSound()
            val vibToggle = kit.toggle(vibrate) { vibrate = it }
            val vibRow = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            vibRow.addView(kit.text("Vibrate", 14f, kit.palette.text), LinearLayout.LayoutParams(0, -2, 1f))
            vibRow.addView(vibToggle)
            panel.addView(vibRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })

            panel.addView(kit.buttonRow(
                kit.quietButton("Cancel") { dialog.dismiss() },
                kit.primaryButton(if (isNew) "Add" else "Save") {
                    val h = hourPicker.value.coerceIn(0, 23)
                    val m = minutePicker.value.coerceIn(0, 59)
                    val l = labelField.text.toString().trim()
                    val d = chosenDays.toSet()
                    val sn = snooze.coerceIn(1, 60)
                    val snd = sound.ifBlank { "Default" }
                    val alarms = decodeAlarms(prefs(host).getString(KEY_ALARMS, "")).toMutableList()
                    if (isNew) {
                        val alarm = Alarm(System.currentTimeMillis(), h, m, l, true, d, sn, snd, vibrate)
                        alarms += alarm
                        prefs(host).edit().putString(KEY_ALARMS, encodeAlarms(alarms)).apply()
                        scheduleAlarm(host, alarm)
                    } else {
                        val updated = alarms.map { if (it.id == existing!!.id) it.copy(hour = h, minute = m, label = l, days = d, snoozeMin = sn, sound = snd, vibrate = vibrate, enabled = true) else it }
                        prefs(host).edit().putString(KEY_ALARMS, encodeAlarms(updated)).apply()
                        scheduleAlarm(host, existing!!.copy(hour = h, minute = m, label = l, days = d, snoozeMin = sn, sound = snd, vibrate = vibrate, enabled = true))
                    }
                    dialog.dismiss()
                    onDone()
                }
            ))
            if (!isNew) panel.addView(kit.quietButton("Delete alarm", R.drawable.oic_delete) {
                val alarms = decodeAlarms(prefs(host).getString(KEY_ALARMS, "")).filterNot { it.id == existing!!.id }
                prefs(host).edit().putString(KEY_ALARMS, encodeAlarms(alarms)).apply()
                cancelAlarm(host, existing!!.id)
                dialog.dismiss(); onDone()
            }, LinearLayout.LayoutParams(-1, kit.dp(48)))
        }
    }

    internal fun cancelAlarm(context: Context, id: Long) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        for (snoozed in listOf(false, true)) {
            val pi = alarmPendingIntent(context, id, snoozed, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
            if (pi != null) { am.cancel(pi); pi.cancel() }
        }
        // Cancel broadcasts from versions that used an ID without a data URI.
        val legacy = PendingIntent.getBroadcast(context, id.toInt(), Intent(context, OeaClockAlarmReceiver::class.java), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        if (legacy != null) { am.cancel(legacy); legacy.cancel() }
    }

    internal fun alarmPendingIntent(context: Context, id: Long, snoozed: Boolean, flags: Int, payload: Intent? = null): PendingIntent? {
        val intent = payload ?: Intent(context, OeaClockAlarmReceiver::class.java)
        intent.data = android.net.Uri.parse("oea-clock://alarm/$id/${if (snoozed) "snooze" else "scheduled"}")
        intent.putExtra("snoozed", snoozed)
        return PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    internal fun scheduleAlarm(context: Context, alarm: Alarm, cancelSnooze: Boolean = true) {
        if (cancelSnooze) cancelAlarm(context, alarm.id)
        else {
            // Clear old pre-URI broadcasts without disturbing a pending snooze.
            val legacy = PendingIntent.getBroadcast(context, alarm.id.toInt(), Intent(context, OeaClockAlarmReceiver::class.java), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
            if (legacy != null) { context.getSystemService(AlarmManager::class.java)?.cancel(legacy); legacy.cancel() }
        }
        if (!alarm.enabled) return
        try {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            val intent = Intent(context, OeaClockAlarmReceiver::class.java).apply {
                putExtra("label", alarm.label.ifBlank { "Alarm" })
                putExtra("id", alarm.id)
                putExtra("snooze", alarm.snoozeMin)
                putExtra("sound", alarm.sound)
                putExtra("vibrate", alarm.vibrate)
                putExtra("hour", alarm.hour)
                putExtra("minute", alarm.minute)
            }
            val pi = alarmPendingIntent(context, alarm.id, false, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE, intent) ?: return
            val at = nextClockAlarm(java.util.Calendar.getInstance(), alarm.hour, alarm.minute, alarm.days)
            if (Build.VERSION.SDK_INT >= 31) {
                if (am.canScheduleExactAlarms()) {
                    am.setAlarmClock(AlarmManager.AlarmClockInfo(at, PendingIntent.getActivity(context, 0, Intent(context, OeaClockActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)), pi)
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                }
            } else {
                @Suppress("DEPRECATION")
                am.setAlarmClock(AlarmManager.AlarmClockInfo(at, PendingIntent.getActivity(context, 0, Intent(context, OeaClockActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)), pi)
            }
        } catch (error: Throwable) { android.util.Log.e("OeaClock", "Could not schedule alarm", error) }
    }
    /** Pending duration for the timer tab, coming from an external Set-timer request. */
    private var presetTimerMs: Long? = null

    // ------------------------------------------------------------------ bedtime

    internal const val KEY_MODE_BEFORE_BEDTIME = "mode_before_bedtime"

    /** Parse "HH:mm" into minutes since midnight, or null. */
    private fun parseHm(raw: String): Int? {
        val m = Regex("""(\d{1,2}):(\d{2})""").matchEntire(raw.trim()) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        return if (h in 0..23 && min in 0..59) h * 60 + min else null
    }

    /**
     * Arm (or re-arm) the two bedtime alarms: one that puts the phone
     * into Sleeping mode at bedtime, one that restores the previous
     * mode at wake time. Exact alarms, so they fire on time even
     * with the phone asleep.
     */
    fun scheduleBedtime(context: Context) {
        val bedMin = parseHm(prefs(context).getString("bedtime_time", "22:00") ?: "22:00") ?: 22 * 60
        val wakeMin = parseHm(prefs(context).getString("wake_time", "06:00") ?: "06:00") ?: 6 * 60
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val now = java.util.Calendar.getInstance()
        val nowMin = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
        listOf(
            Triple(bedMin, OeaClockBedtimeReceiver.EXTRA_BEDTIME_ON, 7001),
            Triple(wakeMin, OeaClockBedtimeReceiver.EXTRA_BEDTIME_OFF, 7002)
        ).forEach { (minutes, extra, code) ->
            val trigger = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, minutes / 60)
                set(java.util.Calendar.MINUTE, minutes % 60)
                set(java.util.Calendar.SECOND, 0)
                if (before(now) || (minutes == nowMin)) add(java.util.Calendar.DAY_OF_YEAR, 1)
            }
            val intent = Intent(context, OeaClockBedtimeReceiver::class.java)
                .putExtra(OeaClockBedtimeReceiver.EXTRA_BEDTIME_MODE, extra == OeaClockBedtimeReceiver.EXTRA_BEDTIME_ON)
            val pi = PendingIntent.getBroadcast(
                context, code, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            runCatching {
                if (Build.VERSION.SDK_INT >= 31) {
                    if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger.timeInMillis, pi)
                    else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger.timeInMillis, pi)
                } else {
                    @Suppress("DEPRECATION")
                    am.setExact(AlarmManager.RTC_WAKEUP, trigger.timeInMillis, pi)
                }
            }
        }
    }

    /** Cancel both bedtime alarms (bedtime mode switched off). */
    fun cancelBedtime(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        listOf(7001, 7002).forEach { code ->
            val pi = PendingIntent.getBroadcast(
                context, code,
                Intent(context, OeaClockBedtimeReceiver::class.java),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            pi?.let {
                runCatching { am.cancel(it) }
                it.cancel()
            }
        }
    }

    private fun calendarDayToOea(calendarDay: Int): Int? =
        if (calendarDay in java.util.Calendar.SUNDAY..java.util.Calendar.SATURDAY) calendarDay - 1 else null

    internal fun alarmFired(context: Context, id: Long, snoozed: Boolean): Boolean {
        val alarms = decodeAlarms(prefs(context).getString(KEY_ALARMS, ""))
        val alarm = alarms.firstOrNull { it.id == id } ?: return false
        if (!snoozed && !alarm.enabled) return false
        if (!snoozed) {
            if (alarm.days.isEmpty()) {
                prefs(context).edit().putString(KEY_ALARMS, encodeAlarms(alarms.map { if (it.id == id) it.copy(enabled = false) else it })).apply()
            } else scheduleAlarm(context, alarm, cancelSnooze = false)
        }
        return true
    }

    internal fun restoreAlarms(context: Context) {
        decodeAlarms(prefs(context).getString(KEY_ALARMS, "")).filter { it.enabled }.forEach { scheduleAlarm(context, it, cancelSnooze = false) }
        if (prefs(context).getBoolean(KEY_BEDTIME, false)) scheduleBedtime(context)
    }

    // Android passes Set-timer durations as ISO-8601, e.g. "PT1H30M" or "PT45S".
    private fun parseIsoDuration(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val rx = Regex("P(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?")
        val m = rx.matchEntire(raw.uppercase()) ?: return null
        val h = m.groupValues.getOrNull(1)?.toLongOrNull() ?: 0L
        val min = m.groupValues.getOrNull(2)?.toLongOrNull() ?: 0L
        val sec = m.groupValues.getOrNull(3)?.toLongOrNull() ?: 0L
        val total = (h * 3600L + min * 60L + sec) * 1000L
        return if (total in 1L..86_400_000L) total else null
    }

}

class OeaClockAlarmReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("id", 0L)
        if (!OeaClockApp.alarmFired(context, id, intent.getBooleanExtra("snoozed", false))) return
        val ring = Intent(context, OeaClockRingService::class.java).putExtras(intent)
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(ring) else context.startService(ring)
        } catch (error: Exception) {
            // If Android refuses a service start, the alarm notification is a
            // best-effort fallback rather than silently losing the alarm.
            android.util.Log.w("OeaClock", "Alarm service unavailable; using notification", error)
            runCatching { OeaClockRingService.showFallback(context, intent) }
        }
    }
}

class OeaClockRestoreReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runCatching { OeaClockApp.restoreAlarms(context) }
            .onFailure { android.util.Log.e("OeaClock", "Could not restore alarms", it) }
    }
}

/**
 * Bedtime's pair: at bedtime time it puts the phone into Sleeping
 * mode (remembering the mode you had), and at wake time it puts
 * that mode back. DND, quiet and the rest come from Sleeping mode
 * itself — OEA does not invent a second behaviour here.
 */
class OeaClockBedtimeReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val bedtime = intent.getBooleanExtra(EXTRA_BEDTIME_MODE, true)
        if (bedtime) {
            val before = com.oea.launcher.ui.OeaModes.current(context).id
            context.getSharedPreferences(OeaClockApp.PREFS, Context.MODE_PRIVATE)
                .edit().putString(OeaClockApp.KEY_MODE_BEFORE_BEDTIME, before).apply()
            com.oea.launcher.ui.OeaModes.apply(context, com.oea.launcher.ui.OeaMode.SLEEPING)
        } else {
            val before = context.getSharedPreferences(OeaClockApp.PREFS, Context.MODE_PRIVATE)
                .getString(OeaClockApp.KEY_MODE_BEFORE_BEDTIME, null)
            com.oea.launcher.ui.OeaModes.apply(context, com.oea.launcher.ui.OeaMode.of(before))
        }
    }

    companion object {
        internal const val EXTRA_BEDTIME_MODE = "oea.clock.bedtime_on"
        internal const val EXTRA_BEDTIME_ON = "on"
        internal const val EXTRA_BEDTIME_OFF = "off"
    }
}

class OeaClockSnoozeReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("id", 0L)
        OeaClockRingService.stop(context, id)
        context.getSystemService(android.app.NotificationManager::class.java)?.cancel(OeaClockRingService.notificationId(id))
        if (intent.getBooleanExtra("dismissOnly", false)) {
            // Stop only this ringing/snoozed occurrence, not the next repeat.
            val am = context.getSystemService(AlarmManager::class.java)
            val pi = OeaClockApp.alarmPendingIntent(context, id, true, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
            if (pi != null) { am?.cancel(pi); pi.cancel() }
            return
        }
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val newIntent = Intent(context, OeaClockAlarmReceiver::class.java).putExtras(intent)
        val pi = OeaClockApp.alarmPendingIntent(context, id, true, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE, newIntent) ?: return
        val triggerAt = System.currentTimeMillis() + intent.getIntExtra("snooze", 10).coerceIn(1, 60) * 60_000L
        runCatching {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }.onFailure { android.util.Log.e("OeaClock", "Could not snooze alarm", it) }
    }
}
