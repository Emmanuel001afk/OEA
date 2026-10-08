package com.oea.launcher.workspace

import android.app.Activity
import android.app.AlertDialog
import android.app.WallpaperManager
import android.content.ClipData
import android.content.ClipDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.*
import com.oea.launcher.data.OeaDataStore
import com.oea.launcher.drawer.OeaAppDrawerController
import com.oea.launcher.folders.OeaFolderController
import com.oea.launcher.icons.OeaIconController
import com.oea.launcher.model.OeaAppInfo
import com.oea.launcher.notifications.OeaNotificationState
import com.oea.launcher.R
import com.oea.launcher.widgets.OeaWidgetController
import com.oea.launcher.shortcuts.OeaShortcutController
import com.oea.launcher.focus.OeaFocusStore
import com.oea.launcher.search.OeaSearchController
import com.oea.launcher.applock.OeaAppFreezer
import com.oea.launcher.applock.OeaAppLockStore
import com.oea.launcher.applock.OeaDeviceAdminReceiver
import com.oea.launcher.callblocker.OeaCallBlockRules
import com.oea.launcher.gameboost.OeaGameBoostService
import com.oea.launcher.interaction.OeaGestureController
import com.oea.launcher.gameboost.OeaGameBoostStore
import com.oea.launcher.split.OeaSplitLauncher
import com.oea.launcher.multitask.OeaMultitaskLauncher
import kotlin.math.roundToInt

class OeaWorkspace(context: Context) : FrameLayout(context) {
    private val store = OeaDataStore.get(context)
    private val ws = OeaWorkspaceStore.get(context)
    private val pages = LinearLayout(context)
    private val pager = HorizontalScrollView(context)
    private val dock = GridLayout(context)
    private val drawer = LinearLayout(context)
    private val drawerScroll = ScrollView(context)
    private val drawerBody = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val drawerGrid = GridLayout(context)
    private val drawerSearch = EditText(context)
    private val search = EditText(context)
    private val dots = TextView(context)
    private val dockIndicator = TextView(context)
    private val wallpaperView = ImageView(context)
    private val homeRoot = LinearLayout(context)
    private lateinit var homeHeader: View
    private lateinit var homeSearch: EditText
    private val wallpaperRequestCode = 0x4F57
    private var apps: List<OeaAppInfo> = emptyList()
    private val drawerController = OeaAppDrawerController()
    private val folderController = OeaFolderController()
    private val iconController = OeaIconController(context)
    private val shortcutController = OeaShortcutController(context)
    private val focusStore = OeaFocusStore.get(context)
    private val searchController = OeaSearchController(context)
    private val widgetController = OeaWidgetController(context)
    private var drawerOpen = false
    private var hostActivity: Activity? = null
    private var dragged: String? = null
    private var themeBackground = Color.rgb(12, 15, 21)
    private var themeSurface = Color.rgb(30, 36, 49)
    private var themeText = Color.WHITE
    private var themeMuted = Color.rgb(154, 162, 177)
    private var wallpaperLightHint: Boolean? = null
    private var wallpaperLoadToken = 0
    private var renderedPages = mutableSetOf<Int>()

    fun attachHost(activity: Activity?) {
        hostActivity = activity
        widgetController.setHostActivity(activity)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        widgetController.start()
    }

    override fun onDetachedFromWindow() {
        widgetController.stop()
        super.onDetachedFromWindow()
    }

    init {
        setBackgroundColor(themeBackground)
        wallpaperView.scaleType = ImageView.ScaleType.CENTER_CROP
        wallpaperView.alpha = 0.98f
        addView(wallpaperView, FrameLayout.LayoutParams(-1, -1))
        homeRoot.orientation = LinearLayout.VERTICAL
        homeRoot.setPadding(dp(16), dp(12), dp(16), dp(8))
        addView(homeRoot, FrameLayout.LayoutParams(-1, -1))
        val root = homeRoot
        loadOeaWallpaper()

        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        homeHeader = header
        header.addView(ImageView(context).apply {
            setImageResource(R.drawable.oea_logo)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = "OEA"
        }, LinearLayout.LayoutParams(dp(46), dp(46)))
        header.addView(TextView(context).apply {
            text = "OEA"
            textSize = 28f
            setTextColor(themeText)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(6), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, dp(52), 1f))
        header.addView(TextView(context).apply {
            text = "⋮"
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(themeText)
            background = rounded(themeSurface, 18)
            contentDescription = "OEA home menu"
            isClickable = true
            isFocusable = true
            setOnClickListener { menu(this) }
        }, LinearLayout.LayoutParams(dp(48), dp(44)).apply {
            leftMargin = dp(6)
        })
        root.addView(header)

        homeSearch = search
        search.hint = "Search apps"
        search.setSingleLine(true)
        search.textSize = 16f
        search.setTextColor(themeText)
        search.setHintTextColor(themeMuted)
        search.setPadding(dp(18), 0, dp(18), 0)
        search.background = rounded(themeSurface, 28)
        search.setOnFocusChangeListener { _, focused -> if (focused && !drawerOpen) openDrawer() }
        search.addTextChangedListener(Watcher { query ->
            if (drawerOpen) {
                if (drawerSearch.text.toString() != query) drawerSearch.setText(query)
                renderDrawer(query)
            }
        })
        root.addView(search, LinearLayout.LayoutParams(-1, dp(54)).apply { bottomMargin = dp(8) })

        pager.isHorizontalScrollBarEnabled = false
        pager.setOnScrollChangeListener { _, scrollX, _, _, _ ->
            val pageWidth = pages.getChildAt(0)?.width ?: pager.width
            if (pageWidth > 0) {
                val page = (scrollX.toFloat() / pageWidth).roundToInt().coerceIn(0, ws.pages() - 1)
                if (page != ws.getCurrentPage()) ws.setCurrentPage(page)
                dots.text = List(ws.pages()) { if (it == page) "●" else "•" }.joinToString(" ")
                ensurePageRendered(page)
                ensurePageRendered(page - 1)
                ensurePageRendered(page + 1)
            }
        }
        pager.setOnTouchListener(OeaGestureController(
            pager,
            onSwipeUp = { openDrawer() },
            onSwipeDown = { closeDrawer() },
            // Horizontal movement belongs to the native pager. The gesture layer
            // must not steal it, otherwise page scrolling becomes inconsistent.
            consumeTouchEvents = false,
        ))
        pages.orientation = LinearLayout.HORIZONTAL
        pager.addView(pages, FrameLayout.LayoutParams(-2, -1))
        root.addView(pager, LinearLayout.LayoutParams(-1, 0, 1f))
        dots.gravity = Gravity.CENTER
        dots.setTextColor(themeMuted)
        root.addView(dots, LinearLayout.LayoutParams(-1, dp(22)))
        dockIndicator.gravity = Gravity.CENTER
        dockIndicator.setTextColor(themeMuted)
        dockIndicator.textSize = 10f
        dockIndicator.setOnClickListener {
            if (ws.dockPageCount() > 1) {
                val next = (ws.currentDockPage() + 1) % ws.dockPageCount()
                ws.setCurrentDockPage(next)
                renderDock()
            }
        }
        root.addView(dockIndicator, LinearLayout.LayoutParams(-1, dp(16)))
        dock.columnCount = OeaWorkspaceStore.DOCK_SLOTS
        dock.setUseDefaultMargins(false)
        dock.setPadding(dp(2), dp(2), dp(2), dp(2))
        dock.setOnDragListener { _, e -> dockDrop(e) }
        root.addView(dock, LinearLayout.LayoutParams(-1, dp(72)))

        buildDrawer()
        widgetController.start()
        post {
            applyThemeFromWallpaper()
            rebuild()
        }
    }

    private fun buildDrawer() {
        drawer.orientation = LinearLayout.VERTICAL
        drawer.setPadding(dp(12), dp(12), dp(12), dp(12))
        drawer.setBackgroundColor(themeBackground)

        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(context).apply {
            text = "‹  All apps"
            textSize = 20f
            setTextColor(themeText)
            setOnClickListener { closeDrawer() }
        }, LinearLayout.LayoutParams(-1, dp(52)))
        drawer.addView(header)
        drawerSearch.hint = "Search all apps"
        drawerSearch.setSingleLine(true)
        drawerSearch.textSize = 16f
        drawerSearch.setTextColor(themeText)
        drawerSearch.setHintTextColor(themeMuted)
        drawerSearch.setPadding(dp(18), 0, dp(18), 0)
        drawerSearch.background = rounded(themeSurface, 24)
        drawerSearch.addTextChangedListener(Watcher { renderDrawer(it) })
        drawer.addView(drawerSearch, LinearLayout.LayoutParams(-1, dp(50)).apply { bottomMargin = dp(8) })
        drawerScroll.addView(drawerBody, FrameLayout.LayoutParams(-1, -2))
        drawer.addView(drawerScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(drawer, FrameLayout.LayoutParams(-1, -1))
        drawer.visibility = View.GONE
    }

    fun bind(value: List<OeaAppInfo>) {
        apps = value
        OeaAppFreezer.syncActualState(context)
        ws.ensureSeeded(apps.filterNot { it.packageName == context.packageName }.map { Triple(it.packageName, it.className, it.label) })
        ws.clearMissing(apps.map { OeaWorkspaceStore.key(it.packageName, it.className) }.toSet())
        rebuild()
        renderDrawer(drawerSearch.text.toString())
    }

    private fun rebuild() {
        normalizeFolderNames()
        normalizeHomeLayout()
        renderedPages.clear()
        pages.removeAllViews()
        val pageWidth = width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        for (p in 0 until ws.pages()) {
            // Each OEA home page has its own vertical scroll surface. Horizontal
            // paging remains the page-to-page navigation; vertical scrolling is reserved
            // for a page whose icons/widgets extend beyond the visible viewport.
            val pageFrame = FrameLayout(context).apply {
                clipChildren = true
                clipToPadding = true
                tag = p
                setBackgroundColor(Color.TRANSPARENT)
            }
            val pageScroll = ScrollView(context).apply {
                isFillViewport = false
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                tag = "pageScroll"
            }
            val pageContent = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(4), 0, dp(12))
                clipChildren = true
            }
            val grid = GridLayout(context).apply {
                columnCount = cols()
                useDefaultMargins = false
                setPadding(dp(3), dp(4), dp(3), dp(2))
                setOnDragListener(pageDrop(p))
                tag = "grid"
            }
            pageContent.addView(grid, LinearLayout.LayoutParams(-1, -2))
            val widgetHost = FrameLayout(context).apply {
                setPadding(dp(4), dp(6), dp(4), dp(10))
                clipChildren = false
                tag = "widgets"
                setOnLongClickListener {
                    hostActivity?.let { widgetController.pickWidget(it, p) }
                    true
                }
                setOnDragListener { host, event ->
                    when (event.action) {
                        DragEvent.ACTION_DRAG_STARTED -> true
                        DragEvent.ACTION_DROP -> {
                            val sourceId = event.clipData?.getItemAt(0)?.text?.toString()?.toIntOrNull()
                            if (sourceId != null) {
                                val container = host as FrameLayout
                                var targetId: Int? = null
                                for (i in 0 until container.childCount) {
                                    val child = container.getChildAt(i)
                                    if (child is android.appwidget.AppWidgetHostView &&
                                        event.x >= child.left && event.x <= child.right &&
                                        event.y >= child.top && event.y <= child.bottom) {
                                        targetId = child.appWidgetId
                                        break
                                    }
                                }
                                widgetController.moveBefore(sourceId, targetId)
                                widgetController.setPage(sourceId, p)
                                refreshPage(p)
                            }
                            true
                        }
                        else -> false
                    }
                }
            }
            // Widgets are content on the page, not a weighted remainder. This prevents
            // their size from being forced by the viewport and lets the page scroll.
            pageContent.addView(widgetHost, LinearLayout.LayoutParams(-1, -2))
            pageScroll.addView(pageContent, FrameLayout.LayoutParams(-1, -2))
            pageFrame.addView(pageScroll, FrameLayout.LayoutParams(-1, -1))
            pages.addView(pageFrame, LinearLayout.LayoutParams(pageWidth, -1))
        }
        val current = ws.getCurrentPage()
        ensurePageRendered(current)
        ensurePageRendered(current - 1)
        ensurePageRendered(current + 1)
        dots.text = List(ws.pages()) { if (it == current) "●" else "•" }.joinToString(" ")
        renderDock()
    }

    private fun ensurePageRendered(page: Int) {
        if (page !in 0 until ws.pages() || renderedPages.contains(page)) return
        val pageFrame = pages.getChildAt(page) as? FrameLayout ?: return
        val pageScroll = pageFrame.getChildAt(0) as? ScrollView ?: return
        val content = pageScroll.getChildAt(0) as? LinearLayout ?: return
        val grid = content.findViewWithTag<GridLayout>("grid") ?: return
        val widgetHost = content.findViewWithTag<FrameLayout>("widgets") ?: return
        renderPage(grid, page)
        renderWidgets(widgetHost, page)
        renderedPages.add(page)
    }

    private fun refreshPage(page: Int) {
        if (page !in 0 until ws.pages()) return
        renderedPages.remove(page)
        ensurePageRendered(page)
        dots.text = List(ws.pages()) { if (it == ws.getCurrentPage()) "●" else "•" }.joinToString(" ")
    }
    private fun refreshPages(vararg pageValues: Int) {
        pageValues.distinct().forEach { refreshPage(it) }
        renderDock()
    }


    private fun normalizeHomeLayout() {
        val capacity = cols() * 4
        if (capacity <= 0) return

        val all = ws.items().toMutableList()
        val folders = ws.folders().toMutableList()
        val occupied = mutableMapOf<Int, MutableSet<Int>>()
        var changed = false

        fun reserve(page: Int, cell: Int) {
            occupied.getOrPut(page) { mutableSetOf() }.add(cell)
        }

        // Repair folder coordinates first. Folder tiles occupy real home cells and
        // must stay inside the same bounded 4-row grid as normal app tiles.
        val normalizedFolders = folders.map { folder ->
            val validPage = folder.page in 0 until OeaWorkspaceStore.MAX_PAGES
            val validCell = folder.cell in 0 until capacity
            val currentUsed = occupied.getOrPut(folder.page.coerceIn(0, OeaWorkspaceStore.MAX_PAGES - 1)) { mutableSetOf() }
            if (validPage && validCell && folder.cell !in currentUsed) {
                reserve(folder.page, folder.cell)
                folder
            } else {
                var targetPage = folder.page.coerceIn(0, OeaWorkspaceStore.MAX_PAGES - 1)
                var targetCell: Int? = null
                while (targetCell == null && targetPage < OeaWorkspaceStore.MAX_PAGES) {
                    val used = occupied.getOrPut(targetPage) { mutableSetOf() }
                    targetCell = (0 until capacity).firstOrNull { it !in used }
                    if (targetCell == null) targetPage++
                }
                if (targetCell != null) {
                    reserve(targetPage, targetCell)
                    changed = true
                    folder.copy(page = targetPage, cell = targetCell)
                } else folder
            }
        }
        folders.clear()
        folders.addAll(normalizedFolders)

        // Repair normal items after folder cells have been reserved.
        val ordered = all.withIndex()
            .filter { it.value.folderId == null }
            .sortedWith(compareBy({ it.value.page }, { it.value.cell }, { it.index }))

        ordered.forEach { indexed ->
            val item = indexed.value
            val valid = item.page in 0 until OeaWorkspaceStore.MAX_PAGES &&
                item.cell in 0 until capacity &&
                item.cell !in occupied.getOrPut(item.page) { mutableSetOf() }

            if (valid) {
                reserve(item.page, item.cell)
                return@forEach
            }

            var targetPage = item.page.coerceIn(0, OeaWorkspaceStore.MAX_PAGES - 1)
            var targetCell: Int? = null
            while (targetCell == null && targetPage < OeaWorkspaceStore.MAX_PAGES) {
                val used = occupied.getOrPut(targetPage) { mutableSetOf() }
                targetCell = (0 until capacity).firstOrNull { it !in used }
                if (targetCell == null) targetPage++
            }
            if (targetCell != null) {
                val index = all.indexOfFirst { it.id == item.id }
                if (index >= 0) {
                    all[index] = item.copy(page = targetPage, cell = targetCell)
                    reserve(targetPage, targetCell)
                    changed = true
                }
            }
        }

        val highestPage = maxOf(
            all.filter { it.folderId == null }.maxOfOrNull { it.page } ?: 0,
            folders.maxOfOrNull { it.page } ?: 0,
        )
        val requiredPages = (highestPage + 1).coerceIn(1, OeaWorkspaceStore.MAX_PAGES)
        if (ws.pages() != requiredPages) {
            ws.setPages(requiredPages)
            ws.setCurrentPage(ws.getCurrentPage())
            changed = true
        }
        if (changed) {
            ws.replaceItems(all)
            ws.replaceFolders(folders)
        }
    }

    private fun renderPage(grid: GridLayout, page: Int) {
        grid.removeAllViews()
        val items = ws.items().filter { it.page == page && it.folderId == null }.associateBy { it.cell }
        val folders = ws.folders().filter { it.page == page }.associateBy { it.cell }
        val count = maxOf(cols() * 4, (items.keys.maxOrNull() ?: -1) + 1, (folders.keys.maxOrNull() ?: -1) + 1)
        for (cell in 0 until count) {
            val view = folders[cell]?.let { folderTile(it) } ?: items[cell]?.let { homeTile(it) } ?: emptyCell()
            grid.addView(view, GridLayout.LayoutParams().apply {
                width = 0
                height = dp(84)
                columnSpec = GridLayout.spec(cell % cols(), 1, 1f)
                rowSpec = GridLayout.spec(cell / cols())
                setMargins(dp(1), dp(1), dp(1), dp(1))
            })
        }
    }

    private fun homeTile(item: OeaWorkspaceStore.Item): View {
        val app = find(item.id) ?: return emptyCell()
        return tile(app).apply {
            setOnLongClickListener {
                dragged = item.id
                startDragAndDrop(
                    ClipData.newPlainText(ClipDescription.MIMETYPE_TEXT_PLAIN, item.id),
                    View.DragShadowBuilder(this), item.id, View.DRAG_FLAG_GLOBAL,
                )
                true
            }
            setOnDragListener { _, e ->
                if (e.action == DragEvent.ACTION_DROP && dragged != null && dragged != item.id) {
                    folder(item.id, dragged!!)
                    true
                } else e.action == DragEvent.ACTION_DRAG_STARTED
            }
        }
    }

    private fun showAppActions(app: OeaAppInfo, anchor: View, itemId: String) {
        val popup = PopupMenu(hostActivity ?: return, anchor)
        val inHome = ws.items().any { it.id == itemId }
        val inDock = ws.dock().contains(itemId)
        val inFolder = ws.folders().any { folder -> folder.members.contains(itemId) }
        popup.menu.add("Open")
        if (!inDock) popup.menu.add("Add to dock")
        if (inHome || inDock || inFolder) popup.menu.add("Remove from home")
        popup.menu.add("App info")
        popup.menu.add(if (store.isHidden(app.packageName, app.className)) "Unhide app" else "Hide app")
        popup.menu.add(if (OeaAppLockStore.isLocked(context, itemId)) "Unlock app" else "Lock app")
        popup.menu.add("Freeze / unfreeze")
        shortcutController.shortcuts(app.packageName).take(5).forEach { shortcut ->
            popup.menu.add(shortcut.shortLabel ?: shortcut.longLabel ?: "Shortcut")
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.title.toString()) {
                "Open" -> { launch(app); true }
                "Add to dock" -> { addToDock(itemId); true }
                "Remove from home" -> { removeFromHome(itemId); true }
                "App info" -> { openAppInfo(app.packageName); true }
                "Hide app" -> { store.setHidden(app.packageName, app.className, true); removeFromHome(itemId); renderDrawer(drawerSearch.text.toString()); true }
                "Unhide app" -> { store.setHidden(app.packageName, app.className, false); renderDrawer(drawerSearch.text.toString()); true }
                "Lock app" -> { lockApp(itemId, app); true }
                "Unlock app" -> { OeaAppLockStore.setLocked(context, itemId, false); Toast.makeText(context, app.label + " unlocked", Toast.LENGTH_SHORT).show(); true }
                "Freeze / unfreeze" -> { freezeDialog(app); true }
                else -> {
                    val shortcut = shortcutController.shortcuts(app.packageName)
                        .firstOrNull { (it.shortLabel ?: it.longLabel ?: "Shortcut") == item.title.toString() }
                    unlockForLaunch(app) {
                        runCatching { shortcut?.intent?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
                            .onFailure { Toast.makeText(context, "Unable to open " + app.label, Toast.LENGTH_SHORT).show() }
                    }
                    true
                }
            }
        }
        popup.show()
    }

    private fun addToDock(key: String) {
        val values = ws.dock().toMutableList()
        if (values.contains(key)) {
            val existingPage = values.indexOf(key) / OeaWorkspaceStore.DOCK_SLOTS
            ws.setCurrentDockPage(existingPage)
            renderDock()
            return
        }
        values.add(key)
        ws.setDock(values)
        ws.setCurrentDockPage(values.lastIndex / OeaWorkspaceStore.DOCK_SLOTS)
        renderDock()
    }

    private fun addToHome(key: String) {
        if (ws.items().any { it.id == key }) return
        val app = find(key) ?: return
        val items = ws.items().toMutableList()
        var page = ws.getCurrentPage()
        var cell = firstFree(items, page, 0)
        val capacity = cols() * 4

        while (cell == null && page + 1 < ws.pages()) {
            page += 1
            cell = firstFree(items, page, 0)
        }
        if (cell == null && page + 1 < OeaWorkspaceStore.MAX_PAGES) {
            page += 1
            ws.setPages(page + 1)
            cell = firstFree(items, page, 0)
        }
        if (cell == null) {
            Toast.makeText(context, "OEA home is full.", Toast.LENGTH_SHORT).show()
            return
        }

        items.add(OeaWorkspaceStore.Item(key, app.packageName, app.className, page, cell!!))
        ws.replaceItems(items)
        // An app has one home representation: moving it from a folder to Home
        // removes its old folder membership instead of creating a duplicate.
        ws.replaceFolders(
            ws.folders()
                .map { it.copy(members = it.members.filterNot { member -> member == key }) }
                .filter { it.members.isNotEmpty() }
        )
        ws.setCurrentPage(page)
        rebuild()
        pager.post { ensurePageRendered(page); pager.smoothScrollTo(page * pager.width, 0) }
    }

    private fun removeFromHome(key: String) {
        // A foldered app is represented by the folder tile, not by an Item row.
        // Capture that folder page before removing membership so the visible folder
        // surface is refreshed immediately instead of leaving a stale tile behind.
        val affectedPages = mutableSetOf<Int>()
        ws.items().firstOrNull { it.id == key }?.page?.let { affectedPages.add(it) }
        ws.folders().firstOrNull { it.members.contains(key) }?.page?.let { affectedPages.add(it) }
        ws.replaceItems(ws.items().filterNot { it.id == key })
        ws.setDock(ws.dock().filterNot { it == key })
        ws.replaceFolders(ws.folders().map { it.copy(members = it.members.filterNot { m -> m == key }) }.filter { it.members.isNotEmpty() })
        if (affectedPages.isNotEmpty()) refreshPages(*affectedPages.toIntArray()) else renderDock()
    }

    private fun lockApp(itemId: String, app: OeaAppInfo) {
        if (OeaAppLockStore.hasPin(context)) {
            OeaAppLockStore.setLocked(context, itemId, true)
            Toast.makeText(context, app.label + " locked", Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(context).apply {
            hint = "4-8 digit PIN"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setSingleLine(true)
        }
        AlertDialog.Builder(hostActivity ?: context).setTitle("Set OEA App Lock PIN").setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Set PIN") { _, _ ->
                val pin = input.text.toString()
                if (pin.length in 4..8) {
                    OeaAppLockStore.setPin(context, pin)
                    OeaAppLockStore.setLocked(context, itemId, true)
                    Toast.makeText(context, app.label + " locked", Toast.LENGTH_SHORT).show()
                } else Toast.makeText(context, "PIN must be 4-8 digits.", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun unlockForLaunch(app: OeaAppInfo, onSuccess: () -> Unit) {
        val key = OeaWorkspaceStore.key(app.packageName, app.className)
        if (!OeaAppLockStore.isLocked(context, key)) {
            onSuccess()
            return
        }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(8), dp(24), dp(4))
        }
        val iconView = ImageView(context).apply {
            setImageDrawable(icon(app.packageName))
            contentDescription = app.label + " locked"
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        card.addView(iconView, LinearLayout.LayoutParams(dp(64), dp(64)).apply { bottomMargin = dp(8) })
        card.addView(TextView(context).apply {
            text = app.label
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(themeText)
        }, LinearLayout.LayoutParams(-1, -2))
        card.addView(TextView(context).apply {
            text = "App locked • enter your PIN to continue"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(themeMuted)
            setPadding(0, dp(4), 0, dp(12))
        }, LinearLayout.LayoutParams(-1, -2))
        val input = EditText(context).apply {
            hint = "PIN"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            gravity = Gravity.CENTER
            setSingleLine(true)
            textSize = 20f
            contentDescription = "OEA App Lock PIN"
        }
        card.addView(input, LinearLayout.LayoutParams(-1, dp(52)))
        val dialog = AlertDialog.Builder(hostActivity ?: context)
            .setTitle("OEA App Lock")
            .setView(card)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Unlock", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (OeaAppLockStore.verifyPin(context, input.text.toString())) {
                    dialog.dismiss()
                    onSuccess()
                } else {
                    input.text?.clear()
                    input.error = "Incorrect PIN"
                    input.requestFocus()
                }
            }
        }
        dialog.show()
    }

    private fun openAppInfo(packageName: String) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun freezeDialog(app: OeaAppInfo) {
        val backend = OeaAppFreezer.backend(context)
        val authority = backend != OeaAppFreezer.Backend.NONE
        val frozen = OeaAppFreezer.frozenPackages(context).contains(app.packageName)
        val authorityLabel = when (backend) {
            OeaAppFreezer.Backend.DEVICE_OWNER -> "device-owner"
            OeaAppFreezer.Backend.ROOT -> "root"
            OeaAppFreezer.Backend.NONE -> "none"
        }
        val message = if (authority) {
            if (frozen) app.label + " is currently frozen by OEA (" + authorityLabel + " authority)."
            else app.label + " can be frozen by OEA (" + authorityLabel + " authority)."
        } else {
            app.label + " is not frozen. True package suspension needs device-owner or root authority; OEA will not repeatedly prompt for unavailable authority."
        }
        AlertDialog.Builder(hostActivity ?: context)
            .setTitle("App Freezer")
            .setMessage(message)
            .setPositiveButton(if (authority) (if (frozen) "Unfreeze" else "Freeze") else "Close") { _, _ ->
                if (authority) {
                    val result = OeaAppFreezer.setFrozen(context, app.packageName, !frozen)
                    Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                    if (result.success) refreshBadges()
                }
            }
            .setNeutralButton(if (authority) "Close" else "How to enable") { _, _ ->
                if (!authority) openDeviceAdminSettings()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openDeviceAdminSettings() {
        AlertDialog.Builder(hostActivity ?: context)
            .setTitle("Freezer authority required")
            .setMessage("Android has not approved device-owner or root authority for OEA. True package freezing is unavailable until that authority is provisioned. OEA will not repeatedly request an unavailable permission.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun openThemeSettings() {
        val choices = arrayOf("System / Wallpaper", "Dark", "Light")
        val current = store.themeMode()
        val checked = when (current) { "dark" -> 1; "light" -> 2; else -> 0 }
        AlertDialog.Builder(hostActivity ?: context)
            .setTitle("OEA Themes")
            .setSingleChoiceItems(choices, checked) { dialog, which ->
                when (which) {
                    0 -> store.setThemeMode("system")
                    1 -> store.setThemeMode("dark")
                    2 -> store.setThemeMode("light")
                }
                applyThemeFromWallpaper()
                rebuild()
                dialog.dismiss()
            }
            .setNegativeButton("Wallpaper") { _, _ -> openWallpaperChooser() }
            .setPositiveButton("Done", null)
            .show()
    }

    private fun openFocusSettings() {
        val choices = apps.filterNot { store.isHidden(it.packageName, it.className) }
        val checked = BooleanArray(choices.size) { focusStore.isFocused(OeaWorkspaceStore.key(choices[it].packageName, choices[it].className)) }
        AlertDialog.Builder(hostActivity ?: context)
            .setTitle("OEA Focus apps (max 7)")
            .setMultiChoiceItems(choices.map { it.label }.toTypedArray(), checked) { dialog, which, selected ->
                val key = OeaWorkspaceStore.key(choices[which].packageName, choices[which].className)
                val current = focusStore.apps().toMutableSet()
                if (selected) {
                    if (current.size >= OeaFocusStore.MAX_APPS) {
                        (dialog as AlertDialog).listView.setItemChecked(which, false)
                        Toast.makeText(context, "Focus is limited to 7 apps.", Toast.LENGTH_SHORT).show()
                    } else current.add(key)
                } else current.remove(key)
                focusStore.setApps(current)
            }
            .setPositiveButton("Done") { _, _ -> renderDrawer(drawerSearch.text.toString()) }
            .show()
    }

    private fun openSystemsSettings() {
        launchOeaTool(null)
    }

    private fun launchOeaTool(screen: String?) {
        runCatching {
            val intent = Intent(context, com.oea.launcher.OeaSystemToolsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            if (screen != null) intent.putExtra(com.oea.launcher.OeaSystemToolsActivity.EXTRA_SCREEN, screen)
            (hostActivity ?: context).startActivity(intent)
        }.onFailure {
            Toast.makeText(context, "OEA tool could not be opened.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openPhone() {
        runCatching { (hostActivity ?: context).startActivity(Intent(context, com.oea.launcher.phone.OeaPhoneActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
            .onFailure { Toast.makeText(context, "OEA Phone could not be opened.", Toast.LENGTH_SHORT).show() }
    }

    private fun openCallBlockerSettings() {
        launchOeaTool("call_blocker")
    }

    private fun openMultitaskDialog() {
        launchOeaTool("multitask")
    }

    private fun openSplitPairDialog() {
        launchOeaTool("split")
    }

    private fun openGameBoostSettings() {
        launchOeaTool("game_boost")
    }

    private fun openFreezerSettings() {
        launchOeaTool("freezer")
    }

    private fun openAppLockSettings() {
        val choices = apps.filterNot { it.packageName == context.packageName }
        val checked = BooleanArray(choices.size) {
            OeaAppLockStore.isLocked(context, OeaWorkspaceStore.key(choices[it].packageName, choices[it].className))
        }
        AlertDialog.Builder(hostActivity ?: context).setTitle("OEA App Lock")
            .setMultiChoiceItems(choices.map { it.label }.toTypedArray(), checked) { _, which, value ->
                if (value && !OeaAppLockStore.hasPin(context)) {
                    Toast.makeText(context, "Set a PIN first from Lock app.", Toast.LENGTH_LONG).show()
                    checked[which] = false
                } else {
                    OeaAppLockStore.setLocked(context, OeaWorkspaceStore.key(choices[which].packageName, choices[which].className), value)
                    checked[which] = value
                }
            }
            .setNeutralButton("Set PIN") { _, _ -> setAppLockPin() }
            .setPositiveButton("Done", null).show()
    }

    private fun setAppLockPin() {
        val input = EditText(context).apply {
            hint = "4-8 digit PIN"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setSingleLine(true)
        }
        AlertDialog.Builder(hostActivity ?: context).setTitle("Set OEA App Lock PIN").setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val pin = input.text.toString()
                if (pin.length in 4..8) OeaAppLockStore.setPin(context, pin)
                else Toast.makeText(context, "PIN must be 4-8 digits.", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun openNotificationAccessSettings() {
        runCatching { hostActivity?.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
    }

    private fun menu(anchor: View) {
        val scroll = ScrollView(context)
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(12))
        }
        fun section(title: String) {
            body.addView(TextView(context).apply {
                text = title
                textSize = 12f
                setTextColor(themeMuted)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, dp(12), 0, dp(6))
            })
        }
        var menuDialog: AlertDialog? = null
        fun action(title: String, subtitle: String = "", onClick: () -> Unit) {
            body.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = rounded(themeSurface, 14)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    onClick()
                    menuDialog?.dismiss()
                }
                addView(TextView(context).apply {
                    text = title
                    textSize = 15f
                    setTextColor(themeText)
                })
                if (subtitle.isNotBlank()) addView(TextView(context).apply {
                    text = subtitle
                    textSize = 11f
                    setTextColor(themeMuted)
                    maxLines = 2
                })
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        }

        section("LAYOUT")
        action("Grid", store.gridColumns().toString() + " columns") { gridDialogFromHomeMenu() }
        action("Add page", "Create another home page") {
            if (ws.pages() < OeaWorkspaceStore.MAX_PAGES) {
                ws.setPages(ws.pages() + 1)
                rebuild()
            } else {
                Toast.makeText(context, "OEA has reached the maximum home pages.", Toast.LENGTH_SHORT).show()
            }
        }
        if (ws.pages() > 1) action("Remove last page", "Removes only the last page") { removeLastPage() }
        action("Add widget", "Open the Android widget picker") {
            hostActivity?.let { widgetController.pickWidget(it, ws.getCurrentPage()) }
                ?: Toast.makeText(context, "OEA Home is not attached to an Activity.", Toast.LENGTH_SHORT).show()
        }

        section("APPEARANCE")
        action("Labels", if (store.showAppLabels()) "Shown under app icons" else "Hidden") {
            store.setShowAppLabels(!store.showAppLabels()); rebuild()
        }
        action("Theme", "System / wallpaper, dark, or light") { openThemeSettings() }
        action("Wallpaper", "Change the OEA wallpaper") { openWallpaperChooser() }

        section("APPS")
        action("Hidden apps", store.hiddenApps().size.toString() + " hidden") { openHiddenAppsSettings() }
        action("Most used", if (store.showMostUsed()) "Shown after repeated launches" else "Hidden") {
            store.setShowMostUsed(!store.showMostUsed())
            renderDrawer(drawerSearch.text.toString())
        }

        section("TOOLS")
        action("OEA Settings", "Open the complete OEA settings screen") { openSystemsSettings() }
        action("App Freezer", "Suspend apps only when OEA has device-owner authority") { launchOeaTool("freezer") }
        action("App Lock", "PIN-protect selected apps") { openAppLockSettings() }
        action("Phone & Calls", "OEA dialer and call controls") { openPhone() }
        action("Call Blocker", "Exact, prefix and suffix rules") { launchOeaTool("call_blocker") }
        action("Game Boost", "Game monitoring and in-game controls") { launchOeaTool("game_boost") }
        action("Multitask", "Floating task when Android/OEM supports freeform") { openMultitaskDialog() }
        action("Split Screen", "Two apps in Android adjacent-window mode") { openSplitPairDialog() }

        section("ANDROID ACCESS")
        action("Notification access", "Required for notification badges and media integration") { openNotificationAccessSettings() }

        scroll.addView(body)
        menuDialog = AlertDialog.Builder(hostActivity ?: context)
            .setTitle("OEA")
            .setView(scroll)
            .setNegativeButton("Close", null)
            .create()
        menuDialog?.show()
    }

    private fun gridDialogFromHomeMenu() {
        val values = arrayOf("3 columns", "4 columns", "5 columns")
        val current = (store.gridColumns() - 3).coerceIn(0, 2)
        AlertDialog.Builder(hostActivity ?: context)
            .setTitle("Home grid")
            .setSingleChoiceItems(values, current) { dialog, which ->
                store.setGridColumns(which + 3)
                rebuild()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun removeLastPage() {
        val last = ws.pages() - 1
        ws.replaceItems(ws.items().filter { it.page != last })
        ws.replaceFolders(ws.folders().filter { it.page != last })
        ws.setPages(last)
        rebuild()
    }

    private fun applyThemeFromWallpaper() {
        val mode = store.themeMode()
        val light = when (mode) {
            "light" -> true
            "dark" -> false
            else -> wallpaperLightHint ?: ((resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) != android.content.res.Configuration.UI_MODE_NIGHT_YES)
        }
        if (light) {
            themeBackground = Color.rgb(245, 246, 249)
            themeSurface = Color.rgb(228, 231, 238)
            themeText = Color.rgb(20, 24, 31)
            themeMuted = Color.rgb(82, 89, 103)
        } else {
            themeBackground = Color.rgb(12, 15, 21)
            themeSurface = Color.rgb(30, 36, 49)
            themeText = Color.WHITE
            themeMuted = Color.rgb(154, 162, 177)
        }
        setBackgroundColor(Color.TRANSPARENT)
        search.setTextColor(themeText)
        search.setHintTextColor(themeMuted)
        drawerSearch.setTextColor(themeText)
        drawerSearch.setHintTextColor(themeMuted)
        drawer.setBackgroundColor(Color.TRANSPARENT)
    }

    private fun loadOeaWallpaper(rebuildAfterLoad: Boolean = false) {
        val token = ++wallpaperLoadToken
        val uri = store.wallpaperUri()?.let(Uri::parse)
        wallpaperView.visibility = View.INVISIBLE
        Thread {
            val result = runCatching {
                if (uri != null) decodeWallpaper(uri)
                else {
                    val drawable = WallpaperManager.getInstance(context).drawable as? BitmapDrawable
                    drawable?.bitmap?.let { downsampleBitmap(it) }
                }
            }.getOrNull()
            post {
                if (token != wallpaperLoadToken) return@post
                if (result != null) {
                    wallpaperView.setImageBitmap(result)
                    wallpaperView.visibility = View.VISIBLE
                    wallpaperLightHint = isBitmapMostlyLight(result)
                    applyThemeFromWallpaper()
                    if (rebuildAfterLoad) rebuild() else invalidate()
                } else {
                    if (uri != null) store.setWallpaperUri(null)
                    wallpaperView.setImageDrawable(null)
                    wallpaperView.visibility = View.VISIBLE
                    wallpaperLightHint = null
                    applyThemeFromWallpaper()
                    if (rebuildAfterLoad) rebuild()
                }
            }
        }.start()
    }

    private fun decodeWallpaper(uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val sample = calculateWallpaperSample(bounds.outWidth, bounds.outHeight)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    private fun downsampleBitmap(source: Bitmap): Bitmap {
        val sample = calculateWallpaperSample(source.width, source.height)
        if (sample <= 1) return source
        return Bitmap.createScaledBitmap(
            source,
            (source.width / sample).coerceAtLeast(1),
            (source.height / sample).coerceAtLeast(1),
            true
        )
    }

    private fun calculateWallpaperSample(width: Int, height: Int): Int {
        if (width <= 0 || height <= 0) return 1
        val targetW = resources.displayMetrics.widthPixels.coerceAtLeast(720)
        val targetH = resources.displayMetrics.heightPixels.coerceAtLeast(1280)
        var sample = 1
        while (width / (sample * 2) >= targetW && height / (sample * 2) >= targetH) sample *= 2
        return sample.coerceAtMost(4)
    }

    private fun isBitmapMostlyLight(bitmap: Bitmap): Boolean {
        if (bitmap.width == 0 || bitmap.height == 0) return false
        val p = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
        return (Color.red(p) * 0.299 + Color.green(p) * 0.587 + Color.blue(p) * 0.114) > 150
    }

    private fun find(key: String) = apps.firstOrNull { OeaWorkspaceStore.key(it.packageName, it.className) == key }
    private fun firstFree(items: List<OeaWorkspaceStore.Item>, page: Int, start: Int): Int? {
        val capacity = cols() * 4
        if (capacity <= 0) return null
        val folderCells = ws.folders()
            .filter { it.page == page }
            .mapTo(mutableSetOf()) { it.cell }
        var n = start.coerceAtLeast(0)
        while (n < capacity) {
            val occupied = items.any { it.page == page && it.cell == n && it.folderId == null }
            if (!occupied && n !in folderCells) return n
            n++
        }
        return null
    }
    private fun launch(app: OeaAppInfo) {
        unlockForLaunch(app) {
            runCatching {
                (hostActivity ?: context).startActivity(Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    component = ComponentName(app.packageName, app.className)
                })
                store.recordLaunch(app.packageName, app.className)
            }.onFailure {
                Toast.makeText(context, "Unable to open " + app.label, Toast.LENGTH_SHORT).show()
            }
        }
    }
    private fun icon(pkg: String): Drawable? = iconController.icon(pkg)
    private fun selectable(): Drawable? = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).let { a ->
        a.getDrawable(0).also { a.recycle() }
    }
    private fun renderWidgets(host: FrameLayout, page: Int) {
        host.removeAllViews()
        val widgets = widgetController.views().filter { widgetController.pageFor(it.appWidgetId) == page }
        widgets.forEach { widget ->
            val (widthDp, heightDp) = widgetController.sizeFor(widget.appWidgetId)
            val widthPx = minOf(dp(widthDp), (resources.displayMetrics.widthPixels - dp(24)).coerceAtLeast(dp(120)))
            widget.isLongClickable = true
            widget.tag = widget.appWidgetId
            widget.setOnLongClickListener {
                val clip = ClipData.newPlainText("oea_widget", "oea_widget:${widget.appWidgetId}")
                widget.startDragAndDrop(
                    clip,
                    View.DragShadowBuilder(widget),
                    widget.appWidgetId,
                    View.DRAG_FLAG_GLOBAL,
                )
                true
            }
            val wrapper = FrameLayout(context).apply {
                clipChildren = false
            }
            wrapper.addView(widget, FrameLayout.LayoutParams(widthPx, dp(heightDp), Gravity.CENTER))
            wrapper.addView(TextView(context).apply {
                text = "⋮"
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(themeText)
                background = rounded(themeSurface, 12)
                contentDescription = "Widget options"
                isClickable = true
                isFocusable = true
                setOnClickListener { widgetOptions(widget) }
            }, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.TOP or Gravity.END))
            host.addView(wrapper, FrameLayout.LayoutParams(widthPx, dp(heightDp), Gravity.CENTER_HORIZONTAL).apply {
                topMargin = dp(6)
                bottomMargin = dp(6)
            })
        }
        if (widgets.isEmpty()) {
            host.addView(TextView(context).apply {
                text = "Long-press here to add a widget"
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(themeMuted)
                // This placeholder belongs to the page being rendered; never use the
                // globally selected page here because adjacent pages can already be rendered.
                setOnClickListener { hostActivity?.let { widgetController.pickWidget(it, page) } }
            }, FrameLayout.LayoutParams(-1, dp(56)))
        }
    }

    private fun updateWidgetSize(widget: android.appwidget.AppWidgetHostView, widthDp: Int, heightDp: Int) {
        val options = android.os.Bundle().apply {
            putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, widthDp)
            putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, heightDp)
            putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, widthDp)
            putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, heightDp)
        }
        widgetController.setSize(widget.appWidgetId, widthDp, heightDp)
        widget.updateAppWidgetSize(options, widthDp, heightDp, widthDp, heightDp)
    }

    private fun widgetOptions(widget: android.appwidget.AppWidgetHostView) {
        val id = widget.appWidgetId
        AlertDialog.Builder(hostActivity ?: context)
            .setTitle("Widget")
            .setItems(arrayOf("Resize: compact", "Resize: medium", "Resize: large", "Remove widget")) { _, which ->
                when (which) {
                    0 -> updateWidgetSize(widget, 120, 80)
                    1 -> updateWidgetSize(widget, 300, 160)
                    2 -> updateWidgetSize(widget, 420, 240)
                    3 -> widgetController.remove(id)
                }
                rebuild()
            }.show()
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }
    private fun cols() = store.gridColumns().coerceIn(3, 5)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    fun refreshBadges() {
        // Resume is frequent (dialogs, permissions, other apps). Rebuild only the
        // rendered surfaces so returning Home does not recreate every page.
        renderedPages.toList().forEach { refreshPage(it) }
        renderDock()
        if (drawerOpen) renderDrawer(drawerSearch.text.toString())
    }

    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode == wallpaperRequestCode) {
            if (resultCode == Activity.RESULT_OK && data?.data != null) {
                val uri = data.data!!
                runCatching {
                    val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    if (flags != 0) context.contentResolver.takePersistableUriPermission(uri, flags)
                }
                store.setWallpaperUri(uri.toString())
                loadOeaWallpaper(true)
            }
            return true
        }
        val handled = widgetController.handleActivityResult(requestCode, resultCode, data)
        if (handled) rebuild()
        return handled
    }

    fun handleBack(): Boolean {
        if (drawerOpen) {
            if (drawerSearch.hasFocus() && drawerSearch.text.isNotEmpty()) {
                drawerSearch.text.clear()
                return true
            }
            closeDrawer()
            return true
        }
        if (search.hasFocus() || search.text.isNotEmpty()) {
            search.clearFocus()
            search.text.clear()
            context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(windowToken, 0)
            return true
        }
        return true
    }

    private class Watcher(private val change: (String) -> Unit) : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { change(s?.toString().orEmpty()) }
        override fun afterTextChanged(s: android.text.Editable?) = Unit
    }
}
