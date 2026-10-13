package com.oea.launcher.widgets

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import kotlin.math.ceil

/**
 * Hosts Android app widgets for OEA Home.
 *
 * Widgets sit on a page as full-width bands spanning whole grid rows. The
 * controller owns ids, page/row-span persistence and the complete add flow:
 * bind permission, the provider's configuration screen and pinned widgets.
 */
class OeaWidgetController(private val context: Context) {
    companion object {
        const val REQUEST_PICK_WIDGET = 7401
        const val REQUEST_CONFIGURE_WIDGET = 7402
        const val REQUEST_BIND_WIDGET = 7403
        const val HOST_ID = 0x4F4541
    }

    private val prefs = context.getSharedPreferences("oea_widgets", Context.MODE_PRIVATE)
    private val manager = AppWidgetManager.getInstance(context)
    private val host = AppWidgetHost(context, HOST_ID)
    private val ids = LinkedHashSet<Int>()
    private var hostActivity: Activity? = null
    private var listening = false
    private var pendingPage = 0
    private var pendingSpan = 2
    private var pendingWidgetId = -1

    /** Called after a widget was added or removed so Home can re-render. */
    var onWidgetsChanged: (() -> Unit)? = null

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

    /** Providers that can live on a home screen, grouped later by app. */
    fun installedProviders(): List<AppWidgetProviderInfo> = runCatching {
        manager.installedProviders.filter {
            (it.widgetCategory and AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN) != 0
        }
    }.getOrDefault(emptyList())

    /** Rows a provider needs on a grid whose cells are [cellHeightDp] tall. */
    fun defaultSpan(info: AppWidgetProviderInfo, cellHeightDp: Int, maxRows: Int): Int {
        val density = context.resources.displayMetrics.density
        val minHeightDp = (maxOf(info.minHeight, info.minResizeHeight.takeIf { it > 0 } ?: info.minHeight) / density)
        val rows = ceil((minHeightDp + 8f) / cellHeightDp.coerceAtLeast(40)).toInt()
        return rows.coerceIn(1, maxRows.coerceAtLeast(1))
    }

    /** OEA widget picker entry point: binds, configures and places the widget. */
    fun addWidget(activity: Activity, provider: AppWidgetProviderInfo, page: Int, spanRows: Int) {
        hostActivity = activity
        pendingPage = page.coerceAtLeast(0)
        pendingSpan = spanRows.coerceAtLeast(1)
        val id = host.allocateAppWidgetId()
        pendingWidgetId = id
        val bound = runCatching {
            manager.bindAppWidgetIdIfAllowed(id, provider.profile, provider.provider, null)
        }.getOrDefault(false)
        if (bound) {
            configureOrCommit(activity, id)
            return
        }
        val bind = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider.provider)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, provider.profile)
        runCatching { activity.startActivityForResult(bind, REQUEST_BIND_WIDGET) }
            .onFailure { discardPending() }
    }

    /** Legacy system picker, kept as a fallback when OEA's picker finds nothing. */
    fun pickWidget(activity: Activity, page: Int = 0) {
        hostActivity = activity
        pendingPage = page.coerceAtLeast(0)
        pendingSpan = 2
        val id = host.allocateAppWidgetId()
        pendingWidgetId = id
        runCatching {
            activity.startActivityForResult(
                Intent(AppWidgetManager.ACTION_APPWIDGET_PICK).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
                REQUEST_PICK_WIDGET,
            )
        }.onFailure { discardPending() }
    }

    private fun needsConfiguration(info: AppWidgetProviderInfo): Boolean {
        if (info.configure == null) return false
        if (Build.VERSION.SDK_INT >= 28) {
            val optional = (info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL) != 0
            val reconfigurable = (info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE) != 0
            if (optional && reconfigurable) return false
        }
        return true
    }

    private fun configureOrCommit(activity: Activity, id: Int) {
        val info = manager.getAppWidgetInfo(id)
        if (info == null) {
            discardPending()
            return
        }
        if (needsConfiguration(info)) {
            val started = runCatching {
                host.startAppWidgetConfigureActivityForResult(activity, id, 0, REQUEST_CONFIGURE_WIDGET, null)
                true
            }.getOrDefault(false)
            if (!started) commit(id, pendingPage, pendingSpan)
            return
        }
        commit(id, pendingPage, pendingSpan)
    }

    fun canReconfigure(id: Int): Boolean {
        val info = manager.getAppWidgetInfo(id) ?: return false
        if (info.configure == null) return false
        return Build.VERSION.SDK_INT < 28 ||
            (info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE) != 0
    }

    fun reconfigure(activity: Activity, id: Int) {
        runCatching { host.startAppWidgetConfigureActivityForResult(activity, id, 0, REQUEST_CONFIGURE_WIDGET + 100, null) }
    }

    private fun commit(id: Int, page: Int, span: Int) {
        ids += id
        prefs.edit().putInt("page_$id", page).putInt("span_$id", span).apply()
        saveIds()
        if (pendingWidgetId == id) pendingWidgetId = -1
        onWidgetsChanged?.invoke()
    }

    private fun discardPending() {
        if (pendingWidgetId > 0) runCatching { host.deleteAppWidgetId(pendingWidgetId) }
        pendingWidgetId = -1
    }

    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode == REQUEST_CONFIGURE_WIDGET + 100) {
            onWidgetsChanged?.invoke()
            return true
        }
        if (requestCode != REQUEST_PICK_WIDGET && requestCode != REQUEST_CONFIGURE_WIDGET && requestCode != REQUEST_BIND_WIDGET) return false
        val returnedId = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1) ?: -1
        val id = if (returnedId > 0) returnedId else pendingWidgetId
        if (id <= 0 || resultCode != Activity.RESULT_OK) {
            if (id > 0) runCatching { host.deleteAppWidgetId(id) }
            pendingWidgetId = -1
            return true
        }
        pendingWidgetId = id
        when (requestCode) {
            REQUEST_BIND_WIDGET, REQUEST_PICK_WIDGET -> {
                val activity = hostActivity
                if (activity == null) commit(id, pendingPage, pendingSpan) else configureOrCommit(activity, id)
            }
            REQUEST_CONFIGURE_WIDGET -> commit(id, pendingPage, pendingSpan)
        }
        return true
    }

    // ----------------------------------------------------- pinned widgets

    fun allocateId(): Int = host.allocateAppWidgetId()

    fun bindIfAllowed(id: Int, info: AppWidgetProviderInfo, options: Bundle?): Boolean = runCatching {
        manager.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, options)
    }.getOrDefault(false)

    fun bindIntent(id: Int, info: AppWidgetProviderInfo): Intent =
        Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile)

    fun commitPinned(id: Int, page: Int, span: Int) = commit(id, page, span)

    fun deleteId(id: Int) { runCatching { host.deleteAppWidgetId(id) } }

    // ------------------------------------------------------------ queries

    fun pageFor(id: Int): Int = prefs.getInt("page_$id", 0).coerceAtLeast(0)

    fun setPage(id: Int, page: Int) {
        if (ids.contains(id)) prefs.edit().putInt("page_$id", page.coerceAtLeast(0)).apply()
    }

    /** Grid rows the widget spans; older installs stored a height in dp instead. */
    fun spanFor(id: Int): Int {
        val span = prefs.getInt("span_$id", 0)
        if (span > 0) return span
        val legacyHeight = prefs.getString("size_$id", null)?.split(",")?.getOrNull(1)?.toIntOrNull() ?: 160
        return when {
            legacyHeight <= 100 -> 1
            legacyHeight <= 180 -> 2
            else -> 3
        }
    }

    fun setSpan(id: Int, rows: Int) {
        if (ids.contains(id)) prefs.edit().putInt("span_$id", rows.coerceAtLeast(1)).apply()
    }

    fun ids(): List<Int> = ids.toList()

    fun info(id: Int): AppWidgetProviderInfo? = runCatching { manager.getAppWidgetInfo(id) }.getOrNull()

    fun createView(viewContext: Context, id: Int): AppWidgetHostView? {
        val info = info(id) ?: return null
        return runCatching { host.createView(viewContext, id, info) }.getOrNull()
    }

    fun views(): List<AppWidgetHostView> = ids.mapNotNull { createView(context, it) }

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
        runCatching { host.deleteAppWidgetId(id) }
        prefs.edit().remove("size_$id").remove("page_$id").remove("span_$id").apply()
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

    fun providers(): List<AppWidgetProviderInfo> = ids.mapNotNull { info(it) }

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
