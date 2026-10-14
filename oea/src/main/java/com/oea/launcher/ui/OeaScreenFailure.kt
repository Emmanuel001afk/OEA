package com.oea.launcher.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.oea.launcher.fault.OeaFaultLog
import com.oea.launcher.fault.OeaFaultShield

/**
 * OEA's in-place failure surface: the screen a sub-app shows when it cannot
 * continue, so the fault stops there instead of becoming a force-close.
 *
 * Rules:
 * - Home may only use it while Home has never rendered (see OeaLauncherActivity);
 *   a working launcher is never replaced by an error page.
 * - Games and other OEA apps use it freely: the launcher stays behind them.
 * - Building the surface may not throw. If anything inside it fails, a plain
 *   minimal message is installed instead, and the original fault stays in
 *   [OeaFaultLog].
 */
object OeaScreenFailure {
    private const val TAG = "OeaScreenFailure"

    fun show(activity: Activity, screen: String, error: Throwable) = show(activity, screen, error, null, null, null)

    /**
     * @param palette theme to render with; null uses OEA's dark diagnostic look.
     * @param retryLabel label for the primary action; null means "Try again".
     * @param onRetry what to do on that action; null recreates the activity.
     * @param onClose optional way out (finish this screen); shown as a quiet button.
     */
    fun show(
        activity: Activity,
        screen: String,
        error: Throwable,
        palette: OeaPalette?,
        retryLabel: String?,
        onRetry: (() -> Unit)?,
        onClose: (() -> Unit)? = null,
    ) {
        OeaFaultShield.note(screen, error)
        val built = runCatching {
            activity.setContentView(build(activity, screen, error, palette, retryLabel, onRetry, onClose))
        }.isSuccess
        if (!built) showMinimal(activity, screen, error)
    }

    private fun build(
        activity: Activity,
        screen: String,
        error: Throwable,
        palette: OeaPalette?,
        retryLabel: String?,
        onRetry: (() -> Unit)?,
        onClose: (() -> Unit)?,
    ): View {
        val dark = palette?.dark ?: true
        val backgroundColor = palette?.background ?: Color.rgb(12, 15, 21)
        val textColor = palette?.text ?: Color.WHITE
        val secondary = palette?.textSecondary ?: Color.rgb(184, 192, 207)
        val accent = palette?.accent ?: Color.rgb(138, 180, 248)
        val card = palette?.card ?: Color.rgb(22, 26, 34)
        val stroke = palette?.stroke ?: Color.argb(28, 255, 255, 255)
        val danger = palette?.danger ?: Color.rgb(255, 128, 128)

        fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density + 0.5f).toInt()
        fun label(value: String, size: Float, color: Int, face: Typeface = OeaType.regular) = TextView(activity).apply {
            text = value
            textSize = size
            setTextColor(color)
            typeface = face
        }

        val root = ScrollView(activity).apply {
            setBackgroundColor(backgroundColor)
            isFillViewport = true
        }
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(28))
        }
        root.addView(column, ViewGroup.LayoutParams(-1, -2))

        val headline = label(screen, 24f, textColor, OeaType.medium)
        column.addView(headline)
        val intro = label("This part of OEA stopped, so nothing else was affected. Your Home screen, layout and settings are untouched.", 14f, secondary)
        intro.setLineSpacing(dp(3).toFloat(), 1f)
        val introParams = LinearLayout.LayoutParams(-1, -2)
        introParams.topMargin = dp(8)
        column.addView(intro, introParams)

        // What actually failed, in a copyable card, so the cause is never hidden.
        val detail = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(card)
                cornerRadius = dp(18).toFloat()
                setStroke(maxOf(1, dp(1)), stroke)
            }
            isLongClickable = true
        }
        detail.addView(label(error.javaClass.simpleName, 13f, danger, OeaType.medium))
        val message = error.message?.takeIf { it.isNotBlank() } ?: "No message was reported."
        val messageView = label(message, 12.5f, secondary)
        messageView.typeface = Typeface.create("monospace", Typeface.NORMAL)
        val messageParams = LinearLayout.LayoutParams(-1, -2)
        messageParams.topMargin = dp(4)
        detail.addView(messageView, messageParams)
        val cause = error.cause
        if (cause != null) {
            val causeView = label("caused by ${cause.javaClass.simpleName}: ${cause.message.orEmpty()}", 12f, secondary)
            causeView.typeface = Typeface.create("monospace", Typeface.NORMAL)
            val causeParams = LinearLayout.LayoutParams(-1, -2)
            causeParams.topMargin = dp(4)
            detail.addView(causeView, causeParams)
        }
        column.addView(detail, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })

        val buttons = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        fun button(value: String, filled: Boolean, action: () -> Unit): TextView = TextView(activity).apply {
            text = value
            textSize = 14f
            typeface = OeaType.medium
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(12), dp(18), dp(12))
            isAllCaps = false
            background = GradientDrawable().apply {
                setColor(if (filled) accent else card)
                cornerRadius = dp(22).toFloat()
                if (!filled) setStroke(maxOf(1, dp(1)), stroke)
            }
            setTextColor(if (filled) (palette?.onAccent ?: Color.rgb(16, 20, 28)) else textColor)
            setOnClickListener { OeaFaultShield.guard("failure:action") { action() } }
        }
        if (onClose != null) {
            buttons.addView(button("Close", false, onClose), LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
        }
        buttons.addView(button("Copy details", false, { copyDetails(activity, screen, error) }), LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
        buttons.addView(button(retryLabel ?: "Try again", true, { onRetry?.invoke() ?: activity.recreate() }))
        column.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })

        val recent = OeaFaultLog.count()
        if (recent > 1) {
            val hint = label("$recent problems have been recorded on this device. Open OEA Settings › Stability to see them.", 12.5f, secondary)
            hint.setLineSpacing(dp(2).toFloat(), 1f)
            val hintParams = LinearLayout.LayoutParams(-1, -2)
            hintParams.topMargin = dp(14)
            column.addView(hint, hintParams)
            val detailsParams = LinearLayout.LayoutParams(-2, -2)
            detailsParams.topMargin = dp(10)
            column.addView(button("Stability details", false, { openStability(activity) }), detailsParams)
        }
        return root
    }

    private fun copyDetails(activity: Activity, screen: String, error: Throwable) {
        val text = buildString {
            append(screen).append('\n')
            append(error.javaClass.name).append(": ").append(error.message).append('\n')
            error.stackTrace.take(14).forEach { append("  at ").append(it).append('\n') }
            append("\nRecent OEA faults:\n")
            OeaFaultLog.history().take(6).forEach { append("- ").append(it.body()).append('\n') }
        }
        runCatching {
            val manager = activity.getSystemService(android.content.ClipboardManager::class.java)
            manager?.setPrimaryClip(android.content.ClipData.newPlainText("OEA diagnosis", text))
            android.widget.Toast.makeText(activity, "Diagnosis copied", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun openStability(activity: Activity) {
        runCatching {
            activity.startActivity(Intent(activity, com.oea.launcher.OeaSystemToolsActivity::class.java)
                .putExtra(com.oea.launcher.OeaSystemToolsActivity.EXTRA_SCREEN, "stability")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Absolutely last resort: text only, no custom drawing that could also fail. */
    private fun showMinimal(activity: Activity, screen: String, error: Throwable) {
        runCatching {
            val view = TextView(activity).apply {
                text = "$screen stopped (${error.javaClass.simpleName}). Your Home screen and settings are safe."
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.rgb(12, 15, 21))
                setPadding(48, 48, 48, 48)
                textSize = 15f
                gravity = Gravity.CENTER
            }
            activity.setContentView(view)
        }
        android.util.Log.e(TAG, "$screen failed and the failure screen could not be built", error)
    }
}
