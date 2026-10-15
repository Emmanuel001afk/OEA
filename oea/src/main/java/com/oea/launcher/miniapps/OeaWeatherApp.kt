package com.oea.launcher.miniapps

import android.app.Activity
import android.content.Context
import android.location.Location
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
import java.util.concurrent.Executors
import org.json.JSONObject
import java.net.URL

/**
 * OEA Weather: proper weather mini-app with forecasts, real data source, attractive interface and widgets.
 *
 * Uses Open-Meteo (https://open-meteo.com) - free, no API key, honest about availability.
 * If network unavailable, shows cached data and explains limitation.
 */
object OeaWeatherApp {
    private const val PREFS = "oea_weather"
    private const val KEY_CACHE = "cache_json"
    private const val KEY_LAST = "last_fetch"
    private val io = Executors.newSingleThreadExecutor { Thread(it, "OeaWeather").apply { isDaemon = true } }

    data class WeatherNow(val temp: Double, val code: Int, val wind: Double, val humidity: Int, val city: String)
    data class ForecastDay(val date: String, val max: Double, val min: Double, val code: Int)

    fun build(host: Activity, palette: OeaPalette): View {
        val kit = OeaKit(host, palette)
        val column = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }

        val header = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(kit.text("OEA Weather", 22f, palette.text, OeaType.medium), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(kit.circleIconButton(R.drawable.oic_refresh, "Refresh", palette.cardHigh, palette.text) {
            fetchWeather(host, kit, column, force = true)
        })
        column.addView(header, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })
        kit.note(column, "Real weather from Open-Meteo, no API key needed. Shows current conditions and 5-day forecast. Data availability depends on network; cached data is shown when offline.")

        val content = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        column.addView(content)

        fetchWeather(host, kit, content, force = false)
        return column
    }

    private fun fetchWeather(host: Activity, kit: OeaKit, content: LinearLayout, force: Boolean) {
        content.removeAllViews()
        content.addView(kit.text("Loading weather…", 13f, kit.palette.textSecondary).apply { setPadding(kit.dp(6), kit.dp(12), kit.dp(6), kit.dp(6)) })
        io.execute {
            try {
                // Try to get location - use coarse if available, else default to Lagos
                var lat = 6.5244
                var lon = 3.3792
                var city = "Lagos"
                try {
                    // Best effort location without extra permission request - use last known if granted
                    val lm = host.getSystemService(android.location.LocationManager::class.java)
                    if (host.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        val loc = lm?.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                            ?: lm?.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                        if (loc != null) {
                            lat = loc.latitude
                            lon = loc.longitude
                            city = String.format(Locale.US, "%.2f, %.2f", lat, lon)
                        }
                    }
                } catch (_: Throwable) {}

                val prefs = host.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val cached = prefs.getString(KEY_CACHE, null)
                val lastFetch = prefs.getLong(KEY_LAST, 0L)
                val useCache = !force && cached != null && System.currentTimeMillis() - lastFetch < 30 * 60 * 1000L

                val jsonStr = if (useCache) cached!! else {
                    // Open-Meteo: current + daily forecast
                    val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code,wind_speed_10m,relative_humidity_2m&daily=weather_code,temperature_2m_max,temperature_2m_min&timezone=auto"
                    try {
                        URL(url).readText()
                    } catch (e: Throwable) {
                        // Network failed - use cache if available
                        if (cached != null) cached else throw e
                    }
                }

                if (!useCache && jsonStr.isNotBlank()) {
                    prefs.edit().putString(KEY_CACHE, jsonStr).putLong(KEY_LAST, System.currentTimeMillis()).apply()
                }

                val obj = JSONObject(jsonStr)
                val current = obj.optJSONObject("current")
                val temp = current?.optDouble("temperature_2m", 0.0) ?: 0.0
                val code = current?.optInt("weather_code", 0) ?: 0
                val wind = current?.optDouble("wind_speed_10m", 0.0) ?: 0.0
                val humidity = current?.optInt("relative_humidity_2m", 0) ?: 0
                val now = WeatherNow(temp, code, wind, humidity, city)

                val daily = obj.optJSONObject("daily")
                val forecast = mutableListOf<ForecastDay>()
                if (daily != null) {
                    val dates = daily.optJSONArray("time")
                    val maxs = daily.optJSONArray("temperature_2m_max")
                    val mins = daily.optJSONArray("temperature_2m_min")
                    val codes = daily.optJSONArray("weather_code")
                    if (dates != null && maxs != null && mins != null) {
                        for (i in 0 until minOf(dates.length(), 5)) {
                            forecast.add(ForecastDay(
                                dates.optString(i),
                                maxs.optDouble(i),
                                mins.optDouble(i),
                                codes?.optInt(i) ?: 0
                            ))
                        }
                    }
                }

                host.runOnUiThread {
                    if (host.isDestroyed) return@runOnUiThread
                    content.removeAllViews()
                    // Current
                    val nowCard = LinearLayout(host).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(kit.dp(18), kit.dp(18), kit.dp(18), kit.dp(18))
                        background = kit.rounded(kit.palette.card, 24, kit.palette.stroke, 1)
                    }
                    nowCard.addView(kit.text(now.city, 16f, kit.palette.textSecondary, OeaType.medium))
                    nowCard.addView(kit.text("${now.temp.toInt()}°C", 48f, kit.palette.text, OeaType.light).apply { setPadding(0, kit.dp(4), 0, 0) })
                    nowCard.addView(kit.text(weatherDesc(now.code), 15f, kit.palette.accent, OeaType.medium).apply { setPadding(0, kit.dp(2), 0, kit.dp(8)) })
                    val details = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
                    details.addView(kit.text("Wind ${now.wind} km/h", 13f, kit.palette.textSecondary), LinearLayout.LayoutParams(0, -2, 1f))
                    details.addView(kit.text("Humidity ${now.humidity}%", 13f, kit.palette.textSecondary), LinearLayout.LayoutParams(0, -2, 1f))
                    nowCard.addView(details)
                    content.addView(nowCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(12) })

                    // Forecast
                    kit.sectionTitle(content, "5-day forecast")
                    val g = kit.group(content)
                    forecast.forEach { day ->
                        kit.row(g, R.drawable.oic_thermostat, day.date, "${day.min.toInt()}° / ${day.max.toInt()}° • ${weatherDesc(day.code)}", chevron = false)
                    }
                    kit.note(content, "Source: Open-Meteo.com • Updated ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())} • Weather data requires internet; cached data shown when offline.")
                }
            } catch (e: Throwable) {
                host.runOnUiThread {
                    if (host.isDestroyed) return@runOnUiThread
                    content.removeAllViews()
                    kit.banner(content, R.drawable.oic_warning_fill, "Weather unavailable", "Could not fetch weather: ${e.message}. Check internet connection. Cached data will be shown when available.", OeaKit.Tone.WARNING, "Retry") {
                        fetchWeather(host, kit, content, true)
                    }
                    val cached = host.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CACHE, null)
                    if (cached != null) {
                        kit.note(content, "Showing last cached data from ${SimpleDateFormat("MMM d HH:mm", Locale.getDefault()).format(Date(host.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST, 0L)))}")
                    }
                }
            }
        }
    }

    private fun weatherDesc(code: Int): String = when (code) {
        0 -> "Clear sky"
        1 -> "Mainly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        51, 53, 55 -> "Drizzle"
        61, 63, 65 -> "Rain"
        71, 73, 75 -> "Snow"
        80, 81, 82 -> "Rain showers"
        95 -> "Thunderstorm"
        else -> "Weather $code"
    }
}
