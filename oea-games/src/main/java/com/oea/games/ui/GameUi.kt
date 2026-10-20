package com.oea.games.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.oea.engine.OeaEngine
import com.oea.engine.core.GameDescriptor
import kotlin.math.sin

/**
 * The visual language of OEA's games, in one file that belongs to the games.
 *
 * `:oea-games` is hosted by the OEA launcher (which has its own
 * `OeaKit`), and the games ship inside the launcher's single APK. A
 * game must never depend on the launcher, so it cannot use `OeaKit` —
 * and before this existed it fell back to stock `AlertDialog`s over
 * an empty window, which is why the game screens looked nothing like
 * OEA.
 *
 * Everything a game shell needs lives here and matches the launcher's kit one
 * for one: the same dark console palette, the same 20–30 dp radii, the same
 * bottom-sheet dialogs, the same ripple and type ramp. The only difference is
 * the accent, which is the game's own colour so each app keeps its identity.
 */
data class GamePalette(
    val accent: Int,
    val onAccent: Int,
    val accentSoft: Int,
    val onAccentSoft: Int,
    val background: Int,
    val card: Int,
    val cardHigh: Int,
    val stroke: Int,
    val text: Int,
    val textSecondary: Int,
    val textTertiary: Int,
    val danger: Int,
    val success: Int,
    val warning: Int
)

/** Colour maths and the OEA dark game palette. */
object GameColors {
    /** Amber: the OEA Games accent, shared with the launcher's Games screens. */
    const val OEA_AMBER = 0xFFFFA53D.toInt()

    fun alpha(color: Int, value: Int): Int =
        Color.argb(value, Color.red(color), Color.green(color), Color.blue(color))

    fun blend(from: Int, to: Int, ratio: Float): Int {
        val r = ratio.coerceIn(0f, 1f)
        return Color.argb(
            (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * r).toInt(),
            (Color.red(from) + (Color.red(to) - Color.red(from)) * r).toInt(),
            (Color.green(from) + (Color.green(to) - Color.green(from)) * r).toInt(),
            (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * r).toInt()
        )
    }

    /**
     * The dark console palette, tinted with [accent] (a game's own colour when
     * one is known, otherwise OEA's amber).
     */
    fun palette(accent: Int = OEA_AMBER, highContrast: Boolean = false): GamePalette {
        val base = accent.takeIf { Color.alpha(it) == 0xFF } ?: OEA_AMBER
        val background = Color.rgb(8, 9, 13)
        val card = Color.rgb(20, 22, 30)
        val onAccent = if (luminance(base) > 0.5f) Color.rgb(20, 14, 4) else Color.rgb(255, 250, 244)
        val built = GamePalette(
            accent = base,
            onAccent = onAccent,
            accentSoft = blend(card, base, 0.22f),
            onAccentSoft = blend(Color.WHITE, base, 0.30f),
            background = background,
            card = card,
            cardHigh = Color.rgb(32, 35, 46),
            stroke = alpha(Color.WHITE, 0x16),
            text = Color.rgb(242, 244, 248),
            textSecondary = Color.rgb(158, 164, 178),
            textTertiary = Color.rgb(104, 110, 124),
            danger = Color.rgb(255, 110, 120),
            success = Color.rgb(80, 225, 160),
            warning = Color.rgb(255, 196, 100)
        )
        // High contrast is a *palette*, not a brightness knob: the accent survives so
        // a game still reads as itself, but grounds go to black and type to white,
        // with strokes hard enough to see with a scratched screen protector.
        if (!highContrast) return built
        return built.copy(
            accentSoft = blend(Color.BLACK, base, 0.55f),
            onAccentSoft = Color.WHITE,
            background = Color.BLACK,
            card = Color.rgb(6, 6, 8),
            cardHigh = Color.rgb(26, 27, 33),
            stroke = alpha(Color.WHITE, 0x9E),
            text = Color.WHITE,
            textSecondary = Color.rgb(238, 240, 245),
            textTertiary = Color.rgb(214, 218, 226),
            danger = Color.rgb(255, 148, 158),
            success = Color.rgb(124, 255, 198),
            warning = Color.rgb(255, 216, 138)
        )
    }

    private fun luminance(color: Int): Float =
        (0.2126f * Color.red(color) + 0.7152f * Color.green(color) + 0.0722f * Color.blue(color)) / 255f
}

/** Typefaces shared by every game screen, matching the launcher's ramp. */
object GameType {
    val regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val bold: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
    val black: Typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
}

/**
 * System-bar insets that work on every supported level, plus the edge-to-edge
 * setup every game screen uses.
 *
 * `Window.getInsetsController()` dereferences the decor without installing it,
 * so calling it before `setContentView` (which is when a screen sets up its
 * bars) throws on newer Android. Always go through `decorView`, and treat every
 * step as optional.
 */
object GameInsets {
    /** left, top, right, bottom of the system bars and the display cutout. */
    fun bars(insets: WindowInsets): IntArray = if (Build.VERSION.SDK_INT >= 30) {
        val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        intArrayOf(i.left, i.top, i.right, i.bottom)
    } else {
        @Suppress("DEPRECATION")
        intArrayOf(
            insets.systemWindowInsetLeft,
            insets.systemWindowInsetTop,
            insets.systemWindowInsetRight,
            insets.systemWindowInsetBottom
        )
    }

    fun edgeToEdge(window: Window) {
        runCatching {
            @Suppress("DEPRECATION")
            window.statusBarColor = Color.TRANSPARENT
            @Suppress("DEPRECATION")
            window.navigationBarColor = Color.TRANSPARENT
            @Suppress("DEPRECATION")
            window.navigationBarDividerColor = Color.TRANSPARENT
        }
        val decor = runCatching { window.peekDecorView() ?: window.decorView }.getOrNull()
        if (Build.VERSION.SDK_INT >= 30 && decor != null) {
            runCatching { window.setDecorFitsSystemWindows(false) }
            runCatching { decor.windowInsetsController?.setSystemBarsAppearance(0, 0) }
        } else if (decor != null) {
            runCatching {
                @Suppress("DEPRECATION")
                decor.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            }
        }
    }
}

/**
 * The game toolkit: cards, buttons, rows, sliders, sheets and the page frame.
 * Built from plain views so it costs nothing to inflate and cannot fail on a
 * device whose theme stack refuses to load a resource.
 */
class GameKit(
    val context: Context,
    val palette: GamePalette = GameColors.palette(
        highContrast = runCatching { OeaEngine.settings.highContrastMenus }.getOrDefault(false)
    )
) {

    private val density = context.resources.displayMetrics.density

    /**
     * Menu scale, read once when this kit is built. Every metric in the kit goes
     * through it, which is why a game screen enlarged here grows as a picture
     * rather than as a column of text with buttons left at finger size.
     */
    var uiScale: Float = runCatching { OeaEngine.settings.uiScale }.getOrDefault(1f)
        private set

    /** True when the player asked for no animation. */
    val reducedMotion: Boolean
        get() = runCatching { OeaEngine.settings.reducedMotion }.getOrDefault(false)

    /** Returns true when the engine's scale moved, so a caller can rebuild. */
    fun syncAccessibility(): Boolean {
        val now = runCatching { OeaEngine.settings.uiScale }.getOrDefault(1f)
        val changed = now != uiScale
        uiScale = now
        return changed
    }

    fun dp(value: Int): Int = (value * density * uiScale + 0.5f).toInt()
    fun dpf(value: Float): Float = value * density * uiScale

    // ------------------------------------------------------------------ shapes

    fun rounded(color: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dpf(radiusDp.toFloat())
            if (strokeDp > 0) setStroke(dp(strokeDp), strokeColor)
        }

    fun roundedTop(color: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        val r = dpf(radiusDp.toFloat())
        cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
    }

    fun oval(color: Int, strokeColor: Int = 0, strokeDp: Int = 0): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        if (strokeDp > 0) setStroke(dp(strokeDp), strokeColor)
    }

    fun ripple(content: Drawable?, radiusDp: Int = 0, color: Int = GameColors.alpha(palette.text, 0x22)): RippleDrawable {
        val mask = if (radiusDp > 0) rounded(Color.WHITE, radiusDp) else ColorDrawable(Color.WHITE)
        return RippleDrawable(ColorStateList.valueOf(color), content, mask)
    }

    // ------------------------------------------------------------------- parts

    fun text(value: CharSequence, sizeSp: Float, color: Int, face: Typeface = GameType.regular): TextView =
        TextView(context).apply {
            text = value
            textSize = sizeSp * uiScale
            setTextColor(color)
            typeface = face
        }

    fun spacer(heightDp: Int): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(heightDp))
    }

    /** Circular tonal badge holding one letter (a game's glyph); the header of a card. */
    fun glyphBadge(glyph: String, bg: Int = palette.accentSoft, tint: Int = palette.onAccentSoft, sizeDp: Int = 40): FrameLayout =
        FrameLayout(context).apply {
            background = oval(bg)
            addView(
                text(glyph, sizeDp * 0.42f, tint, GameType.black).apply { gravity = Gravity.CENTER },
                FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp), Gravity.CENTER)
            )
        }

    // ----------------------------------------------------------------- buttons

    fun primaryButton(label: String, onClick: () -> Unit): TextView =
        button(label, palette.accent, palette.onAccent, onClick)

    fun tonalButton(label: String, onClick: () -> Unit): TextView =
        button(label, palette.accentSoft, palette.onAccentSoft, onClick)

    fun quietButton(label: String, onClick: () -> Unit): TextView =
        button(label, Color.TRANSPARENT, palette.textSecondary, onClick).apply {
            background = ripple(rounded(Color.TRANSPARENT, 22, palette.stroke, 1), 22)
        }

    fun button(label: String, bg: Int, fg: Int, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        textSize = 15f
        setTextColor(fg)
        typeface = GameType.medium
        gravity = Gravity.CENTER
        letterSpacing = 0.01f
        setPadding(dp(18), dp(14), dp(18), dp(14))
        isClickable = true
        isFocusable = true
        background = ripple(rounded(bg, 22), 22)
        setOnClickListener { onClick() }
    }

    fun buttonRow(vararg buttons: View): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
        setPadding(0, dp(10), 0, 0)
        buttons.forEachIndexed { index, b ->
            addView(b, LinearLayout.LayoutParams(0, dp(48), 1f).apply { if (index > 0) leftMargin = dp(10) })
        }
    }

    /**
     * The big list entry of a game screen: a glyph, a title, a line of detail
     * and a chevron. Everything tappable in a game shell is one of these.
     */
    fun actionRow(
        glyph: String,
        title: String,
        subtitle: String? = null,
        highlight: Boolean = false,
        onClick: () -> Unit
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(64)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = ripple(rounded(if (highlight) palette.accentSoft else palette.card, 20), 20)
        isClickable = true
        isFocusable = true
        // TalkBack gets the whole row as one sentence instead of reading a gear
        // glyph, then a title, then a fragment of detail.
        contentDescription = if (subtitle.isNullOrBlank()) title else "$title. $subtitle"
        setOnClickListener { onClick() }
        val badge = glyphBadge(
            glyph,
            if (highlight) palette.accent else palette.cardHigh,
            if (highlight) palette.onAccent else palette.onAccentSoft
        )
        badge.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(badge, LinearLayout.LayoutParams(dp(44), dp(44)))
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(
            text(title, 16f, if (highlight) palette.onAccentSoft else palette.text, GameType.medium).apply {
                setPadding(dp(14), 0, dp(8), 0)
            }
        )
        if (!subtitle.isNullOrBlank()) {
            texts.addView(
                text(subtitle, 12.5f, if (highlight) GameColors.alpha(palette.onAccentSoft, 0xCC) else palette.textSecondary).apply {
                    setPadding(dp(14), dp(2), dp(8), 0)
                    setLineSpacing(dpf(1.5f), 1f)
                }
            )
        }
        addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        addView(
            text("›", 22f, palette.textTertiary, GameType.regular).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(dp(20), dp(20))
        )
    }

    /** A title above a group of rows. */
    fun sectionTitle(parent: ViewGroup, title: String) {
        parent.addView(
            text(title.uppercase(), 12f, palette.textTertiary, GameType.medium).apply {
                letterSpacing = 0.09f
                setPadding(dp(8), dp(20), dp(4), dp(8))
            },
            LinearLayout.LayoutParams(-1, -2)
        )
    }

    fun note(parent: ViewGroup, value: String) {
        parent.addView(
            text(value, 12.5f, palette.textSecondary).apply {
                setPadding(dp(8), dp(8), dp(8), dp(6))
                setLineSpacing(dpf(2f), 1f)
            },
            LinearLayout.LayoutParams(-1, -2)
        )
    }

    fun group(parent: ViewGroup, marginTopDp: Int = 0): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(marginTopDp) })
    }

    fun rowDivider(parent: ViewGroup) {
        parent.addView(
            View(context).apply { setBackgroundColor(palette.stroke) },
            LinearLayout.LayoutParams(-1, 1).apply { leftMargin = dp(16); rightMargin = dp(16) }
        )
    }

    // ------------------------------------------------------------------ inputs

    /** A labelled slider with a live value readout. */
    fun slider(
        parent: ViewGroup,
        label: String,
        value: Float,
        min: Float,
        max: Float,
        format: (Float) -> String = { String.format(java.util.Locale.US, "%.2f", it) },
        onChange: (Float) -> Unit
    ) {
        val caption = text("", 13.5f, palette.textSecondary, GameType.medium).apply {
            setPadding(dp(6), dp(12), dp(6), 0)
        }
        val bar = SeekBar(context).apply {
            this.max = 100
            val span = (max - min).coerceAtLeast(0.01f)
            progress = (((value - min) / span) * 100f).toInt().coerceIn(0, 100)
        }
        val span = (max - min).coerceAtLeast(0.01f)
        fun refresh() {
            val v = min + span * (bar.progress / 100f)
            caption.text = "$label · ${format(v)}"
            onChange(v)
        }
        refresh()
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) = refresh()
            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        })
        parent.addView(caption, LinearLayout.LayoutParams(-1, -2))
        parent.addView(bar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
    }

    /** A switch row that stores its own state in [checked]. */
    fun switchRow(parent: ViewGroup, title: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(16), dp(12), dp(12), dp(12))
            background = ripple(rounded(palette.card, 18), 18)
            isClickable = true
        }
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(title, 15f, palette.text, GameType.medium))
        if (!subtitle.isNullOrBlank()) {
            texts.addView(text(subtitle, 12.5f, palette.textSecondary).apply { setPadding(0, dp(2), 0, 0) })
        }
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        val toggle = GameToggle(context, palette).apply {
            isChecked = checked
            onCheckedChange = { on -> onChange(on) }
        }
        row.addView(toggle, LinearLayout.LayoutParams(dp(46), dp(28)))
        row.setOnClickListener { toggle.toggle() }
        parent.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
    }

    fun field(hint: String, value: String = ""): android.widget.EditText = android.widget.EditText(context).apply {
        this.hint = hint
        setText(value)
        setSelection(text.length)
        textSize = 15f
        setSingleLine(true)
        setTextColor(palette.text)
        setHintTextColor(palette.textTertiary)
        background = rounded(palette.cardHigh, 16)
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }

    // ------------------------------------------------------------------ layout

    fun scroll(child: View): ScrollView = ScrollView(context).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        clipToPadding = false
        addView(child)
    }

    /** A scroll view that never grows past [maxScreenFraction] of the screen (for sheets). */
    fun boundedScroll(child: View, maxScreenFraction: Float): View {
        val maxHeight = (context.resources.displayMetrics.heightPixels * maxScreenFraction).toInt()
        return object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST))
            }
        }.apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(child)
        }
    }

    /**
     * Full-screen page root. It pads itself with the system-bar insets, so a
     * game screen is edge-to-edge and its content never hides under a bar.
     */
    class Page(val root: FrameLayout, val scroll: ScrollView, val content: LinearLayout)

    fun page(padBottomDp: Int = 28): Page {
        val root = FrameLayout(context).apply { setBackgroundColor(palette.background) }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(padBottomDp))
        }
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            clipToPadding = false
            addView(content)
        }
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        val baseLeft = content.paddingLeft
        val baseTop = content.paddingTop
        val baseRight = content.paddingRight
        val baseBottom = content.paddingBottom
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = GameInsets.bars(insets)
            content.setPadding(baseLeft + bars[0], baseTop + bars[1], baseRight + bars[2], baseBottom + bars[3])
            insets
        }
        return Page(root, scroll, content)
    }

    // ------------------------------------------------------------------ sheets

    /** Bottom sheet styled like OEA's. Returns the dialog so callers can dismiss it. */
    fun sheet(title: String?, subtitle: String? = null, build: (LinearLayout, Dialog) -> Unit): Dialog {
        val activity = context as? Activity
            ?: throw IllegalStateException("Sheets need an Activity context")
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(18))
            background = roundedTop(palette.background, 30)
            isClickable = true
        }
        panel.addView(
            View(activity).apply { background = rounded(GameColors.alpha(palette.textSecondary, 0x66), 3) },
            LinearLayout.LayoutParams(dp(36), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(14)
            }
        )
        if (!title.isNullOrBlank()) {
            panel.addView(text(title, 21f, palette.text, GameType.medium).apply { setPadding(dp(4), 0, dp(4), 0) })
        }
        if (!subtitle.isNullOrBlank()) {
            panel.addView(
                text(subtitle, 13.5f, palette.textSecondary).apply {
                    setPadding(dp(4), dp(4), dp(4), 0)
                    setLineSpacing(dpf(2f), 1f)
                }
            )
        }
        if (!title.isNullOrBlank() || !subtitle.isNullOrBlank()) {
            panel.addView(View(activity), LinearLayout.LayoutParams(1, dp(12)))
        }
        build(panel, dialog)
        val baseBottom = panel.paddingBottom
        panel.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, baseBottom + GameInsets.bars(insets)[3])
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

    /** Single-choice list in a sheet; [onPick] fires once, then the sheet closes. */
    fun choose(
        title: String,
        subtitle: String? = null,
        options: List<String>,
        selected: Int,
        descriptions: List<String?> = emptyList(),
        onPick: (Int) -> Unit
    ) {
        sheet(title, subtitle) { panel, dialog ->
            val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            options.forEachIndexed { index, label ->
                list.addView(
                    optionRow(label, descriptions.getOrNull(index), index == selected) {
                        dialog.dismiss()
                        onPick(index)
                    },
                    LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) }
                )
            }
            panel.addView(boundedScroll(list, 0.62f))
        }
    }

    fun optionRow(label: String, description: String?, active: Boolean, onClick: () -> Unit): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(16), dp(10), dp(14), dp(10))
            background = ripple(rounded(if (active) palette.accentSoft else palette.card, 18), 18)
            isClickable = true
            setOnClickListener { onClick() }
            val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(text(label, 15.5f, if (active) palette.onAccentSoft else palette.text, GameType.medium))
            if (!description.isNullOrBlank()) {
                texts.addView(
                    text(description, 12.5f, if (active) GameColors.alpha(palette.onAccentSoft, 0xCC) else palette.textSecondary).apply {
                        setPadding(0, dp(2), 0, 0)
                        setLineSpacing(dpf(1.5f), 1f)
                    }
                )
            }
            addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            addView(radio(active), LinearLayout.LayoutParams(dp(22), dp(22)))
        }

    fun radio(active: Boolean): View = FrameLayout(context).apply {
        background = oval(if (active) palette.accent else Color.TRANSPARENT, if (active) palette.accent else palette.textTertiary, 2)
        if (active) {
            addView(
                text("✓", 12f, palette.onAccent, GameType.bold).apply { gravity = Gravity.CENTER },
                FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
            )
        }
    }

    fun confirm(
        title: String,
        message: String,
        positive: String,
        negative: String = "Cancel",
        destructive: Boolean = false,
        onConfirm: () -> Unit
    ): Dialog {
        return sheet(title, message) { panel, dialog ->
            panel.addView(
                buttonRow(
                    quietButton(negative) { dialog.dismiss() },
                    button(positive, if (destructive) palette.danger else palette.accent, palette.onAccent) {
                        dialog.dismiss()
                        onConfirm()
                    }
                )
            )
        }
    }
}

/**
 * Procedural cover art for a game: its accent as a lit gradient, a motif per
 * game and the glyph. Drawn in code so a new game gets a good-looking card
 * (and no extra APK bytes) without shipping an image.
 */
class GameCoverDrawable(private val game: GameDescriptor, private val radiusPx: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = GameType.black
        textAlign = Paint.Align.CENTER
    }
    private val clip = Path()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        if (w <= 0f || h <= 0f) return
        val rect = RectF(b)
        clip.reset()
        clip.addRoundRect(rect, radiusPx, radiusPx, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)

        val dark = GameColors.blend(Color.rgb(8, 9, 13), game.accent, 0.35f)
        val light = GameColors.blend(game.accent, Color.WHITE, 0.08f)
        paint.shader = LinearGradient(
            0f, h, w, 0f,
            intArrayOf(dark, GameColors.blend(dark, light, 0.55f), light),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, paint)
        paint.shader = null

        when (game.id) {
            "chess" -> {
                val s = h / 6f
                for (row in 0 until 7) for (col in 0 until 16) if ((row + col) % 2 == 0) {
                    paint.color = GameColors.alpha(Color.WHITE, 0x14)
                    canvas.drawRect(b.left + col * s, b.top + row * s, b.left + (col + 1) * s, b.top + (row + 1) * s, paint)
                }
            }
            "runner" -> {
                paint.strokeWidth = h * 0.02f
                for (i in 0 until 9) {
                    paint.color = GameColors.alpha(Color.WHITE, 0x22 - i * 2)
                    val y = b.top + h * (0.2f + i * 0.09f)
                    canvas.drawLine(b.left - w * 0.1f + i * 18f, y, b.left + w * (0.55f + i * 0.04f), y - h * 0.05f, paint)
                }
            }
            "arena" -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = h * 0.018f
                for (i in 1..4) {
                    paint.color = GameColors.alpha(Color.WHITE, 0x30 - i * 6)
                    canvas.drawCircle(b.left + w * 0.72f, b.top + h * 0.5f, h * 0.16f * i, paint)
                }
                paint.style = Paint.Style.FILL
            }
            else -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = h * 0.012f
                paint.color = GameColors.alpha(Color.WHITE, 0x26)
                for (i in 0 until 12) {
                    val x = b.left + w * (0.4f + 0.05f * i)
                    canvas.drawLine(x, b.top + h * (0.5f + 0.4f * sin(i.toFloat())), x + w * 0.1f, b.top + h * (0.5f - 0.4f * sin(i * 1.7f)), paint)
                }
                paint.style = Paint.Style.FILL
            }
        }

        paint.shader = RadialGradient(
            b.left + w * 0.78f, b.top + h * 0.45f, h * 0.7f,
            GameColors.alpha(Color.WHITE, 0x44), Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, paint)
        paint.shader = null

        glyphPaint.textSize = h * 0.62f
        glyphPaint.color = GameColors.alpha(Color.WHITE, 0xE6)
        glyphPaint.setShadowLayer(h * 0.06f, 0f, h * 0.02f, GameColors.alpha(Color.BLACK, 0x66))
        canvas.drawText(game.glyph, b.left + w * 0.78f, b.top + h * 0.5f - (glyphPaint.ascent() + glyphPaint.descent()) / 2f, glyphPaint)

        paint.shader = LinearGradient(
            0f, b.top + h * 0.45f, 0f, b.bottom.toFloat(),
            Color.TRANSPARENT, GameColors.alpha(Color.BLACK, 0x99), Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, paint)
        paint.shader = null
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        glyphPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** An [ImageView] showing a game's cover art, sized in dp. */
fun gameCoverView(context: Context, game: GameDescriptor, widthDp: Int, heightDp: Int, radiusDp: Int = 22): ImageView {
    val density = context.resources.displayMetrics.density
    return ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        setImageDrawable(GameCoverDrawable(game, radiusDp * density))
        layoutParams = LinearLayout.LayoutParams((widthDp * density + 0.5f).toInt(), (heightDp * density + 0.5f).toInt())
    }
}

/**
 * A switch drawn by OEA rather than by the platform.
 *
 * `android.widget.Switch` is deprecated from API 34 and the Material
 * replacement is a library dependency a game app does not need; this is the
 * same control the launcher uses, in twenty lines, with the game's accent.
 */
class GameToggle(context: Context, private val palette: GamePalette) : FrameLayout(context) {

    var isChecked: Boolean = false
        set(value) {
            if (value == field) return
            field = value
            sync(animate = true)
            onCheckedChange?.invoke(value)
        }

    /** Set after [isChecked] when the initial state must not notify. */
    var onCheckedChange: ((Boolean) -> Unit)? = null

    private val track = GradientDrawable().apply { cornerRadius = dpf(14f) }
    private val thumb = GradientDrawable().apply { shape = GradientDrawable.OVAL }
    private val thumbView = View(context).apply { background = thumb }
    private val slide = DecelerateInterpolator(1.6f)

    init {
        isClickable = true
        isFocusable = true
        addView(thumbView, LayoutParams(dp(22), dp(22), Gravity.CENTER_VERTICAL).apply { marginStart = dp(3) })
        setOnClickListener { toggle() }
        sync(animate = false)
    }

    fun toggle() {
        isChecked = !isChecked
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.4f
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(dp(46), dp(28))

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = sync(animate = false)

    private fun sync(animate: Boolean) {
        val travel = (dp(46) - dp(22) - dp(6)).toFloat()
        val target = if (isChecked) travel else 0f
        if (animate) {
            thumbView.animate().translationX(target).setDuration(160L).setInterpolator(slide).start()
        } else {
            thumbView.animate().cancel()
            thumbView.translationX = target
        }
        track.setColor(if (isChecked) palette.accent else palette.cardHigh)
        track.setStroke(dp(1), if (isChecked) palette.accent else palette.stroke)
        thumb.setColor(if (isChecked) palette.onAccent else palette.textSecondary)
        background = track
        contentDescription = if (isChecked) "On" else "Off"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun dpf(value: Float): Float = value * resources.displayMetrics.density
}
