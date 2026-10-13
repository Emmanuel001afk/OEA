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
import android.util.SizeF
import kotlin.math.ceil

/**
 * Hosts Android app widgets for OEA Home.
 *
 * Each widget owns a rectangle of Home cells on one page (x, y, spanX, spanY),
 * exactly like icons own a cell, so it can be dragged between pages and resized
 * in both directions. The controller owns ids, placement persistence, the
 * provider's resize limits and the complete add flow: bind permission, the
 * provider's configuration screen and pinned widgets.
 *
 * Installs from before free placement stored only a page and a row span (the
 * widget was a full-width band). Those entries have no `spanx_` key and are
 * migrated by the workspace, which preserves their previous on-screen layout.
 */
class OeaWidgetController(private val context: Context) {
    companion object {
        const val REQUEST_PICK_WIDGET = 7401
        const val REQUEST_CONFIGURE_WIDGET = 7402
        const val REQUEST_BIND_WIDGET = 7403
        const val HOST_ID = 0x4F4541

        /** Rounding slack when converting a provider's dp size into whole cells. */
        private const val SPAN_TOLERANCE_DP = 4f
    }

    private val prefs = context.getSharedPreferences("oea_widgets", Context.MODE_PRIVATE)
    private val manager = AppWidgetManager.getInstance(context)
    private val host = AppWidgetHost(context, HOST_ID)
    private val ids = LinkedHashSet<Int>()
    private var hostActivity: Activity? = null
    private var listening = false
    private var pendingPage = 0
    private var pendingSpan = 2
    private var pendingSpanX = 0
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

    /**
     * How far a widget may be resized, in Home cells.
     *
     * Mirrors what Android launchers enforce: never smaller than the provider's
     * minResize size (or its min size when minResize is unset), never larger
     * than maxResize on Android 12+, and only along the directions its
     * resizeMode allows. Keeping widgets inside these limits is what stops
     * their content from being squeezed into overlapping views.
     */
    data class SpanLimits(
        val minX: Int,
        val minY: Int,
        val maxX: Int,
        val maxY: Int,
        val horizontal: Boolean,
        val vertical: Boolean,
    )

    fun spanLimits(info: AppWidgetProviderInfo, cellWidthDp: Float, cellHeightDp: Float, cols: Int, rows: Int): SpanLimits {
        val minWidthPx = info.minResizeWidth.takeIf { it in 1..info.minWidth } ?: info.minWidth
        val minHeightPx = info.minResizeHeight.takeIf { it in 1..info.minHeight } ?: info.minHeight
        val minX = cellsFor(minWidthPx, cellWidthDp).coerceIn(1, cols.coerceAtLeast(1))
        val minY = cellsFor(minHeightPx, cellHeightDp).coerceIn(1, rows.coerceAtLeast(1))
        var maxX = cols.coerceAtLeast(1)
        var maxY = rows.coerceAtLeast(1)
        if (Build.VERSION.SDK_INT >= 31) {
            if (info.maxResizeWidth > 0) maxX = cellsFor(info.maxResizeWidth, cellWidthDp).coerceIn(minX, maxX)
            if (info.maxResizeHeight > 0) maxY = cellsFor(info.maxResizeHeight, cellHeightDp).coerceIn(minY, maxY)
        }
        return SpanLimits(
            minX = minX,
            minY = minY,
            maxX = maxX.coerceAtLeast(minX),
            maxY = maxY.coerceAtLeast(minY),
            horizontal = (info.resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL) != 0,
            vertical = (info.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL) != 0,
        )
    }

    /** The size a newly added widget gets: the provider's target cells on Android 12+, else its min size. */
    fun defaultSpans(info: AppWidgetProviderInfo, cellWidthDp: Float, cellHeightDp: Float, cols: Int, rows: Int): Pair<Int, Int> {
        val limits = spanLimits(info, cellWidthDp, cellHeightDp, cols, rows)
        var spanX = cellsFor(info.minWidth, cellWidthDp)
        var spanY = cellsFor(info.minHeight, cellHeightDp)
        if (Build.VERSION.SDK_INT >= 31) {
            if (info.targetCellWidth > 0) spanX = info.targetCellWidth
            if (info.targetCellHeight > 0) spanY = info.targetCellHeight
        }
        return spanX.coerceIn(limits.minX, limits.maxX).coerceAtMost(cols.coerceAtLeast(1)) to
            spanY.coerceIn(limits.minY, limits.maxY).coerceAtMost(rows.coerceAtLeast(1))
    }

    /** Cells needed for a provider dimension given in pixels (AppWidgetProviderInfo sizes are px). */
    private fun cellsFor(sizePx: Int, cellDp: Float): Int {
        if (sizePx <= 0) return 1
        val sizeDp = sizePx / context.resources.displayMetrics.density
        return ceil((sizeDp - SPAN_TOLERANCE_DP) / cellDp.coerceAtLeast(24f)).toInt().coerceAtLeast(1)
    }

    /**
     * Tells the provider the exact size its widget now has, so it can pick the
     * layout that fits (Android 12+ responsive layouts use the SizeF list).
     */
    fun reportSize(view: AppWidgetHostView, widthDp: Int, heightDp: Int) {
        if (widthDp <= 0 || heightDp <= 0) return
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                view.updateAppWidgetSize(Bundle(), listOf(SizeF(widthDp.toFloat(), heightDp.toFloat())))
            } else {
                @Suppress("DEPRECATION")
                view.updateAppWidgetSize(Bundle(), widthDp, heightDp, widthDp, heightDp)
            }
        }
    }

    /** OEA widget picker entry point: binds, configures and places the widget. */
    fun addWidget(activity: Activity, provider: AppWidgetProviderInfo, page: Int, spanX: Int, spanY: Int) {
        hostActivity = activity
        pendingPage = page.coerceAtLeast(0)
        pendingSpan = spanY.coerceAtLeast(1)
        pendingSpanX = spanX.coerceAtLeast(1)
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
        // Unknown provider until the system picker returns; the workspace sizes it from its info.
        pendingSpanX = 0
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
            if (!started) commit(id, pendingPage, pendingSpan, pendingSpanX)
            return
        }
        commit(id, pendingPage, pendingSpan, pendingSpanX)
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

    /**
     * Records a newly bound widget. It has a page and a requested size but no
     * cell yet; the workspace places it in the first free area (spanX 0 means
     * "size it from the provider's own defaults").
     */
    private fun commit(id: Int, page: Int, span: Int, spanX: Int) {
        ids += id
        prefs.edit()
            .putInt("page_$id", page)
            .putInt("span_$id", span.coerceAtLeast(1))
            .putInt("spanx_$id", spanX.coerceAtLeast(0))
            .remove("x_$id")
            .remove("y_$id")
            .apply()
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
                if (activity == null) commit(id, pendingPage, pendingSpan, pendingSpanX) else configureOrCommit(activity, id)
            }
            REQUEST_CONFIGURE_WIDGET -> commit(id, pendingPage, pendingSpan, pendingSpanX)
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

    fun commitPinned(id: Int, page: Int, spanY: Int, spanX: Int) = commit(id, page, spanY, spanX)

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

    /** Where a widget sits on Home, in cells. */
    data class Placement(val page: Int, val x: Int, val y: Int, val spanX: Int, val spanY: Int)

    /** The stored cell rectangle, or null when the widget has not been placed yet (new or legacy). */
    fun placement(id: Int): Placement? {
        if (!prefs.contains("x_$id") || !prefs.contains("y_$id")) return null
        val spanX = prefs.getInt("spanx_$id", 0)
        if (spanX <= 0) return null
        return Placement(pageFor(id), prefs.getInt("x_$id", 0).coerceAtLeast(0), prefs.getInt("y_$id", 0).coerceAtLeast(0), spanX, spanFor(id))
    }

    fun setPlacement(id: Int, page: Int, x: Int, y: Int, spanX: Int, spanY: Int) {
        if (!ids.contains(id)) return
        prefs.edit()
            .putInt("page_$id", page.coerceAtLeast(0))
            .putInt("x_$id", x.coerceAtLeast(0))
            .putInt("y_$id", y.coerceAtLeast(0))
            .putInt("spanx_$id", spanX.coerceAtLeast(1))
            .putInt("span_$id", spanY.coerceAtLeast(1))
            .apply()
    }

    /** Installs from before free placement stored full-width bands with only a page and a row span. */
    fun isLegacyBand(id: Int): Boolean = !prefs.contains("spanx_$id")

    /** Width requested when the widget was added; 0 asks the workspace to use the provider's default. */
    fun requestedSpanX(id: Int): Int = prefs.getInt("spanx_$id", 0)

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
        prefs.edit().remove("size_$id").remove("page_$id").remove("span_$id")
            .remove("spanx_$id").remove("x_$id").remove("y_$id").apply()
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
