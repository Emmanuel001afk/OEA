package app.lawnchair.oea.workspace

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.lawnchair.oea.model.OeaAppInfo
import app.lawnchair.ui.preferences.PreferenceActivity
import java.util.Locale

/**
 * OEA-owned interactive HOME surface.
 *
 * This is intentionally independent of Launcher3's Workspace/CellLayout.
 * It provides a fast, scrollable app surface with native Android controls,
 * real application icons, search/filtering and a direct settings entry point.
 */
class OeaWorkspace(context: Context) : ScrollView(context) {
    private val root = LinearLayout(context)
    private val appGrid = GridLayout(context)
    private val search = EditText(context)
    private var allApps: List<OeaAppInfo> = emptyList()
    private val iconCache = mutableMapOf<String, Drawable.ConstantState?>()

    init {
        isFillViewport = true
        setBackgroundColor(Color.rgb(12, 15, 21))
        isVerticalScrollBarEnabled = false

        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(18), dp(22), dp(18), dp(28))
        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val header = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(context).apply {
            text = "OEA"
            textSize = 28f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f)
        }
        header.addView(title)

        val settings = TextView(context).apply {
            text = "⚙"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            contentDescription = "OEA settings"
            background = rounded(Color.rgb(38, 44, 57), dp(18))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                context.startActivity(
                    Intent(context, PreferenceActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
        header.addView(settings, LinearLayout.LayoutParams(dp(52), dp(52)).apply {
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
        ).apply { topMargin = dp(10); bottomMargin = dp(18) })

        val section = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        val allAppsLabel = TextView(context).apply {
            text = "All apps"
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f)
        }
        section.addView(allAppsLabel)
        val count = TextView(context).apply {
            textSize = 13f
            setTextColor(Color.rgb(160, 169, 185))
        }
        section.addView(count)
        root.addView(section)
        count.tag = "app_count"

        appGrid.columnCount = 4
        appGrid.useDefaultMargins = false
        root.addView(appGrid, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val footer = TextView(context).apply {
            text = "Swipe to browse • Tap an app to open"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(126, 135, 151))
            setPadding(0, dp(20), 0, dp(8))
        }
        root.addView(footer)
    }

    fun bind(apps: List<OeaAppInfo>) {
        allApps = apps
        renderApps(search.text?.toString().orEmpty())
    }

    private fun renderApps(query: String) {
        appGrid.removeAllViews()
        val normalized = query.trim().lowercase(Locale.ROOT)
        val visible = if (normalized.isEmpty()) {
            allApps
        } else {
            allApps.filter { it.label.lowercase(Locale.ROOT).contains(normalized) }
        }

        root.findViewWithTag<TextView>("app_count")?.text =
            if (normalized.isEmpty()) "${allApps.size}" else "${visible.size} found"

        visible.forEach { app ->
            appGrid.addView(createAppTile(app))
        }

        if (visible.isEmpty()) {
            appGrid.addView(TextView(context).apply {
                text = "No apps found"
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(170, 178, 192))
                setPadding(0, dp(36), 0, dp(36))
            }, GridLayout.LayoutParams().apply {
                width = 0
                height = dp(100)
                columnSpec = GridLayout.spec(0, 4, 1f)
            })
        }
    }

    private fun createAppTile(app: OeaAppInfo): View {
        val tile = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = rounded(Color.rgb(30, 36, 49), dp(20))
            foreground = context.obtainStyledAttributes(
                intArrayOf(android.R.attr.selectableItemBackground)
            ).let { attrs ->
                attrs.getDrawable(0).also { attrs.recycle() }
            }
            setPadding(dp(8), dp(10), dp(8), dp(8))
            contentDescription = "Open ${app.label}"
            setOnClickListener {
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    component = ComponentName(app.packageName, app.className)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching { context.startActivity(intent) }
            }
        }

        val icon = ImageView(context).apply {
            setImageDrawable(loadIcon(app.packageName))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        tile.addView(icon, LinearLayout.LayoutParams(dp(48), dp(48)))

        tile.addView(TextView(context).apply {
            text = app.label
            textSize = 12f
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
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

    /**
     * HOME should not accidentally finish its root activity on Back.
     * Clear search first, then return to the top; otherwise consume Back.
     */
    fun handleBack(): Boolean {
        if (search.text?.isNotEmpty() == true) {
            search.text?.clear()
            search.clearFocus()
            context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(search.windowToken, 0)
            return true
        }
        if (scrollY != 0) {
            smoothScrollTo(0, 0)
            return true
        }
        return true
    }

    private fun loadIcon(packageName: String): Drawable? {
        if (iconCache.containsKey(packageName)) {
            return iconCache[packageName]?.newDrawable(resources)
        }
        val state = runCatching {
            context.packageManager.getApplicationIcon(packageName).constantState
        }.getOrNull()
        iconCache[packageName] = state
        return state?.newDrawable(resources)
    }

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
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
