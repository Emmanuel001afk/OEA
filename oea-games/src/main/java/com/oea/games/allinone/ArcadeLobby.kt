package com.oea.games.allinone

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.oea.engine.OeaEngine
import com.oea.engine.core.GameDescriptor
import com.oea.engine.core.GameDimension
import com.oea.engine.core.GameOrientation
import com.oea.engine.core.OeaGame
import com.oea.engine.core.PlayMode
import com.oea.engine.net.lan.LanBrowser
import com.oea.engine.net.lan.LanGame
import com.oea.engine.net.lan.LanJoinCode
import com.oea.engine.net.session.NetSession
import com.oea.engine.net.session.OnlineInvite
import com.oea.engine.net.session.SessionListener

/** A networked challenge: the same cabinet, level and seed on both phones. */
data class ArcadeMatch(
    val spec: ArcadeSpec,
    val config: ArcadeConfig,
    val session: NetSession?,
    val isHost: Boolean,
)

/** Identity used for the arcade's own lobbies; not registered as a hub game. */
object ArcadeNet {
    val descriptor: GameDescriptor = GameDescriptor(
        id = "allinone",
        title = "All in One",
        tagline = "One arcade, one shared level",
        description = "Every cabinet in the arcade, played side by side.",
        accent = ArcadeColors.PINK,
        glyph = "🎮",
        orientation = GameOrientation.PORTRAIT,
        dimension = GameDimension.D2,
        minPlayers = 1,
        maxPlayers = 8,
        modes = setOf(PlayMode.SOLO, PlayMode.SAME_DEVICE, PlayMode.LAN, PlayMode.ONLINE),
        sameDeviceLabel = "Same phone",
        factory = { throw UnsupportedOperationException("The arcade starts its cabinets directly") },
    )
}

/**
 * The arcade's one host/join lobby, shared by every cabinet.
 *
 * The old games each grew their own copy of hosting, browsing, codes and
 * lobbies, and the differences between those copies are what makes "host" work
 * in one game and mysteriously fail in another. Here there is one path for every
 * cabinet:
 *
 *  - **Host on Wi-Fi / hotspot** opens the TCP/UDP host, advertises it, and shows
 *    a join code that survives discovery being blocked;
 *  - **Join nearby** listens for those advertisements and connects to the one you
 *    pick, with the host's IP as a fallback;
 *  - **Join with a code** accepts either the short Wi-Fi code or an online
 *    invite/reply pair;
 *  - the host only starts when a friend is actually in the room, and every phone
 *    starts the *same cabinet, level, difficulty and seed*, then compares scores.
 *
 * Hosting is always refused by the engine when another session is running, so
 * this class closes the previous session first — that, and keeping the browser
 * stopped while connected, removes the "I hosted twice and the second lobby went
 * silent" failure. Addresses are refreshed explicitly because a hotspot can be
 * switched on after the lobby opened, which used to leave a join code pointing at
 * a network that did not exist yet.
 */
class ArcadeLobby(
    private val activity: Activity,
    private val progress: ArcadeProgress,
    private val onMatch: (ArcadeMatch) -> Unit,
) {

    private enum class Stage { HOST_LAN, HOST_ONLINE, JOINING, GUEST }

    private var session: NetSession? = null
    private var browser: LanBrowser? = null
    private var sheet: Dialog? = null
    private var stage = Stage.HOST_LAN
    private var chosenSpec: ArcadeSpec = ArcadeCatalog.all().first { it.create != null }
    private var chosenLevel = 1
    private var statusView: TextView? = null
    private var codeView: TextView? = null
    private var playersView: TextView? = null
    private var startView: View? = null
    private var nearbyHolder: LinearLayout? = null

    private val listener = object : SessionListener {
        override fun onSessionUpdate(session: NetSession) {
            activity.runOnUiThread {
                if (this@ArcadeLobby.session === session) refresh(session)
            }
        }

        override fun onMatchStart(session: NetSession) {
            activity.runOnUiThread {
                if (this@ArcadeLobby.session !== session) return@runOnUiThread
                val spec = ArcadeCatalog.byId(session.matchSettings["game"] ?: "") ?: chosenSpec
                val level = (session.matchSettings["level"]?.toIntOrNull() ?: chosenLevel)
                    .coerceIn(1, spec.levels)
                val difficulty = ArcadeDifficulty.entries
                    .firstOrNull { it.name == session.matchSettings["difficulty"] }
                    ?: progress.difficulty()
                sheet?.dismiss()
                sheet = null
                onMatch(
                    ArcadeMatch(
                        spec = spec,
                        config = ArcadeConfig(level, difficulty, 1, session.seed, ArcadePlayMode.FRIENDS),
                        session = session,
                        isHost = session.isHost,
                    )
                )
            }
        }

        override fun onSessionClosed(session: NetSession, reason: String) {
            activity.runOnUiThread {
                if (this@ArcadeLobby.session !== session) return@runOnUiThread
                this@ArcadeLobby.session = null
                sheet?.dismiss()
                sheet = null
                if (session.phase != com.oea.engine.net.session.SessionPhase.PLAYING) {
                    toast(reason.ifBlank { "The lobby closed." })
                }
            }
        }
    }

    // ------------------------------------------------------------------ entry

    fun open(preferred: ArcadeSpec? = null) {
        if (preferred != null && preferred.create != null) {
            chosenSpec = preferred
            chosenLevel = progress.unlocked(preferred.id)
        } else {
            val last = progress.continueSpec()?.takeIf { it.create != null }
            if (last != null) {
                chosenSpec = last
                chosenLevel = progress.unlocked(last.id)
            }
        }
        showMenu()
    }

    /** Stops discovery. The session is left alone: a running match needs it. */
    fun stopDiscovery() {
        browser?.stop()
        browser = null
    }

    fun closeAll() {
        stopDiscovery()
        sheet?.dismiss()
        sheet = null
    }

    // ------------------------------------------------------------------- menu

    private fun showMenu() {
        stopDiscovery()
        val body = { panel: LinearLayout, dialog: Dialog ->
            panel.addView(
                ArcadeUi.text(
                    activity,
                    "Two phones on the same Wi-Fi or hotspot — or a code when they are not. Both play the same cabinet at the same level and compare scores, so nobody waits for a turn.",
                    12.5f, ArcadeColors.INK_SOFT
                ).apply { setLineSpacing(ArcadeUi.dp(activity, 2f).toFloat(), 1f) },
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ArcadeUi.dp(activity, 12f) }
            )
            fun add(row: View) {
                panel.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ArcadeUi.dp(activity, 7f) })
            }
            add(ArcadeUi.row(activity, "📶", "Host on Wi-Fi / hotspot", "Your friend joins nearby, or pastes your Wi-Fi code") {
                dialog.dismiss()
                hostLan()
            })
            add(ArcadeUi.row(activity, "🔎", "Join nearby", "Find a lobby already open on this network") {
                dialog.dismiss()
                nearby()
            })
            add(ArcadeUi.row(activity, "🔑", "Join with a code", "A Wi-Fi code, an online invite, or your reply code") {
                dialog.dismiss()
                promptJoinCode()
            })
            add(ArcadeUi.row(activity, "🌐", "Host online with a code", "Different networks: share an invite, they reply") {
                dialog.dismiss()
                hostOnline()
            })
            panel.addView(
                ArcadeUi.outlineButton(activity, "Close", ArcadeColors.INK_SOFT) { dialog.dismiss() },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = ArcadeUi.dp(activity, 8f) }
            )
        }
        sheet = ArcadeUi.dialog(activity, "Play with a friend", "No account, no server. One host, up to eight players.", 0.7f, body)
    }

    // ------------------------------------------------------------------- host

    private fun hostLan() {
        val old = session
        if (old != null && old.phase != com.oea.engine.net.session.SessionPhase.CLOSED) old.close("Starting a new lobby")
        session = null
        stage = Stage.HOST_LAN
        val s = NetSession.host(
            activity,
            ArcadeNet.descriptor,
            "${OeaEngine.settings.playerName.ifBlank { "OEA player" }}'s All in One",
        )
        session = s
        s.addListener(listener)
        showHostSheet()
        s.startLan { info, warning ->
            activity.runOnUiThread {
                if (warning != null) toast(warning)
                codeView?.text = info?.joinCode
                    ?: "No network address yet. Turn on Wi-Fi or the hotspot, then tap Refresh network."
                refresh(s)
            }
        }
    }

    private fun showHostSheet() {
        val panel = { body: LinearLayout, dialog: Dialog ->
            statusView = ArcadeUi.text(activity, "Opening the lobby…", 13f, ArcadeColors.INK).apply {
                setPadding(0, 0, 0, ArcadeUi.dp(activity, 6f))
            }
            body.addView(statusView, LinearLayout.LayoutParams(-1, -2))
            codeView = ArcadeUi.text(activity, "Getting your join code…", 11.5f, ArcadeColors.INK_SOFT, mono = true).apply {
                setTextIsSelectable(true)
                setLineSpacing(ArcadeUi.dp(activity, 2f).toFloat(), 1f)
                setPadding(0, 0, 0, ArcadeUi.dp(activity, 8f))
            }
            body.addView(codeView, LinearLayout.LayoutParams(-1, -2))
            playersView = ArcadeUi.text(activity, "", 12.5f, ArcadeColors.INK_SOFT)
            body.addView(playersView, LinearLayout.LayoutParams(-1, -2))
            val gameRow = ArcadeUi.row(activity, "🎮", "Cabinet: ${chosenSpec.title}", "Tap to pick what you both play") {
                val options = ArcadeCatalog.all().filter { it.create != null }
                ArcadeUi.choose(activity, "Choose a cabinet", "Both phones will play this", options.map { it.title }, options.indexOf(chosenSpec)) { index ->
                    chosenSpec = options[index]
                    chosenLevel = progress.unlocked(chosenSpec.id)
                    dialog.dismiss()
                    showHostSheet()
                    refresh(session)
                }
            }
            body.addView(gameRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ArcadeUi.dp(activity, 10f) })
            val levelRow = ArcadeUi.row(activity, "🎚", "Level: ${chosenLevel} · ${chosenSpec.levelName(chosenLevel)}", "Only levels you have unlocked") {
                val max = progress.unlocked(chosenSpec.id)
                val options = (1..max).map { "Level $it · ${chosenSpec.levelName(it)}" }
                ArcadeUi.choose(activity, "Choose a level", chosenSpec.title, options, chosenLevel - 1) { index ->
                    chosenLevel = index + 1
                    dialog.dismiss()
                    showHostSheet()
                    refresh(session)
                }
            }
            body.addView(levelRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ArcadeUi.dp(activity, 7f) })
            startView = ArcadeUi.button(activity, "Start the challenge", ArcadeColors.INK, Color.WHITE) {
                startMatch()
            }
            body.addView(startView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ArcadeUi.dp(activity, 12f) })
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(
                ArcadeUi.outlineButton(activity, "Copy code", ArcadeColors.INK_SOFT) {
                    copy(codeView?.text?.toString().orEmpty())
                },
                LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = ArcadeUi.dp(activity, 8f) }
            )
            row.addView(
                ArcadeUi.outlineButton(activity, "Refresh network", ArcadeColors.INK_SOFT) {
                    session?.refreshLanAddresses()
                    toast("Looking for this network again…")
                },
                LinearLayout.LayoutParams(0, -2, 1f)
            )
            body.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ArcadeUi.dp(activity, 8f) })
            body.addView(
                ArcadeUi.outlineButton(activity, "Close lobby", ArcadeColors.INK_SOFT) {
                    dialog.dismiss()
                    sheet = null
                    session?.close("Host closed the lobby")
                    session = null
                },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = ArcadeUi.dp(activity, 8f) }
            )
        }
        sheet?.dismiss()
        sheet = ArcadeUi.dialog(activity, "Hosting All in One", "Stay on this screen until your friend joins.", 0.66f, panel)
        refresh(session)
    }

    private fun startMatch() {
        val s = session ?: return
        if (!s.isHost) return
        val guests = s.players.count { !it.isHost }
        if (guests == 0) {
            toast("Nobody has joined yet. Keep this screen open and let your friend join nearby or paste the code.")
            return
        }
        val settings = mapOf(
            "game" to chosenSpec.id,
            "level" to chosenLevel.coerceIn(1, chosenSpec.levels).toString(),
            "difficulty" to progress.difficulty().name,
        )
        s.startMatch(settings)
    }

    // ------------------------------------------------------------------ guest

    private fun nearby() {
        stopDiscovery()
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        nearbyHolder = list
        val dialog = ArcadeUi.dialog(activity, "Join nearby", "Games already open on this Wi-Fi or hotspot", 0.55f) { body, d ->
            body.addView(list, LinearLayout.LayoutParams(-1, -2))
            body.addView(
                ArcadeUi.text(activity, "Nothing yet? Ask the host to keep the lobby open. Some routers block discovery — use their Wi-Fi code instead.", 11.5f, ArcadeColors.INK_SOFT)
                    .apply { setPadding(0, ArcadeUi.dp(activity, 8f), 0, ArcadeUi.dp(activity, 8f)) },
                LinearLayout.LayoutParams(-1, -2)
            )
            body.addView(ArcadeUi.outlineButton(activity, "Scan again", ArcadeColors.INK_SOFT) {
                list.removeAllViews()
                list.addView(ArcadeUi.text(activity, "Looking…", 12.5f, ArcadeColors.INK_SOFT))
                stopDiscovery()
                startBrowser(dialog)
            })
            body.addView(ArcadeUi.outlineButton(activity, "Close", ArcadeColors.INK_SOFT) { d.dismiss() }, LinearLayout.LayoutParams(-1, -2))
        }
        list.addView(ArcadeUi.text(activity, "Looking…", 12.5f, ArcadeColors.INK_SOFT))
        dialog.setOnDismissListener { stopDiscovery(); nearbyHolder = null }
        sheet = dialog
        startBrowser(dialog)
    }

    private fun startBrowser(dialog: Dialog) {
        browser = LanBrowser(activity, ArcadeNet.descriptor.id) { games ->
            activity.runOnUiThread {
                val holder = nearbyHolder ?: return@runOnUiThread
                val open = games.filter { it.inLobby && !it.isFull && it.compatible }
                holder.removeAllViews()
                if (open.isEmpty()) {
                    holder.addView(ArcadeUi.text(activity, "Looking for a OEA arcade lobby…", 12.5f, ArcadeColors.INK_SOFT))
                    return@runOnUiThread
                }
                open.forEach { game ->
                    holder.addView(
                        ArcadeUi.row(activity, "🎮", game.roomName.ifBlank { "A OEA lobby" }, "${game.hostName} · ${game.players} in the lobby") {
                            dialog.dismiss()
                            join(game.address, game.port)
                        },
                        LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ArcadeUi.dp(activity, 6f) }
                    )
                }
            }
        }.also { it.start() }
    }

    private fun promptJoinCode() {
        val input = EditText(activity).apply {
            hint = "OEAL-… · OEA-…"
            setSingleLine(false)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setTextColor(ArcadeColors.INK)
            setHintTextColor(ArcadeColors.INK_SOFT)
            textSize = 13f
            setPadding(ArcadeUi.dp(activity, 12f), ArcadeUi.dp(activity, 10f), ArcadeUi.dp(activity, 12f), ArcadeUi.dp(activity, 10f))
            background = ArcadeUi.rounded(0xFFFFF7EC.toInt(), ArcadeUi.dp(activity, 14f), 0xFFE8D9C4.toInt(), ArcadeUi.dp(activity, 1f))
        }
        ArcadeUi.dialog(activity, "Join with a code", "Paste the Wi-Fi code from the host, an online invite, or your reply code.", 0.5f) { body, d ->
            body.addView(input, LinearLayout.LayoutParams(-1, -2))
            body.addView(ArcadeUi.button(activity, "Join", ArcadeColors.MINT, ArcadeColors.INK) {
                val text = input.text.toString().trim()
                if (text.isBlank()) {
                    toast("Paste a code first.")
                } else {
                    d.dismiss()
                    joinWithCode(text)
                }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ArcadeUi.dp(activity, 10f) })
            body.addView(ArcadeUi.outlineButton(activity, "Paste from clipboard", ArcadeColors.INK_SOFT) {
                clipboardText()?.let { input.setText(it) } ?: toast("The clipboard is empty.")
            }, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun joinWithCode(text: String) {
        if (LanJoinCode.isLanCode(text)) {
            val target = LanJoinCode.decode(text).getOrElse {
                toast(it.message ?: "That Wi-Fi code doesn't work.")
                return
            }
            joinAny(target.addresses, target.port)
            return
        }
        val s = NetSession.joinOnline(activity, ArcadeNet.descriptor, text) { reply, error ->
            activity.runOnUiThread {
                if (error != null) {
                    toast(error)
                } else if (reply != null) {
                    showReplySheet(reply)
                }
            }
        }
        if (s == null) return
        session = s
        s.addListener(listener)
        stage = Stage.GUEST
        showGuestSheet("Sending your reply…")
    }

    private fun join(address: String, port: Int) = joinAny(listOf(address), port)

    private fun joinAny(addresses: List<String>, port: Int) {
        stopDiscovery()
        val s = NetSession.joinLanAny(activity, ArcadeNet.descriptor, addresses, port, listener)
        session = s
        stage = Stage.JOINING
        showGuestSheet("Connecting to the host…")
    }

    private fun showGuestSheet(opening: String) {
        val panel = { body: LinearLayout, dialog: Dialog ->
            statusView = ArcadeUi.text(activity, opening, 13.5f, ArcadeColors.INK).apply {
                setLineSpacing(ArcadeUi.dp(activity, 3f).toFloat(), 1f)
            }
            body.addView(statusView, LinearLayout.LayoutParams(-1, -2))
            body.addView(
                ArcadeUi.text(activity, "Both phones need the same OEA build. You will start together when the host presses Start.", 11.5f, ArcadeColors.INK_SOFT)
                    .apply { setPadding(0, ArcadeUi.dp(activity, 8f), 0, 0) },
                LinearLayout.LayoutParams(-1, -2)
            )
            body.addView(ArcadeUi.outlineButton(activity, "Leave", ArcadeColors.INK_SOFT) {
                dialog.dismiss()
                sheet = null
                session?.close("Left the lobby")
                session = null
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ArcadeUi.dp(activity, 14f) })
        }
        sheet?.dismiss()
        sheet = ArcadeUi.dialog(activity, "Joining a friend", null, 0.45f, panel)
        refresh(session)
    }

    private fun showReplySheet(replyCode: String) {
        val panel = { body: LinearLayout, dialog: Dialog ->
            statusView = ArcadeUi.text(activity, "Send this reply back to the host, then wait here.", 13f, ArcadeColors.INK)
            body.addView(statusView, LinearLayout.LayoutParams(-1, -2))
            codeView = ArcadeUi.text(activity, replyCode, 11f, ArcadeColors.INK_SOFT, mono = true).apply {
                setTextIsSelectable(true)
                setPadding(0, ArcadeUi.dp(activity, 8f), 0, ArcadeUi.dp(activity, 8f))
            }
            body.addView(codeView, LinearLayout.LayoutParams(-1, -2))
            body.addView(ArcadeUi.button(activity, "Copy reply code", ArcadeColors.MINT, ArcadeColors.INK) {
                copy(replyCode)
            }, LinearLayout.LayoutParams(-1, -2))
            body.addView(ArcadeUi.outlineButton(activity, "Leave", ArcadeColors.INK_SOFT) {
                dialog.dismiss()
                session?.close("Left the lobby")
                session = null
            }, LinearLayout.LayoutParams(-1, -2))
        }
        sheet?.dismiss()
        sheet = ArcadeUi.dialog(activity, "You're invited", "One last step: the host enters this.", 0.5f, panel)
    }

    // ------------------------------------------------------------- online host

    private fun hostOnline() {
        val old = session
        if (old != null && old.phase != com.oea.engine.net.session.SessionPhase.CLOSED) old.close("Starting a new lobby")
        val s = NetSession.host(
            activity,
            ArcadeNet.descriptor,
            "${OeaEngine.settings.playerName.ifBlank { "OEA player" }}'s All in One",
        )
        session = s
        s.addListener(listener)
        stage = Stage.HOST_ONLINE
        val invite = s.createOnlineInvite()
        showOnlineHostSheet(invite)
    }

    private fun showOnlineHostSheet(invite: OnlineInvite) {
        val reply = EditText(activity).apply {
            hint = "Paste your friend's reply code"
            setTextColor(ArcadeColors.INK)
            setHintTextColor(ArcadeColors.INK_SOFT)
            textSize = 12.5f
            setPadding(ArcadeUi.dp(activity, 12f), ArcadeUi.dp(activity, 10f), ArcadeUi.dp(activity, 12f), ArcadeUi.dp(activity, 10f))
            background = ArcadeUi.rounded(0xFFFFF7EC.toInt(), ArcadeUi.dp(activity, 14f), 0xFFE8D9C4.toInt(), ArcadeUi.dp(activity, 1f))
        }
        statusView = ArcadeUi.text(activity, invite.status.label, 13f, ArcadeColors.INK)
        val panel = { body: LinearLayout, dialog: Dialog ->
            body.addView(statusView, LinearLayout.LayoutParams(-1, -2))
            codeView = ArcadeUi.text(activity, invite.code ?: "Creating your invite…", 11f, ArcadeColors.INK_SOFT, mono = true).apply {
                setTextIsSelectable(true)
                setPadding(0, ArcadeUi.dp(activity, 6f), 0, ArcadeUi.dp(activity, 6f))
            }
            body.addView(codeView, LinearLayout.LayoutParams(-1, -2))
            playersView = ArcadeUi.text(activity, "", 12.5f, ArcadeColors.INK_SOFT)
            body.addView(playersView, LinearLayout.LayoutParams(-1, -2))
            body.addView(reply, LinearLayout.LayoutParams(-1, -2))
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(ArcadeUi.outlineButton(activity, "Copy invite", ArcadeColors.INK_SOFT) {
                copy(invite.code ?: codeView?.text?.toString().orEmpty())
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = ArcadeUi.dp(activity, 8f) })
            row.addView(ArcadeUi.button(activity, "Connect", ArcadeColors.MINT, ArcadeColors.INK) {
                val error = invite.acceptReply(reply.text.toString())
                if (error != null) toast(error)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            body.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ArcadeUi.dp(activity, 8f) })
            startView = ArcadeUi.button(activity, "Start the challenge", ArcadeColors.INK, Color.WHITE) { startMatch() }
            body.addView(startView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ArcadeUi.dp(activity, 10f) })
            body.addView(ArcadeUi.outlineButton(activity, "Close lobby", ArcadeColors.INK_SOFT) {
                dialog.dismiss()
                invite.cancel()
                session?.close("Host closed the lobby")
                session = null
            }, LinearLayout.LayoutParams(-1, -2))
        }
        sheet?.dismiss()
        sheet = ArcadeUi.dialog(activity, "Hosting online", "Send the invite, then paste the reply you get back.", 0.66f, panel)
        invite.onChange = { changed ->
            activity.runOnUiThread {
                statusView?.text = changed.status.label + if (changed.message.isNotBlank()) " — ${changed.message}" else ""
                if (changed.code != null && codeView?.text.isNullOrBlank()) codeView?.text = changed.code
                codeView?.text = changed.code ?: codeView?.text
            }
        }
        sheet?.setOnDismissListener { invite.onChange = null }
        refresh(session)
    }

    // --------------------------------------------------------------- painting

    private fun refresh(session: NetSession?) {
        val s = session ?: return
        val status = statusView ?: return
        val guests = s.players.count { !it.isHost }
        status.text = buildString {
            append(s.status.ifBlank { s.phase.label })
            if (s.phase == com.oea.engine.net.session.SessionPhase.PLAYING) append(" · match starting")
            append("\nGuests: ").append(guests)
            if (guests == 0 && s.isHost) append(" — waiting for a friend")
        }
        playersView?.text = s.players.joinToString("\n") { player ->
            val tags = buildList {
                if (player.isHost) add("host")
                if (player.isLocal) add("you")
                if (player.rttMs > 0) add("${player.rttMs} ms")
            }
            player.name + if (tags.isEmpty()) "" else " · " + tags.joinToString(", ")
        }
        startView?.alpha = if (s.isHost) 1f else 0.5f
        codeView?.let { view ->
            // Keep the LAN join code battery fresh (a hotspot switched on after
            // hosting changes the address) but never overwrite an online invite.
            if (stage == Stage.HOST_LAN) {
                val info = s.lanInfo
                if (info != null) view.text = info.joinCode
            }
        }
    }

    // ----------------------------------------------------------------- utils

    private fun copy(value: String) {
        if (value.isBlank()) {
            toast("There is nothing to copy yet.")
            return
        }
        runCatching {
            activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                ClipData.newPlainText("OEA All in One", value)
            )
        }.onSuccess { toast("Copied.") }.onFailure { toast("Couldn't copy on this phone.") }
    }

    private fun clipboardText(): String? = runCatching {
        activity.getSystemService(ClipboardManager::class.java).takeIf { it.hasPrimaryClip() }
            ?.getPrimaryClip()?.getItemAt(0)?.coerceToText(activity)?.toString()
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun toast(message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    }
}
