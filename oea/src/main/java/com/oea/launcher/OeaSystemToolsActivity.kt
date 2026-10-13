package com.oea.launcher

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.oea.launcher.applock.OeaAppFreezer
import com.oea.launcher.applock.OeaAppLockStore
import com.oea.launcher.assistant.OeaAssistant
import com.oea.launcher.callblocker.OeaCallBlockerActivity
import com.oea.launcher.data.OeaDataStore
import com.oea.launcher.gameboost.OeaGameBoostActivity
import com.oea.launcher.gameboost.OeaGameBoostStore
import com.oea.launcher.icons.OeaIconController
import com.oea.launcher.model.OeaAppInfo
import com.oea.launcher.model.OeaAppModel
import com.oea.launcher.multitask.OeaMultitaskLauncher
import com.oea.launcher.phone.OeaPhoneActivity
import com.oea.launcher.runtime.OeaRuntime
import com.oea.launcher.split.OeaSplitLauncher
import com.oea.launcher.ui.OeaInsets
import com.oea.launcher.ui.OeaKit
import com.oea.launcher.ui.OeaPalette
import com.oea.launcher.ui.OeaTheme
import com.oea.launcher.ui.OeaType
import com.oea.launcher.workspace.OeaWorkspaceStore
import java.io.InputStream

/**
 * OEA Settings: one hub with focused sub-screens for Home, appearance, the
 * drawer, gestures, notifications, privacy, the App Freezer, multitasking,
 * permissions and the OEA apps (Phone, Call Blocker, Game Boost).
 */
class OeaSystemToolsActivity : Activity() {
    companion object {
        const val EXTRA_SCREEN = "oea_screen"
        private const val REQUEST_WALLPAPER = 0x4F58
        private const val REQUEST_CONTACTS = 0x4F59
    }

    private val dataStore by lazy { OeaDataStore.get(this) }
    private var appsCache: List<OeaAppInfo>? = null
    private lateinit var palette: OeaPalette
    private lateinit var kit: OeaKit
    private val stack = ArrayDeque<String>()
    private var current = "hub"
    private var currentScroll: android.widget.ScrollView? = null
    private val scrollPositions = HashMap<String, Int>()
    private var refreshOverviewOnResume = false

    private fun apps(): List<OeaAppInfo> = appsCache ?: run {
        val fromHome = runCatching { OeaRuntime.engine(this).model.apps }.getOrDefault(emptyList())
        (fromHome.ifEmpty { OeaAppModel(this).query() }).also { appsCache = it }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        when (intent.getStringExtra(EXTRA_SCREEN)) {
            "call_blocker" -> { openActivity(OeaCallBlockerActivity::class.java); finish(); return }
            "game_boost" -> { openActivity(OeaGameBoostActivity::class.java); finish(); return }
            "freezer" -> current = "freezer"
            "multitask" -> current = "overview"
            "split" -> current = "split"
            "home" -> current = "home"
            else -> current = "hub"
        }
        show(current)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Permission screens may have changed what a row says; re-render in place.
        if (current != "overview" || refreshOverviewOnResume) {
            refreshOverviewOnResume = false
            rerender()
        }
    }

    @Deprecated("Back is handled with an in-activity navigation stack")
    override fun onBackPressed() {
        if (stack.isNotEmpty()) {
            current = stack.removeLast()
            show(current)
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    private fun navigate(screen: String) {
        currentScroll?.let { scrollPositions[current] = it.scrollY }
        stack.addLast(current)
        current = screen
        show(screen)
    }

    private fun back() {
        @Suppress("DEPRECATION")
        onBackPressed()
    }

    private fun rerender() {
        currentScroll?.let { scrollPositions[current] = it.scrollY }
        show(current)
    }

    private fun show(screen: String) {
        palette = OeaTheme.resolve(this)
        kit = OeaKit(this, palette)
        OeaInsets.edgeToEdge(window, lightBars = !palette.dark)
        window.decorView.setBackgroundColor(palette.background)
        when (screen) {
            "home" -> showHome()
            "appearance" -> showAppearance()
            "drawer" -> showDrawer()
            "gestures" -> showGestures()
            "notifications" -> showNotifications()
            "privacy" -> showPrivacy()
            "freezer" -> showFreezer()
            "multitasking" -> showMultitasking()
            "overview" -> showMultitask()
            "split" -> showSplit()
            "access" -> showAccess()
            "about" -> showAbout()
            else -> showSettings()
        }
    }

    private fun page(title: String, subtitle: String?): OeaKit.Page {
        val root = current == "hub" && stack.isEmpty()
        return kit.page(title, subtitle, onBack = if (root) null else ({ back() }))
    }

    private fun present(page: OeaKit.Page) {
        setContentView(page.root)
        currentScroll = page.scroll
        val y = scrollPositions[current] ?: 0
        if (y > 0) page.scroll.post { page.scroll.scrollTo(0, y) }
    }

    private fun openActivity(cls: Class<*>) {
        runCatching { startActivity(Intent(this, cls)) }
            .onFailure { toast("This OEA app could not be opened.") }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    // ===================================================================== hub

    private fun showSettings() {
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""
        val page = page("OEA Settings", "OEA Launcher $version")
        val box = page.content
        if (!isDefaultHome()) {
            kit.banner(box, R.drawable.oic_home_fill, "OEA isn't your default Home", "Set OEA as default so the Home button, app shortcuts and pinned widgets work.",
                OeaKit.Tone.WARNING, "Set as default") { requestDefaultHome() }
        }
        var group = kit.group(box)
        kit.row(group, R.drawable.oic_home, "Home screen", "${dataStore.gridColumns()} × ${dataStore.gridRows()} grid • ${labelFor(dataStore.searchBarPosition(), mapOf("bottom" to "search at bottom", "top" to "search at top", "hidden" to "no search bar"))}") { navigate("home") }
        kit.row(group, R.drawable.oic_palette, "Appearance", "${themeLabel()} • ${iconShapeLabel()} icons") { navigate("appearance") }
        kit.row(group, R.drawable.oic_apps, "App drawer", if (dataStore.drawerMode() == OeaDataStore.DrawerMode.VERTICAL) "List • ${hiddenCount()} hidden" else "Grid • ${hiddenCount()} hidden") { navigate("drawer") }
        kit.row(group, R.drawable.oic_gesture, "Gestures", "Double-tap: ${labelFor(dataStore.doubleTapAction(), mapOf("lock" to "lock screen", "drawer" to "app drawer", "none" to "off"))}") { navigate("gestures") }
        group = kit.group(box)
        kit.row(group, R.drawable.oic_notifications, "Notification dots", if (notificationAccess()) labelFor(dataStore.badgeStyle(), mapOf("dot" to "Dots", "count" to "Counts", "off" to "Off")) else "Needs notification access") { navigate("notifications") }
        kit.row(group, R.drawable.oic_shield, "Privacy & App Lock", "${OeaAppLockStore.lockedApps(this).size} locked • ${hiddenCount()} hidden") { navigate("privacy") }
        kit.row(group, R.drawable.oic_ac_unit, "App Freezer", freezerStatus()) { navigate("freezer") }
        group = kit.group(box)
        kit.row(group, R.drawable.oic_splitscreen, "Multitasking", "Overview, split screen and floating windows") { navigate("multitasking") }
        kit.row(group, R.drawable.oic_sports_esports, "Game Boost", if (OeaGameBoostStore.enabled(this)) "On • ${OeaGameBoostStore.games(this).size} games" else "Off") { openActivity(OeaGameBoostActivity::class.java) }
        kit.row(group, R.drawable.oic_call, "Phone", "OEA dialer, contacts and in-call screen") { openActivity(OeaPhoneActivity::class.java) }
        kit.row(group, R.drawable.oic_block, "Call Blocker", "Blocked numbers, allow list, rules and history") { openActivity(OeaCallBlockerActivity::class.java) }
        group = kit.group(box)
        kit.row(group, R.drawable.oic_accessibility_new, "Permissions & access", accessSummary()) { navigate("access") }
        kit.row(group, R.drawable.oic_info, "About OEA", "Version $version") { navigate("about") }
        present(page)
    }

    private fun labelFor(value: String, labels: Map<String, String>) = labels[value] ?: value

    // ============================================================ home screen

    private fun showHome() {
        val page = page("Home screen", "Grid, dock, search and At a Glance")
        val box = page.content
        kit.sectionTitle(box, "Layout")
        var group = kit.group(box)
        kit.stepperRow(group, R.drawable.oic_grid_view, "Columns", null, dataStore.gridColumns(), 3, 6) { dataStore.setGridColumns(it) }
        kit.stepperRow(group, R.drawable.oic_view_agenda, "Rows", null, dataStore.gridRows(), 3, 7) { dataStore.setGridRows(it) }
        kit.stepperRow(group, R.drawable.oic_format_size, "Icon size", null, (dataStore.iconSizeDp() - 44) / 2, 0, 11, { "${44 + it * 2} dp" }) {
            dataStore.setIconSizeDp(44 + it * 2)
            OeaIconController.shared(this).invalidate()
        }
        kit.switchRow(group, R.drawable.oic_text_fields, "App labels", "Show names under Home icons", dataStore.showAppLabels()) { dataStore.setShowAppLabels(it) }
        kit.sectionTitle(box, "Dock & search")
        group = kit.group(box)
        choiceRow(group, R.drawable.oic_dock_to_bottom, "Dock style", listOf("glass" to "Glass", "solid" to "Solid", "none" to "No background"), dataStore.dockStyle()) { dataStore.setDockStyle(it) }
        choiceRow(group, R.drawable.oic_search, "Search bar", listOf("bottom" to "Below the dock", "top" to "Under At a Glance", "hidden" to "Hidden"), dataStore.searchBarPosition()) { dataStore.setSearchBarPosition(it) }
        choiceRow(group, R.drawable.oic_schedule, "At a Glance", listOf("large" to "Large clock", "compact" to "Compact date", "hidden" to "Hidden"), dataStore.glanceStyle()) { dataStore.setGlanceStyle(it) }
        kit.sectionTitle(box, "Pages & widgets")
        group = kit.group(box)
        kit.row(group, R.drawable.oic_widgets, "Add a widget", "Opens the OEA widget picker on Home") { homeAction("widgets") }
        kit.switchRow(group, R.drawable.oic_lock, "Lock Home layout", "Prevents icons and widgets from being moved or removed by accident", dataStore.homeLayoutLocked()) { dataStore.setHomeLayoutLocked(it) }
        kit.row(group, R.drawable.oic_home, "Default Home app", homeRoleStatus()) { requestDefaultHome() }
        kit.note(box, "Tip: long-press an empty spot on Home for wallpaper, widgets and pages. Long-press an icon for shortcuts, split screen, floating window, freeze and lock.")
        present(page)
    }

    private fun homeAction(action: String) {
        val intent = Intent(this, OeaLauncherActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .putExtra(OeaLauncherActivity.EXTRA_HOME_ACTION, action)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
    }

    /** A row that opens a single-choice sheet and shows the current value. */
    private fun choiceRow(group: LinearLayout, icon: Int, title: String, options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
        val index = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
        kit.row(group, icon, title, null, value = options[index].second) {
            kit.choose(title, null, options.map { it.second }, index) { pick ->
                onPick(options[pick].first)
                rerender()
            }
        }
    }

    // ============================================================= appearance

    private fun showAppearance() {
        val page = page("Appearance", "Theme, colours, icons and motion")
        val box = page.content
        kit.sectionTitle(box, "Theme")
        var group = kit.group(box)
        choiceRow(group, R.drawable.oic_dark_mode, "Theme", listOf("system" to "Follow system", "dark" to "Dark", "light" to "Light"), dataStore.themeMode()) { dataStore.setThemeMode(it) }
        accentRow(group)
        kit.sectionTitle(box, "Icons")
        group = kit.group(box)
        choiceRow(group, R.drawable.oic_interests, "Icon shape",
            listOf("system" to "System default", "circle" to "Circle", "squircle" to "Squircle", "rounded" to "Rounded square", "teardrop" to "Teardrop", "square" to "Square"),
            dataStore.iconShape()) { dataStore.setIconShape(it); OeaIconController.shared(this).invalidate() }
        if (Build.VERSION.SDK_INT >= 33) {
            kit.switchRow(group, R.drawable.oic_contrast, "Themed icons", "Tints icons with your colours when the app supports it", dataStore.themedIcons()) {
                dataStore.setThemedIcons(it)
                OeaIconController.shared(this).invalidate()
            }
        }
        kit.row(group, R.drawable.oic_auto_awesome, "External icon pack", iconPackLabel()) { iconPackDialog() }
        kit.sectionTitle(box, "Motion & wallpaper")
        group = kit.group(box)
        choiceRow(group, R.drawable.oic_animation, "Animation speed", listOf("0" to "Relaxed", "1" to "Standard", "2" to "Fast"), dataStore.animationSpeed().toString()) {
            dataStore.setAnimationSpeed(it.toInt())
        }
        kit.row(group, R.drawable.oic_wallpaper, "Wallpaper & style", "Open Android's wallpaper picker") {
            runCatching { startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Wallpaper")) }
        }
        kit.row(group, R.drawable.oic_photo_library, "Use a photo as wallpaper", "Pick an image and apply it to Home") { pickWallpaper() }
        present(page)
    }

    private fun accentRow(group: LinearLayout) {
        val options = listOf("wallpaper", "blue", "teal", "violet", "rose", "amber", "green")
        val current = dataStore.accentMode()
        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        options.forEach { mode ->
            val color = when (mode) {
                "blue" -> Color.rgb(66, 133, 244)
                "teal" -> Color.rgb(0, 150, 136)
                "violet" -> Color.rgb(124, 77, 255)
                "rose" -> Color.rgb(233, 30, 99)
                "amber" -> Color.rgb(255, 160, 0)
                "green" -> Color.rgb(67, 160, 71)
                else -> palette.accent
            }
            val swatch = FrameLayout(this).apply {
                background = if (mode == "wallpaper") GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(palette.accent, palette.accentSoft)).apply { shape = GradientDrawable.OVAL }
                else kit.oval(color)
                foreground = if (mode == current) kit.oval(Color.TRANSPARENT, palette.text, 3) else null
                contentDescription = if (mode == "wallpaper") "Wallpaper colours" else "$mode accent"
                isClickable = true
                setOnClickListener {
                    dataStore.setAccentMode(mode)
                    rerender()
                }
                if (mode == "wallpaper") addView(ImageView(this@OeaSystemToolsActivity).apply { setImageDrawable(kit.icon(R.drawable.oic_wallpaper, palette.onAccent)) },
                    FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
            }
            strip.addView(swatch, LinearLayout.LayoutParams(dp(34), dp(34)).apply { rightMargin = dp(10) })
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }
        box.addView(kit.text("Accent colour", 16f, palette.text, OeaType.medium))
        box.addView(kit.text(if (current == "wallpaper") "Matches your wallpaper (Material You)" else "Fixed OEA accent", 13f, palette.textSecondary).apply { setPadding(0, dp(2), 0, dp(12)) })
        box.addView(kit.hscroll(strip))
        group.addView(View(this).apply { setBackgroundColor(palette.stroke) }, LinearLayout.LayoutParams(-1, maxOf(1, dp(1) / 2)).apply { leftMargin = dp(70); rightMargin = dp(18) })
        group.addView(box)
    }

    private fun themeLabel() = when (dataStore.themeMode()) {
        "dark" -> "Dark"
        "light" -> "Light"
        else -> "System theme"
    }

    private fun iconShapeLabel(): String = when (dataStore.iconShape()) {
        "circle" -> "Circle"
        "squircle" -> "Squircle"
        "square" -> "Square"
        "teardrop" -> "Teardrop"
        "rounded" -> "Rounded"
        else -> "System"
    }

    private fun iconPackLabel(): String {
        val selected = dataStore.iconPackPackage() ?: return "System app icons"
        return OeaIconController(this).installedPacks()
            .firstOrNull { it.packageName == selected }?.label
            ?: "Pack unavailable — using system icons"
    }

    private fun iconPackDialog() {
        val packs = OeaIconController(this).installedPacks()
        val labels = listOf("System app icons") + packs.map { it.label } + listOf("Find icon packs…")
        val selectedPackage = dataStore.iconPackPackage()
        val selected = packs.indexOfFirst { it.packageName == selectedPackage }.let { if (selectedPackage == null || it < 0) 0 else it + 1 }
        kit.choose("External icon pack", "Choose an installed ADW/Nova-style icon pack. Apps missing from the pack keep their own icons.", labels, selected) { which ->
            if (which == labels.lastIndex) {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=icon%20pack&c=apps"))) }
                    .onFailure { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/search?q=icon%20pack&c=apps"))) } }
                return@choose
            }
            dataStore.setIconPackPackage(packs.getOrNull(which - 1)?.packageName)
            OeaIconController.shared(this).invalidate()
            rerender()
        }
    }

    private fun pickWallpaper() {
        runCatching {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "image/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                },
                REQUEST_WALLPAPER,
            )
        }.onFailure { toast("No image picker is available.") }
    }

    @Deprecated("Activity results are routed manually for this simple flow")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_WALLPAPER) {
            val uri = data?.data
            if (resultCode == RESULT_OK && uri != null) {
                toast("Applying wallpaper…")
                Thread {
                    val ok = runCatching {
                        contentResolver.openInputStream(uri)?.use { stream: InputStream ->
                            android.app.WallpaperManager.getInstance(this).setStream(stream)
                        } ?: error("unreadable")
                    }.isSuccess
                    runOnUiThread { toast(if (ok) "Wallpaper applied." else "Android could not apply that image.") }
                }.start()
            }
            return
        }
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
    }

    // ================================================================= drawer

    private fun showDrawer() {
        val page = page("App drawer", "Swipe up on Home to open it")
        val box = page.content
        kit.sectionTitle(box, "Layout")
        box.addView(kit.segmented(listOf("Grid", "List"), if (dataStore.drawerMode() == OeaDataStore.DrawerMode.VERTICAL) 1 else 0) {
            dataStore.setDrawerMode(if (it == 1) OeaDataStore.DrawerMode.VERTICAL else OeaDataStore.DrawerMode.GRID)
            rerender()
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        box.addView(kit.segmented(listOf("A–Z", "Recently used"), if (dataStore.sortMode() == OeaDataStore.SortMode.RECENT) 1 else 0) {
            dataStore.setSortMode(if (it == 1) OeaDataStore.SortMode.RECENT else OeaDataStore.SortMode.NAME)
            rerender()
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        val group = kit.group(box)
        kit.switchRow(group, R.drawable.oic_interests, "Smart categories", "Social, Games, Music and more, from Android's app categories", dataStore.drawerCategories()) { dataStore.setDrawerCategories(it) }
        kit.switchRow(group, R.drawable.oic_auto_awesome, "Suggestions", "Your pinned and most-used apps at the top", dataStore.showMostUsed()) { dataStore.setShowMostUsed(it) }
        kit.switchRow(group, R.drawable.oic_search, "Open keyboard automatically", "Start typing as soon as the drawer opens", dataStore.drawerKeyboard()) { dataStore.setDrawerKeyboard(it) }
        kit.row(group, R.drawable.oic_visibility_off, "Hidden apps", "${hiddenCount()} hidden") { hiddenAppsDialog() }
        kit.note(box, "Drawer search also finds contacts, Android settings, calculations (try 18% of 2500 as 2500*18%) and web results.")
        present(page)
    }

    private fun hiddenCount() = dataStore.hiddenApps().size

    private fun hiddenAppsDialog() {
        val choices = apps().filterNot { it.packageName == packageName && it.className.endsWith("OeaSettingsAlias") }
        val selected = choices.filter { dataStore.isHidden(it.packageName, it.className) }.map { it.packageName + "/" + it.className }.toSet()
        kit.pickApps("Hidden apps", "Hidden apps stay installed but don't appear in the drawer or search.", choices, selected, { it.packageName + "/" + it.className }) { chosen ->
            choices.forEach { dataStore.setHidden(it.packageName, it.className, (it.packageName + "/" + it.className) in chosen) }
            rerender()
        }
    }

    // =============================================================== gestures

    private fun showGestures() {
        val page = page("Gestures", "Fast actions from anywhere on Home")
        val box = page.content
        assistantBanner(box)
        val group = kit.group(box)
        kit.row(group, R.drawable.oic_swipe_up, "Swipe up", null, value = "App drawer", chevron = false)
        choiceRow(group, R.drawable.oic_swipe_down, "Swipe down", listOf("notifications" to "Notifications", "quick_settings" to "Quick Settings", "search" to "Search", "none" to "Nothing"), dataStore.swipeDownAction()) { dataStore.setSwipeDownAction(it) }
        choiceRow(group, R.drawable.oic_touch_app, "Double-tap", listOf("lock" to "Lock screen", "drawer" to "Open app drawer", "none" to "Nothing"), dataStore.doubleTapAction()) { dataStore.setDoubleTapAction(it) }
        choiceRow(group, R.drawable.oic_layers, "Recent apps (Home menu)", listOf("system" to "Android Recents", "oea" to "OEA Overview"), dataStore.recentsMode()) { dataStore.setRecentsMode(it) }
        kit.note(box, "Long-press an empty spot for the Home menu. Long-press an icon for its shortcuts and actions, then drag to move it.")
        present(page)
    }

    private fun assistantBanner(box: LinearLayout) {
        when {
            OeaAssistant.isConnected() -> kit.banner(box, R.drawable.oic_check_circle_fill, "OEA Assistant is on",
                "Lock screen, notifications, Recents, split screen and App Freezer actions are available.", OeaKit.Tone.SUCCESS)
            OeaAssistant.isEnabled(this) -> kit.banner(box, R.drawable.oic_warning_fill, "OEA Assistant was stopped by Android",
                "Switch it off and on again to restore lock, Recents and freezer actions.", OeaKit.Tone.WARNING, "Open settings") { OeaAssistant.openSetup(this) }
            else -> {
                val body = "OEA Assistant (an accessibility service) performs only the actions you trigger: double-tap lock, swipe-down notifications, Recents, split screen on older Android and the no-root App Freezer. It never reads or sends your screen content." +
                    if (OeaAssistant.needsRestrictedSettingsHint()) "\n\n" + OeaAssistant.RESTRICTED_SETTINGS_HINT else ""
                val card = kit.banner(box, R.drawable.oic_accessibility_new, "Turn on OEA Assistant", body, OeaKit.Tone.INFO, "Turn on") { OeaAssistant.openSetup(this) }
                if (OeaAssistant.needsRestrictedSettingsHint()) {
                    card.addView(kit.quietButton("Open OEA App info", R.drawable.oic_info) { OeaAssistant.openOeaAppInfo(this) },
                        LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8); leftMargin = dp(34) })
                }
            }
        }
    }

    // ========================================================== notifications

    private fun showNotifications() {
        val page = page("Notification dots", "See which apps have something new")
        val box = page.content
        if (!notificationAccess()) {
            kit.banner(box, R.drawable.oic_notifications, "Allow notification access", "OEA needs it to show dots and counts on icons and what's playing in At a Glance.",
                OeaKit.Tone.WARNING, "Allow") { runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } }
        }
        kit.sectionTitle(box, "Style")
        val styles = listOf("dot", "count", "off")
        box.addView(kit.segmented(listOf("Dots", "Counts", "Off"), styles.indexOf(dataStore.badgeStyle()).coerceAtLeast(0)) {
            dataStore.setBadgeStyle(styles[it])
            rerender()
        })
        val group = kit.group(box)
        kit.row(group, R.drawable.oic_notifications, "Notification access", notificationStatus()) {
            runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        }
        present(page)
    }

    private fun notificationAccess(): Boolean = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        ?.split(":")?.any { it.startsWith("$packageName/") } == true

    private fun notificationStatus() = if (notificationAccess()) "Allowed" else "Not allowed • tap to allow"

    // ================================================================ privacy

    private fun showPrivacy() {
        val page = page("Privacy & App Lock", "Protect apps with a PIN and keep others out of sight")
        val box = page.content
        kit.sectionTitle(box, "App Lock")
        var group = kit.group(box)
        val hasPin = OeaAppLockStore.hasPin(this)
        kit.row(group, R.drawable.oic_lock, if (hasPin) "Change PIN" else "Create PIN", if (hasPin) "Your App Lock PIN is set" else "Required before locking apps") { changePin() }
        kit.row(group, R.drawable.oic_shield, "Locked apps", "${OeaAppLockStore.lockedApps(this).size} apps ask for your PIN") { openAppLockSettings() }
        kit.sectionTitle(box, "Hidden apps")
        group = kit.group(box)
        kit.row(group, R.drawable.oic_visibility_off, "Hidden apps", "${hiddenCount()} hidden from the drawer and search") { hiddenAppsDialog() }
        kit.note(box, "Locked apps ask for your PIN when opened from OEA Home, the drawer, Overview and split screen.")
        present(page)
    }

    private fun changePin() {
        val hasPin = OeaAppLockStore.hasPin(this)
        fun setNew() {
            kit.prompt(if (hasPin) "New PIN" else "Create App Lock PIN", "4–8 digits", "PIN", numeric = true, password = true) { pin ->
                if (pin.length !in 4..8 || !pin.all(Char::isDigit)) return@prompt "PIN must be 4–8 digits"
                OeaAppLockStore.setPin(this, pin)
                toast("App Lock PIN saved.")
                rerender()
                null
            }
        }
        if (!hasPin) {
            setNew()
            return
        }
        kit.prompt("Current PIN", "Enter your current PIN to change it", "Current PIN", numeric = true, password = true, confirmLabel = "Next") { pin ->
            if (!OeaAppLockStore.verifyPin(this, pin)) return@prompt "Incorrect PIN"
            window.decorView.post { setNew() }
            null
        }
    }

    private fun openAppLockSettings() {
        if (!OeaAppLockStore.hasPin(this)) {
            changePin()
            return
        }
        val choices = apps().filterNot { it.packageName == packageName && it.className.endsWith("OeaSettingsAlias") }
        val selected = choices.filter { OeaAppLockStore.isLocked(this, it.packageName + "/" + it.className) }.map { it.packageName + "/" + it.className }.toSet()
        kit.pickApps("Locked apps", "These apps ask for your PIN when opened from OEA.", choices, selected, { it.packageName + "/" + it.className }) { chosen ->
            choices.forEach { OeaAppLockStore.setLocked(this, it.packageName + "/" + it.className, (it.packageName + "/" + it.className) in chosen) }
            rerender()
        }
    }

    private fun protectedLaunch(app: OeaAppInfo, onSuccess: () -> Unit) {
        val key = app.packageName + "/" + app.className
        if (!OeaAppLockStore.isLocked(this, key)) {
            onSuccess()
            return
        }
        kit.prompt("Unlock ${app.label}", "This app is protected by OEA App Lock.", "PIN", numeric = true, password = true, confirmLabel = "Unlock") { pin ->
            if (!OeaAppLockStore.verifyPin(this, pin)) return@prompt "Incorrect PIN"
            window.decorView.post { onSuccess() }
            null
        }
    }

    // ================================================================= freezer

    private fun freezerStatus(): String = when (OeaAppFreezer.backend(this)) {
        OeaAppFreezer.Backend.DEVICE_OWNER -> "System freezer • Device Owner"
        OeaAppFreezer.Backend.ROOT -> "System freezer • Root"
        else -> "OEA Freeze • ${OeaAppFreezer.allFrozen(this).size} frozen" + if (OeaAssistant.isConnected()) "" else " • Assistant off"
    }

    private fun freezerReturnIntent() = Intent(this, OeaSystemToolsActivity::class.java)
        .putExtra(EXTRA_SCREEN, "freezer")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)

    /**
     * App Freezer. Uses true package suspension when Android grants Device
     * Owner or root; otherwise OEA Freeze hibernates apps (OEA blocks them and
     * OEA Assistant force-stops them through Android's App info page).
     * CI regression guards retain the previous SELECT VISIBLE and REFRESH STATE control identifiers.
     */
    private fun showFreezer() {
        val backend = OeaAppFreezer.backend(this)
        val systemMode = OeaAppFreezer.mode(this) == OeaAppFreezer.Mode.SYSTEM
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.background)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), 0)
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(kit.circleIconButton(R.drawable.oic_arrow_back, "Back") { back() }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(12) })
        top.addView(kit.text("App Freezer", 26f, palette.text), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(top, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        when {
            systemMode -> kit.banner(header, R.drawable.oic_shield_fill,
                if (backend == OeaAppFreezer.Backend.ROOT) "System freezer active • Root" else "System freezer active • Device Owner",
                "Frozen apps are suspended by Android. Each change is verified.", OeaKit.Tone.SUCCESS)
            OeaAssistant.isConnected() -> kit.banner(header, R.drawable.oic_ac_unit_fill, "OEA Freeze is ready",
                "Frozen apps are blocked in OEA and force-stopped, so they stay asleep: no background work, alarms or notifications until you unfreeze them.", OeaKit.Tone.SUCCESS)
            else -> kit.banner(header, R.drawable.oic_ac_unit, "OEA Freeze: turn on OEA Assistant",
                "Without root or Device Owner, OEA freezes apps by blocking them in OEA and force-stopping them through Android's App info page. OEA Assistant presses Force stop for you. No ADB or computer needed.",
                OeaKit.Tone.INFO, "Turn on") { OeaAssistant.openSetup(this) }
        }
        val searchField = kit.field("Search apps or package names")
        header.addView(searchField, LinearLayout.LayoutParams(-1, -2))
        val filters = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(kit.hscroll(filters), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        header.addView(controls, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val summary = kit.text("", 12.5f, palette.textSecondary).apply { setPadding(dp(4), dp(8), dp(4), dp(6)) }
        header.addView(summary)
        val bulk = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        header.addView(bulk, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        root.addView(header, LinearLayout.LayoutParams(-1, -2))
        val list = ListView(this).apply {
            divider = ColorDrawable(Color.TRANSPARENT)
            dividerHeight = dp(6)
            selector = ColorDrawable(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(14), dp(4), dp(14), dp(24))
        }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = OeaInsets.bars(insets)
            root.setPadding(bars[0], bars[1], bars[2], 0)
            list.setPadding(dp(14), dp(4), dp(14), dp(24) + bars[3])
            insets
        }

        var currentQuery = ""
        var currentFilter = "all"
        var showSystemApps = false
        val selectedPackages = linkedSetOf<String>()
        var allApps: List<OeaAppInfo> = emptyList()
        var loadingApps = true
        var freezerOperationRunning = false
        var visible: List<OeaAppInfo> = emptyList()
        var rerenderList: (() -> Unit)? = null
        val icons = OeaIconController.shared(this)

        fun reload() {
            loadingApps = true
            rerenderList?.invoke()
            val includeSystem = showSystemApps
            Thread {
                val loaded = runCatching { loadInstalledFreezerApps(includeSystem) }.getOrElse { emptyList() }
                OeaAppFreezer.syncActualState(this, loaded.map { it.packageName })
                runOnUiThread {
                    allApps = loaded
                    loadingApps = false
                    rerenderList?.invoke()
                }
            }.start()
        }

        fun apply(packages: List<String>, freeze: Boolean) {
            if (packages.isEmpty()) {
                toast("Select one or more apps first.")
                return
            }
            if (freezerOperationRunning || OeaAssistant.isForceStopping()) {
                toast("A freezer operation is already running.")
                return
            }
            if (OeaAppFreezer.mode(this) == OeaAppFreezer.Mode.SYSTEM) {
                freezerOperationRunning = true
                rerenderList?.invoke()
                Thread {
                    var succeeded = 0
                    var failed = 0
                    packages.forEach { pkg -> if (OeaAppFreezer.setFrozen(this, pkg, freeze).success) succeeded++ else failed++ }
                    runOnUiThread {
                        freezerOperationRunning = false
                        toast(if (packages.size == 1) (if (failed == 0) "Done and verified." else "Android refused this change.") else "$succeeded apps updated, $failed failed.")
                        selectedPackages.clear()
                        rerenderList?.invoke()
                    }
                }.start()
                return
            }
            OeaAppFreezer.setHibernated(this, packages, freeze)
            selectedPackages.clear()
            rerenderList?.invoke()
            if (!freeze) {
                toast(if (packages.size == 1) "App unfrozen." else "${packages.size} apps unfrozen.")
                return
            }
            if (!OeaAssistant.isConnected()) {
                toast("Frozen in OEA. Turn on OEA Assistant so OEA can also force-stop them.")
                return
            }
            freezerOperationRunning = true
            val started = OeaAssistant.forceStop(packages, freezerReturnIntent()) { stopped, failed ->
                freezerOperationRunning = false
                toast(if (failed.isEmpty()) "${stopped.size} app" + (if (stopped.size == 1) "" else "s") + " frozen and stopped." else "${stopped.size} stopped, ${failed.size} not confirmed by Android (still frozen in OEA).")
                rerenderList?.invoke()
            }
            if (!started) {
                freezerOperationRunning = false
                toast("Frozen in OEA.")
            }
        }

        val adapter = object : BaseAdapter() {
            override fun getCount() = if (visible.isEmpty()) 1 else visible.size
            override fun getItem(position: Int) = visible.getOrNull(position)
            override fun getItemId(position: Int) = position.toLong()
            override fun getViewTypeCount() = 2
            override fun getItemViewType(position: Int) = if (visible.isEmpty()) 1 else 0
            override fun isEnabled(position: Int) = false
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                if (visible.isEmpty()) {
                    return kit.text(when {
                        loadingApps -> "Loading installed apps…"
                        currentQuery.isNotBlank() -> "No apps match this search."
                        currentFilter == "frozen" -> "No frozen apps."
                        else -> "No apps to show."
                    }, 14f, palette.textSecondary).apply {
                        gravity = Gravity.CENTER
                        setPadding(dp(16), dp(40), dp(16), dp(40))
                    }
                }
                val app = visible[position]
                val frozen = OeaAppFreezer.isFrozen(this@OeaSystemToolsActivity, app.packageName)
                val selected = app.packageName in selectedPackages
                val row = convertView as? LinearLayout ?: LinearLayout(this@OeaSystemToolsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(10), dp(10), dp(12), dp(10))
                    addView(FrameLayout(context), LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(10) })
                    addView(ImageView(context), LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(12) })
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(kit.text("", 15f, palette.text, OeaType.medium).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                        addView(kit.text("", 11.5f, palette.textTertiary).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE })
                        addView(kit.text("", 11f, palette.textSecondary, OeaType.medium).apply { setPadding(0, dp(3), 0, 0) })
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(TextView(context).apply {
                        textSize = 13f
                        typeface = OeaType.medium
                        gravity = Gravity.CENTER
                        setPadding(dp(14), dp(8), dp(14), dp(8))
                    }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
                }
                val mark = row.getChildAt(0) as FrameLayout
                mark.removeAllViews()
                mark.background = kit.oval(if (selected) palette.accent else Color.TRANSPARENT, if (selected) palette.accent else palette.textTertiary, 2)
                if (selected) mark.addView(ImageView(this@OeaSystemToolsActivity).apply { setImageDrawable(kit.icon(R.drawable.oic_check, palette.onAccent)) },
                    FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER))
                mark.contentDescription = "Select ${app.label} for bulk actions"
                mark.setOnClickListener {
                    if (selected) selectedPackages.remove(app.packageName) else selectedPackages.add(app.packageName)
                    rerenderList?.invoke()
                }
                (row.getChildAt(1) as ImageView).apply {
                    setImageDrawable(icons.icon(app.packageName, null))
                    alpha = if (frozen) 0.5f else 1f
                }
                val texts = row.getChildAt(2) as LinearLayout
                (texts.getChildAt(0) as TextView).text = app.label
                (texts.getChildAt(1) as TextView).text = app.packageName
                (texts.getChildAt(2) as TextView).apply {
                    text = when {
                        !frozen -> "Not frozen"
                        app.packageName in OeaAppFreezer.frozenPackages(this@OeaSystemToolsActivity) -> "Suspended by Android"
                        else -> "Frozen by OEA"
                    }
                    setTextColor(if (frozen) palette.accent else palette.textSecondary)
                }
                (row.getChildAt(3) as TextView).apply {
                    text = if (frozen) "Restore" else "Freeze"
                    setTextColor(if (frozen) palette.onAccentSoft else palette.onAccent)
                    background = kit.ripple(kit.rounded(if (frozen) palette.accentSoft else palette.accent, 18), 18)
                    alpha = if (freezerOperationRunning) 0.5f else 1f
                    contentDescription = if (frozen) app.label + " frozen, tap Restore" else app.label + " not frozen, tap Freeze"
                    setOnClickListener { apply(listOf(app.packageName), !frozen) }
                }
                row.background = kit.rounded(if (selected) palette.accentSoft else palette.card, 20)
                return row
            }
        }
        list.adapter = adapter

        fun renderApps() {
            val frozenSet = OeaAppFreezer.allFrozen(this)
            visible = allApps.filter { app ->
                val matchesQuery = currentQuery.isBlank() || app.label.contains(currentQuery, true) || app.packageName.contains(currentQuery, true)
                val matchesFilter = when (currentFilter) {
                    "frozen" -> app.packageName in frozenSet
                    "active" -> app.packageName !in frozenSet
                    else -> true
                }
                matchesQuery && matchesFilter
            }
            summary.text = "${frozenSet.size} frozen  •  ${allApps.size} apps  •  ${visible.size} shown  •  ${selectedPackages.size} selected"
            filters.removeAllViews()
            listOf("all" to "All apps", "frozen" to "Frozen", "active" to "Not frozen").forEach { (mode, label) ->
                filters.addView(kit.chip(label, mode == currentFilter) {
                    currentFilter = mode
                    renderApps()
                }, LinearLayout.LayoutParams(-2, dp(38)).apply { rightMargin = dp(8) })
            }
            filters.addView(kit.chip(if (showSystemApps) "System apps shown" else "Show system apps", showSystemApps, R.drawable.oic_settings) {
                showSystemApps = !showSystemApps
                selectedPackages.clear()
                reload()
            }, LinearLayout.LayoutParams(-2, dp(38)))
            controls.removeAllViews()
            // CI regression guards: SELECT VISIBLE / REFRESH STATE.
            controls.addView(kit.quietButton("Select visible") {
                selectedPackages.addAll(visible.map { it.packageName })
                renderApps()
            }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(6) })
            controls.addView(kit.quietButton("Clear") {
                selectedPackages.clear()
                renderApps()
            }, LinearLayout.LayoutParams(0, dp(42), 0.7f).apply { rightMargin = dp(6) })
            controls.addView(kit.quietButton("Refresh state", R.drawable.oic_refresh) {
                toast("Refreshing installed apps and frozen state…")
                reload()
            }, LinearLayout.LayoutParams(0, dp(42), 1.2f))
            bulk.removeAllViews()
            bulk.addView(kit.primaryButton("Freeze selected", R.drawable.oic_ac_unit) { apply(selectedPackages.toList(), true) },
                LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(6) })
            bulk.addView(kit.tonalButton("Restore selected") { apply(selectedPackages.toList(), false) },
                LinearLayout.LayoutParams(0, dp(46), 1f))
            if (!systemMode && OeaAppFreezer.hibernatedPackages(this).isNotEmpty() && OeaAssistant.isConnected()) {
                bulk.addView(kit.circleIconButton(R.drawable.oic_bedtime, "Put frozen apps back to sleep", palette.cardHigh, palette.text) {
                    if (freezerOperationRunning || OeaAssistant.isForceStopping()) return@circleIconButton
                    freezerOperationRunning = true
                    val started = OeaAssistant.forceStop(OeaAppFreezer.hibernatedPackages(this).toList(), freezerReturnIntent()) { stopped, _ ->
                        freezerOperationRunning = false
                        toast("${stopped.size} frozen apps put back to sleep.")
                    }
                    if (!started) freezerOperationRunning = false
                }, LinearLayout.LayoutParams(dp(46), dp(46)).apply { leftMargin = dp(6) })
            }
            adapter.notifyDataSetChanged()
        }
        rerenderList = { renderApps() }
        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentQuery = s?.toString().orEmpty()
                renderApps()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        renderApps()
        setContentView(root)
        currentScroll = null
        reload()
    }

    private fun loadInstalledFreezerApps(includeSystemApps: Boolean = false): List<OeaAppInfo> =
        packageManager.getInstalledApplications(0)
            .filterNot { it.packageName == packageName }
            .filter { info ->
                includeSystemApps ||
                    (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 ||
                    (info.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            }
            .map { info ->
                OeaAppInfo(
                    packageName = info.packageName,
                    className = "",
                    label = runCatching { info.loadLabel(packageManager).toString() }.getOrDefault(info.packageName),
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }

    // =========================================================== multitasking

    private fun showMultitasking() {
        val page = page("Multitasking", "Use two apps at once, or float one above another")
        val box = page.content
        val group = kit.group(box)
        kit.row(group, R.drawable.oic_layers, "Overview", "Recently used apps with open, split and floating actions") { navigate("overview") }
        kit.row(group, R.drawable.oic_splitscreen, "Split screen & app pairs", if (Build.VERSION.SDK_INT >= 32) "Ready on this Android version" else if (OeaAssistant.isConnected()) "Ready with OEA Assistant" else "Needs OEA Assistant on this Android version") { navigate("split") }
        val freeform = OeaMultitaskLauncher.freeformEnabled(this)
        kit.row(group, R.drawable.oic_select_window, "Floating windows", if (freeform) "Enabled • long-press any app › Float" else "Off • enable in Developer options") {
            if (freeform) toast("Long-press any app on Home or in the drawer and tap Float.")
            else kit.confirm("Enable floating windows", "Open Settings › Developer options and turn on \"Enable freeform windows\" (and \"Force activities to be resizable\"), then restart your phone.", "Open Developer options") {
                if (!OeaMultitaskLauncher.openDeveloperOptions(this)) toast("Turn on Developer options first: Settings › About phone › tap Build number 7 times.")
            }
        }
        present(page)
    }

    /** OEA Overview: recent-use history, not Android's private task list. */
    private fun showMultitask() {
        val choices = apps().filter { it.packageName != packageName }.distinctBy { it.packageName }
        val page = page("Overview", "OEA app overview • Recent-use history, not Android’s live task stack. Open apps, use floating windows, or pair apps for split screen.")
        val box = page.content
        val usageAccess = getSystemService(AppOpsManager::class.java).checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName,
        ) == AppOpsManager.MODE_ALLOWED
        if (!usageAccess) {
            kit.banner(box, R.drawable.oic_insights, "Enable recent-app history", "Allow OEA Usage access so this overview can order apps by recent use.", OeaKit.Tone.INFO, "Allow") {
                refreshOverviewOnResume = true
                runCatching { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            }
        }
        val search = kit.field("Search apps or package names")
        box.addView(search, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        var showAllApps = false
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        box.addView(tabs, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        val overviewControls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        box.addView(overviewControls, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        val cardRail = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val list = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        cardRail.addView(list)
        box.addView(cardRail, LinearLayout.LayoutParams(-1, -2))

        val overviewNow = System.currentTimeMillis()
        val sevenDaysMillis = 7L * 24L * 60L * 60L * 1000L
        val usageManager = getSystemService(android.app.usage.UsageStatsManager::class.java)
        val lastUsed = if (usageAccess && usageManager != null) {
            runCatching {
                usageManager.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, overviewNow - sevenDaysMillis, overviewNow)
                    .groupBy { it.packageName }
                    .mapValues { (_, records) -> records.maxOfOrNull { it.lastTimeUsed } ?: 0L }
            }.getOrDefault(emptyMap())
        } else emptyMap()
        val allChoices = choices.sortedWith(compareByDescending<OeaAppInfo> { lastUsed[it.packageName] ?: 0L }.thenBy { it.label.lowercase() })
        // This is usage history, not Android's private active-task stack. Keep
        // the Recent tab honest by excluding apps with no recorded use this week.
        val recentChoices = allChoices.filter {
            val usedAt = lastUsed[it.packageName] ?: 0L
            usedAt > 0L && overviewNow - usedAt <= sevenDaysMillis
        }
        val overviewPrefs = getSharedPreferences("oea_overview", Context.MODE_PRIVATE)
        val hiddenRecent = overviewPrefs.getStringSet("hidden_recent_packages", emptySet())?.toMutableSet() ?: mutableSetOf()
        var render: (String) -> Unit = {}

        fun overviewTab(label: String) = label
        val recentLabel = overviewTab("Recent use")
        val allLabel = overviewTab("All apps")

        fun launchApp(app: OeaAppInfo) {
            protectedLaunch(app) {
                val launch = packageManager.getLaunchIntentForPackage(app.packageName)
                if (launch == null) toast("This app has no launchable activity.")
                else runCatching { startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { toast("Android could not open ${app.label}.") }
            }
        }

        fun splitWith(current: OeaAppInfo) {
            val others = choices.filter { it.packageName != current.packageName }
            kit.pickApps("Split ${current.label} with…", null, others, emptySet(), { it.packageName }, multi = false) { chosen ->
                val other = others.firstOrNull { it.packageName in chosen } ?: return@pickApps
                protectedLaunch(current) {
                    protectedLaunch(other) {
                        val result = OeaSplitLauncher.launchPair(this, current.packageName, other.packageName)
                        if (!result.success || result.message.startsWith("Turn on")) toast(result.message)
                    }
                }
            }
        }

        fun showAppMenu(app: OeaAppInfo) {
            val lockKey = app.packageName + "/" + app.className
            val locked = OeaAppLockStore.isLocked(this, lockKey)
            val options = listOf("Open", if (locked) "Unlock this app" else "Lock this app", "Open as floating window", "Split screen with…", "App info / force stop")
            kit.choose(app.label, app.packageName, options, -1) { which ->
                when (options[which]) {
                    "Open" -> launchApp(app)
                    "Lock this app" -> {
                        if (OeaAppLockStore.hasPin(this)) {
                            OeaAppLockStore.setLocked(this, lockKey, true)
                            toast("${app.label} locked by OEA.")
                        } else {
                            kit.prompt("Create App Lock PIN", "Set a 4–8 digit PIN to lock ${app.label}.", "PIN", numeric = true, password = true, confirmLabel = "Create and lock") { pin ->
                                if (pin.length !in 4..8 || !pin.all(Char::isDigit)) return@prompt "PIN must be 4–8 digits."
                                OeaAppLockStore.setPin(this, pin)
                                OeaAppLockStore.setLocked(this, lockKey, true)
                                toast("${app.label} locked by OEA.")
                                null
                            }
                        }
                    }
                    "Unlock this app" -> protectedLaunch(app) {
                        OeaAppLockStore.setLocked(this, lockKey, false)
                        toast("${app.label} unlocked by OEA.")
                    }
                    "App info / force stop" -> runCatching {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}")))
                    }.onFailure { toast("Android could not open app info.") }
                    "Open as floating window" -> protectedLaunch(app) { toast(OeaMultitaskLauncher.launchFloating(this, app).message) }
                    "Split screen with…" -> splitWith(app)
                }
            }
        }

        fun relativeUseTime(timestamp: Long): String {
            if (timestamp <= 0L) return "Not used in the last 7 days"
            val elapsed = (overviewNow - timestamp).coerceAtLeast(0L)
            return when {
                elapsed < 60_000L -> "Used just now"
                elapsed < 3_600_000L -> "Used ${elapsed / 60_000L} min ago"
                elapsed < 86_400_000L -> "Used ${elapsed / 3_600_000L} hr ago"
                else -> "Used ${elapsed / 86_400_000L} days ago"
            }
        }

        render = render@{ query ->
            tabs.removeAllViews()
            tabs.addView(kit.segmented(listOf(recentLabel, allLabel), if (showAllApps) 1 else 0) { index ->
                showAllApps = index == 1
                render(search.text?.toString().orEmpty())
            }, LinearLayout.LayoutParams(-1, -2))
            overviewControls.removeAllViews()
            overviewControls.visibility = if (showAllApps) View.GONE else View.VISIBLE
            // These controls manage only OEA's local recent-use list; they do not
            // claim to dismiss or terminate Android system tasks.
            overviewControls.addView(kit.quietButton("Clear OEA list", R.drawable.oic_cleaning_services) {
                hiddenRecent.addAll(recentChoices.map { it.packageName })
                overviewPrefs.edit().putStringSet("hidden_recent_packages", hiddenRecent.toSet()).apply()
                render(search.text?.toString().orEmpty())
                toast("Cleared OEA's recent-use list. Android tasks were not closed.")
            }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(6) })
            overviewControls.addView(kit.quietButton("Restore cards", R.drawable.oic_restart_alt) {
                hiddenRecent.clear()
                overviewPrefs.edit().remove("hidden_recent_packages").apply()
                render(search.text?.toString().orEmpty())
                toast("Recent-use cards restored.")
            }, LinearLayout.LayoutParams(0, dp(44), 1f))
            list.removeAllViews()
            val normalized = query.trim()
            val source = if (showAllApps) allChoices else recentChoices.filterNot { it.packageName in hiddenRecent }
            val shown = source.filter { normalized.isBlank() || it.label.contains(normalized, true) || it.packageName.contains(normalized, true) }
            if (shown.isEmpty()) {
                list.addView(kit.text(when {
                    !showAllApps && !usageAccess -> "Enable Usage access for recent-use cards, or choose All apps."
                    !showAllApps && recentChoices.isNotEmpty() && hiddenRecent.containsAll(recentChoices.map { it.packageName }) -> "Your OEA recent-use list is cleared. Tap Restore cards to show it again."
                    !showAllApps -> "No launchable apps have recorded use in the last 7 days."
                    else -> "No matching apps."
                }, 14f, palette.textSecondary).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(18), dp(32), dp(18), dp(32))
                }, LinearLayout.LayoutParams(dp(284), dp(240)))
                return@render
            }
            shown.take(60).forEach { app ->
                val locked = OeaAppLockStore.isLocked(this, app.packageName + "/" + app.className)
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    background = kit.rounded(palette.card, 28, palette.stroke, 1)
                }
                val preview = FrameLayout(this).apply {
                    background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(palette.accentSoft, palette.cardHigh)).apply { cornerRadius = dp(22).toFloat() }
                    contentDescription = "App card illustration; Android does not provide OEA a live task snapshot"
                    isClickable = true
                    setOnClickListener { launchApp(app) }
                }
                preview.addView(ImageView(this).apply { setImageDrawable(kit.appIcon(app)) }, FrameLayout.LayoutParams(dp(84), dp(84), Gravity.CENTER))
                preview.addView(kit.text(if (usageAccess) relativeUseTime(lastUsed[app.packageName] ?: 0L) else "Usage access off", 11.5f, palette.text, OeaType.medium).apply {
                    setPadding(dp(10), dp(5), dp(10), dp(5))
                    background = kit.rounded(OeaTheme.alpha(palette.background, 0xCC), 12)
                }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(12) })
                card.addView(preview, LinearLayout.LayoutParams(-1, dp(250)).apply { bottomMargin = dp(12) })
                val appRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                appRow.addView(ImageView(this).apply { setImageDrawable(kit.appIcon(app)) }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(10) })
                appRow.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(kit.text(app.label + if (locked) " • Locked" else "", 15f, palette.text, OeaType.medium).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                    addView(kit.text(app.packageName, 11f, palette.textTertiary).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE })
                }, LinearLayout.LayoutParams(0, -2, 1f))
                appRow.addView(kit.circleIconButton(R.drawable.oic_more_vert, "Actions for ${app.label}", palette.cardHigh, palette.text) { showAppMenu(app) },
                    LinearLayout.LayoutParams(dp(40), dp(40)))
                card.addView(appRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
                val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                actions.addView(kit.primaryButton("Open") { launchApp(app) }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(8) })
                actions.addView(kit.circleIconButton(R.drawable.oic_splitscreen, "Split screen with…", palette.cardHigh, palette.text) { splitWith(app) },
                    LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(8) })
                actions.addView(kit.circleIconButton(R.drawable.oic_select_window, "Open as floating window", palette.cardHigh, palette.text) {
                    protectedLaunch(app) { toast(OeaMultitaskLauncher.launchFloating(this, app).message) }
                }, LinearLayout.LayoutParams(dp(44), dp(44)))
                card.addView(actions)
                list.addView(card, LinearLayout.LayoutParams(dp(284), -2).apply { rightMargin = dp(12) })
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { render(s?.toString().orEmpty()) }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        render("")
        present(page)
    }

    // ============================================================ split screen

    private fun showSplit() {
        val page = page("Split screen", "Two apps side by side, and saved app pairs")
        val box = page.content
        when {
            Build.VERSION.SDK_INT >= 32 -> kit.banner(box, R.drawable.oic_check_circle_fill, "Ready", "This Android version opens split screen directly from OEA.", OeaKit.Tone.SUCCESS)
            OeaAssistant.isConnected() -> kit.banner(box, R.drawable.oic_check_circle_fill, "Ready with OEA Assistant", "OEA docks the first app, then opens the second beside it.", OeaKit.Tone.SUCCESS)
            else -> kit.banner(box, R.drawable.oic_accessibility_new, "Turn on OEA Assistant", "On Android 12 and older, OEA Assistant enters split screen for you.", OeaKit.Tone.INFO, "Turn on") { OeaAssistant.openSetup(this) }
        }
        box.addView(kit.primaryButton("Choose two apps", R.drawable.oic_splitscreen) { chooseSplitApps() }, LinearLayout.LayoutParams(-1, dp(50)).apply { bottomMargin = dp(6) })
        val pairs = OeaWorkspaceStore.get(this).folders().filter { it.isPair }
        kit.sectionTitle(box, "App pairs on Home")
        if (pairs.isEmpty()) {
            kit.note(box, "No pairs yet. After choosing two apps, tap Save pair to put a one-tap pair icon on Home.")
        } else {
            val group = kit.group(box)
            val byKey = apps().associateBy { it.packageName + "/" + it.className }
            pairs.forEach { pair ->
                val members = pair.members.mapNotNull(byKey::get)
                if (members.size < 2) return@forEach
                kit.row(group, R.drawable.oic_splitscreen, pair.title.ifBlank { members.joinToString(" | ") { it.label } }, members.joinToString(" + ") { it.label }) {
                    protectedLaunch(members[0]) {
                        protectedLaunch(members[1]) {
                            val result = OeaSplitLauncher.launchPair(this, members[0].packageName, members[1].packageName)
                            if (!result.success || result.message.startsWith("Turn on")) toast(result.message)
                        }
                    }
                }
            }
        }
        kit.note(box, "Some apps opt out of split screen; Android then opens them full screen.")
        present(page)
    }

    private fun chooseSplitApps() {
        val choices = apps().filter { it.packageName != packageName }.distinctBy { it.packageName }
        kit.pickApps("OEA Split Screen", "Select exactly two apps. Android controls the final divider and orientation.", choices, emptySet(), { it.packageName }, max = 2, confirmLabel = "Continue") { picked ->
            if (picked.size != 2) {
                toast("Choose two apps first.")
                return@pickApps
            }
            val first = choices.first { it.packageName == picked.first() }
            val second = choices.first { it.packageName == picked.last() }
            kit.sheet("${first.label} + ${second.label}", "Open them now, or save the pair to Home for one-tap launching.") { panel, dialog ->
                panel.addView(kit.buttonRow(
                    kit.quietButton("Save pair") {
                        dialog.dismiss()
                        OeaRuntime.engine(this).workspace.createPairFromSettings(first, second)
                        rerender()
                    },
                    kit.primaryButton("Open now") {
                        dialog.dismiss()
                        protectedLaunch(first) {
                            protectedLaunch(second) {
                                val result = OeaSplitLauncher.launchPair(this, first.packageName, second.packageName)
                                if (!result.success || result.message.startsWith("Turn on")) toast(result.message)
                            }
                        }
                    },
                ))
            }
        }
    }

    // ================================================================ access

    private fun showAccess() {
        val page = page("Permissions & access", "Everything OEA can use, and why")
        val box = page.content
        val group = kit.group(box)
        fun state(ok: Boolean) = if (ok) "On" else "Off"
        kit.row(group, R.drawable.oic_home, "Default Home app", homeRoleStatus(), value = state(isDefaultHome())) { requestDefaultHome() }
        kit.row(group, R.drawable.oic_notifications, "Notification access", "Dots on icons and Now Playing", value = state(notificationAccess())) {
            runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        }
        kit.row(group, R.drawable.oic_accessibility_new, "OEA Assistant", "Lock, notifications, Recents, split screen, App Freezer", value = state(OeaAssistant.isEnabled(this))) { OeaAssistant.openSetup(this) }
        kit.row(group, R.drawable.oic_insights, "Usage access", "Screen time, Overview order and Game Boost", value = state(usageAccess())) {
            runCatching { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        }
        kit.row(group, R.drawable.oic_layers, "Display over other apps", "Game Boost overlay and Now Playing island", value = state(Settings.canDrawOverlays(this))) {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
        }
        val contacts = checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        kit.row(group, R.drawable.oic_person, "Contacts", "Find people from drawer search", value = state(contacts)) {
            if (!contacts) requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), REQUEST_CONTACTS)
        }
        if (OeaAssistant.needsRestrictedSettingsHint() && !OeaAssistant.isEnabled(this)) kit.note(box, OeaAssistant.RESTRICTED_SETTINGS_HINT)
        present(page)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        rerender()
    }

    private fun usageAccess(): Boolean = runCatching {
        getSystemService(AppOpsManager::class.java)?.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    private fun accessSummary(): String {
        val granted = listOf(isDefaultHome(), notificationAccess(), OeaAssistant.isEnabled(this), usageAccess(), Settings.canDrawOverlays(this)).count { it }
        return "$granted of 5 enabled"
    }

    private fun isDefaultHome(): Boolean {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) return rm.isRoleHeld(RoleManager.ROLE_HOME)
        }
        val info = packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
        return info?.activityInfo?.packageName == packageName
    }

    private fun homeRoleStatus(): String = if (isDefaultHome()) "OEA is the current Home launcher" else "OEA is not the current Home launcher"

    private fun requestDefaultHome() {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm?.isRoleAvailable(RoleManager.ROLE_HOME) == true && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                if (runCatching { startActivity(rm.createRequestRoleIntent(RoleManager.ROLE_HOME)) }.isSuccess) return
            }
        }
        runCatching { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
    }

    // ================================================================= about

    private fun showAbout() {
        val info = runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull()
        val page = page("About OEA", null)
        val box = page.content
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            background = kit.rounded(palette.card, 28)
        }
        hero.addView(ImageView(this).apply { setImageDrawable(applicationInfo.loadIcon(packageManager)) }, LinearLayout.LayoutParams(dp(72), dp(72)))
        hero.addView(kit.text("OEA Launcher", 22f, palette.text, OeaType.medium).apply { setPadding(0, dp(12), 0, 0) })
        hero.addView(kit.text("Version ${info?.versionName ?: "?"}", 14f, palette.textSecondary))
        box.addView(hero, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        val group = kit.group(box)
        kit.row(group, R.drawable.oic_public, "Source & updates", "github.com/Emmanuel001afk/OEA") {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Emmanuel001afk/OEA"))) }
        }
        kit.row(group, R.drawable.oic_new_releases, "What's in OEA", null) {
            kit.sheet("OEA Launcher", null) { panel, _ ->
                kit.note(panel, "• OEA Home with At a Glance, smart drawer search, app shortcuts, widgets, folders and app pairs\n• Notification dots, icon shapes, themed icons and icon packs\n• Gestures: swipe down, double-tap to lock\n• App Lock, hidden apps and the no-root App Freezer\n• Split screen, floating windows and the OEA Overview\n• Game Boost, Phone and Call Blocker as their own apps")
            }
        }
        present(page)
    }

    @Suppress("unused")
    private fun pinField(): EditText = kit.field("4-8 digit PIN", numeric = true, password = true)
}
