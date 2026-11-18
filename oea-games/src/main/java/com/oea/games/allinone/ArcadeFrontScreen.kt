package com.oea.games.allinone

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** What the front screen asks of the app hosting it. */
interface ArcadeFrontHost {
    fun startCabinet(config: ArcadeConfig)
    fun openFriends(spec: ArcadeSpec)
    fun leaveCabinet()
    fun toast(message: String)
}

/**
 * One cabinet's front door.
 *
 * Every game gets this screen instead of sharing one cramped menu: the game's
 * own picture, its badge, its record card, its level ladder, the four
 * difficulties, and three ways in — against the OEA AI, two people on this
 * phone, or a friend on another phone. The controls are the same everywhere so a
 * player learns the arcade once, and the content is specific so each game still
 * feels like its own app.
 */
class ArcadeFrontScreen(
    private val activity: Activity,
    private val spec: ArcadeSpec,
    private val progress: ArcadeProgress,
    private val host: ArcadeFrontHost,
) {
    private val root = ScrollView(activity)
    private val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private var mode = ArcadePlayMode.AI
    private var level = progress.unlocked(spec.id)
    private var difficulty = progress.difficulty()

    private fun dp(value: Float): Int = ArcadeUi.dp(activity, value)

    fun build(): View {
        root.isFillViewport = true
        root.isVerticalScrollBarEnabled = false
        root.clipToPadding = false
        root.setBackgroundColor(ArcadeColors.CREAM)
        root.addView(content)
        content.setPadding(dp(18f), dp(12f), dp(18f), dp(30f))
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = com.oea.games.ui.GameInsets.bars(insets)
            content.setPadding(dp(18f), dp(12f) + bars[1], dp(18f), dp(30f) + bars[3])
            insets
        }
        populate()
        return root
    }

    fun refresh() = populate()

    private fun populate() {
        content.removeAllViews()
        content.addView(header(), LinearLayout.LayoutParams(-1, -2))
        content.addView(
            ArcadeHero(activity, spec, progress),
            LinearLayout.LayoutParams(-1, dp(172f)).apply { topMargin = dp(12f) }
        )
        content.addView(recordStrip(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12f) })
        content.addView(modeSection(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16f) })
        content.addView(levelSection(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16f) })
        content.addView(actionSection(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14f) })
        content.addView(howToSection(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18f) })
        content.addView(footer(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16f) })
    }

    // ----------------------------------------------------------------- header

    private fun header(): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = ArcadeUi.text(activity, "‹", 26f, ArcadeColors.INK, bold = true).apply {
            gravity = Gravity.CENTER
            background = ArcadeUi.rounded(0xFFFFF7EC.toInt(), dp(20f), 0xFFE8D9C4.toInt(), dp(1f))
            isClickable = true
            setOnClickListener { host.leaveCabinet() }
        }
        row.addView(back, LinearLayout.LayoutParams(dp(40f), dp(40f)).apply { rightMargin = dp(12f) })
        val titles = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(ArcadeUi.text(activity, "ALL IN ONE", 9.5f, spec.accent, bold = true).apply { letterSpacing = 0.2f })
        titles.addView(ArcadeUi.text(activity, spec.title, 23f, ArcadeColors.INK, bold = true))
        row.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        val stars = ArcadeUi.text(activity, "★ ${progress.coins()}", 12.5f, 0xFF8A5A12.toInt(), bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(11f), dp(7f), dp(11f), dp(7f))
            background = ArcadeUi.rounded(0xFFFFF3D6.toInt(), dp(18f), 0xFFE9C978.toInt(), dp(1f))
        }
        row.addView(stars)
        return row
    }

    private fun recordStrip(): View {
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        fun tile(value: String, caption: String, accent: Boolean = false): LinearLayout {
            val tile = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(6f), dp(12f), dp(6f), dp(12f))
                background = ArcadeUi.rounded(
                    if (accent) spec.accent else 0xFFFFFBF4.toInt(), dp(16f),
                    if (accent) 0 else 0xFFEADCC8.toInt(), dp(1f)
                )
            }
            tile.addView(ArcadeUi.text(activity, value, 17f, if (accent) Color.WHITE else ArcadeColors.INK, bold = true))
            tile.addView(ArcadeUi.text(activity, caption.uppercase(), 8.5f, if (accent) Color.argb(220, 255, 255, 255) else ArcadeColors.INK_SOFT).apply { letterSpacing = 0.12f })
            return tile
        }
        val best = progress.best(spec.id, level)
        val stars = progress.stars(spec.id, level)
        row.addView(tile(stars.toString(), "Stars here", true), LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8f) })
        row.addView(tile(if (best > 0) best.toString() else "—", "Best"), LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8f) })
        row.addView(tile("${progress.unlocked(spec.id)}/${spec.levels}", "Unlocked"), LinearLayout.LayoutParams(0, -2, 1f))
        return row
    }

    // ------------------------------------------------------------------- mode

    private fun modeSection(): View {
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        column.addView(sectionTitle("A game"))
        val tabs = if (spec.twoPlayer) listOf("Vs OEA AI", "Two players", "A friend") else listOf("Vs OEA AI", "A friend")
        val active = when {
            !spec.twoPlayer && mode == ArcadePlayMode.TWO_PLAYER -> 0
            spec.twoPlayer -> when (mode) {
                ArcadePlayMode.AI -> 0
                ArcadePlayMode.TWO_PLAYER -> 1
                else -> 2
            }
            else -> if (mode == ArcadePlayMode.FRIENDS) 1 else 0
        }.coerceIn(0, tabs.lastIndex)
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(5f), dp(5f), dp(5f), dp(5f))
            background = ArcadeUi.rounded(0xFFEFE2D0.toInt(), dp(18f))
        }
        tabs.forEachIndexed { index, label ->
            val on = index == active
            val chip = ArcadeUi.text(activity, label, 12f, if (on) Color.WHITE else ArcadeColors.INK_SOFT, bold = on).apply {
                gravity = Gravity.CENTER
                setPadding(dp(4f), dp(9f), dp(4f), dp(9f))
                background = if (on) ArcadeUi.rounded(spec.accent, dp(14f)) else null
                isClickable = true
                setOnClickListener {
                    mode = when (index) {
                        0 -> ArcadePlayMode.AI
                        1 -> if (spec.twoPlayer) ArcadePlayMode.TWO_PLAYER else ArcadePlayMode.FRIENDS
                        else -> ArcadePlayMode.FRIENDS
                    }
                    populate()
                }
            }
            row.addView(chip, LinearLayout.LayoutParams(0, -2, 1f))
        }
        column.addView(row, LinearLayout.LayoutParams(-1, -2))
        val blurb = when (mode) {
            ArcadePlayMode.AI -> "The OEA AI plays at ${difficulty.label.lowercase()} — your own difficulty, saved for every cabinet."
            ArcadePlayMode.TWO_PLAYER -> "Pass and play: one phone, two people. ${if (spec.twoPlayer) "Take turns or share the screen." else "This cabinet is single player."}"
            ArcadePlayMode.FRIENDS -> "Two phones: host on Wi-Fi/hotspot or with a code, both play this exact level, then compare scores."
        }
        column.addView(
            ArcadeUi.text(activity, blurb, 11.5f, ArcadeColors.INK_SOFT).apply {
                setPadding(dp(2f), dp(8f), dp(2f), 0f)
                setLineSpacing(dp(2f).toFloat(), 1f)
            }
        )
        if (mode != ArcadePlayMode.FRIENDS) {
            column.addView(sectionTitle("Difficulty"), LinearLayout.LayoutParams(-1, -2))
            val diffRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            ArcadeDifficulty.entries.forEach { item ->
                val on = item == difficulty
                val chip = ArcadeUi.text(activity, item.label, 11.5f, if (on) ArcadeColors.INK else ArcadeColors.INK_SOFT, bold = on).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(3f), dp(9f), dp(3f), dp(9f))
                    background = ArcadeUi.rounded(
                        if (on) 0xFFFFE9B8.toInt() else 0xFFFFFBF4.toInt(), dp(14f),
                        if (on) ArcadeColors.GOLD else 0xFFEADCC8.toInt(), dp(1f)
                    )
                    isClickable = true
                    setOnClickListener {
                        difficulty = item
                        progress.setDifficulty(item)
                        populate()
                    }
                }
                diffRow.addView(chip, LinearLayout.LayoutParams(0, -2, 1f).apply { if (item != ArcadeDifficulty.EXPERT) rightMargin = dp(5f) })
            }
            column.addView(diffRow, LinearLayout.LayoutParams(-1, -2))
            column.addView(
                ArcadeUi.text(activity, difficulty.blurb, 11f, ArcadeColors.INK_SOFT).apply {
                    setPadding(dp(2f), dp(7f), dp(2f), 0f)
                }
            )
        }
        return column
    }

    // ------------------------------------------------------------------ levels

    private fun levelSection(): View {
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        column.addView(sectionTitle("Level · ${spec.levelName(level)}"))
        val strip = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val unlocked = progress.unlocked(spec.id)
        (1..spec.levels).forEach { number ->
            val open = number <= unlocked
            val on = number == level
            val chip = ArcadeUi.text(
                activity,
                if (open) number.toString() else "🔒",
                12f,
                if (on) Color.WHITE else if (open) ArcadeColors.INK else 0xFFB6A893.toInt(),
                bold = on
            ).apply {
                gravity = Gravity.CENTER
                setPadding(dp(11f), dp(10f), dp(11f), dp(10f))
                background = if (on) ArcadeUi.rounded(spec.accent, dp(14f))
                else ArcadeUi.rounded(0xFFFFFBF4.toInt(), dp(14f), 0xFFEADCC8.toInt(), dp(1f))
                isClickable = true
                setOnClickListener {
                    if (open) {
                        level = number
                        populate()
                    } else {
                        host.toast("Clear level ${number - 1} to unlock this one.")
                    }
                }
            }
            strip.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6f) })
        }
        column.addView(
            android.widget.HorizontalScrollView(activity).apply {
                isHorizontalScrollBarEnabled = false
                addView(strip)
            },
            LinearLayout.LayoutParams(-1, -2)
        )
        column.addView(
            ArcadeUi.text(activity, "Level ${level} of ${spec.levels} · ${progress.stars(spec.id, level)} of 3 stars", 11f, ArcadeColors.INK_SOFT)
                .apply { setPadding(dp(2f), dp(7f), 0f, 0f) }
        )
        return column
    }

    // ------------------------------------------------------------------ action

    private fun actionSection(): View {
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val label = when (mode) {
            ArcadePlayMode.FRIENDS -> "Host or join a friend"
            ArcadePlayMode.TWO_PLAYER -> "Pass and play · level $level"
            ArcadePlayMode.AI -> "Play level $level · ${difficulty.label}"
        }
        column.addView(
            ArcadeUi.button(activity, label, spec.accent, Color.WHITE, 20) {
                when (mode) {
                    ArcadePlayMode.FRIENDS -> host.openFriends(spec)
                    else -> host.startCabinet(
                        ArcadeConfig(
                            level = level,
                            difficulty = difficulty,
                            players = if (mode == ArcadePlayMode.TWO_PLAYER) 2 else 1,
                            seed = System.nanoTime(),
                            mode = mode,
                        )
                    )
                }
            },
            LinearLayout.LayoutParams(-1, -2)
        )
        if (mode != ArcadePlayMode.FRIENDS) {
            column.addView(
                ArcadeUi.outlineButton(activity, "How to play", ArcadeColors.INK_SOFT) { howToSheet() },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8f) }
            )
        }
        return column
    }

    private fun howToSection(): View {
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        column.addView(sectionTitle("How it plays"))
        spec.howTo.take(4).forEach { (glyph, line) ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(5f), 0, dp(5f))
            }
            row.addView(
                ArcadeUi.text(activity, glyph, 15f, ArcadeColors.INK).apply {
                    gravity = Gravity.CENTER
                    background = ArcadeUi.rounded(0xFFFFF1DE.toInt(), dp(12f))
                },
                LinearLayout.LayoutParams(dp(34f), dp(34f)).apply { rightMargin = dp(11f) }
            )
            row.addView(
                ArcadeUi.text(activity, line, 12f, ArcadeColors.INK_SOFT).apply {
                    setLineSpacing(dp(2f).toFloat(), 1f)
                },
                LinearLayout.LayoutParams(0, -2, 1f)
            )
            column.addView(row)
        }
        return column
    }

    private fun footer(): View {
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        column.addView(
            ArcadeUi.text(
                activity,
                "Progress stays on this phone. Levels unlock as you clear them; stars come from how well you win, not how long you play.",
                11f, 0xFF9C8F7D.toInt()
            ).apply { setLineSpacing(dp(2f).toFloat(), 1f) }
        )
        return column
    }

    private fun howToSheet() {
        ArcadeUi.dialog(activity, spec.title, "How to play · level $level", 0.55f) { body, dialog ->
            spec.howTo.forEach { (glyph, line) ->
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(6f), 0, dp(6f))
                }
                row.addView(ArcadeUi.text(activity, glyph, 16f, ArcadeColors.INK), LinearLayout.LayoutParams(dp(34f), -2))
                row.addView(
                    ArcadeUi.text(activity, line, 12.5f, ArcadeColors.INK_SOFT).apply {
                        setLineSpacing(dp(2f).toFloat(), 1f)
                    },
                    LinearLayout.LayoutParams(0, -2, 1f)
                )
                body.addView(row)
            }
            body.addView(
                ArcadeUi.button(activity, "Got it", ArcadeColors.INK, Color.WHITE) { dialog.dismiss() },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10f) }
            )
        }
    }

    private fun sectionTitle(value: String): TextView =
        ArcadeUi.text(activity, value.uppercase(), 10f, ArcadeColors.INK_SOFT, bold = true).apply {
            letterSpacing = 0.16f
            setPadding(dp(2f), dp(10f), 0f, dp(8f))
        }
}

/** The cabinet's poster, with its own title over its own art. */
class ArcadeHero(context: Context, private val spec: ArcadeSpec, private val progress: ArcadeProgress) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scene = RectF()
    private val density = resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        scene.set(0f, 0f, w, h)
        ArcadeDraw.art(canvas, spec.art, scene, spec.accent)
        paint.shader = LinearGradient(0f, h * 0.3f, 0f, h, intArrayOf(0x00000000, 0xCC000000.toInt()), null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(scene, 24f * density, 24f * density, paint)
        paint.shader = null
        spec.badge?.let { badge ->
            val size = 10f * density
            val width = ArcadeDraw.measure(badge, size, bold = true) + 22f * density
            paint.color = spec.badgeColor.takeIf { it != 0 } ?: ArcadeColors.CORAL
            canvas.drawRoundRect(16f * density, 16f * density, 16f * density + width, 37f * density, 9f * density, 9f * density, paint)
            ArcadeDraw.text(canvas, badge, 16f * density + width / 2f, 30f * density, size, Color.WHITE, bold = true, align = Paint.Align.CENTER)
        }
        ArcadeDraw.text(canvas, spec.title, 18f * density, h - 46f * density, 21f * density, Color.WHITE, bold = true)
        ArcadeDraw.text(
            canvas,
            "Level ${progress.unlocked(spec.id)} · ${spec.levels} levels · AI, two players, friends",
            18f * density,
            h - 25f * density,
            11.5f * density,
            Color.argb(225, 255, 255, 255)
        )
    }
}
