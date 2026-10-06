package com.oea.launcher.widgets

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.appwidget.AppWidgetProviderInfo

class OeaWidgetController(private val context: Context) {
    companion object { const val REQUEST_PICK_WIDGET = 7401 }
    private val prefs = context.getSharedPreferences("oea_widgets", Context.MODE_PRIVATE)
    private val manager = AppWidgetManager.getInstance(context)
    private val host = AppWidgetHost(context, 0x4F4541)
    private val ids = LinkedHashSet<Int>()

    init { loadIds() }

    fun start() = runCatching { host.startListening() }
    fun stop() = runCatching { host.stopListening() }

    fun pickWidget(activity: Activity) {
        val id = host.allocateAppWidgetId()
        activity.startActivityForResult(
            Intent(AppWidgetManager.ACTION_APPWIDGET_PICK)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
            REQUEST_PICK_WIDGET
        )
    }

    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQUEST_PICK_WIDGET) return false
        val id = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1) ?: -1
        if (id <= 0 || resultCode != Activity.RESULT_OK) return true
        if (!manager.getAppWidgetInfo(id).let { it != null }) {
            host.deleteAppWidgetId(id)
            return true
        }
        ids += id
        saveIds()
        return true
    }

    fun views(): List<AppWidgetHostView> = ids.mapNotNull { id ->
        manager.getAppWidgetInfo(id)?.let { info ->
            runCatching { host.createView(context, id, info) }.getOrNull()
        }
    }

    fun remove(id: Int) {
        ids.remove(id)
        host.deleteAppWidgetId(id)
        saveIds()
    }

    fun providers(): List<AppWidgetProviderInfo> = ids.mapNotNull { manager.getAppWidgetInfo(it) }

    private fun loadIds() {
        prefs.getStringSet("ids", emptySet()).orEmpty().mapNotNull { it.toIntOrNull() }.forEach { ids += it }
    }
    private fun saveIds() {
        prefs.edit().putStringSet("ids", ids.map(Int::toString).toSet()).apply()
    }
}
