package com.oea.launcher.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import com.oea.launcher.R
import com.oea.launcher.icons.OeaIconController
import com.oea.launcher.model.OeaAppInfo

/** Typefaces shared by every OEA surface. */
object OeaType {
    val regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val light: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    val thin: Typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
    val bold: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
    val condensed: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
}

/** Insets helper that works on every supported API level. */
object OeaInsets {
    /** left, top, right, bottom of the system bars and display cutout. */
    fun bars(insets: WindowInsets): IntArray = if (Build.VERSION.SDK_INT >= 30) {
        val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        intArrayOf(i.left, i.top, i.right, i.bottom)
    } else {
        @Suppress("DEPRECATION")
        intArrayOf(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
    }

    fun ime(insets: WindowInsets): Int = if (Build.VERSION.SDK_INT >= 30) {
        insets.getInsets(WindowInsets.Type.ime()).bottom
    } else 0

    /**
     * Transparent, edge-to-edge system bars with icon colours matching the surface.
     *
     * This must never throw, and there is one specific reason it used to:
     * `Window.getInsetsController()` does not create the window's decor — it
     * dereferences it. Called before the decor exists (which is exactly the case
     * when an OEA screen sets up its bars *before* `setContentView`, as every OEA
     * screen does) it throws a NullPointerException on `DecorView`. On older
     * Android the deprecated `statusBarColor` setter happened to install the decor
     * first, so the crash only appeared on newer versions, where that setter is a
     * no-op — which is why OEA Home and OEA Settings fell over together.
     *
     * The fix is to ask for `window.decorView` (which *does* install it) and to
     * treat every step as optional.
     */
    fun edgeToEdge(window: Window, lightBars: Boolean) {
        runCatching {
            @Suppress("DEPRECATION")
            window.statusBarColor = Color.TRANSPARENT
            @Suppress("DEPRECATION")
            window.navigationBarColor = Color.TRANSPARENT
        }
        // Ensure decor exists: peekDecorView avoids NPE, decorView installs it.
        val decor = runCatching { window.peekDecorView() ?: window.decorView }.getOrNull()
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching { window.setDecorFitsSystemWindows(false) }
            if (decor != null) {
                applyBarAppearanceInternal(window, decor, lightBars)
            } else {
                runCatching {
                    window.decorView.post {
                        runCatching {
                            val d = window.decorView
                            applyBarAppearanceInternal(window, d, lightBars)
                        }
                    }
                }
            }
        } else {
            runCatching {
                if (decor == null) return@runCatching
                var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                if (lightBars) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                @Suppress("DEPRECATION")
                decor.systemUiVisibility = flags
            }
        }
    }

    private fun applyBarAppearanceInternal(window: Window, decor: View, lightBars: Boolean) {
        if (Build.VERSION.SDK_INT < 30) return
        val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        val appearance = if (lightBars) mask else 0
        val apply: (WindowInsetsController?) -> Unit = { controller ->
            runCatching { controller?.setSystemBarsAppearance(appearance, mask) }
        }
        val controller = runCatching { decor.windowInsetsController }.getOrNull()
            ?: runCatching { window.insetsController }.getOrNull()
        if (controller != null) {
            apply(controller)
        } else {
            runCatching { decor.post { apply(runCatching { decor.windowInsetsController }.getOrNull()) } }
        }
    }

    /**
     * Icon colours for the status and navigation bars: [lightBars] means dark
     * icons on a light surface. The controller is fetched through `decorView`, and
     * if the decor is not up yet the attempt is repeated once it is — never
     * skipped, because a launcher with unreadable status-bar icons is the same bug
     * wearing a different hat.
     */
    fun applyBarAppearance(window: Window, lightBars: Boolean) {
        if (Build.VERSION.SDK_INT < 30) return
        val decor = runCatching { window.peekDecorView() ?: window.decorView }.getOrNull() ?: return
        applyBarAppearanceInternal(window, decor, lightBars)
    }
}

/**
 * OEA's view toolkit. Every OEA screen is built from these pieces so Settings,
 * Game Boost and the Home sheets share one polished visual language.
 */
open class OeaKit(val context: Context, var palette: OeaPalette) {
    private val density = context.resources.displayMetrics.density

    fun dp(v: Int): Int = (v * density + 0.5f).toInt()
    fun dpf(v: Float): Float = v * density

    fun rounded(color: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dpf(radiusDp.toFloat())
        if (strokeDp > 0) setStroke(dp(strokeDp), strokeColor)
    }

    fun roundedTop(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        val r = dpf(radiusDp.toFloat())
        cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
    }

    fun oval(color: Int, strokeColor: Int = 0, strokeDp: Int = 0) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        if (strokeDp > 0) setStroke(dp(strokeDp), strokeColor)
    }

    fun ripple(content: Drawable?, radiusDp: Int = 0, color: Int = palette.withAlpha(palette.text, 0x22)): RippleDrawable {
        val mask = if (radiusDp > 0) rounded(Color.WHITE, radiusDp) else ColorDrawable(Color.WHITE)
        return RippleDrawable(ColorStateList.valueOf(color), content, mask)
    }

    fun icon(res: Int, tint: Int): Drawable? = context.getDrawable(res)?.mutate()?.apply { setTint(tint) }

    fun appIcon(app: OeaAppInfo): Drawable? = OeaIconController.shared(context).icon(app.packageName, app.className.ifBlank { null })

    fun text(value: CharSequence, sizeSp: Float, color: Int, face: Typeface = OeaType.regular) = TextView(context).apply {
        text = value
        textSize = sizeSp
        setTextColor(color)
        typeface = face
        includeFontPadding = true
    }

    fun iconView(res: Int, tint: Int, sizeDp: Int = 24): ImageView = ImageView(context).apply {
        setImageDrawable(icon(res, tint))
        scaleType = ImageView.ScaleType.FIT_CENTER
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    /** Circular tonal badge holding an icon; the leading element of rows. */
    fun iconBadge(res: Int, bg: Int = palette.accentSoft, tint: Int = palette.onAccentSoft, sizeDp: Int = 40): FrameLayout =
        FrameLayout(context).apply {
            background = oval(bg)
            addView(ImageView(context).apply {
                setImageDrawable(icon(res, tint))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, FrameLayout.LayoutParams(dp(sizeDp * 11 / 20), dp(sizeDp * 11 / 20), Gravity.CENTER))
        }

    fun toggle(checked: Boolean, onChange: (Boolean) -> Unit): OeaToggle = OeaToggle(context).apply {
        accent = palette.accent
        onAccent = palette.onAccent
        trackOff = palette.cardHigh
        thumbOff = palette.textSecondary
        setChecked(checked)
        onCheckedChange = onChange
    }

    // ---------------------------------------------------------------- buttons

    fun primaryButton(label: String, iconRes: Int = 0, onClick: () -> Unit): TextView =
        button(label, palette.accent, palette.onAccent, iconRes, onClick)

    fun tonalButton(label: String, iconRes: Int = 0, onClick: () -> Unit): TextView =
        button(label, palette.accentSoft, palette.onAccentSoft, iconRes, onClick)

    fun quietButton(label: String, iconRes: Int = 0, onClick: () -> Unit): TextView =
        button(label, palette.cardHigh, palette.text, iconRes, onClick)

    fun button(label: String, bg: Int, fg: Int, iconRes: Int = 0, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        textSize = 14f
        typeface = OeaType.medium
        gravity = Gravity.CENTER
        setTextColor(fg)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(18), dp(11), dp(18), dp(11))
        background = ripple(rounded(bg, 22), 22)
        if (iconRes != 0) {
            compoundDrawablePadding = dp(8)
            icon(iconRes, fg)?.let { d ->
                d.setBounds(0, 0, dp(18), dp(18))
                setCompoundDrawablesRelative(d, null, null, null)
            }
        }
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    fun chip(label: String, selected: Boolean, iconRes: Int = 0, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        textSize = 13f
        typeface = OeaType.medium
        gravity = Gravity.CENTER
        maxLines = 1
        setPadding(dp(14), dp(8), dp(14), dp(8))
        val bg = if (selected) palette.accent else palette.cardHigh
        val fg = if (selected) palette.onAccent else palette.text
        setTextColor(fg)
        background = ripple(rounded(bg, 18), 18)
        if (iconRes != 0) {
            icon(iconRes, fg)?.let { d ->
                d.setBounds(0, 0, dp(16), dp(16))
                setCompoundDrawablesRelative(d, null, null, null)
                compoundDrawablePadding = dp(6)
            }
        }
        isClickable = true
        setOnClickListener { onClick() }
    }

    /** Pill segmented control: equal-width options, one selected. */
    fun segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit): LinearLayout {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = rounded(palette.cardHigh, 22)
        }
        options.forEachIndexed { index, label ->
            val active = index == selected
            bar.addView(TextView(context).apply {
                text = label
                textSize = 13f
                typeface = OeaType.medium
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(if (active) palette.onAccent else palette.textSecondary)
                background = if (active) rounded(palette.accent, 18) else ripple(null, 18)
                setPadding(dp(6), dp(9), dp(6), dp(9))
                isClickable = true
                setOnClickListener { if (!active) onSelect(index) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        return bar
    }

    // ------------------------------------------------------------------ pages

    /** Full-screen page root: scrolls, pads itself with system-bar insets. */
    class Page(val root: FrameLayout, val scroll: ScrollView, val content: LinearLayout, val header: LinearLayout)

    fun page(title: String, subtitle: String? = null, onBack: (() -> Unit)? = null, trailing: View? = null): Page {
        val root = FrameLayout(context).apply { setBackgroundColor(palette.background) }
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            clipToPadding = false
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(28))
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(14))
        }
        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (onBack != null) {
            topRow.addView(circleIconButton(R.drawable.oic_arrow_back, "Back") { onBack() },
                LinearLayout.LayoutParams(dp(44), dp(44)))
        }
        topRow.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        if (trailing != null) topRow.addView(trailing)
        if (onBack != null || trailing != null) header.addView(topRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        header.addView(text(title, 32f, palette.text, OeaType.regular).apply {
            letterSpacing = -0.01f
        })
        if (!subtitle.isNullOrBlank()) {
            header.addView(text(subtitle, 14.5f, palette.textSecondary).apply {
                setPadding(0, dp(6), 0, 0)
                setLineSpacing(dpf(2f), 1f)
            })
        }
        content.addView(header, LinearLayout.LayoutParams(-1, -2))
        scroll.addView(content, FrameLayout.LayoutParams(-1, -2))
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = OeaInsets.bars(insets)
            scroll.setPadding(bars[0], bars[1], bars[2], bars[3])
            insets
        }
        return Page(root, scroll, content, header)
    }

    fun circleIconButton(res: Int, description: String, bg: Int = palette.cardHigh, tint: Int = palette.text, onClick: () -> Unit) =
        FrameLayout(context).apply {
            background = ripple(oval(bg), 22)
            contentDescription = description
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            addView(ImageView(context).apply {
                setImageDrawable(icon(res, tint))
            }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        }

    fun sectionTitle(parent: ViewGroup, title: String) {
        parent.addView(text(title, 14f, palette.accent, OeaType.medium).apply {
            setPadding(dp(6), dp(18), dp(6), dp(8))
        }, LinearLayout.LayoutParams(-1, -2))
    }

    fun note(parent: ViewGroup, value: String) {
        parent.addView(text(value, 13f, palette.textSecondary).apply {
            setPadding(dp(6), dp(2), dp(6), dp(10))
            setLineSpacing(dpf(2f), 1f)
        }, LinearLayout.LayoutParams(-1, -2))
    }

    /** Rounded card that holds rows; rows are separated by hairline dividers. */
    fun group(parent: ViewGroup): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(palette.card, 26)
            clipToOutline = true
        }
        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        return card
    }

    private fun addToGroup(group: LinearLayout, row: View) {
        if (group.childCount > 0) {
            group.addView(View(context).apply { setBackgroundColor(palette.stroke) },
                LinearLayout.LayoutParams(-1, maxOf(1, dp(1) / 2)).apply {
                    leftMargin = dp(70)
                    rightMargin = dp(18)
                })
        }
        group.addView(row, LinearLayout.LayoutParams(-1, -2))
    }

    /**
     * A settings row. [trailing] may be a value string (shown in accent), a
     * view (e.g. a toggle) or null for a chevron when [onClick] is set.
     */
    fun row(
        group: LinearLayout,
        iconRes: Int,
        title: String,
        subtitle: String? = null,
        value: String? = null,
        trailing: View? = null,
        chevron: Boolean = true,
        iconBg: Int = palette.accentSoft,
        iconTint: Int = palette.onAccentSoft,
        onClick: (() -> Unit)? = null
): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(66)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            if (onClick != null) {
                background = ripple(null)
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }
        }
        if (iconRes != 0) {
            row.addView(iconBadge(iconRes, iconBg, iconTint), LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(14) })
        }
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(title, 16f, palette.text, OeaType.medium).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })
        if (!subtitle.isNullOrBlank()) {
            texts.addView(text(subtitle, 13f, palette.textSecondary).apply {
                maxLines = 4
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(2), 0, 0)
                setLineSpacing(dpf(1.5f), 1f)
            })
        }
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        if (!value.isNullOrBlank()) {
            row.addView(text(value, 13f, palette.accent, OeaType.medium).apply {
                maxLines = 1
                gravity = Gravity.END
                setPadding(dp(10), 0, dp(2), 0)
            }, LinearLayout.LayoutParams(-2, -2))
        }
        when {
            trailing != null -> row.addView(trailing, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12) })
            onClick != null && chevron -> row.addView(iconView(R.drawable.oic_chevron_right, palette.textTertiary, 22),
                LinearLayout.LayoutParams(dp(22), dp(22)).apply { leftMargin = dp(6) })
        }
        addToGroup(group, row)
        return row
    }

    fun switchRow(
        group: LinearLayout,
        iconRes: Int,
        title: String,
        subtitle: String?,
        checked: Boolean,
        onChange: (Boolean) -> Unit
): OeaToggle {
        val toggle = toggle(checked, onChange)
        val row = row(group, iconRes, title, subtitle, trailing = toggle, chevron = false) { toggle.toggle(fromUser = true) }
        row.contentDescription = title
        return toggle
    }

    /** Row with − value + controls for small numeric settings. */
    fun stepperRow(group: LinearLayout, iconRes: Int, title: String, subtitle: String?, value: Int, min: Int, max: Int, format: (Int) -> String = { it.toString() }, onChange: (Int) -> Unit) {
        var current = value
        val valueView = text(format(current), 15f, palette.text, OeaType.medium).apply {
            gravity = Gravity.CENTER
            minWidth = dp(40)
        }
        val minus = circleIconButton(R.drawable.oic_expand_more, "Decrease $title", palette.cardHigh, palette.text) {}
        val plus = circleIconButton(R.drawable.oic_add, "Increase $title", palette.cardHigh, palette.text) {}
        fun refresh() {
            valueView.text = format(current)
            minus.alpha = if (current > min) 1f else 0.35f
            plus.alpha = if (current < max) 1f else 0.35f
        }
        minus.setOnClickListener { if (current > min) { current--; refresh(); onChange(current) } }
        plus.setOnClickListener { if (current < max) { current++; refresh(); onChange(current) } }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(minus, LinearLayout.LayoutParams(dp(36), dp(36)))
            addView(valueView, LinearLayout.LayoutParams(-2, -2))
            addView(plus, LinearLayout.LayoutParams(dp(36), dp(36)))
        }
        refresh()
        row(group, iconRes, title, subtitle, trailing = box, chevron = false)
    }

    enum class Tone { INFO, SUCCESS, WARNING, DANGER }

    fun banner(parent: ViewGroup, iconRes: Int, title: String, body: String?, tone: Tone = Tone.INFO, actionLabel: String? = null, onAction: (() -> Unit)? = null): LinearLayout {
        val toneColor = when (tone) {
            Tone.INFO -> palette.accent
            Tone.SUCCESS -> palette.success
            Tone.WARNING -> palette.warning
            Tone.DANGER -> palette.danger
        }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = rounded(OeaTheme.blend(palette.card, toneColor, if (palette.dark) 0.16f else 0.10f), 24,
                OeaTheme.alpha(toneColor, 0x40), 1)
        }
        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(iconView(iconRes, toneColor, 22), LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(12) })
        top.addView(text(title, 15.5f, palette.text, OeaType.medium), LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(top)
        if (!body.isNullOrBlank()) {
            card.addView(text(body, 13.5f, palette.textSecondary).apply {
                setPadding(dp(34), dp(6), 0, 0)
                setLineSpacing(dpf(2f), 1f)
            })
        }
        if (actionLabel != null && onAction != null) {
            val action = button(actionLabel, toneColor, if (OeaTheme.luminance(toneColor) > 0.45f) Color.rgb(16, 20, 28) else Color.WHITE) { onAction() }
            card.addView(action, LinearLayout.LayoutParams(-2, -2).apply {
                topMargin = dp(12)
                leftMargin = dp(34)
            })
        }
        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        return card
    }

    fun hscroll(child: View): HorizontalScrollView = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        clipToPadding = false
        addView(child)
    }

    // ----------------------------------------------------------------- sheets

    /** Bottom sheet styled like the rest of OEA. Returns the dialog. */
    fun sheet(title: String?, subtitle: String? = null, build: (LinearLayout, Dialog) -> Unit): Dialog {
        val activity = context as? Activity ?: throw IllegalStateException("Sheets need an Activity context")
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(18))
            background = roundedTop(palette.background, 30)
            isClickable = true
        }
        panel.addView(View(activity).apply { background = rounded(OeaTheme.alpha(palette.textSecondary, 0x66), 3) },
            LinearLayout.LayoutParams(dp(36), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(14)
            })
        if (!title.isNullOrBlank()) {
            panel.addView(text(title, 21f, palette.text, OeaType.medium).apply { setPadding(dp(4), 0, dp(4), 0) })
        }
        if (!subtitle.isNullOrBlank()) {
            panel.addView(text(subtitle, 13.5f, palette.textSecondary).apply {
                setPadding(dp(4), dp(4), dp(4), 0)
                setLineSpacing(dpf(2f), 1f)
            })
        }
        if (!title.isNullOrBlank() || !subtitle.isNullOrBlank()) {
            panel.addView(View(activity), LinearLayout.LayoutParams(1, dp(14)))
        }
        build(panel, dialog)
        val basePadding = panel.paddingBottom
        panel.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, basePadding + OeaInsets.bars(insets)[3])
            insets
        }
        dialog.setContentView(panel)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.48f)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        panel.translationY = activity.resources.displayMetrics.heightPixels.toFloat()
        dialog.setOnShowListener {
            panel.animate().translationY(0f).setDuration(260L).setInterpolator(DecelerateInterpolator(1.8f)).start()
        }
        dialog.show()
        return dialog
    }

    /** Single choice list in a sheet; [onPick] fires once and the sheet closes. */
    fun choose(title: String, subtitle: String? = null, options: List<String>, selected: Int, descriptions: List<String?> = emptyList(), onPick: (Int) -> Unit) {
        sheet(title, subtitle) { panel, dialog ->
            val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            options.forEachIndexed { index, label ->
                val active = index == selected
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(56)
                    setPadding(dp(16), dp(10), dp(14), dp(10))
                    background = ripple(rounded(if (active) palette.accentSoft else palette.card, 18), 18)
                    isClickable = true
                    setOnClickListener {
                        dialog.dismiss()
                        onPick(index)
                    }
                }
                val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                texts.addView(text(label, 15.5f, if (active) palette.onAccentSoft else palette.text, OeaType.medium))
                descriptions.getOrNull(index)?.takeIf { it.isNotBlank() }?.let {
                    texts.addView(text(it, 12.5f, if (active) OeaTheme.alpha(palette.onAccentSoft, 0xCC) else palette.textSecondary).apply {
                        setPadding(0, dp(2), 0, 0)
                    })
                }
                row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(radio(active), LinearLayout.LayoutParams(dp(22), dp(22)))
                list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
            }
            panel.addView(boundedScroll(list, 0.62f))
        }
    }

    fun radio(active: Boolean): View = FrameLayout(context).apply {
        background = oval(if (active) palette.accent else Color.TRANSPARENT, if (active) palette.accent else palette.textTertiary, 2)
        if (active) addView(ImageView(context).apply { setImageDrawable(icon(R.drawable.oic_check, palette.onAccent)) },
            FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER))
    }

    fun boundedScroll(child: View, maxScreenFraction: Float): View {
        val max = (context.resources.displayMetrics.heightPixels * maxScreenFraction).toInt()
        return object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(max, View.MeasureSpec.AT_MOST))
            }
        }.apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(child)
        }
    }

    fun confirm(title: String, message: String, positive: String, destructive: Boolean = false, negative: String = "Cancel", onConfirm: () -> Unit) {
        sheet(title, message) { panel, dialog ->
            panel.addView(buttonRow(
                quietButton(negative) { dialog.dismiss() },
                button(positive, if (destructive) palette.danger else palette.accent,
                    if (destructive) Color.WHITE else palette.onAccent) { dialog.dismiss(); onConfirm() }
))
        }
    }

    fun buttonRow(vararg buttons: View): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
        setPadding(0, dp(8), 0, 0)
        buttons.forEachIndexed { index, b ->
            addView(b, LinearLayout.LayoutParams(0, dp(48), 1f).apply { if (index > 0) leftMargin = dp(10) })
        }
    }

    fun field(hint: String, value: String = "", numeric: Boolean = false, password: Boolean = false): EditText = EditText(context).apply {
        this.hint = hint
        setText(value)
        setSelection(text.length)
        textSize = 16f
        setSingleLine(true)
        setTextColor(palette.text)
        setHintTextColor(palette.textTertiary)
        background = rounded(palette.card, 18, palette.stroke, 1)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        inputType = when {
            numeric && password -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            numeric -> InputType.TYPE_CLASS_NUMBER
            password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
    }

    /** Text / PIN entry sheet. [onSubmit] returns an error message to keep the sheet open, or null. */
    fun prompt(title: String, subtitle: String?, hint: String, initial: String = "", numeric: Boolean = false, password: Boolean = false, confirmLabel: String = "Save", onSubmit: (String) -> String?) {
        sheet(title, subtitle) { panel, dialog ->
            val input = field(hint, initial, numeric, password)
            val error = text("", 12.5f, palette.danger).apply { visibility = View.GONE; setPadding(dp(6), dp(6), 0, 0) }
            panel.addView(input, LinearLayout.LayoutParams(-1, -2))
            panel.addView(error)
            fun submit() {
                val message = onSubmit(input.text.toString())
                if (message == null) {
                    hideKeyboard(input)
                    dialog.dismiss()
                } else {
                    error.text = message
                    error.visibility = View.VISIBLE
                }
            }
            input.imeOptions = EditorInfo.IME_ACTION_DONE
            input.setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_DONE || event?.keyCode == KeyEvent.KEYCODE_ENTER) { submit(); true } else false
            }
            panel.addView(buttonRow(
                quietButton("Cancel") { hideKeyboard(input); dialog.dismiss() },
                primaryButton(confirmLabel) { submit() }
), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            input.post {
                input.requestFocus()
                context.getSystemService(InputMethodManager::class.java)?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    fun hideKeyboard(view: View) {
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    /**
     * Polished app picker with search, icons and check marks. Selection is by
     * [keyOf] (package or package/class). [max] caps multi-selection.
     */
    fun pickApps(
        title: String,
        subtitle: String?,
        apps: List<OeaAppInfo>,
        selected: Set<String>,
        keyOf: (OeaAppInfo) -> String,
        multi: Boolean = true,
        max: Int = Int.MAX_VALUE,
        confirmLabel: String = "Save",
        onDone: (Set<String>) -> Unit
) {
        val chosen = LinkedHashSet(selected)
        sheet(title, subtitle) { panel, dialog ->
            val search = field("Search apps").apply {
                setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null)
                icon(R.drawable.oic_search, palette.textSecondary)?.let { d ->
                    d.setBounds(0, 0, dp(20), dp(20))
                    setCompoundDrawablesRelative(d, null, null, null)
                    compoundDrawablePadding = dp(10)
                }
            }
            panel.addView(search, LinearLayout.LayoutParams(-1, -2))
            val counter = text("", 12.5f, palette.textSecondary).apply { setPadding(dp(6), dp(8), dp(6), dp(6)) }
            if (multi) panel.addView(counter)
            var visible = apps
            val adapter = object : BaseAdapter() {
                override fun getCount() = visible.size
                override fun getItem(position: Int) = visible[position]
                override fun getItemId(position: Int) = position.toLong()
                override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                    val app = visible[position]
                    val row = (convertView as? LinearLayout) ?: LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(10), dp(8), dp(12), dp(8))
                        addView(ImageView(context), LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(14) })
                        addView(LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(text("", 15f, palette.text, OeaType.medium).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                            addView(text("", 11.5f, palette.textTertiary).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE })
                        }, LinearLayout.LayoutParams(0, -2, 1f))
                        addView(FrameLayout(context), LinearLayout.LayoutParams(dp(24), dp(24)))
                    }
                    val key = keyOf(app)
                    val isOn = key in chosen
                    (row.getChildAt(0) as ImageView).setImageDrawable(appIcon(app))
                    val texts = row.getChildAt(1) as LinearLayout
                    (texts.getChildAt(0) as TextView).text = app.label
                    (texts.getChildAt(1) as TextView).text = app.packageName
                    val mark = row.getChildAt(2) as FrameLayout
                    mark.removeAllViews()
                    mark.background = oval(if (isOn) palette.accent else Color.TRANSPARENT, if (isOn) palette.accent else palette.textTertiary, 2)
                    if (isOn) mark.addView(ImageView(context).apply { setImageDrawable(icon(R.drawable.oic_check, palette.onAccent)) },
                        FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER))
                    row.background = if (isOn) rounded(palette.accentSoft, 16) else null
                    return row
                }
            }
            val list = ListView(context).apply {
                divider = null
                dividerHeight = dp(4)
                selector = ColorDrawable(Color.TRANSPARENT)
                isVerticalScrollBarEnabled = false
                this.adapter = adapter
            }
            fun refreshCounter() {
                counter.text = if (max != Int.MAX_VALUE) "${chosen.size} of $max selected" else "${chosen.size} selected"
            }
            list.setOnItemClickListener { _, _, position, _ ->
                val key = keyOf(visible[position])
                if (!multi) {
                    dialog.dismiss()
                    onDone(setOf(key))
                    return@setOnItemClickListener
                }
                if (key in chosen) chosen.remove(key)
                else if (chosen.size < max) chosen.add(key)
                else android.widget.Toast.makeText(context, "You can choose up to $max apps.", android.widget.Toast.LENGTH_SHORT).show()
                refreshCounter()
                adapter.notifyDataSetChanged()
            }
            search.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    val q = s?.toString()?.trim().orEmpty()
                    visible = if (q.isEmpty()) apps else apps.filter {
                        it.label.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true)
                    }
                    adapter.notifyDataSetChanged()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
            refreshCounter()
            panel.addView(list, LinearLayout.LayoutParams(-1, (context.resources.displayMetrics.heightPixels * 0.52f).toInt()).apply {
                topMargin = dp(4)
            })
            if (multi) {
                panel.addView(buttonRow(
                    quietButton("Cancel") { dialog.dismiss() },
                    primaryButton(confirmLabel) { dialog.dismiss(); onDone(chosen.toSet()) }
), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            }
        }
    }
}
