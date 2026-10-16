package com.oea.games

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
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

/**
 * The heart of every standalone OEA game app. Each game subclasses this once
 * with a fixed game id; the app is then complete on its own:
 *
 *  - a front menu (Play, Match setup, Play with friends, Controls & aim, About);
 *  - match setup over the game's own options, remembered per game;
 *  - look / ADS sensitivity, invert-Y and a turn-speed limit, tuned live;
 *  - its own multiplayer: host on Wi-Fi or with an online code, join nearby
 *    games or with a code — no launcher, no hub required;
 *  - direct launch with extras (from the OEA Games hub or shortcuts).
 *
 * Nothing here depends on `com.oea.launcher`.
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

    // Multiplayer state (one session at a time, like the engine itself).
    private var browser: LanBrowser? = null
    private var nearbyDialog: AlertDialog? = null
    private var lobbyDialog: AlertDialog? = null
    private var lobbyStatus: TextView? = null
    private var onlineInvite: OnlineInvite? = null
    private var inviteDialog: AlertDialog? = null
    private var lobbyMode = PlayMode.LAN
    private var pendingStart = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            prepare()
        } catch (t: Throwable) {
            try { Toast.makeText(this, "Game could not start: ${t.javaClass.simpleName}", Toast.LENGTH_LONG).show() } catch (_: Throwable) {}
            try { finish() } catch (_: Throwable) {}
        }
    }

    private fun prepare() {
        try { OeaEngine.init(applicationContext) } catch (_: Throwable) {}
        OeaGames.registerBuiltIns(applicationContext)
        val id = intent.getStringExtra(EXTRA_GAME_ID)?.takeIf { it.isNotBlank() } ?: standaloneGameId
        val descriptor = try { OeaEngine.registry.find(id) } catch (_: Throwable) { null }
        if (descriptor == null) {
            Toast.makeText(this, "This game isn't available in this build.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        this.descriptor = descriptor
        descriptor.options.forEach { opt ->
            chosenOptions[opt.id] = OeaEngine.settings.lastOption(descriptor.id, opt.id)?.takeIf { v -> opt.choices.any { it.value == v } } ?: opt.default
        }
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                onBackInvokedDispatcher.registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                ) { handleBack() }
            } catch (_: Throwable) {}
        }
        // Direct launch (hub, shortcut, automation): start the match at once.
        if (intent.hasExtra(EXTRA_GAME_ID) || intent.hasExtra(EXTRA_GAME_SEED) || intent.hasExtra(EXTRA_AUTO_START)) {
            directLaunch = true
            val mode = runCatching { PlayMode.valueOf(intent.getStringExtra(EXTRA_GAME_MODE) ?: "SOLO") }.getOrDefault(PlayMode.SOLO)
            val options = LinkedHashMap(chosenOptions)
            try {
                intent.getBundleExtra(EXTRA_GAME_OPTIONS)?.let { bundle ->
                    bundle.keySet().forEach { key -> bundle.getString(key)?.let { options[key] = it } }
                }
            } catch (_: Throwable) {}
            val players = parsePlayers(intent.getStringExtra(EXTRA_GAME_PLAYERS), descriptor)
            val session = NetSession.current?.takeIf { it.descriptor.id == descriptor.id && it.phase == SessionPhase.PLAYING }
            beginMatch(mode, options, intent.getLongExtra(EXTRA_GAME_SEED, System.nanoTime()), players, session)
        } else {
            showFrontMenu()
        }
    }

    private fun parsePlayers(csv: String?, d: GameDescriptor): List<LocalPlayer> {
        val names = (csv ?: "").split(';').map { it.trim() }.filter { it.isNotEmpty() }
        if (names.isEmpty() || d.maxPlayers <= 1) return listOf(LocalPlayer(OeaEngine.settings.playerName, OeaEngine.settings.playerColor))
        val colors = com.oea.engine.EngineSettings.PLAYER_COLORS
        return names.take(4).mapIndexed { i, n -> LocalPlayer(n, colors[i % colors.size]) }
    }

    // ================================================================ front menu

    private fun showFrontMenu() {
        val d = descriptor ?: return
        val items = arrayOf(
            "Play",
            "Match setup",
            if (d.maxPlayers > 1) "Play with friends" else null,
            "Controls & aim",
            "About ${d.title}",
        ).filterNotNull().toTypedArray()
        val builder = AlertDialog.Builder(this)
            .setTitle(d.title)
            .setMessage(d.tagline)
            .setItems(items) { _, which ->
                when (items[which]) {
                    "Play" -> beginMatch(PlayMode.SOLO, chosenOptions, System.nanoTime(), parsePlayers(null, d), null)
                    "Match setup" -> showMatchSetup()
                    "Play with friends" -> showFriendsMenu()
                    "Controls & aim" -> showControlsDialog()
                    else -> AlertDialog.Builder(this).setTitle(d.title).setMessage(d.description)
                        .setPositiveButton("Back") { _, _ -> showFrontMenu() }
                        .setOnCancelListener { showFrontMenu() }
                        .show()
                }
            }
            .setCancelable(true)
        builder.setOnCancelListener { finish() }
        builder.show()
    }

    /** Match setup: one screen per option, values remembered for the next match. */
    private fun showMatchSetup() {
        val d = descriptor ?: return
        val labels = d.options.map { opt ->
            val v = chosenOptions[opt.id] ?: opt.default
            "${opt.label} · ${opt.choices.firstOrNull { it.value == v }?.label ?: v}"
        }.toTypedArray()
        val builder = AlertDialog.Builder(this)
            .setTitle("Match setup")
            .setItems(labels) { _, which ->
                val opt = d.options[which]
                val names = opt.choices.map { c -> c.description?.let { "${c.label} — $it" } ?: c.label }.toTypedArray()
                val current = opt.choices.indexOfFirst { it.value == (chosenOptions[opt.id] ?: opt.default) }.coerceAtLeast(0)
                AlertDialog.Builder(this)
                    .setTitle(opt.label)
                    .setSingleChoiceItems(names, current) { dialog, pick ->
                        chosenOptions[opt.id] = opt.choices[pick].value
                        OeaEngine.settings.setLastOption(d.id, opt.id, opt.choices[pick].value)
                        dialog.dismiss()
                        showMatchSetup()
                    }
                    .setNegativeButton("Back") { _, _ -> showMatchSetup() }
                    .setOnCancelListener { showMatchSetup() }
                    .show()
            }
            .setNegativeButton("Back") { _, _ -> showFrontMenu() }
            .setOnCancelListener { showFrontMenu() }
        builder.show()
    }

    // ================================================================ controls & aim

    private fun showControlsDialog() {
        val settings = OeaEngine.settings
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad)
        }

        fun slider(label: String, value: Float, min: Float, max: Float, onDone: (Float) -> Unit) {
            val text = TextView(this)
            val seek = SeekBar(this)
            val span = (max - min).coerceAtLeast(0.01f)
            seek.max = 100
            seek.progress = (((value - min) / span) * 100f).toInt().coerceIn(0, 100)
            fun refresh() {
                val v = min + span * (seek.progress / 100f)
                text.text = String.format(java.util.Locale.US, "%s · %.2f", label, v)
                onDone(v)
            }
            text.setPadding(0, (10 * resources.displayMetrics.density).toInt(), 0, 0)
            panel.addView(text, LinearLayout.LayoutParams(-1, -2))
            panel.addView(seek, LinearLayout.LayoutParams(-1, -2))
            refresh()
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) = refresh()
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }

        slider("Look sensitivity", settings.lookSensitivity, 0.2f, 3f) { settings.lookSensitivity = it }
        slider("ADS sensitivity", settings.adsSensitivity, 0.3f, 1.5f) { settings.adsSensitivity = it }
        slider("Turn limit (0 = off, °/s)", settings.turnSpeedLimit, 0f, 360f) { settings.turnSpeedLimit = it }
        val invert = CheckBox(this).apply {
            text = "Invert vertical look"
            isChecked = settings.invertLookY
            setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
            setOnCheckedChangeListener { _, checked -> settings.invertLookY = checked }
        }
        panel.addView(invert, LinearLayout.LayoutParams(-1, -2))

        AlertDialog.Builder(this)
            .setTitle("Controls & aim")
            .setView(panel)
            .setPositiveButton("Done") { _, _ -> showFrontMenu() }
            .setOnCancelListener { showFrontMenu() }
            .show()
    }

    // ================================================================ play with friends

    private fun showFriendsMenu() {
        val d = descriptor ?: return
        val items = arrayOf(
            "Host on Wi-Fi / hotspot",
            "Host online with a code",
            "Join nearby",
            "Join with a code",
        )
        AlertDialog.Builder(this)
            .setTitle("Play with friends")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> hostSession(lan = true)
                    1 -> hostSession(lan = false)
                    2 -> showNearbyBrowser()
                    3 -> promptJoinCode()
                }
            }
            .setNegativeButton("Back") { _, _ -> showFrontMenu() }
            .setOnCancelListener { showFrontMenu() }
            .show()
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
            onlineInvite = invite
            showLobby(joined = false, extra = "Creating your invite…")
            showInviteSheet(invite)
        }
    }

    private fun showInviteSheet(invite: OnlineInvite) {
        inviteDialog?.dismiss()
        val status = TextView(this)
        val codeView = TextView(this).apply {
            setTextIsSelectable(true)
            textSize = 14f
            setPadding(0, 12, 0, 12)
        }
        val reply = EditText(this).apply { hint = "Paste your friend's reply code (OEA-…)" }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad)
            addView(status)
            addView(codeView)
            addView(reply, LinearLayout.LayoutParams(-1, -2))
        }
        fun refresh() {
            status.text = invite.status.label + (if (invite.message.isNotBlank()) " — ${invite.message}" else "")
            val code = invite.code
            if (code != null && codeView.text.isEmpty()) codeView.text = "Invite code (share this):\n$code"
        }
        invite.onChange = { main.post { refresh() } }
        refresh()
        val dialog = AlertDialog.Builder(this)
            .setTitle("Invite a friend online")
            .setView(panel)
            .setPositiveButton("Connect") { _, _ -> invite.acceptReply(reply.text.toString())?.let { toast(it) } }
            .setNeutralButton("Share invite") { _, _ ->
                invite.code?.let { share(it, "OEA invite code") }
            }
            .setNegativeButton("Close", null)
            .setOnDismissListener { invite.onChange = null }
            .show()
        inviteDialog = dialog
    }

    private fun showNearbyBrowser() {
        val d = descriptor ?: return
        toast("Looking for games on this network…")
        browser?.stop()
        browser = LanBrowser(this, d.id) { games -> main.post { presentNearby(games) } }.also { it.start() }
    }

    private fun presentNearby(games: List<LanGame>) {
        if (isFinishing) return
        val open = games.filter { it.compatible && !it.isFull && it.inLobby }
        if (open.isEmpty()) return
        // Beacons arrive continuously: one picker at a time, never stacked.
        if (nearbyDialog?.isShowing == true) return
        val names = open.map { "${it.roomName} · ${it.players}/${it.maxPlayers} · ${it.hostName}" }.toTypedArray()
        val dialog = AlertDialog.Builder(this)
            .setTitle("Join nearby")
            .setItems(names) { _, which ->
                val g = open[which]
                browser?.stop(); browser = null
                val s = NetSession.joinLan(this, descriptor ?: return@setItems, g.address, g.port, lobbyListener)
                lobbyMode = PlayMode.LAN
                showLobby(joined = true, extra = "Connecting to ${g.roomName}…")
                s.setReady(true)
            }
            .setNegativeButton("Cancel") { _, _ -> browser?.stop(); browser = null; showFrontMenu() }
            .setOnCancelListener { browser?.stop(); browser = null; showFrontMenu() }
            .show()
        nearbyDialog = dialog
    }

    private fun promptJoinCode() {
        val input = EditText(this).apply { hint = "OEA-… invite, reply or Wi-Fi code" }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad)
            addView(input, LinearLayout.LayoutParams(-1, -2))
        }
        AlertDialog.Builder(this)
            .setTitle("Join with a code")
            .setView(panel)
            .setPositiveButton("Join") { _, _ -> joinWithCode(input.text.toString()) }
            .setNegativeButton("Back") { _, _ -> showFrontMenu() }
            .setOnCancelListener { showFrontMenu() }
            .show()
    }

    private fun joinWithCode(text: String) {
        val d = descriptor ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) { toast("Paste a code first."); promptJoinCode(); return }
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
                main.post { showLobby(joined = true, extra = err) }
            } else if (code != null) {
                main.post {
                    lobbyDialog?.setMessage("Send this reply code back to the host:\n\n$code")
                }
            }
        }
        if (s != null) {
            lobbyMode = PlayMode.ONLINE
            showLobby(joined = true, extra = "Preparing your reply…")
            s.setReady(true)
        }
    }

    /** One compact lobby dialog for hosting and joining; it updates itself. */
    private fun showLobby(joined: Boolean, extra: String?) {
        val s = NetSession.current ?: return
        s.removeListener(lobbyListener)
        s.addListener(lobbyListener)
        lobbyDialog?.dismiss()
        val status = TextView(this).apply { setPadding(0, 8, 0, 8); textSize = 14f }
        lobbyStatus = status
        val builder = AlertDialog.Builder(this)
            .setTitle(s.roomName.ifBlank { "Lobby" })
            .setView(status)
            .setNegativeButton(if (joined) "Leave" else "Cancel") { _, _ ->
                lobbyDialog = null
                s.close("Left the lobby")
                showFrontMenu()
            }
        if (!joined && s.isHost) {
            builder.setPositiveButton("Start match") { _, _ ->
                val settings = LinkedHashMap(chosenOptions)
                s.startMatch(settings)
            }
        }
        builder.setOnCancelListener {
            lobbyDialog = null
            if (s.phase == SessionPhase.LOBBY || s.phase == SessionPhase.CONNECTING) {
                s.close("Left the lobby")
            }
            showFrontMenu()
        }
        val dialog = builder.show()
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
            main.post {
                if (lobbyDialog?.isShowing == true) refreshLobby()
            }
        }

        override fun onMatchStart(session: NetSession) {
            main.post {
                lobbyDialog?.dismiss(); lobbyDialog = null
                inviteDialog?.dismiss(); inviteDialog = null
                pendingStart = true
                beginMatch(lobbyMode, LinkedHashMap(chosenOptions), session.seed, parsePlayers(null, session.descriptor), session)
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
                    showFrontMenu()
                }
            }
        }
    }

    // ================================================================ running the match

    private fun beginMatch(mode: PlayMode, options: Map<String, String>, seed: Long, players: List<LocalPlayer>, session: NetSession?) {
        val d = descriptor ?: return
        if (!d.supports(mode)) {
            Toast.makeText(this, "This build doesn't support ${mode.label}.", Toast.LENGTH_LONG).show()
            showFrontMenu()
            return
        }
        val problem = try { OeaEngine.surfaceProblem() } catch (e: Throwable) {
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
            showFrontMenu()
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
    }

    // ================================================================ activity plumbing

    override fun onResume() {
        super.onResume()
        try { host?.onResume() } catch (_: Throwable) {}
    }

    override fun onPause() {
        try { host?.onPause() } catch (_: Throwable) {}
        super.onPause()
    }

    override fun onDestroy() {
        try { browser?.stop() } catch (_: Throwable) {}
        browser = null
        try { host?.onDestroy() } catch (_: Throwable) {}
        host = null
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        try { host?.onWindowFocusChanged(hasFocus) } catch (_: Throwable) {}
    }

    @Deprecated("Back is routed to the game first")
    override fun onBackPressed() = handleBack()

    private fun handleBack() {
        val currentHost = host
        if (currentHost == null) {
            // In the front menu, Back leaves the app instead of looping dialogs.
            lobbyDialog?.let { if (it.isShowing) { it.getButton(AlertDialog.BUTTON_NEGATIVE)?.performClick(); return } }
            inviteDialog?.let { if (it.isShowing) { it.dismiss(); return } }
            finish()
            return
        }
        try {
            currentHost.onBackPressed {
                confirm("Leave the game?", "Your progress in this round will be lost.", "Leave", "Stay") { leave ->
                    if (leave) {
                        host = null
                        runCatching { currentHost.onDestroy() }
                        setContentView(LinearLayout(this))
                        if (directLaunch) finish() else showFrontMenu()
                    }
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

    override fun toast(message: String) = runCatching { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }.let { }

    override fun share(text: String, title: String) {
        runCatching {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), title))
        }.onFailure { toast("Nothing can share text on this phone.") }
    }

    override fun menu(title: String, subtitle: String?, items: List<MenuItem>, onPick: (String?) -> Unit) {
        try {
            val labels = items.map { it.label }.toTypedArray()
            val builder = AlertDialog.Builder(this).setTitle(title).setItems(labels) { _, which -> onPick(items[which].id) }
            if (!subtitle.isNullOrBlank()) builder.setMessage(subtitle)
            builder.setOnCancelListener { onPick(null) }
            builder.show()
        } catch (_: Throwable) { onPick(null) }
    }

    override fun confirm(title: String, message: String, positive: String, negative: String, onResult: (Boolean) -> Unit) {
        try {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(positive) { _, _ -> onResult(true) }
                .setNegativeButton(negative) { _, _ -> onResult(false) }
                .setOnCancelListener { onResult(false) }
                .show()
        } catch (_: Throwable) { onResult(false) }
    }

    override fun prompt(title: String, hint: String, initial: String, onResult: (String?) -> Unit) {
        try {
            val input = EditText(this).apply {
                this.hint = hint
                setText(initial)
                setSelection(text.length)
            }
            AlertDialog.Builder(this)
                .setTitle(title)
                .setView(input)
                .setPositiveButton("OK") { _, _ -> onResult(input.text?.toString()) }
                .setNegativeButton("Cancel") { _, _ -> onResult(null) }
                .setOnCancelListener { onResult(null) }
                .show()
        } catch (_: Throwable) { onResult(null) }
    }

    override fun exit(result: GameResult?) {
        val currentHost = host ?: run { finish(); return }
        host = null
        runCatching { currentHost.onDestroy() }
        runCatching { setContentView(LinearLayout(this)) }
        pendingStart = false
        if (isFinishing || directLaunch) {
            result?.summary?.let { toast(it) }
            finish()
            return
        }
        result?.summary?.let { toast(it) }
        showFrontMenu()
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
