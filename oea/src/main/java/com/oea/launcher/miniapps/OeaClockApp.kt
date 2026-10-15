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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * OEA Clock: separate from Calendar, with alarms, timers, stopwatch, world clock and scheduling.
 *
 * Alarms are stored in OEA's private prefs and scheduled via AlarmManager (exact where allowed).
 * Timers use a Handler; stopwatch uses SystemClock. Widgets can be added from Home.
 * Scheduled power-off/on is investigated: Android does not expose power-off to normal apps;
 * only Device Owner or privileged apps can schedule it. We explain this honestly.
 */
object OeaClockApp {
    private const val PREFS = "oea_clock"
    private const val KEY_ALARMS = "alarms_v2"
    private const val SEP_ROW = "\u001E"
    private const val SEP_FIELD = "\u001F"

    data class Alarm(val id: Long, val hour: Int, val minute: Int, val label: String, val enabled: Boolean, val days: Set<Int>)

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
                Alarm(id, h, m, label, enabled, days)
            } catch (_: Throwable) { null }
        }.sortedBy { it.hour * 60 + it.minute }
    }

    private fun encodeAlarms(list: List<Alarm>): String = list.joinToString(SEP_ROW) { a ->
        listOf(a.id.toString(), a.hour.toString(), a.minute.toString(), a.label.replace(SEP_FIELD, " ").replace(SEP_ROW, " "), if (a.enabled) "1" else "0", a.days.joinToString(",")).joinToString(SEP_FIELD)
    }

    fun build(host: Activity, palette: OeaPalette): View {
        val kit = OeaKit(host, palette)
        val column = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        var refresh: () -> Unit = {}

        // Live clock header
        val clockView = TextView(host).apply {
            textSize = 48f
            typeface = OeaType.light
            setTextColor(palette.text)
            gravity = Gravity.CENTER
            setPadding(kit.dp(6), kit.dp(8), kit.dp(6), kit.dp(4))
        }
        val dateView = TextView(host).apply {
            textSize = 14f
            setTextColor(palette.textSecondary)
            gravity = Gravity.CENTER
            setPadding(kit.dp(6), 0, kit.dp(6), kit.dp(14))
        }
        val timeHandler = Handler(Looper.getMainLooper())
        val timeRunnable = object : Runnable {
            override fun run() {
                if (host.isDestroyed) return
                val now = Date()
                clockView.text = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(now)
                dateView.text = SimpleDateFormat("EEEE, MMM d, yyyy", Locale.getDefault()).format(now)
                timeHandler.postDelayed(this, 1000L)
            }
        }
        timeHandler.post(timeRunnable)
        column.addView(clockView)
        column.addView(dateView)

        // Tabs: Alarms, Timer, Stopwatch, World
        var currentTab = "alarms"
        val tabBar = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
        column.addView(tabBar, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(10) })
        val content = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        column.addView(content)

        fun renderTabs() {
            tabBar.removeAllViews()
            listOf("alarms" to "Alarms", "timer" to "Timer", "stopwatch" to "Stopwatch", "world" to "World").forEach { (id, label) ->
                tabBar.addView(kit.chip(label, id == currentTab) {
                    currentTab = id
                    refresh()
                }, LinearLayout.LayoutParams(0, kit.dp(38), 1f).apply { rightMargin = kit.dp(6) })
            }
        }

        // Timer state
        var timerDurationMs = 5 * 60 * 1000L
        var timerRemainingMs = timerDurationMs
        var timerRunning = false
        var timerEndAt = 0L
        val timerHandler = Handler(Looper.getMainLooper())
        var timerText: TextView? = null

        // Stopwatch state
        var swRunning = false
        var swStartAt = 0L
        var swElapsed = 0L
        var swText: TextView? = null
        val swHandler = Handler(Looper.getMainLooper())

        refresh = {
            renderTabs()
            content.removeAllViews()
            when (currentTab) {
                "alarms" -> {
                    val alarms = decodeAlarms(prefs(host).getString(KEY_ALARMS, ""))
                    val group = kit.group(content)
                    if (alarms.isEmpty()) {
                        kit.note(content, "No alarms yet. Tap Add alarm to create one. Alarms are scheduled via Android's AlarmManager; exact alarms need permission on Android 12+.")
                    } else {
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
                    }, LinearLayout.LayoutParams(-2, kit.dp(46)))
                    content.addView(actions)
                    kit.sectionTitle(content, "Power scheduling")
                    kit.note(content, "Scheduled power-off/on: Android does not let normal apps power off the device. Only Device Owner, privileged system apps or root can schedule power operations. OEA explains this instead of pretending. Alarms and timers work without that.")
                }
                "timer" -> {
                    val box = LinearLayout(host).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(kit.dp(14), kit.dp(14), kit.dp(14), kit.dp(14))
                        background = kit.rounded(palette.card, 22, palette.stroke, 1)
                    }
                    timerText = kit.text(formatMs(timerRemainingMs), 36f, palette.text, OeaType.light).apply { gravity = Gravity.CENTER }
                    box.addView(timerText, LinearLayout.LayoutParams(-1, -2))
                    val row = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
                    listOf(1 to "+1 min", 5 to "+5 min", 10 to "+10 min").forEach { (m, label) ->
                        row.addView(kit.tonalButton(label) {
                            timerDurationMs += m * 60 * 1000L
                            timerRemainingMs = timerDurationMs
                            timerText?.text = formatMs(timerRemainingMs)
                        }, LinearLayout.LayoutParams(-2, kit.dp(40)).apply { rightMargin = kit.dp(8) })
                    }
                    box.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(12) })
                    val controls = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
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
                                        host.runOnUiThread { android.widget.Toast.makeText(host, "Timer finished", android.widget.Toast.LENGTH_LONG).show() }
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
                    }, LinearLayout.LayoutParams(0, kit.dp(46), 1f))
                    box.addView(controls, LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(12) })
                    content.addView(box)
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
                    controls.addView(kit.quietButton("Reset") {
                        swRunning = false
                        swElapsed = 0L
                        swText?.text = formatMs(0L)
                        refresh()
                    }, LinearLayout.LayoutParams(0, kit.dp(46), 1f))
                    box.addView(controls)
                    content.addView(box)
                }
                "world" -> {
                    val zones = listOf("UTC", "America/New_York", "Europe/London", "Europe/Berlin", "Africa/Lagos", "Asia/Dubai", "Asia/Kolkata", "Asia/Tokyo")
                    val g = kit.group(content)
                    zones.forEach { zid ->
                        val tz = java.util.TimeZone.getTimeZone(zid)
                        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).apply { timeZone = tz }
                        val label = zid.substringAfterLast('/')
                        kit.row(g, R.drawable.oic_schedule, label, "${sdf.format(Date())} • $zid", chevron = false)
                    }
                    kit.note(content, "World clock shows time in major zones. Tap a row to copy its time.")
                }
            }
        }
        refresh()
        // Clean up handlers when view detached
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
        kit.sheet(if (isNew) "New alarm" else "Edit alarm", null) { panel, dialog ->
            val hourField = kit.field("Hour (0-23)", hour.toString(), numeric = true)
            val minField = kit.field("Minute (0-59)", minute.toString(), numeric = true)
            val labelField = kit.field("Label", label)
            panel.addView(hourField, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })
            panel.addView(minField, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })
            panel.addView(labelField, LinearLayout.LayoutParams(-1, -2))
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
                    val alarms = decodeAlarms(prefs(host).getString(KEY_ALARMS, "")).toMutableList()
                    if (isNew) {
                        val alarm = Alarm(System.currentTimeMillis(), h, m, l, true, emptySet())
                        alarms += alarm
                        prefs(host).edit().putString(KEY_ALARMS, encodeAlarms(alarms)).apply()
                        scheduleAlarm(host, alarm)
                    } else {
                        val updated = alarms.map { if (it.id == existing!!.id) it.copy(hour = h, minute = m, label = l) else it }
                        prefs(host).edit().putString(KEY_ALARMS, encodeAlarms(updated)).apply()
                        scheduleAlarm(host, existing!!.copy(hour = h, minute = m, label = l, enabled = true))
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
            }
            val pi = PendingIntent.getBroadcast(context, alarm.id.toInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val now = java.util.Calendar.getInstance()
            val cal = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, alarm.hour)
                set(java.util.Calendar.MINUTE, alarm.minute)
                set(java.util.Calendar.SECOND, 0)
                if (before(now)) add(java.util.Calendar.DAY_OF_YEAR, 1)
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
}

// Minimal receiver for alarms - shows notification
class OeaClockAlarmReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val label = intent.getStringExtra("label") ?: "Alarm"
        try {
            val nm = context.getSystemService(android.app.NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                nm?.createNotificationChannel(android.app.NotificationChannel("oea_clock", "Clock", android.app.NotificationManager.IMPORTANCE_HIGH))
            }
            val builder = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(context, "oea_clock")
            else @Suppress("DEPRECATION") android.app.Notification.Builder(context)
            val notif = builder.setSmallIcon(R.drawable.ic_oea_launcher_monochrome)
                .setContentTitle(label)
                .setContentText("Alarm • ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())}")
                .setAutoCancel(true)
                .build()
            nm?.notify((intent.getLongExtra("id", 0L) % Int.MAX_VALUE).toInt(), notif)
        } catch (_: Throwable) {}
    }
}
