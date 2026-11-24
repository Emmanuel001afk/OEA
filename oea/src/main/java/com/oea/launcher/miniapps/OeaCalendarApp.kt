package com.oea.launcher.miniapps

import android.app.Activity
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.oea.launcher.R
import com.oea.launcher.ui.OeaAppKit
import com.oea.launcher.ui.OeaKit
import com.oea.launcher.ui.OeaPalette
import com.oea.launcher.ui.OeaTheme
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
 * - Backup and restore: OEA's own JSON, or a real .ics file both ways
 * - Categories with colors, filter
 * - Moon phase for the selected day, from the synodic cycle
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

    /** One worker for provider reads; a single thread keeps them in order. */
    private val DEVICE = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "OeaCalendarDevice").apply { isDaemon = true }
    }

    /** One worker for file imports; a second import waits its turn instead of racing. */
    private val IMPORTER = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "OeaCalendarImport").apply { isDaemon = true }
    }

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
            val uri = android.provider.CalendarContract.Instances.CONTENT_URI.buildUpon()
            android.content.ContentUris.appendId(uri, start)
            android.content.ContentUris.appendId(uri, end)
            host.contentResolver.query(
                uri.build(),
                arrayOf(
                    android.provider.CalendarContract.Instances.TITLE,
                    android.provider.CalendarContract.Instances.BEGIN,
                    android.provider.CalendarContract.Instances.END,
                    android.provider.CalendarContract.Instances.ALL_DAY,
                ),
                null, null,
                android.provider.CalendarContract.Instances.BEGIN + " ASC",
            )?.use { c ->
                val titleI = c.getColumnIndex(android.provider.CalendarContract.Instances.TITLE)
                val beginI = c.getColumnIndex(android.provider.CalendarContract.Instances.BEGIN)
                val endI = c.getColumnIndex(android.provider.CalendarContract.Instances.END)
                val allDayI = c.getColumnIndex(android.provider.CalendarContract.Instances.ALL_DAY)
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

    private const val REQUEST_IMPORT = 1005

    /** Set while the file picker is open: what to report once the file is read. */
    private var pendingImport: ((added: Int, skipped: Int) -> Unit)? = null

    /**
     * The one activity result the calendar needs: the imported file. Everything
     * else about a picked document happens here so no screen has to remember it.
     */
    internal fun handleResult(host: Activity, requestCode: Int, resultCode: Int, data: android.content.Intent?): Boolean {
        val pending = pendingImport ?: return false
        if (requestCode != REQUEST_IMPORT) return false
        pendingImport = null
        if (resultCode != Activity.RESULT_OK) return true
        val uri = data?.data ?: return true
        // One thread for every import (a new one per file leaked a thread each time),
        // and the store write goes through the one place that keeps the reminders and
        // the widget in step with it.
        IMPORTER.execute {
            val text = runCatching {
                host.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            val parsed = text?.let { if (it.contains("BEGIN:VCALENDAR")) fromIcs(it) else decode(it) } ?: emptyList()
            val existing = events(host)
            val identity = { e: Event -> "${e.year}-${e.month}-${e.day}|${e.title.lowercase(Locale.getDefault())}|${e.time}" }
            val known = existing.map(identity).toSet()
            val fresh = parsed.filter { !known.contains(identity(it)) }
            val skipped = parsed.size - fresh.size
            // Same title and day but a different time is a second occurrence, not a
            // duplicate; a clash on the id would corrupt the store, so re-stamp them.
            val withTimes = fresh.map { it.copy(id = freshEventId()) }
            saveEvents(host, existing + withTimes)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching { pending(withTimes.size, skipped) }
            }
        }
        return true
    }

    /** Writes the visible events out as an iCalendar file; see [OeaCalendarIcs]. */
    internal fun toIcs(events: List<Event>): String = OeaCalendarIcs.toIcs(events)

    /** Reads an iCalendar file written by any calendar; see [OeaCalendarIcs]. */
    internal fun fromIcs(text: String): List<Event> = OeaCalendarIcs.fromIcs(text)

    /**
     * The moon for one day, from the synodic cycle. It is arithmetic against a known
     * new moon rather than an ephemeris, so it is within a day — which is all a
     * calendar row can show anyway.
     */
    private fun moonPhase(cal: Calendar): String {
        val knownNewMoon = 946147200000L // 31 Dec 1999 23:14 UTC
        val synodic = 29.530588853f * 24f * 60f * 60f * 1000f
        val days = cal.timeInMillis - knownNewMoon
        val cycle = ((days % synodic) / synodic).let { if (it < 0f) it + 1f else it }
        return when {
            cycle < 0.033f || cycle > 0.967f -> "🌑 new moon"
            cycle < 0.22f -> "🌒 waxing crescent"
            cycle < 0.28f -> "🌓 first quarter"
            cycle < 0.47f -> "🌔 waxing gibbous"
            cycle < 0.53f -> "🌕 full moon"
            cycle < 0.72f -> "🌖 waning gibbous"
            cycle < 0.78f -> "🌗 last quarter"
            else -> "🌘 waning crescent"
        }
    }

    private fun shareText(host: Activity, type: String, name: String, body: String) {
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND)
            .setType(type)
            .putExtra(android.content.Intent.EXTRA_SUBJECT, name)
            .putExtra(android.content.Intent.EXTRA_TEXT, body)
        runCatching { host.startActivity(android.content.Intent.createChooser(intent, "Share $name")) }
            .onFailure { android.widget.Toast.makeText(host, "Nothing here accepts text", android.widget.Toast.LENGTH_SHORT).show() }
    }

    private val ORDER = compareBy<Event>({ it.year }, { it.month }, { it.day }, { it.time }, { it.title })

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
        }.sortedWith(ORDER)
    }

    /** One field can never contain a separator, or it would split a row in two. */
    private fun clean(value: String): String =
        value.replace(SEP_FIELD, " ").replace(SEP_ROW, " ").trim()

    private fun encode(list: List<Event>): String = list.sortedWith(ORDER).joinToString(SEP_ROW) { e ->
        listOf(
            e.id.toString(), e.year.toString(), e.month.toString(), e.day.toString(),
            clean(e.title), clean(e.time), clean(e.endTime), clean(e.location), clean(e.desc),
            e.category.ifBlank { "OTHER" }, e.reminderMin.coerceAtLeast(0).toString(),
            e.recurrence.ifBlank { "NONE" }, if (e.allDay) "1" else "0"
        ).joinToString(SEP_FIELD)
    }

    // ------------------------------------------------------------------- store

    /**
     * The store, decoded once per change.
     *
     * `refresh()` used to call `decode(prefs.getString(...))` on every paint: every day
     * tap, every month step, every keystroke and every filter chip re-split the whole
     * blob and allocated a fresh `Event` for every row. With a few hundred events that
     * is milliseconds of garbage per finger. The raw string is the cache key, so any
     * writer — this object, the importer, a restore — invalidates it by changing it.
     */
    private var cacheRaw: String? = null
    private var cacheEvents: List<Event> = emptyList()

    internal fun events(ctx: Context): List<Event> {
        val raw = prefs(ctx).getString(KEY_EVENTS, "").orEmpty()
        if (raw == cacheRaw) return cacheEvents
        val parsed = decode(raw)
        cacheRaw = raw
        cacheEvents = parsed
        return parsed
    }

    /**
     * Writes OEA's own events, then keeps the two things that read the store behind its
     * back in step: the reminder alarms and the home-screen widget.
     */
    internal fun saveEvents(ctx: Context, list: List<Event>) {
        val encoded = encode(list)
        prefs(ctx).edit().putString(KEY_EVENTS, encoded).apply()
        cacheRaw = encoded
        cacheEvents = decode(encoded)
        runCatching { OeaCalendarReminders.sync(ctx) }
        runCatching { com.oea.launcher.widgets.OeaWidgetStudio.refreshWidgets(ctx, "calendar") }
    }

    private val idLock = Any()
    private var idCounter = 0L

    /**
     * A positive id no stored event can already have.
     *
     * New events took `System.currentTimeMillis()`, so two created in the same
     * millisecond shared an id and deleting one deleted both. Imports used
     * `System.nanoTime()`, which is allowed to be negative — a negative id means
     * "device calendar event" to every view, so an imported event with one became
     * read-only and was skipped by the export.
     */
    internal fun freshEventId(seed: Long? = null): Long = synchronized(idLock) {
        val floor = maxOf(
            seed ?: 0L,
            System.currentTimeMillis(),
            cacheEvents.maxOfOrNull { it.id }?.coerceAtLeast(0L) ?: 0L
        )
        idCounter = if (idCounter > floor) idCounter + 1 else floor + 1
        idCounter
    }

    fun build(host: Activity, palette: OeaPalette): View {
        val kit = OeaAppKit(host, palette)
        host.miniResultHook = { requestCode, resultCode, data -> handleResult(host, requestCode, resultCode, data) }
        var currentCal = Calendar.getInstance()
        var selectedCal = Calendar.getInstance()
        var viewMode = runCatching { ViewMode.valueOf(prefs(host).getString(KEY_VIEW, "MONTH") ?: "MONTH") }.getOrDefault(ViewMode.MONTH)
        var searchQuery = ""
        var filterCategory: String? = null
        var refresh: () -> Unit = {}
        // The phone's own calendar is a content provider: the Instances query walks
        // every account's expansion of the month, which is hundreds of milliseconds
        // on a busy calendar. It used to run inline in refresh(), so every keystroke
        // in the search box, every month step and every day tap waited for it on the
        // UI thread. Now a month is read once, off the main thread, and painted when
        // it arrives; the second and later reads of the same month are free.
        val deviceMonths = HashMap<String, List<Event>>()
        var deviceLoading = ""
        val deviceMain = android.os.Handler(android.os.Looper.getMainLooper())
        fun deviceKey(cal: Calendar) = "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH)}"
        fun requestDeviceMonth(cal: Calendar) {
            if (!hasCalendarPermission(host)) return
            val key = deviceKey(cal)
            if (deviceMonths.containsKey(key) || deviceLoading == key) return
            deviceLoading = key
            val month = cal.clone() as Calendar
            DEVICE.execute {
                val events = loadDeviceEvents(host, month)
                deviceMain.post {
                    if (host.isDestroyed || deviceLoading != key) return@post
                    deviceMonths[key] = events
                    deviceLoading = ""
                    refresh()
                }
            }
        }
        host.miniPermissionHook = {
            // A permission granted after the first paint has to be believed: the
            // months cached while it was denied were all empty lists.
            deviceMonths.clear()
            deviceLoading = ""
            refresh()
        }
        host.miniResumeHook = {
            // An event added in another app is not in a month cached before the user
            // left; a resume drops the cache and asks for the visible month again.
            // The read is off the UI thread, so this costs the first frame nothing.
            deviceMonths.clear()
            deviceLoading = ""
            refresh()
            // Coming back is also the moment the alarm table is known to be current:
            // another calendar app may have been the last thing to run.
            runCatching { OeaCalendarReminders.sync(host) }
        }

        val indexBack = host.miniIndexBack()
        val navItems = listOf(
            OeaAppKit.NavItem(ViewMode.MONTH.name, "Month", R.drawable.oic_grid_view),
            OeaAppKit.NavItem(ViewMode.WEEK.name, "Week", R.drawable.oic_view_carousel),
            OeaAppKit.NavItem(ViewMode.DAY.name, "Day", R.drawable.oic_schedule),
            OeaAppKit.NavItem(ViewMode.AGENDA.name, "Agenda", R.drawable.oic_view_agenda)
        )
        val shell = kit.appShell(
            title = "Calendar",
            navItems = navItems,
            initialNav = if (viewMode == ViewMode.YEAR) ViewMode.MONTH.name else viewMode.name,
            onBack = indexBack
        ) { id ->
            viewMode = runCatching { ViewMode.valueOf(id) }.getOrDefault(ViewMode.MONTH)
            prefs(host).edit().putString(KEY_VIEW, viewMode.name).apply()
            refresh()
        }
        val column = shell.body

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
                    saveEvents(host, events(host) + Event(
                        freshEventId(),
                        calStart.get(Calendar.YEAR), calStart.get(Calendar.MONTH), calStart.get(Calendar.DAY_OF_MONTH),
                        title, time, endTime, location, desc, "OTHER", 15, "NONE", allDay
                    ))
                    selectedCal = calStart
                    currentCal = calStart
                }
                host.intent = null
            }
        }

        val searchBox = kit.searchField("Search events or locations", onQuery = { q ->
            searchQuery = q.trim()
            refresh()
        })
        val searchRow = android.widget.FrameLayout(host).apply {
            setPadding(kit.dp(16), 0, kit.dp(16), kit.dp(8))
            addView(searchBox.root, android.widget.FrameLayout.LayoutParams(-1, -2))
            visibility = View.GONE
        }
        shell.headerExtras.addView(searchRow, LinearLayout.LayoutParams(-1, -2))

        fun toggleSearch() {
            if (searchRow.visibility == View.VISIBLE) {
                searchRow.visibility = View.GONE
                searchBox.close()
                if (searchQuery.isNotEmpty()) {
                    searchQuery = ""
                    searchBox.setText("")
                    refresh()
                }
            } else {
                searchRow.visibility = View.VISIBLE
                searchBox.focus()
            }
        }

        fun openBackupSheet(events: List<Event>) {
            val json = encode(events.filter { it.id >= 0L })
            kit.sheet("Backup & import", "${events.count { it.id >= 0L }} OEA events") { panel, dialog ->
                val group = kit.group(panel)
                kit.row(group, R.drawable.oic_share_filled, "Export as .ics", "Works with Google, Apple and standard calendars") {
                    dialog.dismiss()
                    shareText(host, "text/calendar", "oea-calendar.ics", toIcs(events.filter { it.id >= 0L }))
                }
                kit.row(group, R.drawable.oic_share_filled, "Export as backup", "Share OEA's compact calendar archive") {
                    dialog.dismiss()
                    shareText(host, "text/plain", "oea-calendar.json", json)
                }
                kit.row(group, R.drawable.oic_file_copy, "Copy backup", "Copy your events to the clipboard") {
                    runCatching {
                        host.getSystemService(android.content.ClipboardManager::class.java)
                            ?.setPrimaryClip(android.content.ClipData.newPlainText("OEA calendar", json))
                        android.widget.Toast.makeText(host, "Calendar copied", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    dialog.dismiss()
                }
                kit.row(group, R.drawable.oic_file_download, "Import file", "Open an .ics or OEA backup file", chevron = true) {
                    dialog.dismiss()
                    pendingImport = { added, skipped ->
                        android.widget.Toast.makeText(
                            host,
                            if (added > 0) "$added event(s) added" + if (skipped > 0) ", $skipped already present" else ""
                            else "No new events found in that file",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                        refresh()
                    }
                    runCatching {
                        host.startActivityForResult(
                            android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
                                addCategory(android.content.Intent.CATEGORY_OPENABLE)
                                type = "*/*"
                            },
                            REQUEST_IMPORT
                        )
                    }.onFailure { android.widget.Toast.makeText(host, "Could not open file picker", android.widget.Toast.LENGTH_SHORT).show() }
                }
            }
        }

        shell.bar.addAction(R.drawable.oic_search, "Search events") { toggleSearch() }
        shell.bar.addAction(R.drawable.oic_add, "New event") {
            editEvent(host, kit, null, selectedCal) { refresh() }
        }
        shell.bar.addAction(R.drawable.oic_more_vert, "Calendar options") {
            val monday = prefs(host).getBoolean(KEY_WEEK_START, false)
            kit.actions("Calendar options", null, listOf(
                OeaAppKit.Action(R.drawable.oic_refresh, "Jump to today", SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(java.util.Date())) {
                    currentCal = Calendar.getInstance()
                    selectedCal = Calendar.getInstance()
                    refresh()
                },
                OeaAppKit.Action(R.drawable.oic_layers, "Year overview", SimpleDateFormat("yyyy", Locale.getDefault()).format(currentCal.time)) {
                    viewMode = ViewMode.YEAR
                    prefs(host).edit().putString(KEY_VIEW, viewMode.name).apply()
                    refresh()
                },
                OeaAppKit.Action(R.drawable.oic_schedule, "First day of week", if (monday) "Monday · Tap for Sunday" else "Sunday · Tap for Monday") {
                    prefs(host).edit().putBoolean(KEY_WEEK_START, !monday).apply()
                    refresh()
                },
                OeaAppKit.Action(R.drawable.oic_file_download, "Backup & import", "Export or import .ics calendar files") {
                    openBackupSheet(events(host))
                }
            ))
        }

        host.miniBackHook = {
            when {
                searchRow.visibility == View.VISIBLE -> { toggleSearch(); true }
                viewMode == ViewMode.YEAR -> {
                    viewMode = ViewMode.MONTH
                    prefs(host).edit().putString(KEY_VIEW, viewMode.name).apply()
                    refresh()
                    true
                }
                else -> false
            }
        }

        fun stepPeriod(dir: Int) {
            when (viewMode) {
                ViewMode.MONTH -> {
                    currentCal.set(Calendar.DAY_OF_MONTH, 1)
                    currentCal.add(Calendar.MONTH, dir)
                    selectedCal = currentCal.clone() as Calendar
                }
                ViewMode.WEEK -> {
                    currentCal.add(Calendar.WEEK_OF_YEAR, dir)
                    selectedCal = currentCal.clone() as Calendar
                }
                ViewMode.DAY -> {
                    selectedCal.add(Calendar.DAY_OF_YEAR, dir)
                    currentCal = selectedCal.clone() as Calendar
                }
                ViewMode.YEAR -> {
                    currentCal.add(Calendar.YEAR, dir)
                    selectedCal.set(Calendar.YEAR, currentCal.get(Calendar.YEAR))
                }
                ViewMode.AGENDA -> Unit
            }
            refresh()
        }

        val stepper = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, kit.dp(8))
        }
        val previousButton = kit.circleIconButton(R.drawable.oic_chevron_right, "Previous period", palette.card, palette.text) { stepPeriod(-1) }
            .apply { rotation = 180f }
        val nextButton = kit.circleIconButton(R.drawable.oic_chevron_right, "Next period", palette.card, palette.text) { stepPeriod(1) }
        val monthText = kit.text("", 18f, palette.text, OeaType.medium).apply {
            gravity = Gravity.CENTER
        }
        val todayChip = kit.tonalButton("Today") {
            currentCal = Calendar.getInstance()
            selectedCal = Calendar.getInstance()
            refresh()
        }
        stepper.addView(previousButton, LinearLayout.LayoutParams(kit.dp(42), kit.dp(42)))
        stepper.addView(monthText, LinearLayout.LayoutParams(0, -2, 1f).apply {
            leftMargin = kit.dp(8); rightMargin = kit.dp(8)
        })
        stepper.addView(nextButton, LinearLayout.LayoutParams(kit.dp(42), kit.dp(42)).apply { rightMargin = kit.dp(8) })
        stepper.addView(todayChip, LinearLayout.LayoutParams(-2, kit.dp(42)))
        column.addView(stepper, LinearLayout.LayoutParams(-1, -2))

        val filterBar = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
        column.addView(kit.hscroll(filterBar), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(10) })

        val deviceHost = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        column.addView(deviceHost, LinearLayout.LayoutParams(-1, -2))

        val gridHost = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        column.addView(gridHost)
        val eventsHost = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        column.addView(eventsHost)

        fun renderFilterBar() {
            filterBar.removeAllViews()
            filterBar.addView(kit.chip("All", filterCategory == null) { filterCategory = null; refresh() }, LinearLayout.LayoutParams(-2, kit.dp(38)).apply { rightMargin = kit.dp(6) })
            Category.values().forEach { cat ->
                filterBar.addView(kit.chip(cat.label, filterCategory == cat.name) {
                    filterCategory = if (filterCategory == cat.name) null else cat.name
                    refresh()
                }, LinearLayout.LayoutParams(-2, kit.dp(38)).apply { rightMargin = kit.dp(6) })
            }
        }

        fun eventSubtitle(ev: Event, includeDate: Boolean, on: java.time.LocalDate? = null): String {
            val cat = runCatching { Category.valueOf(ev.category) }.getOrDefault(Category.OTHER)
            val rec = runCatching { Recurrence.valueOf(ev.recurrence) }.getOrDefault(Recurrence.NONE)
            val parts = ArrayList<String>()
            if (includeDate) {
                // A repeated appearance has its own date; the event only knows its anchor.
                val date = on ?: runCatching { java.time.LocalDate.of(ev.year, ev.month + 1, ev.day) }.getOrNull()
                if (date != null) {
                    val millis = date.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                    parts += SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(java.util.Date(millis))
                }
            }
            parts += when {
                ev.allDay || ev.time.isBlank() -> "All day"
                ev.endTime.isNotBlank() && ev.endTime != ev.time -> "${ev.time} – ${ev.endTime}"
                else -> ev.time
            }
            parts += cat.label
            if (ev.location.isNotBlank()) parts += ev.location
            if (rec != Recurrence.NONE) parts += rec.label
            if (ev.id < 0L) parts += "Device calendar"
            return parts.joinToString(" · ")
        }

        refresh = {
            if (viewMode != ViewMode.YEAR) shell.nav?.select(viewMode.name, notify = false)
            shell.bar.setBack(
                if (viewMode == ViewMode.YEAR) ({
                    viewMode = ViewMode.MONTH
                    prefs(host).edit().putString(KEY_VIEW, viewMode.name).apply()
                    refresh()
                }) else indexBack
            )
            renderFilterBar()
            val sdfMonth = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
            shell.bar.title.text = when (viewMode) {
                ViewMode.MONTH -> "Calendar"
                ViewMode.WEEK -> "Week"
                ViewMode.DAY -> "Day"
                ViewMode.AGENDA -> "Agenda"
                ViewMode.YEAR -> SimpleDateFormat("yyyy", Locale.getDefault()).format(currentCal.time)
            }
            monthText.text = when (viewMode) {
                ViewMode.MONTH -> sdfMonth.format(currentCal.time)
                ViewMode.WEEK -> "Week of ${SimpleDateFormat("MMM d", Locale.getDefault()).format(currentCal.time)}"
                ViewMode.DAY -> SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(selectedCal.time)
                ViewMode.AGENDA -> "Upcoming & saved"
                ViewMode.YEAR -> SimpleDateFormat("yyyy", Locale.getDefault()).format(currentCal.time)
            }
            stepper.visibility = if (viewMode == ViewMode.AGENDA) View.GONE else View.VISIBLE

            gridHost.removeAllViews()
            eventsHost.removeAllViews()
            deviceHost.removeAllViews()
            val deviceEvents = deviceMonths[deviceKey(currentCal)].orEmpty()
            requestDeviceMonth(currentCal)
            if (!hasCalendarPermission(host)) {
                kit.row(kit.group(deviceHost), R.drawable.oic_schedule, "Connect device calendar",
                    "Show events from Google, Exchange and CalDAV accounts", chevron = true) {
                    runCatching {
                        host.requestPermissions(arrayOf("android.permission.READ_CALENDAR", "android.permission.WRITE_CALENDAR"), 2101)
                    }
                }
            }
            val allEvents = events(host) + deviceEvents
            val filteredEvents = allEvents.filter { ev ->
                (filterCategory == null || ev.category == filterCategory) &&
                (searchQuery.isBlank() || ev.title.contains(searchQuery, true) || ev.location.contains(searchQuery, true) || ev.desc.contains(searchQuery, true))
            }
            // Repeating events are expanded for the range on screen, never stored.
            val todayDate = java.time.LocalDate.now()
            fun appearances(from: java.time.LocalDate, toExclusive: java.time.LocalDate) =
                OeaCalendarRecurrence.occurrences(filteredEvents, from, toExclusive)
            shell.bar.setSubtitle(
                when {
                    searchQuery.isNotBlank() -> "${filteredEvents.size} matching ${if (filteredEvents.size == 1) "event" else "events"}"
                    deviceEvents.isNotEmpty() -> "${deviceEvents.size} synced this month"
                    else -> ""
                }
            )

            when (viewMode) {
                ViewMode.MONTH -> {
                    val calCard = LinearLayout(host).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(kit.dp(10), kit.dp(12), kit.dp(10), kit.dp(10))
                        background = kit.rounded(palette.card, 22, palette.stroke, 1)
                    }
                    val weekHeader = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
                    val weekStartMonday = prefs(host).getBoolean(KEY_WEEK_START, false)
                    val days = if (weekStartMonday) listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun") else listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
                    days.forEach { day ->
                        weekHeader.addView(kit.text(day, 11.5f, palette.textSecondary, OeaType.medium).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, -2, 1f))
                    }
                    calCard.addView(weekHeader, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })

                    val month = currentCal.get(Calendar.MONTH)
                    val year = currentCal.get(Calendar.YEAR)
                    val firstDay = Calendar.getInstance().apply { set(year, month, 1) }
                    var startOffset = firstDay.get(Calendar.DAY_OF_WEEK) - 1
                    if (weekStartMonday) startOffset = (startOffset + 6) % 7
                    val firstOfMonth = java.time.LocalDate.of(year, month + 1, 1)
                    val gridStart = firstOfMonth.minusDays(startOffset.toLong())
                    val selectedDate = runCatching {
                        java.time.LocalDate.of(
                            selectedCal.get(Calendar.YEAR),
                            selectedCal.get(Calendar.MONTH) + 1,
                            selectedCal.get(Calendar.DAY_OF_MONTH)
                        )
                    }.getOrDefault(todayDate)
                    // The dots come from the same expansion the other views use, so a
                    // weekly event dots every week it actually happens.
                    val byDay = appearances(gridStart, gridStart.plusDays(42)).groupBy { it.date }
                    var rowIndex = 0
                    while (rowIndex < 6) {
                        val week = (0 until 7).map { gridStart.plusDays((rowIndex * 7 + it).toLong()) }
                        // A row with no day of this month is not drawn: a five-week month
                        // does not get a sixth, empty one.
                        if (rowIndex >= 3 && week.none { it.year == year && it.monthValue - 1 == month }) break
                        val rowLayout = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
                        week.forEachIndexed { col, cellDate ->
                            val inMonth = cellDate.year == year && cellDate.monthValue - 1 == month
                            val isSelected = inMonth && cellDate == selectedDate
                            val isToday = inMonth && cellDate == todayDate
                            val dayEvs = if (inMonth) byDay[cellDate].orEmpty() else emptyList()
                            val cell = LinearLayout(host).apply {
                                orientation = LinearLayout.VERTICAL
                                gravity = Gravity.CENTER
                                minimumHeight = kit.dp(44)
                                setPadding(kit.dp(2), kit.dp(6), kit.dp(2), kit.dp(6))
                                background = when {
                                    isSelected -> kit.rounded(palette.accent, 14)
                                    isToday -> kit.rounded(palette.accentSoft, 14, OeaTheme.alpha(palette.accent, 0x55), 1)
                                    else -> null
                                }
                                isClickable = inMonth
                                isFocusable = inMonth
                                setOnClickListener {
                                    if (!inMonth) return@setOnClickListener
                                    selectedCal = Calendar.getInstance().apply {
                                        set(cellDate.year, cellDate.monthValue - 1, cellDate.dayOfMonth, 12, 0, 0)
                                        set(Calendar.MILLISECOND, 0)
                                    }
                                    refresh()
                                }
                            }
                            cell.addView(kit.text(if (inMonth) cellDate.dayOfMonth.toString() else "", 14f, when {
                                !inMonth -> palette.textTertiary
                                isSelected -> palette.onAccent
                                isToday -> palette.onAccentSoft
                                else -> palette.text
                            }, if (isSelected || isToday) OeaType.bold else OeaType.medium).apply { gravity = Gravity.CENTER })
                            val dotColor = if (dayEvs.isNotEmpty()) (if (isSelected) palette.onAccent else palette.accent) else android.graphics.Color.TRANSPARENT
                            cell.addView(View(host).apply { background = kit.oval(dotColor) }, LinearLayout.LayoutParams(kit.dp(5), kit.dp(5)).apply { topMargin = kit.dp(3); gravity = Gravity.CENTER })
                            rowLayout.addView(cell, LinearLayout.LayoutParams(0, -2, 1f).apply {
                                if (col > 0) leftMargin = kit.dp(2)
                            })
                        }
                        calCard.addView(rowLayout, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(2) })
                        rowIndex++
                    }
                    gridHost.addView(calCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(6) })
                }
                ViewMode.WEEK -> {
                    val startOfWeek = currentCal.clone() as Calendar
                    startOfWeek.set(Calendar.DAY_OF_WEEK, if (prefs(host).getBoolean(KEY_WEEK_START, false)) Calendar.MONDAY else Calendar.SUNDAY)
                    val weekStartDate = runCatching {
                        java.time.LocalDate.of(
                            startOfWeek.get(Calendar.YEAR),
                            startOfWeek.get(Calendar.MONTH) + 1,
                            startOfWeek.get(Calendar.DAY_OF_MONTH)
                        )
                    }.getOrDefault(todayDate)
                    val weekByDay = appearances(weekStartDate, weekStartDate.plusDays(7)).groupBy { it.date }
                    val selectedDate = runCatching {
                        java.time.LocalDate.of(
                            selectedCal.get(Calendar.YEAR),
                            selectedCal.get(Calendar.MONTH) + 1,
                            selectedCal.get(Calendar.DAY_OF_MONTH)
                        )
                    }.getOrDefault(todayDate)
                    (0 until 7).map { weekStartDate.plusDays(it.toLong()) }.forEach { dayDate ->
                        val dayCal = Calendar.getInstance().apply {
                            set(dayDate.year, dayDate.monthValue - 1, dayDate.dayOfMonth, 12, 0, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        val isSelected = dayDate == selectedDate
                        val dayEvents = weekByDay[dayDate].orEmpty().map { it.event }
                        val row = LinearLayout(host).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(kit.dp(14), kit.dp(10), kit.dp(14), kit.dp(10))
                            background = kit.ripple(kit.rounded(if (isSelected) palette.accentSoft else palette.card, 16, palette.stroke, 1), 16)
                            isClickable = true
                            isFocusable = true
                            setOnClickListener { selectedCal = dayCal.clone() as Calendar; refresh() }
                        }
                        val headRow = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                        headRow.addView(
                            kit.text(SimpleDateFormat("EEEE, MMM d", Locale.getDefault()).format(dayCal.time), 14.5f, if (isSelected) palette.onAccentSoft else palette.text, OeaType.medium),
                            LinearLayout.LayoutParams(0, -2, 1f)
                        )
                        if (dayEvents.isNotEmpty()) {
                            headRow.addView(kit.badge("${dayEvents.size}", if (isSelected) palette.accent else palette.accentSoft, if (isSelected) palette.onAccent else palette.onAccentSoft))
                        }
                        row.addView(headRow)
                        dayEvents.take(3).forEach { ev ->
                            val t = if (ev.allDay || ev.time.isBlank()) "All day" else ev.time
                            row.addView(kit.text("$t · ${ev.title}", 12.5f, if (isSelected) palette.onAccentSoft else palette.textSecondary).apply { setPadding(0, kit.dp(3), 0, 0) })
                        }
                        gridHost.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(6) })
                    }
                }
                ViewMode.DAY -> {
                    val dayDate = runCatching {
                        java.time.LocalDate.of(
                            selectedCal.get(Calendar.YEAR),
                            selectedCal.get(Calendar.MONTH) + 1,
                            selectedCal.get(Calendar.DAY_OF_MONTH)
                        )
                    }.getOrDefault(todayDate)
                    val dayEvents = appearances(dayDate, dayDate.plusDays(1)).map { it.event }
                    val allDayEvents = dayEvents.filter { it.allDay || it.time.isBlank() }
                    if (allDayEvents.isNotEmpty()) {
                        val g = kit.group(gridHost)
                        allDayEvents.forEach { ev ->
                            val readOnly = ev.id < 0L
                            kit.row(g, R.drawable.oic_schedule, ev.title, eventSubtitle(ev, false), chevron = !readOnly,
                                onClick = if (readOnly) null else ({ editEvent(host, kit, ev) { refresh() } }))
                        }
                    }
                    val activeHours = (6..22).toMutableSet()
                    dayEvents.forEach { ev -> ev.time.take(2).toIntOrNull()?.let { activeHours += it } }
                    val timelineCard = LinearLayout(host).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(kit.dp(12), kit.dp(8), kit.dp(12), kit.dp(8))
                        background = kit.rounded(palette.card, 20, palette.stroke, 1)
                    }
                    activeHours.sorted().forEachIndexed { idx, h ->
                        val evs = dayEvents.filter { !it.allDay && it.time.startsWith(String.format(Locale.US, "%02d:", h)) }
                        if (idx > 0) {
                            timelineCard.addView(View(host).apply { setBackgroundColor(palette.stroke) }, LinearLayout.LayoutParams(-1, 1))
                        }
                        val row = LinearLayout(host).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            minimumHeight = kit.dp(40)
                            setPadding(kit.dp(4), kit.dp(6), kit.dp(4), kit.dp(6))
                            isClickable = true
                            setOnClickListener {
                                val slot = (selectedCal.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, 0) }
                                editEvent(host, kit, null, slot, defaultHour = h) { refresh() }
                            }
                        }
                        row.addView(kit.mono(kit.text(String.format(Locale.US, "%02d:00", h), 12f, palette.textSecondary)), LinearLayout.LayoutParams(kit.dp(52), -2))
                        if (evs.isNotEmpty()) {
                            val col = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
                            evs.forEach { ev ->
                                col.addView(
                                    kit.text("${ev.time}  ${ev.title}", 13.5f, palette.onAccentSoft, OeaType.medium).apply {
                                        setPadding(kit.dp(10), kit.dp(6), kit.dp(10), kit.dp(6))
                                        background = kit.rounded(palette.accentSoft, 10)
                                        if (ev.id >= 0L) setOnClickListener { editEvent(host, kit, ev) { refresh() } }
                                    },
                                    LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(3) }
                                )
                            }
                            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
                        }
                        timelineCard.addView(row)
                    }
                    gridHost.addView(timelineCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(6) })
                }
                ViewMode.AGENDA -> {
                    // A year of real appearances, not every stored event ever saved: a
                    // weekly event belongs in an agenda twelve months of times over.
                    val sorted = appearances(todayDate, todayDate.plusDays(366))
                    if (sorted.isEmpty()) {
                        gridHost.addView(
                            kit.emptyState(R.drawable.oic_schedule, "No scheduled events", "Add an event with start time, repeat rules and reminders.", "New event") {
                                editEvent(host, kit, null, selectedCal) { refresh() }
                            },
                            LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(8) }
                        )
                    } else {
                        val g = kit.group(gridHost)
                        sorted.take(80).forEach { occ ->
                            val ev = occ.event
                            val readOnly = ev.id < 0L
                            val action: (() -> Unit)? = if (readOnly) null else ({ editEvent(host, kit, ev) { refresh() } })
                            kit.row(g, R.drawable.oic_schedule, ev.title, eventSubtitle(ev, includeDate = true, on = occ.date), chevron = !readOnly, onClick = action)
                        }
                    }
                }
                ViewMode.YEAR -> {
                    val g = kit.group(gridHost)
                    for (m in 0 until 12) {
                        val cal = Calendar.getInstance().apply { set(currentCal.get(Calendar.YEAR), m, 1) }
                        val monthStart = java.time.LocalDate.of(currentCal.get(Calendar.YEAR), m + 1, 1)
                        val count = appearances(monthStart, monthStart.plusMonths(1)).size
                        kit.row(
                            g,
                            R.drawable.oic_grid_view,
                            SimpleDateFormat("MMMM", Locale.getDefault()).format(cal.time),
                            if (count == 0) "No events" else "$count ${if (count == 1) "event" else "events"}",
                            chevron = true
                        ) {
                            currentCal.set(Calendar.MONTH, m)
                            viewMode = ViewMode.MONTH
                            prefs(host).edit().putString(KEY_VIEW, viewMode.name).apply()
                            refresh()
                        }
                    }
                }
            }

            if (viewMode == ViewMode.MONTH || viewMode == ViewMode.WEEK) {
                val selectedDate = runCatching {
                    java.time.LocalDate.of(
                        selectedCal.get(Calendar.YEAR),
                        selectedCal.get(Calendar.MONTH) + 1,
                        selectedCal.get(Calendar.DAY_OF_MONTH)
                    )
                }.getOrDefault(todayDate)
                val dayEvents = appearances(selectedDate, selectedDate.plusDays(1)).map { it.event }
                kit.sectionTitle(
                    eventsHost,
                    "${SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(selectedCal.time)} · ${moonPhase(selectedCal)}"
                )
                if (dayEvents.isEmpty()) {
                    val emptyGroup = kit.group(eventsHost)
                    kit.row(emptyGroup, R.drawable.oic_add, "No events on this day", "Tap to schedule something", chevron = true) {
                        editEvent(host, kit, null, selectedCal) { refresh() }
                    }
                } else {
                    val g = kit.group(eventsHost)
                    dayEvents.forEach { ev ->
                        val readOnly = ev.id < 0L
                        val action: (() -> Unit)? = if (readOnly) null else ({ editEvent(host, kit, ev) { refresh() } })
                        kit.row(g, R.drawable.oic_schedule, ev.title, eventSubtitle(ev, includeDate = false), chevron = !readOnly, onClick = action)
                    }
                    eventsHost.addView(
                        kit.primaryButton("New event", R.drawable.oic_add) {
                            editEvent(host, kit, null, selectedCal) { refresh() }
                        },
                        LinearLayout.LayoutParams(-1, kit.dp(48)).apply { topMargin = kit.dp(6) }
                    )
                }
            }
        }
        refresh()
        // Reminders outlive the screen, so they are armed every time it is opened.
        runCatching { OeaCalendarReminders.sync(host) }
        return shell.root
    }

    private fun parseEventHm(value: String, fallbackH: Int, fallbackM: Int = 0): Pair<Int, Int> {
        val parts = value.trim().split(":")
        val h = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: fallbackH
        val m = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: fallbackM
        return h to m
    }

    private fun formatEventHm(h: Int, m: Int): String = String.format(Locale.US, "%02d:%02d", h.coerceIn(0, 23), m.coerceIn(0, 59))

    /**
     * A date, chosen with wheels.
     *
     * The editor could change an event's time but not its day: moving an event meant
     * deleting it and typing it again. The day wheel is rebuilt against the chosen
     * month, so 31 January → February becomes 28/29 rather than an invalid date.
     */
    private fun pickDate(
        host: Activity,
        kit: OeaAppKit,
        year0: Int,
        month0: Int,
        day0: Int,
        onPicked: (year: Int, month: Int, day: Int) -> Unit
    ) {
        val today = java.time.LocalDate.now()
        val palette = kit.palette
        kit.sheet("Date", "When this event happens") { panel, dialog ->
            var year = year0
            var month = month0
            var day = day0
            fun lengthOf(y: Int, m: Int) = runCatching { java.time.YearMonth.of(y, m + 1).lengthOfMonth() }.getOrDefault(31)
            val dayWheel = com.oea.launcher.ui.OeaWheelPicker(host, palette, 1, lengthOf(year, month), day.coerceIn(1, lengthOf(year, month)), wrap = true, label = "Day")
            val monthWheel = com.oea.launcher.ui.OeaWheelPicker(
                host, palette, 0, 11, month.coerceIn(0, 11), wrap = true, label = "Month",
                formatter = { java.time.Month.of(it + 1).getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault()) }
            )
            val yearWheel = com.oea.launcher.ui.OeaWheelPicker(
                host, palette, today.year - 5, today.year + 10, year.coerceIn(today.year - 5, today.year + 10), wrap = false, label = "Year"
            )
            fun keepDayValid() {
                val max = lengthOf(yearWheel.value, monthWheel.value)
                dayWheel.maxValue = max
                if (dayWheel.value > max) dayWheel.setValue(max, notify = false)
            }
            monthWheel.onValueChanged = { keepDayValid() }
            yearWheel.onValueChanged = { keepDayValid() }
            val row = LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(dayWheel, LinearLayout.LayoutParams(0, kit.dp(152), 0.8f))
            row.addView(monthWheel, LinearLayout.LayoutParams(0, kit.dp(152), 1.2f))
            row.addView(yearWheel, LinearLayout.LayoutParams(0, kit.dp(152), 1.2f))
            panel.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(10) })
            panel.addView(kit.buttonRow(
                kit.quietButton("Cancel") { dialog.dismiss() },
                kit.primaryButton("Set date") {
                    dialog.dismiss()
                    onPicked(yearWheel.value, monthWheel.value, dayWheel.value)
                }
            ))
        }
    }

    private fun editEvent(
        host: Activity,
        kit: OeaAppKit,
        existing: Event?,
        defaultCal: Calendar = Calendar.getInstance(),
        defaultHour: Int = 9,
        onDone: () -> Unit
    ) {
        val isNew = existing == null
        val palette = kit.palette
        val is24h = android.text.format.DateFormat.is24HourFormat(host)
        // The day is editable: moving an event used to mean deleting and retyping it.
        var year = existing?.year ?: defaultCal.get(Calendar.YEAR)
        var month = existing?.month ?: defaultCal.get(Calendar.MONTH)
        var day = existing?.day ?: defaultCal.get(Calendar.DAY_OF_MONTH)
        val title = existing?.title ?: ""
        var (startH, startM) = parseEventHm(existing?.time.orEmpty(), defaultHour.coerceIn(0, 23))
        var (endH, endM) = parseEventHm(existing?.endTime.orEmpty(), (startH + 1).coerceAtMost(23), startM)
        val location = existing?.location ?: ""
        val desc = existing?.desc ?: ""
        var category = existing?.category ?: "OTHER"
        var reminder = existing?.reminderMin ?: 15
        var recurrence = existing?.recurrence ?: "NONE"
        var allDay = existing?.allDay ?: false

        val dateCal = Calendar.getInstance().apply { set(year, month, day) }
        val dateLabel = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(dateCal.time)

        fun displayTime(h: Int, m: Int): String =
            if (is24h) String.format(Locale.getDefault(), "%02d:%02d", h, m)
            else String.format(Locale.getDefault(), "%d:%02d %s", (h % 12).let { if (it == 0) 12 else it }, m, if (h < 12) "AM" else "PM")

        kit.sheet(if (isNew) "New event" else "Edit event", dateLabel) { panel, dialog ->
            val titleField = kit.field("Event title", title)
            val locField = kit.field("Location (optional)", location)
            val descField = kit.field("Notes (optional)", desc)
            panel.addView(titleField, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(10) })

            val timeGroup = kit.group(panel)
            fun paintTimeGroup() {
                timeGroup.removeAllViews()
                kit.row(timeGroup, R.drawable.oic_schedule, "Date", null,
                    value = SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault()).format(dateCal.time), chevron = true) {
                    pickDate(host, kit, year, month, day) { y, m, d ->
                        year = y
                        month = m
                        day = d
                        dateCal.set(y, m, d, 12, 0, 0)
                        paintTimeGroup()
                    }
                }
                kit.switchRow(timeGroup, R.drawable.oic_schedule, "All day", null, allDay) { value ->
                    allDay = value
                    paintTimeGroup()
                }
                if (!allDay) {
                    kit.row(timeGroup, R.drawable.oic_alarm, "Starts", null, value = displayTime(startH, startM), chevron = true) {
                        kit.pickTime("Start time", dateLabel, startH, startM, is24h) { h, m ->
                            startH = h
                            startM = m
                            if (endH * 60 + endM <= startH * 60 + startM) {
                                val next = (startH * 60 + startM + 60).coerceAtMost(23 * 60 + 59)
                                endH = next / 60
                                endM = next % 60
                            }
                            paintTimeGroup()
                        }
                    }
                    kit.row(timeGroup, R.drawable.oic_alarm, "Ends", null, value = displayTime(endH, endM), chevron = true) {
                        kit.pickTime("End time", dateLabel, endH, endM, is24h) { h, m ->
                            endH = h
                            endM = m
                            paintTimeGroup()
                        }
                    }
                }
            }
            paintTimeGroup()

            panel.addView(locField, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })
            panel.addView(descField, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(10) })

            panel.addView(kit.text("Category", 12.5f, palette.textSecondary, OeaType.medium).apply {
                setPadding(kit.dp(4), 0, 0, kit.dp(6))
            })
            val catRow = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
            fun paintCategories() {
                catRow.removeAllViews()
                Category.values().forEach { cat ->
                    catRow.addView(kit.chip(cat.label, cat.name == category) {
                        category = cat.name
                        paintCategories()
                    }, LinearLayout.LayoutParams(-2, kit.dp(40)).apply { rightMargin = kit.dp(6) })
                }
            }
            paintCategories()
            panel.addView(kit.hscroll(catRow), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(10) })

            panel.addView(kit.text("Reminder", 12.5f, palette.textSecondary, OeaType.medium).apply {
                setPadding(kit.dp(4), 0, 0, kit.dp(6))
            })
            val reminderRow = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
            fun paintReminders() {
                reminderRow.removeAllViews()
                listOf(0 to "None", 5 to "5m", 15 to "15m", 30 to "30m", 60 to "1h", 1440 to "1d").forEach { (min, label) ->
                    reminderRow.addView(kit.chip(label, reminder == min) {
                        reminder = min
                        paintReminders()
                    }, LinearLayout.LayoutParams(-2, kit.dp(40)).apply { rightMargin = kit.dp(6) })
                }
            }
            paintReminders()
            panel.addView(kit.hscroll(reminderRow), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(10) })

            panel.addView(kit.text("Repeat", 12.5f, palette.textSecondary, OeaType.medium).apply {
                setPadding(kit.dp(4), 0, 0, kit.dp(6))
            })
            val recurRow = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
            fun paintRecurrence() {
                recurRow.removeAllViews()
                Recurrence.values().forEach { rec ->
                    recurRow.addView(kit.chip(rec.label, recurrence == rec.name) {
                        recurrence = rec.name
                        paintRecurrence()
                    }, LinearLayout.LayoutParams(-2, kit.dp(40)).apply { rightMargin = kit.dp(6) })
                }
            }
            paintRecurrence()
            panel.addView(kit.hscroll(recurRow), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })

            panel.addView(kit.buttonRow(
                kit.quietButton("Cancel") { dialog.dismiss() },
                kit.primaryButton(if (isNew) "Save event" else "Save") {
                    val t = titleField.text.toString().trim()
                    if (t.isEmpty()) {
                        android.widget.Toast.makeText(host, "Enter an event title", android.widget.Toast.LENGTH_SHORT).show()
                        return@primaryButton
                    }
                    val startStr = if (allDay) "" else formatEventHm(startH, startM)
                    val endStr = if (allDay) "" else formatEventHm(endH, endM)
                    val store = events(host).toMutableList()
                    if (isNew) {
                        store += Event(freshEventId(), year, month, day, t, startStr, endStr, locField.text.toString().trim(), descField.text.toString().trim(), category, reminder, recurrence, allDay)
                    } else {
                        val anchor = existing!!.id
                        val updated = store.map {
                            if (it.id == anchor) it.copy(title = t, time = startStr, endTime = endStr, location = locField.text.toString().trim(), desc = descField.text.toString().trim(), category = category, reminderMin = reminder, recurrence = recurrence, allDay = allDay)
                            else it
                        }
                        store.clear()
                        store.addAll(updated)
                    }
                    saveEvents(host, store)
                    // A reminder that cannot post a notification is the bug this whole
                    // change is about, so ask for that permission at the moment it is
                    // first needed — the same way Clock does when an alarm is set.
                    if (reminder > 0 && android.os.Build.VERSION.SDK_INT >= 33 &&
                        host.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {
                        runCatching { host.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2102) }
                    }
                    dialog.dismiss()
                    onDone()
                }
            ))
            if (!isNew) {
                panel.addView(
                    kit.button("Delete event", palette.cardHigh, palette.danger, R.drawable.oic_delete) {
                        saveEvents(host, events(host).filterNot { it.id == existing!!.id })
                        dialog.dismiss()
                        onDone()
                    },
                    LinearLayout.LayoutParams(-1, kit.dp(48)).apply { topMargin = kit.dp(8) }
                )
            }
        }
    }
}
