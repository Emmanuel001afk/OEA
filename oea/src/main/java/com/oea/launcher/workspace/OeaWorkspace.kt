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
import android.util.Log
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
    internal enum class Source { HOME_ITEM, HOME_FOLDER, DOCK, DRAWER, FOLDER_MEMBER, WIDGET }

    /** Where an app tile lives, so popups and drags know what "Remove" means. */
    internal data class TileRef(
        val app: OeaAppInfo? = null,
        val item: OeaWorkspaceStore.Item? = null,
        val folder: OeaWorkspaceStore.Folder? = null,
        val dockIndex: Int = -1,
        val memberOf: String? = null,
        val source: Source
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
    private var hiddenSpaceRef: OeaHiddenAppsSpace? = null
    /** Hidden apps as their own space (drawer ⋮ › Hidden apps). */
    internal val hiddenSpace: OeaHiddenAppsSpace
        get() = hiddenSpaceRef ?: OeaHiddenAppsSpace(this).also { hiddenSpaceRef = it }
    private val main = Handler(Looper.getMainLooper())
    /** Read live: a display-size change updates it without restarting the process. */
    private val density: Float get() = resources.displayMetrics.density
    private var builtForDensity = 0f
    private val launcherApps: LauncherApps? = context.getSystemService(LauncherApps::class.java)

    private val scrim = View(context)

    /**
     * Home's own wallpaper surface, used only when the user chose the OEA
     * wallpaper source (OeaDataStore.WALLPAPER_OEA). It sits under the scrim, so
     * the status-bar gradient still works, and it is the reason a scene can be
     * live: Home draws it itself and Android's wallpaper is never touched.
     */
    private val wallpaperBackdrop = FrameLayout(context)
    private var liveBackdrop: com.oea.launcher.wallpaper.OeaLiveSceneView? = null
    private var backdropKey: String? = null
    /**
     * Ink mode's paper grain and press bloom. Both are created for every mode (they
     * cost nothing while hidden) and shown only while Ink is the mode in use, so
     * switching modes never has to build a view on the main thread.
     */
    private var paperSheet: com.oea.launcher.ui.OeaModeCharacter.PaperOverlay? = null
    private var inkBloom: com.oea.launcher.ui.OeaModeCharacter.InkBloomView? = null
    /** The motion of the mode in use: parallax, trail and press feedback. */
    private var character = com.oea.launcher.ui.OeaModeCharacter.CLASSIC

    /** Home's page position, 0 = first page; drives Depth's parallax. */
    private var pageProgress = 0f

    /**
     * Liquid glass surfaces that answer the finger: the dock and the search bar.
     * [touchedGlass] is whichever the current touch started on, so one gesture
     * never blooms two panes at once.
     */
    private var dockGlass: com.oea.launcher.ui.OeaGlassDrawable? = null
    private var searchGlass: com.oea.launcher.ui.OeaGlassDrawable? = null
    private var searchGlassView: View? = null
    private var touchedGlass: com.oea.launcher.ui.OeaGlassDrawable? = null

    /**
     * Taps are counted here, never dispatched straight from the detector: that is
     * what lets double-tap and triple-tap coexist (see OeaTapRouter).
     */
    private val tapRouter = com.oea.launcher.gesture.OeaTapRouter()
    private var twoFingerDownY = 0f
    private var twoFingerStartX = 0f
    private var twoFingerHandled = false
    private var backdropWidth = 0
    private var backdropHeight = 0
    private val homeLayer = FrameLayout(context)
    private val homeColumn = LinearLayout(context)
    internal val glance = OeaGlanceView(context)
    private val dynamicIsland = OeaDynamicIslandView(context)
    /** The expandable island around the front camera (second live-activity style). */
    private val cameraIsland = OeaDynamicIslandView(context, OeaDynamicIslandView.Surface.CAMERA)
    /** Keeps the card island's collapsed slot in the Home column; it expands over Home. */
    private val islandSpacer = IslandSpacer(context)
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
    // Declared before init: the drawer reports its backdrop while Home is constructed.
    private var drawerWallpaperVisible = false
    private var blurApplied = false
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
        /** Finger position inside the dragged view, so it does not jump under the finger. */
        val offsetX: Float,
        val offsetY: Float,
        /** Widget drags only: the widget being moved and its size in cells. */
        val widgetId: Int = -1,
        val spanX: Int = 1,
        val spanY: Int = 1,
        val sourceView: View? = tile
) {
        var page = -1
        var cell = -1
        var dockSlot = -1
        var dropTarget: String? = null
        /** Widget drags only: top-left cell of the drop target. */
        var cellX = -1
        var cellY = -1
        val isWidget get() = source == Source.WIDGET
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
        addView(wallpaperBackdrop, LayoutParams(-1, -1))
        wallpaperBackdrop.visibility = GONE
        addView(scrim, LayoutParams(-1, -1))

        // Ink mode's two surfaces, in the chrome layer with the scrim: the paper
        // grain sits over the wallpaper and under the icons, and the bloom is drawn
        // on the same layer so a press marks the page rather than the pane.
        val sheet = com.oea.launcher.ui.OeaModeCharacter.PaperOverlay(context).apply { visibility = GONE }
        val bloom = com.oea.launcher.ui.OeaModeCharacter.InkBloomView(context).apply { visibility = GONE }
        paperSheet = sheet
        inkBloom = bloom
        addView(sheet, LayoutParams(-1, -1))
        addView(bloom, LayoutParams(-1, -1))
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

        // Both live-activity surfaces float above the Home column, so expanding
        // them never shrinks the icon grid or resizes widgets.
        homeColumn.addView(islandSpacer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
        homeLayer.addView(dynamicIsland, LayoutParams(-1, -1))
        homeLayer.addView(cameraIsland, LayoutParams(-1, -1))
        dynamicIsland.onReservedHeightChanged = { islandSpacer.requestLayout() }
        cameraIsland.onReservedHeightChanged = {
            // With both styles on, the card sits below the camera island.
            dynamicIsland.setExtraTop(cameraIsland.reservedHeight())
            islandSpacer.requestLayout()
        }
        glance.onOpenOverview = { openSystemTool("multitask") }
        homeColumn.addView(glance, LinearLayout.LayoutParams(-1, -2).apply {
            leftMargin = dp(26)
            rightMargin = dp(20)
            topMargin = dp(18)
        })
        homeColumn.addView(topSearchHost, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        pager.onScroll = { position ->
            indicator.position = position
            pageProgress = position
            // The backdrop drifts against the pages by as much as the mode asks for:
            // Tide's water moves most, Depth's ridges are read as distance, and Ink's
            // paper stays put because a sheet of paper does not slide under its ink.
            liveBackdrop?.parallax = (position * 2f - 1f) * character.parallax
            // A page in motion drags the glass with it: the panes the finger is
            // not touching bend with the swipe and flatten again when it settles.
            val drift = position - Math.round(position)
            val bend = if (Math.abs(drift) < 0.01f) 0f else -drift * 2.6f
            dockGlass?.liquify(bend)
            searchGlass?.liquify(bend * 0.7f)
        }
        pager.onPageChanged = { page -> ws.setCurrentPage(page) }
        pager.effect = appliedPageEffect()
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

        wallpaperBackdrop.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                backdropKey = null
                applyWallpaperBackdrop()
            }
        }
        tapRouter.attach(main)
        tapRouter.onDoubleTap = {
            if (!drawerOpen && drag == null) gesture("double_tap")
        }
        tapRouter.onTripleTap = {
            if (!drawerOpen && drag == null) gesture("triple_tap")
        }
        val homeGestures = GestureDetector(context, emptyGestureListener())
        homeColumn.setOnTouchListener { _, event ->
            homeGestures.onTouchEvent(event)
            trackTwoFinger(event)
            true
        }

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

        /**
         * A confirmed tap only *counts*; the router decides afterwards whether it
         * was a double or a triple tap. GestureDetector's own onDoubleTap is
         * deliberately not used, because it fires too early for a third tap to
         * ever be seen.
         */
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (drag == null && !drawerOpen) tapRouter.tap()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (drag != null || drawerOpen) return
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            val action = store.emptyLongPressAction()
            when (action) {
                "widgets" -> menus.showWidgetPicker(pager.currentPage)
                "settings" -> openSystemTool("")
                "low" -> gesture("long_press")
                else -> menus.showHomeMenu(e.rawX, e.rawY)   // "wallpaper" and anything unexpected
            }
        }
    }

    /**
     * Two-finger swipe. Detected from the pointer count and the axis, so it can
     * never be confused with the one-finger swipe-up (which the pager owns) or
     * with a tap: the fingers must move past the touch slop, and vertically.
     */
    private fun trackTwoFinger(event: MotionEvent) {
        if (store.twoFingerSwipeAction() == "none") return
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    twoFingerHandled = false
                    twoFingerDownY = event.getY(0)
                    twoFingerStartX = event.getX(0)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount < 2 || twoFingerHandled || drawerOpen || drag != null) return
                val dy = event.getY(0) - twoFingerDownY
                val dx = kotlin.math.abs(event.getX(0) - twoFingerStartX)
                if (kotlin.math.abs(dy) > dp(44) && kotlin.math.abs(dy) > dx * 1.4f) {
                    twoFingerHandled = true
                    gesture("two_finger")
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> twoFingerHandled = false
        }
    }

    /** Runs one gesture through the registry, with Home performing it in place. */
    private fun gesture(slot: String): Boolean =
        com.oea.launcher.gesture.OeaGestures.run(context, slot, homeAction = ::performHomeAction)

    /**
     * Liquid glass reacts to the finger before dispatch continues, exactly like a
     * physical surface: the pane under the touch presses in, the specular
     * highlight follows the finger across it, and letting go releases the pane
     * with a short spring. Nothing here consumes the event, so taps, drags and
     * gestures behave exactly as before.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        com.oea.launcher.fault.OeaFaultShield.guard("home:glass-touch") { trackGlass(ev) }
        return super.dispatchTouchEvent(ev)
    }

    private fun trackGlass(event: MotionEvent) {
        if (character.ink) {
            // Ink mode: the press spreads ink on the page instead of pressing glass.
            if (event.actionMasked == MotionEvent.ACTION_DOWN) bloomInk(event.rawX, event.rawY)
            return
        }
        if (store.glassStyle() != OeaDataStore.GLASS_LIQUID) return
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            touchedGlass = glassAt(event.rawX, event.rawY)
            touchedGlass?.press(1f)
        }
        val glass = touchedGlass ?: return
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val fraction = fractionInside(glass, event.rawX)
                if (fraction != null) glass.specular(fraction, 0.12f)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                releaseGlass(glass)
                touchedGlass = null
            }
        }
    }

    /** The liquid pane that contains a screen point, if any. */
    private fun glassAt(rawX: Float, rawY: Float): com.oea.launcher.ui.OeaGlassDrawable? {
        val point = IntArray(2)
        searchGlassView?.let { view ->
            if (view.visibility == VISIBLE && view.width > 0) {
                view.getLocationOnScreen(point)
                if (rawX >= point[0] && rawX <= point[0] + view.width && rawY >= point[1] && rawY <= point[1] + view.height) {
                    return searchGlass
                }
            }
        }
        dockGlass?.let {
            dockFrame.getLocationOnScreen(point)
            if (dockFrame.width > 0 && rawX >= point[0] && rawX <= point[0] + dockFrame.width &&
                rawY >= point[1] && rawY <= point[1] + dockFrame.height
            ) {
                return it
            }
        }
        return null
    }

    /** Where a screen x sits inside a pane, 0..1, or null when the pane is gone. */
    private fun fractionInside(glass: com.oea.launcher.ui.OeaGlassDrawable, rawX: Float): Float? {
        val view = if (glass === searchGlass) searchGlassView else dockFrame
        if (view == null || view.width <= 0) return null
        val point = IntArray(2)
        view.getLocationOnScreen(point)
        return ((rawX - point[0]) / view.width).coerceIn(0f, 1f)
    }

    /**
     * Starts an ink bloom at a screen point. The point is moved into the bloom
     * view's own coordinates, so the stain appears under the finger wherever the
     * workspace happens to sit on screen.
     */
    private fun bloomInk(rawX: Float, rawY: Float) {
        val view = inkBloom ?: return
        if (view.visibility != VISIBLE || view.width <= 0) return
        val point = IntArray(2)
        view.getLocationOnScreen(point)
        view.bloom(rawX - point[0], rawY - point[1], dp(150).toFloat())
    }

    /** Springs a pane back to rest instead of snapping it. */
    private fun releaseGlass(glass: com.oea.launcher.ui.OeaGlassDrawable) {
        val animator = ValueAnimator.ofFloat(1f, 0f).setDuration(180L)
        animator.interpolator = DecelerateInterpolator(2.2f)
        animator.addUpdateListener { glass.press(it.animatedValue as Float) }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) = glass.reset()
        })
        animator.start()
    }

    private fun searchPill(): View {
        val glass = if (store.glassStyle() == OeaDataStore.GLASS_LIQUID) {
            com.oea.launcher.ui.OeaGlassDrawable(palette.glassStrong, dp(28).toFloat(), density, palette.accent)
        } else null
        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), 0, dp(8), 0)
            background = kit.ripple(
                glass ?: kit.rounded(palette.glassStrong, 28, palette.glassStroke, 1),
                28
)
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
        // The ripple draws inside the glass, so the surface itself blooms on touch.
        if (glass != null) {
            searchGlass = glass
            searchGlassView = pill
        } else {
            searchGlass = null
            searchGlassView = null
        }
        return pill
    }

    /** Re-applies colours to every piece of chrome (not the pages themselves). */
    /**
     * Page motion is a real battery cost, so Low mode overrides the user's choice
     * while it is on and restores it the moment it ends.
     */
    /** True when the mode's own glass is a liquid pane (so it can carry light). */
    private fun liquidGlassRequested(): Boolean = store.glassStyle() == OeaDataStore.GLASS_LIQUID

    private fun appliedPageEffect(): String =
        if (com.oea.launcher.perf.OeaLowMode.active(context)) "none" else store.pageEffect()

    private fun applyChrome() {
        kit.palette = palette
        appLoadStatus.setTextColor(palette.text)
        appLoadStatus.background = kit.rounded(palette.cardHigh, 22, palette.stroke, 1)
        dynamicIsland.setPalette(palette)
        cameraIsland.setPalette(palette)
        scrim.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(palette.scrimTop, Color.TRANSPARENT, Color.TRANSPARENT, palette.scrimBottom)
)
        indicator.color = palette.homeText

        // ---- the mode's character: its motion, its paper and its press feedback
        character = com.oea.launcher.ui.OeaModeCharacter.current(context)
        val lowMode = com.oea.launcher.perf.OeaLowMode.active(context)
        paperSheet?.visibility = if (character.paper && !lowMode) VISIBLE else GONE
        inkBloom?.visibility = if (character.ink && !lowMode) VISIBLE else GONE
        dockFrame.elevation = dp(character.lift).toFloat()
        // The pager reads its effect from the mode, so a mode change is felt in the
        // very next swipe instead of waiting for a rebuild.
        pager.effect = appliedPageEffect()

        val liquid = store.glassStyle() == OeaDataStore.GLASS_LIQUID
        dockFrame.background = when (store.dockStyle()) {
            "none" -> {
                dockGlass = null
                null
            }
            "solid" -> {
                dockGlass = null
                kit.rounded(palette.glassStrong, 30)
            }
            else -> if (liquid) {
                com.oea.launcher.ui.OeaGlassDrawable(palette.glass, dp(30).toFloat(), density, palette.accent)
                    .also { dockGlass = it }
            } else {
                dockGlass = null
                kit.rounded(palette.glass, 30, palette.glassStroke, 1)
            }
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
        // Moving light inside the glass is a Liquid/Tide trait. It is applied here,
        // after the panes above have been (re)built, and switched off in Low mode
        // and for every mode that does not ask for it — no frame is spent drawing
        // light nobody asked to see.
        val caustics = character.caustics && !lowMode && liquidGlassRequested()
        dockGlass?.setCaustics(caustics)
        searchGlass?.setCaustics(caustics)

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
        val camera = topCutout(insets)
        dynamicIsland.setTopArea(insetTop, camera)
        cameraIsland.setTopArea(insetTop, camera)
        drawer.applyInsets(insetTop, max(insetBottom, imeBottom))
        (dropBar.layoutParams as? LayoutParams)?.let {
            it.topMargin = insetTop + dp(6)
            dropBar.layoutParams = it
        }
        menus.onInsetsChanged(insetTop, max(insetBottom, imeBottom))
    }

    /** The display cutout at the top of the screen (the front camera), in window coordinates. */
    private fun topCutout(insets: WindowInsets): Rect? {
        if (Build.VERSION.SDK_INT < 28) return null
        val rects = runCatching { insets.displayCutout?.boundingRects }.getOrNull().orEmpty()
        val screenWidth = resources.displayMetrics.widthPixels
        return rects
            .filter { it.top <= dp(8) && it.width() in 1 until screenWidth / 2 }
            .minByOrNull { kotlin.math.abs(it.centerX() - screenWidth / 2) }
            ?.let { Rect(it) }
    }

    /** Collapses any expanded live-activity surface; true when one was open. */
    private fun collapseIslands(): Boolean {
        val open = dynamicIsland.isExpanded || cameraIsland.isExpanded
        dynamicIsland.collapse()
        cameraIsland.collapse()
        return open
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
        // First start (nothing cached yet): a branded card instead of a bare label.
        val logo = if (retry == null) context.getDrawable(R.drawable.oea_logo)?.apply { setBounds(0, 0, dp(56), dp(56)) } else null
        appLoadStatus.compoundDrawablePadding = dp(12)
        appLoadStatus.setCompoundDrawablesRelative(null, logo, null, null)
        appLoadStatus.isClickable = retry != null
        appLoadStatus.isFocusable = retry != null
        appLoadStatus.setOnClickListener { retry?.invoke() }
        appLoadStatus.visibility = VISIBLE
    }

    fun attachHost(activity: Activity?) {
        hostActivity = activity
        widgets.setHostActivity(activity)
        updateSystemBars()
        // Display size (density) changes recreate the Home activity but not
        // this process-wide view tree; rebuild so sizes match the new density.
        if (builtForDensity != 0f && builtForDensity != density) {
            builtForDensity = density
            applyChrome()
            scheduleRebuild()
        }
        if (builtForDensity == 0f) builtForDensity = density
        if (activity != null && !store.onboardingDone()) {
            main.postDelayed({ if (hostActivity != null && !store.onboardingDone()) menus.showOnboarding() }, 700L)
        }
    }

    /**
     * Home's window is going away for good. The workspace itself is
     * process-scoped and survives, so only the transient host is released:
     * keeping a destroyed Activity would leak it and crash the next widget or
     * dialog callback that reaches for it.
     */
    fun detachHost() {
        hostActivity = null
        widgets.setHostActivity(null)
    }

    /**
     * Rebuilds Home in place after a contained fault. Transient surfaces are
     * closed first so a half-open drawer, resize frame or popup cannot survive
     * into the new render, then the saved workspace is bound again.
     *
     * @return true when a usable Home came back, so the fault shield can stop
     *   there instead of escalating to an activity recreate.
     */
    fun recoverSurface(): Boolean {
        // Success means "the whole re-render ran without faulting". An empty app
        // list is not a broken surface: the engine shows its own retry pill for
        // that, and judging recovery by the rebuild's own result keeps the two
        // problems apart.
        val recovered = com.oea.launcher.fault.OeaFaultShield.value("home:recover", false) {
            onHostPaused()
            rebuild()
            true
        }
        if (recovered) com.oea.launcher.fault.OeaFaultShield.noteHomeRendered()
        toastRecovering(!recovered)
        return recovered
    }

    private fun toastRecovering(failed: Boolean) {
        val activity = hostActivity ?: return
        runCatching {
            android.widget.Toast.makeText(
                activity,
                if (failed) "Home had a problem and could not be refreshed; your layout is saved."
                else "Home recovered from a problem. Nothing was lost.",
                android.widget.Toast.LENGTH_SHORT
).show()
        }
    }

    /** Memory pressure: hand back caches that are cheap to rebuild. */
    fun onHostTrimMemory(level: Int) {
        val aggressive = level >= android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE
        runCatching { com.oea.launcher.icons.OeaIconController.shared(context).trimMemory(aggressive) }
        if (aggressive) {
            closeWidgetResize()
            collapseIslands()
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
        com.oea.engine.core.OeaGuard.start("oea-wallpaper-migrate", "home:wallpaper") {
            runCatching {
                context.contentResolver.openInputStream(Uri.parse(uri))?.use { stream ->
                    WallpaperManager.getInstance(context).setStream(stream, null, true, WallpaperManager.FLAG_SYSTEM)
                }
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        migrateLegacyWallpaper()
        if (!com.oea.launcher.fault.OeaFaultShield.safeMode) widgets.start()
        store.registerListener(prefsListener)
        OeaNotificationState.addListener(notificationListener)
        registerWallpaperListener()
        runCatching { WallpaperManager.getInstance(context).setWallpaperOffsets(windowToken, 0.5f, 0.5f) }
        requestApplyInsets()
    }

    override fun onDetachedFromWindow() {
        runCatching { widgets.stop() }
        store.unregisterListener(prefsListener)
        OeaNotificationState.removeListener(notificationListener)
        unregisterWallpaperListener()
        super.onDetachedFromWindow()
    }

    fun onHostResumed() {
        resumed = true
        // While Home is in front, Home is the screen that reports contained faults
        // (the shield stays quiet for anyone else).
        com.oea.launcher.fault.OeaFaultShield.setAlertPresenter { record -> onFaultForHome(record); true }
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
        applySafeModeVisibility()
        applyWallpaperBackdrop()
        glance.refresh()
        dynamicIsland.refresh()
        cameraIsland.refresh()
        renderBanner()
        rebindNotificationListener()
        updateSystemBars()
    }

    /**
     * Decides what sits behind Home's icons.
     *
     * With Android's wallpaper source the backdrop stays invisible and Home's
     * window is transparent over the real wallpaper, exactly as before. With the
     * OEA source, Home paints the scene itself: a live one (Tide, Depth, Ink,
     * Aurora) is drawn frame by frame, a still one is rendered once per size, and
     * Low mode or safe mode freezes anything animated so power saving is real.
     */
    private fun applyWallpaperBackdrop() {
        val source = if (store.wallpaperSource() == OeaDataStore.WALLPAPER_OEA &&
            !com.oea.launcher.fault.OeaFaultShield.safeMode
        ) store.homeScene() else null
        if (source == null) {
            liveBackdrop?.release()
            liveBackdrop = null
            backdropKey = null
            wallpaperBackdrop.visibility = GONE
            return
        }
        wallpaperBackdrop.visibility = VISIBLE
        val frozen = com.oea.launcher.perf.OeaLowMode.active(context)
        if (com.oea.launcher.wallpaper.OeaLiveScenes.isLive(source)) {
            val existing = liveBackdrop
            if (existing != null && existing.sceneId == source) {
                existing.animated = !frozen
                return
            }
            existing?.release()
            wallpaperBackdrop.removeAllViews()
            val view = com.oea.launcher.wallpaper.OeaLiveScenes.view(context, source)
            view.animated = !frozen
            view.parallax = (pageProgress * 2f - 1f) * character.parallax
            liveBackdrop = view
            backdropKey = source
            wallpaperBackdrop.addView(view, LayoutParams(-1, -1))
            return
        }
        liveBackdrop?.release()
        liveBackdrop = null
        val scene = com.oea.launcher.wallpaper.OeaWallpaper.byId(source) ?: return
        val width = wallpaperBackdrop.width
        val height = wallpaperBackdrop.height
        if (width <= 0 || height <= 0) {
            // Not measured yet: keep the previous frame and try again after layout.
            wallpaperBackdrop.post { applyWallpaperBackdrop() }
            return
        }
        val dim = com.oea.launcher.wallpaper.OeaWallpaperStore.dimPercent(context)
        val key = "${scene.id}:${width}x$height:$dim"
        if (key == backdropKey) return
        backdropKey = key
        // Half the screen resolution is plenty for a gradient scene and keeps the
        // render off the critical path of Home's first frame.
        val renderWidth = (width / 2).coerceIn(360, com.oea.launcher.wallpaper.OeaWallpaperRenderer.MAX_EDGE)
        val renderHeight = (renderWidth.toFloat() / width * height).toInt().coerceAtLeast(360)
        val bitmap = com.oea.engine.core.OeaGuard.call("home:backdrop", null as android.graphics.Bitmap?) {
            com.oea.launcher.wallpaper.OeaWallpaperRenderer.render(scene, renderWidth, renderHeight)
        } ?: return
        wallpaperBackdrop.removeAllViews()
        wallpaperBackdrop.background = android.graphics.drawable.BitmapDrawable(resources, bitmap)
        if (dim > 0) {
            wallpaperBackdrop.addView(
                View(context).apply { setBackgroundColor(android.graphics.Color.argb(dim * 255 / 100, 0, 0, 0)) },
                LayoutParams(-1, -1)
)
        }
    }

    /**
     * Safe mode is OEA's own "start without the optional parts" switch, entered
     * automatically after repeated faults and available in OEA Settings. Only the
     * surfaces that can legitimately be missing are hidden; the grid, dock,
     * folders and drawer (the launcher's actual job) always stay.
     */
    private fun applySafeModeVisibility() {
        val safe = com.oea.launcher.fault.OeaFaultShield.safeMode
        // Low mode hides the same optional surfaces as safe mode, plus the live
        // activities: they poll and animate, which is exactly what it is for.
        val light = safe || com.oea.launcher.perf.OeaLowMode.active(context)
        val optional = if (light) GONE else VISIBLE
        if (glance.visibility != optional) glance.visibility = optional
        if (dynamicIsland.visibility != optional) dynamicIsland.visibility = optional
        if (cameraIsland.visibility != optional) cameraIsland.visibility = optional
    }

    fun onHostPaused() {
        resumed = false
        com.oea.launcher.fault.OeaFaultShield.setAlertPresenter(null)
        menus.dismissPopups()
        closeWidgetResize()
        collapseIslands()
        if (drag != null) finishDrag(0f, 0f, cancelled = true)
        if (drawerOpen && drawer.hasQuery()) closeDrawerOnPause = true
    }

    /** Home pressed. Close what is open; from a clean Home, glide back to page one. */
    fun onHomeIntent(alreadyOnHome: Boolean) {
        val hadResize = closeWidgetResize()
        val hadIsland = collapseIslands()
        val hadHidden = hiddenSpaceRef?.close(animate = alreadyOnHome) == true
        val hadOverlay = menus.closeAll() || hadResize || hadIsland || hadHidden
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

    /**
     * Applies a settings change with the smallest update that reflects it.
     *
     * Every change used to rebuild all Home pages and recreate every widget
     * host view, so unrelated writes (island options, gestures, the layout
     * lock, OEA theme bookkeeping) made Home visibly reload. Only settings that
     * change the grid or the tiles themselves rebuild the pages now.
     */
    private fun onPreferenceChanged(key: String?) {
        if (key == null) return
        when (preferenceImpact(key)) {
            PrefImpact.NONE -> Unit
            PrefImpact.ISLAND -> {
                main.removeCallbacks(islandRefresh)
                main.postDelayed(islandRefresh, 80L)
            }
            PrefImpact.DRAWER -> {
                main.removeCallbacks(drawerRefresh)
                main.postDelayed(drawerRefresh, 80L)
            }
            PrefImpact.CHROME -> {
                main.removeCallbacks(chromeRefresh)
                main.postDelayed(chromeRefresh, 100L)
            }
            PrefImpact.FULL -> {
                main.removeCallbacks(themeRefresh)
                main.postDelayed(themeRefresh, 120L)
            }
        }
    }

    private enum class PrefImpact { NONE, ISLAND, DRAWER, CHROME, FULL }

    private fun preferenceImpact(key: String): PrefImpact = when {
        key.startsWith("launch_count:") -> PrefImpact.NONE
        key in QUIET_KEYS -> PrefImpact.NONE
        key == "dynamic_island_enabled" || key.startsWith("island_") -> PrefImpact.ISLAND
        key in DRAWER_KEYS -> PrefImpact.DRAWER
        key in CHROME_KEYS -> PrefImpact.CHROME
        else -> PrefImpact.FULL
    }

    private val islandRefresh = Runnable {
        dynamicIsland.refresh()
        cameraIsland.refresh()
        islandSpacer.requestLayout()
    }

    private val drawerRefresh = Runnable {
        drawer.applyPalette()
        if (drawer.visibility == VISIBLE) drawer.refresh()
        hiddenSpaceRef?.refresh()
    }

    private val chromeRefresh = Runnable {
        palette = OeaTheme.resolve(context)
        applyChrome()
        // Toggling Low mode from Settings must be visible at once, without
        // rebuilding the pages: this is the same pass that runs on resume.
        applySafeModeVisibility()
        glance.applyStyle()
        glance.refresh()
        pager.effect = appliedPageEffect()
        backdropKey = null
        applyWallpaperBackdrop()
    }

    private val themeRefresh = Runnable {
        palette = OeaTheme.resolve(context)
        applyChrome()
        glance.applyStyle()
        glance.refresh()
        pager.effect = appliedPageEffect()
        rebuild()
    }

    private fun registerWallpaperListener() {
        if (Build.VERSION.SDK_INT < 27 || wallpaperListener != null) return
        val listener = WallpaperManager.OnColorsChangedListener { _, which ->
            if ((which and WallpaperManager.FLAG_SYSTEM) != 0) {
                // The system wallpaper changed, so OEA's own scene may no longer be
                // the one on screen: re-check before the theme follows it.
                runCatching { com.oea.launcher.wallpaper.OeaWallpaperStore.syncWithSystem(context) }
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

    /** Space for the collapsed live-activity surfaces at the top of the Home column. */
    private inner class IslandSpacer(context: Context) : View(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), cameraIsland.reservedHeight() + dynamicIsland.reservedHeight())
        }
    }

    // ==================================================================== data

    /**
     * Binds an app catalogue. Only an [authoritative] catalogue (a live
     * PackageManager query) may seed the first layout or prune Home items of
     * apps that are gone; the persisted catalogue used for instant cold starts
     * only draws what it knows.
     */
    fun bind(value: List<OeaAppInfo>, authoritative: Boolean = true) {
        apps = value
        appsByKey = value.associateBy { appKey(it) }
        catalogueAuthoritative = authoritative
        if (authoritative && !ws.isSeeded() && value.isNotEmpty()) {
            runCatching { seedLayout() }.onFailure { Log.e(TAG, "First-run Home layout could not be seeded", it) }
        }
        rebuild()
    }

    /** The live catalogue matched the cached one exactly; it can now be trusted for pruning. */
    fun markCatalogueAuthoritative() {
        catalogueAuthoritative = true
    }

    private var catalogueAuthoritative = false

    /**
     * True when Home has a catalogue and at least one built page, i.e. the
     * user is looking at a working launcher. The engine uses this to decide
     * whether a refresh problem deserves a visible warning at all.
     */
    fun hasUsableHome(): Boolean = apps.isNotEmpty() && pager.childCount > 0

    /** Runs one optional rebuild stage; a failure is logged instead of aborting Home. */
    private inline fun stage(name: String, block: () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            Log.e(TAG, "Home $name failed; continuing with the rest of Home", error)
        }
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
            appFor(Intent(Intent.ACTION_VIEW).setType("image/*"))
).forEach { dock += it }
        val oeaApps = visible.filter { it.packageName == context.packageName && it !in dock }
        val rest = visible.filter { it !in dock && it !in oeaApps }
        rest.filter { it.packageName != context.packageName }.take(OeaWorkspaceStore.DOCK_SLOTS - dock.size).forEach { if (dock.size < OeaWorkspaceStore.DOCK_SLOTS) dock += it }
        val remaining = rest.filter { it !in dock }
        val essentials = listOfNotNull(
            byPackage["com.android.vending"]?.firstOrNull(),
            appFor(Intent(Settings.ACTION_SETTINGS)),
            appFor(Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS))
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

    /** One widget's cell rectangle on a page, as currently laid out. */
    internal data class WidgetRect(val id: Int, val page: Int, val x: Int, val y: Int, val spanX: Int, val spanY: Int) {
        fun overlaps(ox: Int, oy: Int, osx: Int, osy: Int) = x < ox + osx && ox < x + spanX && y < oy + osy && oy < y + spanY
        fun coversCell(cell: Int, cols: Int) = (cell % cols) in x until x + spanX && (cell / cols) in y until y + spanY
    }

    /** Widget rectangles from the latest normalization; icons can not use these cells. */
    private var widgetRects: List<WidgetRect> = emptyList()

    internal fun widgetRect(id: Int): WidgetRect? = widgetRects.firstOrNull { it.id == id }

    private fun isWidgetCell(page: Int, cell: Int): Boolean {
        val cols = cols()
        return widgetRects.any { it.page == page && it.coversCell(cell, cols) }
    }

    /** Rows on [page] that contain at least one widget cell. */
    private fun widgetRowsOnPage(page: Int): Int =
        widgetRects.filter { it.page == page }.flatMap { it.y until it.y + it.spanY }.toSet().size.coerceAtMost(rows())

    /** Laid-out cell size in dp, or null before Home has been measured. */
    internal fun measuredCellDp(): Pair<Float, Float>? {
        val page = currentPageLayout() ?: (pager.pageAt(0) as? OeaCellLayout)
        if (page == null || page.width <= 0 || page.height <= 0) return null
        // Use the page's size with the *current* grid: during a rebuild the old
        // page views may still carry the previous column/row count.
        val w = (page.width - page.paddingLeft - page.paddingRight).toFloat() / cols()
        val h = (page.height - page.paddingTop - page.paddingBottom).toFloat() / rows()
        if (w <= 0f || h <= 0f) return null
        return w / density to h / density
    }

    /** Cell size in dp, estimated from the screen when Home has not been measured yet. */
    internal fun cellDp(): Pair<Float, Float> = measuredCellDp() ?: run {
        val metrics = resources.displayMetrics
        val widthDp = (metrics.widthPixels / density - 12f) / cols()
        (widthDp.coerceAtLeast(48f)) to cellHeightDp().toFloat()
    }

    internal fun widgetLimits(id: Int): OeaWidgetController.SpanLimits? {
        val info = widgets.info(id) ?: return null
        val (cw, ch) = cellDp()
        return widgets.spanLimits(info, cw, ch, cols(), rows())
    }

    internal fun defaultWidgetSpans(info: android.appwidget.AppWidgetProviderInfo): Pair<Int, Int> {
        val (cw, ch) = cellDp()
        return widgets.defaultSpans(info, cw, ch, cols(), rows())
    }

    /**
     * Gives every widget a valid, non-overlapping rectangle for the current grid.
     *
     * - Stored placements are kept, clamped to the grid and to the provider's
     *   resize limits (so a widget is never squeezed below its minimum size).
     * - Legacy full-width bands are migrated in their stored order, keeping the
     *   layout the user already had.
     * - New widgets go to the first area free of widgets and icons, on their
     *   page or the next one; only when no empty area exists do they push icons.
     */
    private fun normalizeWidgets(items: List<OeaWorkspaceStore.Item>, folders: List<OeaWorkspaceStore.Folder>) {
        val cols = cols()
        val rows = rows()
        val measured = measuredCellDp()
        val iconCells = HashMap<Int, MutableSet<Int>>()
        items.forEach { iconCells.getOrPut(it.page) { mutableSetOf() } += it.cell }
        folders.forEach { iconCells.getOrPut(it.page) { mutableSetOf() } += it.cell }
        val placed = mutableListOf<WidgetRect>()

        fun fits(page: Int, x: Int, y: Int, sx: Int, sy: Int) =
            page in 0 until OeaWorkspaceStore.MAX_PAGES && x >= 0 && y >= 0 && x + sx <= cols && y + sy <= rows &&
                placed.none { it.page == page && it.overlaps(x, y, sx, sy) }

        fun iconFree(page: Int, x: Int, y: Int, sx: Int, sy: Int): Boolean {
            val taken = iconCells[page] ?: return true
            for (cy in y until y + sy) for (cx in x until x + sx) if ((cy * cols + cx) in taken) return false
            return true
        }

        fun spotOn(page: Int, sx: Int, sy: Int, avoidIcons: Boolean): Pair<Int, Int>? {
            for (y in 0..(rows - sy)) for (x in 0..(cols - sx)) {
                if (fits(page, x, y, sx, sy) && (!avoidIcons || iconFree(page, x, y, sx, sy))) return x to y
            }
            return null
        }

        fun findSpot(startPage: Int, sx: Int, sy: Int, preferEmpty: Boolean): Triple<Int, Int, Int>? {
            if (preferEmpty) {
                for (page in startPage until OeaWorkspaceStore.MAX_PAGES) spotOn(page, sx, sy, avoidIcons = true)?.let { return Triple(page, it.first, it.second) }
            }
            spotOn(startPage, sx, sy, avoidIcons = false)?.let { return Triple(startPage, it.first, it.second) }
            for (page in startPage + 1 until OeaWorkspaceStore.MAX_PAGES) spotOn(page, sx, sy, avoidIcons = false)?.let { return Triple(page, it.first, it.second) }
            return null
        }

        val legacyNextRow = HashMap<Int, Int>()
        widgets.ids().forEach { id ->
            val info = widgets.info(id) ?: return@forEach
            val stored = widgets.placement(id)
            val legacy = stored == null && widgets.isLegacyBand(id)
            val page = (stored?.page ?: widgets.pageFor(id)).coerceIn(0, OeaWorkspaceStore.MAX_PAGES - 1)
            var x: Int
            var y: Int
            var sx: Int
            var sy: Int
            when {
                stored != null -> { x = stored.x; y = stored.y; sx = stored.spanX; sy = stored.spanY }
                legacy -> { x = 0; y = legacyNextRow[page] ?: widgetRowsOnPage(page); sx = cols; sy = widgets.spanFor(id) }
                else -> {
                    val requested = widgets.requestedSpanX(id)
                    val defaults = defaultWidgetSpans(info)
                    sx = if (requested > 0) requested else defaults.first
                    sy = if (requested > 0) widgets.spanFor(id) else defaults.second
                    x = -1
                    y = -1
                }
            }
            sx = sx.coerceIn(1, cols)
            sy = sy.coerceIn(1, rows)
            if (measured != null) {
                // Only enforce provider limits with a real cell size; an
                // estimate could otherwise grow widgets that already fit.
                val limits = widgets.spanLimits(info, measured.first, measured.second, cols, rows)
                sx = sx.coerceIn(limits.minX, limits.maxX).coerceAtMost(cols)
                sy = sy.coerceIn(limits.minY, limits.maxY).coerceAtMost(rows)
            }
            if (x >= 0) x = x.coerceIn(0, cols - sx)
            if (y >= 0) y = y.coerceIn(0, rows - sy)
            if (legacy) legacyNextRow[page] = y + sy

            var targetPage = page
            if (x < 0 || y < 0 || !fits(page, x, y, sx, sy)) {
                val spot = findSpot(page, sx, sy, preferEmpty = stored == null && !legacy)
                if (spot == null) {
                    Log.w(TAG, "No room on Home for widget $id (${sx}x$sy); it stays saved but hidden")
                    return@forEach
                }
                targetPage = spot.first
                x = spot.second
                y = spot.third
            }
            placed += WidgetRect(id, targetPage, x, y, sx, sy)
            if (stored == null || stored.page != targetPage || stored.x != x || stored.y != y || stored.spanX != sx || stored.spanY != sy) {
                widgets.setPlacement(id, targetPage, x, y, sx, sy)
            }
        }
        widgetRects = placed
    }

    /**
     * Keeps the stored layout valid for the current grid: remaps cells after a
     * grid change, resolves overlaps, keeps icons out of widget cells and drops
     * apps that were uninstalled.
     */
    private fun normalizeLayout() {
        val cols = cols()
        val rows = rows()
        if (apps.isNotEmpty() && catalogueAuthoritative) pruneUninstalled()
        pruneHiddenApps()
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
        // Widgets claim their cells first; icons then flow around them.
        normalizeWidgets(items, folders)

        var pages = ws.pages()
        val capacity = cols * rows
        val occupied = HashMap<Int, MutableSet<Int>>()
        fun isFree(page: Int, cell: Int) = cell in 0 until capacity && !isWidgetCell(page, cell) && occupied[page]?.contains(cell) != true
        fun take(page: Int, cell: Int) { occupied.getOrPut(page) { mutableSetOf() } += cell }
        fun findFree(startPage: Int): Pair<Int, Int> {
            var page = startPage.coerceAtLeast(0)
            while (page < OeaWorkspaceStore.MAX_PAGES) {
                // Fill from the bottom rows up: icons sit close to the thumb.
                for (cell in capacity - 1 downTo 0) if (isFree(page, cell)) return page to cell
                page++
            }
            return -1 to -1
        }
        /** An icon pushed off its cell (e.g. by a widget) moves to the closest free cell on its own page first. */
        fun findNear(page: Int, cell: Int): Pair<Int, Int> {
            if (page in 0 until OeaWorkspaceStore.MAX_PAGES && cell in 0 until capacity) {
                val near = (0 until capacity).filter { isFree(page, it) }.minByOrNull { c ->
                    val dx = c % cols - cell % cols
                    val dy = c / cols - cell / cols
                    dx * dx + dy * dy
                }
                if (near != null) return page to near
            }
            return findFree(page)
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
            val (page, cell) = findNear(f.page, f.cell)
            if (page >= 0) { take(page, cell); placedFolders += f.copy(page = page, cell = cell) }
        }
        pendingItems.forEach { i ->
            val (page, cell) = findNear(i.page, i.cell)
            if (page >= 0) { take(page, cell); placedItems += i.copy(page = page, cell = cell) }
        }
        val maxUsed = (placedItems.map { it.page } + placedFolders.map { it.page } +
            widgetRects.map { it.page }).maxOrNull() ?: 0
        pages = max(pages, maxUsed + 1).coerceIn(1, OeaWorkspaceStore.MAX_PAGES)
        if (placedItems != ws.items()) ws.replaceItems(placedItems)
        if (placedFolders != ws.folders()) ws.replaceFolders(placedFolders)
        ws.setPages(pages)
        ws.setLayoutGrid(cols, rows)
    }

    private fun pruneHiddenApps() {
        if (apps.isEmpty()) return
        val hiddenKeys = apps.filter { store.isHidden(it.packageName, it.className) }.map { appKey(it) }.toSet()
        if (hiddenKeys.isEmpty()) return
        val items = ws.items().filterNot { !it.isShortcut && it.id in hiddenKeys }
        if (items.size != ws.items().size) ws.replaceItems(items)
        val dock = ws.dock().filterNot { it in hiddenKeys }
        if (dock.size != ws.dock().size) ws.setDock(dock)
        val folders = ws.folders().mapNotNull { folder ->
            val remaining = folder.members.filterNot { it in hiddenKeys }
            when {
                remaining.isEmpty() -> null
                remaining.size < 2 && !folder.isPair -> null
                remaining.size != folder.members.size -> folder.copy(members = remaining)
                else -> folder
            }
        }
        if (folders.size != ws.folders().size || folders.any { f -> f.members.size != ws.folders().firstOrNull { it.id == f.id }?.members?.size }) {
            ws.replaceFolders(folders)
        }
    }

    /**
     * Removes Home, dock and folder entries only for apps that are really
     * gone. A launcher query can transiently omit apps (profile switch, an
     * app briefly disabling its launcher activity, a partial OEM reply); those
     * entries are kept and simply reappear, instead of being deleted from the
     * user's layout as before.
     */
    private fun pruneUninstalled() {
        val known = appsByKey.keys
        val referenced = LinkedHashSet<String>()
        ws.items().filterNot { it.isShortcut }.forEach { referenced += it.id }
        referenced += ws.dock()
        ws.folders().forEach { referenced += it.members }
        val unknown = referenced.filter { it !in known }
        val pm = context.packageManager
        val stillThere = unknown.filter { key ->
            val pkg = key.substringBefore('/')
            val cls = key.substringAfter('/', "")
            cls.isNotBlank() && runCatching { pm.getActivityInfo(ComponentName(pkg, cls), 0); true }.getOrDefault(false)
        }.toSet()
        val installedPackages = HashSet<String>()
        apps.forEach { installedPackages += it.packageName }
        ws.items().filter { it.isShortcut && it.packageName !in installedPackages }.forEach { item ->
            if (runCatching { pm.getApplicationInfo(item.packageName, 0); true }.getOrDefault(false)) installedPackages += item.packageName
        }
        if (unknown.isEmpty() && ws.items().none { it.isShortcut && it.packageName !in installedPackages }) return
        if (stillThere.isNotEmpty()) Log.i(TAG, "Keeping ${stillThere.size} Home entries whose apps are installed but not listed right now")
        ws.clearMissing(known + stillThere, installedPackages)
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
        stage("layout normalization") { normalizeLayout() }
        homeTiles.clear()
        val target = ws.getCurrentPage()
        pager.removeAllViews()
        for (page in 0 until ws.pages()) {
            val built = try {
                buildPage(page)
            } catch (error: Throwable) {
                // One broken page must not empty the whole pager.
                Log.e(TAG, "Home page $page failed to build; showing it empty", error)
                emptyPage(page)
            }
            pager.addView(built)
        }
        pager.setPageImmediately(target)
        indicator.pages = ws.pages()
        indicator.position = target.toFloat()
        stage("dock") { renderDock() }
        stage("drawer refresh") { if (drawer.visibility == VISIBLE) drawer.refresh() }
        stage("open folder refresh") { menus.refreshOpenFolder() }
        stage("hidden apps refresh") { hiddenSpaceRef?.refresh() }
    }

    private fun emptyPage(page: Int): OeaCellLayout {
        val layout = OeaCellLayout(context)
        layout.cols = cols()
        layout.rows = rows()
        layout.page = page
        layout.setPadding(dp(6), 0, dp(6), 0)
        layout.highlightColor = OeaTheme.alpha(palette.homeText, 0x2E)
        layout.emptyGestures = GestureDetector(context, emptyGestureListener())
        return layout
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
        // Each widget/icon is isolated: one broken provider or tile must not
        // drop the rest of the page.
        widgetRects.filter { it.page == page }.forEach { rect ->
            stage("widget ${rect.id}") {
                val host = widgets.createView(context, rect.id) ?: return@stage
                val frame = WidgetFrame(context, rect.id, host)
                layout.addView(frame, OeaCellLayout.LayoutParams(rect.x, rect.y, rect.spanX, rect.spanY))
            }
        }
        ws.folders().filter { it.page == page }.forEach { folder ->
            stage("folder ${folder.id}") {
                val tile = OeaTileView(context)
                bindFolderTile(tile, folder, TileStyle.HOME)
                tile.listener = homeListener
                layout.addView(tile, OeaCellLayout.LayoutParams(folder.cell % cols(), folder.cell / cols()))
                homeTiles += tile
            }
        }
        ws.items().filter { it.page == page }.forEach { item ->
            stage("item ${item.id}") {
                val tile = OeaTileView(context)
                if (item.isShortcut) {
                    bindShortcutTile(tile, item)
                } else {
                    val app = appsByKey[item.id] ?: return@stage
                    bindTile(tile, app, TileStyle.HOME)
                    tile.payload = TileRef(app = app, item = item, source = Source.HOME_ITEM)
                }
                tile.listener = homeListener
                layout.addView(tile, OeaCellLayout.LayoutParams(item.cell % cols(), item.cell / cols()))
                homeTiles += tile
            }
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

    /**
     * Home presents contained faults itself rather than letting them pass in
     * silence: a bar at the top names what failed and offers the log.
     *
     * This is the answer to "something misbehaved and I have no idea what it was":
     * the fault was always recorded, it was never *shown*. The bar replaces the
     * default Home banner for a few seconds and then gives the space back.
     */
    private val faultBar = Runnable {
        val record = lastFault
        if (record == null || !resumed) return@Runnable
        bannerHost.removeAllViews()
        bannerHost.visibility = VISIBLE
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(9), dp(8), dp(9))
            background = kit.rounded(palette.glassStrong, 22, OeaTheme.alpha(palette.warning, 0x66), 1)
        }
        bar.addView(ImageView(context).apply { setImageDrawable(kit.icon(R.drawable.oic_warning_fill, palette.warning)) },
            LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(10) })
        bar.addView(TextView(context).apply {
            text = "OEA contained a problem in ${record.subsystemLabel()}"
            textSize = 13f
            typeface = OeaType.medium
            maxLines = 2
            setTextColor(palette.homeText)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(kit.button("Details", palette.accent, palette.onAccent) {
            openSystemTool("stability")
            dismissFaultBar()
        }.apply { setPadding(dp(14), dp(6), dp(14), dp(6)) }, LinearLayout.LayoutParams(-2, -2))
        bar.addView(ImageView(context).apply {
            setImageDrawable(kit.icon(R.drawable.oic_close, palette.homeTextSecondary))
            setPadding(dp(9), dp(9), dp(9), dp(9))
            setOnClickListener { dismissFaultBar() }
        }, LinearLayout.LayoutParams(dp(38), dp(38)))
        bannerHost.addView(bar, LayoutParams(-1, -2).apply { leftMargin = dp(14); rightMargin = dp(14); topMargin = dp(2); bottomMargin = dp(6) })
        main.removeCallbacks(faultBarHide)
        main.postDelayed(faultBarHide, 9_000L)
    }

    private val faultBarHide = Runnable { dismissFaultBar() }

    private fun dismissFaultBar() {
        main.removeCallbacks(faultBar)
        main.removeCallbacks(faultBarHide)
        lastFault = null
        renderBanner()
    }

    private var lastFault: com.oea.launcher.fault.OeaFaultRecord? = null

    /** Called on the main thread for every contained fault while Home is in front. */
    private fun onFaultForHome(record: com.oea.launcher.fault.OeaFaultRecord) {
        if (!resumed) return
        lastFault = record
        main.removeCallbacks(faultBar)
        // Give a fault that arrives during start-up a moment: Home's own surface
        // must be up before a bar is placed on it.
        main.postDelayed(faultBar, 400L)
    }

    private fun renderBanner() {
        bannerHost.removeAllViews()
        if (lastFault != null && resumed) {
            faultBar.run()
            return
        }
        if (com.oea.launcher.fault.OeaFaultShield.safeMode) {
            bannerHost.visibility = VISIBLE
            bannerHost.addView(safeModeCard(), LayoutParams(-1, -2).apply { leftMargin = dp(14); rightMargin = dp(14); topMargin = dp(2); bottomMargin = dp(6) })
            return
        }
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

    /** Shown while safe mode is on, with the way out of it. */
    private fun safeModeCard(): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(8), dp(10))
            background = kit.rounded(palette.glassStrong, 22, palette.glassStroke, 1)
        }
        card.addView(ImageView(context).apply { setImageDrawable(kit.icon(R.drawable.oic_shield, palette.warning)) },
            LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(12) })
        card.addView(TextView(context).apply {
            text = "Home is in safe mode"
            textSize = 14f
            typeface = OeaType.medium
            setTextColor(palette.homeText)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(kit.button("Fix", palette.accent, palette.onAccent) {
            val shield = com.oea.launcher.fault.OeaFaultShield
            // Never let recovery work escape this click listener and kill Home.
            // Restore safe mode if rebuilding the core visibility state fails;
            // widget startup is optional and must not block launcher recovery.
            shield.setSafeMode(false, context)
            val visibilityReady = runCatching { applySafeModeVisibility() }
                .onFailure { shield.note("safe-mode:fix:visibility", it) }
                .isSuccess
            if (visibilityReady) {
                runCatching { widgets.start() }
                    .onFailure { shield.note("safe-mode:fix:widgets", it) }
            } else {
                shield.setSafeMode(true, context)
                runCatching { applySafeModeVisibility() }
                    .onFailure { shield.note("safe-mode:fix:fallback", it) }
            }
            val rebuilt = runCatching { scheduleRebuild() }
                .onFailure { shield.note("safe-mode:fix:rebuild", it) }
                .isSuccess
            if (!rebuilt) {
                shield.setSafeMode(true, context)
                runCatching { applySafeModeVisibility() }
                    .onFailure { shield.note("safe-mode:fix:rebuild-fallback", it) }
            }
        }.apply { setPadding(dp(14), dp(8), dp(14), dp(8)) }, LinearLayout.LayoutParams(-2, -2))
        card.setOnClickListener {
            runCatching {
                context.startActivity(Intent(context, com.oea.launcher.OeaSystemToolsActivity::class.java)
                    .putExtra(com.oea.launcher.OeaSystemToolsActivity.EXTRA_SCREEN, "stability")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        return card
    }

    internal fun isDefaultHome(): Boolean {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = context.getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) return rm.isRoleHeld(RoleManager.ROLE_HOME)
        }
        val info = context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY
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
        if (closeWidgetResize()) return true
        if (collapseIslands()) return true
        if (menus.closeAll()) return true
        if (hiddenSpaceRef?.close() == true) return true
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
                options
)
            true
        }.getOrDefault(false)
        if (launched) {
            store.recordLaunch(app.packageName, app.className)
            if (drawerOpen) closeDrawerOnPause = true
            // A selected game opened from OEA starts its Game Boost session
            // directly; nothing watches the foreground while no game runs.
            runCatching { com.oea.launcher.gameboost.OeaGameBoost.onAppLaunched(context, app.packageName) }
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

    private fun onHomeSwipeDown() {
        // Bound through the gesture registry, so a swipe-down and any other
        // gesture can never end up sharing an action without the warning.
        val action = store.swipeDownAction()
        when (action) {
            "notifications" -> if (!com.oea.launcher.gesture.OeaGestures.expandNotifications(context)) {
                menus.showAssistantNeeded("Swipe down for notifications")
            }
            "quick_settings" -> if (!com.oea.launcher.gesture.OeaGestures.expandQuickSettings(context)) {
                menus.showAssistantNeeded("Swipe down for Quick Settings")
            }
            "silence", "volume", "low" -> gesture("swipe_down")
            else -> gesture("swipe_down")
        }
    }

    /**
     * Quick controls, shown *inside* Home.
     *
     * This used to launch an activity, which on a phone where Android refused the
     * background start (or where the activity itself faulted) looked exactly like
     * "quick controls threw me out of the launcher". A sheet drawn in Home's own
     * overlay cannot do that: the launcher stays in front, and the panel is one
     * more view that can be dismissed by tapping anywhere else.
     *
     * The activity still exists for the Quick Settings tile and for Settings; it
     * builds the same sheet, so there is no second copy of the behaviour.
     */
    private fun openQuickControls() {
        val anchor = Rect(0, height - dp(160), width, height - dp(80))
        val card = menus.panel(widthDp = 320) { host ->
            host.addView(
                com.oea.launcher.power.OeaQuickControls.sheet(context, palette) { menus.dismissPopups() },
                LinearLayout.LayoutParams(-1, -2)
)
        }
        menus.showPanel(anchor, card) { menus.dismissPopups() }
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
        collapseIslands()
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
            updateBackdropBlur()
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

    /** Called by the drawer when its background starts or stops showing the wallpaper. */
    internal fun onDrawerBackdropChanged(wallpaperVisible: Boolean) {
        drawerWallpaperVisible = wallpaperVisible
        updateBackdropBlur()
    }

    /**
     * Frosts the wallpaper behind All Apps and search with Android's window
     * blur (Android 12+, only where the device enables cross-window blur;
     * many low-power phones and Battery Saver turn it off). Applied once
     * when the drawer opens and removed when it closes, never per frame.
     */
    private fun updateBackdropBlur() {
        val window = hostActivity?.window ?: return
        if (Build.VERSION.SDK_INT < 31) return
        val wm = context.getSystemService(android.view.WindowManager::class.java) ?: return
        val want = drawerOpen && drawerWallpaperVisible && store.wallpaperBlur() &&
            runCatching { wm.isCrossWindowBlurEnabled }.getOrDefault(false)
        if (want == blurApplied) return
        blurApplied = want
        runCatching {
            val attrs = window.attributes
            if (want) {
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                attrs.blurBehindRadius = dp(28)
            } else {
                attrs.blurBehindRadius = 0
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            }
            window.attributes = attrs
        }.onFailure { Log.w(TAG, "Window blur unavailable", it) }
    }

    internal fun openHiddenApps() {
        menus.dismissPopups()
        hiddenSpace.open()
    }

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
                    updateBackdropBlur()
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
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && drag == null) {
            // A tap anywhere else on Home closes an expanded live activity.
            dynamicIsland.collapseIfOutside(ev.rawX, ev.rawY)
            cameraIsland.collapseIfOutside(ev.rawX, ev.rawY)
        }
        if (drag != null) return true
        if (drawerOpen || drawerAnimator != null || menus.hasOverlay() || resizeFrame != null || hiddenSpaceRef?.isOpen == true) return false
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
                val slop = touchSlop * store.gestureSlopScale()
                if (abs(dy) > slop * 1.2f && abs(dy) > abs(dx) * 1.3f) {
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
                } else if (abs(dx) > slop * 1.5f) {
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
                // The user's touch-gesture sensitivity sets how strong a fling
                // has to be to dismiss the drawer.
                val flingOpen = -900f * store.gestureSlopScale()
                val open = vy < flingOpen || (vy < 600f && progress > 0.3f)
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
        closeWidgetResize()
        val bitmap = snapshot(tile)
        val view = ImageView(context).apply { setImageBitmap(bitmap) }
        overlay.addView(view, LayoutParams(bitmap.width, bitmap.height))
        val state = DragState(source, key, tile, view, ref, bitmap.width / 2f, bitmap.height / 2f)
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

    /**
     * Widgets drag like icons: long-press, then move. The widget keeps its
     * size, can cross pages at the screen edges and lands on any free area.
     */
    private fun beginWidgetDrag(frame: WidgetFrame, rawX: Float, rawY: Float) {
        if (drag != null) return
        if (store.homeLayoutLocked()) {
            menus.dismissPopups()
            toast("Home layout is locked. Unlock it in OEA Settings › Home screen.")
            return
        }
        val rect = widgetRect(frame.widgetId) ?: return
        menus.dismissPopups()
        closeWidgetResize()
        val bitmap = snapshotView(frame)
        val view = ImageView(context).apply {
            setImageBitmap(bitmap)
            elevation = dp(10).toFloat()
        }
        overlay.addView(view, LayoutParams(bitmap.width, bitmap.height))
        val loc = IntArray(2)
        frame.getLocationOnScreen(loc)
        val state = DragState(
            Source.WIDGET, "widget:${frame.widgetId}", null, view, TileRef(source = Source.WIDGET),
            offsetX = (rawX - loc[0]).coerceIn(0f, bitmap.width.toFloat()),
            offsetY = (rawY - loc[1]).coerceIn(0f, bitmap.height.toFloat()),
            widgetId = frame.widgetId, spanX = rect.spanX, spanY = rect.spanY, sourceView = frame
)
        drag = state
        frame.alpha = 0.25f
        positionDragView(state, rawX, rawY)
        view.alpha = 0.92f
        view.animate().scaleX(1.03f).scaleY(1.03f).setDuration(150L).start()
        showDropBar(Source.WIDGET, state.ref)
        requestDisallowInterceptTouchEvent(false)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun snapshotView(view: View): Bitmap {
        val w = view.width.coerceAtLeast(1)
        val h = view.height.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        runCatching { view.draw(Canvas(bitmap)) }
        return bitmap
    }

    private fun positionDragView(state: DragState, rawX: Float, rawY: Float) {
        val loc = IntArray(2)
        overlay.getLocationOnScreen(loc)
        state.view.x = rawX - loc[0] - state.offsetX
        state.view.y = rawY - loc[1] - state.offsetY
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
        clearPageHighlights()
        dockGrid.highlightCell = -1
        d.page = -1
        d.cell = -1
        d.dockSlot = -1
        d.cellX = -1
        d.cellY = -1
        if (target != null) {
            cancelFlip()
            return
        }
        if (d.isWidget) {
            widgetDragMove(d, rawX, rawY)
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
        if (cell >= 0 && !isWidgetCell(pager.currentPage, cell)) {
            d.page = pager.currentPage
            d.cell = cell
            page.highlightCell = cell
        }
    }

    private fun clearPageHighlights() {
        currentPageLayout()?.let {
            it.highlightCell = -1
            it.highlightRect = null
        }
    }

    /**
     * Finds the cell rectangle under a dragged widget. Other widgets block a
     * drop; icons in the way are moved to the nearest free cells on drop.
     */
    private fun widgetDragMove(d: DragState, rawX: Float, rawY: Float) {
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
        val cols = cols()
        val rows = rows()
        val cw = page.cellWidth().toFloat()
        val ch = page.cellHeight().toFloat()
        val left = rawX - d.offsetX - pageRect.left - page.paddingLeft
        val top = rawY - d.offsetY - pageRect.top - page.paddingTop
        val x = Math.round(left / cw).coerceIn(0, (cols - d.spanX).coerceAtLeast(0))
        val y = Math.round(top / ch).coerceIn(0, (rows - d.spanY).coerceAtLeast(0))
        if (d.spanX > cols || d.spanY > rows) return
        val blocked = widgetRects.any { it.id != d.widgetId && it.page == pager.currentPage && it.overlaps(x, y, d.spanX, d.spanY) }
        if (blocked) return
        d.page = pager.currentPage
        d.cellX = x
        d.cellY = y
        page.highlightRect = Rect(x, y, x + d.spanX, y + d.spanY)
    }

    private fun dropWidget(d: DragState) {
        val current = widgetRect(d.widgetId) ?: return
        if (d.page == current.page && d.cellX == current.x && d.cellY == current.y) return
        widgets.setPlacement(d.widgetId, d.page, d.cellX, d.cellY, d.spanX, d.spanY)
        // The next normalization moves any icon under the new rectangle to the
        // closest free cell, the way Android launchers make room for a widget.
        ws.setCurrentPage(d.page)
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
        clearPageHighlights()
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
        clearPageHighlights()
        dockGrid.highlightCell = -1
        d.tile?.dragging = false
        d.sourceView?.alpha = 1f
        d.view.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).setDuration(140L).withEndAction { overlay.removeView(d.view) }.start()
        if (!cancelled && d.isWidget) {
            when {
                d.dropTarget == "remove" -> widgets.remove(d.widgetId)
                d.page >= 0 && d.cellX >= 0 && d.cellY >= 0 -> dropWidget(d)
            }
        } else if (!cancelled) {
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
                Source.DRAWER, Source.WIDGET -> Unit
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
            val taken = (edit.items.filter { it.page == page }.map { it.cell } + edit.folders.filter { it.page == page }.map { it.cell }).toSet()
            val cols = cols()
            targetCell = (0 until cols * rows()).filter { it !in taken && !isWidgetCell(page, it) }
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

    /** True when the app already has its own icon on a Home page (folders and the dock don't count). */
    internal fun isHomeIcon(key: String): Boolean = ws.items().any { it.id == key }

    /** Places an app (or pinned shortcut) in the first free cell from the current page on. */
    internal fun addToHome(item: OeaWorkspaceStore.Item): Boolean {
        val cols = cols()
        val capacity = cols * rows()
        val edit = Edit()
        var page = pager.currentPage.coerceAtLeast(0)
        while (page < OeaWorkspaceStore.MAX_PAGES) {
            val taken = (edit.items.filter { it.page == page }.map { it.cell } + edit.folders.filter { it.page == page }.map { it.cell }).toSet()
            val free = (capacity - 1 downTo 0).firstOrNull { it !in taken && !isWidgetCell(page, it) }
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
            kind = OeaWorkspaceStore.KIND_SHORTCUT, shortcutId = info.id, label = shortcuts.label(info)
)
        if (addToHome(item)) toast("Shortcut added to Home")
    }

    /** Called by the pin-request screen once Android accepted a pinned widget. */
    fun addPinnedWidget(id: Int) {
        val info = widgets.info(id)
        val (spanX, spanY) = if (info != null) defaultWidgetSpans(info) else cols() to 2
        widgets.commitPinned(id, pager.currentPage.coerceAtLeast(0), spanY, spanX)
        rebuild()
        widgetRect(id)?.let { if (it.page != pager.currentPage) pager.snapToPage(it.page) }
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
            val taken = (edit.items.filter { it.page == page }.map { it.cell } + edit.folders.filter { it.page == page }.map { it.cell }).toSet()
            val free = (capacity - 1 downTo 0).firstOrNull { it !in taken && !isWidgetCell(page, it) }
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
    fun performHomeAction(action: String): Boolean {
        when {
            action == "widgets" -> menus.showWidgetPicker(pager.currentPage)
            action == "drawer" -> openDrawer()
            action == "search" -> openDrawer(searchFocus = true)
            action == "hidden" -> openHiddenApps()
            action == "quick_controls" -> openQuickControls()
            action == "notifications" -> if (!com.oea.launcher.gesture.OeaGestures.expandNotifications(context)) {
                menus.showAssistantNeeded("Notifications")
            }
            action == "quick_settings" -> if (!com.oea.launcher.gesture.OeaGestures.expandQuickSettings(context)) {
                menus.showAssistantNeeded("Quick Settings")
            }
            action == "wallpaper" -> openSystemTool("wallpaper")
            // Home is a View, so the launch goes through the same guarded helper
            // every other OEA launch uses (it falls back to the application context).
            action == "themes" -> startSafely(
                Intent(context, com.oea.launcher.themes.OeaThemesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
)
            action == "settings" -> openSystemTool("")
            // Stability screen: rebuild in place, or restart Home's window.
            action == "rebuild" -> {
                val rebuilt = recoverSurface()
                toast(if (rebuilt) "Home rebuilt from your saved layout" else "Home could not be rebuilt; try Restart OEA Home")
            }

            action == "restart" -> hostActivity?.recreate()
            action.startsWith(HOME_ACTION_ADD_APPS) -> addAppsFromSettings(action.removePrefix(HOME_ACTION_ADD_APPS))
            else -> return false
        }
        return true
    }

    /** "pkg/cls,pkg/cls" from OEA Settings: give each app its own Home icon. */
    private fun addAppsFromSettings(keys: String) {
        val chosen = keys.split(',').map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull(appsByKey::get)
        if (chosen.isEmpty()) {
            toast("Those apps aren't available yet")
            return
        }
        var added = 0
        chosen.forEach { app ->
            if (!isHomeIcon(appKey(app)) && addToHome(OeaWorkspaceStore.Item(appKey(app), app.packageName, app.className, 0, 0))) added++
        }
        toast(when (added) {
            0 -> "Already on Home"
            1 -> "Added ${chosen.first().label} to Home"
            else -> "Added $added apps to Home"
        })
    }

    fun createPairFromSettings(a: OeaAppInfo, b: OeaAppInfo) = createPair(a, b)

    /**
     * Top-left cell of the first [spanX]×[spanY] area on [page] that no other
     * widget uses; with [avoidIcons] the area must also be free of icons.
     */
    private fun freeWidgetSpot(page: Int, spanX: Int, spanY: Int, avoidIcons: Boolean, ignoreId: Int = -1): Pair<Int, Int>? {
        val cols = cols()
        val rows = rows()
        if (spanX > cols || spanY > rows) return null
        val taken: Set<Int> = if (!avoidIcons) emptySet() else
            (ws.items().filter { it.page == page }.map { it.cell } + ws.folders().filter { it.page == page }.map { it.cell }).toSet()
        for (y in 0..(rows - spanY)) for (x in 0..(cols - spanX)) {
            if (widgetRects.any { it.id != ignoreId && it.page == page && it.overlaps(x, y, spanX, spanY) }) continue
            var free = true
            if (avoidIcons) {
                loop@ for (cy in y until y + spanY) for (cx in x until x + spanX) {
                    if ((cy * cols + cx) in taken) { free = false; break@loop }
                }
            }
            if (free) return x to y
        }
        return null
    }

    /** True when [page] has an area of [spanX]×[spanY] cells free of widgets and icons. */
    internal fun pageHasRoomForWidget(page: Int, spanX: Int, spanY: Int): Boolean =
        freeWidgetSpot(page, spanX, spanY, avoidIcons = true) != null

    /** Moves a widget to another page: an empty area if there is one, else icons make room. */
    internal fun moveWidgetToPage(id: Int, page: Int) {
        val rect = widgetRect(id) ?: return
        val target = page.coerceIn(0, OeaWorkspaceStore.MAX_PAGES - 1)
        val spot = freeWidgetSpot(target, rect.spanX, rect.spanY, avoidIcons = true, ignoreId = id)
            ?: freeWidgetSpot(target, rect.spanX, rect.spanY, avoidIcons = false, ignoreId = id)
        if (spot == null) {
            toast("There isn't room for this widget on page ${target + 1}")
            return
        }
        widgets.setPlacement(id, target, spot.first, spot.second, rect.spanX, rect.spanY)
        if (target >= ws.pages()) ws.setPages(target + 1)
        ws.setCurrentPage(target)
        rebuild()
        pager.snapToPage(target)
    }

    internal fun removeWidget(id: Int) {
        closeWidgetResize()
        widgets.remove(id)
        rebuild()
    }

    // ===================================================== widget containers

    /**
     * Widget wrapper, handled like an app icon: a long-press shows the
     * widget's actions, and moving the finger after the long-press picks the
     * widget up so it can be dragged anywhere on Home, including other pages.
     *
     * The provider's content is clipped to the widget's own cells, so a widget
     * can never draw over neighbouring icons, and every size change is reported
     * to the provider so it can switch to a layout that fits.
     */
    private inner class WidgetFrame(context: Context, val widgetId: Int, val host: AppWidgetHostView) : FrameLayout(context) {
        private var downRawX = 0f
        private var downRawY = 0f
        private var longPressed = false
        private var dragStarted = false
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
            clipChildren = true
            clipToPadding = true
            addView(host, LayoutParams(-1, -1).apply {
                val m = dp(WIDGET_INSET_DP)
                setMargins(m, m, m, m)
            })
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            val inset = dp(WIDGET_INSET_DP) * 2
            val wDp = ((w - inset) / density).toInt()
            val hDp = ((h - inset) / density).toInt()
            if (wDp > 0 && hDp > 0 && (wDp != reportedW || hDp != reportedH)) {
                reportedW = wDp
                reportedH = hDp
                widgets.reportSize(host, wDp, hDp)
                widgets.setSize(widgetId, wDp, hDp)
            }
        }

        private fun movedBeyondSlop(rawX: Float, rawY: Float) =
            abs(rawX - downRawX) > touchSlop || abs(rawY - downRawY) > touchSlop

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    longPressed = false
                    dragStarted = false
                    removeCallbacks(longPress)
                    postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!longPressed && movedBeyondSlop(ev.rawX, ev.rawY)) removeCallbacks(longPress)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(longPress)
            }
            return longPressed
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                // Nothing inside the widget took this touch. Keep the gesture
                // here so the long-press timer is cancelled on lift, instead of
                // falling through to Home's empty-space gestures.
                MotionEvent.ACTION_DOWN -> return true
                MotionEvent.ACTION_MOVE -> {
                    if (longPressed && !dragStarted && movedBeyondSlop(event.rawX, event.rawY)) {
                        dragStarted = true
                        beginWidgetDrag(this, event.rawX, event.rawY)
                    } else if (!longPressed && movedBeyondSlop(event.rawX, event.rawY)) {
                        removeCallbacks(longPress)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(longPress)
                    longPressed = false
                    dragStarted = false
                }
            }
            return true
        }

        override fun onDetachedFromWindow() {
            removeCallbacks(longPress)
            super.onDetachedFromWindow()
        }
    }

    // ======================================================= widget resizing

    private var resizeFrame: WidgetResizeFrame? = null

    /** Shows Android-style resize handles around a widget; tap outside to finish. */
    internal fun startWidgetResize(id: Int) {
        closeWidgetResize()
        menus.dismissPopups()
        if (store.homeLayoutLocked()) {
            toast("Home layout is locked. Unlock it in OEA Settings › Home screen.")
            return
        }
        val rect = widgetRect(id) ?: return
        val limits = widgetLimits(id) ?: return
        if (!limits.horizontal && !limits.vertical) {
            toast("This widget can't be resized")
            return
        }
        if (pager.currentPage != rect.page) pager.setPageImmediately(rect.page)
        val frame = WidgetResizeFrame(context, id)
        overlay.addView(frame, LayoutParams(-1, -1))
        resizeFrame = frame
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    internal fun closeWidgetResize(): Boolean {
        val frame = resizeFrame ?: return false
        resizeFrame = null
        overlay.removeView(frame)
        return true
    }

    /**
     * Resize frame with a handle on each edge the provider allows. Sizes snap
     * to whole cells and stay inside the provider's min/max resize limits, so
     * a widget is laid out at a size it supports instead of being squeezed.
     * Other widgets block the frame; icons move aside when the size is applied.
     */
    private inner class WidgetResizeFrame(context: Context, val widgetId: Int) : View(context) {
        private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val stroke = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
        }
        private val handleFill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val handleStroke = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
        }
        private val box = android.graphics.RectF()
        private val cells = Rect()
        private val startCells = Rect()
        private var activeHandle = HANDLE_NONE
        private var downX = 0f
        private var downY = 0f
        private var limits: OeaWidgetController.SpanLimits? = null

        init {
            isClickable = true
            contentDescription = "Resize widget. Drag a handle, then tap outside to finish."
            refreshFromLayout()
        }

        private fun refreshFromLayout() {
            val rect = widgetRect(widgetId) ?: return
            cells.set(rect.x, rect.y, rect.x + rect.spanX, rect.y + rect.spanY)
            limits = widgetLimits(widgetId)
            invalidate()
        }

        /** Pixel box of [cells] in this view's coordinates; false until the page is laid out. */
        private fun computeBox(): Boolean {
            val page = currentPageLayout() ?: return false
            if (page.width <= 0 || page.height <= 0) {
                postInvalidateOnAnimation()
                return false
            }
            val pageLoc = IntArray(2)
            page.getLocationOnScreen(pageLoc)
            val myLoc = IntArray(2)
            getLocationOnScreen(myLoc)
            val left = (pageLoc[0] - myLoc[0] + page.paddingLeft).toFloat()
            val top = (pageLoc[1] - myLoc[1] + page.paddingTop).toFloat()
            val cw = page.cellWidth().toFloat()
            val ch = page.cellHeight().toFloat()
            box.set(left + cells.left * cw, top + cells.top * ch, left + cells.right * cw, top + cells.bottom * ch)
            box.inset(dp(3).toFloat(), dp(3).toFloat())
            return true
        }

        override fun onDraw(canvas: Canvas) {
            if (!computeBox()) return
            val accent = palette.accent
            fill.color = OeaTheme.alpha(accent, 0x26)
            stroke.color = accent
            val radius = dp(20).toFloat()
            canvas.drawRoundRect(box, radius, radius, fill)
            canvas.drawRoundRect(box, radius, radius, stroke)
            val l = limits ?: return
            handleFill.color = Color.WHITE
            handleStroke.color = accent
            if (l.horizontal) {
                drawHandle(canvas, box.left, box.centerY())
                drawHandle(canvas, box.right, box.centerY())
            }
            if (l.vertical) {
                drawHandle(canvas, box.centerX(), box.top)
                drawHandle(canvas, box.centerX(), box.bottom)
            }
        }

        private fun drawHandle(canvas: Canvas, cx: Float, cy: Float) {
            val r = dp(9).toFloat()
            canvas.drawCircle(cx, cy, r, handleFill)
            canvas.drawCircle(cx, cy, r, handleStroke)
        }

        private fun handleAt(x: Float, y: Float): Int {
            val l = limits ?: return HANDLE_NONE
            val reach = dp(30).toFloat()
            val candidates = mutableListOf<Pair<Int, Float>>()
            fun consider(handle: Int, cx: Float, cy: Float) {
                val d = kotlin.math.hypot(x - cx, y - cy)
                if (d <= reach) candidates += handle to d
            }
            if (l.horizontal) {
                consider(HANDLE_LEFT, box.left, box.centerY())
                consider(HANDLE_RIGHT, box.right, box.centerY())
            }
            if (l.vertical) {
                consider(HANDLE_TOP, box.centerX(), box.top)
                consider(HANDLE_BOTTOM, box.centerX(), box.bottom)
            }
            return candidates.minByOrNull { it.second }?.first ?: HANDLE_NONE
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!computeBox()) return true
                    activeHandle = handleAt(event.x, event.y)
                    if (activeHandle == HANDLE_NONE) {
                        // Like Android launchers: a tap outside the frame finishes resizing.
                        if (!box.contains(event.x, event.y)) closeWidgetResize()
                        return true
                    }
                    downX = event.x
                    downY = event.y
                    startCells.set(cells)
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> if (activeHandle != HANDLE_NONE) updateFromDrag(event.x - downX, event.y - downY)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (activeHandle != HANDLE_NONE) {
                    activeHandle = HANDLE_NONE
                    commit()
                }
            }
            return true
        }

        private fun bounded(value: Int, low: Int, high: Int, fallback: Int) =
            if (low <= high) value.coerceIn(low, high) else fallback

        private fun updateFromDrag(dx: Float, dy: Float) {
            val page = currentPageLayout() ?: return
            val l = limits ?: return
            val cols = cols()
            val rows = rows()
            val stepX = Math.round(dx / page.cellWidth().coerceAtLeast(1))
            val stepY = Math.round(dy / page.cellHeight().coerceAtLeast(1))
            val next = Rect(startCells)
            when (activeHandle) {
                HANDLE_LEFT -> next.left = bounded(startCells.left + stepX, (startCells.right - l.maxX).coerceAtLeast(0), startCells.right - l.minX, startCells.left)
                HANDLE_RIGHT -> next.right = bounded(startCells.right + stepX, startCells.left + l.minX, (startCells.left + l.maxX).coerceAtMost(cols), startCells.right)
                HANDLE_TOP -> next.top = bounded(startCells.top + stepY, (startCells.bottom - l.maxY).coerceAtLeast(0), startCells.bottom - l.minY, startCells.top)
                HANDLE_BOTTOM -> next.bottom = bounded(startCells.bottom + stepY, startCells.top + l.minY, (startCells.top + l.maxY).coerceAtMost(rows), startCells.bottom)
            }
            if (next == cells) return
            val rect = widgetRect(widgetId) ?: return
            val blocked = widgetRects.any { it.id != widgetId && it.page == rect.page && it.overlaps(next.left, next.top, next.width(), next.height()) }
            if (blocked) return
            cells.set(next)
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            invalidate()
        }

        private fun commit() {
            val rect = widgetRect(widgetId) ?: return
            if (cells.left == rect.x && cells.top == rect.y && cells.width() == rect.spanX && cells.height() == rect.spanY) return
            widgets.setPlacement(widgetId, rect.page, cells.left, cells.top, cells.width(), cells.height())
            rebuild()
            refreshFromLayout()
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
        /** Home action prefix: "add_apps:pkg/cls,pkg/cls". */
        const val HOME_ACTION_ADD_APPS = "add_apps:"
        private const val TAG = "OeaWorkspace"
        /** Settings writes that never change what Home shows. */
        private val QUIET_KEYS = setOf(
            "recent_launches", "last_command", "last_result", "onboarding_done", "default_home_banner_dismissed",
            "home_layout_locked", "haptics", "gesture_swipe_down", "gesture_double_tap", "recents_mode",
            "drawer_keyboard", "floating_window_size", "hidden_space_locked", "favorite_apps", "home_surface_enabled",
            "wallpaper_uri", "theme_active_id", "wallpaper_blur"
)
        /** Settings that only change the app drawer and search. */
        private val DRAWER_KEYS = setOf(
            "drawer_mode", "sort_mode", "drawer_categories", "show_most_used", "drawer_wallpaper_background",
            "search_wallpaper_visible"
)
        /** Settings that restyle Home's chrome (dock, search bar, glance, glass, page motion) without new pages. */
        private val CHROME_KEYS = setOf("dock_style", "search_bar_position", "glance_style", "glass_style", "page_effect", "low_mode_enabled")
        /** Breathing room between a widget and its cell edges. */
        private const val WIDGET_INSET_DP = 6
        private const val HANDLE_NONE = 0
        private const val HANDLE_LEFT = 1
        private const val HANDLE_TOP = 2
        private const val HANDLE_RIGHT = 3
        private const val HANDLE_BOTTOM = 4
    }
}
