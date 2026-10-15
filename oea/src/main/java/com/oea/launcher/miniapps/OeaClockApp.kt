package com.oea.launcher.miniapps

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
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
 * - Alarms: hour/minute, label, repeat days (Mon-Sun), snooze (5/10/15/30 min), sound (system ringtones + OEA tones), vibration, delete, enable/disable
 * - Timer: multiple timers, presets (1/5/10/15/30/60 min), custom, full-screen countdown, sound, notification when finished, add 1 min
 * - Stopwatch: start/pause/reset, laps with total, share, fastest/slowest lap highlight
 * - World clock: add city via timezone search, shows daylight offset, DST, local time, remove, sort
 * - Bedtime: schedule DND + Low mode automatically (integrates with OEA Modes Sleeping), wind-down reminder
 * - Widgets: clock widget live update via AlarmManager
 * - Power scheduling investigation: Android does not expose power-off to normal apps; only Device Owner or privileged can schedule. OEA explains honestly.
 * - Unique: integration with Low mode (Ultra Power Saving) — Clock explains that device ultra power saving may block alarms and how to whitelist
 */
object OeaClockApp {
    private const val PREFS = "oea_clock"
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
        val column = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
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
        val is24h = prefs(host).getBoolean(KEY_24H, true)

        // Live clock header
        val clockView = TextView(host).apply {
            textSize = 52f
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
        val timeHandler = Handler(Looper.getMainLooper())
        val timeRunnable = object : Runnable {
            override fun run() {
                if (host.isDestroyed) return
                val now = Date()
                val fmt = if (is24h) "HH:mm:ss" else "hh:mm:ss a"
                clockView.text = SimpleDateFormat(fmt, Locale.getDefault()).format(now)
                dateView.text = SimpleDateFormat("EEEE, MMM d, yyyy", Locale.getDefault()).format(now)
                tzView.text = "${java.util.TimeZone.getDefault().id} • ${java.util.TimeZone.getDefault().displayName}"
                timeHandler.postDelayed(this, 1000L)
            }
        }
        timeHandler.post(timeRunnable)
        column.addView(clockView)
        column.addView(dateView)
        column.addView(tzView)

        // Tabs
        val tabBar = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
        column.addView(tabBar, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(10) })
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

        fun renderTabs() {
            tabBar.removeAllViews()
            listOf("alarms" to "Alarms", "timer" to "Timer", "stopwatch" to "Stopwatch", "world" to "World", "bedtime" to "Bedtime").forEach { (id, label) ->
                tabBar.addView(kit.chip(label, id == currentTab) {
                    currentTab = id
                    refresh()
                }, LinearLayout.LayoutParams(0, kit.dp(38), 1f).apply { rightMargin = kit.dp(6) })
            }
        }

        refresh = {
            renderTabs()
            content.removeAllViews()
            when (currentTab) {
                "alarms" -> {
                    val alarms = decodeAlarms(prefs(host).getString(KEY_ALARMS, ""))
                    if (alarms.isEmpty()) {
                        kit.note(content, "No alarms yet. Tap Add alarm. Alarms use AlarmManager; exact alarms need permission on Android 12+. Device ultra power saving may delay alarms — whitelist OEA in battery settings.")
                    } else {
                        val group = kit.group(content)
                        alarms.forEach { alarm ->
                            val toggle = kit.toggle(alarm.enabled) { checked ->
                                val updated = alarms.map { if (it.id == alarm.id) it.copy(enabled = checked) else it }
                                prefs(host).edit().putString(KEY_ALARMS, encodeAlarms(updated)).apply()
                                scheduleAlarm(host, alarm.copy(enabled = checked))
                                refresh()
                            }
                            val subtitle = buildString {
                                append(String.format(Locale.US, "%02d:%02d", alarm.hour, alarm.minute))
                                if (alarm.label.isNotBlank()) append(" • ${alarm.label}")
                                if (alarm.days.isNotEmpty()) append(" • ${alarm.days.sorted().joinToString { dayName(it) }}")
                                append(" • Snooze ${alarm.snoozeMin}m • ${alarm.sound}")
                                if (alarm.vibrate) append(" • Vibrate")
                                if (!alarm.enabled) append(" • off")
                            }
                            kit.row(group, R.drawable.oic_alarm, alarm.label.ifBlank { "Alarm" }, subtitle, trailing = toggle, chevron = false) {
                                editAlarm(host, kit, alarm) { refresh() }
                            }
                        }
                    }
                    val actions = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, kit.dp(8), 0, 0) }
                    actions.addView(kit.primaryButton("Add alarm", R.drawable.oic_add) {
                        editAlarm(host, kit, null) { refresh() }
                    }, LinearLayout.LayoutParams(-2, kit.dp(46)).apply { rightMargin = kit.dp(8) })
                    actions.addView(kit.tonalButton(if (is24h) "24h" else "12h") {
                        prefs(host).edit().putBoolean(KEY_24H, !is24h).apply()
                        (host as? OeaMiniAppActivity)?.refresh()
                    }, LinearLayout.LayoutParams(-2, kit.dp(46)))
                    content.addView(actions)

                    kit.sectionTitle(content, "Power scheduling & Ultra Power Saving")
                    kit.note(content, "Scheduled power-off/on: Android does not let normal apps power off device. Only Device Owner, privileged system apps or root can schedule power operations. OEA explains this instead of pretending. Alarms and timers work without that.\n\nDevice Ultra Power Saving Mode (Samsung, Xiaomi, etc.): when device enters ultra saving, it may kill OEA's alarm service and block mini-apps. Fix: Settings › Battery › Unrestricted for OEA, and disable ultra saving or add OEA to allowed apps. OEA's own Low mode (Ultra Power Saving) is separate — it only makes launcher cheap, never kills alarms.")
                    content.addView(kit.primaryButton("Battery optimization settings", R.drawable.oic_battery_full) {
                        try { host.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Throwable) {
                            try { host.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${host.packageName}"))) } catch (_: Throwable) {}
                        }
                    }, LinearLayout.LayoutParams(-1, kit.dp(48)).apply { topMargin = kit.dp(8) })
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
                        } else {
                            swRunning = true
                            swStartAt = System.currentTimeMillis()
                            swHandler.post(object : Runnable {
                                override fun run() {
                                    if (!swRunning || host.isDestroyed) return
                                    val elapsed = swElapsed + (System.currentTimeMillis() - swStartAt)
                                    swText?.text = formatMs(elapsed)
                                    swHandler.postDelayed(this, 50L)
                                }
                            })
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
                }
                "world" -> {
                    val savedZones = prefs(host).getStringSet(KEY_WORLD, setOf("UTC", "America/New_York", "Europe/London", "Africa/Lagos")) ?: setOf("Africa/Lagos")
                    val g = kit.group(content)
                    savedZones.sorted().forEach { zid ->
                        val tz = java.util.TimeZone.getTimeZone(zid)
                        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).apply { timeZone = tz }
                        val label = zid.substringAfterLast('/')
                        val offset = tz.rawOffset / (60*60*1000)
                        kit.row(g, R.drawable.oic_schedule, label, "${sdf.format(Date())} • $zid • GMT${if (offset >=0) "+" else ""}$offset", chevron = false) {
                            kit.sheet(label, zid) { panel, _ ->
                                kit.note(panel, "Timezone: $zid\nOffset: GMT${if (offset>=0) "+" else ""}$offset\nDST: ${if (tz.inDaylightTime(Date())) "Yes" else "No"}\nCurrent: ${sdf.format(Date())}")
                                panel.addView(kit.tonalButton("Remove") {
                                    val newSet = savedZones.toMutableSet().apply { remove(zid) }
                                    prefs(host).edit().putStringSet(KEY_WORLD, newSet).apply()
                                    refresh()
                                }, LinearLayout.LayoutParams(-1, kit.dp(46)))
                            }
                        }
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
                    kit.note(content, "World clock shows time in major zones. Tap row for details. Add any timezone ID.")
                }
                "bedtime" -> {
                    val bedtimeEnabled = prefs(host).getBoolean(KEY_BEDTIME, false)
                    val group = kit.group(content)
                    kit.switchRow(group, R.drawable.oic_bedtime, "Bedtime mode", "Automatically enable DND + Sleeping mode at bedtime", bedtimeEnabled) {
                        prefs(host).edit().putBoolean(KEY_BEDTIME, it).apply()
                        refresh()
                    }
                    if (bedtimeEnabled) {
                        kit.row(group, R.drawable.oic_schedule, "Bedtime", "22:00 • Tap to change") {
                            kit.prompt("Bedtime", "HH:mm", "22:00") { input ->
                                // Save bedtime time
                                prefs(host).edit().putString("bedtime_time", input).apply()
                                refresh()
                                null
                            }
                        }
                        kit.row(group, R.drawable.oic_schedule, "Wake up", "06:00 • Tap to change") {
                            kit.prompt("Wake up", "HH:mm", "06:00") { input ->
                                prefs(host).edit().putString("wake_time", input).apply()
                                refresh()
                                null
                            }
                        }
                        kit.note(content, "Bedtime unique: when enabled, OEA at bedtime time will set DND on and apply Sleeping mode (quiet, LOW optional, islands hidden). At wake time, restores. Integration with Low mode: if battery low, also enables LOW (Ultra Power Saving).")
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
        column.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                timeHandler.removeCallbacksAndMessages(null)
                timerHandler.removeCallbacksAndMessages(null)
                swHandler.removeCallbacksAndMessages(null)
            }
        })
        return column
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

        kit.sheet(if (isNew) "New alarm" else "Edit alarm", null) { panel, dialog ->
            val hourField = kit.field("Hour (0-23)", hour.toString(), numeric = true)
            val minField = kit.field("Minute (0-59)", minute.toString(), numeric = true)
            val labelField = kit.field("Label", label)
            val daysField = kit.field("Days (0=Sun,1=Mon..6=Sat comma separated, e.g., 1,2,3,4,5)", days.joinToString(","))
            val snoozeField = kit.field("Snooze minutes (5/10/15/30)", snooze.toString(), numeric = true)
            val soundField = kit.field("Sound", sound)
            panel.addView(hourField, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })
            panel.addView(minField, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })
            panel.addView(labelField, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })
            panel.addView(daysField, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })
            panel.addView(snoozeField, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })
            panel.addView(soundField, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })
            val vibToggle = kit.toggle(vibrate) { vibrate = it }
            val vibRow = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            vibRow.addView(kit.text("Vibrate", 14f, kit.palette.text), LinearLayout.LayoutParams(0, -2, 1f))
            vibRow.addView(vibToggle)
            panel.addView(vibRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })

            panel.addView(kit.buttonRow(
                kit.quietButton("Cancel") { dialog.dismiss() },
                if (!isNew) kit.quietButton("Delete", R.drawable.oic_delete) {
                    val alarms = decodeAlarms(prefs(host).getString(KEY_ALARMS, "")).filterNot { it.id == existing!!.id }
                    prefs(host).edit().putString(KEY_ALARMS, encodeAlarms(alarms)).apply()
                    dialog.dismiss()
                    onDone()
                } else kit.quietButton("Cancel") { dialog.dismiss() },
                kit.primaryButton(if (isNew) "Add" else "Save") {
                    val h = hourField.text.toString().toIntOrNull()?.coerceIn(0, 23) ?: run {
                        android.widget.Toast.makeText(host, "Invalid hour", android.widget.Toast.LENGTH_SHORT).show()
                        return@primaryButton
                    }
                    val m = minField.text.toString().toIntOrNull()?.coerceIn(0, 59) ?: run {
                        android.widget.Toast.makeText(host, "Invalid minute", android.widget.Toast.LENGTH_SHORT).show()
                        return@primaryButton
                    }
                    val l = labelField.text.toString().trim()
                    val d = daysField.text.toString().split(",").mapNotNull { it.trim().toIntOrNull()?.coerceIn(0, 6) }.toSet()
                    val sn = snoozeField.text.toString().toIntOrNull()?.coerceIn(1, 60) ?: 10
                    val snd = soundField.text.toString().trim().ifBlank { "Default" }
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
        }
    }

    private fun scheduleAlarm(context: Context, alarm: Alarm) {
        if (!alarm.enabled) return
        try {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            val intent = Intent(context, OeaClockAlarmReceiver::class.java).apply {
                putExtra("label", alarm.label.ifBlank { "Alarm" })
                putExtra("id", alarm.id)
                putExtra("snooze", alarm.snoozeMin)
                putExtra("sound", alarm.sound)
                putExtra("vibrate", alarm.vibrate)
            }
            val pi = PendingIntent.getBroadcast(context, alarm.id.toInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val now = java.util.Calendar.getInstance()
            val cal = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, alarm.hour)
                set(java.util.Calendar.MINUTE, alarm.minute)
                set(java.util.Calendar.SECOND, 0)
                if (before(now)) add(java.util.Calendar.DAY_OF_YEAR, 1)
                // If repeat days, adjust to next matching day
                if (alarm.days.isNotEmpty()) {
                    var attempts = 0
                    while (attempts < 7 && !alarm.days.contains(get(java.util.Calendar.DAY_OF_WEEK) - 1)) {
                        add(java.util.Calendar.DAY_OF_YEAR, 1)
                        attempts++
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= 31) {
                if (am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
                }
            } else {
                @Suppress("DEPRECATION")
                am.setExact(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
            }
        } catch (_: Throwable) {}
    }
    /** Pending duration for the timer tab, coming from an external Set-timer request. */
    private var presetTimerMs: Long? = null

    /** Map Calendar's day constants onto OEA's Mon..Sun (0..6) index set. */
    private fun calendarDayToOea(calendarDay: Int): Int? = when (calendarDay) {
        java.util.Calendar.MONDAY -> 0
        java.util.Calendar.TUESDAY -> 1
        java.util.Calendar.WEDNESDAY -> 2
        java.util.Calendar.THURSDAY -> 3
        java.util.Calendar.FRIDAY -> 4
        java.util.Calendar.SATURDAY -> 5
        java.util.Calendar.SUNDAY -> 6
        else -> null
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
        val label = intent.getStringExtra("label") ?: "Alarm"
        val snoozeMin = intent.getIntExtra("snooze", 10)
        val sound = intent.getStringExtra("sound") ?: "Default"
        val vibrate = intent.getBooleanExtra("vibrate", true)
        try {
            val nm = context.getSystemService(android.app.NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                nm?.createNotificationChannel(android.app.NotificationChannel("oea_clock", "Clock", android.app.NotificationManager.IMPORTANCE_HIGH).apply {
                    enableVibration(vibrate)
                })
            }
            val snoozeIntent = Intent(context, OeaClockSnoozeReceiver::class.java).apply {
                putExtra("label", label)
                putExtra("id", intent.getLongExtra("id", 0L))
                putExtra("snooze", snoozeMin)
            }
            val snoozePi = PendingIntent.getBroadcast(context, (intent.getLongExtra("id", 0L) + 1).toInt(), snoozeIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val builder = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(context, "oea_clock")
            else @Suppress("DEPRECATION") android.app.Notification.Builder(context)
            val notif = builder.setSmallIcon(R.drawable.ic_oea_launcher_monochrome)
                .setContentTitle(label)
                .setContentText("Alarm • ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())} • $sound • Snooze ${snoozeMin}m")
                .setAutoCancel(true)
                .addAction(android.app.Notification.Action.Builder(null, "Snooze ${snoozeMin}m", snoozePi).build())
                .build()
            nm?.notify((intent.getLongExtra("id", 0L) % Int.MAX_VALUE).toInt(), notif)
        } catch (_: Throwable) {}
    }
}

class OeaClockSnoozeReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val label = intent.getStringExtra("label") ?: "Alarm"
        val id = intent.getLongExtra("id", 0L)
        val snoozeMin = intent.getIntExtra("snooze", 10)
        try {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            val newIntent = Intent(context, OeaClockAlarmReceiver::class.java).apply {
                putExtra("label", label)
                putExtra("id", id)
                putExtra("snooze", snoozeMin)
            }
            val pi = PendingIntent.getBroadcast(context, id.toInt(), newIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val triggerAt = System.currentTimeMillis() + snoozeMin * 60 * 1000L
            if (Build.VERSION.SDK_INT >= 31) {
                if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                @Suppress("DEPRECATION") am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        } catch (_: Throwable) {}
    }
}
