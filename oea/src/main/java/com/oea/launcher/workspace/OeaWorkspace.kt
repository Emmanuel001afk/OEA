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
    private val drawerBody = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val drawerGrid = GridLayout(context)
    private val drawerSearch = EditText(context)
    private val search = EditText(context)
    private val dots = TextView(context)
    private val wallpaperView = ImageView(context)
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
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }
        addView(root, FrameLayout.LayoutParams(-1, -1))
        loadOeaWallpaper()

        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(ImageView(context).apply {
            setImageResource(R.drawable.oea_logo)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = "OEA"
        }, LinearLayout.LayoutParams(dp(50), dp(50)))
        header.addView(TextView(context).apply {
            text = "OEA"
            textSize = 28f
            setTextColor(themeText)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(6), 0, 0, 0)
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
            onSwipeLeft = { nextPage() },
            onSwipeRight = { previousPage() },
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
        root.addView(dock, LinearLayout.LayoutParams(-1, dp(64)))
        root.addView(TextView(context).apply {
            text = "All apps"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(themeText)
            background = rounded(themeSurface, 22)
            setOnClickListener { openDrawer() }
        }, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(4) })

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
        ws.ensureSeeded(apps.filterNot { it.packageName == context.packageName }.map { Triple(it.packageName, it.className, it.label) })
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
        renderWidgets()
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
        val popup = PopupMenu(context, anchor)
        popup.menu.add("Open")
        if (!ws.dock().contains(itemId)) popup.menu.add("Add to dock")
        if (!ws.items().any { it.id == itemId && it.folderId == null }) popup.menu.add("Add to home")
        if (ws.items().any { it.id == itemId } || ws.dock().contains(itemId)) popup.menu.add("Remove from home")
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
                "Add to home" -> { addToHome(itemId); true }
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
        ws.replaceFolders(ws.folders().map { it.copy(members = it.members.filterNot { m -> m == key }) }.filter { it.members.isNotEmpty() })
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
        AlertDialog.Builder(context).setTitle("Set OEA App Lock PIN").setView(input)
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
        AlertDialog.Builder(context).setTitle("Unlock " + app.label).setView(input)
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
        val dpm = context.getSystemService(android.app.admin.DevicePolicyManager::class.java)
        val owner = dpm?.isDeviceOwnerApp(context.packageName) == true
        val message = if (owner) {
            app.label + " can be frozen by OEA now."
        } else {
            app.label + " is not frozen yet. Android only permits true package suspension to a device-owner app. OEA will not repeatedly prompt for authority."
        }
        AlertDialog.Builder(context)
            .setTitle("App Freezer")
            .setMessage(message)
            .setPositiveButton(if (owner) "Freeze" else "Close") { _, _ ->
                if (owner) {
                    val result = OeaAppFreezer.setFrozen(context, app.packageName, true)
                    Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton(if (owner) "Unfreeze" else "How to enable") { _, _ ->
                if (owner) {
                    val result = OeaAppFreezer.setFrozen(context, app.packageName, false)
                    Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                } else {
                    openDeviceAdminSettings()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openDeviceAdminSettings() {
        val command = "adb shell dpm set-device-owner com.oea.launcher/com.oea.launcher.applock.OeaDeviceAdminReceiver"
        getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(
            android.content.ClipData.newPlainText("OEA device-owner command", command)
        )
        AlertDialog.Builder(context)
            .setTitle("Freezer authority")
            .setMessage("Android does not grant true package freezing through the normal Device Admin screen. OEA must be provisioned as device owner.\n\nADB setup command:\n$command\n\nThe command was copied to your clipboard.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun openThemeSettings() {
        val choices = arrayOf("System / Wallpaper", "Dark", "Light")
        val current = store.themeMode()
        val checked = when (current) { "dark" -> 1; "light" -> 2; else -> 0 }
        AlertDialog.Builder(context)
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
        AlertDialog.Builder(context)
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
        runCatching {
            (hostActivity ?: context).startActivity(Intent().setComponent(
                ComponentName(context, "com.oea.launcher.OeaSettings")
            ))
        }.onFailure {
            Toast.makeText(context, "OEA Settings could not be opened.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openPhone() {
        runCatching { (hostActivity ?: context).startActivity(Intent().setClassName(context, "com.oea.launcher.phone.OeaPhoneActivity")) }
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
        val choices = apps.filter { it.packageName != context.packageName }.distinctBy { it.packageName }
        val checked = BooleanArray(choices.size)
        AlertDialog.Builder(context)
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
                if (picked.size == 2 && !OeaSplitLauncher.launchPair(context, picked[0], picked[1]))
                    Toast.makeText(context, "Android could not start the pair in split screen.", Toast.LENGTH_LONG).show()
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
        if (query.isBlank()) renderFocusStrip()
        addSectionLabel(drawerBody, "Apps · " + visible.size)
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
                val pages = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                val rowsPerPage = 4
                visible.chunked(rowsPerPage * cols()).forEach { pageApps ->
                    val column = GridLayout(context).apply {
                        columnCount = cols()
                        rowCount = rowsPerPage
                        setPadding(dp(2), dp(2), dp(2), dp(2))
                    }
                    pageApps.forEachIndexed { index, app ->
                        column.addView(tile(app), GridLayout.LayoutParams().apply {
                            width = dp(78)
                            height = dp(88)
                            columnSpec = GridLayout.spec(index % cols())
                            rowSpec = GridLayout.spec(index / cols())
                            setMargins(dp(3), dp(3), dp(3), dp(3))
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
                visible.forEachIndexed { index, app ->
                    drawerGrid.addView(tile(app).apply {
                        setOnLongClickListener { showAppActions(app, this, OeaWorkspaceStore.key(app.packageName, app.className)); true }
                    }, GridLayout.LayoutParams().apply {
                        width = 0
                        height = dp(92)
                        columnSpec = GridLayout.spec(index % cols(), 1, 1f)
                        rowSpec = GridLayout.spec(index / cols())
                        setMargins(dp(3), dp(3), dp(3), dp(3))
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
        addSectionLabel(list, "Search providers")
        actions.forEach { action ->
            list.addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), 0, dp(14), 0)
                background = rounded(themeSurface, 16)
                addView(TextView(context).apply {
                    text = action.title
                    textSize = 14f
                    setTextColor(themeText)
                }, LinearLayout.LayoutParams(0, dp(56), 1f))
                addView(TextView(context).apply {
                    text = action.subtitle
                    textSize = 11f
                    setTextColor(themeMuted)
                }, LinearLayout.LayoutParams(-2, dp(56)))
                setOnClickListener {
                    runCatching { context.startActivity(action.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }, LinearLayout.LayoutParams(-1, dp(56)).apply { bottomMargin = dp(5) })
        }
        drawerBody.addView(list)
    }

    private fun renderFocusStrip() {
        val focused = focusStore.apps().mapNotNull(::find)
        val allKeys = apps.filterNot { store.isHidden(it.packageName, it.className) }
            .map { OeaWorkspaceStore.key(it.packageName, it.className) }
        val used = store.mostUsed(allKeys, 8).mapNotNull(::find)
        if (focused.isEmpty() && used.isEmpty()) return
        val section = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        if (focused.isNotEmpty()) {
            addSectionLabel(section, "OEA Focus · " + focused.size + "/" + OeaFocusStore.MAX_APPS)
            section.addView(appStrip(focused), LinearLayout.LayoutParams(-1, dp(100)))
        }
        if (used.isNotEmpty() && store.showMostUsed()) {
            addSectionLabel(section, "Most used")
            section.addView(appStrip(used), LinearLayout.LayoutParams(-1, dp(100)))
        }
        drawerBody.addView(section)
    }

    private fun appStrip(values: List<OeaAppInfo>): HorizontalScrollView {
        val strip = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        values.forEach { app ->
            row.addView(tile(app), LinearLayout.LayoutParams(dp(78), dp(88)).apply {
                setMargins(dp(3), dp(3), dp(3), dp(3))
            })
        }
        strip.addView(row, FrameLayout.LayoutParams(-2, -2))
        return strip
    }

    private fun addSectionLabel(parent: LinearLayout, title: String) {
        parent.addView(TextView(context).apply {
            text = title
            textSize = 12f
            setTextColor(themeMuted)
            setPadding(dp(4), dp(8), dp(4), dp(6))
        }, LinearLayout.LayoutParams(-1, dp(34)))
    }

    private fun renderOeaTools() {
        addSectionLabel(drawerBody, "OEA tools")
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            "⚙" to "OEA Settings",
            "❄" to "App Freezer",
            "☎" to "Call Blocker",
            "🎮" to "Game Boost",
            "▣" to "Multitask / Split",
        ).forEach { (iconText, title) ->
            row.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = rounded(themeSurface, 18)
                setOnClickListener {
                    when (title) {
                        "OEA Settings" -> openSystemsSettings()
                        "App Freezer" -> openDeviceAdminSettings()
                        "Phone & Calls" -> openPhone()
                    "Call Blocker" -> openCallBlockerSettings()
                        "Game Boost" -> openGameBoostSettings()
                        "Multitask / Split" -> openSplitPairDialog()
                    }
                }
                addView(TextView(context).apply {
                    text = iconText
                    textSize = 25f
                    gravity = Gravity.CENTER
                    setTextColor(themeText)
                }, LinearLayout.LayoutParams(-1, dp(42)))
                addView(TextView(context).apply {
                    text = title
                    textSize = 9f
                    gravity = Gravity.CENTER
                    setTextColor(themeText)
                    maxLines = 2
                }, LinearLayout.LayoutParams(-1, dp(32)))
            }, LinearLayout.LayoutParams(0, dp(82), 1f).apply {
                setMargins(dp(3), dp(3), dp(3), dp(8))
            })
        }
        drawerBody.addView(row, LinearLayout.LayoutParams(-1, dp(94)))
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
        val preview = GridLayout(context).apply { columnCount = 2 }
        folder.members.mapNotNull(::find).take(4).forEach { app ->
            preview.addView(ImageView(context).apply {
                setImageDrawable(icon(app.packageName))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, GridLayout.LayoutParams().apply {
                width = dp(22); height = dp(22)
                setMargins(dp(2), dp(2), dp(2), dp(2))
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
        background = rounded(themeSurface, 18)
        foreground = selectable()
        contentDescription = "Open " + app.label
        setOnClickListener { launch(app) }
        addView(FrameLayout(context).apply {
            val iconView = ImageView(context).apply {
                setImageDrawable(icon(app.packageName))
                scaleType = ImageView.ScaleType.FIT_CENTER
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        if (store.iconShape() == "circle") outline.setOval(0, 0, view.width, view.height)
                        else outline.setRoundRect(0, 0, view.width, view.height, dp(if (store.iconShape() == "square") 4 else 14).toFloat())
                    }
                }
                setPadding(dp(3), dp(3), dp(3), dp(3))
            }
            addView(iconView, FrameLayout.LayoutParams(dp(46), dp(46), Gravity.CENTER))
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
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
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
        if (values.size >= OeaWorkspaceStore.DOCK_SLOTS) {
            Toast.makeText(context, "Dock is full — drag an existing icon out first.", Toast.LENGTH_SHORT).show()
            dragged = null
            return true
        }
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
        rebuild()
    }

    private fun suggestFolderName(keys: List<String>): String {
        val labels = keys.mapNotNull(::find).map { (it.label + " " + it.packageName).lowercase() }
        return when {
            labels.any { it.contains("game") || it.contains("pubg") || it.contains("free fire") || it.contains("codm") } -> "Games"
            labels.any { it.contains("music") || it.contains("spotify") || it.contains("sound") } -> "Music"
            labels.any { it.contains("chat") || it.contains("whatsapp") || it.contains("telegram") || it.contains("messenger") } -> "Social"
            labels.any { it.contains("video") || it.contains("youtube") || it.contains("netflix") } -> "Video"
            labels.any { it.contains("photo") || it.contains("gallery") || it.contains("camera") } -> "Photos"
            else -> "Folder"
        }
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

    private fun dockTile(app: OeaAppInfo) = FrameLayout(context).apply {
        isClickable = true
        foreground = selectable()
        background = ColorDrawable(Color.TRANSPARENT)
        contentDescription = "Open " + app.label
        setOnClickListener { launch(app) }
        addView(ImageView(context).apply {
            setImageDrawable(icon(app.packageName))
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }, FrameLayout.LayoutParams(dp(46), dp(46), Gravity.CENTER))
    }

    private fun renderDock() {
        dock.removeAllViews()
        val values = ws.dock()
        repeat(OeaWorkspaceStore.DOCK_SLOTS) { slot ->
            val app = values.getOrNull(slot)?.let(::find)
            val v = if (app == null) emptyCell() else dockTile(app)
            if (app != null) {
                v.setOnLongClickListener {
                    showAppActions(app, v, values[slot])
                    true
                }
            }
            v.setOnDragListener { _, e ->
                if (e.action == DragEvent.ACTION_DROP && dragged != null) {
                    val key = dragged!!
                    if (!values.contains(key) && values.size >= OeaWorkspaceStore.DOCK_SLOTS) {
                        Toast.makeText(context, "Dock is full — drag an existing icon out first.", Toast.LENGTH_SHORT).show()
                        dragged = null
                        return@setOnDragListener true
                    }
                    val list = values.filterNot { it == key }.toMutableList()
                    val target = slot.coerceIn(0, list.size)
                    list.add(target, key)
                    ws.setDock(list.take(OeaWorkspaceStore.DOCK_SLOTS))
                    ws.replaceItems(ws.items().filterNot { it.id == key })
                    ws.replaceFolders(ws.folders().map { it.copy(members = it.members.filterNot { m -> m == key }) }.filter { it.members.isNotEmpty() })
                    dragged = null
                    rebuild()
                    true
                } else e.action == DragEvent.ACTION_DRAG_STARTED
            }
            dock.addView(v, GridLayout.LayoutParams().apply {
                width = 0
                height = dp(58)
                columnSpec = GridLayout.spec(slot, 1, 1f)
            })
        }
    }

    private fun nextPage() {
        val target = (ws.getCurrentPage() + 1).coerceAtMost(ws.pages() - 1)
        if (target == ws.getCurrentPage()) return
        ws.setCurrentPage(target)
        pager.post { pager.smoothScrollTo(target * pager.width, 0) }
        dots.text = List(ws.pages()) { if (it == target) "●" else "•" }.joinToString(" ")
    }

    private fun previousPage() {
        val target = (ws.getCurrentPage() - 1).coerceAtLeast(0)
        if (target == ws.getCurrentPage()) return
        ws.setCurrentPage(target)
        pager.post { pager.smoothScrollTo(target * pager.width, 0) }
        dots.text = List(ws.pages()) { if (it == target) "●" else "•" }.joinToString(" ")
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
        AlertDialog.Builder(context).setTitle("Icon shape")
            .setSingleChoiceItems(values, current) { dialog, which ->
                store.setIconShape(when (which) { 1 -> "circle"; 2 -> "square"; else -> "rounded" })
                rebuild()
                renderDrawer(drawerSearch.text.toString())
                dialog.dismiss()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun openWallpaperChooser() {
        val choices = arrayOf("Choose from gallery", "Use current system wallpaper", "Remove OEA wallpaper")
        AlertDialog.Builder(context).setTitle("OEA Wallpaper").setItems(choices) { _, which ->
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
                    loadOeaWallpaper()
                    applyThemeFromWallpaper()
                    rebuild()
                }
                2 -> {
                    store.setWallpaperUri(null)
                    wallpaperView.setImageDrawable(null)
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
        AlertDialog.Builder(context).setTitle("Hidden apps").setView(box)
            .setPositiveButton("Done", null).show()
    }

    private fun openFreezerSettings() {
        val choices = apps.filterNot { it.packageName == context.packageName }
        val frozen = OeaAppFreezer.frozenPackages(context)
        val checked = BooleanArray(choices.size) { frozen.contains(choices[it].packageName) }
        AlertDialog.Builder(context).setTitle("App Freezer")
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
        AlertDialog.Builder(context).setTitle("OEA App Lock")
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
        AlertDialog.Builder(context).setTitle("Set OEA App Lock PIN").setView(input)
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
        PopupMenu(context, anchor).apply {
            menu.add("Add page")
            if (ws.pages() > 1) menu.add("Remove last page")
            menu.add("Grid: 3 columns")
            menu.add("Grid: 4 columns")
            menu.add("Grid: 5 columns")
            menu.add("Show / hide labels")
            menu.add("OEA Themes")
            menu.add("Add widget")
            menu.add("Wallpaper")
            menu.add("Notification access")
            menu.add("OEA Focus apps")
            menu.add("Hidden apps")
            menu.add("App Freezer")
            menu.add("App Lock")
            menu.add("Phone & Calls")
            menu.add("Call Blocker")
            menu.add("Game Boost")
            menu.add("Split Screen")
            menu.add(if (store.showMostUsed()) "Hide most-used apps" else "Show most-used apps")
            menu.add("Icon shape")
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
                    "Add widget" -> hostActivity?.let { widgetController.pickWidget(it) } ?: Toast.makeText(context, "OEA Home is not attached to an Activity.", Toast.LENGTH_SHORT).show()
                    "Wallpaper" -> openWallpaperChooser()
                    "Notification access" -> openNotificationAccessSettings()
                    "OEA Focus apps" -> openFocusSettings()
                    "Hidden apps" -> openHiddenAppsSettings()
                    "App Freezer" -> openFreezerSettings()
                    "App Lock" -> openAppLockSettings()
                    "Call Blocker" -> openCallBlockerSettings()
                    "Game Boost" -> openGameBoostSettings()
                    "Split Screen" -> openSplitPairDialog()
                    "Hide most-used apps" -> { store.setShowMostUsed(false); renderDrawer(drawerSearch.text.toString()) }
                    "Show most-used apps" -> { store.setShowMostUsed(true); renderDrawer(drawerSearch.text.toString()) }
                    "Icon shape" -> openIconShapeSettings()
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
        val mode = store.themeMode()
        val light = when (mode) {
            "light" -> true
            "dark" -> false
            else -> runCatching {
                val d = (wallpaperView.drawable as? BitmapDrawable)
                    ?: (WallpaperManager.getInstance(context).drawable as? BitmapDrawable)
                if (d != null && d.bitmap.width > 0 && d.bitmap.height > 0) {
                    val b = d.bitmap
                    val p = b.getPixel(b.width / 2, b.height / 2)
                    (Color.red(p) * 0.299 + Color.green(p) * 0.587 + Color.blue(p) * 0.114) > 150
                } else {
                    (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) != android.content.res.Configuration.UI_MODE_NIGHT_YES
                }
            }.getOrDefault(false)
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

    private fun loadOeaWallpaper() {
        val uri = store.wallpaperUri()?.let(Uri::parse)
        if (uri != null) {
            runCatching {
                wallpaperView.setImageURI(uri)
                wallpaperView.visibility = View.VISIBLE
            }.onFailure {
                store.setWallpaperUri(null)
                wallpaperView.setImageDrawable(null)
            }
        } else {
            runCatching {
                wallpaperView.setImageDrawable(WallpaperManager.getInstance(context).drawable)
                wallpaperView.visibility = View.VISIBLE
            }.onFailure {
                wallpaperView.setImageDrawable(null)
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
    private fun renderWidgets() {
        val grid = pages.getChildAt(0) as? GridLayout ?: return
        widgetController.views().forEach { view ->
            grid.addView(view, GridLayout.LayoutParams().apply {
                width = 0
                height = dp(220)
                columnSpec = GridLayout.spec(0, cols(), 1f)
                rowSpec = GridLayout.spec((grid.childCount / cols()) + 1)
                setMargins(dp(6), dp(6), dp(6), dp(6))
            })
        }
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }
    private fun cols() = store.gridColumns().coerceIn(3, 5)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    fun refreshBadges() { rebuild(); renderDrawer(drawerSearch.text.toString()) }

    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode == wallpaperRequestCode) {
            if (resultCode == Activity.RESULT_OK && data?.data != null) {
                val uri = data.data!!
                runCatching {
                    val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    if (flags != 0) context.contentResolver.takePersistableUriPermission(uri, flags)
                }
                store.setWallpaperUri(uri.toString())
                loadOeaWallpaper()
                applyThemeFromWallpaper()
                rebuild()
            }
            return true
        }
        return widgetController.handleActivityResult(requestCode, resultCode, data)
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
