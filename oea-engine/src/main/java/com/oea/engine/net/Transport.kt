package com.oea.engine.net

import java.util.concurrent.atomic.AtomicInteger

/** Delivery guarantee for a message. */
enum class Channel {
    /** Ordered and guaranteed (chess moves, lobby, kills, results). */
    RELIABLE,
    /** Fire-and-forget, newest wins (positions, inputs, snapshots). */
    UNRELIABLE,
}

/** Which of the two supported multiplayer methods a connection uses. */
enum class TransportKind(val label: String) {
    LAN("Wi-Fi / hotspot"),
    WEBRTC("Online"),
}

/** Callbacks from a transport. They run on transport threads; [com.oea.engine.net.session.NetSession] serialises them. */
interface LinkListener {
    fun onLinkUp(link: Link)
    fun onLinkMessage(link: Link, channel: Channel, data: ByteArray)
    fun onLinkDown(link: Link, reason: String)
    /** Optional: connection quality changed (e.g. WebRTC reconnecting). */
    fun onLinkStatus(link: Link, status: String) {}
}

/** One connection to one remote device. Thread-safe. */
abstract class Link(val kind: TransportKind) {
    val id = ids.incrementAndGet()
    @Volatile var isUp = false
        protected set
    /** Session bookkeeping (player id). */
    @Volatile var playerId = -1
    /** Wall time of the last received message (ms). */
    @Volatile var lastHeardMs = System.currentTimeMillis()
        internal set

    abstract fun send(channel: Channel, data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Boolean
    abstract fun close(reason: String)
    /** Short human description of the path, e.g. "Wi-Fi • 192.168.1.20" or "Online • direct". */
    abstract val description: String

    companion object { private val ids = AtomicInteger() }
}

/** User-facing connection failure with a clear explanation and an optional hint. */
class NetError(val title: String, val detail: String = "", val retryable: Boolean = true) {
    override fun toString() = if (detail.isEmpty()) title else "$title. $detail"
}
