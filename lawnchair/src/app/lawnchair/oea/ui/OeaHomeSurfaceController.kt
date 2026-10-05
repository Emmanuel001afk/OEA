package app.lawnchair.oea.ui

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.lawnchair.oea.engine.OeaAppCatalog
import app.lawnchair.oea.engine.OeaEngine
import com.android.launcher3.Launcher

/**
 * OEA-owned HOME surface.
 *
 * This visible surface is driven by OEA engine state and Android launcher APIs.
 * It does not read Launcher3's model/database to decide which apps are shown.
 */
class OeaHomeSurfaceController(private val launcher: Launcher) {
    private var surface: ViewGroup? = null

    fun attach() {
        if (surface != null) return

        val engine = OeaEngine.get(launcher).start()
        val root = launcher.dragLayer ?: return

        val container = LinearLayout(launcher).apply {
            orientation = LinearLayout.VERTICAL
            tag = OEA_HOME_TAG
            setPadding(24, 32, 24, 16)
            setBackgroundColor(Color.rgb(18, 18, 20))
            elevation = 40f
            isClickable = true
            isFocusable = true
        }

        container.addView(TextView(launcher).apply {
            text = "OEA"
            textSize = 28f
            setTextColor(Color.WHITE)
            setPadding(4, 4, 4, 8)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        val health = TextView(launcher).apply {
            textSize = 12f
            setTextColor(Color.LTGRAY)
            setPadding(4, 0, 4, 12)
        }
        container.addView(health, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        val searchBox = EditText(launcher).apply {
            hint = "Search apps"
            setSingleLine(true)
            textSize = 16f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setPadding(18, 0, 18, 0)
            background = ColorDrawable(Color.rgb(38, 38, 42))
        }
        container.addView(searchBox, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            52,
        ).apply { bottomMargin = 12 })

        val scroll = ScrollView(launcher)
        val appGrid = LinearLayout(launcher).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(appGrid, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        container.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))

        root.addView(container, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        container.bringToFront()
        container.post { container.bringToFront() }
        surface = container

        fun render(query: String = "") {
            val q = query.trim()
            val apps = if (q.isEmpty()) engine.apps.value else engine.search.search(q).mapNotNull { result -> engine.apps.value.firstOrNull { it.component == result.component && it.user == result.user } }
            renderApps(appGrid, apps)
            health.text = "Independent engine • " + engine.health().appCount + " apps • " + apps.size + " shown"
        }

        searchBox.addTextChangedListener(SimpleTextWatcher { render(it) })
        render()
    }

    private fun renderApps(container: LinearLayout, apps: List<OeaAppCatalog.App>) {
        container.removeAllViews()
        if (apps.isEmpty()) {
            container.addView(TextView(launcher).apply {
                text = "No apps found"
                textSize = 16f
                setTextColor(Color.LTGRAY)
                gravity = Gravity.CENTER
                setPadding(8, 40, 8, 40)
            })
            return
        }

        var row: LinearLayout? = null
        apps.forEachIndexed { index, app ->
            if (index % 4 == 0) {
                row = LinearLayout(launcher).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.TOP
                }
                container.addView(row, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    108,
                ))
            }

            val item = LinearLayout(launcher).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setPadding(4, 4, 4, 4)
                setOnClickListener { OeaEngine.get(launcher).launch(app.component, app.user) }
            }

            val icon = ImageView(launcher)
            val drawable: Drawable? = runCatching {
                launcher.getSystemService(android.content.pm.LauncherApps::class.java)
                    .getActivityIcon(app.component, app.user)
            }.getOrNull()
            icon.setImageDrawable(drawable)
            item.addView(icon, LinearLayout.LayoutParams(48, 48))

            item.addView(TextView(launcher).apply {
                text = app.label
                textSize = 11f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(76, 40))

            row?.addView(item, LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1f,
            ))
        }
    }

    fun detach() {
        surface?.let { view -> (view.parent as? ViewGroup)?.removeView(view) }
        surface = null
    }

    companion object {
        const val OEA_HOME_TAG = "oea_home_surface"
    }

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
