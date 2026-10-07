package com.oea.launcher.widgets

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent

class OeaWidgetController(private val context: Context) {
    companion object {
        const val REQUEST_PICK_WIDGET = 7401
        const val REQUEST_CONFIGURE_WIDGET = 7402
    }

    private val prefs = context.getSharedPreferences("oea_widgets", Context.MODE_PRIVATE)
    private val manager = AppWidgetManager.getInstance(context)
    private val host = AppWidgetHost(context, 0x4F4541)
    private val ids = LinkedHashSet<Int>()
    private var hostActivity: Activity? = null
    private var listening = false
    private var pendingPage = 0
    private var pendingWidgetId = -1

    init { loadIds() }

    fun setHostActivity(activity: Activity?) { hostActivity = activity }
    fun start() {
        if (listening) return
        runCatching { host.startListening(); listening = true }
    }

    fun stop() {
        if (!listening) return
        runCatching { host.stopListening() }
        listening = false
    }

    fun pickWidget(activity: Activity, page: Int = 0) {
        hostActivity = activity
        pendingPage = page.coerceAtLeast(0)
        val id = host.allocateAppWidgetId()
        pendingWidgetId = id
        activity.startActivityForResult(
            Intent(AppWidgetManager.ACTION_APPWIDGET_PICK)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
            REQUEST_PICK_WIDGET,
        )
    }

    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQUEST_PICK_WIDGET && requestCode != REQUEST_CONFIGURE_WIDGET) return false
        val returnedId = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1) ?: -1
        val id = if (returnedId > 0) returnedId else pendingWidgetId
        if (id <= 0 || resultCode != Activity.RESULT_OK) {
            if (id > 0 && (requestCode == REQUEST_PICK_WIDGET || requestCode == REQUEST_CONFIGURE_WIDGET)) host.deleteAppWidgetId(id)
            pendingWidgetId = -1
            return true
        }
        val info = manager.getAppWidgetInfo(id)
        if (info == null) {
            host.deleteAppWidgetId(id)
            pendingWidgetId = -1
            return true
        }
        if (requestCode == REQUEST_PICK_WIDGET && info.configure != null) {
            val activity = hostActivity
            if (activity == null) {
                host.deleteAppWidgetId(id)
                return true
            }
            runCatching {
                activity.startActivityForResult(
                    Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
                        .setComponent(info.configure)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
                    REQUEST_CONFIGURE_WIDGET,
                )
            }.onFailure { host.deleteAppWidgetId(id); pendingWidgetId = -1 }
            return true
        }
        ids += id
        prefs.edit().putInt("page_$id", pendingPage).apply()
        saveIds()
        pendingWidgetId = -1
        return true
    }

    fun pageFor(id: Int): Int = prefs.getInt("page_$id", 0).coerceAtLeast(0)

    fun setPage(id: Int, page: Int) {
        if (ids.contains(id)) prefs.edit().putInt("page_$id", page.coerceAtLeast(0)).apply()
    }

    fun views(): List<AppWidgetHostView> = ids.mapNotNull { id ->
        manager.getAppWidgetInfo(id)?.let { info ->
            runCatching { host.createView(context, id, info) }.getOrNull()
        }
    }

    fun moveBefore(id: Int, targetId: Int?) {
        if (!ids.contains(id) || (targetId != null && !ids.contains(targetId))) return
        val list = ids.toMutableList().apply { remove(id) }
        val index = if (targetId == null) list.size else list.indexOf(targetId).coerceAtLeast(0)
        list.add(index, id)
        ids.clear()
        ids.addAll(list)
        saveIds()
    }

    fun remove(id: Int) {
        ids.remove(id)
        host.deleteAppWidgetId(id)
        prefs.edit().remove("size_$id").remove("page_$id").apply()
        saveIds()
    }

    fun sizeFor(id: Int): Pair<Int, Int> {
        val raw = prefs.getString("size_$id", null)?.split(",")
        val w = raw?.getOrNull(0)?.toIntOrNull()
        val h = raw?.getOrNull(1)?.toIntOrNull()
        return if (w != null && h != null && w > 0 && h > 0) w to h else 420 to 240
    }

    fun setSize(id: Int, widthDp: Int, heightDp: Int) {
        prefs.edit().putString("size_$id", "$widthDp,$heightDp").apply()
    }

    fun providers(): List<AppWidgetProviderInfo> = ids.mapNotNull { manager.getAppWidgetInfo(it) }

    private fun loadIds() {
        val ordered = prefs.getString("ordered_ids", null)
            ?.split(",")
            ?.mapNotNull { it.toIntOrNull() }
            .orEmpty()
        if (ordered.isNotEmpty()) {
            ordered.forEach { ids += it }
            return
        }
        prefs.getStringSet("ids", emptySet()).orEmpty()
            .mapNotNull { it.toIntOrNull() }
            .forEach { ids += it }
    }

    private fun saveIds() {
        prefs.edit()
            .putString("ordered_ids", ids.joinToString(","))
            .putStringSet("ids", ids.map(Int::toString).toSet())
            .apply()
    }
}
