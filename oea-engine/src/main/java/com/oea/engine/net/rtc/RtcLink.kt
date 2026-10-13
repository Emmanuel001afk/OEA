package com.oea.engine.net.rtc

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.oea.engine.net.ByteReader
import com.oea.engine.net.ByteWriter
import com.oea.engine.net.Channel
import com.oea.engine.net.Link
import com.oea.engine.net.LinkListener
import com.oea.engine.net.TransportKind
import livekit.org.webrtc.CandidatePairChangeEvent
import livekit.org.webrtc.DataChannel
import livekit.org.webrtc.IceCandidate
import livekit.org.webrtc.MediaConstraints
import livekit.org.webrtc.MediaStream
import livekit.org.webrtc.PeerConnection
import livekit.org.webrtc.PeerConnectionFactory
import livekit.org.webrtc.SdpObserver
import livekit.org.webrtc.SessionDescription
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Lazily initialised WebRTC runtime (data channels only; no camera or microphone). */
object RtcRuntime {
    @Volatile private var factory: PeerConnectionFactory? = null
    @Volatile var unavailableReason: String? = null
        private set

    /** True when WebRTC's native library works on this device. */
    fun isAvailable(context: Context): Boolean = factory(context) != null

    internal fun factory(context: Context): PeerConnectionFactory? {
        factory?.let { return it }
        synchronized(this) {
            factory?.let { return it }
            if (unavailableReason != null) return null
            return try {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions(),
                )
                PeerConnectionFactory.builder().createPeerConnectionFactory().also { factory = it }
            } catch (t: Throwable) {
                Log.e("OeaRtc", "WebRTC unavailable", t)
                unavailableReason = when (t) {
                    is UnsatisfiedLinkError -> "Online play isn't available on this phone's processor type."
                    else -> "Online play couldn't start (${t.javaClass.simpleName})."
                }
                null
            }
        }
    }
}

/**
 * One WebRTC peer connection carrying two data channels: "r" (reliable,
 * ordered) and "u" (unordered, no retransmits). Signalling is manual: the
 * offerer's SDP goes into an invite code, the answerer's into a reply code.
 *
 * After the first exchange the reliable channel doubles as the signalling
 * path: new ICE candidates and ICE restarts (e.g. Wi-Fi → mobile data) are
 * negotiated over it, so a connection can survive network changes without
 * exchanging new codes.
 */
class RtcLink internal constructor(
    private val context: Context,
    val isOfferer: Boolean,
    private val iceServers: List<IceServerConfig>,
    private val listener: LinkListener,
) : Link(TransportKind.WEBRTC) {

    enum class State(val label: String) {
        NEW("Preparing"),
        GATHERING("Finding network routes"),
        WAITING("Waiting for the other code"),
        CONNECTING("Connecting"),
        CONNECTED("Connected"),
        RECONNECTING("Reconnecting"),
        FAILED("Failed"),
        CLOSED("Closed"),
    }

    @Volatile var state = State.NEW
        private set
    @Volatile var stateDetail = ""
        private set
    @Volatile var path = "Online"
        private set
    /** Main-thread callback for UI: state and a detail message. */
    var onState: ((State, String) -> Unit)? = null

    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "oea-rtc-$id").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private var pc: PeerConnection? = null
    private var reliable: DataChannel? = null
    private var unreliable: DataChannel? = null
    private val localCandidates = ArrayList<IceCandidate>()
    private var gatheringDone: (() -> Unit)? = null
    private var exchangeComplete = false
    private var closed = false
    private var connectTimer: java.util.concurrent.ScheduledFuture<*>? = null
    private var reconnectTimer: java.util.concurrent.ScheduledFuture<*>? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var remoteSdp = ""

    override val description: String get() = path

    // ---------------------------------------------------------------- setup

    private fun create(): Boolean {
        val factory = RtcRuntime.factory(context) ?: run {
            fail(RtcRuntime.unavailableReason ?: "Online play isn't available on this device.")
            return false
        }
        val servers = iceServers.map { s ->
            PeerConnection.IceServer.builder(s.url).apply {
                if (s.username.isNotEmpty()) setUsername(s.username)
                if (s.credential.isNotEmpty()) setPassword(s.credential)
            }.createIceServer()
        }
        val config = PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
            candidateNetworkPolicy = PeerConnection.CandidateNetworkPolicy.ALL
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
            keyType = PeerConnection.KeyType.ECDSA
            iceCandidatePoolSize = 0
        }
        val connection = factory.createPeerConnection(config, Observer()) ?: run {
            fail("Couldn't create the connection.")
            return false
        }
        pc = connection
        // Pre-negotiated channels: both sides create them with the same ids, no extra round trip.
        reliable = connection.createDataChannel("r", DataChannel.Init().apply { ordered = true; negotiated = true; id = 0 })
        unreliable = connection.createDataChannel("u", DataChannel.Init().apply { ordered = false; maxRetransmits = 0; negotiated = true; id = 1 })
        reliable?.registerObserver(ChannelObserver(true))
        unreliable?.registerObserver(ChannelObserver(false))
        watchNetwork()
        return true
    }

    /** Offerer: creates the invite SDP (with all gathered candidates). [onReady] runs on the main thread (null on failure). */
    fun createOffer(onReady: (String?) -> Unit) = onExec {
        if (!create()) { post { onReady(null) }; return@onExec }
        setState(State.GATHERING, "")
        val connection = pc ?: return@onExec
        connection.createOffer(object : SdpAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                onExec {
                    connection.setLocalDescription(object : SdpAdapter() {
                        override fun onSetSuccess() = onExec { awaitGathering { post { onReady(localSdp()) }; setState(State.WAITING, "") } }.let { }
                        override fun onSetFailure(error: String) { fail("Couldn't prepare the invite ($error)."); post { onReady(null) } }
                    }, sdp)
                }
            }
            override fun onCreateFailure(error: String) { fail("Couldn't prepare the invite ($error)."); post { onReady(null) } }
        }, MediaConstraints())
    }

    /** Answerer: applies the invite SDP and produces the reply SDP. */
    fun acceptOffer(offerSdp: String, onReady: (String?) -> Unit) = onExec {
        if (!create()) { post { onReady(null) }; return@onExec }
        remoteSdp = offerSdp
        setState(State.GATHERING, "")
        val connection = pc ?: return@onExec
        connection.setRemoteDescription(object : SdpAdapter() {
            override fun onSetSuccess() {
                onExec {
                    connection.createAnswer(object : SdpAdapter() {
                        override fun onCreateSuccess(sdp: SessionDescription) {
                            onExec {
                                connection.setLocalDescription(object : SdpAdapter() {
                                    override fun onSetSuccess() = onExec {
                                        awaitGathering {
                                            exchangeComplete = true
                                            post { onReady(localSdp()) }
                                            setState(State.CONNECTING, "Waiting for the host to enter your reply code")
                                        }
                                    }.let { }
                                    override fun onSetFailure(error: String) { fail("Couldn't create the reply ($error)."); post { onReady(null) } }
                                }, sdp)
                            }
                        }
                        override fun onCreateFailure(error: String) { fail("Couldn't create the reply ($error)."); post { onReady(null) } }
                    }, MediaConstraints())
                }
            }
            override fun onSetFailure(error: String) { fail("This invite can't be used ($error). Ask for a new one."); post { onReady(null) } }
        }, SessionDescription(SessionDescription.Type.OFFER, offerSdp))
    }

    /** Offerer: applies the reply SDP; connection follows. [onResult] gets null on success or an error message. */
    fun acceptAnswer(answerSdp: String, onResult: (String?) -> Unit) = onExec {
        val connection = pc ?: run { post { onResult("This invite is no longer active. Make a new one.") }; return@onExec }
        if (exchangeComplete) { post { onResult("This invite was already used. Make a new invite for another player.") }; return@onExec }
        remoteSdp = answerSdp
        connection.setRemoteDescription(object : SdpAdapter() {
            override fun onSetSuccess() {
                onExec {
                    exchangeComplete = true
                    setState(State.CONNECTING, "")
                    connectTimer = later(CONNECT_TIMEOUT_S * 1000L) {
                        if (state != State.CONNECTED && !closed) fail(diagnoseFailure())
                    }
                    post { onResult(null) }
                }
            }
            override fun onSetFailure(error: String) { post { onResult("That reply code doesn't match this invite ($error).") } }
        }, SessionDescription(SessionDescription.Type.ANSWER, answerSdp))
    }

    private fun awaitGathering(done: () -> Unit) {
        val connection = pc ?: return
        if (connection.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) { done(); return }
        var fired = false
        val once = { if (!fired) { fired = true; gatheringDone = null; done() } }
        gatheringDone = once
        // Without STUN/TURN only local candidates exist and gathering is quick; servers can take longer.
        later(if (iceServers.isEmpty()) 3500L else 8000L) { once() }
    }

    /** Local SDP, making sure every gathered candidate is included. */
    private fun localSdp(): String? {
        val sdp = pc?.localDescription?.description ?: return null
        if (sdp.contains("a=candidate:") || localCandidates.isEmpty()) return sdp
        val extra = localCandidates.joinToString("") { "a=${it.sdp}\r\n" }
        return if (sdp.endsWith("\r\n")) sdp + extra else sdp + "\r\n" + extra
    }

    // ---------------------------------------------------------------- data

    override fun send(channel: Channel, data: ByteArray, offset: Int, length: Int): Boolean {
        val dc = (if (channel == Channel.RELIABLE) reliable else unreliable) ?: return false
        if (dc.state() != DataChannel.State.OPEN) return false
        if (channel == Channel.UNRELIABLE && dc.bufferedAmount() > 256 * 1024) return false
        val buf = ByteBuffer.allocateDirect(length + 1)
        buf.put(KIND_DATA.toByte())
        buf.put(data, offset, length)
        buf.flip()
        return runCatching { dc.send(DataChannel.Buffer(buf, true)) }.getOrDefault(false)
    }

    private fun sendControl(w: ByteWriter) {
        val dc = reliable ?: return
        if (dc.state() != DataChannel.State.OPEN) return
        val bytes = w.toByteArray()
        val buf = ByteBuffer.allocateDirect(bytes.size + 1)
        buf.put(KIND_CONTROL.toByte()); buf.put(bytes); buf.flip()
        runCatching { dc.send(DataChannel.Buffer(buf, true)) }
    }

    private inner class ChannelObserver(private val isReliable: Boolean) : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) = Unit
        override fun onStateChange() {
            onExec {
                val r = reliable ?: return@onExec
                if (isReliable && r.state() == DataChannel.State.OPEN && !isUp) {
                    isUp = true
                    connectTimer?.cancel(false)
                    setState(State.CONNECTED, "")
                    detectPath()
                    listener.onLinkUp(this@RtcLink)
                } else if (isReliable && (r.state() == DataChannel.State.CLOSED) && isUp) {
                    close("The connection was closed")
                }
            }
        }

        override fun onMessage(buffer: DataChannel.Buffer) {
            val data = buffer.data
            if (data.remaining() < 1) return
            val bytes = ByteArray(data.remaining())
            data.get(bytes)
            lastHeardMs = System.currentTimeMillis()
            when (bytes[0].toInt()) {
                KIND_DATA -> listener.onLinkMessage(this@RtcLink, if (isReliable) Channel.RELIABLE else Channel.UNRELIABLE, bytes.copyOfRange(1, bytes.size))
                KIND_CONTROL -> onExec { onControl(bytes) }
            }
        }
    }

    // ---------------------------------------------------------------- in-band signalling (ICE restarts)

    private fun onControl(bytes: ByteArray) {
        val connection = pc ?: return
        runCatching {
            val r = ByteReader(bytes, 1)
            when (r.u8()) {
                CTRL_CANDIDATE -> connection.addIceCandidate(IceCandidate(r.string(), r.u8(), r.string(2048)))
                CTRL_OFFER -> {
                    val sdp = r.string(16384)
                    connection.setRemoteDescription(object : SdpAdapter() {
                        override fun onSetSuccess() {
                            onExec {
                                connection.createAnswer(object : SdpAdapter() {
                                    override fun onCreateSuccess(sdp: SessionDescription) {
                                        onExec {
                                            connection.setLocalDescription(object : SdpAdapter() {
                                                override fun onSetSuccess() { sendControl(ByteWriter().u8(CTRL_ANSWER).string(sdp.description)) }
                                            }, sdp)
                                        }
                                    }
                                }, MediaConstraints())
                            }
                        }
                    }, SessionDescription(SessionDescription.Type.OFFER, sdp))
                }
                CTRL_ANSWER -> connection.setRemoteDescription(SdpAdapter(), SessionDescription(SessionDescription.Type.ANSWER, r.string(16384)))
            }
        }.onFailure { Log.w(TAG, "Bad control message", it) }
    }

    /** Offerer: renegotiate ICE over the open channel, e.g. after a network switch. */
    private fun restartIce() = onExec {
        val connection = pc ?: return@onExec
        if (!isOfferer || !exchangeComplete || closed) return@onExec
        if (reliable?.state() != DataChannel.State.OPEN) return@onExec
        if (connection.signalingState() != PeerConnection.SignalingState.STABLE) return@onExec
        val constraints = MediaConstraints().apply { mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true")) }
        connection.createOffer(object : SdpAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                onExec {
                    connection.setLocalDescription(object : SdpAdapter() {
                        override fun onSetSuccess() { sendControl(ByteWriter().u8(CTRL_OFFER).string(sdp.description)) }
                    }, sdp)
                }
            }
        }, constraints)
    }

    private fun watchNetwork() {
        if (!isOfferer) return
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            private var first = true
            override fun onAvailable(network: Network) {
                if (first) { first = false; return }
                if (isUp) restartIce()
            }
            override fun onLost(network: Network) { if (isUp) later(800L) { restartIce() } }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb); networkCallback = cb }
    }

    // ---------------------------------------------------------------- state

    private inner class Observer : PeerConnection.Observer {
        override fun onSignalingChange(newState: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {
            if (newState == PeerConnection.IceGatheringState.COMPLETE) onExec { gatheringDone?.invoke() }
        }
        override fun onIceCandidate(candidate: IceCandidate) {
            onExec {
                localCandidates += candidate
                // After the code exchange, new candidates travel over the data channel.
                if (exchangeComplete && isUp) {
                    sendControl(ByteWriter().u8(CTRL_CANDIDATE).string(candidate.sdpMid ?: "0").u8(candidate.sdpMLineIndex).string(candidate.sdp))
                }
            }
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(dataChannel: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent) { onExec { detectPath() } }
        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            onExec {
                when (newState) {
                    PeerConnection.PeerConnectionState.CONNECTED -> {
                        reconnectTimer?.cancel(false)
                        if (isUp) setState(State.CONNECTED, "")
                    }
                    PeerConnection.PeerConnectionState.DISCONNECTED -> if (isUp) {
                        setState(State.RECONNECTING, "Trying to restore the connection…")
                        listener.onLinkStatus(this@RtcLink, "Reconnecting…")
                        restartIce()
                        reconnectTimer?.cancel(false)
                        reconnectTimer = later(RECONNECT_TIMEOUT_S * 1000L) {
                            if (state == State.RECONNECTING) close("Lost the connection — the network between the phones stopped working")
                        }
                    }
                    PeerConnection.PeerConnectionState.FAILED -> {
                        if (isUp) close("Lost the connection — the network between the phones stopped working")
                        else fail(diagnoseFailure())
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun detectPath() {
        val connection = pc ?: return
        connection.getStats { report ->
            runCatching {
                val stats = report.statsMap
                val transport = stats.values.firstOrNull { it.type == "transport" }
                val pairId = transport?.members?.get("selectedCandidatePairId") as? String
                val pair = pairId?.let { stats[it] }
                val local = (pair?.members?.get("localCandidateId") as? String)?.let { stats[it] }
                val remote = (pair?.members?.get("remoteCandidateId") as? String)?.let { stats[it] }
                val lt = local?.members?.get("candidateType") as? String
                val rt = remote?.members?.get("candidateType") as? String
                path = when {
                    lt == "relay" || rt == "relay" -> "Online • relayed via your TURN server"
                    lt == "host" && rt == "host" -> "Online • direct (same network)"
                    lt != null -> "Online • direct"
                    else -> "Online"
                }
            }
        }
    }

    /** Best guess at why ICE failed, in plain words. */
    private fun diagnoseFailure(): String {
        val hasRelay = iceServers.any { it.isTurn }
        val local = localCandidates.map { it.sdp }
        val remote = remoteSdp.lines().filter { it.startsWith("a=candidate:") }
        val localSrflx = local.any { it.contains(" typ srflx") }
        val remoteSrflx = remote.any { it.contains(" typ srflx") }
        val v6Local = local.any { it.contains(":") && it.split(' ').getOrNull(4)?.contains(':') == true }
        val v6Remote = remote.any { it.split(' ').getOrNull(4)?.contains(':') == true }
        return when {
            remote.isEmpty() -> "The other phone sent no network routes. Check that it's online and make new codes."
            hasRelay -> "Couldn't connect, even through your TURN server. Check the server address and login in Games settings."
            !localSrflx && !remoteSrflx && !(v6Local && v6Remote) ->
                "The phones couldn't reach each other directly. On different networks this needs IPv6 on both sides or your own STUN/TURN server " +
                    "(Games settings → Connection servers). Playing nearby? Use Wi-Fi / hotspot mode."
            else -> "The networks block a direct connection (strict NAT or firewall). Add your own TURN server in Games settings, or play on the same Wi-Fi / hotspot."
        }
    }

    private fun setState(s: State, detail: String) {
        state = s
        stateDetail = detail
        val cb = onState ?: return
        post { cb(s, detail) }
    }

    private fun fail(message: String) {
        if (closed) return
        setState(State.FAILED, message)
        closeInternal(message, notify = isUp)
    }

    override fun close(reason: String) = onExec { closeInternal(reason, notify = true) }

    private fun closeInternal(reason: String, notify: Boolean) {
        if (closed) return
        closed = true
        val wasUp = isUp
        isUp = false
        connectTimer?.cancel(false)
        reconnectTimer?.cancel(false)
        networkCallback?.let { cb -> runCatching { context.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } }
        networkCallback = null
        runCatching { reliable?.unregisterObserver(); reliable?.close(); reliable?.dispose() }
        runCatching { unreliable?.unregisterObserver(); unreliable?.close(); unreliable?.dispose() }
        reliable = null; unreliable = null
        runCatching { pc?.close(); pc?.dispose() }
        pc = null
        if (state != State.FAILED) setState(State.CLOSED, reason)
        if (notify || wasUp) listener.onLinkDown(this, reason)
        exec.shutdown()
    }

    private fun post(block: () -> Unit) { main.post(block) }

    /** Runs on the link's thread; silently ignored once the link is closed (late WebRTC callbacks). */
    private fun onExec(block: () -> Unit) {
        if (exec.isShutdown) return
        try { exec.execute(block) } catch (_: java.util.concurrent.RejectedExecutionException) { }
    }

    private fun later(delayMs: Long, block: () -> Unit): java.util.concurrent.ScheduledFuture<*>? {
        if (exec.isShutdown) return null
        return try { exec.schedule(block, delayMs, TimeUnit.MILLISECONDS) } catch (_: java.util.concurrent.RejectedExecutionException) { null }
    }

    /** Empty SdpObserver base. */
    private open class SdpAdapter : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String) { Log.w(TAG, "SDP create failed: $error") }
        override fun onSetFailure(error: String) { Log.w(TAG, "SDP set failed: $error") }
    }

    private companion object {
        const val TAG = "OeaRtcLink"
        const val KIND_DATA = 0
        const val KIND_CONTROL = 1
        const val CTRL_CANDIDATE = 1
        const val CTRL_OFFER = 2
        const val CTRL_ANSWER = 3
        const val CONNECT_TIMEOUT_S = 30L
        const val RECONNECT_TIMEOUT_S = 25L
    }
}
