package com.oea.launcher.widgets

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** Shared visual presets for OEA's own RemoteViews widgets. */
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

    val presets = listOf(
        Preset("glass", "Liquid Glass", "Cool glass with a clear cyan highlight", 0xFF181F2F.toInt(), 0xFFFFFFFF.toInt(), 0xFFB8C4D8.toInt(), 0xFF78D9FF.toInt()),
        Preset("lagoon", "Lagoon", "Deep teal with a fresh mint accent", 0xFF0D2727.toInt(), 0xFFF0FFFA.toInt(), 0xFFB6DAD2.toInt(), 0xFF70E7C0.toInt()),
        Preset("ember", "Ember", "Warm dusk surfaces and soft coral light", 0xFF2A191E.toInt(), 0xFFFFF3EC.toInt(), 0xFFE3C0B8.toInt(), 0xFFFFAD83.toInt()),
        Preset("pixel", "Pixel Night", "High-contrast monochrome with a gold signal", 0xFF111216.toInt(), 0xFFF5F5F5.toInt(), 0xFFB7B8C0.toInt(), 0xFFFFD166.toInt())
    )

    private const val PREFS = "oea_widget_studio"
    private const val KEY_PRESET = "preset"
    private const val KEY_OPACITY = "opacity"
    const val MIN_OPACITY = 35
    const val MAX_OPACITY = 100

    fun selected(context: Context): Preset {
        val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PRESET, "glass")
        return presets.firstOrNull { it.id == id } ?: presets.first()
    }

    fun opacity(context: Context): Int = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getInt(KEY_OPACITY, 82).coerceIn(MIN_OPACITY, MAX_OPACITY)

    fun save(context: Context, presetId: String = selected(context).id, opacityPercent: Int = opacity(context)) {
        val normalizedId = presets.firstOrNull { it.id == presetId }?.id ?: presets.first().id
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PRESET, normalizedId)
            .putInt(KEY_OPACITY, opacityPercent.coerceIn(MIN_OPACITY, MAX_OPACITY))
            .apply()
        refreshWidgets(context)
    }

    fun apply(views: RemoteViews, context: Context, rootId: Int, primaryIds: IntArray, secondaryIds: IntArray, accentIds: IntArray) {
        val style = selected(context)
        views.setInt(rootId, "setBackgroundColor", style.surface(opacity(context)))
        primaryIds.forEach { views.setTextColor(it, style.primary) }
        secondaryIds.forEach { views.setTextColor(it, style.secondary) }
        accentIds.forEach { views.setTextColor(it, style.accent) }
    }

    /** Updates every currently placed OEA widget; newly added instances read the same saved preset. */
    fun refreshWidgets(context: Context) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val providers = listOf(
            OeaClockWidgetProvider::class.java,
            OeaWeatherWidgetProvider::class.java,
            OeaCalendarWidgetProvider::class.java,
            OeaNotesWidgetProvider::class.java,
            OeaImageWidgetProvider::class.java,
            OeaVideoWidgetProvider::class.java,
            OeaCalculatorWidgetProvider::class.java,
            OeaCameraWidgetProvider::class.java
        )
        providers.forEach { provider ->
            val component = ComponentName(context, provider)
            val ids = appWidgetManager.getAppWidgetIds(component)
            if (ids.isEmpty()) return@forEach
            context.sendBroadcast(
                Intent(context, provider)
                    .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            )
        }
    }
}
