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
        val input = EditText(context).apply {
            hint = "PIN"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setSingleLine(true)
        }
        AlertDialog.Builder(hostActivity ?: context).setTitle("Unlock " + app.label).setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Unlock") { _, _ ->
                if (OeaAppLockStore.verifyPin(context, input.text.toString())) onSuccess()
                else Toast.makeText(context, "Incorrect PIN.", Toast.LENGTH_SHORT).show()
            }.show()
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
    }

    private fun openDeviceAdminSettings() {
        val command = "adb shell dpm set-device-owner com.oea.launcher/com.oea.launcher.applock.OeaDeviceAdminReceiver"
        context.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(
            android.content.ClipData.newPlainText("OEA device-owner command", command)
        )
        AlertDialog.Builder(hostActivity ?: context)
            .setTitle("Freezer authority")
            .setMessage("Android does not grant true package freezing through the normal Device Admin screen. OEA must be provisioned as device owner.\n\nADB setup command:\n$command\n\nThe command was copied to your clipboard.")
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
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val rm = context.getSystemService(android.app.role.RoleManager::class.java)
            if (rm?.isRoleAvailable(android.app.role.RoleManager.ROLE_CALL_SCREENING) == true &&
                !rm.isRoleHeld(android.app.role.RoleManager.ROLE_CALL_SCREENING)) {
                runCatching { context.startActivity(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_CALL_SCREENING)) }
            }
        }
        val input = EditText(context).apply {
            hint = "Exact number to block (optional)"
            setSingleLine(true)
        }
        AlertDialog.Builder(hostActivity ?: context)
            .setTitle("OEA Call blocker")
            .setMessage("Add an exact number. Contacts remain allowed by default.")
            .setView(input)
            .setNegativeButton("Close", null)
            .setNeutralButton(if (OeaCallBlockRules.enabled(context)) "Disable" else "Enable") { _, _ ->
                OeaCallBlockRules.setEnabled(context, !OeaCallBlockRules.enabled(context))
            }
            .setPositiveButton("Save") { _, _ ->
                val number = input.text.toString().trim()
                if (number.isNotEmpty()) {
                    OeaCallBlockRules.setRules(
                        context,
                        OeaCallBlockRules.getExact(context) + number,
                        OeaCallBlockRules.getPrefix(context),
                        OeaCallBlockRules.getSuffix(context),
                    )
                }
                OeaCallBlockRules.setEnabled(context, true)
            }.show()
    }

    private fun openMultitaskDialog() {
        val choices = apps.filter { it.packageName != context.packageName }.distinctBy { it.packageName }
        if (choices.isEmpty()) {
            Toast.makeText(context, "No apps are available for multitask.", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(hostActivity ?: context)
            .setTitle("OEA Multitask")
            .setMessage("Choose an app to open as a floating task over the current app. Android/OEM support determines whether the task can float.")
            .setItems(choices.map { it.label }.toTypedArray()) { _, which ->
                val app = choices[which]
                unlockForLaunch(app) {
                    val result = OeaMultitaskLauncher.launchFloating(context, app)
                    if (!result.success) Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openSplitPairDialog() {
        val choices = apps.filter { it.packageName != context.packageName }.distinctBy { it.packageName }
        if (choices.size < 2) {
            Toast.makeText(context, "At least two apps are required for split screen.", Toast.LENGTH_SHORT).show()
            return
        }
        val checked = BooleanArray(choices.size)
        AlertDialog.Builder(hostActivity ?: context)
            .setTitle("OEA Split Screen")
            .setMessage("Choose exactly two apps. Android controls the final divider and orientation.")
            .setMultiChoiceItems(choices.map { it.label }.toTypedArray(), checked) { dialog, which, value ->
                if (value && checked.count { it } >= 2) {
                    (dialog as AlertDialog).listView.setItemChecked(which, false)
                    Toast.makeText(context, "Choose only two apps.", Toast.LENGTH_SHORT).show()
                } else checked[which] = value
            }
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Launch") { _, _ ->
                val picked = choices.mapIndexedNotNull { i, app -> app.packageName.takeIf { checked[i] } }
                if (picked.size != 2) {
                    Toast.makeText(context, "Choose two apps first.", Toast.LENGTH_SHORT).show()
                } else {
                    val first = choices.first { it.packageName == picked[0] }
                    val second = choices.first { it.packageName == picked[1] }
                    unlockForLaunch(first) {
                        unlockForLaunch(second) {
                            val result = OeaSplitLauncher.launchPair(context, first.packageName, second.packageName)
                            if (!result.success) Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .show()
    }

    private fun openGameBoostSettings() {
        val input = EditText(context).apply {
            hint = "Game package name"
            setSingleLine(true)
        }
        AlertDialog.Builder(hostActivity ?: context)
            .setTitle("OEA Game Boost")
            .setMessage("Add a game package, then enable monitoring. Usage access and overlay permission are required for automatic detection/overlay.")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Permissions") { _, _ ->
                runCatching { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            .setPositiveButton(if (OeaGameBoostStore.enabled(context)) "Disable" else "Enable") { _, _ ->
                val packageName = input.text.toString().trim()
                if (packageName.isNotEmpty()) OeaGameBoostStore.setGames(context, OeaGameBoostStore.games(context) + packageName)
                val enabled = !OeaGameBoostStore.enabled(context)
                OeaGameBoostStore.setEnabled(context, enabled)
                if (enabled) {
                    runCatching {
                        if (android.os.Build.VERSION.SDK_INT >= 26) context.startForegroundService(Intent(context, OeaGameBoostService::class.java))
                        else context.startService(Intent(context, OeaGameBoostService::class.java))
                    }
                } else {
                    context.stopService(Intent(context, OeaGameBoostService::class.java))
                }
            }.show()
    }

    private fun drawerLayoutMenu(anchor: View) {
        PopupMenu(hostActivity ?: return, anchor).apply {
            menu.add("Grid")
            menu.add("Vertical list")
            menu.add("Horizontal")
            setOnMenuItemClickListener {
                when (it.title.toString()) {
                    "Grid" -> store.setDrawerMode(OeaDataStore.DrawerMode.GRID)
                    "Vertical list" -> store.setDrawerMode(OeaDataStore.DrawerMode.VERTICAL)
                    "Horizontal" -> store.setDrawerMode(OeaDataStore.DrawerMode.HORIZONTAL)
                }
                renderDrawer(drawerSearch.text.toString())
                true
            }
            show()
        }
    }

    private fun renderDrawer(query: String) {
        if (!drawerOpen) return
        drawerBody.removeAllViews()
        val visible = drawerController.filter(apps, query)
            .filterNot { store.isHidden(it.packageName, it.className) }
        if (query.isBlank()) {
            renderFocusStrip()
            renderOeaTools()
            addSectionLabel(drawerBody, "Apps · " + visible.size)
        } else if (visible.isNotEmpty()) {
            addSectionLabel(drawerBody, "Apps · " + visible.size)
        }
        when (store.drawerMode()) {
            OeaDataStore.DrawerMode.VERTICAL -> {
                val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                visible.forEach { app ->
                    val row = drawerRow(app)
                    list.addView(row, LinearLayout.LayoutParams(-1, dp(56)).apply { bottomMargin = dp(3) })
                }
                drawerBody.addView(list)
            }
            OeaDataStore.DrawerMode.HORIZONTAL -> {
                val horizontal = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
                val pages = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                val rowsPerPage = 4
                visible.chunked(rowsPerPage * cols()).forEach { pageApps ->
                    val column = GridLayout(context).apply {
                        columnCount = cols()
                        rowCount = rowsPerPage
                        setPadding(dp(2), dp(2), dp(2), dp(2))
                    }
                    pageApps.forEachIndexed { index, app ->
                        column.addView(drawerTile(app), GridLayout.LayoutParams().apply {
                            width = dp(72)
                            height = dp(72)
                            columnSpec = GridLayout.spec(index % cols())
                            rowSpec = GridLayout.spec(index / cols())
                            setMargins(dp(1), dp(1), dp(1), dp(1))
                        })
                    }
                    pages.addView(column, LinearLayout.LayoutParams(cols() * dp(84), rowsPerPage * dp(94)))
                }
                horizontal.addView(pages, FrameLayout.LayoutParams(-2, -2))
                drawerBody.addView(horizontal, FrameLayout.LayoutParams(-1, -2))
            }
            OeaDataStore.DrawerMode.GRID -> {
                drawerGrid.removeAllViews()
                drawerGrid.columnCount = cols()
                if (visible.isNotEmpty()) visible.forEachIndexed { index, app ->
                    drawerGrid.addView(drawerTile(app).apply {
                        setOnLongClickListener { showAppActions(app, this, OeaWorkspaceStore.key(app.packageName, app.className)); true }
                    }, GridLayout.LayoutParams().apply {
                        width = 0
                        height = dp(68)
                        columnSpec = GridLayout.spec(index % cols(), 1, 1f)
                        rowSpec = GridLayout.spec(index / cols())
                        setMargins(dp(1), dp(1), dp(1), dp(1))
                    })
                }
                drawerBody.addView(drawerGrid, LinearLayout.LayoutParams(-1, -2))
            }
        }
        if (query.isNotBlank()) renderSearchActions(query)
    }

    private fun renderSearchActions(query: String) {
        val actions = searchController.actions(query)
        if (actions.isEmpty()) return
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        if (query.isNotBlank()) {
            addSectionLabel(list, if (drawerController.filter(apps, query).none { !store.isHidden(it.packageName, it.className) }) "Search" else "Search actions")
        }
        actions.forEach { action ->
            list.addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), 0, dp(10), 0)
                background = rounded(themeSurface, 16)
                addView(TextView(context).apply {
                    text = action.title
                    textSize = 13f
                    setTextColor(themeText)
                }, LinearLayout.LayoutParams(0, dp(42), 1f))
                addView(TextView(context).apply {
                    text = action.subtitle
                    textSize = 10f
                    setTextColor(themeMuted)
                }, LinearLayout.LayoutParams(-2, dp(42)))
                setOnClickListener {
                    runCatching { context.startActivity(action.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }, LinearLayout.LayoutParams(-1, dp(42)).apply { bottomMargin = dp(2) })
        }
        drawerBody.addView(list)
    }

    private fun renderFocusStrip() {
        val focused = focusStore.apps().mapNotNull(::find)
        val allKeys = apps.filterNot { store.isHidden(it.packageName, it.className) }
            .map { OeaWorkspaceStore.key(it.packageName, it.className) }
        val used = store.mostUsed(allKeys, 8)
            .filter { store.launchCount(it) >= 2 }
            .mapNotNull(::find)
            .take(8)
        if (focused.isEmpty() && used.isEmpty()) return
        val section = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        if (focused.isNotEmpty()) {
            addSectionLabel(section, "OEA Focus · " + focused.size + "/" + OeaFocusStore.MAX_APPS)
            section.addView(appStrip(focused), LinearLayout.LayoutParams(-1, dp(82)))
        }
        if (used.isNotEmpty() && store.showMostUsed()) {
            addSectionLabel(section, "Most used · " + used.size)
            val grid = GridLayout(context).apply {
                columnCount = 4
                useDefaultMargins = false
            }
            used.forEachIndexed { index, app ->
                grid.addView(drawerTile(app), GridLayout.LayoutParams().apply {
                    width = 0
                    height = dp(68)
                    columnSpec = GridLayout.spec(index % 4, 1, 1f)
                    rowSpec = GridLayout.spec(index / 4)
                    setMargins(dp(1), dp(1), dp(1), dp(1))
                })
            }
            val rows = ((used.size + 3) / 4).coerceAtLeast(1)
            section.addView(grid, LinearLayout.LayoutParams(-1, dp(rows * 68)))
        }
        drawerBody.addView(section)
    }

    private fun appStrip(values: List<OeaAppInfo>): HorizontalScrollView {
        val strip = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        values.forEach { app ->
            row.addView(drawerTile(app), LinearLayout.LayoutParams(dp(68), dp(68)).apply {
                setMargins(dp(1), dp(1), dp(1), dp(1))
            })
        }
        strip.addView(row, FrameLayout.LayoutParams(-2, -2))
        return strip
    }

    private fun addSectionLabel(parent: LinearLayout, title: String) {
        parent.addView(TextView(context).apply {
            text = title
            textSize = 11f
            setTextColor(themeMuted)
            setPadding(dp(4), dp(4), dp(4), dp(2))
        }, LinearLayout.LayoutParams(-1, dp(34)))
    }

    private fun renderOeaTools() {
        addSectionLabel(drawerBody, "OEA Systems")
        val row = GridLayout(context).apply {
            columnCount = 3
            useDefaultMargins = false
        }
        listOf(
            "⚙" to "OEA Settings",
            "❄" to "App Freezer",
            "☎" to "Phone & Calls",
            "⛔" to "Call Blocker",
            "🎮" to "Game Boost",
            "▣" to "Multitask",
            "▤" to "Split Screen",
        ).forEachIndexed { index, (iconText, title) ->
            row.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = rounded(themeSurface, 18)
                isClickable = true
                isFocusable = true
                setPadding(dp(4), dp(3), dp(4), dp(3))
                setOnClickListener {
                    when (title) {
                        "OEA Settings" -> openSystemsSettings()
                        "App Freezer" -> openFreezerSettings()
                        "Phone & Calls" -> openPhone()
                        "Call Blocker" -> openCallBlockerSettings()
                        "Game Boost" -> openGameBoostSettings()
                        "Multitask" -> openMultitaskDialog()
                        "Split Screen" -> openSplitPairDialog()
                    }
                }
                addView(TextView(context).apply {
                    text = iconText
                    textSize = 22f
                    gravity = Gravity.CENTER
                    setTextColor(themeText)
                }, LinearLayout.LayoutParams(-1, dp(34)))
                addView(TextView(context).apply {
                    text = title
                    textSize = 10f
                    gravity = Gravity.CENTER
                    setTextColor(themeText)
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(-1, dp(30)))
            }, GridLayout.LayoutParams().apply {
                width = 0
                height = dp(72)
                columnSpec = GridLayout.spec(index % 3, 1, 1f)
                rowSpec = GridLayout.spec(index / 3)
                setMargins(dp(1), dp(1), dp(1), dp(3))
            })
        }
        drawerBody.addView(row, LinearLayout.LayoutParams(-1, dp(220)))
    }

    private fun drawerTile(app: OeaAppInfo) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        isClickable = true
        background = ColorDrawable(Color.TRANSPARENT)
        contentDescription = "Open " + app.label
        setOnClickListener { launch(app) }
        addView(FrameLayout(context).apply {
            val iconView = ImageView(context).apply {
                setImageDrawable(icon(app.packageName))
                scaleType = ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = true
            }
            addView(iconView, FrameLayout.LayoutParams(dp(38), dp(38), Gravity.CENTER))
            val count = OeaNotificationState.countForPackage(app.packageName)
            if (count > 0) {
                addView(TextView(context).apply {
                    text = if (count > 99) "99+" else count.toString()
                    textSize = 7f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    background = rounded(Color.rgb(210, 60, 70), 10)
                    minWidth = dp(16)
                    minHeight = dp(16)
                    setPadding(dp(2), 0, dp(2), 0)
                }, FrameLayout.LayoutParams(-2, dp(16), Gravity.TOP or Gravity.END))
            }
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        if (store.showAppLabels()) addView(TextView(context).apply {
            text = app.label
            textSize = 9f
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(themeText)
        }, LinearLayout.LayoutParams(-1, dp(20)))
    }

    private fun drawerRow(app: OeaAppInfo): View {
        val key = OeaWorkspaceStore.key(app.packageName, app.className)
        return LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
            background = rounded(themeSurface, 16)
            addView(ImageView(context).apply {
                setImageDrawable(icon(app.packageName))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(dp(42), dp(42)))
            addView(TextView(context).apply {
                text = app.label
                textSize = 15f
                setTextColor(themeText)
                setPadding(dp(12), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, -1, 1f))
            addView(TextView(context).apply {
                text = if (ws.items().any { it.id == key }) "✓" else "+"
                textSize = 20f
                gravity = Gravity.CENTER
                setTextColor(themeText)
                contentDescription = "Add to home"
                setOnClickListener {
                    if (!ws.items().any { it.id == key }) addToHome(key)
                    else removeFromHome(key)
                }
            }, LinearLayout.LayoutParams(dp(42), -1))
            addView(TextView(context).apply {
                text = "⋮"
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(themeMuted)
                setOnClickListener { showAppActions(app, this, key) }
            }, LinearLayout.LayoutParams(dp(44), -1))
            setOnClickListener { launch(app) }
            setOnLongClickListener { showAppActions(app, this, key); true }
        }
    }

    private fun folderTile(folder: OeaWorkspaceStore.Folder) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = ColorDrawable(Color.TRANSPARENT)
        setOnClickListener { openFolder(folder) }
        setOnLongClickListener { showFolderRename(folder); true }
        setOnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> true
                DragEvent.ACTION_DROP -> {
                    dragged?.let { addToFolder(folder.id, it) }
                    true
                }
                DragEvent.ACTION_DRAG_ENDED -> {
                    dragged = null
                    false
                }
                else -> false
            }
        }
        val preview = GridLayout(context).apply {
            columnCount = 2
            useDefaultMargins = false
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = rounded(themeSurface, 16)
        }
        folder.members.mapNotNull(::find).take(4).forEach { app ->
            preview.addView(ImageView(context).apply {
                setImageDrawable(icon(app.packageName))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, GridLayout.LayoutParams().apply {
                width = dp(22); height = dp(22)
                setMargins(dp(1), dp(1), dp(1), dp(1))
            })
        }
        addView(preview, LinearLayout.LayoutParams(dp(56), dp(56)))
        addView(TextView(context).apply {
            text = folder.title
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(themeText)
        }, LinearLayout.LayoutParams(-1, dp(28)))
    }

    private fun emptyCell() = TextView(context).apply {
        setOnLongClickListener { menu(this); true }
    }

    private fun tile(app: OeaAppInfo) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        isClickable = true
        background = ColorDrawable(Color.TRANSPARENT)
        foreground = null
        contentDescription = "Open " + app.label
        setOnClickListener { launch(app) }
        addView(FrameLayout(context).apply {
            val iconView = ImageView(context).apply {
                // Use the app's real launcher drawable as-is. OEA must not wrap, pad,
                // clip, or paint a second rectangular/circular background around it.
                setImageDrawable(icon(app.packageName))
                scaleType = ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = true
                alpha = if (OeaAppFreezer.frozenPackages(context).contains(app.packageName)) 0.45f else 1f
                setPadding(0, 0, 0, 0)
            }
            addView(iconView, FrameLayout.LayoutParams(dp(42), dp(42), Gravity.CENTER))
            val count = OeaNotificationState.countForPackage(app.packageName)
            if (count > 0) {
                addView(TextView(context).apply {
                    text = if (count > 99) "99+" else count.toString()
                    textSize = 8f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    background = rounded(Color.rgb(210, 60, 70), 12)
                    minWidth = dp(18)
                    minHeight = dp(18)
                    setPadding(dp(3), 0, dp(3), 0)
                }, FrameLayout.LayoutParams(-2, dp(18), Gravity.TOP or Gravity.END))
            }
        }, LinearLayout.LayoutParams(dp(46), dp(46)))
        if (store.showAppLabels()) addView(TextView(context).apply {
            text = app.label
            textSize = 10f
            gravity = Gravity.CENTER
            maxLines = 2
            setTextColor(themeText)
        }, LinearLayout.LayoutParams(-1, dp(22)))
    }

    private fun pageDrop(page: Int) = View.OnDragListener { view, e ->
        when (e.action) {
            DragEvent.ACTION_DRAG_STARTED -> true
            DragEvent.ACTION_DROP -> {
                val widgetId = e.clipData?.getItemAt(0)?.text?.toString()
                    ?.removePrefix("oea_widget:")?.toIntOrNull()
                if (widgetId != null) {
                    widgetController.setPage(widgetId, page)
                    refreshPage(page)
                    return@OnDragListener true
                }
                val key = dragged ?: return@OnDragListener true
                val grid = view as GridLayout
                val cw = (grid.width / cols()).coerceAtLeast(1)
                val col = (e.x / cw).toInt().coerceIn(0, cols() - 1)
                val rowHeight = dp(76).coerceAtLeast(1)
                val row = (e.y / rowHeight).toInt().coerceAtLeast(0)
                val cell = row * cols() + col
                val targetFolder = ws.folders().firstOrNull { it.page == page && it.cell == cell }
                if (targetFolder != null) addToFolder(targetFolder.id, key) else move(key, page, cell)
                true
            }
            DragEvent.ACTION_DRAG_ENDED -> { dragged = null; false }
            else -> false
        }
    }

    private fun dockDrop(e: DragEvent): Boolean {
        if (e.action == DragEvent.ACTION_DRAG_STARTED) return true
        if (e.action != DragEvent.ACTION_DROP) return false
        val key = dragged ?: return true
        val values = ws.dock().filterNot { it == key }.toMutableList()
        values.add(key)
        ws.setDock(values)
        ws.setCurrentDockPage(values.lastIndex / OeaWorkspaceStore.DOCK_SLOTS)
        ws.replaceItems(ws.items().filterNot { it.id == key })
        ws.replaceFolders(
            ws.folders()
                .map { it.copy(members = it.members.filterNot { member -> member == key }) }
                .filter { it.members.isNotEmpty() }
        )
        dragged = null
        renderDock()
        rebuild()
        pager.post {
            val page = ws.getCurrentPage()
            ensurePageRendered(page)
            pager.smoothScrollTo(page * pager.width, 0)
        }
        return true
    }

    private fun move(key: String, page: Int, cell: Int) {
        val all = ws.items().toMutableList()
        val moving = all.firstOrNull { it.id == key } ?: return
        val capacity = cols() * 4
        val targetCell = cell.coerceIn(0, capacity - 1)
        val targetFolder = ws.folders().firstOrNull { it.page == page && it.cell == targetCell }
        if (targetFolder != null) {
            addToFolder(targetFolder.id, key)
            return
        }

        val oldPage = moving.page
        val collision = all.firstOrNull {
            it.id != key && it.page == page && it.cell == targetCell && it.folderId == null
        }
        var destinationPage = page
        var destinationCell = targetCell
        if (collision != null) {
            val next = firstFree(all, destinationPage, targetCell + 1)
            if (next != null) {
                all[all.indexOf(collision)] = collision.copy(page = destinationPage, cell = next)
            } else {
                // A full target page should not dead-end a drag. Continue into the
                // next available home page, creating one when capacity permits.
                var foundPage = -1
                var foundCell = -1
                for (candidate in (page + 1) until OeaWorkspaceStore.MAX_PAGES) {
                    val free = firstFree(all, candidate, 0)
                    if (free != null) {
                        foundPage = candidate
                        foundCell = free
                        break
                    }
                }
                if (foundPage < 0) {
                    Toast.makeText(context, "OEA home has no free space.", Toast.LENGTH_SHORT).show()
                    dragged = null
                    return
                }
                destinationPage = foundPage
                destinationCell = foundCell
                if (ws.pages() <= destinationPage) ws.setPages(destinationPage + 1)
            }
        }

        all[all.indexOf(moving)] = moving.copy(page = destinationPage, cell = destinationCell, folderId = null)
        ws.replaceItems(all)
        ws.setDock(ws.dock().filterNot { it == key })
        dragged = null
        refreshPages(oldPage, destinationPage)
        ws.setCurrentPage(destinationPage)
        pager.post { ensurePageRendered(destinationPage); pager.smoothScrollTo(destinationPage * pager.width, 0) }
    }

    private fun folder(target: String, source: String) {
        val t = ws.items().firstOrNull { it.id == target } ?: return
        val folders = ws.folders().toMutableList()
        val existing = folders.firstOrNull { it.page == t.page && it.cell == t.cell }
        if (existing == null) {
            val members = listOf(target, source)
            folders.add(OeaWorkspaceStore.Folder(
                "folder-" + System.currentTimeMillis(),
                suggestFolderName(members),
                t.page,
                t.cell,
                members,
            ))
        } else {
            val members = folderController.mergeMembers(existing.members, source)
            folders[folders.indexOf(existing)] = existing.copy(
                members = members,
                title = if (existing.title == "Folder") suggestFolderName(members) else existing.title,
            )
        }
        ws.replaceFolders(folders)
        ws.replaceItems(ws.items().filterNot { it.id == target || it.id == source })
        ws.setDock(ws.dock().filterNot { it == source || it == target })
        dragged = null
        refreshPages(t.page)
    }

    private fun addToFolder(folderId: String, key: String) {
        val folder = ws.folders().firstOrNull { it.id == folderId } ?: return
        if (folder.members.contains(key)) {
            dragged = null
            return
        }
        val updated = folder.copy(
            members = folder.members + key,
            title = if (folder.title == "Folder") suggestFolderName(folder.members + key) else folder.title,
        )
        ws.replaceFolders(ws.folders().map { if (it.id == folderId) updated else it })
        ws.replaceItems(ws.items().filterNot { it.id == key })
        ws.setDock(ws.dock().filterNot { it == key })
        dragged = null
        refreshPages(folder.page)
    }

    private fun suggestFolderName(keys: List<String>): String {
        val labels = keys.mapNotNull(::find).map { (it.label + " " + it.packageName).lowercase() }
        return when {
            labels.any { it.contains("ai") || it.contains("deepseek") || it.contains("claude") || it.contains("anthropic") || it.contains("openai") || it.contains("chatgpt") || it.contains("gemini") || it.contains("copilot") || it.contains("perplexity") } -> "AI"
            labels.any { it.contains("game") || it.contains("pubg") || it.contains("free fire") || it.contains("codm") } -> "Games"
            labels.any { it.contains("music") || it.contains("spotify") || it.contains("sound") } -> "Music"
            labels.any { it.contains("chat") || it.contains("whatsapp") || it.contains("telegram") || it.contains("messenger") } -> "Social"
            labels.any { it.contains("video") || it.contains("youtube") || it.contains("netflix") } -> "Video"
            labels.any { it.contains("photo") || it.contains("gallery") || it.contains("camera") } -> "Photos"
            else -> "Folder"
        }
    }

    private fun normalizeFolderNames() {
        val updated = ws.folders().map { folder ->
            if (folder.title.equals("Social", ignoreCase = true)) {
                val members = folder.members.mapNotNull(::find)
                val allAi = members.isNotEmpty() && members.all { app ->
                    val text = (app.label + " " + app.packageName).lowercase()
                    text.contains("ai") || text.contains("deepseek") || text.contains("claude") ||
                        text.contains("anthropic") || text.contains("openai") || text.contains("chatgpt") ||
                        text.contains("gemini") || text.contains("copilot") || text.contains("perplexity")
                }
                if (allAi) folder.copy(title = "AI") else folder
            } else folder
        }
        if (updated != ws.folders()) ws.replaceFolders(updated)
    }

    private fun openFolder(folder: OeaWorkspaceStore.Folder) {
        val activity = hostActivity ?: run {
            Toast.makeText(context, "OEA Home is not ready for folders.", Toast.LENGTH_SHORT).show()
            return
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(8))
            background = rounded(themeSurface, 24)
        }
        box.addView(TextView(context).apply {
            text = folder.title + "  •  " + folder.members.size + " apps"
            textSize = 20f
            setTextColor(themeText)
            setPadding(0, 0, 0, dp(10))
        })
        val grid = GridLayout(activity).apply { columnCount = 4; useDefaultMargins = false }
        folder.members.mapNotNull(::find).forEach { app ->
            val index = grid.childCount
            grid.addView(tile(app), GridLayout.LayoutParams().apply {
                width = 0
                height = dp(88)
                columnSpec = GridLayout.spec(index % 4, 1, 1f)
                rowSpec = GridLayout.spec(index / 4)
                setMargins(dp(1), dp(1), dp(1), dp(1))
            })
        }
        val folderScroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            addView(grid, FrameLayout.LayoutParams(-1, -2))
        }
        box.addView(folderScroll, LinearLayout.LayoutParams(-1, dp(260)))
        val dialog = AlertDialog.Builder(activity).setView(box)
            .setNeutralButton("Rename") { _, _ -> showFolderRename(folder) }
            .setPositiveButton("Done", null).create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            dialog.window?.setDimAmount(0.55f)
        }
        dialog.show()
    }

    private fun showFolderRename(folder: OeaWorkspaceStore.Folder) {
        val activity = hostActivity ?: run {
            Toast.makeText(context, "OEA Home is not ready for folder editing.", Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(activity).apply { setSingleLine(true); setText(folder.title); setSelection(text.length) }
        AlertDialog.Builder(activity).setTitle("Rename folder").setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val title = input.text.toString().trim().ifBlank { "Folder" }
                ws.replaceFolders(ws.folders().map { if (it.id == folder.id) it.copy(title = title) else it })
                refreshPages(folder.page)
            }.show()
    }

    private fun dockTile(app: OeaAppInfo) = FrameLayout(context).apply {
        isClickable = true
        foreground = null
        background = ColorDrawable(Color.TRANSPARENT)
        contentDescription = "Open " + app.label
        setOnClickListener { launch(app) }
        addView(ImageView(context).apply {
            setImageDrawable(icon(app.packageName))
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(0, 0, 0, 0)
            adjustViewBounds = true
        }, FrameLayout.LayoutParams(dp(42), dp(42), Gravity.CENTER))
    }

    private fun renderDock() {
        dock.removeAllViews()
        val values = ws.dock()
        val page = ws.currentDockPage().coerceAtMost(ws.dockPageCount() - 1)
        if (page != ws.currentDockPage()) ws.setCurrentDockPage(page)
        val pageValues = ws.dockPageValues(page)
        repeat(OeaWorkspaceStore.DOCK_SLOTS) { slot ->
            val key = pageValues.getOrNull(slot)
            val app = key?.let(::find)
            val v = if (app == null) emptyCell() else dockTile(app)
            if (app != null) v.setOnLongClickListener {
                dragged = key
                v.startDragAndDrop(
                    ClipData.newPlainText(ClipDescription.MIMETYPE_TEXT_PLAIN, key),
                    View.DragShadowBuilder(v),
                    key,
                    View.DRAG_FLAG_GLOBAL,
                )
                true
            }
            v.setOnDragListener { _, e ->
                if (e.action == DragEvent.ACTION_DROP && dragged != null) {
                    val draggedKey = dragged!!
                    val fromDock = values.contains(draggedKey)
                    val list = values.filterNot { it == draggedKey }.toMutableList()
                    val targetIndex = (page * OeaWorkspaceStore.DOCK_SLOTS + slot)
                        .coerceIn(0, list.size)
                    list.add(targetIndex, draggedKey)
                    ws.setDock(list)

                    if (!fromDock) {
                        val affectedPage = ws.items().firstOrNull { it.id == draggedKey }?.page
                        ws.replaceItems(ws.items().filterNot { it.id == draggedKey })
                        ws.replaceFolders(
                            ws.folders()
                                .map { it.copy(members = it.members.filterNot { member -> member == draggedKey }) }
                                .filter { it.members.isNotEmpty() }
                        )
                        if (affectedPage != null) refreshPages(affectedPage)
                    }

                    dragged = null
                    // If the insertion pushed past the current page, show the page
                    // containing the dropped app immediately instead of leaving it off-screen.
                    val dockPage = ws.dock().indexOf(draggedKey)
                        .coerceAtLeast(0) / OeaWorkspaceStore.DOCK_SLOTS
                    ws.setCurrentDockPage(dockPage)
                    renderDock()
                    true
                } else e.action == DragEvent.ACTION_DRAG_STARTED
            }
            dock.addView(v, GridLayout.LayoutParams().apply {
                width = 0
                height = dp(68)
                columnSpec = GridLayout.spec(slot, 1, 1f)
            })
        }
        dockIndicator.text = if (ws.dockPageCount() > 1)
            List(ws.dockPageCount()) { if (it == page) "●" else "•" }.joinToString(" ")
        else ""
        dockIndicator.contentDescription = if (ws.dockPageCount() > 1)
            "Dock page " + (page + 1) + " of " + ws.dockPageCount() + ". Tap to switch pages."
        else "Dock"
        dock.contentDescription = "Dock page " + (page + 1) + " of " + ws.dockPageCount()
    }

    private fun nextPage() {
        val target = (ws.getCurrentPage() + 1).coerceAtMost(ws.pages() - 1)
        if (target == ws.getCurrentPage()) return
        ws.setCurrentPage(target)
        pager.post { ensurePageRendered(target); pager.smoothScrollTo(target * pager.width, 0) }
        dots.text = List(ws.pages()) { if (it == target) "●" else "•" }.joinToString(" ")
    }

    private fun previousPage() {
        val target = (ws.getCurrentPage() - 1).coerceAtLeast(0)
        if (target == ws.getCurrentPage()) return
        ws.setCurrentPage(target)
        pager.post { ensurePageRendered(target); pager.smoothScrollTo(target * pager.width, 0) }
        dots.text = List(ws.pages()) { if (it == target) "●" else "•" }.joinToString(" ")
    }

    private fun openDrawer() {
        drawerOpen = true
        homeRoot.visibility = View.GONE
        drawer.visibility = View.VISIBLE
        drawerSearch.setText(search.text.toString())
        drawerSearch.setSelection(drawerSearch.text.length)
        renderDrawer(drawerSearch.text.toString())
        drawerSearch.requestFocus()
    }

    private fun closeDrawer() {
        drawerOpen = false
        drawer.visibility = View.GONE
        homeRoot.visibility = View.VISIBLE
        search.setText("")
        drawerSearch.setText("")
        search.clearFocus()
        drawerSearch.clearFocus()
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(windowToken, 0)
    }

    private fun iconShapeBackground(): Drawable = GradientDrawable().apply {
        val radius = when (store.iconShape()) {
            "circle" -> 999f
            "square" -> 4f
            else -> 14f
        }
        setColor(themeSurface)
        cornerRadius = dp(radius.toInt()).toFloat()
    }

    private fun openIconShapeSettings() {
        val values = arrayOf("Rounded", "Circle", "Square")
        val current = when (store.iconShape()) { "circle" -> 1; "square" -> 2; else -> 0 }
        AlertDialog.Builder(hostActivity ?: context).setTitle("Icon shape")
            .setSingleChoiceItems(values, current) { dialog, which ->
                store.setIconShape(when (which) { 1 -> "circle"; 2 -> "square"; else -> "rounded" })
                rebuild()
                renderDrawer(drawerSearch.text.toString())
                dialog.dismiss()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun openWallpaperChooser() {
        val choices = arrayOf("Choose from gallery", "Use current system wallpaper", "Remove OEA wallpaper")
        AlertDialog.Builder(hostActivity ?: context).setTitle("OEA Wallpaper").setItems(choices) { _, which ->
            when (which) {
                0 -> runCatching {
                    hostActivity?.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "image/*"
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                    }, wallpaperRequestCode) ?: throw IllegalStateException("OEA Home activity unavailable")
                }.onFailure {
                    Toast.makeText(context, "Image picker unavailable.", Toast.LENGTH_SHORT).show()
                }
                1 -> {
                    store.setWallpaperUri(null)
                    loadOeaWallpaper(true)
                }
                2 -> {
                    store.setWallpaperUri(null)
                    wallpaperView.setImageDrawable(null)
                    wallpaperLightHint = null
                    applyThemeFromWallpaper()
                    rebuild()
                }
            }
        }.setNegativeButton("Cancel", null).show()
    }

    private fun openHiddenAppsSettings() {
        val hiddenApps = apps.filter { store.isHidden(it.packageName, it.className) }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        box.addView(TextView(context).apply {
            text = if (hiddenApps.isEmpty()) "No hidden apps." else "Hidden apps (" + hiddenApps.size + ")"
            textSize = 16f
            setTextColor(themeText)
        })
        hiddenApps.forEach { app ->
            box.addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6), dp(4), dp(6), dp(4))
                addView(ImageView(context).apply {
                    setImageDrawable(icon(app.packageName))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }, LinearLayout.LayoutParams(dp(42), dp(42)))
                addView(TextView(context).apply {
                    text = app.label
                    textSize = 15f
                    setTextColor(themeText)
                }, LinearLayout.LayoutParams(0, dp(48), 1f))
                addView(Button(context).apply {
                    text = "Unhide"
                    setOnClickListener {
                        store.setHidden(app.packageName, app.className, false)
                        rebuild()
                        renderDrawer(drawerSearch.text.toString())
                        openHiddenAppsSettings()
                    }
                }, LinearLayout.LayoutParams(-2, dp(48)))
            }, LinearLayout.LayoutParams(-1, dp(52)))
        }
        AlertDialog.Builder(hostActivity ?: context).setTitle("Hidden apps").setView(box)
            .setPositiveButton("Done", null).show()
    }

    private fun openFreezerSettings() {
        val choices = apps.filterNot { it.packageName == context.packageName }
        val frozen = OeaAppFreezer.frozenPackages(context)
        val checked = BooleanArray(choices.size) { frozen.contains(choices[it].packageName) }
        AlertDialog.Builder(hostActivity ?: context).setTitle("App Freezer")
            .setMultiChoiceItems(choices.map { app ->
                if (frozen.contains(app.packageName)) "❄ " + app.label + "  •  FROZEN" else app.label
            }.toTypedArray(), checked) { _, which, value ->
                val result = OeaAppFreezer.setFrozen(context, choices[which].packageName, value)
                if (!result.success) Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                else checked[which] = value
            }
            .setNeutralButton("Authority") { _, _ -> openDeviceAdminSettings() }
            .setPositiveButton("Done", null).show()
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
