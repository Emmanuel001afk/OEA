package com.oea.launcher.workspace

import android.app.AlertDialog
import android.app.WallpaperManager
import android.content.ClipData
import android.content.ClipDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
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
import com.oea.launcher.interaction.OeaGestureController
import com.oea.launcher.model.OeaAppInfo
import com.oea.launcher.notifications.OeaNotificationState
import com.oea.launcher.shortcuts.OeaShortcutController
import com.oea.launcher.applock.OeaAppFreezer
import com.oea.launcher.applock.OeaDeviceAdminReceiver
import com.oea.launcher.callblocker.OeaCallBlockRules
import com.oea.launcher.gameboost.OeaGameBoostService
import com.oea.launcher.gameboost.OeaGameBoostStore
import com.oea.launcher.split.OeaSplitLauncher
import kotlin.math.roundToInt

class OeaWorkspace(context: Context) : FrameLayout(context) {
    private val store = OeaDataStore.get(context)
    private val ws = OeaWorkspaceStore.get(context)
    private val pages = LinearLayout(context)
    private val pager = HorizontalScrollView(context)
    private val dock = GridLayout(context)
    private val drawer = LinearLayout(context)
    private val drawerScroll = ScrollView(context)
    private val drawerBody = FrameLayout(context)
    private val drawerGrid = GridLayout(context)
    private val drawerSearch = EditText(context)
    private val search = EditText(context)
    private val dots = TextView(context)
    private var apps: List<OeaAppInfo> = emptyList()
    private val drawerController = OeaAppDrawerController()
    private val folderController = OeaFolderController()
    private val iconController = OeaIconController(context)
    private val shortcutController = OeaShortcutController(context)
    private var drawerOpen = false
    private var dragged: String? = null
    private var themeBackground = Color.rgb(12, 15, 21)
    private var themeSurface = Color.rgb(30, 36, 49)
    private var themeText = Color.WHITE
    private var themeMuted = Color.rgb(154, 162, 177)

    init {
        setBackgroundColor(themeBackground)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }
        addView(root, FrameLayout.LayoutParams(-1, -1))

        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(context).apply {
            text = "OEA"
            textSize = 28f
            setTextColor(themeText)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, dp(52), 1f))
        header.addView(TextView(context).apply {
            text = "⋮"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(themeText)
            background = rounded(themeSurface, 18)
            setOnClickListener { menu(it) }
        }, LinearLayout.LayoutParams(dp(52), dp(52)))
        root.addView(header)

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
        pager.setOnTouchListener(OeaGestureController(
            pager,
            onSwipeUp = { openDrawer() },
            onSwipeDown = { closeDrawer() },
        ))
        pages.orientation = LinearLayout.HORIZONTAL
        pager.addView(pages, FrameLayout.LayoutParams(-2, -1))
        root.addView(pager, LinearLayout.LayoutParams(-1, 0, 1f))
        dots.gravity = Gravity.CENTER
        dots.setTextColor(themeMuted)
        root.addView(dots, LinearLayout.LayoutParams(-1, dp(22)))
        root.addView(TextView(context).apply {
            text = "DOCK"
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(themeMuted)
        }, LinearLayout.LayoutParams(-1, dp(18)))
        dock.columnCount = OeaWorkspaceStore.DOCK_SLOTS
        dock.setPadding(dp(4), dp(2), dp(4), dp(2))
        dock.setOnDragListener { _, e -> dockDrop(e) }
        root.addView(dock, LinearLayout.LayoutParams(-1, dp(78)))
        root.addView(TextView(context).apply {
            text = "All apps"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(themeText)
            background = rounded(themeSurface, 22)
            setOnClickListener { openDrawer() }
        }, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(4) })

        buildDrawer()
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
        }, LinearLayout.LayoutParams(0, dp(52), 1f))
        header.addView(TextView(context).apply {
            text = "Layout"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(themeText)
            background = rounded(themeSurface, 18)
            setOnClickListener { drawerLayoutMenu(it) }
        }, LinearLayout.LayoutParams(dp(82), dp(44)))
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
        ws.ensureSeeded(apps.map { Triple(it.packageName, it.className, it.label) })
        ws.clearMissing(apps.map { OeaWorkspaceStore.key(it.packageName, it.className) }.toSet())
        rebuild()
        renderDrawer(drawerSearch.text.toString())
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
        dots.text = List(ws.pages()) { if (it == ws.getCurrentPage()) "●" else "•" }.joinToString(" ")
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
            setOnLongClickListener { showAppActions(app, this, item.id); true }
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
        popup.menu.add("Open")
        if (!ws.dock().contains(itemId)) popup.menu.add("Add to dock")
        if (!ws.items().any { it.id == itemId && it.folderId == null }) popup.menu.add("Add to home")
        if (ws.items().any { it.id == itemId } || ws.dock().contains(itemId)) popup.menu.add("Remove from home")
        popup.menu.add("App info")
        popup.menu.add(if (store.isHidden(app.packageName, app.className)) "Unhide app" else "Hide app")
        popup.menu.add("Freeze / unfreeze")
        popup.menu.add("Drag to place")
        shortcutController.shortcuts(app.packageName).take(5).forEach { shortcut ->
            popup.menu.add(shortcut.shortLabel ?: shortcut.longLabel ?: "Shortcut")
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.title.toString()) {
                "Open" -> { launch(app); true }
                "Add to dock" -> { addToDock(itemId); true }
                "Add to home" -> { addToHome(itemId); true }
                "Remove from home" -> { removeFromHome(itemId); true }
                "App info" -> { openAppInfo(app.packageName); true }
                "Hide app" -> { store.setHidden(app.packageName, app.className, true); removeFromHome(itemId); renderDrawer(drawerSearch.text.toString()); true }
                "Unhide app" -> { store.setHidden(app.packageName, app.className, false); renderDrawer(drawerSearch.text.toString()); true }
                "Freeze / unfreeze" -> { freezeDialog(app); true }
                "Drag to place" -> {
                    dragged = itemId
                    startDragAndDrop(
                        ClipData.newPlainText(ClipDescription.MIMETYPE_TEXT_PLAIN, itemId),
                        View.DragShadowBuilder(anchor), itemId, View.DRAG_FLAG_GLOBAL,
                    )
                    true
                }
                else -> {
                    val shortcut = shortcutController.shortcuts(app.packageName)
                        .firstOrNull { (it.shortLabel ?: it.longLabel ?: "Shortcut") == item.title.toString() }
                    runCatching { shortcut?.intent?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
                    true
                }
            }
        }
        popup.show()
    }

    private fun addToDock(key: String) {
        if (ws.dock().contains(key)) return
        val values = ws.dock().filterNot { it == key }.toMutableList()
        if (values.size >= OeaWorkspaceStore.DOCK_SLOTS) {
            Toast.makeText(context, "Dock is full — drag an existing icon out first.", Toast.LENGTH_SHORT).show()
            return
        }
        values.add(0, key)
        ws.setDock(values)
        ws.replaceItems(ws.items().filterNot { it.id == key })
        rebuild()
    }

    private fun addToHome(key: String) {
        if (ws.items().any { it.id == key }) return
        val app = find(key) ?: return
        val items = ws.items().toMutableList()
        val cell = firstFree(items, ws.getCurrentPage(), 0)
        items.add(OeaWorkspaceStore.Item(key, app.packageName, app.className, ws.getCurrentPage(), cell))
        ws.replaceItems(items)
        rebuild()
    }

    private fun removeFromHome(key: String) {
        ws.replaceItems(ws.items().filterNot { it.id == key })
        ws.setDock(ws.dock().filterNot { it == key })
        ws.replaceFolders(ws.folders().map { it.copy(members = it.members.filterNot { m -> m == key }) }.filter { it.members.isNotEmpty() })
        rebuild()
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
        AlertDialog.Builder(context)
            .setTitle("App Freezer")
            .setMessage("Freeze or unfreeze " + app.label + ". OEA must be device owner for real package suspension.")
            .setPositiveButton("Freeze") { _, _ ->
                val result = OeaAppFreezer.setFrozen(context, app.packageName, true)
                if (!result.success) Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
            }
            .setNeutralButton("Unfreeze") { _, _ ->
                val result = OeaAppFreezer.setFrozen(context, app.packageName, false)
                if (!result.success) Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Authority") { _, _ -> openDeviceAdminSettings() }
            .show()
    }

    private fun openDeviceAdminSettings() {
        runCatching {
            context.startActivity(
                Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(
                        android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                        ComponentName(context, OeaDeviceAdminReceiver::class.java),
                    )
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun openThemeSettings() {
        runCatching { context.startActivity(Intent(Intent.ACTION_SET_WALLPAPER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun openSystemsSettings() {
        val choices = arrayOf("Call blocker", "Split pair", "Game Boost", "App freezer authority")
        AlertDialog.Builder(context).setTitle("OEA Systems").setItems(choices) { _, which ->
            when (which) {
                0 -> openCallBlockerSettings()
                1 -> openSplitPairDialog()
                2 -> openGameBoostSettings()
                3 -> openDeviceAdminSettings()
            }
        }.show()
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
        AlertDialog.Builder(context)
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

    private fun openSplitPairDialog() {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        val first = EditText(context).apply { hint = "First package name"; setSingleLine(true) }
        val second = EditText(context).apply { hint = "Second package name"; setSingleLine(true) }
        box.addView(first)
        box.addView(second, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        AlertDialog.Builder(context)
            .setTitle("OEA Split pair")
            .setMessage("OEA launches both activities with adjacent/multi-task flags. Android decides the final split presentation.")
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Launch") { _, _ ->
                if (!OeaSplitLauncher.launchPair(context, first.text.toString().trim(), second.text.toString().trim())) {
                    Toast.makeText(context, "Could not launch both apps", Toast.LENGTH_LONG).show()
                }
            }.show()
    }

    private fun openGameBoostSettings() {
        val input = EditText(context).apply {
            hint = "Game package name"
            setSingleLine(true)
        }
        AlertDialog.Builder(context)
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
        PopupMenu(context, anchor).apply {
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
        when (store.drawerMode()) {
            OeaDataStore.DrawerMode.VERTICAL -> {
                val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                visible.forEach { app ->
                    val row = drawerRow(app)
                    list.addView(row, LinearLayout.LayoutParams(-1, dp(64)).apply { bottomMargin = dp(4) })
                }
                drawerBody.addView(list)
            }
            OeaDataStore.DrawerMode.HORIZONTAL -> {
                val horizontal = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
                val strip = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                visible.forEach { app ->
                    strip.addView(tile(app), LinearLayout.LayoutParams(dp(84), dp(92)).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
                }
                horizontal.addView(strip, FrameLayout.LayoutParams(-2, -2))
                drawerBody.addView(horizontal, FrameLayout.LayoutParams(-1, -2))
            }
            OeaDataStore.DrawerMode.GRID -> {
                drawerGrid.removeAllViews()
                drawerGrid.columnCount = cols()
                visible.forEach { app ->
                    drawerGrid.addView(tile(app).apply {
                        setOnLongClickListener { showAppActions(app, this, OeaWorkspaceStore.key(app.packageName, app.className)); true }
                    }, GridLayout.LayoutParams().apply {
                        width = 0
                        height = dp(92)
                        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)
                        setMargins(dp(3), dp(3), dp(3), dp(3))
                    })
                }
                drawerBody.addView(drawerGrid, FrameLayout.LayoutParams(-1, -2))
            }
        }
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
        background = rounded(themeSurface, 18)
        setOnClickListener { openFolder(folder) }
        setOnLongClickListener { showFolderRename(folder); true }
        addView(TextView(context).apply {
            text = "▦"
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(themeText)
        }, LinearLayout.LayoutParams(dp(54), dp(54)))
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
        background = rounded(themeSurface, 18)
        foreground = selectable()
        contentDescription = "Open " + app.label
        setOnClickListener { launch(app) }
        addView(FrameLayout(context).apply {
            val iconView = ImageView(context).apply {
                setImageDrawable(icon(app.packageName))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            addView(iconView, FrameLayout.LayoutParams(dp(44), dp(46), Gravity.CENTER))
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
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        if (store.showAppLabels()) addView(TextView(context).apply {
            text = app.label
            textSize = 10.5f
            gravity = Gravity.CENTER
            maxLines = 2
            setTextColor(themeText)
        }, LinearLayout.LayoutParams(-1, dp(30)))
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
        if (existing == null) folders.add(OeaWorkspaceStore.Folder(
            "folder-" + System.currentTimeMillis(), "Folder", t.page, t.cell, listOf(target, source)
        )) else folders[folders.indexOf(existing)] = existing.copy(members = folderController.mergeMembers(existing.members, source))
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
        box.addView(TextView(context).apply { text = folder.title; textSize = 20f; setTextColor(themeText) })
        val grid = GridLayout(context).apply { columnCount = 4 }
        folder.members.mapNotNull(::find).forEach { grid.addView(tile(it), GridLayout.LayoutParams().apply { width = dp(78); height = dp(90) }) }
        box.addView(grid)
        AlertDialog.Builder(context).setView(box).setPositiveButton("Done", null).show()
    }

    private fun showFolderRename(folder: OeaWorkspaceStore.Folder) {
        val input = EditText(context).apply { setSingleLine(true); setText(folder.title); setSelection(text.length) }
        AlertDialog.Builder(context).setTitle("Rename folder").setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val title = input.text.toString().trim().ifBlank { "Folder" }
                ws.replaceFolders(ws.folders().map { if (it.id == folder.id) it.copy(title = title) else it })
                rebuild()
            }.show()
    }

    private fun renderDock() {
        dock.removeAllViews()
        val values = ws.dock()
        repeat(OeaWorkspaceStore.DOCK_SLOTS) { slot ->
            val app = values.getOrNull(slot)?.let(::find)
            val v = if (app == null) emptyCell() else tile(app)
            if (app != null) {
                v.setOnLongClickListener {
                    showAppActions(app, v, values[slot])
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
                width = 0
                height = dp(70)
                columnSpec = GridLayout.spec(slot, 1, 1f)
            })
        }
    }

    private fun openDrawer() {
        drawerOpen = true
        pager.visibility = View.GONE
        dock.visibility = View.GONE
        drawer.visibility = View.VISIBLE
        drawerSearch.setText(search.text.toString())
        drawerSearch.setSelection(drawerSearch.text.length)
        renderDrawer(drawerSearch.text.toString())
        drawerSearch.requestFocus()
    }

    private fun closeDrawer() {
        drawerOpen = false
        drawer.visibility = View.GONE
        pager.visibility = View.VISIBLE
        dock.visibility = View.VISIBLE
        search.setText("")
        drawerSearch.setText("")
        search.clearFocus()
        drawerSearch.clearFocus()
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(windowToken, 0)
    }

    private fun openNotificationAccessSettings() {
        runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun menu(anchor: View) {
        PopupMenu(context, anchor).apply {
            menu.add("Add page")
            if (ws.pages() > 1) menu.add("Remove last page")
            menu.add("Grid: 3 columns")
            menu.add("Grid: 4 columns")
            menu.add("Grid: 5 columns")
            menu.add("Show / hide labels")
            menu.add("OEA Themes")
            menu.add("Notification access")
            menu.add("OEA Systems")
            setOnMenuItemClickListener {
                when (it.title.toString()) {
                    "Add page" -> { ws.setPages(ws.pages() + 1); rebuild() }
                    "Remove last page" -> removeLastPage()
                    "Grid: 3 columns" -> { store.setGridColumns(3); rebuild() }
                    "Grid: 4 columns" -> { store.setGridColumns(4); rebuild() }
                    "Grid: 5 columns" -> { store.setGridColumns(5); rebuild() }
                    "Show / hide labels" -> { store.setShowAppLabels(!store.showAppLabels()); rebuild() }
                    "OEA Themes" -> openThemeSettings()
                    "Notification access" -> openNotificationAccessSettings()
                    "OEA Systems" -> openSystemsSettings()
                }
                true
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

    private fun applyThemeFromWallpaper() {
        runCatching {
            val drawable = WallpaperManager.getInstance(context).drawable as? BitmapDrawable ?: return
            val bitmap = drawable.bitmap
            if (bitmap.width <= 0 || bitmap.height <= 0) return
            val pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
            val lum = (Color.red(pixel) * 0.299 + Color.green(pixel) * 0.587 + Color.blue(pixel) * 0.114)
            if (lum > 150) {
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
            setBackgroundColor(themeBackground)
            drawer.setBackgroundColor(themeBackground)
            search.setTextColor(themeText)
            search.setHintTextColor(themeMuted)
            search.background = rounded(themeSurface, 28)
            drawerSearch.setTextColor(themeText)
            drawerSearch.setHintTextColor(themeMuted)
            drawerSearch.background = rounded(themeSurface, 24)
            (drawer.getChildAt(0) as? LinearLayout)?.let { header ->
                header.setBackgroundColor(Color.TRANSPARENT)
                (header.getChildAt(0) as? TextView)?.setTextColor(themeText)
                (header.getChildAt(1) as? TextView)?.setTextColor(themeText)
                (header.getChildAt(1) as? TextView)?.background = rounded(themeSurface, 18)
            }
        }
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
        }.onFailure {
            Toast.makeText(context, "Unable to open " + app.label, Toast.LENGTH_SHORT).show()
        }
    }
    private fun icon(pkg: String): Drawable? = iconController.icon(pkg)
    private fun selectable(): Drawable? = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).let { a ->
        a.getDrawable(0).also { a.recycle() }
    }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }
    private fun cols() = store.gridColumns().coerceIn(3, 5)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

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
