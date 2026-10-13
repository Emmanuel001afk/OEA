package com.oea.launcher.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Last-resort, native error surface so a recoverable screen error is not a force-close. */
object OeaScreenFailure {
    fun show(activity: Activity, screen: String, error: Throwable) {
        Log.e(TAG, "$screen failed to render", error)
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(28), dp(28), dp(28), dp(28))
            setBackgroundColor(Color.rgb(12, 15, 21))
        }
        root.addView(TextView(activity).apply {
            text = "$screen couldn't start"
            textSize = 25f
            setTextColor(Color.WHITE)
            typeface = OeaType.medium
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(TextView(activity).apply {
            text = "OEA kept your saved workspace and settings. Try again; diagnostic details are available in Logcat under $TAG."
            textSize = 14f
            setTextColor(0xFFB8C0CF.toInt())
            setLineSpacing(dp(3).toFloat(), 1f)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        root.addView(Button(activity).apply {
            text = "Try again"
            isAllCaps = false
            setOnClickListener { activity.recreate() }
            background = GradientDrawable().apply {
                setColor(0xFF8AB4F8.toInt())
                cornerRadius = dp(20).toFloat()
            }
            setTextColor(0xFF10141C.toInt())
        }, LinearLayout.LayoutParams(-2, dp(48)).apply { topMargin = dp(22) })
        activity.setContentView(root)
    }

    private const val TAG = "OeaScreenFailure"
}
