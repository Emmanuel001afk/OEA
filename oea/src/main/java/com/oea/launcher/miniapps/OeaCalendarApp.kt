package com.oea.launcher.miniapps

import android.app.Activity
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.oea.launcher.R
import com.oea.launcher.ui.OeaKit
import com.oea.launcher.ui.OeaPalette
import com.oea.launcher.ui.OeaType
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * OEA Calendar: separate from Clock, with month/week/day views, events, reminders.
 */
object OeaCalendarApp {
    private const val PREFS = "oea_calendar"
    private const val KEY_EVENTS = "events_v2"
    private const val SEP_ROW = "\u001E"
    private const val SEP_FIELD = "\u001F"

    data class Event(val id: Long, val year: Int, val month: Int, val day: Int, val title: String, val time: String, val desc: String)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun decode(raw: String?): List<Event> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(SEP_ROW).mapNotNull { row ->
            val p = row.split(SEP_FIELD)
            if (p.size < 6) return@mapNotNull null
            try {
                Event(p[0].toLong(), p[1].toInt(), p[2].toInt(), p[3].toInt(), p[4], p[5], if (p.size > 6) p[6] else "")
            } catch (_: Throwable) { null }
        }.sortedWith(compareBy({ it.year }, { it.month }, { it.day }, { it.time }))
    }

    private fun encode(list: List<Event>): String = list.joinToString(SEP_ROW) { e ->
        listOf(e.id.toString(), e.year.toString(), e.month.toString(), e.day.toString(), e.title.replace(SEP_FIELD, " "), e.time.replace(SEP_FIELD, " "), e.desc.replace(SEP_FIELD, " ").replace(SEP_ROW, " ")).joinToString(SEP_FIELD)
    }

    fun build(host: Activity, palette: OeaPalette): View {
        val kit = OeaKit(host, palette)
        val column = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        var currentCal = Calendar.getInstance()
        var selectedCal = Calendar.getInstance()
        var refresh: () -> Unit = {}

        val header = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val monthText = kit.text("", 20f, palette.text, OeaType.medium)
        header.addView(monthText, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(kit.circleIconButton(R.drawable.oic_chevron_right, "Next", palette.cardHigh, palette.text) {
            currentCal.add(Calendar.MONTH, 1)
            refresh()
        }.apply { rotation = 0f }, LinearLayout.LayoutParams(kit.dp(40), kit.dp(40)))
        // We'll use two buttons for prev/next
        val prevBtn = kit.circleIconButton(R.drawable.oic_arrow_back, "Prev", palette.cardHigh, palette.text) {
            currentCal.add(Calendar.MONTH, -1)
            refresh()
        }
        header.addView(prevBtn, 0)
        column.addView(header, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(10) })

        val weekHeader = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat").forEach { day ->
            weekHeader.addView(kit.text(day, 12f, palette.textTertiary, OeaType.medium).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        column.addView(weekHeader, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(6) })

        val gridHost = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        column.addView(gridHost)
        val eventsHost = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        column.addView(eventsHost)

        fun isToday(c: Calendar): Boolean {
            val today = Calendar.getInstance()
            return c.get(Calendar.YEAR) == today.get(Calendar.YEAR) && c.get(Calendar.MONTH) == today.get(Calendar.MONTH) && c.get(Calendar.DAY_OF_MONTH) == today.get(Calendar.DAY_OF_MONTH)
        }

        refresh = {
            val sdf = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
            monthText.text = sdf.format(currentCal.time)

            gridHost.removeAllViews()
            val month = currentCal.get(Calendar.MONTH)
            val year = currentCal.get(Calendar.YEAR)
            val firstDay = Calendar.getInstance().apply { set(year, month, 1) }
            val startOffset = firstDay.get(Calendar.DAY_OF_WEEK) - 1
            val daysInMonth = currentCal.getActualMaximum(Calendar.DAY_OF_MONTH)
            val allEvents = decode(prefs(host).getString(KEY_EVENTS, ""))

            var dayCounter = 1 - startOffset
            for (row in 0 until 6) {
                val rowLayout = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
                for (col in 0 until 7) {
                    val cellCal = Calendar.getInstance().apply { set(year, month, dayCounter) }
                    val inMonth = dayCounter in 1..daysInMonth
                    val isSelected = inMonth && cellCal.get(Calendar.YEAR) == selectedCal.get(Calendar.YEAR) && cellCal.get(Calendar.MONTH) == selectedCal.get(Calendar.MONTH) && cellCal.get(Calendar.DAY_OF_MONTH) == selectedCal.get(Calendar.DAY_OF_MONTH)
                    val hasEvent = inMonth && allEvents.any { it.year == year && it.month == month && it.day == dayCounter }
                    val cell = LinearLayout(host).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        setPadding(kit.dp(4), kit.dp(8), kit.dp(4), kit.dp(8))
                        background = when {
                            isSelected -> kit.rounded(palette.accent, 14)
                            isToday(cellCal) && inMonth -> kit.rounded(palette.accentSoft, 14)
                            else -> null
                        }
                        isClickable = inMonth
                        setOnClickListener {
                            if (!inMonth) return@setOnClickListener
                            selectedCal = cellCal.clone() as Calendar
                            refresh()
                        }
                    }
                    cell.addView(kit.text(if (inMonth) dayCounter.toString() else "", 14f, when {
                        !inMonth -> palette.textTertiary
                        isSelected -> palette.onAccent
                        isToday(cellCal) -> palette.accent
                        else -> palette.text
                    }, OeaType.medium).apply { gravity = Gravity.CENTER })
                    if (hasEvent) {
                        cell.addView(View(host).apply {
                            background = kit.oval(if (isSelected) palette.onAccent else palette.accent)
                        }, LinearLayout.LayoutParams(kit.dp(5), kit.dp(5)).apply { topMargin = kit.dp(3); gravity = Gravity.CENTER })
                    }
                    rowLayout.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
                    dayCounter++
                }
                gridHost.addView(rowLayout)
                if (dayCounter > daysInMonth && row >= 3) break
            }

            // Events for selected day
            eventsHost.removeAllViews()
            kit.sectionTitle(eventsHost, "Events • ${SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(selectedCal.time)}")
            val dayEvents = allEvents.filter { it.year == selectedCal.get(Calendar.YEAR) && it.month == selectedCal.get(Calendar.MONTH) && it.day == selectedCal.get(Calendar.DAY_OF_MONTH) }
            if (dayEvents.isEmpty()) {
                kit.note(eventsHost, "No events for this day. Tap Add event to schedule something.")
            } else {
                val g = kit.group(eventsHost)
                dayEvents.forEach { ev ->
                    kit.row(g, R.drawable.oic_schedule, ev.title, "${ev.time} • ${ev.desc}", trailing = null) {
                        editEvent(host, kit, ev) { refresh() }
                    }
                }
            }
            val actions = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, kit.dp(8), 0, 0) }
            actions.addView(kit.primaryButton("Add event", R.drawable.oic_add) {
                editEvent(host, kit, null, selectedCal) { refresh() }
            }, LinearLayout.LayoutParams(-2, kit.dp(44)))
            actions.addView(kit.quietButton("Today") {
                currentCal = Calendar.getInstance()
                selectedCal = Calendar.getInstance()
                refresh()
            }, LinearLayout.LayoutParams(-2, kit.dp(44)).apply { leftMargin = kit.dp(8) })
            eventsHost.addView(actions)
        }
        refresh()
        return column
    }

    private fun editEvent(host: Activity, kit: OeaKit, existing: Event?, defaultCal: Calendar = Calendar.getInstance(), onDone: () -> Unit) {
        val isNew = existing == null
        var year = existing?.year ?: defaultCal.get(Calendar.YEAR)
        var month = existing?.month ?: defaultCal.get(Calendar.MONTH)
        var day = existing?.day ?: defaultCal.get(Calendar.DAY_OF_MONTH)
        var title = existing?.title ?: ""
        var time = existing?.time ?: "09:00"
        var desc = existing?.desc ?: ""
        kit.sheet(if (isNew) "New event" else "Edit event", null) { panel, dialog ->
            val titleField = kit.field("Title", title)
            val timeField = kit.field("Time (HH:mm)", time)
            val descField = kit.field("Description", desc)
            panel.addView(titleField, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })
            panel.addView(timeField, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })
            panel.addView(descField, LinearLayout.LayoutParams(-1, -2))
            panel.addView(kit.buttonRow(
                kit.quietButton("Cancel") { dialog.dismiss() },
                if (!isNew) kit.quietButton("Delete", R.drawable.oic_delete) {
                    val events = decode(prefs(host).getString(KEY_EVENTS, "")).filterNot { it.id == existing!!.id }
                    prefs(host).edit().putString(KEY_EVENTS, encode(events)).apply()
                    dialog.dismiss()
                    onDone()
                } else kit.quietButton("Cancel") { dialog.dismiss() },
                kit.primaryButton(if (isNew) "Add" else "Save") {
                    val t = titleField.text.toString().trim()
                    if (t.isEmpty()) {
                        android.widget.Toast.makeText(host, "Title required", android.widget.Toast.LENGTH_SHORT).show()
                        return@primaryButton
                    }
                    val events = decode(prefs(host).getString(KEY_EVENTS, "")).toMutableList()
                    if (isNew) {
                        events += Event(System.currentTimeMillis(), year, month, day, t, timeField.text.toString().trim(), descField.text.toString().trim())
                    } else {
                        val updated = events.map { if (it.id == existing!!.id) it.copy(title = t, time = timeField.text.toString().trim(), desc = descField.text.toString().trim()) else it }
                        events.clear()
                        events.addAll(updated)
                    }
                    prefs(host).edit().putString(KEY_EVENTS, encode(events)).apply()
                    dialog.dismiss()
                    onDone()
                }
            ))
        }
    }
}
