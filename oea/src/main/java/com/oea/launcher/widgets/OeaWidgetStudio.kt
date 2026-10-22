package com.oea.launcher.widgets

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import android.widget.RemoteViews
import kotlin.math.roundToInt

/** Shared looks and lightweight content controls for OEA's own RemoteViews widgets. */
object OeaWidgetStudio {
    data class Preset(
        val id: String,
        val label: String,
        val description: String,
        val background: Int,
        val primary: Int,
        val secondary: Int,
        val accent: Int
    ) {
        fun surface(opacityPercent: Int): Int {
            val alpha = opacityPercent.coerceIn(MIN_OPACITY, MAX_OPACITY) * 255 / 100
            return (alpha shl 24) or (background and 0x00FFFFFF)
        }
    }

    data class Family(val id: String, val label: String, val description: String)

    const val ALL_FAMILY = "all"
    val families = listOf(
        Family(ALL_FAMILY, "All OEA widgets", "Set a shared fallback style for every OEA widget."),
        Family("clock", "Clock", "Time, date and quick access to OEA Clock."),
        Family("weather", "Weather", "Current conditions with a selectable temperature unit."),
        Family("calendar", "Calendar", "The current month and event summary."),
        Family("notes", "Notes", "A compact view of your saved notes."),
        Family("image", "EMAGE", "A shortcut and favourites count for your pictures."),
        Family("video", "VIP", "A shortcut with favourites and playback history."),
        Family("calculator", "Calculator", "Your most recent calculator result."),
        Family("camera", "Camera", "A one-tap entry to OEA Camera.")
    )

    /**
     * Curated, named widget looks. These are deliberately color/surface presets rather than
     * claiming to be independent layouts: every preset works with every supported OEA widget.
     */
    val presets = listOf(
        Preset("glass", "Liquid Glass", "Translucent slate with a cyan highlight", 0xFF181F2F.toInt(), 0xFFFFFFFF.toInt(), 0xFFB8C4D8.toInt(), 0xFF78D9FF.toInt()),
        Preset("lagoon", "Lagoon", "Deep teal with a fresh mint accent", 0xFF0D2727.toInt(), 0xFFF0FFFA.toInt(), 0xFFB6DAD2.toInt(), 0xFF70E7C0.toInt()),
        Preset("ember", "Ember", "Warm dusk surfaces and soft coral light", 0xFF2A191E.toInt(), 0xFFFFF3EC.toInt(), 0xFFE3C0B8.toInt(), 0xFFFFAD83.toInt()),
        Preset("pixel", "Pixel Night", "High-contrast dark monochrome with gold", 0xFF111216.toInt(), 0xFFF5F5F5.toInt(), 0xFFB7B8C0.toInt(), 0xFFFFD166.toInt()),
        Preset("arctic", "Arctic", "Cool navy with ice-blue details", 0xFF101F32.toInt(), 0xFFF4FAFF.toInt(), 0xFFB9D0E6.toInt(), 0xFF82CFFF.toInt()),
        Preset("rose", "Rose Quartz", "Plum glass with a soft rose accent", 0xFF2A1B2A.toInt(), 0xFFFFF5FB.toInt(), 0xFFE6C9DE.toInt(), 0xFFFF9ECF.toInt()),
        Preset("forest", "Forest", "Deep evergreen with a lime-mint signal", 0xFF14271D.toInt(), 0xFFF2FFF5.toInt(), 0xFFB9D7C2.toInt(), 0xFFA5E887.toInt()),
        Preset("sunset", "Sunset", "Dark aubergine with peach and amber", 0xFF2A1B2D.toInt(), 0xFFFFF5F0.toInt(), 0xFFE5C4BB.toInt(), 0xFFFFB36B.toInt()),
        Preset("paper", "Soft Paper", "Light warm surface with readable dark text", 0xFFF2EEE6.toInt(), 0xFF252A32.toInt(), 0xFF5D6470.toInt(), 0xFF176E8A.toInt()),
        Preset("midnight", "Midnight Blue", "Minimal midnight blue with violet signal", 0xFF17172D.toInt(), 0xFFF7F6FF.toInt(), 0xFFC5C3E6.toInt(), 0xFFB4A1FF.toInt())
    )

    private const val PREFS = "oea_widget_studio"
    private const val KEY_PRESET = "preset"
    private const val KEY_OPACITY = "opacity"
    private const val KEY_CLOCK_24_HOUR = "clock_24_hour"
    private const val KEY_CLOCK_SHOW_DATE = "clock_show_date"
    private const val KEY_WEATHER_FAHRENHEIT = "weather_fahrenheit"
    const val MIN_OPACITY = 35
    const val MAX_OPACITY = 100

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun familyKey(key: String, familyId: String) = "${key}_$familyId"

    fun selected(context: Context, familyId: String = ALL_FAMILY): Preset {
        val store = prefs(context)
        val id = if (familyId == ALL_FAMILY) store.getString(KEY_PRESET, "glass")
        else store.getString(familyKey(KEY_PRESET, familyId), store.getString(KEY_PRESET, "glass"))
        return presets.firstOrNull { it.id == id } ?: presets.first()
    }

    fun opacity(context: Context, familyId: String = ALL_FAMILY): Int {
        val store = prefs(context)
        val value = if (familyId == ALL_FAMILY) store.getInt(KEY_OPACITY, 82)
        else store.getInt(familyKey(KEY_OPACITY, familyId), store.getInt(KEY_OPACITY, 82))
        return value.coerceIn(MIN_OPACITY, MAX_OPACITY)
    }

    /** Save a shared look and clear family overrides so “All widgets” really updates every family. */
    fun save(context: Context, familyId: String, presetId: String, opacityPercent: Int) {
        val validFamily = families.any { it.id == familyId }
        val target = if (validFamily) familyId else ALL_FAMILY
        val normalizedId = presets.firstOrNull { it.id == presetId }?.id ?: presets.first().id
        val editor = prefs(context).edit()
        if (target == ALL_FAMILY) {
            editor.putString(KEY_PRESET, normalizedId).putInt(KEY_OPACITY, opacityPercent.coerceIn(MIN_OPACITY, MAX_OPACITY))
            families.filter { it.id != ALL_FAMILY }.forEach { family ->
                editor.remove(familyKey(KEY_PRESET, family.id))
                editor.remove(familyKey(KEY_OPACITY, family.id))
            }
        } else {
            editor.putString(familyKey(KEY_PRESET, target), normalizedId)
                .putInt(familyKey(KEY_OPACITY, target), opacityPercent.coerceIn(MIN_OPACITY, MAX_OPACITY))
        }
        editor.apply()
        refreshWidgets(context, target)
    }

    fun apply(views: RemoteViews, context: Context, familyId: String, rootId: Int, primaryIds: IntArray, secondaryIds: IntArray, accentIds: IntArray) {
        val style = selected(context, familyId)
        views.setInt(rootId, "setBackgroundColor", style.surface(opacity(context, familyId)))
        primaryIds.forEach { views.setTextColor(it, style.primary) }
        secondaryIds.forEach { views.setTextColor(it, style.secondary) }
        accentIds.forEach { views.setTextColor(it, style.accent) }
    }

    fun clockUses24Hour(context: Context): Boolean = prefs(context).getBoolean(KEY_CLOCK_24_HOUR, DateFormat.is24HourFormat(context))
    fun clockShowsDate(context: Context): Boolean = prefs(context).getBoolean(KEY_CLOCK_SHOW_DATE, true)
    fun saveClockOptions(context: Context, use24Hour: Boolean, showDate: Boolean) {
        prefs(context).edit().putBoolean(KEY_CLOCK_24_HOUR, use24Hour).putBoolean(KEY_CLOCK_SHOW_DATE, showDate).apply()
        refreshWidgets(context, "clock")
    }

    fun weatherUsesFahrenheit(context: Context): Boolean = prefs(context).getBoolean(KEY_WEATHER_FAHRENHEIT, false)
    fun saveWeatherUnit(context: Context, fahrenheit: Boolean) {
        prefs(context).edit().putBoolean(KEY_WEATHER_FAHRENHEIT, fahrenheit).apply()
        refreshWidgets(context, "weather")
    }

    fun temperature(celsius: Double, fahrenheit: Boolean): String {
        val value = if (fahrenheit) celsiusToFahrenheit(celsius) else celsius.roundToInt()
        return "$value°${if (fahrenheit) "F" else "C"}"
    }

    fun celsiusToFahrenheit(celsius: Double): Int = (celsius * 9.0 / 5.0 + 32.0).roundToInt()

    /** Update the selected family, or every family when the shared fallback changes. */
    fun refreshWidgets(context: Context, familyId: String? = null) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val providers = listOf(
            "clock" to OeaClockWidgetProvider::class.java,
            "weather" to OeaWeatherWidgetProvider::class.java,
            "calendar" to OeaCalendarWidgetProvider::class.java,
            "notes" to OeaNotesWidgetProvider::class.java,
            "image" to OeaImageWidgetProvider::class.java,
            "video" to OeaVideoWidgetProvider::class.java,
            "calculator" to OeaCalculatorWidgetProvider::class.java,
            "camera" to OeaCameraWidgetProvider::class.java
        )
        providers.filter { familyId == null || familyId == ALL_FAMILY || it.first == familyId }.forEach { (_, provider) ->
            val ids = appWidgetManager.getAppWidgetIds(ComponentName(context, provider))
            if (ids.isEmpty()) return@forEach
            context.sendBroadcast(
                Intent(context, provider)
                    .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            )
        }
    }
}
