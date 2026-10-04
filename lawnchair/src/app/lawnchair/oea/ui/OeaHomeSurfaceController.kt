package app.lawnchair.oea.ui

import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.TextView
import app.lawnchair.oea.data.OeaDataStore
import com.android.launcher3.Launcher

/**
 * OEA home-surface attachment point.
 *
 * The base launcher owns workspace/app icons. OEA only attaches its own surface when the
 * user enables it, preventing feature UI from taking over the Launcher3 workspace.
 */
class OeaHomeSurfaceController(private val launcher: Launcher) {
    private var surface: TextView? = null

    fun attach() {
        if (!OeaDataStore.get(launcher).homeSurfaceEnabled.value || surface != null) return
        val root = launcher.dragLayer ?: return
        val view = TextView(launcher).apply {
            text = "OEA"
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(0x66000000)
            setPadding(12, 6, 12, 6)
            gravity = Gravity.CENTER
        }
        root.addView(
            view,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        view.translationY = 24f
        surface = view
    }

    fun detach() {
        surface?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
        }
        surface = null
    }
}
