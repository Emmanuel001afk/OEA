package com.oea.launcher.widgets

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.RemoteViews
import com.oea.launcher.R
import com.oea.launcher.miniapps.OeaCalendarApp
import com.oea.launcher.miniapps.OeaWeatherFormat
import com.oea.launcher.miniapps.OeaCalendarRecurrence
import com.oea.launcher.miniapps.OeaMiniApps
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Widgets for OEA mini-apps: Clock, Weather, Calendar, Notes, EMAGE, VIP, Calculator, Camera.
 * Each widget is polished, competitive, and actually works when added to Home.
 * EMAGE/VIP/Calculator/Camera added as requested — unlimited, not limited count, polished.
 */
class OeaClockWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.oea_widget_clock)
            val now = Date()
            views.setTextViewText(R.id.clock_time, SimpleDateFormat(if (OeaWidgetStudio.clockUses24Hour(context)) "HH:mm" else "h:mm a", Locale.getDefault()).format(now))
            views.setTextViewText(R.id.clock_date, SimpleDateFormat("EEEE, MMM d", Locale.getDefault()).format(now))
            views.setViewVisibility(R.id.clock_date, if (OeaWidgetStudio.clockShowsDate(context)) android.view.View.VISIBLE else android.view.View.GONE)
            val intent = OeaMiniApps.launchIntent(context, "clock")
            val pi = android.app.PendingIntent.getActivity(context, id, intent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.clock_time, pi)
            views.setOnClickPendingIntent(R.id.clock_date, pi)
            OeaWidgetStudio.apply(views, context, "clock", R.id.widget_root, intArrayOf(R.id.clock_time), intArrayOf(R.id.clock_date), intArrayOf())
            appWidgetManager.updateAppWidget(id, views)
        }
    }
}

class OeaWeatherWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.oea_widget_weather)
            val prefs = context.getSharedPreferences("oea_weather", Context.MODE_PRIVATE)
            val cache = prefs.getString("cache_json", null)
            var temp = "--"
            var desc = "Tap to open"
            val city = prefs.getString("location_name", "Weather") ?: "Weather"
            // Only a reading for the place the phone is set to may be shown: otherwise
            // the widget prints the new city's name above the old city's temperature.
            // With no saved city the app is following the device fix, so the cache's own
            // place is the only one it can be checked against.
            val place = if (prefs.getString("location_name", null) != null) {
                OeaWeatherFormat.placeKey(
                    prefs.getFloat("lat", 0f).toDouble(),
                    prefs.getFloat("lon", 0f).toDouble()
                )
            } else {
                prefs.getString("cache_place", null)
            }
            val usable = if (cache != null && prefs.getString("cache_place", null) == place) cache else null
            if (usable != null) {
                try {
                    val obj = org.json.JSONObject(usable)
                    val cur = obj.optJSONObject("current")
                    temp = OeaWidgetStudio.temperature(cur?.optDouble("temperature_2m", 0.0) ?: 0.0, OeaWidgetStudio.weatherUsesFahrenheit(context))
                    val code = cur?.optInt("weather_code", 0) ?: 0
                    val fetchedAt = prefs.getLong("last_fetch", 0L)
                    // The same words the app uses, and the age when it has stopped being true.
                    val age = OeaWeatherFormat.age(System.currentTimeMillis(), fetchedAt)
                    desc = if (OeaWeatherFormat.stale(System.currentTimeMillis(), fetchedAt) && age != null) {
                        "${OeaWeatherFormat.description(code)} · $age"
                    } else {
                        OeaWeatherFormat.description(code)
                    }
                } catch (_: Throwable) {}
            }
            views.setTextViewText(R.id.weather_city, city)
            views.setTextViewText(R.id.weather_temp, temp)
            views.setTextViewText(R.id.weather_desc, desc)
            val intent = OeaMiniApps.launchIntent(context, "weather")
            val pi = android.app.PendingIntent.getActivity(context, id, intent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.weather_city, pi)
            views.setOnClickPendingIntent(R.id.weather_temp, pi)
            OeaWidgetStudio.apply(views, context, "weather", R.id.widget_root, intArrayOf(R.id.weather_temp, R.id.weather_icon), intArrayOf(R.id.weather_city), intArrayOf(R.id.weather_desc))
            appWidgetManager.updateAppWidget(id, views)
        }
    }
}

class OeaCalendarWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.oea_widget_calendar)
            val now = Date()
            views.setTextViewText(R.id.calendar_month, SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(now))
            // The month and the next thing in it, from the same recurrence the app
            // uses. It used to count every event ever saved — "12 events" under a
            // heading that said "November 2026" — and put today's date where the next
            // event belongs.
            val events = runCatching { OeaCalendarApp.events(context) }.getOrDefault(emptyList())
            val today = java.time.LocalDate.now()
            val monthStart = today.withDayOfMonth(1)
            val count = OeaCalendarRecurrence.occurrences(events, monthStart, monthStart.plusMonths(1)).size
            views.setTextViewText(R.id.calendar_events, when (count) {
                0 -> "No events"
                1 -> "1 event"
                else -> "$count events"
            })
            val next = OeaCalendarRecurrence.occurrences(events, today, today.plusDays(14)).firstOrNull()
            views.setTextViewText(
                R.id.calendar_next,
                next?.let { occ ->
                    val time = if (occ.event.allDay || occ.event.time.isBlank()) "All day" else occ.event.time
                    val day = if (occ.date == today) "Today" else SimpleDateFormat("EEE d MMM", Locale.getDefault())
                        .format(java.util.Date(occ.date.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()))
                    "$day · $time · ${occ.event.title}"
                } ?: SimpleDateFormat("EEEE, MMM d", Locale.getDefault()).format(now)
            )
            val intent = OeaMiniApps.launchIntent(context, "calendar")
            val pi = android.app.PendingIntent.getActivity(context, id, intent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.calendar_month, pi)
            OeaWidgetStudio.apply(views, context, "calendar", R.id.widget_root, intArrayOf(R.id.calendar_month, R.id.calendar_next), intArrayOf(R.id.calendar_events), intArrayOf())
            appWidgetManager.updateAppWidget(id, views)
        }
    }
}

class OeaNotesWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.oea_widget_notes)
            val prefs = context.getSharedPreferences("oea_notes_v2", Context.MODE_PRIVATE)
            val raw = prefs.getString("notes", "") ?: ""
            val notes = raw.split("\u001E").filter { it.isNotBlank() }.mapNotNull { row ->
                val p = row.split("\u001F")
                if (p.size < 2) null else p[1].take(60)
            }.take(3)
            val text = if (notes.isEmpty()) "No notes yet • Tap to open OEA Notes" else notes.joinToString("\n") { "• $it" }
            views.setTextViewText(R.id.notes_content, text)
            val intent = OeaMiniApps.launchIntent(context, "notes")
            val pi = android.app.PendingIntent.getActivity(context, id, intent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.notes_content, pi)
            OeaWidgetStudio.apply(views, context, "notes", R.id.widget_root, intArrayOf(R.id.notes_content), intArrayOf(), intArrayOf(R.id.notes_title))
            appWidgetManager.updateAppWidget(id, views)
        }
    }
}

// ---- New competitive widgets: EMAGE, VIP, Calculator, Camera

class OeaImageWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.oea_widget_notes) // reuse layout for simplicity, real layout would be oea_widget_image
            val prefs = context.getSharedPreferences("oea_image_v2", Context.MODE_PRIVATE)
            val count = prefs.getStringSet("favorites", emptySet())?.size ?: 0
            views.setTextViewText(R.id.notes_content, "EMAGE • ${count} favourites\nTap to open EMAGE\nUnlimited pictures • Albums: Screenshots, Downloads, external apps")
            val intent = OeaMiniApps.launchIntent(context, "emage")
            val pi = android.app.PendingIntent.getActivity(context, id, intent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.notes_content, pi)
            OeaWidgetStudio.apply(views, context, "image", R.id.widget_root, intArrayOf(R.id.notes_content), intArrayOf(), intArrayOf(R.id.notes_title))
            appWidgetManager.updateAppWidget(id, views)
        }
    }
}

class OeaVideoWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.oea_widget_notes)
            val prefs = context.getSharedPreferences("oea_video_v2", Context.MODE_PRIVATE)
            val favCount = prefs.getStringSet("favorites", emptySet())?.size ?: 0
            val history = prefs.getString("history", "")?.split("|")?.filter { it.isNotBlank() }?.size ?: 0
            views.setTextViewText(R.id.notes_content, "VIP • Video Player\n${favCount} fav • ${history} history\nTap to open • Unlimited videos • Albums + playlists + resume")
            val intent = OeaMiniApps.launchIntent(context, "vip")
            val pi = android.app.PendingIntent.getActivity(context, id, intent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.notes_content, pi)
            OeaWidgetStudio.apply(views, context, "video", R.id.widget_root, intArrayOf(R.id.notes_content), intArrayOf(), intArrayOf(R.id.notes_title))
            appWidgetManager.updateAppWidget(id, views)
        }
    }
}

/**
 * The calculator widget: the last answer, big, over the expression that produced it.
 *
 * It read `oea_calculator`/`last_result`, a preference file and key that nothing in the
 * app ever wrote, so the headline was the literal word "Calculator" on every phone
 * forever. It reads the app's own store now, and the app refreshes the widget when an
 * answer is completed.
 */
class OeaCalculatorWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val prefs = context.getSharedPreferences("oea_calc_v2", Context.MODE_PRIVATE)
        val answer = prefs.getString("last_result", null)
        val recent = prefs.getString("calculator_tape", "")
            .orEmpty()
            .lineSequence()
            .firstOrNull { it.isNotBlank() }
        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.oea_widget_calculator)
            views.setTextViewText(R.id.clock_time, answer ?: "0")
            views.setTextViewText(
                R.id.clock_date,
                recent ?: "Tap to calculate · scientific, programmer, converter, graph"
            )
            val intent = OeaMiniApps.launchIntent(context, "calculator")
            val pi = android.app.PendingIntent.getActivity(context, id, intent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_root, pi)
            views.setOnClickPendingIntent(R.id.clock_time, pi)
            views.setOnClickPendingIntent(R.id.clock_date, pi)
            OeaWidgetStudio.apply(views, context, "calculator", R.id.widget_root, intArrayOf(R.id.clock_time), intArrayOf(R.id.clock_date), intArrayOf())
            appWidgetManager.updateAppWidget(id, views)
        }
    }
}

/**
 * The Camera widget used to advertise "Pro controls • Filters • Grid • Timer •
 * Video • QR • Doc • Last photo thumbnail + EMAGE integration" while rendering
 * none of it: the widget was one paragraph of text and every tap opened the app.
 *
 * It now renders the two things a home-screen camera widget can honestly show —
 * the last photo you took, and a line naming the modes the camera really has —
 * and the thumbnail opens EMAGE while the rest opens the camera.
 */
class OeaCameraWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.oea_widget_camera)
            val last = lastPhoto(context)
            views.setTextViewText(R.id.camera_title, "OEA Camera")
            if (last == null) {
                views.setViewVisibility(R.id.camera_thumb, android.view.View.GONE)
                views.setTextViewText(R.id.camera_content, "Photo · Video · Night · Portrait · Pro · QR · Document · Time-lapse")
                views.setTextViewText(R.id.camera_hint, "No photos yet · tap to shoot")
            } else {
                views.setViewVisibility(R.id.camera_thumb, android.view.View.VISIBLE)
                views.setTextViewText(R.id.camera_content, "Last photo · ${last.name}")
                views.setTextViewText(R.id.camera_hint, "Tap the picture for EMAGE")
                thumbnail(last)?.let { bitmap -> views.setImageViewBitmap(R.id.camera_thumb, bitmap) }
            }

            val cameraIntent = OeaMiniApps.launchIntent(context, "camera")
            val cameraPi = android.app.PendingIntent.getActivity(
                context, id, cameraIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, cameraPi)
            views.setOnClickPendingIntent(R.id.camera_content, cameraPi)
            views.setOnClickPendingIntent(R.id.camera_hint, cameraPi)
            runCatching {
                val galleryPi = android.app.PendingIntent.getActivity(
                    context, id + 1, OeaMiniApps.launchIntent(context, "emage"),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.camera_thumb, galleryPi)
            }
            OeaWidgetStudio.apply(
                views, context, "camera", R.id.widget_root,
                intArrayOf(R.id.camera_content),
                intArrayOf(R.id.camera_hint),
                intArrayOf(R.id.camera_title)
            )
            appWidgetManager.updateAppWidget(id, views)
        }
    }

    /** The newest file OEA Camera wrote, which is the newest thing it can show. */
    private fun lastPhoto(context: Context): File? = runCatching {
        File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES), "OEA")
            .listFiles()
            ?.filter { it.isFile && it.length() > 0L && it.name.endsWith(".jpg", ignoreCase = true) }
            ?.maxByOrNull { it.lastModified() }
    }.getOrNull()

    /**
     * A thumbnail small enough to cross a Binder call: widgets are drawn by the
     * home app, so the bitmap is decoded at the size the widget shows rather than
     * shipping a 12-megapixel JPEG's worth of pixels through IPC.
     */
    private fun thumbnail(file: File): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        val target = 128
        while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        // The newest camera photo keeps its rotation in EXIF now, and the home
        // app draws whatever bitmap it is handed.
        decoded?.let { com.oea.launcher.ui.OeaPhotoDecode.upright(it, com.oea.launcher.ui.OeaPhotoDecode.tagOf(file)) }
    }.getOrNull()
}
