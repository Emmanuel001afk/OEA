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
import android.os.Handler
import android.os.Looper
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
import com.oea.launcher.perf.OeaLowMode
import com.oea.launcher.power.OeaPower
import com.oea.launcher.fault.OeaFaultLog
import com.oea.launcher.fault.OeaFaultShield
import com.oea.launcher.freezer.OeaFreezerActivity
import com.oea.launcher.gameboost.OeaGameBoostActivity
import com.oea.launcher.gameboost.OeaGameBoostStore
import com.oea.launcher.multitask.OeaFloatingWindows
import com.oea.launcher.icons.OeaIconController
import com.oea.launcher.model.OeaAppInfo
import com.oea.launcher.model.OeaAppModel
import com.oea.launcher.multitask.OeaMultitaskLauncher
import com.oea.launcher.phone.OeaPhoneActivity
import com.oea.launcher.privilege.Capability
import com.oea.launcher.privilege.Grant
import com.oea.launcher.privilege.OeaShell
import com.oea.launcher.privilege.OeaPrivileges
import com.oea.launcher.privilege.Privilege
import com.oea.launcher.runtime.OeaRuntime
import com.oea.launcher.split.OeaSplitLauncher
import com.oea.launcher.ui.OeaInsets
import com.oea.launcher.miniapps.OeaMiniAppSwitches
import com.oea.launcher.ui.OeaKit
import com.oea.launcher.ui.OeaPalette
import com.oea.launcher.ui.OeaScreenFailure
import com.oea.launcher.ui.OeaTheme
import com.oea.launcher.ui.OeaType
import com.oea.launcher.wallpaper.OeaWallpaper
import com.oea.launcher.wallpaper.OeaWallpaperRenderer
import com.oea.launcher.wallpaper.OeaWallpaperStore
import com.oea.launcher.workspace.OeaWorkspaceStore
import com.oea.launcher.workspace.OeaDynamicIslandOverlayService
import com.oea.launcher.widgets.OeaWidgetStudio
import java.util.concurrent.Executors

/**
 * OEA Settings: one hub with focused sub-screens for Home, appearance, the
 * drawer, gestures, notifications, privacy, the App Freezer, multitasking,
 * permissions and the OEA apps (Phone, Call Blocker, Game Boost).
 */
open class OeaSystemToolsActivity : Activity() {
    /**
     * The screen this activity opens on and treats as its top level. OEA
     * Settings starts at the hub; the standalone Freezer app reuses the same
     * screens but starts (and finishes) at the freezer.
     */
    protected open val rootScreen: String get() = "hub"

    companion object {
        const val EXTRA_SCREEN = "oea_screen"
        private const val REQUEST_CONTACTS = 0x4F59
        private const val REQUEST_ISLAND_OVERLAY = 0x4F5A
        private const val REQUEST_DEVICE_ADMIN = 0x4F5B
        private const val FREEZER_LIST_FRESH_MS = 30_000L
    }

    private val dataStore by lazy { OeaDataStore.get(this) }
    /** Set while the access doctor is on screen, so a fix can re-verify at once. */
    private var doctorCheck: (() -> Unit)? = null
    private val doctorWorkers = Executors.newSingleThreadExecutor { task ->
        Thread(task, "OeaAccessDoctor").apply { isDaemon = true }
    }
    private val appQueryWorker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "OeaSettingsApps").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    private var appsCache: List<OeaAppInfo>? = null
    private var appsLoading = false
    private var appsLoadFailed = false
    private var appsLoadError: String? = null
    private var usageStatsCache: Map<String, Long>? = null
    private var usageStatsLoading = false
    private var usageStatsLoadFailed = false
    private var usageStatsLoadError: String? = null
    private lateinit var palette: OeaPalette
    private lateinit var kit: OeaKit
    private val stack = ArrayDeque<String>()
    private var current = "hub"
    private var currentScroll: android.widget.ScrollView? = null
    /**
     * Keep one Activity content root for the lifetime of Settings. Replacing the
     * decor content on every switch used to flash the status-bar/top area and
     * briefly detach the whole screen, making simple toggles look like reloads.
     */
    private var pageHost: FrameLayout? = null
    private val scrollPositions = HashMap<String, Int>()
    private var refreshOverviewOnResume = false
    private var pendingIslandOverlayEnable = false

    /** Returns the current snapshot immediately and never scans packages on the UI thread. */
    private fun apps(): List<OeaAppInfo> {
        appsCache?.let { return it }
        val fromHome = runCatching { OeaRuntime.engine(this).model.apps }.getOrDefault(emptyList())
        if (fromHome.isNotEmpty()) {
            appsCache = fromHome
            appsLoadFailed = false
            appsLoadError = null
            return fromHome
        }
        if (!appsLoading && !appsLoadFailed) loadAppsInBackground()
        return emptyList()
    }

    private fun loadAppsInBackground() {
        appsLoading = true
        try {
            appQueryWorker.execute {
                val result = runCatching { OeaAppModel(applicationContext).query() }
                main.post {
                    if (isDestroyed) return@post
                    appsLoading = false
                    result.fold(
                        onSuccess = { value ->
                            appsCache = value
                            appsLoadError = null
                            appsLoadFailed = false
                        },
                        onFailure = { error ->
                            appsLoadError = error.message ?: error.javaClass.simpleName
                            appsLoadFailed = true
                            android.util.Log.w("OeaSystemToolsActivity", "Could not load app catalogue", error)
                        }
)
                    if (!isFinishing) rerender()
                }
            }
        } catch (error: RuntimeException) {
            appsLoading = false
            appsLoadFailed = true
            appsLoadError = error.message ?: error.javaClass.simpleName
            android.util.Log.w("OeaSystemToolsActivity", "Could not queue app catalogue load", error)
        }
    }

    private fun retryAppLoad() {
        appsLoadFailed = false
        appsLoadError = null
        loadAppsInBackground()
        rerender()
    }

    private fun requireApps(): List<OeaAppInfo>? {
        val value = apps()
        if (appsCache == null) {
            if (appsLoadFailed) {
                toast("App list could not load. Retrying now.")
                retryAppLoad()
            } else {
                toast("App list is loading. Try again in a moment.")
            }
            return null
        }
        if (value.isEmpty()) {
            toast("No launchable apps found.")
            return null
        }
        return value
    }

    private fun recentUsageStats(hasAccess: Boolean, now: Long, windowMillis: Long): Map<String, Long> {
        if (!hasAccess) return emptyMap()
        usageStatsCache?.let { return it }
        if (!usageStatsLoading && !usageStatsLoadFailed) {
            val manager = getSystemService(android.app.usage.UsageStatsManager::class.java)
            if (manager == null) {
                usageStatsLoadFailed = true
                usageStatsLoadError = "Usage history is not available on this device."
                return emptyMap()
            }
            usageStatsLoading = true
            val start = now - windowMillis
            try {
                appQueryWorker.execute {
                    val result = runCatching {
                        manager.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, start, now)
                            .orEmpty()
                            .groupBy { it.packageName }
                            .mapValues { (_, records) -> records.maxOfOrNull { it.lastTimeUsed } ?: 0L }
                    }
                    main.post {
                        if (isDestroyed) return@post
                        usageStatsLoading = false
                        result.fold(
                            onSuccess = { usage ->
                                usageStatsCache = usage
                                usageStatsLoadError = null
                                usageStatsLoadFailed = false
                            },
                            onFailure = { error ->
                                usageStatsLoadError = error.message ?: error.javaClass.simpleName
                                usageStatsLoadFailed = true
                                android.util.Log.w("OeaSystemToolsActivity", "Could not load usage history", error)
                            }
)
                        if (!isFinishing && current == "overview") rerender()
                    }
                }
            } catch (error: RuntimeException) {
                usageStatsLoading = false
                usageStatsLoadError = error.message ?: error.javaClass.simpleName
                usageStatsLoadFailed = true
            }
        }
        return emptyMap()
    }

    private fun retryUsageStats() {
        usageStatsCache = null
        usageStatsLoadError = null
        usageStatsLoadFailed = false
        rerender()
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        com.oea.launcher.assistant.OeaAssistant.addListener(wirelessListener)
        if (rootScreen != "hub") {
            current = rootScreen
            show(current)
            return
        }
        when (intent.getStringExtra(EXTRA_SCREEN)) {
            "call_blocker" -> { openActivity(OeaCallBlockerActivity::class.java); finish(); return }
            "game_boost" -> { openActivity(OeaGameBoostActivity::class.java); finish(); return }
            "freezer" -> { openActivity(OeaFreezerActivity::class.java); finish(); return }
            "multitask" -> current = "overview"
            "split" -> current = "split"
            "home" -> current = "home"
            "notifications" -> current = "notifications"
            "power" -> current = "power"
            "games" -> { openActivity(com.oea.launcher.games.OeaGamesActivity::class.java); finish(); return }
            "stability" -> current = "stability"
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
        if (pendingIslandOverlayEnable) {
            finishIslandOverlayRequest(cameFromResult = false)
        } else if (dataStore.islandOnOtherApps()) {
            // Keep persisted UI state honest if the user revoked overlay access
            // from Android Settings while OEA was in the background.
            if (OeaPrivileges.canOverlay(this)) {
                OeaDynamicIslandOverlayService.sync(this)
            } else {
                dataStore.setIslandOnOtherApps(false)
                OeaDynamicIslandOverlayService.sync(this)
                toast("Display over other apps access was removed; Dynamic Island is paused.")
            }
        }
        // Permission screens may have changed what a row says; re-render in place.
        if (current != "overview" || refreshOverviewOnResume) {
            if (refreshOverviewOnResume) {
                usageStatsCache = null
                usageStatsLoadFailed = false
                usageStatsLoadError = null
            }
            refreshOverviewOnResume = false
            rerender()
        }
    }

    /** Access Doctor's Wireless debugging card refreshes as OEA Assistant reads values. */
    private val wirelessListener: () -> Unit = {
        if (current == "access_doctor" && !isDestroyed) main.post { if (current == "access_doctor" && !isDestroyed) rerender() }
    }

    override fun onDestroy() {
        com.oea.launcher.assistant.OeaAssistant.removeListener(wirelessListener)
        main.removeCallbacksAndMessages(null)
        appQueryWorker.shutdownNow()
        super.onDestroy()
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
        try {
            palette = OeaTheme.resolve(this)
            kit = OeaKit(this, palette)
            OeaInsets.edgeToEdge(window, lightBars = !palette.dark)
            runCatching { window.decorView.setBackgroundColor(palette.background) }
            if (screen != "power") stopWaveTest()
            when (screen) {
                "power" -> showPower()
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
                "access_doctor" -> showAccessDoctor()
                "stability" -> showStability()
                "wallpaper" -> showWallpaperGallery()
                "miniapps" -> showMiniApps()
                "modes" -> showModes()
                "low" -> showLowMode()
                "about" -> showAbout()
                else -> showSettings()
            }
        } catch (error: Throwable) {
            pageHost = null
            OeaScreenFailure.show(this, "OEA Settings", error)
        }
    }

    private fun page(title: String, subtitle: String?): OeaKit.Page {
        val root = current == rootScreen && stack.isEmpty()
        // The OEA Settings hub carries the OEA brand mark.
        val brand = if (root && rootScreen == "hub") ImageView(this).apply {
            setImageResource(R.drawable.oea_logo)
            contentDescription = "OEA"
        } else null
        val page = kit.page(title, subtitle, onBack = if (root) null else ({ back() }), trailing = brand)
        brand?.layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        return page
    }

    private fun present(page: OeaKit.Page) {
        val host = pageHost ?: FrameLayout(this).also {
            pageHost = it
            setContentView(it)
        }
        // Attach the fully built page before removing the visible page. This
        // avoids a blank frame at the top of Settings when a toggle triggers
        // rerender(), while keeping the Activity window and system bars stable.
        val previous = host.getChildAt(0)
        host.addView(page.root, FrameLayout.LayoutParams(-1, -1))
        if (previous != null) host.removeView(previous)
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
        kit.row(group, R.drawable.oic_wallpaper, "Themes & wallpapers", "Opens the OEA Themes app") { openActivity(com.oea.launcher.themes.OeaThemesActivity::class.java) }
        kit.row(group, R.drawable.oic_apps, "App drawer", if (dataStore.drawerMode() == OeaDataStore.DrawerMode.VERTICAL) "List • ${hiddenCount()} hidden" else "Grid • ${hiddenCount()} hidden") { navigate("drawer") }
        kit.row(group, R.drawable.oic_gesture, "Gestures", "Double-tap: ${labelFor(dataStore.doubleTapAction(), mapOf("lock" to "lock screen", "drawer" to "app drawer", "power_menu" to "power menu", "quick_controls" to "quick controls", "none" to "off"))}") { navigate("gestures") }
        kit.row(group, R.drawable.oic_power, "Buttons & screen", powerSummary()) { navigate("power") }
        group = kit.group(box)
        kit.row(group, R.drawable.oic_notifications, "Notification dots", if (notificationAccess()) labelFor(dataStore.badgeStyle(), mapOf("dot" to "Dots", "count" to "Counts", "off" to "Off")) else "Needs notification access") { navigate("notifications") }
        kit.row(group, R.drawable.oic_shield, "Privacy & App Lock", "${OeaAppLockStore.lockedApps(this).size} locked • ${hiddenCount()} hidden") { navigate("privacy") }
        kit.row(group, R.drawable.oic_ac_unit, "Freezer", freezerStatus()) { openActivity(OeaFreezerActivity::class.java) }
        group = kit.group(box)
        kit.row(group, R.drawable.oic_splitscreen, "Multitasking", "Overview, split screen and floating windows") { navigate("multitasking") }
        kit.row(group, R.drawable.oic_sports_esports, "Game Boost", if (OeaGameBoostStore.enabled(this)) "On • ${OeaGameBoostStore.activeGameCount(this)} games" else "Off") { openActivity(OeaGameBoostActivity::class.java) }
        kit.row(group, R.drawable.oic_apps, "Mini-apps", "${OeaMiniAppSwitches.countOn(this)} of ${com.oea.launcher.miniapps.OeaMiniApps.all.size} on • turn each one on or off") { navigate("miniapps") }
        kit.row(group, R.drawable.oic_call, "Phone", "OEA dialer, contacts and in-call screen") { openActivity(OeaPhoneActivity::class.java) }
        kit.row(group, R.drawable.oic_block, "Call Blocker", "Blocked numbers, allow list, rules and history") { openActivity(OeaCallBlockerActivity::class.java) }
        kit.row(group, R.drawable.oic_home, "Add OEA apps to Home", "Endless Days, Send Off, Chess, Game Boost, Phone, Call Blocker, Freezer and OEA Settings as normal Home icons") { addOeaAppsToHome() }
        group = kit.group(box)
        kit.row(group, R.drawable.oic_accessibility_new, "Permissions & access", accessSummary()) { navigate("access") }
        kit.row(group, R.drawable.oic_developer_mode, "Access doctor", "Each permission verified live, with the exact fix — including the ones that look switched on but are not working") { navigate("access_doctor") }
        kit.row(group, R.drawable.oic_battery_charging_full, "LOW mode", OeaLowMode.summary(this)) { navigate("low") }
        kit.row(group, R.drawable.oic_shield_fill, "Stability", stabilitySummary()) { navigate("stability") }
        kit.row(group, R.drawable.oic_info, "About OEA", "Version $version") { navigate("about") }
        present(page)
    }

    private fun labelFor(value: String, labels: Map<String, String>) = labels[value] ?: value

    /** OEA's built-in apps are ordinary launcher entries; let the user pin any of them to Home. */
    /**
     * Mini-apps: one switch per app. Off removes the app from the drawer, search,
     * Recents and the Home picker through Android's own component switch; its
     * data stays where it is, and turning it back on restores it.
     */
    private fun showMiniApps() {
        val page = page("Mini-apps", "Built into OEA, no download. Turn off the ones you never use.")
        val box = page.content
        val group = kit.group(box)
        com.oea.launcher.miniapps.OeaMiniApps.all.forEach { app ->
            kit.switchRow(group, app.icon, app.label, app.blurb, OeaMiniAppSwitches.isOn(this, app.id)) { checked ->
                if (!OeaMiniAppSwitches.set(this, app.id, checked)) {
                    toast("Android would not change ${app.label}. Try again in a moment.")
                }
                rerender()
            }
        }
        kit.note(box, "A mini-app that is off leaves the app drawer, search, Recents and the Home picker. Its settings and files are kept, and turning it back on brings it back exactly as it was.")
        present(page)
    }

    private fun addOeaAppsToHome() {
        val builtIns = (requireApps() ?: return).filter { it.packageName == packageName }
        if (builtIns.isEmpty()) {
            toast("OEA apps are still loading. Try again in a moment.")
            return
        }
        kit.pickApps("Add OEA apps to Home", "Each app gets its own icon on Home, just like any other app.", builtIns, emptySet(),
            { it.packageName + "/" + it.className }, confirmLabel = "Add to Home") { chosen ->
            if (chosen.isEmpty()) return@pickApps
            homeAction(com.oea.launcher.workspace.OeaWorkspace.HOME_ACTION_ADD_APPS + chosen.joinToString(","))
        }
    }

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
        kit.row(group, R.drawable.oic_auto_awesome, "OEA Widget Studio", "Choose an original look and surface opacity for OEA's own widgets") { openWidgetStudio() }
        kit.switchRow(group, R.drawable.oic_lock, "Lock Home layout", "Prevents icons and widgets from being moved or removed by accident", dataStore.homeLayoutLocked()) { dataStore.setHomeLayoutLocked(it) }
        kit.row(group, R.drawable.oic_home, "Default Home app", homeRoleStatus()) { requestDefaultHome() }
        kit.note(box, "Tip: long-press an empty spot on Home for wallpaper, widgets and pages. Long-press an icon for shortcuts, split screen, floating window, freeze and lock.")
        present(page)
    }

    private fun openWidgetStudio() {
        kit.sheet("OEA Widget Studio", "Personalize the look and a few live details of OEA's own widgets.") { panel, dialog ->
            kit.note(panel, "Choose a widget family for its own style override. Choosing All OEA widgets sets a shared fallback and clears family overrides. Other apps' widgets are unchanged.")

            var family = OeaWidgetStudio.families.first()
            var selected = OeaWidgetStudio.selected(this, family.id)
            var opacity = OeaWidgetStudio.opacity(this, family.id)
            val preview = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(18), dp(16), dp(18), dp(16))
            }
            val previewPrimary = kit.text("09:41", 30f, selected.primary, OeaType.medium)
            val previewSecondary = kit.text("Thursday · Lagos", 12f, selected.secondary)
            val previewDescription = kit.text("", 12.5f, palette.textSecondary)
            preview.addView(previewPrimary)
            preview.addView(previewSecondary)
            fun refreshPreview() {
                preview.background = kit.rounded(selected.surface(opacity), 22, OeaTheme.alpha(selected.accent, 0x88), 1)
                previewPrimary.setTextColor(selected.primary)
                previewSecondary.setTextColor(selected.secondary)
                val sample = when (family.id) {
                    "weather" -> "${OeaWidgetStudio.temperature(28.0, OeaWidgetStudio.weatherUsesFahrenheit(this))}  ·  Partly cloudy"
                    "calendar" -> "October 2026  ·  3 events"
                    "notes" -> "Notes  ·  Remember to…"
                    "image" -> "EMAGE  ·  4 favourites"
                    "video" -> "VIP  ·  2 favourites"
                    "calculator" -> "128 + 64  =  192"
                    "camera" -> "OEA Camera  ·  Tap to open"
                    else -> "Thursday · Lagos"
                }
                previewPrimary.text = when (family.id) {
                    "weather" -> OeaWidgetStudio.temperature(28.0, OeaWidgetStudio.weatherUsesFahrenheit(this))
                    "calendar" -> "October 2026"
                    "notes" -> "Notes"
                    "image" -> "EMAGE"
                    "video" -> "VIP"
                    "calculator" -> "192"
                    "camera" -> "Camera"
                    else -> "09:41"
                }
                previewSecondary.text = sample
                previewDescription.text = "${family.description}  ${selected.description}"
            }
            refreshPreview()
            panel.addView(preview, LinearLayout.LayoutParams(-1, dp(112)).apply { topMargin = dp(8); bottomMargin = dp(8) })
            panel.addView(previewDescription, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

            val familyBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            panel.addView(kit.hscroll(familyBar), LinearLayout.LayoutParams(-1, -2))
            var renderFamilyBar: () -> Unit = {}
            var renderPresetBar: () -> Unit = {}
            var renderFamilyOptions: () -> Unit = {}
            var opacityControl: android.widget.SeekBar? = null
            var opacityLabelView: TextView? = null
            var applyButtonView: TextView? = null

            renderFamilyBar = {
                familyBar.removeAllViews()
                OeaWidgetStudio.families.forEach { option ->
                    familyBar.addView(kit.chip(option.label, option.id == family.id) {
                        family = option
                        selected = OeaWidgetStudio.selected(this, family.id)
                        opacity = OeaWidgetStudio.opacity(this, family.id)
                        opacityControl?.progress = opacity - OeaWidgetStudio.MIN_OPACITY
                        opacityLabelView?.text = "Surface opacity · $opacity%"
                        applyButtonView?.text = "Apply to ${family.label}"
                        renderFamilyBar()
                        renderPresetBar()
                        renderFamilyOptions()
                        refreshPreview()
                    }, LinearLayout.LayoutParams(-2, dp(36)).apply { rightMargin = dp(7) })
                }
            }

            val presetBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            panel.addView(kit.hscroll(presetBar), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            renderPresetBar = {
                presetBar.removeAllViews()
                OeaWidgetStudio.presets.forEach { preset ->
                    presetBar.addView(kit.chip(preset.label, preset.id == selected.id) {
                        selected = preset
                        renderPresetBar()
                        refreshPreview()
                    }, LinearLayout.LayoutParams(-2, dp(36)).apply { rightMargin = dp(7) })
                }
            }

            val options = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            panel.addView(options, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            renderFamilyOptions = {
                options.removeAllViews()
                when (family.id) {
                    "clock" -> {
                        val group = kit.group(options)
                        kit.switchRow(group, R.drawable.oic_schedule, "24-hour time", "Use 00:00–23:59 instead of AM/PM", OeaWidgetStudio.clockUses24Hour(this)) { use24Hour ->
                            OeaWidgetStudio.saveClockOptions(this, use24Hour, OeaWidgetStudio.clockShowsDate(this))
                        }
                        kit.switchRow(group, R.drawable.oic_schedule, "Show date", "Keep the date line in the clock widget", OeaWidgetStudio.clockShowsDate(this)) { showDate ->
                            OeaWidgetStudio.saveClockOptions(this, OeaWidgetStudio.clockUses24Hour(this), showDate)
                        }
                    }
                    "weather" -> {
                        kit.sectionTitle(options, "Temperature unit")
                        val unitChoices = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                        val fahrenheit = OeaWidgetStudio.weatherUsesFahrenheit(this)
                        unitChoices.addView(kit.chip("Celsius · °C", !fahrenheit) {
                            OeaWidgetStudio.saveWeatherUnit(this, false)
                            renderFamilyOptions()
                            refreshPreview()
                        }, LinearLayout.LayoutParams(-2, dp(36)).apply { rightMargin = dp(7) })
                        unitChoices.addView(kit.chip("Fahrenheit · °F", fahrenheit) {
                            OeaWidgetStudio.saveWeatherUnit(this, true)
                            renderFamilyOptions()
                            refreshPreview()
                        }, LinearLayout.LayoutParams(-2, dp(36)))
                        options.addView(unitChoices)
                    }
                }
            }
            renderFamilyBar()
            renderPresetBar()
            renderFamilyOptions()

            val opacityLabel = kit.text("Surface opacity · $opacity%", 13f, palette.textSecondary).apply { setPadding(0, dp(12), 0, dp(2)) }
            opacityLabelView = opacityLabel
            panel.addView(opacityLabel)
            val opacitySeekBar = android.widget.SeekBar(this).apply {
                max = OeaWidgetStudio.MAX_OPACITY - OeaWidgetStudio.MIN_OPACITY
                progress = opacity - OeaWidgetStudio.MIN_OPACITY
                contentDescription = "Widget surface opacity"
            }
            opacityControl = opacitySeekBar
            opacitySeekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    opacity = OeaWidgetStudio.MIN_OPACITY + progress
                    opacityLabel.text = "Surface opacity · $opacity%"
                    refreshPreview()
                }
                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
            })
            panel.addView(opacitySeekBar, LinearLayout.LayoutParams(-1, dp(44)))
            val applyButton = kit.primaryButton("Apply to ${family.label}", R.drawable.oic_check) {
                OeaWidgetStudio.save(this, family.id, selected.id, opacity)
                dialog.dismiss()
                Toast.makeText(this, "${family.label} style updated", Toast.LENGTH_SHORT).show()
            }
            applyButtonView = applyButton
            panel.addView(applyButton, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) })
        }
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
        val page = page("Appearance", "Modes, theme, colours, icons and motion")
        val box = page.content
        kit.sectionTitle(box, "Mode")
        var modeGroup = kit.group(box)
        kit.row(modeGroup, R.drawable.oic_auto_awesome, "Launcher mode", modeSummary()) { navigate("modes") }
        kit.note(box, "A mode sets the surfaces, motion, icons and wallpaper together. Everything it sets stays editable below, and nothing you change here flips the mode behind your back.")
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
        kit.sectionTitle(box, "Surfaces & motion")
        group = kit.group(box)
        choiceRow(group, R.drawable.oic_layers, "Glass style", listOf(OeaDataStore.GLASS_CLASSIC to "Classic", OeaDataStore.GLASS_LIQUID to "Liquid glass"), dataStore.glassStyle()) {
            dataStore.setGlassStyle(it)
        }
        choiceRow(group, R.drawable.oic_view_carousel, "Page transition", listOf(
            "classic" to "Classic", "liquid" to "Liquid", "cube" to "Cube", "stack" to "Stack", "zoom" to "Zoom", "fade" to "Fade", "none" to "None"
), dataStore.pageEffect()) { dataStore.setPageEffect(it) }
        choiceRow(group, R.drawable.oic_animation, "Animation speed", listOf("0" to "Relaxed", "1" to "Standard", "2" to "Fast"), dataStore.animationSpeed().toString()) {
            dataStore.setAnimationSpeed(it.toInt())
        }
        kit.sectionTitle(box, "Themes & wallpaper")
        group = kit.group(box)
        kit.row(group, R.drawable.oic_palette, "Themes & wallpapers", "Opens the OEA Themes app: themes, your own themes and the wallpaper library") {
            openActivity(com.oea.launcher.themes.OeaThemesActivity::class.java)
        }
        kit.row(group, R.drawable.oic_wallpaper, "OEA wallpapers", oeaWallpaperSummary()) { navigate("wallpaper") }
        kit.row(group, R.drawable.oic_palette, "Theme follows wallpaper", wallpaperThemeSummary()) {
            val next = !OeaWallpaperStore.themeFollowsScene(this)
            OeaWallpaperStore.setThemeFollowsScene(this, next)
            toast(if (next) "OEA's colours will follow the wallpaper scene." else "OEA keeps the accent you chose.")
            rerender()
        }
        present(page)
    }

    private fun accentRow(group: LinearLayout) {
        val options = listOf("wallpaper", "blue", "teal", "violet", "rose", "amber", "green", "indigo", "coral", "lime", "slate")
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
                "indigo" -> Color.rgb(92, 107, 192)
                "coral" -> Color.rgb(255, 112, 67)
                "lime" -> Color.rgb(156, 204, 101)
                "slate" -> Color.rgb(120, 144, 156)
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

    // ================================================================== modes

    private fun modeSummary(): String {
        val mode = com.oea.launcher.ui.OeaModes.current(this)
        return if (mode.isLive) "${mode.label} · live" else mode.label
    }

    /**
     * The mode picker. Each mode is a preset the user can then edit; the screen
     * says exactly what a mode will set, so nothing here is a surprise, and a
     * theme's own colours are never overwritten by the preview.
     */
    /**
     * The mode picker: the one screen that sets the whole look at once.
     *
     * Each entry says exactly what it changes — surfaces, motion, icons and the
     * scene — and what it does *not* touch, because a mode that silently moved the
     * user's wallpaper would be a bad surprise. Everything a mode sets stays
     * editable in Appearance afterwards, and the chosen mode is remembered.
     */
    private fun showModes() {
        val page = page("Launcher mode", "One choice that sets the whole look")
        val box = page.content
        val current = com.oea.launcher.ui.OeaModes.current(this)
        kit.note(box, "A mode sets the surfaces, the page motion, the icon shape and the scene behind your icons together. Nothing else changes: same apps, same folders, same layout. Android's wallpaper is only touched by the modes that say so.")

        com.oea.launcher.ui.OeaMode.entries.forEach { mode ->
            val spec = com.oea.launcher.ui.OeaModes.spec(mode)
            val inUse = mode == current
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = kit.rounded(
                    if (inUse) OeaTheme.blend(palette.card, palette.accent, 0.14f) else palette.card,
                    24,
                    if (inUse) OeaTheme.alpha(palette.accent, 0x55) else palette.stroke,
                    1
)
                isClickable = true
                setOnClickListener { applyMode(mode) }
            }
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(kit.text(mode.label, 17f, palette.text, OeaType.medium), LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(modePreview(spec), LinearLayout.LayoutParams(dp(78), dp(42)).apply { rightMargin = dp(10) })
            if (inUse) head.addView(kit.text("In use", 12f, palette.accent, OeaType.medium))
            card.addView(head)
            card.addView(kit.text(mode.tagline, 13.5f, palette.accent, OeaType.medium).apply { setPadding(0, dp(4), 0, 0) })
            card.addView(kit.text(mode.detail, 12.5f, palette.textSecondary).apply {
                setPadding(0, dp(5), 0, 0)
                setLineSpacing(dp(2).toFloat(), 1f)
            })
            val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(9), 0, 0) }
            modeChips(spec).forEach { chip ->
                chips.addView(kit.text(chip, 11f, palette.textSecondary, OeaType.medium).apply {
                    setPadding(dp(9), dp(3), dp(9), dp(3))
                    background = kit.rounded(palette.cardHigh, 20, palette.stroke, 1)
                }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
            }
            card.addView(chips)
            if (mode.isLive) {
                card.addView(kit.text("Live scene drawn by Home · Android's wallpaper untouched", 11.5f, palette.accent).apply { setPadding(0, dp(8), 0, 0) })
            }
            box.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        }

        kit.sectionTitle(box, "The scene behind your icons")
        val details = kit.group(box)
        kit.row(details, R.drawable.oic_wallpaper, "Wallpaper source", wallpaperSourceSummary()) { navigate("wallpaper") }
        kit.switchRow(details, R.drawable.oic_contrast, "Theme follows the scene", "The accent and Home's text colour are taken from the artwork", com.oea.launcher.wallpaper.OeaWallpaperStore.themeFollowsScene(this)) {
            com.oea.launcher.wallpaper.OeaWallpaperStore.setThemeFollowsScene(this, it)
            com.oea.launcher.ui.OeaTheme.invalidate()
            rerender()
        }
        kit.stepperRow(details, R.drawable.oic_gesture, "Parallax", "How far layered scenes move with your pages", dataStore.parallaxStrength() / 10, 0, 10, { "${it * 10}%" }) {
            dataStore.setParallaxStrength(it * 10)
        }
        kit.note(box, "Tide, Depth and Ink are OEA's own: Home draws them itself, so nothing is written to Android's wallpaper and the lock screen keeps whatever you set there.")
        present(page)
    }

    /** A tiny strip of what a mode looks like: the surface, its accent, its scene. */
    private fun modePreview(spec: com.oea.launcher.ui.ModeSpec): View {
        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val accent = spec.scene?.let { com.oea.launcher.wallpaper.OeaLiveScenes.accent(it, !palette.dark) } ?: palette.accent
        val scene = spec.scene?.let { com.oea.launcher.wallpaper.OeaLiveScenes.accent(it, true) } ?: palette.cardHigh
        listOf(palette.glass, accent, scene).forEachIndexed { index, colour ->
            strip.addView(View(this).apply {
                background = kit.rounded(colour, 8, OeaTheme.alpha(palette.stroke, 0x66), 1)
            }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { if (index < 2) rightMargin = dp(3) })
        }
        return strip
    }

    /** Plain-language chips: what this mode changes. */
    private fun modeChips(spec: com.oea.launcher.ui.ModeSpec): List<String> {
        val chips = mutableListOf<String>()
        when (spec.glass) {
            OeaDataStore.GLASS_LIQUID -> chips += "Liquid glass"
            OeaDataStore.GLASS_CLASSIC -> chips += "Classic surfaces"
        }
        spec.effect?.let { chips += "Motion: $it" }
        spec.iconShape?.let { shape -> chips += "Icons: ${shape.replaceFirstChar { letter -> letter.uppercase() }}" }
        if (spec.wallpaperSource == OeaDataStore.WALLPAPER_OEA) chips += "Drawn by Home"
        return chips
    }

    private fun applyMode(mode: com.oea.launcher.ui.OeaMode) {
        com.oea.launcher.ui.OeaModes.apply(this, mode)
        toast("${mode.label} mode applied")
        rerender()
    }

    private fun wallpaperSourceSummary(): String =
        if (dataStore.wallpaperSource() == OeaDataStore.WALLPAPER_OEA) "Drawn by Home · ${com.oea.launcher.wallpaper.OeaHomeScene.describe(this)}"
        else "Android's wallpaper · ${com.oea.launcher.wallpaper.OeaHomeScene.describe(this)}"

    // =============================================================== low mode

    /**
     * LOW mode's screen. Every row states what the mode actually does on *this*
     * phone, including which layers are unavailable without device owner or root.
     */
    /**
     * LOW mode's own screen.
     *
     * It states what the mode actually does on *this* phone, and separates the
     * parts that always work from the parts that need a role the phone may not have
     * granted, so the user never has to guess which of the two they are looking at.
     */
    private fun showLowMode() {
        val page = page("LOW mode", "OEA's own power saving")
        val box = page.content
        val active = OeaLowMode.active(this)
        val (percent, charging) = OeaLowMode.battery(this)

        if (active) {
            kit.banner(box, R.drawable.oic_battery_charging_full, "LOW mode is on",
                OeaLowMode.summary(this).replaceFirstChar { letter -> letter.uppercase() } + ". Home is running light.", OeaKit.Tone.INFO, "Turn off") {
                OeaLowMode.setEnabled(this, false)
                rerender()
            }
        } else {
            val body = when {
                percent in 0..100 && charging -> "$percent% and charging. OEA will not switch itself on while the phone is plugged in."
                percent in 0..100 -> "$percent% battery."
                else -> "The battery level is not readable on this phone, so only the manual switch applies."
            }
            kit.banner(box, R.drawable.oic_battery_full, "LOW mode is off", body, OeaKit.Tone.INFO)
        }

        kit.sectionTitle(box, "What it turns off")
        kit.note(box, "OEA's own costs, and only OEA's: the live scene under your icons stops animating, pages stop moving, At a Glance and the islands hide, the system-wide island overlay service stops, Game Boost sessions end and stay off, and the wave and lift sensors are released. No app is killed and nothing of yours is changed, so everything comes back exactly as it was the moment LOW mode ends.")

        kit.sectionTitle(box, "Switching")
        val switching = kit.group(box)
        kit.switchRow(switching, R.drawable.oic_battery_charging_full, "LOW mode", "Turn it on now", active) {
            OeaLowMode.setEnabled(this, it)
            rerender()
        }
        kit.switchRow(switching, R.drawable.oic_battery_full, "Turn on by itself", "When the battery gets low, without asking", dataStore.lowModeAuto()) {
            dataStore.setLowModeAuto(it)
            rerender()
        }
        kit.stepperRow(switching, R.drawable.oic_insights, "Turn on at", "Battery percentage", dataStore.lowModeThreshold() / 5, 1, 10, { "${it * 5}%" }) {
            dataStore.setLowModeThreshold(it * 5)
        }
        kit.stepperRow(switching, R.drawable.oic_refresh, "Turn off at", "Or as soon as the phone is charging", dataStore.lowModeExit() / 5, 2, 16, { "${it * 5}%" }) {
            dataStore.setLowModeExit(it * 5)
        }
        kit.note(box, "One that switched itself on also switches itself off when the battery recovers or the phone is plugged in. One you turned on by hand stays on until you turn it off.")

        kit.sectionTitle(box, "Going further")
        val deep = kit.group(box)
        val backend = OeaAppFreezer.backend(this)
        if (backend != OeaAppFreezer.Backend.DEVICE_OWNER &&
            backend != OeaAppFreezer.Backend.ROOT &&
            backend != OeaAppFreezer.Backend.ADB) {
            kit.row(deep, R.drawable.oic_info, "Suspend chosen apps",
                "Ordinary Device Admin cannot suspend apps. Pair OEA Authority in Access Doctor, or use Device Owner/root.",
                chevron = false)
        } else {
            kit.switchRow(deep, R.drawable.oic_block, "Suspend chosen apps",
                "Stops the apps you pick while LOW mode is on (${backend.name.lowercase().replace('_', ' ')})", dataStore.lowModeSuspendApps()) {
                dataStore.setLowModeSuspendApps(it)
                OeaLowMode.apply(this)
                rerender()
            }
            kit.row(deep, R.drawable.oic_apps, "Apps to suspend", "${dataStore.lowModeAppList().size} chosen") { chooseLowModeApps() }
        }
        present(page)
    }

    /** Pick the apps LOW mode suspends; only reachable with a backend that can. */
    private fun chooseLowModeApps() {
        val apps = requireApps() ?: return
        val chosen = dataStore.lowModeAppList()
        val names = apps.sortedBy { it.label.lowercase() }
        kit.sheet("Apps to suspend", "Tap to add or remove; OEA verifies every change with Android") { panel, dialog ->
            names.forEach { app ->
                val selected = chosen.contains(app.packageName)
                kit.row(panel, 0, (if (selected) "✓ " else "") + app.label, null, chevron = false) {
                    val next = dataStore.lowModeAppList().toMutableSet()
                    if (!next.add(app.packageName)) next.remove(app.packageName)
                    dataStore.setLowModeAppList(next)
                    dialog.dismiss()
                    chooseLowModeApps()
                }
            }
            kit.note(panel, "Suspended apps are hidden from OEA until LOW mode ends, exactly like the Freezer's own suspensions.")
        }
    }

    /**
     * OEA's own wallpapers: drawn by OEA at the phone's resolution, applied
     * through Android's wallpaper service (install-time permission, no prompt),
     * with a dim control for busy scenes and a preview for each one.
     */
    private fun showWallpaperGallery() {
        val page = page("OEA wallpapers", "Drawn for this screen, applied to Android's wallpaper")
        val box = page.content
        val current = OeaWallpaperStore.scene(this)
        kit.note(box, "These replace the system wallpaper on the target you choose. Your own photos, the stock picker and Android's live wallpapers keep working exactly as before.")

        kit.sectionTitle(box, "Where Home's background lives")
        val source = kit.group(box)
        choiceRow(source, R.drawable.oic_wallpaper, "Wallpaper source",
            listOf(
                OeaDataStore.WALLPAPER_SYSTEM to "Android's wallpaper",
                OeaDataStore.WALLPAPER_OEA to "Drawn by Home (OEA only)"
),
            dataStore.wallpaperSource()) {
            dataStore.setWallpaperSource(it)
            rerender()
        }
        kit.note(box, "Drawn by Home keeps Android's wallpaper — and your lock screen — completely untouched: Home paints its own background behind the icons, so nothing is written to the system. Every scene below works in both sources; the live ones animate only when Home draws them.")

        kit.sectionTitle(box, "Live scenes")
        val live = kit.group(box)
        com.oea.launcher.wallpaper.OeaLiveScenes.ids.forEach { id ->
            val liveCurrent = com.oea.launcher.wallpaper.OeaHomeScene.live(this) == id
            val label = com.oea.launcher.wallpaper.OeaLiveScenes.label(id)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                isClickable = true
                setOnClickListener { pickLiveScene(id) }
            }
            val preview = ImageView(this)
            runCatching {
                preview.setImageBitmap(
                    com.oea.launcher.wallpaper.OeaLiveScenes.still(id, dp(54), dp(90))
)
            }
            preview.scaleType = ImageView.ScaleType.CENTER_CROP
            preview.background = kit.rounded(Color.TRANSPARENT, 12, palette.stroke, 1)
            row.addView(preview, LinearLayout.LayoutParams(dp(54), dp(90)).apply { rightMargin = dp(14) })
            val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            text.addView(kit.text(label, 15.5f, palette.text, OeaType.medium))
            text.addView(kit.text(com.oea.launcher.wallpaper.OeaLiveScenes.blurb(id), 12.5f, palette.textSecondary).apply { setLineSpacing(dp(2).toFloat(), 1f) })
            if (liveCurrent) text.addView(kit.text("In use", 12f, palette.accent, OeaType.medium).apply { setPadding(0, dp(4), 0, 0) })
            row.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
            val swatch = View(this).apply { background = kit.oval(com.oea.launcher.wallpaper.OeaLiveScenes.accent(id, true)) }
            row.addView(swatch, LinearLayout.LayoutParams(dp(22), dp(22)))
            live.addView(row)
        }
        kit.row(live, R.drawable.oic_close, "No live scene", "Use the still scenes below, or Android's wallpaper") {
            com.oea.launcher.wallpaper.OeaHomeScene.clearLive(this)
            rerender()
        }

        val group = kit.group(box)
        kit.stepperRow(group, R.drawable.oic_contrast, "Dim", "Darkens the scene when icons need more contrast", OeaWallpaperStore.dimPercent(this) / 5, 0, 12, { "${it * 5}%" }) {
            OeaWallpaperStore.setDimPercent(this, it * 5)
            OeaWallpaperStore.scene(this)?.let { scene -> applyWallpaper(scene, silent = true) }
        }
        kit.switchRow(group, R.drawable.oic_palette, "Theme follows the scene", "OEA's accent and Home text colour are picked from the wallpaper", OeaWallpaperStore.themeFollowsScene(this)) {
            OeaWallpaperStore.setThemeFollowsScene(this, it)
            rerender()
        }

        kit.sectionTitle(box, "Scenes")
        kit.note(box, "Drawn for Home. The Themes app has a separate lock-screen set with its own artwork.")
        val list = kit.group(box)
        OeaWallpaper.homeSet.forEach { scene ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                isClickable = true
                setOnClickListener { wallpaperTargetSheet(scene) }
            }
            val preview = ImageView(this)
            runCatching { preview.setImageBitmap(OeaWallpaperRenderer.preview(scene, dp(54), dp(96))) }
            preview.scaleType = ImageView.ScaleType.CENTER_CROP
            preview.background = kit.rounded(Color.TRANSPARENT, 12, palette.stroke, 1)
            row.addView(preview, LinearLayout.LayoutParams(dp(54), dp(90)).apply { rightMargin = dp(14) })
            val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            text.addView(kit.text(scene.label, 15.5f, palette.text, OeaType.medium))
            text.addView(kit.text(scene.blurb, 12.5f, palette.textSecondary).apply { setLineSpacing(dp(2).toFloat(), 1f) })
            if (current == scene) text.addView(kit.text("In use", 12f, palette.accent, OeaType.medium).apply { setPadding(0, dp(4), 0, 0) })
            row.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
            val swatch = View(this).apply { background = kit.oval(scene.accent) }
            row.addView(swatch, LinearLayout.LayoutParams(dp(22), dp(22)))
            list.addView(row)
        }

        val actions = kit.group(box)
        kit.row(actions, R.drawable.oic_refresh, "Apply the scene in use again", "Useful after changing dim or after another app changed the wallpaper") {
            OeaWallpaperStore.reapply(this) { result -> toast(result.message); rerender() }
        }
        kit.row(actions, R.drawable.oic_wallpaper, "Android's wallpaper picker", "Stock wallpapers and live wallpapers") {
            runCatching { startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Wallpaper")) }
        }
        present(page)
    }

    /**
     * A live scene can either be drawn by Home (nothing is written to Android) or
     * rendered once and set as a normal wallpaper, which works from the lock
     * screen too.
     */
    private fun pickLiveScene(id: String) {
        val label = com.oea.launcher.wallpaper.OeaLiveScenes.label(id)
        val host = this
        kit.sheet(label, "Drawn by Home, frame by frame") { panel, dialog ->
            panel.addView(kit.hscroll(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                val home = kit.primaryButton("Draw on Home", R.drawable.oic_wallpaper) {
                    dialog.dismiss()
                    com.oea.launcher.wallpaper.OeaHomeScene.setLive(host, id)
                    dataStore.setWallpaperSource(OeaDataStore.WALLPAPER_OEA)
                    toast("$label is drawn by Home; Android's wallpaper is untouched")
                    rerender()
                }
                addView(home, LinearLayout.LayoutParams(-2, dp(48)).apply { rightMargin = dp(8) })
                OeaWallpaperStore.Target.entries.forEach { target ->
                    val button = kit.tonalButton("Freeze to ${target.label}", R.drawable.oic_refresh) {
                        dialog.dismiss()
                        freezeLiveScene(id, target)
                    }
                    addView(button, LinearLayout.LayoutParams(-2, dp(48)).apply { rightMargin = dp(8) })
                }
            }))
            kit.note(panel, "Drawing on Home means the scene moves with your pages, and Depth follows your swipe. Freezing renders one frame and gives it to Android, so it also appears on the lock screen — from then on it is a picture, and the theme stops following it.")
        }
    }

    /**
     * Renders one frame of a live scene off the main thread and hands it to
     * Android as an ordinary wallpaper, which is also what makes it appear on the
     * lock screen. Rendering never runs on the UI thread, and a failure only ever
     * produces a message.
     */
    private fun freezeLiveScene(id: String, target: OeaWallpaperStore.Target) {
        toast("Rendering the scene…")
        val app = this
        val metrics = resources.displayMetrics
        Thread {
            val frame = runCatching {
                com.oea.launcher.wallpaper.OeaLiveScenes.still(id, metrics.widthPixels, metrics.heightPixels)
            }.getOrNull()
            runOnUiThread {
                if (isFinishing) {
                    frame?.recycle()
                    return@runOnUiThread
                }
                if (frame == null) {
                    toast("The scene could not be rendered on this device")
                    return@runOnUiThread
                }
                com.oea.launcher.wallpaper.OeaWallpaperStore.applyBitmap(app, frame, target) { result ->
                    runCatching { frame.recycle() }
                    if (isFinishing) return@applyBitmap
                    toast(result.message)
                    if (result is com.oea.launcher.wallpaper.OeaWallpaperStore.Result.Applied) rerender()
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun wallpaperTargetSheet(scene: OeaWallpaper) {
        kit.sheet(scene.label, scene.blurb) { panel, dialog ->
            panel.addView(kit.hscroll(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                OeaWallpaperStore.Target.entries.forEach { target ->
                    val button = kit.primaryButton("Apply to ${target.label}", R.drawable.oic_wallpaper) {
                        dialog.dismiss()
                        applyWallpaper(scene, target)
                    }
                    addView(button, LinearLayout.LayoutParams(-2, dp(48)).apply { rightMargin = dp(8) })
                }
            }))
            kit.note(panel, "OEA renders the scene for this phone's screen and hands it to Android, so it behaves like any other wallpaper.")
        }
    }

    private fun applyWallpaper(scene: OeaWallpaper, target: OeaWallpaperStore.Target = OeaWallpaperStore.Target.HOME, silent: Boolean = false) {
        if (!silent) toast("Applying ${scene.label}…")
        OeaWallpaperStore.apply(this, scene, target) { result ->
            if (!isFinishing) {
                toast(result.message)
                if (result is OeaWallpaperStore.Result.Applied) rerender()
            }
        }
    }

    private fun oeaWallpaperSummary(): String {
        val scene = OeaWallpaperStore.scene(this)
        val target = OeaWallpaperStore.lastTarget(this)
        return when {
            scene == null -> "${OeaWallpaper.homeSet.size} bundled scenes, drawn to fit this screen"
            target == null -> "${scene.label} (in use)"
            else -> "${scene.label} on the ${target.label.lowercase()}"
        }
    }

    private fun wallpaperThemeSummary(): String {
        if (!OeaWallpaperStore.themeFollowsScene(this)) return "Off — using your accent choice"
        val scene = OeaWallpaperStore.scene(this)
        return if (scene == null) "On — follows Android's wallpaper colours" else "On — follows ${scene.label}"
    }

    @Deprecated("Activity results are routed manually for this simple flow")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_ISLAND_OVERLAY) {
            finishIslandOverlayRequest(cameFromResult = true)
            return
        }
        if (requestCode == REQUEST_DEVICE_ADMIN) {
            val canLock = OeaPower.deviceAdminCanLock(this)
            android.util.Log.i("OeaSystemToolsActivity", "Device Admin result=${resultCode}; active=${OeaPower.deviceAdminActive(this)}; forceLockGranted=${canLock}")
            toast(
                if (canLock) "Device Admin authorization confirmed. Screen lock fallback is ready."
                else if (OeaPower.deviceAdminActive(this)) "Device Admin is active, but Android has not granted screen-lock permission. Open Device admin lock and approve the additional policy."
                else "Device Admin was not activated. You can retry from Device admin lock."
            )
            if (current == "power") rerender()
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
        kit.switchRow(group, R.drawable.oic_wallpaper, "Show wallpaper in All Apps", "Optional translucent drawer background; off keeps the current plain appearance", dataStore.drawerWallpaperBackground()) {
            dataStore.setDrawerWallpaperBackground(it)
            rerender()
        }
        kit.switchRow(group, R.drawable.oic_search, "Show wallpaper behind search",
            "The search bar area at the top of All Apps shows your wallpaper, and the whole screen does while you search (from Home or All Apps). A contrast scrim keeps results readable.",
            dataStore.searchWallpaperVisible()) {
            dataStore.setSearchWallpaperVisible(it)
            rerender()
        }
        if (Build.VERSION.SDK_INT >= 31 && (dataStore.drawerWallpaperBackground() || dataStore.searchWallpaperVisible())) {
            val blurSupported = runCatching { getSystemService(android.view.WindowManager::class.java)?.isCrossWindowBlurEnabled == true }.getOrDefault(false)
            kit.switchRow(group, R.drawable.oic_contrast, "Frosted wallpaper",
                if (blurSupported) "Blurs the wallpaper behind All Apps and search" else "Android has window blur turned off on this phone right now (it's off on many low-power phones and in Battery Saver), so OEA uses the plain scrim",
                dataStore.wallpaperBlur()) { dataStore.setWallpaperBlur(it) }
        }
        kit.row(group, R.drawable.oic_visibility_off, "Hidden apps", "${hiddenCount()} hidden") { hiddenAppsDialog() }
        kit.note(box, "Drawer search also finds contacts, Android settings, calculations (try 18% of 2500 as 2500*18%) and web results.")
        present(page)
    }

    private fun hiddenCount() = dataStore.hiddenApps().size

    private fun hiddenAppsDialog() {
        val choices = (requireApps() ?: return)
            .filterNot { it.packageName == packageName && it.className.endsWith("OeaSettingsAlias") }
        val selected = choices.filter { dataStore.isHidden(it.packageName, it.className) }.map { it.packageName + "/" + it.className }.toSet()
        kit.pickApps("Hidden apps", "Hidden apps stay installed but don't appear in the drawer or search.", choices, selected, { it.packageName + "/" + it.className }) { chosen ->
            choices.forEach { dataStore.setHidden(it.packageName, it.className, (it.packageName + "/" + it.className) in chosen) }
            rerender()
        }
    }

    // =============================================================== gestures

    private fun showGestures() {
        val page = page("Gestures", "Fast actions, each one bound separately — expanded with wake, lock, DND and more")
        val box = page.content
        assistantBanner(box)

        kit.sectionTitle(box, "Touch")
        val group = kit.group(box)
        kit.row(group, R.drawable.oic_swipe_up, "Swipe up", null, value = "App drawer", chevron = false)
        kit.stepperRow(group, R.drawable.oic_contrast, "Touch sensitivity", "How far a swipe or fling must travel before Home treats it as a gesture — Low guards against accidental swipes, High reacts to lighter movements. Applies straight away and survives restarts and power saving modes.", dataStore.gestureSensitivity(), 0, 2, { listOf("Low", "Standard", "High")[it] }) {
            dataStore.setGestureSensitivity(it)
            rerender()
        }
        gestureRow(group, "swipe_down", R.drawable.oic_swipe_down, "Swipe down")
        gestureRow(group, "double_tap", R.drawable.oic_touch_app, "Double-tap Home")
        gestureRow(group, "triple_tap", R.drawable.oic_touch_app, "Triple-tap Home")
        gestureRow(group, "two_finger", R.drawable.oic_gesture, "Two-finger swipe")
        gestureRow(group, "long_press", R.drawable.oic_widgets, "Long-press empty Home")
        gestureRow(group, "shake", R.drawable.oic_gesture, "Shake device")
        gestureRow(group, "flip", R.drawable.oic_phone_android, "Flip device")

        kit.sectionTitle(box, "Motion — wave and lift")
        val motion = kit.group(box)
        gestureRow(motion, "wave", R.drawable.oic_gesture, "Wave in front of the phone (screen on)")
        gestureRow(motion, "wave_off", R.drawable.oic_visibility_off, "Hold-and-wave (screen off)")
        kit.switchRow(motion, R.drawable.oic_touch_app, "Wave to wake, lift to wake", "Uses proximity and motion sensors only while these are on. Settings are backed up to main prefs so they survive Ultra Power Saving Mode and reboot.", OeaPower.waveToWake(this) || OeaPower.liftToWake(this)) {
            OeaPower.set(this, com.oea.launcher.power.OeaPower.KEY_WAVE, it)
            OeaPower.set(this, com.oea.launcher.power.OeaPower.KEY_LIFT, it)
            OeaPower.syncWakeService(this)
            rerender()
        }
        kit.stepperRow(motion, R.drawable.oic_contrast, "Wave sensitivity", "How deliberate a wave has to be — Low/Standard/High", OeaPower.sensitivity(this), 0, 2, { listOf("Low", "Standard", "High")[it] }) {
            OeaPower.setSensitivity(this, it)
            OeaPower.syncWakeService(this)
            rerender()
        }
        kit.switchRow(motion, R.drawable.oic_gesture, "Require two waves", "Fewer accidental wakes: wave twice within ~1.5s", OeaPower.requireTwoWaves(this)) {
            OeaPower.prefs(this).edit().putBoolean(com.oea.launcher.power.OeaPower.KEY_TWO_WAVES, it).apply()
            OeaPower.syncWakeService(this)
            rerender()
        }
        kit.switchRow(motion, R.drawable.oic_visibility_off, "Pocket guard", "Ignore waves and lifts while covered (pocket/bag)", OeaPower.pocketGuard(this)) {
            OeaPower.prefs(this).edit().putBoolean(com.oea.launcher.power.OeaPower.KEY_POCKET, it).apply()
            OeaPower.syncWakeService(this)
            rerender()
        }
        if (com.oea.launcher.perf.OeaLowMode.active(this)) {
            kit.banner(box, R.drawable.oic_battery_charging_full, "LOW mode is on",
                "Motion gestures are released while LOW mode is on, so sensors cost nothing. Settings are kept and restored when LOW mode ends.", OeaKit.Tone.INFO)
        }
        // Explain persistence fix
        kit.sectionTitle(box, "Persistence")
        kit.note(box, "Fixed: sensitivity and wave settings now mirrored to main OEA DataStore, so they survive Ultra Power Saving Mode (Samsung, Xiaomi, etc.) and reboot. Boot receiver restores wake service, island overlay and Low mode. Investigated: some OEMs wipe secondary prefs or kill services in ultra saving; OEA now restores from backup.")

        kit.sectionTitle(box, "Expanded actions")
        kit.note(box, "Expanded: gestures now support separate configurable actions — wake device, lock/switch off screen, enable/disable DND (on/off/toggle), flashlight, camera, screenshot, quick controls, LOW mode, volume, silence and more. Each action is bound separately, not forced into one switch.")

        kit.sectionTitle(box, "Recently used")
        val recents = kit.group(box)
        choiceRow(recents, R.drawable.oic_layers, "Recent apps (Home menu)", listOf("system" to "Android Recents", "oea" to "OEA Overview"), dataStore.recentsMode()) { dataStore.setRecentsMode(it) }
        kit.note(box, "This chooses what the Recent apps action inside OEA's Home menu opens. Android reserves the system Recents button and gesture for its own task switcher; OEA cannot replace those system surfaces without privileged OS support.")
        kit.note(box, "Double-tap and triple-tap are told apart by time: after second tap Home waits 260ms to see if third comes, so same taps never run both.")
        present(page)
    }

    /**
     * One gesture slot. The sheet only lists the actions that make sense for that
     * gesture, and warns when another gesture already has the same action, so two
     * gestures can never silently do the same thing.
     */
    private fun gestureRow(group: LinearLayout, slot: String, icon: Int, title: String) {
        val current = com.oea.launcher.gesture.OeaGestures.action(this, slot)
        val conflicts = com.oea.launcher.gesture.OeaGestures.conflicts(this, current, ignore = slot)
        kit.row(group, icon, title, if (conflicts.isEmpty()) null else "Also bound to ${conflicts.size} other gesture${if (conflicts.size == 1) "" else "s"}", value = com.oea.launcher.gesture.OeaGestures.actionLabel(current)) {
            val allowed = com.oea.launcher.gesture.OeaGestures.allowed[slot].orEmpty()
            val labels = allowed.map { com.oea.launcher.gesture.OeaGestures.actionLabel(it) }
            val index = allowed.indexOf(current).coerceAtLeast(0)
            kit.choose(title, "Each gesture has its own binding; OEA warns before two gestures share one.", labels, index) { pick ->
                val action = allowed[pick]
                val taken = com.oea.launcher.gesture.OeaGestures.conflicts(this, action, ignore = slot)
                if (taken.isEmpty()) {
                    com.oea.launcher.gesture.OeaGestures.set(this, slot, action)
                    rerender()
                } else {
                    kit.confirm(
                        "That action is already used",
                        "${com.oea.launcher.gesture.OeaGestures.actionLabel(action)} is already bound to ${taken.joinToString { com.oea.launcher.gesture.OeaGestures.slots[it] ?: it }}. Two gestures would then do the same thing; give that one a different action first.",
                        "OK"
) { rerender() }
                }
            }
        }
    }

    private fun assistantBanner(box: LinearLayout) {
        when {
            OeaAssistant.isConnected() -> kit.banner(box, R.drawable.oic_check_circle_fill, "OEA Assistant is on",
                "Lock screen, power menu, notifications, Recents, split screen and App Freezer actions are available.", OeaKit.Tone.SUCCESS)
            OeaAssistant.isEnabled(this) -> kit.banner(box, R.drawable.oic_warning_fill, "OEA Assistant was stopped by Android",
                "Switch it off and on again to restore lock, Recents and freezer actions.", OeaKit.Tone.WARNING, "Open settings") { OeaAssistant.openSetup(this) }
            else -> {
                val body = "OEA Assistant (an accessibility service) performs only the actions you trigger: double-tap lock, the power menu, swipe-down notifications, Recents, split screen on older Android and the no-root App Freezer. With Game Boost on it also notices which app is in front (package name only) so game sessions need no background Usage access checks. It never reads or sends your screen content." +
                    if (OeaAssistant.needsRestrictedSettingsHint()) "\n\n" + OeaAssistant.RESTRICTED_SETTINGS_HINT else ""
                val card = kit.banner(box, R.drawable.oic_accessibility_new, "Turn on OEA Assistant", body, OeaKit.Tone.INFO, "Turn on") { OeaAssistant.openSetup(this) }
                if (OeaAssistant.needsRestrictedSettingsHint()) {
                    card.addView(kit.quietButton("Open OEA App info", R.drawable.oic_info) { OeaAssistant.openOeaAppInfo(this) },
                        LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8); leftMargin = dp(34) })
                }
            }
        }
    }

    // ======================================================= buttons & screen

    private fun powerSummary(): String {
        val parts = mutableListOf<String>()
        if (com.oea.launcher.power.OeaPower.waveToWake(this)) parts += "wave to wake"
        if (com.oea.launcher.data.OeaDataStore.get(this).coverToLockEnabled()) parts += "cover sensor to lock"
        if (com.oea.launcher.power.OeaPower.liftToWake(this) && com.oea.launcher.power.OeaPower.liftSensor(this) != null) parts += "lift to wake"
        if (OeaAssistant.isConnected()) parts += "lock & power menu ready"
        return if (parts.isEmpty()) "Alternatives for a faulty power or volume button" else parts.joinToString(" • ").replaceFirstChar { it.uppercase() }
    }

    private var waveTestListener: android.hardware.SensorEventListener? = null

    private fun stopWaveTest() {
        val listener = waveTestListener ?: return
        waveTestListener = null
        runCatching { getSystemService(android.hardware.SensorManager::class.java)?.unregisterListener(listener) }
    }

    /**
     * Faulty power button alternatives. Everything here uses supported
     * Android APIs, and each row says plainly what it needs and what it
     * can't do (no app can switch on a powered-off phone).
     */
    private fun showPower() {
        val power = com.oea.launcher.power.OeaPower
        val page = page("Buttons & screen", "Alternatives when the power or volume button is faulty")
        val box = page.content

        kit.sectionTitle(box, "Wake the screen")
        val proximity = power.proximitySensor(this)
        val lift = power.liftSensor(this)
        var group = kit.group(box)
        if (proximity == null) {
            kit.row(group, R.drawable.oic_touch_app, "Wave to wake", "This phone has no proximity sensor OEA can use.", chevron = false)
        } else {
            kit.switchRow(group, R.drawable.oic_touch_app, "Wave to wake",
                "Wave a hand over the top of the screen (near the front camera) while the screen is off." +
                    if (proximity.isWakeUpSensor) "" else " On this phone the sensor can't wake the processor, so it works best shortly after the screen turns off.",
                power.waveToWake(this)) { on ->
                power.set(this, power.KEY_WAVE, on)
                rerender()
            }
            if (power.waveToWake(this)) {
                val sens = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(12)) }
                sens.addView(kit.text("Sensitivity", 13f, palette.textSecondary, OeaType.medium).apply { setPadding(dp(2), 0, 0, dp(8)) })
                sens.addView(kit.segmented(listOf("Low", "Medium", "High"), power.sensitivity(this)) {
                    power.setSensitivity(this, it)
                    rerender()
                })
                sens.addView(kit.text("Low needs a quick, deliberate wave; High also accepts slower waves.", 12f, palette.textTertiary).apply { setPadding(dp(2), dp(8), 0, 0) })
                group.addView(sens)
                kit.switchRow(group, R.drawable.oic_gesture, "Require two waves", "Fewer accidental wakes: wave twice within about a second and a half", power.requireTwoWaves(this)) {
                    power.set(this, power.KEY_TWO_WAVES, it)
                    rerender()
                }
            }
        }
        if (lift != null) {
            kit.switchRow(group, R.drawable.oic_phone_android, "Lift to wake", "Uses this phone's low-power pick-up sensor (${lift.name}).", power.liftToWake(this)) {
                power.set(this, power.KEY_LIFT, it)
                rerender()
            }
        } else {
            kit.row(group, R.drawable.oic_phone_android, "Lift to wake", "Not available: this phone doesn't expose a low-power pick-up sensor, and keeping the motion sensors awake would drain the battery.", chevron = false)
        }
        if (power.waveToWake(this) || (power.liftToWake(this) && lift != null)) {
            kit.switchRow(group, R.drawable.oic_shield, "Pocket guard", "Ignores the sensor while it stays covered, in a pocket or bag", power.pocketGuard(this)) {
                power.set(this, power.KEY_POCKET, it)
                rerender()
            }
        }
        kit.sectionTitle(box, "Turn the screen off")
        group = kit.group(box)
        val coverToLock = dataStore.coverToLockEnabled()
        if (proximity == null) {
            kit.row(group, R.drawable.oic_lock, "Cover sensor to lock screen", "Unavailable: this phone does not expose a proximity sensor to OEA.", chevron = false)
        } else {
            kit.switchRow(
                group, R.drawable.oic_lock, "Cover sensor to lock screen",
                "While the screen is on in portrait, hold your hand over the proximity sensor for about 1.2 seconds. Uncover sooner to cancel. Disabled in landscape and during calls; ignores a sensor already covered when listening starts.",
                coverToLock
            ) { on ->
                dataStore.setCoverToLockEnabled(on)
                power.syncWakeService(this)
                rerender()
            }
        }
        if (coverToLock && !OeaAssistant.isConnected() && !power.deviceAdminCanLock(this)) {
            kit.note(box, "The cover gesture is detected, but locking needs OEA Assistant or OEA Device Admin with Force Lock enabled.")
        }

        // Keep the existing, separate screen-off gesture configurable. It is not
        // the screen-on cover-to-lock feature above.
        val offBound = dataStore.waveOffAction() != "none"
        kit.switchRow(
            group, R.drawable.oic_gesture, "Hold gesture while the screen is off",
            "Runs the separate Hold-and-wave (screen off) action from Gestures. This does not turn the screen off.",
            offBound
        ) { on ->
            if (!on) {
                dataStore.setWaveOffAction("none")
            } else if (dataStore.waveOffAction() == "none") {
                dataStore.setWaveOffAction("lock")
            }
            power.syncWakeService(this)
            rerender()
        }
        if (offBound) {
            kit.row(group, R.drawable.oic_touch_app, "Screen-off gesture action",
                "Separate from cover-to-lock; configure its action under Gestures.",
                value = com.oea.launcher.gesture.OeaGestures.actionLabel(dataStore.waveOffAction()), chevron = false)
        }
        // ---- what this phone can actually do, checked live
        kit.sectionTitle(box, "What this phone provides")
        val caps = kit.group(box)
        kit.row(caps, R.drawable.oic_visibility_off, "Proximity sensor",
            if (proximity != null) "Available (${proximity.name}) — drives wave-to-wake and optional cover-to-lock" else "Not exposed by this phone, so proximity gestures cannot work here",
            chevron = false)
        kit.row(caps, R.drawable.oic_phone_android, "Pick-up sensor",
            if (lift != null) "Available (${lift.name})" else "Not exposed: lift to wake stays switched off",
            chevron = false)
        kit.row(caps, R.drawable.oic_battery_charging_full, "Sensor service",
            if (power.wakeFeaturesOn(this)) "On — listeners are registered only for enabled gestures in the current screen state" else "Off — enable a sensor gesture above",
            chevron = false)

        if (proximity != null && (power.waveToWake(this) || offBound || coverToLock)) {
            val status = kit.text("Live sensor test: briefly cover and uncover for wave detection. If Cover sensor to lock is enabled, a 1.2-second cover can lock the phone.", 13f, palette.textSecondary).apply {
                setPadding(dp(18), dp(10), dp(18), dp(14))
                setLineSpacing(dp(2).toFloat(), 1f)
            }
            group.addView(status)
            startWaveTest(proximity, status)
        }
        kit.note(box, "Wave/lift-to-wake sensors listen while the screen is off. The proximity sensor also listens while the screen is on only when Cover sensor to lock is enabled; it ignores a sensor already covered when listening starts and pauses during calls. A quiet notification shows while any sensor gesture is enabled. On Android 14+ allow \"Turn screen on\" for OEA if waking doesn't work, and keep OEA unrestricted in battery settings. Double-tap to wake, where your phone has it, is a phone-maker feature in Android Settings.")
        group = kit.group(box)
        kit.row(group, R.drawable.oic_settings, "Allow OEA to turn the screen on", "Android 14+ special app access; also keep battery use unrestricted") {
            runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        }

        kit.sectionTitle(box, "Lock & power")
        group = kit.group(box)
        kit.row(group, R.drawable.oic_lock, "Lock screen now", if (OeaAssistant.isConnected()) "OEA Assistant • normal fingerprint/face unlock" else if (power.deviceAdminCanLock(this)) "Device Admin fallback • Android may ask for PIN" else "Needs OEA Assistant or approved Device Admin screen-lock policy") {
            if (power.lockScreen(this) == com.oea.launcher.power.OeaPower.LockResult.NEEDS_SETUP) {
                val assistantEnabled = OeaAssistant.isEnabled(this)
                toast(if (assistantEnabled) "OEA Assistant is enabled but not connected. Reopen its Accessibility settings, or authorize Device Admin lock below." else "Enable OEA Assistant for the primary lock method, or authorize Device Admin lock below.")
            }
        }
        kit.row(group, R.drawable.oic_restart_alt, "Power menu", "Android's own menu to power off or restart. Needs OEA Assistant") {
            if (!power.openPowerMenu()) {
                toast("Turn on OEA Assistant to open the power menu.")
                OeaAssistant.openSetup(this)
            }
        }
        kit.row(group, R.drawable.oic_accessibility_new, "OEA Assistant", if (OeaAssistant.isConnected()) "On" else "Off • needed for lock without PIN and for the power menu") { OeaAssistant.openSetup(this) }
        val adminLock = power.deviceAdminCanLock(this)
        kit.row(group, R.drawable.oic_shield, "Device admin lock", when {
            adminLock -> "Authorized • fallback ready when OEA Assistant is unavailable"
            power.deviceAdminActive(this) -> "Active, but Android has not granted screen-lock policy • tap for recovery steps"
            else -> "Not active • tap to request Android authorization"
        }) {
            if (adminLock) {
                toast("Device Admin screen-lock permission is already approved.")
            } else runCatching {
                if (power.deviceAdminActive(this)) {
                    toast("OEA Device Admin is already active but lacks force-lock approval. In Android Security settings, deactivate OEA's administrator, then return here and activate it again. OEA will not deactivate it automatically.")
                    startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                } else {
                    startActivityForResult(power.deviceAdminIntent(this), REQUEST_DEVICE_ADMIN)
                }
            }.onFailure {
                android.util.Log.e("OeaSystemToolsActivity", "Android Device Admin authorization/recovery screen could not be opened", it)
                toast("Android could not open Device Admin settings. Open Settings › Security › Device admin apps, then retry.")
            }
        }
        kit.row(group, R.drawable.oic_touch_app, "Double-tap Home", labelFor(dataStore.doubleTapAction(), mapOf("lock" to "Locks the screen", "power_menu" to "Opens the power menu", "quick_controls" to "Opens Quick controls", "drawer" to "Opens the app drawer", "none" to "Off"))) { navigate("gestures") }

        kit.sectionTitle(box, "Anywhere: Quick Settings tiles")
        group = kit.group(box)
        kit.row(group, R.drawable.oic_lock, "Add the Lock screen tile", "Lock from the notification shade in any app") {
            addTile(com.oea.launcher.power.OeaLockTileService::class.java, "Lock screen", R.drawable.oic_lock)
        }
        kit.row(group, R.drawable.oic_tune, "Add the Quick controls tile", "Power menu, volume, sound mode and Do Not Disturb in one sheet") {
            addTile(com.oea.launcher.power.OeaQuickControlsTileService::class.java, "Quick controls", R.drawable.oic_tune)
        }

        kit.sectionTitle(box, "Volume & sound")
        group = kit.group(box)
        kit.row(group, R.drawable.oic_volume_up, "Open Quick controls", "Volume sliders, sound/vibrate/silent and Do Not Disturb") {
            openActivity(com.oea.launcher.power.OeaQuickControlsActivity::class.java)
        }
        kit.row(group, R.drawable.oic_volume_up, "Android volume panel", null) { power.showVolumePanel(this) }
        kit.row(group, R.drawable.oic_do_not_disturb_on, "Do Not Disturb settings", if (power.policyAccess(this)) "Access allowed" else "Allow access to switch DND from OEA") {
            runCatching { startActivity(if (power.policyAccess(this)) power.dndSettingsIntent() else power.policyAccessIntent()) }
                .onFailure { toast("Android doesn't offer that screen on this phone.") }
        }
        kit.row(group, R.drawable.oic_notifications, "Notification settings", null) {
            runCatching { startActivity(power.notificationSettingsIntent()) }.onFailure { runCatching { startActivity(power.soundSettingsIntent()) } }
        }
        kit.note(box, "Android doesn't let any app switch on a phone that is powered off, or restart it without the system's own power menu. To turn the phone on with a broken power key, connect a charger (many phones start then) or ask a repair shop.")
        present(page)
    }

    /**
     * The single path every overlay request ends in — the toggle, the return from
     * Android Settings, and the re-check on resume.
     *
     * Android's own "Display over other apps" switch on some phones (and
     * AppOps-based firmware) answers "allowed" to the check while draw-over
     * still fails, which is exactly the "it is on but nothing happens" report.
     * The state is therefore verified against the real draw path as well, and
     * whatever the answer is, the user is told it and the setting matches.
     */
    private fun finishIslandOverlayRequest(cameFromResult: Boolean) {
        pendingIslandOverlayEnable = false
        val granted = OeaPrivileges.canOverlay(this)
        val verified = granted && OeaDynamicIslandOverlayService.verifyOverlay(this)
        if (verified) {
            dataStore.setIslandOnOtherApps(true)
            OeaDynamicIslandOverlayService.sync(this)
            if (cameFromResult) toast("Dynamic Island will appear over other apps.")
        } else {
            dataStore.setIslandOnOtherApps(false)
            OeaDynamicIslandOverlayService.sync(this)
            toast(
                when {
                    !granted -> "Android did not grant Display over other apps, so the island stays on Home only."
                    else -> "Android reports the permission, but drawing over apps was refused. Try restarting OEA; if it persists, the phone's firmware is blocking it and the island stays on Home only."
                }
)
        }
        if (current == "notifications" || current == "overview") rerender()
    }

    private fun startWaveTest(sensor: android.hardware.Sensor, status: TextView) {
        stopWaveTest()
        val sm = getSystemService(android.hardware.SensorManager::class.java) ?: return
        val power = com.oea.launcher.power.OeaPower
        val detector = com.oea.launcher.power.OeaWaveDetector(power.sensitivity(this), power.requireTwoWaves(this), power.pocketGuard(this))
        detector.reset(android.os.SystemClock.elapsedRealtime(), false)
        var waves = 0
        val listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(event: android.hardware.SensorEvent) {
                val distance = event.values.firstOrNull() ?: return
                val covered = distance < minOf(sensor.maximumRange, 5f) * 0.6f + 0.01f
                if (detector.onReading(covered, android.os.SystemClock.elapsedRealtime()) == com.oea.launcher.power.OeaWaveDetector.Result.ON) waves++
                val parts = mutableListOf(if (covered) "Sensor covered" else "Sensor clear")
                if (waves > 0) parts += "wave ×$waves ✓"
                if (waves == 0) parts += "briefly cover and uncover to test wave detection"
                if (dataStore.coverToLockEnabled()) parts += "a 1.2-second cover can lock the screen"
                status.text = parts.joinToString(" • ")
                status.setTextColor(if (waves > 0) palette.success else palette.textSecondary)
            }
            override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) = Unit
        }
        if (runCatching { sm.registerListener(listener, sensor, android.hardware.SensorManager.SENSOR_DELAY_UI) }.getOrDefault(false)) waveTestListener = listener
    }

    /** Android 13+ can add a tile directly; older versions explain where to add it. */
    private fun addTile(cls: Class<*>, label: String, icon: Int) {
        if (Build.VERSION.SDK_INT >= 33) {
            val sbm = getSystemService(android.app.StatusBarManager::class.java)
            if (sbm != null) {
                runCatching {
                    sbm.requestAddTileService(
                        android.content.ComponentName(this, cls), label,
                        android.graphics.drawable.Icon.createWithResource(this, icon), mainExecutor
) { result ->
                        toast(when (result) {
                            android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "$label tile added to Quick Settings."
                            android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "The $label tile is already in Quick Settings."
                            else -> "Tile not added. You can add it by editing Quick Settings."
                        })
                    }
                }.onSuccess { return }
            }
        }
        toast("Open Quick Settings, tap the pencil (Edit) and drag \"$label\" into your tiles.")
    }

    override fun onPause() {
        stopWaveTest()
        super.onPause()
    }

    // ========================================================== notifications

    private fun showNotifications() {
        val page = page("Notification dots", "See which apps have something new")
        val box = page.content
        if (!notificationAccess()) {
            kit.banner(box, R.drawable.oic_notifications, "Allow notification access", "OEA needs it for app badges, media playback information, and controls from supported notifications.",
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
        kit.sectionTitle(box, "Live activity area")
        val islandGroup = kit.group(box)
        kit.switchRow(
            islandGroup,
            R.drawable.oic_music_note,
            "Show Dynamic Island",
            "Show active calls, charging, media and compatible notification activities on OEA Home, with an optional system-wide overlay below.",
            dataStore.dynamicIslandEnabled()
) {
            dataStore.setDynamicIslandEnabled(it)
            OeaDynamicIslandOverlayService.sync(this)
            rerender()
        }
        if (dataStore.dynamicIslandEnabled()) {
            val styles = listOf(
                OeaDataStore.ISLAND_CARD to "Card under the status bar",
                OeaDataStore.ISLAND_CAMERA to "Island around the camera",
                OeaDataStore.ISLAND_BOTH to "Both"
)
            choiceRow(islandGroup, R.drawable.oic_view_agenda, "Style", styles, dataStore.islandStyle()) {
                dataStore.setIslandStyle(it)
            }
            if (dataStore.islandStyle() == OeaDataStore.ISLAND_CAMERA || dataStore.islandStyle() == OeaDataStore.ISLAND_BOTH) {
                kit.row(islandGroup, R.drawable.oic_touch_app, "Camera island position", "Move the capsule to match your front-camera layout") {
                    cameraIslandPositionDialog()
                }
            }
            kit.switchRow(
                islandGroup,
                R.drawable.oic_apps,
                "Show over other apps",
                "Optional system-wide island. Android requires Display over other apps access and keeps an OEA foreground-service notice; its notification action appears when notifications are allowed.",
                dataStore.islandOnOtherApps()
) { enabled ->
                if (!enabled) {
                    pendingIslandOverlayEnable = false
                    dataStore.setIslandOnOtherApps(false)
                    OeaDynamicIslandOverlayService.sync(this)
                    rerender()
                } else if (OeaPrivileges.canOverlay(this)) {
                    dataStore.setIslandOnOtherApps(true)
                    OeaDynamicIslandOverlayService.sync(this)
                    rerender()
                } else {
                    pendingIslandOverlayEnable = true
                    // Prefer the per-app screen, and fall back to the global list
                    // (some firmware has no per-app entry) and to OEA's own
                    // request screen — three routes, so "it just didn't work" is
                    // never the end of it.
                    val opened = runCatching {
                        startActivityForResult(
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                            REQUEST_ISLAND_OVERLAY
)
                    }.isSuccess || runCatching {
                        startActivityForResult(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION), REQUEST_ISLAND_OVERLAY)
                    }.isSuccess
                    if (!opened && !OeaPrivileges.requestOverlay(this, REQUEST_ISLAND_OVERLAY)) {
                        pendingIslandOverlayEnable = false
                        toast("OEA could not open Android's Display over other apps screen. Android Settings › Apps › OEA › Display over other apps grants it by hand.")
                        rerender()
                    }
                }
            }
            if (dataStore.islandOnOtherApps()) {
                kit.switchRow(islandGroup, R.drawable.oic_apps, "Show for all apps", "When off, choose the apps whose compatible live notifications may appear.", dataStore.islandAllApps()) {
                    dataStore.setIslandAllApps(it)
                    OeaDynamicIslandOverlayService.sync(this)
                    rerender()
                }
                if (!dataStore.islandAllApps()) {
                    kit.row(islandGroup, R.drawable.oic_apps, "Choose apps", islandAppsSummary(dataStore.islandSelectedApps())) {
                        pickIslandVisibleApps()
                    }
                }
            }
            if (dataStore.islandStyle() == OeaDataStore.ISLAND_BOTH) {
                kit.row(islandGroup, R.drawable.oic_apps, "Camera island apps", islandAppsSummary(dataStore.islandCameraApps())) {
                    pickIslandApps(camera = true)
                }
                kit.row(islandGroup, R.drawable.oic_apps, "Card apps", islandAppsSummary(dataStore.islandCardApps())) {
                    pickIslandApps(camera = false)
                }
                choiceRow(islandGroup, R.drawable.oic_tune, "Everything else",
                    listOf(OeaDataStore.ISLAND_CARD to "Card", OeaDataStore.ISLAND_CAMERA to "Camera island"),
                    dataStore.islandDefaultSurface()) {
                    dataStore.setIslandDefaultSurface(it)
                }
            }
            kit.switchRow(islandGroup, R.drawable.oic_open_in_full, "Expand for new activity",
                "Opens briefly for a new message, a new song, a charger or a game event, and stays open while a call is ringing. Tap to close; it won't reopen for the same thing.",
                dataStore.islandPeek()) { dataStore.setIslandPeek(it) }
            if (dataStore.islandPeek()) {
                kit.stepperRow(islandGroup, R.drawable.oic_timer, "Auto-collapse after",
                    "How long an automatic expansion stays open, in seconds",
                    (dataStore.islandAutoCollapseMs() / 1000).toInt().coerceIn(2, 60), 2, 60, { "$it s" }) {
                    dataStore.setIslandAutoCollapseMs(it * 1000)
                }
                kit.switchRow(islandGroup, R.drawable.oic_visibility_off, "Keep it small over other apps",
                    "While you are in another app, an expansion stays compact (title and one action) instead of covering content, and collapses by itself when you touch outside it.",
                    dataStore.islandCompactOverApps()) {
                    dataStore.setIslandCompactOverApps(it)
                    OeaDynamicIslandOverlayService.sync(this)
                }
            }
            kit.switchRow(islandGroup, R.drawable.oic_mic, "Camera & microphone dots",
                "Green and orange dots inside the island while any app uses the camera or microphone. Android 12+ shows its own indicator, so the island only shows dots on top of an activity it already shows; on older Android it can show them on their own.",
                dataStore.islandPrivacyDots()) { dataStore.setIslandPrivacyDots(it) }
            kit.row(islandGroup, R.drawable.oic_stadia_controller_fill, "Games in the island", "Choose card, camera island, both or automatic for each game") {
                openActivity(com.oea.launcher.games.OeaGamesActivity::class.java)
            }
        }
        kit.note(box, when (dataStore.islandStyle()) {
            OeaDataStore.ISLAND_CAMERA -> "The island sits around your front camera. Tap it to expand, tap outside to close, long-press to open the app."
            OeaDataStore.ISLAND_BOTH -> "Each app shows in the style you assign it to, so music can live in the camera island while chats use the card. Calls, charging and apps you haven't assigned use \"Everything else\"."
            else -> "Tap the capsule on Home to expand it."
        } + " Media sessions use standard play, pause and skip controls; other apps can provide notification actions. Notification-based details require Notification access. Charging status does not." +
            if (dataStore.islandOnOtherApps()) " Across-app display is active only while Android's overlay permission is granted; when notifications are allowed, its ongoing OEA notice includes a Turn off action." else "")
        present(page)
    }

    private fun islandAppsSummary(packages: Set<String>): String {
        if (packages.isEmpty()) return "None yet"
        val labels = packages.mapNotNull { pkg ->
            runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
        }.sorted()
        return when {
            labels.isEmpty() -> "${packages.size} apps"
            labels.size <= 2 -> labels.joinToString(", ")
            else -> "${labels.take(2).joinToString(", ")} +${labels.size - 2}"
        }
    }

    private fun pickIslandVisibleApps() {
        val choices = (requireApps() ?: return).distinctBy { it.packageName }
        kit.pickApps(
            "Dynamic Island apps",
            "When Show for all apps is off, only compatible live notifications from these apps appear on OEA and its optional overlay. Calls and battery status remain available.",
            choices,
            dataStore.islandSelectedApps(),
            { it.packageName },
            confirmLabel = "Save"
) { chosen ->
            dataStore.setIslandSelectedApps(chosen)
            OeaDynamicIslandOverlayService.sync(this)
            rerender()
        }
    }

    private fun cameraIslandPositionDialog() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(4))
        }
        val xSeek = android.widget.SeekBar(this).apply {
            max = 160
            progress = (dataStore.islandCameraOffsetX() + 80).coerceIn(0, max)
        }
        var positionX = xSeek.progress - 80
        val xLabel = kit.text("Horizontal: ${positionX} dp", 13f, palette.textSecondary)
        val ySeek = android.widget.SeekBar(this).apply {
            max = 160
            progress = (dataStore.islandCameraOffsetY() + 40).coerceIn(0, max)
        }
        var positionY = ySeek.progress - 40
        val yLabel = kit.text("Vertical: ${positionY} dp", 13f, palette.textSecondary).apply { setPadding(0, dp(14), 0, 0) }
        fun savePosition(x: Int, y: Int) {
            dataStore.setIslandCameraPosition(x, y)
            OeaDynamicIslandOverlayService.sync(this)
        }
        xSeek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                positionX = progress - 80
                xLabel.text = "Horizontal: ${positionX} dp"
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
        })
        ySeek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                positionY = progress - 40
                yLabel.text = "Vertical: ${positionY} dp"
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
        })
        content.addView(xLabel)
        content.addView(xSeek, LinearLayout.LayoutParams(-1, -2))
        content.addView(yLabel)
        content.addView(ySeek, LinearLayout.LayoutParams(-1, -2))
        kit.sheet("Camera island position", "Adjust the cutout anchor for your phone. Tap Done to save; the new position appears on Home when you return.") { panel, sheet ->
            panel.addView(content)
            panel.addView(kit.buttonRow(
                kit.quietButton("Cancel") { sheet.dismiss() },
                kit.quietButton("Reset") {
                    savePosition(0, 0)
                    sheet.dismiss()
                },
                kit.primaryButton("Done") {
                    savePosition(positionX, positionY)
                    sheet.dismiss()
                }
), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
    }

    /** Assigns apps to one live-activity style; an app can only belong to one of them. */
    private fun pickIslandApps(camera: Boolean) {
        val choices = (requireApps() ?: return).distinctBy { it.packageName }
        val selected = if (camera) dataStore.islandCameraApps() else dataStore.islandCardApps()
        kit.pickApps(
            if (camera) "Camera island apps" else "Card apps",
            if (camera) "Live activities from these apps appear in the island around the camera." else "Live activities from these apps appear in the card under the status bar.",
            choices, selected, { it.packageName }, confirmLabel = "Save"
) { chosen ->
            if (camera) dataStore.setIslandCameraApps(chosen) else dataStore.setIslandCardApps(chosen)
            rerender()
        }
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
        kit.row(group, R.drawable.oic_visibility_off, "Open hidden apps", "Use your ${hiddenCount()} hidden apps in their own space") { homeAction("hidden") }
        kit.row(group, R.drawable.oic_edit, "Choose hidden apps", "${hiddenCount()} hidden from Home, the drawer and search") { hiddenAppsDialog() }
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
        val choices = (requireApps() ?: return)
            .filterNot { it.packageName == packageName && it.className.endsWith("OeaSettingsAlias") }
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
        else -> "OEA Freeze • ${OeaAppFreezer.allFrozen(this).size} frozen" +
            if (OeaAssistant.isEnabled(this)) "" else " • Assistant off"
    }

    /** Where OEA Assistant returns after force-stopping: this Freezer, in its own task. */
    private fun freezerReturnIntent() = Intent(this, javaClass)
        .putExtra(EXTRA_SCREEN, "freezer")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)

    // Freezer screen state survives re-renders (resume, OEA Assistant changes).
    private var freezerQuery = ""
    private var freezerFilter = "all"
    private var freezerShowSystem = false
    private val freezerSelected = linkedSetOf<String>()
    private var freezerApps: List<OeaAppInfo>? = null
    private var freezerAppsIncludeSystem = false
    private val freezerBusy = linkedSetOf<String>()
    private val freezerJustFrozen = HashMap<String, Long>()
    private var freezerOperationRunning = false
    private var freezerBackendShown: OeaAppFreezer.Backend? = null
    private var freezerAppsLoadedAt = 0L

    private fun freezerIce(): Int = if (palette.dark) Color.rgb(112, 212, 255) else Color.rgb(0, 118, 186)

    private fun freezerToast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    /**
     * Freezer: the main screen of the standalone Freezer app (and the App
     * Freezer page reached from OEA Settings). Uses true package suspension
     * when Android grants Device Owner or root; otherwise OEA Freeze hibernates
     * apps: OEA blocks them and OEA Assistant force-stops them through
     * Android's App info page, behind its own progress screen.
     * CI regression guards retain the previous SELECT VISIBLE and REFRESH STATE control identifiers.
     */
    private fun showFreezer() {
        // Root (if any) is detected once in the background; the screen updates when it is known.
        OeaAppFreezer.primeRootCheck { if (!isDestroyed && current == "freezer" && OeaAppFreezer.backend(this) != freezerBackendShown) rerender() }
        val backend = OeaAppFreezer.backend(this)
        freezerBackendShown = backend
        val systemMode = OeaAppFreezer.mode(this) == OeaAppFreezer.Mode.SYSTEM
        val standalone = current == rootScreen && stack.isEmpty()
        val ice = freezerIce()
        val iceSoft = OeaTheme.blend(palette.card, ice, if (palette.dark) 0.20f else 0.12f)
        val icons = OeaIconController.shared(this)
        val grayscale = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(0f) })

        val root = FrameLayout(this).apply { setBackgroundColor(palette.background) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(column, FrameLayout.LayoutParams(-1, -1))
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), 0)
        }
        column.addView(header, LinearLayout.LayoutParams(-1, -2))

        // ---- identity row: icon, name and live status, like Game Boost and Call Blocker
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (!standalone) {
            top.addView(kit.circleIconButton(R.drawable.oic_arrow_back, "Back") { back() },
                LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(10) })
        }
        top.addView(ImageView(this).apply {
            setImageDrawable(getDrawable(R.drawable.ic_oea_freezer))
            contentDescription = null
        }, LinearLayout.LayoutParams(dp(42), dp(42)).apply { rightMargin = dp(12) })
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(kit.text("Freezer", 24f, palette.text, OeaType.medium))
        val assistantGranted = OeaAssistant.isEnabled(this)
        val modeLine = when {
            systemMode -> when (backend) {
                OeaAppFreezer.Backend.ROOT -> "System freeze • Root"
                OeaAppFreezer.Backend.ADB -> "System freeze • Wireless Debugging"
                else -> "System freeze • Device Owner"
            }
            OeaAssistant.isConnected() -> "OEA Freeze • Assistant ready"
            assistantGranted -> "OEA Freeze • Assistant on"
            else -> "OEA Freeze • Assistant off"
        }
        titles.addView(kit.text(modeLine, 12.5f, palette.textSecondary).apply { setPadding(0, dp(1), 0, 0) })
        top.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        val overflow = kit.circleIconButton(R.drawable.oic_more_vert, "Freezer options", palette.cardHigh, palette.text) {}
        top.addView(overflow, LinearLayout.LayoutParams(dp(44), dp(44)))
        header.addView(top, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

        // ---- status card
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(OeaTheme.blend(palette.card, ice, if (palette.dark) 0.26f else 0.14f), palette.card)
).apply {
                cornerRadius = dp(26).toFloat()
                setStroke(maxOf(1, dp(1)), OeaTheme.alpha(ice, 0x44))
            }
        }
        val heroTop = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val frozenCount = kit.text("0", 34f, palette.text, OeaType.medium)
        heroTop.addView(frozenCount)
        heroTop.addView(kit.text("frozen", 15f, palette.textSecondary).apply { setPadding(dp(8), dp(10), 0, 0) },
            LinearLayout.LayoutParams(0, -2, 1f))
        heroTop.addView(kit.iconBadge(R.drawable.oic_ac_unit_fill, OeaTheme.alpha(ice, 0x30), ice, 44), LinearLayout.LayoutParams(dp(44), dp(44)))
        hero.addView(heroTop)
        hero.addView(kit.text(
            if (systemMode) "Android suspends frozen apps: they can't open, run in the background or notify until you unfreeze them."
            else "Frozen apps are blocked in OEA and force-stopped, so they stay asleep: no background work, alarms or notifications.",
            13f, palette.textSecondary
).apply { setLineSpacing(dp(2).toFloat(), 1f); setPadding(0, dp(4), 0, 0) })
        if (backend != OeaAppFreezer.Backend.DEVICE_OWNER &&
            backend != OeaAppFreezer.Backend.ROOT &&
            backend != OeaAppFreezer.Backend.ADB) {
            hero.addView(kit.quietButton("Pair OEA Authority for system freeze", R.drawable.oic_lock) {
                startActivity(Intent(this, com.oea.launcher.privilege.OeaAuthorityActivity::class.java))
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
            hero.addView(kit.text(
                "Wireless Debugging lets OEA request real Android package suspension after you pair it on this phone.",
                12f, palette.textTertiary
            ).apply { setPadding(0, dp(6), 0, 0); setLineSpacing(dp(2).toFloat(), 1f) })
        }
        if (!systemMode && !assistantGranted) {
            // Only when OEA Assistant has genuinely never been switched on.
            hero.addView(kit.button("Turn on OEA Assistant", ice, if (palette.dark) Color.rgb(6, 22, 34) else Color.WHITE, R.drawable.oic_accessibility_new) {
                OeaAssistant.openSetup(this)
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
            hero.addView(kit.text("Needed once so OEA can also force-stop frozen apps. No root, ADB or computer.", 12f, palette.textTertiary)
                .apply { setPadding(dp(2), dp(6), 0, 0) })
        } else if (!systemMode && !OeaAssistant.isConnected()) {
            // Granted, but Android paused it in the background: never ask again,
            // just explain and offer the setting that keeps it running.
            hero.addView(kit.text("OEA Assistant is on. Android paused it in the background; OEA reconnects automatically when you freeze an app.",
                12f, palette.textTertiary).apply { setPadding(0, dp(8), 0, 0); setLineSpacing(dp(2).toFloat(), 1f) })
            hero.addView(kit.quietButton("Keep OEA running", R.drawable.oic_battery_full) {
                runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        }
        header.addView(hero, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        val searchField = kit.field("Search apps or package names", freezerQuery)
        header.addView(searchField, LinearLayout.LayoutParams(-1, -2))
        val filters = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(kit.hscroll(filters), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10); bottomMargin = dp(6) })

        val list = ListView(this).apply {
            divider = ColorDrawable(Color.TRANSPARENT)
            dividerHeight = dp(8)
            selector = ColorDrawable(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(14), dp(4), dp(14), dp(96))
        }
        column.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))

        // ---- sticky selection bar
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(10), dp(10))
            background = kit.rounded(palette.cardHigh, 28, palette.stroke, 1)
            elevation = dp(10).toFloat()
            visibility = View.GONE
        }
        val barLabel = kit.text("", 14f, palette.text, OeaType.medium)
        bar.addView(barLabel, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            leftMargin = dp(14); rightMargin = dp(14); bottomMargin = dp(16)
        })
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = OeaInsets.bars(insets)
            column.setPadding(bars[0], bars[1], bars[2], 0)
            list.setPadding(dp(14), dp(4), dp(14), dp(96) + bars[3])
            (bar.layoutParams as? FrameLayout.LayoutParams)?.let {
                it.bottomMargin = dp(16) + bars[3]
                bar.layoutParams = it
            }
            insets
        }

        var loadingApps = freezerApps == null || freezerAppsIncludeSystem != freezerShowSystem
        var visible: List<OeaAppInfo> = emptyList()
        var renderApps: () -> Unit = {}

        fun reload() {
            loadingApps = true
            renderApps()
            val includeSystem = freezerShowSystem
            com.oea.engine.core.OeaGuard.start("oea-freezer-query", "settings:freezer") {
                val loaded = runCatching { loadInstalledFreezerApps(includeSystem) }.getOrElse { emptyList() }
                OeaAppFreezer.syncActualState(this, loaded.map { it.packageName })
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    freezerApps = loaded
                    freezerAppsIncludeSystem = includeSystem
                    freezerAppsLoadedAt = android.os.SystemClock.elapsedRealtime()
                    loadingApps = false
                    renderApps()
                }
            }
        }

        fun reportForceStop(stopped: List<String>, failed: List<String>) {
            freezerToast(when {
                failed.isEmpty() && stopped.size == 1 -> "Frozen and asleep."
                failed.isEmpty() -> "${stopped.size} apps frozen and asleep."
                stopped.isEmpty() -> "Frozen in OEA. OEA Assistant wasn't running, so Force stop was skipped (⋮ › Put frozen apps back to sleep)."
                else -> "${stopped.size} asleep • ${failed.size} frozen in OEA (Android didn't confirm Force stop)."
            })
        }

        fun apply(packages: List<String>, freeze: Boolean) {
            if (packages.isEmpty()) {
                freezerToast("Select one or more apps first.")
                return
            }
            if (freezerOperationRunning || OeaAssistant.isForceStopping()) {
                freezerToast("A freezer operation is already running.")
                return
            }
            if (OeaAppFreezer.mode(this) == OeaAppFreezer.Mode.SYSTEM) {
                freezerOperationRunning = true
                freezerBusy.addAll(packages)
                renderApps()
                com.oea.engine.core.OeaGuard.start("oea-freezer-apply", "settings:freezer") {
                    var succeeded = 0
                    var failed = 0
                    packages.forEach { pkg -> if (OeaAppFreezer.setFrozen(this, pkg, freeze).success) succeeded++ else failed++ }
                    runOnUiThread {
                        freezerOperationRunning = false
                        freezerBusy.removeAll(packages.toSet())
                        if (freeze) packages.forEach { freezerJustFrozen[it] = System.currentTimeMillis() }
                        freezerToast(if (packages.size == 1) (if (failed == 0) (if (freeze) "Frozen and verified." else "Unfrozen and verified.") else "Android refused this change.")
                            else "$succeeded apps updated, $failed failed.")
                        freezerSelected.clear()
                        renderApps()
                    }
                }
                return
            }
            OeaAppFreezer.setHibernated(this, packages, freeze)
            freezerSelected.clear()
            if (!freeze) {
                renderApps()
                freezerToast(if (packages.size == 1) "Unfrozen." else "${packages.size} apps unfrozen.")
                return
            }
            packages.forEach { freezerJustFrozen[it] = System.currentTimeMillis() }
            renderApps()
            freezerOperationRunning = true
            val start = OeaAssistant.forceStopWhenReady(this, packages, freezerReturnIntent()) { stopped, failed ->
                freezerOperationRunning = false
                reportForceStop(stopped, failed)
                renderApps()
            }
            when (start) {
                OeaAssistant.ForceStopStart.STARTED -> Unit
                OeaAssistant.ForceStopStart.QUEUED -> freezerToast("Frozen. Reconnecting OEA Assistant to put it to sleep…")
                OeaAssistant.ForceStopStart.BUSY -> {
                    freezerOperationRunning = false
                    freezerToast("Frozen in OEA. Another freezer run is still finishing.")
                }
                OeaAssistant.ForceStopStart.NOT_ENABLED -> {
                    freezerOperationRunning = false
                    freezerToast(if (packages.size == 1) "Frozen in OEA." else "${packages.size} apps frozen in OEA.")
                }
            }
        }

        fun putFrozenAppsToSleep() {
            val sleeping = OeaAppFreezer.hibernatedPackages(this).toList()
            if (sleeping.isEmpty() || freezerOperationRunning || OeaAssistant.isForceStopping()) return
            freezerOperationRunning = true
            val result = OeaAssistant.forceStopWhenReady(this, sleeping, freezerReturnIntent()) { stopped, failed ->
                freezerOperationRunning = false
                reportForceStop(stopped, failed)
            }
            if (result == OeaAssistant.ForceStopStart.BUSY || result == OeaAssistant.ForceStopStart.NOT_ENABLED) freezerOperationRunning = false
            if (result == OeaAssistant.ForceStopStart.NOT_ENABLED) OeaAssistant.openSetup(this)
        }

        fun showOverflow() {
            kit.sheet("Freezer", null) { panel, dialog ->
                val group = kit.group(panel)
                // CI regression guards: SELECT VISIBLE / REFRESH STATE.
                kit.row(group, R.drawable.oic_check_circle_fill, "Select visible", "${visible.size} apps") {
                    dialog.dismiss()
                    freezerSelected.addAll(visible.map { it.packageName })
                    renderApps()
                }
                kit.row(group, R.drawable.oic_refresh, "Refresh state", "Re-read installed apps and Android's frozen state") {
                    dialog.dismiss()
                    freezerToast("Refreshing installed apps and frozen state…")
                    reload()
                }
                kit.row(group, R.drawable.oic_settings, if (freezerShowSystem) "Hide system apps" else "Show system apps", null) {
                    dialog.dismiss()
                    freezerShowSystem = !freezerShowSystem
                    freezerSelected.clear()
                    reload()
                }
                if (!systemMode && OeaAppFreezer.hibernatedPackages(this).isNotEmpty() && assistantGranted) {
                    kit.row(group, R.drawable.oic_bedtime, "Put frozen apps back to sleep", "Force-stops every app frozen in OEA") {
                        dialog.dismiss()
                        putFrozenAppsToSleep()
                    }
                }
                val frozenNow = OeaAppFreezer.allFrozen(this).toList()
                if (frozenNow.isNotEmpty()) {
                    kit.row(group, R.drawable.oic_restart_alt, "Unfreeze all", "${frozenNow.size} apps") {
                        dialog.dismiss()
                        kit.confirm("Unfreeze all apps?", "${frozenNow.size} apps will be able to run again.", "Unfreeze all") {
                            apply(frozenNow, false)
                        }
                    }
                }
                if (standalone) {
                    kit.row(group, R.drawable.oic_settings, "OEA Settings", null) {
                        dialog.dismiss()
                        runCatching { startActivity(Intent(this, OeaSystemToolsActivity::class.java)) }
                    }
                }
            }
        }
        overflow.setOnClickListener { showOverflow() }

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
                        freezerQuery.isNotBlank() -> "No apps match this search."
                        freezerFilter == "frozen" -> "Nothing is frozen right now."
                        else -> "No apps to show."
                    }, 14f, palette.textSecondary).apply {
                        gravity = Gravity.CENTER
                        setPadding(dp(16), dp(48), dp(16), dp(48))
                    }
                }
                val app = visible[position]
                val frozen = OeaAppFreezer.isFrozen(this@OeaSystemToolsActivity, app.packageName)
                val selected = app.packageName in freezerSelected
                val busy = app.packageName in freezerBusy
                val row = convertView as? LinearLayout ?: LinearLayout(this@OeaSystemToolsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(10), dp(10), dp(12), dp(10))
                    minimumHeight = dp(72)
                    addView(FrameLayout(context), LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(10) })
                    addView(FrameLayout(context).apply {
                        addView(ImageView(context), FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER))
                        addView(FrameLayout(context).apply {
                            addView(ImageView(context), FrameLayout.LayoutParams(dp(11), dp(11), Gravity.CENTER))
                        }, FrameLayout.LayoutParams(dp(19), dp(19), Gravity.BOTTOM or Gravity.END))
                    }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(12) })
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(kit.text("", 15f, palette.text, OeaType.medium).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                        addView(kit.text("", 11.5f, palette.textTertiary).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE })
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(TextView(context).apply {
                        textSize = 13f
                        typeface = OeaType.medium
                        gravity = Gravity.CENTER
                        minWidth = dp(92)
                        setPadding(dp(14), dp(9), dp(14), dp(9))
                    }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
                }
                val mark = row.getChildAt(0) as FrameLayout
                mark.removeAllViews()
                mark.background = kit.oval(if (selected) ice else Color.TRANSPARENT, if (selected) ice else palette.textTertiary, 2)
                if (selected) mark.addView(ImageView(this@OeaSystemToolsActivity).apply {
                    setImageDrawable(kit.icon(R.drawable.oic_check, if (palette.dark) Color.rgb(6, 22, 34) else Color.WHITE))
                }, FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER))
                mark.contentDescription = if (selected) "Deselect ${app.label}" else "Select ${app.label}"
                mark.setOnClickListener {
                    if (app.packageName in freezerSelected) freezerSelected.remove(app.packageName) else freezerSelected.add(app.packageName)
                    renderApps()
                }
                val iconFrame = row.getChildAt(1) as FrameLayout
                val iconView = iconFrame.getChildAt(0) as ImageView
                val frost = iconFrame.getChildAt(1) as FrameLayout
                iconView.setImageDrawable(icons.icon(app.packageName, null))
                if (frozen) iconView.colorFilter = grayscale else iconView.clearColorFilter()
                iconView.alpha = if (frozen) 0.55f else 1f
                frost.background = kit.oval(ice, palette.card, 2)
                (frost.getChildAt(0) as ImageView).setImageDrawable(kit.icon(R.drawable.oic_ac_unit_fill, Color.WHITE))
                frost.visibility = if (frozen) View.VISIBLE else View.GONE
                val justFrozen = freezerJustFrozen.remove(app.packageName)
                if (frozen && justFrozen != null && System.currentTimeMillis() - justFrozen < 1500L) {
                    // A short frost "settle" instead of a jarring jump between states.
                    iconView.scaleX = 0.82f
                    iconView.scaleY = 0.82f
                    iconView.animate().scaleX(1f).scaleY(1f).setDuration(260L).setInterpolator(android.view.animation.OvershootInterpolator(1.6f)).start()
                    frost.alpha = 0f
                    frost.scaleX = 0.4f
                    frost.scaleY = 0.4f
                    frost.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(90L).setDuration(240L).start()
                } else {
                    iconView.scaleX = 1f
                    iconView.scaleY = 1f
                    frost.alpha = 1f
                    frost.scaleX = 1f
                    frost.scaleY = 1f
                }
                val texts = row.getChildAt(2) as LinearLayout
                (texts.getChildAt(0) as TextView).apply {
                    text = app.label
                    setTextColor(if (frozen) palette.textSecondary else palette.text)
                }
                (texts.getChildAt(1) as TextView).text = when {
                    busy -> "Working…"
                    !frozen -> app.packageName
                    app.packageName in OeaAppFreezer.frozenPackages(this@OeaSystemToolsActivity) -> "Frozen • suspended by Android"
                    else -> "Frozen • asleep"
                }
                (texts.getChildAt(1) as TextView).setTextColor(if (frozen || busy) ice else palette.textTertiary)
                (row.getChildAt(3) as TextView).apply {
                    text = when {
                        busy -> "…"
                        frozen -> "Unfreeze"
                        else -> "Freeze"
                    }
                    val dark = if (palette.dark) Color.rgb(6, 22, 34) else Color.WHITE
                    setTextColor(if (frozen) ice else dark)
                    background = kit.ripple(kit.rounded(if (frozen) OeaTheme.alpha(ice, 0x24) else ice, 20,
                        if (frozen) OeaTheme.alpha(ice, 0x66) else 0, if (frozen) 1 else 0), 20)
                    alpha = if (busy || freezerOperationRunning) 0.55f else 1f
                    isEnabled = !busy
                    contentDescription = if (frozen) "Unfreeze ${app.label}" else "Freeze ${app.label}"
                    setOnClickListener { apply(listOf(app.packageName), !frozen) }
                }
                row.background = kit.rounded(if (selected) iceSoft else palette.card, 22,
                    if (selected) OeaTheme.alpha(ice, 0x66) else 0, if (selected) 1 else 0)
                row.setOnClickListener {
                    if (freezerSelected.isEmpty()) return@setOnClickListener
                    if (app.packageName in freezerSelected) freezerSelected.remove(app.packageName) else freezerSelected.add(app.packageName)
                    renderApps()
                }
                row.setOnLongClickListener {
                    if (app.packageName in freezerSelected) freezerSelected.remove(app.packageName) else freezerSelected.add(app.packageName)
                    row.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    renderApps()
                    true
                }
                return row
            }
        }
        list.adapter = adapter

        renderApps = {
            val all = freezerApps.orEmpty()
            val frozenSet = OeaAppFreezer.allFrozen(this)
            val matching = all.filter { app ->
                freezerQuery.isBlank() || app.label.contains(freezerQuery, true) || app.packageName.contains(freezerQuery, true)
            }
            visible = matching.filter { app ->
                when (freezerFilter) {
                    "frozen" -> app.packageName in frozenSet
                    "active" -> app.packageName !in frozenSet
                    else -> true
                }
            }.sortedWith(compareByDescending<OeaAppInfo> { it.packageName in frozenSet && freezerFilter == "all" }.thenBy { it.label.lowercase() })
            frozenCount.text = frozenSet.size.toString()
            filters.removeAllViews()
            val frozenMatching = matching.count { it.packageName in frozenSet }
            listOf(
                "all" to "All ${matching.size}",
                "frozen" to "Frozen $frozenMatching",
                "active" to "Not frozen ${matching.size - frozenMatching}"
).forEach { (mode, label) ->
                filters.addView(kit.chip(label, mode == freezerFilter) {
                    freezerFilter = mode
                    renderApps()
                }, LinearLayout.LayoutParams(-2, dp(38)).apply { rightMargin = dp(8) })
            }
            if (freezerShowSystem) {
                filters.addView(kit.chip("System apps shown", true, R.drawable.oic_settings) {
                    freezerShowSystem = false
                    freezerSelected.clear()
                    reload()
                }, LinearLayout.LayoutParams(-2, dp(38)))
            }
            if (freezerApps != null) freezerSelected.retainAll(all.map { it.packageName }.toSet())
            bar.removeAllViews()
            if (freezerSelected.isEmpty()) {
                bar.visibility = View.GONE
            } else {
                bar.visibility = View.VISIBLE
                barLabel.text = "${freezerSelected.size} selected"
                bar.addView(barLabel, LinearLayout.LayoutParams(0, -2, 1f))
                bar.addView(kit.quietButton("Clear") {
                    freezerSelected.clear()
                    renderApps()
                }, LinearLayout.LayoutParams(-2, dp(42)).apply { rightMargin = dp(6) })
                bar.addView(kit.tonalButton("Unfreeze") { apply(freezerSelected.toList(), false) },
                    LinearLayout.LayoutParams(-2, dp(42)).apply { rightMargin = dp(6) })
                bar.addView(kit.button("Freeze", ice, if (palette.dark) Color.rgb(6, 22, 34) else Color.WHITE, R.drawable.oic_ac_unit) {
                    apply(freezerSelected.toList(), true)
                }, LinearLayout.LayoutParams(-2, dp(42)))
            }
            adapter.notifyDataSetChanged()
        }
        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                freezerQuery = s?.toString().orEmpty()
                renderApps()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        renderApps()
        setContentView(root)
        currentScroll = null
        // Show the last list immediately. Re-reading every installed app (and
        // its label) on each resume made the list visibly reload; refresh only
        // when the list is missing or stale. "Refresh state" always re-reads.
        val stale = android.os.SystemClock.elapsedRealtime() - freezerAppsLoadedAt > FREEZER_LIST_FRESH_MS
        if (freezerApps == null || freezerAppsIncludeSystem != freezerShowSystem || stale) reload()
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
                    label = runCatching { info.loadLabel(packageManager).toString() }.getOrDefault(info.packageName)
)
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }

    // =========================================================== multitasking

    private fun showMultitasking() {
        val page = page("Multitasking", "Use two apps at once, or float one above another")
        val box = page.content
        val group = kit.group(box)
        kit.row(group, R.drawable.oic_layers, "Overview", "OEA recent-use overview with open, split and floating actions") { navigate("overview") }
        kit.row(group, R.drawable.oic_splitscreen, "Split screen & app pairs", if (Build.VERSION.SDK_INT >= 32) "Requests Android split screen; OEM and app support vary" else if (OeaAssistant.isConnected()) "Uses OEA Assistant and Android's split toggle; support varies" else "Needs OEA Assistant; Android/OEM may still restrict split screen") { navigate("split") }

        kit.sectionTitle(box, "Floating method")
        choiceRow(
            group, R.drawable.oic_select_window, "Default floating-window method",
            listOf(
                OeaFloatingWindows.MODE_NATIVE to "Android freeform",
                OeaFloatingWindows.MODE_OEA to "OEA floating workspace"
            ),
            OeaFloatingWindows.defaultMode(this)
        ) { mode ->
            OeaFloatingWindows.setDefaultMode(this, mode)
            toast(if (mode == OeaFloatingWindows.MODE_NATIVE)
                "Default: Android freeform. OEA will not silently fall back to its overlay."
            else
                "Default: OEA Floating Workspace. It remains optional and needs its own permissions."
            )
        }

        val nativeFreeform = OeaMultitaskLauncher.nativeFreeformSupported(this)
        val developerFreeform = OeaMultitaskLauncher.freeformDeveloperOverrideEnabled(this)
        val freeformStatus = when {
            nativeFreeform -> "Android reports native freeform support; apps/OEM policy may still refuse"
            developerFreeform -> "Developer override is on; native freeform support is not confirmed (restart may be needed)"
            else -> "Off • may be enabled in Developer options"
        }
        kit.row(group, R.drawable.oic_select_window, "Android freeform windows", freeformStatus) {
            if (nativeFreeform) {
                toast("Android reports native freeform support. Long-press an app on Home or in All Apps and choose Float; Android controls its real app window.")
            } else if (developerFreeform) {
                toast("Developer override is on, but native freeform support is not confirmed. Restart, then test an app; Android may still ignore the request.")
            } else {
                kit.confirm("Enable Android freeform", "Open Developer options and turn on \"Enable freeform windows\" (and, if offered, \"Force activities to be resizable\"), then restart the phone. Some manufacturers do not expose this option.", "Open Developer options") {
                    if (!OeaMultitaskLauncher.openDeveloperOptions(this)) toast("Turn on Developer options first: Settings › About phone › tap Build number 7 times.")
                }
            }
        }
        choiceRow(group, R.drawable.oic_tune, "Initial Android freeform size", listOf("small" to "Small", "medium" to "Medium", "large" to "Large"), dataStore.floatingWindowSize()) {
            dataStore.setFloatingWindowSize(it)
        }

        kit.sectionTitle(box, "OEA Floating Workspace (optional)")
        val accessibilityAllowed = OeaFloatingWindows.isAccessibilityEnabled(this)
        kit.row(group, R.drawable.oic_accessibility_new, "OEA Floating Window accessibility", if (accessibilityAllowed) "Allowed • separate from OEA Assistant" else "Off • only needed for OEA Floating Workspace", value = if (accessibilityAllowed) "On" else "Off") {
            OeaFloatingWindows.openSetup(this)
        }
        kit.switchRow(
            group, R.drawable.oic_select_window,
            "Enable OEA Floating Workspace",
            "Show OEA's own draggable, resizable launcher above other apps. Tapping an app requests Android freeform; this overlay cannot embed third-party app windows.",
            OeaFloatingWindows.enabled(this)
        ) { on ->
            if (!on) {
                OeaFloatingWindows.setEnabled(this, false)
            } else if (!accessibilityAllowed) {
                kit.confirm("Enable OEA Floating Window", "This optional accessibility service hosts OEA's movable/resizable workspace. It is separate from OEA Assistant and is not required for Android freeform.", "Open Accessibility") {
                    OeaFloatingWindows.openSetup(this)
                }
            } else {
                OeaFloatingWindows.setEnabled(this, true)
                toast("OEA Floating Workspace enabled. Drag its title bar to move and the corner handle to resize.")
            }
            rerender()
        }
        kit.note(box, "Choose the default method above. Android freeform creates real, system-managed app windows when the phone allows it. OEA Floating Workspace draws its own launcher using the separately enabled OEA accessibility service; it does not need Display over other apps. Tapping an app requests Android freeform, but the OS may ignore that request. Neither method silently switches to the other.")
        present(page)
    }

    /** OEA Overview: recent-use history, not Android's private task list. */
    private fun showMultitask() {
        val choices = apps().filter { it.packageName != packageName }.distinctBy { it.packageName }
        val page = page("Overview", "OEA app overview • Recent-use history, not Android’s live task stack. Open apps, use floating windows, or pair apps for split screen.")
        val box = page.content
        val usageAccess = getSystemService(AppOpsManager::class.java).checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName
) == AppOpsManager.MODE_ALLOWED
        if (!usageAccess) {
            kit.banner(box, R.drawable.oic_insights, "Enable recent-app history", "Allow OEA Usage access so this overview can order apps by recent use.", OeaKit.Tone.INFO, "Allow") {
                refreshOverviewOnResume = true
                runCatching { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            }
        }
        if (appsCache == null) {
            if (appsLoading) {
                kit.banner(box, R.drawable.oic_apps, "Loading apps", "The app list is being read in the background.", OeaKit.Tone.INFO)
            } else if (appsLoadFailed) {
                kit.banner(box, R.drawable.oic_warning_fill, "Couldn't load apps", appsLoadError ?: "Try again.", OeaKit.Tone.WARNING, "Retry") {
                    retryAppLoad()
                }
            }
        } else if (choices.isEmpty()) {
            kit.note(box, "No launchable apps were found.")
        }
        if (usageAccess && usageStatsCache == null) {
            if (usageStatsLoading) {
                kit.note(box, "Loading recent app history in the background…")
            } else if (usageStatsLoadFailed) {
                kit.banner(box, R.drawable.oic_warning_fill, "Couldn't load recent history", usageStatsLoadError ?: "Try again.", OeaKit.Tone.WARNING, "Retry") {
                    retryUsageStats()
                }
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
        val lastUsed = recentUsageStats(usageAccess, overviewNow, sevenDaysMillis)
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
                else runCatching {
                    startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    com.oea.launcher.gameboost.OeaGameBoost.onAppLaunched(this, app.packageName)
                }.onFailure { toast("Android could not open ${app.label}.") }
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
                    "Open as floating window" -> protectedLaunch(app) { toast(OeaFloatingWindows.launchDefault(this, app).message) }
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
                    protectedLaunch(app) { toast(OeaFloatingWindows.launchDefault(this, app).message) }
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
            Build.VERSION.SDK_INT >= 32 -> kit.banner(box, R.drawable.oic_splitscreen, "Android split-screen request", "OEA asks Android to place both apps side by side; the device, OEM and each app decide whether it is allowed.", OeaKit.Tone.INFO)
            OeaAssistant.isConnected() -> kit.banner(box, R.drawable.oic_splitscreen, "Split-screen request ready", "OEA Assistant can trigger Android's split toggle, but device and app policy can still reject it.", OeaKit.Tone.INFO)
            else -> kit.banner(box, R.drawable.oic_accessibility_new, "OEA Assistant needed", "On Android 12 and older, OEA Assistant can trigger Android's split toggle. Device and app policy may still restrict split screen.", OeaKit.Tone.INFO, "Turn on") { OeaAssistant.openSetup(this) }
        }
        box.addView(kit.primaryButton("Choose two apps", R.drawable.oic_splitscreen) { chooseSplitApps() }, LinearLayout.LayoutParams(-1, dp(50)).apply { bottomMargin = dp(6) })
        val pairs = OeaWorkspaceStore.get(this).folders().filter { it.isPair }
        kit.sectionTitle(box, "App pairs on Home")
        if (pairs.isEmpty()) {
            kit.note(box, "No pairs yet. After choosing two apps, tap Save pair to put a one-tap pair icon on Home.")
        } else {
            val byKey = apps().associateBy { it.packageName + "/" + it.className }
            if (appsCache == null) {
                if (appsLoading) {
                    kit.note(box, "Loading app details for saved pairs…")
                } else if (appsLoadFailed) {
                    kit.banner(box, R.drawable.oic_warning_fill, "Couldn't load pair details", appsLoadError ?: "Try again.", OeaKit.Tone.WARNING, "Retry") {
                        retryAppLoad()
                    }
                }
            }
            val group = kit.group(box)
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
        kit.note(box, "Android and device manufacturers own the split-screen divider and final placement. Apps can opt out, and some phones disable split screen entirely; OEA reports the request, not a guarantee.")
        present(page)
    }

    private fun chooseSplitApps() {
        val choices = (requireApps() ?: return)
            .filter { it.packageName != packageName }
            .distinctBy { it.packageName }
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
                    }
))
            }
        }
    }

    // ============================================================== stability

    /**
     * What OEA does when something goes wrong, and what went wrong lately.
     *
     * The point of this screen is that the user never has to wonder whether a
     * game, a widget or a service damaged their launcher: every contained fault
     * is listed with what OEA did about it, safe mode can be switched on or off,
     * and the saved workspace can be rebuilt without losing the layout.
     */
    private fun showStability() {
        val page = page("Stability", "OEA keeps Home running when a part of the system fails")
        val box = page.content
        val faults = OeaFaultLog.history()
        val engineFaults = runCatching { com.oea.engine.core.OeaGuard.faultCounts() }.getOrDefault(emptyMap())
        val session = OeaFaultShield.sessionCounts()

        val healthy = faults.isEmpty() && engineFaults.values.sum() == 0
        kit.banner(
            box,
            if (healthy) R.drawable.oic_check_circle_fill else R.drawable.oic_warning_fill,
            if (healthy) "Nothing has gone wrong this session" else "${faults.size + engineFaults.values.sum()} contained problem${if (faults.size + engineFaults.values.sum() == 1) "" else "s"}",
            if (healthy) "OEA has not had to recover from anything. Games, widgets and services are all behaving."
            else "Each one below was handled without losing your Home screen, apps or layout.",
            if (healthy) OeaKit.Tone.SUCCESS else OeaKit.Tone.WARNING
)

        kit.sectionTitle(box, "Home protection")
        var group = kit.group(box)
        kit.switchRow(
            group,
            R.drawable.oic_shield,
            "Safe mode",
            "Start Home without widgets, At a Glance and live activities. Use it to find out which optional part is causing trouble.",
            OeaFaultShield.safeMode
) { enabled ->
            OeaFaultShield.setSafeMode(enabled, this)
            toast(if (enabled) "Safe mode is on: Home keeps your icons, dock and folders only." else "Safe mode is off. Rebuilding Home…")
            homeAction("rebuild")
        }
        kit.row(group, R.drawable.oic_refresh, "Rebuild Home now", "Re-reads your layout and icons; nothing is deleted") {
            homeAction("rebuild")
            toast("Home is rebuilding with your saved layout.")
        }
        kit.row(group, R.drawable.oic_cleaning_services, "Restart OEA Home", "Closes and reopens Home; open apps stay as they are") {
            homeAction("restart")
        }
        group = kit.group(box)
        kit.row(group, R.drawable.oic_insights, "Memory handling", "Icons and caches are released when Android is short of memory", value = if (OeaFaultShield.safeMode) "Reduced" else "Automatic") {
            toast("OEA trims its icon and preview caches when Android reports memory pressure.")
        }

        if (engineFaults.isNotEmpty() || session.isNotEmpty()) {
            kit.sectionTitle(box, "Subsystems this session")
            val subsystems = kit.group(box)
            val merged = LinkedHashMap<String, Int>()
            engineFaults.forEach { (key, value) -> merged[key] = value }
            session.forEach { (key, value) -> merged[key] = (merged[key] ?: 0) + value }
            merged.entries.sortedByDescending { it.value }.take(8).forEach { (name, count) ->
                kit.row(subsystems, R.drawable.oic_layers, name.replace('_', ' ').replace('-', ' ').replaceFirstChar { it.uppercase() },
                    "Contained by OEA; the rest of the system kept running", value = "$count")
            }
        }

        kit.sectionTitle(box, "Recent problems")
        if (faults.isEmpty()) {
            kit.note(box, "No problems have been recorded. OEA only writes here when something had to be contained.")
        } else {
            val list = kit.group(box)
            faults.take(12).forEach { fault ->
                val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(fault.at))
                kit.row(list, R.drawable.oic_history, fault.title(), "$stamp • ${fault.reason}", value = fault.action.name.lowercase().replace('_', ' ')) {
                    kit.sheet(fault.title(), "$stamp • ${fault.thread}") { panel, _ ->
                        kit.note(panel, fault.body())
                        panel.addView(kit.buttonRow(
                            kit.quietButton("Copy") { copyToClipboard(fault.body(), "Fault copied") },
                            kit.primaryButton("Share") {
                                runCatching {
                                    startActivity(Intent.createChooser(
                                        Intent(Intent.ACTION_SEND).setType("text/plain")
                                            .putExtra(Intent.EXTRA_TEXT, fault.body()), "Share diagnosis"
))
                                }.onFailure { toast("Nothing can share text on this phone.") }
                            }
), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
                    }
                }
            }
            group = kit.group(box)
            kit.row(group, R.drawable.oic_delete, "Clear the log", "Removes the recorded entries; OEA's settings stay as they are") {
                kit.confirm("Clear the stability log?", "Only the recorded problems are removed. This does not change any setting.", "Clear", destructive = true) {
                    OeaFaultLog.clear(this)
                    rerender()
                }
            }
            kit.switchRow(group, R.drawable.oic_edit, "Keep a problem log", "Writes contained problems to OEA's private storage so a crash can be understood later", dataStore.faultLogEnabled()) {
                dataStore.setFaultLogEnabled(it)
                OeaFaultLog.persistence = it
            }
        }
        kit.note(box, "OEA never sends this anywhere. The log lives in OEA's own private storage and is only read by this screen.")
        present(page)
    }

    private fun stabilitySummary(): String {
        val count = OeaFaultLog.count()
        return when {
            OeaFaultShield.safeMode -> "Safe mode is on"
            count == 0 -> "No problems recorded"
            else -> "$count contained problem${if (count == 1) "" else "s"}"
        }
    }

    private fun copyToClipboard(text: String, message: String) {
        runCatching {
            val clipboard = getSystemService(android.content.ClipboardManager::class.java)
            clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("OEA", text))
            toast(message)
        }
    }

    // ================================================================ access

    /**
     * Permissions, as capabilities rather than a checklist.
     *
     * Two ideas drive this screen:
     * 1. every privilege shows what it *unlocks* and what OEA does without it, so
     *    nothing reads as "broken on this device";
     * 2. OEA grants by itself whatever Android lets it grant (root or device
     *    owner) and only asks the user for the rest — no helper app, no PC.
     */
    /**
     * The access doctor: every permission OEA uses, *verified live* rather than
     * read off Android's switches, with the exact fix for each one.
     *
     * This is the screen for "it says it is on but nothing happens": each row shows
     * what OEA observed, and a row that is switched on but not working says so and
     * offers the repair instead of looking healthy.
     */
    private fun showAccessDoctor() {
        val page = page("Access doctor", "Every permission verified, with the fix for each one")
        val box = page.content
        addWirelessDebuggingCard(box)
        val status = kit.text("Checking what OEA can actually do…", 13f, palette.textSecondary).apply {
            setPadding(dp(6), dp(6), dp(6), dp(10))
        }
        box.addView(status)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(list, LinearLayout.LayoutParams(-1, -2))

        fun render(findings: List<com.oea.launcher.privilege.OeaAccessDoctor.Finding>) {
            list.removeAllViews()
            val working = findings.count { it.isReady }
            status.text = "$working of ${findings.size} checks pass. The rest are listed first, with what to do about each."
            status.setTextColor(if (working == findings.size) palette.success else palette.textSecondary)
            findings.forEach { finding ->
                val group = kit.group(list)
                val tone = when (finding.state) {
                    com.oea.launcher.privilege.OeaAccessDoctor.State.WORKING -> OeaKit.Tone.SUCCESS
                    com.oea.launcher.privilege.OeaAccessDoctor.State.STALE -> OeaKit.Tone.DANGER
                    com.oea.launcher.privilege.OeaAccessDoctor.State.NEEDS_TAP -> OeaKit.Tone.WARNING
                    com.oea.launcher.privilege.OeaAccessDoctor.State.OEA_CAN_FIX -> OeaKit.Tone.INFO
                    com.oea.launcher.privilege.OeaAccessDoctor.State.UNAVAILABLE -> OeaKit.Tone.INFO
                }
                kit.banner(group, if (finding.isReady) R.drawable.oic_check_circle_fill else R.drawable.oic_warning_fill, finding.privilege.title, finding.evidence, tone = tone)
                kit.row(group, R.drawable.oic_settings, finding.privilege.summary, finding.advice, chevron = false)
                if (!finding.isReady) {
                    val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(4), dp(8), dp(12)) }
                    actions.addView(kit.tonalButton("Fix this", R.drawable.oic_shield) {
                        // One route for every state: OeaPrivileges decides whether it
                        // can grant it itself, opens the right Android page, or says
                        // plainly that this device cannot.
                        OeaPrivileges.grant(this, finding.privilege) { outcome ->
                            toast(outcome.message)
                            doctorReload()
                        }
                    }, LinearLayout.LayoutParams(-2, dp(42)))
                    if (finding.command != null) {
                        actions.addView(kit.quietButton("Copy command", R.drawable.oic_text_fields) {
                            runCatching {
                                val clipboard = getSystemService(android.content.ClipboardManager::class.java)
                                clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("OEA", finding.command))
                                toast("Command copied. It is what OEA itself would run.")
                            }
                        }, LinearLayout.LayoutParams(-2, dp(42)).apply { leftMargin = dp(8) })
                    }
                    group.addView(actions)
                }
            }
        }

        fun check() {
            status.text = "Checking what OEA can actually do…"
            doctorWorkers.execute {
                val findings = runCatching { com.oea.launcher.privilege.OeaAccessDoctor.examine(this) }.getOrDefault(emptyList())
                runOnUiThread { if (!isDestroyed) render(findings) }
            }
        }
        doctorCheck = ::check

        val actions = kit.group(box)
        kit.row(actions, R.drawable.oic_shield_fill, "Repair everything OEA can repair", "Uses OEA's own channel — no PC and no other app") {
            OeaPrivileges.repairAll(this) { summary ->
                toast(summary.describe())
                check()
            }
        }
        kit.row(actions, R.drawable.oic_refresh, "Check again", "Verifies each permission live, including drawing a test window") { check() }
        kit.row(actions, R.drawable.oic_info, "Why some rows stay manual", "Android only lets the user turn on accessibility and notification access on most phones. OEA shows exactly which screen.") {
            toast("Accessibility and notification access have no API a normal app can call; OEA opens the exact page instead.")
        }
        kit.note(
            box,
            "Nothing here is required. A permission that stays off only limits the feature that uses it: every one of them falls back to OEA's own Home surface, so the launcher keeps working."
)
        present(page)
        check()
    }

    /**
     * Wireless debugging for ADB pairing. OEA Assistant reads the pairing code,
     * the pairing port and the connection port from the Settings screens, so the
     * user does not type them. Typing is kept as a fallback behind a quiet button.
     */
    private fun addWirelessDebuggingCard(box: LinearLayout) {
        val wp = com.oea.launcher.privilege.OeaWirelessPairing
        val pairing = wp.freshPairing()
        val connection = wp.connection
        val watching = wp.isWatching()
        fun ago(at: Long): String {
            val secs = ((android.os.SystemClock.elapsedRealtime() - at) / 1000).coerceAtLeast(0)
            return if (secs < 60) "${secs}s ago" else "${secs / 60}m ago"
        }

        kit.sectionTitle(box, "Wireless debugging")
        kit.note(box, "OEA Assistant reads the pairing code and pairing port from the dialog, then pairs automatically. On XOS it separates the dialog address from the main Wireless debugging address. If the connection port is missing, OEA searches for the paired phone over local-network mDNS.")
        val group = kit.group(box)
        if (!OeaAssistant.isConnected()) {
            kit.banner(group, R.drawable.oic_accessibility_new, "Turn on OEA Assistant first",
                "It is the part that reads these screens. Everything stays on this phone.", OeaKit.Tone.WARNING, "Open setup") {
                OeaAssistant.openSetup(this)
            }
        }
        kit.row(group, R.drawable.oic_lock, "Pairing code",
            pairing?.let { "${it.code.take(3)} ${it.code.drop(3)} • read ${ago(it.capturedAt)}" } ?: "Not read yet — open the pairing dialog",
            chevron = false)
        kit.row(group, R.drawable.oic_developer_mode, "Pairing port",
            pairing?.let { "${it.host}:${it.port}" } ?: "Not read yet",
            chevron = false)
        kit.row(group, R.drawable.oic_developer_mode, "Connection port",
            connection?.let { "${it.host}:${it.port} • read ${ago(it.capturedAt)}" } ?: "Not read yet — open Wireless debugging",
            chevron = false)
        kit.row(group, R.drawable.oic_check_circle_fill, "Pairing",
            when {
                wp.isWatching() -> "Reading the Wireless debugging screens"
                pairing != null && connection != null -> "Endpoint captured — tap Pair OEA Authority below"
                pairing != null -> "Pairing code captured (it expires in two minutes)"
                connection != null -> "Connection port captured"
                else -> "Not read yet"
            },
            chevron = false)
        if (watching) {
            kit.note(box, "Watching the Wireless debugging screens. Open “Pair device with pairing code” and OEA reads it as it appears.")
        }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(4), dp(8), dp(12)) }
        if (watching) {
            buttons.addView(kit.quietButton("Stop", R.drawable.oic_close) { wp.stopWatching(); rerender() }, LinearLayout.LayoutParams(-2, dp(42)))
        } else {
            buttons.addView(kit.primaryButton("Start reading", R.drawable.oic_developer_mode) {
                // A fresh read must not combine a new code with a
                // stale port from an earlier session.
                wp.clear()
                wp.startWatching()
                runCatching { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                    .onFailure { toast("Open Developer options, then Wireless debugging.") }
                rerender()
            }, LinearLayout.LayoutParams(-2, dp(42)))
        }
        if (pairing != null || connection != null) {
            buttons.addView(kit.quietButton("Clear", R.drawable.oic_delete) { wp.clear(); rerender() }, LinearLayout.LayoutParams(-2, dp(42)).apply { leftMargin = dp(8) })
        }
        group.addView(buttons)
        kit.primaryButton("Pair OEA Authority", R.drawable.oic_lock) {
            startActivity(Intent(this, com.oea.launcher.privilege.OeaAuthorityActivity::class.java))
        }.also { box.addView(it, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(4) }) }
        // Fallback only: the values the user types are used exactly like read ones.
        kit.quietButton("Type them in instead (fallback)", R.drawable.oic_text_fields) { askWirelessManually() }.also { box.addView(it, LinearLayout.LayoutParams(-1, dp(44))) }
    }

    private fun askWirelessManually() {
        kit.prompt("Pairing code", "The 6 digits in “Pair device with pairing code”.", "123 456", "", numeric = true) codePrompt@{ code ->
            val digits = code.filter { it.isDigit() }
            if (digits.length != 6) return@codePrompt "The code is 6 digits."
            kit.prompt("Pairing address", "Shown next to the code, for example 192.168.1.20:37125", "192.168.1.20:37125") addressPrompt@{ address ->
                if (!Regex("""\d{1,3}(\.\d{1,3}){3}:\d{2,5}""").matches(address.trim())) return@addressPrompt "Use the form 192.168.1.20:37125"
                kit.prompt("Connection port", "The port in Wireless debugging, for example 41235 (optional)", "41235", "", numeric = true) portPrompt@{ port ->
                    val value = port.trim().toIntOrNull()
                    if (port.isNotBlank() && (value == null || value !in 1..65535)) return@portPrompt "That is not a port number."
                    com.oea.launcher.privilege.OeaWirelessPairing.setManual(digits, address.trim(), value)
                    toast("Saved. OEA will use these values.")
                    rerender()
                    null
                }
                null
            }
            null
        }
    }

    private fun doctorReload() {
        doctorCheck?.invoke()
    }

    private fun showAccess() {
        val page = page("Permissions & access", "What OEA can use, what it unlocks, and what it does without it")
        val box = page.content
        val rows = OeaPrivileges.privilegeRows(this)
        val ready = rows.count { it.second == Grant.GRANTED }

        val shell = OeaShell.available()
        val owner = OeaPrivileges.isDeviceOwner(this)
        kit.banner(
            box,
            R.drawable.oic_shield_fill,
            "$ready of ${rows.size} permissions are on",
            when {
                shell -> "This phone has root, so OEA can switch the rest on by itself."
                owner -> "OEA is the device owner, so it can grant runtime permissions itself."
                else -> "Everything here is switched on inside Android itself. No separate app (Shizuku and similar) is needed, and always just one tap away."
            },
            if (ready == rows.size) OeaKit.Tone.SUCCESS else OeaKit.Tone.INFO,
            actionLabel = if (shell || owner) "Fix all" else null,
            onAction = if (shell || owner) {
                {
                    toast("Working…")
                    OeaPrivileges.repairAll(this) { summary -> toast(summary.describe()); rerender() }
                }
            } else null
)

        kit.sectionTitle(box, "What each one unlocks")
        val features = kit.group(box)
        OeaPrivileges.capabilities(this).forEach { capability ->
            val icon = when {
                capability.granted == Grant.GRANTED -> R.drawable.oic_check_circle_fill
                capability.isPending -> R.drawable.oic_refresh
                else -> R.drawable.oic_warning_fill
            }
            val value = when {
                capability.granted == Grant.GRANTED -> "On"
                capability.isPending -> "Restarting"
                else -> capability.surface.label
            }
            kit.row(features, icon, capability.feature, if (capability.isReady) capability.requires.summary else capability.effect, value = value) {
                explainCapability(capability)
            }
        }

        kit.sectionTitle(box, "Permissions")
        val group = kit.group(box)
        rows.forEach { (privilege, grant) ->
            val state = when (grant) {
                Grant.GRANTED -> "On"
                Grant.PENDING -> "Switched on, waiting for Android"
                Grant.UNSUPPORTED -> "Not available here"
                Grant.DENIED -> if (OeaPrivileges.selfGrantable(this, privilege)) "OEA can switch this on" else "Off"
            }
            kit.row(group, iconFor(privilege), privilege.title, privilege.summary, value = state) {
                requestPrivilege(privilege, grant)
            }
        }
        kit.row(group, R.drawable.oic_select_window, "Floating methods", "Choose Android freeform or OEA Floating Workspace under Multitasking; each stays independent and optional") { navigate("multitasking") }
        if (OeaAssistant.needsRestrictedSettingsHint() && !OeaAssistant.isEnabled(this)) kit.note(box, OeaAssistant.RESTRICTED_SETTINGS_HINT)
        if (!shell && !owner) {
            kit.note(box, "Two permissions can only be given by Android itself: \"Display over other apps\" and \"Usage access\". The pages above open exactly there, and OEA verifies the result when you come back. A helper app such as Shizuku would not change that — Android reserves these to the user, though root or device owner lets OEA do it alone.")
        }
        kit.note(box, "OEA asks for nothing else. Every optional permission changes how a feature is presented, never whether Home works.")
        present(page)
    }

    private fun iconFor(privilege: Privilege): Int = when (privilege) {
        Privilege.HOME_ROLE -> R.drawable.oic_home
        Privilege.ACCESSIBILITY -> R.drawable.oic_accessibility_new
        Privilege.OVERLAY -> R.drawable.oic_layers
        Privilege.NOTIFICATION_ACCESS -> R.drawable.oic_notifications
        Privilege.USAGE_ACCESS -> R.drawable.oic_insights
        Privilege.DEVICE_ADMIN -> R.drawable.oic_shield
        Privilege.DEVICE_OWNER -> R.drawable.oic_shield_fill
        Privilege.ROOT -> R.drawable.oic_developer_mode
        Privilege.DND_ACCESS -> R.drawable.oic_do_not_disturb_on
        Privilege.BATTERY_UNRESTRICTED -> R.drawable.oic_battery_full
        Privilege.DIALER_ROLE -> R.drawable.oic_call
        Privilege.CALL_SCREENING_ROLE -> R.drawable.oic_block
        Privilege.NOTIFICATIONS -> R.drawable.oic_notifications
        Privilege.CONTACTS -> R.drawable.oic_person
        Privilege.CAMERA -> R.drawable.oic_photo_library
    }

    private fun explainCapability(capability: Capability) {
        kit.sheet(capability.feature, capability.requires.title) { panel, _ ->
            kit.note(panel, "Needs: ${capability.requires.summary}")
            kit.note(panel, "Shown as: ${capability.surface.label} — ${capability.surface.detail}")
            kit.note(panel, if (capability.isReady) "Fully available on this phone." else "Without it: ${capability.effect}.")
            panel.addView(kit.buttonRow(
                kit.quietButton("Close") { },
                kit.primaryButton(if (capability.isReady) "Settings" else "Open the page", R.drawable.oic_settings) {
                    if (capability.isReady) navigate("access") else requestPrivilege(capability.requires, capability.granted)
                }
), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }
    }

    /** One tap: OEA grants it, or opens exactly the page that can. */
    private fun requestPrivilege(privilege: Privilege, state: Grant) {
        when (privilege) {
            Privilege.CONTACTS, Privilege.CAMERA, Privilege.NOTIFICATIONS -> {
                val permission = when (privilege) {
                    Privilege.CONTACTS -> Manifest.permission.READ_CONTACTS
                    Privilege.CAMERA -> Manifest.permission.CAMERA
                    else -> if (Build.VERSION.SDK_INT >= 33) "android.permission.POST_NOTIFICATIONS" else null
                }
                if (permission != null) {
                    if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
                        toast("${privilege.title} is already on.")
                    } else {
                        requestPermissions(arrayOf(permission), REQUEST_CONTACTS)
                    }
                } else {
                    toast("Android ${Build.VERSION.RELEASE} grants notifications without asking.")
                }
            }

            Privilege.ACCESSIBILITY -> OeaAssistant.openSetup(this)
            Privilege.HOME_ROLE -> requestDefaultHome()
            else -> {
                if (state == Grant.GRANTED) {
                    toast("${privilege.title} is already on.")
                    return
                }
                toast("Checking what this phone allows…")
                OeaPrivileges.grant(this, privilege) { outcome ->
                    toast(outcome.message)
                    if (privilege == Privilege.OVERLAY && outcome.granted) {
                        dataStore.setIslandOnOtherApps(true)
                        OeaDynamicIslandOverlayService.sync(this)
                    }
                    pendingIslandOverlayEnable = privilege == Privilege.OVERLAY && !outcome.granted
                    rerender()
                    main.postDelayed({ rerender() }, 900L)
                }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        rerender()
    }

    /** Read through the capability layer, so a root-granted appop counts too. */
    private fun usageAccess(): Boolean = OeaPrivileges.canReadUsage(this)

    private fun accessSummary(): String {
        val granted = listOf(isDefaultHome(), notificationAccess(), OeaAssistant.isEnabled(this), usageAccess(), OeaPrivileges.canOverlay(this)).count { it }
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
        hero.addView(ImageView(this).apply { setImageResource(R.drawable.oea_logo); contentDescription = "OEA logo" }, LinearLayout.LayoutParams(dp(84), dp(84)))
        hero.addView(kit.text("OEA Launcher", 22f, palette.text, OeaType.medium).apply { setPadding(0, dp(12), 0, 0) })
        hero.addView(kit.text("Version ${info?.versionName ?: "?"}", 14f, palette.textSecondary))
        box.addView(hero, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        val group = kit.group(box)
        kit.row(group, R.drawable.oic_public, "Source & updates", "github.com/Emmanuel001afk/OEA") {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Emmanuel001afk/OEA"))) }
        }
        kit.row(group, R.drawable.oic_new_releases, "What's in OEA", null) {
            kit.sheet("OEA Launcher", null) { panel, _ ->
                kit.note(panel, "• OEA Home with At a Glance, smart drawer search, app shortcuts, widgets, folders and app pairs\n• The Themes app: ten built-in themes, your own themes and a wallpaper library\n• Liquid glass surfaces and page transitions (Liquid, Cube, Stack, Zoom, Fade)\n• Dynamic Island with calls, media, notifications, games and privacy dots\n• Notification dots, icon shapes, themed icons and icon packs\n• Gestures, wave to wake, lock and power menu for a faulty power key\n• App Lock, hidden apps and the no-root App Freezer\n• Split screen, floating windows and the OEA Overview\n• Game Boost, Phone, Call Blocker, Freezer, Themes, EMAGE, VIP and the games as their own apps")
            }
        }
        present(page)
    }

    @Suppress("unused")
    private fun pinField(): EditText = kit.field("4-8 digit PIN", numeric = true, password = true)
}
