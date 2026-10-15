package com.oea.launcher.workspace

import android.app.AppOpsManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Process
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.oea.launcher.OeaLauncherActivity
import com.oea.launcher.R
import com.oea.launcher.applock.OeaAppFreezer
import com.oea.launcher.applock.OeaAppLockStore
import com.oea.launcher.assistant.OeaAssistant
import com.oea.launcher.data.OeaDataStore
import com.oea.launcher.model.OeaAppInfo
import com.oea.launcher.multitask.OeaMultitaskLauncher
import com.oea.launcher.ui.OeaKit
import com.oea.launcher.ui.OeaTheme
import com.oea.launcher.ui.OeaType
import kotlin.math.ceil
import kotlin.math.min

/**
 * Everything that floats above OEA Home: icon popups with app shortcuts and
 * actions, the folder panel, the Home menu, widget picker and options, and the
 * App Lock / App Freezer / OEA Assistant prompts.
 */
internal class OeaWorkspaceMenus(private val w: OeaWorkspace) {
    private val context: Context = w.context
    private var popup: View? = null
    private var popupScrim: View? = null
    private var folderPanel: LinearLayout? = null
    private var folderScrim: View? = null
    private var openFolderId: String? = null
    private var insetTop = 0
    private var insetBottom = 0

    private val palette get() = w.palette
    private val kit get() = w.kit
    private fun dp(v: Int) = w.dp(v)

    fun onInsetsChanged(top: Int, bottom: Int) {
        insetTop = top
        insetBottom = bottom
    }

    fun hasOverlay() = popup != null || folderPanel != null

    /** OeaKit bound to the Home activity (sheets need an Activity window). */
    private fun activityKit(): OeaKit? = w.hostActivity?.let { OeaKit(it, palette) }

    fun dismissPopups() {
        popup?.let { card ->
            popup = null
            card.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f).setDuration(120L).withEndAction { w.overlay.removeView(card) }.start()
        }
        popupScrim?.let { w.overlay.removeView(it) }
        popupScrim = null
    }

    fun closeAll(): Boolean {
        val had = hasOverlay()
        dismissPopups()
        closeFolder(animate = true)
        return had
    }

    // ================================================================ popups

    private fun showPopup(anchor: Rect, widthDp: Int, build: (LinearLayout) -> Unit) {
        dismissPopups()
        val overlay = w.overlay
        val scrim = View(context).apply {
            isClickable = true
            setOnClickListener { dismissPopups() }
        }
        overlay.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = kit.rounded(palette.background, 26, palette.stroke, 1)
            elevation = dp(14).toFloat()
            setPadding(dp(6), dp(8), dp(6), dp(8))
            clipToOutline = true
            isClickable = true
        }
        build(card)
        val width = min(dp(widthDp), overlay.width - dp(24)).coerceAtLeast(dp(160))
        card.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(overlay.height, View.MeasureSpec.AT_MOST)
)
        val loc = IntArray(2)
        overlay.getLocationOnScreen(loc)
        val cardH = card.measuredHeight
        val top = anchor.top - loc[1]
        val bottom = anchor.bottom - loc[1]
        val above = top - cardH - dp(8) >= insetTop + dp(8)
        val y = if (above) top - cardH - dp(8) else (bottom + dp(8)).coerceAtMost(overlay.height - insetBottom - cardH - dp(8)).coerceAtLeast(insetTop + dp(8))
        val x = (anchor.centerX() - loc[0] - width / 2).coerceIn(dp(12), (overlay.width - width - dp(12)).coerceAtLeast(dp(12)))
        overlay.addView(card, FrameLayout.LayoutParams(width, -2).apply {
            leftMargin = x
            topMargin = y
        })
        card.pivotX = (anchor.centerX() - loc[0] - x).toFloat()
        card.pivotY = if (above) cardH.toFloat() else 0f
        card.alpha = 0f
        card.scaleX = 0.86f
        card.scaleY = 0.86f
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(190L).setInterpolator(DecelerateInterpolator(1.8f)).start()
        popup = card
        popupScrim = scrim
    }

    private fun bounds(view: View): Rect {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val r = Rect(loc[0], loc[1], loc[0] + view.width, loc[1] + view.height)
        if (view is OeaTileView) {
            val icon = Rect()
            view.iconBounds(icon)
            if (!icon.isEmpty) r.set(loc[0] + icon.left, loc[1] + icon.top, loc[0] + icon.right, loc[1] + icon.bottom)
        }
        return r
    }

    private fun popupHeader(card: LinearLayout, title: String, subtitle: String?, onInfo: (() -> Unit)?) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(4), dp(6))
        }
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(kit.text(title, 16f, palette.text, OeaType.medium).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        if (!subtitle.isNullOrBlank()) texts.addView(kit.text(subtitle, 12f, palette.textSecondary).apply { maxLines = 1 })
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        if (onInfo != null) {
            row.addView(kit.circleIconButton(R.drawable.oic_info, "App info", palette.cardHigh, palette.text) {
                dismissPopups()
                onInfo()
            }, LinearLayout.LayoutParams(dp(36), dp(36)))
        }
        card.addView(row)
    }

    private fun popupRow(card: LinearLayout, icon: Drawable?, label: String, trailing: View? = null, onClick: () -> Unit) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPadding(dp(12), dp(6), dp(8), dp(6))
            background = kit.ripple(null, 16)
            isClickable = true
            setOnClickListener {
                dismissPopups()
                onClick()
            }
        }
        row.addView(ImageView(context).apply { setImageDrawable(icon) }, LinearLayout.LayoutParams(dp(26), dp(26)).apply { rightMargin = dp(14) })
        row.addView(kit.text(label, 15f, palette.text).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
        if (trailing != null) row.addView(trailing, LinearLayout.LayoutParams(dp(36), dp(36)))
        card.addView(row, LinearLayout.LayoutParams(-1, -2))
    }

    private data class Action(val icon: Int, val label: String, val run: () -> Unit)

    private fun actionGrid(card: LinearLayout, actions: List<Action>) {
        if (actions.isEmpty()) return
        card.addView(View(context).apply { setBackgroundColor(palette.stroke) }, LinearLayout.LayoutParams(-1, maxOf(1, dp(1) / 2)).apply {
            topMargin = dp(4)
            bottomMargin = dp(6)
            leftMargin = dp(10)
            rightMargin = dp(10)
        })
        actions.chunked(4).forEach { chunk ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEach { action ->
                val cell = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(dp(2), dp(6), dp(2), dp(6))
                    background = kit.ripple(null, 16)
                    isClickable = true
                    contentDescription = action.label
                    setOnClickListener {
                        dismissPopups()
                        action.run()
                    }
                }
                cell.addView(kit.iconBadge(action.icon, palette.cardHigh, palette.text, 40), LinearLayout.LayoutParams(dp(40), dp(40)))
                cell.addView(kit.text(action.label, 11.5f, palette.textSecondary).apply {
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(4), 0, 0)
                })
                row.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
            }
            repeat(4 - chunk.size) { row.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f)) }
            card.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
    }

    fun showTilePopup(tile: OeaTileView, ref: OeaWorkspace.TileRef) {
        when {
            ref.folder != null -> folderPopup(tile, ref)
            ref.item?.isShortcut == true -> shortcutItemPopup(tile, ref)
            ref.app != null -> appPopup(tile, ref, ref.app)
        }
    }

    private fun appPopup(tile: OeaTileView, ref: OeaWorkspace.TileRef, app: OeaAppInfo) {
        val key = w.appKey(app)
        val pkg = app.packageName
        val frozen = w.isFrozen(pkg)
        val locked = OeaAppLockStore.isLocked(context, key)
        val shortcutList = w.shortcuts.shortcuts(pkg, app.className)
        val status = when {
            frozen -> "Frozen"
            locked -> "Locked with OEA App Lock"
            w.badgeFor(pkg) > 0 -> "${w.badgeFor(pkg)} notification" + if (w.badgeFor(pkg) == 1) "" else "s"
            else -> null
        }
        showPopup(bounds(tile), 288) { card ->
            popupHeader(card, app.label, status) { w.openAppInfo(pkg) }
            shortcutList.forEach { info ->
                val pin = ImageView(context).apply {
                    setImageDrawable(kit.icon(R.drawable.oic_push_pin, palette.textSecondary))
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    background = kit.ripple(null, 18)
                    contentDescription = "Add shortcut to Home"
                    setOnClickListener {
                        dismissPopups()
                        w.addShortcutToHome(info)
                    }
                }
                popupRow(card, w.shortcuts.icon(info) ?: kit.icon(R.drawable.oic_open_in_new, palette.text), w.shortcuts.label(info), pin) {
                    if (!w.shortcuts.start(pkg, info.id, bounds(tile), w.launchOptions(tile))) w.toast("This shortcut could not be opened")
                }
            }
            if (shortcutList.isEmpty() && !w.shortcuts.hasPermission() && !w.isDefaultHome()) {
                popupRow(card, kit.icon(R.drawable.oic_home, palette.accent), "Set as default Home for app shortcuts") { w.requestDefaultHome() }
            }
            val actions = mutableListOf<Action>()
            if (ref.source == OeaWorkspace.Source.DRAWER) {
                // Built-in OEA apps (Game Boost, Phone, Call Blocker, Freezer, OEA
                // Settings) start inside the seeded "OEA" folder. Being in a folder
                // or the dock must not stop an app from also getting its own icon.
                if (!w.isHomeIcon(key)) actions += Action(R.drawable.oic_home, "Add to Home") { w.addAppToHome(app) }
            } else {
                actions += Action(R.drawable.oic_close, if (ref.memberOf != null) "Take out" else "Remove") { w.removeHomeItem(ref) }
            }
            actions += Action(R.drawable.oic_splitscreen, "Split") { splitWith(app) }
            actions += Action(R.drawable.oic_select_window, "Float") { floating(app) }
            actions += Action(R.drawable.oic_ac_unit, if (frozen) "Unfreeze" else "Freeze") { freezeToggle(app) }
            actions += Action(if (locked) R.drawable.oic_lock_open else R.drawable.oic_lock, if (locked) "Unlock" else "Lock") { lockToggle(app) }
            if (ref.source == OeaWorkspace.Source.DRAWER && pkg != context.packageName) {
                actions += Action(R.drawable.oic_visibility_off, "Hide") {
                    w.store.setHidden(pkg, app.className, true)
                    w.drawer.refresh()
                    w.hiddenSpace.refresh()
                    w.toast("${app.label} moved to Hidden apps (drawer ⋮ › Hidden apps)")
                }
            }
            val focused = w.focusStore.isFocused(key)
            actions += Action(if (focused) R.drawable.oic_star_fill else R.drawable.oic_star, if (focused) "Unpin" else "Pin top") {
                val now = w.focusStore.toggle(key)
                w.toast(if (now) "${app.label} pinned to the top of the drawer" else "${app.label} unpinned")
                w.drawer.refresh()
            }
            if (!w.isSystemApp(pkg) && pkg != context.packageName) actions += Action(R.drawable.oic_delete, "Uninstall") { w.uninstall(pkg) }
            actionGrid(card, actions)
        }
    }

    private fun folderPopup(tile: OeaTileView, ref: OeaWorkspace.TileRef) {
        val folder = ref.folder ?: return
        showPopup(bounds(tile), 280) { card ->
            popupHeader(card, tile.label.toString(), if (folder.isPair) "App pair" else "${folder.members.size} apps", null)
            val actions = mutableListOf<Action>()
            if (folder.isPair) {
                actions += Action(R.drawable.oic_splitscreen, "Open") { w.launchPair(folder) }
                actions += Action(R.drawable.oic_restart_alt, "Swap") { w.updateFolder(folder.copy(members = folder.members.reversed())) }
            } else {
                actions += Action(R.drawable.oic_folder, "Open") { openFolder(folder, tile) }
                actions += Action(R.drawable.oic_add, "Add apps") { editFolderApps(folder) }
            }
            actions += Action(R.drawable.oic_edit, "Rename") { renameFolder(folder) }
            actions += Action(R.drawable.oic_close, "Remove") { w.removeHomeItem(ref) }
            actionGrid(card, actions)
        }
    }

    private fun shortcutItemPopup(tile: OeaTileView, ref: OeaWorkspace.TileRef) {
        val item = ref.item ?: return
        showPopup(bounds(tile), 260) { card ->
            popupHeader(card, tile.label.toString(), "Shortcut • ${ref.app?.label ?: item.packageName}", null)
            actionGrid(card, listOf(
                Action(R.drawable.oic_open_in_new, "Open") { w.launchShortcut(item, tile) },
                Action(R.drawable.oic_close, "Remove") { w.removeHomeItem(ref) },
                Action(R.drawable.oic_info, "App info") { w.openAppInfo(item.packageName) }
))
        }
    }

    // ========================================================= embedded panels

    /**
     * A panel built from the same pieces as a popup, but handed to the caller so it
     * can be assembled — and dismissed — by whatever owns it.
     *
     * This is what lets Home show things like Quick controls *inside* Home instead
     * of launching a screen: the launcher stays in front, the user keeps their
     * place, and nothing about opening a control can look like "OEA closed".
     */
    fun panel(widthDp: Int, build: (LinearLayout) -> Unit): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = kit.rounded(palette.background, 26, palette.stroke, 1)
            elevation = dp(14).toFloat()
            setPadding(dp(6), dp(8), dp(6), dp(8))
            clipToOutline = true
            isClickable = true
        }
        build(card)
        val overlay = w.overlay
        card.measure(
            View.MeasureSpec.makeMeasureSpec(min(dp(widthDp), overlay.width - dp(24)).coerceAtLeast(dp(160)), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(overlay.height, View.MeasureSpec.AT_MOST)
)
        return card
    }

    /** Places a panel from [panel] in the overlay, anchored like a popup. */
    fun showPanel(anchor: Rect, card: LinearLayout, scrimDismiss: () -> Unit) {
        dismissPopups()
        val overlay = w.overlay
        val scrim = View(context).apply {
            isClickable = true
            setOnClickListener { scrimDismiss() }
        }
        overlay.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        val loc = IntArray(2)
        overlay.getLocationOnScreen(loc)
        val cardH = card.measuredHeight
        val cardW = card.measuredWidth
        val top = anchor.top - loc[1]
        val below = anchor.bottom - loc[1] + dp(8)
        val fitsBelow = below + cardH <= overlay.height - insetBottom - dp(8)
        val y = if (fitsBelow) below else (top - cardH - dp(8)).coerceAtLeast(insetTop + dp(8))
        val x = (anchor.centerX() - loc[0] - cardW / 2).coerceIn(dp(12), (overlay.width - cardW - dp(12)).coerceAtLeast(dp(12)))
        overlay.addView(card, FrameLayout.LayoutParams(cardW, -2).apply {
            leftMargin = x
            topMargin = y
        })
        card.alpha = 0f
        card.scaleX = 0.9f
        card.scaleY = 0.9f
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(190L).setInterpolator(DecelerateInterpolator(1.8f)).start()
        popup = card
        popupScrim = scrim
    }

    // ============================================================ home menu

    fun showHomeMenu(rawX: Float, rawY: Float) {
        val anchor = Rect(rawX.toInt(), rawY.toInt(), rawX.toInt() + 1, rawY.toInt() + 1)
        val locked = w.store.homeLayoutLocked()
        showPopup(anchor, 250) { card ->
            popupRow(card, kit.icon(R.drawable.oic_palette, palette.text), "Themes & wallpapers") {
                // The OEA Themes app: themes, your own themes and the wallpaper library.
                val themes = Intent(context, com.oea.launcher.themes.OeaThemesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (!w.startSafely(themes) && !w.startSafely(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Wallpaper"))) {
                    w.toast("No wallpaper picker found")
                }
            }
            popupRow(card, kit.icon(R.drawable.oic_widgets, palette.text), "Widgets") { showWidgetPicker(w.currentPage()) }
            popupRow(card, kit.icon(R.drawable.oic_apps, palette.text), "Mini-apps") {
                if (!com.oea.launcher.miniapps.OeaMiniApps.open(context)) w.toast("Mini-apps could not be opened")
            }
            popupRow(card, kit.icon(R.drawable.oic_settings, palette.text), "Home settings") { w.openSystemTool("home") }
            popupRow(card, kit.icon(R.drawable.oic_layers, palette.text), "Recent apps") { openRecents() }
            popupRow(card, kit.icon(R.drawable.oic_view_carousel, palette.text), "Add page") { w.addPage() }
            if (w.ws.pages() > 1) popupRow(card, kit.icon(R.drawable.oic_delete, palette.text), "Remove this page") { w.removeCurrentPageIfEmpty() }
            popupRow(card, kit.icon(if (locked) R.drawable.oic_lock_open else R.drawable.oic_lock, palette.text),
                if (locked) "Unlock Home layout" else "Lock Home layout") {
                w.store.setHomeLayoutLocked(!locked)
                w.toast(if (locked) "Home layout unlocked" else "Home layout locked. Icons can't be moved by accident.")
            }
        }
    }

    private fun openRecents() {
        if (w.store.recentsMode() == "system") {
            if (OeaAssistant.openRecents()) return
            if (!OeaAssistant.isEnabled(context)) {
                showAssistantNeeded("Recent apps")
                return
            }
        }
        w.openSystemTool("multitask")
    }

    fun showDrawerMenu(anchor: View) {
        val store = w.store
        showPopup(bounds(anchor), 260) { card ->
            popupRow(card, kit.icon(R.drawable.oic_settings, palette.text), "OEA Settings") { w.openSystemTool(null) }
            val hiddenCount = w.hiddenSpace.hiddenApps().size
            popupRow(card, kit.icon(R.drawable.oic_visibility_off, palette.text), if (hiddenCount > 0) "Hidden apps ($hiddenCount)" else "Hidden apps") { w.openHiddenApps() }
            val list = store.drawerMode() == OeaDataStore.DrawerMode.VERTICAL
            popupRow(card, kit.icon(if (list) R.drawable.oic_grid_view else R.drawable.oic_view_list, palette.text),
                if (list) "Show as grid" else "Show as list") {
                store.setDrawerMode(if (list) OeaDataStore.DrawerMode.GRID else OeaDataStore.DrawerMode.VERTICAL)
            }
            val byName = store.sortMode() == OeaDataStore.SortMode.NAME
            popupRow(card, kit.icon(if (byName) R.drawable.oic_history else R.drawable.oic_sort_by_alpha, palette.text),
                if (byName) "Sort by recent use" else "Sort A–Z") {
                store.setSortMode(if (byName) OeaDataStore.SortMode.RECENT else OeaDataStore.SortMode.NAME)
            }
            popupRow(card, kit.icon(R.drawable.oic_auto_awesome, palette.text),
                if (store.showMostUsed()) "Hide suggestions" else "Show suggestions") { store.setShowMostUsed(!store.showMostUsed()) }
            popupRow(card, kit.icon(R.drawable.oic_interests, palette.text),
                if (store.drawerCategories()) "Hide categories" else "Show categories") { store.setDrawerCategories(!store.drawerCategories()) }
        }
    }

    // ================================================================ folders

    fun openFolder(folder: OeaWorkspaceStore.Folder, source: View?) {
        closeAll()
        val overlay = w.overlay
        openFolderId = folder.id
        val scrim = View(context).apply {
            setBackgroundColor(Color.argb(120, 0, 0, 0))
            alpha = 0f
            isClickable = true
            setOnClickListener { closeFolder(true) }
        }
        overlay.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        scrim.animate().alpha(1f).setDuration(200L).start()
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = kit.rounded(palette.background, 32)
            elevation = dp(16).toFloat()
            setPadding(dp(18), dp(18), dp(18), dp(16))
            isClickable = true
        }
        folderPanel = panel
        folderScrim = scrim
        renderFolder(folder)
        val width = min(overlay.width - dp(32), dp(380))
        overlay.addView(panel, FrameLayout.LayoutParams(width, -2, Gravity.CENTER))
        val sourceRect = source?.let { bounds(it) }
        panel.alpha = 0f
        panel.scaleX = 0.7f
        panel.scaleY = 0.7f
        if (sourceRect != null) {
            panel.post {
                val loc = IntArray(2)
                panel.getLocationOnScreen(loc)
                panel.pivotX = (sourceRect.centerX() - loc[0]).toFloat()
                panel.pivotY = (sourceRect.centerY() - loc[1]).toFloat()
            }
        }
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(240L).setInterpolator(DecelerateInterpolator(2f)).start()
    }

    private fun renderFolder(folder: OeaWorkspaceStore.Folder) {
        val panel = folderPanel ?: return
        panel.removeAllViews()
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(kit.text(folder.title.ifBlank { "Folder" }, 22f, palette.text, OeaType.regular).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(4), 0, 0, 0)
            setOnClickListener { renameFolder(folder) }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(kit.circleIconButton(R.drawable.oic_edit, "Rename folder", palette.cardHigh, palette.text) { renameFolder(folder) },
            LinearLayout.LayoutParams(dp(38), dp(38)))
        panel.addView(header, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        val members = folder.members.mapNotNull(w::findApp)
        val cols = 4
        val rows = ceil(members.size / cols.toFloat()).toInt().coerceAtLeast(1)
        val grid = OeaCellLayout(context).apply {
            this.cols = cols
            this.rows = rows
        }
        val listener = w.folderMemberListener(folder)
        members.forEachIndexed { i, app ->
            val tile = OeaTileView(context)
            w.bindTile(tile, app, OeaWorkspace.TileStyle.FOLDER)
            tile.payload = OeaWorkspace.TileRef(app = app, memberOf = folder.id, source = OeaWorkspace.Source.FOLDER_MEMBER)
            tile.listener = listener
            grid.addView(tile, OeaCellLayout.LayoutParams(i % cols, i / cols))
        }
        val maxRows = ((w.height * 0.55f) / dp(96)).toInt().coerceAtLeast(2)
        if (rows > maxRows) {
            val scroll = android.widget.ScrollView(context).apply {
                isVerticalScrollBarEnabled = false
                addView(grid, FrameLayout.LayoutParams(-1, rows * dp(96)))
            }
            panel.addView(scroll, LinearLayout.LayoutParams(-1, maxRows * dp(96)))
        } else {
            panel.addView(grid, LinearLayout.LayoutParams(-1, rows * dp(96)))
        }
        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        footer.addView(kit.tonalButton("Add apps", R.drawable.oic_add) { editFolderApps(folder) }, LinearLayout.LayoutParams(-2, dp(44)))
        panel.addView(footer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    }

    fun refreshOpenFolder() {
        val id = openFolderId ?: return
        val folder = w.ws.folders().firstOrNull { it.id == id }
        if (folder == null || folder.members.size < 2) {
            closeFolder(false)
            return
        }
        renderFolder(folder)
    }

    fun closeFolder(animate: Boolean) {
        val panel = folderPanel ?: return
        val scrim = folderScrim
        folderPanel = null
        folderScrim = null
        openFolderId = null
        if (!animate) {
            w.overlay.removeView(panel)
            scrim?.let { w.overlay.removeView(it) }
            return
        }
        panel.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f).setDuration(170L).withEndAction { w.overlay.removeView(panel) }.start()
        scrim?.animate()?.alpha(0f)?.setDuration(170L)?.withEndAction { w.overlay.removeView(scrim) }?.start()
    }

    private fun renameFolder(folder: OeaWorkspaceStore.Folder) {
        val k = activityKit() ?: return
        k.prompt(if (folder.isPair) "Rename app pair" else "Rename folder", null, "Name", folder.title) { value ->
            val name = value.trim()
            if (name.isEmpty()) return@prompt "Enter a name"
            w.updateFolder(folder.copy(title = name))
            null
        }
    }

    private fun editFolderApps(folder: OeaWorkspaceStore.Folder) {
        val k = activityKit() ?: return
        val choices = w.drawerApps()
        k.pickApps("Apps in ${folder.title}", "Choose the apps this folder holds.", choices, folder.members.toSet(), { w.appKey(it) }, confirmLabel = "Save") { chosen ->
            val ordered = folder.members.filter { it in chosen } + chosen.filter { it !in folder.members }
            if (ordered.size < 2) {
                w.toast("A folder needs at least two apps")
                return@pickApps
            }
            w.updateFolder(folder.copy(members = ordered))
        }
    }

    // ================================================================ widgets

    fun showWidgetPicker(page: Int) {
        val activity = w.hostActivity ?: return
        val k = activityKit() ?: return
        val providers = w.widgets.installedProviders()
        if (providers.isEmpty()) {
            w.widgets.pickWidget(activity, page)
            return
        }
        val pm = context.packageManager
        val appLabel = HashMap<String, String>()
        fun ownerLabel(info: AppWidgetProviderInfo): String = appLabel.getOrPut(info.provider.packageName) {
            runCatching { pm.getApplicationLabel(pm.getApplicationInfo(info.provider.packageName, 0)).toString() }.getOrDefault(info.provider.packageName)
        }
        val sorted = providers.sortedWith(compareBy({ ownerLabel(it).lowercase() }, { it.loadLabel(pm).lowercase() }))
        val previews = HashMap<String, Drawable?>()
        k.sheet("Widgets", "Tap a widget to add it to this page.") { panel, dialog ->
            val search = k.field("Search widgets")
            panel.addView(search, LinearLayout.LayoutParams(-1, -2))
            var visible = sorted
            val adapter = object : BaseAdapter() {
                override fun getCount() = visible.size
                override fun getItem(position: Int) = visible[position]
                override fun getItemId(position: Int) = position.toLong()
                override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                    val info = visible[position]
                    val row = convertView as? LinearLayout ?: LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                        addView(ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER },
                            LinearLayout.LayoutParams(dp(96), dp(72)).apply { rightMargin = dp(14) })
                        addView(LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(kit.text("", 15f, palette.text, OeaType.medium).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
                            addView(kit.text("", 12.5f, palette.textSecondary).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                        }, LinearLayout.LayoutParams(0, -2, 1f))
                    }
                    val key = info.provider.flattenToShortString()
                    val preview = previews.getOrPut(key) {
                        runCatching { info.loadPreviewImage(context, 0) }.getOrNull()
                            ?: runCatching { info.loadIcon(context, 0) }.getOrNull()
                    }
                    (row.getChildAt(0) as ImageView).setImageDrawable(preview)
                    val texts = row.getChildAt(1) as LinearLayout
                    (texts.getChildAt(0) as TextView).text = info.loadLabel(pm)
                    val (spanX, spanY) = w.defaultWidgetSpans(info)
                    (texts.getChildAt(1) as TextView).text = "${ownerLabel(info)} • $spanX × $spanY"
                    row.background = kit.ripple(kit.rounded(palette.card, 20), 20)
                    return row
                }
            }
            val list = ListView(context).apply {
                divider = ColorDrawable(Color.TRANSPARENT)
                dividerHeight = dp(6)
                selector = ColorDrawable(Color.TRANSPARENT)
                isVerticalScrollBarEnabled = false
                this.adapter = adapter
            }
            list.setOnItemClickListener { _, _, position, _ ->
                val info = visible[position]
                dialog.dismiss()
                val (spanX, spanY) = w.defaultWidgetSpans(info)
                // Use this page when it has an empty area of the right size,
                // otherwise the next page that does (a new page if needed).
                var target = page.coerceAtLeast(0)
                while (target < OeaWorkspaceStore.MAX_PAGES - 1 && !w.pageHasRoomForWidget(target, spanX, spanY)) target++
                if (target >= w.ws.pages()) w.ws.setPages(target + 1)
                if (target != page) w.ws.setCurrentPage(target)
                w.widgets.addWidget(activity, info, target, spanX, spanY)
            }
            search.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    val q = s?.toString()?.trim().orEmpty()
                    visible = if (q.isEmpty()) sorted else sorted.filter {
                        it.loadLabel(pm).contains(q, true) || ownerLabel(it).contains(q, true)
                    }
                    adapter.notifyDataSetChanged()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
            panel.addView(list, LinearLayout.LayoutParams(-1, (context.resources.displayMetrics.heightPixels * 0.58f).toInt()).apply { topMargin = dp(10) })
        }
    }

    /**
     * Long-press menu for a widget, anchored like the app icon popup. Moving
     * the finger instead drags the widget (handled by the workspace).
     */
    fun showWidgetOptions(id: Int, frame: View) {
        val info = w.widgets.info(id)
        val title = info?.loadLabel(context.packageManager) ?: "Widget"
        val rect = w.widgetRect(id)
        val limits = w.widgetLimits(id)
        val resizable = limits != null && (limits.horizontal || limits.vertical)
        val subtitle = buildString {
            if (rect != null) append("${rect.spanX} × ${rect.spanY} • ")
            append("Drag to move")
        }
        showPopup(bounds(frame), 296) { card ->
            popupHeader(card, title, subtitle, null)
            val actions = mutableListOf<Action>()
            if (resizable) actions += Action(R.drawable.oic_open_in_full, "Resize") { w.startWidgetResize(id) }
            if (w.widgets.canReconfigure(id)) actions += Action(R.drawable.oic_tune, "Settings") {
                w.hostActivity?.let { w.widgets.reconfigure(it, id) }
            }
            actions += Action(R.drawable.oic_view_carousel, "Move page") { chooseWidgetPage(id) }
            actions += Action(R.drawable.oic_delete, "Remove") { w.removeWidget(id) }
            actionGrid(card, actions)
        }
        frame.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
    }

    private fun chooseWidgetPage(id: Int) {
        val k = activityKit() ?: return
        val current = w.widgetRect(id)?.page ?: w.widgets.pageFor(id)
        val pages = (0 until w.ws.pages()).map { "Page ${it + 1}" } +
            if (w.ws.pages() < OeaWorkspaceStore.MAX_PAGES) listOf("New page") else emptyList()
        k.choose("Move widget", null, pages, current) { choice ->
            w.moveWidgetToPage(id, choice)
        }
    }

    // ============================================================ app actions

    private fun splitWith(app: OeaAppInfo) {
        val k = activityKit() ?: return
        val others = w.drawerApps().filter { it.packageName != app.packageName }
        k.pickApps("Split ${app.label} with…", "Pick the app for the other half of the screen.", others, emptySet(), { w.appKey(it) }, multi = false) { chosen ->
            val other = others.firstOrNull { w.appKey(it) in chosen } ?: return@pickApps
            k.sheet("${app.label} + ${other.label}", "Open both apps in split screen. Save the pair to open them together with one tap.") { panel, dialog ->
                panel.addView(k.buttonRow(
                    k.quietButton("Save pair") {
                        dialog.dismiss()
                        w.createPair(app, other)
                    },
                    k.primaryButton("Open now") {
                        dialog.dismiss()
                        w.launchPair(OeaWorkspaceStore.Folder("tmp", "", 0, 0, listOf(w.appKey(app), w.appKey(other)), OeaWorkspaceStore.KIND_PAIR))
                    }
))
            }
        }
    }

    private fun floating(app: OeaAppInfo) {
        if (!OeaMultitaskLauncher.freeformEnabled(context)) {
            val k = activityKit() ?: return
            k.sheet("Floating windows are off", "Android keeps floating (freeform) windows behind a developer switch on phones. Turn on Developer options › \"Enable freeform windows\", then restart your phone.") { panel, dialog ->
                panel.addView(k.buttonRow(
                    k.quietButton("Try anyway") {
                        dialog.dismiss()
                        w.toast(OeaMultitaskLauncher.launchFloating(context, app).message)
                    },
                    k.primaryButton("Developer options") {
                        dialog.dismiss()
                        if (!OeaMultitaskLauncher.openDeveloperOptions(context)) w.toast("Turn on Developer options first: Settings › About phone › tap Build number 7 times")
                    }
))
            }
            return
        }
        val launch = { w.toast(OeaMultitaskLauncher.launchFloating(context, app).message) }
        if (OeaAppLockStore.isLocked(context, w.appKey(app))) showUnlockPrompt(app) { launch() } else launch()
    }

    private fun homeIntent(): Intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        .setComponent(ComponentName(context, OeaLauncherActivity::class.java))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun freezeToggle(app: OeaAppInfo) {
        val pkg = app.packageName
        if (pkg == context.packageName) {
            w.toast("OEA cannot freeze itself")
            return
        }
        val frozen = w.isFrozen(pkg)
        if (OeaAppFreezer.mode(context) == OeaAppFreezer.Mode.SYSTEM && !w.isHibernated(pkg)) {
            com.oea.engine.core.OeaGuard.start("oea-freezer-toggle", "home:freezer") {
                val result = OeaAppFreezer.setFrozen(context, pkg, !frozen)
                w.post {
                    w.toast(result.message)
                    w.refreshBadges()
                }
            }
            return
        }
        if (frozen) {
            OeaAppFreezer.setHibernated(context, listOf(pkg), false)
            w.toast("${app.label} unfrozen")
            w.refreshBadges()
            return
        }
        OeaAppFreezer.setHibernated(context, listOf(pkg), true)
        w.refreshBadges()
        val start = OeaAssistant.forceStopWhenReady(context, listOf(pkg), homeIntent()) { stopped, _ ->
            w.toast(if (pkg in stopped) "${app.label} frozen and asleep" else "${app.label} frozen in OEA. Force stop was skipped; use Freezer › ⋮ › Put frozen apps back to sleep.")
        }
        when (start) {
            OeaAssistant.ForceStopStart.STARTED -> Unit
            // Granted but paused by Android: reconnect quietly, never re-ask.
            OeaAssistant.ForceStopStart.QUEUED -> w.toast("${app.label} frozen. Reconnecting OEA Assistant…")
            OeaAssistant.ForceStopStart.BUSY -> w.toast("${app.label} frozen in OEA")
            OeaAssistant.ForceStopStart.NOT_ENABLED -> offerAssistantForFreezer(app)
        }
    }

    /**
     * Shown only when OEA Assistant has never been switched on, and only once
     * per session; afterwards freezing just confirms with a toast.
     */
    private fun offerAssistantForFreezer(app: OeaAppInfo) {
        if (assistantOfferShown) {
            w.toast("${app.label} frozen in OEA")
            return
        }
        assistantOfferShown = true
        val k = activityKit() ?: return
        k.sheet("${app.label} is frozen in OEA", "OEA blocks it from Home and the drawer. Turn on OEA Assistant once so OEA can also force-stop frozen apps: no background activity, alarms or notifications until you unfreeze them. No root, ADB or computer needed.") { panel, dialog ->
            panel.addView(k.buttonRow(
                k.quietButton("Not now") { dialog.dismiss() },
                k.primaryButton("Turn on") {
                    dialog.dismiss()
                    OeaAssistant.openSetup(context)
                }
))
        }
    }

    private var assistantOfferShown = false

    fun showFrozenPrompt(app: OeaAppInfo) {
        val k = activityKit() ?: return
        k.sheet("${app.label} is frozen", "Unfreeze it to open it. You can freeze it again from its long-press menu or OEA Settings › App Freezer.") { panel, dialog ->
            panel.addView(k.buttonRow(
                k.quietButton("Keep frozen") { dialog.dismiss() },
                k.primaryButton("Unfreeze & open") {
                    dialog.dismiss()
                    OeaAppFreezer.setHibernated(context, listOf(app.packageName), false)
                    w.refreshBadges()
                    w.launchApp(app, null)
                }
))
        }
    }

    private fun lockToggle(app: OeaAppInfo) {
        val k = activityKit() ?: return
        val key = w.appKey(app)
        if (OeaAppLockStore.isLocked(context, key)) {
            k.prompt("Unlock ${app.label}", "Enter your OEA App Lock PIN to remove the lock.", "PIN", numeric = true, password = true, confirmLabel = "Remove lock") { pin ->
                if (!OeaAppLockStore.verifyPin(context, pin)) return@prompt "Incorrect PIN"
                OeaAppLockStore.setLocked(context, key, false)
                w.refreshBadges()
                null
            }
            return
        }
        if (OeaAppLockStore.hasPin(context)) {
            OeaAppLockStore.setLocked(context, key, true)
            w.refreshBadges()
            w.toast("${app.label} locked")
            return
        }
        k.prompt("Create App Lock PIN", "Choose a 4–8 digit PIN. OEA asks for it whenever a locked app is opened.", "New PIN", numeric = true, password = true, confirmLabel = "Lock app") { pin ->
            if (pin.length !in 4..8 || !pin.all(Char::isDigit)) return@prompt "PIN must be 4–8 digits"
            OeaAppLockStore.setPin(context, pin)
            OeaAppLockStore.setLocked(context, key, true)
            w.refreshBadges()
            null
        }
    }

    fun showUnlockPrompt(app: OeaAppInfo, onSuccess: () -> Unit) {
        val k = activityKit() ?: return
        k.prompt("Unlock ${app.label}", "This app is protected by OEA App Lock.", "PIN", numeric = true, password = true, confirmLabel = "Unlock") { pin ->
            if (!OeaAppLockStore.verifyPin(context, pin)) return@prompt "Incorrect PIN"
            w.post { onSuccess() }
            null
        }
    }

    fun showAssistantNeeded(feature: String) {
        val k = activityKit() ?: return
        val enabledButDisconnected = OeaAssistant.isEnabled(context) && !OeaAssistant.isConnected()
        val body = buildString {
            append("$feature uses OEA Assistant, OEA's accessibility service. It only acts when you trigger an OEA action and never reads or sends your screen content.")
            if (enabledButDisconnected) append("\n\nOEA Assistant is switched on but Android stopped it. Switch it off and on again.")
            if (OeaAssistant.needsRestrictedSettingsHint()) append("\n\n" + OeaAssistant.RESTRICTED_SETTINGS_HINT)
        }
        k.sheet(if (enabledButDisconnected) "OEA Assistant was paused" else "Turn on OEA Assistant", body) { panel, dialog ->
            if (OeaAssistant.needsRestrictedSettingsHint()) {
                panel.addView(k.quietButton("Open OEA App info", R.drawable.oic_info) {
                    dialog.dismiss()
                    OeaAssistant.openOeaAppInfo(context)
                }, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(8) })
            }
            panel.addView(k.buttonRow(
                k.quietButton("Not now") { dialog.dismiss() },
                k.primaryButton(if (enabledButDisconnected) "Open settings" else "Turn on") {
                    dialog.dismiss()
                    OeaAssistant.openSetup(context)
                }
))
        }
    }

    // ============================================================= onboarding

    fun showOnboarding() {
        val k = activityKit() ?: return
        fun usageGranted() = runCatching {
            context.getSystemService(AppOpsManager::class.java).checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
) == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
        val notificationsOn = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            ?.contains(context.packageName) == true
        val dialog = k.sheet("Welcome to OEA", "Four quick switches unlock everything OEA can do. You can change them any time in OEA Settings.") { panel, dialog ->
            val group = k.group(panel)
            fun status(on: Boolean) = if (on) "Done" else null
            k.row(group, R.drawable.oic_home, "Default Home", "Press Home to come back to OEA", value = status(w.isDefaultHome())) {
                dialog.dismiss(); w.requestDefaultHome()
            }
            k.row(group, R.drawable.oic_notifications, "Notification dots", "Badges on icons and Now Playing", value = status(notificationsOn)) {
                dialog.dismiss(); w.startSafely(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            k.row(group, R.drawable.oic_accessibility_new, "OEA Assistant", "Double-tap to lock, Recents, split screen, App Freezer",
                value = status(OeaAssistant.isEnabled(context))) {
                dialog.dismiss(); OeaAssistant.openSetup(context)
            }
            k.row(group, R.drawable.oic_insights, "Usage access", "Screen time, suggestions and Game Boost", value = status(usageGranted())) {
                dialog.dismiss(); w.startSafely(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
            panel.addView(k.primaryButton("Start using OEA") { dialog.dismiss() }, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(8) })
        }
        dialog.setOnDismissListener { w.store.setOnboardingDone(true) }
    }
}
