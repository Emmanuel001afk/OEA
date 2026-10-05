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
import com.android.launcher3.BubbleTextView
import com.android.launcher3.Launcher
import com.android.launcher3.folder.FolderIcon
import com.android.launcher3.widget.LauncherAppWidgetHostView

/**
 * OEA launcher safety net.
 *
 * Launcher3/Lawnchair remains the real launcher. This is only a last-resort recovery
 * surface: if the launcher activity is resumed but has no actual launcher content, it exposes a
 * functional app surface instead of leaving the user on a black screen.
 *
 * It deliberately does not replace Launcher3 features during normal operation.
 */
class OeaLauncherSafetyNet(private val launcher: Launcher) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var rebindAttempted = false

    /** Starts the non-critical recovery check after Launcher3 has had a chance to draw. */
    fun start() {
        scheduleHealthCheck(launcher, 1500L)
    }

    private fun scheduleHealthCheck(activity: Launcher, delayMs: Long) {
        mainHandler.postDelayed({
            if (activity.isFinishing || activity.isDestroyed) return@postDelayed
            val root = activity.window?.decorView as? ViewGroup ?: return@postDelayed

            // Give the real Launcher3 model one controlled rebind before exposing recovery.
            // OEA never replaces the Launcher3 workspace during normal operation.
            if (!hasUsableLauncherContent(root)) {
                if (!rebindAttempted) {
                    rebindAttempted = true
                    runCatching {
                        com.android.launcher3.LauncherAppState.getInstance(activity)
                            .model.rebindCallbacks()
                    }
                    scheduleHealthCheck(activity, 1200L)
                } else {
                    installRecoverySurface(activity, root)
                }
            }
        }, delayMs)
    }

    private fun hasUsableLauncherContent(root: ViewGroup): Boolean {
        if (root.findViewWithTag<View>(TAG) != null) return true

        var meaningfulItems = 0
        fun walk(view: View) {
            if (view.visibility != View.VISIBLE || view.width <= 0 || view.height <= 0) return
            if (view is BubbleTextView || view is FolderIcon || view is LauncherAppWidgetHostView) {
                meaningfulItems++
                return
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    walk(view.getChildAt(i))
                    if (meaningfulItems >= 1) return
                }
            }
        }

        walk(root)
        return meaningfulItems > 0
    }

    private fun installRecoverySurface(activity: Launcher, root: ViewGroup) {
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

        val apps = activity.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            0,
        ).sortedBy { it.loadLabel(activity.packageManager).toString().lowercase() }

        val grid = GridLayout(activity).apply {
            columnCount = 4
            useDefaultMargins = true
            alignmentMode = GridLayout.ALIGN_BOUNDS
        }
        for (resolveInfo in apps) {
            val label = resolveInfo.loadLabel(activity.packageManager).toString()
            val icon = resolveInfo.loadIcon(activity.packageManager)
            grid.addView(Button(activity).apply {
                text = label
                setTextColor(Color.WHITE)
                setCompoundDrawablesWithIntrinsicBounds(null, icon, null, null)
                setOnClickListener {
                    runCatching {
                        val launch = Intent(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_LAUNCHER)
                            component = ComponentName(
                                resolveInfo.activityInfo.packageName,
                                resolveInfo.activityInfo.name,
                            )
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        activity.startActivity(launch)
                    }
                }
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
