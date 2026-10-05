package app.lawnchair

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import app.lawnchair.oea.OeaLauncherSafetyNet
import app.lawnchair.oea.runtime.OeaHomeController
import com.android.launcher3.Launcher

/**
 * OEA's HOME activity.
 *
 * This is intentionally a thin Android/Launcher3 compatibility shell. The visible HOME,
 * application inventory, search and launching are owned by OEA, not by Lawnchair's Quickstep
 * activity or Launcher3's workspace model.
 */
class OeaLauncherActivity : Launcher() {
    private val oeaHomeController = OeaHomeController()
    private var safetyNet: OeaLauncherSafetyNet? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // Give the HOME window a real surface before the Launcher3 compatibility shell draws.
        // This prevents a failed/slow legacy workspace bind from presenting a black window.
        window.setBackgroundDrawable(ColorDrawable(Color.rgb(18, 18, 20)))
        super.onCreate(savedInstanceState)
        oeaHomeController.onLauncherAttached(this)
    }

    override fun onResume() {
        super.onResume()
        safetyNet = OeaLauncherSafetyNet(this).also { it.start() }
    }

    override fun onDestroy() {
        safetyNet = null
        oeaHomeController.onLauncherDetached()
        super.onDestroy()
    }
}
