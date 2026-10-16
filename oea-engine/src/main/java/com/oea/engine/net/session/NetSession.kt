package com.oea.engine.net.session

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.oea.engine.EngineSettings
import com.oea.engine.OeaEngine
import com.oea.engine.core.OeaGuard
import com.oea.engine.core.GameDescriptor
import com.oea.engine.net.ByteReader
import com.oea.engine.net.ByteWriter
import com.oea.engine.net.Channel
import com.oea.engine.net.Link
import com.oea.engine.net.LinkListener
import com.oea.engine.net.lan.LanAdvertiser
import com.oea.engine.net.lan.LanBeacon
import com.oea.engine.net.lan.LanClientLink
import com.oea.engine.net.lan.LanJoinCode
import com.oea.engine.net.lan.LanServer
import com.oea.engine.net.lan.LocalAddress
import com.oea.engine.net.lan.NetInterfaces
import com.oea.engine.net.rtc.RtcLink
import com.oea.engine.net.rtc.RtcRuntime
import com.oea.engine.net.rtc.SignalCode
import com.oea.engine.net.rtc.SignalCodeException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

enum class SessionRole { HOST, CLIENT }

enum class SessionPhase(val label: String) {
    CONNECTING("Connecting"),
    LOBBY("In the lobby"),
    PLAYING("Playing"),
    CLOSED("Closed"),
}

/** A participant as seen by everyone in the session. */
data class NetPlayer(
    val id: Int,
    val name: String,
    val color: Int,
    val isHost: Boolean,
    val isLocal: Boolean,
    val ready: Boolean,
    val connected: Boolean,
    val rttMs: Int,
    /** How this player is connected to the host, e.g. "Wi-Fi • 192.168.1.20" or "Online • direct". */
    val connection: String,
)

/** A game message delivered by [NetSession.poll]. Read the payload with [reader]. */
class NetMessage internal constructor(val type: Int, val from: Int, val reliable: Boolean, private val data: ByteArray, private val offset: Int) {
    fun reader(): ByteReader = ByteReader(data, offset)
}

/** Session events on the main thread (lobby UIs). */
interface SessionListener {
    fun onSessionUpdate(session: NetSession) {}
    /** The host started a match: launch the game activity. */
    fun onMatchStart(session: NetSession) {}
    fun onSessionClosed(session: NetSession, reason: String) {}
}

/** Host side of one pending online (WebRTC) invitation. */
class OnlineInvite internal constructor(private val session: NetSession, internal val link: RtcLink, val nonce: Long) {
    enum class Status(val label: String) {
        CREATING("Creating your invite…"),
        READY("Share this invite, then enter the reply code"),
        CONNECTING("Connecting…"),
        CONNECTED("Connected"),
        FAILED("Couldn't connect"),
        CANCELLED("Cancelled"),
    }

    @Volatile var status = Status.CREATING
        internal set
    @Volatile var code: String? = null
        internal set
    @Volatile var message: String = ""
        internal set
    /** Main-thread change callback. */
    var onChange: ((OnlineInvite) -> Unit)? = null

    internal fun update(s: Status, msg: String = message) {
        status = s
        message = msg
        session.post { onChange?.invoke(this) }
    }

    /**
     * Applies the reply code your friend sent back. Returns an error message
     * right away if the code is unusable, otherwise null (progress is
     * reported through [onChange]).
     */
    fun acceptReply(text: String): String? {
        if (status == Status.CONNECTED) return "This invite is already connected."
        if (status == Status.CANCELLED || status == Status.FAILED) return "This invite is closed. Make a new one."
        val p = try { SignalCode.decode(text) } catch (e: SignalCodeException) { return e.message }
        if (p.kind != SignalCode.Kind.REPLY) return "That's an invite code. Your friend needs to scan your invite and send back their reply code."
        if (p.gameId != session.descriptor.id) return "That reply is for a different game."
        if (p.nonce != nonce) return "That reply belongs to a different invite. Ask your friend to scan your newest invite."
        update(Status.CONNECTING, "Connecting to ${p.playerName.ifBlank { "your friend" }}…")
        link.acceptAnswer(p.sdp) { err -> if (err != null) update(Status.FAILED, err) }
        return null
    }

    fun cancel() {
        if (status == Status.CONNECTED) return
        update(Status.CANCELLED, "")
        link.close("Invite cancelled")
    }
}

/** Address and code that let others join a LAN host. */
data class LanInfo(val port: Int, val addresses: List<LocalAddress>, val joinCode: String)

/**
 * A multiplayer session: lobby, players, routing, clock sync and the game
 * message channel. One host, any number of clients (up to the game's
 * maximum), connected through either supported method — local Wi-Fi /
 * hotspot or online WebRTC codes — and a host may mix both.
 *
 * Topology is a star: clients talk to the host, which relays messages
 * addressed to other players. Game code uses only [send], [broadcast],
 * [poll], [now] and [players].
 */
class NetSession private constructor(
    private val context: Context,
    val role: SessionRole,
    val descriptor: GameDescriptor,
    roomName: String,
) : LinkListener {
    private val settingsStore: EngineSettings = OeaEngine.settings
    private val exec = OeaGuard.scheduled("net-session", "oea-session")
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<SessionListener>()
    private val gameQueue = ConcurrentLinkedQueue<NetMessage>()

    private val links = LinkedHashMap<Int, Link>()       // host: link id -> link
    private var hostLink: Link? = null                     // client
    private val roster = LinkedHashMap<Int, PlayerState>()  // host-authoritative
    private val invites = CopyOnWriteArrayList<OnlineInvite>()
    private var lanServer: LanServer? = null
    private var advertiser: LanAdvertiser? = null
    private var beacon: LanBeacon? = null
    private var pingSeq = 0
    private var offsetSamples = ArrayList<LongArray>()     // [rtt, offset]
    @Volatile private var clockOffset = 0L
    private var lastLobbyBroadcast = 0L
    /** Client: the WebRTC link while it isn't up yet. */
    @Volatile private var hostLinkPending: Link? = null
    /** Desired lobby readiness survives the short connection handshake. */
    @Volatile private var requestedReady = false

    val sessionId: Long = Random.nextLong()
    @Volatile var localId = if (role == SessionRole.HOST) 0 else -1
        private set
    @Volatile var phase = if (role == SessionRole.HOST) SessionPhase.LOBBY else SessionPhase.CONNECTING
        private set
    @Volatile var roomName: String = roomName
        private set
    @Volatile var seed: Long = 0L
        private set
    /** Host clock time (see [now]) at which the match begins; games count down to it. */
    @Volatile var startAt: Long = 0L
        private set
    @Volatile var matchSettings: Map<String, String> = emptyMap()
        private set
    @Volatile var players: List<NetPlayer> = emptyList()
        private set
    @Volatile var closeReason: String = ""
        private set
    @Volatile var lanInfo: LanInfo? = null
        private set
    /** Short status for UIs ("Waiting for players", "Connected to Ada's game" …). */
    @Volatile var status: String = ""
        private set

    val isHost get() = role == SessionRole.HOST
    val maxPlayers get() = descriptor.maxPlayers
    val localPlayer: NetPlayer? get() = players.firstOrNull { it.id == localId }
    val onlineInvites: List<OnlineInvite> get() = invites.toList()

    private class PlayerState(
        val id: Int, var name: String, var color: Int, val token: Long,
        var ready: Boolean, var connected: Boolean, var rtt: Int, var link: Link?, var connection: String,
    )

    // ---------------------------------------------------------------- listeners

    fun addListener(l: SessionListener) { listeners += l }
    fun removeListener(l: SessionListener) { listeners -= l }
    internal fun post(block: () -> Unit) { main.post(block) }
    private fun notifyUpdate() = main.post { listeners.forEach { runCatching { it.onSessionUpdate(this) } } }

    // ---------------------------------------------------------------- game API (any thread)

    /** Shared clock in milliseconds, identical (within a few ms) on every device. */
    fun now(): Long = SystemClock.elapsedRealtime() + clockOffset

    fun rttTo(playerId: Int): Int = players.firstOrNull { it.id == playerId }?.rttMs ?: 0

    /** Sends a game message (type ≥ [FIRST_GAME_TYPE]) to one player. */
    fun send(to: Int, type: Int, reliable: Boolean = true, write: (ByteWriter) -> Unit = {}) {
        require(type in FIRST_GAME_TYPE..255) { "Game message types must be $FIRST_GAME_TYPE..255" }
        transmit(build(type, localId, to, write), to, if (reliable) Channel.RELIABLE else Channel.UNRELIABLE)
    }

    /** Sends a game message to every other player. */
    fun broadcast(type: Int, reliable: Boolean = true, write: (ByteWriter) -> Unit = {}) {
        require(type in FIRST_GAME_TYPE..255) { "Game message types must be $FIRST_GAME_TYPE..255" }
        transmit(build(type, localId, TO_ALL, write), TO_ALL, if (reliable) Channel.RELIABLE else Channel.UNRELIABLE)
    }

    fun sendToHost(type: Int, reliable: Boolean = true, write: (ByteWriter) -> Unit = {}) = send(HOST_ID, type, reliable, write)

    /** Delivers queued game messages (and [MSG_PLAYER_JOINED] / [MSG_PLAYER_LEFT] / [MSG_MATCH_ENDED]) on the calling thread. */
    fun poll(handler: (NetMessage) -> Unit) {
        var guard = 0
        while (guard++ < 1024) {
            val m = gameQueue.poll() ?: return
            try { handler(m) } catch (e: com.oea.engine.net.NetFormatException) { Log.w(TAG, "Dropped malformed message ${m.type}", e) }
        }
    }

    private fun build(type: Int, from: Int, to: Int, write: (ByteWriter) -> Unit): ByteArray {
        val w = ByteWriter(64).u8(type).u8(from and 0xFF).u8(to and 0xFF)
        write(w)
        return w.toByteArray()
    }

    private fun transmit(bytes: ByteArray, to: Int, channel: Channel) {
        if (role == SessionRole.CLIENT) {
            hostLink?.send(channel, bytes)
            return
        }
        synchronized(roster) {
            if (to == TO_ALL) {
                for (p in roster.values) if (p.id != localId && p.connected) p.link?.send(channel, bytes)
            } else {
                roster[to]?.link?.send(channel, bytes)
            }
        }
    }

    // ---------------------------------------------------------------- lobby API (UI thread)

    fun setReady(ready: Boolean) {
        requestedReady = ready
        if (isHost) exec.execute { roster[localId]?.ready = ready; rebuildAndBroadcast() }
        else hostLink?.send(Channel.RELIABLE, build(MSG_READY, localId, HOST_ID) { it.bool(ready) })
    }

    /** Host: starts the match for everyone with [settings]; the match begins at [now] + [countdownMs]. */
    fun startMatch(settings: Map<String, String>, countdownMs: Long = 3500L) {
        check(isHost) { "Only the host starts the match" }
        exec.execute {
            seed = Random.nextLong()
            startAt = now() + countdownMs
            matchSettings = settings
            gameQueue.clear()
            phase = SessionPhase.PLAYING
            beacon?.inLobby = false
            val bytes = build(MSG_START, localId, TO_ALL) { w -> writeStart(w) }
            synchronized(roster) { roster.values.forEach { if (it.id != localId) it.link?.send(Channel.RELIABLE, bytes) } }
            rebuildAndBroadcast()
            main.post { listeners.forEach { runCatching { it.onMatchStart(this) } } }
        }
    }

    /** Host: ends the match and returns everyone to the lobby. */
    fun endMatch() {
        if (!isHost) return
        exec.execute {
            phase = SessionPhase.LOBBY
            beacon?.inLobby = true
            synchronized(roster) { roster.values.forEach { it.ready = it.id == localId } }
            val bytes = build(MSG_END, localId, TO_ALL) {}
            synchronized(roster) { roster.values.forEach { if (it.id != localId) it.link?.send(Channel.RELIABLE, bytes) } }
            gameQueue.add(NetMessage(MSG_MATCH_ENDED, HOST_ID, true, ByteArray(3), 3))
            rebuildAndBroadcast()
        }
    }

    fun kick(playerId: Int) {
        if (!isHost || playerId == localId) return
        exec.execute {
            val p = roster[playerId] ?: return@execute
            p.link?.send(Channel.RELIABLE, build(MSG_KICK, localId, playerId) { it.string("The host removed you from the game.") })
            exec.schedule({ p.link?.close("Removed by host") }, 300, TimeUnit.MILLISECONDS)
        }
    }

    fun renameRoom(name: String) {
        if (!isHost) return
        exec.execute { roomName = name.take(40); beacon?.roomName = roomName; rebuildAndBroadcast() }
    }

    /** Leaves (client) or closes (host) the session. */
    fun close(reason: String = if (isHost) "The host closed the game" else "Left the game") {
        exec.execute { shutdown(reason, sendBye = true) }
    }

    // ---------------------------------------------------------------- host: transports

    /** Host: accept players on this Wi-Fi / hotspot. [onReady] runs on the main thread. */
    fun startLan(onReady: (LanInfo?, String?) -> Unit) {
        check(isHost)
        exec.execute {
            try {
                if (lanServer == null) {
                    val server = LanServer(this)
                    val port = server.start()
                    lanServer = server
                    val b = LanBeacon(sessionId, descriptor.id, roomName, settingsStore.playerName, port, players.size, maxPlayers, phase == SessionPhase.LOBBY)
                    beacon = b
                    advertiser = LanAdvertiser(context, b).also { it.start() }
                }
                val addresses = NetInterfaces.localAddresses()
                val port = lanServer!!.port
                val info = LanInfo(port, addresses, LanJoinCode.encode(addresses.map { it.address }, port, sessionId, descriptor.id))
                lanInfo = info
                main.post { onReady(info, if (addresses.isEmpty()) "Not connected to Wi-Fi or a hotspot yet. Turn one on — players on it will see this game." else null) }
                notifyUpdate()
            } catch (e: Exception) {
                Log.w(TAG, "LAN start failed", e)
                main.post { onReady(null, "Couldn't open the game on this network (${e.message}).") }
            }
        }
    }

    /** Refreshes [lanInfo] (e.g. after the hotspot was switched on). */
    fun refreshLanAddresses() = startLan { _, _ -> }

    /** Host: creates a new online invite (one per remote player). */
    fun createOnlineInvite(): OnlineInvite {
        check(isHost)
        val nonce = Random.nextLong()
        val link = RtcLink(context, isOfferer = true, iceServers = settingsStore.iceServers, listener = this)
        val invite = OnlineInvite(this, link, nonce)
        invites += invite
        link.onState = { s, detail ->
            when (s) {
                RtcLink.State.CONNECTED -> invite.update(OnlineInvite.Status.CONNECTED, link.description)
                RtcLink.State.FAILED -> invite.update(OnlineInvite.Status.FAILED, detail)
                RtcLink.State.RECONNECTING -> invite.update(invite.status, detail)
                else -> Unit
            }
        }
        if (!RtcRuntime.isAvailable(context)) {
            invite.update(OnlineInvite.Status.FAILED, RtcRuntime.unavailableReason ?: "Online play isn't available on this device.")
            return invite
        }
        link.createOffer { sdp ->
            if (sdp == null) {
                if (invite.status != OnlineInvite.Status.FAILED) invite.update(OnlineInvite.Status.FAILED, link.stateDetail.ifBlank { "Couldn't create the invite." })
                return@createOffer
            }
            invite.code = SignalCode.encode(SignalCode.Payload(SignalCode.Kind.INVITE, descriptor.id, descriptor.version, OeaEngine.PROTOCOL, nonce,
                System.currentTimeMillis() / 1000L, settingsStore.playerName, sdp))
            invite.update(OnlineInvite.Status.READY, "")
        }
        return invite
    }

    // ---------------------------------------------------------------- link callbacks (transport threads)

    override fun onLinkUp(link: Link) {
        exec.execute {
            if (role == SessionRole.HOST) {
                links[link.id] = link
            } else {
                hostLink = link
                status = "Joining…"
                link.send(Channel.RELIABLE, build(MSG_HELLO, 0, HOST_ID) { w ->
                    w.u16(OeaEngine.PROTOCOL).string(OeaEngine.VERSION).string(descriptor.id).u16(descriptor.version)
                        .string(settingsStore.playerName).i32(settingsStore.playerColor).i64(settingsStore.playerToken)
                })
                notifyUpdate()
            }
        }
    }

    override fun onLinkStatus(link: Link, status: String) {
        exec.execute {
            if (role == SessionRole.CLIENT) { this.status = status; notifyUpdate() }
        }
    }

    override fun onLinkMessage(link: Link, channel: Channel, data: ByteArray) {
        exec.execute {
            try { handle(link, channel, data) } catch (e: Throwable) { OeaGuard.report("net-message", e) }
        }
    }

    override fun onLinkDown(link: Link, reason: String) {
        exec.execute {
            if (role == SessionRole.CLIENT) {
                if (link === hostLink || hostLink == null) shutdown(if (phase == SessionPhase.CONNECTING) reason else "Lost connection to the host: $reason", sendBye = false)
                return@execute
            }
            links.remove(link.id)
            val p = synchronized(roster) { roster.values.firstOrNull { it.link === link } } ?: return@execute
            p.link = null
            p.connected = false
            if (phase == SessionPhase.LOBBY) synchronized(roster) { roster.remove(p.id) }
            if (phase == SessionPhase.PLAYING) gameQueue.add(NetMessage(MSG_PLAYER_LEFT, p.id, true, ByteArray(3), 3))
            rebuildAndBroadcast()
        }
    }

    // ---------------------------------------------------------------- protocol (session thread)

    private fun handle(link: Link, channel: Channel, data: ByteArray) {
        if (data.size < 3) return
        val type = data[0].toInt() and 0xFF
        val from = data[1].toInt() and 0xFF
        val to = data[2].toInt() and 0xFF
        val r = ByteReader(data, 3)
        if (role == SessionRole.HOST) {
            if (type == MSG_HELLO) { onHello(link, r); return }
            val pid = link.playerId
            if (pid < 0 || from != pid) return
            when {
                type == MSG_READY -> { roster[pid]?.ready = r.bool(); rebuildAndBroadcast() }
                type == MSG_PING -> link.send(Channel.UNRELIABLE, build(MSG_PONG, localId, pid) { w -> w.u16(r.u16()).i64(r.i64()).i64(now()) })
                type == MSG_PONG -> { r.u16(); val t0 = r.i64(); roster[pid]?.rtt = (SystemClock.elapsedRealtime() - t0).toInt().coerceIn(0, 9999) }
                type == MSG_BYE -> link.close(r.string(200).ifBlank { "Left the game" })
                type >= FIRST_GAME_TYPE -> route(type, pid, to, channel, data)
            }
        } else {
            when (type) {
                MSG_WELCOME -> {
                    localId = r.u8()
                    r.i64() // host session id
                    val hostTime = r.i64()
                    clockOffset = hostTime - SystemClock.elapsedRealtime()
                    phase = SessionPhase.LOBBY
                    status = "Connected"
                    // A lobby UI may have requested readiness before HELLO
                    // completed; now that the host assigned our id, replay it.
                    if (requestedReady) link.send(Channel.RELIABLE, build(MSG_READY, localId, HOST_ID) { it.bool(true) })
                    notifyUpdate()
                }
                MSG_REJECT -> shutdown(r.string(300), sendBye = false)
                MSG_LOBBY -> readLobby(r)
                MSG_START -> {
                    readStart(r)
                    gameQueue.clear()
                    phase = SessionPhase.PLAYING
                    notifyUpdate()
                    main.post { listeners.forEach { runCatching { it.onMatchStart(this) } } }
                }
                MSG_END -> {
                    phase = SessionPhase.LOBBY
                    gameQueue.add(NetMessage(MSG_MATCH_ENDED, HOST_ID, true, ByteArray(3), 3))
                    notifyUpdate()
                }
                MSG_PING -> link.send(Channel.UNRELIABLE, build(MSG_PONG, localId, HOST_ID) { w -> w.u16(r.u16()).i64(r.i64()).i64(SystemClock.elapsedRealtime()) })
                MSG_PONG -> onPong(r)
                MSG_KICK -> shutdown(r.string(300), sendBye = false)
                MSG_BYE -> shutdown(r.string(200).ifBlank { "The host closed the game" }, sendBye = false)
                MSG_PLAYER_EVENT -> {
                    val kind = r.u8(); val pid = r.u8()
                    gameQueue.add(NetMessage(if (kind == 1) MSG_PLAYER_JOINED else MSG_PLAYER_LEFT, pid, true, ByteArray(3), 3))
                }
                else -> if (type >= FIRST_GAME_TYPE) gameQueue.add(NetMessage(type, from, channel == Channel.RELIABLE, data, 3))
            }
        }
    }

    /** Host routing of a client's game message. */
    private fun route(type: Int, from: Int, to: Int, channel: Channel, data: ByteArray) {
        val reliable = channel == Channel.RELIABLE
        when (to) {
            HOST_ID -> gameQueue.add(NetMessage(type, from, reliable, data, 3))
            TO_ALL -> {
                gameQueue.add(NetMessage(type, from, reliable, data, 3))
                synchronized(roster) { roster.values.forEach { if (it.id != localId && it.id != from && it.connected) it.link?.send(channel, data) } }
            }
            else -> synchronized(roster) { roster[to]?.takeIf { it.connected }?.link?.send(channel, data) }
        }
    }

    private fun onHello(link: Link, r: ByteReader) {
        val protocol = r.u16()
        val engineVersion = r.string(32)
        val gameId = r.string(64)
        val gameVersion = r.u16()
        var name = r.string(96).trim().take(EngineSettings.MAX_NAME).ifBlank { "Player" }
        var color = r.i32()
        val token = r.i64()
        fun reject(msg: String) {
            link.send(Channel.RELIABLE, build(MSG_REJECT, localId, 0) { it.string(msg) })
            exec.schedule({ link.close(msg) }, 400, TimeUnit.MILLISECONDS)
        }
        when {
            protocol != OeaEngine.PROTOCOL -> return reject("Your OEA version ($engineVersion) can't play with the host's (${OeaEngine.VERSION}). Update OEA on both phones.")
            gameId != descriptor.id -> return reject("The host is playing ${descriptor.title}, not the game you opened.")
            gameVersion != descriptor.version -> return reject("${descriptor.title} versions differ. Update OEA on both phones.")
        }
        val returning = synchronized(roster) { roster.values.firstOrNull { it.token == token && !it.connected } }
        val player: PlayerState
        if (returning != null) {
            player = returning
            player.connected = true
            player.link = link
        } else {
            if (phase == SessionPhase.PLAYING) return reject("A match is in progress. Wait for it to finish and join again.")
            if (synchronized(roster) { roster.size } >= maxPlayers) return reject("The game is full (${maxPlayers} players).")
            val id = (1 until 250).first { candidate -> synchronized(roster) { !roster.containsKey(candidate) } }
            val names = synchronized(roster) { roster.values.map { it.name.lowercase() }.toSet() }
            if (name.lowercase() in names) { var n = 2; while ("$name $n".lowercase() in names) n++; name = "$name $n" }
            val colors = synchronized(roster) { roster.values.map { it.color }.toSet() }
            if (color in colors || color == 0) color = EngineSettings.PLAYER_COLORS.firstOrNull { it !in colors } ?: color
            player = PlayerState(id, name, color, token, ready = false, connected = true, rtt = 0, link = link, connection = link.description)
            synchronized(roster) { roster[id] = player }
        }
        player.connection = link.description
        link.playerId = player.id
        link.send(Channel.RELIABLE, build(MSG_WELCOME, localId, player.id) { w -> w.u8(player.id).i64(sessionId).i64(now()) })
        rebuildAndBroadcast()
        if (phase == SessionPhase.PLAYING) {
            // A returning player rejoins the running match.
            link.send(Channel.RELIABLE, build(MSG_START, localId, player.id) { w -> writeStart(w) })
            gameQueue.add(NetMessage(MSG_PLAYER_JOINED, player.id, true, ByteArray(3), 3))
            val ev = build(MSG_PLAYER_EVENT, localId, TO_ALL) { it.u8(1).u8(player.id) }
            synchronized(roster) { roster.values.forEach { if (it.id != localId && it.id != player.id) it.link?.send(Channel.RELIABLE, ev) } }
        }
    }

    private fun writeStart(w: ByteWriter) {
        w.i64(seed).i64(startAt).varint(matchSettings.size)
        matchSettings.forEach { (k, v) -> w.string(k).string(v) }
    }

    private fun readStart(r: ByteReader) {
        seed = r.i64()
        startAt = r.i64()
        val n = r.varint().coerceAtMost(64)
        matchSettings = (0 until n).associate { r.string(64) to r.string(256) }
    }

    private fun readLobby(r: ByteReader) {
        val ph = r.u8()
        roomName = r.string(96)
        val n = r.varint().coerceAtMost(64)
        matchSettings = (0 until n).associate { r.string(64) to r.string(256) }
        val count = r.u8()
        val list = ArrayList<NetPlayer>(count)
        repeat(count) {
            val id = r.u8(); val name = r.string(96); val color = r.i32(); val flags = r.u8(); val rtt = r.u16(); val conn = r.string(96)
            list += NetPlayer(id, name, color, isHost = flags and 1 != 0, isLocal = id == localId, ready = flags and 2 != 0, connected = flags and 4 != 0, rttMs = rtt, connection = conn)
        }
        players = list
        if (phase != SessionPhase.CLOSED) phase = SessionPhase.entries.getOrElse(ph) { SessionPhase.LOBBY }.let { if (it == SessionPhase.CONNECTING) SessionPhase.LOBBY else it }
        status = "Connected to ${list.firstOrNull { it.isHost }?.name ?: "the host"}"
        notifyUpdate()
    }

    private fun onPong(r: ByteReader) {
        r.u16()
        val t0 = r.i64()
        val hostTime = r.i64()
        val nowLocal = SystemClock.elapsedRealtime()
        val rtt = nowLocal - t0
        if (rtt < 0 || rtt > 5000) return
        offsetSamples.add(longArrayOf(rtt, hostTime + rtt / 2 - nowLocal))
        if (offsetSamples.size > 12) offsetSamples.removeAt(0)
        // The lowest-latency sample has the least asymmetric delay: trust it most.
        clockOffset = offsetSamples.minByOrNull { it[0] }!![1]
    }

    /** Host: rebuilds [players] and sends the lobby to everyone. */
    private fun rebuildAndBroadcast() {
        val list = synchronized(roster) {
            roster.values.map { p ->
                NetPlayer(p.id, p.name, p.color, p.id == HOST_ID, p.id == localId, p.ready || p.id == HOST_ID, p.connected, p.rtt,
                    if (p.id == HOST_ID) "Host" else p.connection)
            }
        }
        players = list
        beacon?.players = list.count { it.connected }
        beacon?.maxPlayers = maxPlayers
        status = when (phase) {
            SessionPhase.LOBBY -> if (list.size <= 1) "Waiting for players" else "${list.size} of $maxPlayers players"
            SessionPhase.PLAYING -> "Match in progress"
            else -> phase.label
        }
        val bytes = build(MSG_LOBBY, localId, TO_ALL) { w ->
            w.u8(phase.ordinal).string(roomName).varint(matchSettings.size)
            matchSettings.forEach { (k, v) -> w.string(k).string(v) }
            w.u8(list.size)
            list.forEach { p ->
                w.u8(p.id).string(p.name).i32(p.color)
                    .u8((if (p.isHost) 1 else 0) or (if (p.ready) 2 else 0) or (if (p.connected) 4 else 0))
                    .u16(p.rttMs.coerceIn(0, 65535)).string(p.connection)
            }
        }
        synchronized(roster) { roster.values.forEach { if (it.id != localId && it.connected) it.link?.send(Channel.RELIABLE, bytes) } }
        lastLobbyBroadcast = SystemClock.elapsedRealtime()
        notifyUpdate()
    }

    private fun tick() {
        try {
            val nowMs = System.currentTimeMillis()
            if (role == SessionRole.HOST) {
                val ping = build(MSG_PING, localId, TO_ALL) { w -> w.u16(pingSeq++ and 0xFFFF).i64(SystemClock.elapsedRealtime()) }
                for (l in links.values.toList()) {
                    if (!l.isUp) continue
                    if (nowMs - l.lastHeardMs > TIMEOUT_MS) { l.close("Stopped responding"); continue }
                    l.send(Channel.UNRELIABLE, ping)
                }
                if (phase == SessionPhase.LOBBY && SystemClock.elapsedRealtime() - lastLobbyBroadcast > 3000L) rebuildAndBroadcast()
            } else {
                val l = hostLink ?: return
                if (!l.isUp) return
                if (nowMs - l.lastHeardMs > TIMEOUT_MS) { l.close("The host stopped responding"); return }
                l.send(Channel.UNRELIABLE, build(MSG_PING, localId, HOST_ID) { w -> w.u16(pingSeq++ and 0xFFFF).i64(SystemClock.elapsedRealtime()) })
            }
        } catch (e: Exception) {
            Log.w(TAG, "tick failed", e)
        }
    }

    private fun shutdown(reason: String, sendBye: Boolean) {
        if (phase == SessionPhase.CLOSED) return
        val wasPlaying = phase == SessionPhase.PLAYING
        phase = SessionPhase.CLOSED
        closeReason = reason
        status = reason
        if (sendBye) {
            val bye = build(MSG_BYE, localId, TO_ALL) { it.string(reason) }
            if (role == SessionRole.HOST) synchronized(roster) { roster.values.forEach { it.link?.send(Channel.RELIABLE, bye) } }
            else hostLink?.send(Channel.RELIABLE, bye)
        }
        if (wasPlaying) gameQueue.add(NetMessage(MSG_MATCH_ENDED, HOST_ID, true, ByteArray(3), 3))
        exec.schedule({
            advertiser?.stop(); advertiser = null
            lanServer?.stop(); lanServer = null
            invites.forEach { runCatching { it.link.close(reason) } }
            links.values.toList().forEach { runCatching { it.close(reason) } }
            (hostLink ?: hostLinkPending)?.close(reason)
            exec.shutdown()
        }, 250, TimeUnit.MILLISECONDS)
        if (current === this) current = null
        main.post { listeners.forEach { runCatching { it.onSessionClosed(this, reason) } } }
    }

    init {
        if (role == SessionRole.HOST) {
            synchronized(roster) {
                roster[HOST_ID] = PlayerState(HOST_ID, settingsStore.playerName, settingsStore.playerColor, settingsStore.playerToken,
                    ready = true, connected = true, rtt = 0, link = null, connection = "Host")
            }
            exec.execute { rebuildAndBroadcast() }
        }
        exec.scheduleWithFixedDelay({ tick() }, 1000, 1000, TimeUnit.MILLISECONDS)
    }

    companion object {
        private const val TAG = "OeaSession"
        const val HOST_ID = 0
        const val TO_ALL = 0xFF
        const val TIMEOUT_MS = 12_000L
        /** Game message types start here (lower values are reserved for the engine). */
        const val FIRST_GAME_TYPE = 32
        /** Delivered by [poll] when a player joins or rejoins during a match ([NetMessage.from] = player id). */
        const val MSG_PLAYER_JOINED = 20
        /** Delivered by [poll] when a player leaves or drops during a match. */
        const val MSG_PLAYER_LEFT = 21
        /** Delivered by [poll] when the match ended (host returned to the lobby or the session closed). */
        const val MSG_MATCH_ENDED = 22

        private const val MSG_HELLO = 1
        private const val MSG_WELCOME = 2
        private const val MSG_REJECT = 3
        private const val MSG_LOBBY = 4
        private const val MSG_READY = 5
        private const val MSG_START = 6
        private const val MSG_PING = 7
        private const val MSG_PONG = 8
        private const val MSG_BYE = 9
        private const val MSG_END = 10
        private const val MSG_KICK = 11
        private const val MSG_PLAYER_EVENT = 12

        /** The session the hub and the running game share (one at a time). */
        @Volatile var current: NetSession? = null
            private set

        private fun replaceCurrent(s: NetSession) {
            current?.takeIf { it !== s && it.phase != SessionPhase.CLOSED }?.close("Started another game")
            current = s
        }

        /** Creates a hosted session (no transports yet; call [startLan] and/or [createOnlineInvite]). */
        fun host(context: Context, descriptor: GameDescriptor, roomName: String): NetSession =
            NetSession(context.applicationContext, SessionRole.HOST, descriptor, roomName).also { replaceCurrent(it) }

        /** Joins a LAN host. Progress and errors arrive through [SessionListener]. */
        fun joinLan(context: Context, descriptor: GameDescriptor, address: String, port: Int, listener: SessionListener? = null): NetSession {
            val s = NetSession(context.applicationContext, SessionRole.CLIENT, descriptor, "")
            listener?.let { s.addListener(it) }
            replaceCurrent(s)
            s.status = "Connecting to $address…"
            s.exec.execute {
                try {
                    LanClientLink.connect(address, port, s)
                } catch (e: Exception) {
                    s.shutdown(e.message ?: "Couldn't connect", sendBye = false)
                }
            }
            return s
        }

        /** Tries several addresses (from a LAN code) until one connects. */
        fun joinLanAny(context: Context, descriptor: GameDescriptor, addresses: List<String>, port: Int, listener: SessionListener? = null): NetSession {
            val s = NetSession(context.applicationContext, SessionRole.CLIENT, descriptor, "")
            listener?.let { s.addListener(it) }
            replaceCurrent(s)
            s.status = "Connecting…"
            s.exec.execute {
                var last: Exception? = null
                for (a in addresses) {
                    try { LanClientLink.connect(a, port, s, timeoutMs = 3500); return@execute } catch (e: Exception) { last = e }
                }
                s.shutdown(last?.message ?: "Couldn't reach the host on this network.", sendBye = false)
            }
            return s
        }

        /**
         * Joins an online host from their invite code. [onReply] (main thread)
         * receives the reply code to send back, or an error message.
         */
        fun joinOnline(context: Context, descriptor: GameDescriptor, inviteText: String, onReply: (code: String?, error: String?) -> Unit): NetSession? {
            val p = try { SignalCode.decode(inviteText) } catch (e: SignalCodeException) { onReply(null, e.message); return null }
            val error = when {
                p.kind != SignalCode.Kind.INVITE -> "That's a reply code — it goes to the host. Ask the host for their invite code."
                p.gameId != descriptor.id -> "This invite is for a different game (${OeaEngine.registry.find(p.gameId)?.title ?: p.gameId})."
                p.protocol != OeaEngine.PROTOCOL || p.gameVersion != descriptor.version -> "The host uses a different OEA version. Update OEA on both phones."
                System.currentTimeMillis() / 1000L - p.createdAtSeconds > SignalCode.MAX_AGE_SECONDS -> "This invite is too old. Ask the host for a new one."
                !RtcRuntime.isAvailable(context) -> RtcRuntime.unavailableReason ?: "Online play isn't available on this device."
                else -> null
            }
            if (error != null) { onReply(null, error); return null }
            val s = NetSession(context.applicationContext, SessionRole.CLIENT, descriptor, "")
            replaceCurrent(s)
            s.status = "Preparing your reply…"
            val link = RtcLink(context.applicationContext, isOfferer = false, iceServers = OeaEngine.settings.iceServers, listener = s)
            link.onState = { state, detail ->
                if (state == RtcLink.State.FAILED) s.exec.execute { s.shutdown(detail, sendBye = false) }
                else if (state == RtcLink.State.CONNECTING && detail.isNotEmpty()) { s.status = detail; s.notifyUpdate() }
            }
            link.acceptOffer(p.sdp) { sdp ->
                if (sdp == null) {
                    onReply(null, link.stateDetail.ifBlank { "Couldn't create a reply for this invite." })
                    s.close("Couldn't create a reply")
                    return@acceptOffer
                }
                val code = SignalCode.encode(SignalCode.Payload(SignalCode.Kind.REPLY, descriptor.id, descriptor.version, OeaEngine.PROTOCOL, p.nonce,
                    System.currentTimeMillis() / 1000L, OeaEngine.settings.playerName, sdp))
                s.status = "Send your reply code to ${p.playerName.ifBlank { "the host" }}"
                s.notifyUpdate()
                onReply(code, null)
                s.exec.schedule({
                    if (s.phase == SessionPhase.CONNECTING) s.shutdown("The host didn't enter your reply code in time. Ask for a new invite.", sendBye = false)
                }, 15, TimeUnit.MINUTES)
            }
            s.hostLinkPending = link
            return s
        }
    }
}
