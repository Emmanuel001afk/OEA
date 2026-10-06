package app.lawnchair.oea.workspace

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.lawnchair.oea.data.OeaDataStore
import app.lawnchair.oea.model.OeaAppInfo
import java.util.Locale

/**
 * OEA-owned interactive HOME surface.
 *
 * The surface intentionally avoids Launcher3 Workspace/CellLayout. It provides
 * launcher-grade basics directly: searchable apps, favorites/dock, recent
 * ordering, configurable grid density, app actions and smooth scrolling.
 */
class OeaWorkspace(context: Context) : ScrollView(context) {
    private val store = OeaDataStore.get(context)
    private val root = LinearLayout(context)
    private val appGrid = GridLayout(context)
    private val favoritesRow = LinearLayout(context)
    private val favoritesSection = LinearLayout(context)
    private val search = EditText(context)
    private val countLabel = TextView(context)
    private var allApps: List<OeaAppInfo> = emptyList()
    private val iconCache = mutableMapOf<String, Drawable.ConstantState?>()

    init {
        isFillViewport = true
        setBackgroundColor(Color.rgb(12, 15, 21))
        isVerticalScrollBarEnabled = false
        descendantFocusability = FOCUS_AFTER_DESCENDANTS

        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(18), dp(20), dp(18), dp(30))
        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(context).apply {
            text = "OEA"
            textSize = 28f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f)
        })

        val menu = TextView(context).apply {
            text = "⋮"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            contentDescription = "OEA launcher menu"
            background = rounded(Color.rgb(38, 44, 57), dp(18))
            isClickable = true
            isFocusable = true
            setOnClickListener { showLauncherMenu(it) }
        }
        header.addView(menu, LinearLayout.LayoutParams(dp(52), dp(52)).apply {
            marginStart = dp(8)
        })
        root.addView(header)

        search.hint = "Search apps"
        search.textSize = 16f
        search.setSingleLine(true)
        search.setTextColor(Color.WHITE)
        search.setHintTextColor(Color.rgb(154, 162, 177))
        search.setPadding(dp(18), 0, dp(18), 0)
        search.background = rounded(Color.rgb(30, 36, 49), dp(28))
        search.contentDescription = "Search installed apps"
        search.addTextChangedListener(SimpleTextWatcher { renderApps(it) })
        root.addView(search, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(58)
        ).apply { topMargin = dp(10); bottomMargin = dp(16) })

        buildFavoritesSection()

        val section = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        section.addView(TextView(context).apply {
            text = "All apps"
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f)
        })
        countLabel.textSize = 13f
        countLabel.setTextColor(Color.rgb(160, 169, 185))
        section.addView(countLabel)
        root.addView(section)

        appGrid.columnCount = store.gridColumns()
        appGrid.useDefaultMargins = false
        root.addView(appGrid, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        root.addView(TextView(context).apply {
            text = "Tap to open • Long-press for actions • Swipe to browse"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(126, 135, 151))
            setPadding(0, dp(20), 0, dp(8))
        })
    }

    private fun buildFavoritesSection() {
        favoritesSection.orientation = LinearLayout.VERTICAL
        favoritesSection.addView(TextView(context).apply {
            text = "Favorites"
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        val horizontal = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(favoritesRow, HorizontalScrollView.LayoutParams(
                HorizontalScrollView.LayoutParams.WRAP_CONTENT, dp(104)
            ))
        }
        favoritesSection.addView(horizontal)
        root.addView(favoritesSection, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(138)
        ).apply { bottomMargin = dp(4) })
    }

    fun bind(apps: List<OeaAppInfo>) {
        allApps = apps
        renderApps(search.text?.toString().orEmpty())
    }

    private fun renderApps(query: String) {
        val normalized = query.trim().lowercase(Locale.ROOT)
        val filtered = if (normalized.isEmpty()) allApps else allApps.filter {
            it.label.lowercase(Locale.ROOT).contains(normalized) ||
                it.packageName.lowercase(Locale.ROOT).contains(normalized)
        }
        val visible = sortApps(filtered)
        countLabel.text = if (normalized.isEmpty()) "${visible.size}" else "${visible.size} found"

        appGrid.removeAllViews()
        appGrid.columnCount = store.gridColumns()
        visible.forEach { appGrid.addView(createAppTile(app)) }

        if (visible.isEmpty()) {
            appGrid.addView(TextView(context).apply {
                text = if (normalized.isEmpty()) "No launchable apps found" else "No apps found"
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(170, 178, 192))
                setPadding(0, dp(36), 0, dp(36))
            }, GridLayout.LayoutParams().apply {
                width = 0
                height = dp(100)
                columnSpec = GridLayout.spec(0, appGrid.columnCount, 1f)
            })
        }
        renderFavorites()
    }

    private fun sortApps(apps: List<OeaAppInfo>): List<OeaAppInfo> {
        return when (store.sortMode()) {
            OeaDataStore.SortMode.NAME -> apps.sortedBy { it.label.lowercase(Locale.ROOT) }
            OeaDataStore.SortMode.RECENT -> {
                val positions = store.recentLaunches().withIndex().associate { it.value to it.index }
                apps.sortedWith(compareBy<OeaAppInfo> {
                    positions["${it.packageName}/${it.className}"] ?: Int.MAX_VALUE
                }.thenBy { it.label.lowercase(Locale.ROOT) })
            }
        }
    }

    private fun renderFavorites() {
        favoritesRow.removeAllViews()
        val favorites = allApps.filter {
            store.isFavorite(it.packageName, it.className)
        }
        favoritesSection.visibility = if (favorites.isEmpty()) View.GONE else View.VISIBLE
        favorites.forEach { app ->
            favoritesRow.addView(createFavoriteTile(app))
        }
    }

    private fun createFavoriteTile(app: OeaAppInfo): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = rounded(Color.rgb(30, 36, 49), dp(18))
            contentDescription = "Open favorite ${app.label}"
            setOnClickListener { launch(app) }
            setOnLongClickListener {
                showAppMenu(this, app)
                true
            }
            addView(ImageView(context).apply {
                setImageDrawable(loadIcon(app.packageName))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(dp(42), dp(48)))
            addView(TextView(context).apply {
                text = app.label
                textSize = 11f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
            }, LinearLayout.LayoutParams(dp(82), dp(28)))
        }.also {
            it.layoutParams = LinearLayout.LayoutParams(dp(92), dp(96)).apply {
                marginEnd = dp(8)
                topMargin = dp(4)
            }
        }

    private fun createAppTile(app: OeaAppInfo): View {
        val tile = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = rounded(Color.rgb(30, 36, 49), dp(20))
            foreground = selectableBackground()
            setPadding(dp(8), dp(10), dp(8), dp(8))
            contentDescription = "Open ${app.label}"
            setOnClickListener { launch(app) }
            setOnLongClickListener {
                showAppMenu(this, app)
                true
            }
        }

        tile.addView(ImageView(context).apply {
            setImageDrawable(loadIcon(app.packageName))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(dp(48), dp(48)))

        tile.addView(TextView(context).apply {
            text = app.label
            textSize = 12f
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(Color.WHITE)
            setPadding(2, dp(6), 2, 0)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(38)
        ))

        return tile.apply {
            layoutParams = GridLayout.LayoutParams().apply {
                width = 0
                height = dp(106)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
        }
    }

    private fun showAppMenu(anchor: View, app: OeaAppInfo) {
        PopupMenu(context, anchor).apply {
            menu.add(if (store.isFavorite(app.packageName, app.className)) "Remove from favorites" else "Add to favorites")
            menu.add("App info")
            setOnMenuItemClickListener { item ->
                when (item.title.toString()) {
                    "Add to favorites" -> {
                        store.setFavorite(app.packageName, app.className, true)
                        renderFavorites()
                        true
                    }
                    "Remove from favorites" -> {
                        store.setFavorite(app.packageName, app.className, false)
                        renderFavorites()
                        true
                    }
                    "App info" -> {
                        openAppInfo(app)
                        true
                    }
                    else -> false
                }
            }
            show()
        }
    }

    private fun showLauncherMenu(anchor: View) {
        PopupMenu(context, anchor).apply {
            menu.add("Grid: ${store.gridColumns()} columns")
            menu.add("Grid: 3 columns")
            menu.add("Grid: 4 columns")
            menu.add("Grid: 5 columns")
            menu.add("Sort: A-Z")
            menu.add("Sort: Recent")
            menu.add("Refresh apps")
            setOnMenuItemClickListener { item ->
                when {
                    item.title.toString().startsWith("Grid: 3") -> {
                        store.setGridColumns(3); renderApps(search.text.toString()); true
                    }
                    item.title.toString().startsWith("Grid: 4") -> {
                        store.setGridColumns(4); renderApps(search.text.toString()); true
                    }
                    item.title.toString().startsWith("Grid: 5") -> {
                        store.setGridColumns(5); renderApps(search.text.toString()); true
                    }
                    item.title.toString() == "Sort: A-Z" -> {
                        store.setSortMode(OeaDataStore.SortMode.NAME); renderApps(search.text.toString()); true
                    }
                    item.title.toString() == "Sort: Recent" -> {
                        store.setSortMode(OeaDataStore.SortMode.RECENT); renderApps(search.text.toString()); true
                    }
                    item.title.toString() == "Refresh apps" -> {
                        Toast.makeText(context, "Apps refresh when HOME resumes", Toast.LENGTH_SHORT).show()
                        true
                    }
                    else -> false
                }
            }
            show()
        }
    }

    private fun launch(app: OeaAppInfo) {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = ComponentName(app.packageName, app.className)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onSuccess { store.recordLaunch(app.packageName, app.className) }
            .onFailure {
                Toast.makeText(context, "Unable to open ${app.label}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun openAppInfo(app: OeaAppInfo) {
        runCatching {
            context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${app.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }.onFailure {
            Toast.makeText(context, "App info unavailable", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadIcon(packageName: String): Drawable? {
        if (iconCache.containsKey(packageName)) return iconCache[packageName]?.newDrawable(resources)
        val state = runCatching {
            context.packageManager.getApplicationIcon(packageName).constantState
        }.getOrNull()
        iconCache[packageName] = state
        return state?.newDrawable(resources)
    }

    private fun selectableBackground(): Drawable? =
        context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
            .let { attrs -> attrs.getDrawable(0).also { attrs.recycle() } }

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    fun handleBack(): Boolean {
        if (search.text?.isNotEmpty() == true) {
            search.text?.clear()
            search.clearFocus()
            context.getSystemService(InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(search.windowToken, 0)
            return true
        }
        if (scrollY != 0) {
            smoothScrollTo(0, 0)
            return true
        }
        return true
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private class SimpleTextWatcher(
        private val onChanged: (String) -> Unit,
    ) : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            onChanged(s?.toString().orEmpty())
        }
        override fun afterTextChanged(s: android.text.Editable?) = Unit
    }
}
