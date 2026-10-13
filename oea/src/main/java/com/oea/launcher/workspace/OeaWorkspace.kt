package com.oea.launcher.workspace

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.app.ActivityOptions
import android.app.WallpaperManager
import android.app.role.RoleManager
import android.appwidget.AppWidgetHostView
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.speech.RecognizerIntent
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.oea.launcher.OeaSystemToolsActivity
import com.oea.launcher.R
import com.oea.launcher.applock.OeaAppFreezer
import com.oea.launcher.applock.OeaAppLockStore
import com.oea.launcher.assistant.OeaAssistant
import com.oea.launcher.assistant.OeaSystemActions
import com.oea.launcher.data.OeaDataStore
import com.oea.launcher.focus.OeaFocusStore
import com.oea.launcher.icons.OeaIconController
import com.oea.launcher.model.OeaAppInfo
import com.oea.launcher.notifications.OeaNotificationListener
import com.oea.launcher.notifications.OeaNotificationState
import com.oea.launcher.search.OeaSearchAction
import com.oea.launcher.search.OeaSearchController
import com.oea.launcher.shortcuts.OeaShortcutController
import com.oea.launcher.split.OeaSplitLauncher
import com.oea.launcher.ui.OeaInsets
import com.oea.launcher.ui.OeaKit
import com.oea.launcher.ui.OeaPalette
import com.oea.launcher.ui.OeaTheme
import com.oea.launcher.ui.OeaType
import com.oea.launcher.widgets.OeaWidgetController
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * OEA Home: wallpaper-transparent pages of icons and widgets, At a Glance,
 * page indicator, glass dock and search pill, the app drawer, popups,
 * folders, app pairs and launcher-grade drag and drop.
 *
 * One instance lives for the whole process and is re-attached to the Home
 * activity whenever Android recreates it.
 */
class OeaWorkspace(context: Context) : FrameLayout(context) {
    enum class TileStyle { HOME, DOCK, DRAWER, FOLDER }
    internal enum class Source { HOME_ITEM, HOME_FOLDER, DOCK, DRAWER, FOLDER_MEMBER }

    /** Where an app tile lives, so popups and drags know what "Remove" means. */
    internal data class TileRef(
        val app: OeaAppInfo? = null,
        val item: OeaWorkspaceStore.Item? = null,
        val folder: OeaWorkspaceStore.Folder? = null,
        val dockIndex: Int = -1,
        val memberOf: String? = null,
        val source: Source,
    )

    internal val store = OeaDataStore.get(context)
    internal val ws = OeaWorkspaceStore.get(context)
    internal val focusStore = OeaFocusStore.get(context)
    internal val icons = OeaIconController.shared(context)
    internal val widgets = OeaWidgetController(context)
    internal val shortcuts = OeaShortcutController(context)
    internal val searchController = OeaSearchController(context)
    internal var palette: OeaPalette = OeaTheme.resolve(context)
        private set
    internal val kit = OeaKit(context, palette)
    internal var hostActivity: Activity? = null
        private set
    internal var apps: List<OeaAppInfo> = emptyList()
        private set
    private var appsByKey: Map<String, OeaAppInfo> = emptyMap()
    private val menus = OeaWorkspaceMenus(this)
    private val main = Handler(Looper.getMainLooper())
    private val density = resources.displayMetrics.density
    private val launcherApps: LauncherApps? = context.getSystemService(LauncherApps::class.java)

    private val scrim = View(context)
    private val homeLayer = FrameLayout(context)
    private val homeColumn = LinearLayout(context)
    internal val glance = OeaGlanceView(context)
    private val dynamicIsland = OeaDynamicIslandView(context)
    private val appLoadStatus = TextView(context)
    private val topSearchHost = FrameLayout(context)
    internal val pager = OeaPagedView(context)
    private val indicator = OeaPageIndicator(context)
    private val bannerHost = FrameLayout(context)
    private val dockFrame = DockFrame(context)
    private val dockGrid = OeaCellLayout(context)
    private val bottomSearchHost = FrameLayout(context)
    internal val drawer: OeaDrawerView
    internal val overlay = FrameLayout(context)
    private val dropBar = LinearLayout(context)

    private var insetTop = 0
    private var insetBottom = 0
    private var insetLeft = 0
    private var insetRight = 0
    private var imeBottom = 0

    internal var drawerOpen = false
        private set
    private var drawerAnimator: ValueAnimator? = null
    private val homeTiles = mutableListOf<OeaTileView>()
    private val dockTiles = mutableListOf<OeaTileView>()
    private var rebuildPosted = false
    private var resumed = false
    private var closeDrawerOnPause = false
    private val shortcutIconCache = HashMap<String, android.graphics.drawable.Drawable?>()

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var gestureDecided = false
    private var drawerDragging = false
    private var velocityTracker: VelocityTracker? = null

    private inner class DragState(
        val source: Source,
        val key: String,
        val tile: OeaTileView?,
        val view: ImageView,
        val ref: TileRef,
    ) {
        var page = -1
        var cell = -1
        var dockSlot = -1
        var dropTarget: String? = null
    }

    private var drag: DragState? = null
    private var flipDirection = 0
    private val flipRunnable = Runnable { flipPageDuringDrag() }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> onPreferenceChanged(key) }
    private val notificationListener: () -> Unit = { refreshBadges() }
    private var wallpaperListener: Any? = null

    init {
        clipChildren = false
        isMotionEventSplittingEnabled = false
        drawer = OeaDrawerView(context, this)
        buildViews()
        widgets.onWidgetsChanged = { scheduleRebuild() }
        setOnApplyWindowInsetsListener { _, insets -> applyInsets(insets); insets }
    }

    // =================================================================== views

    private fun buildViews() {
        addView(scrim, LayoutParams(-1, -1))
        addView(homeLayer, LayoutParams(-1, -1))
        homeColumn.orientation = LinearLayout.VERTICAL
        homeColumn.clipChildren = false
        homeColumn.clipToPadding = false
        homeLayer.addView(homeColumn, LayoutParams(-1, -1))

        appLoadStatus.apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(12), dp(18), dp(12))
            visibility = GONE
            isClickable = false
            isFocusable = false
        }
        homeLayer.addView(appLoadStatus, LayoutParams(-2, -2, Gravity.CENTER))

        homeColumn.addView(dynamicIsland, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
        glance.onOpenOverview = { openSystemTool("multitask") }
        homeColumn.addView(glance, LinearLayout.LayoutParams(-1, -2).apply {
            leftMargin = dp(26)
            rightMargin = dp(20)
            topMargin = dp(18)
        })
        homeColumn.addView(topSearchHost, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        pager.onScroll = { position -> indicator.position = position }
        pager.onPageChanged = { page -> ws.setCurrentPage(page) }
        homeColumn.addView(pager, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(6) })
        homeColumn.addView(indicator, LinearLayout.LayoutParams(-1, dp(20)))
        homeColumn.addView(bannerHost, LinearLayout.LayoutParams(-1, -2))
        dockGrid.cols = OeaWorkspaceStore.DOCK_SLOTS
        dockGrid.rows = 1
        dockFrame.addView(dockGrid, LayoutParams(-1, -1))
        homeColumn.addView(dockFrame, LinearLayout.LayoutParams(-1, dockHeight()).apply {
            leftMargin = dp(12)
            rightMargin = dp(12)
            topMargin = dp(4)
        })
        homeColumn.addView(bottomSearchHost, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        val homeGestures = GestureDetector(context, emptyGestureListener())
        homeColumn.setOnTouchListener { _, event -> homeGestures.onTouchEvent(event); true }

        drawer.visibility = GONE
        addView(drawer, LayoutParams(-1, -1))
        overlay.clipChildren = false
        addView(overlay, LayoutParams(-1, -1))
        dropBar.orientation = LinearLayout.HORIZONTAL
        dropBar.gravity = Gravity.CENTER
        dropBar.visibility = GONE
        overlay.addView(dropBar, LayoutParams(-1, dp(64), Gravity.TOP))
        applyChrome()
    }

    private fun dockHeight() = dp(store.iconSizeDp() + 26)

    private fun emptyGestureListener() = object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onDoubleTap(e: MotionEvent): Boolean {
            onHomeDoubleTap()
            return true
        }
        override fun onLongPress(e: MotionEvent) {
            if (drag != null || drawerOpen) return
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            menus.showHomeMenu(e.rawX, e.rawY)
        }
    }

    private fun searchPill(): View {
        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), 0, dp(8), 0)
            background = kit.ripple(kit.rounded(palette.glassStrong, 28, palette.glassStroke, 1), 28)
            isClickable = true
            contentDescription = "Search"
            setOnClickListener { openDrawer(searchFocus = true) }
        }
        pill.addView(ImageView(context).apply { setImageDrawable(kit.icon(R.drawable.oic_search, palette.homeText)) },
            LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(12) })
        pill.addView(TextView(context).apply {
            text = "Search apps, contacts & web"
            textSize = 15f
            setTextColor(palette.homeTextSecondary)
            maxLines = 1
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val voice = Intent(RecognizerIntent.ACTION_WEB_SEARCH)
        if (context.packageManager.resolveActivity(voice, 0) != null) {
            pill.addView(ImageView(context).apply {
                setImageDrawable(kit.icon(R.drawable.oic_mic, palette.homeText))
                setPadding(dp(9), dp(9), dp(9), dp(9))
                background = kit.ripple(null, 20)
                contentDescription = "Voice search"
                setOnClickListener { startSafely(Intent(voice).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)) }
            }, LinearLayout.LayoutParams(dp(42), dp(42)))
        }
        return pill
    }

    /** Re-applies colours to every piece of chrome (not the pages themselves). */
    private fun applyChrome() {
        kit.palette = palette
        appLoadStatus.setTextColor(palette.text)
        appLoadStatus.background = kit.rounded(palette.cardHigh, 22, palette.stroke, 1)
        dynamicIsland.setPalette(palette)
        scrim.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(palette.scrimTop, Color.TRANSPARENT, Color.TRANSPARENT, palette.scrimBottom),
        )
        indicator.color = palette.homeText
        dockFrame.background = when (store.dockStyle()) {
            "none" -> null
            "solid" -> kit.rounded(palette.glassStrong, 30)
            else -> kit.rounded(palette.glass, 30, palette.glassStroke, 1)
        }
        (dockFrame.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.height = dockHeight()
            dockFrame.layoutParams = it
        }
        dockFrame.setPadding(dp(6), dp(6), dp(6), dp(6))
        topSearchHost.removeAllViews()
        bottomSearchHost.removeAllViews()
        when (store.searchBarPosition()) {
            "top" -> topSearchHost.addView(searchPill(), LayoutParams(-1, dp(52)).apply { leftMargin = dp(18); rightMargin = dp(18) })
            "hidden" -> Unit
            else -> bottomSearchHost.addView(searchPill(), LayoutParams(-1, dp(52)).apply { leftMargin = dp(18); rightMargin = dp(18) })
        }
        topSearchHost.visibility = if (topSearchHost.childCount > 0) VISIBLE else GONE
        bottomSearchHost.visibility = if (bottomSearchHost.childCount > 0) VISIBLE else GONE
        dockGrid.highlightColor = OeaTheme.alpha(palette.homeText, 0x30)
        glance.setPalette(palette)
        drawer.applyPalette()
        renderBanner()
        updateSystemBars()
    }

    private fun applyInsets(insets: WindowInsets) {
        val bars = OeaInsets.bars(insets)
        insetLeft = bars[0]
        insetTop = bars[1]
        insetRight = bars[2]
        insetBottom = bars[3]
        imeBottom = OeaInsets.ime(insets)
        homeColumn.setPadding(insetLeft, insetTop, insetRight, insetBottom + dp(8))
        drawer.applyInsets(insetTop, max(insetBottom, imeBottom))
        (dropBar.layoutParams as? LayoutParams)?.let {
            it.topMargin = insetTop + dp(6)
            dropBar.layoutParams = it
        }
        menus.onInsetsChanged(insetTop, max(insetBottom, imeBottom))
    }

    internal fun insetTop() = insetTop
    internal fun insetBottom() = max(insetBottom, imeBottom)

    // =============================================================== lifecycle

    /** Small, non-blocking status affordance while the engine hydrates app data. */
    fun setAppLoadStatus(message: String?, retry: (() -> Unit)? = null) {
        if (message.isNullOrBlank()) {
            appLoadStatus.animate().cancel()
            appLoadStatus.visibility = GONE
            appLoadStatus.setOnClickListener(null)
            return
        }
        appLoadStatus.text = message
        appLoadStatus.contentDescription = message
        appLoadStatus.isClickable = retry != null
        appLoadStatus.isFocusable = retry != null
        appLoadStatus.setOnClickListener { retry?.invoke() }
        appLoadStatus.visibility = VISIBLE
    }

    fun attachHost(activity: Activity?) {
        hostActivity = activity
        widgets.setHostActivity(activity)
        updateSystemBars()
        if (activity != null && !store.onboardingDone()) {
            main.postDelayed({ if (hostActivity != null && !store.onboardingDone()) menus.showOnboarding() }, 700L)
        }
    }

    /**
     * Earlier OEA builds drew their own wallpaper image. Home now shows the real
     * system wallpaper, so a previously chosen image is applied to the system
     * Home screen once (best effort; the image may no longer be readable).
     */
    private fun migrateLegacyWallpaper() {
        val uri = store.wallpaperUri() ?: return
        store.setWallpaperUri(null)
        Thread {
            runCatching {
                context.contentResolver.openInputStream(Uri.parse(uri))?.use { stream ->
                    WallpaperManager.getInstance(context).setStream(stream, null, true, WallpaperManager.FLAG_SYSTEM)
                }
            }
        }.start()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        migrateLegacyWallpaper()
        widgets.start()
        store.registerListener(prefsListener)
        OeaNotificationState.addListener(notificationListener)
        registerWallpaperListener()
        runCatching { WallpaperManager.getInstance(context).setWallpaperOffsets(windowToken, 0.5f, 0.5f) }
        requestApplyInsets()
    }

    override fun onDetachedFromWindow() {
        widgets.stop()
        store.unregisterListener(prefsListener)
        OeaNotificationState.removeListener(notificationListener)
        unregisterWallpaperListener()
        super.onDetachedFromWindow()
    }

    fun onHostResumed() {
        resumed = true
        if (closeDrawerOnPause && drawerOpen) closeDrawer(animate = false)
        closeDrawerOnPause = false
        val fresh = OeaTheme.resolve(context)
        if (fresh != palette) {
            palette = fresh
            applyChrome()
            scheduleRebuild()
        } else {
            refreshBadges()
        }
        glance.refresh()
        dynamicIsland.refresh()
        renderBanner()
        rebindNotificationListener()
        updateSystemBars()
    }

    fun onHostPaused() {
        resumed = false
        menus.dismissPopups()
        if (drag != null) finishDrag(0f, 0f, cancelled = true)
        if (drawerOpen && drawer.hasQuery()) closeDrawerOnPause = true
    }

    /** Home pressed. Close what is open; from a clean Home, glide back to page one. */
    fun onHomeIntent(alreadyOnHome: Boolean) {
        val hadOverlay = menus.closeAll()
        val hadDrawer = drawerOpen
        if (drawerOpen) closeDrawer(animate = alreadyOnHome)
        if (drag != null) finishDrag(0f, 0f, cancelled = true)
        if (alreadyOnHome && !hadOverlay && !hadDrawer && pager.currentPage != 0) pager.snapToPage(0)
    }

    fun onHostConfigurationChanged(config: Configuration) {
        val fresh = OeaTheme.resolve(context)
        if (fresh != palette) {
            palette = fresh
            applyChrome()
        }
        scheduleRebuild()
    }

    private fun onPreferenceChanged(key: String?) {
        if (key == null) return
        if (key.startsWith("launch_count:") || key == "recent_launches" || key == "last_command" || key == "last_result") return
        if (key == "onboarding_done" || key == "default_home_banner_dismissed") return
        main.removeCallbacks(themeRefresh)
        main.postDelayed(themeRefresh, 120L)
    }

    private val themeRefresh = Runnable {
        palette = OeaTheme.resolve(context)
        applyChrome()
        glance.applyStyle()
        glance.refresh()
        rebuild()
    }

    private fun registerWallpaperListener() {
        if (Build.VERSION.SDK_INT < 27 || wallpaperListener != null) return
        val listener = WallpaperManager.OnColorsChangedListener { _, which ->
            if ((which and WallpaperManager.FLAG_SYSTEM) != 0) {
                main.removeCallbacks(themeRefresh)
                main.postDelayed(themeRefresh, 150L)
            }
        }
        runCatching { WallpaperManager.getInstance(context).addOnColorsChangedListener(listener, main) }
        wallpaperListener = listener
    }

    private fun unregisterWallpaperListener() {
        if (Build.VERSION.SDK_INT < 27) return
        val listener = wallpaperListener as? WallpaperManager.OnColorsChangedListener ?: return
        runCatching { WallpaperManager.getInstance(context).removeOnColorsChangedListener(listener) }
        wallpaperListener = null
    }

    private fun rebindNotificationListener() {
        if (OeaNotificationState.connected) return
        val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            ?.contains(context.packageName) == true
        if (enabled) runCatching { NotificationListenerService.requestRebind(ComponentName(context, OeaNotificationListener::class.java)) }
    }

    internal fun updateSystemBars() {
        val window = hostActivity?.window ?: return
        val light = if (drawerOpen) !palette.dark else palette.wallpaperLight
        OeaInsets.edgeToEdge(window, lightBars = light)
    }

    // ==================================================================== data

    fun bind(value: List<OeaAppInfo>) {
        apps = value
        appsByKey = value.associateBy { appKey(it) }
        if (!ws.isSeeded() && value.isNotEmpty()) seedLayout()
        rebuild()
    }

    internal fun appKey(app: OeaAppInfo) = OeaWorkspaceStore.key(app.packageName, app.className)
    internal fun findApp(key: String): OeaAppInfo? = appsByKey[key]
    internal fun cols() = store.gridColumns()
    internal fun rows() = store.gridRows()
    internal fun isFrozen(pkg: String) = OeaAppFreezer.isFrozen(context, pkg)
    internal fun isHibernated(pkg: String) = pkg in OeaAppFreezer.hibernatedPackages(context)

    /** Drawer list: visible apps in the chosen order. */
    internal fun drawerApps(): List<OeaAppInfo> {
        val visible = apps.filterNot { store.isHidden(it.packageName, it.className) }
        return if (store.sortMode() == OeaDataStore.SortMode.RECENT) {
            val recent = store.recentLaunches()
            visible.sortedWith(compareBy<OeaAppInfo>({ recent.indexOf(appKey(it)).let { i -> if (i < 0) Int.MAX_VALUE else i } },
                { -store.launchCount(appKey(it)) }))
        } else visible
    }

    /** Focus apps first, then frequently and recently used apps. */
    internal fun suggestions(limit: Int): List<OeaAppInfo> {
        val visibleKeys = drawerApps().map(::appKey).toSet()
        val picked = LinkedHashSet<String>()
        focusStore.apps().filter { it in visibleKeys }.forEach { picked += it }
        store.mostUsed(visibleKeys, limit * 2).filter { store.launchCount(it) >= 2 }.forEach { picked += it }
        store.recentLaunches().filter { it in visibleKeys }.forEach { picked += it }
        return picked.mapNotNull(appsByKey::get).take(limit)
    }

    internal fun drawerColumns() = max(4, min(6, cols()))
    internal fun drawerRowHeight() = dp(min(store.iconSizeDp(), 56) + 46)

    internal fun badgeFor(pkg: String): Int =
        if (store.badgeStyle() == "off") 0 else OeaNotificationState.countForPackage(pkg)

    // ================================================================ seeding

    /** First run: OEA dock essentials, an OEA folder and a tidy first page. */
    private fun seedLayout() {
        val cols = cols()
        val rows = rows()
        val visible = apps.filterNot { store.isHidden(it.packageName, it.className) }
        val byPackage = visible.groupBy { it.packageName }
        fun appFor(intent: Intent): OeaAppInfo? {
            val info = runCatching { context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) }.getOrNull()
            val pkg = info?.activityInfo?.packageName
            if (pkg != null && pkg != "android" && info.activityInfo?.name?.contains("Resolver") != true) {
                val exact = byPackage[pkg]?.firstOrNull { it.className == info.activityInfo.name }
                if (exact != null) return exact
                // Never guess between OEA's own apps (Phone, Call Blocker, Game Boost…).
                if (pkg != context.packageName) byPackage[pkg]?.firstOrNull()?.let { return it }
            }
            val candidates = runCatching { context.packageManager.queryIntentActivities(intent, 0) }.getOrNull().orEmpty()
            return candidates.firstNotNullOfOrNull { byPackage[it.activityInfo.packageName]?.firstOrNull() }
        }
        val dock = LinkedHashSet<OeaAppInfo>()
        listOfNotNull(
            appFor(Intent(Intent.ACTION_DIAL)),
            Telephony.Sms.getDefaultSmsPackage(context)?.let { byPackage[it]?.firstOrNull() },
            appFor(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))),
            appFor(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)),
            appFor(Intent(Intent.ACTION_VIEW).setType("image/*")),
        ).forEach { dock += it }
        val oeaApps = visible.filter { it.packageName == context.packageName && it !in dock }
        val rest = visible.filter { it !in dock && it !in oeaApps }
        rest.filter { it.packageName != context.packageName }.take(OeaWorkspaceStore.DOCK_SLOTS - dock.size).forEach { if (dock.size < OeaWorkspaceStore.DOCK_SLOTS) dock += it }
        val remaining = rest.filter { it !in dock }
        val essentials = listOfNotNull(
            byPackage["com.android.vending"]?.firstOrNull(),
            appFor(Intent(Settings.ACTION_SETTINGS)),
            appFor(Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS)),
        ).filter { it in remaining }.distinct()
        val firstRow = (essentials + remaining.filter { it !in essentials }).take(if (oeaApps.size >= 2) cols - 1 else cols)
        val items = mutableListOf<OeaWorkspaceStore.Item>()
        val baseCell = (rows - 1) * cols
        firstRow.forEachIndexed { i, app -> items += OeaWorkspaceStore.Item(appKey(app), app.packageName, app.className, 0, baseCell + i) }
        val folders = mutableListOf<OeaWorkspaceStore.Folder>()
        if (oeaApps.size >= 2) {
            folders += OeaWorkspaceStore.Folder("folder_oea", "OEA", 0, baseCell + cols - 1, oeaApps.map(::appKey))
        } else {
            oeaApps.firstOrNull()?.let { if (firstRow.size < cols) items += OeaWorkspaceStore.Item(appKey(it), it.packageName, it.className, 0, baseCell + firstRow.size) }
        }
        val secondPage = remaining.filter { it !in firstRow }.take(cols * rows)
        secondPage.forEachIndexed { i, app -> items += OeaWorkspaceStore.Item(appKey(app), app.packageName, app.className, 1, i) }
        ws.replaceItems(items)
        ws.replaceFolders(folders)
        ws.setDock(dock.take(OeaWorkspaceStore.DOCK_SLOTS).map(::appKey))
        ws.setPages(if (secondPage.isEmpty()) 1 else 2)
        ws.setCurrentPage(0)
        ws.setLayoutGrid(cols, rows)
        ws.markSeeded()
    }

    // ============================================================== normalize

    private fun widgetRowsOnPage(page: Int): Int =
        widgets.ids().filter { widgets.pageFor(it) == page && widgets.info(it) != null }.sumOf { widgets.spanFor(it) }.coerceAtMost(rows())

    /**
     * Keeps the stored layout valid for the current grid: remaps cells after a
     * grid change, resolves overlaps, keeps icons out of widget rows and drops
     * apps that were uninstalled.
     */
    private fun normalizeLayout() {
        val cols = cols()
        val rows = rows()
        if (apps.isNotEmpty()) {
            val installed = apps.map { it.packageName }.toSet()
            ws.clearMissing(appsByKey.keys, installed)
        }
        var items = ws.items()
        var folders = ws.folders()
        // Legacy data: folder members were also stored as items carrying folderId.
        val folderIds = folders.map { it.id }.toSet()
        val legacyMembers = items.filter { it.folderId != null && it.folderId in folderIds }
        if (legacyMembers.isNotEmpty()) {
            folders = folders.map { f ->
                val extra = legacyMembers.filter { it.folderId == f.id }.map { it.id }
                f.copy(members = (f.members + extra).distinct())
            }
        }
        items = items.filter { it.folderId == null || it.folderId !in folderIds }
            .map { if (it.folderId != null) it.copy(folderId = null) else it }

        val oldCols = ws.layoutColumns().takeIf { it > 0 } ?: cols
        val oldRows = ws.layoutRows().takeIf { it > 0 } ?: 4
        fun remap(cell: Int): Int {
            if (oldCols == cols && oldRows == rows) return cell
            val r = cell / oldCols
            val c = cell % oldCols
            return if (c < cols && r < rows) r * cols + c else -1
        }
        if (oldCols != cols || oldRows != rows) {
            items = items.map { it.copy(cell = remap(it.cell)) }
            folders = folders.map { it.copy(cell = remap(it.cell)) }
        }

        var pages = ws.pages()
        val capacity = cols * rows
        val occupied = HashMap<Int, MutableSet<Int>>()
        fun reserved(page: Int) = widgetRowsOnPage(page) * cols
        fun isFree(page: Int, cell: Int) = cell in reserved(page) until capacity && occupied[page]?.contains(cell) != true
        fun take(page: Int, cell: Int) { occupied.getOrPut(page) { mutableSetOf() } += cell }
        fun findFree(startPage: Int): Pair<Int, Int> {
            var page = startPage.coerceAtLeast(0)
            while (page < OeaWorkspaceStore.MAX_PAGES) {
                // Fill from the bottom rows up: icons sit close to the thumb.
                for (cell in capacity - 1 downTo reserved(page)) if (isFree(page, cell)) return page to cell
                page++
            }
            return -1 to -1
        }
        val placedItems = mutableListOf<OeaWorkspaceStore.Item>()
        val placedFolders = mutableListOf<OeaWorkspaceStore.Folder>()
        val pendingItems = mutableListOf<OeaWorkspaceStore.Item>()
        val pendingFolders = mutableListOf<OeaWorkspaceStore.Folder>()
        folders.sortedWith(compareBy({ it.page }, { it.cell })).forEach { f ->
            if (f.page in 0 until OeaWorkspaceStore.MAX_PAGES && isFree(f.page, f.cell)) { take(f.page, f.cell); placedFolders += f } else pendingFolders += f
        }
        items.sortedWith(compareBy({ it.page }, { it.cell })).forEach { i ->
            if (i.page in 0 until OeaWorkspaceStore.MAX_PAGES && isFree(i.page, i.cell)) { take(i.page, i.cell); placedItems += i } else pendingItems += i
        }
        pendingFolders.forEach { f ->
            val (page, cell) = findFree(f.page)
            if (page >= 0) { take(page, cell); placedFolders += f.copy(page = page, cell = cell) }
        }
        pendingItems.forEach { i ->
            val (page, cell) = findFree(i.page)
            if (page >= 0) { take(page, cell); placedItems += i.copy(page = page, cell = cell) }
        }
        val maxUsed = (placedItems.map { it.page } + placedFolders.map { it.page } +
            widgets.ids().map { widgets.pageFor(it) }).maxOrNull() ?: 0
        pages = max(pages, maxUsed + 1).coerceIn(1, OeaWorkspaceStore.MAX_PAGES)
        if (placedItems != ws.items()) ws.replaceItems(placedItems)
        if (placedFolders != ws.folders()) ws.replaceFolders(placedFolders)
        ws.setPages(pages)
        ws.setLayoutGrid(cols, rows)
    }

    // ================================================================= render

    internal fun scheduleRebuild() {
        if (rebuildPosted) return
        rebuildPosted = true
        main.post { rebuildPosted = false; rebuild() }
    }

    internal fun rebuild() {
        if (drag != null) {
            // Never rebuild under a live drag; it is replayed when the drop ends.
            scheduleRebuildAfterDrag = true
            return
        }
        normalizeLayout()
        homeTiles.clear()
        val target = ws.getCurrentPage()
        pager.removeAllViews()
        for (page in 0 until ws.pages()) pager.addView(buildPage(page))
        pager.setPageImmediately(target)
        indicator.pages = ws.pages()
        indicator.position = target.toFloat()
        renderDock()
        if (drawer.visibility == VISIBLE) drawer.refresh()
        menus.refreshOpenFolder()
    }

    private var scheduleRebuildAfterDrag = false

    private fun buildPage(page: Int): OeaCellLayout {
        val layout = OeaCellLayout(context)
        layout.cols = cols()
        layout.rows = rows()
        layout.page = page
        layout.setPadding(dp(6), 0, dp(6), 0)
        layout.highlightColor = OeaTheme.alpha(palette.homeText, 0x2E)
        layout.emptyGestures = GestureDetector(context, emptyGestureListener())
        var row = 0
        widgets.ids().filter { widgets.pageFor(it) == page }.forEach { id ->
            if (row >= rows()) return@forEach
            val span = widgets.spanFor(id).coerceIn(1, rows() - row)
            val host = widgets.createView(context, id) ?: return@forEach
            val frame = WidgetFrame(context, id, host)
            layout.addView(frame, OeaCellLayout.LayoutParams(0, row, cols(), span))
            row += span
        }
        ws.folders().filter { it.page == page }.forEach { folder ->
            val tile = OeaTileView(context)
            bindFolderTile(tile, folder, TileStyle.HOME)
            tile.listener = homeListener
            layout.addView(tile, OeaCellLayout.LayoutParams(folder.cell % cols(), folder.cell / cols()))
            homeTiles += tile
        }
        ws.items().filter { it.page == page }.forEach { item ->
            val tile = OeaTileView(context)
            if (item.isShortcut) {
                bindShortcutTile(tile, item)
            } else {
                val app = appsByKey[item.id] ?: return@forEach
                bindTile(tile, app, TileStyle.HOME)
                tile.payload = TileRef(app = app, item = item, source = Source.HOME_ITEM)
            }
            tile.listener = homeListener
            layout.addView(tile, OeaCellLayout.LayoutParams(item.cell % cols(), item.cell / cols()))
            homeTiles += tile
        }
        return layout
    }

    private fun renderDock() {
        dockGrid.removeAllViews()
        dockTiles.clear()
        val page = ws.currentDockPage()
        ws.dockPageValues(page).forEachIndexed { i, key ->
            val app = appsByKey[key] ?: return@forEachIndexed
            val tile = OeaTileView(context)
            bindTile(tile, app, TileStyle.DOCK)
            tile.payload = TileRef(app = app, dockIndex = page * OeaWorkspaceStore.DOCK_SLOTS + i, source = Source.DOCK)
            tile.listener = homeListener
            dockGrid.addView(tile, OeaCellLayout.LayoutParams(i, 0))
            dockTiles += tile
        }
        dockGrid.emptyGestures = GestureDetector(context, emptyGestureListener())
    }

    private fun styleTile(tile: OeaTileView, style: TileStyle) {
        val iconDp = store.iconSizeDp()
        tile.badgeStyle = store.badgeStyle()
        tile.accent = palette.accent
        tile.onAccent = palette.onAccent
        tile.stateBadgeBg = palette.cardHigh
        tile.stateBadgeTint = palette.text
        tile.folderCornerFraction = when (store.iconShape()) {
            "circle" -> 0.5f
            "square" -> 0.12f
            else -> 0.3f
        }
        when (style) {
            TileStyle.HOME -> {
                tile.showLabel = store.showAppLabels()
                tile.labelColor = palette.homeText
                tile.labelShadow = palette.homeShadow
                tile.iconSizePx = dp(iconDp)
                tile.labelSizeSp = 12f
                tile.folderColor = palette.glassStrong
            }
            TileStyle.DOCK -> {
                tile.showLabel = false
                tile.iconSizePx = dp(iconDp)
                tile.folderColor = palette.glassStrong
            }
            TileStyle.DRAWER -> {
                tile.showLabel = true
                tile.labelColor = palette.text
                tile.labelShadow = 0
                tile.iconSizePx = dp(min(iconDp, 56))
                tile.labelSizeSp = 12.5f
                tile.folderColor = palette.cardHigh
            }
            TileStyle.FOLDER -> {
                tile.showLabel = true
                tile.labelColor = palette.text
                tile.labelShadow = 0
                tile.iconSizePx = dp(min(iconDp, 54))
                tile.labelSizeSp = 12.5f
                tile.folderColor = palette.cardHigh
            }
        }
    }

    internal fun bindTile(tile: OeaTileView, app: OeaAppInfo, style: TileStyle) {
        styleTile(tile, style)
        tile.kind = OeaTileView.Kind.APP
        tile.key = appKey(app)
        tile.payload = TileRef(app = app, source = if (style == TileStyle.DRAWER) Source.DRAWER else Source.HOME_ITEM)
        tile.label = app.label
        tile.icon = icons.icon(app.packageName, app.className)
        tile.badgeCount = badgeFor(app.packageName)
        tile.frozen = isFrozen(app.packageName)
        tile.locked = OeaAppLockStore.isLocked(context, appKey(app))
        tile.dragging = false
    }

    internal fun bindFolderTile(tile: OeaTileView, folder: OeaWorkspaceStore.Folder, style: TileStyle) {
        styleTile(tile, style)
        val members = folder.members.mapNotNull(appsByKey::get)
        tile.kind = if (folder.isPair) OeaTileView.Kind.PAIR else OeaTileView.Kind.FOLDER
        tile.key = folder.id
        tile.payload = TileRef(folder = folder, source = Source.HOME_FOLDER)
        tile.label = if (folder.isPair) folder.title.ifBlank { members.joinToString(" | ") { it.label } } else folder.title
        tile.previews = members.take(4).map { icons.icon(it.packageName, it.className) }
        tile.badgeCount = if (members.any { badgeFor(it.packageName) > 0 }) members.sumOf { badgeFor(it.packageName) } else 0
        tile.frozen = false
        tile.locked = false
    }

    private fun bindShortcutTile(tile: OeaTileView, item: OeaWorkspaceStore.Item) {
        styleTile(tile, TileStyle.HOME)
        val app = apps.firstOrNull { it.packageName == item.packageName && (item.className.isBlank() || it.className == item.className) }
            ?: apps.firstOrNull { it.packageName == item.packageName }
        tile.kind = OeaTileView.Kind.SHORTCUT
        tile.key = item.id
        tile.payload = TileRef(app = app, item = item, source = Source.HOME_ITEM)
        tile.label = item.label ?: app?.label.orEmpty()
        val shortcutIcon = shortcutIconCache.getOrPut(item.id) {
            item.shortcutId?.let { id -> shortcuts.find(item.packageName, id)?.let { shortcuts.icon(it) } }
        }
        val appIcon = app?.let { icons.icon(it.packageName, it.className) }
        tile.icon = shortcutIcon ?: appIcon
        tile.smallBadge = if (shortcutIcon != null) appIcon else null
        tile.badgeCount = 0
        tile.frozen = isFrozen(item.packageName)
        tile.locked = false
    }

    /** Updates badges and frozen/locked states in place (no relayout). */
    fun refreshBadges() {
        val style = store.badgeStyle()
        (homeTiles + dockTiles).forEach { tile ->
            tile.badgeStyle = style
            val ref = tile.payload as? TileRef ?: return@forEach
            when {
                ref.folder != null -> {
                    val members = ref.folder.members.mapNotNull(appsByKey::get)
                    tile.badgeCount = members.sumOf { badgeFor(it.packageName) }
                }
                ref.item?.isShortcut == true -> tile.frozen = isFrozen(ref.item.packageName)
                ref.app != null -> {
                    tile.badgeCount = badgeFor(ref.app.packageName)
                    tile.frozen = isFrozen(ref.app.packageName)
                    tile.locked = OeaAppLockStore.isLocked(context, appKey(ref.app))
                }
            }
        }
        if (drawer.visibility == VISIBLE) drawer.notifyChanged()
        menus.refreshOpenFolder()
    }

    private fun renderBanner() {
        bannerHost.removeAllViews()
        if (store.defaultHomeBannerDismissed() || isDefaultHome()) {
            bannerHost.visibility = GONE
            return
        }
        bannerHost.visibility = VISIBLE
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(8), dp(10))
            background = kit.rounded(palette.glassStrong, 22, palette.glassStroke, 1)
        }
        card.addView(ImageView(context).apply { setImageDrawable(kit.icon(R.drawable.oic_home_fill, palette.accent)) },
            LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(12) })
        card.addView(TextView(context).apply {
            text = "Make OEA your default Home"
            textSize = 14f
            typeface = OeaType.medium
            setTextColor(palette.homeText)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(kit.button("Set", palette.accent, palette.onAccent) { requestDefaultHome() }.apply {
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }, LinearLayout.LayoutParams(-2, -2))
        card.addView(ImageView(context).apply {
            setImageDrawable(kit.icon(R.drawable.oic_close, palette.homeTextSecondary))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            contentDescription = "Dismiss"
            setOnClickListener { store.setDefaultHomeBannerDismissed(true); renderBanner() }
        }, LinearLayout.LayoutParams(dp(38), dp(38)).apply { leftMargin = dp(4) })
        bannerHost.addView(card, LayoutParams(-1, -2).apply { leftMargin = dp(14); rightMargin = dp(14); topMargin = dp(2); bottomMargin = dp(6) })
    }

    internal fun isDefaultHome(): Boolean {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = context.getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) return rm.isRoleHeld(RoleManager.ROLE_HOME)
        }
        val info = context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY,
        )
        return info?.activityInfo?.packageName == context.packageName
    }

    internal fun requestDefaultHome() {
        val activity = hostActivity
        if (Build.VERSION.SDK_INT >= 29 && activity != null) {
            val rm = context.getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                if (runCatching { activity.startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), REQUEST_HOME_ROLE) }.isSuccess) return
            }
        }
        startSafely(Intent(Settings.ACTION_HOME_SETTINGS))
    }

    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode == REQUEST_HOME_ROLE) {
            if (!isDefaultHome() && resultCode != Activity.RESULT_OK) startSafely(Intent(Settings.ACTION_HOME_SETTINGS))
            renderBanner()
            return true
        }
        return widgets.handleActivityResult(requestCode, resultCode, data)
    }

    fun handleBack(): Boolean {
        if (drag != null) {
            finishDrag(0f, 0f, cancelled = true)
            return true
        }
        if (menus.closeAll()) return true
        if (drawerOpen) {
            if (drawer.hasQuery()) drawer.search.text?.clear() else closeDrawer()
            return true
        }
        return true
    }

    // ================================================================= launch

    internal fun launchApp(app: OeaAppInfo, source: View?) {
        if (isHibernated(app.packageName)) {
            menus.showFrozenPrompt(app)
            return
        }
        if (OeaAppLockStore.isLocked(context, appKey(app))) {
            menus.showUnlockPrompt(app) { startApp(app, source) }
            return
        }
        startApp(app, source)
    }

    private fun startApp(app: OeaAppInfo, source: View?) {
        val component = ComponentName(app.packageName, app.className)
        val bounds = source?.let { viewBounds(it) }
        val options = launchOptions(source)
        val launched = runCatching {
            launcherApps?.startMainActivity(component, Process.myUserHandle(), bounds, options) ?: error("no LauncherApps")
            true
        }.recoverCatching {
            context.startActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
                options,
            )
            true
        }.getOrDefault(false)
        if (launched) {
            store.recordLaunch(app.packageName, app.className)
            if (drawerOpen) closeDrawerOnPause = true
        } else {
            toast("Unable to open ${app.label}")
        }
    }

    internal fun launchOptions(source: View?): Bundle? {
        if (source == null || source.width == 0) return null
        return runCatching {
            ActivityOptions.makeClipRevealAnimation(source, 0, 0, source.width, source.height).toBundle()
        }.getOrNull()
    }

    private fun viewBounds(view: View): Rect {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + view.width, loc[1] + view.height)
    }

    internal fun launchShortcut(item: OeaWorkspaceStore.Item, source: View?) {
        val id = item.shortcutId ?: return
        if (isHibernated(item.packageName)) {
            findApp(apps.firstOrNull { it.packageName == item.packageName }?.let(::appKey) ?: "")?.let { menus.showFrozenPrompt(it) }
            return
        }
        if (!shortcuts.start(item.packageName, id, source?.let(::viewBounds), launchOptions(source))) {
            toast(if (shortcuts.hasPermission()) "This shortcut is no longer available" else "Set OEA as default Home to use shortcuts")
        }
    }

    internal fun launchPair(folder: OeaWorkspaceStore.Folder) {
        val members = folder.members.mapNotNull(appsByKey::get)
        if (members.size < 2) return
        val (a, b) = members[0] to members[1]
        fun go() {
            val first = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(ComponentName(a.packageName, a.className))
            val second = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(ComponentName(b.packageName, b.className))
            val result = OeaSplitLauncher.launchPair(context, first, second)
            if (!result.success || result.message.startsWith("Turn on")) toast(result.message)
            if (result.success) {
                store.recordLaunch(a.packageName, a.className)
                store.recordLaunch(b.packageName, b.className)
            }
        }
        val lockedApp = members.take(2).firstOrNull { OeaAppLockStore.isLocked(context, appKey(it)) }
        if (lockedApp != null) menus.showUnlockPrompt(lockedApp) { go() } else go()
    }

    internal fun runSearchAction(action: OeaSearchAction) {
        val copy = action.copyText
        if (copy != null) {
            val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
            clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("OEA", copy))
            toast("Copied $copy")
            return
        }
        val intent = action.intent ?: return
        if (startSafely(intent)) closeDrawerOnPause = true else toast("No app can handle this")
    }

    internal fun startSafely(intent: Intent): Boolean = runCatching {
        val activity = hostActivity
        if (activity != null) activity.startActivity(intent) else context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    internal fun openSystemTool(screen: String?) {
        val intent = Intent(context, OeaSystemToolsActivity::class.java)
        if (screen != null) intent.putExtra(OeaSystemToolsActivity.EXTRA_SCREEN, screen)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!startSafely(intent)) toast("OEA Settings could not be opened")
    }

    internal fun openAppInfo(pkg: String) {
        startSafely(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
    }

    internal fun uninstall(pkg: String) {
        if (pkg == context.packageName) {
            openAppInfo(pkg)
            return
        }
        startSafely(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))
    }

    internal fun isSystemApp(pkg: String): Boolean = runCatching {
        val info = context.packageManager.getApplicationInfo(pkg, 0)
        (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 &&
            (info.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
    }.getOrDefault(false)

    internal fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    private fun onHomeDoubleTap() {
        if (drawerOpen || drag != null) return
        when (store.doubleTapAction()) {
            "lock" -> if (!OeaAssistant.lockScreen()) menus.showAssistantNeeded("Double-tap to lock")
            "drawer" -> openDrawer()
            else -> Unit
        }
    }

    private fun onHomeSwipeDown() {
        when (store.swipeDownAction()) {
            "notifications" -> if (!OeaSystemActions.expandNotifications(context)) menus.showAssistantNeeded("Swipe down for notifications")
            "quick_settings" -> if (!OeaSystemActions.expandQuickSettings(context)) menus.showAssistantNeeded("Swipe down for Quick Settings")
            "search" -> openDrawer(searchFocus = true)
            else -> Unit
        }
    }

    // ================================================================= drawer

    internal fun openDrawer(searchFocus: Boolean = false) {
        menus.closeAll()
        if (drawerOpen && drawerAnimator == null) {
            if (searchFocus) drawer.focusSearch(true)
            return
        }
        prepareDrawer()
        animateDrawer(open = true) {
            if (searchFocus || store.drawerKeyboard()) drawer.focusSearch(true)
        }
    }

    private fun prepareDrawer() {
        if (drawer.visibility != VISIBLE) {
            drawer.refresh()
            drawer.translationY = height.toFloat().coerceAtLeast(1f)
            drawer.visibility = VISIBLE
        }
    }

    internal fun closeDrawer(animate: Boolean = true) {
        drawer.hideKeyboard()
        if (!animate) {
            drawerAnimator?.cancel()
            setDrawerTranslation(height.toFloat())
            drawer.visibility = GONE
            drawer.resetState()
            drawerOpen = false
            updateSystemBars()
            return
        }
        animateDrawer(open = false)
    }

    internal fun onDrawerDragged(fraction: Float) {
        applyDrawerProgress(fraction.coerceIn(0f, 1f))
    }

    internal fun settleDrawer(open: Boolean) {
        animateDrawer(open)
    }

    internal fun showDrawerMenu(anchor: View) = menus.showDrawerMenu(anchor)

    private fun animateDrawer(open: Boolean, end: (() -> Unit)? = null) {
        drawerAnimator?.cancel()
        val h = height.toFloat().coerceAtLeast(1f)
        val from = drawer.translationY
        val to = if (open) 0f else h
        val distance = abs(to - from) / h
        drawerOpen = open
        val animator = ValueAnimator.ofFloat(from, to).apply {
            duration = store.animationDuration((if (open) 340L else 260L) * distance.coerceIn(0.4f, 1f).let { (it * 100).toLong() } / 100L)
            interpolator = if (open) PathInterpolator(0.2f, 0f, 0f, 1f) else DecelerateInterpolator(1.5f)
            addUpdateListener { setDrawerTranslation(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (drawerAnimator === animation) drawerAnimator = null
                    if (cancelled) return
                    if (!open) {
                        drawer.visibility = GONE
                        drawer.resetState()
                    }
                    updateSystemBars()
                    end?.invoke()
                }
            })
        }
        drawerAnimator = animator
        if (drawer.visibility != VISIBLE) prepareDrawer()
        animator.start()
        updateSystemBars()
    }

    private fun setDrawerTranslation(t: Float) {
        drawer.translationY = t
        applyDrawerProgress(1f - t / height.toFloat().coerceAtLeast(1f))
    }

    private fun applyDrawerProgress(progress: Float) {
        val p = progress.coerceIn(0f, 1f)
        homeLayer.alpha = 1f - p
        homeLayer.scaleX = 1f - 0.06f * p
        homeLayer.scaleY = 1f - 0.06f * p
        homeLayer.translationY = -dp(28) * p
        scrim.alpha = 1f - p
    }

    // ============================================================== gestures

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (drag != null) return true
        if (drawerOpen || drawerAnimator != null || menus.hasOverlay()) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                gestureDecided = false
                drawerDragging = false
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().also { it.addMovement(ev) }
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(ev)
                if (gestureDecided) return false
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (abs(dy) > touchSlop * 1.2f && abs(dy) > abs(dx) * 1.3f) {
                    gestureDecided = true
                    val direction = if (dy < 0) 1 else -1
                    if (canScrollUnder(this, ev.x, ev.y, direction)) return false
                    if (dy < 0) {
                        drawerDragging = true
                        prepareDrawer()
                        return true
                    } else {
                        onHomeSwipeDown()
                        return true
                    }
                } else if (abs(dx) > touchSlop * 1.5f) {
                    gestureDecided = true
                }
            }
        }
        return false
    }

    /** True when a scrollable view (e.g. a widget's list) under the finger can scroll [direction]. */
    private fun canScrollUnder(group: ViewGroup, x: Float, y: Float, direction: Int): Boolean {
        for (i in group.childCount - 1 downTo 0) {
            val child = group.getChildAt(i)
            if (child.visibility != VISIBLE) continue
            val loc = IntArray(2)
            child.getLocationInWindow(loc)
            val rootLoc = IntArray(2)
            getLocationInWindow(rootLoc)
            val left = loc[0] - rootLoc[0]
            val top = loc[1] - rootLoc[1]
            if (x < left || x > left + child.width || y < top || y > top + child.height) continue
            if (child !is OeaPagedView && child.canScrollVertically(direction)) return true
            if (child is ViewGroup && canScrollUnder(child, x, y, direction)) return true
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val active = drag
        if (active != null) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> dragMove(event.rawX, event.rawY)
                MotionEvent.ACTION_UP -> finishDrag(event.rawX, event.rawY, cancelled = false)
                MotionEvent.ACTION_CANCEL -> finishDrag(event.rawX, event.rawY, cancelled = true)
            }
            return true
        }
        if (!drawerDragging) return true
        velocityTracker?.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val h = height.toFloat()
                setDrawerTranslation((h + (event.y - downY) + touchSlop).coerceIn(0f, h))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocityTracker?.computeCurrentVelocity(1000)
                val vy = velocityTracker?.yVelocity ?: 0f
                val progress = 1f - drawer.translationY / height.toFloat().coerceAtLeast(1f)
                drawerDragging = false
                val open = vy < -900f || (vy < 600f && progress > 0.3f)
                animateDrawer(open) { if (open && store.drawerKeyboard()) drawer.focusSearch(true) }
            }
        }
        return true
    }

    // ============================================================ tile events

    private val homeListener = object : OeaTileView.Listener {
        override fun onTileClick(tile: OeaTileView) {
            val ref = tile.payload as? TileRef ?: return
            when {
                ref.folder?.isPair == true -> launchPair(ref.folder)
                ref.folder != null -> menus.openFolder(ref.folder, tile)
                ref.item?.isShortcut == true -> launchShortcut(ref.item, tile)
                ref.app != null -> launchApp(ref.app, tile)
            }
        }

        override fun onTileLongPress(tile: OeaTileView) {
            val ref = tile.payload as? TileRef ?: return
            menus.showTilePopup(tile, ref)
        }

        override fun onTileDragStart(tile: OeaTileView, rawX: Float, rawY: Float) {
            val ref = tile.payload as? TileRef ?: return
            val key = when (ref.source) {
                Source.HOME_FOLDER -> ref.folder?.id
                Source.HOME_ITEM -> ref.item?.id
                else -> ref.app?.let(::appKey)
            } ?: return
            beginDrag(ref.source, key, tile, ref, rawX, rawY)
        }

        override fun onTileDragMove(tile: OeaTileView, rawX: Float, rawY: Float) = dragMove(rawX, rawY)
        override fun onTileDragEnd(tile: OeaTileView, rawX: Float, rawY: Float, cancelled: Boolean) {
            // After hand-off the root owns the gesture; the tile's CANCEL is expected.
            if (!cancelled) finishDrag(rawX, rawY, false)
        }
    }

    internal val drawerTileListener = object : OeaTileView.Listener {
        override fun onTileClick(tile: OeaTileView) {
            val app = (tile.payload as? TileRef)?.app ?: return
            launchApp(app, tile)
        }

        override fun onTileLongPress(tile: OeaTileView) {
            val app = (tile.payload as? TileRef)?.app ?: return
            menus.showTilePopup(tile, TileRef(app = app, source = Source.DRAWER))
        }

        override fun onTileDragStart(tile: OeaTileView, rawX: Float, rawY: Float) {
            val app = (tile.payload as? TileRef)?.app ?: return
            beginDrag(Source.DRAWER, appKey(app), tile, TileRef(app = app, source = Source.DRAWER), rawX, rawY)
        }

        override fun onTileDragMove(tile: OeaTileView, rawX: Float, rawY: Float) = dragMove(rawX, rawY)
        override fun onTileDragEnd(tile: OeaTileView, rawX: Float, rawY: Float, cancelled: Boolean) {
            if (!cancelled) finishDrag(rawX, rawY, false)
        }
    }

    /** Folder panel members: tap launches, long-press opens the popup, drag pulls the app out. */
    internal fun folderMemberListener(folder: OeaWorkspaceStore.Folder) = object : OeaTileView.Listener {
        override fun onTileClick(tile: OeaTileView) {
            val app = (tile.payload as? TileRef)?.app ?: return
            menus.closeAll()
            launchApp(app, tile)
        }

        override fun onTileLongPress(tile: OeaTileView) {
            val app = (tile.payload as? TileRef)?.app ?: return
            menus.showTilePopup(tile, TileRef(app = app, memberOf = folder.id, source = Source.FOLDER_MEMBER))
        }

        override fun onTileDragStart(tile: OeaTileView, rawX: Float, rawY: Float) {
            val app = (tile.payload as? TileRef)?.app ?: return
            beginDrag(Source.FOLDER_MEMBER, appKey(app), tile, TileRef(app = app, memberOf = folder.id, source = Source.FOLDER_MEMBER), rawX, rawY)
        }

        override fun onTileDragMove(tile: OeaTileView, rawX: Float, rawY: Float) = dragMove(rawX, rawY)
        override fun onTileDragEnd(tile: OeaTileView, rawX: Float, rawY: Float, cancelled: Boolean) {
            if (!cancelled) finishDrag(rawX, rawY, false)
        }
    }

    // ============================================================ drag & drop

    private fun beginDrag(source: Source, key: String, tile: OeaTileView, ref: TileRef, rawX: Float, rawY: Float) {
        if (drag != null) return
        if (store.homeLayoutLocked()) {
            menus.dismissPopups()
            toast("Home layout is locked. Unlock it in OEA Settings › Home screen.")
            return
        }
        menus.dismissPopups()
        val bitmap = snapshot(tile)
        val view = ImageView(context).apply { setImageBitmap(bitmap) }
        overlay.addView(view, LayoutParams(bitmap.width, bitmap.height))
        val state = DragState(source, key, tile, view, ref)
        drag = state
        tile.dragging = true
        positionDragView(state, rawX, rawY)
        view.animate().scaleX(1.12f).scaleY(1.12f).setDuration(150L).start()
        showDropBar(source, ref)
        when (source) {
            Source.DRAWER -> closeDrawer(animate = true)
            Source.FOLDER_MEMBER -> menus.closeAll()
            else -> Unit
        }
        // Take over the touch stream at the root so the source view may go away.
        requestDisallowInterceptTouchEvent(false)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun snapshot(tile: OeaTileView): Bitmap {
        val w = tile.width.coerceAtLeast(1)
        val h = tile.height.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val label = tile.showLabel
        val scale = tile.scaleX
        tile.showLabel = false
        tile.scaleX = 1f
        tile.scaleY = 1f
        tile.draw(Canvas(bitmap))
        tile.showLabel = label
        tile.scaleX = scale
        tile.scaleY = scale
        return bitmap
    }

    private fun positionDragView(state: DragState, rawX: Float, rawY: Float) {
        val loc = IntArray(2)
        overlay.getLocationOnScreen(loc)
        state.view.x = rawX - loc[0] - state.view.width / 2f
        state.view.y = rawY - loc[1] - state.view.height / 2f
    }

    private fun showDropBar(source: Source, ref: TileRef) {
        dropBar.removeAllViews()
        val pkg = ref.app?.packageName ?: ref.item?.packageName
        val targets = mutableListOf<Triple<String, String, Int>>()
        if (source != Source.DRAWER) targets += Triple("remove", "Remove", R.drawable.oic_close)
        if (pkg != null && ref.folder == null) {
            targets += Triple("info", "App info", R.drawable.oic_info)
            if (!isSystemApp(pkg) && pkg != context.packageName) targets += Triple("uninstall", "Uninstall", R.drawable.oic_delete)
        }
        targets.forEach { (id, label, icon) ->
            dropBar.addView(TextView(context).apply {
                tag = id
                text = label
                textSize = 14f
                typeface = OeaType.medium
                gravity = Gravity.CENTER
                setTextColor(palette.homeText)
                setShadowLayer(4f * density, 0f, 1f * density, palette.homeShadow)
                compoundDrawablePadding = dp(8)
                kit.icon(icon, palette.homeText)?.let { d ->
                    d.setBounds(0, 0, dp(20), dp(20))
                    setCompoundDrawablesRelative(d, null, null, null)
                }
                setPadding(dp(16), dp(10), dp(16), dp(10))
            }, LinearLayout.LayoutParams(0, -1, 1f))
        }
        dropBar.visibility = VISIBLE
        dropBar.alpha = 0f
        dropBar.animate().alpha(1f).setDuration(160L).start()
    }

    private fun dropTargetAt(rawX: Float, rawY: Float): String? {
        if (dropBar.visibility != VISIBLE) return null
        for (i in 0 until dropBar.childCount) {
            val child = dropBar.getChildAt(i)
            val r = viewBounds(child)
            r.inset(0, -dp(10))
            if (r.contains(rawX.toInt(), rawY.toInt())) return child.tag as? String
        }
        return null
    }

    private fun highlightDropTarget(id: String?) {
        for (i in 0 until dropBar.childCount) {
            val child = dropBar.getChildAt(i) as TextView
            val active = child.tag == id
            child.background = if (active) kit.rounded(if (id == "info") palette.accent else palette.danger, 22) else null
            child.setTextColor(if (active) Color.WHITE else palette.homeText)
            child.compoundDrawablesRelative[0]?.setTint(if (active) Color.WHITE else palette.homeText)
        }
    }

    private fun currentPageLayout(): OeaCellLayout? = pager.pageAt(pager.currentPage) as? OeaCellLayout

    private fun dragMove(rawX: Float, rawY: Float) {
        val d = drag ?: return
        positionDragView(d, rawX, rawY)
        val target = dropTargetAt(rawX, rawY)
        highlightDropTarget(target)
        d.dropTarget = target
        currentPageLayout()?.highlightCell = -1
        dockGrid.highlightCell = -1
        d.page = -1
        d.cell = -1
        d.dockSlot = -1
        if (target != null) {
            cancelFlip()
            return
        }
        val dockRect = viewBounds(dockGrid)
        if (dockRect.contains(rawX.toInt(), rawY.toInt())) {
            cancelFlip()
            val slot = ((rawX - dockRect.left) / (dockRect.width() / OeaWorkspaceStore.DOCK_SLOTS.toFloat())).toInt()
                .coerceIn(0, OeaWorkspaceStore.DOCK_SLOTS - 1)
            d.dockSlot = slot
            dockGrid.highlightCell = slot
            return
        }
        val pagerRect = viewBounds(pager)
        if (rawY < pagerRect.top || rawY > pagerRect.bottom) {
            cancelFlip()
            return
        }
        val edge = dp(22)
        when {
            rawX < pagerRect.left + edge -> scheduleFlip(-1)
            rawX > pagerRect.right - edge -> scheduleFlip(1)
            else -> cancelFlip()
        }
        val page = currentPageLayout() ?: return
        val pageRect = viewBounds(page)
        val cell = page.cellAt(rawX - pageRect.left, rawY - pageRect.top)
        if (cell >= 0 && cell >= widgetRowsOnPage(pager.currentPage) * cols()) {
            d.page = pager.currentPage
            d.cell = cell
            page.highlightCell = cell
        }
    }

    private fun scheduleFlip(direction: Int) {
        if (flipDirection == direction) return
        cancelFlip()
        flipDirection = direction
        main.postDelayed(flipRunnable, 520L)
    }

    private fun cancelFlip() {
        flipDirection = 0
        main.removeCallbacks(flipRunnable)
    }

    private fun flipPageDuringDrag() {
        val direction = flipDirection
        flipDirection = 0
        if (drag == null || direction == 0) return
        val target = pager.currentPage + direction
        if (target < 0) return
        if (target >= pager.pageCount()) {
            if (pager.pageCount() >= OeaWorkspaceStore.MAX_PAGES) return
            ws.setPages(pager.pageCount() + 1)
            pager.addView(buildPage(pager.pageCount()))
            indicator.pages = pager.pageCount()
        }
        currentPageLayout()?.highlightCell = -1
        pager.snapToPage(target)
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        // Keep flipping while the finger rests on the edge.
        flipDirection = direction
        main.postDelayed(flipRunnable, 900L)
    }

    private fun finishDrag(rawX: Float, rawY: Float, cancelled: Boolean) {
        val d = drag ?: return
        drag = null
        cancelFlip()
        dropBar.animate().alpha(0f).setDuration(140L).withEndAction { dropBar.visibility = GONE }.start()
        currentPageLayout()?.highlightCell = -1
        dockGrid.highlightCell = -1
        d.tile?.dragging = false
        d.view.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).setDuration(140L).withEndAction { overlay.removeView(d.view) }.start()
        if (!cancelled) {
            when {
                d.dropTarget == "remove" -> removeDragged(d)
                d.dropTarget == "info" -> (d.ref.app?.packageName ?: d.ref.item?.packageName)?.let(::openAppInfo)
                d.dropTarget == "uninstall" -> (d.ref.app?.packageName ?: d.ref.item?.packageName)?.let(::uninstall)
                d.dockSlot >= 0 -> dropOnDock(d)
                d.page >= 0 && d.cell >= 0 -> dropOnCell(d)
            }
        }
        dropTrailingEmptyPages()
        scheduleRebuildAfterDrag = false
        rebuild()
    }

    private fun dropTrailingEmptyPages() {
        var pages = ws.pages()
        val used = (ws.items().map { it.page } + ws.folders().map { it.page } + widgets.ids().map { widgets.pageFor(it) }).toSet()
        while (pages > 1 && (pages - 1) !in used && pages - 1 > ws.getCurrentPage()) pages--
        if (pages != ws.pages()) ws.setPages(pages)
    }

    /** Mutable snapshot of the layout for one edit. */
    private inner class Edit {
        val items = ws.items().toMutableList()
        val folders = ws.folders().toMutableList()
        val dock = ws.dock().toMutableList()

        fun commit() {
            ws.replaceItems(items)
            ws.replaceFolders(folders)
            ws.setDock(dock)
        }

        /** Detaches the dragged thing from where it was. */
        fun detach(d: DragState) {
            when (d.source) {
                Source.HOME_ITEM -> items.removeAll { it.id == d.key }
                Source.HOME_FOLDER -> folders.removeAll { it.id == d.key }
                Source.DOCK -> if (d.ref.dockIndex in dock.indices && dock[d.ref.dockIndex] == d.key) dock.removeAt(d.ref.dockIndex) else dock.remove(d.key)
                Source.FOLDER_MEMBER -> removeFromFolder(d.ref.memberOf, d.key)
                Source.DRAWER -> Unit
            }
        }

        fun removeFromFolder(folderId: String?, key: String) {
            val index = folders.indexOfFirst { it.id == folderId }
            if (index < 0) return
            val folder = folders[index]
            val members = folder.members.filterNot { it == key }
            if (members.size >= 2 && !folder.isPair) {
                folders[index] = folder.copy(members = members)
            } else {
                // A folder or pair with one app left becomes that app.
                folders.removeAt(index)
                members.firstOrNull()?.let { last ->
                    appsByKey[last]?.let { app -> items += OeaWorkspaceStore.Item(last, app.packageName, app.className, folder.page, folder.cell) }
                }
            }
        }
    }

    private fun removeDragged(d: DragState) {
        val edit = Edit()
        edit.detach(d)
        edit.commit()
        if (d.source == Source.HOME_FOLDER) toast("Folder removed. Its apps are still in the app drawer.")
    }

    private fun dropOnDock(d: DragState) {
        val app = d.ref.app
        if (app == null || d.ref.item?.isShortcut == true || d.source == Source.HOME_FOLDER) {
            toast("Only apps fit in the dock")
            return
        }
        val key = appKey(app)
        val edit = Edit()
        val index = ws.currentDockPage() * OeaWorkspaceStore.DOCK_SLOTS + d.dockSlot
        edit.detach(d)
        edit.dock.remove(key)
        edit.dock.add(index.coerceIn(0, edit.dock.size), key)
        edit.items.removeAll { it.id == key }
        edit.commit()
    }

    private fun dropOnCell(d: DragState) {
        val page = d.page
        val cell = d.cell
        val edit = Edit()
        val occupantItem = edit.items.firstOrNull { it.page == page && it.cell == cell }
        val occupantFolder = edit.folders.firstOrNull { it.page == page && it.cell == cell }
        val draggedApp = d.ref.app?.takeIf { d.ref.item?.isShortcut != true && d.source != Source.HOME_FOLDER }
        val draggedKey = draggedApp?.let(::appKey)
        if (occupantItem?.id == d.key || occupantFolder?.id == d.key) return

        if (occupantItem != null && !occupantItem.isShortcut && draggedApp != null && draggedKey != occupantItem.id) {
            // Drop an app on an app: create a folder.
            edit.detach(d)
            edit.items.removeAll { it.id == occupantItem.id || it.id == draggedKey }
            val members = listOf(occupantItem.id, draggedKey!!)
            val title = suggestFolderName(members.mapNotNull(appsByKey::get))
            edit.folders += OeaWorkspaceStore.Folder("folder_" + System.currentTimeMillis(), title, page, cell, members)
            edit.commit()
            return
        }
        if (occupantFolder != null && !occupantFolder.isPair && draggedApp != null) {
            edit.detach(d)
            val index = edit.folders.indexOfFirst { it.id == occupantFolder.id }
            if (index >= 0) {
                val folder = edit.folders[index]
                edit.folders[index] = folder.copy(members = (folder.members + draggedKey!!).distinct())
            }
            edit.items.removeAll { it.id == draggedKey }
            edit.commit()
            return
        }
        var targetCell = cell
        if (occupantItem != null || occupantFolder != null) {
            val reserved = widgetRowsOnPage(page) * cols()
            val taken = (edit.items.filter { it.page == page }.map { it.cell } + edit.folders.filter { it.page == page }.map { it.cell }).toSet()
            val cols = cols()
            targetCell = (reserved until cols * rows()).filter { it !in taken }
                .minByOrNull { c ->
                    val dx = c % cols - cell % cols
                    val dy = c / cols - cell / cols
                    dx * dx + dy * dy
                } ?: -1
            if (targetCell < 0) {
                toast("No space on this page")
                return
            }
        }
        edit.detach(d)
        when {
            d.source == Source.HOME_FOLDER -> {
                val folder = ws.folders().firstOrNull { it.id == d.key } ?: return
                edit.folders += folder.copy(page = page, cell = targetCell)
            }
            d.source == Source.HOME_ITEM && d.ref.item != null -> edit.items += d.ref.item.copy(page = page, cell = targetCell)
            draggedApp != null -> {
                edit.items.removeAll { it.id == draggedKey }
                edit.items += OeaWorkspaceStore.Item(draggedKey!!, draggedApp.packageName, draggedApp.className, page, targetCell)
            }
        }
        edit.commit()
        ws.setCurrentPage(page)
    }

    internal fun suggestFolderName(members: List<OeaAppInfo>): String {
        val category = members.map { it.category }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: -1
        return when (category) {
            android.content.pm.ApplicationInfo.CATEGORY_GAME -> "Games"
            android.content.pm.ApplicationInfo.CATEGORY_SOCIAL -> "Social"
            android.content.pm.ApplicationInfo.CATEGORY_AUDIO -> "Music"
            android.content.pm.ApplicationInfo.CATEGORY_VIDEO -> "Entertainment"
            android.content.pm.ApplicationInfo.CATEGORY_IMAGE -> "Photos"
            android.content.pm.ApplicationInfo.CATEGORY_NEWS -> "News"
            android.content.pm.ApplicationInfo.CATEGORY_MAPS -> "Travel"
            android.content.pm.ApplicationInfo.CATEGORY_PRODUCTIVITY -> "Productivity"
            else -> if (members.all { it.packageName == context.packageName }) "OEA" else "Folder"
        }
    }

    // ========================================================= layout edits

    internal fun isOnHome(key: String): Boolean =
        ws.items().any { it.id == key } || ws.dock().contains(key) || ws.folders().any { key in it.members }

    /** Places an app (or pinned shortcut) in the first free cell from the current page on. */
    internal fun addToHome(item: OeaWorkspaceStore.Item): Boolean {
        val cols = cols()
        val capacity = cols * rows()
        val edit = Edit()
        var page = pager.currentPage.coerceAtLeast(0)
        while (page < OeaWorkspaceStore.MAX_PAGES) {
            val reserved = widgetRowsOnPage(page) * cols
            val taken = (edit.items.filter { it.page == page }.map { it.cell } + edit.folders.filter { it.page == page }.map { it.cell }).toSet()
            val free = (capacity - 1 downTo reserved).firstOrNull { it !in taken }
            if (free != null) {
                edit.items.removeAll { it.id == item.id }
                edit.items += item.copy(page = page, cell = free)
                edit.commit()
                if (page >= ws.pages()) ws.setPages(page + 1)
                ws.setCurrentPage(page)
                rebuild()
                pager.snapToPage(page)
                return true
            }
            page++
        }
        toast("Home is full")
        return false
    }

    internal fun addAppToHome(app: OeaAppInfo) {
        if (ws.items().any { it.id == appKey(app) }) {
            toast("${app.label} is already on Home")
            return
        }
        if (addToHome(OeaWorkspaceStore.Item(appKey(app), app.packageName, app.className, 0, 0))) toast("Added ${app.label} to Home")
    }

    internal fun addShortcutToHome(info: ShortcutInfo) {
        val id = OeaWorkspaceStore.shortcutKey(info.`package`, info.id)
        if (ws.items().any { it.id == id }) {
            toast("Already on Home")
            return
        }
        val pinned = ws.items().filter { it.isShortcut && it.packageName == info.`package` }.mapNotNull { it.shortcutId }
        shortcuts.pin(info.`package`, (pinned + info.id).distinct())
        shortcutIconCache.remove(id)
        val item = OeaWorkspaceStore.Item(
            id, info.`package`, info.activity?.className ?: "", 0, 0,
            kind = OeaWorkspaceStore.KIND_SHORTCUT, shortcutId = info.id, label = shortcuts.label(info),
        )
        if (addToHome(item)) toast("Shortcut added to Home")
    }

    /** Called by the pin-request screen once Android accepted a pinned widget. */
    fun addPinnedWidget(id: Int) {
        val info = widgets.info(id)
        val span = if (info != null) widgets.defaultSpan(info, cellHeightDp(), rows()) else 2
        widgets.commitPinned(id, pager.currentPage.coerceAtLeast(0), span)
        rebuild()
    }

    internal fun cellHeightDp(): Int {
        val page = currentPageLayout()
        val h = page?.cellHeight() ?: 0
        return if (h > 0) (h / density).toInt() else 96
    }

    internal fun removeHomeItem(ref: TileRef) {
        val edit = Edit()
        when {
            ref.memberOf != null && ref.app != null -> edit.removeFromFolder(ref.memberOf, appKey(ref.app))
            ref.folder != null -> edit.folders.removeAll { it.id == ref.folder.id }
            ref.dockIndex >= 0 && ref.app != null -> edit.dock.remove(appKey(ref.app))
            ref.item != null -> edit.items.removeAll { it.id == ref.item.id }
        }
        edit.commit()
        rebuild()
    }

    internal fun updateFolder(folder: OeaWorkspaceStore.Folder) {
        val folders = ws.folders().map { if (it.id == folder.id) folder else it }
        ws.replaceFolders(folders)
        // Apps moved into a folder leave their own Home cells.
        ws.replaceItems(ws.items().filterNot { it.id in folder.members })
        rebuild()
    }

    internal fun createPair(a: OeaAppInfo, b: OeaAppInfo) {
        val edit = Edit()
        val folder = OeaWorkspaceStore.Folder("pair_" + System.currentTimeMillis(), "${a.label} | ${b.label}", 0, 0,
            listOf(appKey(a), appKey(b)), OeaWorkspaceStore.KIND_PAIR)
        val cols = cols()
        val capacity = cols * rows()
        var page = pager.currentPage.coerceAtLeast(0)
        while (page < OeaWorkspaceStore.MAX_PAGES) {
            val reserved = widgetRowsOnPage(page) * cols
            val taken = (edit.items.filter { it.page == page }.map { it.cell } + edit.folders.filter { it.page == page }.map { it.cell }).toSet()
            val free = (capacity - 1 downTo reserved).firstOrNull { it !in taken }
            if (free != null) {
                edit.folders += folder.copy(page = page, cell = free)
                edit.commit()
                if (page >= ws.pages()) ws.setPages(page + 1)
                ws.setCurrentPage(page)
                rebuild()
                pager.snapToPage(page)
                toast("App pair saved to Home")
                return
            }
            page++
        }
        toast("Home is full")
    }

    internal fun addPage() {
        if (ws.pages() >= OeaWorkspaceStore.MAX_PAGES) {
            toast("OEA supports up to ${OeaWorkspaceStore.MAX_PAGES} pages")
            return
        }
        ws.setPages(ws.pages() + 1)
        ws.setCurrentPage(ws.pages() - 1)
        rebuild()
    }

    internal fun removeCurrentPageIfEmpty() {
        val page = pager.currentPage
        val used = ws.items().any { it.page == page } || ws.folders().any { it.page == page } || widgets.ids().any { widgets.pageFor(it) == page }
        if (used) {
            toast("Move the apps and widgets off this page first")
            return
        }
        if (ws.pages() <= 1) return
        ws.replaceItems(ws.items().map { if (it.page > page) it.copy(page = it.page - 1) else it })
        ws.replaceFolders(ws.folders().map { if (it.page > page) it.copy(page = it.page - 1) else it })
        widgets.ids().forEach { id -> widgets.pageFor(id).let { p -> if (p > page) widgets.setPage(id, p - 1) } }
        ws.setPages(ws.pages() - 1)
        ws.setCurrentPage((page - 1).coerceAtLeast(0))
        rebuild()
    }

    internal fun currentPage() = pager.currentPage

    /** Actions OEA Settings can request on Home. */
    fun performHomeAction(action: String) {
        when (action) {
            "widgets" -> menus.showWidgetPicker(pager.currentPage)
            "drawer" -> openDrawer()
            "search" -> openDrawer(searchFocus = true)
        }
    }

    fun createPairFromSettings(a: OeaAppInfo, b: OeaAppInfo) = createPair(a, b)

    internal fun pageHasRoomForWidget(page: Int, span: Int): Boolean = widgetRowsOnPage(page) + span <= rows()

    // ===================================================== widget containers

    /** Widget wrapper: long-press opens widget options without triggering the widget. */
    private inner class WidgetFrame(context: Context, val widgetId: Int, val host: AppWidgetHostView) : FrameLayout(context) {
        private var downRawX = 0f
        private var downRawY = 0f
        private var longPressed = false
        private var reportedW = 0
        private var reportedH = 0
        private val longPress = Runnable {
            longPressed = true
            parent?.requestDisallowInterceptTouchEvent(true)
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            menus.showWidgetOptions(widgetId, this)
        }

        init {
            tag = widgetId
            clipChildren = false
            addView(host, LayoutParams(-1, -1).apply {
                val m = dp(6)
                setMargins(m, m, m, m)
            })
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            val wDp = ((w - dp(12)) / density).toInt()
            val hDp = ((h - dp(12)) / density).toInt()
            if (wDp > 0 && hDp > 0 && (wDp != reportedW || hDp != reportedH)) {
                reportedW = wDp
                reportedH = hDp
                @Suppress("DEPRECATION")
                runCatching { host.updateAppWidgetSize(Bundle(), wDp, hDp, wDp, hDp) }
                widgets.setSize(widgetId, wDp, hDp)
            }
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    longPressed = false
                    postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(ev.rawX - downRawX) > touchSlop || abs(ev.rawY - downRawY) > touchSlop) removeCallbacks(longPress)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(longPress)
            }
            return longPressed
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) longPressed = false
            return longPressed
        }
    }

    /** Dock container: a horizontal fling switches dock pages when the dock holds more than five apps. */
    private inner class DockFrame(context: Context) : FrameLayout(context) {
        private var startX = 0f
        private var startY = 0f
        private var swiping = false

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            if (ws.dockPageCount() <= 1) return false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = ev.x
                    startY = ev.y
                    swiping = false
                }
                MotionEvent.ACTION_MOVE -> if (!swiping && abs(ev.x - startX) > touchSlop * 2 && abs(ev.x - startX) > abs(ev.y - startY)) {
                    swiping = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            return swiping
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!swiping) return super.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                val dx = event.x - startX
                swiping = false
                val count = ws.dockPageCount()
                val current = ws.currentDockPage()
                val next = if (dx < 0) (current + 1) % count else (current - 1 + count) % count
                ws.setCurrentDockPage(next)
                dockGrid.translationX = if (dx < 0) width * 0.25f else -width * 0.25f
                dockGrid.alpha = 0f
                renderDock()
                dockGrid.animate().translationX(0f).alpha(1f).setDuration(220L).setInterpolator(DecelerateInterpolator(1.6f)).start()
            }
            return true
        }
    }

    internal fun dp(v: Int): Int = (v * density + 0.5f).toInt()

    companion object {
        const val REQUEST_HOME_ROLE = 7410
    }
}
