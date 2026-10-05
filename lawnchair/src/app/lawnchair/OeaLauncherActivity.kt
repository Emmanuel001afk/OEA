package app.lawnchair

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Window
import android.widget.FrameLayout
import app.lawnchair.oea.OeaLauncherSafetyNet
import app.lawnchair.oea.runtime.OeaHomeController

/**
 * OEA's real HOME activity.
 *
 * Launcher3 remains available as a feature foundation elsewhere in the project, but it does not
 * own OEA HOME window initialization or visible HOME state.
 */
class OeaLauncherActivity : Activity() {
    private lateinit var homeRoot: FrameLayout
    private lateinit var oeaHomeController: OeaHomeController
    private var safetyNet: OeaLauncherSafetyNet? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setBackgroundDrawable(ColorDrawable(Color.rgb(18, 18, 20)))
        super.onCreate(savedInstanceState)

        homeRoot = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(18, 18, 20))
        }
        setContentView(homeRoot)

        oeaHomeController = OeaHomeController()
        oeaHomeController.onHomeAttached(this, homeRoot)
    }

    override fun onResume() {
        super.onResume()
        safetyNet = OeaLauncherSafetyNet(this).also { it.start() }
    }

    override fun onDestroy() {
        safetyNet = null
        if (::oeaHomeController.isInitialized) oeaHomeController.onHomeDetached()
        super.onDestroy()
    }
}
