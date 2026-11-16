package com.oea.games

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.oea.engine.core.GameOption
import com.oea.engine.graphics.CharParts
import com.oea.engine.graphics.CharSpec
import com.oea.engine.math.Rng
import com.oea.games.ui.GameArt
import com.oea.games.ui.GameColors
import com.oea.games.ui.GameType

/**
 * Send Off's front screen: the shooter's own command deck.
 *
 * A shooter front end is not a settings page. It is the screen a player sits on
 * between matches — so it leads with the soldier (rank, XP to the next level,
 * career totals), then the kit they will drop in with, then the mode and the
 * island, and it finishes with a single amber DEPLOY bar. Everything is laid
 * out for a phone held sideways: career and loadout down the left, the war map
 * and the squad down the right.
 *
 * The settings offered are the shooter's own — look and ADS sensitivity, the
 * turn-speed cap, invert-Y, ADS tap-or-hold, the button layout reset, audio and
 * graphics. Nothing here pretends a chess clock is a scope.
 */
class ArenaFront(host: GameFrontHost) : GameFront(host) {

    /** Save keys written by Send Off itself (see OeaArena). */
    private val keySpec = "char_spec_v1"
    private val keyAdsHold = "ads_hold"

    override fun build(): View {
        val deck = kit.deck(padBottomDp = 12)
        val topBar = headerControls()
        val career = if (deck.wide) null else careerBlock()
        kit.heroBand(
            deck.top,
            heightDp = if (deck.wide) 82 else 124,
            title = "Send Off",
            eyebrow = "Tactical operations · ${rankName(level())}",
            subtitle = "Level ${level()} · ${xp()} XP · ${prefs.getInt("kills_total", 0)} eliminations",
            art = GameArt.hero("arena", scheme),
            leftInsetDp = 20,
            horizontal = deck.wide,
            footer = career,
            topBar = topBar
        )

        // ------------------------------------------------------------- left
        val left = deck.left
        section(left, "Soldier career")
        val careerCard = card(left)
        kit.statStrip(
            careerCard,
            listOf(
                level().toString() to "Level",
                pretty(prefs.getInt("matches", 0).toLong()) to "Matches",
                pretty(prefs.getInt("wins", 0).toLong()) to "Wins",
                pretty(prefs.getInt("kills_total", 0).toLong()) to "Elims"
            )
        )
        val next = xpForLevel(level() + 1)
        val current = xpForLevel(level())
        val inLevel = (xp() - current).coerceAtLeast(0)
        val span = (next - current).coerceAtLeast(1)
        careerCard.addView(
            kit.text(
                if (level() >= 60) "Highest rank reached" else "${(next - xp()).coerceAtLeast(0)} XP to level ${level() + 1}",
                12f,
                palette.textSecondary
            ).apply { setPadding(0, kit.dp(12), 0, kit.dp(6)) },
            LinearLayout.LayoutParams(-1, -2)
        )
        careerCard.addView(progressBar(inLevel.toFloat() / span, palette.signal, 8), LinearLayout.LayoutParams(-1, kit.dp(8)))

        section(left, "Loadout")
        val loadout = card(left)
        val spec = spec()
        val row = LinearLayout(kit.context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val figure = FrameLayout(kit.context).apply {
            background = kit.rounded(palette.well, 12, palette.stroke, 1)
            addView(loadoutFigure(spec), FrameLayout.LayoutParams(-1, -1))
        }
        row.addView(figure, LinearLayout.LayoutParams(0, kit.dp(96), 1.1f))
        val texts = LinearLayout(kit.context).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(kit.text(loadoutTitle(spec), 15f, palette.text, GameType.medium))
        texts.addView(
            kit.text(loadoutDetail(spec), 12f, palette.textSecondary).apply {
                setLineSpacing(kit.dpf(2f), 1f)
                setPadding(0, kit.dp(3), 0, 0)
            }
        )
        val actions = LinearLayout(kit.context).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(
            kit.tonalButton("Randomise") { randomise() },
            LinearLayout.LayoutParams(0, kit.dp(42), 1f).apply { rightMargin = kit.dp(8) }
        )
        actions.addView(
            kit.quietButton("Studio") { studioSheet() },
            LinearLayout.LayoutParams(0, kit.dp(42), 1f)
        )
        texts.addView(actions, LinearLayout.LayoutParams(-1, kit.dp(42)).apply { topMargin = kit.dp(10) })
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1.5f).apply { leftMargin = kit.dp(14) })
        loadout.addView(row, LinearLayout.LayoutParams(-1, -2))

        // ------------------------------------------------------------ right
        val right = deck.right
        section(right, "Game mode")
        val rules = option("rules")
        if (rules != null) {
            val index = rules.choices.indexOfFirst { it.value == value("rules") }.coerceAtLeast(0)
            kit.chipRow(right, rules.choices.map { it.label }, index, { pick -> set("rules", rules.choices[pick].value) }, palette.signal)
            right.addView(
                kit.text(rules.choices[index].description ?: "", 12f, palette.textSecondary).apply {
                    setPadding(kit.dp(2), kit.dp(8), kit.dp(2), kit.dp(4))
                    setLineSpacing(kit.dpf(2f), 1f)
                },
                LinearLayout.LayoutParams(-1, -2)
            )
        }
        section(right, "Island")
        val terrain = option("terrain")
        if (terrain != null) {
            val index = terrain.choices.indexOfFirst { it.value == value("terrain") }.coerceAtLeast(0)
            kit.chipRow(right, terrain.choices.map { it.label }, index, { pick -> set("terrain", terrain.choices[pick].value) })
        }

        section(right, "Match")
        val match = LinearLayout(kit.context).apply { orientation = LinearLayout.VERTICAL }
        right.addView(match, LinearLayout.LayoutParams(-1, -2))
        val bots = option("bots")
        if (bots != null) {
            val current = bots.choices.indexOfFirst { it.value == value("bots") }.coerceAtLeast(0)
            valueRow(match, "Opponents", bots.choices[current].label, "Bots to drop against") {
                pickChoice(bots, current)
            }
        }
        val difficulty = option("difficulty")
        if (difficulty != null) {
            val current = difficulty.choices.indexOfFirst { it.value == value("difficulty") }.coerceAtLeast(0)
            valueRow(match, "Bot skill", difficulty.choices[current].label, null) { pickChoice(difficulty, current) }
        }
        val loadoutPool = option("loadout")
        if (loadoutPool != null) {
            val current = loadoutPool.choices.indexOfFirst { it.value == value("loadout") }.coerceAtLeast(0)
            valueRow(match, "Weapon pool", loadoutPool.choices[current].label, null) { pickChoice(loadoutPool, current) }
        }
        val zone = option("zone")
        if (zone != null) {
            val current = zone.choices.indexOfFirst { it.value == value("zone") }.coerceAtLeast(0)
            valueRow(match, "Zone speed", zone.choices[current].label, null) { pickChoice(zone, current) }
        }

        section(right, "Squad")
        val squad = LinearLayout(kit.context).apply { orientation = LinearLayout.VERTICAL }
        right.addView(squad, LinearLayout.LayoutParams(-1, -2))
        valueRow(squad, "Wi-Fi / hotspot lobby", "Host or join", "Same router, or your phone's hotspot · up to 12", true) {
            host.frontFriends()
        }
        valueRow(squad, "Online with a code", "Direct over WebRTC", "Send an invite, they reply with theirs") {
            host.frontFriends()
        }

        section(right, "Shooter settings")
        val tuning = LinearLayout(kit.context).apply { orientation = LinearLayout.VERTICAL }
        right.addView(tuning, LinearLayout.LayoutParams(-1, -2))
        valueRow(tuning, "Screen orientation", orientationLabel(host.frontOrientationMode()), "Play upright, widescreen, or follow phone rotation", true) {
            orientationSheet()
        }
        valueRow(tuning, "Look & aim", "${sensitivity()} · ADS ${(settings.adsSensitivity * 100).toInt()}%", "Sensitivity, turn limit, invert-Y") {
            aimSheet()
        }
        valueRow(tuning, "Aim button", if (prefs.getInt(keyAdsHold, 0) == 1) "Hold to aim" else "Tap to toggle", "How ADS behaves on the button") {
            val hold = prefs.getInt(keyAdsHold, 0) == 1
            prefs.edit().putInt(keyAdsHold, if (hold) 0 else 1).apply()
            host.frontRebuild()
        }
        valueRow(
            tuning,
            "Buttons",
            controlKeys.count { prefs.contains("btn_${it}_x") }.let { if (it == 0) "Factory layout" else "$it moved" },
            "Drag buttons in-match; reset here"
        ) { layoutSheet() }
        valueRow(tuning, "Sound", if (settings.sound) "On" else "Off", "Effects, music, haptics") { audioSheet("Send Off audio") }
        valueRow(tuning, "Graphics", qualityLabel(), "Quality and frame rate") { graphicsSheet("Send Off graphics") }

        // ------------------------------------------------------------- bar
        deck.bar.addView(
            kit.quietButton("Controls") { host.frontControls() },
            LinearLayout.LayoutParams(0, kit.dp(50), 0.8f).apply { rightMargin = kit.dp(10) }
        )
        deck.bar.addView(
            kit.tonalButton("Squad up") { host.frontFriends() },
            LinearLayout.LayoutParams(0, kit.dp(50), 0.9f).apply { rightMargin = kit.dp(10) }
        )
        deployButton()?.let { deploy ->
            deck.bar.addView(deploy, LinearLayout.LayoutParams(0, kit.dp(50), 1.7f))
        }
        return deck.root
    }

    // ---------------------------------------------------------------- career

    /** The thin line of career progress under the hero band. */
    private fun careerBlock(): View {
        val line = LinearLayout(kit.context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        line.addView(
            kit.text(
                "${prefs.getInt("matches", 0)} matches · ${prefs.getInt("wins", 0)} wins · ${prefs.getInt("kills_total", 0)} eliminations",
                12f,
                palette.textSecondary,
                GameType.medium
            ),
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        line.addView(
            kit.text(if (isDeployed()) "Squad ready" else "Ready to deploy", 11f, palette.signal, GameType.medium).apply {
                letterSpacing = 0.1f
                setPadding(kit.dp(10), kit.dp(4), kit.dp(10), kit.dp(4))
                background = kit.rounded(GameColors.alpha(palette.signal, 0x22), 10, GameColors.alpha(palette.signal, 0x66), 1)
            },
            LinearLayout.LayoutParams(-2, -2)
        )
        return line
    }

    private fun isDeployed(): Boolean = prefs.getInt("matches", 0) > 0

    private fun deployButton(): View? {
        val ranks = mapOf(
            "br" to "Battle Royale", "br_res" to "Resurgence", "tdm" to "Team Deathmatch",
            "cs" to "Clash Squad", "wolf" to "Lone Wolf", "warfare" to "Warfare",
            "warfare_koth" to "King of the Hill", "warfare_ad" to "Attack & Defend",
            "gun" to "Gun Game", "last_day" to "Last Day", "lights_out" to "Lights Out",
            "haunted" to "Haunted"
        )
        val mode = ranks[value("rules")] ?: "Battle Royale"
        val button = kit.button("DEPLOY · ${mode.uppercase()}", palette.signal, palette.onAccent) {
            host.frontPlay(com.oea.engine.core.PlayMode.SOLO)
        }
        button.textSize = 14.5f
        button.letterSpacing = 0.05f
        button.maxLines = 1
        button.ellipsize = android.text.TextUtils.TruncateAt.END
        return button
    }

    private fun orientationLabel(mode: String): String = when (mode) {
        "portrait" -> "Portrait"
        "landscape" -> "Landscape"
        else -> "Auto"
    }

    private fun headerControls(): View {
        val bar = LinearLayout(kit.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = kit.text("← Exit", 12.5f, palette.text, GameType.medium).apply {
            setPadding(kit.dp(12), kit.dp(6), kit.dp(12), kit.dp(6))
            background = kit.ripple(kit.rounded(GameColors.alpha(palette.card, 0xD8), 14, palette.stroke, 1), 14)
            isClickable = true
            isFocusable = true
            contentDescription = "Exit Send Off"
            setOnClickListener { host.frontExit() }
        }
        bar.addView(back, LinearLayout.LayoutParams(-2, -2))
        bar.addView(View(kit.context), LinearLayout.LayoutParams(0, 1, 1f))
        val mode = host.frontOrientationMode()
        val rotate = kit.text("⟳ ${orientationLabel(mode)}", 12f, palette.accent, GameType.medium).apply {
            setPadding(kit.dp(12), kit.dp(6), kit.dp(12), kit.dp(6))
            background = kit.ripple(kit.rounded(GameColors.alpha(palette.card, 0xD8), 14, GameColors.alpha(palette.accent, 0x66), 1), 14)
            isClickable = true
            isFocusable = true
            contentDescription = "Screen orientation: ${orientationLabel(mode)}"
            setOnClickListener { orientationSheet() }
        }
        bar.addView(rotate, LinearLayout.LayoutParams(-2, -2))
        return bar
    }

    private fun orientationSheet() {
        val modes = listOf("auto", "landscape", "portrait")
        val labels = listOf("Auto (follow phone)", "Landscape (widescreen)", "Portrait (upright)")
        val details = listOf(
            "Stays upright when you hold the phone upright, and rotates sideways when auto-rotate is on.",
            "Widescreen two-column command deck and full horizontal shooter HUD.",
            "Upright one-hand/two-thumb portrait command deck and adapted portrait HUD."
        )
        val current = modes.indexOf(host.frontOrientationMode()).coerceAtLeast(0)
        kit.choose("Screen orientation", "Choose how Send Off opens and plays", labels, current, details) { pick ->
            val next = modes[pick]
            host.frontSetOrientationMode(next)
            host.frontToast("Screen orientation: ${orientationLabel(next)}")
        }
    }

    // ---------------------------------------------------------------- loadout

    /** A small soldier silhouette built from the saved character's own colours. */
    private fun loadoutFigure(spec: CharSpec): View {
        val skin = CharParts.SKIN_TONES.getOrElse(spec.skin.tone) { 0xFFE0B48C.toInt() }
        val top = spec.outfit.top.color
        val bottom = spec.outfit.bottom.color
        return object : View(kit.context) {
            private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

            override fun onDraw(canvas: android.graphics.Canvas) {
                val w = width.toFloat()
                val h = height.toFloat()
                val cx = w / 2f
                val unit = h / 10f
                // Head, torso, legs and a rifle — the silhouette a shooter reads.
                paint.color = skin
                canvas.drawCircle(cx, unit * 1.9f, unit * 0.95f, paint)
                paint.color = top
                canvas.drawRoundRect(cx - unit * 1.4f, unit * 2.9f, cx + unit * 1.4f, unit * 6.2f, unit * 0.5f, unit * 0.5f, paint)
                paint.color = bottom
                canvas.drawRect(cx - unit * 1.2f, unit * 6.2f, cx - unit * 0.15f, unit * 9.2f, paint)
                canvas.drawRect(cx + unit * 0.15f, unit * 6.2f, cx + unit * 1.2f, unit * 9.2f, paint)
                paint.color = GameColors.blend(top, Color.BLACK, 0.35f)
                canvas.drawRect(cx - unit * 1.4f, unit * 5.6f, cx + unit * 1.4f, unit * 6.2f, paint)
                // Rifle across the chest.
                paint.color = GameColors.blend(palette.text, Color.BLACK, 0.55f)
                canvas.drawRect(cx - unit * 1.8f, unit * 4.4f, cx + unit * 1.9f, unit * 4.9f, paint)
                canvas.drawRect(cx + unit * 0.4f, unit * 3.9f, cx + unit * 1.0f, unit * 4.5f, paint)
                // Helmet glint.
                paint.color = GameColors.alpha(palette.signal, 0xC0)
                canvas.drawRect(cx - unit * 0.9f, unit * 1.15f, cx + unit * 0.9f, unit * 1.35f, paint)
            }
        }
    }

    private fun loadoutTitle(spec: CharSpec): String {
        val top = CharParts.TOPS.firstOrNull { it.id == spec.outfit.top.partId }?.label ?: "T-shirt"
        val outer = CharParts.OUTERWEAR.firstOrNull { it.id == spec.outfit.outer.partId }?.label
        return if (outer.isNullOrBlank() || spec.outfit.outer.partId == "none") top else "$outer over $top"
    }

    private fun loadoutDetail(spec: CharSpec): String {
        val head = CharParts.HEADWEAR.firstOrNull { it.id == spec.outfit.headwear.partId }?.label ?: "Bare head"
        val back = CharParts.BACK.firstOrNull { it.id == spec.outfit.back.partId }?.label ?: "No pack"
        val build = when (spec.gender.name) {
            "FEMININE" -> "Feminine build"
            "NEUTRAL" -> "Neutral build"
            else -> "Masculine build"
        }
        return "$head · $back\n$build · ${spec.hair.style.let { CharParts.HAIR_STYLES.getOrNull(it)?.label ?: "Short" }}"
    }

    private fun spec(): CharSpec = CharSpec.fromJson(prefs.getString(keySpec, null)) ?: CharSpec()

    private fun saveSpec(spec: CharSpec) {
        runCatching { prefs.edit().putString(keySpec, spec.toJson().toString()).apply() }
    }

    private fun randomise() {
        val spec = CharSpec()
        spec.gender = com.oea.engine.graphics.CharGender.entries[Rng().nextInt(com.oea.engine.graphics.CharGender.entries.size)]
        CharParts.randomize(spec, Rng())
        saveSpec(spec)
        host.frontToast("New operator rolled")
        host.frontRebuild()
    }

    /** Outfit presets, the three silhouettes a shooter actually ships with. */
    private fun studioSheet() {
        kit.sheet("Character", "Full character editing opens in a match: Pause › Character studio, on the live model.") { panel, dialog ->
            val body = LinearLayout(kit.context).apply { orientation = LinearLayout.VERTICAL }
            val presets = listOf(
                Triple("Operator", "Tactical shirt, cargo, helmet", 0),
                Triple("Scout", "Tank top, joggers, cap", 1),
                Triple("Heavy", "Hoodie under, tactical pants, beanie", 2)
            )
            presets.forEach { (name, detail, which) ->
                kit.valueRow(body, name, "Wear", detail) {
                    applyPreset(which)
                    dialog.dismiss()
                    host.frontRebuild()
                }
            }
            kit.valueRow(body, "Randomise everything", "Roll", "Build, face, hair and clothing") {
                randomise()
                dialog.dismiss()
            }
            kit.note(body, "Your character is stored on this phone and comes back in every match, on every mode.")
            panel.addView(kit.boundedScroll(body, 0.66f))
            panel.addView(kit.buttonRow(kit.quietButton("Close") { dialog.dismiss() }))
        }
    }

    private fun applyPreset(which: Int) {
        val spec = CharSpec()
        val top = when (which) {
            0 -> "tactical"; 1 -> "tank"; else -> "hoodie_t"
        }
        val bottom = when (which) {
            0 -> "tactical_p"; 1 -> "joggers"; else -> "tactical_p"
        }
        val head = when (which) {
            0 -> "helmet"; 1 -> "cap"; else -> "beanie"
        }
        CharParts.TOPS.firstOrNull { it.id == top }?.let { spec.outfit.top.partId = it.id; spec.outfit.top.textureKey = it.textureKey }
        CharParts.BOTTOMS.firstOrNull { it.id == bottom }?.let { spec.outfit.bottom.partId = it.id; spec.outfit.bottom.textureKey = it.textureKey }
        CharParts.HEADWEAR.firstOrNull { it.id == head }?.let { spec.outfit.headwear.partId = it.id; spec.outfit.headwear.textureKey = it.textureKey }
        if (which == 2) {
            CharParts.OUTERWEAR.firstOrNull { it.id == "vest" }?.let { spec.outfit.outer.partId = it.id; spec.outfit.outer.textureKey = it.textureKey }
        }
        if (which == 0) {
            CharParts.BACK.firstOrNull { it.id == "daypack" }?.let { spec.outfit.back.partId = it.id; spec.outfit.back.textureKey = it.textureKey }
        }
        saveSpec(spec)
        host.frontToast("Outfit set")
    }

    // --------------------------------------------------------------- settings

    private fun sensitivity(): String = "%.1f×".format(settings.lookSensitivity)

    private fun aimSheet() {
        kit.sheet("Look & aim", "Send Off's own camera, tuned live — the match applies it next frame.") { panel, dialog ->
            val body = LinearLayout(kit.context).apply { orientation = LinearLayout.VERTICAL }
            kit.slider(body, "Look sensitivity", settings.lookSensitivity, 0.2f, 3f, { "%.1f×".format(it) }) { settings.lookSensitivity = it }
            kit.slider(body, "ADS sensitivity", settings.adsSensitivity, 0.3f, 1.5f, { "${(it * 100).toInt()}%" }) { settings.adsSensitivity = it }
            kit.slider(body, "Turn speed limit", settings.turnSpeedLimit, 0f, 360f, { if (it <= 0.5f) "Off" else "${it.toInt()}°/s" }) { settings.turnSpeedLimit = it }
            kit.switchRow(body, "Invert vertical look", "Drag up to look down", settings.invertLookY) { settings.invertLookY = it }
            kit.switchRow(body, "Hold to aim", "Off: tap ADS to toggle. On: hold it", prefs.getInt(keyAdsHold, 0) == 1) { hold ->
                prefs.edit().putInt(keyAdsHold, if (hold) 1 else 0).apply()
            }
            kit.note(body, "The turn limit is what keeps a fast drag from spinning the camera past a full circle — it is the reason aiming here does not glitch on a cheap digitiser.")
            panel.addView(kit.boundedScroll(body, 0.66f))
            panel.addView(kit.buttonRow(kit.primaryButton("Done") { dialog.dismiss(); host.frontRebuild() }))
        }
    }

    /** The layout reset: touch layout lives in the game's own save file. */
    private fun layoutSheet() {
        kit.sheet("Buttons", "Drag any button in a match to move it. These reset the layout.") { panel, dialog ->
            val body = LinearLayout(kit.context).apply { orientation = LinearLayout.VERTICAL }
            val moved = controlKeys.count { prefs.contains("btn_${it}_x") || prefs.contains("btn_p_${it}_x") }
            kit.valueRow(
                body,
                "Customised buttons",
                if (moved == 0) "Factory" else "$moved of ${controlKeys.size}",
                "Position and size, remembered for Send Off alone"
            ) {
                val edit = prefs.edit()
                controlKeys.forEach { key ->
                    edit.remove("btn_${key}_x")
                    edit.remove("btn_${key}_y")
                    edit.remove("btn_${key}_r")
                    edit.remove("btn_p_${key}_x")
                    edit.remove("btn_p_${key}_y")
                    edit.remove("btn_p_${key}_r")
                }
                edit.apply()
                dialog.dismiss()
                host.frontToast("Controls are back to the factory layout")
                host.frontRebuild()
            }
            kit.valueRow(body, "Aim button", if (prefs.getInt(keyAdsHold, 0) == 1) "Hold to aim" else "Tap to toggle", "Back to tap-to-aim") {
                prefs.edit().putInt(keyAdsHold, 0).apply()
                dialog.dismiss()
                host.frontRebuild()
            }
            kit.note(body, "The match reads these on its next start, so a layout you like survives every mode and every island.")
            panel.addView(body)
            panel.addView(kit.buttonRow(kit.quietButton("Close") { dialog.dismiss() }))
        }
    }

    /** Every control the match can have its layout saved for. */
    private val controlKeys = listOf(
        "fire", "fire2", "ads", "jump", "crouch", "reload", "heal", "pickup",
        "stun", "frag", "smoke", "mount", "sprint", "prone"
    )

    private fun qualityLabel(): String = when (settings.quality) {
        "low" -> "Low"
        "medium" -> "Medium"
        "high" -> "High"
        "ultra" -> "Ultra"
        else -> "Auto"
    }

    // ---------------------------------------------------------------- pieces

    private fun section(parent: LinearLayout, title: String) {
        parent.addView(
            kit.text(title.uppercase(), 10f, palette.textTertiary, GameType.medium).apply {
                letterSpacing = 0.18f
                setPadding(kit.dp(4), kit.dp(14), kit.dp(4), kit.dp(7))
            },
            LinearLayout.LayoutParams(-1, -2)
        )
    }

    private fun card(parent: LinearLayout): LinearLayout {
        val card = LinearLayout(kit.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(kit.dp(12), kit.dp(12), kit.dp(12), kit.dp(12))
            background = kit.rounded(palette.card, 14, palette.stroke, 1)
        }
        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(4) })
        return card
    }

    private fun valueRow(parent: LinearLayout, title: String, value: String, subtitle: String?, accentValue: Boolean = false, onClick: () -> Unit) {
        kit.valueRow(parent, title, value, subtitle, accentValue, onClick)
    }

    private fun pickChoice(opt: GameOption, current: Int) {
        kit.choose(opt.label, opt.choices.getOrNull(current)?.description, opt.choices.map { it.label }, current, opt.choices.map { it.description }) { pick ->
            set(opt.id, opt.choices[pick].value)
        }
    }

    // -------------------------------------------------------------- progress

    private fun xp(): Int = prefs.getInt("xp", 0)

    private fun level(): Int {
        val stored = prefs.getInt("level", 0)
        val computed = 1 + kotlin.math.sqrt(xp().coerceAtLeast(0) / 400f).toInt()
        return maxOf(stored, computed, 1)
    }

    private fun xpForLevel(level: Int): Int = 400 * (level - 1) * (level - 1)

    private fun rankName(level: Int): String = when {
        level >= 55 -> "Marshal"
        level >= 45 -> "Commander"
        level >= 36 -> "Captain"
        level >= 28 -> "Lieutenant"
        level >= 21 -> "Sergeant"
        level >= 15 -> "Corporal"
        level >= 9 -> "Lance corporal"
        level >= 5 -> "Private"
        else -> "Recruit"
    }
}
