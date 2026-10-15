package com.oea.launcher.workspace

import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.oea.launcher.R
import com.oea.launcher.applock.OeaAppLockStore
import com.oea.launcher.model.OeaAppInfo
import com.oea.launcher.ui.OeaKit
import com.oea.launcher.ui.OeaTheme
import com.oea.launcher.ui.OeaType

/**
 * Hidden apps as their own space.
 *
 * Hidden apps stay out of Home, the drawer, suggestions and search, but this
 * space lists them as a normal, working app grid: tap to open (App Lock and
 * Freezer still apply), long-press for Unhide / App info / Lock. "Manage"
 * chooses which apps are hidden. The space can require the App Lock PIN.
 *
 * It stays open while you use one of its apps, so Back returns here; pressing
 * Home leaves it.
 */
internal class OeaHiddenAppsSpace(private val w: OeaWorkspace) {
    private val context = w.context
    private var panel: FrameLayout? = null
    private var grid: LinearLayout? = null
    private var subtitle: android.widget.TextView? = null
    private var query = ""
    /** PIN entered once per Home session is enough to come back after opening an app. */
    private var unlocked = false

    val isOpen: Boolean get() = panel != null

    private val palette get() = w.palette
    private val kit get() = w.kit
    private fun dp(v: Int) = w.dp(v)

    fun hiddenApps(): List<OeaAppInfo> = w.apps.filter { w.store.isHidden(it.packageName, it.className) }

    fun open() {
        if (isOpen) return
        val needsPin = w.store.hiddenSpaceLocked() && OeaAppLockStore.hasPin(context) && !unlocked
        if (!needsPin) {
            show()
            return
        }
        val k = w.hostActivity?.let { OeaKit(it, palette) } ?: return
        k.prompt("Hidden apps", "Enter your App Lock PIN to open hidden apps.", "PIN", numeric = true, password = true, confirmLabel = "Open") { pin ->
            if (!OeaAppLockStore.verifyPin(context, pin)) return@prompt "Incorrect PIN"
            unlocked = true
            w.post { show() }
            null
        }
    }

    /** Leaving Home (screen off, Home pressed) locks the space again. */
    fun relock() {
        unlocked = false
    }

    /** Closes the space; leaving it (Back, Home, close button) requires the PIN again next time. */
    fun close(animate: Boolean = true, relock: Boolean = true): Boolean {
        val view = panel ?: return false
        if (relock) unlocked = false
        panel = null
        grid = null
        subtitle = null
        query = ""
        w.kit.hideKeyboard(view)
        if (animate) {
            view.animate().alpha(0f).translationY(dp(18).toFloat()).setDuration(160L)
                .withEndAction { w.overlay.removeView(view) }.start()
        } else {
            w.overlay.removeView(view)
        }
        return true
    }

    /** Re-reads the hidden list (after Manage, Unhide or an app install/removal). */
    fun refresh() {
        if (isOpen) render()
    }

    private fun show() {
        close(animate = false, relock = false)
        val root = FrameLayout(context).apply {
            setBackgroundColor(palette.background)
            isClickable = true
            isFocusable = true
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), w.insetTop() + dp(8), dp(16), 0)
        }
        root.addView(column, FrameLayout.LayoutParams(-1, -1))

        val top = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(kit.circleIconButton(R.drawable.oic_arrow_back, "Close hidden apps") { close() },
            LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(10) })
        val titles = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(kit.text("Hidden apps", 24f, palette.text, OeaType.medium))
        val sub = kit.text("", 12.5f, palette.textSecondary)
        titles.addView(sub)
        top.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        val locked = w.store.hiddenSpaceLocked()
        top.addView(kit.circleIconButton(if (locked) R.drawable.oic_lock else R.drawable.oic_lock_open,
            if (locked) "Hidden apps need your PIN. Tap to change." else "Hidden apps open without a PIN. Tap to change.") { toggleLock() },
            LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(6) })
        top.addView(kit.tonalButton("Manage", R.drawable.oic_edit) { manage() }, LinearLayout.LayoutParams(-2, dp(44)))
        column.addView(top, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        val search = kit.field("Search hidden apps", query)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                query = s?.toString()?.trim().orEmpty()
                render()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        column.addView(search, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(0, dp(6), 0, w.insetBottom() + dp(24))
        }
        val gridView = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(gridView, FrameLayout.LayoutParams(-1, -2))
        column.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        w.overlay.addView(root, FrameLayout.LayoutParams(-1, -1))
        panel = root
        grid = gridView
        subtitle = sub
        render()
        root.alpha = 0f
        root.translationY = dp(24).toFloat()
        root.animate().alpha(1f).translationY(0f).setDuration(220L).setInterpolator(DecelerateInterpolator(1.6f)).start()
    }

    private fun render() {
        val gridView = grid ?: return
        val all = hiddenApps()
        subtitle?.text = when (all.size) {
            0 -> "Nothing hidden"
            1 -> "1 app • hidden from Home, the drawer and search"
            else -> "${all.size} apps • hidden from Home, the drawer and search"
        }
        val visible = if (query.isBlank()) all else all.filter {
            it.label.contains(query, true) || it.packageName.contains(query, true)
        }
        gridView.removeAllViews()
        if (visible.isEmpty()) {
            gridView.addView(emptyState(all.isEmpty()), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(48) })
            return
        }
        val cols = w.drawerColumns()
        visible.chunked(cols).forEach { chunk ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEach { app ->
                val tile = OeaTileView(context)
                w.bindTile(tile, app, OeaWorkspace.TileStyle.DRAWER)
                tile.listener = tileListener
                row.addView(tile, LinearLayout.LayoutParams(0, w.drawerRowHeight(), 1f))
            }
            repeat(cols - chunk.size) { row.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f)) }
            gridView.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun emptyState(nothingHidden: Boolean): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(kit.iconBadge(R.drawable.oic_visibility_off, OeaTheme.alpha(palette.accent, 0x26), palette.accent, 64),
            LinearLayout.LayoutParams(dp(64), dp(64)).apply { bottomMargin = dp(14) })
        addView(kit.text(if (nothingHidden) "No hidden apps" else "No hidden apps match", 17f, palette.text, OeaType.medium).apply {
            gravity = Gravity.CENTER
        })
        addView(kit.text(
            if (nothingHidden) "Hide apps to keep them off Home, the drawer and search. They stay installed and open from here."
            else "Try a different name.",
            13.5f, palette.textSecondary
).apply {
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(6), dp(24), 0)
        })
        if (nothingHidden) {
            addView(kit.primaryButton("Choose apps to hide", R.drawable.oic_add) { manage() },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(18) })
        }
    }

    private val tileListener = object : OeaTileView.Listener {
        override fun onTileClick(tile: OeaTileView) {
            val app = (tile.payload as? OeaWorkspace.TileRef)?.app ?: return
            // Same launch path as everywhere else: App Lock and Freezer still apply.
            w.launchApp(app, tile)
        }

        override fun onTileLongPress(tile: OeaTileView) {
            val app = (tile.payload as? OeaWorkspace.TileRef)?.app ?: return
            options(app)
        }

        // Hidden apps stay in their space; dragging them onto Home would unhide them.
        override fun onTileDragStart(tile: OeaTileView, rawX: Float, rawY: Float) = Unit
        override fun onTileDragMove(tile: OeaTileView, rawX: Float, rawY: Float) = Unit
        override fun onTileDragEnd(tile: OeaTileView, rawX: Float, rawY: Float, cancelled: Boolean) = Unit
    }

    private fun options(app: OeaAppInfo) {
        val k = w.hostActivity?.let { OeaKit(it, palette) } ?: return
        val key = w.appKey(app)
        val appLocked = OeaAppLockStore.isLocked(context, key)
        k.sheet(app.label, "Hidden app") { panel, dialog ->
            val group = k.group(panel)
            k.row(group, R.drawable.oic_open_in_new, "Open", null) {
                dialog.dismiss()
                w.launchApp(app, null)
            }
            k.row(group, R.drawable.oic_visibility_off, "Unhide", "Show it in the drawer and search again") {
                dialog.dismiss()
                w.store.setHidden(app.packageName, app.className, false)
                w.drawer.refresh()
                render()
                w.toast("${app.label} is visible again")
            }
            if (OeaAppLockStore.hasPin(context)) {
                k.row(group, if (appLocked) R.drawable.oic_lock_open else R.drawable.oic_lock,
                    if (appLocked) "Remove App Lock" else "Lock with PIN", null) {
                    dialog.dismiss()
                    OeaAppLockStore.setLocked(context, key, !appLocked)
                    render()
                }
            }
            k.row(group, R.drawable.oic_info, "App info", null) {
                dialog.dismiss()
                w.openAppInfo(app.packageName)
            }
        }
    }

    private fun manage() {
        val k = w.hostActivity?.let { OeaKit(it, palette) } ?: return
        val all = w.apps.filter { it.packageName != context.packageName || !it.className.endsWith("OeaSettingsAlias") }
        val hidden = all.filter { w.store.isHidden(it.packageName, it.className) }.map { w.appKey(it) }.toSet()
        k.pickApps("Hidden apps", "Chosen apps disappear from Home, the drawer and search, and open from Hidden apps.",
            all, hidden, { w.appKey(it) }, confirmLabel = "Save") { chosen ->
            all.forEach { app -> w.store.setHidden(app.packageName, app.className, w.appKey(app) in chosen) }
            w.drawer.refresh()
            render()
        }
    }

    private fun toggleLock() {
        val locked = w.store.hiddenSpaceLocked()
        if (!locked && !OeaAppLockStore.hasPin(context)) {
            w.toast("Create an App Lock PIN first: OEA Settings › Privacy & App Lock")
            return
        }
        w.store.setHiddenSpaceLocked(!locked)
        unlocked = !locked
        w.toast(if (!locked) "Hidden apps now need your PIN" else "Hidden apps open without a PIN")
        // Rebuild the header so the lock icon reflects the new state.
        val q = query
        close(animate = false, relock = false)
        query = q
        show()
    }
}
