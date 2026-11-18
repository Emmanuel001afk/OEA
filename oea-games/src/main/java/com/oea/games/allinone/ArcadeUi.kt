package com.oea.games.allinone

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The arcade's own furniture: warm paper sheets, chunky rounded buttons and the
 * cream dialog used by the game fronts, pause menu and lobby.
 *
 * Games in OEA usually borrow the dark launcher kit. The arcade has its own,
 * brighter room — the same one the wall of cabinets lives in — so sheets, cards
 * and buttons here match the hub instead of turning the whole app dark the
 * moment a game opens.
 */
object ArcadeUi {

    fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    fun rounded(color: Int, radiusPx: Int, stroke: Int = 0, strokePx: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radiusPx.toFloat()
            if (strokePx > 0) setStroke(strokePx, stroke)
        }

    fun text(
        context: Context,
        value: String,
        size: Float,
        color: Int,
        bold: Boolean = false,
        mono: Boolean = false,
    ): TextView =
        TextView(context).apply {
            text = value
            textSize = size
            setTextColor(color)
            includeFontPadding = false
            typeface = android.graphics.Typeface.create(
                if (mono) "monospace" else "sans-serif",
                if (bold || mono) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
            )
        }

    fun button(
        context: Context,
        label: String,
        background: Int,
        foreground: Int,
        radiusDp: Int = 18,
        onClick: () -> Unit,
    ): TextView = text(context, label, 14f, foreground, bold = true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(context, 18f), dp(context, 12f), dp(context, 18f), dp(context, 12f))
        this.background = RippleDrawable(
            ColorStateList.valueOf(0x26000000),
            rounded(background, dp(context, radiusDp.toFloat())),
            rounded(Color.WHITE, dp(context, radiusDp.toFloat()))
        )
        isClickable = true
        setOnClickListener { onClick() }
    }

    fun outlineButton(context: Context, label: String, color: Int, onClick: () -> Unit): TextView =
        text(context, label, 14f, color, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(context, 18f), dp(context, 12f), dp(context, 18f), dp(context, 12f))
            background = RippleDrawable(
                ColorStateList.valueOf(0x22000000),
                rounded(Color.TRANSPARENT, dp(context, 18f), color, dp(context, 1.2f)),
                rounded(Color.WHITE, dp(context, 18f))
            )
            isClickable = true
            setOnClickListener { onClick() }
        }

    /** A full-width row with a title, a description and an optional trailing glyph. */
    fun row(
        context: Context,
        glyph: String,
        title: String,
        description: String?,
        onClick: () -> Unit,
    ): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 14f), dp(context, 12f), dp(context, 12f), dp(context, 12f))
            background = RippleDrawable(
                ColorStateList.valueOf(0x14000000),
                rounded(0xFFFFF7EC.toInt(), dp(context, 16f), 0xFFE8D9C4.toInt(), dp(context, 1f)),
                rounded(Color.WHITE, dp(context, 16f))
            )
            isClickable = true
            setOnClickListener { onClick() }
        }
        row.addView(
            text(context, glyph, 20f, ArcadeColors.INK).apply {
                gravity = Gravity.CENTER
                background = rounded(0xFFF3E6D4.toInt(), dp(context, 14f))
            },
            LinearLayout.LayoutParams(dp(context, 42f), dp(context, 42f)).apply { rightMargin = dp(context, 12f) }
        )
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(text(context, title, 14.5f, ArcadeColors.INK, bold = true))
        if (!description.isNullOrBlank()) {
            column.addView(
                text(context, description, 11.5f, ArcadeColors.INK_SOFT).apply { setPadding(0, dp(context, 2f), 0, 0) }
            )
        }
        row.addView(column, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(text(context, "›", 22f, ArcadeColors.INK_SOFT, bold = true))
        return row
    }

    /**
     * A cream sheet with a title, a scrollable body, and the app's own buttons.
     * The caller adds views to the body; [Dialog] is passed for dismissal.
     */
    fun dialog(
        activity: Activity,
        title: String,
        subtitle: String? = null,
        maxBodyFraction: Float = 0.62f,
        build: (LinearLayout, Dialog) -> Unit,
    ): Dialog {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 20f), dp(activity, 18f), dp(activity, 20f), dp(activity, 16f))
            background = rounded(0xFFFFFBF4.toInt(), dp(activity, 26f))
        }
        panel.addView(text(activity, title, 21f, ArcadeColors.INK, bold = true))
        if (!subtitle.isNullOrBlank()) {
            panel.addView(
                text(activity, subtitle, 12.5f, ArcadeColors.INK_SOFT).apply {
                    setPadding(0, dp(activity, 5f), 0, dp(activity, 6f))
                    setLineSpacing(dp(activity, 2f).toFloat(), 1f)
                }
            )
        }
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            addView(body)
        }
        val maxHeight = (activity.resources.displayMetrics.heightPixels * maxBodyFraction).toInt()
        panel.addView(scroll, LinearLayout.LayoutParams(-1, maxHeight))
        build(body, dialog)
        dialog.setContentView(panel)
        dialog.window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.setLayout(
                (activity.resources.displayMetrics.widthPixels * 0.94f).toInt(),
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }
        dialog.show()
        return dialog
    }

    /** A simple list of choices, styled like the rest of the arcade. */
    fun choose(
        activity: Activity,
        title: String,
        subtitle: String?,
        options: List<String>,
        selected: Int,
        onPick: (Int) -> Unit,
    ) {
        dialog(activity, title, subtitle, 0.6f) { body, d ->
            options.forEachIndexed { index, option ->
                val active = index == selected
                val row = text(activity, option, 14f, if (active) ArcadeColors.INK else ArcadeColors.INK_SOFT, bold = active).apply {
                    setPadding(dp(activity, 14f), dp(activity, 13f), dp(activity, 12f), dp(activity, 13f))
                    background = RippleDrawable(
                        ColorStateList.valueOf(0x14000000),
                        rounded(
                            if (active) 0xFFEAF7F1.toInt() else 0xFFFFF7EC.toInt(),
                            dp(activity, 14f),
                            if (active) ArcadeColors.MINT else 0xFFE8D9C4.toInt(),
                            dp(activity, 1f)
                        ),
                        rounded(Color.WHITE, dp(activity, 14f))
                    )
                    isClickable = true
                    setOnClickListener { d.dismiss(); onPick(index) }
                }
                body.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(activity, 6f) })
            }
            body.addView(
                outlineButton(activity, "Close", ArcadeColors.INK_SOFT) { d.dismiss() },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(activity, 8f) }
            )
        }
    }

    /** A horizontal strip of chips, one per option. */
    fun chips(
        context: Context,
        labels: List<String>,
        selected: Int,
        accent: Int,
        onPick: (Int) -> Unit,
    ): View {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        labels.forEachIndexed { index, label ->
            val active = index == selected
            val chip = text(context, label, 12.5f, if (active) Color.WHITE else ArcadeColors.INK, bold = active).apply {
                gravity = Gravity.CENTER
                setPadding(dp(context, 13f), dp(context, 8f), dp(context, 13f), dp(context, 8f))
                background = RippleDrawable(
                    ColorStateList.valueOf(0x22000000),
                    if (active) rounded(accent, dp(context, 16f))
                    else rounded(0xFFFFF7EC.toInt(), dp(context, 16f), 0xFFE8D9C4.toInt(), dp(context, 1f)),
                    rounded(Color.WHITE, dp(context, 16f))
                )
                isClickable = true
                setOnClickListener { onPick(index) }
            }
            row.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(context, 7f) })
        }
        return HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }
}
