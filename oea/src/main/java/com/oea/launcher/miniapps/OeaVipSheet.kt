package com.oea.launcher.miniapps

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.graphics.drawable.RippleDrawable
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.oea.launcher.R
import com.oea.launcher.ui.OeaIconToggle
import com.oea.launcher.ui.OeaPalette
import com.oea.launcher.ui.OeaSlider
import com.oea.launcher.ui.OeaToggle
import com.oea.launcher.ui.OeaType

/**
 * The one sheet style VIP's player uses.
 *
 * The player is a black window with no theme of its own, so a normal OEA sheet —
 * which follows the light or dark app palette — would float over it like a dialog
 * from another app. These views are painted for the player instead: translucent
 * dark cards, white labels, the accent only where a choice is live.
 */
internal class OeaVipSheet private constructor(
    private val activity: Activity,
    private val palette: OeaPalette,
    val dialog: Dialog,
    val panel: LinearLayout
) {

    private val density = activity.resources.displayMetrics.density

    fun dp(value: Int): Int = (value * density + 0.5f).toInt()

    /** The header label, so a caller can rename the sheet in place. */
    var titleView: TextView? = null

    // ------------------------------------------------------------------- shell

    fun heading(value: String) {
        panel.addView(
            TextView(activity).apply {
                text = value
                setTextColor(0xB3FFFFFF.toInt())
                textSize = 12.5f
                letterSpacing = 0.08f
                setAllCaps(true)
                setPadding(dp(4), dp(18), dp(4), dp(8))
            },
            LinearLayout.LayoutParams(-1, -2)
        )
    }

    fun group(): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(0x1AFFFFFF)
            cornerRadius = dp(18).toFloat()
            setStroke(dp(1), 0x22FFFFFF)
        }
        clipToOutline = true
        panel.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }

    fun note(value: String) {
        panel.addView(
            TextView(activity).apply {
                text = value
                setTextColor(0x99FFFFFF.toInt())
                textSize = 12.5f
                setLineSpacing(dp(2).toFloat(), 1f)
                setPadding(dp(6), dp(6), dp(6), dp(2))
            },
            LinearLayout.LayoutParams(-1, -2)
        )
    }

    /** A settings row inside [group]. [selected] lights the icon up. */
    fun row(
        group: LinearLayout,
        iconRes: Int,
        label: String,
        detail: String? = null,
        value: String? = null,
        selected: Boolean = false,
        destructive: Boolean = false,
        trailing: View? = null,
        onClick: (() -> Unit)? = null
    ): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(13), dp(12), dp(13))
            background = ripple(rounded(0x0AFFFFFF, 0))
            isClickable = onClick != null || trailing is OeaToggle
            if (onClick != null) {
                setOnClickListener { runCatching { onClick() } }
            } else if (trailing is OeaToggle) {
                setOnClickListener { runCatching { trailing.toggle(fromUser = true) } }
            }
        }
        if (iconRes != 0) {
            row.addView(
                ImageView(activity).apply {
                    setImageResource(iconRes)
                    setColorFilter(if (selected) palette.accent else if (destructive) 0xFFFF8A80.toInt() else 0xCCFFFFFF.toInt())
                },
                LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(14) }
            )
        }
        val texts = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(
            TextView(activity).apply {
                text = label
                setTextColor(if (destructive) 0xFFFF8A80.toInt() else Color.WHITE)
                textSize = 15f
                typeface = OeaType.medium
                maxLines = 2
            }
        )
        if (!detail.isNullOrBlank()) {
            texts.addView(
                TextView(activity).apply {
                    text = detail
                    setTextColor(0x99FFFFFF.toInt())
                    textSize = 12f
                    setPadding(0, dp(2), 0, 0)
                    maxLines = 2
                }
            )
        }
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        if (!value.isNullOrBlank()) {
            row.addView(
                TextView(activity).apply {
                    text = value
                    setTextColor(palette.accent)
                    textSize = 13f
                    typeface = OeaType.medium
                    setPadding(dp(10), 0, 0, 0)
                    maxLines = 1
                }
            )
        }
        if (trailing != null) {
            row.addView(trailing, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
        } else if (onClick != null) {
            row.addView(
                ImageView(activity).apply {
                    setImageResource(R.drawable.oic_chevron_right)
                    setColorFilter(0x66FFFFFF.toInt())
                },
                LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(6) }
            )
        }
        if (group.childCount > 0) {
            group.addView(
                View(activity).apply { setBackgroundColor(0x1AFFFFFF) },
                LinearLayout.LayoutParams(-1, maxOf(1, dp(1)))
            )
        }
        group.addView(row, LinearLayout.LayoutParams(-1, -2))
        return row
    }

    /** A row with a switch. Returns the switch so a caller can flip it later. */
    fun switch(group: LinearLayout, iconRes: Int, label: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit): OeaToggle {
        val toggle = OeaToggle(activity).apply {
            accent = palette.accent
            onAccent = palette.onAccent
            trackOff = 0x33FFFFFF
            thumbOff = 0x99FFFFFF.toInt()
            setChecked(checked, animate = false)
            onCheckedChange = onChange
        }
        row(group, iconRes, label, detail, trailing = toggle)
        return toggle
    }

    /** A scrollable single-choice strip — speed, aspect, repeat, EQ presets. */
    inner class Chips(private val chips: List<TextView>) {
        var selectedIndex: Int = -1
            private set

        fun select(which: Int) {
            selectedIndex = which
            chips.forEachIndexed { index, chip ->
                chip.background = rounded(if (index == which) palette.accent else 0x1FFFFFFF, 18)
                chip.setTextColor(if (index == which) palette.onAccent else Color.WHITE)
                chip.typeface = if (index == which) OeaType.medium else OeaType.regular
            }
        }
    }

    fun chips(parent: ViewGroup, labels: List<String>, selected: Int, onPick: (Int) -> Unit): Chips {
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(2), 0, dp(2), 0) }
        val chips = ArrayList<TextView>(labels.size)
        val holder = Chips(chips)
        labels.forEachIndexed { index, label ->
            val chip = TextView(activity).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 13f
                setPadding(dp(14), dp(9), dp(14), dp(9))
                typeface = if (index == selected) OeaType.medium else OeaType.regular
                background = rounded(if (index == selected) palette.accent else 0x1FFFFFFF, 18)
                setTextColor(if (index == selected) palette.onAccent else Color.WHITE)
                isClickable = true
                setOnClickListener {
                    holder.select(index)
                    onPick(index)
                }
            }
            chips.add(chip)
            row.addView(chip, LinearLayout.LayoutParams(-2, -2).apply {
                marginStart = if (index == 0) 0 else dp(7)
            })
        }
        parent.addView(
            HorizontalScrollView(activity).apply {
                isHorizontalScrollBarEnabled = false
                addView(row, FrameLayout.LayoutParams(-2, -2))
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4); bottomMargin = dp(4) }
        )
        holder.select(selected)
        return holder
    }

    /** A slider with a live value readout. */
    fun slider(
        parent: ViewGroup,
        label: String,
        min: Float,
        max: Float,
        steps: Int,
        value: Float,
        format: (Float) -> String,
        onValue: (Float, Boolean) -> Unit
    ): OeaSlider {
        val slider = OeaSlider(activity).apply {
            minValue = min
            maxValue = max
            this.steps = steps
            this.format = format
            setColors(palette, onDark = true)
            this.value = value
        }
        val readout = TextView(activity).apply {
            text = format(value)
            setTextColor(palette.accent)
            textSize = 13f
            typeface = OeaType.medium
        }
        slider.onValue = { v, final ->
            readout.text = format(v)
            onValue(v, final)
        }
        parent.addView(
            LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6), dp(6), dp(6), 0)
                addView(
                    TextView(activity).apply {
                        text = label
                        setTextColor(Color.WHITE)
                        textSize = 14f
                    },
                    LinearLayout.LayoutParams(0, -2, 1f)
                )
                addView(readout)
            },
            LinearLayout.LayoutParams(-1, -2)
        )
        parent.addView(slider, LinearLayout.LayoutParams(-1, dp(40)))
        return slider
    }

    /** A text field, so the player can ask for a name without a dialog theme. */
    fun input(parent: ViewGroup, hint: String, initial: String): EditText {
        val field = EditText(activity).apply {
            setHint(hint)
            setText(initial)
            setTextColor(Color.WHITE)
            setHintTextColor(0x80FFFFFF.toInt())
            textSize = 16f
            maxLines = 1
            isSingleLine = true
            typeface = OeaType.medium
            background = null
            setPadding(dp(14), dp(14), dp(14), dp(12))
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        }
        parent.addView(
            field,
            LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(6)
                bottomMargin = dp(6)
            }
        )
        field.requestFocus()
        return field
    }

    /** An icon toggle used as a labelled button inside a sheet. */
    fun iconToggle(parent: ViewGroup, iconRes: Int, label: String, selected: Boolean, onToggle: (Boolean) -> Unit): OeaIconToggle {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        val toggle = OeaIconToggle(activity).apply {
            icon = activity.getDrawable(iconRes)?.mutate()?.apply { setTint(if (selected) palette.onAccent else Color.WHITE) }
            selectedBg = palette.accent
            idleBg = 0x1FFFFFFF
            this.selected = selected
            circleSizeDp = 46
            onChanged = { onToggle(this.selected) }
        }
        box.addView(toggle, LinearLayout.LayoutParams(dp(46), dp(46)))
        box.addView(
            TextView(activity).apply {
                text = label
                setTextColor(0xB3FFFFFF.toInt())
                textSize = 11.5f
                setPadding(0, dp(4), 0, 0)
            }
        )
        parent.addView(box, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10) })
        return toggle
    }

    fun buttons(vararg entries: Pair<String, () -> Unit>) {
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        entries.forEachIndexed { index, entry ->
            row.addView(
                TextView(activity).apply {
                    text = entry.first
                    gravity = Gravity.CENTER
                    textSize = 14.5f
                    typeface = OeaType.medium
                    setTextColor(if (index == entries.lastIndex) palette.accent else Color.WHITE)
                    background = ripple(rounded(0x14FFFFFF, 16))
                    isClickable = true
                    setOnClickListener {
                        runCatching { entry.second() }
                        dismiss()
                    }
                },
                LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                    marginStart = if (index == 0) 0 else dp(8)
                }
            )
        }
        panel.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    }

    private fun rounded(color: Int, radiusDp: Int) = android.graphics.drawable.GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    /** [content] is the resting tint, the ripple is drawn over it. */
    private fun ripple(content: android.graphics.drawable.Drawable?) = RippleDrawable(
        android.content.res.ColorStateList.valueOf(0x2AFFFFFF),
        null,
        content
    )

    fun dismiss() {
        runCatching { if (dialog.isShowing) dialog.dismiss() }
    }

    fun show() {
        runCatching { dialog.show() }
        panel.alpha = 0f
        panel.translationY = dp(40).toFloat()
        panel.animate().alpha(1f).translationY(0f).setDuration(180L).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
    }

    companion object {
        fun build(activity: Activity, palette: OeaPalette, title: String, subtitle: String? = null): OeaVipSheet {
            val dialog = Dialog(activity)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            val panel = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp0(activity, 18), dp0(activity, 16), dp0(activity, 18), dp0(activity, 22))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xF20C0E13.toInt())
                    cornerRadii = floatArrayOf(dp0f(activity, 26f), dp0f(activity, 26f), dp0f(activity, 26f), dp0f(activity, 26f), 0f, 0f, 0f, 0f)
                }
            }
            val header = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val grabber = View(activity).apply {
                background = rounded(activity, 0x33FFFFFF, 2)
            }
            header.addView(grabber, LinearLayout.LayoutParams(-1, dp0(activity, 3)).apply { bottomMargin = dp0(activity, 12) })
            val titles = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            val titleView = TextView(activity).apply {
                text = title
                setTextColor(Color.WHITE)
                textSize = 19f
                typeface = OeaType.medium
                maxLines = 1
            }
            titles.addView(titleView)
            if (!subtitle.isNullOrBlank()) {
                titles.addView(
                    TextView(activity).apply {
                        text = subtitle
                        setTextColor(0x99FFFFFF.toInt())
                        textSize = 12.5f
                        maxLines = 2
                        setPadding(0, dp0(activity, 3), 0, 0)
                    }
                )
            }
            header.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
            header.addView(
                ImageView(activity).apply {
                    setImageResource(R.drawable.oic_close)
                    setColorFilter(0xB3FFFFFF.toInt())
                    setPadding(dp0(activity, 6), dp0(activity, 6), dp0(activity, 6), dp0(activity, 6))
                    isClickable = true
                    setOnClickListener { runCatching { dialog.dismiss() } }
                },
                LinearLayout.LayoutParams(dp0(activity, 34), dp0(activity, 34))
            )
            panel.addView(header, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp0(activity, 8) })
            val scroll = android.widget.ScrollView(activity).apply {
                isVerticalScrollBarEnabled = false
                addView(panel, FrameLayout.LayoutParams(-1, -2))
            }
            val root = FrameLayout(activity).apply {
                setBackgroundColor(Color.argb(0x66, 0, 0, 0))
                isClickable = true
                setOnClickListener { runCatching { dialog.dismiss() } }
                addView(scroll, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            }
            dialog.setContentView(root)
            dialog.window?.apply {
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setDimAmount(0.5f)
                setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
            val sheet = OeaVipSheet(activity, palette, dialog, panel)
            sheet.titleView = titleView
            return sheet
        }

        private fun rounded(context: Activity, color: Int, radiusDp: Int) = android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = (radiusDp * context.resources.displayMetrics.density)
        }

        private fun dp0(activity: Activity, value: Int): Int = (value * activity.resources.displayMetrics.density + 0.5f).toInt()

        private fun dp0f(activity: Activity, value: Float): Float = value * activity.resources.displayMetrics.density
    }
}
