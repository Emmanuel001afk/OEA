package app.lawnchair.oea.workspace

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.GridLayout
import android.widget.TextView
import app.lawnchair.oea.model.OeaAppInfo

/**
 * First OEA-owned workspace/grid implementation.
 *
 * It intentionally does not depend on Launcher3 Workspace, CellLayout,
 * LauncherModel or LauncherAppState.
 */
class OeaWorkspace(context: Context) : GridLayout(context) {
    init {
        columnCount = 4
        rowCount = 5
        setPadding(24, 48, 24, 24)
        setBackgroundColor(Color.BLACK)
    }

    fun bind(apps: List<OeaAppInfo>) {
        removeAllViews()
        apps.take(20).forEach { app ->
            val tile = TextView(context).apply {
                text = app.label
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(8, 8, 8, 8)
                setOnClickListener {
                    val intent = context.packageManager.getLaunchIntentForPackage(app.packageName)
                    intent?.let(context::startActivity)
                }
            }
            addView(tile, GridLayout.LayoutParams().apply {
                width = 0
                height = 0
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(4, 4, 4, 4)
            })
        }
    }
}
