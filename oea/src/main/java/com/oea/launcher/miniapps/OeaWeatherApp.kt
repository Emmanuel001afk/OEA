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
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * OEA Weather: real numbers from Open-Meteo, and nothing invented around them.
 *
 * What is genuinely here:
 * - Current conditions, a 24-hour rail and a 7-day list from one request, no API key,
 *   no account, no ad SDK.
 * - A city search against Open-Meteo's geocoder, or the phone's last known position if
 *   you grant coarse location.
 * - Celsius/fahrenheit, and the wind and rain figures follow the same choice, because a
 *   app that shows 90 °F next to "12 km/h" is half converted and therefore wrong.
 * - Air quality from Open-Meteo's air-quality endpoint, best effort: if that one call
 *   fails the card is simply not shown, and nothing else breaks.
 * - A cache: the last reading stays on the phone, so opening Weather on a plane or in a
 *   blackout still tells you something, and says when it stopped being true.
 * - Advisories computed here from the same numbers — big wind, high UV, freezing
 *   nights, heavy rain, brutal heat. OEA writes those itself and labels them as its own
 *   reading; it does not claim to be a national weather service and does not pretend to
 *   receive official warning feeds, because there is no such feed it can read without a
 *   country-specific API.
 * - The home-screen weather widget reads the cache written here, so the two never
 *   disagree; it refreshes on the interval the system gives it.
 */
object OeaWeatherApp {
    private const val PREFS = "oea_weather"
    private const val KEY_CACHE = "cache_json"
    private const val KEY_LAST = "last_fetch"
    private const val KEY_LOCATION = "location_name"
    private const val KEY_LAT = "lat"
    private const val KEY_LON = "lon"
    private const val KEY_UNIT_C = "unit_celsius"
    private const val KEY_AQ_CACHE = "air_cache"
    private const val KEY_AQ_LAST = "air_fetch"
    private const val CACHE_MS = 30 * 60 * 1000L

    private val io = Executors.newFixedThreadPool(2) { Thread(it, "OeaWeather").apply { isDaemon = true } }

    data class Now(
        val temp: Double,
        val feelsLike: Double,
        val code: Int,
        val wind: Double,
        val gust: Double,
        val humidity: Int,
        val pressure: Double,
        val cloud: Int,
        val precip: Double,
        val uvPeak: Double,
        val sunrise: String,
        val sunset: String,
        val city: String,
        val lat: Double,
        val lon: Double
    )

    data class Hour(val time: String, val temp: Double, val code: Int, val wind: Double, val rainChance: Int)
    data class ForecastDay(
        val date: String,
        val max: Double,
        val min: Double,
        val code: Int,
        val sunrise: String,
        val sunset: String,
        val rainMm: Double,
        val rainChance: Int,
        val uvPeak: Double,
        val gust: Double
    )
    data class Air(val aqi: Int, val pm25: Double, val pm10: Double)

    fun build(host: Activity, palette: OeaPalette): View {
        val kit = OeaKit(host, palette)
        val column = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }

        val header = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(kit.text("Weather", 22f, palette.text, OeaType.medium), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(kit.circleIconButton(R.drawable.oic_search, "Search location", palette.cardHigh, palette.text) {
            searchLocation(host, kit) { _, _, _ -> fetch(host, kit, column, force = true) }
        }, LinearLayout.LayoutParams(kit.dp(40), kit.dp(40)).apply { rightMargin = kit.dp(8) })
        header.addView(kit.circleIconButton(R.drawable.oic_refresh, "Refresh now", palette.cardHigh, palette.text) {
            fetch(host, kit, column, force = true)
        })
        column.addView(header, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })

        val unitRow = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val isCelsius = prefs(host).getBoolean(KEY_UNIT_C, true)
        unitRow.addView(
            kit.chip(if (isCelsius) "°C · km/h · mm" else "°F · mph · in", true, R.drawable.oic_thermostat) {
                prefs(host).edit().putBoolean(KEY_UNIT_C, !isCelsius).apply()
                (host as? OeaMiniAppActivity)?.refresh()
            },
            LinearLayout.LayoutParams(-2, kit.dp(36))
        )
        val savedCity = prefs(host).getString(KEY_LOCATION, null)
        if (savedCity != null) {
            unitRow.addView(
                kit.quietButton("Use my phone's place", R.drawable.oic_location_on) {
                    prefs(host).edit().remove(KEY_LOCATION).remove(KEY_LAT).remove(KEY_LON).apply()
                    (host as? OeaMiniAppActivity)?.refresh()
                },
                LinearLayout.LayoutParams(-2, kit.dp(36)).apply { leftMargin = kit.dp(6) }
            )
        }
        column.addView(unitRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })

        val content = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        column.addView(content)
        fetch(host, kit, content, force = false)
        return column
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ search

    private fun searchLocation(host: Activity, kit: OeaKit, onSelected: (String, Double, Double) -> Unit) {
        kit.sheet("Search location", "Open-Meteo's geocoder — free, no key, no account") { panel, dialog ->
            val field = kit.field("City name (e.g. Lagos, Tokyo)", "")
            val results = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
            panel.addView(field, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })
            panel.addView(results)
            kit.note(results, "Two letters is enough to start. Nothing is sent anywhere but the geocoder's name lookup.")

            fun search(query: String) {
                if (query.trim().length < 2) {
                    results.removeAllViews()
                    kit.note(results, "Two letters is enough to start. Nothing is sent anywhere but the geocoder's name lookup.")
                    return
                }
                results.removeAllViews()
                results.addView(kit.text("Searching…", 13f, kit.palette.textSecondary))
                io.execute {
                    try {
                        val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
                        val obj = JSONObject(URL("https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=6&language=en&format=json").readText())
                        val arr = obj.optJSONArray("results")
                        host.runOnUiThread {
                            if (host.isDestroyed) return@runOnUiThread
                            results.removeAllViews()
                            if (arr == null || arr.length() == 0) {
                                kit.note(results, "No place called \"$query\" in the geocoder")
                                return@runOnUiThread
                            }
                            val group = kit.group(results)
                            for (i in 0 until arr.length()) {
                                val r = arr.getJSONObject(i)
                                val name = r.optString("name")
                                val country = r.optString("country")
                                val admin = r.optString("admin1")
                                val lat = r.optDouble("latitude")
                                val lon = r.optDouble("longitude")
                                val label = "$name, $admin, $country"
                                kit.row(group, R.drawable.oic_location_on, name, "$admin, $country • ${"%.3f, %.3f".format(lat, lon)}") {
                                    prefs(host).edit()
                                        .putString(KEY_LOCATION, label)
                                        .putFloat(KEY_LAT, lat.toFloat())
                                        .putFloat(KEY_LON, lon.toFloat())
                                        .apply()
                                    dialog.dismiss()
                                    onSelected(label, lat, lon)
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        host.runOnUiThread {
                            if (host.isDestroyed) return@runOnUiThread
                            results.removeAllViews()
                            kit.note(results, "The search did not come back: ${e.message}")
                        }
                    }
                }
            }

            field.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: android.text.Editable?) { search(s?.toString() ?: "") }
            })
        }
    }

    // ------------------------------------------------------------------ fetch

    private fun fetch(host: Activity, kit: OeaKit, content: LinearLayout, force: Boolean) {
        content.removeAllViews()
        content.addView(
            kit.text("Asking Open-Meteo…", 13f, kit.palette.textSecondary)
                .apply { setPadding(kit.dp(6), kit.dp(12), kit.dp(6), kit.dp(6)) }
        )
        io.execute {
            try {
                val p = prefs(host)
                var lat = p.getFloat(KEY_LAT, 0f).toDouble()
                var lon = p.getFloat(KEY_LON, 0f).toDouble()
                var city = p.getString(KEY_LOCATION, null)
                var located = city != null
                if (!located) {
                    val lm = host.getSystemService(android.location.LocationManager::class.java)
                    val granted = host.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                    val fix = if (granted) {
                        runCatching {
                            lm?.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                                ?: lm?.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                        }.getOrNull()
                    } else null
                    if (fix != null) {
                        lat = fix.latitude
                        lon = fix.longitude
                        city = "%.3f, %.3f".format(lat, lon)
                    } else {
                        // A fixed starting point beats an empty screen; the chip in the
                        // header lets you put your own city in one tap.
                        lat = 6.5244
                        lon = 3.3792
                        city = "Lagos (not your city? search, top right)"
                    }
                }

                val cached = p.getString(KEY_CACHE, null)
                val lastFetch = p.getLong(KEY_LAST, 0L)
                val fresh = System.currentTimeMillis() - lastFetch < CACHE_MS
                val text = if (!force && cached != null && fresh) {
                    cached
                } else {
                    val url = "https://api.open-meteo.com/v1/forecast" +
                        "?latitude=$lat&longitude=$lon" +
                        "&current=temperature_2m,apparent_temperature,relative_humidity_2m,precipitation,weather_code,cloud_cover,pressure_msl,surface_pressure,wind_speed_10m,wind_gusts_10m" +
                        "&hourly=temperature_2m,weather_code,wind_speed_10m,precipitation_probability" +
                        "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset,precipitation_sum,precipitation_probability_max,uv_index_max,wind_gusts_10m_max" +
                        "&timezone=auto&forecast_days=7"
                    runCatching { URL(url).readText() }.getOrElse { error ->
                        if (cached != null) cached else throw error
                    }
                }
                if (cached != text) p.edit().putString(KEY_CACHE, text).putLong(KEY_LAST, System.currentTimeMillis()).apply()
                val fetchedAt = if (cached != text) System.currentTimeMillis() else lastFetch

                val obj = JSONObject(text)
                val current = obj.optJSONObject("current")
                val daily = obj.optJSONObject("daily")
                val now = Now(
                    temp = current?.optDouble("temperature_2m", 0.0) ?: 0.0,
                    feelsLike = current?.optDouble("apparent_temperature") ?: (current?.optDouble("temperature_2m") ?: 0.0),
                    code = current?.optInt("weather_code", 0) ?: 0,
                    wind = current?.optDouble("wind_speed_10m", 0.0) ?: 0.0,
                    gust = current?.optDouble("wind_gusts_10m", 0.0) ?: 0.0,
                    humidity = current?.optInt("relative_humidity_2m", 0) ?: 0,
                    pressure = current?.optDouble("surface_pressure", 0.0) ?: 0.0,
                    cloud = current?.optInt("cloud_cover", 0) ?: 0,
                    precip = current?.optDouble("precipitation", 0.0) ?: 0.0,
                    uvPeak = daily?.optJSONArray("uv_index_max")?.optDouble(0) ?: 0.0,
                    sunrise = daily?.optJSONArray("sunrise")?.optString(0) ?: "",
                    sunset = daily?.optJSONArray("sunset")?.optString(0) ?: "",
                    city = city ?: "Somewhere",
                    lat = lat,
                    lon = lon
                )

                val hours = ArrayList<Hour>()
                obj.optJSONObject("hourly")?.let { h ->
                    val times = h.optJSONArray("time")
                    val temps = h.optJSONArray("temperature_2m")
                    val codes = h.optJSONArray("weather_code")
                    val winds = h.optJSONArray("wind_speed_10m")
                    val pops = h.optJSONArray("precipitation_probability")
                    if (times != null && temps != null) {
                        // Start at the current hour, not midnight, or the rail shows a
                        // day that is already over.
                        val nowIso = current?.optString("time") ?: ""
                        var start = 0
                        for (i in 0 until times.length()) {
                            if (times.optString(i) >= nowIso) {
                                start = i
                                break
                            }
                        }
                        for (i in start until minOf(start + 24, times.length())) {
                            hours.add(
                                Hour(
                                    times.optString(i),
                                    temps.optDouble(i),
                                    codes?.optInt(i) ?: 0,
                                    winds?.optDouble(i) ?: 0.0,
                                    pops?.optInt(i) ?: 0
                                )
                            )
                        }
                    }
                }

                val days = ArrayList<ForecastDay>()
                if (daily != null) {
                    val dates = daily.optJSONArray("time")
                    val maxs = daily.optJSONArray("temperature_2m_max")
                    val mins = daily.optJSONArray("temperature_2m_min")
                    if (dates != null && maxs != null && mins != null) {
                        for (i in 0 until minOf(dates.length(), 7)) {
                            days.add(
                                ForecastDay(
                                    dates.optString(i),
                                    maxs.optDouble(i),
                                    mins.optDouble(i),
                                    daily.optJSONArray("weather_code")?.optInt(i) ?: 0,
                                    daily.optJSONArray("sunrise")?.optString(i) ?: "",
                                    daily.optJSONArray("sunset")?.optString(i) ?: "",
                                    daily.optJSONArray("precipitation_sum")?.optDouble(i) ?: 0.0,
                                    daily.optJSONArray("precipitation_probability_max")?.optInt(i) ?: 0,
                                    daily.optJSONArray("uv_index_max")?.optDouble(i) ?: 0.0,
                                    daily.optJSONArray("wind_gusts_10m_max")?.optDouble(i) ?: 0.0
                                )
                            )
                        }
                    }
                }

                // Best effort, separate call, never fatal: a phone in a country the air
                // endpoint does not cover simply gets no card.
                val air = fetchAir(host, lat, lon)

                host.runOnUiThread {
                    if (host.isDestroyed) return@runOnUiThread
                    render(host, kit, content, now, hours, days, air, fetchedAt)
                }
            } catch (e: Throwable) {
                host.runOnUiThread {
                    if (host.isDestroyed) return@runOnUiThread
                    content.removeAllViews()
                    kit.banner(
                        content,
                        R.drawable.oic_warning_fill,
                        "No weather",
                        "Open-Meteo did not answer (${e.message}). Weather needs a connection; whatever was cached before is kept.",
                        OeaKit.Tone.WARNING,
                        "Try again"
                    ) { fetch(host, kit, content, true) }
                    kit.note(content, "If you are somewhere the internet is throttled, OEA will not make up a forecast — it shows the last real reading instead and says when it was taken.")
                }
            }
        }
    }

    private fun fetchAir(context: Context, lat: Double, lon: Double): Air? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cached = prefs.getString(KEY_AQ_CACHE, null)
        val last = prefs.getLong(KEY_AQ_LAST, 0L)
        if (cached != null && System.currentTimeMillis() - last < CACHE_MS) return parseAir(cached)
        return runCatching {
            val body = URL(
                "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=$lat&longitude=$lon&current=us_aqi,pm2_5,pm10"
            ).readText()
            val parsed = parseAir(body)
            if (parsed != null) prefs.edit().putString(KEY_AQ_CACHE, body).putLong(KEY_AQ_LAST, System.currentTimeMillis()).apply()
            parsed
        }.getOrNull()
    }

    private fun parseAir(body: String): Air? = runCatching {
        val current = JSONObject(body).optJSONObject("current") ?: return null
        val aqi = current.optInt("us_aqi", -1)
        if (aqi < 0) return null
        Air(aqi, current.optDouble("pm2_5", 0.0), current.optDouble("pm10", 0.0))
    }.getOrNull()

    // ------------------------------------------------------------------ render

    private fun render(
        host: Activity,
        kit: OeaKit,
        content: LinearLayout,
        now: Now,
        hours: List<Hour>,
        days: List<ForecastDay>,
        air: Air?,
        fetchedAt: Long
    ) {
        val palette = kit.palette
        content.removeAllViews()
        val celsius = prefs(host).getBoolean(KEY_UNIT_C, true)

        val card = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(kit.dp(18), kit.dp(18), kit.dp(18), kit.dp(18))
            background = kit.rounded(palette.card, 24, palette.stroke, 1)
        }
        card.addView(kit.text(now.city, 15f, palette.textSecondary, OeaType.medium))
        card.addView(
            kit.text("${shown(if (celsius) now.temp else toF(now.temp))}°", 52f, palette.text, OeaType.light)
                .apply { setPadding(0, kit.dp(2), 0, 0) }
        )
        card.addView(
            kit.text(
                "${weatherDesc(now.code)} · feels ${shown(if (celsius) now.feelsLike else toF(now.feelsLike))}° · ${now.cloud}% cloud",
                14.5f,
                palette.accent,
                OeaType.medium
            ).apply { setPadding(0, kit.dp(2), 0, kit.dp(10)) }
        )
        val speed = if (celsius) "${now.wind.toInt()} km/h" else "%.0f mph".format(now.wind * 0.621371)
        val gust = if (celsius) "${now.gust.toInt()} km/h" else "%.0f mph".format(now.gust * 0.621371)
        val rain = if (celsius) "${now.precip} mm" else "%.2f in".format(now.precip / 25.4)
        card.addView(kit.text("Wind $speed, gusting $gust · Humidity ${now.humidity}% · ${now.pressure.toInt()} hPa · rain now $rain/h", 13f, palette.textSecondary))
        card.addView(
            kit.text(
                "Sunrise ${formatTime(now.sunrise)} · sunset ${formatTime(now.sunset)} · UV ${now.uvPeak.toInt()} at its worst today",
                12f,
                palette.textTertiary
            ).apply { setPadding(0, kit.dp(4), 0, 0) }
        )
        card.addView(
            kit.tonalButton("Open this place on a map", R.drawable.oic_location_on) {
                runCatching {
                    host.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("geo:${now.lat},${now.lon}?q=${now.lat},${now.lon}(${encode(now.city)})")
                        )
                    )
                }
            },
            LinearLayout.LayoutParams(-1, kit.dp(44)).apply { topMargin = kit.dp(12) }
        )
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(12) })

        val advisories = advisories(now, days)
        if (advisories.isNotEmpty()) {
            kit.sectionTitle(content, "OEA's reading of these numbers")
            advisories.forEach { advice ->
                kit.banner(content, advice.icon, advice.title, advice.body, advice.tone, null, null)
            }
            kit.note(content, "These lines are computed on this phone from the forecast above — they are not official warnings from any weather service, and no weather service's feed is being read.")
        }

        if (hours.isNotEmpty()) {
            kit.sectionTitle(content, "The next ${hours.size} hours")
            val row = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
            hours.forEach { hour ->
                val col = LinearLayout(host).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(kit.dp(8), kit.dp(8), kit.dp(8), kit.dp(8))
                    background = kit.rounded(palette.card, 14, palette.stroke, 1)
                }
                col.addView(kit.text(formatTime(hour.time), 11f, palette.textTertiary).apply { gravity = Gravity.CENTER })
                col.addView(
                    kit.text("${shown(if (celsius) hour.temp else toF(hour.temp))}°", 16f, palette.text, OeaType.medium)
                        .apply { gravity = Gravity.CENTER; setPadding(0, kit.dp(4), 0, kit.dp(2)) }
                )
                col.addView(kit.text(weatherDesc(hour.code), 9.5f, palette.textSecondary).apply { gravity = Gravity.CENTER; maxLines = 2 })
                val drop = View(host).apply {
                    background = kit.rounded(palette.accentSoft, 3)
                    layoutParams = LinearLayout.LayoutParams(kit.dp(34), kit.dp(4)).apply { topMargin = kit.dp(5) }
                }
                drop.scaleX = (hour.rainChance / 100f).coerceIn(0.04f, 1f)
                col.addView(drop)
                col.addView(kit.text("${hour.rainChance}% rain", 9f, palette.textTertiary).apply { gravity = Gravity.CENTER })
                row.addView(col, LinearLayout.LayoutParams(kit.dp(84), -2).apply { rightMargin = kit.dp(8) })
            }
            content.addView(kit.hscroll(row), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(12) })
        }

        if (days.isNotEmpty()) {
            kit.sectionTitle(content, "Seven days")
            val group = kit.group(content)
            days.forEachIndexed { index, day ->
                val maxT = if (celsius) day.max else toF(day.max)
                val minT = if (celsius) day.min else toF(day.min)
                val rain = if (celsius) "${day.rainMm} mm" else "%.2f in".format(day.rainMm / 25.4)
                kit.row(
                    group,
                    if (index == 0) R.drawable.oic_star_fill else R.drawable.oic_thermostat,
                    dayLabel(day.date),
                    "${weatherDesc(day.code)} · $minT° to $maxT° · $rain at ${day.rainChance}% · gusts ${if (celsius) "${day.gust.toInt()} km/h" else "%.0f mph".format(day.gust * 0.621371)}",
                    value = "UV ${day.uvPeak.toInt()}",
                    chevron = false
                ) {
                    showDay(host, kit, day, celsius)
                }
            }
        }

        if (air != null) {
            kit.sectionTitle(content, "Air")
            val group = kit.group(content)
            kit.row(
                group,
                R.drawable.oic_ac_unit,
                "US AQI ${air.aqi}",
                airAdvice(air.aqi),
                value = "PM2.5 ${"%.1f".format(air.pm25)}",
                chevron = false
            )
            kit.row(group, R.drawable.oic_visibility_off, "PM10 ${"%.1f".format(air.pm10)} µg/m³", "Open-Meteo's air-quality model, the same source as the forecast", chevron = false)
            kit.note(content, "Modelled air quality, not a monitoring station. It is a good guide to whether to go running; it is not a legal or medical measurement.")
        }

        val when1 = if (fetchedAt <= 0L) "no fetch yet" else SimpleDateFormat("HH:mm 'on' d MMM", Locale.getDefault()).format(Date(fetchedAt))
        kit.note(
            content,
            "Open-Meteo · reading taken $when1 · kept for 30 minutes and reused when the network is not there. The home-screen weather widget shows this same cache. OEA does not repaint your theme from the weather; if you want a grey day, that part is yours."
        )
    }

    private fun showDay(host: Activity, kit: OeaKit, day: ForecastDay, celsius: Boolean) {
        kit.sheet(dayLabel(day.date), weatherDesc(day.code)) { panel, _ ->
            val group = kit.group(panel)
            kit.row(group, R.drawable.oic_thermostat, "High / low", "${if (celsius) day.max else toF(day.max)}° / ${if (celsius) day.min else toF(day.min)}°", chevron = false)
            kit.row(group, R.drawable.oic_ac_unit, "Rain", "${day.rainMm} mm total, ${day.rainChance}% of it falling", chevron = false)
            kit.row(group, R.drawable.oic_brightness_medium, "UV at its worst", day.uvPeak.toInt().toString(), chevron = false)
            kit.row(group, R.drawable.oic_speed, "Gusts", "${day.gust.toInt()} km/h", chevron = false)
            kit.row(group, R.drawable.oic_schedule, "Sunrise · sunset", "${formatTime(day.sunrise)} → ${formatTime(day.sunset)}", chevron = false)
        }
    }

    // ------------------------------------------------------------------ advisories

    private class Advice(val icon: Int, val title: String, val body: String, val tone: OeaKit.Tone)

    private fun advisories(now: Now, days: List<ForecastDay>): List<Advice> {
        val out = ArrayList<Advice>()
        if (now.gust >= 60) out.add(Advice(R.drawable.oic_speed, "Gusts at ${now.gust.toInt()} km/h", "Loose things outside will move. OEA raises this at 60 km/h and above.", OeaKit.Tone.WARNING))
        if (now.code in 95..99) out.add(Advice(R.drawable.oic_warning_fill, "Thunder overhead", "Wet code ${now.code} — the storm is the forecast's, not OEA's guess.", OeaKit.Tone.DANGER))
        if (now.uvPeak >= 8) out.add(Advice(R.drawable.oic_brightness_medium, "UV ${now.uvPeak.toInt()} today", "Short exposure burns at this level. Raised from 8.", OeaKit.Tone.WARNING))
        if (days.firstOrNull()?.min?.let { it <= 2.0 } == true) out.add(Advice(R.drawable.oic_ac_unit, "Near freezing tonight", "${days[0].min.toInt()}° at the coldest — frost where it is damp.", OeaKit.Tone.INFO))
        val wet = days.firstOrNull()?.rainMm ?: 0.0
        if (wet >= 20) out.add(Advice(R.drawable.oic_visibility_off, "${wet.toInt()} mm of rain today", "Standing water and slow drains; the number is the model's total.", OeaKit.Tone.WARNING))
        val hot = days.firstOrNull()?.max ?: 0.0
        if (hot >= 38) out.add(Advice(R.drawable.oic_thermostat, "High of ${hot.toInt()}°", "Water, shade, and no midday work outdoors.", OeaKit.Tone.DANGER))
        return out
    }

    private fun airAdvice(aqi: Int): String = when {
        aqi <= 50 -> "Good — go outside"
        aqi <= 100 -> "Moderate — sensitive throats may care"
        aqi <= 150 -> "Unhealthy for the young, the old and anyone breathing hard"
        aqi <= 200 -> "Unhealthy for everyone; keep the run short"
        aqi <= 300 -> "Very unhealthy — windows shut"
        else -> "Hazardous — this is a stay-in day"
    }

    // ------------------------------------------------------------------ small

    private fun toF(celsiusValue: Double): Double = celsiusValue * 9.0 / 5.0 + 32.0

    private fun shown(value: Double): String = value.toInt().toString()

    private fun dayLabel(iso: String): String = try {
        val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso)
        if (parsed == null) iso else SimpleDateFormat("EEEE d MMM", Locale.getDefault()).format(parsed)
    } catch (_: Throwable) {
        iso
    }

    private fun formatTime(iso: String): String =
        if (iso.isBlank()) "—" else (if (iso.contains("T")) iso.substringAfter("T").take(5) else iso.take(5))

    private fun encode(text: String): String = java.net.URLEncoder.encode(text, "UTF-8")

    private fun weatherDesc(code: Int): String = when (code) {
        0 -> "Clear sky"
        1 -> "Mainly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing drizzle"
        61 -> "Light rain"
        63 -> "Rain"
        65 -> "Heavy rain"
        66, 67 -> "Freezing rain"
        71 -> "Light snow"
        73, 75 -> "Snow"
        77 -> "Snow grains"
        80, 81, 82 -> "Rain showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm with hail"
        else -> "Weather $code"
    }
}
