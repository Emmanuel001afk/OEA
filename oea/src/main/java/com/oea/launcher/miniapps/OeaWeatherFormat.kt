package com.oea.launcher.miniapps

import java.util.Locale
import kotlin.math.roundToInt

/** What an advisory is about; the app maps this to a colour, the rules stay testable. */
internal enum class OeaWeatherAdvisoryKind { GUSTS, THUNDER, UV, FROST, RAIN, HEAT }

/** One advisory, already worded for the unit the screen is showing. */
internal data class OeaWeatherAdvisory(val kind: OeaWeatherAdvisoryKind, val title: String, val body: String)

/**
 * Units, rounding, wording and thresholds — the parts of Weather that can be wrong
 * without crashing, and therefore the parts worth testing.
 *
 * Open-Meteo is asked for metric everything (the request sends no unit parameters), so
 * every number in the app arrives in °C, km/h and mm. The screen converts on the way
 * out, and this is the only place the conversion happens: before this, the day sheet
 * said "12 km/h" and "3 mm" inside a screen that was otherwise in °F and mph, which is
 * exactly the half-converted app the file's own header says it refuses to be.
 */
internal object OeaWeatherFormat {

    /** One mile per hour in kilometres per hour, exactly. */
    const val KMH_PER_MPH = 1.609344

    /** One inch of rain in millimetres, exactly. */
    const val MM_PER_INCH = 25.4

    fun toF(celsius: Double): Double = celsius * 9.0 / 5.0 + 32.0

    /**
     * The number a person should see, rounded rather than truncated.
     *
     * `Double.toInt()` turned 21.9 into 21 and -0.4 into 0 but -1.2 into -1, so the
     * card could read a degree colder all winter than every other weather app.
     */
    fun temperature(value: Double, celsius: Boolean): Int =
        if (celsius) value.roundToInt() else toF(value).roundToInt()

    /** "21°" — the degree sign without a letter is what the card prints. */
    fun degrees(value: Double, celsius: Boolean): String = "${temperature(value, celsius)}°"

    /** "12 km/h" or "7 mph". */
    fun speed(kmh: Double, celsius: Boolean): String =
        if (celsius) "${kmh.roundToInt()} km/h"
        else "${(kmh / KMH_PER_MPH).roundToInt()} mph"

    /** "3.2 mm" or "0.13 in". */
    fun rain(mm: Double, celsius: Boolean): String =
        if (celsius) String.format(Locale.US, "%.1f mm", mm)
        else String.format(Locale.US, "%.2f in", mm / MM_PER_INCH)

    /** "1013 hPa" — pressure is not converted, and saying so is cheaper than guessing. */
    fun pressure(hPa: Double): String = "${hPa.roundToInt()} hPa"

    /**
     * The identity of a place for caching.
     *
     * The cache used to be one blob and one timestamp for every place, so changing city
     * and then opening Weather offline showed the new city's name above the old city's
     * weather. Three decimals is ~110 m: closer than any fix this app accepts.
     */
    fun placeKey(lat: Double, lon: Double): String =
        String.format(Locale.US, "%.3f,%.3f", lat, lon)

    /** Open-Meteo's WMO weather codes, in one place so the widget cannot disagree. */
    fun description(code: Int): String = when (code) {
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

    /** US AQI bands, worded the way a person would say them. */
    fun airAdvice(aqi: Int): String = when {
        aqi <= 50 -> "Good — go outside"
        aqi <= 100 -> "Moderate — sensitive throats may care"
        aqi <= 150 -> "Unhealthy for the young, the old and anyone breathing hard"
        aqi <= 200 -> "Unhealthy for everyone; keep the run short"
        aqi <= 300 -> "Very unhealthy — windows shut"
        else -> "Hazardous — this is a stay-in day"
    }

    /**
     * Advisories computed from the model's own numbers.
     *
     * The thresholds are metric and stay metric (the source data is metric), and only
     * the sentence is converted — a rule that changed meaning when the user tapped °F
     * would be a different rule, not a translation.
     */
    fun advisories(now: OeaWeatherApp.Now, today: OeaWeatherApp.ForecastDay?, celsius: Boolean): List<OeaWeatherAdvisory> {
        val out = ArrayList<OeaWeatherAdvisory>(4)
        if (now.gust >= 60.0) {
            out += OeaWeatherAdvisory(
                OeaWeatherAdvisoryKind.GUSTS,
                "Gusts at ${speed(now.gust, celsius)}",
                "Loose things outside will move. OEA raises this at 60 km/h (37 mph)."
            )
        }
        if (now.code in 95..99) {
            out += OeaWeatherAdvisory(
                OeaWeatherAdvisoryKind.THUNDER,
                "Thunder overhead",
                "Wet code ${now.code} — the storm is the forecast's, not OEA's guess."
            )
        }
        if (now.uvPeak >= 8.0) {
            out += OeaWeatherAdvisory(
                OeaWeatherAdvisoryKind.UV,
                "UV ${now.uvPeak.roundToInt()} today",
                "Short exposure burns at this level. OEA raises this from 8."
            )
        }
        if (today != null && today.min <= 2.0) {
            out += OeaWeatherAdvisory(
                OeaWeatherAdvisoryKind.FROST,
                "Near freezing tonight",
                "${degrees(today.min, celsius)} at the coldest — frost where it is damp."
            )
        }
        if (today != null && today.rainMm >= 20.0) {
            out += OeaWeatherAdvisory(
                OeaWeatherAdvisoryKind.RAIN,
                "${rain(today.rainMm, celsius)} of rain today",
                "Standing water and slow drains; the number is the model's total."
            )
        }
        if (today != null && today.max >= 38.0) {
            out += OeaWeatherAdvisory(
                OeaWeatherAdvisoryKind.HEAT,
                "High of ${degrees(today.max, celsius)}",
                "Water, shade, and no midday work outdoors."
            )
        }
        return out
    }

    /**
     * How old a reading is, in words, and whether it should be presented as stale.
     *
     * The cache exists so Weather opens on a plane and still says something; it should
     * also say how long ago that something was true, because "Updated 09:12" means very
     * different things at 09:20 and at 09:12 the next morning.
     */
    fun age(now: Long, fetchedAt: Long): String? {
        if (fetchedAt <= 0L) return null
        val minutes = ((now - fetchedAt).coerceAtLeast(0L) / 60_000L)
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 48 * 60 -> "${minutes / 60} h ago"
            else -> "${minutes / (24 * 60)} days ago"
        }
    }

    /** Older than a day of readings is worth flagging on the card. */
    fun stale(now: Long, fetchedAt: Long): Boolean =
        fetchedAt > 0L && now - fetchedAt > 24L * 60L * 60L * 1000L
}
