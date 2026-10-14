package com.oea.launcher.games

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.oea.engine.EngineSettings
import com.oea.engine.OeaEngine
import com.oea.engine.core.GameDescriptor
import com.oea.engine.core.GameLaunch
import com.oea.engine.core.LocalPlayer
import com.oea.engine.core.PlayMode
import com.oea.engine.island.IslandPlacement
import com.oea.engine.net.lan.LanBrowser
import com.oea.engine.net.lan.LanGame
import com.oea.engine.net.lan.LanJoinCode
import com.oea.engine.net.qr.QrCodes
import com.oea.engine.net.qr.QrScannerView
import com.oea.engine.net.rtc.IceServerConfig
import com.oea.engine.net.rtc.RtcProbe
import com.oea.engine.net.rtc.SignalCode
import com.oea.engine.net.rtc.SignalCodeException
import com.oea.engine.net.session.NetSession
import com.oea.engine.net.session.OnlineInvite
import com.oea.engine.net.session.SessionListener
import com.oea.engine.net.session.SessionPhase
import com.oea.engine.perf.QualityTier
import com.oea.games.OeaGames
import com.oea.games.OeaStandaloneGameActivity
import com.oea.games.lab.EngineLab
import com.oea.launcher.OeaSystemToolsActivity
import com.oea.launcher.R
import com.oea.launcher.data.OeaDataStore
import com.oea.launcher.perf.OeaGameRescue
import com.oea.launcher.ui.OeaInsets
import com.oea.launcher.ui.OeaKit
import com.oea.launcher.ui.OeaPalette
import com.oea.launcher.ui.OeaScreenFailure
import com.oea.launcher.ui.OeaType

/**
 * OEA Games: the hub for every game built on the OEA Engine. Lists games,
 * starts solo and same-device matches, hosts and joins multiplayer through
 * the two supported methods (local Wi-Fi / hotspot, and online WebRTC with
 * codes and QR), runs lobbies and holds the engine's settings.
 */
class OeaGamesActivity : Activity(), SessionListener {
    private sealed class Page {
        object Home : Page()
        data class Game(val id: String) : Page()
        object Lobby : Page()
        data class JoinOnline(val id: String?) : Page()
        data class Nearby(val id: String?) : Page()
        object Graphics : Page()
        object Servers : Page()
        object Island : Page()
        object Profile : Page()
    }

    private lateinit var palette: OeaPalette
    private lateinit var kit: OeaKit
    private val main = Handler(Looper.getMainLooper())
    private var page: Page = Page.Home
    private val history = ArrayDeque<Page>()
    private var currentScroll: android.widget.ScrollView? = null
    private val scrollMemory = HashMap<String, Int>()
    private var browser: LanBrowser? = null
    private var nearby: List<LanGame> = emptyList()
    private var joinReply: String? = null
    private var joinError: String? = null
    private var joinInput = ""
    private var probeResult: String? = null
    private var pendingCamera: (() -> Unit)? = null
    private var pendingImage: ((String?) -> Unit)? = null
    private val lobbyOptions = HashMap<String, String>()
    private var lobbyMode = PlayMode.LAN

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            OeaGames.registerBuiltIns(this)
            palette = OeaGamesTheme.palette()
            kit = OeaKit(this, palette)
            OeaInsets.edgeToEdge(window, lightBars = false)
            NetSession.current?.let { s -> s.addListener(this); if (s.phase == SessionPhase.LOBBY || s.phase == SessionPhase.PLAYING || s.phase == SessionPhase.CONNECTING) page = Page.Lobby }
            render()
            // Android 16+ (target SDK 36) delivers Back only through this callback.
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { handleBack() }
            }
        } catch (e: Throwable) {
            OeaScreenFailure.show(this, "OEA Games", e)
        }
    }

    override fun onResume() {
        super.onResume()
        val s = NetSession.current
        if (page == Page.Lobby && (s == null || s.phase == SessionPhase.CLOSED)) { page = Page.Home; history.clear() }
        if (::kit.isInitialized) render()
    }

    override fun onDestroy() {
        browser?.stop()
        NetSession.current?.removeListener(this)
        super.onDestroy()
    }

    @Deprecated("Navigation handled in-page")
    override fun onBackPressed() = handleBack()

    private fun handleBack() {
        if (page == Page.Lobby) {
            val s = NetSession.current
            if (s != null && s.phase != SessionPhase.CLOSED) {
                kit.confirm(if (s.isHost) "Close the game?" else "Leave the game?", if (s.isHost) "Everyone in the lobby will be disconnected." else "You can join again later.",
                    if (s.isHost) "Close" else "Leave", destructive = true) { s.close(); goBack() }
                return
            }
        }
        if (page is Page.JoinOnline && joinReply != null) NetSession.current?.takeIf { it.phase == SessionPhase.CONNECTING }?.close("Cancelled")
        if (!goBack()) finish()
    }

    private fun navigate(p: Page) {
        currentScroll?.let { scrollMemory[key(page)] = it.scrollY }
        history.addLast(page)
        page = p
        onEnter(p)
        render()
    }

    private fun goBack(): Boolean {
        currentScroll?.let { scrollMemory[key(page)] = it.scrollY }
        onLeave(page)
        val prev = history.removeLastOrNull() ?: return false.also { if (page != Page.Home) { page = Page.Home; render() } }
        page = prev
        render()
        return true
    }

    private fun key(p: Page) = p.toString()

    private fun onEnter(p: Page) {
        when (p) {
            is Page.Nearby -> startBrowsing(p.id)
            is Page.JoinOnline -> { joinReply = null; joinError = null }
            else -> Unit
        }
    }

    private fun onLeave(p: Page) {
        if (p is Page.Nearby) { browser?.stop(); browser = null }
    }

    private fun render() {
        try {
            when (val p = page) {
                Page.Home -> renderHome()
                is Page.Game -> renderGame(p.id)
                Page.Lobby -> renderLobby()
                is Page.JoinOnline -> renderJoinOnline(p.id)
                is Page.Nearby -> renderNearby(p.id)
                Page.Graphics -> renderGraphics()
                Page.Servers -> renderServers()
                Page.Island -> renderIsland()
                Page.Profile -> renderProfile()
            }
        } catch (e: Throwable) {
            OeaScreenFailure.show(this, "OEA Games", e)
        }
    }

    private fun page(title: String, subtitle: String?): OeaKit.Page =
        kit.page(title, subtitle, onBack = if (page == Page.Home) null else ({ onBackPressedCompat() }))

    private fun onBackPressedCompat() = handleBack()

    private fun present(p: OeaKit.Page) {
        setContentView(p.root)
        currentScroll = p.scroll
        val y = scrollMemory[key(page)] ?: 0
        if (y > 0) p.scroll.post { p.scroll.scrollTo(0, y) }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    // ================================================================ home

    private fun renderHome() {
        val device = OeaEngine.device
        val perfHz = device.maxRefreshRate.toInt()
        val p = page("OEA Games", "OEA Engine ${OeaEngine.VERSION} · ${device.recommendedTier.label} graphics · $perfHz Hz")
        val box = p.content
        NetSession.current?.takeIf { it.phase != SessionPhase.CLOSED }?.let { s ->
            kit.banner(box, R.drawable.oic_sports_esports, if (s.isHost) "You're hosting ${s.descriptor.title}" else "You're in a ${s.descriptor.title} lobby",
                s.status, OeaKit.Tone.SUCCESS, "Open lobby") { navigate(Page.Lobby) }
        }
        for (d in OeaEngine.registry.all()) if (d.id != EngineLab.ID) gameCard(box, d)
        kit.sectionTitle(box, "Play with friends")
        var g = kit.group(box)
        kit.row(g, R.drawable.oic_wifi, "Join a nearby game", "Same Wi-Fi or a friend's hotspot · games appear automatically") { navigate(Page.Nearby(null)) }
        kit.row(g, R.drawable.oic_qr_code, "Join with a code or QR", "An invite from a friend on any network") { navigate(Page.JoinOnline(null)) }
        kit.sectionTitle(box, "Engine")
        g = kit.group(box)
        OeaEngine.registry.find(EngineLab.ID)?.let { lab ->
            kit.row(g, R.drawable.oic_auto_awesome, lab.title, lab.tagline) { launchGame(lab, PlayMode.SOLO, emptyMap()) }
        }
        kit.row(g, R.drawable.oic_rocket_launch, "Graphics & performance", "${qualityLabel()} · ${fpsLabel()}") { navigate(Page.Graphics) }
        kit.row(g, R.drawable.oic_public, "Online connection servers", serversSummary()) { navigate(Page.Servers) }
        kit.row(g, R.drawable.oic_music_note, "Dynamic Island in games", islandSummary()) { navigate(Page.Island) }
        kit.row(g, R.drawable.oic_person, "Player profile", OeaEngine.settings.playerName) { navigate(Page.Profile) }
        kit.note(box, "Everything runs on your phones: no accounts, no game servers. Nearby play uses your Wi-Fi or hotspot; online play connects phones directly with WebRTC after you exchange a code or QR.")
        present(p)
    }

    private fun gameCard(parent: ViewGroup, d: GameDescriptor) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = kit.rounded(palette.card, 26)
            clipToOutline = true
        }
        val cover = View(this).apply { background = GameCoverDrawable(d, kit.dpf(26f)) }
        card.addView(cover, LinearLayout.LayoutParams(-1, kit.dp(150)))
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(kit.dp(18), kit.dp(14), kit.dp(18), kit.dp(16)) }
        body.addView(kit.text(d.title, 22f, palette.text, OeaType.medium))
        body.addView(kit.text(d.tagline, 13.5f, palette.textSecondary).apply { setPadding(0, kit.dp(2), 0, kit.dp(10)) })
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val modeLabels = d.modes.map { when (it) { PlayMode.SOLO -> "Solo"; PlayMode.SAME_DEVICE -> d.sameDeviceLabel; PlayMode.LAN -> "Wi-Fi/hotspot"; PlayMode.ONLINE -> "Online" } } +
            listOf(d.dimension.label) + (if (d.maxPlayers > 1) listOf("Up to ${d.maxPlayers}") else emptyList())
        for (label in modeLabels) chips.addView(kit.text(label, 11.5f, palette.textSecondary).apply {
            background = kit.rounded(palette.cardHigh, 12)
            setPadding(kit.dp(9), kit.dp(4), kit.dp(9), kit.dp(4))
        }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = kit.dp(6) })
        body.addView(kit.hscroll(chips))
        val buttons = mutableListOf<View>()
        buttons += kit.primaryButton("Play", R.drawable.oic_play_arrow) { playSheet(d) }
        if (d.supports(PlayMode.LAN) || d.supports(PlayMode.ONLINE)) buttons += kit.tonalButton("With friends", R.drawable.oic_sports_esports) { navigate(Page.Game(d.id)) }
        body.addView(kit.buttonRow(*buttons.toTypedArray()), LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(12) })
        card.addView(body)
        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(14) })
    }

    /** Solo and same-device modes (multiplayer goes through the game page). */
    private fun playSheet(d: GameDescriptor) {
        val modes = listOf(PlayMode.SOLO, PlayMode.SAME_DEVICE).filter { d.supports(it) }
        when (modes.size) {
            0 -> navigate(Page.Game(d.id))
            1 -> startSheet(d, modes[0])
            else -> kit.choose(d.title, "How do you want to play?", modes.map { if (it == PlayMode.SOLO) "Solo" else d.sameDeviceLabel }, 0,
                modes.map { if (it == PlayMode.SOLO) "Just you${if (d.id == "chess") " against the AI" else if (d.id == "arena") " against bots" else ""}" else "${d.sameDevicePlayers} players on this phone" }) { i ->
                startSheet(d, modes[i])
            }
        }
    }

    private fun optionValue(d: GameDescriptor, id: String): String {
        val opt = d.options.first { it.id == id }
        return OeaEngine.settings.lastOption(d.id, id)?.takeIf { v -> opt.choices.any { it.value == v } } ?: opt.default
    }

    /** Options for [mode] with remembered values, then Start. */
    private fun startSheet(d: GameDescriptor, mode: PlayMode) {
        val relevant = d.options.filter { mode in it.modes }
        val values = relevant.associate { it.id to optionValue(d, it.id) }.toMutableMap()
        kit.sheet(d.title, if (mode == PlayMode.SOLO) "Solo" else d.sameDeviceLabel) { panel, dialog ->
            val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            fun build() {
                list.removeAllViews()
                val group = kit.group(list)
                for (opt in relevant) {
                    val current = opt.choices.firstOrNull { it.value == values[opt.id] } ?: opt.choices.first()
                    kit.row(group, 0, opt.label, current.description, value = current.label) {
                        kit.choose(opt.label, null, opt.choices.map { it.label }, opt.choices.indexOf(current), opt.choices.map { it.description }) { i ->
                            values[opt.id] = opt.choices[i].value
                            build()
                        }
                    }
                }
            }
            build()
            panel.addView(list)
            var p2: EditText? = null
            if (mode == PlayMode.SAME_DEVICE) {
                panel.addView(kit.text("Second player", 13f, palette.textSecondary).apply { setPadding(kit.dp(6), kit.dp(6), 0, kit.dp(6)) })
                p2 = kit.field("Name", "Player 2").also { panel.addView(it, LinearLayout.LayoutParams(-1, -2)) }
            }
            panel.addView(kit.buttonRow(
                kit.quietButton("Cancel") { dialog.dismiss() },
                kit.primaryButton("Start") {
                    values.forEach { (k, v) -> OeaEngine.settings.setLastOption(d.id, k, v) }
                    dialog.dismiss()
                    val s = OeaEngine.settings
                    val players = if (mode == PlayMode.SAME_DEVICE) listOf(
                        LocalPlayer(s.playerName, s.playerColor),
                        LocalPlayer(p2?.text?.toString()?.trim()?.ifBlank { null } ?: "Player 2", EngineSettings.PLAYER_COLORS.first { it != s.playerColor }),
                    ) else listOf(LocalPlayer(s.playerName, s.playerColor))
                    launchGame(d, mode, values, players)
                },
            ), LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(8) })
        }
    }

    private fun launchGame(d: GameDescriptor, mode: PlayMode, options: Map<String, String>, players: List<LocalPlayer> = emptyList(), session: NetSession? = null) {
        // If the user installed one of the separate game APKs, route simple
        // solo launches through its own Android app/icon. Keep the in-hub
        // engine path as a complete fallback and for same-device/network play.
        val standalonePackage = when (d.id) {
            "arena" -> "com.oea.arena"
            "chess" -> "com.oea.chess"
            "runner" -> "com.oea.runner"
            else -> null
        }
        if (mode == PlayMode.SOLO && session == null && standalonePackage != null) {
            val external = runCatching { packageManager.getLaunchIntentForPackage(standalonePackage) }.getOrNull()
            if (external != null) {
                val optionBundle = android.os.Bundle().apply { options.forEach { (key, value) -> putString(key, value) } }
                val gameSeed = System.nanoTime()
                runCatching {
                    startActivity(external
                        .putExtra(OeaStandaloneGameActivity.EXTRA_GAME_ID, d.id)
                        .putExtra(OeaStandaloneGameActivity.EXTRA_GAME_MODE, mode.name)
                        .putExtra(OeaStandaloneGameActivity.EXTRA_GAME_OPTIONS, optionBundle)
                        .putExtra(OeaStandaloneGameActivity.EXTRA_GAME_SEED, gameSeed))
                }.onSuccess { return }
                    .onFailure { toast("Opening the standalone game failed; starting it in OEA instead.") }
            }
        }
        val launch = GameLaunch(mode, options, session?.seed ?: System.nanoTime(), players, session)
        OeaGameLauncher.start(this, d.id, launch)
    }

    // ================================================================ game page (multiplayer)

    private fun renderGame(id: String) {
        val d = OeaEngine.registry.find(id) ?: return run { page = Page.Home; render() }
        val p = page(d.title, d.description)
        val box = p.content
        val cover = View(this).apply { background = GameCoverDrawable(d, kit.dpf(26f)) }
        box.addView(cover, 1, LinearLayout.LayoutParams(-1, kit.dp(140)).apply { bottomMargin = kit.dp(12) })
        if (OeaGameRescue.isUnstable(this, id)) repairCard(box, d)
        if (d.maxPlayers <= 1) {
            kit.note(box, "${d.title} is single-player.")
            present(p); return
        }
        kit.sectionTitle(box, "Host a game")
        var g = kit.group(box)
        if (d.supports(PlayMode.LAN)) kit.row(g, R.drawable.oic_wifi, "Host on Wi-Fi / hotspot", "Friends on your Wi-Fi or your phone's hotspot see the game instantly") { host(d, lan = true) }
        if (d.supports(PlayMode.ONLINE)) kit.row(g, R.drawable.oic_qr_code, "Host online with a code", "Send an invite code or QR to a friend anywhere") { host(d, lan = false) }
        kit.sectionTitle(box, "Join a friend")
        g = kit.group(box)
        if (d.supports(PlayMode.LAN)) kit.row(g, R.drawable.oic_wifi, "Join nearby", "Find games on this Wi-Fi or hotspot") { navigate(Page.Nearby(d.id)) }
        if (d.supports(PlayMode.ONLINE)) kit.row(g, R.drawable.oic_qr_code, "Join with a code or QR", "Paste or scan your friend's invite") { navigate(Page.JoinOnline(d.id)) }
        kit.note(box, "${d.minPlayers}–${d.maxPlayers} players. A host can mix both methods: friends nearby on Wi-Fi and others online in the same lobby.")
        present(p)
    }

    private fun host(d: GameDescriptor, lan: Boolean) {
        val s = NetSession.host(this, d, "${OeaEngine.settings.playerName}'s ${d.title}")
        s.addListener(this)
        lobbyMode = if (lan) PlayMode.LAN else PlayMode.ONLINE
        lobbyOptions.clear()
        d.options.filter { lobbyMode in it.modes }.forEach { lobbyOptions[it.id] = optionValue(d, it.id) }
        if (lan) s.startLan { _, warning -> if (warning != null) toast(warning); if (page == Page.Lobby) render() }
        navigate(Page.Lobby)
        if (!lan) main.post { openInviteSheet() }
    }

    /**
     * Shown when this game stopped more than once in a row. OEA knows the
     * difference between a save that went bad, a phone that cannot hold the
     * scene and an unpatched driver, so the card offers the fixes that match
     * instead of asking the player to try again and hope.
     */
    private fun repairCard(box: LinearLayout, d: GameDescriptor) {
        kit.banner(
            box,
            R.drawable.oic_warning_fill,
            "${d.title} stopped more than once",
            "OEA kept everything else intact. These are the fixes that usually work, from the least to the most drastic.",
            OeaKit.Tone.WARNING,
        )
        val group = kit.group(box)
        kit.row(group, R.drawable.oic_tune, "Lower the graphics quality", "Renders smaller so the phone's memory and GPU keep up") {
            val tier = OeaGameRescue.lowerQuality()
            OeaGameRescue.noteRunOk(this, d.id)
            toast("Quality is now $tier. Start the game again.")
            render()
        }
        kit.row(group, R.drawable.oic_delete, "Reset this game's saved data", "Clears only ${d.title}'s records (progress, options, replays)") {
            kit.confirm("Reset ${d.title}'s data?", "Only this game's saved records are removed. OEA's own settings, your Home layout and other games stay as they are.", "Reset", destructive = true) {
                val cleared = OeaGameRescue.clearSaveData(this, d.id)
                toast(if (cleared) "${d.title}'s data was reset." else "There was nothing saved to reset.")
                render()
            }
        }
        val standalone = OeaGameRescue.standaloneFor(d.id)
        if (standalone != null && OeaGameRescue.launchable(this, standalone)) {
            kit.row(group, R.drawable.oic_open_in_new, "Play in its own app", "${d.title} also ships as a separate app, which runs outside OEA entirely") {
                runCatching { packageManager.getLaunchIntentForPackage(standalone) }
                    .getOrNull()
                    ?.let { startActivity(it) }
                    ?: toast("That app is not installed any more.")
            }
        }
        kit.note(box, "Nothing is lost by trying these: OEA keeps your workspace, apps and settings, and the game's own save data is only cleared if you ask for it.")
    }

    // ================================================================ lobby

    private fun renderLobby() {
        val s = NetSession.current ?: return run { page = Page.Home; render() }
        val d = s.descriptor
        val p = page(d.title, if (s.isHost) "${s.roomName} · ${s.status}" else s.status)
        val box = p.content
        if (s.phase == SessionPhase.CONNECTING) {
            kit.banner(box, R.drawable.oic_refresh, "Connecting…", s.status.ifBlank { "Reaching the host" }, OeaKit.Tone.INFO)
            present(p); return
        }
        if (s.phase == SessionPhase.PLAYING) {
            kit.banner(box, R.drawable.oic_sports_esports, "Match in progress", "Return to the game to keep playing.", OeaKit.Tone.SUCCESS, "Back to game") {
                launchGame(d, lobbyMode, s.matchSettings, session = s)
            }
        }
        kit.sectionTitle(box, "Players · ${s.players.size} of ${s.maxPlayers}")
        var g = kit.group(box)
        for (pl in s.players) {
            val status = when {
                pl.isHost -> "Host"
                !pl.connected -> "Reconnecting…"
                pl.ready -> "Ready"
                else -> "Not ready"
            }
            val subtitle = buildString {
                append(if (pl.isLocal) "You" else pl.connection)
                if (!pl.isHost && pl.rttMs > 0) append(" · ${pl.rttMs} ms")
            }
            kit.row(g, R.drawable.oic_person, pl.name, subtitle, value = status, iconBg = pl.color, iconTint = Color.BLACK,
                onClick = if (s.isHost && !pl.isLocal) ({ kit.confirm("Remove ${pl.name}?", "They can join again later.", "Remove", destructive = true) { s.kick(pl.id) } }) else null)
        }
        if (s.isHost) {
            kit.sectionTitle(box, "Invite players")
            g = kit.group(box)
            val lan = s.lanInfo
            kit.row(g, R.drawable.oic_wifi, "Wi-Fi / hotspot", lan?.let { info ->
                if (info.addresses.isEmpty()) "Turn on Wi-Fi or your hotspot" else info.addresses.joinToString(" · ") { "${it.label} ${it.address.hostAddress}" } + " · port ${info.port}"
            } ?: "Tap to open this game on your network") {
                if (s.lanInfo == null) s.startLan { _, w -> if (w != null) toast(w); render(); showLanSheet() } else showLanSheet()
            }
            kit.row(g, R.drawable.oic_qr_code, "Online invite (code / QR)", inviteSummary(s)) { openInviteSheet() }
            val opts = d.options.filter { PlayMode.LAN in it.modes || PlayMode.ONLINE in it.modes }
            if (opts.isNotEmpty()) {
                kit.sectionTitle(box, "Match settings")
                g = kit.group(box)
                for (opt in opts) {
                    val cur = opt.choices.firstOrNull { it.value == lobbyOptions[opt.id] } ?: opt.choices.first { it.value == opt.default }
                    kit.row(g, R.drawable.oic_tune, opt.label, cur.description, value = cur.label) {
                        kit.choose(opt.label, null, opt.choices.map { it.label }, opt.choices.indexOf(cur), opt.choices.map { it.description }) { i ->
                            lobbyOptions[opt.id] = opt.choices[i].value
                            OeaEngine.settings.setLastOption(d.id, opt.id, opt.choices[i].value)
                            render()
                        }
                    }
                }
            }
            val enough = s.players.count { it.connected } >= maxOf(2, d.minPlayers)
            val allReady = s.players.all { it.ready || it.isHost || !it.connected }
            box.addView(kit.primaryButton(if (!enough) "Waiting for players…" else if (!allReady) "Start anyway" else "Start match", R.drawable.oic_play_arrow) {
                if (!enough) { toast("At least ${maxOf(2, d.minPlayers)} players are needed."); return@primaryButton }
                s.startMatch(lobbyOptions.toMap())
            }.apply { alpha = if (enough) 1f else 0.5f }, LinearLayout.LayoutParams(-1, kit.dp(54)).apply { topMargin = kit.dp(8) })
        } else {
            val host = s.players.firstOrNull { it.isHost }
            kit.sectionTitle(box, "Waiting for ${host?.name ?: "the host"} to start")
            g = kit.group(box)
            val me = s.localPlayer
            kit.switchRow(g, R.drawable.oic_check, "I'm ready", "Let the host know you're set", me?.ready == true) { s.setReady(it) }
            if (s.matchSettings.isNotEmpty()) {
                kit.sectionTitle(box, "Match settings")
                g = kit.group(box)
                for ((k, v) in s.matchSettings) {
                    val opt = d.options.firstOrNull { it.id == k } ?: continue
                    kit.row(g, R.drawable.oic_tune, opt.label, null, value = opt.choices.firstOrNull { it.value == v }?.label ?: v, chevron = false)
                }
            }
        }
        box.addView(kit.quietButton(if (s.isHost) "Close game" else "Leave") { onBackPressedCompat() }, LinearLayout.LayoutParams(-1, kit.dp(50)).apply { topMargin = kit.dp(10) })
        present(p)
    }

    private fun inviteSummary(s: NetSession): String {
        val invites = s.onlineInvites
        val connected = invites.count { it.status == OnlineInvite.Status.CONNECTED }
        val waiting = invites.count { it.status == OnlineInvite.Status.READY || it.status == OnlineInvite.Status.CONNECTING }
        return when {
            waiting > 0 -> "$waiting invite${if (waiting > 1) "s" else ""} waiting for a reply"
            connected > 0 -> "$connected joined online · tap to invite another"
            else -> "Make a code or QR for a friend anywhere"
        }
    }

    private fun showLanSheet() {
        val s = NetSession.current ?: return
        val info = s.lanInfo ?: return
        kit.sheet("Join on Wi-Fi / hotspot", "Friends on the same Wi-Fi or connected to your hotspot see \"${s.roomName}\" under Join nearby. If not, they can scan this.") { panel, _ ->
            panel.addView(qrView(info.joinCode, 600), LinearLayout.LayoutParams(kit.dp(240), kit.dp(240)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = kit.dp(12) })
            val addr = if (info.addresses.isEmpty()) "Not on Wi-Fi or a hotspot yet." else info.addresses.joinToString("\n") { "${it.label}: ${it.address.hostAddress}:${info.port}" }
            panel.addView(kit.text(addr, 14f, palette.text).apply { gravity = Gravity.CENTER; setTextIsSelectable(true) })
            kit.note(panel, "Tip: turn on your phone's hotspot and let friends connect to it — no router or internet needed. The game list refreshes by itself.")
            panel.addView(kit.buttonRow(kit.tonalButton("Refresh addresses") { s.refreshLanAddresses(); main.postDelayed({ render() }, 600) }))
        }
    }

    // ================================================================ online invite (host)

    private fun openInviteSheet(forceNew: Boolean = false) {
        val s = NetSession.current ?: return
        if (!s.isHost) return
        // Reopen an invite that is still waiting for its reply; a new one is for an extra player.
        val pending = if (forceNew) null else s.onlineInvites.lastOrNull {
            it.status == OnlineInvite.Status.READY || it.status == OnlineInvite.Status.CREATING || it.status == OnlineInvite.Status.CONNECTING
        }
        val invite = pending ?: s.createOnlineInvite()
        kit.sheet("Invite a friend online", "1. Share this invite.  2. Your friend sends back a reply code.  3. Enter it below.") { panel, dialog ->
            val status = kit.text(invite.status.label, 14f, palette.textSecondary).apply { setPadding(kit.dp(4), 0, kit.dp(4), kit.dp(10)) }
            panel.addView(status)
            val qrHolder = FrameLayout(this)
            panel.addView(qrHolder, LinearLayout.LayoutParams(-1, -2))
            val codeView = codeText("")
            panel.addView(codeView, LinearLayout.LayoutParams(-1, -2))
            val shareRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            panel.addView(shareRow, LinearLayout.LayoutParams(-1, -2))
            val replyField = codeField("Paste your friend's reply code (OEA-…)")
            panel.addView(replyField, LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(12) })
            val error = kit.text("", 13f, palette.danger).apply { visibility = View.GONE; setPadding(kit.dp(4), kit.dp(6), kit.dp(4), 0) }
            panel.addView(error)
            fun submit(text: String) {
                val msg = invite.acceptReply(text)
                if (msg != null) { error.text = msg; error.visibility = View.VISIBLE } else error.visibility = View.GONE
            }
            panel.addView(kit.buttonRow(
                kit.quietButton("Paste") { clipboardText()?.let { replyField.setText(it); submit(it) } ?: toast("The clipboard is empty.") },
                kit.quietButton("Scan QR") { scanQr("Scan the reply QR") { replyField.setText(it); submit(it) } },
                kit.primaryButton("Connect") { submit(replyField.text.toString()) },
            ))
            fun refresh() {
                status.text = buildString {
                    append(invite.status.label)
                    if (invite.message.isNotBlank()) append(" — ").append(invite.message)
                }
                status.setTextColor(when (invite.status) {
                    OnlineInvite.Status.FAILED -> palette.danger
                    OnlineInvite.Status.CONNECTED -> palette.success
                    else -> palette.textSecondary
                })
                val code = invite.code
                if (code != null && qrHolder.childCount == 0) {
                    qrHolder.addView(qrView(code, 720), FrameLayout.LayoutParams(kit.dp(250), kit.dp(250), Gravity.CENTER_HORIZONTAL))
                    codeView.text = groupCode(code)
                    shareRow.addView(kit.tonalButton("Copy") { copy(code) }, LinearLayout.LayoutParams(0, kit.dp(46), 1f).apply { rightMargin = kit.dp(8) })
                    shareRow.addView(kit.tonalButton("Share") { shareCode(s.descriptor, code, invite = true) }, LinearLayout.LayoutParams(0, kit.dp(46), 1f))
                }
                if (invite.status == OnlineInvite.Status.CONNECTED) main.postDelayed({ if (dialog.isShowing) dialog.dismiss() }, 1200)
                if ((invite.status == OnlineInvite.Status.FAILED || invite.status == OnlineInvite.Status.CONNECTED) && shareRow.childCount < 3) {
                    shareRow.addView(kit.primaryButton("New invite") { dialog.dismiss(); openInviteSheet(forceNew = true) }, LinearLayout.LayoutParams(0, kit.dp(46), 1f).apply { leftMargin = kit.dp(8) })
                }
                if (page == Page.Lobby) render()
            }
            invite.onChange = { refresh() }
            refresh()
            dialog.setOnDismissListener { invite.onChange = null }
        }
    }

    // ================================================================ join online

    private fun renderJoinOnline(id: String?) {
        val target = id?.let { OeaEngine.registry.find(it) }
        val p = page("Join with a code", if (target != null) target.title else "Paste or scan the invite your friend sent")
        val box = p.content
        val s = NetSession.current
        val reply = joinReply
        if (reply != null && s != null && s.role == com.oea.engine.net.session.SessionRole.CLIENT && s.phase == SessionPhase.CONNECTING) {
            kit.sectionTitle(box, "Send this reply back to ${s.descriptor.title} host")
            box.addView(qrView(reply, 720), LinearLayout.LayoutParams(kit.dp(260), kit.dp(260)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = kit.dp(10) })
            box.addView(codeText(groupCode(reply)))
            box.addView(kit.buttonRow(kit.tonalButton("Copy") { copy(reply) }, kit.primaryButton("Share") { shareCode(s.descriptor, reply, invite = false) }))
            kit.banner(box, R.drawable.oic_refresh, s.status.ifBlank { "Waiting for the host" }, "Keep this screen open. You'll be connected as soon as the host enters your reply.", OeaKit.Tone.INFO)
            box.addView(kit.quietButton("Cancel") { s.close("Cancelled"); joinReply = null; render() }, LinearLayout.LayoutParams(-1, kit.dp(48)))
            present(p); return
        }
        if (s != null && s.role == com.oea.engine.net.session.SessionRole.CLIENT && s.phase == SessionPhase.CONNECTING) {
            kit.banner(box, R.drawable.oic_refresh, "Preparing your reply code…", "Finding this phone's network routes. This takes a few seconds.", OeaKit.Tone.INFO)
        }
        joinError?.let { kit.banner(box, R.drawable.oic_warning_fill, "Can't use this code", it, OeaKit.Tone.DANGER) }
        val field = codeField("Invite code (OEA-…)").apply { setText(joinInput) }
        box.addView(field, LinearLayout.LayoutParams(-1, -2))
        box.addView(kit.buttonRow(
            kit.quietButton("Paste") { clipboardText()?.let { field.setText(it); join(id, it) } ?: toast("The clipboard is empty.") },
            kit.quietButton("Scan QR") { scanQr("Scan the invite QR") { field.setText(it); join(id, it) } },
            kit.primaryButton("Join") { join(id, field.text.toString()) },
        ), LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(6) })
        box.addView(kit.quietButton("Scan from a screenshot") { pickImage { text -> if (text == null) toast("No OEA code found in that picture.") else { field.setText(text); join(id, text) } } },
            LinearLayout.LayoutParams(-1, kit.dp(48)).apply { topMargin = kit.dp(8) })
        kit.note(box, "How it works: your friend shares an invite, you paste or scan it here and get a reply code to send back. When they enter it, the phones connect directly — on the same Wi-Fi, across mobile networks with IPv6, or through your own STUN/TURN server.")
        present(p)
    }

    private fun join(id: String?, text: String) {
        joinInput = text
        val trimmed = text.trim()
        if (trimmed.isEmpty()) { joinError = "Paste or scan a code first."; render(); return }
        if (LanJoinCode.isLanCode(trimmed)) { joinLanCode(trimmed); return }
        val gameId = try { SignalCode.decode(trimmed).gameId } catch (e: SignalCodeException) { joinError = e.message; render(); return }
        val d = OeaEngine.registry.find(gameId) ?: run { joinError = "This invite is for a game that isn't installed ($gameId). Update OEA."; render(); return }
        if (id != null && id != gameId) { joinError = "This invite is for ${d.title}, not ${OeaEngine.registry.find(id)?.title}."; render(); return }
        joinError = null
        val s = NetSession.joinOnline(this, d, trimmed) { code, err ->
            if (err != null) { joinError = err; joinReply = null } else joinReply = code
            render()
        } ?: return
        s.addListener(this)
        lobbyMode = PlayMode.ONLINE
        render()
    }

    // ================================================================ nearby (LAN)

    private fun startBrowsing(gameId: String?) {
        browser?.stop()
        nearby = emptyList()
        browser = LanBrowser(this, gameId) { list -> nearby = list; if (page is Page.Nearby) render() }.also { it.start() }
    }

    private fun renderNearby(id: String?) {
        val d = id?.let { OeaEngine.registry.find(it) }
        val p = page("Join nearby", if (d != null) "${d.title} games on this Wi-Fi or hotspot" else "Games on this Wi-Fi or hotspot")
        val box = p.content
        val s = NetSession.current
        if (s != null && s.role == com.oea.engine.net.session.SessionRole.CLIENT && s.phase == SessionPhase.CONNECTING) {
            kit.banner(box, R.drawable.oic_refresh, "Connecting…", s.status, OeaKit.Tone.INFO)
        }
        kit.sectionTitle(box, if (nearby.isEmpty()) "Looking for games…" else "Found ${nearby.size}")
        val g = kit.group(box)
        if (nearby.isEmpty()) kit.row(g, R.drawable.oic_wifi, "Nothing yet", "Ask your friend to host on Wi-Fi / hotspot. Both phones must be on the same network (or yours connected to their hotspot).", chevron = false)
        for (game in nearby) {
            val desc = OeaEngine.registry.find(game.gameId)
            val subtitle = buildString {
                append(desc?.title ?: game.gameId)
                append(" · ").append(game.hostName)
                if (game.maxPlayers > 0) append(" · ${game.players}/${game.maxPlayers}")
                if (!game.inLobby) append(" · match in progress")
                if (!game.compatible) append(" · different OEA version")
            }
            kit.row(g, R.drawable.oic_sports_esports, game.roomName, subtitle) {
                when {
                    desc == null -> toast("This game isn't installed on this phone.")
                    !game.compatible -> toast("The host uses a different OEA version. Update OEA on both phones.")
                    game.isFull && game.maxPlayers > 0 -> toast("That game is full.")
                    else -> {
                        val session = NetSession.joinLan(this, desc, game.address, game.port, this)
                        lobbyMode = PlayMode.LAN
                        render()
                        @Suppress("UNUSED_VARIABLE") val unused = session
                    }
                }
            }
        }
        kit.sectionTitle(box, "Can't see it?")
        val g2 = kit.group(box)
        kit.row(g2, R.drawable.oic_qr_code, "Scan the host's Wi-Fi QR", "The host finds it in the lobby under Wi-Fi / hotspot") { scanQr("Scan the host's QR") { joinLanCode(it) } }
        kit.row(g2, R.drawable.oic_edit, "Enter the address", "For example 192.168.43.1:47812") {
            kit.prompt("Host address", "Shown in the host's lobby", "IP:port", "") { text ->
                val parts = text.trim().split(':')
                val port = parts.getOrNull(1)?.toIntOrNull() ?: com.oea.engine.net.lan.LanServer.DEFAULT_PORT
                val target = d ?: OeaEngine.registry.all().firstOrNull { it.isMultiplayer } ?: return@prompt "No multiplayer game available"
                if (parts[0].isBlank()) return@prompt "Enter an address"
                NetSession.joinLan(this, d ?: target, parts[0], port, this)
                lobbyMode = PlayMode.LAN
                null
            }
        }
        kit.note(box, "Discovery uses local broadcast and mDNS. Some public or office Wi-Fi networks block devices from seeing each other; a phone hotspot always works.")
        present(p)
    }

    private fun joinLanCode(text: String) {
        val target = LanJoinCode.decode(text).getOrElse { toast(it.message ?: "Invalid code"); return }
        val d = OeaEngine.registry.find(target.gameId) ?: run { toast("That game isn't installed."); return }
        NetSession.joinLanAny(this, d, target.addresses, target.port, this)
        lobbyMode = PlayMode.LAN
        render()
    }

    // ================================================================ session callbacks

    override fun onSessionUpdate(session: NetSession) {
        if (session.role == com.oea.engine.net.session.SessionRole.CLIENT && session.phase == SessionPhase.LOBBY && page != Page.Lobby) {
            browser?.stop(); browser = null
            joinReply = null
            navigate(Page.Lobby)
            return
        }
        if (page == Page.Lobby || page is Page.JoinOnline || page is Page.Nearby) render()
    }

    override fun onMatchStart(session: NetSession) {
        val d = session.descriptor
        launchGame(d, lobbyMode, session.matchSettings, session = session)
    }

    override fun onSessionClosed(session: NetSession, reason: String) {
        if (reason.isNotBlank() && reason != "Left the game" && reason != "Cancelled") toast(reason)
        if (page == Page.Lobby) { history.clear(); page = Page.Home }
        if (page is Page.JoinOnline && joinReply != null) { joinReply = null; joinError = reason }
        render()
    }

    // ================================================================ settings pages

    private fun qualityLabel(): String {
        val q = OeaEngine.settings.quality
        return if (q == "auto") "Auto (${OeaEngine.device.recommendedTier.label})" else QualityTier.parse(q)?.label ?: q
    }

    private fun fpsLabel(): String = OeaEngine.settings.fpsTarget.let { if (it == 0) "max ${OeaEngine.device.maxRefreshRate.toInt()} fps" else "$it fps" }

    private fun serversSummary(): String {
        val list = OeaEngine.settings.iceServers
        return if (list.isEmpty()) "None (direct connections only)" else "${list.size} of your own server${if (list.size > 1) "s" else ""}"
    }

    private fun islandSummary(): String {
        if (!OeaEngine.settings.islandInGames) return "Off inside games"
        return when (OeaDataStore.get(this).islandStyle()) {
            OeaDataStore.ISLAND_CAMERA -> "Camera island"
            OeaDataStore.ISLAND_BOTH -> "Card and camera island"
            else -> "Card"
        }
    }

    private fun choiceRow(group: LinearLayout, icon: Int, title: String, options: List<Pair<String, String>>, selected: String, descriptions: List<String?> = emptyList(), onPick: (String) -> Unit) {
        val index = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
        kit.row(group, icon, title, null, value = options[index].second) {
            kit.choose(title, null, options.map { it.second }, index, descriptions) { pick -> onPick(options[pick].first); render() }
        }
    }

    private fun renderGraphics() {
        val s = OeaEngine.settings
        val device = OeaEngine.device
        val p = page("Graphics & performance", "OEA pushes your phone to its maximum: highest refresh rate, dynamic resolution and supersampling when there is headroom.")
        val box = p.content
        kit.banner(box, R.drawable.oic_memory, "This phone: ${device.recommendedTier.label} tier", device.describe(), OeaKit.Tone.INFO)
        var g = kit.group(box)
        choiceRow(g, R.drawable.oic_auto_awesome, "Quality", listOf("auto" to "Auto (${device.recommendedTier.label})", "low" to "Low", "medium" to "Medium", "high" to "High", "ultra" to "Ultra"), s.quality,
            listOf("Measured for this phone", "Fastest, longest battery", "Balanced", "Shadows and bloom", "Supersampling, 2K shadows — maximum image quality")) { s.quality = it }
        val rates = (listOf(0) + device.refreshRates.map { it.toInt() }.filter { it >= 30 }.distinct().sortedDescending() + listOf(60, 30)).distinct()
        choiceRow(g, R.drawable.oic_rocket_launch, "Frame rate", rates.map { it.toString() to if (it == 0) "Maximum (${device.maxRefreshRate.toInt()} Hz)" else "$it fps" }, s.fpsTarget.toString()) { s.fpsTarget = it.toInt() }
        kit.switchRow(g, R.drawable.oic_tune, "Adaptive resolution", "Lowers or raises rendering resolution to hold the frame rate", s.adaptiveResolution) { s.adaptiveResolution = it }
        kit.switchRow(g, R.drawable.oic_insights, "Show FPS counter", "Frame rate, frame time, resolution scale and draw calls", s.showFps) { s.showFps = it }
        kit.switchRow(g, R.drawable.oic_ac_unit, "Stable performance", "Android's sustained mode: steadier and cooler, lower peaks", s.sustainedPerformance) { s.sustainedPerformance = it }
        g = kit.group(box)
        kit.switchRow(g, R.drawable.oic_touch_app, "Haptics", null, s.haptics) { s.haptics = it }
        kit.switchRow(g, R.drawable.oic_music_note, "Sound effects", null, s.sound) { s.sound = it }
        kit.switchRow(g, R.drawable.oic_headset, "Music", null, s.music) { s.music = it }
        kit.stepperRow(g, R.drawable.oic_gesture, "Aim sensitivity", "Arena and other shooters", (s.lookSensitivity * 10f).toInt(), 2, 30, { String.format(java.util.Locale.US, "%.1f×", it / 10f) }) { s.lookSensitivity = it / 10f }
        kit.note(box, "Thermal protection steps quality down before your phone throttles, and Android's Game Mode (battery or performance) is respected. Changes apply the next time a game starts.")
        present(p)
    }

    private fun renderServers() {
        val s = OeaEngine.settings
        val p = page("Online connection servers", "Optional. OEA never contacts any server by itself.")
        val box = p.content
        kit.note(box, "Online play connects the two phones directly. On many networks (same Wi-Fi, or IPv6 mobile networks) that just works. Behind strict carrier NAT a direct connection can be impossible — then add your own STUN or TURN server (for example coturn on a home server or VPS). No account, free tier or third-party service is involved unless you add one here.")
        kit.sectionTitle(box, "Your servers")
        val g = kit.group(box)
        val list = s.iceServers
        if (list.isEmpty()) kit.row(g, R.drawable.oic_public, "None", "Direct connections only", chevron = false)
        for (srv in list) {
            kit.row(g, R.drawable.oic_public, srv.url, if (srv.isTurn) "TURN relay${if (srv.username.isNotEmpty()) " · ${srv.username}" else ""}" else "STUN") {
                kit.confirm("Remove server?", srv.url, "Remove", destructive = true) { s.iceServers = s.iceServers - srv; render() }
            }
        }
        kit.row(g, R.drawable.oic_add, "Add a server", "stun:host:3478  or  turn:host:3478|user|password") {
            kit.prompt("Add a server", "One line: URL, or URL|username|password", "turn:my.server.net:3478|user|secret", "") { text ->
                val parsed = IceServerConfig.parseList(text)
                if (parsed.isEmpty()) return@prompt "Use stun:host:port or turn:host:port|user|password"
                s.iceServers = (s.iceServers + parsed).distinct()
                render()
                null
            }
        }
        kit.sectionTitle(box, "Check")
        val g2 = kit.group(box)
        kit.row(g2, R.drawable.oic_insights, "Check online readiness", probeResult ?: "Finds this phone's network routes and explains what they mean") {
            probeResult = "Checking…"
            render()
            RtcProbe.run(this, s.iceServers) { report -> probeResult = report.summary(); if (page == Page.Servers) render() }
        }
        kit.note(box, "Minimal coturn example (on your own machine):\nlistening-port=3478\nrealm=oea\nuser=player:secret\nlt-cred-mech\nThen add  turn:YOUR-ADDRESS:3478|player|secret  above.")
        present(p)
    }

    private fun renderIsland() {
        val s = OeaEngine.settings
        val store = OeaDataStore.get(this)
        val p = page("Dynamic Island in games", "Games use your Home island styles: the card under the status bar, the island around the camera, or both.")
        val box = p.content
        val style = when (store.islandStyle()) { OeaDataStore.ISLAND_CAMERA -> "Island around the camera"; OeaDataStore.ISLAND_BOTH -> "Both styles"; else -> "Card under the status bar" }
        kit.banner(box, R.drawable.oic_music_note, "Current style: $style", if (store.dynamicIslandEnabled()) "Change it in OEA Settings › Notifications › Live activity area." else "The Dynamic Island is turned off in OEA Settings.",
            OeaKit.Tone.INFO, "Open settings") {
            runCatching { startActivity(Intent(this, OeaSystemToolsActivity::class.java).putExtra(OeaSystemToolsActivity.EXTRA_SCREEN, "notifications")) }
        }
        var g = kit.group(box)
        kit.switchRow(g, R.drawable.oic_sports_esports, "Island inside games", "Game events, calls and music over the game", s.islandInGames) { s.islandInGames = it }
        kit.switchRow(g, R.drawable.oic_music_note, "Music in games", "Show what's playing (compact) while you play", s.islandMediaInGames) { s.islandMediaInGames = it }
        kit.sectionTitle(box, "Where each game appears")
        if (store.islandStyle() != OeaDataStore.ISLAND_BOTH) kit.note(box, "With one style enabled, every game uses it. Turn on both styles to assign games to each, manually or automatically.")
        g = kit.group(box)
        for (d in OeaEngine.registry.all()) {
            val current = s.islandPlacement(d.id)
            choiceRow(g, R.drawable.oic_view_agenda, d.title, IslandPlacement.entries.map { it.name to it.label }, current.name,
                IslandPlacement.entries.map { if (it == IslandPlacement.AUTO) "${it.description} (${d.title} prefers the ${d.preferredIsland.label.lowercase()})" else it.description }) {
                s.setIslandPlacement(d.id, IslandPlacement.valueOf(it))
            }
        }
        present(p)
    }

    private fun renderProfile() {
        val s = OeaEngine.settings
        val p = page("Player profile", "How other players see you. Stored only on this phone.")
        val box = p.content
        val g = kit.group(box)
        kit.row(g, R.drawable.oic_person, "Name", s.playerName) {
            kit.prompt("Your name", "Up to ${EngineSettings.MAX_NAME} characters", "Name", s.playerName) { v ->
                if (v.isBlank()) return@prompt "Enter a name"
                s.playerName = v
                render()
                null
            }
        }
        kit.sectionTitle(box, "Colour")
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(kit.dp(4), kit.dp(4), kit.dp(4), kit.dp(12)) }
        for (c in EngineSettings.PLAYER_COLORS) {
            val selected = c == s.playerColor
            row.addView(View(this).apply {
                background = kit.oval(c, if (selected) palette.text else Color.TRANSPARENT, if (selected) 3 else 0)
                setOnClickListener { s.playerColor = c; render() }
            }, LinearLayout.LayoutParams(kit.dp(40), kit.dp(40)).apply { rightMargin = kit.dp(10) })
        }
        box.addView(kit.hscroll(row))
        kit.note(box, "Your colour tints your runner, soldier and lobby badge. If a friend already uses it, the host picks a free one for you.")
        present(p)
    }

    // ================================================================ codes, QR, clipboard

    private fun groupCode(code: String): String = "OEA-" + com.oea.engine.net.Codes.group(code.removePrefix("OEA-"), 5)

    private fun codeText(text: String): TextView = kit.text(text, 13f, palette.text).apply {
        typeface = Typeface.MONOSPACE
        setTextIsSelectable(true)
        background = kit.rounded(palette.cardHigh, 16)
        setPadding(kit.dp(14), kit.dp(12), kit.dp(14), kit.dp(12))
        setLineSpacing(kit.dpf(2f), 1f)
    }

    private fun codeField(hint: String): EditText = kit.field(hint).apply {
        setSingleLine(false)
        maxLines = 5
        typeface = Typeface.MONOSPACE
        textSize = 13f
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
    }

    private fun qrView(text: String, px: Int): View = FrameLayout(this).apply {
        background = kit.rounded(Color.WHITE, 22)
        setPadding(kit.dp(10), kit.dp(10), kit.dp(10), kit.dp(10))
        val bmp: Bitmap? = runCatching { QrCodes.encode(text, px) }.getOrNull()
        addView(ImageView(this@OeaGamesActivity).apply {
            setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.FIT_CENTER
            // Crisp modules when scaled.
            drawable?.let { (it as? android.graphics.drawable.BitmapDrawable)?.paint?.isFilterBitmap = false }
            contentDescription = "QR code"
        }, FrameLayout.LayoutParams(-1, -1))
    }

    private fun copy(code: String) {
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("OEA code", code))
        toast("Copied. Paste it into any chat app.")
    }

    private fun clipboardText(): String? = runCatching {
        getSystemService(ClipboardManager::class.java)?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun shareCode(d: GameDescriptor, code: String, invite: Boolean) {
        val text = if (invite) "Join my ${d.title} game in OEA! Open OEA Games › Join with a code, paste this, then send me the reply code:\n\n$code"
        else "Here's my reply for ${d.title} — paste it in your invite screen (Connect):\n\n$code"
        runCatching { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share code")) }
    }

    /** Full-screen camera scanner for OEA codes. */
    private fun scanQr(title: String, onResult: (String) -> Unit) {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            pendingCamera = { scanQr(title, onResult) }
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
            return
        }
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = FrameLayout(this)
        val scanner = QrScannerView(this)
        scanner.accept = { it.contains("OEA", ignoreCase = true) }
        root.addView(scanner, FrameLayout.LayoutParams(-1, -1))
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(kit.dp(16), kit.dp(36), kit.dp(16), kit.dp(12))
        }
        top.addView(kit.circleIconButton(R.drawable.oic_close, "Close", 0x66000000, Color.WHITE) { dialog.dismiss() }, LinearLayout.LayoutParams(kit.dp(44), kit.dp(44)))
        top.addView(kit.text(title, 18f, Color.WHITE, OeaType.medium).apply { setPadding(kit.dp(14), 0, 0, 0) })
        root.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(kit.dp(20), kit.dp(12), kit.dp(20), kit.dp(40)) }
        bottom.addView(kit.quietButton("Torch") { scanner.setTorch(!scanner.torch) }, LinearLayout.LayoutParams(0, kit.dp(48), 1f).apply { rightMargin = kit.dp(10) })
        bottom.addView(kit.quietButton("From picture") { dialog.dismiss(); pickImage { t -> if (t != null) onResult(t) else toast("No OEA code found in that picture.") } }, LinearLayout.LayoutParams(0, kit.dp(48), 1f))
        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        scanner.onResult = { text -> dialog.dismiss(); onResult(text) }
        scanner.onError = { msg -> toast(msg) }
        dialog.setContentView(root)
        dialog.setOnShowListener { scanner.start() }
        dialog.setOnDismissListener { scanner.stop() }
        dialog.show()
    }

    private fun pickImage(onResult: (String?) -> Unit) {
        pendingImage = onResult
        runCatching { startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), REQ_IMAGE) }
            .onFailure { pendingImage = null; toast("No picture picker available.") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_IMAGE) return
        val cb = pendingImage ?: return
        pendingImage = null
        val uri = data?.data ?: return
        com.oea.engine.core.OeaGuard.start("oea-qr-decode", "oea-games:qr") {
            val text = runCatching {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                var sample = 1
                while (maxOf(opts.outWidth, opts.outHeight) / sample > 2400) sample *= 2
                val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
                bmp?.let { QrCodes.decode(it) }
            }.getOrNull()
            main.post { cb(text) }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA) {
            val cb = pendingCamera
            pendingCamera = null
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) cb?.invoke()
            else toast("Camera permission is needed to scan codes. You can paste codes instead.")
        }
    }

    private companion object {
        const val REQ_CAMERA = 41
        const val REQ_IMAGE = 42
    }
}
