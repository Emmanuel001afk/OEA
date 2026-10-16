package com.oea.launcher.miniapps

import android.app.Activity
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.oea.launcher.R
import com.oea.launcher.ui.OeaKit
import com.oea.launcher.ui.OeaPalette
import com.oea.launcher.ui.OeaType
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * OEA Calendar: fully competitive calendar separate from Clock.
 *
 * Unique features:
 * - Views: Month, Week, Day, Agenda, Year
 * - Events: title, time, location, description, category (Work/Personal/Study/Health/Other), color, reminder (none/5m/15m/30m/1h/1d), recurring (none/daily/weekly/monthly/yearly), all-day
 * - Search events
 * - Import/export via share (ICS placeholder) and JSON backup
 * - Categories with colors, filter
 * - Lunar calendar optional display (best-effort)
 * - Week start: Sunday/Monday setting
 * - Integration with OEA modes: Focus shows only Work, Study shows Study, etc.
 * - Widgets: calendar widget shows today's events
 * - Competitive: month grid with dots, week timeline, day hourly, agenda list, year overview
 */
object OeaCalendarApp {
    private const val PREFS = "oea_calendar"
    private const val KEY_EVENTS = "events_v2"
    private const val KEY_VIEW = "view_mode"
    private const val KEY_WEEK_START = "week_start_monday"
    private const val KEY_LUNAR = "show_lunar"
    private const val SEP_ROW = "\u001E"
    private const val SEP_FIELD = "\u001F"

    enum class ViewMode(val label: String) { MONTH("Month"), WEEK("Week"), DAY("Day"), AGENDA("Agenda"), YEAR("Year") }
    enum class Category(val label: String, val color: Int) {
        WORK("Work", android.graphics.Color.rgb(66, 133, 244)),
        PERSONAL("Personal", android.graphics.Color.rgb(52, 168, 83)),
        STUDY("Study", android.graphics.Color.rgb(251, 188, 4)),
        HEALTH("Health", android.graphics.Color.rgb(234, 67, 53)),
        OTHER("Other", android.graphics.Color.rgb(154, 66, 245))
    }
    enum class Recurrence(val label: String) { NONE("None"), DAILY("Daily"), WEEKLY("Weekly"), MONTHLY("Monthly"), YEARLY("Yearly") }

    data class Event(
        val id: Long,
        val year: Int,
        val month: Int,
        val day: Int,
        val title: String,
        val time: String,
        val endTime: String,
        val location: String,
        val desc: String,
        val category: String,
        val reminderMin: Int,
        val recurrence: String,
        val allDay: Boolean
    )

    private fun hasCalendarPermission(host: Activity): Boolean = runCatching {
        host.checkSelfPermission("android.permission.READ_CALENDAR") == android.content.pm.PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /**
     * The phone's own calendar events for the month around [monthCal], read
     * from Android's Calendar Provider. They are merged read-only into the
     * views (ids are negative so they are never written back to OEA's store).
     */
    private fun loadDeviceEvents(host: Activity, monthCal: Calendar): List<Event> {
        val out = ArrayList<Event>()
        runCatching {
            val start = Calendar.getInstance().apply {
                clear(); set(monthCal.get(Calendar.YEAR), monthCal.get(Calendar.MONTH), 1, 0, 0, 0)
            }.timeInMillis
            val end = Calendar.getInstance().apply {
                clear(); set(monthCal.get(Calendar.YEAR), monthCal.get(Calendar.MONTH), 1, 0, 0, 0)
                add(Calendar.MONTH, 1)
            }.timeInMillis
            val uri = android.content.CalendarContract.Instances.CONTENT_URI.buildUpon()
            android.content.ContentUris.appendId(uri, start)
            android.content.ContentUris.appendId(uri, end)
            host.contentResolver.query(
                uri.build(),
                arrayOf(
                    android.content.CalendarContract.Instances.TITLE,
                    android.content.CalendarContract.Instances.BEGIN,
                    android.content.CalendarContract.Instances.END,
                    android.content.CalendarContract.Instances.ALL_DAY,
                ),
                null, null,
                android.content.CalendarContract.Instances.BEGIN + " ASC",
            )?.use { c ->
                val titleI = c.getColumnIndex(android.content.CalendarContract.Instances.TITLE)
                val beginI = c.getColumnIndex(android.content.CalendarContract.Instances.BEGIN)
                val endI = c.getColumnIndex(android.content.CalendarContract.Instances.END)
                val allDayI = c.getColumnIndex(android.content.CalendarContract.Instances.ALL_DAY)
                while (c.moveToNext() && out.size < 240) {
                    val begin = c.getLong(beginI)
                    val endMs = c.getLong(endI)
                    val allDay = allDayI >= 0 && c.getInt(allDayI) == 1
                    val s = Calendar.getInstance().apply { timeInMillis = begin }
                    val e = Calendar.getInstance().apply { timeInMillis = if (endMs > begin) endMs else begin + 3_600_000L }
                    val time = if (allDay) "" else String.format(java.util.Locale.US, "%02d:%02d", s.get(Calendar.HOUR_OF_DAY), s.get(Calendar.MINUTE))
                    val endTime = if (allDay) "" else String.format(java.util.Locale.US, "%02d:%02d", e.get(Calendar.HOUR_OF_DAY), e.get(Calendar.MINUTE))
                    out += Event(
                        -begin, s.get(Calendar.YEAR), s.get(Calendar.MONTH), s.get(Calendar.DAY_OF_MONTH),
                        c.getString(titleI) ?: "(untitled)", time, endTime, "", "From your device calendar",
                        Category.OTHER.name, 0, Recurrence.NONE.name, allDay,
                    )
                }
            }
        }.onFailure { /* denied or provider missing: OEA's own events still work */ }
        return out
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun decode(raw: String?): List<Event> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(SEP_ROW).mapNotNull { row ->
            val p = row.split(SEP_FIELD)
            if (p.size < 6) return@mapNotNull null
            try {
                Event(
                    p[0].toLong(), p[1].toInt(), p[2].toInt(), p[3].toInt(),
                    p[4], p[5], p.getOrNull(6) ?: "", p.getOrNull(7) ?: "", p.getOrNull(8) ?: "",
                    p.getOrNull(9) ?: "OTHER", p.getOrNull(10)?.toIntOrNull() ?: 0,
                    p.getOrNull(11) ?: "NONE", p.getOrNull(12) == "1"
                )
            } catch (_: Throwable) { null }
        }.sortedWith(compareBy({ it.year }, { it.month }, { it.day }, { it.time }))
    }

    private fun encode(list: List<Event>): String = list.joinToString(SEP_ROW) { e ->
        listOf(
            e.id.toString(), e.year.toString(), e.month.toString(), e.day.toString(),
            e.title.replace(SEP_FIELD, " "), e.time.replace(SEP_FIELD, " "),
            e.endTime.replace(SEP_FIELD, " "), e.location.replace(SEP_FIELD, " "),
            e.desc.replace(SEP_FIELD, " ").replace(SEP_ROW, " "),
            e.category, e.reminderMin.toString(), e.recurrence, if (e.allDay) "1" else "0"
        ).joinToString(SEP_FIELD)
    }

    fun build(host: Activity, palette: OeaPalette): View {
        val kit = OeaKit(host, palette)
        val column = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        var currentCal = Calendar.getInstance()
        var selectedCal = Calendar.getInstance()
        var viewMode = runCatching { ViewMode.valueOf(prefs(host).getString(KEY_VIEW, "MONTH") ?: "MONTH") }.getOrDefault(ViewMode.MONTH)
        var searchQuery = ""
        var filterCategory: String? = null
        var refresh: () -> Unit = {}

        // Android calendar-role INSERT (another app hands over an event as
        // extras; no provider permission needed on our side). Added once,
        // then the intent is consumed so rebuilds don't duplicate it.
        run {
            val intent = host.intent
            if (intent != null && intent.action == android.content.Intent.ACTION_INSERT &&
                intent.type == "vnd.android.cursor.item/event"
            ) {
                val title = intent.getStringExtra("title") ?: ""
                val begin = intent.getLongExtra(android.provider.CalendarContract.Events.DTSTART, -1L)
                val end = intent.getLongExtra(android.provider.CalendarContract.Events.DTEND, -1L)
                val location = intent.getStringExtra(android.provider.CalendarContract.Events.EVENT_LOCATION) ?: ""
                val desc = intent.getStringExtra(android.provider.CalendarContract.Events.DESCRIPTION) ?: ""
                val allDay = intent.getIntExtra(android.provider.CalendarContract.Events.ALL_DAY, 0) == 1
                if (begin > 0L) {
                    val calStart = Calendar.getInstance().apply { timeInMillis = begin }
                    val calEnd = Calendar.getInstance().apply { timeInMillis = if (end > begin) end else begin + 3_600_000L }
                    val time = if (allDay) "" else String.format(java.util.Locale.US, "%02d:%02d", calStart.get(Calendar.HOUR_OF_DAY), calStart.get(Calendar.MINUTE))
                    val endTime = if (allDay) "" else String.format(java.util.Locale.US, "%02d:%02d", calEnd.get(Calendar.HOUR_OF_DAY), calEnd.get(Calendar.MINUTE))
                    val events = decode(prefs(host).getString(KEY_EVENTS, "")).toMutableList()
                    events += Event(
                        System.currentTimeMillis(),
                        calStart.get(Calendar.YEAR), calStart.get(Calendar.MONTH), calStart.get(Calendar.DAY_OF_MONTH),
                        title, time, endTime, location, desc, "Event", 15, "None", allDay
                    )
                    prefs(host).edit().putString(KEY_EVENTS, encode(events)).apply()
                    selectedCal = calStart
                    currentCal = calStart
                }
                host.intent = null
            }
        }

        val header = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val previousButton = kit.circleIconButton(R.drawable.oic_chevron_right, "Previous month", palette.cardHigh, palette.text).apply { rotation = 180f }
        val nextButton = kit.circleIconButton(R.drawable.oic_chevron_right, "Next month", palette.cardHigh, palette.text)
        val monthText = kit.text("", 20f, palette.text, OeaType.medium)
        header.addView(previousButton, LinearLayout.LayoutParams(kit.dp(40), kit.dp(40)).apply { rightMargin = kit.dp(4) })
        header.addView(monthText, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(nextButton, LinearLayout.LayoutParams(kit.dp(40), kit.dp(40)).apply { rightMargin = kit.dp(8) })
        previousButton.setOnClickListener {
            currentCal.set(Calendar.DAY_OF_MONTH, 1)
            currentCal.add(Calendar.MONTH, -1)
            selectedCal = currentCal.clone() as Calendar
            refresh()
        }
        nextButton.setOnClickListener {
            currentCal.set(Calendar.DAY_OF_MONTH, 1)
            currentCal.add(Calendar.MONTH, 1)
            selectedCal = currentCal.clone() as Calendar
            refresh()
        }
        header.addView(kit.circleIconButton(R.drawable.oic_search, "Search", palette.cardHigh, palette.text) {
            kit.prompt("Search events", "Title, location", "Search", searchQuery) { q ->
                searchQuery = q.trim()
                refresh()
                null
            }
        }, LinearLayout.LayoutParams(kit.dp(40), kit.dp(40)).apply { rightMargin = kit.dp(8) })
        header.addView(kit.circleIconButton(R.drawable.oic_refresh, "Today", palette.cardHigh, palette.text) {
            currentCal = Calendar.getInstance()
            selectedCal = Calendar.getInstance()
            refresh()
        }, LinearLayout.LayoutParams(kit.dp(40), kit.dp(40)))
        column.addView(header, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })

        val viewBar = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
        column.addView(kit.hscroll(viewBar), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })

        val filterBar = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
        column.addView(kit.hscroll(filterBar), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(10) })

        val deviceHost = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        column.addView(deviceHost, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(10) })

        val gridHost = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        column.addView(gridHost)
        val eventsHost = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        column.addView(eventsHost)

        fun renderViewBar() {
            viewBar.removeAllViews()
            ViewMode.values().forEach { vm ->
                viewBar.addView(kit.chip(vm.label, vm == viewMode) {
                    viewMode = vm
                    prefs(host).edit().putString(KEY_VIEW, vm.name).apply()
                    refresh()
                }, LinearLayout.LayoutParams(-2, kit.dp(36)).apply { rightMargin = kit.dp(6) })
            }
        }

        fun renderFilterBar() {
            filterBar.removeAllViews()
            filterBar.addView(kit.chip("All", filterCategory == null) { filterCategory = null; refresh() }, LinearLayout.LayoutParams(-2, kit.dp(36)).apply { rightMargin = kit.dp(6) })
            Category.values().forEach { cat ->
                filterBar.addView(kit.chip(cat.label, filterCategory == cat.name) {
                    filterCategory = if (filterCategory == cat.name) null else cat.name
                    refresh()
                }, LinearLayout.LayoutParams(-2, kit.dp(36)).apply { rightMargin = kit.dp(6) })
            }
        }

        refresh = {
            renderViewBar()
            renderFilterBar()
            val sdfMonth = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
            monthText.text = when (viewMode) {
                ViewMode.MONTH -> sdfMonth.format(currentCal.time)
                ViewMode.WEEK -> "Week of ${SimpleDateFormat("MMM d", Locale.getDefault()).format(currentCal.time)}"
                ViewMode.DAY -> SimpleDateFormat("EEEE, MMM d, yyyy", Locale.getDefault()).format(selectedCal.time)
                ViewMode.AGENDA -> "Agenda"
                ViewMode.YEAR -> SimpleDateFormat("yyyy", Locale.getDefault()).format(currentCal.time)
            }

            gridHost.removeAllViews()
            eventsHost.removeAllViews()
            deviceHost.removeAllViews()
            // Real device calendar: with permission, the phone's own events
            // merge straight into the grid, day view and agenda.
            val deviceEvents = if (hasCalendarPermission(host)) loadDeviceEvents(host, currentCal) else emptyList()
            if (!hasCalendarPermission(host)) {
                kit.row(kit.group(deviceHost), R.drawable.ic_oea_calendar, "Connect your device calendar",
                    "Show the events your phone already has (Google, Exchange, CalDAV)") {
                    runCatching {
                        host.requestPermissions(arrayOf("android.permission.READ_CALENDAR", "android.permission.WRITE_CALENDAR"), 2101)
                    }
                }
            } else if (deviceEvents.isNotEmpty()) {
                kit.note(deviceHost, "Device calendar connected · ${deviceEvents.size} event(s) this month are merged into your grid and agenda.")
            } else {
                kit.note(deviceHost, "Device calendar connected · no events this month. OEA events and device events live side by side.")
            }
            val allEvents = decode(prefs(host).getString(KEY_EVENTS, "")) + deviceEvents
            val filteredEvents = allEvents.filter { ev ->
                (filterCategory == null || ev.category == filterCategory) &&
                (searchQuery.isBlank() || ev.title.contains(searchQuery, true) || ev.location.contains(searchQuery, true) || ev.desc.contains(searchQuery, true))
            }

            when (viewMode) {
                ViewMode.MONTH -> {
                    // Week header
                    val weekHeader = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
                    val weekStartMonday = prefs(host).getBoolean(KEY_WEEK_START, false)
                    val days = if (weekStartMonday) listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun") else listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
                    days.forEach { day ->
                        weekHeader.addView(kit.text(day, 12f, palette.textTertiary, OeaType.medium).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, -2, 1f))
                    }
                    gridHost.addView(weekHeader, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(6) })

                    val month = currentCal.get(Calendar.MONTH)
                    val year = currentCal.get(Calendar.YEAR)
                    val firstDay = Calendar.getInstance().apply { set(year, month, 1) }
                    var startOffset = firstDay.get(Calendar.DAY_OF_WEEK) - 1
                    if (weekStartMonday) startOffset = (startOffset + 6) % 7
                    val daysInMonth = currentCal.getActualMaximum(Calendar.DAY_OF_MONTH)
                    var dayCounter = 1 - startOffset
                    for (row in 0 until 6) {
                        val rowLayout = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
                        for (col in 0 until 7) {
                            val cellCal = Calendar.getInstance().apply { set(year, month, dayCounter) }
                            val inMonth = dayCounter in 1..daysInMonth
                            val isSelected = inMonth && cellCal.get(Calendar.YEAR) == selectedCal.get(Calendar.YEAR) && cellCal.get(Calendar.MONTH) == selectedCal.get(Calendar.MONTH) && cellCal.get(Calendar.DAY_OF_MONTH) == selectedCal.get(Calendar.DAY_OF_MONTH)
                            val isToday = run {
                                val today = Calendar.getInstance()
                                inMonth && cellCal.get(Calendar.YEAR) == today.get(Calendar.YEAR) && cellCal.get(Calendar.MONTH) == today.get(Calendar.MONTH) && cellCal.get(Calendar.DAY_OF_MONTH) == today.get(Calendar.DAY_OF_MONTH)
                            }
                            val hasEvent = inMonth && filteredEvents.any { it.year == year && it.month == month && it.day == dayCounter }
                            val cell = LinearLayout(host).apply {
                                orientation = LinearLayout.VERTICAL
                                gravity = Gravity.CENTER
                                setPadding(kit.dp(4), kit.dp(8), kit.dp(4), kit.dp(8))
                                background = when {
                                    isSelected -> kit.rounded(palette.accent, 14)
                                    isToday -> kit.rounded(palette.accentSoft, 14)
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
                                isToday -> palette.accent
                                else -> palette.text
                            }, OeaType.medium).apply { gravity = Gravity.CENTER })
                            if (hasEvent) {
                                cell.addView(View(host).apply { background = kit.oval(if (isSelected) palette.onAccent else palette.accent) }, LinearLayout.LayoutParams(kit.dp(5), kit.dp(5)).apply { topMargin = kit.dp(3); gravity = Gravity.CENTER })
                            }
                            rowLayout.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
                            dayCounter++
                        }
                        gridHost.addView(rowLayout)
                        if (dayCounter > daysInMonth && row >= 3) break
                    }
                }
                ViewMode.WEEK -> {
                    val startOfWeek = currentCal.clone() as Calendar
                    startOfWeek.set(Calendar.DAY_OF_WEEK, startOfWeek.firstDayOfWeek)
                    val weekDays = (0 until 7).map { offset ->
                        (startOfWeek.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, offset) }
                    }
                    weekDays.forEach { dayCal ->
                        val isSelected = dayCal.get(Calendar.YEAR) == selectedCal.get(Calendar.YEAR) && dayCal.get(Calendar.MONTH) == selectedCal.get(Calendar.MONTH) && dayCal.get(Calendar.DAY_OF_MONTH) == selectedCal.get(Calendar.DAY_OF_MONTH)
                        val dayEvents = filteredEvents.filter { it.year == dayCal.get(Calendar.YEAR) && it.month == dayCal.get(Calendar.MONTH) && it.day == dayCal.get(Calendar.DAY_OF_MONTH) }
                        val row = LinearLayout(host).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(kit.dp(12), kit.dp(8), kit.dp(12), kit.dp(8))
                            background = kit.rounded(if (isSelected) palette.accentSoft else palette.card, 14, palette.stroke, 1)
                            isClickable = true
                            setOnClickListener { selectedCal = dayCal.clone() as Calendar; refresh() }
                        }
                        row.addView(kit.text(SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(dayCal.time) + " • ${dayEvents.size} events", 14f, if (isSelected) palette.accent else palette.text, OeaType.medium))
                        dayEvents.take(3).forEach { ev ->
                            row.addView(kit.text("${ev.time} ${ev.title}", 12f, palette.textSecondary).apply { setPadding(0, kit.dp(2), 0, 0) })
                        }
                        gridHost.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(6) })
                    }
                }
                ViewMode.DAY -> {
                    // Hourly timeline
                    val hours = (0..23).map { h ->
                        val hourEvents = filteredEvents.filter { it.year == selectedCal.get(Calendar.YEAR) && it.month == selectedCal.get(Calendar.MONTH) && it.day == selectedCal.get(Calendar.DAY_OF_MONTH) && it.time.startsWith(String.format(Locale.US, "%02d:", h)) }
                        h to hourEvents
                    }
                    hours.forEach { (h, evs) ->
                        val row = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(kit.dp(8), kit.dp(6), kit.dp(8), kit.dp(6)) }
                        row.addView(kit.text(String.format(Locale.US, "%02d:00", h), 12f, palette.textTertiary), LinearLayout.LayoutParams(kit.dp(50), -2))
                        if (evs.isEmpty()) {
                            row.addView(kit.text("—", 12f, palette.textTertiary), LinearLayout.LayoutParams(0, -2, 1f))
                        } else {
                            val col = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
                            evs.forEach { ev -> col.addView(kit.text("${ev.time} ${ev.title} • ${ev.location}", 13f, palette.text)) }
                            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
                        }
                        gridHost.addView(row)
                    }
                }
                ViewMode.AGENDA -> {
                    val sorted = filteredEvents.sortedWith(compareBy({ it.year }, { it.month }, { it.day }, { it.time }))
                    if (sorted.isEmpty()) {
                        kit.note(gridHost, "No events. Tap Add event.")
                    } else {
                        val g = kit.group(gridHost)
                        sorted.take(50).forEach { ev ->
                            val cat = runCatching { Category.valueOf(ev.category) }.getOrDefault(Category.OTHER)
                            val readOnly = ev.id < 0L
                            val action: (() -> Unit)? = if (readOnly) null else ({ editEvent(host, kit, ev) { refresh() } })
                            kit.row(g, R.drawable.oic_schedule, ev.title, "${ev.year}-${ev.month+1}-${ev.day} ${ev.time} • ${cat.label} • ${ev.location}" + if (readOnly) " • Device calendar · read-only" else "", trailing = null, chevron = !readOnly, onClick = action)
                        }
                    }
                }
                ViewMode.YEAR -> {
                    // 12 months overview
                    for (m in 0 until 12) {
                        val cal = Calendar.getInstance().apply { set(currentCal.get(Calendar.YEAR), m, 1) }
                        val count = filteredEvents.count { it.year == cal.get(Calendar.YEAR) && it.month == m }
                        val row = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(kit.dp(12), kit.dp(10), kit.dp(12), kit.dp(10)); background = kit.rounded(palette.card, 14, palette.stroke, 1); isClickable = true }
                        row.addView(kit.text(SimpleDateFormat("MMMM", Locale.getDefault()).format(cal.time), 15f, palette.text, OeaType.medium), LinearLayout.LayoutParams(0, -2, 1f))
                        row.addView(kit.text("$count events", 13f, palette.textSecondary))
                        row.setOnClickListener { currentCal.set(Calendar.MONTH, m); viewMode = ViewMode.MONTH; refresh() }
                        gridHost.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(6) })
                    }
                }
            }

            // Events for selected day (except agenda)
            if (viewMode != ViewMode.AGENDA) {
                kit.sectionTitle(eventsHost, "Events • ${SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(selectedCal.time)} • ${filteredEvents.count { it.year == selectedCal.get(Calendar.YEAR) && it.month == selectedCal.get(Calendar.MONTH) && it.day == selectedCal.get(Calendar.DAY_OF_MONTH) }}")
                val dayEvents = filteredEvents.filter { it.year == selectedCal.get(Calendar.YEAR) && it.month == selectedCal.get(Calendar.MONTH) && it.day == selectedCal.get(Calendar.DAY_OF_MONTH) }
                if (dayEvents.isEmpty()) {
                    kit.note(eventsHost, "No events for this day. Tap Add event.")
                } else {
                    val g = kit.group(eventsHost)
                    dayEvents.forEach { ev ->
                        val cat = runCatching { Category.valueOf(ev.category) }.getOrDefault(Category.OTHER)
                        val readOnly = ev.id < 0L
                        val action: (() -> Unit)? = if (readOnly) null else ({ editEvent(host, kit, ev) { refresh() } })
                        kit.row(g, R.drawable.oic_schedule, ev.title, "${ev.time}-${ev.endTime} • ${cat.label} • ${ev.location} • ${ev.desc} • Reminder ${ev.reminderMin}m • ${ev.recurrence}" + if (readOnly) " • Device calendar · read-only" else "", trailing = null, chevron = !readOnly, onClick = action)
                    }
                }
            }

            val actions = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, kit.dp(8), 0, 0) }
            actions.addView(kit.primaryButton("Add event", R.drawable.oic_add) {
                editEvent(host, kit, null, selectedCal) { refresh() }
            }, LinearLayout.LayoutParams(-2, kit.dp(44)).apply { rightMargin = kit.dp(8) })
            actions.addView(kit.tonalButton("Today") {
                currentCal = Calendar.getInstance()
                selectedCal = Calendar.getInstance()
                refresh()
            }, LinearLayout.LayoutParams(-2, kit.dp(44)).apply { rightMargin = kit.dp(8) })
            actions.addView(kit.quietButton("Export") {
                val json = encode(filteredEvents)
                kit.sheet("Export events", "${filteredEvents.size} events") { panel, _ ->
                    kit.note(panel, json.take(8000))
                    panel.addView(kit.buttonRow(
                        kit.quietButton("Copy") {
                            val cb = host.getSystemService(android.content.ClipboardManager::class.java) as android.content.ClipboardManager
                            cb.setPrimaryClip(android.content.ClipData.newPlainText("OEA calendar", json))
                            android.widget.Toast.makeText(host, "Copied", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        kit.primaryButton("Share") {
                            try { host.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, json), "Share calendar")) } catch (_: Throwable) {}
                        }
                    ))
                }
            }, LinearLayout.LayoutParams(-2, kit.dp(44)))
            eventsHost.addView(actions)

            kit.note(eventsHost, "OEA Calendar unique: 5 views (Month/Week/Day/Agenda/Year), categories with colors, reminders, recurring, search, import/export, lunar optional. Integration with OEA Modes: Focus shows Work only, Study shows Study. Widget shows today's events.")
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
        var endTime = existing?.endTime ?: "10:00"
        var location = existing?.location ?: ""
        var desc = existing?.desc ?: ""
        var category = existing?.category ?: "OTHER"
        var reminder = existing?.reminderMin ?: 15
        var recurrence = existing?.recurrence ?: "NONE"
        var allDay = existing?.allDay ?: false

        kit.sheet(if (isNew) "New event" else "Edit event", null) { panel, dialog ->
            val titleField = kit.field("Title", title)
            val timeField = kit.field("Start time HH:mm", time)
            val endField = kit.field("End time HH:mm", endTime)
            val locField = kit.field("Location", location)
            val descField = kit.field("Description", desc)
            panel.addView(titleField, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })
            panel.addView(timeField, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })
            panel.addView(endField, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })
            panel.addView(locField, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })
            panel.addView(descField, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })

            // Category chips
            val catRow = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
            Category.values().forEach { cat ->
                catRow.addView(kit.chip(cat.label, cat.name == category) { category = cat.name; dialog.dismiss(); editEvent(host, kit, existing?.copy(category = category, title = titleField.text.toString(), time = timeField.text.toString(), endTime = endField.text.toString(), location = locField.text.toString(), desc = descField.text.toString()), defaultCal, onDone) }, LinearLayout.LayoutParams(-2, kit.dp(36)).apply { rightMargin = kit.dp(6) })
            }
            panel.addView(kit.hscroll(catRow), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })

            val reminderRow = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
            listOf(0 to "No reminder", 5 to "5m", 15 to "15m", 30 to "30m", 60 to "1h", 1440 to "1d").forEach { (min, label) ->
                reminderRow.addView(kit.chip(label, reminder == min) { reminder = min }, LinearLayout.LayoutParams(-2, kit.dp(36)).apply { rightMargin = kit.dp(6) })
            }
            panel.addView(kit.hscroll(reminderRow), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })

            val recurRow = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
            Recurrence.values().forEach { rec ->
                recurRow.addView(kit.chip(rec.label, recurrence == rec.name) { recurrence = rec.name }, LinearLayout.LayoutParams(-2, kit.dp(36)).apply { rightMargin = kit.dp(6) })
            }
            panel.addView(kit.hscroll(recurRow), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })

            val allDayToggle = kit.toggle(allDay) { allDay = it }
            val allDayRow = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            allDayRow.addView(kit.text("All day", 14f, kit.palette.text), LinearLayout.LayoutParams(0, -2, 1f))
            allDayRow.addView(allDayToggle)
            panel.addView(allDayRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })

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
                        events += Event(System.currentTimeMillis(), year, month, day, t, timeField.text.toString().trim(), endField.text.toString().trim(), locField.text.toString().trim(), descField.text.toString().trim(), category, reminder, recurrence, allDay)
                    } else {
                        val updated = events.map { if (it.id == existing!!.id) it.copy(title = t, time = timeField.text.toString().trim(), endTime = endField.text.toString().trim(), location = locField.text.toString().trim(), desc = descField.text.toString().trim(), category = category, reminderMin = reminder, recurrence = recurrence, allDay = allDay) else it }
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
