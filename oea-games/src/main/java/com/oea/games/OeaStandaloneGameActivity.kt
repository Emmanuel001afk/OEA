package com.oea.games

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.oea.engine.OeaEngine
import com.oea.engine.core.GameDescriptor
import com.oea.engine.core.GameHost
import com.oea.engine.core.GameLaunch
import com.oea.engine.core.GameResult
import com.oea.engine.core.HostUi
import com.oea.engine.core.LocalPlayer
import com.oea.engine.core.MenuItem
import com.oea.engine.core.PlayMode
import com.oea.engine.net.lan.LanBrowser
import com.oea.engine.net.lan.LanGame
import com.oea.engine.net.lan.LanJoinCode
import com.oea.engine.net.session.NetPlayer
import com.oea.engine.net.session.NetSession
import com.oea.engine.net.session.OnlineInvite
import com.oea.engine.net.session.SessionListener
import com.oea.engine.net.session.SessionPhase
import com.oea.games.ui.GameColors
import com.oea.games.ui.GameCoverDrawable
import com.oea.games.ui.GameInsets
import com.oea.games.ui.GameKit
import com.oea.games.ui.GamePalette
import com.oea.games.ui.GameType

/**
 * The heart of every standalone OEA game app. Each game subclasses this once
 * with a fixed game id; the app is then complete on its own:
 *
 *  - a real front screen: cover art, title, the match summary and the actions
 *    (Play, Match setup, Play with friends, Controls & aim, About) as OEA rows;
 *  - match setup over the game's own options, remembered per game;
 *  - look / ADS sensitivity, invert-Y and a turn-speed limit, tuned live — offered
 *    only to games you look around inside, with each game's own gesture legend for
 *    the rest;
 *  - its own multiplayer: host on Wi-Fi or with an online code, join nearby
 *    games or with a code — no launcher, no hub required;
 *  - direct launch with extras (from the OEA Games hub or shortcuts).
 *
 * Nothing here depends on `com.oea.launcher`. The chrome comes from
 * [com.oea.games.ui.GameKit], the games' own copy of the launcher's visual
 * language, so a separately installed game looks like OEA rather than like a
 * stack of platform dialogs over an empty window.
 */
open class OeaStandaloneGameActivity : Activity(), HostUi {
    protected open val standaloneGameId: String = ""

    private var host: GameHost? = null
    private val main = Handler(Looper.getMainLooper())

    /** The game this app is for. */
    private var descriptor: GameDescriptor? = null
    /** Options chosen in Match setup (persisted per game). */
    private val chosenOptions = LinkedHashMap<String, String>()
    private var chosenMode = PlayMode.SOLO
    /** True when the hub/automation launched us with extras: exit then finishes. */
    private var directLaunch = false

    /** The palette and toolkit, tinted with this game's accent. */
    private var palette: GamePalette = GameColors.palette()
    private var kit: GameKit? = null

    // Multiplayer state (one session at a time, like the engine itself).
    private var browser: LanBrowser? = null
    private var nearbyDialog: Dialog? = null
    private var nearbyList: LinearLayout? = null
    private var lobbyDialog: Dialog? = null
    private var lobbyStatus: TextView? = null
    private var inviteDialog: Dialog? = null
    private var lobbyMode = PlayMode.LAN
    private var pendingStart = false

    private fun toolkit(): GameKit = kit ?: GameKit(this, palette).also { kit = it }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            prepare()
        } catch (t: Throwable) {
            try {
                Toast.makeText(this, "Game could not start: ${t.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            } catch (_: Throwable) {
            }
            try {
                finish()
            } catch (_: Throwable) {
            }
        }
    }

    private fun prepare() {
        try {
            OeaEngine.init(applicationContext)
        } catch (_: Throwable) {
        }
        val id = intent.getStringExtra(EXTRA_GAME_ID)?.takeIf { it.isNotBlank() } ?: standaloneGameId
        // This app is one game. Register just it, so the engine's registry (and
        // therefore every "which games exist?" answer in this app) matches what
        // the icon on the home screen promised.
        OeaGames.registerBuiltIns(applicationContext, only = id.takeIf { it.isNotBlank() }?.let { listOf(it) })
        val descriptor = try {
            OeaEngine.registry.find(id)
        } catch (_: Throwable) {
            null
        }
        if (descriptor == null) {
            Toast.makeText(this, "This game isn't available in this build.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        this.descriptor = descriptor
        palette = GameColors.palette(
            descriptor.accent,
            highContrast = runCatching { OeaEngine.settings.highContrastMenus }.getOrDefault(false)
        )
        kit = null
        GameInsets.edgeToEdge(window)
        descriptor.options.forEach { option ->
            chosenOptions[option.id] = OeaEngine.settings.lastOption(descriptor.id, option.id)
                ?.takeIf { v -> option.choices.any { it.value == v } } ?: option.default
        }
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                onBackInvokedDispatcher.registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT
                ) { handleBack() }
            } catch (_: Throwable) {
            }
        }
        // Direct launch (hub, shortcut, automation): start the match at once.
        if (intent.hasExtra(EXTRA_GAME_ID) || intent.hasExtra(EXTRA_GAME_SEED) || intent.hasExtra(EXTRA_AUTO_START)) {
            directLaunch = true
            val mode = runCatching { PlayMode.valueOf(intent.getStringExtra(EXTRA_GAME_MODE) ?: "SOLO") }
                .getOrDefault(PlayMode.SOLO)
            val options = LinkedHashMap(chosenOptions)
            try {
                intent.getBundleExtra(EXTRA_GAME_OPTIONS)?.let { bundle ->
                    bundle.keySet().forEach { key -> bundle.getString(key)?.let { options[key] = it } }
                }
            } catch (_: Throwable) {
            }
            val players = parsePlayers(intent.getStringExtra(EXTRA_GAME_PLAYERS), descriptor)
            val session = NetSession.current?.takeIf { it.descriptor.id == descriptor.id && it.phase == SessionPhase.PLAYING }
            beginMatch(mode, options, intent.getLongExtra(EXTRA_GAME_SEED, System.nanoTime()), players, session)
        } else {
            showHome()
        }
    }

    private fun parsePlayers(csv: String?, d: GameDescriptor): List<LocalPlayer> {
        val names = (csv ?: "").split(';').map { it.trim() }.filter { it.isNotEmpty() }
        if (names.isEmpty() || d.maxPlayers <= 1) return listOf(LocalPlayer(OeaEngine.settings.playerName, OeaEngine.settings.playerColor))
        val colors = com.oea.engine.EngineSettings.PLAYER_COLORS
        return names.take(4).mapIndexed { i, n -> LocalPlayer(n, colors[i % colors.size]) }
    }

    /** Current value of one option, as a label. */
    private fun optionSummary(opt: com.oea.engine.core.GameOption): String {
        val v = chosenOptions[opt.id] ?: opt.default
        return opt.choices.firstOrNull { it.value == v }?.label ?: v
    }

    /** One line describing the match that Play will start. */
    private fun matchSummary(d: GameDescriptor): String {
        val bits = mutableListOf<String>()
        bits += if (d.maxPlayers > 1) "${d.minPlayers}–${d.maxPlayers} players" else "Single player"
        bits += d.dimension.label
        bits += d.modes.joinToString(" · ") { it.label }
        return bits.joinToString("  ·  ")
    }

    // ================================================================ front screen

    /**
     * The game's home screen. Before this existed the front menu was a platform
     * dialog floating over an activity with no content view at all, which is why
     * the standalone apps looked like a system popup on a black background.
     */
    private fun showHome() {
        val d = descriptor ?: return
        val k = toolkit()
        val page = k.page(padBottomDp = 30)
        val content = page.content

        val cover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageDrawable(GameCoverDrawable(d, k.dpf(24f)))
        }
        content.addView(cover, LinearLayout.LayoutParams(-1, k.dp(168)))

        content.addView(
            k.text(d.title, 30f, palette.text, GameType.bold).apply {
                setPadding(k.dp(4), k.dp(18), k.dp(4), 0)
                letterSpacing = -0.015f
            }
        )
        content.addView(
            k.text(d.tagline, 14.5f, palette.textSecondary).apply {
                setPadding(k.dp(4), k.dp(6), k.dp(4), 0)
                setLineSpacing(k.dpf(2f), 1f)
            }
        )
        content.addView(
            k.text(matchSummary(d), 12f, palette.textTertiary, GameType.medium).apply {
                setPadding(k.dp(4), k.dp(10), k.dp(4), 0)
                letterSpacing = 0.04f
            }
        )

        val play = k.primaryButton("Play") {
            beginMatch(PlayMode.SOLO, chosenOptions, System.nanoTime(), parsePlayers(null, d), null)
        }
        play.textSize = 16.5f
        play.letterSpacing = 0.02f
        content.addView(play, LinearLayout.LayoutParams(-1, k.dp(56)).apply { topMargin = k.dp(20) })

        k.sectionTitle(content, "Before you start")

        val actions = k.group(content)
        actions.addView(
            k.actionRow("⚙", "Match setup", optionsLine(d), highlight = false) { showMatchSetup() },
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = k.dp(8) }
        )
        if (d.maxPlayers > 1) {
            actions.addView(
                k.actionRow("👥", "Play with friends", "Host on Wi-Fi or with an online code, or join one") { showFriendsMenu() },
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = k.dp(8) }
            )
        }
        // Aim sliders only mean something to a game you look around inside. Showing
        // them on a chess app is a control that does nothing, so each game is asked
        // what its own fingers do and gets that screen instead.
        if (d.dimension == com.oea.engine.core.GameDimension.D3 || d.dimension == com.oea.engine.core.GameDimension.D4) {
            actions.addView(
                k.actionRow("🎯", "Controls & aim", "Look and ADS sensitivity, turn limit, invert Y") { showControlsSheet() },
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = k.dp(8) }
            )
        }
        if (controlLegend.isNotEmpty()) {
            actions.addView(
                k.actionRow("✳", "How you play ${d.title}", "This game's own inputs, on one screen") { showLegendSheet() },
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = k.dp(8) }
            )
        }
        actions.addView(
            k.actionRow("◐", "Screen & access", "Menu size, high contrast, no fading") { showAccessSheet() },
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = k.dp(8) }
        )
        actions.addView(
            k.actionRow("ⓘ", "About ${d.title}", d.description.lineSequence().firstOrNull()?.take(60) ?: "What this game is") { showAboutSheet() }
        )

        k.note(
            content,
            "This is the full game, not a demo: matches, settings and multiplayer all run inside this app. " +
                "Nothing else needs to be installed."
        )

        setContentView(page.root)
        if (toolkit().reducedMotion) {
            page.root.alpha = 1f
        } else {
            page.root.alpha = 0f
            page.root.animate().alpha(1f).setDuration(180L).start()
        }
    }

    private fun optionsLine(d: GameDescriptor): String = when {
        d.options.isEmpty() -> "Nothing to set — start playing"
        else -> d.options.joinToString(" · ") { "${it.label}: ${optionSummary(it)}" }.take(90)
    }

    // ================================================================ match setup

    /** Match setup: one sheet of options, each opening its own picker. */
    private fun showMatchSetup() {
        val d = descriptor ?: return
        val k = toolkit()
        if (d.options.isEmpty()) {
            k.sheet("Match setup", "This game has nothing to configure.") { panel, _ ->
                panel.addView(k.quietButton("Back") { showHome() })
            }
            return
        }
        k.sheet("Match setup", "Mode: ${chosenMode.label}. Your choices are remembered for the next match.") { panel, dialog ->
            val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            d.options.forEachIndexed { index, option ->
                list.addView(
                    k.optionRow(option.label, optionSummary(option), false) {
                        dialog.dismiss()
                        showOptionPicker(index)
                    },
                    LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = k.dp(6) }
                )
            }
            panel.addView(k.boundedScroll(list, 0.62f))
            panel.addView(k.buttonRow(k.quietButton("Back") { dialog.dismiss(); showHome() }))
        }
    }

    private fun showOptionPicker(index: Int) {
        val d = descriptor ?: return
        val k = toolkit()
        val opt: com.oea.engine.core.GameOption = d.options.getOrNull(index) ?: return showMatchSetup()
        val labels = opt.choices.map { it.label }
        val descriptions = opt.choices.map { it.description }
        val current = opt.choices.indexOfFirst { it.value == (chosenOptions[opt.id] ?: opt.default) }.coerceAtLeast(0)
        k.choose(opt.label, opt.choices.getOrNull(current)?.description, labels, current, descriptions) { pick ->
            val choice = opt.choices[pick]
            chosenOptions[opt.id] = choice.value
            OeaEngine.settings.setLastOption(d.id, opt.id, choice.value)
            showMatchSetup()
        }
    }

    // ================================================================ controls & aim

    private fun showControlsSheet() {
        val settings = OeaEngine.settings
        val k = toolkit()
        k.sheet("Controls & aim", "Applied to every OEA game on this phone, live — no restart.") { panel, dialog ->
            val host = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            k.slider(host, "Look sensitivity", settings.lookSensitivity, 0.2f, 3f) { settings.lookSensitivity = it }
            k.slider(host, "ADS sensitivity", settings.adsSensitivity, 0.3f, 1.5f) { settings.adsSensitivity = it }
            k.slider(host, "Turn limit (0 = off)", settings.turnSpeedLimit, 0f, 360f, { v ->
                if (v <= 0.5f) "off" else "${v.toInt()}°/s"
            }) { settings.turnSpeedLimit = it }
            k.switchRow(host, "Invert vertical look", "Moves the view the other way when you drag up", settings.invertLookY) {
                settings.invertLookY = it
            }
            panel.addView(k.boundedScroll(host, 0.6f))
            panel.addView(k.buttonRow(k.primaryButton("Done") { dialog.dismiss(); showHome() }))
        }
    }

    /**
     * Accessibility for every game screen in this app. Three switches, all of them
     * live: the scale re-lays the menus, contrast swaps the palette, and reduced
     * motion takes the fades out. They are stored on the engine, so the same choices
     * apply to the other OEA game apps on this phone.
     */
    private fun showAccessSheet() {
        val k = toolkit()
        val d = descriptor ?: return
        val settings = OeaEngine.settings
        var scale = settings.uiScale
        k.sheet("Screen & access", "Applies to every OEA game on this phone") { panel, dialog ->
            val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            k.slider(body, "Menu size", scale, 0.85f, 1.5f, { v -> "%.0f%%".format(v * 100f) }) { value ->
                scale = value
                settings.uiScale = value
            }
            k.switchRow(body, "High contrast menus", "Black grounds, white type, hard edges — ${d.title}'s own accent stays", settings.highContrastMenus) {
                settings.highContrastMenus = it
                palette = GameColors.palette(d.accent, highContrast = it)
                kit = null
                dialog.dismiss()
                showHome()
            }
            k.switchRow(body, "No fading or sliding", "Screens change at once instead of cross-fading", settings.reducedMotion) {
                settings.reducedMotion = it
            }
            panel.addView(k.boundedScroll(body, 0.55f))
            k.note(panel, "Menu size is the one that moves touch targets as well as type, so a larger menu is still a tappable one. Saved per phone, not per match.")
            panel.addView(k.buttonRow(k.quietButton("Reset to 100%") { settings.uiScale = 1f; dialog.dismiss(); kit = null; showHome() }, k.primaryButton("Done") { dialog.dismiss(); kit = null; showHome() }))
        }
    }

    /**
     * What the fingers do, per game. The shared shell cannot know this — a runner
     * swipes, a board taps — so each standalone app answers for itself.
     */
    open val controlLegend: List<Pair<String, String>> get() = emptyList()

    private fun showLegendSheet() {
        val d = descriptor ?: return
        val k = toolkit()
        k.sheet("How you play ${d.title}", "${controlLegend.size} gestures, and the keys that mirror them") { panel, dialog ->
            val group = k.group(panel)
            controlLegend.forEachIndexed { index, (glyph, meaning) ->
                if (index > 0) k.rowDivider(group)
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(k.dp(2), k.dp(10), k.dp(2), k.dp(10))
                }
                row.addView(k.glyphBadge(glyph, palette.cardHigh, palette.accent, 36), LinearLayout.LayoutParams(k.dp(36), k.dp(36)))
                row.addView(
                    k.text(meaning, 13.5f, palette.text).apply { setPadding(k.dp(14), 0, 0, 0) },
                    LinearLayout.LayoutParams(0, -2, 1f)
                )
                group.addView(row, LinearLayout.LayoutParams(-1, -2))
            }
            if (!keyLegend.isEmpty()) {
                k.sectionTitle(panel, "Keyboard & gamepad")
                k.note(panel, keyLegend.joinToString(" · "))
            }
            k.note(panel, "OEA shows you this instead of a wall of sliders that would do nothing in ${d.title}.")
            panel.addView(k.buttonRow(k.primaryButton("Done") { dialog.dismiss() }))
        }
    }

    /** Physical inputs, for the games that have them. */
    open val keyLegend: List<String> get() = emptyList()

    // ================================================================ play with friends

    private fun showFriendsMenu() {
        val d = descriptor ?: return
        val k = toolkit()
        k.sheet("Play with friends", "Up to ${d.maxPlayers} players. Everyone needs the same game and version.") { panel, dialog ->
            val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            list.addView(
                k.actionRow("📶", "Host on Wi-Fi / hotspot", "People on the same network join with the code you get") {
                    dialog.dismiss(); hostSession(lan = true)
                },
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = k.dp(8) }
            )
            list.addView(
                k.actionRow("🌐", "Host online with a code", "Share an invite code; your friend replies with theirs") {
                    dialog.dismiss(); hostSession(lan = false)
                },
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = k.dp(8) }
            )
            list.addView(
                k.actionRow("🔎", "Join nearby", "Look for games already open on this network") {
                    dialog.dismiss(); showNearbyBrowser()
                },
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = k.dp(8) }
            )
            list.addView(
                k.actionRow("🔑", "Join with a code", "Paste an invite, reply or Wi-Fi code") {
                    dialog.dismiss(); promptJoinCode()
                }
            )
            panel.addView(list)
            panel.addView(k.buttonRow(k.quietButton("Back") { dialog.dismiss(); showHome() }))
        }
    }

    private fun hostSession(lan: Boolean) {
        val d = descriptor ?: return
        val s = NetSession.host(this, d, "${OeaEngine.settings.playerName}'s ${d.title}")
        s.addListener(lobbyListener)
        lobbyMode = if (lan) PlayMode.LAN else PlayMode.ONLINE
        if (lan) {
            s.startLan { info, warning ->
                if (warning != null) toast(warning)
                showLobby(joined = false, extra = info?.joinCode?.let { "Wi-Fi join code:\n$it" })
            }
        } else {
            val invite = s.createOnlineInvite()
            showLobby(joined = false, extra = "Creating your invite…")
            showInviteSheet(invite)
        }
    }

    private fun showInviteSheet(invite: OnlineInvite) {
        inviteDialog?.dismiss()
        val k = toolkit()
        val status = k.text("", 13.5f, palette.textSecondary, GameType.medium)
        val codeView = k.text("", 14f, palette.text, GameType.medium).apply {
            setTextIsSelectable(true)
            setPadding(0, k.dp(10), 0, k.dp(10))
            setLineSpacing(k.dpf(2f), 1f)
        }
        val reply = k.field("Paste your friend's reply code (OEA-…)")
        fun refresh() {
            status.text = invite.status.label + (if (invite.message.isNotBlank()) " — ${invite.message}" else "")
            val code = invite.code
            if (code != null && codeView.text.isEmpty()) codeView.text = "Invite code (share this):\n\n$code"
        }
        invite.onChange = { main.post { refresh() } }
        refresh()
        val dialog = k.sheet("Invite a friend online", "Send your code, then paste the reply you get back.") { panel, d ->
            panel.addView(status)
            panel.addView(codeView)
            panel.addView(reply, LinearLayout.LayoutParams(-1, -2).apply { topMargin = k.dp(6) })
            panel.addView(
                k.buttonRow(
                    k.quietButton("Share") { invite.code?.let { share(it, "OEA invite code") } },
                    k.primaryButton("Connect") { invite.acceptReply(reply.text.toString())?.let { toast(it) } }
                )
            )
            panel.addView(k.quietButton("Close") { d.dismiss() }, LinearLayout.LayoutParams(-1, -2))
        }
        dialog.setOnDismissListener { invite.onChange = null }
        inviteDialog = dialog
    }

    private fun showNearbyBrowser() {
        val d = descriptor ?: return
        toast("Looking for games on this network…")
        browser?.stop()
        nearbyList = null
        nearbyDialog?.dismiss()
        nearbyDialog = null
        browser = LanBrowser(this, d.id) { games -> main.post { presentNearby(games) } }.also { it.start() }
        showNearbySheet(emptyList())
    }

    /** The nearby picker: one sheet that refreshes itself as beacons arrive. */
    private fun showNearbySheet(games: List<LanGame>) {
        val k = toolkit()
        val open = games.filter { it.compatible && !it.isFull && it.inLobby }
        val list = nearbyList
        if (list != null) {
            renderNearbyRows(list, open)
            return
        }
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        nearbyList = container
        renderNearbyRows(container, open)
        k.sheet("Join nearby", if (open.isEmpty()) "Looking…" else "${open.size} game(s) on this network") { panel, dialog ->
            panel.addView(k.boundedScroll(container, 0.6f))
            panel.addView(
                k.buttonRow(
                    k.quietButton("Stop") {
                        browser?.stop(); browser = null
                        nearbyDialog = null; nearbyList = null
                        dialog.dismiss(); showHome()
                    }
                )
            )
        }.also { dialog ->
            nearbyDialog = dialog
            dialog.setOnDismissListener {
                if (nearbyDialog === dialog) {
                    nearbyDialog = null
                    nearbyList = null
                }
            }
        }
    }

    private fun renderNearbyRows(container: LinearLayout, games: List<LanGame>) {
        val k = toolkit()
        container.removeAllViews()
        if (games.isEmpty()) {
            container.addView(
                k.text("No open games yet. Ask the host to open Play with friends › Host on Wi-Fi.", 13f, palette.textSecondary).apply {
                    setPadding(k.dp(6), k.dp(10), k.dp(6), k.dp(10))
                    setLineSpacing(k.dpf(2f), 1f)
                }
            )
            return
        }
        games.forEach { g ->
            container.addView(
                k.actionRow("📶", g.roomName, "${g.players}/${g.maxPlayers} · ${g.hostName}") {
                    browser?.stop(); browser = null
                    nearbyDialog?.dismiss(); nearbyDialog = null; nearbyList = null
                    val d = descriptor ?: return@actionRow
                    val s = NetSession.joinLan(this, d, g.address, g.port, lobbyListener)
                    lobbyMode = PlayMode.LAN
                    showLobby(joined = true, extra = "Connecting to ${g.roomName}…")
                    s.setReady(true)
                },
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = k.dp(6) }
            )
        }
    }

    private fun presentNearby(games: List<LanGame>) {
        if (isFinishing || isDestroyed) return
        val list = nearbyList ?: return showNearbySheet(games)
        main.post { renderNearbyRows(list, games.filter { it.compatible && !it.isFull && it.inLobby }) }
    }

    private fun promptJoinCode() {
        val k = toolkit()
        val input = k.field("OEA-… invite, reply or Wi-Fi code")
        k.sheet("Join with a code", "Any OEA code works: an online invite, a reply, or a Wi-Fi code.") { panel, dialog ->
            panel.addView(input, LinearLayout.LayoutParams(-1, -2))
            panel.addView(
                k.buttonRow(
                    k.quietButton("Back") { dialog.dismiss(); showHome() },
                    k.primaryButton("Join") { dialog.dismiss(); joinWithCode(input.text.toString()) }
                )
            )
        }
        input.post {
            input.requestFocus()
            getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                ?.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun joinWithCode(text: String) {
        val d = descriptor ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            toast("Paste a code first.")
            promptJoinCode()
            return
        }
        if (LanJoinCode.isLanCode(trimmed)) {
            val target = LanJoinCode.decode(trimmed).getOrElse {
                toast(it.message ?: "That code doesn't work.")
                promptJoinCode()
                return
            }
            val s = NetSession.joinLanAny(this, d, target.addresses, target.port, lobbyListener)
            lobbyMode = PlayMode.LAN
            showLobby(joined = true, extra = "Connecting…")
            s.setReady(true)
            return
        }
        val s = NetSession.joinOnline(this, d, trimmed) { code, err ->
            if (err != null) {
                toast(err)
                main.post {
                    if (lobbyStatus == null) showLobby(joined = true, extra = err) else refreshLobby(err)
                    // The reply code only exists once the connection attempt has
                    // been answered, so it is appended to the lobby text.
                    lobbyStatus?.let { status ->
                        if (code != null && !status.text.contains(code)) {
                            status.text = status.text.toString() + "\n\nSend this reply code to the host:\n$code"
                        }
                    }
                }
            } else if (code != null) {
                main.post {
                    lobbyStatus?.let { status ->
                        if (!status.text.contains(code)) {
                            status.text = status.text.toString() + "\n\nSend this reply code to the host:\n$code"
                        }
                    }
                }
            }
        }
        if (s != null) {
            lobbyMode = PlayMode.ONLINE
            showLobby(joined = true, extra = "Preparing your reply…")
            s.setReady(true)
        }
    }

    // ================================================================ about

    private fun showAboutSheet() {
        val d = descriptor ?: return
        val k = toolkit()
        k.sheet(d.title, d.tagline) { panel, dialog ->
            panel.addView(
                k.text(d.description, 14f, palette.textSecondary).apply {
                    setLineSpacing(k.dpf(2f), 1f)
                    setPadding(0, 0, 0, k.dp(10))
                }
            )
            panel.addView(
                k.text(
                    "Players: ${if (d.maxPlayers > 1) "${d.minPlayers}–${d.maxPlayers}" else "1"}\n" +
                        "Modes: ${d.modes.joinToString(", ") { it.label }}\n" +
                        "Rendering: ${d.dimension.label}\n" +
                        "Engine: OEA Engine ${com.oea.engine.OeaEngine.VERSION}",
                    12.5f,
                    palette.textTertiary
                ).apply { setLineSpacing(k.dpf(3f), 1f) }
            )
            panel.addView(k.buttonRow(k.primaryButton("Close") { dialog.dismiss() }))
        }
    }

    // ================================================================ lobby

    /** One compact lobby sheet for hosting and joining; it updates itself. */
    private fun showLobby(joined: Boolean, extra: String?) {
        val s = NetSession.current ?: return
        s.removeListener(lobbyListener)
        s.addListener(lobbyListener)
        lobbyDialog?.dismiss()
        val k = toolkit()
        val status = k.text("", 14f, palette.text, GameType.regular).apply {
            setLineSpacing(k.dpf(3f), 1f)
            setPadding(0, k.dp(4), 0, k.dp(8))
        }
        lobbyStatus = status
        val dialog = k.sheet(s.roomName.ifBlank { "Lobby" }, if (joined) "You are in." else "Waiting for players.") { panel, d ->
            panel.addView(k.boundedScroll(status, 0.5f))
            val buttons = mutableListOf<View>()
            if (!joined && s.isHost) {
                buttons += k.primaryButton("Start match") {
                    s.startMatch(LinkedHashMap(chosenOptions))
                }
            }
            buttons += k.quietButton(if (joined) "Leave" else "Cancel") {
                lobbyDialog = null
                lobbyStatus = null
                d.dismiss()
                s.close("Left the lobby")
                if (!directLaunch) showHome()
            }
            panel.addView(k.buttonRow(*buttons.toTypedArray()))
        }
        dialog.setOnDismissListener {
            if (lobbyDialog === dialog) {
                lobbyDialog = null
                lobbyStatus = null
            }
        }
        lobbyDialog = dialog
        refreshLobby(extra)
    }

    private fun refreshLobby(extra: String? = null) {
        val s = NetSession.current ?: return
        val status = lobbyStatus ?: return
        val players = s.players.joinToString("\n") { p: NetPlayer ->
            val tags = buildList {
                if (p.isHost) add("host")
                if (p.ready) add("ready")
                if (!p.connected) add("reconnecting")
            }
            p.name + if (tags.isEmpty()) "" else " · " + tags.joinToString(", ")
        }
        status.text = buildString {
            append(s.status.ifBlank { s.phase.label })
            if (extra != null) append("\n\n").append(extra)
            if (players.isNotBlank()) append("\n\n").append(players)
            if (s.isHost) append("\n\nYou are the host. Start the match when everyone is in.")
            else append("\n\nWaiting for the host to start the match.")
        }
    }

    private val lobbyListener = object : SessionListener {
        override fun onSessionUpdate(session: NetSession) {
            main.post { if (lobbyDialog?.isShowing == true) refreshLobby() }
        }

        override fun onMatchStart(session: NetSession) {
            main.post {
                lobbyDialog?.dismiss(); lobbyDialog = null
                inviteDialog?.dismiss(); inviteDialog = null
                pendingStart = true
                // The host's authoritative match settings travel in the start
                // packet; using our local defaults here would desynchronise
                // maps, rules or time controls before the first frame.
                beginMatch(lobbyMode, LinkedHashMap(session.matchSettings), session.seed, parsePlayers(null, session.descriptor), session)
            }
        }

        override fun onSessionClosed(session: NetSession, reason: String) {
            main.post {
                // If the user already left the lobby themselves, do nothing —
                // only an unexpected close (kick, host left) needs a message.
                val hadDialog = lobbyDialog != null || inviteDialog != null
                lobbyDialog?.dismiss(); lobbyDialog = null
                inviteDialog?.dismiss(); inviteDialog = null
                if (hadDialog && host == null && !isFinishing && !pendingStart) {
                    toast(reason)
                    if (!directLaunch) showHome()
                }
            }
        }
    }

    // ================================================================ running the match

    private fun beginMatch(mode: PlayMode, options: Map<String, String>, seed: Long, players: List<LocalPlayer>, session: NetSession?) {
        val d = descriptor ?: return
        if (!d.supports(mode)) {
            Toast.makeText(this, "This build doesn't support ${mode.label}.", Toast.LENGTH_LONG).show()
            if (!directLaunch) showHome()
            return
        }
        val problem = try {
            OeaEngine.surfaceProblem()
        } catch (e: Throwable) {
            e.message
        }
        if (problem != null) {
            Toast.makeText(this, problem, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        chosenMode = mode
        val launch = GameLaunch(
            mode = mode,
            options = options,
            seed = seed,
            localPlayers = players,
            session = session,
        )
        val gameHost = try {
            GameHost(this, d, launch, this)
        } catch (e: Throwable) {
            Toast.makeText(this, "${d.title} could not start on this device (${e.javaClass.simpleName})", Toast.LENGTH_LONG).show()
            if (!directLaunch) showHome()
            return
        }
        host = gameHost
        gameHost.onFatalError = { error ->
            runCatching { Toast.makeText(this, "${d.title} stopped (${error.javaClass.simpleName})", Toast.LENGTH_LONG).show() }
            runCatching { finish() }
        }
        try {
            setContentView(gameHost.root)
        } catch (_: Throwable) {
            Toast.makeText(this, "${d.title} could not create its screen", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        onMatchStarted(d.id)
    }

    /**
     * Called when a match actually reached the screen. Host apps (the OEA
     * launcher entries) override this to report the session to their own
     * systems — Game Boost, for example — which live outside this library.
     */
    protected open fun onMatchStarted(gameId: String) {}

    // ================================================================ activity plumbing

    override fun onResume() {
        super.onResume()
        try {
            host?.onResume()
        } catch (_: Throwable) {
        }
    }

    override fun onPause() {
        try {
            host?.onPause()
        } catch (_: Throwable) {
        }
        super.onPause()
    }

    override fun onDestroy() {
        try {
            browser?.stop()
        } catch (_: Throwable) {
        }
        browser = null
        nearbyDialog = null
        lobbyDialog = null
        inviteDialog = null
        try {
            host?.onDestroy()
        } catch (_: Throwable) {
        }
        host = null
        // A standalone activity owns its lobby/session. Do not leave sockets,
        // multicast locks or an orphaned host running after the user leaves.
        runCatching {
            NetSession.current?.takeIf { it.phase != SessionPhase.CLOSED }?.close("Game app closed")
        }
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        try {
            host?.onWindowFocusChanged(hasFocus)
        } catch (_: Throwable) {
        }
    }

    @Deprecated("Back is routed to the game first")
    override fun onBackPressed() = handleBack()

    private fun handleBack() {
        val currentHost = host
        if (currentHost == null) {
            // On the front screen, Back leaves the app instead of looping sheets.
            finish()
            return
        }
        try {
            currentHost.onBackPressed {
                toolkit().confirm(
                    "Leave the game?",
                    "Your progress in this round will be lost.",
                    "Leave",
                    "Stay"
                ) {
                    host = null
                    runCatching { currentHost.onDestroy() }
                    runCatching {
                        NetSession.current?.takeIf { it.phase != SessionPhase.CLOSED }?.close("Left the match")
                    }
                    if (directLaunch) finish() else showHome()
                }
            }
        } catch (e: Throwable) {
            finish()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        if (host?.dispatchKeyEvent(event) == true) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean =
        host?.dispatchGenericMotionEvent(event) == true || super.onGenericMotionEvent(event)

    // ---------------------------------------------------------------- HostUi

    override fun toast(message: String) = runCatching {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }.let { }

    override fun share(text: String, title: String) {
        runCatching {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), title))
        }.onFailure { toast("Nothing can share text on this phone.") }
    }

    override fun menu(title: String, subtitle: String?, items: List<MenuItem>, onPick: (String?) -> Unit) {
        try {
            val k = toolkit()
            var picked = false
            val descriptions = items.map { it.description }
            val labels = items.map { it.label }
            k.sheet(title, subtitle) { panel, dialog ->
                val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                labels.forEachIndexed { index, label ->
                    list.addView(
                        k.optionRow(label, descriptions.getOrNull(index), false) {
                            picked = true
                            dialog.dismiss()
                            onPick(items[index].id)
                        },
                        LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = k.dp(6) }
                    )
                }
                panel.addView(k.boundedScroll(list, 0.62f))
            }.setOnDismissListener { if (!picked) onPick(null) }
        } catch (_: Throwable) {
            onPick(null)
        }
    }

    override fun confirm(title: String, message: String, positive: String, negative: String, onResult: (Boolean) -> Unit) {
        try {
            var answered = false
            toolkit().confirm(title, message, positive, negative) { answered = true; onResult(true) }
                .setOnDismissListener { if (!answered) onResult(false) }
        } catch (_: Throwable) {
            onResult(false)
        }
    }

    override fun prompt(title: String, hint: String, initial: String, onResult: (String?) -> Unit) {
        try {
            val k = toolkit()
            val input = k.field(hint, initial)
            var answered = false
            k.sheet(title, null) { panel, dialog ->
                panel.addView(input, LinearLayout.LayoutParams(-1, -2))
                panel.addView(
                    k.buttonRow(
                        k.quietButton("Cancel") { answered = true; dialog.dismiss(); onResult(null) },
                        k.primaryButton("OK") { answered = true; dialog.dismiss(); onResult(input.text?.toString()) }
                    )
                )
            }.setOnDismissListener { if (!answered) onResult(null) }
            input.post {
                input.requestFocus()
                getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                    ?.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        } catch (_: Throwable) {
            onResult(null)
        }
    }

    override fun exit(result: GameResult?) {
        val currentHost = host ?: run { finish(); return }
        host = null
        runCatching { currentHost.onDestroy() }
        pendingStart = false
        runCatching {
            NetSession.current?.takeIf { it.phase != SessionPhase.CLOSED }?.close("Left the match")
        }
        result?.summary?.let { toast(it) }
        if (isFinishing || directLaunch) {
            finish()
            return
        }
        showHome()
    }


    companion object {
        const val EXTRA_GAME_ID = "com.oea.games.extra.GAME_ID"
        const val EXTRA_GAME_MODE = "com.oea.games.extra.GAME_MODE"
        const val EXTRA_GAME_OPTIONS = "com.oea.games.extra.GAME_OPTIONS"
        const val EXTRA_GAME_SEED = "com.oea.games.extra.GAME_SEED"
        /** Semicolon-separated player names for same-device play. */
        const val EXTRA_GAME_PLAYERS = "com.oea.games.extra.GAME_PLAYERS"
        /** Set to true to skip the front menu even without other extras. */
        const val EXTRA_AUTO_START = "com.oea.games.extra.AUTO_START"
    }
}
