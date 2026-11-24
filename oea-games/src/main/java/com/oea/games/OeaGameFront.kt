package com.oea.games

import android.content.SharedPreferences
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.oea.engine.OeaEngine
import com.oea.engine.core.GameDescriptor
import com.oea.engine.core.GameOption
import com.oea.engine.core.LocalPlayer
import com.oea.engine.core.PlayMode
import com.oea.games.ui.GameKit
import com.oea.games.ui.GamePalette
import com.oea.games.ui.GameScheme
import com.oea.games.ui.GameType

/**
 * What a game's front screen is allowed to ask of the activity hosting it.
 *
 * The three OEA games are not three skins of one menu. Send Off is a shooter
 * bent around a soldier's career and a deploy button; Chess is a club room with
 * a board on the table; Endless Days is an arcade cabinet with a score marquee.
 * Each therefore builds its own screen, and this is the whole surface it needs
 * from the shell: its colours, its remembered options, a way to start a match,
 * the shared sheets, and the game's own save data.
 */
interface GameFrontHost {
    /** The game this front belongs to. */
    val frontDescriptor: GameDescriptor

    /** The game's own materials, from its launcher icon. */
    val frontScheme: GameScheme

    fun frontKit(): GameKit

    /** `SharedPreferences("oea_game_<id>")` — the same file the match reads. */
    fun gamePrefs(): SharedPreferences

    /** Remembered value of one of the game's own options. */
    fun frontOption(id: String): String

    /** Stores an option and remembers it for the next match. */
    fun frontSetOption(id: String, value: String)

    /** Starts a match now, using the options as they stand. */
    fun frontPlay(mode: PlayMode, players: List<LocalPlayer> = emptyList())

    /** Wi-Fi / hotspot / online hosting and joining. */
    fun frontFriends()

    /** The game's own control legend. */
    fun frontControls()

    /** Screen size, contrast and motion. */
    fun frontAccess()

    fun frontToast(message: String)

    /** Rebuilds this screen after a setting or a record changed. */
    fun frontRebuild()

    /** Leaves the standalone game screen cleanly. */
    fun frontExit() {}

    /** Current saved orientation mode: `"auto"`, `"portrait"`, or `"landscape"`. */
    fun frontOrientationMode(): String = "auto"

    /** Persists and immediately applies `"auto"`, `"portrait"`, or `"landscape"`. */
    fun frontSetOrientationMode(mode: String) {}
}

/**
 * Shared machinery for the game front screens: the kit, the palette, stat
 * tiles, colour pickers and the sheets every game needs (sound, graphics,
 * about) — but never the layout. Layout belongs to the game.
 */
abstract class GameFront(protected val host: GameFrontHost) {

    protected val d: GameDescriptor get() = host.frontDescriptor
    protected val kit: GameKit get() = host.frontKit()
    protected val palette: GamePalette get() = kit.palette
    protected val scheme: GameScheme get() = host.frontScheme
    protected val prefs: SharedPreferences get() = host.gamePrefs()

    /** The game's engine settings (audio, graphics, aim, access). */
    protected val settings get() = OeaEngine.settings

    /** Builds the whole front screen. Called again after any change. */
    abstract fun build(): View

    // ------------------------------------------------------------- options

    protected fun option(id: String): GameOption? = d.options.firstOrNull { it.id == id }

    protected fun value(id: String): String = host.frontOption(id)

    protected fun label(id: String): String {
        val opt = option(id) ?: return value(id)
        return opt.choices.firstOrNull { it.value == value(id) }?.label ?: value(id)
    }

    protected fun choose(id: String) {
        val opt = option(id) ?: return
        val current = opt.choices.indexOfFirst { it.value == value(id) }.coerceAtLeast(0)
        kit.choose(opt.label, opt.choices.getOrNull(current)?.description, opt.choices.map { it.label }, current, opt.choices.map { it.description }) { pick ->
            host.frontSetOption(opt.id, opt.choices[pick].value)
            host.frontRebuild()
        }
    }

    /** Sets an option and rebuilds the screen in one step. */
    protected fun set(id: String, newValue: String) {
        host.frontSetOption(id, newValue)
        host.frontRebuild()
    }

    // ------------------------------------------------------------- widgets

    /** A thin progress bar for XP or a record. */
    protected fun progressBar(fraction: Float, color: Int = palette.accent, heightDp: Int = 6): FrameLayout {
        val f = fraction.coerceIn(0f, 1f)
        val track = FrameLayout(kit.context).apply {
            background = kit.rounded(palette.cardHigh, heightDp)
            clipToOutline = true
        }
        val fill = View(kit.context).apply { background = kit.rounded(color, heightDp) }
        track.addView(
            fill,
            FrameLayout.LayoutParams(0, kit.dp(heightDp)).apply { gravity = Gravity.START }
        )
        // Sized from the measured track, not from a one-shot post{}: a screen
        // built before its first layout has a width of 0, which is how a
        // progress bar ends up permanently empty.
        track.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                val width = (v.width * f).toInt()
                val params = fill.layoutParams as FrameLayout.LayoutParams
                if (params.width != width) {
                    params.width = width
                    fill.layoutParams = params
                }
            }
        })
        return track
    }

    /** A caption line that matches the game's own letterspacing. */
    protected fun caption(text: String, color: Int = palette.textTertiary): TextView =
        kit.text(text.uppercase(), 10f, color, GameType.medium).apply { letterSpacing = 0.16f }

    /** A row of colour swatches; the current one is ringed in the accent. */
    protected fun colourRow(
        parent: LinearLayout,
        colors: IntArray,
        selected: Int,
        onPick: (Int) -> Unit
    ) {
        val row = LinearLayout(kit.context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        colors.forEach { c ->
            val dot = View(kit.context).apply {
                background = kit.oval(c, if (c == selected) palette.accent else 0, if (c == selected) 3 else 0)
                isClickable = true
                contentDescription = "Colour"
                setOnClickListener { onPick(c) }
            }
            row.addView(dot, LinearLayout.LayoutParams(kit.dp(30), kit.dp(30)).apply { rightMargin = kit.dp(8) })
        }
        parent.addView(kit.hscrollRow(row), LinearLayout.LayoutParams(-1, -2))
    }

    /** A compact numeric stepper used for counts (bots, best-of, tier). */
    protected fun stepperRow(parent: LinearLayout, title: String, value: Int, min: Int, max: Int, format: (Int) -> String, onChange: (Int) -> Unit) {
        val row = LinearLayout(kit.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(kit.dp(14), kit.dp(10), kit.dp(10), kit.dp(10))
            background = kit.rounded(palette.card, 12, palette.stroke, 1)
        }
        row.addView(kit.text(title, 14.5f, palette.text, GameType.medium), LinearLayout.LayoutParams(0, -2, 1f))
        fun bump(delta: Int) {
            val next = (value + delta).coerceIn(min, max)
            if (next != value) onChange(next)
        }
        row.addView(
            kit.text("−", 20f, palette.textSecondary, GameType.bold).apply {
                gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener { bump(-1) }
            },
            LinearLayout.LayoutParams(kit.dp(38), kit.dp(36))
        )
        row.addView(
            kit.text(format(value), 14f, palette.accent, GameType.mono).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(kit.dp(58), -2)
        )
        row.addView(
            kit.text("+", 20f, palette.textSecondary, GameType.bold).apply {
                gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener { bump(1) }
            },
            LinearLayout.LayoutParams(kit.dp(38), kit.dp(36))
        )
        parent.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(6) })
    }

    // ---------------------------------------------------------------- sheets

    /** Which channels are up and how loud, in one line, for a front screen's Sound row. */
    protected fun audioSummary(): String {
        if (!settings.sound) return "Muted"
        val on = listOfNotNull(
            if (settings.sfx) "effects" else null,
            if (settings.music) "music" else null,
            if (settings.voice) "announcer" else null,
            if (settings.haptics) "haptics" else null,
        )
        return "${(settings.volume * 100).toInt()}% · " + (if (on.isEmpty()) "all off" else on.joinToString(", "))
    }

    /**
     * Every audio channel, one page — shared by every OEA game on this phone.
     *
     * A single "Sound on/off" is not a sound setting. A player wants the music
     * quiet and the effects loud, the announcer off and the haptics hard, and
     * those are six independent numbers. Each channel has its own switch and its
     * own level under one master, and the mix survives the master being muted.
     */
    protected fun audioSheet(title: String = "Sound") {
        kit.sheet(title, "Shared with every OEA game on this phone, and applied live.") { panel, dialog ->
            val body = LinearLayout(kit.context).apply { orientation = LinearLayout.VERTICAL }
            kit.sectionTitle(body, "Master")
            kit.switchRow(body, "Sound", "The master switch — off silences everything, mix kept", settings.sound) { settings.sound = it }
            kit.slider(body, "Master volume", settings.volume, 0f, 1f, { "${(it * 100f).toInt()}%" }) { settings.volume = it }

            kit.sectionTitle(body, "Channels")
            kit.switchRow(body, "Sound effects", "Weapons, steps, coins — the game's own noises", settings.sfx) { settings.sfx = it }
            kit.slider(body, "Effects level", settings.sfxVolume, 0f, 1f, { "${(it * 100f).toInt()}%" }) { settings.sfxVolume = it }
            kit.switchRow(body, "Music", "The game's own soundtrack", settings.music) { settings.music = it }
            kit.slider(body, "Music level", settings.musicVolume, 0f, 1f, { "${(it * 100f).toInt()}%" }) { settings.musicVolume = it }
            kit.switchRow(body, "Announcer", "Round calls, callouts, match start", settings.voice) { settings.voice = it }
            kit.slider(body, "Announcer level", settings.voiceVolume, 0f, 1f, { "${(it * 100f).toInt()}%" }) { settings.voiceVolume = it }
            kit.switchRow(body, "Duck music for the announcer", "The soundtrack steps back under a callout", settings.duckMusicForVoice) {
                settings.duckMusicForVoice = it
            }

            kit.sectionTitle(body, "Touch")
            kit.switchRow(body, "Haptics", "A tap on the phone for hits, jumps and moves", settings.haptics) { settings.haptics = it }
            kit.slider(body, "Haptic strength", settings.hapticStrength, 0.25f, 1f, { "${(it * 100f).toInt()}%" }) {
                settings.hapticStrength = it
            }

            kit.note(body, "Each channel is a fraction of the master, so muting with the master switch never loses the mix you set.")
            panel.addView(kit.boundedScroll(body, 0.72f))
            panel.addView(kit.buttonRow(
                kit.quietButton("Reset mix") {
                    settings.volume = 0.9f
                    settings.sfxVolume = 1f
                    settings.musicVolume = 0.45f
                    settings.voiceVolume = 0.85f
                    settings.hapticStrength = 1f
                    dialog.dismiss()
                    host.frontRebuild()
                },
                kit.primaryButton("Done") { dialog.dismiss(); host.frontRebuild() }
            ))
        }
    }

    /** Quality and frame rate for this game on this phone. */
    protected fun graphicsSheet(title: String = "Graphics") {
        val device = OeaEngine.device
        kit.sheet(title, "${device.recommendedTier.label} tier on this phone · ${device.maxRefreshRate.toInt()} Hz") { panel, dialog ->
            val body = LinearLayout(kit.context).apply { orientation = LinearLayout.VERTICAL }
            val qualities = listOf("auto", "low", "medium", "high", "ultra")
            val labels = listOf("Auto (${device.recommendedTier.label})", "Low", "Medium", "High", "Ultra")
            val index = qualities.indexOf(settings.quality).coerceAtLeast(0)
            kit.valueRow(body, "Quality", labels[index], "Auto measures this phone once and stays there") {
                kit.choose("Quality", null, labels, index, listOf("Measured for this phone", "Fastest, longest battery", "Balanced", "Shadows and bloom", "Supersampling — maximum image quality")) { pick ->
                    settings.quality = qualities[pick]
                    host.frontRebuild()
                }
            }
            val rates = (listOf(0) + device.refreshRates.map { it.toInt() }.filter { it >= 30 }.distinct().sortedDescending() + listOf(60, 30)).distinct()
            val rateLabels = rates.map { if (it == 0) "Max (${device.maxRefreshRate.toInt()} Hz)" else "$it fps" }
            val rateIndex = rates.indexOf(settings.fpsTarget).coerceAtLeast(0)
            kit.valueRow(body, "Frame rate", rateLabels[rateIndex], "What the screen should hold") {
                kit.choose("Frame rate", null, rateLabels, rateIndex) { pick ->
                    settings.fpsTarget = rates[pick]
                    host.frontRebuild()
                }
            }
            kit.switchRow(body, "Adaptive resolution", "Trades sharpness for a steady frame rate", settings.adaptiveResolution) { settings.adaptiveResolution = it }
            kit.sectionTitle(body, "Picture")
            kit.slider(body, "Colour grade", settings.colourGrade, 0f, 1.5f, {
                when {
                    it < 0.02f -> "Off (raw render)"
                    it < 0.6f -> "Subtle · ${(it * 100).toInt()}%"
                    it < 1.05f -> "Filmic · ${(it * 100).toInt()}%"
                    else -> "Punchy · ${(it * 100).toInt()}%"
                }
            }) { settings.colourGrade = it }
            kit.switchRow(body, "Vignette", "Darkened corners — off for a flat, clinical image", settings.vignette) { settings.vignette = it }
            kit.note(body, "Each world carries its own grade — night is cooler and softer, noon is punchier, a haunted night is nearly monochrome. The slider scales that look; it does not replace it, so turning it down keeps the time of day readable.")
            kit.sectionTitle(body, "On-screen")
            kit.slider(body, "HUD opacity", settings.hudOpacity, 0.4f, 1f, { "${(it * 100f).toInt()}%" }) { settings.hudOpacity = it }
            kit.slider(body, "Button opacity", settings.buttonOpacity, 0.35f, 1f, { "${(it * 100f).toInt()}%" }) { settings.buttonOpacity = it }
            kit.switchRow(body, "FPS counter", "Frame rate and resolution scale, on screen", settings.showFps) { settings.showFps = it }
            kit.switchRow(body, "Steady performance", "Cooler and steadier, lower peaks", settings.sustainedPerformance) { settings.sustainedPerformance = it }
            panel.addView(kit.boundedScroll(body, 0.66f))
            panel.addView(kit.buttonRow(kit.primaryButton("Done") { dialog.dismiss(); host.frontRebuild() }))
        }
    }

    protected fun aboutSheet() {
        kit.sheet(d.title, d.tagline) { panel, dialog ->
            panel.addView(
                kit.text(d.description, 13.5f, palette.textSecondary).apply {
                    setLineSpacing(kit.dpf(2f), 1f)
                    setPadding(0, 0, 0, kit.dp(12))
                }
            )
            panel.addView(
                kit.text(
                    "Players: ${if (d.maxPlayers > 1) "${d.minPlayers}–${d.maxPlayers}" else "1"}\n" +
                        "Modes: ${d.modes.joinToString(", ") { it.label }}\n" +
                        "Engine: OEA Engine ${OeaEngine.VERSION}",
                    12.5f,
                    palette.textTertiary
                ).apply { setLineSpacing(kit.dpf(3f), 1f) }
            )
            panel.addView(kit.buttonRow(kit.primaryButton("Close") { dialog.dismiss() }))
        }
    }

    // -------------------------------------------------------------- records

    /** Reads a saved high score, tolerating both key spellings the engine uses. */
    protected fun savedBest(key: String): Long {
        val has = prefs.contains("best_$key")
        return if (has) prefs.getLong("best_$key", 0L) else prefs.getLong(key, 0L)
    }

    /** A number that reads well on a marquee. */
    protected fun pretty(value: Long): String = when {
        value >= 1_000_000 -> "%.1fM".format(value / 1_000_000f)
        value >= 10_000 -> "%.1fK".format(value / 1000f)
        else -> value.toString()
    }

    protected fun trim(text: String, max: Int): String = if (text.length <= max) text else text.take(max - 1).trimEnd() + "…"

}
