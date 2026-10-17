package com.oea.launcher.miniapps

import android.content.Context

/**
 * The arithmetic behind the calculator's converter.
 *
 * Every unit is a factor against one base unit for its family, which is how a
 * converter actually works: one multiply, no lookup tables of pairs. Temperature is
 * the only family that is not a ratio (it has an offset), so it gets its own case
 * instead of a fudged factor.
 *
 * Currency is deliberately not here. Rates are not arithmetic — they are somebody's
 * opinion on a Tuesday afternoon — so OEA keeps them as numbers *you* set in the
 * app's preferences and never claims to know today's rate without being told.
 */
internal object OeaUnits {

    data class Unit(val symbol: String, val label: String, val toBase: Double)

    data class Family(val name: String, val base: Unit, val units: List<Unit>) {
        /** Every unit of this family, base first, for a picker. */
        val all: List<Unit> get() = listOf(base) + units
    }

    val LENGTH = Family(
        "Length",
        Unit("m", "metre", 1.0),
        listOf(
            Unit("nm", "nanometre", 1e-9),
            Unit("µm", "micrometre", 1e-6),
            Unit("mm", "millimetre", 1e-3),
            Unit("cm", "centimetre", 0.01),
            Unit("km", "kilometre", 1000.0),
            Unit("in", "inch", 0.0254),
            Unit("ft", "foot", 0.3048),
            Unit("yd", "yard", 0.9144),
            Unit("mi", "mile", 1609.344),
            Unit("nmi", "nautical mile", 1852.0),
            Unit("ly", "light year", 9.4607304725808e15)
        )
    )

    val MASS = Family(
        "Mass",
        Unit("kg", "kilogram", 1.0),
        listOf(
            Unit("mg", "milligram", 1e-6),
            Unit("g", "gram", 0.001),
            Unit("t", "tonne", 1000.0),
            Unit("oz", "ounce", 0.028349523125),
            Unit("lb", "pound", 0.45359237),
            Unit("st", "stone", 6.35029318),
            Unit("ct", "carat", 2e-4)
        )
    )

    val AREA = Family(
        "Area",
        Unit("m²", "square metre", 1.0),
        listOf(
            Unit("cm²", "square centimetre", 1e-4),
            Unit("ha", "hectare", 10_000.0),
            Unit("km²", "square kilometre", 1e6),
            Unit("ft²", "square foot", 0.09290304),
            Unit("ac", "acre", 4046.8564224),
            Unit("mi²", "square mile", 2589988.110336)
        )
    )

    val VOLUME = Family(
        "Volume",
        Unit("L", "litre", 1.0),
        listOf(
            Unit("mL", "millilitre", 0.001),
            Unit("m³", "cubic metre", 1000.0),
            Unit("in³", "cubic inch", 0.016387064),
            Unit("ft³", "cubic foot", 28.316846592),
            Unit("gal(US)", "US gallon", 3.785411784),
            Unit("gal(UK)", "imperial gallon", 4.54609),
            Unit("pt(US)", "US pint", 0.473176473),
            Unit("cup(US)", "US cup", 0.2365882365)
        )
    )

    val SPEED = Family(
        "Speed",
        Unit("m/s", "metres per second", 1.0),
        listOf(
            Unit("km/h", "kilometres per hour", 1.0 / 3.6),
            Unit("mph", "miles per hour", 0.44704),
            Unit("kn", "knot", 0.514444),
            Unit("m/s²·s", "metres per second squared", 1.0)
        )
    )

    val TIME = Family(
        "Time",
        Unit("s", "second", 1.0),
        listOf(
            Unit("ms", "millisecond", 0.001),
            Unit("µs", "microsecond", 1e-6),
            Unit("ns", "nanosecond", 1e-9),
            Unit("min", "minute", 60.0),
            Unit("h", "hour", 3600.0),
            Unit("d", "day", 86_400.0),
            Unit("wk", "week", 604_800.0),
            Unit("yr", "year (365 d)", 31_536_000.0)
        )
    )

    val DATA = Family(
        "Data",
        Unit("B", "byte", 1.0),
        listOf(
            Unit("bit", "bit", 0.125),
            Unit("KB", "kilobyte (1000)", 1000.0),
            Unit("KiB", "kibibyte (1024)", 1024.0),
            Unit("MB", "megabyte", 1e6),
            Unit("MiB", "mebibyte", 1048576.0),
            Unit("GB", "gigabyte", 1e9),
            Unit("GiB", "gibibyte", 1073741824.0),
            Unit("TB", "terabyte", 1e12),
            Unit("TiB", "tebibyte", 1099511627776.0)
        )
    )

    val PRESSURE = Family(
        "Pressure",
        Unit("Pa", "pascal", 1.0),
        listOf(
            Unit("hPa", "hectopascal", 100.0),
            Unit("bar", "bar", 100_000.0),
            Unit("mbar", "millibar", 100.0),
            Unit("psi", "pound per sq inch", 6894.757293168),
            Unit("atm", "atmosphere", 101_325.0),
            Unit("mmHg", "millimetre of mercury", 133.322387415)
        )
    )

    val ENERGY = Family(
        "Energy",
        Unit("J", "joule", 1.0),
        listOf(
            Unit("kJ", "kilojoule", 1000.0),
            Unit("cal", "calorie", 4.184),
            Unit("kcal", "kilocalorie", 4184.0),
            Unit("Wh", "watt hour", 3600.0),
            Unit("kWh", "kilowatt hour", 3.6e6),
            Unit("BTU", "British thermal unit", 1055.05585),
            Unit("eV", "electronvolt", 1.602176634e-19)
        )
    )

    val TEMPERATURE = Family(
        "Temperature",
        Unit("°C", "celsius", 1.0),
        listOf(Unit("°F", "fahrenheit", 1.0), Unit("K", "kelvin", 1.0), Unit("°R", "rankine", 1.0))
    )

    /** Rates are the user's numbers, so the family carries no factors of its own. */
    val CURRENCY = Family("Currency", Unit("USD", "US dollar", 1.0), emptyList())

    /** Everything the converter offers, in the order the chips show it. */
    val families = listOf(LENGTH, MASS, TEMPERATURE, AREA, VOLUME, SPEED, TIME, DATA, PRESSURE, ENERGY, CURRENCY)

    fun isTemperature(family: Family): Boolean = family.name == "Temperature"

    /** Temperature is the only family that needs an offset, so it is its own case. */
    fun temperature(value: Double, from: String, to: String): Double {
        val celsius = when (from) {
            "°F" -> (value - 32.0) * 5.0 / 9.0
            "K" -> value - 273.15
            "°R" -> (value - 491.67) * 5.0 / 9.0
            else -> value
        }
        return when (to) {
            "°F" -> celsius * 9.0 / 5.0 + 32.0
            "K" -> celsius + 273.15
            "°R" -> (celsius + 273.15) * 9.0 / 5.0
            else -> celsius
        }
    }

    fun convert(family: Family, from: Unit, to: Unit, value: Double): Double? {
        if (isTemperature(family)) return temperature(value, from.symbol, to.symbol)
        if (isCurrency(family)) return null
        return value * from.toBase / to.toBase
    }

    // ------------------------------------------------------------------ currency

    private const val PREFS = "oea_calc_currency"
    private const val KEY_RATES = "rates"
    private const val KEY_BASE = "base"

    /** Codes OEA offers; the numbers beside them are yours. */
    val CURRENCY_CODES = listOf(
        "USD", "EUR", "GBP", "NGN", "GH₵", "ZAR", "KES", "INR", "PKR", "BDT",
        "JPY", "CNY", "AED", "SAR", "CAD", "AUD", "CHF", "SEK", "BRL", "MXN", "EGP"
    )

    fun isCurrency(family: Family): Boolean = family.name == "Currency"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Units per [base], as the user last set them. The seed numbers are round
     * figures rather than a claim about today's market — enough to be useful with no
     * network, and labelled as approximate wherever they are shown.
     */
    fun rates(context: Context): Map<String, Double> {
        val base = base(context)
        val stored = prefs(context).getString(KEY_RATES, "") ?: ""
        val parsed = LinkedHashMap<String, Double>()
        stored.split(';').forEach { entry ->
            val parts = entry.split(':')
            if (parts.size == 2) {
                val value = parts[1].toDoubleOrNull()
                if (parts[0].isNotBlank() && value != null && value > 0.0) parsed[parts[0]] = value
            }
        }
        CURRENCY_CODES.forEach { code -> if (!parsed.containsKey(code)) parsed[code] = seed(code, base) }
        return parsed
    }

    private fun seed(code: String, base: String): Double {
        // Per-ish USD figures, rounded on purpose; edit them to your own bank's.
        val perUsd = mapOf(
            "USD" to 1.0, "EUR" to 0.92, "GBP" to 0.79, "NGN" to 1550.0, "GH₵" to 15.0,
            "ZAR" to 18.5, "KES" to 130.0, "INR" to 83.0, "PKR" to 278.0, "BDT" to 110.0,
            "JPY" to 149.0, "CNY" to 7.1, "AED" to 3.67, "SAR" to 3.75, "CAD" to 1.36,
            "AUD" to 1.52, "CHF" to 0.9, "SEK" to 10.6, "BRL" to 5.0, "MXN" to 17.0,
            "EGP" to 31.0
        )
        val from = perUsd[base] ?: 1.0
        val to = perUsd[code] ?: 1.0
        return (to / from * 10000.0).let { (it.toLong()) / 10000.0 }
    }

    fun base(context: Context): String = prefs(context).getString(KEY_BASE, "USD") ?: "USD"

    fun setBase(context: Context, code: String) {
        prefs(context).edit().putString(KEY_BASE, code).apply()
    }

    fun setRate(context: Context, code: String, value: Double) {
        val current = rates(context).toMutableMap()
        current[code] = value
        prefs(context).edit().putString(
            KEY_RATES,
            current.entries.joinToString(";") { "${it.key}:${"%.6f".format(it.value).trimEnd('0').trimEnd('.')}" }
        ).apply()
    }

    fun convertCurrency(context: Context, from: String, to: String, value: Double): Double? {
        val table = rates(context)
        val fromRate = table[from] ?: return null
        val toRate = table[to] ?: return null
        return value / fromRate * toRate
    }

    /** Trims the trailing zero soup a double can produce, keeping significant digits. */
    fun show(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return "—"
        val magnitude = kotlin.math.abs(value)
        val text = when {
            magnitude >= 1_000_000.0 || (magnitude > 0.0 && magnitude < 0.001) -> "%.6e".format(value)
            magnitude >= 100.0 -> "%.2f".format(value).trimEnd('0').trimEnd('.')
            else -> "%.6f".format(value).trimEnd('0').trimEnd('.')
        }
        return text.ifBlank { "0" }
    }
}
