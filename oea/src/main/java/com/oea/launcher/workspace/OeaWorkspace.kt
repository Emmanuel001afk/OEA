package com.oea.launcher.workspace

import android.app.Activity
import android.app.AlertDialog
import android.app.WallpaperManager
import android.app.role.RoleManager
import android.content.ClipData
import android.content.ClipDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.DragEvent
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import com.oea.launcher.data.OeaDataStore
import com.oea.launcher.drawer.OeaAppDrawerController
import com.oea.launcher.folders.OeaFolderController
import com.oea.launcher.icons.OeaIconController
import com.oea.launcher.model.OeaAppInfo
import com.oea.launcher.notifications.OeaNotificationState
import com.oea.launcher.R
import com.oea.launcher.OeaSystemToolsActivity
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
    private val pagerGestures = OeaGestureController(
        pager,
        onSwipeUp = { openDrawer() },
        onSwipeDown = { closeDrawer() },
        // Horizontal movement belongs to the native pager. The gesture layer
        // must not steal it, otherwise page scrolling becomes inconsistent.
        consumeTouchEvents = false,
    )
    private val dock = GridLayout(context)
    private val dockCard = FrameLayout(context)
    private val drawer = LinearLayout(context)
    private val drawerScroll = ScrollView(context)
    private val drawerBody = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val drawerGrid = GridLayout(context)
    private val drawerSearch = EditText(context)
    private val drawerModeButtons = mutableListOf<Pair<TextView, OeaDataStore.DrawerMode>>()
    private val glanceButtons = mutableListOf<TextView>()
    private val search = EditText(context)
    private val dots = LinearLayout(context)
    private val dockIndicator = TextView(context)
    private val wallpaperView = ImageView(context)
    private val wallpaperScrim = View(context)
    private val glanceClock = TextView(context)
    private val glanceDate = TextView(context)
    private val defaultHomeBanner = LinearLayout(context)
    private val homeRoot = LinearLayout(context)
    private lateinit var homeHeader: View
    private lateinit var homeSearch: EditText
    private val wallpaperRequestCode = 0x4F57
    private val defaultHomeRequestCode = 0x4F58
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
    private var themeShadow = Color.argb(170, 0, 0, 0)
    private var wallpaperLightHint: Boolean? = null
    private var wallpaperLoadToken = 0
    private var renderedPages = mutableSetOf<Int>()
    private var pagerVelocityTracker: VelocityTracker? = null
    private var pagerTouchStartX = 0f
    private var pagerTouchStartY = 0f
    private var pagerSnapRunnable: Runnable? = null
    private var pageWidthSyncAttempts = 0

    /**
     * At-a-Glance ticker. It re-posts itself only while OEA Home is attached, so
     * the clock keeps time without a long-lived handler keeping the view alive.
     */
    private val clockTicker = object : Runnable {
        override fun run() {
            updateClock()
            postDelayed(this, CLOCK_TICK_MILLIS)
        }
    }

    fun attachHost(activity: Activity?) {
        hostActivity = activity
        widgetController.setHostActivity(activity)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        widgetController.start()
        removeCallbacks(clockTicker)
        clockTicker.run()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(clockTicker)
        pagerVelocityTracker?.recycle()
        pagerVelocityTracker = null
        widgetController.stop()
        super.onDetachedFromWindow()
    }

    init {
        setBackgroundColor(themeBackground)
        wallpaperView.scaleType = ImageView.ScaleType.CENTER_CROP
        wallpaperView.alpha = 0.98f
        addView(wallpaperView, FrameLayout.LayoutParams(-1, -1))
        // Subtle top-to-bottom scrim: keeps the At-a-Glance text and icons legible
        // on bright or busy wallpapers without hiding the wallpaper itself.
        addView(wallpaperScrim, FrameLayout.LayoutParams(-1, -1))
        homeRoot.orientation = LinearLayout.VERTICAL
        homeRoot.setPadding(dp(16), dp(12), dp(16), dp(8))
        addView(homeRoot, FrameLayout.LayoutParams(-1, -1))
        val root = homeRoot
        loadOeaWallpaper()

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(10), dp(8))
            background = rounded(themeSurface, 26)
        }
        homeHeader = header
        header.addView(ImageView(context).apply {
            setImageResource(R.drawable.oea_logo)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = "OEA"
        }, LinearLayout.LayoutParams(dp(42), dp(42)))
        val glance = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(4), 0)
        }
        glanceClock.textSize = 26f
        glanceClock.setTextColor(themeText)
        glanceClock.setSingleLine(true)
        glanceClock.setTypeface(glanceClock.typeface, android.graphics.Typeface.BOLD)
        glance.addView(glanceClock, LinearLayout.LayoutParams(-1, dp(32)))
        glanceDate.textSize = 11f
        glanceDate.setTextColor(themeMuted)
        glanceDate.setSingleLine(true)
        glanceDate.ellipsize = android.text.TextUtils.TruncateAt.END
        glance.addView(glanceDate, LinearLayout.LayoutParams(-1, dp(18)))
        header.addView(glance, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(glanceButton("⊞", "Open OEA All Apps drawer") { openDrawer() }, glanceButtonParams())
        header.addView(glanceButton("▢", "Open OEA multitasking overview") { openOverview() }, glanceButtonParams())
        header.addView(glanceButton("⋮", "OEA home menu") { menu(it) }, glanceButtonParams())
        root.addView(header, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        updateClock()

        buildDefaultHomeBanner()
        root.addView(defaultHomeBanner, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

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
        pager.overScrollMode = View.OVER_SCROLL_NEVER
        pager.setOnScrollChangeListener { _, scrollX, _, _, _ ->
            val pageWidth = currentPageWidth()
            if (pageWidth > 0) {
                val page = (scrollX.toFloat() / pageWidth).roundToInt().coerceIn(0, ws.pages() - 1)
                if (page != ws.getCurrentPage()) ws.setCurrentPage(page)
                updatePageDots(page)
                ensurePageRendered(page)
                ensurePageRendered(page - 1)
                ensurePageRendered(page + 1)
            }
            // Safety net: whenever paging comes to rest between two pages, glide
            // into the nearest one so a half-dragged page never stays half shown.
            schedulePagerSnap()
        }
        pager.setOnTouchListener { view, event ->
            // Vertical swipes still open/close the All Apps drawer; horizontal
            // movement stays with the native pager.
            pagerGestures.onTouch(view, event)
            handlePagerSnapTouch(view, event)
        }
        pages.orientation = LinearLayout.HORIZONTAL
        pager.addView(pages, FrameLayout.LayoutParams(-2, -1))
        root.addView(pager, LinearLayout.LayoutParams(-1, 0, 1f))
        dots.orientation = LinearLayout.HORIZONTAL
        dots.gravity = Gravity.CENTER
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
        attachVerticalHomeSwipe(dock)
        // The dock floats inside a rounded translucent card so it reads as one
        // surface above the wallpaper instead of a bare row of icons.
        dockCard.setPadding(dp(6), dp(4), dp(6), dp(4))
        dockCard.background = rounded(themeSurface, 28)
        dockCard.elevation = dp(4).toFloat()
        dockCard.setOnDragListener { _, e -> dockDrop(e) }
        attachVerticalHomeSwipe(dockCard)
        dockCard.addView(dock, FrameLayout.LayoutParams(-1, -1))
        root.addView(
            dockCard,
            LinearLayout.LayoutParams(-1, dp(76)).apply {
                leftMargin = dp(8)
                rightMargin = dp(8)
                bottomMargin = dp(2)
            },
        )

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

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(context).apply {
            text = "‹  All apps"
            textSize = 20f
            setTextColor(themeText)
            setSingleLine(true)
            contentDescription = "Close All apps"
            setOnClickListener { closeDrawer() }
        }, LinearLayout.LayoutParams(0, dp(52), 1f))
        // Layout mode switcher: Grid, vertical List and paged drawer layouts.
        header.addView(drawerModeButton("Grid ⊞", OeaDataStore.DrawerMode.GRID), drawerModeParams())
        header.addView(drawerModeButton("List ☰", OeaDataStore.DrawerMode.VERTICAL), drawerModeParams())
        header.addView(drawerModeButton("Pages ◫", OeaDataStore.DrawerMode.HORIZONTAL), drawerModeParams())
        header.addView(TextView(context).apply {
            text = "⋮"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(themeMuted)
            contentDescription = "More All apps layout options"
            isClickable = true
            isFocusable = true
            setOnClickListener { drawerLayoutMenu(this) }
        }, LinearLayout.LayoutParams(dp(36), dp(44)))
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
        updateDrawerModeButtons()
    }

    private fun glanceButtonParams() = LinearLayout.LayoutParams(dp(42), dp(42)).apply { leftMargin = dp(6) }

    private fun glanceButton(glyph: String, description: String, action: (View) -> Unit) = TextView(context).apply {
        text = glyph
        textSize = 20f
        gravity = Gravity.CENTER
        setTextColor(themeText)
        background = rounded(themeSurface, 16)
        contentDescription = description
        isClickable = true
        isFocusable = true
        setOnClickListener { view -> action(view) }
        glanceButtons.add(this)
        attachPressAnimation(this)
    }

    /** At-a-Glance header: live HH:mm clock plus date and the OEA engine label. */
    private fun updateClock() {
        val now = Date()
        val locale = Locale.getDefault()
        glanceClock.text = SimpleDateFormat(CLOCK_FORMAT, locale).format(now)
        glanceDate.text = SimpleDateFormat(DATE_FORMAT, locale).format(now) + " • OEA Engine"
    }

    private fun buildDefaultHomeBanner() {
        defaultHomeBanner.orientation = LinearLayout.HORIZONTAL
        defaultHomeBanner.gravity = Gravity.CENTER_VERTICAL
        defaultHomeBanner.setPadding(dp(14), dp(10), dp(12), dp(10))
        defaultHomeBanner.background = rounded(themeSurface, 20)
        defaultHomeBanner.isClickable = true
        defaultHomeBanner.isFocusable = true
        defaultHomeBanner.contentDescription = "Set OEA as Default Home Launcher"
        defaultHomeBanner.addView(TextView(context).apply {
            text = "⌂"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(themeText)
        }, LinearLayout.LayoutParams(dp(28), dp(28)))
        defaultHomeBanner.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(8), 0)
            addView(TextView(context).apply {
                text = "Set OEA as Default Home Launcher"
                textSize = 14f
                setTextColor(themeText)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setSingleLine(true)
            })
            addView(TextView(context).apply {
                text = "One tap to give OEA the Android Home role"
                textSize = 11f
                setTextColor(themeMuted)
                setSingleLine(true)
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        defaultHomeBanner.addView(TextView(context).apply {
            text = "SET"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(themeText)
            background = rounded(withAlpha(themeText, 42), 14)
            setPadding(dp(12), dp(5), dp(12), dp(5))
        }, LinearLayout.LayoutParams(-2, -2))
        defaultHomeBanner.setOnClickListener { requestDefaultHomeLauncher() }
        attachPressAnimation(defaultHomeBanner)
        defaultHomeBanner.visibility = View.GONE
    }

    /** The banner is only useful while another launcher still owns the Home role. */
    private fun renderDefaultHomeBanner() {
        defaultHomeBanner.visibility = if (isDefaultHomeLauncher()) View.GONE else View.VISIBLE
    }

    private fun isDefaultHomeLauncher(): Boolean =
        roleHomeHeld() ?: run {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolved = runCatching {
                context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
            }.getOrNull()
            resolved?.activityInfo?.packageName == context.packageName
        }

    /**
     * Android 10+ Home role, kept isolated behind one SDK check so pre-Q devices
     * never resolve RoleManager. Null means "this Android has no Home role".
     */
    private fun roleHomeHeld(): Boolean? {
        if (Build.VERSION.SDK_INT < 29) return null
        val roleManager = context.getSystemService(RoleManager::class.java) ?: return null
        if (!roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) return null
        return runCatching { roleManager.isRoleHeld(RoleManager.ROLE_HOME) }.getOrNull()
    }

    private fun roleHomeIntent(): Intent? {
        if (Build.VERSION.SDK_INT < 29) return null
        val roleManager = context.getSystemService(RoleManager::class.java) ?: return null
        if (!roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) return null
        return runCatching { roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME) }.getOrNull()
    }

    private fun requestDefaultHomeLauncher() {
        if (roleHomeHeld() == true) {
            Toast.makeText(context, "OEA is already your default Home launcher.", Toast.LENGTH_SHORT).show()
            return
        }
        val activity = hostActivity
        val roleIntent = roleHomeIntent()
        if (activity != null && roleIntent != null) {
            val requested = runCatching { activity.startActivityForResult(roleIntent, defaultHomeRequestCode) }.isSuccess
            if (requested) return
        }
        openHomeSettings()
    }

    private fun openHomeSettings() {
        val intent = if (Build.VERSION.SDK_INT >= 29) {
            Intent(Settings.ACTION_HOME_SETTINGS)
        } else {
            Intent(Settings.ACTION_SETTINGS)
        }
        runCatching { (hostActivity ?: context).startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Toast.makeText(context, "Open Android Home settings to choose OEA.", Toast.LENGTH_SHORT).show() }
    }

    private fun openOverview() {
        val overview = Intent(context, OeaSystemToolsActivity::class.java)
            .putExtra(OeaSystemToolsActivity.EXTRA_SCREEN, "multitask")
        runCatching { (hostActivity ?: context).startActivity(overview) }
            .onFailure { Toast.makeText(context, "Could not open OEA overview.", Toast.LENGTH_SHORT).show() }
    }

    private fun drawerModeParams() = LinearLayout.LayoutParams(-2, dp(34)).apply { leftMargin = dp(4) }

    private fun drawerModeButton(label: String, mode: OeaDataStore.DrawerMode) = TextView(context).apply {
        text = label
        textSize = 11f
        gravity = Gravity.CENTER
        setSingleLine(true)
        setPadding(dp(8), 0, dp(8), 0)
        contentDescription = label + " layout"
        isClickable = true
        isFocusable = true
        setOnClickListener {
            store.setDrawerMode(mode)
            updateDrawerModeButtons()
            renderDrawer(drawerSearch.text.toString())
        }
        drawerModeButtons.add(this to mode)
    }

    private fun updateDrawerModeButtons() {
        val current = store.drawerMode()
        drawerModeButtons.forEach { (view, mode) ->
            val active = mode == current
            view.background = rounded(if (active) withAlpha(themeText, 40) else Color.TRANSPARENT, 14)
            view.setTextColor(if (active) themeText else themeMuted)
        }
    }

    private fun withAlpha(color: Int, alpha: Int) =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    /** Re-tints the fixed home chrome after the wallpaper/theme changes. */
    private fun applyHomeChromeColors() {
        if (::homeHeader.isInitialized) homeHeader.background = rounded(themeSurface, 26)
        glanceClock.setTextColor(themeText)
        glanceDate.setTextColor(themeMuted)
        glanceButtons.forEach {
            it.setTextColor(themeText)
            it.background = rounded(withAlpha(themeText, 34), 16)
        }
        dockCard.background = rounded(withAlpha(themeSurface, 214), 28)
        dockIndicator.setTextColor(themeMuted)
        search.background = rounded(withAlpha(themeSurface, 214), 28)
        defaultHomeBanner.background = rounded(withAlpha(themeSurface, 214), 20)
        wallpaperScrim.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(withAlpha(themeBackground, 118), withAlpha(themeBackground, 46), Color.TRANSPARENT),
        )
        updatePageDots(ws.getCurrentPage())
        updateDrawerModeButtons()
    }

    fun bind(value: List<OeaAppInfo>) {
        apps = value
        OeaAppFreezer.syncActualState(context)
        ws.ensureSeeded(apps.filterNot { it.packageName == context.packageName }.map { Triple(it.packageName, it.className, it.label) })
        ws.clearMissing(apps.map { OeaWorkspaceStore.key(it.packageName, it.className) }.toSet())
        rebuild()
        renderDefaultHomeBanner()
        renderDrawer(drawerSearch.text.toString())
    }

    private fun rebuild() {
        normalizeFolderNames()
        normalizeHomeLayout()
        renderedPages.clear()
        pages.removeAllViews()
        // Pages are exactly one pager viewport wide. The full screen width is
        // wrong here: the pager is inset by the home padding, so screen-wide
        // pages made horizontal scrolling drift out of alignment.
        val pageWidth = currentPageWidth()
        for (p in 0 until ws.pages()) {
            // Keep the four-row icon grid fixed in the visible home viewport.
            // Only the widget area can scroll, so a large widget stack never turns
            // the entire home page into a long vertical document.
            val pageFrame = FrameLayout(context).apply {
                clipChildren = true
                clipToPadding = true
                tag = p
                setBackgroundColor(Color.TRANSPARENT)
            }
            val pageContent = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(4), 0, dp(8))
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
                            val sourceId = event.clipData?.getItemAt(0)?.text?.toString()
                                ?.removePrefix("oea_widget:")?.toIntOrNull()
                            if (sourceId != null) {
                                val container = host as FrameLayout
                                var targetId: Int? = null
                                for (i in 0 until container.childCount) {
                                    val child = container.getChildAt(i)
                                    val childId = child.tag as? Int
                                    if (childId != null &&
                                        event.x >= child.left && event.x <= child.right &&
                                        event.y >= child.top && event.y <= child.bottom) {
                                        targetId = childId
                                        break
                                    }
                                }
                                widgetController.moveBefore(sourceId, targetId)
                                widgetController.setPage(sourceId, p)
                                renderedPages.toList().forEach { refreshPage(it) }
                            }
                            true
                        }
                        else -> false
                    }
                }
            }
            // Give widgets the remaining viewport below the fixed icon grid.
            // Their own region scrolls if multiple widgets exceed that available area.
            val widgetScroll = ScrollView(context).apply {
                isFillViewport = false
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                tag = "widgetScroll"
            }
            widgetScroll.addView(widgetHost, FrameLayout.LayoutParams(-1, -2))
            pageContent.addView(widgetScroll, LinearLayout.LayoutParams(-1, 0, 1f))
            pageFrame.addView(pageContent, FrameLayout.LayoutParams(-1, -1))
            pages.addView(pageFrame, LinearLayout.LayoutParams(pageWidth, -1))
        }
        val current = ws.getCurrentPage()
        ensurePageRendered(current)
        ensurePageRendered(current - 1)
        ensurePageRendered(current + 1)
        updatePageDots(current)
        renderDock()
        // rebuild() can run before the pager has been measured; re-apply the real
        // viewport width to every child page once layout has happened.
        pager.post { syncPageWidths() }
    }

    /** Width of a single home page: the pager viewport, never the whole screen. */
    private fun currentPageWidth(): Int {
        pages.getChildAt(0)?.width?.takeIf { it > 0 }?.let { return it }
        pager.width.takeIf { it > 0 }?.let { return it }
        val root = homeRoot
        (root.width - root.paddingLeft - root.paddingRight).takeIf { it > 0 }?.let { return it }
        return resources.displayMetrics.widthPixels
    }

    /**
     * Keeps every child page exactly as wide as the pager viewport and restores
     * the selected page. Retries briefly while the pager is still unmeasured.
     */
    private fun syncPageWidths() {
        val target = pager.width
        if (target <= 0) {
            if (pageWidthSyncAttempts < MAX_PAGE_WIDTH_SYNC_ATTEMPTS) {
                pageWidthSyncAttempts++
                pager.post { syncPageWidths() }
            }
            return
        }
        pageWidthSyncAttempts = 0
        var changed = false
        for (index in 0 until pages.childCount) {
            val child = pages.getChildAt(index)
            val params = child.layoutParams as? LinearLayout.LayoutParams ?: continue
            if (params.width != target) {
                params.width = target
                child.layoutParams = params
                changed = true
            }
        }
        if (changed) pages.requestLayout()
        val current = ws.getCurrentPage()
        val x = current * target
        if (pager.scrollX != x) pager.scrollTo(x, 0)
        updatePageDots(current)
    }

    /**
     * Page snapping on top of the native pager. The pager keeps its own
     * horizontal scrolling; this only decides where a released gesture settles,
     * so slow drags and fast flicks both land on a whole page.
     */
    private fun handlePagerSnapTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pagerTouchStartX = event.rawX
                pagerTouchStartY = event.rawY
                pagerVelocityTracker?.recycle()
                pagerVelocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
            }
            MotionEvent.ACTION_MOVE -> {
                val tracker = pagerVelocityTracker
                if (tracker == null) {
                    // A drag that starts on a home tile only reaches the pager
                    // once the pager intercepts it, so the DOWN event is missing:
                    // start tracking from the first intercepted move instead.
                    pagerTouchStartX = event.rawX
                    pagerTouchStartY = event.rawY
                    pagerVelocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                } else {
                    tracker.addMovement(event)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val tracker = pagerVelocityTracker
                pagerVelocityTracker = null
                val dx = event.rawX - pagerTouchStartX
                val dy = event.rawY - pagerTouchStartY
                var velocityX = 0f
                if (tracker != null) {
                    tracker.computeCurrentVelocity(1000)
                    velocityX = tracker.xVelocity
                    tracker.recycle()
                }
                // Mostly-vertical gestures belong to the All Apps swipe instead.
                if (abs(dy) > abs(dx) && abs(dy) > dp(SWIPE_UP_DISTANCE_DP)) return false
                val pageWidth = currentPageWidth()
                if (pageWidth <= 0) return false
                val settled = nearestPage()
                val flingThreshold = dp(PAGER_FLING_VELOCITY_DP).toFloat()
                val target = when {
                    velocityX <= -flingThreshold -> settled + 1
                    velocityX >= flingThreshold -> settled - 1
                    else -> settled
                }
                // Posted so the snap replaces the pager's own fling animation.
                view.post { snapToPage(target) }
            }
        }
        return false
    }

    private fun nearestPage(): Int {
        val pageWidth = currentPageWidth()
        if (pageWidth <= 0) return ws.getCurrentPage()
        return ((pager.scrollX + pageWidth / 2f) / pageWidth).toInt().coerceIn(0, ws.pages() - 1)
    }

    /** Glides into the nearest page once paging has come to rest. */
    private fun schedulePagerSnap() {
        pagerSnapRunnable?.let { pager.removeCallbacks(it) }
        val runnable = Runnable {
            pagerSnapRunnable = null
            snapToPage(nearestPage())
        }
        pagerSnapRunnable = runnable
        pager.postDelayed(runnable, PAGER_SNAP_IDLE_MILLIS)
    }

    /** Snaps the pager to one whole page and renders that page and its neighbours. */
    private fun snapToPage(page: Int) {
        val pageWidth = currentPageWidth()
        if (pageWidth <= 0) return
        val target = page.coerceIn(0, ws.pages() - 1)
        val x = target * pageWidth
        if (pager.scrollX != x) pager.smoothScrollTo(x, 0)
        if (ws.getCurrentPage() != target) ws.setCurrentPage(target)
        ensurePageRendered(target)
        ensurePageRendered(target - 1)
        ensurePageRendered(target + 1)
        updatePageDots(target)
    }

    /** Interactive page indicator: tap a dot to jump straight to that page. */
    private fun updatePageDots(current: Int) {
        val total = ws.pages()
        if (dots.childCount != total) {
            dots.removeAllViews()
            repeat(total) { index ->
                val dot = View(context).apply {
                    contentDescription = "Go to home page " + (index + 1)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { snapToPage(index) }
                }
                dots.addView(
                    dot,
                    LinearLayout.LayoutParams(dp(7), dp(7)).apply {
                        leftMargin = dp(4)
                        rightMargin = dp(4)
                    },
                )
            }
        }
        for (index in 0 until dots.childCount) {
            val active = index == current
            dots.getChildAt(index).background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (active) themeText else withAlpha(themeText, 74))
            }
        }
        dots.contentDescription = "Home page " + (current + 1) + " of " + total
    }

    /**
     * Swipe up anywhere on the home screen. Tiles, folder tiles, empty cells and
     * the dock all open the All Apps drawer, so the gesture never depends on
     * finding a free patch of wallpaper first.
     */
    private fun attachVerticalHomeSwipe(view: View) {
        var downX = 0f
        var downY = 0f
        var triggered = false
        view.setOnTouchListener { target, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    triggered = false
                    pressIn(target)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = event.rawY - downY
                    val dx = event.rawX - downX
                    if (!triggered && dy < -dp(SWIPE_UP_DISTANCE_DP) && abs(dy) > abs(dx) * 1.3f) {
                        triggered = true
                        pressOut(target)
                        openDrawer()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pressOut(target)
            }
            // Never consume: taps, long-press drags and horizontal paging win.
            false
        }
    }

    /** Press feedback only: springy scale-down while held, back to 1f on release. */
    private fun attachPressAnimation(view: View) {
        view.setOnTouchListener { target, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> pressIn(target)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pressOut(target)
            }
            false
        }
    }

    private fun pressIn(view: View) {
        view.animate().cancel()
        view.animate()
            .scaleX(PRESS_SCALE)
            .scaleY(PRESS_SCALE)
            .setDuration(store.animationDuration(PRESS_IN_MILLIS))
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun pressOut(view: View) {
        view.animate().cancel()
        view.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(store.animationDuration(PRESS_OUT_MILLIS))
            .setInterpolator(OvershootInterpolator(PRESS_SPRING_TENSION))
            .start()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Rotation and freeform resizes change the viewport: keep page widths in sync.
        pager.post { syncPageWidths() }
    }

    private fun ensurePageRendered(page: Int) {
        if (page !in 0 until ws.pages() || renderedPages.contains(page)) return
        val pageFrame = pages.getChildAt(page) as? FrameLayout ?: return
        val content = pageFrame.getChildAt(0) as? LinearLayout ?: return
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
        updatePageDots(ws.getCurrentPage())
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
        // Never shrink the page count: pages the user added from the Home menu
        // stay even while they are still empty.
        val requiredPages = maxOf(ws.pages(), highestPage + 1).coerceIn(1, OeaWorkspaceStore.MAX_PAGES)
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
        // The workspace is exactly four rows tall; overflow is moved to another page by normalizeHomeLayout().
        val count = cols() * 4
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
            attachVerticalHomeSwipe(this)
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
            setImageDrawable(icon(app.packageName, app.className))
                applyIconShape(this)
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
        val authority = backend == OeaAppFreezer.Backend.DEVICE_OWNER || backend == OeaAppFreezer.Backend.ROOT
        val frozen = OeaAppFreezer.frozenPackages(context).contains(app.packageName)
        val authorityLabel = when (backend) {
            OeaAppFreezer.Backend.DEVICE_OWNER -> "device-owner"
            OeaAppFreezer.Backend.ROOT -> "root"
            OeaAppFreezer.Backend.DEVICE_ADMIN -> "device-admin (not sufficient for freezing)"
            OeaAppFreezer.Backend.NONE -> "none"
        }
        val message = if (authority) {
            if (frozen) app.label + " is currently frozen by OEA (" + authorityLabel + " authority)."
            else app.label + " can be frozen by OEA (" + authorityLabel + " authority)."
        } else {
            app.label + " is not frozen. True package suspension needs Device Owner or root; OEA will not repeatedly prompt for unavailable authority."
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
                if (!authority) openFreezerSetup()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openFreezerSetup() {
        val intent = Intent(context, OeaSystemToolsActivity::class.java)
            .putExtra(OeaSystemToolsActivity.EXTRA_SCREEN, "freezer")
        if (hostActivity == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { (hostActivity ?: context).startActivity(intent) }
            .onFailure { Toast.makeText(context, "Could not open OEA App Freezer setup.", Toast.LENGTH_SHORT).show() }
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
        runCatching {
            (hostActivity ?: context).startActivity(
                Intent(context, com.oea.launcher.callblocker.OeaCallBlockerActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        }.onFailure {
            Toast.makeText(context, "OEA Call Blocker could not be opened.", Toast.LENGTH_SHORT).show()
        }
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
        updateDrawerModeButtons()
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
        // System-only tools remain available from the Home menu and OEA Settings,
        // not as experimental shortcuts mixed into the normal All Apps drawer.
        val tools = listOf(
            "⚙" to "OEA Settings",
            "☎" to "Phone & Calls",
            "⛔" to "Call Blocker",
            "🎮" to "Game Boost",
        )
        tools.forEachIndexed { index, (iconText, title) ->
            row.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = rounded(themeSurface, 18)
                isClickable = true
                isFocusable = true
                setPadding(dp(3), dp(2), dp(3), dp(2))
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
                    textSize = 30f
                    gravity = Gravity.CENTER
                    setTextColor(themeText)
                }, LinearLayout.LayoutParams(-1, dp(40)))
                addView(TextView(context).apply {
                    text = title
                    textSize = 9f
                    gravity = Gravity.CENTER
                    setTextColor(themeText)
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(-1, dp(26)))
            }, GridLayout.LayoutParams().apply {
                width = 0
                height = dp(70)
                columnSpec = GridLayout.spec(index % 3, 1, 1f)
                rowSpec = GridLayout.spec(index / 3)
                setMargins(dp(1), dp(1), dp(1), dp(2))
            })
        }
        drawerBody.addView(row, LinearLayout.LayoutParams(-1, dp(((tools.size + 2) / 3) * 73)))
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
                setImageDrawable(icon(app.packageName, app.className))
                applyIconShape(this)
                scaleType = ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = true
            }
            addView(iconView, FrameLayout.LayoutParams(dp(46), dp(46), Gravity.CENTER))
            val count = OeaNotificationState.countForPackage(app.packageName)
            if (count > 0) {
                addView(TextView(context).apply {
                    text = if (count > 99) "99+" else count.toString()
                    textSize = 8f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    background = rounded(Color.rgb(210, 60, 70), 11)
                    minWidth = dp(17)
                    minHeight = dp(17)
                    setPadding(dp(3), 0, dp(3), 0)
                }, FrameLayout.LayoutParams(-2, dp(17), Gravity.TOP or Gravity.END))
            }
        }, LinearLayout.LayoutParams(dp(46), dp(46)))
        if (store.showAppLabels()) {
            addView(tileLabel(app.label), LinearLayout.LayoutParams(-1, dp(20)))
        }
    }

    private fun drawerRow(app: OeaAppInfo): View {
        val key = OeaWorkspaceStore.key(app.packageName, app.className)
        return LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
            background = rounded(themeSurface, 16)
            addView(ImageView(context).apply {
                setImageDrawable(icon(app.packageName, app.className))
                applyIconShape(this)
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
        attachVerticalHomeSwipe(this)
        // Framed 2x2 preview: four slots always, so a nearly empty folder still
        // reads as a folder instead of a stray icon.
        val preview = GridLayout(context).apply {
            columnCount = 2
            rowCount = 2
            useDefaultMargins = false
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = GradientDrawable().apply {
                setColor(withAlpha(themeSurface, 205))
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), withAlpha(themeText, 54))
            }
        }
        val previewApps = folder.members.mapNotNull(::find).take(4)
        repeat(4) { index ->
            val app = previewApps.getOrNull(index)
            val cell = ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                if (app != null) {
                    setImageDrawable(icon(app.packageName, app.className))
                    applyIconShape(this)
                } else {
                    background = rounded(withAlpha(themeText, 26), 9)
                }
            }
            preview.addView(cell, GridLayout.LayoutParams().apply {
                width = dp(20)
                height = dp(20)
                columnSpec = GridLayout.spec(index % 2)
                rowSpec = GridLayout.spec(index / 2)
                setMargins(dp(1), dp(1), dp(1), dp(1))
            })
        }
        addView(preview, LinearLayout.LayoutParams(dp(52), dp(52)))
        addView(tileLabel(folder.title), LinearLayout.LayoutParams(-1, dp(20)))
    }

    private fun emptyCell() = TextView(context).apply {
        setOnLongClickListener { menu(this); true }
        attachVerticalHomeSwipe(this)
    }

    /** Crisp single-line tile label with a soft drop shadow for busy wallpapers. */
    private fun tileLabel(text: String) = TextView(context).apply {
        this.text = text
        textSize = 11f
        gravity = Gravity.CENTER
        setSingleLine(true)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setTextColor(themeText)
        setShadowLayer(dp(2).toFloat(), 0f, dp(1).toFloat(), themeShadow)
        setPadding(dp(2), 0, dp(2), 0)
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
                setImageDrawable(icon(app.packageName, app.className))
                applyIconShape(this)
                scaleType = ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = true
                alpha = if (OeaAppFreezer.frozenPackages(context).contains(app.packageName)) 0.45f else 1f
                setPadding(0, 0, 0, 0)
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
        }, LinearLayout.LayoutParams(dp(50), dp(50)))
        if (store.showAppLabels()) {
            addView(tileLabel(app.label), LinearLayout.LayoutParams(-1, dp(20)))
        }
    }

    private fun pageDrop(page: Int) = View.OnDragListener { view, e ->
        when (e.action) {
            DragEvent.ACTION_DRAG_STARTED -> true
            DragEvent.ACTION_DROP -> {
                val widgetId = e.clipData?.getItemAt(0)?.text?.toString()
                    ?.removePrefix("oea_widget:")?.toIntOrNull()
                if (widgetId != null) {
                    widgetController.setPage(widgetId, page)
                    renderedPages.toList().forEach { refreshPage(it) }
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
        var folderDialog: AlertDialog? = null
        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(4))
        }
        actions.addView(
            folderActionButton("Add / Remove Apps") {
                folderDialog?.dismiss()
                openFolderAppManager(folder)
            },
            LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(4) },
        )
        actions.addView(
            folderActionButton("Rename") {
                folderDialog?.dismiss()
                showFolderRename(folder)
            },
            LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(4) },
        )
        box.addView(actions, LinearLayout.LayoutParams(-1, -2))
        val dialog = AlertDialog.Builder(activity).setView(box)
            .setPositiveButton("Done", null).create()
        folderDialog = dialog
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            dialog.window?.setDimAmount(0.55f)
        }
        dialog.show()
        dialog.window?.decorView?.apply {
            alpha = 0f
            scaleX = 0.94f
            scaleY = 0.94f
            animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(store.animationDuration(180))
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
    }

    private fun folderActionButton(label: String, action: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 13f
        gravity = Gravity.CENTER
        setSingleLine(true)
        setTextColor(themeText)
        background = rounded(withAlpha(themeText, 34), 16)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        contentDescription = label
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
        attachPressAnimation(this)
    }

    /**
     * Multi-select manager for one folder: tick an app to move it in, untick it
     * to send it back to the home grid. Changes apply on Save.
     */
    private fun openFolderAppManager(folder: OeaWorkspaceStore.Folder) {
        val activity = hostActivity ?: run {
            Toast.makeText(context, "OEA Home is not ready for folders.", Toast.LENGTH_SHORT).show()
            return
        }
        val current = ws.folders().firstOrNull { it.id == folder.id } ?: return
        val choices = apps
            .filterNot { store.isHidden(it.packageName, it.className) }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
        val keys = choices.map { OeaWorkspaceStore.key(it.packageName, it.className) }
        val selected = keys.map { current.members.contains(it) }.toBooleanArray()
        AlertDialog.Builder(activity)
            .setTitle("Add / Remove Apps")
            .setMultiChoiceItems(choices.map { it.label }.toTypedArray(), selected) { _, which, value ->
                selected[which] = value
            }
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val additions = keys.filterIndexed { index, key ->
                    selected[index] && !current.members.contains(key)
                }
                val removals = current.members.filter { member ->
                    val index = keys.indexOf(member)
                    index >= 0 && !selected[index]
                }
                applyFolderMembership(current, additions, removals)
            }
            .show()
    }

    private fun applyFolderMembership(
        folder: OeaWorkspaceStore.Folder,
        additions: List<String>,
        removals: List<String>,
    ) {
        if (additions.isEmpty() && removals.isEmpty()) return
        val pagesBefore = ws.pages()
        val affected = mutableSetOf(folder.page)
        var members = ws.folders().firstOrNull { it.id == folder.id }?.members?.toMutableList() ?: return
        var items = ws.items().toMutableList()

        if (additions.isNotEmpty()) {
            items.filter { it.id in additions }.forEach { affected.add(it.page) }
            items = items.filterNot { it.id in additions }.toMutableList()
            ws.setDock(ws.dock().filterNot { additions.contains(it) })
            additions.forEach { key -> if (!members.contains(key)) members.add(key) }
        }

        removals.forEach { key ->
            members.remove(key)
            val app = find(key) ?: return@forEach
            val spot = findFreeCell(items) ?: return@forEach
            items.add(OeaWorkspaceStore.Item(key, app.packageName, app.className, spot.first, spot.second))
            affected.add(spot.first)
        }

        ws.replaceItems(items)
        if (members.isEmpty()) {
            ws.replaceFolders(ws.folders().filterNot { it.id == folder.id })
        } else {
            val title = if (folder.title == "Folder") suggestFolderName(members) else folder.title
            ws.replaceFolders(
                ws.folders().map { if (it.id == folder.id) it.copy(members = members, title = title) else it },
            )
        }
        if (ws.pages() != pagesBefore) rebuild() else refreshPages(*affected.toIntArray())
        renderDrawer(drawerSearch.text.toString())
    }

    /** First free home cell, opening one more page when every page is full. */
    private fun findFreeCell(items: List<OeaWorkspaceStore.Item>): Pair<Int, Int>? {
        for (page in 0 until ws.pages()) {
            firstFree(items, page, 0)?.let { return page to it }
        }
        if (ws.pages() >= OeaWorkspaceStore.MAX_PAGES) return null
        val page = ws.pages()
        ws.setPages(page + 1)
        return firstFree(items, page, 0)?.let { page to it }
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
            setImageDrawable(icon(app.packageName, app.className))
                applyIconShape(this)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(0, 0, 0, 0)
            adjustViewBounds = true
        }, FrameLayout.LayoutParams(dp(46), dp(46), Gravity.CENTER))
        attachVerticalHomeSwipe(this)
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
        snapToPage(ws.getCurrentPage() + 1)
    }

    private fun previousPage() {
        snapToPage(ws.getCurrentPage() - 1)
    }

    private fun openDrawer() {
        if (drawerOpen) return
        drawerOpen = true
        drawer.animate().cancel()
        homeRoot.animate().cancel()
        // Home shrinks and fades while the drawer scales and slides in, so the
        // swipe-up reads as one continuous transition instead of a hard cut.
        homeRoot.animate()
            .alpha(0f)
            .scaleX(HOME_EXIT_SCALE)
            .scaleY(HOME_EXIT_SCALE)
            .translationY(dp(20).toFloat())
            .setDuration(store.animationDuration(140))
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { if (drawerOpen) homeRoot.visibility = View.GONE }
            .start()
        drawer.alpha = 0f
        drawer.scaleX = DRAWER_ENTER_SCALE
        drawer.scaleY = DRAWER_ENTER_SCALE
        drawer.translationY = dp(28).toFloat()
        drawer.visibility = View.VISIBLE
        drawerSearch.setText(search.text.toString())
        drawerSearch.setSelection(drawerSearch.text.length)
        renderDrawer(drawerSearch.text.toString())
        updateDrawerModeButtons()
        drawer.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(store.animationDuration(220))
            .setInterpolator(DecelerateInterpolator())
            .start()
        drawerSearch.requestFocus()
    }

    private fun closeDrawer() {
        if (!drawerOpen) return
        drawerOpen = false
        drawer.animate().cancel()
        drawer.animate()
            .alpha(0f)
            .scaleX(DRAWER_ENTER_SCALE)
            .scaleY(DRAWER_ENTER_SCALE)
            .translationY(dp(24).toFloat())
            .setDuration(store.animationDuration(140))
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                drawer.visibility = View.GONE
                drawer.translationY = 0f
                drawer.scaleX = 1f
                drawer.scaleY = 1f
                drawer.alpha = 1f
                if (drawerOpen) return@withEndAction
                homeRoot.animate().cancel()
                homeRoot.alpha = 0f
                homeRoot.scaleX = HOME_EXIT_SCALE
                homeRoot.scaleY = HOME_EXIT_SCALE
                homeRoot.translationY = dp(20).toFloat()
                homeRoot.visibility = View.VISIBLE
                homeRoot.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .translationY(0f)
                    .setDuration(store.animationDuration(180))
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            .start()
        search.setText("")
        drawerSearch.setText("")
        search.clearFocus()
        drawerSearch.clearFocus()
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(windowToken, 0)
    }

    private fun applyIconShape(view: ImageView) {
        val shape = store.iconShape()
        val radius = when (shape) {
            "circle" -> dp(999).toFloat()
            "square" -> 0f
            "squircle" -> dp(15).toFloat()
            else -> dp(10).toFloat()
        }
        view.background = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = radius
        }
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(target: View, outline: android.graphics.Outline) {
                val w = target.width
                val h = target.height
                if (w <= 0 || h <= 0) {
                    outline.setRoundRect(0, 0, dp(40), dp(40), radius)
                    return
                }
                if (shape == "squircle" && android.os.Build.VERSION.SDK_INT >= 30) {
                    // A convex superellipse produces the squarer, softer mask used by
                    // modern adaptive icon systems instead of a generic rounded rect.
                    val path = android.graphics.Path()
                    val halfW = w / 2f
                    val halfH = h / 2f
                    for (step in 0..64) {
                        val t = (2.0 * Math.PI * step / 64.0)
                        val cos = kotlin.math.cos(t)
                        val sin = kotlin.math.sin(t)
                        val x = halfW + halfW * Math.signum(cos) * Math.pow(Math.abs(cos), 0.5)
                        val y = halfH + halfH * Math.signum(sin) * Math.pow(Math.abs(sin), 0.5)
                        if (step == 0) path.moveTo(x.toFloat(), y.toFloat()) else path.lineTo(x.toFloat(), y.toFloat())
                    }
                    path.close()
                    outline.setConvexPath(path)
                } else {
                    val r = if (shape == "circle") minOf(w, h) / 2f else radius
                    outline.setRoundRect(0, 0, w, h, r)
                }
            }
        }
        view.clipToOutline = true
        view.invalidateOutline()
    }

    private fun openIconShapeSettings() {
        val labels = arrayOf("Circle", "Rounded square", "Squircle", "Square")
        val values = arrayOf("circle", "rounded", "squircle", "square")
        val current = values.indexOf(store.iconShape()).takeIf { it >= 0 } ?: 1
        AlertDialog.Builder(hostActivity ?: context).setTitle("Icon shape")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                store.setIconShape(values[which])
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
                    setImageDrawable(icon(app.packageName, app.className))
                applyIconShape(this)
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
        action("Icon shape", store.iconShape()) { openIconShapeSettings() }
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
        action(
            "Set OEA as Default Home Launcher",
            if (isDefaultHomeLauncher()) "OEA already holds the Android Home role"
            else "Ask Android to make OEA the default Home app",
        ) { requestDefaultHomeLauncher() }
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
            themeShadow = Color.argb(110, 255, 255, 255)
        } else {
            themeBackground = Color.rgb(12, 15, 21)
            themeSurface = Color.rgb(30, 36, 49)
            themeText = Color.WHITE
            themeMuted = Color.rgb(154, 162, 177)
            themeShadow = Color.argb(170, 0, 0, 0)
        }
        setBackgroundColor(Color.TRANSPARENT)
        search.setTextColor(themeText)
        search.setHintTextColor(themeMuted)
        drawerSearch.setTextColor(themeText)
        drawerSearch.setHintTextColor(themeMuted)
        drawer.setBackgroundColor(Color.TRANSPARENT)
        applyHomeChromeColors()
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
                val launchIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    component = ComponentName(app.packageName, app.className)
                }
                val launchContext = hostActivity ?: context
                launchContext.startActivity(
                    launchIntent,
                    android.app.ActivityOptions.makeCustomAnimation(
                        launchContext,
                        android.R.anim.fade_in,
                        android.R.anim.fade_out,
                    ).toBundle(),
                )
                store.recordLaunch(app.packageName, app.className)
            }.onFailure {
                Toast.makeText(context, "Unable to open " + app.label, Toast.LENGTH_SHORT).show()
            }
        }
    }
    private fun icon(pkg: String, className: String? = null): Drawable? = iconController.icon(pkg, className)
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
                tag = widget.appWidgetId
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
            // Styled widget slot: a framed card instead of loose grey text, so the
            // empty widget area reads as a place to put something.
            host.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = GradientDrawable().apply {
                    setColor(withAlpha(themeSurface, 150))
                    cornerRadius = dp(20).toFloat()
                    setStroke(dp(1), withAlpha(themeText, 46))
                }
                isClickable = true
                isFocusable = true
                contentDescription = "Add an Android widget"
                addView(TextView(context).apply {
                    text = "＋"
                    textSize = 18f
                    gravity = Gravity.CENTER
                    setTextColor(themeMuted)
                }, LinearLayout.LayoutParams(dp(26), dp(26)))
                addView(TextView(context).apply {
                    text = "Long-press here to add a widget"
                    textSize = 12f
                    gravity = Gravity.CENTER_VERTICAL
                    setTextColor(themeMuted)
                    setSingleLine(true)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                // This placeholder belongs to the page being rendered; never use the
                // globally selected page here because adjacent pages can already be rendered.
                setOnClickListener { hostActivity?.let { widgetController.pickWidget(it, page) } }
            }, FrameLayout.LayoutParams(-1, dp(60), Gravity.CENTER_HORIZONTAL).apply {
                leftMargin = dp(4)
                rightMargin = dp(4)
                topMargin = dp(6)
            })
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
        renderDefaultHomeBanner()
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
        if (requestCode == defaultHomeRequestCode) {
            // Android answered the Home-role request: show or hide the banner.
            renderDefaultHomeBanner()
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

    companion object {
        /** At-a-Glance ticker cadence: often enough to always show the minute. */
        private const val CLOCK_TICK_MILLIS = 15_000L
        private const val CLOCK_FORMAT = "HH:mm"
        private const val DATE_FORMAT = "EEE, MMM d"

        /** Vertical distance that turns a touch into "open All Apps". */
        private const val SWIPE_UP_DISTANCE_DP = 56

        /** Released speed (dp/s) that carries the pager one extra page. */
        private const val PAGER_FLING_VELOCITY_DP = 120

        /** Quiet period after the last scroll event before the pager snaps. */
        private const val PAGER_SNAP_IDLE_MILLIS = 90L

        /** Layout passes to wait for the pager to be measured. */
        private const val MAX_PAGE_WIDTH_SYNC_ATTEMPTS = 3

        /** Spring press feedback: scale down while held, overshoot back to 1f. */
        private const val PRESS_SCALE = 0.93f
        private const val PRESS_IN_MILLIS = 90L
        private const val PRESS_OUT_MILLIS = 240L
        private const val PRESS_SPRING_TENSION = 2.4f

        /** Home / All Apps transition scales. */
        private const val HOME_EXIT_SCALE = 0.94f
        private const val DRAWER_ENTER_SCALE = 0.96f
    }
}
