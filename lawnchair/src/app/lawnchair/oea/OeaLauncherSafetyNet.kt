package app.lawnchair.oea

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.lawnchair.oea.engine.OeaEngine
import app.lawnchair.oea.ui.OeaHomeSurfaceController

/**
 * OEA launcher safety net.
 *
 * OEA owns the visible launcher surface. This is only a last-resort recovery layer: if the
 * OEA surface itself failed to attach, it exposes a functional app surface instead of leaving
 * the user on a black screen.
 *
 * It deliberately does not replace Launcher3 features during normal operation.
 */
class OeaLauncherSafetyNet(private val launcher: Activity) {
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Starts the non-critical recovery check after Launcher3 has had a chance to draw. */
    fun start() {
        scheduleHealthCheck(launcher, 1500L)
    }

    private fun scheduleHealthCheck(activity: Launcher, delayMs: Long) {
        mainHandler.postDelayed({
            if (activity.isFinishing || activity.isDestroyed) return@postDelayed
            val root = activity.window?.decorView as? ViewGroup ?: return@postDelayed

            // Never rebind or manipulate Launcher3's model from the safety layer.
            // The independent OEA engine is already running and is used as the recovery source.
            if (!hasUsableLauncherContent(root)) {
                installRecoverySurface(activity, root)
            }
        }, delayMs)
    }

    private fun hasUsableLauncherContent(root: ViewGroup): Boolean {
        if (root.findViewWithTag<View>(OeaHomeSurfaceController.OEA_HOME_TAG) != null) return true
        if (root.findViewWithTag<View>(TAG) != null) return true

        return false
    }

    private fun installRecoverySurface(activity: Activity, root: ViewGroup) {
        if (root.findViewWithTag<View>(TAG) != null) return

        val scroll = ScrollView(activity).apply {
            tag = TAG
            setBackgroundColor(Color.BLACK)
            isFillViewport = true
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(activity, 24), dp(activity, 32), dp(activity, 24), dp(activity, 32))
        }

        content.addView(TextView(activity).apply {
            text = "OEA Launcher Recovery"
            textSize = 28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }, match())
        content.addView(TextView(activity).apply {
            text = "The launcher did not load its Home content. Your apps are still installed."
            textSize = 15f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
        }, match())

        val apps = OeaEngine.get(activity).apps.value

        val grid = GridLayout(activity).apply {
            columnCount = 4
            useDefaultMargins = true
            alignmentMode = GridLayout.ALIGN_BOUNDS
        }
        for (app in apps) {
            val label = app.label
            val icon = appContextIcon(activity, app.component)
            grid.addView(Button(activity).apply {
                text = label
                setTextColor(Color.WHITE)
                setCompoundDrawablesWithIntrinsicBounds(null, icon, null, null)
                setOnClickListener { OeaEngine.get(activity).launch(app.component, app.user) }
            }, GridLayout.LayoutParams().apply {
                width = 0
                height = dp(activity, 92)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            })
        }
        content.addView(grid, match())

        content.addView(Button(activity).apply {
            text = "Switch default Home launcher"
            setOnClickListener { startSettings(activity, Settings.ACTION_HOME_SETTINGS) }
        }, match())

        content.addView(Button(activity).apply {
            text = "OEA app settings"
            setOnClickListener {
                runCatching {
                    activity.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${activity.packageName}"),
                        ),
                    )
                }
            }
        }, match())

        content.addView(Button(activity).apply {
            text = "Widgets"
            setOnClickListener {
                runCatching {
                    activity.startActivity(
                        Intent("android.intent.action.PICK").addCategory(Intent.CATEGORY_DEFAULT),
                    )
                }
            }
        }, match())

        content.addView(Button(activity).apply {
            text = "Restart launcher"
            setOnClickListener {
                root.removeView(scroll)
                activity.recreate()
            }
        }, match())

        scroll.addView(content, match())
        root.addView(scroll, ViewGroup.LayoutParams(-1, -1))
    }

    private fun appContextIcon(activity: Activity, component: ComponentName) =
        runCatching { activity.packageManager.getActivityIcon(component) }.getOrNull()

    private fun startSettings(activity: Activity, action: String) {
        runCatching { activity.startActivity(Intent(action)) }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun match(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = 8
        }

    companion object {
        private const val TAG = "oea_launcher_recovery"
    }
}
