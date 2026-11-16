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
import android.widget.HorizontalScrollView
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
    val warning: Int,
    /** Secondary brand colour (dials, splits, highlights). Defaults to the accent. */
    val signal: Int = accent,
    /** Highest surface: pressed rows, chips and wells. Defaults to [cardHigh]. */
    val well: Int = cardHigh
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

    /**
     * A game's surfaces as the toolkit's palette, so every existing row, sheet
     * and button is drawn in that game's own materials.
     */
    fun from(scheme: GameScheme, highContrast: Boolean = false): GamePalette {
        val built = GamePalette(
            accent = scheme.accent,
            onAccent = scheme.onAccent,
            accentSoft = scheme.accentSoft,
            onAccentSoft = scheme.onAccentSoft,
            background = scheme.ground,
            card = scheme.panel,
            cardHigh = scheme.well,
            stroke = scheme.stroke,
            text = scheme.text,
            textSecondary = scheme.textSecondary,
            textTertiary = scheme.textTertiary,
            danger = scheme.danger,
            success = scheme.success,
            warning = scheme.signal
        )
        if (!highContrast) return built
        return if (scheme.light) built.copy(
            accentSoft = blend(scheme.well, scheme.accent, 0.4f),
            onAccentSoft = Color.BLACK,
            background = Color.WHITE,
            card = Color.rgb(248, 246, 240),
            cardHigh = Color.rgb(232, 226, 214),
            stroke = alpha(Color.BLACK, 0x9E),
            text = Color.BLACK,
            textSecondary = Color.rgb(40, 38, 32),
            textTertiary = Color.rgb(70, 66, 58)
        ) else built.copy(
            accentSoft = blend(Color.BLACK, scheme.accent, 0.55f),
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

    /** Monospaced digits for clocks, scores and kill counts. */
    val mono: Typeface = Typeface.create("monospace", Typeface.BOLD)

    /** A brush/wood-lettering face for boards and records, with a safe fallback. */
    val serif: Typeface = runCatching { Typeface.create("serif", Typeface.BOLD) }
        .getOrDefault(Typeface.create("sans-serif", Typeface.BOLD))
}

/**
 * A game's own surface palette.
 *
 * OEA's games are not three skins of one app: a shooter reads like a gun-metal
 * command deck, a chess club like walnut and ivory, a rail runner like a neon
 * arcade cabinet. This turns a game's signature colours (the same ones in its
 * launcher icon) into a full set of surfaces, so every game screen can be built
 * from its own materials instead of a shared "dark console with an accent".
 *
 * Everything is optional: an omitted value is derived from the three brand
 * colours, so a new game only has to name its ground, its accent and its signal.
 */
data class GameScheme(
    /** Page ground. */
    val ground: Int,
    /** Raised panels and cards. */
    val panel: Int,
    /** The highest surface: pressed rows, chips, wells. */
    val well: Int,
    val accent: Int,
    val onAccent: Int,
    /** Secondary brand colour: dials, splits, highlights. */
    val signal: Int,
    val text: Int,
    val textSecondary: Int,
    val textTertiary: Int,
    val stroke: Int,
    val danger: Int = 0xFFFF6E78.toInt(),
    val success: Int = 0xFF50E1A0.toInt(),
    /** Card radius in dp: 14 fits a briefing card, 26 a soft one. */
    val radius: Int = 22,
    /** True when the ground is light (the chess club is ivory, not black). */
    val light: Boolean = false
) {
    val accentSoft: Int get() = GameColors.blend(panel, accent, if (light) 0.24f else 0.26f)
    val onAccentSoft: Int get() = if (light) GameColors.blend(accent, Color.BLACK, 0.25f) else GameColors.blend(Color.WHITE, accent, 0.35f)
    val signalSoft: Int get() = GameColors.blend(panel, signal, 0.22f)
}

/** The games' schemes, one per game, straight from their launcher icons. */
object GameSchemes {
    /**
     * Send Off: deep navy hull, tactical cyan and amber visor. Metal, not glass —
     * tight radii, hairline strokes, a HUD that looks like it is bolted on.
     */
    val arena = GameScheme(
        ground = 0xFF101D35.toInt(),
        panel = 0xFF16273F.toInt(),
        well = 0xFF1E3350.toInt(),
        accent = 0xFF38C8EE.toInt(),
        onAccent = 0xFF06111D.toInt(),
        signal = 0xFFFFB24D.toInt(),
        text = 0xFFEAF4FF.toInt(),
        textSecondary = 0xFF9DB4CE.toInt(),
        textTertiary = 0xFF6C86A3.toInt(),
        stroke = 0x33A9D6F5,
        danger = 0xFFFF6B6B.toInt(),
        success = 0xFF4FE0A6.toInt(),
        radius = 14
    )

    /**
     * Chess: a club study. Warm ivory paper, walnut board and brass fittings.
     * A light scheme on purpose — a chess club is a room, not a cockpit.
     */
    val chess = GameScheme(
        ground = 0xFFF2E8D6.toInt(),
        panel = 0xFFFFFAF0.toInt(),
        well = 0xFFEADCC2.toInt(),
        accent = 0xFF6B452B.toInt(),
        onAccent = 0xFFFFF6E6.toInt(),
        signal = 0xFFC9962F.toInt(),
        text = 0xFF25231C.toInt(),
        textSecondary = 0xFF5F584A.toInt(),
        textTertiary = 0xFF8A8071.toInt(),
        stroke = 0x336B452B,
        danger = 0xFFA8332B.toInt(),
        success = 0xFF2F6D4F.toInt(),
        radius = 18,
        light = true
    )

    /**
     * Endless Days: a neon rail arcade at dusk. Slate platform, electric cyan
     * rails, sunset-orange beacons. Big radii, glow, a scoreboard on top.
     */
    val runner = GameScheme(
        ground = 0xFF182132.toInt(),
        panel = 0xFF202C42.toInt(),
        well = 0xFF2A3852.toInt(),
        accent = 0xFF6BCBFF.toInt(),
        onAccent = 0xFF07131F.toInt(),
        signal = 0xFFFF9354.toInt(),
        text = 0xFFF4F8FF.toInt(),
        textSecondary = 0xFFA6B7CE.toInt(),
        textTertiary = 0xFF77879F.toInt(),
        stroke = 0x33B7DAFF,
        danger = 0xFFFF7A6B.toInt(),
        success = 0xFF63E6B0.toInt(),
        radius = 26
    )

    /** The Engine Lab keeps OEA's own amber console. */
    val lab = GameScheme(
        ground = 0xFF08090D.toInt(),
        panel = 0xFF14161E.toInt(),
        well = 0xFF20232E.toInt(),
        accent = 0xFFFFA53D.toInt(),
        onAccent = 0xFF140E04.toInt(),
        signal = 0xFF4FC3F7.toInt(),
        text = 0xFFF2F4F8.toInt(),
        textSecondary = 0xFF9EA4B2.toInt(),
        textTertiary = 0xFF686E7C.toInt(),
        stroke = 0x16FFFFFF,
        radius = 22
    )

    /** The scheme for an engine id, falling back to the game's own accent. */
    fun forId(id: String, accent: Int = GameColors.OEA_AMBER): GameScheme = when (id) {
        "arena" -> arena
        "chess" -> chess
        "runner" -> runner
        "lab" -> lab
        else -> lab.copy(accent = accent)
    }
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

    // --------------------------------------------------- game-identity surfaces


    /** Typeface a game's headline uses. Chess sets its club signage in serif. */
    var paletteTypeface: Typeface = GameType.black

    /**
     * A hero band in the game's own material: a gradient of [signal] into
     * [accent] behind the title block, with an optional [art] motif drawn on the
     * right. This is what replaces the shared "cover image + title + Play"
     * header: every game gets a front-of-box that belongs to it.
     */
    fun heroBand(
        parent: ViewGroup,
        heightDp: Int,
        title: String,
        eyebrow: String?,
        subtitle: String?,
        art: Drawable,
        leftInsetDp: Int = 20,
        horizontal: Boolean = false,
        footer: View? = null,
        titleSizeSp: Float = if (horizontal) 26f else 32f,
        topBar: View? = null
    ): FrameLayout {
        // Composite the artwork and bottom contrast scrim directly on the band
        // background. Child Views with MATCH_PARENT (-1) inside a WRAP_CONTENT
        // FrameLayout force Android to expand the band to the entire viewport,
        // starving the scrolling deck and pushing the bottom action bar off-screen.
        val band = FrameLayout(context).apply {
            clipToOutline = true
            background = android.graphics.drawable.LayerDrawable(
                arrayOf(art, GameArt.scrim(palette.background, 0xDC))
            )
        }

        val vPadTop = dp(if (horizontal) 10 else 14)
        val vPadBottom = dp(if (horizontal) 10 else 16)
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(leftInsetDp), vPadTop, dp(20), vPadBottom)
            gravity = Gravity.BOTTOM
        }
        if (topBar != null) {
            column.addView(topBar, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(if (horizontal) 6 else 10)
            })
        }
        if (!eyebrow.isNullOrBlank()) {
            column.addView(
                text(eyebrow.uppercase(), 10.5f, palette.accent, GameType.medium).apply {
                    letterSpacing = 0.2f
                }
            )
        }
        column.addView(
            text(title, titleSizeSp, palette.text, paletteTypeface).apply {
                letterSpacing = -0.02f
                setPadding(0, dp(if (horizontal) 2 else 4), 0, 0)
            }
        )
        if (!subtitle.isNullOrBlank()) {
            column.addView(
                text(subtitle, if (horizontal) 12f else 13f, palette.textSecondary).apply {
                    setPadding(0, dp(if (horizontal) 2 else 4), 0, 0)
                    setLineSpacing(dpf(2f), 1f)
                }
            )
        }
        if (footer != null) {
            footer.setPadding(0, dp(if (horizontal) 6 else 10), 0, 0)
            column.addView(footer, LinearLayout.LayoutParams(-1, -2))
        }
        val minHeightDp = if (horizontal) heightDp.coerceAtMost(96) else heightDp
        column.minimumHeight = dp(minHeightDp)
        band.addView(column, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        parent.addView(band, LinearLayout.LayoutParams(-1, -2))
        return band
    }


    /** One number that matters, with its caption: XP, record, best score. */
    fun statTile(value: String, caption: String, accent: Boolean = false, wide: Boolean = false): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = ripple(rounded(if (accent) palette.accentSoft else palette.card, 14, palette.stroke, 1), 14)
            addView(
                text(value, if (wide) 26f else 22f, if (accent) palette.accent else palette.text, GameType.mono).apply {
                    isSingleLine = true
                }
            )
            addView(
                text(caption.uppercase(), 9.5f, palette.textTertiary, GameType.medium).apply {
                    letterSpacing = 0.12f
                    setPadding(0, dp(3), 0, 0)
                }
            )
        }

    /** A row of [statTile]s that share the width evenly. */
    fun statStrip(parent: ViewGroup, values: List<Pair<String, String>>, accentFirst: Boolean = true) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        values.forEachIndexed { index, (value, caption) ->
            row.addView(
                statTile(value, caption, accent = accentFirst && index == 0),
                LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) leftMargin = dp(8) }
            )
        }
        parent.addView(row, LinearLayout.LayoutParams(-1, -2))
    }

    /**
     * A segmented selector: the game's primary choices (mode, time control,
     * side) as a strip of mutually exclusive tabs, so a front screen can show
     * where you are without a modal dialog in front of it.
     */
    fun segmented(
        parent: ViewGroup,
        labels: List<String>,
        selected: Int,
        onPick: (Int) -> Unit
    ): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = rounded(palette.card, 16, palette.stroke, 1)
        }
        labels.forEachIndexed { index, label ->
            val active = index == selected
            row.addView(
                text(label, 13f, if (active) palette.onAccent else palette.textSecondary, GameType.medium).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(6), dp(10), dp(6), dp(10))
                    background = if (active) ripple(rounded(palette.accent, 13), 13) else null
                    isClickable = true
                    setOnClickListener { onPick(index) }
                },
                LinearLayout.LayoutParams(0, -2, 1f)
            )
        }
        parent.addView(row, LinearLayout.LayoutParams(-1, -2))
        return row
    }

    /** A horizontally scrolling row of chips; one is selected. */
    fun chipRow(parent: ViewGroup, labels: List<String>, selected: Int, onPick: (Int) -> Unit, accent: Int = palette.accent) {
        val strip = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        labels.forEachIndexed { index, label ->
            val active = index == selected
            strip.addView(
                text(label, 12f, if (active) palette.onAccent else palette.textSecondary, GameType.medium).apply {
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    background = ripple(
                        rounded(if (active) accent else palette.card, 12, if (active) 0 else palette.stroke, if (active) 0 else 1),
                        12
                    )
                    isClickable = true
                    setOnClickListener { onPick(index) }
                },
                LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(7) }
            )
        }
        parent.addView(
            HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                clipToPadding = false
                addView(strip)
            },
            LinearLayout.LayoutParams(-1, -2)
        )
    }

    /**
     * A labelled "this is what is chosen" row for the front screen: title, live
     * value, and a chevron. Unlike [actionRow] it sits in a game's own group with
     * no glyph badge, so a match setup reads like a scorecard line.
     */
    fun valueRow(
        parent: ViewGroup,
        title: String,
        value: String,
        subtitle: String? = null,
        accentValue: Boolean = false,
        onClick: () -> Unit
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(54)
        setPadding(dp(14), dp(11), dp(12), dp(11))
        background = ripple(rounded(palette.card, 12, palette.stroke, 1), 12)
        isClickable = true
        contentDescription = if (subtitle.isNullOrBlank()) "$title: $value" else "$title: $value. $subtitle"
        setOnClickListener { onClick() }
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(title, 14.5f, palette.text, GameType.medium))
        if (!subtitle.isNullOrBlank()) {
            texts.addView(text(subtitle, 11.5f, palette.textTertiary).apply { setPadding(0, dp(2), 0, 0) })
        }
        addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        addView(
            text(value, 14f, if (accentValue) palette.accent else palette.textSecondary, GameType.medium).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), 0, dp(6), 0)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(-2, -2)
        )
        addView(text("›", 20f, palette.textTertiary).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(14), dp(20)))
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
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

    /**
     * A landscape "deck": a hero band across the top, two independent columns
     * and a sticky action bar at the bottom.
     *
     * This is the shape a shooter front screen wants on a phone held sideways —
     * career and loadout on one side, modes and the lobby on the other — and it
     * is what a portrait game never needs. [GameKit.deck] collapses to one column
     * on a portrait screen so the same code still opens sensibly if the device
     * refuses to rotate.
     */
    class Deck(val root: FrameLayout, val top: FrameLayout, val left: LinearLayout, val right: LinearLayout, val bar: LinearLayout, val wide: Boolean)

    fun deck(padBottomDp: Int = 14): Deck {
        val wide = context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val root = FrameLayout(context).apply { setBackgroundColor(palette.background) }
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val top = FrameLayout(context)
        column.addView(top, LinearLayout.LayoutParams(-1, -2))

        val left = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(if (wide) 10 else 18), dp(20))
        }
        val right = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(if (wide) 10 else 18), dp(10), dp(18), dp(20))
        }
        if (wide) {
            val body = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            body.addView(verticalScroll(left), LinearLayout.LayoutParams(0, -1, 1f))
            body.addView(verticalScroll(right), LinearLayout.LayoutParams(0, -1, 1f))
            column.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            val stack = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            stack.addView(left, LinearLayout.LayoutParams(-1, -2))
            stack.addView(right, LinearLayout.LayoutParams(-1, -2))
            column.addView(verticalScroll(stack), LinearLayout.LayoutParams(-1, 0, 1f))
        }

        column.addView(
            View(context).apply { setBackgroundColor(palette.stroke) },
            LinearLayout.LayoutParams(-1, maxOf(1, dp(1)))
        )
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(if (wide) 8 else 10), dp(16), dp(if (wide) 8 else padBottomDp))
            setBackgroundColor(GameColors.blend(palette.background, palette.card, 0.9f))
        }
        column.addView(bar, LinearLayout.LayoutParams(-1, -2))
        root.addView(column, FrameLayout.LayoutParams(-1, -1))

        val base = intArrayOf(column.paddingLeft, column.paddingTop, column.paddingRight, column.paddingBottom)
        val barBottom = bar.paddingBottom
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = GameInsets.bars(insets)
            column.setPadding(base[0] + bars[0], base[1] + bars[1], base[2] + bars[2], base[3])
            bar.setPadding(bar.paddingLeft, bar.paddingTop, bar.paddingRight, barBottom + bars[3])
            insets
        }
        return Deck(root, top, left, right, bar, wide)
    }

    private fun verticalScroll(child: View): ScrollView = ScrollView(context).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        clipToPadding = false
        addView(child, ViewGroup.LayoutParams(-1, -2))
    }

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
    fun hscroll(child: View): HorizontalScrollView = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        clipToPadding = false
        addView(child)
    }

    /** Same as [hscroll]; named for rows of chips and swatches. */
    fun hscrollRow(child: View): HorizontalScrollView = hscroll(child)

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

    /** A one-line text prompt in a sheet; [onResult] gets the text or null. */
    fun prompt(title: String, hint: String, initial: String = "", onResult: (String?) -> Unit) {
        val input = field(hint, initial)
        sheet(title, null) { panel, dialog ->
            panel.addView(input, LinearLayout.LayoutParams(-1, -2))
            panel.addView(
                buttonRow(
                    quietButton("Cancel") { dialog.dismiss(); onResult(null) },
                    primaryButton("OK") { dialog.dismiss(); onResult(input.text?.toString()) }
                )
            )
        }
        input.post {
            input.requestFocus()
            (context.getSystemService(android.view.inputmethod.InputMethodManager::class.java))
                ?.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
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
 * Each game's own front-of-box art, drawn in code.
 *
 * A shooter's band is a sight picture over a scope grid, a chess club's is a
 * walnut board with a brass rule, a rail runner's is a receding track under
 * neon. None of them is "the accent colour with the glyph in the corner", which
 * is what made the three games look like one app before this existed.
 */
object GameArt {
    /** A flat colour as a drawable, for views that only take a background. */
    fun solid(color: Int): Drawable = ColorDrawable(color)

    /** A vertical fade used to sink artwork behind type. */
    fun scrim(color: Int, alpha: Int, fromTop: Boolean = false): Drawable {
        val a = GameColors.alpha(color, alpha)
        val clear = GameColors.alpha(color, 0)
        return object : Drawable() {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun draw(canvas: Canvas) {
                val b = bounds
                paint.shader = LinearGradient(
                    0f, b.top.toFloat(), 0f, b.bottom.toFloat(),
                    if (fromTop) intArrayOf(a, clear) else intArrayOf(clear, a),
                    null, Shader.TileMode.CLAMP
                )
                canvas.drawRect(b, paint)
            }
            override fun setAlpha(alpha2: Int) { paint.alpha = alpha2 }
            override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }
    }

    /** The hero band of [gameId], in that game's own material. */
    fun hero(gameId: String, scheme: GameScheme): Drawable = when (gameId) {
        "arena" -> ArenaArt(scheme)
        "chess" -> ChessArt(scheme)
        "runner" -> RunnerArt(scheme)
        else -> LabArt(scheme)
    }

    /** The small motif drawn beside a title, e.g. on a hub card. */
    fun glyphArt(gameId: String, scheme: GameScheme): Drawable = when (gameId) {
        "arena" -> ReticleArt(scheme)
        "chess" -> KnightArt(scheme)
        "runner" -> TrackArt(scheme)
        else -> ReticleArt(scheme)
    }

    // ------------------------------------------------------------ the shooter

    /** A sight picture: horizon, scope grid, range rings, a warm visor accent. */
    private class ArenaArt(private val s: GameScheme) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(canvas: Canvas) {
            val b = bounds
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            if (w <= 0f || h <= 0f) return
            paint.shader = LinearGradient(
                0f, b.top.toFloat(), 0f, b.bottom.toFloat(),
                intArrayOf(GameColors.blend(s.ground, Color.BLACK, 0.25f), GameColors.blend(s.ground, s.accent, 0.22f)),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawRect(b, paint)
            paint.shader = null
            // Distant ridgeline.
            val ridge = Path()
            ridge.moveTo(b.left.toFloat(), b.top + h)
            var x = b.left.toFloat()
            var i = 0
            while (x <= b.right) {
                val y = b.top + h * (0.58f + 0.1f * sin(i * 0.7f + 0.4f))
                ridge.lineTo(x, y)
                x += w / 26f
                i++
            }
            ridge.lineTo(b.right.toFloat(), b.top + h)
            ridge.close()
            paint.color = GameColors.alpha(Color.BLACK, 0x66)
            canvas.drawPath(ridge, paint)
            // Scope grid.
            paint.strokeWidth = h * 0.004f
            paint.color = GameColors.alpha(s.accent, 0x2A)
            for (k in 1..7) canvas.drawLine(b.left + w * k / 8f, b.top.toFloat(), b.left + w * k / 8f, b.top + h, paint)
            for (k in 1..3) canvas.drawLine(b.left.toFloat(), b.top + h * k / 4f, b.right.toFloat(), b.top + h * k / 4f, paint)
            // Range rings on the right: the reticle.
            val cx = b.left + w * 0.78f
            val cy = b.top + h * 0.42f
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = h * 0.012f
            for (k in 1..3) {
                paint.color = GameColors.alpha(s.signal, 0x66 - k * 0x14)
                canvas.drawCircle(cx, cy, h * 0.11f * k, paint)
            }
            paint.color = GameColors.alpha(s.accent, 0xD0)
            paint.strokeWidth = h * 0.008f
            canvas.drawLine(cx - h * 0.2f, cy, cx - h * 0.05f, cy, paint)
            canvas.drawLine(cx + h * 0.05f, cy, cx + h * 0.2f, cy, paint)
            canvas.drawLine(cx, cy - h * 0.2f, cx, cy - h * 0.05f, paint)
            canvas.drawLine(cx, cy + h * 0.05f, cx, cy + h * 0.2f, paint)
            paint.style = Paint.Style.FILL
            paint.color = s.signal
            canvas.drawCircle(cx, cy, h * 0.012f, paint)
            // Amber scan sweep along the bottom edge.
            paint.shader = LinearGradient(
                b.left.toFloat(), 0f, b.right.toFloat(), 0f,
                intArrayOf(GameColors.alpha(s.signal, 0), GameColors.alpha(s.signal, 0x88), GameColors.alpha(s.signal, 0)),
                floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawRect(b.left.toFloat(), b.top + h * 0.94f, b.right.toFloat(), b.top + h, paint)
            paint.shader = null
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    /** A scope reticle on its own, for a hub tile. */
    private class ReticleArt(private val s: GameScheme) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(canvas: Canvas) {
            val b = bounds
            val cx = b.exactCenterX()
            val cy = b.exactCenterY()
            val r = minOf(b.width(), b.height()) * 0.34f
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = r * 0.09f
            paint.color = GameColors.alpha(s.accent, 0xC0)
            canvas.drawCircle(cx, cy, r, paint)
            paint.color = GameColors.alpha(s.signal, 0x90)
            canvas.drawCircle(cx, cy, r * 0.55f, paint)
            paint.strokeWidth = r * 0.07f
            canvas.drawLine(cx - r * 1.4f, cy, cx - r * 0.3f, cy, paint)
            canvas.drawLine(cx + r * 0.3f, cy, cx + r * 1.4f, cy, paint)
            canvas.drawLine(cx, cy - r * 1.4f, cx, cy - r * 0.3f, paint)
            canvas.drawLine(cx, cy + r * 0.3f, cx, cy + r * 1.4f, paint)
            paint.style = Paint.Style.FILL
            paint.color = s.signal
            canvas.drawCircle(cx, cy, r * 0.1f, paint)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    // -------------------------------------------------------------- the club

    /** Walnut and brass: a board edge-on, a rank of pieces, a rule of gold. */
    private class ChessArt(private val s: GameScheme) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val piece = Path()
        override fun draw(canvas: Canvas) {
            val b = bounds
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            if (w <= 0f || h <= 0f) return
            paint.shader = null
            paint.color = s.ground
            canvas.drawRect(b, paint)
            // A walnut table across the lower two thirds.
            paint.shader = LinearGradient(
                0f, b.top + h * 0.3f, 0f, b.top + h,
                intArrayOf(GameColors.blend(s.accent, Color.BLACK, 0.15f), GameColors.blend(s.accent, Color.BLACK, 0.45f)),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawRect(b.left.toFloat(), b.top + h * 0.34f, b.right.toFloat(), b.top + h, paint)
            paint.shader = null
            // Brass rule.
            paint.color = GameColors.alpha(s.signal, 0xCC)
            canvas.drawRect(b.left.toFloat(), b.top + h * 0.335f, b.right.toFloat(), b.top + h * 0.345f, paint)
            // The board itself, seen at a shallow angle: three rows of squares.
            val boardTop = b.top + h * 0.44f
            val sq = h * 0.15f
            paint.color = GameColors.alpha(Color.WHITE, 0x1A)
            canvas.drawRect(b.left.toFloat(), boardTop - sq * 0.3f, b.right.toFloat(), boardTop + sq * 3.2f, paint)
            for (row in 0 until 4) {
                for (col in 0 until (w / sq).toInt() + 1) {
                    if ((row + col) % 2 != 0) continue
                    paint.color = GameColors.alpha(Color.WHITE, 0x30)
                    canvas.drawRect(
                        b.left + col * sq, boardTop + row * sq,
                        b.left + (col + 1) * sq, boardTop + (row + 1) * sq, paint
                    )
                }
            }
            // A knight and a pawn, in profile, sitting on the board.
            drawPiece(canvas, b.left + w * 0.72f, boardTop + sq * 1.1f, sq * 1.25f)
            // Cast a long warm light from the top left.
            paint.shader = RadialGradient(
                b.left + w * 0.1f, b.top.toFloat(), h * 0.9f,
                GameColors.alpha(Color.WHITE, 0x40), Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
            canvas.drawRect(b, paint)
            paint.shader = null
        }

        private fun drawPiece(canvas: Canvas, cx: Float, baseY: Float, size: Float) {
            paint.style = Paint.Style.FILL
            paint.color = GameColors.blend(s.panel, Color.WHITE, 0.15f)
            piece.reset()
            // Base, stem and a horselike head: a chess piece silhouette.
            piece.addRect(cx - size * 0.30f, baseY - size * 0.14f, cx + size * 0.30f, baseY, Path.Direction.CW)
            piece.addRect(cx - size * 0.18f, baseY - size * 0.62f, cx + size * 0.16f, baseY - size * 0.12f, Path.Direction.CW)
            piece.moveTo(cx - size * 0.16f, baseY - size * 0.58f)
            piece.lineTo(cx - size * 0.10f, baseY - size * 1.02f)
            piece.lineTo(cx + size * 0.26f, baseY - size * 0.94f)
            piece.lineTo(cx + size * 0.30f, baseY - size * 0.80f)
            piece.lineTo(cx + size * 0.06f, baseY - size * 0.74f)
            piece.lineTo(cx + size * 0.18f, baseY - size * 0.58f)
            piece.close()
            canvas.drawPath(piece, paint)
            paint.color = GameColors.alpha(Color.BLACK, 0x44)
            canvas.drawRect(cx - size * 0.34f, baseY - size * 0.05f, cx + size * 0.34f, baseY + size * 0.03f, paint)
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    /** A brass knight for a hub tile. */
    private class KnightArt(private val s: GameScheme) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(canvas: Canvas) {
            val b = bounds
            val cx = b.exactCenterX()
            val cy = b.exactCenterY()
            val size = minOf(b.width(), b.height()) * 0.9f
            paint.color = s.signal
            val p = Path()
            val baseY = cy + size * 0.38f
            p.addRect(cx - size * 0.28f, baseY - size * 0.10f, cx + size * 0.28f, baseY, Path.Direction.CW)
            p.addRect(cx - size * 0.15f, baseY - size * 0.50f, cx + size * 0.13f, baseY - size * 0.08f, Path.Direction.CW)
            p.moveTo(cx - size * 0.14f, baseY - size * 0.46f)
            p.lineTo(cx - size * 0.08f, baseY - size * 0.92f)
            p.lineTo(cx + size * 0.22f, baseY - size * 0.84f)
            p.lineTo(cx + size * 0.26f, baseY - size * 0.70f)
            p.lineTo(cx + size * 0.05f, baseY - size * 0.64f)
            p.lineTo(cx + size * 0.16f, baseY - size * 0.46f)
            p.close()
            canvas.drawPath(p, paint)
            paint.color = GameColors.alpha(Color.BLACK, 0x55)
            canvas.drawCircle(cx + size * 0.02f, baseY - size * 0.80f, size * 0.022f, paint)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    // --------------------------------------------------------------- the rail

    /** Receding rails under neon lamps, with the skyline of the yard behind. */
    private class RunnerArt(private val s: GameScheme) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(canvas: Canvas) {
            val b = bounds
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            if (w <= 0f || h <= 0f) return
            paint.shader = LinearGradient(
                0f, b.top.toFloat(), 0f, b.top + h * 0.7f,
                intArrayOf(GameColors.blend(s.ground, s.signal, 0.12f), s.ground),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawRect(b, paint)
            paint.shader = null
            // Sunset glow behind the yard.
            paint.shader = RadialGradient(
                b.left + w * 0.5f, b.top + h * 0.62f, h * 0.6f,
                GameColors.alpha(s.signal, 0x66), Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
            canvas.drawRect(b, paint)
            paint.shader = null
            // Skyline.
            paint.color = GameColors.alpha(Color.BLACK, 0x55)
            var x = b.left.toFloat()
            var i = 0
            while (x < b.right) {
                val bw = w / 16f * (0.6f + ((i * 37) % 10) / 12f)
                val bh = h * (0.10f + ((i * 53) % 13) / 60f)
                canvas.drawRect(x, b.top + h * 0.62f - bh, x + bw * 0.8f, b.top + h * 0.62f, paint)
                x += bw
                i++
            }
            // Rails converging to a vanishing point.
            val vx = b.left + w * 0.5f
            val vy = b.top + h * 0.58f
            paint.strokeWidth = h * 0.018f
            for (k in intArrayOf(-1, 0, 1)) {
                val lane = vx + k * w * 0.055f
                val spread = w * 0.42f
                paint.color = if (k == 0) GameColors.alpha(s.accent, 0xE0) else GameColors.alpha(s.accent, 0x80)
                canvas.drawLine(lane, vy, vx + k * spread + (lane - vx) * 2.2f, b.top + h, paint)
            }
            // Sleepers: short rungs that grow as they approach.
            paint.strokeWidth = h * 0.008f
            var t = 0.06f
            while (t < 1f) {
                val y = vy + (b.top + h - vy) * t * t
                val half = w * 0.03f + w * 0.42f * t * t
                paint.color = GameColors.alpha(s.text, (0x30 * (1f - t * 0.5f)).toInt())
                canvas.drawLine(vx - half, y, vx + half, y, paint)
                t += 0.045f
            }
            // Lamps along the platform, the game's own orange beacons.
            for (k in 0 until 4) {
                val p = 0.18f + k * 0.22f
                val y = vy + (b.top + h - vy) * p * p
                val half = w * 0.03f + w * 0.42f * p * p
                paint.color = GameColors.alpha(s.signal, 0xCC)
                canvas.drawCircle(vx - half, y - h * 0.02f, h * 0.008f + h * 0.01f * p, paint)
                paint.color = GameColors.alpha(s.signal, 0x44)
                canvas.drawCircle(vx + half, y - h * 0.02f, h * 0.008f + h * 0.01f * p, paint)
            }
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    /** Three converging rails for a hub tile. */
    private class TrackArt(private val s: GameScheme) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(canvas: Canvas) {
            val b = bounds
            val vx = b.exactCenterX()
            val top = b.top + b.height() * 0.18f
            val bottom = b.top + b.height() * 0.96f
            paint.strokeWidth = b.height() * 0.05f
            for (k in intArrayOf(-1, 0, 1)) {
                paint.color = if (k == 0) s.accent else GameColors.alpha(s.accent, 0x88)
                val laneTop = vx + k * b.width() * 0.05f
                canvas.drawLine(laneTop, top, vx + k * b.width() * 0.36f, bottom, paint)
            }
            paint.strokeWidth = b.height() * 0.02f
            paint.color = GameColors.alpha(s.signal, 0xCC)
            for (t in 1..5) {
                val f = t / 5f
                val y = top + (bottom - top) * f * f
                val half = b.width() * 0.05f + b.width() * 0.36f * f * f
                canvas.drawLine(vx - half, y, vx + half, y, paint)
            }
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    // ---------------------------------------------------------------- the lab

    private class LabArt(private val s: GameScheme) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(canvas: Canvas) {
            val b = bounds
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            paint.shader = LinearGradient(
                0f, b.top.toFloat(), w, b.top + h,
                intArrayOf(GameColors.blend(s.ground, s.accent, 0.30f), s.ground),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawRect(b, paint)
            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = h * 0.012f
            paint.color = GameColors.alpha(s.accent, 0x50)
            for (i in 0 until 12) {
                val x = b.left + w * (0.4f + 0.05f * i)
                canvas.drawLine(x, b.top + h * (0.5f + 0.4f * sin(i.toFloat())), x + w * 0.1f, b.top + h * (0.5f - 0.4f * sin(i * 1.7f)), paint)
            }
            paint.style = Paint.Style.FILL
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
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
