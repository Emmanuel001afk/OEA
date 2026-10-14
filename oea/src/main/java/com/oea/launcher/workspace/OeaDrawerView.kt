package com.oea.launcher.workspace

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.oea.launcher.R
import com.oea.launcher.data.OeaDataStore
import com.oea.launcher.model.OeaAppInfo
import com.oea.launcher.search.OeaSearchAction
import com.oea.launcher.ui.OeaTheme
import com.oea.launcher.ui.OeaType
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/**
 * OEA app drawer: universal search (apps, calculator, settings, contacts and
 * web), smart categories, suggestions, an A–Z rail, grid or list layout and
 * drag-down-to-close.
 */
@SuppressLint("ViewConstructor")
class OeaDrawerView(context: Context, private val ws: OeaWorkspace) : FrameLayout(context) {
    private sealed class Row {
        data class Label(val text: String) : Row()
        data class Apps(val apps: List<OeaAppInfo>) : Row()
        data class ListApp(val app: OeaAppInfo) : Row()
        data class Action(val action: OeaSearchAction) : Row()
        data class Empty(val text: String) : Row()
    }

    private val density = resources.displayMetrics.density
    private val column = LinearLayout(context)
    private val handle = View(context)
    private val searchBox = LinearLayout(context)
    val search = EditText(context)
    private val clearButton = ImageView(context)
    private val moreButton = ImageView(context)
    private val chipsScroll = HorizontalScrollView(context)
    private val chips = LinearLayout(context)
    private val listArea = FrameLayout(context)
    private val list = ListView(context)
    private val rail = AlphabetRail(context)
    private val bubble = TextView(context)
    private var rows: List<Row> = emptyList()
    private var letterRows: List<Pair<Char, Int>> = emptyList()
    private var category = "all"
    private var topInset = 0
    private var bottomInset = 0
    private val adapter = DrawerAdapter()
    /** Last applied backdrop; declared before init because init applies the palette. */
    private var backdropKey: String? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downY = 0f
    private var downX = 0f
    private var closing = false
    private var velocity: VelocityTracker? = null

    init {
        isClickable = true
        isFocusable = true
        column.orientation = LinearLayout.VERTICAL
        addView(column, LayoutParams(-1, -1))

        column.addView(handle, LinearLayout.LayoutParams(dp(36), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(10)
            bottomMargin = dp(10)
        })

        searchBox.orientation = LinearLayout.HORIZONTAL
        searchBox.gravity = Gravity.CENTER_VERTICAL
        searchBox.setPadding(dp(16), 0, dp(6), 0)
        searchBox.addView(ImageView(context).apply { tag = "searchIcon" }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(12) })
        search.apply {
            hint = "Search apps, contacts, settings & web"
            background = null
            textSize = 16f
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setPadding(0, 0, 0, 0)
        }
        searchBox.addView(search, LinearLayout.LayoutParams(0, -1, 1f))
        clearButton.apply {
            contentDescription = "Clear search"
            setPadding(dp(9), dp(9), dp(9), dp(9))
            visibility = GONE
            setOnClickListener { search.text?.clear() }
        }
        searchBox.addView(clearButton, LinearLayout.LayoutParams(dp(40), dp(40)))
        moreButton.apply {
            contentDescription = "Drawer options"
            setPadding(dp(9), dp(9), dp(9), dp(9))
            setOnClickListener { ws.showDrawerMenu(this) }
        }
        searchBox.addView(moreButton, LinearLayout.LayoutParams(dp(40), dp(40)))
        column.addView(searchBox, LinearLayout.LayoutParams(-1, dp(52)).apply {
            leftMargin = dp(16)
            rightMargin = dp(16)
        })

        chips.orientation = LinearLayout.HORIZONTAL
        chips.setPadding(dp(16), 0, dp(16), 0)
        chipsScroll.apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            addView(chips)
        }
        column.addView(chipsScroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        list.apply {
            divider = null
            dividerHeight = 0
            selector = ColorDrawable(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            clipToPadding = false
            cacheColorHint = Color.TRANSPARENT
            adapter = this@OeaDrawerView.adapter
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) {
                    if (scrollState == AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL) hideKeyboard()
                }
                override fun onScroll(view: AbsListView?, firstVisibleItem: Int, visibleItemCount: Int, totalItemCount: Int) = Unit
            })
        }
        listArea.addView(list, LayoutParams(-1, -1))
        rail.onLetter = { letter, y -> jumpTo(letter, y) }
        rail.onRelease = { bubble.animate().alpha(0f).setDuration(160L).start() }
        listArea.addView(rail, LayoutParams(dp(26), -1, Gravity.END).apply { topMargin = dp(8); bottomMargin = dp(8) })
        bubble.apply {
            textSize = 30f
            typeface = OeaType.medium
            gravity = Gravity.CENTER
            alpha = 0f
        }
        listArea.addView(bubble, LayoutParams(dp(72), dp(72), Gravity.END or Gravity.TOP).apply { rightMargin = dp(44) })
        column.addView(listArea, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(6) })

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                clearButton.visibility = if (s.isNullOrEmpty()) GONE else VISIBLE
                rebuild()
                applyBackdrop()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        search.setOnFocusChangeListener { _, _ -> applyBackdrop() }
        search.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                launchFirstResult()
                true
            } else false
        }
        applyPalette()
    }

    fun applyPalette() {
        val p = ws.palette
        applyBackdrop()
        handle.background = ws.kit.rounded(OeaTheme.alpha(p.textSecondary, 0x55), 3)
        searchBox.background = if (ws.store.glassStyle() == OeaDataStore.GLASS_LIQUID) {
            com.oea.launcher.ui.OeaGlassDrawable(p.cardHigh, dp(26).toFloat(), density, p.accent, rimAlpha = if (p.dark) 0x70 else 0x40)
        } else ws.kit.rounded(p.cardHigh, 26)
        (searchBox.findViewWithTag<ImageView>("searchIcon"))?.setImageDrawable(ws.kit.icon(R.drawable.oic_search, p.textSecondary))
        search.setTextColor(p.text)
        search.setHintTextColor(p.textTertiary)
        clearButton.setImageDrawable(ws.kit.icon(R.drawable.oic_close, p.textSecondary))
        clearButton.background = ws.kit.ripple(null, 20)
        moreButton.setImageDrawable(ws.kit.icon(R.drawable.oic_more_vert, p.textSecondary))
        moreButton.background = ws.kit.ripple(null, 20)
        bubble.setTextColor(p.onAccent)
        bubble.background = ws.kit.oval(p.accent)
        rail.color = p.textSecondary
        rail.activeColor = p.accent
        rail.invalidate()
    }

    /** True while the user is searching (field focused or a query typed). */
    private val searchActive: Boolean get() = search.hasFocus() || !search.text.isNullOrEmpty()

    /**
     * What sits behind All Apps and search:
     *  - "Show wallpaper in All Apps": the whole drawer is translucent;
     *  - "Show wallpaper behind search": the search area at the top always
     *    shows the wallpaper, and the whole drawer does while searching;
     *  - neither: the opaque panel.
     * The scrim keeps text readable: it is lighter where the wallpaper's own
     * brightness already contrasts with the panel text, and strong (above
     * 80% opacity, enough for WCAG AA text) where it would fight it.
     */
    fun applyBackdrop() {
        val p = ws.palette
        val all = ws.store.drawerWallpaperBackground()
        val searchWallpaper = ws.store.searchWallpaperVisible()
        val searching = searchActive
        val agrees = p.dark != p.wallpaperLight
        val scrim = OeaTheme.alpha(p.drawer, if (agrees) 0xA0 else 0xD6)
        val key = "$all|$searchWallpaper|$searching|${p.drawer}|$agrees"
        if (key == backdropKey) return
        backdropKey = key
        when {
            searchWallpaper && searching -> {
                background = ws.kit.roundedTop(scrim, 28)
                listArea.background = null
            }
            searchWallpaper -> {
                background = ws.kit.roundedTop(scrim, 28)
                listArea.background = if (all) null else ws.kit.roundedTop(p.drawer, 24)
            }
            all -> {
                background = ws.kit.roundedTop(OeaTheme.alpha(p.drawer, 0xD8), 28)
                listArea.background = null
            }
            else -> {
                background = ws.kit.roundedTop(p.drawer, 28)
                listArea.background = null
            }
        }
        ws.onDrawerBackdropChanged(wallpaperVisible = all || searchWallpaper)
    }

    fun applyInsets(top: Int, bottom: Int) {
        topInset = top
        bottomInset = bottom
        column.setPadding(0, top, 0, 0)
        list.setPadding(dp(8), dp(4), dp(8), bottom + dp(16))
        (rail.layoutParams as? LayoutParams)?.let {
            it.bottomMargin = bottom + dp(8)
            rail.layoutParams = it
        }
    }

    /** Rebuilds categories and rows from the current app list. */
    fun refresh() {
        ws.searchController.clearResolveCache()
        buildChips()
        rebuild()
    }

    fun resetState() {
        if (!search.text.isNullOrEmpty()) search.text?.clear()
        if (category != "all") {
            category = "all"
            buildChips()
            rebuild()
        }
        list.setSelection(0)
        hideKeyboard()
    }

    fun focusSearch(showKeyboard: Boolean) {
        search.requestFocus()
        if (showKeyboard) {
            search.post {
                context.getSystemService(InputMethodManager::class.java)?.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    fun hideKeyboard() {
        if (search.hasFocus()) search.clearFocus()
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(windowToken, 0)
    }

    fun hasQuery() = !search.text.isNullOrBlank()

    /** Re-binds visible rows (badges, frozen/locked states) without rebuilding. */
    fun notifyChanged() = adapter.notifyDataSetChanged()

    fun isAtTop(): Boolean = !list.canScrollVertically(-1)

    // ------------------------------------------------------------ categories

    private fun categoryOf(app: OeaAppInfo): String? = when (app.category) {
        ApplicationInfo.CATEGORY_GAME -> "games"
        ApplicationInfo.CATEGORY_AUDIO -> "audio"
        ApplicationInfo.CATEGORY_VIDEO -> "video"
        ApplicationInfo.CATEGORY_IMAGE -> "photos"
        ApplicationInfo.CATEGORY_SOCIAL -> "social"
        ApplicationInfo.CATEGORY_NEWS -> "news"
        ApplicationInfo.CATEGORY_MAPS -> "maps"
        ApplicationInfo.CATEGORY_PRODUCTIVITY -> "productivity"
        else -> null
    }

    private val categoryLabels = linkedMapOf(
        "all" to "All",
        "social" to "Social",
        "games" to "Games",
        "audio" to "Music",
        "video" to "Video",
        "photos" to "Photos",
        "productivity" to "Productivity",
        "news" to "News",
        "maps" to "Travel",
        "frozen" to "Frozen",
    )

    private fun buildChips() {
        chips.removeAllViews()
        val apps = ws.drawerApps()
        val show = ws.store.drawerCategories()
        chipsScroll.visibility = if (show) VISIBLE else GONE
        if (!show) {
            category = "all"
            return
        }
        val present = apps.mapNotNull(::categoryOf).toSet()
        val anyFrozen = apps.any { ws.isFrozen(it.packageName) }
        categoryLabels.forEach { (key, label) ->
            val visible = key == "all" || key in present || (key == "frozen" && anyFrozen)
            if (!visible) return@forEach
            chips.addView(ws.kit.chip(label, key == category) {
                category = key
                buildChips()
                rebuild()
                list.setSelection(0)
            }, LinearLayout.LayoutParams(-2, dp(36)).apply { rightMargin = dp(8) })
        }
        if (category != "all" && category !in present && !(category == "frozen" && anyFrozen)) category = "all"
    }

    // ------------------------------------------------------------------ rows

    fun rebuild() {
        val query = search.text?.toString()?.trim().orEmpty()
        val cols = ws.drawerColumns()
        val listMode = ws.store.drawerMode() == OeaDataStore.DrawerMode.VERTICAL
        val out = mutableListOf<Row>()
        val letters = mutableListOf<Pair<Char, Int>>()
        val all = ws.drawerApps()
        if (query.isEmpty()) {
            val filtered = when (category) {
                "all" -> all
                "frozen" -> all.filter { ws.isFrozen(it.packageName) }
                else -> all.filter { categoryOf(it) == category }
            }
            if (category == "all") {
                val suggestions = if (ws.store.showMostUsed()) ws.suggestions(cols) else emptyList()
                if (suggestions.isNotEmpty()) {
                    out += Row.Label("Suggested")
                    out += Row.Apps(suggestions)
                    out += Row.Label("All apps")
                }
            }
            val nameSorted = ws.store.sortMode() == OeaDataStore.SortMode.NAME
            if (listMode) {
                filtered.forEach { app ->
                    if (nameSorted) indexLetter(app.label)?.let { c -> if (letters.none { it.first == c }) letters += c to out.size }
                    out += Row.ListApp(app)
                }
            } else {
                filtered.chunked(cols).forEach { chunk ->
                    if (nameSorted) chunk.forEach { app ->
                        indexLetter(app.label)?.let { c -> if (letters.none { it.first == c }) letters += c to out.size }
                    }
                    out += Row.Apps(chunk)
                }
            }
            if (filtered.isEmpty()) out += Row.Empty("No apps here yet.")
            rail.visibility = if (nameSorted && category == "all" && letters.size > 4) VISIBLE else GONE
        } else {
            rail.visibility = GONE
            val matches = rank(all, query)
            if (matches.isNotEmpty()) {
                out += Row.Label("Apps")
                if (listMode) matches.forEach { out += Row.ListApp(it) } else matches.chunked(cols).forEach { out += Row.Apps(it) }
            }
            // Non-app results arrive from a worker; app matches above show instantly.
            val actions = if (actionsQuery == query) lastActions else emptyList()
            if (actionsQuery != query) requestActions(query)
            if (actions.isNotEmpty()) {
                out += Row.Label(if (matches.isEmpty()) "Results" else "More results")
                actions.forEach { out += Row.Action(it) }
            }
            if (out.isEmpty()) out += Row.Empty(if (actionsQuery == query) "No results for \"$query\"" else "Searching…")
        }
        rows = out
        letterRows = letters
        rail.letters = letters.map { it.first }
        adapter.notifyDataSetChanged()
    }

    private var actionsQuery: String? = null
    private var lastActions: List<OeaSearchAction> = emptyList()
    private var pendingQuery: String? = null
    private val actionsRequest = Runnable {
        val query = pendingQuery ?: return@Runnable
        ws.searchController.actionsAsync(query) { q, list ->
            if (q != search.text?.toString()?.trim().orEmpty()) return@actionsAsync
            actionsQuery = q
            lastActions = list
            rebuild()
        }
    }

    private fun requestActions(query: String) {
        pendingQuery = query
        removeCallbacks(actionsRequest)
        postDelayed(actionsRequest, 90L)
    }

    private fun indexLetter(label: String): Char? {
        val first = label.trim().firstOrNull() ?: return null
        val c = Normalizer.normalize(first.toString(), Normalizer.Form.NFD).firstOrNull()?.uppercaseChar() ?: return null
        return if (c in 'A'..'Z') c else '#'
    }

    private fun normalize(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "").lowercase(Locale.getDefault())

    /** Prefix > word-start > initials > substring > package name. */
    private fun rank(apps: List<OeaAppInfo>, query: String): List<OeaAppInfo> {
        val q = normalize(query)
        return apps.mapNotNull { app ->
            val label = normalize(app.label)
            val words = label.split(' ', '-', '_', '.').filter { it.isNotEmpty() }
            val initials = words.joinToString("") { it.take(1) }
            val score = when {
                label.startsWith(q) -> 0
                words.any { it.startsWith(q) } -> 1
                q.length >= 2 && initials.startsWith(q) -> 2
                label.contains(q) -> 3
                app.packageName.lowercase(Locale.ROOT).contains(q) -> 4
                else -> return@mapNotNull null
            }
            Triple(app, score, label)
        }.sortedWith(compareBy<Triple<OeaAppInfo, Int, String>>({ it.second }, { -ws.store.launchCount(ws.appKey(it.first)) }, { it.third }))
            .map { it.first }
    }

    private fun launchFirstResult() {
        val firstApp = rows.firstNotNullOfOrNull { row ->
            when (row) {
                is Row.Apps -> row.apps.firstOrNull()
                is Row.ListApp -> row.app
                else -> null
            }
        }
        if (firstApp != null && hasQuery()) {
            ws.launchApp(firstApp, null)
            return
        }
        val query = search.text?.toString()?.trim().orEmpty()
        val action = rows.firstNotNullOfOrNull { (it as? Row.Action)?.action }
            ?: if (query.isNotEmpty()) runCatching { ws.searchController.actions(query).firstOrNull() }.getOrNull() else null
        action?.let { ws.runSearchAction(it) }
    }

    private fun jumpTo(letter: Char, y: Float) {
        val row = letterRows.firstOrNull { it.first == letter }?.second ?: return
        list.setSelectionFromTop(row, 0)
        bubble.text = letter.toString()
        bubble.translationY = (y - dp(36)).coerceIn(0f, (listArea.height - dp(72)).toFloat().coerceAtLeast(0f))
        bubble.animate().cancel()
        bubble.alpha = 1f
    }

    // --------------------------------------------------------- drag to close

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                closing = false
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(ev) }
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(ev)
                val dy = ev.rawY - downY
                val dx = abs(ev.rawX - downX)
                val overList = ev.y > listArea.top
                if (!closing && dy > touchSlop * 1.5f && dy > dx * 1.4f && (!overList || isAtTop())) {
                    closing = true
                    hideKeyboard()
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!closing) return true
        velocity?.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val dy = (event.rawY - downY).coerceAtLeast(0f)
                translationY = dy
                ws.onDrawerDragged(1f - dy / height.coerceAtLeast(1))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocity?.computeCurrentVelocity(1000)
                val vy = velocity?.yVelocity ?: 0f
                val dy = event.rawY - downY
                closing = false
                ws.settleDrawer(open = !(vy > 1200f || dy > height * 0.25f))
            }
        }
        return true
    }

    // ------------------------------------------------------------- adapter

    private inner class DrawerAdapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 5
        override fun getItemViewType(position: Int) = when (rows[position]) {
            is Row.Label -> 0
            is Row.Apps -> 1
            is Row.ListApp -> 2
            is Row.Action -> 3
            is Row.Empty -> 4
        }
        override fun isEnabled(position: Int) = false

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val p = ws.palette
            return when (val row = rows[position]) {
                is Row.Label -> {
                    val view = convertView as? TextView ?: TextView(context).apply {
                        textSize = 13f
                        typeface = OeaType.medium
                        setPadding(dp(14), dp(14), dp(14), dp(6))
                    }
                    view.text = row.text
                    view.setTextColor(p.accent)
                    view
                }
                is Row.Apps -> {
                    val cols = ws.drawerColumns()
                    val strip = (convertView as? LinearLayout)?.takeIf { it.childCount == cols } ?: LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        repeat(cols) { addView(OeaTileView(context), LinearLayout.LayoutParams(0, ws.drawerRowHeight(), 1f)) }
                    }
                    for (i in 0 until cols) {
                        val tile = strip.getChildAt(i) as OeaTileView
                        val app = row.apps.getOrNull(i)
                        if (app == null) {
                            tile.visibility = INVISIBLE
                            tile.listener = null
                        } else {
                            tile.visibility = VISIBLE
                            ws.bindTile(tile, app, OeaWorkspace.TileStyle.DRAWER)
                            tile.listener = ws.drawerTileListener
                        }
                    }
                    strip
                }
                is Row.ListApp -> {
                    val holder = convertView as? LinearLayout ?: LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(8), 0, dp(12), 0)
                        addView(OeaTileView(context), LinearLayout.LayoutParams(dp(56), dp(60)))
                        addView(TextView(context).apply {
                            textSize = 16f
                            maxLines = 1
                            ellipsize = TextUtils.TruncateAt.END
                        }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(10) })
                    }
                    val tile = holder.getChildAt(0) as OeaTileView
                    ws.bindTile(tile, row.app, OeaWorkspace.TileStyle.DRAWER)
                    tile.showLabel = false
                    tile.iconSizePx = dp(42)
                    tile.listener = ws.drawerTileListener
                    (holder.getChildAt(1) as TextView).apply {
                        text = row.app.label
                        setTextColor(if (ws.isFrozen(row.app.packageName)) p.textTertiary else p.text)
                    }
                    holder.background = ws.kit.ripple(null, 18)
                    holder.setOnClickListener { ws.launchApp(row.app, tile) }
                    holder
                }
                is Row.Action -> {
                    // Card inside a padded frame so result cards don't touch.
                    val frame = convertView as? FrameLayout ?: FrameLayout(context).apply {
                        setPadding(dp(6), dp(3), dp(6), dp(3))
                        addView(LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(dp(14), dp(10), dp(14), dp(10))
                            addView(FrameLayout(context), LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(14) })
                            addView(LinearLayout(context).apply {
                                orientation = LinearLayout.VERTICAL
                                addView(TextView(context).apply { textSize = 15.5f; typeface = OeaType.medium; maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                                addView(TextView(context).apply { textSize = 12.5f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                            }, LinearLayout.LayoutParams(0, -2, 1f))
                        }, LayoutParams(-1, -2))
                    }
                    val holder = frame.getChildAt(0) as LinearLayout
                    val badge = holder.getChildAt(0) as FrameLayout
                    badge.removeAllViews()
                    badge.background = ws.kit.oval(p.accentSoft)
                    badge.addView(ImageView(context).apply { setImageDrawable(ws.kit.icon(row.action.iconRes, p.onAccentSoft)) },
                        LayoutParams(dp(22), dp(22), Gravity.CENTER))
                    val texts = holder.getChildAt(1) as LinearLayout
                    (texts.getChildAt(0) as TextView).apply { text = row.action.title; setTextColor(p.text) }
                    (texts.getChildAt(1) as TextView).apply { text = row.action.subtitle; setTextColor(p.textSecondary) }
                    holder.background = ws.kit.ripple(ws.kit.rounded(p.card, 20), 20)
                    holder.setOnClickListener { ws.runSearchAction(row.action) }
                    frame
                }
                is Row.Empty -> {
                    val view = convertView as? TextView ?: TextView(context).apply {
                        textSize = 14.5f
                        gravity = Gravity.CENTER
                        setPadding(dp(24), dp(48), dp(24), dp(48))
                    }
                    view.text = row.text
                    view.setTextColor(p.textSecondary)
                    view
                }
            }
        }
    }

    private fun dp(v: Int) = (v * density + 0.5f).toInt()

    /** Vertical A–Z strip; drag along it to jump through the alphabet. */
    private class AlphabetRail(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = OeaType.medium
        }
        private var active = -1
        var letters: List<Char> = emptyList()
            set(value) { field = value; invalidate() }
        var color = Color.GRAY
        var activeColor = Color.WHITE
        var onLetter: ((Char, Float) -> Unit)? = null
        var onRelease: (() -> Unit)? = null

        override fun onDraw(canvas: Canvas) {
            if (letters.isEmpty()) return
            val step = height / letters.size.toFloat()
            paint.textSize = (step * 0.62f).coerceAtMost(11.5f * resources.displayMetrics.scaledDensity)
            letters.forEachIndexed { i, c ->
                paint.color = if (i == active) activeColor else color
                val y = step * i + step / 2f - (paint.ascent() + paint.descent()) / 2f
                canvas.drawText(c.toString(), width / 2f, y, paint)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (letters.isEmpty()) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    val index = (event.y / height * letters.size).toInt().coerceIn(0, letters.lastIndex)
                    if (index != active) {
                        active = index
                        performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                        onLetter?.invoke(letters[index], event.y)
                        invalidate()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    active = -1
                    invalidate()
                    onRelease?.invoke()
                }
            }
            return true
        }
    }
}
