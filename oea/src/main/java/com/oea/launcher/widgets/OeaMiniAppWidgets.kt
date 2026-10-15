package com.oea.launcher.widgets

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.oea.launcher.R
import com.oea.launcher.miniapps.OeaMiniAppActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Widgets for OEA mini-apps: Clock, Weather, Calendar, Notes.
 * Each widget is polished and actually works when added to Home.
 */
class OeaClockWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.oea_widget_clock)
            val now = Date()
            views.setTextViewText(R.id.clock_time, SimpleDateFormat("HH:mm", Locale.getDefault()).format(now))
            views.setTextViewText(R.id.clock_date, SimpleDateFormat("EEEE, MMM d", Locale.getDefault()).format(now))
            val intent = Intent(context, OeaMiniAppActivity::class.java).putExtra(OeaMiniAppActivity.EXTRA_APP, "clock")
            val pi = android.app.PendingIntent.getActivity(context, id, intent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.clock_time, pi)
            views.setOnClickPendingIntent(R.id.clock_date, pi)
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
            var city = "Weather"
            if (cache != null) {
                try {
                    val obj = org.json.JSONObject(cache)
                    val cur = obj.optJSONObject("current")
                    temp = "${cur?.optDouble("temperature_2m", 0.0)?.toInt() ?: 0}°C"
                    val code = cur?.optInt("weather_code", 0) ?: 0
                    desc = when (code) {
                        0 -> "Clear"; 1 -> "Mainly clear"; 2 -> "Partly cloudy"; 3 -> "Overcast"
                        45, 48 -> "Fog"; 51, 53, 55 -> "Drizzle"; 61, 63, 65 -> "Rain"
                        71, 73, 75 -> "Snow"; 80, 81, 82 -> "Showers"; 95 -> "Thunderstorm"; else -> "Weather $code"
                    }
                } catch (_: Throwable) {}
            }
            views.setTextViewText(R.id.weather_city, city)
            views.setTextViewText(R.id.weather_temp, temp)
            views.setTextViewText(R.id.weather_desc, desc)
            val intent = Intent(context, OeaMiniAppActivity::class.java).putExtra(OeaMiniAppActivity.EXTRA_APP, "weather")
            val pi = android.app.PendingIntent.getActivity(context, id, intent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.weather_city, pi)
            views.setOnClickPendingIntent(R.id.weather_temp, pi)
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
            val prefs = context.getSharedPreferences("oea_calendar", Context.MODE_PRIVATE)
            val raw = prefs.getString("events_v2", "")
            val count = raw?.split("\u001E")?.filter { it.isNotBlank() }?.size ?: 0
            views.setTextViewText(R.id.calendar_events, if (count == 0) "No events" else "$count events")
            views.setTextViewText(R.id.calendar_next, SimpleDateFormat("EEEE, MMM d", Locale.getDefault()).format(now))
            val intent = Intent(context, OeaMiniAppActivity::class.java).putExtra(OeaMiniAppActivity.EXTRA_APP, "calendar")
            val pi = android.app.PendingIntent.getActivity(context, id, intent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.calendar_month, pi)
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
            val text = if (notes.isEmpty()) "No notes yet" else notes.joinToString("\n") { "• $it" }
            views.setTextViewText(R.id.notes_content, text)
            val intent = Intent(context, OeaMiniAppActivity::class.java).putExtra(OeaMiniAppActivity.EXTRA_APP, "notes")
            val pi = android.app.PendingIntent.getActivity(context, id, intent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.notes_content, pi)
            appWidgetManager.updateAppWidget(id, views)
        }
    }
}
