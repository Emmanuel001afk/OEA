package app.lawnchair.oea.workspace

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.*
import app.lawnchair.oea.data.OeaDataStore
import app.lawnchair.oea.drawer.OeaAppDrawerController
import app.lawnchair.oea.folders.OeaFolderController
import app.lawnchair.oea.icons.OeaIconController
import app.lawnchair.oea.interaction.OeaGestureController
import app.lawnchair.oea.model.OeaAppInfo
import app.lawnchair.oea.shortcuts.OeaShortcutController
import kotlin.math.roundToInt

/**
 * OEA-owned HOME renderer. Pages, cells, dock and folders are OEA state; Launcher3 is not used
 * to create or render this surface.
 */
class OeaWorkspace(context: Context) : FrameLayout(context) {
    private val store = OeaDataStore.get(context)
    private val ws = OeaWorkspaceStore.get(context)
    private val pages = LinearLayout(context)
    private val pager = HorizontalScrollView(context)
    private val dock = GridLayout(context)
    private val drawer = ScrollView(context)
    private val drawerGrid = GridLayout(context)
    private val search = EditText(context)
    private val dots = TextView(context)
    private var apps: List<OeaAppInfo> = emptyList()
    private val drawerController = OeaAppDrawerController()
    private val folderController = OeaFolderController()
    private val iconController = OeaIconController(context)
    private val shortcutController = OeaShortcutController(context)
    private var drawerOpen = false
    private var dragged: String? = null

    init {
        setBackgroundColor(Color.rgb(12, 15, 21))
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }
        addView(root, FrameLayout.LayoutParams(-1, -1))

        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(context).apply {
            text = "OEA"
            textSize = 28f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, dp(52), 1f))
        header.addView(TextView(context).apply {
            text = "⋮"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(38, 44, 57), 18)
            setOnClickListener { menu(it) }
        }, LinearLayout.LayoutParams(dp(52), dp(52)))
        root.addView(header)

        search.hint = "Search apps"
        search.setSingleLine(true)
        search.textSize = 16f
        search.setTextColor(Color.WHITE)
        search.setHintTextColor(Color.rgb(154, 162, 177))
        search.setPadding(dp(18), 0, dp(18), 0)
        search.background = rounded(Color.rgb(30, 36, 49), 28)
        search.setOnFocusChangeListener { _, focus -> if (focus) openDrawer() }
        search.addTextChangedListener(Watcher { renderDrawer(it) })
        root.addView(search, LinearLayout.LayoutParams(-1, dp(54)).apply { bottomMargin = dp(8) })

        pager.isHorizontalScrollBarEnabled = false
        pager.setOnTouchListener(OeaGestureController(pager, onSwipeUp = { openDrawer() }, onSwipeDown = { closeDrawer() }))
        pager.setOnScrollChangeListener { _, scrollX, _, _, _ ->
            val pageWidth = width.coerceAtLeast(1)
            val current = (scrollX.toFloat() / pageWidth).roundToInt().coerceIn(0, ws.pages() - 1)
            ws.setCurrentPage(current)
            dots.text = List(ws.pages()) { if (it == current) "●" else "•" }.joinToString(" ")
        }
        pages.orientation = LinearLayout.HORIZONTAL
        pager.addView(pages, HorizontalScrollView.LayoutParams(-2, -1))
        root.addView(pager, LinearLayout.LayoutParams(-1, 0, 1f))

        dots.gravity = Gravity.CENTER
        dots.setTextColor(Color.rgb(160, 169, 185))
        root.addView(dots, LinearLayout.LayoutParams(-1, dp(22)))

        root.addView(TextView(context).apply {
            text = "DOCK"
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(130, 140, 158))
        }, LinearLayout.LayoutParams(-1, dp(18)))
        dock.columnCount = OeaWorkspaceStore.DOCK_SLOTS
        dock.setPadding(dp(4), dp(2), dp(4), dp(2))
        dock.setOnDragListener { _, e -> dockDrop(e) }
        root.addView(dock, LinearLayout.LayoutParams(-1, dp(78)))

        root.addView(TextView(context).apply {
            text = "All apps"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(30, 36, 49), 22)
            setOnClickListener { openDrawer() }
        }, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(4) })

        drawer.setBackgroundColor(Color.rgb(12, 15, 21))
        drawerGrid.columnCount = cols()
        drawer.addView(drawerGrid, ScrollView.LayoutParams(-1, -2))
        addView(drawer, FrameLayout.LayoutParams(-1, -1))
        drawer.visibility = View.GONE
        post { rebuild() }
    }

    fun bind(value: List<OeaAppInfo>) {
        apps = value
        ws.ensureSeeded(apps.map { Triple(it.packageName, it.className, it.label) })
        ws.clearMissing(apps.map { OeaWorkspaceStore.key(it.packageName, it.className) }.toSet())
        rebuild()
        renderDrawer(search.text.toString())
    }

    private fun rebuild() {
        pages.removeAllViews()
        val pageWidth = width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        for (p in 0 until ws.pages()) {
            val grid = GridLayout(context).apply {
                columnCount = cols()
                useDefaultMargins = false
                setPadding(dp(3), dp(6), dp(3), dp(6))
                setOnDragListener(pageDrop(p))
            }
            pages.addView(grid, LinearLayout.LayoutParams(pageWidth, -1))
            renderPage(grid, p)
        }
        dots.text = "• ".repeat(ws.pages()).trim()
        renderDock()
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
                height = dp(96)
                columnSpec = GridLayout.spec(cell % cols(), 1, 1f)
                rowSpec = GridLayout.spec(cell / cols())
                setMargins(dp(3), dp(3), dp(3), dp(3))
            })
        }
    }

    private fun homeTile(item: OeaWorkspaceStore.Item): View {
        val app = find(item.id) ?: return emptyCell()
        return tile(app).apply {
            setOnLongClickListener {
                showAppActions(app, this, item.id)
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
        val popup = PopupMenu(context, anchor)
        popup.menu.add(0, 1, 0, "Open")
        popup.menu.add(0, 2, 1, "Drag to place")
        val shortcuts = shortcutController.shortcuts(app.packageName)
        shortcuts.take(5).forEachIndexed { index, shortcut ->
            popup.menu.add(0, 1000 + index, index + 1, shortcut.shortLabel ?: shortcut.longLabel ?: "Shortcut")
        }
        popup.setOnMenuItemClickListener { item ->
            if (item.itemId == 1) {
                launch(app)
                true
            } else if (item.itemId == 2) {
                dragged = itemId
                startDragAndDrop(ClipData.newPlainText(ClipDescription.MIMETYPE_TEXT_PLAIN, itemId), View.DragShadowBuilder(anchor), itemId, View.DRAG_FLAG_GLOBAL)
                true
            } else if (item.itemId >= 1000) {
                shortcuts.getOrNull(item.itemId - 1000)?.let {
                    runCatching { context.startActivity(it.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
                true
            } else false
        }
        popup.show()
    }

    private fun folderTile(folder: OeaWorkspaceStore.Folder): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = rounded(Color.rgb(30, 36, 49), 18)
            setOnClickListener { openFolder(folder) }
            addView(TextView(context).apply {
                text = "▦"
                textSize = 30f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
            }, LinearLayout.LayoutParams(dp(54), dp(54)))
            addView(TextView(context).apply {
                text = folder.title
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
            }, LinearLayout.LayoutParams(-1, dp(28)))
        }

    private fun emptyCell() = TextView(context).apply {
        setOnLongClickListener { menu(this); true }
    }

    private fun tile(app: OeaAppInfo) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        isClickable = true
        background = rounded(Color.rgb(30, 36, 49), 18)
        foreground = selectable()
        contentDescription = "Open " + app.label
        setOnClickListener { launch(app) }
        addView(ImageView(context).apply {
            setImageDrawable(icon(app.packageName))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(dp(50), dp(52)))
        if (store.showAppLabels()) addView(TextView(context).apply {
            text = app.label
            textSize = 11f
            gravity = Gravity.CENTER
            maxLines = 2
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(-1, dp(32)))
    }

    private fun pageDrop(page: Int) = View.OnDragListener { view, e ->
        when (e.action) {
            DragEvent.ACTION_DRAG_STARTED -> true
            DragEvent.ACTION_DROP -> {
                val key = dragged ?: return@OnDragListener true
                val grid = view as GridLayout
                val cw = (grid.width / cols()).coerceAtLeast(1)
                val col = (e.x / cw).toInt().coerceIn(0, cols() - 1)
                val row = (e.y / dp(96)).toInt().coerceAtLeast(0)
                move(key, page, row * cols() + col)
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
        ws.replaceItems(ws.items().filterNot { it.id == key })
        dragged = null
        rebuild()
        return true
    }

    private fun move(key: String, page: Int, cell: Int) {
        val all = ws.items().toMutableList()
        val moving = all.firstOrNull { it.id == key } ?: return
        val collision = all.firstOrNull { it.id != key && it.page == page && it.cell == cell && it.folderId == null }
        if (collision != null) {
            val next = firstFree(all, page, cell + 1)
            all[all.indexOf(collision)] = collision.copy(page = page, cell = next)
        }
        all[all.indexOf(moving)] = moving.copy(page = page, cell = cell, folderId = null)
        ws.replaceItems(all)
        ws.setDock(ws.dock().filterNot { it == key })
        dragged = null
        rebuild()
    }

    private fun folder(target: String, source: String) {
        val t = ws.items().firstOrNull { it.id == target } ?: return
        val folders = ws.folders().toMutableList()
        val existing = folders.firstOrNull { it.page == t.page && it.cell == t.cell }
        if (existing == null) folders.add(OeaWorkspaceStore.Folder("folder-" + System.currentTimeMillis(), "Folder", t.page, t.cell, listOf(target, source)))
        else folders[folders.indexOf(existing)] = existing.copy(members = folderController.mergeMembers(existing.members, source))
        ws.replaceFolders(folders)
        ws.replaceItems(ws.items().filterNot { it.id == target || it.id == source })
        ws.setDock(ws.dock().filterNot { it == source || it == target })
        dragged = null
        rebuild()
    }

    private fun openFolder(folder: OeaWorkspaceStore.Folder) {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        box.addView(TextView(context).apply {
            text = folder.title
            textSize = 20f
            setTextColor(Color.WHITE)
        })
        val grid = GridLayout(context).apply { columnCount = 4 }
        folder.members.mapNotNull(::find).forEach { grid.addView(tile(it), GridLayout.LayoutParams().apply {
            width = dp(78); height = dp(90)
        }) }
        box.addView(grid)
        AlertDialog.Builder(context).setView(box).setPositiveButton("Done", null).show()
    }

    private fun showFolderRename(folder: OeaWorkspaceStore.Folder) {
        val input = EditText(context).apply {
            setSingleLine(true)
            setText(folder.title)
            setSelection(text.length)
        }
        AlertDialog.Builder(context)
            .setTitle("Rename folder")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val title = input.text.toString().trim().ifBlank { "Folder" }
                ws.replaceFolders(ws.folders().map { if (it.id == folder.id) it.copy(title = title) else it })
                rebuild()
            }
            .show()
    }

    private fun renderDock() {
        dock.removeAllViews()
        val values = ws.dock()
        repeat(OeaWorkspaceStore.DOCK_SLOTS) { slot ->
            val app = values.getOrNull(slot)?.let(::find)
            val v = if (app == null) emptyCell() else tile(app)
            if (app != null) {
                v.setOnLongClickListener {
                    dragged = values[slot]
                    startDragAndDrop(
                        ClipData.newPlainText(ClipDescription.MIMETYPE_TEXT_PLAIN, values[slot]),
                        View.DragShadowBuilder(v), values[slot], View.DRAG_FLAG_GLOBAL,
                    )
                    true
                }
            }
            v.setOnDragListener { _, e ->
                if (e.action == DragEvent.ACTION_DROP && dragged != null) {
                    val list = values.filterNot { it == dragged }.toMutableList()
                    while (list.size < slot) list.add("")
                    list.add(slot, dragged!!)
                    ws.setDock(list.filter { it.isNotBlank() })
                    ws.replaceItems(ws.items().filterNot { it.id == dragged })
                    dragged = null
                    rebuild()
                    true
                } else e.action == DragEvent.ACTION_DRAG_STARTED
            }
            dock.addView(v, GridLayout.LayoutParams().apply {
                width = 0; height = dp(70)
                columnSpec = GridLayout.spec(slot, 1, 1f)
            })
        }
    }

    private fun openDrawer() {
        drawerOpen = true
        pager.visibility = View.GONE
        dock.visibility = View.GONE
        drawer.visibility = View.VISIBLE
        renderDrawer(search.text.toString())
        search.requestFocus()
    }

    private fun renderDrawer(query: String) {
        if (!drawerOpen) return
        dragged = OeaWorkspaceStore.key(it.packageName, it.className).lowercase(Locale.ROOT)
        drawerGrid.removeAllViews()
        drawerController.filter(apps, q).filterNot { store.isHidden(it.packageName, it.className) }.forEach {
            val v = tile(it).apply {
                setOnLongClickListener {
                    dragged = OeaWorkspaceStore.key(it.packageName, it.className)
                    startDragAndDrop(
                        ClipData.newPlainText(ClipDescription.MIMETYPE_TEXT_PLAIN, dragged),
                        View.DragShadowBuilder(this), dragged, View.DRAG_FLAG_GLOBAL,
                    )
                    true
                }
            }
            drawerGrid.addView(v, GridLayout.LayoutParams().apply {
                width = 0; height = dp(96)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)
                setMargins(dp(3), dp(3), dp(3), dp(3))
            })
        }
    }

    private fun closeDrawer() {
        drawerOpen = false
        drawer.visibility = View.GONE
        pager.visibility = View.VISIBLE
        dock.visibility = View.VISIBLE
        search.clearFocus()
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(search.windowToken, 0)
    }

    private fun menu(anchor: View) {
        PopupMenu(context, anchor).apply {
            menu.add("Add page")
            if (ws.pages() > 1) menu.add("Remove last page")
            menu.add("Grid: 3 columns")
            menu.add("Grid: 4 columns")
            menu.add("Grid: 5 columns")
            menu.add("Show labels")
            setOnMenuItemClickListener {
                when (it.title.toString()) {
                    "Add page" -> { ws.setPages(ws.pages() + 1); rebuild(); true }
                    "Remove last page" -> { removeLastPage(); true }
                    "Grid: 3 columns" -> { store.setGridColumns(3); rebuild(); true }
                    "Grid: 4 columns" -> { store.setGridColumns(4); rebuild(); true }
                    "Grid: 5 columns" -> { store.setGridColumns(5); rebuild(); true }
                    "Show labels" -> { store.setShowAppLabels(!store.showAppLabels()); rebuild(); true }
                    else -> false
                }
            }
            show()
        }
    }

    private fun removeLastPage() {
        val last = ws.pages() - 1
        ws.replaceItems(ws.items().filter { it.page != last })
        ws.replaceFolders(ws.folders().filter { it.page != last })
        ws.setPages(last)
        rebuild()
    }

    private fun find(key: String) = apps.firstOrNull { OeaWorkspaceStore.key(it.packageName, it.className) == key }

    private fun firstFree(items: List<OeaWorkspaceStore.Item>, page: Int, start: Int): Int {
        var n = start
        while (items.any { it.page == page && it.cell == n && it.folderId == null }) n++
        return n
    }

    private fun launch(app: OeaAppInfo) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = ComponentName(app.packageName, app.className)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            store.recordLaunch(app.packageName, app.className)
        }.onFailure { Toast.makeText(context, "Unable to open " + app.label, Toast.LENGTH_SHORT).show() }
    }

    private fun icon(pkg: String): Drawable? {
        return iconController.icon(pkg)
    }

    private fun selectable(): Drawable? =
        context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).let { a ->
            a.getDrawable(0).also { a.recycle() }
        }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }

    private fun cols() = store.gridColumns().coerceIn(3, 5)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    fun handleBack(): Boolean {
        if (drawerOpen) { closeDrawer(); return true }
        if (search.text.isNotEmpty()) { search.text.clear(); return true }
        return true
    }

    private class Watcher(private val change: (String) -> Unit) : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { change(s?.toString().orEmpty()) }
        override fun afterTextChanged(s: android.text.Editable?) = Unit
    }
}
