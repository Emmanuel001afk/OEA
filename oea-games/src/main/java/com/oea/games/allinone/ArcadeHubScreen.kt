package com.oea.games.allinone

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.ColorDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import com.oea.games.ui.GameInsets

/** What the hub can ask of the app that hosts it. */
interface ArcadeHubHost {
    /**
     * Opens a cabinet. [seed] pins the board and [level] pins the rung — the daily
     * challenge passes both, so "today's board" is the same board and the same level
     * for every player, not each player's own progress.
     */
    fun openCabinet(spec: ArcadeSpec, seed: Long? = null, level: Int? = null)
    fun openFriends(spec: ArcadeSpec? = null)
    fun openProfile()
    fun toast(message: String)
}

/**
 * The wall of cabinets.
 *
 * This is the screen from the reference picture, rebuilt in OEA's own
 * materials: a warm paper background, one tall card per game in that game's own
 * colours, badges for the loud ones, and a "continue" card for the cabinet you
 * left last. It is deliberately not a grid of identical tiles — that is how a
 * variety arcade turns into a settings page. Each card is a poster for its own
 * game, drawn in code, and each opens that game's own front screen: modes,
 * levels, difficulty, opponents and records, never a cramped shared menu.
 */
class ArcadeHubScreen(
    private val context: Context,
    private val host: ArcadeHubHost,
    private val progress: ArcadeProgress,
) {
    private val density = context.resources.displayMetrics.density
    private val root = FrameLayout(context).apply { setBackgroundColor(ArcadeColors.CREAM) }
    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var selected: ArcadeCategory? = null
    private var query = ""
    private lateinit var coinText: TextView
    private lateinit var gridHolder: LinearLayout
    private lateinit var chipsHolder: LinearLayout
    private lateinit var progressText: TextView
    private lateinit var shelfTitle: TextView

    private fun dp(value: Int): Int = (value * density + 0.5f).toInt()
    private fun dpf(value: Float): Float = value * density

    fun build(): View {
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            addView(content)
        }
        root.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xFFFBF4E8.toInt(), ArcadeColors.CREAM, 0xFFF1E3D0.toInt())
        )
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        content.setPadding(dp(18), dp(14), dp(18), dp(30))
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = GameInsets.bars(insets)
            content.setPadding(dp(18), dp(14) + bars[1], dp(18), dp(30) + bars[3])
            insets
        }
        populate()
        return root
    }

    /** Rebuilds after a game, a level or the coin balance changed. */
    fun refresh() {
        populate()
    }

    private fun populate() {
        content.removeAllViews()
        content.addView(header(), LinearLayout.LayoutParams(-1, -2))
        content.addView(continueCard(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        content.addView(dailyCard(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        content.addView(searchRow(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        chipsHolder = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(4))
        }
        chips()
        content.addView(
            HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(chipsHolder)
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) }
        )
        shelfTitle = TextView(context).apply {
            setTextColor(ArcadeColors.INK_SOFT)
            textSize = 10.5f
            letterSpacing = 0.18f
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            setPadding(dp(2), dp(12), 0, dp(8))
        }
        content.addView(shelfTitle, LinearLayout.LayoutParams(-1, -2))
        gridHolder = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        content.addView(gridHolder, LinearLayout.LayoutParams(-1, -2))
        grid()
        content.addView(friendsCard(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
        content.addView(footer(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
    }

    // ------------------------------------------------------------------ header

    private fun header(): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titles = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(label("OEA ARCADE", 10f, ArcadeColors.PINK, bold = true).apply { letterSpacing = 0.22f })
        titles.addView(label("All in One", 31f, ArcadeColors.INK, bold = true).apply { setPadding(0, dp(2), 0, dp(1)) })
        progressText = label("", 12f, ArcadeColors.INK_SOFT)
        titles.addView(progressText)
        row.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))

        val coins = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = RippleDrawable(ColorStateList.valueOf(0x22000000), rounded(0xFFFFF3D6.toInt(), 22, 0xFFE9C978.toInt(), 1), rounded(Color.WHITE, 22))
            isClickable = true
            setPadding(dp(10), dp(6), dp(12), dp(6))
            setOnClickListener { host.openProfile() }
        }
        coins.addView(label("★", 17f, ArcadeColors.GOLD, bold = true))
        coinText = label("0", 14f, 0xFF8A5A12.toInt(), bold = true)
        coins.addView(coinText, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(5) })
        row.addView(coins, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })

        val surprise = FrameLayout(context).apply {
            background = RippleDrawable(ColorStateList.valueOf(0x22000000), rounded(0xFFFBF4E8.toInt(), 22, 0xFFE2D2BC.toInt(), 1), rounded(Color.WHITE, 22))
            isClickable = true
            contentDescription = "Surprise me — open a random cabinet"
            setOnClickListener {
                val playable = ArcadeCatalog.all().filter { it.isPlayable }
                if (playable.isNotEmpty()) host.openCabinet(playable.random())
            }
        }
        surprise.addView(label("🎲", 17f, ArcadeColors.INK, bold = true).apply { gravity = Gravity.CENTER })
        row.addView(surprise, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(8) })

        val profile = FrameLayout(context).apply {
            background = RippleDrawable(ColorStateList.valueOf(0x22000000), rounded(ArcadeColors.INK, 22), rounded(Color.WHITE, 22))
            isClickable = true
            contentDescription = "Your arcade profile"
            setOnClickListener { host.openProfile() }
        }
        profile.addView(label("☰", 20f, ArcadeColors.WHITE, bold = true).apply { gravity = Gravity.CENTER })
        row.addView(profile, LinearLayout.LayoutParams(dp(44), dp(44)))
        return row
    }

    /**
     * One cabinet a day, the same seed for everyone: a reason to open the app
     * that the wall alone cannot give. The card is deliberately darker and wider
     * than a tile so it reads as an event, not as another game.
     */
    private fun dailyCard(): View {
        val spec = dailySpec()
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(17), dp(15), dp(15), dp(15))
            background = RippleDrawable(
                ColorStateList.valueOf(0x26FFFFFF),
                GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF22203A.toInt(), spec.accent)),
                rounded(Color.WHITE, 22)
            )
            isClickable = true
            setOnClickListener { host.openCabinet(spec, dailySeed(), dailyLevel(spec)) }
        }
        val top = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(label("TODAY'S CHALLENGE", 9.5f, ArcadeColors.MINT, bold = true).apply { letterSpacing = 0.2f }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(label("SAME SEED FOR ALL", 8.5f, Color.argb(200, 255, 255, 255), bold = true).apply { letterSpacing = 0.12f })
        card.addView(top, LinearLayout.LayoutParams(-1, -2))
        card.addView(label(spec.title, 21f, Color.WHITE, bold = true).apply { setPadding(0, dp(5), 0, dp(2)) })
        card.addView(label("${spec.levelName(dailyLevel(spec))} · everyone plays the same board today", 11.5f, Color.argb(225, 255, 255, 255)))
        val action = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(11), 0, 0) }
        action.addView(
            label("Play today's board", 12.5f, Color.WHITE, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(9), dp(16), dp(9))
                background = rounded(Color.argb(48, 255, 255, 255), 15, Color.argb(90, 255, 255, 255), 1)
            },
            LinearLayout.LayoutParams(-2, -2)
        )
        action.addView(label("★ ${dailyStars(spec)}", 12f, ArcadeColors.GOLD, bold = true), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12) })
        card.addView(action, LinearLayout.LayoutParams(-1, -2))
        return card
    }

    private fun searchRow(): View {
        val shell = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(2), dp(12), dp(2))
            background = rounded(0xFFFFFBF4.toInt(), 18, 0xFFE7D8C3.toInt(), 1)
        }
        shell.addView(label("⌕", 18f, ArcadeColors.INK_SOFT, bold = true), LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
        val field = EditText(context).apply {
            hint = "Search ${ArcadeCatalog.all().size} cabinets"
            setHintTextColor(0xFFB3A38D.toInt())
            setTextColor(ArcadeColors.INK)
            textSize = 13f
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = null
            setPadding(0, dp(11), 0, dp(11))
            setText(query)
            setSelection(text.length)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    val next = s?.toString().orEmpty()
                    if (next == query) return
                    query = next
                    renderGrid()
                }
            })
        }
        shell.addView(field, LinearLayout.LayoutParams(0, -2, 1f))
        if (query.isNotBlank()) {
            shell.addView(label("✕", 15f, ArcadeColors.INK_SOFT, bold = true).apply {
                setPadding(dp(10), dp(8), dp(2), dp(8))
                isClickable = true
                setOnClickListener {
                    query = ""
                    populate()
                }
            })
        }
        return shell
    }

    private fun dailySpec(): ArcadeSpec {
        val playable = ArcadeCatalog.all().filter { it.isPlayable }
        if (playable.isEmpty()) return ArcadeCatalog.all().first()
        val day = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR)
        return playable[day % playable.size]
    }

    private fun dailySeed(): Long {
        val calendar = java.util.Calendar.getInstance()
        val year = calendar.get(java.util.Calendar.YEAR).toLong()
        val day = calendar.get(java.util.Calendar.DAY_OF_YEAR).toLong()
        return year * 1000L + day
    }

    /**
     * The rung today's challenge is played on. Fixed per day and the same for every
     * player, which is what the card claims; it used to open at whatever level the
     * player had reached, so two people playing "the same board" were not.
     */
    private fun dailyLevel(spec: ArcadeSpec): Int {
        val day = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR)
        return 1 + (day % spec.levels.coerceAtLeast(1))
    }

    private fun dailyStars(spec: ArcadeSpec): Int = progress.stars(spec.id, dailyLevel(spec))

    private fun continueCard(): View {
        val spec = progress.continueSpec() ?: ArcadeCatalog.byId("racing") ?: ArcadeCatalog.all().first()
        val level = progress.unlocked(spec.id).coerceAtLeast(1)
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(14), dp(14))
            background = RippleDrawable(
                ColorStateList.valueOf(0x2AFFFFFF),
                rounded(spec.accent, 20),
                rounded(Color.WHITE, 20)
            )
            isClickable = true
            setOnClickListener { host.openCabinet(spec) }
        }
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(label("KEEP GOING", 9.5f, Color.argb(210, 255, 255, 255), bold = true).apply { letterSpacing = 0.2f })
        texts.addView(label(spec.title, 18f, Color.WHITE, bold = true).apply { setPadding(0, dp(1), 0, dp(1)) })
        texts.addView(
            label("${spec.levelName(level)} · ${progress.stars(spec.id, level)}/3 stars · played ${progress.playedCount(spec.id)}×", 11.5f, Color.argb(225, 255, 255, 255))
        )
        val fraction = progress.levelFraction(spec.id).coerceIn(0f, 1f)
        val rail = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        rail.addView(View(context).apply { background = rounded(Color.WHITE, 2) },
            LinearLayout.LayoutParams(0, dp(4), fraction.coerceAtLeast(0.0001f)))
        rail.addView(View(context), LinearLayout.LayoutParams(0, dp(4), (1f - fraction).coerceAtLeast(0.0001f)))
        texts.addView(rail, LinearLayout.LayoutParams(-1, dp(4)).apply { topMargin = dp(7); rightMargin = dp(10) })
        card.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        val play = FrameLayout(context).apply { background = rounded(Color.argb(235, 255, 255, 255), 24) }
        play.addView(label("▶", 17f, spec.accent, bold = true).apply { gravity = Gravity.CENTER })
        card.addView(play, LinearLayout.LayoutParams(dp(50), dp(50)))
        return card
    }

    // ------------------------------------------------------------------- chips

    private fun chips() {
        val entries = listOf<Pair<String, ArcadeCategory?>>("All" to null) +
            ArcadeCategory.entries.map { it.label to it }
        entries.forEach { (name, category) ->
            val active = selected == category
            val chip = label(name, 12.5f, if (active) Color.WHITE else ArcadeColors.INK, bold = active).apply {
                setPadding(dp(15), dp(9), dp(15), dp(9))
                gravity = Gravity.CENTER
                background = if (active) rounded(ArcadeColors.INK, 18)
                else rounded(0xFFFBF4E8.toInt(), 18, 0xFFE2D2BC.toInt(), 1)
                isClickable = true
                setOnClickListener {
                    selected = category
                    populate()
                }
            }
            chipsHolder.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
        }
    }

    // -------------------------------------------------------------------- grid

    private fun renderGrid() {
        gridHolder.removeAllViews()
        grid()
    }

    private fun grid() {
        var specs = if (selected == null) ArcadeCatalog.all() else ArcadeCatalog.inCategory(selected)
        if (selected == ArcadeCategory.FAVOURITES) {
            val recent = progress.recent().mapNotNull { ArcadeCatalog.byId(it) }
            specs = recent.ifEmpty { ArcadeCatalog.all().filter { it.badge != null } }
        }
        if (query.isNotBlank()) {
            specs = specs.filter {
                it.title.contains(query, ignoreCase = true) ||
                    it.tagline.contains(query, ignoreCase = true) ||
                    it.category.label.contains(query, ignoreCase = true)
            }
        }
        shelfTitle.text = if (query.isNotBlank()) {
            "RESULTS · ${specs.size}"
        } else {
            "${selected?.label ?: "ALL CABINETS"} · ${specs.size}"
        }
        if (specs.isEmpty()) {
            gridHolder.addView(
                label("No cabinet matches \u201c$query\u201d. Try a game name or a shelf.", 13f, ArcadeColors.INK_SOFT).apply {
                    setPadding(dp(4), dp(24), dp(4), dp(24))
                }
            )
            progressText.text = "${progress.totalStars()} stars · ${ArcadeCatalog.all().count { it.isPlayable }} cabinets"
            coinText.text = progress.coins().toString()
            return
        }
        var i = 0
        while (i < specs.size) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 2f
            }
            val left = specs[i]
            row.addView(tile(left), LinearLayout.LayoutParams(0, dp(238), 1f).apply { rightMargin = dp(11) })
            val right = specs.getOrNull(i + 1)
            if (right != null) {
                row.addView(tile(right), LinearLayout.LayoutParams(0, dp(238), 1f))
            } else {
                row.addView(View(context), LinearLayout.LayoutParams(0, dp(238), 1f))
            }
            gridHolder.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
            i += 2
        }
        progressText.text = "${progress.totalStars()} stars · ${ArcadeCatalog.all().count { it.isPlayable }} cabinets"
        coinText.text = progress.coins().toString()
    }

    private fun tile(spec: ArcadeSpec): View = ArcadeTileView(context, spec, progress).apply {
        setOnClickListener { host.openCabinet(spec) }
    }

    // ----------------------------------------------------------------- banners

    private fun friendsCard(): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = rounded(ArcadeColors.NIGHT, 24)
        }
        card.addView(label("PLAY WITH A FRIEND", 10f, ArcadeColors.MINT, bold = true).apply { letterSpacing = 0.2f })
        card.addView(label("One phone, or two", 20f, Color.WHITE, bold = true).apply { setPadding(0, dp(3), 0, dp(3)) })
        card.addView(
            label(
                "Pass and play is built into every two-player cabinet. On two phones, host on Wi-Fi or with a code and both play the same seeded level, then compare scores.",
                12.5f, 0xFFBEB6C9.toInt()
            ).apply { setLineSpacing(dpf(3f), 1f) }
        )
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        row.addView(
            label("Host or join", 13.5f, ArcadeColors.INK, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(18), dp(11), dp(18), dp(11))
                background = RippleDrawable(ColorStateList.valueOf(0x33000000), rounded(ArcadeColors.MINT, 16), rounded(Color.WHITE, 16))
                isClickable = true
                setOnClickListener { host.openFriends() }
            },
            LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) }
        )
        row.addView(
            label("How it works", 13.5f, Color.WHITE, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(18), dp(11), dp(18), dp(11))
                background = RippleDrawable(
                    ColorStateList.valueOf(0x22FFFFFF),
                    rounded(0x00000000, 16, 0x66FFFFFF, 1),
                    rounded(Color.WHITE, 16)
                )
                isClickable = true
                setOnClickListener {
                    host.toast("Host on the same Wi-Fi or hotspot. The guest joins nearby or pastes the Wi-Fi code. No account, no server.")
                }
            }
        )
        card.addView(row)
        return card
    }

    private fun footer(): View {
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(
            label(
                "Every cabinet has its own front screen — modes, levels, four difficulties, the OEA AI, pass-and-play and records. Nothing is squeezed into one shared game.",
                11.5f, ArcadeColors.INK_SOFT
            ).apply { setLineSpacing(dpf(3f), 1f) }
        )
        column.addView(
            label("Made for OEA · no ads, no accounts, no coins to buy", 10.5f, 0xFF9C8F7D.toInt()).apply {
                setPadding(0, dp(8), 0, 0)
            }
        )
        return column
    }

    // ------------------------------------------------------------------ widgets

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(context).apply {
            text = value
            textSize = size
            setTextColor(color)
            typeface = android.graphics.Typeface.create("sans-serif", if (bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            includeFontPadding = false
        }

    private fun rounded(color: Int, radiusDp: Int, stroke: Int = 0, strokeDp: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dpf(radiusDp.toFloat())
            if (strokeDp > 0) setStroke(dp(strokeDp), stroke)
        }
}

/**
 * One poster on the wall.
 *
 * Everything is drawn: the scene (from [ArcadeDraw.art]), a top scrim so the
 * title reads over any picture, the game's name, its badge, and a level bar with
 * the stars earned. The view has no children, so scrolling stays smooth even
 * with sixteen of them.
 */
class ArcadeTileView(
    context: Context,
    private val spec: ArcadeSpec,
    private val progress: ArcadeProgress,
) : View(context) {

    private val density = context.resources.displayMetrics.density
    private val scene = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "${spec.title}. ${spec.tagline}. ${spec.levels} levels."
        setBackgroundColor(Color.TRANSPARENT)
    }

    private fun dp(value: Float): Float = value * density

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val radius = dp(22f)
        scene.set(dp(1f), dp(1f), w - dp(1f), h - dp(1f))

        // The card's ground gives each game a colour even before its art lands.
        paint.color = Color.argb(20, Color.red(spec.accent), Color.green(spec.accent), Color.blue(spec.accent))
        canvas.drawRoundRect(scene, radius, radius, paint)

        val artRect = RectF(scene.left, scene.top, scene.right, scene.top + h * 0.72f)
        ArcadeDraw.art(canvas, spec.art, artRect, spec.accent)

        // A soft diagonal gloss makes the poster feel like printed card rather
        // than a flat rectangle of colour.
        paint.shader = LinearGradient(
            artRect.left, artRect.bottom, artRect.right, artRect.top,
            intArrayOf(0x00FFFFFF, 0x1FFFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(artRect, radius, radius, paint)
        paint.shader = null

        // Scrim, so the title is legible over a bright scene.
        paint.shader = LinearGradient(
            0f, artRect.top, 0f, artRect.top + artRect.height() * 0.62f,
            intArrayOf(0xCC000000.toInt(), 0x00000000), null, Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(artRect, radius, radius, paint)
        paint.shader = null

        // Title block.
        val titleSize = ArcadeDraw.fitted(spec.title, dp(17f), w - dp(28f), bold = true)
        ArcadeDraw.text(canvas, spec.title, dp(14f), artRect.top + dp(30f), titleSize, ArcadeColors.WHITE, bold = true)
        paint.color = spec.accent
        canvas.drawRoundRect(dp(14f), artRect.top + dp(36f), dp(14f) + minOf(dp(58f), w * 0.32f), artRect.top + dp(39f), dp(2f), dp(2f), paint)

        // Badge: a slightly rotated ribbon, like a shop sticker.
        spec.badge?.let { text ->
            canvas.save()
            val bw = ArcadeDraw.measure(text, dp(9.5f), bold = true) + dp(18f)
            val bx = scene.right - bw - dp(10f)
            val by = scene.top + dp(12f)
            canvas.rotate(-7f, bx + bw / 2f, by + dp(10f))
            badgePaint.color = spec.badgeColor.takeIf { it != 0 } ?: ArcadeColors.CORAL
            canvas.drawRoundRect(bx, by, bx + bw, by + dp(22f), dp(8f), dp(8f), badgePaint)
            ArcadeDraw.text(canvas, text, bx + bw / 2f, by + dp(15f), dp(9.5f), ArcadeColors.WHITE, bold = true, align = Paint.Align.CENTER)
            canvas.restore()
        }

        // Bottom plate: the game's promise, level bar and stars.
        val plateTop = artRect.bottom
        ArcadeDraw.panel(canvas, scene.left, plateTop, scene.right, scene.bottom,
            Color.argb(245, 255, 255, 255), radius)
        // Square off the top of the white plate so it meets the art cleanly.
        paint.color = Color.argb(245, 255, 255, 255)
        canvas.drawRect(scene.left, plateTop, scene.right, plateTop + radius, paint)

        val unlocked = progress.unlocked(spec.id)
        val stars = (1..spec.levels).sumOf { progress.stars(spec.id, it) }
        val pillText = "LVL $unlocked/${spec.levels}"
        val pillWidth = ArcadeDraw.measure(pillText, dp(10f), bold = true) + dp(18f)
        paint.color = Color.argb(28, Color.red(spec.accent), Color.green(spec.accent), Color.blue(spec.accent))
        canvas.drawRoundRect(dp(14f), plateTop + dp(11f), dp(14f) + pillWidth, plateTop + dp(27f), dp(8f), dp(8f), paint)
        ArcadeDraw.text(canvas, pillText, dp(23f), plateTop + dp(22f), dp(10f), spec.accent, bold = true)

        // Earned stars, drawn one by one so a player can see what is left.
        val earned = progress.stars(spec.id, unlocked)
        var starX = scene.right - dp(14f)
        for (index in 3 downTo 1) {
            val full = index <= earned
            ArcadeDraw.text(canvas, "★", starX, plateTop + dp(23f), dp(12f),
                if (full) 0xFFE0A21B.toInt() else 0xFFE3D6C4.toInt(), bold = true, align = Paint.Align.RIGHT)
            starX -= dp(14f)
        }

        // Progress rail with a travelling knob, so the bar reads at a glance.
        val railLeft = dp(14f)
        val railRight = scene.right - dp(14f)
        val railY = plateTop + dp(34f)
        paint.color = 0xFFEDE3D5.toInt()
        canvas.drawRoundRect(railLeft, railY, railRight, railY + dp(6f), dp(3f), dp(3f), paint)
        val fraction = progress.levelFraction(spec.id).coerceIn(0f, 1f)
        if (fraction > 0.001f) {
            paint.color = spec.accent
            canvas.drawRoundRect(railLeft, railY, railLeft + (railRight - railLeft) * fraction, railY + dp(6f), dp(3f), dp(3f), paint)
            paint.color = ArcadeColors.WHITE
            canvas.drawCircle(railLeft + (railRight - railLeft) * fraction, railY + dp(3f), dp(4.5f), paint)
            paint.color = spec.accent
            canvas.drawCircle(railLeft + (railRight - railLeft) * fraction, railY + dp(3f), dp(2.4f), paint)
        }

        // The line that sells the cabinet.
        val line = ArcadeDraw.wrap(spec.tagline, dp(11f), railRight - railLeft, bold = false).firstOrNull().orEmpty()
        ArcadeDraw.text(canvas, line, railLeft, railY + dp(22f), dp(11f), ArcadeColors.INK_SOFT)

        // A thumb affordance for the shell's cards (a real button, not artwork).
        val badgeCircle = plateTop - dp(21f)
        paint.color = Color.argb(235, 255, 255, 255)
        canvas.drawCircle(scene.right - dp(26f), plateTop - dp(18f), dp(17f), paint)
        paint.color = spec.accent
        val arrow = Path().apply {
            moveTo(scene.right - dp(31f), plateTop - dp(23f))
            lineTo(scene.right - dp(22f), plateTop - dp(18f))
            lineTo(scene.right - dp(31f), plateTop - dp(13f))
            close()
        }
        canvas.drawPath(arrow, paint)
        // The circle sits over the plate edge, so redraw its ring.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.5f)
        paint.color = 0x22000000
        canvas.drawCircle(scene.right - dp(26f), plateTop - dp(18f), dp(17f), paint)
        paint.style = Paint.Style.FILL

        // A soft outline keeps light scenes from melting into the paper.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1f)
        paint.color = 0x14000000
        canvas.drawRoundRect(scene, radius, radius, paint)
        paint.style = Paint.Style.FILL
    }
}
