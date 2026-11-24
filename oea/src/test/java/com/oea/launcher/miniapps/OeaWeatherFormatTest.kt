package com.oea.launcher.miniapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of Weather that can be wrong without crashing: rounding, the two unit
 * systems, the words for the same code in two places, and the thresholds behind the
 * advisories. Open-Meteo is asked for metric everything, so a screen in °F that still
 * says "12 km/h" or "3 mm" is half converted — the file's own header calls that wrong,
 * and it was true in the day sheet.
 */
class OeaWeatherFormatTest {

    private fun now(
        gust: Double = 0.0,
        code: Int = 0,
        uv: Double = 0.0
    ) = OeaWeatherApp.Now(
        temp = 20.0, feelsLike = 20.0, code = code, wind = 10.0, gust = gust,
        humidity = 50, pressure = 1013.0, cloud = 0, precip = 0.0, uvPeak = uv,
        sunrise = "", sunset = "", city = "Lagos", lat = 6.5, lon = 3.4
    )

    private fun day(
        max: Double = 30.0,
        min: Double = 20.0,
        rainMm: Double = 0.0,
        uv: Double = 0.0
    ) = OeaWeatherApp.ForecastDay(
        date = "2026-11-24", max = max, min = min, code = 0, sunrise = "", sunset = "",
        rainMm = rainMm, rainChance = 0, uvPeak = uv, gust = 0.0
    )

    // ------------------------------------------------------------------ rounding

    @Test fun temperaturesRoundRatherThanTruncate() {
        assertEquals(22, OeaWeatherFormat.temperature(21.9, celsius = true))
        assertEquals(21, OeaWeatherFormat.temperature(21.4, celsius = true))
        assertEquals(-2, OeaWeatherFormat.temperature(-1.6, celsius = true))
        assertEquals(0, OeaWeatherFormat.temperature(-0.4, celsius = true))
        assertEquals("22°", OeaWeatherFormat.degrees(21.9, celsius = true))
    }

    @Test fun fahrenheitConvertsThenRounds() {
        assertEquals(32, OeaWeatherFormat.temperature(0.0, celsius = false))
        assertEquals(212, OeaWeatherFormat.temperature(100.0, celsius = false))
        // 21.9 °C is 71.42 °F: 71, not 71.42 and not 70.
        assertEquals(71, OeaWeatherFormat.temperature(21.9, celsius = false))
        assertEquals(-40, OeaWeatherFormat.temperature(-40.0, celsius = false))
    }

    // ------------------------------------------------------------------ units

    @Test fun speedIsConvertedNotRelabelled() {
        assertEquals("100 km/h", OeaWeatherFormat.speed(100.0, celsius = true))
        assertEquals("62 mph", OeaWeatherFormat.speed(100.0, celsius = false))
        assertEquals("0 mph", OeaWeatherFormat.speed(0.0, celsius = false))
        // 37 mph is the 60 km/h advisory threshold, and the number must agree.
        assertEquals("37 mph", OeaWeatherFormat.speed(60.0, celsius = false))
    }

    @Test fun rainIsConvertedInTheDaySheetToo() {
        assertEquals("3.2 mm", OeaWeatherFormat.rain(3.2, celsius = true))
        assertEquals("0.13 in", OeaWeatherFormat.rain(3.2, celsius = false))
        assertEquals("0.79 in", OeaWeatherFormat.rain(20.0, celsius = false))
    }

    @Test fun pressureIsStatedNotConverted() {
        assertEquals("1013 hPa", OeaWeatherFormat.pressure(1013.4))
    }

    // ------------------------------------------------------------------ place key

    @Test fun theCacheKeyIsThePlaceNotTheTime() {
        assertEquals(OeaWeatherFormat.placeKey(6.5244, 3.3792), OeaWeatherFormat.placeKey(6.52441, 3.37921))
        assertFalse(OeaWeatherFormat.placeKey(6.5244, 3.3792) == OeaWeatherFormat.placeKey(6.6018, 3.3515))
        // A separate process (the widget) formats it the same way, so it must not be locale-dependent.
        assertEquals("6.524,3.379", OeaWeatherFormat.placeKey(6.5244, 3.3792))
    }

    // ------------------------------------------------------------------ wording

    @Test fun oneVocabularyForTheSameCode() {
        assertEquals("Clear sky", OeaWeatherFormat.description(0))
        assertEquals("Freezing drizzle", OeaWeatherFormat.description(56))
        assertEquals("Rain showers", OeaWeatherFormat.description(80))
        assertEquals("Thunderstorm with hail", OeaWeatherFormat.description(96))
        assertEquals("Weather 42", OeaWeatherFormat.description(42))
        // The widget used to say "Showers" and "Weather 96" where the app said otherwise.
        assertEquals(OeaWeatherFormat.description(96), OeaWeatherFormat.description(99))
    }

    @Test fun airQualityBandsHaveEdges() {
        assertEquals("Good — go outside", OeaWeatherFormat.airAdvice(0))
        assertEquals("Good — go outside", OeaWeatherFormat.airAdvice(50))
        assertTrue(OeaWeatherFormat.airAdvice(51).startsWith("Moderate"))
        assertEquals("Unhealthy for everyone; keep the run short", OeaWeatherFormat.airAdvice(200))
        assertEquals("Hazardous — this is a stay-in day", OeaWeatherFormat.airAdvice(301))
    }

    // ------------------------------------------------------------------ advisories

    private fun kinds(celsius: Boolean, now: OeaWeatherApp.Now, today: OeaWeatherApp.ForecastDay?) =
        OeaWeatherFormat.advisories(now, today, celsius).map { it.kind }

    @Test fun aGustAdvisoryStartsAtSixtyKilometresAnHour() {
        assertEquals(emptyList<OeaWeatherAdvisoryKind>(), kinds(true, now(gust = 59.9), day()))
        assertEquals(listOf(OeaWeatherAdvisoryKind.GUSTS), kinds(true, now(gust = 60.0), day()))
        assertEquals(listOf(OeaWeatherAdvisoryKind.GUSTS), kinds(true, now(gust = 91.0), day()))
    }

    @Test fun theGustSentenceFollowsTheChosenUnit() {
        val kmh = OeaWeatherFormat.advisories(now(gust = 91.0), day(), celsius = true).single()
        val mph = OeaWeatherFormat.advisories(now(gust = 91.0), day(), celsius = false).single()
        assertEquals("Gusts at 91 km/h", kmh.title)
        assertEquals("Gusts at 57 mph", mph.title)
    }

    @Test fun thunderIsTheStormInTheForecastNotAGuess() {
        assertEquals(emptyList<OeaWeatherAdvisoryKind>(), kinds(true, now(code = 94), day()))
        assertEquals(listOf(OeaWeatherAdvisoryKind.THUNDER), kinds(true, now(code = 95), day()))
        assertEquals(listOf(OeaWeatherAdvisoryKind.THUNDER), kinds(true, now(code = 99), day()))
    }

    @Test fun uvAdvisoryStartsAtEight() {
        assertEquals(emptyList<OeaWeatherAdvisoryKind>(), kinds(true, now(uv = 7.9), day()))
        assertEquals(listOf(OeaWeatherAdvisoryKind.UV), kinds(true, now(uv = 8.0), day()))
    }

    @Test fun frostRainAndHeatComeFromTodayNotTheWeek() {
        assertEquals(listOf(OeaWeatherAdvisoryKind.FROST), kinds(true, now(), day(min = 2.0)))
        assertEquals(emptyList<OeaWeatherAdvisoryKind>(), kinds(true, now(), day(min = 2.1)))
        assertEquals(listOf(OeaWeatherAdvisoryKind.RAIN), kinds(true, now(), day(rainMm = 20.0)))
        assertEquals(emptyList<OeaWeatherAdvisoryKind>(), kinds(true, now(), day(rainMm = 19.9)))
        assertEquals(listOf(OeaWeatherAdvisoryKind.HEAT), kinds(true, now(), day(max = 38.0)))
        assertEquals(emptyList<OeaWeatherAdvisoryKind>(), kinds(true, now(), day(max = 37.9)))
    }

    @Test fun aFrostSentenceSpeaksFahrenheitWhenTheScreenDoes() {
        val celsius = OeaWeatherFormat.advisories(now(), day(min = 1.0), celsius = true).single()
        val fahrenheit = OeaWeatherFormat.advisories(now(), day(min = 1.0), celsius = false).single()
        assertTrue(celsius.body.startsWith("1°"))
        assertTrue(fahrenheit.body.startsWith("34°"))
    }

    @Test fun aRainSentenceConvertsToo() {
        val inches = OeaWeatherFormat.advisories(now(), day(rainMm = 25.4), celsius = false).single()
        assertEquals("1.00 in of rain today", inches.title)
    }

    @Test fun nothingToWarnAboutIsAnEmptyListNotNull() {
        assertEquals(emptyList<OeaWeatherAdvisoryKind>(), kinds(true, now(), null))
        assertEquals(emptyList<OeaWeatherAdvisoryKind>(), kinds(false, now(), null))
    }

    // ------------------------------------------------------------------ age

    @Test fun ageSaysHowLongAgoInWords() {
        val base = 1_700_000_000_000L
        assertEquals("just now", OeaWeatherFormat.age(base + 20_000L, base))
        assertEquals("5 min ago", OeaWeatherFormat.age(base + 5 * 60_000L, base))
        assertEquals("2 h ago", OeaWeatherFormat.age(base + 2 * 3_600_000L, base))
        assertEquals("3 days ago", OeaWeatherFormat.age(base + 3 * 86_400_000L, base))
        assertNull(OeaWeatherFormat.age(base, 0L))
    }

    @Test fun aReadingBecomesStaleAfterADay() {
        val base = 1_700_000_000_000L
        assertFalse(OeaWeatherFormat.stale(base + 23 * 3_600_000L, base))
        assertTrue(OeaWeatherFormat.stale(base + 25 * 3_600_000L, base))
        assertFalse(OeaWeatherFormat.stale(base, 0L))
    }
}
